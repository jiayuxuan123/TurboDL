package dev.turbodl.core

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 多来源（镜像）选择池。
 *
 * 参考 aria2 的 `AdaptiveURISelector`：先让每个来源跑一小段，按实测速度择优，
 * 失败时自动切到别的来源。**不是**简单轮询 —— 轮询会让一条慢 3 倍的镜像
 * 分走 1/n 的流量，总耗时直接被最慢的那条拖住。
 *
 * ## 选择规则
 *
 * 1. 还没测过速度的来源（[Mirror.ewmaBytesPerSec] == 0）优先各来一次，
 *    保证"冷启动时每个镜像都会被实测到"，而不是第一个碰巧快的吃掉全部。
 * 2. 测过之后按 EWMA 速度加权：快的多派，慢的少派。
 * 3. 失败的来源进入指数退避禁用窗口（0.5s → 1s → 2s … 封顶 30s）。
 * 4. 全部被禁用时挑"最早解禁"的那个继续试 —— 宁可慢，也不能卡死。
 *
 * ## 线程安全
 *
 * 所有状态都是 volatile / atomic：分片派发是并发的，这里不做全局锁。
 */
internal class MirrorPool(
    urls: List<String>,
    /**
     * 每个来源允许同时进行的分片数。默认 0 = 不额外限制（由任务并发数决定）。
     * 设置后可以避免所有 worker 同时打同一个镜像。
     */
    private val perMirrorInFlightCap: Int = 0,
) {

    internal class Mirror(val url: String) {
        @Volatile var ewmaBytesPerSec: Double = 0.0
        @Volatile var samples: Int = 0
        @Volatile var consecutiveFailures: Int = 0
        @Volatile var disabledUntilMs: Long = 0L
        /** Set only when the source is proven to be a different object/file. */
        @Volatile var permanentlyFailed: Boolean = false
        @Volatile var totalBytes: Long = 0L
        val inFlight = AtomicInteger(0)
        val picked = AtomicLong(0)

        fun available(nowMs: Long): Boolean =
            !permanentlyFailed && disabledUntilMs <= nowMs
    }

    private val mirrors: List<Mirror> = urls
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .map { Mirror(it) }
        .toList()
    private val pickLock = Any()

    val size: Int get() = mirrors.size

    init {
        require(mirrors.isNotEmpty()) { "MirrorPool 至少需要一个来源" }
    }

    /** 供日志 / 诊断使用：全部来源的实时速度画像。 */
    fun snapshot(): List<String> = mirrors.map {
        "${it.url} → ${"%.2f".format(it.ewmaBytesPerSec / 1_048_576)}MB/s " +
            "samples=${it.samples} fail=${it.consecutiveFailures} " +
            "inFlight=${it.inFlight.get()} quarantined=${it.permanentlyFailed}"
    }

    /**
     * 选一个来源。返回的一定是合法 URL；调用方拿到后直接发起分片请求。
     */
    fun pick(): String {
        val now = System.currentTimeMillis()
        var best: Mirror? = null
        var bestScore = -1.0
        var soonest: Mirror = mirrors[0]

        for (m in mirrors) {
            if (m.disabledUntilMs in (now + 1)..Long.MAX_VALUE) {
                if (m.disabledUntilMs < soonest.disabledUntilMs || soonest.disabledUntilMs <= now) {
                    soonest = m
                }
            }
            if (!m.available(now)) continue
            if (perMirrorInFlightCap > 0 && m.inFlight.get() >= perMirrorInFlightCap) continue

            // 冷启动：还没测过的来源给最高优先级，保证每个都会被实测到。
            if (m.samples == 0) {
                best = m
                bestScore = Double.MAX_VALUE
                break
            }
            // 速度加权 + 轻微随机，避免所有 worker 在同一毫秒选中同一条。
            val jitter = 0.92 + ThreadLocalRandom.current().nextDouble(0.16)
            val score = m.ewmaBytesPerSec * jitter
            if (score > bestScore) {
                best = m
                bestScore = score
            }
        }

        val chosen = best ?: soonest
        chosen.inFlight.incrementAndGet()
        chosen.picked.incrementAndGet()
        return chosen.url
    }

    /**
     * Strict variant used by the scheduler when a per-source cap is configured.
     * Returns null while every otherwise eligible source is at its in-flight
     * limit; the caller waits briefly and retries instead of exceeding the cap.
     */
    fun pickWithLimit(): String? = synchronized(pickLock) {
        val now = System.currentTimeMillis()
        val usable = mirrors.filter { !it.permanentlyFailed }
        if (usable.isEmpty()) return null
        val available = usable.filter { it.available(now) }
        if (available.isEmpty()) {
            // Keep the old "never deadlock on a pool whose sources are all in
            // backoff" behavior: pick the source that becomes available first.
            val soonest = usable.minByOrNull { it.disabledUntilMs } ?: return null
            soonest.inFlight.incrementAndGet()
            soonest.picked.incrementAndGet()
            return soonest.url
        }
        val candidates = available.filter {
            perMirrorInFlightCap <= 0 || it.inFlight.get() < perMirrorInFlightCap
        }
        if (candidates.isEmpty()) return null

        var best: Mirror? = null
        var bestScore = -1.0
        for (m in candidates) {
            if (m.samples == 0) {
                best = m
                break
            }
            val jitter = 0.92 + ThreadLocalRandom.current().nextDouble(0.16)
            val score = m.ewmaBytesPerSec * jitter
            if (score > bestScore) {
                best = m
                bestScore = score
            }
        }
        val chosen = best ?: candidates.first()
        chosen.inFlight.incrementAndGet()
        chosen.picked.incrementAndGet()
        chosen.url
    }

    /**
     * 归还一次派发的结果。
     *
     * @param bytes 这次分片实际写入的字节数（成功时用来更新速度画像）
     * @param elapsedMs 这次分片耗时（毫秒）；<=0 时不更新速度
     */
    fun report(
        url: String,
        ok: Boolean,
        bytes: Long,
        elapsedMs: Long,
        structuralFailure: Boolean = false,
    ) {
        val m = mirrors.firstOrNull { it.url == url } ?: return
        m.inFlight.updateAndGet { if (it > 0) it - 1 else 0 }
        if (structuralFailure) {
            // A size/identity mismatch is not a transient network error. If
            // left in the ordinary backoff path it is selected again as a
            // never-sampled source and can starve every healthy mirror.
            m.samples = m.samples.coerceAtLeast(1)
            m.consecutiveFailures++
            m.permanentlyFailed = true
            m.disabledUntilMs = Long.MAX_VALUE
            return
        }
        if (ok) {
            m.permanentlyFailed = false
            m.consecutiveFailures = 0
            m.disabledUntilMs = 0L
            m.totalBytes += bytes.coerceAtLeast(0)
            if (bytes > 0 && elapsedMs > 0) {
                val instant = bytes * 1000.0 / elapsedMs
                m.ewmaBytesPerSec = if (m.samples == 0) instant else m.ewmaBytesPerSec * 0.7 + instant * 0.3
                m.samples++
            }
        } else {
            val n = m.consecutiveFailures + 1
            m.consecutiveFailures = n
            // 0.5s → 1s → 2s → 4s … 封顶 30s
            val backoff = (500L shl (n - 1).coerceIn(0, 6)).coerceAtMost(30_000L)
            m.disabledUntilMs = System.currentTimeMillis() + backoff
        }
    }

    /** 是否还有来源可用（用于决定"整体失败"还是"再用别的镜像试一次"）。 */
    fun hasAvailable(): Boolean {
        val now = System.currentTimeMillis()
        return mirrors.any { !it.permanentlyFailed && it.available(now) }
    }

    /** True while at least one source has not been proven to be a different file. */
    fun hasUsableSources(): Boolean = mirrors.any { !it.permanentlyFailed }

    /**
     * 只归还 in-flight 计数（异常路径用）。
     *
     * 分片请求可能抛异常直接跳过 [report]；不归还的话这个来源的 in-flight
     * 会永远虚高，perMirrorInFlightCap 一旦生效就再也不会被选中。
     */
    fun release(url: String) {
        val m = mirrors.firstOrNull { it.url == url } ?: return
        m.inFlight.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    /** 所有来源的 URL（探测阶段用）。 */
    fun allUrls(): List<String> = mirrors.map { it.url }
}

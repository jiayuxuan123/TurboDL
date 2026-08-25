package dev.turbodl.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * 多线程分段下载调度器（引擎核心）。
 *
 * 综合各开源下载器思想（仅思想，无源码复制）：
 *  - **aria2**：`--split` 预切 N 段 + `--min-split-size` 控制粒度；固定连接数满并发。
 *  - **IDM / XDM 的动态分段效果**：本实现用「细粒度预分块 + 工作窃取」达成等效的消除长尾效果——
 *    块数远多于连接数，慢连接拖住某块时其余连接持续领新块，天然负载均衡；
 *    相比运行时就地劈分「在飞段」，预分块无区间重叠风险、断点续传更简单可靠。
 *    [TurboConfig.dynamicSegmentation]=true 时用更小的块（blockSize/4，下限 minSegmentSize）以贴近 IDM 的细粒度再平衡。
 *  - **axel / Persepolis**：顺序分片优先（靠前的先下，便于边下边预览/播放）。
 *  - **Motrix**：全局限速、队列并发。
 *
 * 关键策略（对应用户要求）：
 *  1. 服务器不支持/忽略 Range → 返回 [Outcome.NeedWholeFallback]，上层整文件回退。
 *  2. 坏块/超时 → 仅重试该分片（回投优先级队列），不作废整任务。
 *  3. 分片优先级 → 所有块按 start 有序入优先队列，靠前分片先被领取。
 *  4. 背压（非 AIMD）→ 仅在 429/503 或连续失败达阈值时乘性下调并发；普通波动不减线程。
 *  5. Range 篡改校验 → 由 [SegmentDownloader] 逐段校验实际字节匹配请求区间。
 */
internal class SegmentScheduler(
    private val downloader: SegmentDownloader,
    private val config: TurboConfig,
    private val speedLimiter: SpeedLimiter,
) {
    sealed interface Outcome {
        object Completed : Outcome
        object NeedWholeFallback : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** 固定区间分片（预分块，区间不可变——避免运行时缩短导致的重叠/膨胀）。 */
    private class Segment(
        val start: Long,
        val end: Long,
        var attempts: Int = 0,
    ) {
        val length get() = end - start + 1
        fun file(dir: File) = File(dir, "seg_${start}_${end}.part")
    }

    suspend fun run(
        taskId: Long,
        url: String,
        total: Long,
        chunkDir: File,
        headers: Map<String, String>,
        connections: Int,
        resumeFrom: Long,
        onBytes: suspend (delta: Long, absolute: Long) -> Unit,
        onConnections: (Int) -> Unit,
        isActive: () -> Boolean,
    ): Outcome = coroutineScope {
        chunkDir.mkdirs()
        val workers = connections.coerceIn(1, 256)

        // 有效块大小：动态分段用更细粒度（blockSize/4，下限 minSegmentSize），否则用 blockSize。
        val effBlock = if (config.dynamicSegmentation)
            max(config.minSegmentSize, config.blockSize / 4)
        else config.blockSize

        // ---------- 预分块：整文件切成 [effBlock] 大小的连续区间 ----------
        val allSegments = ArrayList<Segment>()
        run {
            var s = 0L
            while (s < total) {
                val e = min(s + effBlock - 1, total - 1)
                allSegments.add(Segment(s, e))
                s = e + 1
            }
        }

        // ---------- 断点续传：已完整的块跳过，累加已下载量 ----------
        val downloaded = AtomicLong(0)
        val pending = java.util.PriorityQueue<Segment>(compareBy { it.start })  // 分片优先级：靠前优先
        val pendingLock = Any()
        for (seg in allSegments) {
            val f = seg.file(chunkDir)
            if (f.exists() && f.length() >= seg.length) {
                downloaded.addAndGet(seg.length)
            } else {
                synchronized(pendingLock) { pending.offer(seg) }
                if (f.exists()) downloaded.addAndGet(f.length())  // 部分完成的续传起点
            }
        }
        downloaded.set(min(downloaded.get(), total))

        val needWholeFallback = AtomicBoolean(false)
        val failReason = AtomicReference<String?>(null)
        val consecutiveFailures = AtomicInteger(0)

        val desired = AtomicInteger(workers)
        val sem = Semaphore(workers)
        val parked = java.util.concurrent.ConcurrentLinkedDeque<Unit>()

        onConnections(workers)

        fun poll(): Segment? = synchronized(pendingLock) { pending.poll() }
        fun offer(seg: Segment) = synchronized(pendingLock) { pending.offer(seg) }

        suspend fun throttleDown() {
            if (config.backpressureConsecutiveFailures <= 0) return
            val cur = desired.get()
            val target = max(1, cur / 2)
            var diff = cur - target
            while (diff > 0) { sem.acquire(); parked.add(Unit); diff-- }
            desired.set(target)
            onConnections(target)
        }

        val ok = try {
            val jobs = List(workers) { idx ->
                async(Dispatchers.IO) {
                    if (idx in 1..8) delay(idx * 20L)  // 错峰建连
                    while (isActive() && !needWholeFallback.get()) {
                        val seg = poll() ?: break
                        sem.withPermit {
                            if (needWholeFallback.get() || !isActive()) return@withPermit
                            val res = downloader.downloadSegment(
                                taskId, url, seg.start, seg.end, seg.file(chunkDir), headers
                            ) { bytes ->
                                speedLimiter.awaitAllow(bytes)
                                val abs = min(downloaded.addAndGet(bytes), total)
                                if (!isActive()) return@downloadSegment
                                onBytes(bytes, abs)
                            }
                            when (res) {
                                SegmentResult.OK -> consecutiveFailures.set(0)
                                SegmentResult.RANGE_IGNORED ->
                                    needWholeFallback.compareAndSet(false, true)
                                SegmentResult.THROTTLED -> {
                                    val n = consecutiveFailures.incrementAndGet()
                                    offer(Segment(seg.start, seg.end, seg.attempts))
                                    if (config.backpressureConsecutiveFailures in 1..n) {
                                        throttleDown(); consecutiveFailures.set(0)
                                    }
                                }
                                SegmentResult.FAILED -> {
                                    seg.attempts++
                                    val n = consecutiveFailures.incrementAndGet()
                                    if (seg.attempts > config.maxRetries) {
                                        failReason.compareAndSet(
                                            null,
                                            "分片 ${seg.start}-${seg.end} 重试 ${seg.attempts} 次仍失败"
                                        )
                                    } else {
                                        offer(seg)  // 仅重试该分片
                                    }
                                    if (config.backpressureConsecutiveFailures in 1..n) {
                                        throttleDown(); consecutiveFailures.set(0)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            jobs.awaitAll()
            true
        } catch (e: CancellationException) {
            throw e
        }

        if (needWholeFallback.get()) return@coroutineScope Outcome.NeedWholeFallback
        if (!isActive()) return@coroutineScope Outcome.Failed("任务已暂停")
        failReason.get()?.let { return@coroutineScope Outcome.Failed(it) }

        val (covered, missing) = verifyCoverage(chunkDir, total)
        if (!covered) return@coroutineScope Outcome.Failed("缺失区间 ${missing.size} 段")
        Outcome.Completed
    }

    /** 校验 [0,total) 被完整块连续覆盖。 */
    private fun verifyCoverage(dir: File, total: Long): Pair<Boolean, List<LongRange>> {
        val blocks = dir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }
            ?.mapNotNull { f ->
                val name = f.name.removePrefix("seg_").removeSuffix(".part")
                val s = name.substringBefore('_').toLongOrNull() ?: return@mapNotNull null
                val e = name.substringAfter('_').toLongOrNull() ?: return@mapNotNull null
                if (f.length() >= (e - s + 1)) s..e else null
            }?.sortedBy { it.first } ?: emptyList()
        val missing = mutableListOf<LongRange>()
        var pos = 0L
        for (r in blocks) {
            if (r.first > pos) missing.add(pos until r.first)
            if (r.last + 1 > pos) pos = r.last + 1
        }
        if (pos < total) missing.add(pos until total)
        return (missing.isEmpty() && pos >= total) to missing
    }

    /** 合并所有分片（按 start 排序）。 */
    fun finalParts(chunkDir: File): List<File> =
        chunkDir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }
            ?.sortedBy { it.name.removePrefix("seg_").substringBefore('_').toLongOrNull() ?: 0L }
            ?: emptyList()
}

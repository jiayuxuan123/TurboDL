package dev.turbodl.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 单个连接数档位的测量结果。 */
data class ConnectionTierResult(
    val connections: Int,
    /** 本窗口内实际收到的字节数。 */
    val bytes: Long,
    val elapsedMs: Long,
    /** 该档位观察到的峰值并发连接数（用来判断"设定的连接数是否真的跑满"）。 */
    val peakConnections: Int,
    val error: String? = null,
) {
    val mbPerSec: Double
        get() = if (elapsedMs <= 0) 0.0 else bytes / 1048576.0 / (elapsedMs / 1000.0)

    override fun toString(): String =
        "连接=%-4d 吞吐=%6.2f MB/s 峰值并发=%-4d 采样=%d 字节%s".format(
            connections, mbPerSec, peakConnections, bytes,
            error?.let { " 错误=$it" } ?: ""
        )
}

/**
 * 现场诊断工具（**不进下载主链路**，不改变任何下载行为；只在显式调用时运行）。
 *
 * ## 它回答的问题
 *
 * 「多开连接到底有没有用？」——这**完全取决于服务端怎么限速**，而引擎事先不知道对方是哪种：
 *
 * | 服务端模型 | 加连接的后果 |
 * |---|---|
 * | 每连接限速 | 吞吐 **∝ 连接数**，多多益善 |
 * | 按 IP/账户聚合限速 | 吞吐**封顶**，加连接只增加请求开销 |
 * | 对并发有惩罚（429/503） | 加连接**触发拒绝 → 重试风暴** |
 *
 * ## 做法
 *
 * 只改连接数、其他配置完全相同，**每档只跑固定时长窗口后立即取消**（不下完整个文件），
 * 对比吞吐曲线的形状：
 *
 * | 曲线 | 判读 | 该往哪改 |
 * |---|---|---|
 * | 线性上升 | 每连接限速 | 加连接，直到收益饱和 |
 * | 基本持平 | 按 IP 聚合限速 | 加连接无用 → 降到刚好够用，省请求 |
 * | 先升后降 | 对并发有惩罚 | 必须自适应降到拐点（aria2 限 16 的理由） |
 *
 * 为什么必须在**真实链路**上跑：回环没有 RTT、没有真实 CDN 的限流与调度策略，
 * 测出来的曲线与真实网络可能完全不同。
 */
object TurboDiagnostics {

    /**
     * 扫描一组连接数，返回每档的吞吐。
     *
     * @param tiers 连接数档位；默认 `8/16/64/128`（含 rc14 的默认 16 与用户常用的 128）。
     * @param windowMs 每档测量窗口；到点即取消，**不会**下完整个文件。
     * @param onTier 每档结束后的回调（可用来实时刷新界面/日志）。
     */
    suspend fun sweepConnections(
        url: String,
        headers: Map<String, String> = emptyMap(),
        knownSize: Long = -1L,
        tiers: List<Int> = listOf(8, 16, 64, 128),
        windowMs: Long = 15_000,
        gapMs: Long = 1_000,
        workDir: File? = null,
        onTier: (suspend (ConnectionTierResult) -> Unit)? = null,
    ): List<ConnectionTierResult> = coroutineScope {
        val out = mutableListOf<ConnectionTierResult>()
        for (n in tiers) {
            val r = runCatching { measureTier(url, headers, knownSize, n, windowMs, workDir) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    ConnectionTierResult(n, 0, 0, 0, e.message ?: e.toString())
                }
            out += r
            onTier?.invoke(r)
            if (gapMs > 0) delay(gapMs)
        }
        out
    }

    /** 根据曲线形状给出判读（宿主可直接展示给用户）。 */
    fun interpret(results: List<ConnectionTierResult>): String {
        val ok = results.filter { it.error == null && it.bytes > 0 }
        if (ok.size < 2) return "样本不足，无法判读（检查链接/请求头/网络可达性）"
        val first = ok.first()
        val best = ok.maxByOrNull { it.mbPerSec }!!
        val gain = if (first.mbPerSec > 0) best.mbPerSec / first.mbPerSec else 0.0
        return buildString {
            append("最低档 ${first.connections} 连接 = %.2f MB/s；最优 ${best.connections} 连接 = %.2f MB/s".format(first.mbPerSec, best.mbPerSec))
            append("（增益 %.2f 倍）\n".format(gain))
            append(
                when {
                    best.connections == first.connections && gain <= 1.15 ->
                        "→ 加连接无收益：很可能是按 IP/账户聚合限速。应降低连接数（省请求与握手），而不是继续加。"
                    best.connections == ok.last().connections && gain > 1.15 ->
                        "→ 加连接有收益且最高档最优：结合「峰值并发」看是否真跑满，再决定上限。"
                    else ->
                        "→ 出现先升后降：服务端对并发有惩罚。应自适应降到拐点连接数（这正是 aria2 --max-connection-per-server=16 的理由）。"
                }
            )
        }
    }

    private suspend fun measureTier(
        url: String,
        headers: Map<String, String>,
        knownSize: Long,
        connections: Int,
        windowMs: Long,
        workDir: File?,
    ): ConnectionTierResult = coroutineScope {
        val out = File.createTempFile("turbodl-diag", ".bin", workDir).apply { deleteOnExit() }
        // 固定并发：关掉慢启动与预热，才能干净地只对比"连接数"这一个变量。
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = connections,
                maxConcurrentTasks = 1,
                warmUpConnections = false,
                slowStart = false,
                workDir = workDir,
            )
        )
        val lastBytes = AtomicLong(0)
        val peak = AtomicInteger(0)
        var collector: kotlinx.coroutines.Job? = null
        val t0 = System.currentTimeMillis()
        try {
            val id = client.submit(
                DownloadRequest(url = url, destination = out, headers = headers, knownSize = knownSize)
            )
            collector = launch {
                client.events.collect { ev ->
                    if (ev is TurboEvent.Progress) {
                        val p = ev.progress
                        if (p.downloadedBytes > lastBytes.get()) lastBytes.set(p.downloadedBytes)
                        if (p.activeConnections > peak.get()) peak.set(p.activeConnections)
                    }
                }
            }
            while (System.currentTimeMillis() - t0 < windowMs) delay(200)
            val elapsed = System.currentTimeMillis() - t0
            client.cancel(id)
            ConnectionTierResult(connections, lastBytes.get(), elapsed, peak.get())
        } finally {
            collector?.cancel()
            runCatching { client.shutdown() }
            runCatching { out.delete() }
        }
    }
}

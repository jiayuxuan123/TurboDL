package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 第一阶段：**只测不改**的 A/B 对照台。
 *
 * 目的：用 aria2 / Motrix 的机制（少连接 + 大分片）与 TurboDL 现状（多连接 + 细分片）
 * 做同条件对比，用**数据**判断方向。**不改任何产品代码**，只通过 [TurboConfig] 传参。
 *
 * ## 为什么要模拟网络而不是跑回环
 *
 * 本地回环（127.0.0.1）**RTT 近乎为 0、带宽无限**，因此：
 *  - 「每个分片一次 HTTP 请求」的代价几乎为零 → 细分片的劣势**完全被掩盖**；
 *  - 结论会失真（这正是我上一轮 A/B 想法的漏洞，见方案 P-6）。
 *
 * 所以本测量台给服务器加了两个可配置维度：
 *  - [SimServer.rttMs]：**每个请求**在返回响应头之前延迟这么久（模拟握手/首字节往返）；
 *  - [SimServer.perConnBps]：**每条连接**的带宽上限（模拟 CDN 的每连接限速）。
 *
 * 这两个正是"请求数"与"连接数"各自的代价来源，缺一不可。
 *
 * ## 记录指标
 *  - `elapsedMs`：总耗时
 *  - `requests`：发出的 Range 请求数（= 分片数）
 *  - `peakConns`：峰值并发连接
 *  - `throughput`：等效吞吐
 */
class Phase1ABTest {

    private companion object {
        /**
         * 【默认跳过】本类是**诊断测量台**，不是回归测试：
         * 它跑 3 个场景 × 6 组配置（每组含每连接限速），总耗时约 40 秒，
         * 会把全量套件从 ~2 分钟拖到 3 分钟以上。
         * 需要时显式开启：环境变量 `TURBODL_BENCH=1`。
         */
        const val BENCH_SKIP_HINT =
            "[BENCH] 跳过基于时间的 A/B 测量台（设置 TURBODL_BENCH=1 启用）"

        fun benchEnabled(): Boolean = System.getenv("TURBODL_BENCH") == "1"
    }

    // ------------------------------------------------------------------
    // 模拟网络服务器
    // ------------------------------------------------------------------

    private class SimServer(
        private val payload: ByteArray,
        /** 每个请求返回响应头前的延迟（毫秒）—— 模拟 RTT / 首字节往返。 */
        private val rttMs: Long,
        /** 每条连接的带宽上限（字节/秒）；<=0 表示不限。 */
        private val perConnBps: Long,
    ) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        val requests = AtomicInteger(0)
        val activeConns = AtomicInteger(0)
        val peakConns = AtomicInteger(0)
        private val total = payload.size

        init {
            server.createContext("/f.bin") { ex: HttpExchange ->
                val range = ex.requestHeaders.getFirst("Range")
                val cur = activeConns.incrementAndGet()
                peakConns.updateAndGet { maxOf(it, cur) }
                try {
                    // 模拟握手 / 首字节往返：在写响应头之前等待
                    if (rttMs > 0) Thread.sleep(rttMs)
                    val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                    if (m == null) {
                        ex.responseHeaders.add("Accept-Ranges", "bytes")
                        ex.sendResponseHeaders(200, total.toLong())
                        pump(ex, 0, total)
                        return@createContext
                    }
                    requests.incrementAndGet()
                    val s = m.groupValues[1].toInt()
                    val e = m.groupValues[2].toIntOrNull() ?: (total - 1)
                    val len = e - s + 1
                    ex.responseHeaders.add("Content-Range", "bytes $s-$e/$total")
                    ex.responseHeaders.add("Accept-Ranges", "bytes")
                    ex.sendResponseHeaders(206, len.toLong())
                    pump(ex, s, e + 1)
                } finally {
                    activeConns.decrementAndGet()
                }
            }
            server.executor = Executors.newFixedThreadPool(96)
            server.start()
        }

        /** 按 perConnBps 限速吐出 [from,to) 的字节。 */
        private fun pump(ex: HttpExchange, from: Int, to: Int) {
            ex.responseBody.use { out ->
                if (perConnBps <= 0) {
                    out.write(payload, from, to - from)
                    return
                }
                val chunk = 32 * 1024
                var off = from
                while (off < to) {
                    val n = minOf(chunk, to - off)
                    val t0 = System.nanoTime()
                    out.write(payload, off, n)
                    out.flush()
                    off += n
                    // 让本条连接平均不超过 perConnBps
                    val shouldCostNs = n * 1_000_000_000L / perConnBps
                    val usedNs = System.nanoTime() - t0
                    val sleepNs = shouldCostNs - usedNs
                    if (sleepNs > 0) {
                        val ms = sleepNs / 1_000_000
                        if (ms > 0) Thread.sleep(ms)
                    }
                }
            }
        }

        fun stop() = server.stop(0)
    }

    // ------------------------------------------------------------------
    // 待对比的配置（全部通过 TurboConfig 表达，不改产品代码）
    // ------------------------------------------------------------------

    private data class Case(
        val name: String,
        val connections: Int,
        val maxConnPerHost: Int,
        val minSegmentSize: Long,
        val blockSize: Long,
        val segmentsPerConnection: Int,
    ) {
        fun toConfig(workDir: File) = TurboConfig(
            maxConnectionsPerTask = connections,
            maxConcurrentTasks = 1,
            maxConnectionsPerHost = maxConnPerHost,
            minSegmentSize = minSegmentSize,
            blockSize = blockSize,
            segmentsPerConnection = segmentsPerConnection,
            workDir = workDir,
            warmUpConnections = false,
        )
    }

    private val cases = listOf(
        // —— TurboDL 现状（多连接 + 细分片 + 工作窃取）——
        Case("TurboDL 现状", connections = 8, maxConnPerHost = 0, minSegmentSize = 64 * 1024, blockSize = 16L * 1024 * 1024, segmentsPerConnection = 4),
        // —— aria2 默认：split=5，max-connection-per-server=1，min-split-size=20M ——
        // 注意：`TurboConfig` 有 `require(blockSize >= minSegmentSize)` 的硬约束，
        // 因此要表达 minSegmentSize=20MB **必须同时把 blockSize 抬到 ≥20MB**。
        // 这本身就说明「只改 minSegmentSize」在当前实现里是表达不出来的（见方案 P-1）。
        Case("aria2 默认", connections = 5, maxConnPerHost = 1, minSegmentSize = 20L * 1024 * 1024, blockSize = 32L * 1024 * 1024, segmentsPerConnection = 1),
        // —— aria2 手动加速 -x16 -s16，min-split-size=4M ——
        Case("aria2 加速", connections = 16, maxConnPerHost = 16, minSegmentSize = 4L * 1024 * 1024, blockSize = 16L * 1024 * 1024, segmentsPerConnection = 1),
        // —— Motrix balanced：16 连接 / minSplitSize=10M ——
        Case("Motrix balanced", connections = 16, maxConnPerHost = 16, minSegmentSize = 10L * 1024 * 1024, blockSize = 16L * 1024 * 1024, segmentsPerConnection = 1),
        // —— Motrix maximum：64 连接 / minSplitSize=1M ——
        Case("Motrix maximum", connections = 64, maxConnPerHost = 64, minSegmentSize = 1L * 1024 * 1024, blockSize = 16L * 1024 * 1024, segmentsPerConnection = 1),
        // —— 正交对照：只放大分片、连接数不变（隔离"粒度"这一个变量）——
        Case("对照组: 8连接+大分片", connections = 8, maxConnPerHost = 0, minSegmentSize = 4L * 1024 * 1024, blockSize = 16L * 1024 * 1024, segmentsPerConnection = 1),
    )

    private suspend fun runCase(c: Case, payload: ByteArray, rttMs: Long, perConnBps: Long, label: String): String {
        val srv = SimServer(payload, rttMs, perConnBps)
        val workDir = File(System.getProperty("java.io.tmpdir"), "turbodl-ab-${System.nanoTime()}").apply { mkdirs() }
        val client = TurboClient(c.toConfig(workDir))
        val out = File.createTempFile("abtest", ".bin").apply { deleteOnExit() }
        return try {
            val t0 = System.currentTimeMillis()
            val id = client.submit(
                DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out, knownSize = payload.size.toLong())
            )
            val ok = client.await(id)
            val elapsed = System.currentTimeMillis() - t0
            val byteExact = ok.isSuccess && payload.contentEquals(out.readBytes())
            val mbps = payload.size.toDouble() / 1024 / 1024 / (elapsed / 1000.0)
            String.format(
                "%-22s | %6d ms | %8.1f MB/s | %5d 请求 | 峰值 %3d 连接 | 字节精确=%s | %s",
                c.name, elapsed, mbps, srv.requests.get(), srv.peakConns.get(), byteExact, label,
            )
        } finally {
            client.shutdown()
            srv.stop()
            workDir.deleteRecursively()
            out.delete()
        }
    }

    /**
     * 【场景 1】共享带宽充裕 + 每连接限速 —— 这是"多连接能加成"的场景，
     * 也是 TurboDL 设计所针对的场景。
     * 每连接 2MB/s，8 连接理论上限 16MB/s，16 连接理论上限 32MB/s。
     */
    @Test
    fun `scenario 1 - per-connection throttled server`() = runBlocking {
        if (!benchEnabled()) { println(BENCH_SKIP_HINT); return@runBlocking }
        val size = 24 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 7 + 3) % 251).toByte() }
        println("=== 场景 1：每连接限速 2MB/s，RTT=30ms，文件 24MB（理论上限：8连接=16MB/s, 16连接=32MB/s）===")
        for (c in cases) {
            println(runCase(c, payload, rttMs = 30, perConnBps = 2L * 1024 * 1024, label = "每连接2MB/s"))
        }
        assertTrue(true)
    }

    /**
     * 【场景 2】服务器不限速、带宽充裕 —— 此时"连接数"没有增益，
     * 唯一差异是**请求数带来的 RTT 开销**。
     * 这是"少连接+大分片"应当胜出的场景。
     */
    @Test
    fun `scenario 2 - unthrottled server, request overhead dominates`() = runBlocking {
        if (!benchEnabled()) { println(BENCH_SKIP_HINT); return@runBlocking }
        val size = 24 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 7 + 3) % 251).toByte() }
        println("=== 场景 2：不限速，RTT=30ms，文件 24MB（连接数无增益，只剩请求数带来的 RTT 开销）===")
        for (c in cases) {
            println(runCase(c, payload, rttMs = 30, perConnBps = 0, label = "不限速"))
        }
        assertTrue(true)
    }

    /** 【场景 3】高 RTT 弱网：RTT=150ms。请求数的代价被放大 5 倍。 */
    @Test
    fun `scenario 3 - high latency, request count penalty amplified`() = runBlocking {
        if (!benchEnabled()) { println(BENCH_SKIP_HINT); return@runBlocking }
        val size = 12 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 11 + 5) % 253).toByte() }
        println("=== 场景 3：不限速，RTT=150ms（弱网），文件 12MB ===")
        for (c in cases) {
            println(runCase(c, payload, rttMs = 150, perConnBps = 0, label = "RTT150ms"))
        }
        assertTrue(true)
    }
}

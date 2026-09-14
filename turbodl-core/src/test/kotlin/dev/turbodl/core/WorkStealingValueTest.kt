package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 决定性实验：**在连接速度不均时，「多分片 + 工作窃取」到底值不值？**
 *
 * ## 为什么必须做这个实验
 *
 * 阶段一的 A/B（`Phase1ABTest`）证明：**连接速度完全相同时**，块数 ≈ 连接数最快
 * （8 连接 32 块 26.8 MB/s → 8 连接 8 块 55.6 MB/s，×2.1）。
 *
 * 但那个实验有个**结构性偏向**：所有连接速度一样时，**工作窃取无事可做** ——
 * 慢连接不存在，就不需要"让快连接接手慢连接剩下的活"。
 * 于是它必然得出"块越少越好"的结论，而这**不能代表真实网络**。
 *
 * 真实网络里连接速度是**不均**的（拥塞、不同边缘节点、无线抖动）。
 * 那时「块数 ≫ 连接数」才有意义：快连接干完自己的活，还能去抢慢连接的剩余块。
 *
 * ## 本实验的设计
 *
 * 按**客户端 TCP 端口**识别连接：**第一条被观察到的连接**全程降速到 200 KB/s，
 * 其余连接不限速。这样"慢"是绑定在**连接**上的（真实），而不是绑定在"字节区间"上
 * ——后者会让窃取失去意义（谁下慢区间谁就慢，抢过来也还是慢）。
 *
 * 然后对比 `segmentsPerConnection` = 1 / 2 / 4 的总耗时。
 */
class WorkStealingValueTest {

    private companion object {
        /** 【默认跳过】诊断测量台，非回归测试。约 25 秒。设置 `TURBODL_BENCH=1` 启用。 */
        const val BENCH_SKIP_HINT =
            "[BENCH] 跳过工作窃取价值测量台（设置 TURBODL_BENCH=1 启用）"

        fun benchEnabled(): Boolean = System.getenv("TURBODL_BENCH") == "1"
    }

    private class StragglerServer(
        private val payload: ByteArray,
        /** 慢连接的带宽（字节/秒）。第一条被观察到的客户端端口用这个速率。 */
        private val slowBps: Long,
    ) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        val requests = AtomicInteger(0)
        /** 客户端端口 → 是否被判为"慢连接"。第一条出现的端口为慢。 */
        private val slowPorts = ConcurrentHashMap<Int, Boolean>()
        private val seenOrder = AtomicInteger(0)
        private val total = payload.size

        private fun isSlow(ex: HttpExchange): Boolean {
            val p = (ex.remoteAddress as? java.net.InetSocketAddress)?.port ?: return false
            slowPorts[p]?.let { return it }
            // 第一次见到这个端口：按出现顺序判定
            val order = seenOrder.incrementAndGet()
            val slow = order == 1
            slowPorts[p] = slow
            if (slow) println("    [server] 端口 $p 被判为慢连接（${slowBps / 1024} KB/s）")
            return slow
        }

        init {
            server.createContext("/f.bin") { ex: HttpExchange ->
                val range = ex.requestHeaders.getFirst("Range")
                requests.incrementAndGet()
                val slow = isSlow(ex)
                val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                if (m == null) {
                    ex.sendResponseHeaders(200, total.toLong())
                    pump(ex, 0, total, slow)
                    return@createContext
                }
                val s = m.groupValues[1].toInt()
                val e = m.groupValues[2].toIntOrNull() ?: (total - 1)
                val len = e - s + 1
                ex.responseHeaders.add("Content-Range", "bytes $s-$e/$total")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(206, len.toLong())
                pump(ex, s, e + 1, slow)
            }
            server.executor = Executors.newFixedThreadPool(64)
            server.start()
        }

        private fun pump(ex: HttpExchange, from: Int, to: Int, slow: Boolean) {
            ex.responseBody.use { out ->
                if (!slow) {
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
                    val targetNs = n * 1_000_000_000L / slowBps
                    val usedNs = System.nanoTime() - t0
                    if (targetNs > usedNs) Thread.sleep((targetNs - usedNs) / 1_000_000)
                }
            }
        }

        fun stop() = server.stop(0)
    }

    private data class Case(val spc: Int, val label: String)

    private suspend fun runCase(c: Case, payload: ByteArray, connections: Int, slowBps: Long): String {
        val srv = StragglerServer(payload, slowBps)
        val workDir = File(System.getProperty("java.io.tmpdir"), "turbodl-ws-${System.nanoTime()}").apply { mkdirs() }
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = connections,
                maxConcurrentTasks = 1,
                segmentsPerConnection = c.spc,
                workDir = workDir,
                warmUpConnections = false,
                slowStart = false,          // 直接全开，隔离"窃取"这一个变量
            )
        )
        val out = File.createTempFile("worksteal", ".bin").apply { deleteOnExit() }
        return try {
            val t0 = System.currentTimeMillis()
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out, knownSize = payload.size.toLong()))
            val ok = client.await(id)
            val elapsed = System.currentTimeMillis() - t0
            val exact = ok.isSuccess && payload.contentEquals(out.readBytes())
            String.format(
                "%-18s | %7d ms | %7.2f MB/s | %4d 请求 | 字节精确=%s",
                c.label, elapsed, payload.size / 1024.0 / 1024.0 / (elapsed / 1000.0), srv.requests.get(), exact,
            )
        } finally {
            client.shutdown()
            srv.stop()
            workDir.deleteRecursively()
            out.delete()
        }
    }

    /**
     * 16MB 文件、8 连接、**其中 1 条连接被限速到 200KB/s**。
     *
     * 若「多块 + 工作窃取」真的能救长尾，那么 spc=4（32 块）应当明显快于 spc=1（8 块）——
     * 因为前者能让 7 条快连接把慢连接没干完的活抢过来。
     *
     * 若 spc=1 仍然不慢，说明**块数 ≈ 连接数**在任何情况下都够用，
     * 那 `RampUpReportingTest` 的期望就需要重新审视（它守的是"上报数字不掉"，不是吞吐）。
     */
    @Test
    fun `slow connection - does work stealing pay off`() = runBlocking {
        if (!benchEnabled()) { println(BENCH_SKIP_HINT); return@runBlocking }
        val size = 16 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 7 + 3) % 251).toByte() }
        val slowBps = 200L * 1024
        val connections = 8
        // 慢连接需下完"它自己那份"：spc=1 时 1/8 文件=2MB @200KB/s ≈ 10.2s
        println("=== 8 连接，其中 1 条限速 200KB/s，文件 16MB，slowStart=false ===")
        println("（若工作窃取有效，spc=4 应显著快于 spc=1）")
        for (c in listOf(Case(1, "spc=1 (8块)"), Case(2, "spc=2 (16块)"), Case(4, "spc=4 (32块)"))) {
            println(runCase(c, payload, connections, slowBps))
        }
        assertTrue(true)
    }

    /** 对照：**没有慢连接**时同样三档的耗时（应与阶段一结论一致：块越少越快）。 */
    @Test
    fun `no slow connection - control group`() = runBlocking {
        if (!benchEnabled()) { println(BENCH_SKIP_HINT); return@runBlocking }
        val size = 16 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 7 + 3) % 251).toByte() }
        println("=== 对照：8 连接全部不限速，文件 16MB ===")
        for (c in listOf(Case(1, "spc=1 (8块)"), Case(2, "spc=2 (16块)"), Case(4, "spc=4 (32块)"))) {
            println(runCase(c, payload, 8, slowBps = Long.MAX_VALUE))
        }
        assertTrue(true)
    }
}

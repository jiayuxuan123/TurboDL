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
 * 偏斜度扫描：**「多分片 + 工作窃取」的收益，随链路偏斜度如何衰减？**
 *
 * ## 为什么需要这个扫描
 *
 * 已知（`WorkStealingValueTest`）：8 条连接里 **1 条**限速 200KB/s 时，
 * 32 块比 8 块**快 3.6 倍**。
 *
 * 但那是**极端偏斜**（1/8 的连接慢 10 倍以上）。真实 CDN 的不均更温和。
 * 若 3.6 倍只是极端偏斜下的上限，那么"固定 spc=4"就缺少依据 ——
 * 需要看收益随偏斜度**怎么衰减**。
 *
 * ## 设计
 *
 * 固定 16MB / 8 连接 / 慢连接 200KB/s，扫描**慢连接条数** ∈ {0, 1, 2, 4}，
 * 每档对比 spc ∈ {1, 2, 4}（块数 8 / 16 / 32）。
 *
 * 判定标准：**只有量级大且单调的数据才采信**（小文件、短耗时的那几档噪声大）。
 */
class SkewSweepTest {

    private companion object {
        /** 【默认跳过】诊断测量台。设置 `TURBODL_BENCH=1` 启用。 */
        const val BENCH_SKIP_HINT = "[BENCH] 跳过偏斜度扫描（设置 TURBODL_BENCH=1 启用）"
        fun benchEnabled(): Boolean = System.getenv("TURBODL_BENCH") == "1"
    }

    private class SkewServer(
        private val payload: ByteArray,
        /** 前 N 条被观察到的客户端连接降速。 */
        private val slowConnCount: Int,
        private val slowBps: Long,
    ) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        val requests = AtomicInteger(0)
        private val slowPorts = ConcurrentHashMap<Int, Boolean>()
        private val seenOrder = AtomicInteger(0)
        private val total = payload.size

        private fun isSlow(ex: HttpExchange): Boolean {
            val p = (ex.remoteAddress as? java.net.InetSocketAddress)?.port ?: return false
            slowPorts[p]?.let { return it }
            val order = seenOrder.incrementAndGet()
            val slow = order <= slowConnCount
            slowPorts[p] = slow
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
                    pump(ex, 0, total, slow); return@createContext
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
            runCatching {
                ex.responseBody.use { out ->
                    if (!slow) { out.write(payload, from, to - from); return }
                    val chunk = 32 * 1024
                    var off = from
                    while (off < to) {
                        val n = minOf(chunk, to - off)
                        val t0 = System.nanoTime()
                        out.write(payload, off, n); out.flush(); off += n
                        val targetNs = n * 1_000_000_000L / slowBps
                        val usedNs = System.nanoTime() - t0
                        if (targetNs > usedNs) Thread.sleep((targetNs - usedNs) / 1_000_000)
                    }
                }
            }
            // 客户端会在被"抢活"后主动断开，服务端写流会抛 IOException —— 正常，忽略。
        }

        fun stop() = server.stop(0)
    }

    private suspend fun measure(spc: Int, slowConns: Int, payload: ByteArray, connections: Int, slowBps: Long): Long {
        val srv = SkewServer(payload, slowConns, slowBps)
        val workDir = File(System.getProperty("java.io.tmpdir"), "turbodl-skew-${System.nanoTime()}").apply { mkdirs() }
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = connections,
                maxConcurrentTasks = 1,
                segmentsPerConnection = spc,
                workDir = workDir,
                warmUpConnections = false,
                slowStart = false,
            )
        )
        val out = File.createTempFile("skewsweep", ".bin").apply { deleteOnExit() }
        return try {
            val t0 = System.currentTimeMillis()
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out, knownSize = payload.size.toLong()))
            val ok = client.await(id)
            val el = System.currentTimeMillis() - t0
            if (!ok.isSuccess || !payload.contentEquals(out.readBytes())) -1L else el
        } finally {
            client.shutdown(); srv.stop(); workDir.deleteRecursively(); out.delete()
        }
    }

    /** 每档取 3 次，**取中位数** —— 单次短耗时不可信（见 _PHASE2_RESULT.md §三）。 */
    private suspend fun median(spc: Int, slowConns: Int, payload: ByteArray, conns: Int, slowBps: Long): Long {
        val runs = ArrayList<Long>()
        repeat(3) { val v = measure(spc, slowConns, payload, conns, slowBps); if (v > 0) runs.add(v) }
        if (runs.isEmpty()) return -1
        runs.sort()
        return runs[runs.size / 2]
    }

    @Test
    fun `skew sweep - how does work stealing gain decay`() = runBlocking {
        if (!benchEnabled()) { println(BENCH_SKIP_HINT); return@runBlocking }
        val size = 16 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 7 + 3) % 251).toByte() }
        val conns = 8
        val slowBps = 200L * 1024

        println("=== 偏斜度扫描：16MB / 8 连接 / 慢连接 200KB/s，每档 3 次取中位数 ===")
        println(String.format("%-14s %12s %12s %12s %14s", "慢连接数", "spc=1(8块)", "spc=2(16块)", "spc=4(32块)", "4比1快多少"))
        for (slowConns in intArrayOf(0, 1, 2, 4)) {
            val a = median(1, slowConns, payload, conns, slowBps)
            val b = median(2, slowConns, payload, conns, slowBps)
            val c = median(4, slowConns, payload, conns, slowBps)
            val gain = if (a > 0 && c > 0) String.format("%.2fx", a.toDouble() / c) else "n/a"
            println(String.format("%-14s %10d ms %10d ms %10d ms %14s", "${slowConns}/8 慢", a, b, c, gain))
        }
        println()
        println("读法：只有「量级大且单调」的档位可信；若收益随偏斜度迅速衰减到 ~1x，")
        println("      则「固定 spc=4」缺少依据，应改为按实测偏斜度自适应。")
        assertTrue(true)
    }
}

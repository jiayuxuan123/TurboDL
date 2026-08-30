package dev.turbodl.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 并发爬升速度与上报语义回归测试（复刻用户实测反馈）。
 *
 * 两个缺陷：
 *  1. **爬升太慢**：慢启动每次只 +1，从初始 4 爬到 128 需 124 次上调、约 248 个成功分片；
 *     中小文件/慢网根本爬不到设定值，用户看到"线程数始终远低于设定"。
 *     修复：按比例上调（当前值 × 0.5，至少 +1），十余次即到顶。
 *  2. **上报语义错误**：上报"瞬时在飞分片数"，而 worker 完成一个分片到领取下一个之间存在空档，
 *     速度越快、分片完成越频繁，空档占比越大，采样到的数字反而越小
 *     ——表现为"速度变快但显示的线程数下降"。
 *     修复：上报 min(目标并发, 剩余工作量)。
 */
class RampUpReportingTest {

    private class Server(payload: ByteArray, val chunkSleepMs: Long) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        private val total = payload.size

        init {
            server.createContext("/f.bin") { ex ->
                val m = ex.requestHeaders.getFirst("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                if (m == null) {
                    ex.responseHeaders.add("Accept-Ranges", "bytes")
                    ex.sendResponseHeaders(200, total.toLong())
                    ex.responseBody.use { it.write(payload) }
                    return@createContext
                }
                val s = m.groupValues[1].toInt()
                val e = m.groupValues[2].toIntOrNull() ?: (total - 1)
                val len = e - s + 1
                ex.responseHeaders.add("Content-Range", "bytes $s-$e/$total")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { out ->
                    var off = s
                    val chunk = 32 * 1024
                    while (off <= e) {
                        val n = minOf(chunk, e - off + 1)
                        out.write(payload, off, n); out.flush()
                        off += n
                        if (chunkSleepMs > 0) Thread.sleep(chunkSleepMs)
                    }
                }
            }
            server.executor = Executors.newFixedThreadPool(300)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    @Test
    fun `slow start reaches configured concurrency on a modest file`() = runBlocking {
        // 8MB / 128 连接：块数 = 128×4 = 512，但旧的“每 2 个成功 +1”从初始 4 爬到 128
        // 需 248 次上调、约 496 个成功分片，几乎要把整个文件下完才到顶——实际永远跑不满。
        val size = 8 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 13 + 7) % 256).toByte() }
        val srv = Server(payload, chunkSleepMs = 3)
        val connections = 128
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = connections,
                maxConcurrentTasks = 1,
                slowStart = true,
                warmUpConnections = false,
                progressIntervalMs = 0,   // 关闭节流：本用例专测爬升，需要密集采样
            )
        )
        val maxReported = AtomicInteger(0)
        val collector = launch {
            client.progress.collect { map ->
                map.values.forEach { p -> maxReported.updateAndGet { m -> maxOf(m, p.activeConnections) } }
            }
        }
        val out = File.createTempFile("rampup", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
            assertTrue(client.await(id).isSuccess)
            assertEquals(size.toLong(), out.length())
            // 应爬升到接近设定值（放宽到 75% 以吸收收尾阶段与调度抖动）
            assertTrue(
                maxReported.get() >= connections * 3 / 4,
                "慢启动应快速爬升到接近设定的 $connections，实际上报峰值 ${maxReported.get()}"
            )
        } finally {
            collector.cancel()
            client.shutdown()
            srv.stop()
        }
    }

    @Test
    fun `reported connections stay high while transfer is fast`() = runBlocking {
        // 无人为延迟的极快传输：旧的“瞬时在飞数”采样会因 worker 领取空档而偏低。
        val size = 16 * 1024 * 1024
        val payload = ByteArray(size) { (it % 256).toByte() }
        val srv = Server(payload, chunkSleepMs = 0)
        val connections = 32
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = connections,
                maxConcurrentTasks = 1,
                slowStart = false,          // 直接全开，专测上报语义
                warmUpConnections = false,
                progressIntervalMs = 0,   // 关闭节流：需要密集采样才能统计占比
            )
        )
        // 关键：用户抱怨的是“持续显示的数字下降”，而非峰值达不到。
        // 故统计下载中的全部上报采样，考察“接近满并发的采样占比”。
        val samples = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val collector = launch {
            client.progress.collect { map ->
                map.values.forEach { p ->
                    if (p.state == TaskState.DOWNLOADING && p.downloadedBytes > 0) {
                        samples.add(p.activeConnections)
                    }
                }
            }
        }
        val out = File.createTempFile("reporting", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
            assertTrue(client.await(id).isSuccess)
            assertEquals(size.toLong(), out.length())
            val list = samples.toList()
            assertTrue(list.size >= 10, "应采集到足够的上报样本，实际 ${list.size}")
            // 去掉最后 10% 的收尾阶段（剩余工作量确实不足，并发天然下降属正常）
            val body = list.take((list.size * 9 / 10).coerceAtLeast(5))
            val high = body.count { it >= connections * 3 / 4 }
            val ratio = high.toDouble() / body.size
            assertTrue(
                ratio >= 0.7,
                "高速传输中应持续上报接近 $connections 并发；实际仅 ${(ratio * 100).toInt()}% 的采样达标" +
                    "（样本=${body.size}, 中位数=${body.sorted()[body.size / 2]}）"
            )
        } finally {
            collector.cancel()
            client.shutdown()
            srv.stop()
        }
    }
}

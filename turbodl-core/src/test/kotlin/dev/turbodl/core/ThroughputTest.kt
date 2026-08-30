package dev.turbodl.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 吞吐量瓶颈回归测试：复刻用户反馈「后期也只能稳定 3MB/s，别的下载器能跑几十 MB/s」。
 *
 * 三个自身瓶颈（与服务器/网络无关）：
 *  1. **Dispatchers.IO 并行度天花板**：分片下载是阻塞式 socket read，一个分片占死一个线程；
 *     Dispatchers.IO 默认并行度 = max(64, CPU 核数)，设 128 连接时实际只有 ~64 个能同时搜数据。
 *     修复：为每个任务用 limitedParallelism 开专用阻塞 IO 池（workers + 余量）。
 *  2. **进度上报未节流**：每个缓冲块都重建进度 Map + 启协程发事件；64 连接高速下每秒上千次，
 *     CPU 耗在上报而非搬数据。修复：默认 200ms 节流。
 *  3. **缓冲区过小**：256KB 缓冲在 50MB/s 下每秒 200 次完整回调链路。修复：默认 1MB。
 */
class ThroughputTest {

    /** 本地服务器：可配每个请求的固定处理耗时，使并发窗口足够长以便观测真实并发度。 */
    private class FastServer(private val payload: ByteArray, private val perRequestDelayMs: Long = 0) {
        val peak = AtomicInteger(0)
        private val cur = AtomicInteger(0)
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        private val total = payload.size

        init {
            server.createContext("/f.bin") { ex ->
                val now = cur.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                try {
                    // 先挡住：让所有并发请求同时在场，否则 localhost 上分片瞬完，观测不到真实并发度
                    if (perRequestDelayMs > 0) Thread.sleep(perRequestDelayMs)
                    val m = ex.requestHeaders.getFirst("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                    if (m == null) {
                        ex.responseHeaders.add("Accept-Ranges", "bytes")
                        ex.sendResponseHeaders(200, total.toLong())
                        ex.responseBody.use { it.write(payload) }
                        return@createContext
                    }
                    val s = m.groupValues[1].toInt()
                    val e = m.groupValues[2].toIntOrNull() ?: (total - 1)
                    val end = minOf(e, total - 1)
                    val len = end - s + 1
                    ex.responseHeaders.add("Content-Range", "bytes $s-$end/$total")
                    ex.responseHeaders.add("Accept-Ranges", "bytes")
                    ex.sendResponseHeaders(206, len.toLong())
                    ex.responseBody.use { it.write(payload, s, len) }
                } finally {
                    cur.decrementAndGet()
                }
            }
            server.executor = Executors.newFixedThreadPool(320)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    /**
     * 128 连接在本地极速服务器上应能真正并发到接近 128。
     *
     * 这是 Dispatchers.IO 并行度瓶颈的直接体现：修复前受 max(64, cpus) 限制，
     * 服务端观测到的峰值并发上不去（本机 CPU 核数远小于 128）。
     */
    @Test
    fun `128 connections actually run concurrently beyond IO dispatcher default`() = runBlocking {
        val size = 8 * 1024 * 1024
        val payload = ByteArray(size) { (it % 256).toByte() }
        // 每个请求挡 400ms：保证同一时刻有大量请求在场，能真实反映客户端并行度
        val srv = FastServer(payload, perRequestDelayMs = 400)
        val connections = 128
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = connections,
                maxConcurrentTasks = 1,
                slowStart = false,          // 直接全开，专测并行度上限
                warmUpConnections = false,
                segmentsPerConnection = 2,  // 每连接 2 块，保证块数充足
            )
        )
        val out = File.createTempFile("throughput", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
            assertTrue(client.await(id).isSuccess)
            assertEquals(size.toLong(), out.length())
            val cpus = Runtime.getRuntime().availableProcessors()
            val ioDefault = maxOf(64, cpus)
            // 关键：服务端观测到的峰值并发应明显超过 Dispatchers.IO 的默认并行度
            assertTrue(
                srv.peak.get() > ioDefault,
                "128 连接应突破 Dispatchers.IO 默认并行度($ioDefault)；实际峰值 ${srv.peak.get()}" +
                    "（说明仍受共享 IO 池限制）"
            )
        } finally {
            client.shutdown()
            srv.stop()
        }
    }

    /** 字节精确性在大缓冲 + 节流下不受影响（防止性能优化引入数据损坏）。 */
    @Test
    fun `large buffer and throttled progress keep bytes exact`() = runBlocking {
        val size = 24 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 31 + 7) % 256).toByte() }
        val srv = FastServer(payload)
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 32,
                maxConcurrentTasks = 1,
                warmUpConnections = false,
                ioBufferSize = 2 * 1024 * 1024,   // 大缓冲
                progressIntervalMs = 500,         // 强节流
            )
        )
        val out = File.createTempFile("bufexact", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
            assertTrue(client.await(id).isSuccess)
            assertEquals(size.toLong(), out.length())
            assertTrue(out.readBytes().contentEquals(payload), "大缓冲 + 节流下内容仍应逐字节一致")
        } finally {
            client.shutdown()
            srv.stop()
        }
    }

    /** 默认配置的吞吐相关预算，防止再次退化。 */
    @Test
    fun `throughput related defaults are sane`() {
        val c = TurboConfig()
        assertTrue(c.ioBufferSize >= 512 * 1024, "IO 缓冲默认应 ≥512KB（现为 ${c.ioBufferSize}）")
        assertTrue(
            c.progressIntervalMs in 1..1000,
            "进度上报应默认节流在 1..1000ms（现为 ${c.progressIntervalMs}）"
        )
    }
}

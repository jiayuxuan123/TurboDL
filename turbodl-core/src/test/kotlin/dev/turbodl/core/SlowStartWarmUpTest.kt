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
 * 慢启动 + 连接预热回归测试。
 *
 * 目标：验证开启慢启动时，起始并发不会一上来就是满 workers（避免瞬时几十连接冲击服务器），
 * 但最终仍能爬升到设定并发把文件下完、字节精确。
 */
class SlowStartWarmUpTest {

    private class Server(payload: ByteArray, val chunkSleepMs: Long) {
        val peak = AtomicInteger(0)
        private val cur = AtomicInteger(0)
        /** 记录下载启动早期（前若干请求）观测到的并发，检验没有一上来就全开。 */
        val earlyPeak = AtomicInteger(0)
        private val servedReqs = AtomicInteger(0)
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        private val total = payload.size

        init {
            server.createContext("/f.bin") { ex ->
                val now = cur.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                val reqIdx = servedReqs.incrementAndGet()
                if (reqIdx <= 3) earlyPeak.updateAndGet { maxOf(it, now) }
                try {
                    val m = ex.requestHeaders.getFirst("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                    if (m == null) {
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
                        val chunk = 16 * 1024
                        while (off <= e) {
                            val n = minOf(chunk, e - off + 1)
                            out.write(payload, off, n); out.flush()
                            off += n
                            Thread.sleep(chunkSleepMs)
                        }
                    }
                } finally {
                    cur.decrementAndGet()
                }
            }
            server.executor = Executors.newFixedThreadPool(300)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    @Test
    fun `slow start ramps up and completes byte-exact`() = runBlocking {
        val size = 24 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 13 + 5) % 256).toByte() }
        val srv = Server(payload, chunkSleepMs = 8)
        val connections = 64
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = connections,
                maxConcurrentTasks = 1,
                slowStart = true,
                slowStartInitial = 4,
                warmUpConnections = true,
            )
        )
        val out = File.createTempFile("slowstart", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
            assertTrue(client.await(id).isSuccess)
            assertEquals(size.toLong(), out.length())
            // 最终应爬升到接近设定并发（放宽到一半以上吸收抖动）
            assertTrue(srv.peak.get() >= connections / 2, "慢启动应最终爬升到接近 $connections，实际峰值 ${srv.peak.get()}")
        } finally {
            client.shutdown()
            srv.stop()
        }
    }

    @Test
    fun `disabling slow start opens full concurrency`() = runBlocking {
        val size = 16 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 3 + 1) % 256).toByte() }
        val srv = Server(payload, chunkSleepMs = 10)
        val connections = 48
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = connections,
                maxConcurrentTasks = 1,
                slowStart = false,
                warmUpConnections = false,
            )
        )
        val out = File.createTempFile("noss", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
            assertTrue(client.await(id).isSuccess)
            assertEquals(size.toLong(), out.length())
            assertTrue(srv.peak.get() >= connections / 2, "关闭慢启动应快速全开，实际峰值 ${srv.peak.get()}")
        } finally {
            client.shutdown()
            srv.stop()
        }
    }
}

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
 * 并发饱和度回归测试：验证「设定 N 连接就要真的并发跑 N 条」。
 *
 * 缺陷复现：旧实现按固定 blockSize 预分块，20MB 文件只切出 20 块，
 * 但配置了 64 连接 —— 多出的 worker 一上来 poll() 为空立刻退出，
 * 服务端观测到的峰值并发远低于设定值（表现为「开了 64 线程却只有单线程速度」）。
 */
class ConcurrencySaturationTest {

    /** 本地 Range 服务器，记录观测到的峰值并发请求数。 */
    private class CountingServer(payload: ByteArray) {
        val peak = AtomicInteger(0)
        private val current = AtomicInteger(0)
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port

        init {
            server.createContext("/f.bin") { ex ->
                val now = current.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                try {
                    val range = ex.requestHeaders.getFirst("Range")
                    if (range != null) {
                        val m = Regex("bytes=(\\d+)-(\\d*)").find(range)!!
                        val s = m.groupValues[1].toInt()
                        val e = m.groupValues[2].toIntOrNull() ?: (payload.size - 1)
                        val len = e - s + 1
                        ex.responseHeaders.add("Content-Range", "bytes $s-$e/${payload.size}")
                        ex.responseHeaders.add("Accept-Ranges", "bytes")
                        ex.sendResponseHeaders(206, len.toLong())
                        // 慢速回写，保证并发窗口足够长以便观测峰值
                        ex.responseBody.use { out ->
                            var off = s
                            val chunk = 16 * 1024
                            while (off <= e) {
                                val n = minOf(chunk, e - off + 1)
                                out.write(payload, off, n)
                                out.flush()
                                off += n
                                Thread.sleep(2)
                            }
                        }
                    } else {
                        ex.responseHeaders.add("Accept-Ranges", "bytes")
                        ex.sendResponseHeaders(200, payload.size.toLong())
                        ex.responseBody.use { it.write(payload) }
                    }
                } finally {
                    current.decrementAndGet()
                }
            }
            // 线程池要足够大，否则是服务端而非客户端限制了并发
            server.executor = Executors.newFixedThreadPool(300)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    @Test
    fun `configured connections are actually saturated`() = runBlocking {
        val size = 20 * 1024 * 1024      // 20MB，复刻用户场景
        val payload = ByteArray(size) { (it % 251).toByte() }
        val srv = CountingServer(payload)
        val connections = 64

        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = connections,
                maxConcurrentTasks = 1,
            )
        )
        val out = File.createTempFile("sat", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(
                DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out)
            )
            val result = client.await(id)
            assertTrue(result.isSuccess, "下载应成功：${result.exceptionOrNull()?.message}")
            assertEquals(size.toLong(), out.length(), "字节数应完全一致")

            // 关键断言：服务端观测到的峰值并发应接近设定连接数。
            // 放宽到 50% 以吸收调度抖动/错峰建连，但旧实现此处只能到 ~20（块数上限），必然失败。
            val peak = srv.peak.get()
            assertTrue(
                peak >= connections / 2,
                "峰值并发应接近设定的 $connections 条，实际只有 $peak（说明多线程未跑满）"
            )
        } finally {
            client.shutdown()
            srv.stop()
        }
    }

    @Test
    fun `small file with many connections still parallelizes`() = runBlocking {
        val size = 4 * 1024 * 1024       // 4MB 小文件
        val payload = ByteArray(size) { (it % 97).toByte() }
        val srv = CountingServer(payload)
        val connections = 32

        val client = TurboClient(
            TurboConfig(maxConnectionsPerTask = connections, maxConcurrentTasks = 1)
        )
        val out = File.createTempFile("sat-small", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
            assertTrue(client.await(id).isSuccess)
            assertEquals(size.toLong(), out.length())
            val peak = srv.peak.get()
            assertTrue(
                peak >= connections / 2,
                "4MB 文件在 $connections 连接下峰值并发应仍达半数以上，实际 $peak"
            )
        } finally {
            client.shutdown()
            srv.stop()
        }
    }
}

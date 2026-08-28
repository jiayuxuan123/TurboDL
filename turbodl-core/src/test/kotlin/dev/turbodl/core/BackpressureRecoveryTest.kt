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
 * 背压恢复回归测试：复刻用户反馈「下到后面线程数莫名掉下去、速度只剩几 KB」。
 *
 * 缺陷根因：旧背压只降不升 —— 一段时间内的瞬时 503/网络抖动会把并发乘性减半（可累减到 1），
 * 之后即使服务器完全恢复，并发也永远回不去，导致后半程退化成近似单线程。
 *
 * 修复后：持续成功若干分片后主动把并发恢复到设定值。本测试让服务器在下载前半程对部分请求
 * 返回 503（触发降并发），后半程全部正常（应触发恢复），最终断言下载成功且字节精确。
 */
class BackpressureRecoveryTest {

    private class FlakyThenHealthyServer(private val payload: ByteArray, private val flakyUntilRatio: Double) {
        val served = AtomicInteger(0)
        val throttled = AtomicInteger(0)
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        private val total = payload.size

        init {
            server.createContext("/f.bin") { ex ->
                val range = ex.requestHeaders.getFirst("Range")
                val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                if (m == null) {
                    ex.sendResponseHeaders(200, total.toLong())
                    ex.responseBody.use { it.write(payload) }
                    return@createContext
                }
                val s = m.groupValues[1].toInt()
                val e = m.groupValues[2].toIntOrNull() ?: (total - 1)
                // 前半程对部分靠前分片返回 503，制造「持续失败 → 降并发」
                val inFlakyZone = s < (total * flakyUntilRatio).toInt()
                if (inFlakyZone && (s / 65536) % 2 == 0) {
                    throttled.incrementAndGet()
                    ex.sendResponseHeaders(503, -1)
                    ex.close()
                    return@createContext
                }
                served.incrementAndGet()
                val len = e - s + 1
                ex.responseHeaders.add("Content-Range", "bytes $s-$e/$total")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { out ->
                    var off = s
                    val chunk = 16 * 1024
                    while (off <= e) {
                        val n = minOf(chunk, e - off + 1)
                        out.write(payload, off, n)
                        out.flush()
                        off += n
                        Thread.sleep(3)
                    }
                }
            }
            server.executor = Executors.newFixedThreadPool(200)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    @Test
    fun `concurrency recovers after transient throttling and completes byte-exact`() = runBlocking {
        val size = 16 * 1024 * 1024
        val payload = ByteArray(size) { (it % 253).toByte() }
        val srv = FlakyThenHealthyServer(payload, flakyUntilRatio = 0.3)
        val connections = 32

        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = connections,
                maxConcurrentTasks = 1,
                maxRetries = 10,                       // 允许被 503 的分片重试
                backpressureConsecutiveFailures = 4,   // 开启背压（复刻线上配置）
            )
        )
        val out = File.createTempFile("bpx", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
            val result = client.await(id)
            assertTrue(result.isSuccess, "背压恢复后应下载成功：${result.exceptionOrNull()?.message}")
            assertEquals(size.toLong(), out.length(), "字节数应精确一致")
            // 确认确实触发过 503（否则测试没覆盖到降并发路径）
            assertTrue(srv.throttled.get() > 0, "测试应触发过 503 限流以复刻降并发场景")
        } finally {
            client.shutdown()
            srv.stop()
        }
    }
}

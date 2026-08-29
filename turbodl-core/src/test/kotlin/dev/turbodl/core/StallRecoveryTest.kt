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
 * 卡死（stall）恢复回归测试：复刻用户反馈「显示在下载但一直不动」。
 *
 * 两类真实卡死场景，OkHttp 的 readTimeout 都管不了：
 *  1. **探测阶段挂起**：服务器接受连接但迟迟不返回响应头 → connect+read 超时叠加可达近分钟，
 *     期间 UI 已进入 DOWNLOADING 但字节为 0（用户描述的「连解析都没做」）。
 *     修复：probe 带独立超时 + 重试。
 *  2. **传输中涓涓细流**：服务器每隔很久吐几字节，read 永不超时，进度几乎不动。
 *     修复：任务级 stall 守护，指定时间内零增长即 cancel 在飞请求让分片重试。
 *
 * 设计参考 aria2 `--lowest-speed-limit` 与 curl `--speed-limit/--speed-time` 的思路（仅逻辑，非代码）。
 */
class StallRecoveryTest {

    /** 前 N 个请求故意挂起不响应，之后正常服务（模拟节点异常/网络抖动）。 */
    private class HangThenServeServer(private val payload: ByteArray, private val hangFirst: Int) {
        val hung = AtomicInteger(0)
        val served = AtomicInteger(0)
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        private val total = payload.size

        init {
            server.createContext("/f.bin") { ex ->
                if (hung.get() < hangFirst) {
                    hung.incrementAndGet()
                    // 接受连接但既不写响应头也不关闭：模拟"卡住不动"
                    Thread.sleep(60_000)
                    runCatching { ex.close() }
                    return@createContext
                }
                served.incrementAndGet()
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
                ex.responseBody.use { it.write(payload, s, len) }
            }
            server.executor = Executors.newFixedThreadPool(64)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    @Test
    fun `probe timeout and retry recovers from hung first attempt`() = runBlocking {
        val size = 2 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 17 + 3) % 256).toByte() }
        // 第一个探测请求挂死，重试应成功
        val srv = HangThenServeServer(payload, hangFirst = 1)
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 4,
                maxConcurrentTasks = 1,
                probeTimeoutMs = 3000,     // 3s 就放弃这次探测
                probeRetries = 2,
                warmUpConnections = false, // 排除预热干扰，专测探测
                slowStart = false,
            )
        )
        val out = File.createTempFile("stall-probe", ".bin").apply { deleteOnExit() }
        try {
            val started = System.currentTimeMillis()
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
            val result = client.await(id)
            val elapsed = System.currentTimeMillis() - started
            assertTrue(result.isSuccess, "探测超时后重试应成功：${result.exceptionOrNull()?.message}")
            assertEquals(size.toLong(), out.length())
            // 不应等到 OkHttp 的 60s read timeout：3s 超时 + 退避重试，总耗时应远小于 30s
            assertTrue(elapsed < 30_000, "应快速超时重试而非长时间挂起，实际 ${elapsed}ms")
            assertTrue(srv.hung.get() >= 1, "应确实发生过一次挂起")
        } finally {
            client.shutdown()
            srv.stop()
        }
    }

    @Test
    fun `stall watchdog fails fast instead of hanging forever`() = runBlocking {
        val size = 4 * 1024 * 1024
        val payload = ByteArray(size) { (it % 256).toByte() }
        // 所有请求都挂死：应在有限时间内失败，而不是无限"显示下载中"
        val srv = HangThenServeServer(payload, hangFirst = Int.MAX_VALUE)
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 4,
                maxConcurrentTasks = 1,
                probeTimeoutMs = 2000,
                probeRetries = 1,
                stallTimeoutMs = 4000,      // 4s 无字节即判卡死
                maxStallRecoveries = 1,
                maxRetries = 2,
                warmUpConnections = false,
                slowStart = false,
                readTimeoutMs = 60_000,     // 故意保留长 readTimeout，证明守护独立生效
            )
        )
        val out = File.createTempFile("stall-wd", ".bin").apply { deleteOnExit() }
        try {
            val started = System.currentTimeMillis()
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
            val result = client.await(id)
            val elapsed = System.currentTimeMillis() - started
            assertTrue(result.isFailure, "全程无响应应判定失败而非永久挂起")
            // 关键：不能等到 60s readTimeout；守护应在数十秒内收敛
            assertTrue(elapsed < 55_000, "应由 stall 守护快速收敛，实际 ${elapsed}ms")
        } finally {
            client.shutdown()
            srv.stop()
        }
    }
}

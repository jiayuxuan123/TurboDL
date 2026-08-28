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
 * 302 重定向解析回归测试：复刻用户反馈「原始链接卡在下载中、线程/字节都不动」。
 *
 * 根因：网盘/更新等原始链接会 302 跳到带签名的 CDN 临时直链，只有临时直链支持 Range 多线程。
 * 旧实现探测时能跟随重定向拿到 206，但下载分片仍用原始 URL —— 每个分片连接都要重走 302，
 * 可能命中不同节点/被拒，表现为「显示下载中但字节/线程不动，最后 Whole-file download failed」。
 *
 * 修复：probe 返回重定向后的最终 URL，后续分片/整文件下载都用它。
 * 本测试的原始端点只对「探测(bytes=0-0)」返回 302，对其它 Range 请求返回 403，
 * 只有 CDN 端点支持完整 Range —— 若下载没切换到 CDN URL，必然失败。
 */
class RedirectResolveTest {

    private class RedirectingServer(private val payload: ByteArray) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        private val total = payload.size
        /** 原始端点收到的非探测 Range 请求数（应为 0，证明下载没打原始 URL）。 */
        val originalNonProbeHits = AtomicInteger(0)
        val cdnHits = AtomicInteger(0)

        init {
            // 原始端点：/orig —— 任何请求都 302 到 /cdn；若被用于真正下载分片则记账并 403。
            server.createContext("/orig") { ex ->
                val range = ex.requestHeaders.getFirst("Range").orEmpty()
                val isProbe = range == "bytes=0-0"
                if (!isProbe) originalNonProbeHits.incrementAndGet()
                ex.responseHeaders.add("Location", "http://127.0.0.1:$port/cdn")
                ex.sendResponseHeaders(302, -1)
                ex.close()
            }
            // CDN 端点：/cdn —— 支持 Range 分片。
            server.createContext("/cdn") { ex ->
                cdnHits.incrementAndGet()
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
    fun `download follows 302 to CDN and uses resolved url for segments`() = runBlocking {
        val size = 4 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 7 + 3) % 256).toByte() }
        val srv = RedirectingServer(payload)
        val client = TurboClient(TurboConfig(maxConnectionsPerTask = 8, maxConcurrentTasks = 1))
        val out = File.createTempFile("redir", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/orig", out))
            val result = client.await(id)
            assertTrue(result.isSuccess, "应跟随 302 到 CDN 并成功：${result.exceptionOrNull()?.message}")
            assertEquals(size.toLong(), out.length())
            assertTrue(out.readBytes().contentEquals(payload), "内容应逐字节一致")
            // 关键：真正的分片下载不应再打原始 /orig 端点。
            assertEquals(0, srv.originalNonProbeHits.get(), "分片下载应使用解析后的 CDN URL，不再请求原始链接")
            assertTrue(srv.cdnHits.get() > 0, "应从 CDN 端点下载分片")
        } finally {
            client.shutdown()
            srv.stop()
        }
    }
}

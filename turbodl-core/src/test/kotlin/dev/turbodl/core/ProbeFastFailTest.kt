package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
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
 * 回归测试：**「确定性否定结论」不得重试**。
 *
 * ## 背景（用户实报）
 *
 * 「即使只有 10MB、6MB 这种小文件，解析阶段也很慢；网盘的反而更快。」
 *
 * ## 根因
 *
 * `probeWithRetry` 旧实现**只在 `supportsRange == true` 时提前返回**。
 * 当服务器**确定性地不支持 Range**（200 + Content-Length + 无 `Accept-Ranges`）时，
 * 它把这当成"失败"继续跑满 `probeRetries`，而每一轮又发 **2 次**请求
 * （`bytes=0-0` + `bytes=0-` 复探）再加指数退避 —— 结论却永远不会变。
 *
 * 而网盘链接因为调用方**已知文件大小**，命中 `skipProbeWhenSizeKnown = true` **跳过探测**，
 * 所以反而更快。这解释了"网盘快、普通直链慢"的反直觉现象。
 *
 * ## 本测试守什么
 *
 * - **不支持的服务器**：探测请求数必须 ≤ 2（一轮最小探测 + 一轮复探），
 *   绝不能随 `probeRetries` 线性放大；
 * - **HTML（网页）响应**：必须**立即**返回，不重试（OVH 类 hostile server 场景）。
 */
class ProbeFastFailTest {

    /** 恒定返回 200、带 Content-Length、**不带** Accept-Ranges —— 即"确定性不支持 Range"。 */
    private class NoRangeServer(private val payload: ByteArray) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0
        )
        val port: Int get() = server.address.port
        val requests = AtomicInteger(0)
        val rangeRequests = AtomicInteger(0)

        init {
            server.createContext("/f.bin") { ex: HttpExchange ->
                requests.incrementAndGet()
                if (ex.requestHeaders.getFirst("Range") != null) rangeRequests.incrementAndGet()
                // 故意：忽略 Range，永远回整文件 200，且不带 Accept-Ranges
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.use { it.write(payload) }
            }
            server.executor = Executors.newFixedThreadPool(8)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    /** 恒定返回 HTML —— 模拟反爬/区域拦截（如 OVH 对中国来源）。 */
    private class HtmlServer {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        val requests = AtomicInteger(0)
        private val html = "<html><body>Access Denied</body></html>".toByteArray()

        init {
            server.createContext("/f.bin") { ex: HttpExchange ->
                requests.incrementAndGet()
                ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                ex.sendResponseHeaders(200, html.size.toLong())
                ex.responseBody.use { it.write(html) }
            }
            server.executor = Executors.newFixedThreadPool(8)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    @Test
    fun `server without range support is not probed repeatedly`() = runBlocking {
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        val srv = NoRangeServer(payload)
        // probeRetries 设成 3：旧实现会发 (3+1) 轮 × 2 次 = 8 次请求；
        // 修复后应当只发 2 次（一轮最小探测 + 一轮 bytes=0- 复探）就定性返回。
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 2,
                maxConcurrentTasks = 1,
                probeRetries = 3,
                warmUpConnections = false,
            )
        )
        val out = File.createTempFile("probeff", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
            // 不支持 Range → 引擎会退化为整文件单流，这是正确行为
            client.await(id)
            println("[PROBE-FF] 不支持 Range 的服务器：总请求=${srv.requests.get()}（其中带 Range 的 ${srv.rangeRequests.get()}）")
            assertTrue(
                srv.requests.get() <= 4,
                "不支持 Range 属**确定性结论**，不应随 probeRetries 放大。" +
                    "实际 ${srv.requests.get()} 次（probeRetries=3 时旧实现约需 8 次）"
            )
            assertTrue(payload.contentEquals(out.readBytes()), "退化单流下载仍需字节精确")
        } finally {
            client.shutdown(); srv.stop(); out.delete()
        }
    }

    /** 健康服务器：正常支持 Range（206 + Content-Range）。 */
    private class RangeServer(private val payload: ByteArray) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        /** 只统计**最小探测**（bytes=0-0）次数，用于验证快路径没有退化。 */
        val minProbes = AtomicInteger(0)
        /** 复探（bytes=0-）次数；健康服务器**不应**发生复探。 */
        val reprobes = AtomicInteger(0)

        init {
            server.createContext("/f.bin") { ex: HttpExchange ->
                val range = ex.requestHeaders.getFirst("Range")
                ex.responseHeaders.add("Content-Type", "application/octet-stream")
                if (range == "bytes=0-0") minProbes.incrementAndGet()
                if (range == "bytes=0-") reprobes.incrementAndGet()
                val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                if (m == null) {
                    ex.sendResponseHeaders(200, payload.size.toLong())
                    ex.responseBody.use { it.write(payload) }
                    return@createContext
                }
                val s = m.groupValues[1].toInt()
                val e = m.groupValues[2].toIntOrNull() ?: (payload.size - 1)
                val end = minOf(e, payload.size - 1)
                val len = end - s + 1
                ex.responseHeaders.add("Content-Range", "bytes $s-$end/${payload.size}")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { it.write(payload, s, len) }
            }
            server.executor = Executors.newFixedThreadPool(16)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    @Test
    fun `healthy range server is probed exactly once`() = runBlocking {
        val payload = ByteArray(256 * 1024) { (it % 241).toByte() }
        val srv = RangeServer(payload)
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 4,
                maxConcurrentTasks = 1,
                probeRetries = 3,
                warmUpConnections = false,
                slowStart = false,
            )
        )
        val out = File.createTempFile("probeok", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
            assertTrue(client.await(id).isSuccess)
            assertEquals(payload.size.toLong(), out.length())
            println("[PROBE-FF] 健康服务器：最小探测=${srv.minProbes.get()} 复探=${srv.reprobes.get()}")
            // 快路径守卫：206 一次就定性，**不得**再复探、更不得重试。
            // 这次修「解析慢」时最容易犯的错就是把所有服务器都拖慢 —— 本断言专门钉住它。
            assertEquals(1, srv.minProbes.get(), "健康服务器应恰好探测 1 次")
            assertEquals(0, srv.reprobes.get(), "已确认支持 Range 就不该再复探")
        } finally {
            client.shutdown(); srv.stop(); out.delete()
        }
    }

    @Test
    fun `html response fails fast without retrying`() = runBlocking {
        val srv = HtmlServer()
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 2,
                maxConcurrentTasks = 1,
                probeRetries = 3,
                warmUpConnections = false,
            )
        )
        val out = File.createTempFile("probehtml", ".bin").apply { deleteOnExit() }
        try {
            val t0 = System.currentTimeMillis()
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
            val res = client.await(id)
            val elapsed = System.currentTimeMillis() - t0
            println("[PROBE-FF] HTML 响应：请求=${srv.requests.get()} 耗时=${elapsed}ms 成功=${res.isSuccess}")
            assertTrue(res.isFailure, "返回 HTML 说明不是可下载文件，应当失败并给出原因")
            assertTrue(
                srv.requests.get() <= 4,
                "HTML 属**确定性结论**，不应重试放大。实际 ${srv.requests.get()} 次"
            )
        } finally {
            client.shutdown(); srv.stop(); out.delete()
        }
    }
}

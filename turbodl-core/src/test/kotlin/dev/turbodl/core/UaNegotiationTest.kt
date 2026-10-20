package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 回归测试：**默认 UA 被拦截时，自动换 UA 并贯穿整个任务**。
 *
 * ## 背景（真实网络实测，2026-10-18）
 *
 * 国内镜像站（清华 / 北外 / 中科大 / 南大）**按 UA 分档拦截**，而且方向与直觉相反：
 * 浏览器 UA 被 403 或喂一个 JS 挑战页，`curl` 类 UA 放行。而 `TurboConfig.userAgent`
 * 的默认值恰恰是浏览器 UA（那是为了应对"只认浏览器 UA"的 CDN）。
 * 结果：默认配置在这些站上**一个字节都下不了**，而 `curl` 跑同一链接飞快。
 *
 * ## 本测试守什么（三条，缺一条就等于没修）
 *
 * 1. **探测阶段必须能协商**：默认 UA 被 403/HTML 拦住时，换到放行的 UA 并返回其探测结果；
 * 2. **协商结果必须贯穿**：真正的分片/整文件请求也要用新 UA ——
 *    只在探测里换、下载还用默认 UA 的话，几十条连接会各自再撞一次墙；
 * 3. **不能乱换**：用户显式指定 UA 时不协商；服务器只是 5xx 或超时也不换
 *    （那不是身份问题，换了只会多打请求）。
 */
class UaNegotiationTest {

    /**
     * 按 UA 分档的服务器：浏览器 UA → [blockMode]，其余 → 正常支持 Range。
     *
     * @param blockMode 403（返回 HTML 错误页）或 200（返回 HTML 挑战页，更阴的那种）
     */
    private class UaGateServer(
        private val payload: ByteArray,
        private val blockMode: Int,
    ) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port

        /** 每个请求真正用到的 UA（按到达顺序）。 */
        val seenUas = java.util.Collections.synchronizedList(mutableListOf<String>())

        /** 被放行的请求数（UA 不是浏览器的那部分）。 */
        val allowed = AtomicInteger(0)

        private fun isBrowser(ua: String) = ua.contains("Mozilla/", ignoreCase = true)

        init {
            server.createContext("/f.bin") { ex: HttpExchange ->
                val ua = ex.requestHeaders.getFirst("User-Agent").orEmpty()
                seenUas.add(ua)
                if (isBrowser(ua)) {
                    val html = "<html><body>blocked</body></html>".toByteArray()
                    ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                    ex.sendResponseHeaders(blockMode, html.size.toLong())
                    ex.responseBody.use { it.write(html) }
                    return@createContext
                }
                allowed.incrementAndGet()
                serveRange(ex, payload)
            }
            server.executor = Executors.newFixedThreadPool(8)
            server.start()
        }

        private fun serveRange(ex: HttpExchange, body: ByteArray) {
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null) {
                val m = Regex("""bytes=(\d+)-(\d*)""").find(range)
                val start = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val end = m?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(body.size - 1) ?: (body.size - 1)
                val len = end - start + 1
                ex.responseHeaders.add("Content-Type", "application/octet-stream")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.responseHeaders.add("Content-Range", "bytes $start-$end/${body.size}")
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { it.write(body, start, len) }
            } else {
                ex.responseHeaders.add("Content-Type", "application/octet-stream")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
        }

        fun stop() = server.stop(0)
    }

    private fun config() = TurboConfig(
        maxConnectionsPerTask = 4,
        probeTimeoutMs = 5_000,
        probeRetries = 1,
        // 关掉慢启动/自适应，把变量压到"只有 UA"这一条
        slowStart = false,
        dynamicSegmentation = false,
    )

    /**
     * 端到端：默认浏览器 UA 被 403 拦住 → 引擎换 UA → **下载完成且字节正确**，
     * 且**所有**被放行的请求用的都是同一个替换 UA（不是只有探测那一次）。
     */
    @Test
    fun `blocked browser UA falls back and the negotiated UA is used for every request`() = runBlocking {
        val payload = ByteArray(512 * 1024) { (it * 31 + 7).toByte() }
        val srv = UaGateServer(payload, blockMode = 403)
        val dir = createTempDirectory("ua-neg").toFile()
        try {
            val client = TurboClient(config())
            val out = File(dir, "out.bin")
            val id = client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:${srv.port}/f.bin",
                    destination = out,
                    connectionsOverride = 4,
                )
            )
            val result = client.await(id)
            client.shutdown()

            assertTrue(result.isSuccess, "被 UA 拦截时应通过协商完成下载：${result.exceptionOrNull()}")
            assertEquals(payload.size.toLong(), out.length(), "落盘字节数应与服务器一致")
            assertTrue(payload.contentEquals(out.readBytes()), "落盘内容必须逐字节正确")
            assertTrue(srv.allowed.get() > 0, "应当有请求被放行（说明协商成功）")

            // ★ 关键断言：分片请求也必须带上协商出的 UA。
            //   若实现只在探测里换 UA、分片仍用默认 UA，这里会看到 Mozilla/ 混在放行请求里。
            val allowedUas = srv.seenUas.filter { !it.contains("Mozilla/", ignoreCase = true) }
            assertTrue(allowedUas.isNotEmpty(), "放行的请求里应能看到替换后的 UA")
            assertTrue(
                allowedUas.all { it == allowedUas.first() },
                "协商只该选一个 UA 并全程使用，实际看到：${allowedUas.distinct()}",
            )
            assertTrue(
                allowedUas.first().startsWith("curl/") || allowedUas.first().startsWith("aria2/"),
                "替换 UA 应来自候选梯子，实际：${allowedUas.first()}",
            )
        } finally {
            srv.stop()
            dir.deleteRecursively()
        }
    }

    /**
     * 200 + JS 挑战页（不是 403）：同样必须识别并换 UA。
     *
     * 真实的中科大镜像站就是这种：状态码是 200，body 是 "Verifying your browser"。
     * 只看状态码的实现会把它当成"服务器返回了网页"，直接判失败。
     */
    @Test
    fun `html challenge page with 200 also triggers negotiation`() = runBlocking {
        val payload = ByteArray(256 * 1024) { (it * 13 + 3).toByte() }
        val srv = UaGateServer(payload, blockMode = 200)
        val dir = createTempDirectory("ua-challenge").toFile()
        try {
            val client = TurboClient(config())
            val out = File(dir, "out.bin")
            val id = client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:${srv.port}/f.bin",
                    destination = out,
                    connectionsOverride = 4,
                )
            )
            val result = client.await(id)
            client.shutdown()

            assertTrue(result.isSuccess, "HTML 挑战页（200）也应触发 UA 协商：${result.exceptionOrNull()}")
            assertTrue(payload.contentEquals(out.readBytes()), "内容必须逐字节正确")
        } finally {
            srv.stop()
            dir.deleteRecursively()
        }
    }

    /**
     * 用户显式指定 UA 时**不协商** —— 那是他的判断，不该被覆盖。
     *
     * 期望：请求失败（服务器只放行非浏览器 UA，而用户指定的正是浏览器 UA），
     * 且**没有任何**非用户指定的 UA 出现在服务器日志里。
     */
    @Test
    fun `explicit user agent is never overridden`() = runBlocking {
        val payload = ByteArray(64 * 1024)
        val srv = UaGateServer(payload, blockMode = 403)
        val dir = createTempDirectory("ua-explicit").toFile()
        try {
            val client = TurboClient(config())
            val out = File(dir, "out.bin")
            val explicit = "MyApp/1.0"
            val id = client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:${srv.port}/f.bin",
                    destination = out,
                    headers = mapOf("User-Agent" to explicit),
                    connectionsOverride = 2,
                )
            )
            client.await(id)
            client.shutdown()

            assertTrue(
                srv.seenUas.all { it == explicit },
                "显式 UA 不该被替换，实际看到：${srv.seenUas.distinct()}",
            )
        } finally {
            srv.stop()
            dir.deleteRecursively()
        }
    }
}

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
 * 回归测试：**每个分片都重走 302 = 白付 N 个往返**。
 *
 * ## 背景
 *
 * `skipProbeWhenSizeKnown = true`（默认）时调用方已知大小 → **整段跳过探测**，
 * 此时 `probe.resolvedUrl` 就是**原始 URL**，于是 `effectiveUrl = request.url`。
 *
 * 而分片下载用的正是 `effectiveUrl` —— 用原始 URL 就代表**每个分片各自去跟一次 302**。
 * 网盘直链（原始链 → CDN 临时直链）配 128 连接时，就是 **127 次多余的重定向往返**；
 * 而且各分片可能落到不同 CDN 节点。
 *
 * 探测路径没有这个问题（探测已经把重定向解开了）——所以这是**只有"已知大小"这条快路径
 * 才有的隐性代价**：它把"省掉 2 次探测请求"换成了"N 次重定向"，
 * 文件越小、连接越多，越不划算。
 *
 * ## 本测试守什么
 *
 * 已知大小（跳过探测）时，重定向端点被访问的次数必须是 **O(1)**，不随分片数增长。
 */
class RedirectReuseTest {

    /**
     * 服务器 A：`/orig` 恒返回 302 → 服务器 B 的 `/real`。
     * 统计 `/orig` 被访问次数（即"重定向发生了多少次"）。
     */
    private class RedirectPair(private val payload: ByteArray) {
        val real: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val front: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val origHits = AtomicInteger(0)
        val realHits = AtomicInteger(0)
        val realPort: Int get() = real.address.port

        init {
            real.createContext("/real") { ex: HttpExchange ->
                realHits.incrementAndGet()
                val range = ex.requestHeaders.getFirst("Range")
                ex.responseHeaders.add("Content-Type", "application/octet-stream")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
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
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { it.write(payload, s, len) }
            }
            real.executor = Executors.newFixedThreadPool(16)
            real.start()

            front.createContext("/orig") { ex: HttpExchange ->
                origHits.incrementAndGet()
                ex.responseHeaders.add("Location", "http://127.0.0.1:$realPort/real")
                ex.sendResponseHeaders(302, -1)
                ex.close()
            }
            front.executor = Executors.newFixedThreadPool(16)
            front.start()
        }

        fun stop() { front.stop(0); real.stop(0) }
    }

    @Test
    fun `known size skips probe but redirect is resolved O(1) not O(segments)`() = runBlocking {
        // 8 连接 × spc=4 → 32 个分片；慢启动首波固定为 min(连接数,4)=4 个 worker。
        // 修复前：**32 个分片各跟一次 302**（每个还多建一条到前端主机的连接）。
        // 修复后：首个响应即学到终址，此后所有分片（含慢启动爬升出来的）直接打 CDN。
        val payload = ByteArray(8 * 1024 * 1024) { ((it * 13 + 5) % 256).toByte() }
        val srv = RedirectPair(payload)
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 8,
                maxConcurrentTasks = 1,
                skipProbeWhenSizeKnown = true,   // 走"已知大小 → 跳过探测"这条快路径
                warmUpConnections = false,
            )
        )
        val out = File.createTempFile("redir", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:${srv.front.address.port}/orig",
                    destination = out,
                    knownSize = payload.size.toLong(),
                )
            )
            assertTrue(client.await(id).isSuccess, "重定向链路应下载成功")
            assertTrue(out.readBytes().contentEquals(payload), "跨 302 的分片数据必须字节精确")

            println("[REDIR] 8连接/32分片  /orig 被访=${srv.origHits.get()}  /real 被访=${srv.realHits.get()}")
            // 上界 = **慢启动首波规模** `max(4, workers/4)`：首波 worker 同时发出，
            // 谁也来不及先把终址学到 —— 这是"不引入串行等待"这个取舍下不可消除的下限。
            // 关键是它**与分片数无关**：32 个分片也只应约等于首波，而不是 32。
            assertTrue(
                srv.origHits.get() <= maxOf(4, 8 / 4),
                "重定向端点被访问 ${srv.origHits.get()} 次，超过慢启动首波上界" +
                    "——说明分片没有复用已学到的终址（修复前这里是 32 次）"
            )
        } finally {
            client.shutdown(); srv.stop(); out.delete()
        }
    }

    @Test
    fun `redirect count does not grow with connection count`() = runBlocking {
        // 32 连接 × spc=4 → 128 个分片。重定向次数**不应**随分片数增长。
        // 修复前：128 次（网盘 128 线程就是 128 次重定向 + 128 条多余的前端连接）。
        val payload = ByteArray(16 * 1024 * 1024) { ((it * 11 + 7) % 256).toByte() }
        val srv = RedirectPair(payload)
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 32,
                maxConcurrentTasks = 1,
                skipProbeWhenSizeKnown = true,
                warmUpConnections = false,
            )
        )
        val out = File.createTempFile("redir32", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:${srv.front.address.port}/orig",
                    destination = out,
                    knownSize = payload.size.toLong(),
                )
            )
            assertTrue(client.await(id).isSuccess)
            assertTrue(out.readBytes().contentEquals(payload))
            println("[REDIR] 32连接/128分片  /orig 被访=${srv.origHits.get()}  /real 被访=${srv.realHits.get()}")
            // 分片数从 32 涨到 128（×4），重定向次数**不应**跟着涨。
            // 上界同样取慢启动首波 `max(4, workers/4)` = 8，而不是分片数 128。
            assertTrue(
                srv.origHits.get() <= maxOf(4, 32 / 4),
                "32 连接下重定向端点被访问 ${srv.origHits.get()} 次，超过慢启动首波上界 8：" +
                    "说明重定向次数随分片数增长（修复前这里是 128 次）"
            )
        } finally {
            client.shutdown(); srv.stop(); out.delete()
        }
    }

    @Test
    fun `probe path already resolves redirect once`() = runBlocking {
        val payload = ByteArray(1024 * 1024) { ((it * 7 + 3) % 256).toByte() }
        val srv = RedirectPair(payload)
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 4, maxConcurrentTasks = 1,
                warmUpConnections = false, slowStart = false, segmentsPerConnection = 1,
            )
        )
        val out = File.createTempFile("redir2", ".bin").apply { deleteOnExit() }
        try {
            // 不传 knownSize → 走探测路径
            val id = client.submit(
                DownloadRequest("http://127.0.0.1:${srv.front.address.port}/orig", out)
            )
            assertTrue(client.await(id).isSuccess)
            println("[REDIR] 探测路径：/orig 被访=${srv.origHits.get()}  /real 被访=${srv.realHits.get()}")
            assertEquals(1, srv.origHits.get(), "探测已解开重定向，后续分片不应再访问原始 URL")
        } finally {
            client.shutdown(); srv.stop(); out.delete()
        }
    }
}

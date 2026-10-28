package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真实网络里那些"不干净"的情况，引擎必须自己吸收掉。
 *
 * 这一组不测峰值速度，测的是**用户能不能顺利拿到正确的文件**：
 * 服务器间歇 5xx、随机掐断连接、重定向跳转、高并发、续传后立刻恢复速度。
 *
 * 每条都以 **SHA-256 比对**收尾 —— 只看大小抓不住"新旧混杂"这类静默损坏。
 */
class DownloadStabilityTest {

    private companion object {
        const val SIZE = 12 * 1024 * 1024
    }

    private fun payload(seed: Int): ByteArray = ByteArray(SIZE) { ((it * seed + 29) % 251).toByte() }

    private fun sha(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun shaOf(f: File): String = sha(f.readBytes())

    /** 可编程的 Range 服务器：每个请求由一个回调决定怎么答。 */
    private class Prog(
        private val body: ByteArray,
        private val handler: (n: Int, ex: HttpExchange, s: Int, end: Int) -> Unit,
    ) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        val requests = AtomicInteger(0)
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val writeFailures = AtomicInteger(0)

        init {
            server.createContext("/") { ex: HttpExchange ->
                val n = requests.incrementAndGet()
                maxActive.accumulateAndGet(active.incrementAndGet()) { a, b -> if (a > b) a else b }
                val range = ex.requestHeaders.getFirst("Range")
                val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                val s = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val end = m?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(body.size - 1)
                    ?: (body.size - 1)
                // 【必须关 exchange】sun HttpServer 要求 handler 显式 close()：
                // 不关的话 keep-alive 连接上的响应不会被标记结束，客户端读响应体时
                // 会一直等到读超时（实测 128 连接下表现为「分片重试 6 次仍失败（读取超时）」，
                // 而服务器端 writeFail=0、handler 也早就返回了）。
                try { handler(n, ex, s, end) } finally { active.decrementAndGet(); runCatching { ex.close() } }
            }
            server.executor = Executors.newFixedThreadPool(128)
            server.start()
        }

        fun stop() = server.stop(0)

        /** 正常回一段 206。 */
        fun ok(ex: HttpExchange, s: Int, end: Int) {
            val len = end - s + 1
            ex.responseHeaders.add("Content-Type", "application/octet-stream")
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            ex.responseHeaders.add("Content-Range", "bytes $s-$end/${body.size}")
            ex.sendResponseHeaders(206, len.toLong())
            try { ex.responseBody.write(body, s, len) } catch (e: Throwable) { writeFailures.incrementAndGet() }
        }
    }

    private fun workDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "st-$tag-${System.nanoTime()}")
            .apply { mkdirs(); deleteOnExit() }

    private fun cfg(dir: File, conns: Int) = TurboConfig(
        maxConnectionsPerTask = conns,
        maxConcurrentTasks = 1,
        workDir = dir,
        warmUpConnections = false,
        slowStart = false,
    )

    // ---------------------------------------------------------------- 1
    /**
     * 间歇 5xx：每 5 个请求有 2 个返回 500。
     *
     * 引擎必须重试并最终完成，且内容正确。真实 CDN 的边缘节点抖动就是这个形态。
     */
    @Test
    fun `间歇 5xx 被重试吸收 最终内容正确`() = runBlocking {
        val data = payload(13)
        lateinit var srv: Prog
        srv = Prog(data) { n, ex, s, end ->
            if (n % 5 == 2 || n % 5 == 4) {
                ex.sendResponseHeaders(500, -1); ex.close()
            } else {
                srv.ok(ex, s, end)
            }
        }
        val dir = workDir("5xx")
        val out = File.createTempFile("st1", ".bin").apply { deleteOnExit() }
        try {
            val c = TurboClient(cfg(dir, 4))
            val r = c.await(c.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out)))
            c.shutdown()
            assertTrue(r.isSuccess, "间歇 5xx 不该让任务失败：${r.exceptionOrNull()?.message}")
            assertEquals(sha(data), shaOf(out), "内容必须一致")
            assertTrue(srv.requests.get() > 10, "应当发生过重试（请求数 ${srv.requests.get()}）")
        } finally {
            runCatching { srv.stop() }; runCatching { dir.deleteRecursively() }; runCatching { out.delete() }
        }
    }

    // ---------------------------------------------------------------- 2
    /**
     * 随机掐断：每 4 个请求有 1 个只发一半就断。
     *
     * 断在**响应体中间**，客户端会拿到"字节不够"的 EOF。必须按分片重试吸收。
     */
    @Test
    fun `随机中途断连 被分片重试吸收`() = runBlocking {
        val data = payload(19)
        val srv = Prog(data) { n, ex, s, end ->
            val len = end - s + 1
            val truncate = n <= 3
            ex.responseHeaders.add("Content-Type", "application/octet-stream")
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            ex.responseHeaders.add("Content-Range", "bytes $s-$end/${data.size}")
            if (truncate) ex.responseHeaders.add("Connection", "close")
            ex.sendResponseHeaders(206, len.toLong())
            runCatching {
                ex.responseBody.write(data, s, if (truncate) len / 2 else len)
            }
        }
        val dir = workDir("cut")
        val out = File.createTempFile("st2", ".bin").apply { deleteOnExit() }
        try {
            val c = TurboClient(cfg(dir, 4).copy(readTimeoutMs = 3_000, stallTimeoutMs = 3_000))
            val r = c.await(c.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out)))
            c.shutdown()
            assertTrue(r.isSuccess, "随机断连不该让任务失败：${r.exceptionOrNull()?.message}")
            assertEquals(sha(data), shaOf(out), "内容必须一致")
        } finally {
            runCatching { srv.stop() }; runCatching { dir.deleteRecursively() }; runCatching { out.delete() }
        }
    }

    // ---------------------------------------------------------------- 3
    /** 302 跳到另一个路径：必须学习到最终地址，后续分片直接打它（不再每片跳一次）。 */
    @Test
    fun `重定向后 后续分片直接用最终地址`() = runBlocking {
        val data = payload(23)
        val hops = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex: HttpExchange ->
            val path = ex.requestURI.path
            if (path.startsWith("/redir")) {
                hops.incrementAndGet()
                ex.responseHeaders.add("Location", "/real")
                ex.sendResponseHeaders(302, -1); ex.close()
                return@createContext
            }
            val range = ex.requestHeaders.getFirst("Range")
            val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
            if (m == null) {
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(200, data.size.toLong())
                runCatching { ex.responseBody.write(data) }
                return@createContext
            }
            val s = m.groupValues[1].toInt()
            val end = (m.groupValues[2].toIntOrNull() ?: (data.size - 1)).coerceAtMost(data.size - 1)
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            ex.responseHeaders.add("Content-Range", "bytes $s-$end/${data.size}")
            ex.sendResponseHeaders(206, (end - s + 1).toLong())
            runCatching { ex.responseBody.write(data, s, end - s + 1) }
        }
        server.executor = Executors.newFixedThreadPool(32)
        server.start()
        val dir = workDir("redirect")
        val out = File.createTempFile("st3", ".bin").apply { deleteOnExit() }
        try {
            val c = TurboClient(cfg(dir, 8))
            val r = c.await(c.submit(DownloadRequest("http://127.0.0.1:${server.address.port}/redir/f.bin", out)))
            c.shutdown()
            assertTrue(r.isSuccess, "重定向后应能下完：${r.exceptionOrNull()?.message}")
            assertEquals(sha(data), shaOf(out), "内容必须一致")
            // 64 分片全走 302 的话 hops 会接近分片数；学习终址后只应当有个位数。
            assertTrue(hops.get() <= 4, "重定向发生了 ${hops.get()} 次 —— 终址没有被复用")
        } finally {
            runCatching { server.stop(0) }; runCatching { dir.deleteRecursively() }; runCatching { out.delete() }
        }
    }

    // ---------------------------------------------------------------- 4
    /** 128 连接：高并发下的完整性（分片越碎越容易在边界上出错）。 */
    @Test
    fun `高并发下内容仍然完整`() = runBlocking {
        val data = payload(31)
        lateinit var srv: Prog
        srv = Prog(data) { _, ex, s, end -> srv.ok(ex, s, end) }
        val dir = workDir("many")
        val out = File.createTempFile("st4", ".bin").apply { deleteOnExit() }
        try {
            val c = TurboClient(cfg(dir, 128))
            val r = c.await(c.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out)))
            c.shutdown()
            assertTrue(
                r.isSuccess,
                "128 连接应能下完：${r.exceptionOrNull()?.message} | srv: req=${srv.requests.get()} " +
                    "active=${srv.active.get()} maxActive=${srv.maxActive.get()} writeFail=${srv.writeFailures.get()}"
            )
            assertEquals(sha(data), shaOf(out), "高并发下内容必须一致")
            println("[high-concurrency] 请求数=${srv.requests.get()}")
        } finally {
            runCatching { srv.stop() }; runCatching { dir.deleteRecursively() }; runCatching { out.delete() }
        }
    }

    // ---------------------------------------------------------------- 5
    /**
     * 续传后**立刻**满速：不应再从 4 条连接慢慢爬。
     *
     * 用户点"继续"期待的是马上恢复原来的速度。慢启动防的是"第一次见面"，
     * 而续传时这些连接上一轮已经被服务器接受过。
     */
    @Test
    fun `续传后立刻使用全部连接`() = runBlocking {
        val data = payload(37)
        lateinit var srv: Prog
        srv = Prog(data) { _, ex, s, end ->
            // 每个响应慢一点，让并发能被采样到
            Thread.sleep(15)
            srv.ok(ex, s, end)
        }
        val dir = workDir("faststart")
        val key = "k-fast"
        val out = File.createTempFile("st5", ".bin").apply { deleteOnExit() }
        val url = "http://127.0.0.1:${srv.port}/f.bin"
        try {
            val c1 = TurboClient(cfg(dir, 16).copy(slowStart = true))
            val id = c1.submit(DownloadRequest(url, out, stableKey = key))
            val dl = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < dl) {
                val p = c1.progress.value[id]
                if ((p?.downloadedBytes ?: 0) >= SIZE / 5L) break
                delay(10)
            }
            c1.pause(id)
            var w = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < w && c1.progress.value[id]?.state != TaskState.PAUSED) delay(10)
            c1.shutdown()

            // 续传：slowStart 打开，但因为是续传，应当直接满并发
            val c2 = TurboClient(cfg(dir, 16).copy(slowStart = true, progressIntervalMs = 0))
            val id2 = c2.submit(DownloadRequest(url, out, stableKey = key))
            var peak = 0
            val poller = launch {
                while (true) {
                    val a = c2.progress.value[id2]?.activeConnections ?: 0
                    if (a > peak) peak = a
                    delay(10)
                }
            }
            val r = c2.await(id2)
            poller.cancel()
            c2.shutdown()
            assertTrue(r.isSuccess, "续传应成功：${r.exceptionOrNull()?.message}")
            assertEquals(sha(data), shaOf(out), "内容必须一致")
            println("[resume-faststart] 续传期间观测到的峰值并发=$peak")
            assertTrue(peak >= 8, "续传后并发只有 $peak，没有直接起满（期望接近 16）")
        } finally {
            runCatching { srv.stop() }; runCatching { dir.deleteRecursively() }; runCatching { out.delete() }
        }
    }
}

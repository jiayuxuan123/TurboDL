package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **断点续传的完整性**，以及几类会直接影响体感的异常路径。
 *
 * ## 为什么把"续传"当成一等公民来测
 *
 * 续传出错的表现是**静默的**：文件看起来下完了、大小也对，内容却是新旧混杂的。
 * 所以这一组用例全部以 **SHA-256 比对**收尾 —— 只看长度是抓不住这类问题的
 * （历史上就踩过"长度正确、SHA 不符"）。
 *
 * 另一个方向是"续传失效"：用户暂停后继续，结果从头再下一遍。它不报错、也不损坏文件，
 * 只是白费流量和时间 —— 同样只能靠**服务端实际发出的字节数**来判定。
 *
 * ## 覆盖的路径
 *
 * 1. 暂停 → 继续：不重下已完成的区间，最终内容一致
 * 2. **客户端被直接丢弃**（模拟进程被杀，没有走 pause）：下次仍能从磁盘进度接着下
 * 3. 续传时**连接数变了**：分片网格必须沿用旧的，否则进度对不上
 * 4. **目标文件被删**：必须干净重来，不能在空洞上"续传"
 * 5. **侧车损坏**：不得崩溃，按全新任务处理
 * 6. **服务器换了同大小不同内容**：弱校验器下必须靠内容指纹拦下，整体重下
 * 7. 服务器不支持 Range：整文件单流仍要正确落盘
 */
class ResumeIntegrityTest {

    private companion object {
        const val SIZE = 12 * 1024 * 1024
    }

    private fun payload(seed: Int): ByteArray = ByteArray(SIZE) { ((it * seed + 17) % 251).toByte() }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun shaOf(f: File): String = sha(f.readBytes())

    /** 支持 Range 的服务器；可指定 ETag、是否忽略 Range、每块延迟。 */
    private class Srv(
        private val body: ByteArray,
        private val etag: String? = null,
        private val ignoreRange: Boolean = false,
        private val chunkDelayMs: Long = 0,
    ) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        /** 实际发出的正文字节数 —— 用来判断"有没有重下已经下的部分"。 */
        val bytesSent = java.util.concurrent.atomic.AtomicLong(0)
        val requests = AtomicInteger(0)

        init {
            server.createContext("/f.bin") { ex: HttpExchange ->
                requests.incrementAndGet()
                val size = body.size
                val range = ex.requestHeaders.getFirst("Range")
                ex.responseHeaders.add("Content-Type", "application/octet-stream")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                etag?.let { ex.responseHeaders.add("ETag", it) }

                if (ignoreRange || range == null) {
                    ex.sendResponseHeaders(200, size.toLong())
                    ex.responseBody.use { out ->
                        out.write(body); bytesSent.addAndGet(size.toLong())
                    }
                    return@createContext
                }
                val m = Regex("bytes=(\\d+)-(\\d*)").find(range) ?: run {
                    ex.sendResponseHeaders(416, -1); ex.close(); return@createContext
                }
                val s = m.groupValues[1].toInt()
                val e = m.groupValues[2].toIntOrNull() ?: (size - 1)
                if (s >= size) { ex.sendResponseHeaders(416, -1); ex.close(); return@createContext }
                val end = minOf(e, size - 1)
                val len = end - s + 1
                ex.responseHeaders.add("Content-Range", "bytes $s-$end/$size")
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { out ->
                    // 分块写 + 可选延迟：让暂停能落在"写了一半"的时刻
                    var pos = s
                    while (pos <= end) {
                        val n = minOf(64 * 1024, end - pos + 1)
                        if (chunkDelayMs > 0) Thread.sleep(chunkDelayMs)
                        out.write(body, pos, n)
                        bytesSent.addAndGet(n.toLong())
                        pos += n
                    }
                }
            }
            server.executor = Executors.newFixedThreadPool(64)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    private fun workDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "ri-$tag-${System.nanoTime()}")
            .apply { mkdirs(); deleteOnExit() }

    private fun cfg(dir: File, conns: Int) = TurboConfig(
        maxConnectionsPerTask = conns,
        maxConcurrentTasks = 1,
        workDir = dir,
        warmUpConnections = false,
        slowStart = false,
    )

    private suspend fun waitBytes(client: TurboClient, id: Long, atLeast: Long, timeoutMs: Long = 20_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val p = client.progress.value[id]
            if ((p?.downloadedBytes ?: 0L) >= atLeast) return
            if (p?.state == TaskState.COMPLETED || p?.state == TaskState.FAILED) return
            delay(10)
        }
    }

    private suspend fun waitPaused(client: TurboClient, id: Long) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (client.progress.value[id]?.state == TaskState.PAUSED) return
            delay(10)
        }
        error("暂停未生效")
    }

    // ------------------------------------------------------------------ 1
    @Test
    fun `暂停后继续 不重下已完成部分 且内容一致`() = runBlocking {
        val data = payload(31)
        val srv = Srv(data, chunkDelayMs = 10)
        val dir = workDir("pause")
        val key = "k-pause"
        val out = File.createTempFile("ri1", ".bin").apply { deleteOnExit() }
        val client = TurboClient(cfg(dir, 8))
        try {
            val url = "http://127.0.0.1:${srv.port}/f.bin"
            val id1 = client.submit(DownloadRequest(url, out, stableKey = key))
            waitBytes(client, id1, SIZE / 4L)
            client.pause(id1)
            waitPaused(client, id1)

            val sentFirst = srv.bytesSent.get()
            assertTrue(sentFirst < SIZE, "暂停前不应已经下完（已发 $sentFirst）")

            srv.bytesSent.set(0)
            val id2 = client.submit(DownloadRequest(url, out, stableKey = key))
            val r = client.await(id2)
            assertTrue(r.isSuccess, "续传应成功：${r.exceptionOrNull()?.message}")

            val sentSecond = srv.bytesSent.get()
            assertEquals(sha(data), shaOf(out), "最终内容必须与源一致")
            // 允许少量重叠（最后一个未完成分片会重下），但绝不能接近整包
            assertTrue(
                sentSecond < SIZE - sentFirst / 2,
                "第二次又发了 $sentSecond 字节，而已落盘约 $sentFirst —— 像是从头下",
            )
            println("[resume] 首次发=$sentFirst 续传发=$sentSecond 总计=${sentFirst + sentSecond}")
        } finally {
            runCatching { client.shutdown() }; runCatching { srv.stop() }
            runCatching { dir.deleteRecursively() }; runCatching { out.delete() }
        }
    }

    // ------------------------------------------------------------------ 2
    /**
     * **不走 pause**，直接把客户端丢掉（等价于进程被杀 / 应用被系统回收）。
     *
     * 这条最贴近真实：用户并不是每次都点"暂停"，更多是切走、后台被杀、或者干脆断网。
     * 只要侧车里的进度是完整的，下次提交就应该接着下。
     */
    @Test
    fun `客户端被直接丢弃后 下次仍能接着下`() = runBlocking {
        val data = payload(47)
        val srv = Srv(data, chunkDelayMs = 10)
        val dir = workDir("kill")
        val key = "k-kill"
        val out = File.createTempFile("ri2", ".bin").apply { deleteOnExit() }
        val url = "http://127.0.0.1:${srv.port}/f.bin"
        try {
            val c1 = TurboClient(cfg(dir, 8))
            val id = c1.submit(DownloadRequest(url, out, stableKey = key))
            waitBytes(c1, id, SIZE / 4L)
            // 模拟"进程被杀"：不 pause、不 await，直接丢弃客户端并取消它的协程作用域
            c1.shutdown()
            delay(300)
            val sentFirst = srv.bytesSent.get()

            srv.bytesSent.set(0)
            val c2 = TurboClient(cfg(dir, 8))
            val r = c2.await(c2.submit(DownloadRequest(url, out, stableKey = key)))
            c2.shutdown()

            assertTrue(r.isSuccess, "丢弃后重提应成功：${r.exceptionOrNull()?.message}")
            assertEquals(sha(data), shaOf(out), "内容必须一致")
            val sentSecond = srv.bytesSent.get()
            assertTrue(
                sentSecond < SIZE - sentFirst / 2,
                "重提后发了 $sentSecond，已落盘约 $sentFirst —— 进度没有被沿用",
            )
            println("[kill-resume] 首次发=$sentFirst 重提发=$sentSecond")
        } finally {
            runCatching { srv.stop() }
            runCatching { dir.deleteRecursively() }; runCatching { out.delete() }
        }
    }

    // ------------------------------------------------------------------ 3
    @Test
    fun `续传时连接数改变 仍沿用旧分片网格`() = runBlocking {
        val data = payload(59)
        val srv = Srv(data, chunkDelayMs = 10)
        val dir = workDir("conns")
        val key = "k-conns"
        val out = File.createTempFile("ri3", ".bin").apply { deleteOnExit() }
        val url = "http://127.0.0.1:${srv.port}/f.bin"
        try {
            val c1 = TurboClient(cfg(dir, 4))       // 先 4 连接暂停
            val id = c1.submit(DownloadRequest(url, out, stableKey = key))
            waitBytes(c1, id, SIZE / 4L)
            c1.pause(id); waitPaused(c1, id); c1.shutdown()

            val c2 = TurboClient(cfg(dir, 16))      // 再用 16 连接继续
            val r = c2.await(c2.submit(DownloadRequest(url, out, stableKey = key)))
            c2.shutdown()
            assertTrue(r.isSuccess, "换连接数续传应成功：${r.exceptionOrNull()?.message}")
            assertEquals(sha(data), shaOf(out), "网格沿用后内容必须一致")
        } finally {
            runCatching { srv.stop() }
            runCatching { dir.deleteRecursively() }; runCatching { out.delete() }
        }
    }

    // ------------------------------------------------------------------ 4
    @Test
    fun `目标文件被删后 必须干净重来`() = runBlocking {
        val data = payload(71)
        val srv = Srv(data)
        val dir = workDir("gone")
        val key = "k-gone"
        val out = File.createTempFile("ri4", ".bin").apply { deleteOnExit() }
        val url = "http://127.0.0.1:${srv.port}/f.bin"
        try {
            val c1 = TurboClient(cfg(dir, 4))
            val id = c1.submit(DownloadRequest(url, out, stableKey = key))
            waitBytes(c1, id, SIZE / 4L)
            c1.pause(id); waitPaused(c1, id); c1.shutdown()
            assertTrue(out.delete(), "前提：目标文件确实被删")

            srv.bytesSent.set(0)
            val c2 = TurboClient(cfg(dir, 4))
            val r = c2.await(c2.submit(DownloadRequest(url, out, stableKey = key)))
            c2.shutdown()
            assertTrue(r.isSuccess, "应成功：${r.exceptionOrNull()?.message}")
            assertEquals(sha(data), shaOf(out), "内容必须一致（不能把旧进度用在空洞上）")
            assertTrue(
                srv.bytesSent.get() >= SIZE,
                "目标文件没了，就必须重新下满 ${SIZE} 字节（实际 ${srv.bytesSent.get()}）",
            )
        } finally {
            runCatching { srv.stop() }
            runCatching { dir.deleteRecursively() }; runCatching { out.delete() }
        }
    }

    // ------------------------------------------------------------------ 5
    @Test
    fun `侧车损坏不崩溃 按全新任务处理`() = runBlocking {
        val data = payload(83)
        val srv = Srv(data)
        val dir = workDir("corrupt")
        val key = "k-corrupt"
        val out = File.createTempFile("ri5", ".bin").apply { deleteOnExit() }
        val url = "http://127.0.0.1:${srv.port}/f.bin"
        try {
            val c1 = TurboClient(cfg(dir, 4))
            val id = c1.submit(DownloadRequest(url, out, stableKey = key))
            waitBytes(c1, id, SIZE / 4L)
            c1.pause(id); waitPaused(c1, id); c1.shutdown()

            // 把侧车写坏（模拟断电写了半截）
            val state = File(dir, "key_$key/progress.tdlp")
            if (state.isFile) state.writeBytes(byteArrayOf(9, 9, 9, 9, 1, 2, 3))

            val c2 = TurboClient(cfg(dir, 4))
            val r = c2.await(c2.submit(DownloadRequest(url, out, stableKey = key)))
            c2.shutdown()
            assertTrue(r.isSuccess, "侧车损坏时必须能自愈：${r.exceptionOrNull()?.message}")
            assertEquals(sha(data), shaOf(out), "内容必须一致")
        } finally {
            runCatching { srv.stop() }
            runCatching { dir.deleteRecursively() }; runCatching { out.delete() }
        }
    }

    // ------------------------------------------------------------------ 6
    /**
     * 服务器换了**同样大小、内容不同**的文件，且只给弱校验器（无 ETag）。
     *
     * 这是弱校验器唯一防不住的场景 —— 必须靠**内容指纹**（逐字节比对已写区间）拦住，
     * 整体重下。否则新旧字节混在一起，长度校验还会通过。
     */
    @Test
    fun `同大小换文件时 弱校验器靠内容指纹拦下`() = runBlocking {
        val old = payload(97)
        val fresh = payload(199)
        val dir = workDir("swap")
        val key = "k-swap"
        val out = File.createTempFile("ri6", ".bin").apply { deleteOnExit() }

        // 第一次：下旧文件的一部分
        val srvOld = Srv(old, chunkDelayMs = 10)
        try {
            val c1 = TurboClient(cfg(dir, 4))
            val id = c1.submit(
                DownloadRequest("http://127.0.0.1:${srvOld.port}/f.bin", out, stableKey = key)
            )
            waitBytes(c1, id, SIZE / 4L)
            c1.pause(id); waitPaused(c1, id); c1.shutdown()
        } finally {
            srvOld.stop()
        }

        // 第二次：同一个大小、不同内容，且没有 ETag
        val srvNew = Srv(fresh)
        try {
            val c2 = TurboClient(cfg(dir, 4))
            val r = c2.await(
                DownloadRequest("http://127.0.0.1:${srvNew.port}/f.bin", out, stableKey = key)
                    .let { c2.submit(it) }
            )
            c2.shutdown()
            assertTrue(r.isSuccess, "应成功：${r.exceptionOrNull()?.message}")
            assertEquals(sha(fresh), shaOf(out), "同一位置换了内容时必须整体重下，不能新旧混杂")
        } finally {
            runCatching { srvNew.stop() }
            runCatching { dir.deleteRecursively() }; runCatching { out.delete() }
        }
    }

    // ------------------------------------------------------------------ 7
    @Test
    fun `服务器不支持 Range 时 整文件单流仍正确`() = runBlocking {
        val data = payload(113)
        val srv = Srv(data, ignoreRange = true)
        val dir = workDir("norange")
        val out = File.createTempFile("ri7", ".bin").apply { deleteOnExit() }
        try {
            val c = TurboClient(cfg(dir, 8))
            val r = c.await(c.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out)))
            c.shutdown()
            assertTrue(r.isSuccess, "不支持 Range 也必须能下：${r.exceptionOrNull()?.message}")
            assertEquals(sha(data), shaOf(out), "内容必须一致")
        } finally {
            runCatching { srv.stop() }
            runCatching { dir.deleteRecursively() }; runCatching { out.delete() }
        }
    }

    // ------------------------------------------------------------------ 8
    /**
     * 分片中途断连：必须**只重试这一片**，且最终内容正确。
     *
     * 用一个会掐断前若干个连接的服务器模拟（真实 CDN 的常见行为）。
     */
    @Test
    fun `分片中途断连 只重试该片且内容一致`() = runBlocking {
        val data = payload(127)
        val size = data.size
        val cut = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/f.bin") { ex: HttpExchange ->
            val range = ex.requestHeaders.getFirst("Range")
            ex.responseHeaders.add("Content-Type", "application/octet-stream")
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
            if (m == null) {
                ex.sendResponseHeaders(200, size.toLong())
                ex.responseBody.use { it.write(data) }
                return@createContext
            }
            val s = m.groupValues[1].toInt()
            val e = m.groupValues[2].toIntOrNull() ?: (size - 1)
            val end = minOf(e, size - 1)
            val len = end - s + 1
            ex.responseHeaders.add("Content-Range", "bytes $s-$end/$size")
            // 前 3 个请求只发一半就断开
            val truncate = cut.incrementAndGet() <= 3
            val send = if (truncate) len / 2 else len
            if (truncate) ex.responseHeaders.add("Connection", "close")
            ex.sendResponseHeaders(206, len.toLong())
            try {
                ex.responseBody.write(data, s, send)
                ex.responseBody.flush()
            } catch (_: Exception) {
            }
            // 声明了 len 却只写一半：必须**显式关闭 exchange**，否则 sun HttpServer
            // 会把这条连接吊在半空（既不补完也不发 EOF），客户端只能等读超时。
            // 实测代价：3 个截断请求 → 每条毒化的 keep-alive 连接上后续请求各等 3s，
            // 整个恢复过程从 ~2s 涨到 39.5s（断言 <30s 直接失败），而且测的已经不是
            // "截断检测"而是"服务器挂住"。真实 CDN 掉线是**立即 EOF/RST**，不是吊着。
            runCatching { ex.close() }
        }
        server.executor = Executors.newFixedThreadPool(32)
        server.start()

        val dir = workDir("truncate")
        val out = File.createTempFile("ri8", ".bin").apply { deleteOnExit() }
        try {
            val c = TurboClient(
                cfg(dir, 4).copy(
                    // 短超时：断连必须**几秒内**被发现并重试，而不是干等到 45 秒的卡死判定。
                    // 这里也顺带验证超时配置真的生效（而不是写死在别处）。
                    readTimeoutMs = 3_000,
                    stallTimeoutMs = 3_000,
                )
            )
            val started = System.currentTimeMillis()
            val r = c.await(c.submit(DownloadRequest("http://127.0.0.1:${server.address.port}/f.bin", out)))
            val elapsed = System.currentTimeMillis() - started
            c.shutdown()
            assertTrue(r.isSuccess, "断连应被重试吸收：${r.exceptionOrNull()?.message}")
            assertTrue(elapsed < 30_000, "断连恢复用了 ${elapsed}ms，太慢")
            assertEquals(sha(data), shaOf(out), "断连重试后内容必须一致")
            assertTrue(cut.get() > 3, "应当发生了重试")
        } finally {
            runCatching { server.stop(0) }
            runCatching { dir.deleteRecursively() }; runCatching { out.delete() }
        }
    }
}

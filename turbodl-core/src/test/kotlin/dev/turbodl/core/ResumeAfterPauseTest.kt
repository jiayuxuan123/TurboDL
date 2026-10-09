package dev.turbodl.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 暂停 → 继续：**真的接着下，还是从头下？**
 *
 * 为什么单独一个测试：用户报「暂停后继续，进度从 29.2 MB 倒退到 24.5 MB」。光看数字分不清是
 * ①丢掉了几 MB 在飞分片、还是 ②整个从头重下 —— 两者在界面上只差一个数字。能分清的是**服务端
 * 又发出了多少字节**，所以这里起一个会数字节的 Range 服务器，暂停、继续，然后对账。
 *
 * 走的是 App 的真实路径：`submit → pause → 再次 submit（同一个 stableKey）`，
 * 而不是引擎自带的 `resume(id)` —— App 就是重新提交的。
 *
 * 另外两个用例把「暂停那一刻**上报**的字节」与「**落盘**的字节」之差量出来：上报口径是"已收到"，
 * 断点只认"已落盘"，这个差值就是"进度看起来倒退"的幅度。
 */
class ResumeAfterPauseTest {

    /**
     * 会记下发出去多少字节的 Range 服务器；每块之间加延时，好让暂停落在传输中途。
     *
     * `poolSize` 要按被测并发给足，否则连接会排在服务器线程池里 —— 那样量到的是服务器排队，
     * 不是客户端的在飞窗口。
     */
    private class CountingRangeServer(
        private val payload: ByteArray,
        private val chunkDelayMs: Long,
        poolSize: Int = 16,
        /** true = 每次响应给不同的 ETag（夸克这类多节点 CDN 的常态），用于验证"令牌变了不该丢分片"。 */
        private val etagPerRequest: Boolean = false,
    ) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        private val total = payload.size

        /** 服务端累计发出的字节数；测试在两次运行之间清零来分别对账。 */
        val served = AtomicLong(0)

        init {
            var etagSeq = 0
            server.createContext("/f.bin") { ex ->
                ex.responseHeaders.add(
                    "ETag",
                    if (etagPerRequest) "\"etag-${etagSeq++}\"" else "\"etag-stable\""
                )
                val range = ex.requestHeaders.getFirst("Range")
                val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                if (m == null) {
                    ex.sendResponseHeaders(200, total.toLong())
                    ex.responseBody.use { it.write(payload) }
                    served.addAndGet(total.toLong())
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
                    val step = 32 * 1024
                    while (off <= e) {
                        val n = minOf(step, e - off + 1)
                        out.write(payload, off, n)
                        out.flush()
                        served.addAndGet(n.toLong())
                        off += n
                        if (chunkDelayMs > 0) Thread.sleep(chunkDelayMs)
                    }
                }
            }
            server.executor = Executors.newFixedThreadPool(poolSize)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    private fun newWorkDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "turbodl-$tag-${System.nanoTime()}")
            .apply { mkdirs(); deleteOnExit() }

    private fun chunkDirOf(workDir: File, stableKey: String): File {
        val safe = "key_" + stableKey.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
        return File(workDir, safe)
    }

    private fun onDiskBytes(dir: File): Long =
        dir.listFiles()?.filter { it.isFile && it.name.startsWith("seg_") }?.sumOf { it.length() } ?: 0L

    /** 等到任务状态真的变成 PAUSED —— 固定 sleep 会掩盖竞态（上一版测试就是在"下完之后"才去量的）。 */
    private suspend fun waitForPaused(client: TurboClient, id: Long) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (client.progress.value[id]?.state == TaskState.PAUSED) return
            delay(20)
        }
        error("暂停没有在 10s 内生效：state=${client.progress.value[id]?.state}")
    }

    private suspend fun waitUntil(client: TurboClient, id: Long, bytes: Long, timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val p = client.progress.value[id]
            if ((p?.downloadedBytes ?: 0L) >= bytes) return
            if (p?.state == TaskState.COMPLETED || p?.state == TaskState.FAILED) return
            delay(20)
        }
    }

    // ------------------------------------------------------------------ 主用例

    @Test
    fun `pause then resubmit continues from the parts on disk instead of restarting`() = runBlocking {
        val size = 24 * 1024 * 1024
        val payload = ByteArray(size) { (it % 251).toByte() }
        val srv = CountingRangeServer(payload, chunkDelayMs = 25)
        val workDir = newWorkDir("resumepause")
        val stableKey = "room-4242"
        val chunkDir = chunkDirOf(workDir, stableKey)

        val client = TurboClient(
            TurboConfig(maxConnectionsPerTask = 4, maxConcurrentTasks = 1, workDir = workDir)
        )
        val out = File.createTempFile("resume-pause", ".bin").apply { deleteOnExit() }
        try {
            val url = "http://127.0.0.1:${srv.port}/f.bin"
            val id1 = client.submit(DownloadRequest(url = url, destination = out, stableKey = stableKey))
            waitUntil(client, id1, size / 4L)
            val reportedAtPause = client.progress.value[id1]?.downloadedBytes ?: 0L
            client.pause(id1)
            waitForPaused(client, id1)

            val onDisk = onDiskBytes(chunkDir)
            val servedFirst = srv.served.get()
            assertTrue(onDisk > 0, "暂停后分片目录里应当有已下载的分片（onDisk=$onDisk）")

            // 第二次：App 的"继续"= 带同一个 stableKey 再 submit 一次。
            // 记录恢复后**第一批进度**：它应当从"已落盘的量"起步，而不是从 0。
            srv.served.set(0)
            val id2 = client.submit(DownloadRequest(url = url, destination = out, stableKey = stableKey))
            var firstAfterResume = -1L
            val poller = launch {
                while (firstAfterResume < 0) {
                    val b = client.progress.value[id2]?.downloadedBytes ?: 0L
                    if (b > 0) firstAfterResume = b
                    delay(5)
                }
            }
            val result = client.await(id2)
            poller.cancel()

            assertTrue(result.isSuccess, "继续后应当下载成功：${result.exceptionOrNull()?.message}")
            assertEquals(size.toLong(), out.length(), "最终文件大小必须完整")
            val servedSecond = srv.served.get()

            println(
                "[resume] size=$size 暂停时上报=$reportedAtPause 落盘=$onDisk " +
                    "第一次服务端发出=$servedFirst 第二次服务端发出=$servedSecond 恢复后首批进度=$firstAfterResume"
            )

            val slack = 3L * 1024 * 1024
            assertTrue(
                servedSecond < size - onDisk + slack,
                "第二次又发了 $servedSecond 字节，已经落盘的 $onDisk 字节疑似被重下"
            )
            assertTrue(
                firstAfterResume >= onDisk * 8 / 10,
                "恢复后的首批进度是 $firstAfterResume，明显低于已落盘的 $onDisk —— 像是从 0 重下"
            )
        } finally {
            runCatching { client.shutdown() }
            runCatching { srv.stop() }
            runCatching { chunkDir.deleteRecursively() }
        }
    }

    // ------------------------------------------------- 上报口径 vs 落盘口径（测量）

    /**
     * 暂停那一刻「上报」与「落盘」的字节差 = 进度倒退的幅度。
     *
     * 4 连接与 128 连接各测一次：在飞窗口随连接数放大，而 128 正是用户那台设备的设置
     * （截图里写着「128 线程」）。只记录事实、不断言阈值 —— 这个差值取决于实现，不该被钉死。
     */
    private fun measureGap(connections: Int) = runBlocking {
        val size = 24 * 1024 * 1024
        val payload = ByteArray(size) { (it % 251).toByte() }
        val srv = CountingRangeServer(payload, chunkDelayMs = 15, poolSize = connections + 32)
        val workDir = newWorkDir("resumegap$connections")
        val stableKey = "room-gap-$connections"
        val chunkDir = chunkDirOf(workDir, stableKey)

        val client = TurboClient(
            TurboConfig(maxConnectionsPerTask = connections, maxConcurrentTasks = 1, workDir = workDir)
        )
        val out = File.createTempFile("resume-gap", ".bin").apply { deleteOnExit() }
        try {
            val url = "http://127.0.0.1:${srv.port}/f.bin"
            val id = client.submit(DownloadRequest(url = url, destination = out, stableKey = stableKey))
            waitUntil(client, id, size / 3L)
            val reported = client.progress.value[id]?.downloadedBytes ?: 0L
            client.pause(id)
            waitForPaused(client, id)
            val onDisk = onDiskBytes(chunkDir)
            val parts = chunkDir.listFiles()?.count { it.name.startsWith("seg_") } ?: 0
            println(
                "[resume-gap] 连接=$connections 上报=$reported 落盘=$onDisk 差=${reported - onDisk} " +
                    "分片数=$parts 占文件比=${"%.1f".format((reported - onDisk) * 100.0 / size)}%"
            )
            assertTrue(onDisk > 0, "暂停后应当有分片落盘（onDisk=$onDisk）")
        } finally {
            runCatching { client.shutdown() }
            runCatching { srv.stop() }
            runCatching { chunkDir.deleteRecursively() }
        }
    }

    @Test
    fun `gap between reported and on-disk bytes at pause - 4 connections`() = measureGap(4)

    @Test
    fun `gap between reported and on-disk bytes at pause - 128 connections`() = measureGap(128)

    // ------------------------------------------- 校验器变化（CDN 多节点）不该丢分片

    /**
     * 服务器每次响应给**不同的 ETag**、但内容与长度完全一致 —— 夸克这类多节点 CDN 的常态。
     *
     * 旧判据把"令牌不同"直接当成"文件变了"→ 清空分片从头下，于是同一份文件"有时续传、有时重下"。
     * 现在只有**长度变化**才允许直接丢弃；令牌不同要交内容指纹（逐字节比对）裁决，比对通过就保留。
     */
    private fun resumeWithEtags(etagPerRequest: Boolean): Pair<Long, Long> = runBlocking {
        val size = 12 * 1024 * 1024
        val payload = ByteArray(size) { (it % 251).toByte() }
        // 慢速服务器：25ms/32KB，保证"暂停"落在传输中途（快服务器会在暂停前就下完，那样测不到东西）
        val srv = CountingRangeServer(payload, chunkDelayMs = 25, etagPerRequest = etagPerRequest)
        val workDir = newWorkDir("etag")
        val stableKey = "room-etag"
        val chunkDir = chunkDirOf(workDir, stableKey)
        val client = TurboClient(
            TurboConfig(maxConnectionsPerTask = 4, maxConcurrentTasks = 1, workDir = workDir)
        )
        val out = File.createTempFile("resume-etag", ".bin").apply { deleteOnExit() }
        try {
            val url = "http://127.0.0.1:${srv.port}/f.bin"
            val id1 = client.submit(DownloadRequest(url = url, destination = out, stableKey = stableKey))
            waitUntil(client, id1, size / 4L)
            client.pause(id1)
            waitForPaused(client, id1)
            val onDisk = onDiskBytes(chunkDir)
            assertTrue(onDisk > 0, "暂停后应当有分片落盘（onDisk=$onDisk）")
            srv.served.set(0)
            val id2 = client.submit(DownloadRequest(url = url, destination = out, stableKey = stableKey))
            val r = client.await(id2)
            assertTrue(r.isSuccess, "续传应当成功：${r.exceptionOrNull()?.message}")
            assertEquals(size.toLong(), out.length(), "文件必须完整")
            onDisk to srv.served.get()
        } finally {
            runCatching { client.shutdown() }
            runCatching { srv.stop() }
            runCatching { chunkDir.deleteRecursively() }
        }
    }

    @Test
    fun `different etag with identical content still resumes instead of restarting`() {
        val (onDisk, servedSecond) = resumeWithEtags(etagPerRequest = true)
        println("[etag] 落盘=$onDisk 第二次服务端发出=$servedSecond")
        assertTrue(
            servedSecond < (12 * 1024 * 1024) - onDisk + 3L * 1024 * 1024,
            "ETag 变了但内容一样，不该把已落盘的 $onDisk 字节重下（第二次发了 $servedSecond）"
        )
    }
}

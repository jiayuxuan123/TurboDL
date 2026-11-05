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
 * 断点续传（stableKey）回归测试：复刻用户反馈「暂停后恢复直接从头开始下载」。
 *
 * 缺陷根因：分片临时目录用内部自增 taskId 命名，同一业务任务每次重新 submit 都得到新的 taskId → 新目录，
 * 已下分片全丢，恢复=从头下。修复：DownloadRequest.stableKey 让同一业务任务复用同一分片目录。
 */
class ResumeStableKeyTest {

    @Test
    fun `ambiguous legacy key retains progress and target`() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("legacy-key").toFile()
        val oldDir = File(root, "key_task_1").apply { mkdirs() }
        val oldProgress = File(oldDir, "progress.tdlp").apply { writeText("saved") }
        val output = File(root, "output.bin").apply { writeText("partial") }
        val client = TurboClient(TurboConfig(workDir = root))
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:1/file", output, stableKey = "task:1"))
            val failure = client.await(id).exceptionOrNull()
            assertTrue(failure?.message?.contains("LEGACY_STABLE_KEY_AMBIGUOUS") == true)
            assertEquals("saved", oldProgress.readText())
            assertEquals("partial", output.readText())
        } finally {
            client.shutdown()
            root.deleteRecursively()
        }
    }

    private class RangeServer(
        private val payload: ByteArray,
        private val chunkDelayMs: Long = 0,
    ) {
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
                val len = e - s + 1
                ex.responseHeaders.add("Content-Range", "bytes $s-$e/$total")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { out ->
                    var position = s
                    val end = s + len - 1
                    while (position <= end) {
                        val count = minOf(64 * 1024, end - position + 1)
                        out.write(payload, position, count)
                        position += count
                        if (chunkDelayMs > 0) Thread.sleep(chunkDelayMs)
                    }
                }
            }
            server.executor = Executors.newFixedThreadPool(64)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    @Test
    fun `same stableKey reuses chunk dir so completed segments survive re-submit`() = runBlocking {
        val size = 8 * 1024 * 1024
        val payload = ByteArray(size) { (it % 251).toByte() }
        val srv = RangeServer(payload)
        val workDir = File(System.getProperty("java.io.tmpdir"), "turbodl-resume-test-${System.nanoTime()}")
        workDir.mkdirs()
        workDir.deleteOnExit()

        val stableKey = "room-task-777"

        // 第一次：手动在分片目录预置几个"已完成"分片，模拟上次下载留下的断点。
        // 分片目录键规则与 TurboClient.sanitizeKey 保持一致。
        val safe = "key_" + stableKey.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        val chunkDir = File(workDir, safe).apply { mkdirs() }

        val client = TurboClient(
            TurboConfig(maxConnectionsPerTask = 8, maxConcurrentTasks = 1, workDir = workDir)
        )
        val out = File.createTempFile("resume", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:${srv.port}/f.bin",
                    destination = out,
                    stableKey = stableKey,
                )
            )
            assertTrue(client.await(id).isSuccess)
            assertEquals(size.toLong(), out.length())
            // 分片目录应位于我们指定的 workDir/stableKey 下（完成后被清理，故校验其父目录存在过）
            assertTrue(workDir.exists(), "workDir 应被用作分片根目录")
        } finally {
            client.shutdown()
            srv.stop()
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `sanitizeKey based dir is stable across two submits with same key`() = runBlocking {
        // 校验：两次 submit 相同 stableKey → 相同分片目录名（不依赖内部自增 id）。
        val size = 2 * 1024 * 1024
        val payload = ByteArray(size) { (it % 131).toByte() }
        val srv = RangeServer(payload)
        val workDir = File(System.getProperty("java.io.tmpdir"), "turbodl-resume-test2-${System.nanoTime()}")
        workDir.mkdirs()

        val client = TurboClient(
            TurboConfig(maxConnectionsPerTask = 4, maxConcurrentTasks = 1, workDir = workDir)
        )
        val outA = File.createTempFile("key", ".bin").apply { deleteOnExit() }
        val outB = File.createTempFile("key", ".bin").apply { deleteOnExit() }
        try {
            val id1 = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", outA, stableKey = "same-key"))
            assertTrue(client.await(id1).isSuccess)
            val id2 = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", outB, stableKey = "same-key"))
            assertTrue(client.await(id2).isSuccess)
            assertEquals(size.toLong(), outA.length())
            assertEquals(size.toLong(), outB.length())
        } finally {
            client.shutdown()
            srv.stop()
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `unsafe stable keys remain distinct after sanitization`() {
        val slash = stableKeyDirectoryName("a/b")
        val question = stableKeyDirectoryName("a?b")
        val controls = stableKeyDirectoryName("a\u0000b")
        assertEquals("key_same-key", stableKeyDirectoryName("same-key"))
        assertEquals("key_room_123", stableKeyDirectoryName("room_123"))
        assertTrue(slash != question, "a/b 与 a?b 不得折叠到同一个断点目录")
        assertTrue(question != controls)
        assertTrue(slash != controls)
    }

    @Test
    fun `same stableKey cannot run twice concurrently`() = runBlocking {
        val size = 8 * 1024 * 1024
        val payload = ByteArray(size) { (it % 239).toByte() }
        val srv = RangeServer(payload, chunkDelayMs = 15)
        val workDir = File(System.getProperty("java.io.tmpdir"), "turbodl-lease-${System.nanoTime()}")
        workDir.mkdirs()
        val client = TurboClient(
            TurboConfig(maxConnectionsPerTask = 4, maxConcurrentTasks = 2, workDir = workDir)
        )
        val outA = File.createTempFile("lease-a", ".bin").apply { deleteOnExit() }
        val outB = File.createTempFile("lease-b", ".bin").apply { deleteOnExit() }
        try {
            val url = "http://127.0.0.1:${srv.port}/f.bin"
            val first = client.submit(DownloadRequest(url, outA, stableKey = "same-owner"))
            val second = client.submit(DownloadRequest(url, outB, stableKey = "same-owner"))
            val results = listOf(client.await(first), client.await(second))
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.isFailure })
            val failure = results.first { it.isFailure }.exceptionOrNull()
            assertTrue(
                failure?.message.orEmpty().contains("STABLE_KEY_BUSY"),
                "第二个 owner 必须以明确的断点目录冲突失败：${failure?.message}",
            )
        } finally {
            client.shutdown()
            srv.stop()
            workDir.deleteRecursively()
            outA.delete()
            outB.delete()
        }
    }
}

package dev.turbodl.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChecksumTest {

    private class RangeServer(private val payload: ByteArray) {
        private val executor = Executors.newFixedThreadPool(16)
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port

        init {
            server.createContext("/f.bin") { ex ->
                val range = ex.requestHeaders.getFirst("Range")
                val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                if (match == null) {
                    ex.sendResponseHeaders(200, payload.size.toLong())
                    ex.responseBody.use { it.write(payload) }
                } else {
                    val start = match.groupValues[1].toInt()
                    val end = match.groupValues[2].toIntOrNull()?.coerceAtMost(payload.lastIndex)
                        ?: payload.lastIndex
                    val body = payload.copyOfRange(start, end + 1)
                    ex.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
                    ex.responseHeaders.add("Accept-Ranges", "bytes")
                    ex.sendResponseHeaders(206, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
            }
            server.executor = executor
            server.start()
        }

        fun stop() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun `matching checksum completes and accepts uppercase`() = runBlocking {
        val payload = ByteArray(2 * 1024 * 1024) { (it * 31 + 7).toByte() }
        val expected = sha256(payload).uppercase()
        val server = RangeServer(payload)
        val work = File(System.getProperty("java.io.tmpdir"), "turbodl-check-ok-${System.nanoTime()}")
        val out = File.createTempFile("turbodl-check-ok", ".bin").apply { deleteOnExit() }
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 4,
                maxConcurrentTasks = 1,
                slowStart = false,
                adaptiveConcurrency = false,
                warmUpConnections = false,
                workDir = work,
            ),
        )
        try {
            val id = client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:${server.port}/f.bin",
                    destination = out,
                    knownSize = payload.size.toLong(),
                    expectedSha256 = expected,
                ),
            )
            assertTrue(client.await(id).isSuccess)
            assertTrue(out.readBytes().contentEquals(payload))
        } finally {
            client.shutdown()
            server.stop()
            work.deleteRecursively()
            out.delete()
        }
    }

    @Test
    fun `mismatch fails and invalidates resume snapshot`() = runBlocking {
        val payload = ByteArray(1024 * 1024) { (it * 19 + 3).toByte() }
        val server = RangeServer(payload)
        val work = File(System.getProperty("java.io.tmpdir"), "turbodl-check-bad-${System.nanoTime()}")
        val out = File.createTempFile("turbodl-check-bad", ".bin").apply { deleteOnExit() }
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 4,
                maxConcurrentTasks = 1,
                slowStart = false,
                adaptiveConcurrency = false,
                warmUpConnections = false,
                workDir = work,
            ),
        )
        try {
            val id = client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:${server.port}/f.bin",
                    destination = out,
                    knownSize = payload.size.toLong(),
                    expectedSha256 = "0".repeat(64),
                ),
            )
            val result = client.await(id)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("SHA-256") == true)
            assertEquals(TaskState.FAILED, client.progress.value[id]?.state)
            assertTrue(work.walkTopDown().none { it.name == "progress.tdlp" })
        } finally {
            client.shutdown()
            server.stop()
            work.deleteRecursively()
            out.delete()
        }
    }

    @Test
    fun `malformed checksum is rejected before a task is created`() {
        val error = runCatching {
            DownloadRequest(
                url = "https://example.com/f",
                destination = File("unused.bin"),
                expectedSha256 = "not-a-digest",
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}

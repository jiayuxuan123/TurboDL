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

class SingleConnectionWholeGetTest {
    @Test
    fun `fresh single-connection task uses one non-range data GET`() = runBlocking {
        val payload = ByteArray(1024 * 1024) { (it % 241).toByte() }
        val rangeRequests = AtomicInteger(0)
        val fullRequests = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/f.bin") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            if (range == null) {
                fullRequests.incrementAndGet()
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.use { it.write(payload) }
            } else {
                rangeRequests.incrementAndGet()
                val match = Regex("bytes=(\\d+)-(\\d*)").find(range)!!
                val start = match.groupValues[1].toInt()
                val end = match.groupValues[2].toIntOrNull()?.coerceAtMost(payload.size - 1)
                    ?: (payload.size - 1)
                val length = end - start + 1
                ex.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(206, length.toLong())
                ex.responseBody.use { it.write(payload, start, length) }
            }
        }
        server.executor = Executors.newFixedThreadPool(4)
        server.start()
        val out = File.createTempFile("single-whole", ".bin").apply { deleteOnExit() }
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 1,
                maxConcurrentTasks = 1,
                slowStart = false,
                adaptiveConcurrency = false,
                warmUpConnections = false,
            ),
        )
        try {
            val id = client.submit(
                DownloadRequest("http://127.0.0.1:${server.address.port}/f.bin", out),
            )
            val result = client.await(id)
            assertTrue(result.isSuccess, "download failed: ${result.exceptionOrNull()?.message}")
            assertEquals(payload.size.toLong(), out.length())
            assertTrue(out.readBytes().contentEquals(payload))
            assertEquals(1, fullRequests.get(), "single connection should use one full GET")
            assertEquals(1, rangeRequests.get(), "only the discovery probe should use Range")
        } finally {
            client.shutdown()
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
            out.delete()
        }
    }
}

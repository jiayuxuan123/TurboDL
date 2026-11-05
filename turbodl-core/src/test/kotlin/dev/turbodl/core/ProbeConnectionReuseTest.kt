package dev.turbodl.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProbeConnectionReuseTest {
    @Test
    fun `multi-connection probe warms the segment client connection pool`() = runBlocking {
        val size = 2 * 1024 * 1024
        val payload = ByteArray(size) { (it % 251).toByte() }
        val sourcePorts = ConcurrentHashMap.newKeySet<Int>()
        val rangeRequests = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/f.bin") { ex ->
            sourcePorts += ex.remoteAddress.port
            val range = ex.requestHeaders.getFirst("Range")
            val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
            if (match == null) {
                ex.sendResponseHeaders(200, size.toLong())
                ex.responseBody.use { it.write(payload) }
                return@createContext
            }
            rangeRequests.incrementAndGet()
            val start = match.groupValues[1].toInt()
            val requestedEnd = match.groupValues[2].toIntOrNull() ?: (size - 1)
            val end = minOf(requestedEnd, size - 1)
            val length = end - start + 1
            ex.responseHeaders.add("Content-Range", "bytes $start-$end/$size")
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            ex.sendResponseHeaders(206, length.toLong())
            ex.responseBody.use { it.write(payload, start, length) }
        }
        server.executor = Executors.newFixedThreadPool(16)
        server.start()

        val out = File.createTempFile("probe-reuse", ".bin").apply { deleteOnExit() }
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 2,
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
            assertEquals(size.toLong(), out.length())
            assertTrue(out.readBytes().contentEquals(payload))
            assertTrue(rangeRequests.get() > 1, "should use Range segments")
            assertTrue(
                sourcePorts.size <= 2,
                "probe should reuse the segment client's pool; observed source ports=$sourcePorts",
            )
        } finally {
            client.shutdown()
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
            out.delete()
        }
    }
}

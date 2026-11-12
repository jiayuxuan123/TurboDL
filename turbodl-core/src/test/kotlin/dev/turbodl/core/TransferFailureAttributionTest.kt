package dev.turbodl.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransferFailureAttributionTest {
    @Test
    fun `segment rate limit keeps concrete status and transfer phase`() = runBlocking {
        val payload = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/file") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            if (range == "bytes=0-0") {
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.responseHeaders.add("Content-Range", "bytes 0-0/${payload.size}")
                exchange.sendResponseHeaders(206, 1)
                exchange.responseBody.use { it.write(payload, 0, 1) }
            } else {
                exchange.responseHeaders.add("Retry-After", "300")
                exchange.sendResponseHeaders(503, -1)
            }
            exchange.close()
        }
        server.start()
        val client = TurboClient(TurboConfig(
            maxConnectionsPerTask = 2,
            maxConcurrentTasks = 1,
            warmUpConnections = false,
            minSegmentSize = 256 * 1024,
        ))
        try {
            val id = client.submit(DownloadRequest(
                "http://127.0.0.1:${server.address.port}/file",
                java.io.File.createTempFile("status-attribution", ".bin").apply { deleteOnExit() },
                knownSize = payload.size.toLong(),
            ))
            val result = client.await(id)
            assertTrue(result.isFailure)
            val reason = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(reason.contains("HTTP 503"), reason)
            val details = client.taskDetails(id)
            assertEquals(503, details?.lastHttpStatus)
            assertEquals(TransferPhase.TRANSFERRING, details?.failureStage)
        } finally {
            client.shutdown()
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }
}

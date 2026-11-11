package dev.turbodl.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskTransferExecutionTest {
    @Test fun `task policy stays fixed while global limits remain live`() = runBlocking {
        val root = Files.createTempDirectory("task-policy-").toFile()
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val configs = mutableListOf<TurboConfig>()
        val client = TurboClient(TurboConfig(minSegmentSize = 256 * 1024, maxTotalConnections = 16))
        client.backendResolver = BackendResolver { object : DownloadBackend {
            override val name = "policy-test"
            override fun supports(request: DownloadRequest) = true
            override suspend fun download(context: BackendContext): BackendResult {
                configs += context.config
                entered.complete(Unit)
                proceed.await()
                configs += context.config
                context.request.destination.writeBytes(byteArrayOf(42))
                return BackendResult(listOf(context.request.destination), 1)
            }
        } }
        try {
            val id = client.submitWithPolicy(DownloadRequest("test://policy", File(root, "out")),
                TaskTransferPolicy.builder().minSegmentSize(64 * 1024).slowStart(false).build())
            withTimeout(5_000) { entered.await() }
            client.updateConfig(client.config.copy(minSegmentSize = 512 * 1024, maxTotalConnections = 8))
            proceed.complete(Unit)
            assertTrue(withTimeout(5_000) { client.await(id) }.isSuccess)
            assertEquals(listOf(64L * 1024, 64L * 1024), configs.map { it.minSegmentSize })
            assertEquals(listOf(16, 8), configs.map { it.maxTotalConnections })
        } finally { client.shutdown(); root.deleteRecursively() }
    }

    @Test fun `real requests supply transfer details and independently verified output`() = runBlocking {
        val data = ByteArray(512 * 1024).also { java.util.Random(8191).nextBytes(it) }
        val expected = MessageDigest.getInstance("SHA-256").digest(data)
        val root = Files.createTempDirectory("task-details-").toFile()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/file") { exchange ->
            try {
                val range = exchange.requestHeaders.getFirst("Range")
                val bounds = range?.removePrefix("bytes=")?.split('-')
                val start = bounds?.get(0)?.toInt() ?: 0
                val end = bounds?.getOrNull(1)?.takeIf { it.isNotBlank() }?.toInt() ?: data.lastIndex
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.responseHeaders.add("ETag", "\"fixed-fixture\"")
                if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${data.size}")
                exchange.sendResponseHeaders(if (range != null) 206 else 200, (end - start + 1).toLong())
                exchange.responseBody.use { it.write(data, start, end - start + 1) }
            } finally { exchange.close() }
        }
        server.start()
        val client = TurboClient(TurboConfig(maxConnectionsPerTask = 4, slowStart = false,
            warmUpConnections = false, skipProbeWhenSizeKnown = true))
        try {
            val output = File(root, "output.bin")
            val id = client.submitWithPolicy(DownloadRequest("http://127.0.0.1:${server.address.port}/file", output,
                knownSize = data.size.toLong()), TaskTransferPolicy.builder().backpressureGroup("fixture").build())
            assertTrue(withTimeout(10_000) { client.await(id) }.isSuccess)
            assertTrue(expected.contentEquals(MessageDigest.getInstance("SHA-256").digest(output.readBytes())))
            val details = requireNotNull(client.taskDetails(id))
            assertEquals(TransferPhase.COMPLETED, details.phase)
            assertEquals(0, details.activeRequests)
            assertTrue(details.requestCount > 0)
            assertTrue(details.receivedBytes >= data.size)
            assertTrue(details.peakActiveRequests in 1..4)
            assertTrue(details.timeToFirstByteMs != null)
        } finally { client.shutdown(); server.stop(0); executor.shutdownNow(); root.deleteRecursively() }
    }
}

package dev.turbodl.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RangeStartupMaintenanceTest {
    @Test
    fun `small file default avoids extra range rounds and explicit fine ranges remain supported`() = runBlocking {
        suspend fun checkGrid(minimum: Long?, blockSize: Long, expectedRequests: Int) {
            val root = Files.createTempDirectory("initial-range-grid-").toFile()
            MaintenanceRangeServer(1024 * 1024).use { server ->
                val defaults = TurboConfig(workDir = root.resolve("work"),
                    maxConnectionsPerTask = 8, warmUpConnections = false, slowStart = false,
                    blockSize = blockSize)
                val client = TurboClient(if (minimum == null) defaults else defaults.copy(minSegmentSize = minimum))
                try {
                    val output = root.resolve("result.bin")
                    val id = client.submit(DownloadRequest(server.url, output, knownSize = server.payload.size.toLong()))
                    assertTrue(withTimeout(5_000) { client.await(id) }.isSuccess)
                    assertEquals(server.sha256.toList(), sha256(output.readBytes()).toList())
                    assertEquals(expectedRequests, server.requests.get())
                } finally {
                    client.shutdown()
                    root.deleteRecursively()
                }
            }
        }
        checkGrid(null, 16L * 1024 * 1024, 8)
        checkGrid(64L * 1024, 16L * 1024 * 1024, 16)
        checkGrid(null, 64L * 1024, 16)
    }

    @Test
    fun `fast idle connections rescue a slow large range without waiting for three halvings`() = runBlocking {
        val root = Files.createTempDirectory("weighted-tail-").toFile()
        MaintenanceRangeServer(16 * 1024 * 1024, slowFirstRange = true,
            slowChunkBytes = 16 * 1024, slowDelayMs = 80).use { server ->
            val client = TurboClient(TurboConfig(
                workDir = root.resolve("work"), maxConnectionsPerTask = 8,
                warmUpConnections = false, slowStart = false, segmentsPerConnection = 1,
            ))
            try {
                val output = root.resolve("result.bin")
                val id = client.submit(DownloadRequest(server.url, output, knownSize = server.payload.size.toLong()))
                assertTrue(withTimeout(15_000) { client.await(id) }.isSuccess)
                assertEquals(server.sha256.toList(), sha256(output.readBytes()).toList())
                assertTrue(server.slowBytes.get() < 128 * 1024,
                    "the slow socket still served ${server.slowBytes.get()} bytes despite fast idle peers")
            } finally {
                client.shutdown()
                root.deleteRecursively()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `other ranges start after response headers without waiting for the first body`() = runTest {
        MaintenanceRangeServer(16 * 1024).use { server ->
            val http = HttpClientFactory.build(TurboConfig(proxy = ProxyMode.Direct))
            val downloader = SegmentDownloader({ http })
            val release = CompletableDeferred<Unit>()
            val committed = CompletableDeferred<Unit>()
            val output = ByteArray(server.payload.size)
            suspend fun download(start: Long, end: Long, wait: Boolean): SegmentResult =
                downloader.downloadSegmentInto(
                    taskId = 71,
                    url = server.url,
                    pieceStart = start,
                    endProvider = { end },
                    alreadyWritten = 0,
                    writeAt = { position, bytes, offset, count ->
                        bytes.copyInto(output, position.toInt(), offset, offset + count)
                        count
                    },
                    headers = emptyMap(),
                    onProgress = { _, _ -> if (wait) { committed.complete(Unit); release.await() } },
                    buffer = ByteArray(8192),
                    expectedTotal = server.payload.size.toLong(),
                )
            val first = async { download(0, 8191, true) }
            committed.await()
            val second = async { download(8192, 16383, false) }
            try {
                runCurrent()
                assertTrue(second.isCompleted, "headers already resolved the URL; a body must not hold the startup gate")
                assertEquals(SegmentResult.OK, second.await())
            } finally {
                release.complete(Unit)
                first.await()
                second.await()
                downloader.releaseTask(71)
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
            }
            assertEquals(server.sha256.toList(), sha256(output).toList())
        }
    }

    @Test
    fun `default tail minimum can split a stalled range smaller than 64 KiB`() = runBlocking {
        val root = Files.createTempDirectory("small-tail-").toFile()
        MaintenanceRangeServer(48 * 1024, slowFirstRange = true).use { server ->
            val client = TurboClient(TurboConfig(
                workDir = root.resolve("work"),
                maxConnectionsPerTask = 2,
                warmUpConnections = false,
                slowStart = false,
                segmentsPerConnection = 1,
            ))
            try {
                val output = root.resolve("result.bin")
                val id = client.submit(DownloadRequest(server.url, output, knownSize = server.payload.size.toLong()))
                assertTrue(client.await(id).isSuccess)
                assertEquals(server.sha256.toList(), sha256(output.readBytes()).toList())
                assertTrue(server.requests.get() > 2, "a free worker must assist the remaining 8..64 KiB tail")
            } finally {
                client.shutdown()
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `an oversized tail minimum disables splitting without overflowing`() = runBlocking {
        val root = Files.createTempDirectory("tail-minimum-").toFile()
        MaintenanceRangeServer(48 * 1024, slowFirstRange = true).use { server ->
            val client = TurboClient(TurboConfig(
                workDir = root.resolve("work"), maxConnectionsPerTask = 2,
                warmUpConnections = false, slowStart = false, segmentsPerConnection = 1,
                tailAssistMinBytes = Long.MAX_VALUE,
            ))
            try {
                val output = root.resolve("result.bin")
                val id = client.submit(DownloadRequest(server.url, output, knownSize = server.payload.size.toLong()))
                assertTrue(withTimeout(5_000) { client.await(id) }.isSuccess)
                assertEquals(2, server.requests.get())
                assertEquals(server.sha256.toList(), sha256(output.readBytes()).toList())
            } finally {
                client.shutdown()
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `idle worker does not duplicate a range still waiting for its first byte`() = runBlocking {
        val root = Files.createTempDirectory("tail-startup-cost-").toFile()
        MaintenanceRangeServer(48 * 1024, firstBodyDelayMs = 150).use { server ->
            val client = TurboClient(TurboConfig(
                workDir = root.resolve("work"), maxConnectionsPerTask = 2,
                warmUpConnections = false, slowStart = false, segmentsPerConnection = 1,
            ))
            try {
                val output = root.resolve("result.bin")
                val id = client.submit(DownloadRequest(server.url, output, knownSize = server.payload.size.toLong()))
                assertTrue(withTimeout(5_000) { client.await(id) }.isSuccess)
                assertEquals(server.sha256.toList(), sha256(output.readBytes()).toList())
                assertEquals(2, server.requests.get(), "a new connection cannot bypass the same first-byte delay")
            } finally {
                client.shutdown()
                root.deleteRecursively()
            }
        }
    }
}

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal class MaintenanceRangeServer(
    size: Int,
    private val slowFirstRange: Boolean = false,
    private val firstBodyDelayMs: Long = 0,
    private val slowChunkBytes: Int = 1024,
    private val slowDelayMs: Long = 30,
) : AutoCloseable {
    val payload = ByteArray(size).also { Random(3107).nextBytes(it) }
    val sha256 = sha256(payload)
    val requests = AtomicInteger()
    val slowBytes = AtomicLong()
    private val executor = Executors.newFixedThreadPool(8)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url: String get() = "http://127.0.0.1:${server.address.port}/data.bin"

    init {
        server.executor = executor
        server.createContext("/data.bin") { exchange ->
            try {
                val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(exchange.requestHeaders.getFirst("Range").orEmpty())
                val start = match?.groupValues?.get(1)?.toInt() ?: 0
                val end = match?.groupValues?.get(2)?.toIntOrNull() ?: payload.lastIndex
                val length = end - start + 1
                requests.incrementAndGet()
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.responseHeaders.add("ETag", "\"range-maintenance-v1\"")
                if (match != null) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
                exchange.sendResponseHeaders(if (match == null) 200 else 206, length.toLong())
                exchange.responseBody.use { output ->
                    if (start == 0 && firstBodyDelayMs > 0) {
                        output.flush()
                        Thread.sleep(firstBodyDelayMs)
                    }
                    if (slowFirstRange && start == 0) {
                        var offset = start
                        while (offset <= end) {
                            val count = minOf(slowChunkBytes, end - offset + 1)
                            Thread.sleep(slowDelayMs)
                            output.write(payload, offset, count)
                            output.flush()
                            slowBytes.addAndGet(count.toLong())
                            offset += count
                        }
                    } else output.write(payload, start, length)
                }
            } catch (_: java.io.IOException) {
                // Tail assistance deliberately stops reading the donated suffix.
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
    }
}

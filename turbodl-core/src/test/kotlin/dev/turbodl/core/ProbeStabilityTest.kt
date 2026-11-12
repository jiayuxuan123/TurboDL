package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProbeStabilityTest {
    private class Fixture : AutoCloseable {
        val bytes = Random(9241).nextBytes(128 * 1024)
        val root = Files.createTempDirectory("tdl-probe-stability-").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val times = mutableListOf<Long>()
        val agents = mutableListOf<String?>()
        @Volatile var initialStatus = 200
        @Volatile var retryAfter: String? = null
        @Volatile var alwaysFail = false
        private val pool = Executors.newFixedThreadPool(4)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            createContext("/blocked") { exchange ->
                entered.countDown()
                release.await(8, TimeUnit.SECONDS)
                runCatching { respond(exchange) }
                exchange.close()
            }
            createContext("/file") { exchange ->
                val count = calls.incrementAndGet()
                synchronized(times) {
                    times.add(System.nanoTime())
                    agents.add(exchange.requestHeaders.getFirst("User-Agent"))
                }
                if (initialStatus != 200 && (alwaysFail || count == 1)) {
                    exchange.responseHeaders.add("Content-Type", "text/html")
                    retryAfter?.let { exchange.responseHeaders.add("Retry-After", it) }
                    exchange.sendResponseHeaders(initialStatus, -1)
                    exchange.close()
                } else runCatching { respond(exchange) }.also { exchange.close() }
            }
            start()
        }

        fun url(path: String = "file") = "http://127.0.0.1:${server.address.port}/$path"
        fun output(name: String) = File(root, name)

        private fun respond(exchange: HttpExchange) {
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.responseHeaders.add("ETag", "\"probe-stable\"")
            val range = exchange.requestHeaders.getFirst("Range")
                ?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
            val start = range?.groupValues?.get(1)?.toInt() ?: 0
            val end = (range?.groupValues?.get(2)?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
            if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${bytes.size}")
            exchange.sendResponseHeaders(if (range != null) 206 else 200, (end - start + 1).toLong())
            exchange.responseBody.use { it.write(bytes, start, end - start + 1) }
        }

        fun assertHash(file: File) {
            val hash = MessageDigest.getInstance("SHA-256")
            assertContentEquals(hash.digest(bytes), hash.digest(file.readBytes()))
        }

        override fun close() {
            release.countDown()
            server.stop(0)
            pool.shutdownNow()
            pool.awaitTermination(1, TimeUnit.SECONDS)
            require(root.canonicalFile.parentFile == File(System.getProperty("java.io.tmpdir")).canonicalFile)
            root.deleteRecursively()
        }
    }

    private fun client(total: Int = 2, retries: Int = 1) = TurboClient(TurboConfig(
        maxConnectionsPerTask = 1, maxConcurrentTasks = 2, maxTotalConnections = total,
        probeTimeoutMs = 8_000, probeRetries = retries, skipProbeWhenSizeKnown = false,
        warmUpConnections = false,
    ))

    @Test fun `pausing a blocked probe releases the global request budget promptly`() = runBlocking {
        val fixture = Fixture()
        val client = client(total = 1)
        try {
            val blocked = client.submit(DownloadRequest(fixture.url("blocked"), fixture.output("blocked.bin")))
            assertTrue(withContext(Dispatchers.IO) { fixture.entered.await(2, TimeUnit.SECONDS) })
            client.pause(blocked)
            val healthy = client.submit(DownloadRequest(fixture.url(), fixture.output("healthy.bin")))
            val file = withTimeout(2_000) { client.await(healthy) }.getOrThrow()
            fixture.assertHash(file)
            withTimeout(1_000) { client.awaitSettled(blocked) }
        } finally {
            fixture.release.countDown()
            client.shutdown()
            fixture.close()
        }
    }

    @Test fun `HTML 429 probe honors Retry-After without a caller cooldown group`() =
        transientThrottle(429)

    @Test fun `canceling a weak-resume fingerprint interrupts its blocked request`() = runBlocking {
        val fixture = Fixture()
        val http = HttpClientFactory.build(TurboConfig(), HttpClientFactory.ProtocolPreference.ALLOW_H2)
        val downloader = SegmentDownloader({ http })
        val read = async { downloader.fetchPrefix(fixture.url("blocked"), emptyMap(), 0, 32, 8_000) }
        try {
            assertTrue(withContext(Dispatchers.IO) { fixture.entered.await(2, TimeUnit.SECONDS) })
            withTimeout(800) { read.cancelAndJoin() }
        } finally {
            fixture.release.countDown()
            read.cancelAndJoin()
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
            fixture.close()
        }
    }

    @Test fun `HTML 503 probe retries without changing the user agent`() =
        transientThrottle(503)

    private fun transientThrottle(status: Int) = runBlocking {
        val fixture = Fixture().apply { initialStatus = status; retryAfter = "1" }
        val client = client()
        try {
            val id = client.submit(DownloadRequest(fixture.url(), fixture.output("recovered.bin")))
            val result = withTimeout(4_000) { client.await(id) }
            assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
            fixture.assertHash(result.getOrThrow())
            val times = synchronized(fixture.times) { fixture.times.toList() }
            assertTrue(times.size >= 2)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(times[1] - times[0]) >= 900, "server wait was shortened")
            assertEquals(1, fixture.agents.toSet().size, "throttling is not a user-agent rejection")
        } finally {
            client.shutdown()
            fixture.close()
        }
    }

    @Test fun `long probe cooldown stops automatic retry and keeps the real HTTP cause`() = runBlocking {
        val fixture = Fixture().apply { initialStatus = 429; retryAfter = "300"; alwaysFail = true }
        val client = client(retries = 3)
        try {
            val id = client.submit(DownloadRequest(fixture.url(), fixture.output("deferred.bin")))
            val result = withTimeout(2_000) { client.await(id) }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("限流"), "cooldown mislabeled as a web page")
            assertEquals(1, fixture.calls.get())
            assertEquals(429, client.taskDetails(id)?.lastHttpStatus)
            assertEquals(TransferPhase.PROBING, client.taskDetails(id)?.failureStage)
        } finally {
            client.shutdown()
            fixture.close()
        }
    }
}

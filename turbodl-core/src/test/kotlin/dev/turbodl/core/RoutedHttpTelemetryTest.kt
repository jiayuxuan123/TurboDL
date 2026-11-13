package dev.turbodl.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutedHttpTelemetryTest {
    private fun scenario(status: Int = 206, budget: Int = 8, check: (TurboClient, Long, Long, AtomicInteger) -> Unit) {
        val payload = ByteArray(512 * 1024).also { java.util.Random(94821).nextBytes(it) }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/data") { exchange ->
            val current = active.incrementAndGet()
            peak.accumulateAndGet(current, ::maxOf)
            try {
                Thread.sleep(30)
                if (status != 206) {
                    active.decrementAndGet()
                    exchange.sendResponseHeaders(status, -1)
                } else {
                    val range = exchange.requestHeaders.getFirst("Range")
                    val match = range?.let { Regex("bytes=(\\d+)-(\\d+)").matchEntire(it) }
                    val start = match?.groupValues?.get(1)?.toInt() ?: 0
                    val end = match?.groupValues?.get(2)?.toInt() ?: payload.lastIndex
                    exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
                    // End server-side work before the final response can release the client lease.
                    active.decrementAndGet()
                    exchange.sendResponseHeaders(if (range == null) 200 else 206, (end - start + 1).toLong())
                    exchange.responseBody.write(payload, start, end - start + 1)
                }
            } finally {
                exchange.close()
            }
        }
        server.start()
        val dir = Files.createTempDirectory("routed-http-").toFile()
        val config = TurboConfig(workDir = dir, maxConnectionsPerTask = 4, maxTotalConnections = budget,
            maxConcurrentTasks = 2, slowStart = false, adaptiveConcurrency = false, warmUpConnections = false,
            tailAssist = false, maxRetries = 0)
        val backend = TurboBackends.builtinHttp(config)
        val client = TurboClient(config).apply { backendResolver = BackendResolver { backend } }
        try {
            val url = "http://127.0.0.1:${server.address.port}/data"
            val a = client.submit(DownloadRequest(url, File(dir, "a.bin"), knownSize = payload.size.toLong(), expectedSha256 = digest))
            val b = client.submit(DownloadRequest(url, File(dir, "b.bin"), knownSize = payload.size.toLong(), expectedSha256 = digest))
            check(client, a, b, peak)
        } finally {
            client.shutdown()
            server.stop(0)
            executor.shutdownNow()
            dir.deleteRecursively()
        }
    }

    @Test fun `factory HTTP backend reports requests bytes and active peak`() = runBlocking {
        scenario { client, a, b, _ ->
            assertTrue(runBlocking { client.await(a) }.isSuccess)
            assertTrue(runBlocking { client.await(b) }.isSuccess)
            val details = client.taskDetails(a)!!
            assertTrue(details.requestCount > 0, "routed HTTP requests must be instrumented")
            assertTrue(details.receivedBytes >= 512 * 1024)
            assertTrue(details.peakActiveRequests > 0)
            assertEquals(0, details.activeRequests)
        }
    }

    @Test fun `factory HTTP backend shares client global budget`() = runBlocking {
        scenario(budget = 1) { client, a, b, peak ->
            assertTrue(runBlocking { client.await(a) }.isSuccess)
            assertTrue(runBlocking { client.await(b) }.isSuccess)
            assertEquals(1, peak.get(), "plugin HTTP route must not bypass the client connection budget")
        }
    }

    @Test fun `factory HTTP backend keeps the real HTTP failure status`() = runBlocking {
        scenario(status = 403) { client, a, b, _ ->
            assertTrue(runBlocking { client.await(a) }.isFailure)
            assertTrue(runBlocking { client.await(b) }.isFailure)
            assertEquals(403, client.taskDetails(a)!!.lastHttpStatus)
        }
    }
}

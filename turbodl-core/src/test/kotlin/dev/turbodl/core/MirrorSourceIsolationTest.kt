package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class MirrorSourceIsolationTest {
    private class RangeServer(
        private val payload: ByteArray,
        private val redirectPath: String? = null,
    ) : AutoCloseable {
        private val executor = Executors.newFixedThreadPool(8)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        val fileRequests = AtomicInteger()
        val lastCookie = AtomicReference<String?>()
        val lastAuthorization = AtomicReference<String?>()
        val lastCustomHeader = AtomicReference<String?>()

        init {
            server.createContext("/orig") { exchange ->
                if (redirectPath == null) {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                } else {
                    exchange.responseHeaders.add("Location", redirectPath)
                    exchange.sendResponseHeaders(302, -1)
                    exchange.close()
                }
            }
            server.createContext("/file") { exchange -> serve(exchange) }
            server.executor = executor
            server.start()
        }

        private fun serve(exchange: HttpExchange) {
            fileRequests.incrementAndGet()
            lastCookie.set(exchange.requestHeaders.getFirst("Cookie"))
            lastAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
            lastCustomHeader.set(exchange.requestHeaders.getFirst("X-Route"))
            try {
                val raw = requireNotNull(exchange.requestHeaders.getFirst("Range"))
                val match = requireNotNull(Regex("bytes=(\\d+)-(\\d+)").matchEntire(raw))
                val start = match.groupValues[1].toInt()
                val end = minOf(match.groupValues[2].toInt(), payload.lastIndex)
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.sendResponseHeaders(206, (end - start + 1).toLong())
                exchange.responseBody.use { out ->
                    var offset = start
                    val chunk = 16 * 1024
                    while (offset <= end) {
                        val count = minOf(chunk, end - offset + 1)
                        out.write(payload, offset, count)
                        out.flush()
                        offset += count
                        Thread.sleep(12)
                    }
                }
            } finally {
                exchange.close()
            }
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `redirect learned from primary must not steal mirror requests`() = runBlocking {
        withTimeout(20_000) {
            val payload = ByteArray(512 * 1024) { ((it * 29 + 3) % 251).toByte() }
            RangeServer(payload, redirectPath = "/file").use { primary ->
                RangeServer(payload).use { mirror ->
                    val dir = Files.createTempDirectory("mirror-source-isolation-").toFile()
                    val output = File(dir, "out.bin")
                    val client = TurboClient(
                        TurboConfig(
                            workDir = File(dir, "work"),
                            maxConnectionsPerTask = 2,
                            maxConcurrentTasks = 1,
                            blockSize = 128 * 1024,
                            minSegmentSize = 64 * 1024,
                            segmentsPerConnection = 1,
                            slowStart = false,
                            adaptiveConcurrency = false,
                            warmUpConnections = false,
                            skipProbeWhenSizeKnown = true,
                        )
                    )
                    try {
                        val id = client.submit(
                            DownloadRequest(
                                url = "http://127.0.0.1:${primary.port}/orig",
                                destination = output,
                                knownSize = payload.size.toLong(),
                                mirrors = listOf("http://127.0.0.1:${mirror.port}/file"),
                                headers = mapOf(
                                    "Cookie" to "session=secret",
                                    "Authorization" to "Bearer secret",
                                    "X-Route" to "edge",
                                ),
                            )
                        )
                        val result = client.await(id)
                        assertTrue(result.isSuccess, "mixed-source download failed: ${result.exceptionOrNull()}")
                        assertContentEquals(payload, output.readBytes())
                        assertTrue(
                            mirror.fileRequests.get() > 0,
                            "the mirror was never contacted: primary requests=${primary.fileRequests.get()} " +
                                "mirror requests=${mirror.fileRequests.get()}",
                        )
                        assertTrue(mirror.lastCookie.get() == null, "cross-origin mirror received Cookie")
                        assertTrue(
                            mirror.lastAuthorization.get() == null,
                            "cross-origin mirror received Authorization",
                        )
                        assertTrue(
                            mirror.lastCustomHeader.get() == "edge",
                            "non-credential mirror header was not preserved",
                        )
                    } finally {
                        client.shutdown()
                        dir.deleteRecursively()
                    }
                }
            }
        }
    }
}

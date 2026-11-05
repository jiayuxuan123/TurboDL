package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RedirectHeaderIsolationTest {
    private class TargetServer(private val payload: ByteArray) : AutoCloseable {
        private val executor = Executors.newFixedThreadPool(4)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        val cookie = AtomicReference<String?>()
        val authorization = AtomicReference<String?>()

        init {
            server.createContext("/file") { exchange -> serve(exchange) }
            server.executor = executor
            server.start()
        }

        private fun serve(exchange: HttpExchange) {
            cookie.set(exchange.requestHeaders.getFirst("Cookie"))
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            val raw = requireNotNull(exchange.requestHeaders.getFirst("Range"))
            val match = requireNotNull(Regex("bytes=(\\d+)-(\\d+)").matchEntire(raw))
            val start = match.groupValues[1].toInt()
            val end = minOf(match.groupValues[2].toInt(), payload.lastIndex)
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
            exchange.sendResponseHeaders(206, (end - start + 1).toLong())
            exchange.responseBody.use { it.write(payload, start, end - start + 1) }
            exchange.close()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `cross-origin redirect does not inherit credentials`() = runBlocking {
        withTimeout(15_000) {
            val payload = ByteArray(256 * 1024) { ((it * 17 + 9) % 251).toByte() }
            TargetServer(payload).use { target ->
                val executor = Executors.newFixedThreadPool(2)
                val front = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                front.createContext("/orig") { exchange ->
                    exchange.responseHeaders.add(
                        "Location",
                        "http://127.0.0.1:${target.port}/file",
                    )
                    exchange.sendResponseHeaders(302, -1)
                    exchange.close()
                }
                front.executor = executor
                front.start()
                val dir = Files.createTempDirectory("redirect-header-isolation-").toFile()
                val output = File(dir, "out.bin")
                val client = TurboClient(
                    TurboConfig(
                        workDir = File(dir, "work"),
                        maxConnectionsPerTask = 2,
                        maxConcurrentTasks = 1,
                        slowStart = false,
                        adaptiveConcurrency = false,
                        warmUpConnections = false,
                        skipProbeWhenSizeKnown = true,
                        segmentsPerConnection = 1,
                        blockSize = 64 * 1024,
                        minSegmentSize = 32 * 1024,
                    )
                )
                try {
                    val id = client.submit(
                        DownloadRequest(
                            url = "http://127.0.0.1:${front.address.port}/orig",
                            destination = output,
                            knownSize = payload.size.toLong(),
                            headers = mapOf(
                                "Cookie" to "session=secret",
                                "Authorization" to "Bearer secret",
                            ),
                        )
                    )
                    val result = client.await(id)
                    assertTrue(result.isSuccess, "redirect download failed: ${result.exceptionOrNull()}")
                    assertContentEquals(payload, output.readBytes())
                    assertNull(target.cookie.get(), "cross-origin redirect leaked Cookie")
                    assertNull(target.authorization.get(), "cross-origin redirect leaked Authorization")
                } finally {
                    client.shutdown()
                    front.stop(0)
                    executor.shutdownNow()
                    dir.deleteRecursively()
                }
            }
        }
    }
}

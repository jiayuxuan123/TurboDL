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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RangeNotSatisfiableTest {

    private class Server(handler: (HttpExchange) -> Unit) : AutoCloseable {
        private val executor = Executors.newFixedThreadPool(8)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url: String get() = "http://127.0.0.1:${server.address.port}/file"

        init {
            server.createContext("/file") { exchange ->
                try {
                    handler(exchange)
                } finally {
                    exchange.close()
                }
            }
            server.executor = executor
            server.start()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun payload(size: Int): ByteArray {
        var state = 0x13579BDF
        return ByteArray(size) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            state.toByte()
        }
    }

    @Test
    fun `416 is not success for an incomplete direct-write segment`() = runBlocking {
        Server { it.sendResponseHeaders(416, -1) }.use { server ->
            val writes = AtomicInteger()
            val downloader = SegmentDownloader({ okhttp3.OkHttpClient() })
            val result = downloader.downloadSegmentInto(
                taskId = 1,
                url = server.url,
                pieceStart = 0,
                endProvider = { 99 },
                alreadyWritten = 0,
                writeAt = { _, _, _, length -> writes.addAndGet(length); length },
                headers = emptyMap(),
                onProgress = { _, _ -> },
                buffer = ByteArray(1024),
            )
            assertEquals(SegmentResult.FAILED, result)
            assertEquals(0, writes.get())
        }
    }

    @Test
    fun `416 is not success for an incomplete part-file segment`() = runBlocking {
        val dir = Files.createTempDirectory("turbodl-416-part-").toFile()
        try {
            Server { it.sendResponseHeaders(416, -1) }.use { server ->
                val downloader = SegmentDownloader({ okhttp3.OkHttpClient() })
                val result = downloader.downloadSegment(
                    taskId = 1,
                    url = server.url,
                    start = 0,
                    end = 99,
                    partFile = File(dir, "part"),
                    headers = emptyMap(),
                ) { }
                assertEquals(SegmentResult.FAILED, result)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `transient 416 is retried and the downloaded file is complete`() = runBlocking {
        withTimeout(20_000) {
            val content = payload(512 * 1024)
            val requests = AtomicInteger()
            Server { exchange ->
                val request = requests.getAndIncrement()
                if (request == 0) {
                    exchange.responseHeaders.add("Content-Range", "bytes */${content.size}")
                    exchange.sendResponseHeaders(416, -1)
                } else {
                    val range = requireNotNull(exchange.requestHeaders.getFirst("Range"))
                    val match = requireNotNull(Regex("bytes=(\\d+)-(\\d+)").matchEntire(range))
                    val start = match.groupValues[1].toInt()
                    val end = minOf(match.groupValues[2].toInt(), content.lastIndex)
                    exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${content.size}")
                    exchange.sendResponseHeaders(206, (end - start + 1).toLong())
                    exchange.responseBody.use { it.write(content, start, end - start + 1) }
                }
            }.use { server ->
                val dir = Files.createTempDirectory("turbodl-416-retry-").toFile()
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
                    )
                )
                try {
                    val id = client.submit(
                        DownloadRequest(
                            url = server.url,
                            destination = output,
                            knownSize = content.size.toLong(),
                        )
                    )
                    val result = client.await(id)
                    assertTrue(
                        result.isSuccess,
                        "A transient 416 must be retried: ${result.exceptionOrNull()}",
                    )
                    assertContentEquals(content, output.readBytes())
                    assertTrue(requests.get() > 1, "The engine did not retry after 416")
                } finally {
                    client.shutdown()
                    dir.deleteRecursively()
                }
            }
        }
    }
}

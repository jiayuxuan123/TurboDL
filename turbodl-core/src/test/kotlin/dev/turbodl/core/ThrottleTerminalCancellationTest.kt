package dev.turbodl.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

class ThrottleTerminalCancellationTest {
    @Test
    fun `throttle budget exhaustion cancels a slow tail instead of hanging forever`() = runBlocking {
        val total = 2L * 1024 * 1024
        val slowStart = total / 2
        val throttledRequests = AtomicInteger(0)
        val slowRequests = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/f.bin") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            val start = range
                ?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                ?: 0L
            if (start == 0L) {
                throttledRequests.incrementAndGet()
                exchange.responseHeaders.add("Retry-After", "0")
                exchange.sendResponseHeaders(503, 0)
                exchange.responseBody.close()
                return@createContext
            }

            slowRequests.incrementAndGet()
            val length = total - slowStart
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.responseHeaders.add("ETag", "\"throttle-test\"")
            exchange.responseHeaders.add(
                "Content-Range",
                "bytes $slowStart-${total - 1}/$total",
            )
            exchange.sendResponseHeaders(206, length)
            try {
                exchange.responseBody.use { output ->
                    var remaining = length
                    while (remaining > 0) {
                        output.write(0)
                        output.flush()
                        remaining--
                        Thread.sleep(100)
                    }
                }
            } catch (_: java.io.IOException) {
                // Expected when the scheduler cancels the doomed slow request.
            }
        }
        server.start()

        val dir = java.nio.file.Files.createTempDirectory("tdlp-throttle-terminal-").toFile()
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 2,
                maxConcurrentTasks = 1,
                maxRetries = 0,
                warmUpConnections = false,
                slowStart = false,
                adaptiveConcurrency = false,
                backpressureConsecutiveFailures = 0,
                tailAssist = false,
                segmentsPerConnection = 1,
                blockSize = slowStart,
                minSegmentSize = slowStart,
                stallTimeoutMs = 2_000,
            ),
        )
        try {
            val out = File(dir, "out.bin")
            val id = client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:${server.address.port}/f.bin",
                    destination = out,
                    knownSize = total,
                ),
                dir,
            )
            val result = try {
                withTimeout(60_000) { client.await(id) }
            } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
                error(
                    "任务未在限流终态后退出：state=${client.progress.value[id]?.state} " +
                        "throttled=${throttledRequests.get()} slow=${slowRequests.get()}",
                )
            }
            assertTrue(
                result.isFailure,
                "限流预算耗尽后任务必须以失败结束，不能继续等待涓流连接",
            )
            assertTrue(
                result.exceptionOrNull()?.message.orEmpty().contains("持续被限流"),
                "失败原因应保留限流归因：${result.exceptionOrNull()?.message}",
            )
        } finally {
            client.shutdown()
            server.stop(0)
            dir.deleteRecursively()
        }
    }
}

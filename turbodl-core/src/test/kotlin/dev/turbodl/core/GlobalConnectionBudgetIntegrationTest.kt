package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

class GlobalConnectionBudgetIntegrationTest {

    @Test
    fun `two tasks share maxTotalConnections`() = runBlocking {
        val payload = ByteArray(16 * 1024 * 1024) { ((it * 11 + 3) % 256).toByte() }
        val activeSegments = AtomicInteger(0)
        val peakSegments = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/f.bin") { ex: HttpExchange ->
            val range = ex.requestHeaders.getFirst("Range")
            if (range == null || range == "bytes=0-0") {
                ex.sendResponseHeaders(204, -1)
                ex.close()
                return@createContext
            }
            val current = activeSegments.incrementAndGet()
            peakSegments.updateAndGet { maxOf(it, current) }
            try {
                val m = Regex("bytes=(\\d+)-(\\d+)").find(range)
                    ?: run {
                        ex.sendResponseHeaders(416, -1)
                        ex.close()
                        return@createContext
                    }
                val s = m.groupValues[1].toInt()
                val e = m.groupValues[2].toInt().coerceAtMost(payload.size - 1)
                val len = e - s + 1
                ex.responseHeaders.add("Content-Range", "bytes $s-$e/${payload.size}")
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { out ->
                    var pos = s
                    while (pos <= e) {
                        val n = minOf(64 * 1024, e - pos + 1)
                        out.write(payload, pos, n)
                        pos += n
                        Thread.sleep(3)
                    }
                }
            } finally {
                activeSegments.decrementAndGet()
            }
        }
        server.executor = Executors.newFixedThreadPool(64)
        server.start()

        val dir = File(System.getProperty("java.io.tmpdir"), "global-budget-${System.nanoTime()}")
            .apply { mkdirs(); deleteOnExit() }
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 8,
                maxConcurrentTasks = 2,
                maxTotalConnections = 12,
                warmUpConnections = false,
                slowStart = false,
                adaptiveConcurrency = false,
                workDir = dir,
            )
        )
        try {
            val url = "http://127.0.0.1:${server.address.port}/f.bin"
            val a = client.submit(DownloadRequest(url, File(dir, "a.bin"), knownSize = payload.size.toLong()))
            val b = client.submit(DownloadRequest(url, File(dir, "b.bin"), knownSize = payload.size.toLong()))
            assertTrue(client.await(a).isSuccess)
            assertTrue(client.await(b).isSuccess)
            assertTrue(
                peakSegments.get() <= 12,
                "两个任务实际开了 ${peakSegments.get()} 条 Range 连接，超过全局预算 12",
            )
        } finally {
            client.shutdown()
            server.stop(0)
            dir.deleteRecursively()
        }
    }
}

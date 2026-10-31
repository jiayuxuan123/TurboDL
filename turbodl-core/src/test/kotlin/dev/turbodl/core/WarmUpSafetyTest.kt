package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 预热只负责把 DNS/TCP/TLS 热起来，不能因为服务器忽略 Range 就把整文件读一遍。
 *
 * 旧实现的 `readBytes()` 在 200 响应上会读取完整 body；多个预热连接会同时
 * 制造额外流量、堆分配和正式分片的带宽竞争。
 */
class WarmUpSafetyTest {

    @Test
    fun `warmUp never downloads the whole file when Range is ignored`() = runBlocking {
        val payload = ByteArray(16 * 1024 * 1024) { ((it * 17 + 9) % 256).toByte() }
        val sent = AtomicLong(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/f.bin") { ex: HttpExchange ->
            ex.responseHeaders.add("Content-Type", "application/octet-stream")
            ex.sendResponseHeaders(200, payload.size.toLong())
            runCatching {
                ex.responseBody.use { out ->
                    var offset = 0
                    while (offset < payload.size) {
                        val n = minOf(64 * 1024, payload.size - offset)
                        out.write(payload, offset, n)
                        sent.addAndGet(n.toLong())
                        offset += n
                        Thread.sleep(2)
                    }
                }
            }
        }
        server.executor = Executors.newFixedThreadPool(4)
        server.start()

        val config = TurboConfig(
            maxConnectionsPerTask = 4,
            maxConcurrentTasks = 1,
            warmUpConnections = false,
            readTimeoutMs = 5_000,
        )
        val downloader = SegmentDownloader(
            clientProvider = { HttpClientFactory.build(config, HttpClientFactory.ProtocolPreference.H1_ONLY) },
            streamClientProvider = null,
        )
        try {
            downloader.warmUp(
                url = "http://127.0.0.1:${server.address.port}/f.bin",
                headers = emptyMap(),
                connections = 4,
                timeoutMs = 3_000,
                taskId = 1,
            )
            // Allow the server side to observe the client's close.
            Thread.sleep(200)
            assertTrue(
                sent.get() < payload.size,
                "预热读取了 ${sent.get()} / ${payload.size} 字节；Range 被忽略时只能关闭响应体",
            )
        } finally {
            server.stop(0)
        }
    }
}

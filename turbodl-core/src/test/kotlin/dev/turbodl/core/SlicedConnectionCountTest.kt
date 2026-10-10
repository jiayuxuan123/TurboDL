package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P14 的**本地机制验证**（默认套件会跑）：分片链路的 HTTP/1.1 路径确实会开出 N 条**独立** TCP 连接。
 *
 * ## 它回答 P14 的哪一半
 *
 * `TurboConfig.forceHttp1` 的注释断言了一个机制：
 * > HTTP/2 会把所有并发请求**多路复用**到同一条 TCP 连接上，共享单个拥塞/流控窗口，
 * > 于是"开 64 个线程也只有单连接速度"；HTTP/1.1 下每个并发请求各自建连，
 * > 各自拥有独立拥塞窗口，才能真正获得多连接加速。
 *
 * 这句话的后半段（h1 各自建连）**可以在本地无依赖地验证** —— 只靠 OkHttp 自带的
 * `EventListener` 数连接即可。前半段（h2 是否真的塌缩）需要 h2 服务端，
 * 由 `HttpVersionComparisonTest` 在真实网络上量（标注为 opt-in）。
 *
 * 为什么值得单独测量"h1 到底开了几条连接"：如果分片请求**实际上复用了同一条连接**，
 * 那么整个多线程加速的前提就是假的，而表面的"线程数 128"会掩盖这一点。
 * 这是那种"看起来在工作、其实没有"的失败模式，必须有测试钉住。
 *
 * ## 为什么不用自建 h2 服务端
 *
 * 试过 netty 的 h2 编解码器，代价是给测试编译引入第三方依赖（且 `SelfSignedCertificate`
 * 在无 BouncyCastle 的 JDK 17 上直接抛异常）。为了一个测量破坏本项目
 * "离线可构建、依赖极简"的属性不划算 —— 所以本地只测 h1 这一半，
 * h2 那一半交给真实网络（GitHub 的 CDN 支持 h2）。
 */
class SlicedConnectionCountTest {

    private companion object {
        const val SIZE = 8 * 1024 * 1024

        /** 安全端口范围（与工作区约定一致）：不得使用 8787/8788。 */
        val SAFE_PORT_RANGE = 8791..8850

        fun freeSafePort(): Int? {
            for (port in SAFE_PORT_RANGE) {
                try {
                    ServerSocket(port).use { return port }
                } catch (_: java.io.IOException) {
                    // 换下一个
                }
            }
            return null
        }
    }

    /** 不限速 Range 服务器；记录**服务端看到的并发连接数**作为交叉验证。 */
    private class Server(private val payload: ByteArray, port: Int) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        val port: Int = server.address.port
        private val concurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        init {
            server.createContext("/f.bin") { ex: HttpExchange -> handle(ex) }
            // 线程池要够大：本测试就是要让 N 条连接同时在场
            server.executor = Executors.newFixedThreadPool(320)
            server.start()
        }

        private fun handle(ex: HttpExchange) {
            val cur = concurrent.incrementAndGet()
            peakConcurrent.updateAndGet { maxOf(it, cur) }
            try {
                val size = payload.size
                val m = ex.requestHeaders.getFirst("Range")
                    ?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                if (m == null) {
                    ex.sendResponseHeaders(200, size.toLong())
                    ex.responseBody.use { it.write(payload) }
                    return
                }
                val s = m.groupValues[1].toInt()
                val e = m.groupValues[2].toIntOrNull() ?: (size - 1)
                val end = minOf(e, size - 1)
                if (s > end) { ex.sendResponseHeaders(416, -1); ex.close(); return }
                val len = end - s + 1
                ex.responseHeaders.add("Content-Range", "bytes $s-$end/$size")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { out ->
                    // 慢一点吐，让所有分片连接都能同时在场（否则回环上瞬完，数不到峰值）
                    var off = s
                    val chunk = 32 * 1024
                    while (off <= end) {
                        val n = minOf(chunk, end - off + 1)
                        out.write(payload, off, n)
                        out.flush()
                        off += n
                        Thread.sleep(2)
                    }
                }
            } finally {
                concurrent.decrementAndGet()
            }
        }

        fun stop() = server.stop(0)
    }

    /** 数 OkHttp **真实新建**了多少条连接，以及它们的协商协议。 */
    private class CountingListener : EventListener() {
        val distinctConnections = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val protocols = java.util.concurrent.ConcurrentHashMap.newKeySet<Protocol>()
        val connectStarts = AtomicInteger(0)

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: java.net.Proxy) {
            connectStarts.incrementAndGet()
        }

        override fun connectionAcquired(call: Call, connection: Connection) {
            distinctConnections.add(connection.socket().localPort.toString())
            protocols.add(connection.protocol())
        }
    }

    @Test
    fun `h1 segment requests really open N distinct connections`() = runBlocking {
        val port = freeSafePort() ?: run {
            println("[CONNCOUNT] 安全端口全部占用，跳过（8791-8850）")
            return@runBlocking
        }
        val payload = ByteArray(SIZE) { ((it * 19 + 7) % 256).toByte() }
        val srv = Server(payload, port)
        val out = File.createTempFile("conncount", ".bin").apply { deleteOnExit() }

        val listener = CountingListener()
        // 直接构一个与引擎分片链路**同构**的客户端：H1_ONLY（见 HttpClientFactory 的默认偏好）
        val client = OkHttpClient.Builder()
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 1024; maxRequestsPerHost = 1024 })
            .connectionPool(okhttp3.ConnectionPool(256, 300, TimeUnit.SECONDS))
            .protocols(listOf(Protocol.HTTP_1_1))
            .eventListener(listener)
            .build()

        val connections = 16
        val chunk = SIZE / connections
        try {
            // 并发发 N 个 Range 请求 —— 复刻分片下载的请求形状
            val futures = (0 until connections).map { i ->
                val s = i * chunk
                val e = if (i == connections - 1) SIZE - 1 else (s + chunk - 1)
                client.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:${srv.port}/f.bin")
                        .header("Range", "bytes=$s-$e")
                        .build()
                ).execute()
            }
            futures.forEach { it.use { r -> assertEquals(206, r.code, "每个分片都应拿到 206") } }

            val distinct = listener.distinctConnections.size
            println("=== 分片连接数验证（$connections 个并发 Range 请求）===")
            println("  OkHttp 新建连接数（按本地端口去重）: $distinct")
            println("  协商到的协议: ${listener.protocols}")
            println("  服务端观测峰值并发: ${srv.peakConcurrent.get()}")

            assertTrue(
                listener.protocols.all { it == Protocol.HTTP_1_1 },
                "h1 客户端不应协商出 h2，实际 ${listener.protocols}",
            )
            assertTrue(
                distinct >= connections / 2,
                "h1 下每个并发分片应各占一条连接（至少接近 $connections 条）；" +
                    "实际只有 $distinct 条 —— 若连接被复用，多线程加速的前提就不成立",
            )
            assertTrue(
                srv.peakConcurrent.get() >= connections / 2,
                "服务端应观测到并发的分片请求，实际峰值 ${srv.peakConcurrent.get()}",
            )
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            srv.stop()
            out.delete()
        }
    }
}

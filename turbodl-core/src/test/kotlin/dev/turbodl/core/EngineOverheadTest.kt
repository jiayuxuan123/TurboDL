package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * 【分离变量】引擎的调度开销 vs 纯粹的 HTTP 往返开销。
 *
 * ## 为什么需要这一层
 *
 * `FanoutCostTest` 测出的曲线是：回环、无限速、16MB 文件下
 *
 * | 连接数 | 128 | 64 | 32 | 16 |
 * |---|---|---|---|---|
 * | 吞吐 MB/s | 9.7 | 23.3 | 40.7 | **56.9** |
 *
 * 加连接**反而慢 5.9 倍**。这说明瓶颈在客户端自己身上，但 `FanoutCostTest` 分不出
 * 它到底来自「引擎的调度/记账/合并」还是「本机回环 + HttpServer 处理 N 个并发请求的能力」。
 *
 * 本用例把**同一批 Range 请求**用两种方式各跑一遍：
 *  - **A 组**：直接 `OkHttp` + N 个协程，不经过引擎任何代码；
 *  - **B 组**：`TurboClient` 走完整引擎。
 *
 * 判读：若 A 组随请求数基本不变而 B 组暴跌 → 引擎开销；若两组一起跌 → 是本机/服务器。
 *
 * 默认跳过（`TURBODL_BENCH=1` 启用）：这是诊断台，不是回归断言。
 */
class EngineOverheadTest {

    private companion object {
        const val SIZE = 64 * 1024 * 1024
        const val REPEAT = 2
    }

    private class PlainServer(private val payload: ByteArray) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        val requests = AtomicInteger(0)

        /**
         * 客户端源端口集合 —— 用来判断**连接复用率**。
         * 端口数远小于请求数 = keep-alive 生效；端口数 ≈ 请求数 = 每请求新建连接。
         * 真实 HTTPS 上每个新连接都有 TCP + TLS 握手（移动网络 200~600ms），
         * 所以这个比值直接决定实战速度。
         */
        val clientPorts = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

        init {
            server.createContext("/f.bin") { ex: HttpExchange ->
                requests.incrementAndGet()
                runCatching { clientPorts.add(ex.remoteAddress.port) }
                try {
                    val size = payload.size
                    val range = ex.requestHeaders.getFirst("Range")
                    ex.responseHeaders.add("Content-Type", "application/octet-stream")
                    ex.responseHeaders.add("Accept-Ranges", "bytes")
                    val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                    if (m == null) {
                        ex.sendResponseHeaders(200, size.toLong())
                        ex.responseBody.use { it.write(payload) }
                        return@createContext
                    }
                    val s = m.groupValues[1].toInt()
                    val e = m.groupValues[2].toIntOrNull() ?: (size - 1)
                    if (s >= size) { ex.sendResponseHeaders(416, -1); ex.close(); return@createContext }
                    val end = minOf(e, size - 1)
                    val len = end - s + 1
                    ex.responseHeaders.add("Content-Range", "bytes $s-$end/$size")
                    ex.sendResponseHeaders(206, len.toLong())
                    ex.responseBody.use { it.write(payload, s, len) }
                } finally {
                    ex.close()
                }
            }
            server.executor = Executors.newFixedThreadPool(320)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    /** A 组：纯 OkHttp，N 个并发 Range 请求，写到临时文件。 */
    private fun rawFetch(
        port: Int,
        segments: Int,
        client: OkHttpClient,
        sinkDir: File,
    ): Long = runBlocking {
        val block = SIZE / segments
        val per = LongArray(segments)
        withContext(Dispatchers.IO) {
            (0 until segments).map { i ->
                async {
                    val s = i.toLong() * block
                    val e = if (i == segments - 1) SIZE - 1L else s + block - 1
                    val req = Request.Builder()
                        .url("http://127.0.0.1:$port/f.bin")
                        .header("Range", "bytes=$s-$e")
                        .header("Accept-Encoding", "identity")
                        .get().build()
                    // 只读不落盘：本组要测的是"HTTP 往返本身"，把磁盘 I/O 排除掉。
                    client.newCall(req).execute().use { resp ->
                        val ins = resp.body?.byteStream()
                        val buf = ByteArray(64 * 1024)
                        var n = 0L
                        while (true) {
                            val r = ins?.read(buf) ?: -1
                            if (r <= 0) break
                            n += r
                        }
                        per[i] = n
                    }
                }
            }.awaitAll()
        }
        per.sum()
    }

    private fun engineFetch(
        port: Int,
        conns: Int,
        segmentsPerConn: Int,
        tailAssist: Boolean = true,
        blockSize: Long = 16L * 1024 * 1024,
        adaptive: Boolean = true,
        label: String = "",
    ): Long {
        val out = File.createTempFile("overhead", ".bin").apply { deleteOnExit() }
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = conns,
                maxConcurrentTasks = 1,
                segmentsPerConnection = segmentsPerConn,
                warmUpConnections = false,
                slowStart = false,
                tailAssist = tailAssist,
                blockSize = blockSize,
                adaptiveConcurrency = adaptive,
                progressIntervalMs = 500,
            )
        )
        return try {
            runBlocking {
                val t0 = System.nanoTime()
                val id = client.submit(
                    DownloadRequest(url = "http://127.0.0.1:$port/f.bin", destination = out)
                )
                val res = client.await(id)
                val ms = (System.nanoTime() - t0) / 1_000_000
                val size = res.getOrNull()?.length() ?: out.length()
                println("      ${label.ifBlank { "引擎 ${conns}c/${conns * segmentsPerConn}s" }}: " +
                    "${ms}ms  ${if (ms > 0) size * 1000 / ms / 1024 / 1024 else 0} MB/s")
                size
            }
        } finally {
            runCatching { client.shutdown() }
            runCatching { out.delete() }
        }
    }

    @Test
    fun `raw okhttp vs engine`() {
        if (System.getenv("TURBODL_BENCH") != "1") {
            println("[OVERHEAD] 跳过（设置 TURBODL_BENCH=1 启用）")
            return
        }
        val payload = ByteArray(SIZE) { (it * 31).toByte() }
        val srv = PlainServer(payload)
        val client = HttpClientFactory.build(
            TurboConfig(warmUpConnections = false),
            HttpClientFactory.ProtocolPreference.H1_ONLY,
        )
        try {
            println("================================================================")
            println("[OVERHEAD] SIZE=${SIZE / 1024 / 1024}MB  回环  无限速")
            // 先用一条单请求确认服务器可达，把"服务器没起来"与"引擎有问题"分开
            runCatching {
                val probe = Request.Builder()
                    .url("http://127.0.0.1:${srv.port}/f.bin")
                    .header("Range", "bytes=0-15").get().build()
                client.newCall(probe).execute().use { r ->
                    println("[OVERHEAD] 连通性检查: ${r.code}, ${r.body?.contentLength()} bytes")
                }
            }.onFailure { println("[OVERHEAD] 连通性检查失败: $it") }
            for (segments in listOf(64, 128, 256)) {
                val times = (1..REPEAT).map {
                    val t0 = System.nanoTime()
                    val n = rawFetch(srv.port, segments, client, File(System.getProperty("java.io.tmpdir")))
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    require(n == SIZE.toLong()) { "rawFetch 只读到 $n" }
                    ms
                }.sorted()
                val med = times[times.size / 2]
                println("   A 纯OkHttp  ${segments}请求: ${med}ms  ${SIZE / 1024 / 1024 * 1000L / med} MB/s")
            }
            println("----------------------------------------------------------------")
            println("   B1 引擎 16 连接")
            repeat(REPEAT) { engineFetch(srv.port, 16, 4, label = "16c/64s") }

            println("   B2 引擎 32 连接")
            repeat(REPEAT) { engineFetch(srv.port, 32, 4, label = "32c/128s") }

            println("   B3 引擎 128 连接")
            repeat(REPEAT) { engineFetch(srv.port, 128, 4, label = "128c/512s") }

            println("   B4 引擎 128 连接（关掉自适应并发）")
            repeat(REPEAT) {
                engineFetch(srv.port, 128, 4, adaptive = false, label = "128c 无自适应")
            }

            println("   B5 引擎 128 连接（关掉收尾托管）")
            repeat(REPEAT) {
                engineFetch(srv.port, 128, 4, tailAssist = false, label = "128c 无托管")
            }

            println("----------------------------------------------------------------")
            srv.requests.set(0); srv.clientPorts.clear()
            engineFetch(srv.port, 32, 4, label = "复用率测量")
            val req = srv.requests.get()
            val ports = srv.clientPorts.size.coerceAtLeast(1)
            println("   [连接复用] 请求 $req 次 / 源端口 $ports 个 = 每连接 ${"%.1f".format(req.toDouble() / ports)} 次请求")
            println("================================================================")
        } finally {
            srv.stop()
            runCatching { client.dispatcher.executorService.shutdown() }
            runCatching { client.connectionPool.evictAll() }
        }
    }
}

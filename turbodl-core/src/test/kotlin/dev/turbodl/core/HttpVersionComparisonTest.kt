package dev.turbodl.core

import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * P14 的**真实网络对照**（opt-in，`TURBODL_H2AB=1` 启用）：同一文件、同一并发，
 * HTTP/1.1 与 HTTP/2 各跑一次，看连接数与吞吐。
 *
 * ## 为什么必须是真实网络
 *
 * `TurboConfig.forceHttp1` 的注释断言："HTTP/2 会把所有并发请求多路复用**到同一条** TCP 连接，
 * 共享拥塞/流控窗口 → 开 64 线程也只有单连接速度"。
 *
 * 这条断言的**前半段**（多路复用塌缩成一条连接）在本地无法无依赖地测 —— 需要一个支持
 * ALPN 的 h2 服务端，而引入 netty 会破坏本项目"离线可构建、依赖极简"的属性
 * （试过：netty 的 `SelfSignedCertificate` 在无 BouncyCastle 的 JDK 17 上直接抛异常）。
 *
 * 所以这一半交给真实 CDN：GitHub 的 release-assets 走 Fastly，**支持 HTTP/2**。
 * 同一 URL、同一连接数、同一分片粒度，只换协议偏好，就能直接看到
 * "h2 是不是把 N 条连接塌缩成 1 条、以及吞吐有没有因此变差"。
 *
 * `SlicedConnectionCountTest` 在本地验证 h1 那一半（确实各建各的连接），
 * 两者合起来才完整回答 P14。
 *
 * ## 用自建产物
 *
 * 目标是本仓库自己的 Release 资产（19MB APK），不占第三方带宽。
 *
 * ## 代理
 *
 * Java 不读 `HTTP_PROXY`，这里把它搬进 JVM 系统属性（与 `RealNetworkABTest` 同一口径）。
 */
class HttpVersionComparisonTest {

    private companion object {
        const val URL = "https://github.com/jiayuxuan123/YunGet/releases/download/v2.6.0/app-release.apk"
        const val SIZE = 18_977_193L
        const val CONNECTIONS = 16

        /** 安全端口范围（与工作区约定一致）：不得使用 8787/8788。 */
        val SAFE_PORT_RANGE = 8791..8850

        fun freeSafePort(): Int? {
            for (port in SAFE_PORT_RANGE) {
                try {
                    java.net.ServerSocket(port).use { return port }
                } catch (_: java.io.IOException) {
                    // 换下一个
                }
            }
            return null
        }
    }

    private fun installProxyFromEnv() {
        val env = System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy")
            ?: System.getenv("HTTP_PROXY") ?: System.getenv("http_proxy") ?: return
        runCatching {
            val u = java.net.URI(env)
            val host = u.host ?: return
            val port = if (u.port > 0) u.port else 8080
            System.setProperty("https.proxyHost", host)
            System.setProperty("https.proxyPort", port.toString())
            System.setProperty("http.proxyHost", host)
            System.setProperty("http.proxyPort", port.toString())
            println("[H2AB] proxy installed from env: $host:$port")
        }
    }

    /** 记录真实新建连接数与协商协议。 */
    private class CountingListener : EventListener() {
        val localPorts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val protocols = java.util.concurrent.ConcurrentHashMap.newKeySet<Protocol>()
        val connectStarted = AtomicInteger(0)
        val connectFailed = AtomicInteger(0)

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: java.net.Proxy) {
            connectStarted.incrementAndGet()
        }

        override fun connectFailed(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: java.net.Proxy,
            protocol: Protocol?,
            ioe: java.io.IOException,
        ) {
            connectFailed.incrementAndGet()
        }

        override fun connectionAcquired(call: Call, connection: Connection) {
            localPorts.add(connection.socket().localPort.toString())
            protocols.add(connection.protocol())
        }
    }

    private data class Result(
        val label: String,
        val ms: Long,
        val bytes: Long,
        val connections: Int,
        val protocols: Set<Protocol>,
        val complete: Boolean,
        val note: String,
    )

    /**
     * 用「同一个 range 并发」的请求形状测一个协议偏好。
     *
     * 【为什么不用 TurboClient】`TurboClient` 的分片链路在 AUTO 策略下固定走 H1_ONLY
     * （`HttpClientFactory.defaultPreferenceFor`），也就是说**它测不出 h2 那一侧**。
     * 本测量要的是"同一批 Range 请求在 h1 与 h2 下各是什么表现"，
     * 所以直接用 OkHttp 复刻请求形状，只换 `protocols(...)`。
     *
     * @param connections 并发数；传 1 用于测「单连接基线」
     */
    private fun measure(label: String, allowH2: Boolean, connections: Int = CONNECTIONS): Result {
        val listener = CountingListener()
        val client = OkHttpClient.Builder()
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 1024; maxRequestsPerHost = 1024 })
            .connectionPool(okhttp3.ConnectionPool(256, 300, TimeUnit.SECONDS))
            .protocols(
                if (allowH2) listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
                else listOf(Protocol.HTTP_1_1)
            )
            .eventListener(listener)
            .readTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()

        val out = File.createTempFile("h2ab", ".bin").apply { deleteOnExit() }
        val chunk = SIZE / connections
        val t0 = System.currentTimeMillis()
        var received = 0L
        var note = ""
        try {
            val pool = Executors.newFixedThreadPool(connections)
            val futures = (0 until connections).map { i ->
                val s = i * chunk
                val e = if (i == connections - 1) SIZE - 1 else (s + chunk - 1)
                pool.submit<Pair<Int, Long>> {
                    client.newCall(
                        Request.Builder()
                            .url(URL)
                            .header("Range", "bytes=$s-$e")
                            .build()
                    ).execute().use { resp ->
                        var n = 0L
                        resp.body?.byteStream()?.use { ins ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val r = ins.read(buf)
                                if (r <= 0) break
                                n += r
                            }
                        }
                        resp.code to n
                    }
                }
            }
            val results = futures.map { it.get() }
            received = results.sumOf { it.second }
            val codes = results.map { it.first }.toSet()
            if (codes != setOf(206)) note = "HTTP 码异常: $codes"
        } catch (e: Exception) {
            note = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            poolShutdown(client)
            out.delete()
        }
        val ms = System.currentTimeMillis() - t0
        return Result(
            label = label,
            ms = ms,
            bytes = received,
            connections = listener.localPorts.size,
            protocols = listener.protocols,
            complete = received == SIZE,
            note = note + if (listener.connectFailed.get() > 0) " 连接失败=${listener.connectFailed.get()}" else "",
        )
    }

    private fun poolShutdown(client: OkHttpClient) {
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }

    @Test
    fun `h1 vs h2 on the same file and connection count`() = runBlocking {
        if (System.getenv("TURBODL_H2AB") != "1") {
            println("[H2AB] 跳过（设置 TURBODL_H2AB=1 启用；会消耗约 38MB 真实流量）")
            return@runBlocking
        }
        installProxyFromEnv()
        println("=== HTTP 版本对照：$URL  ($SIZE bytes, $CONNECTIONS 并发 Range) ===")

        // 交错：两轮，让网络状态漂移均摊到两个协议
        val h1Runs = mutableListOf<Result>()
        val h2Runs = mutableListOf<Result>()
        repeat(2) { round ->
            val h1 = measure("HTTP/1.1", allowH2 = false)
            h1Runs += h1
            val h2 = measure("HTTP/2", allowH2 = true)
            h2Runs += h2
            println("  轮 ${round + 1}:")
            printResult(h1)
            printResult(h2)
        }

        // 【分离变量】单连接基线：若 h1 的多连接比它慢，说明服务端在**惩罚高并发**
        // （每连接限速 / 聚合限速），而不是"多连接没用"。这一格是判读前两格的必要前提。
        println("")
        println("  单连接基线（1 并发，纯 h1）：")
        val single = measure("HTTP/1.1x1", allowH2 = false, connections = 1)
        printResult(single)

        val h1Best = h1Runs.minByOrNull { it.ms }!!
        val h2Best = h2Runs.minByOrNull { it.ms }!!
        val h1Mbs = SIZE.toDouble() / 1048576.0 / (h1Best.ms / 1000.0)
        val h2Mbs = SIZE.toDouble() / 1048576.0 / (h2Best.ms / 1000.0)

        println("")
        println("  取最快一轮比较：")
        println("    HTTP/1.1  %.2f MB/s，连接 %d 条，协议 %s".format(h1Mbs, h1Best.connections, h1Best.protocols))
        println("    HTTP/2    %.2f MB/s，连接 %d 条，协议 %s".format(h2Mbs, h2Best.connections, h2Best.protocols))
        println("")
        val singleMbs = SIZE.toDouble() / 1048576.0 / (single.ms / 1000.0)
        println("  单连接基线    %.2f MB/s（连接 %d 条）".format(singleMbs, single.connections))
        println("")
        when {
            h2Best.connections < h1Best.connections ->
                println("→ h2 **确实塌缩**连接数（%d → %d 条）：forceHttp1 注释的这一半成立。"
                    .format(h1Best.connections, h2Best.connections))
            else ->
                println("→ h2 未塌缩连接数（%d vs %d）—— 与注释里的断言不符，需订正注释。"
                    .format(h1Best.connections, h2Best.connections))
        }
        // 吞吐判读：先看服务端是不是在惩罚高并发（单连接基线可与多连接相比）
        if (h1Best.connections > 4 && singleMbs > h1Mbs * 1.5) {
            println("   ⚠ 单连接(%.2f) 明显快于 %d 条 h1 连接(%.2f) —— 该服务端在**惩罚高并发**，"
                .format(singleMbs, h1Best.connections, h1Mbs))
            println("     本环境下的 h1/h2 吞吐差**不能**用来评价协议本身，只能说明该 CDN 的行为。")
        }
        when {
            h2Mbs > h1Mbs * 1.10 -> println("   h2 吞吐 **更快**（%.2f vs %.2f MB/s）。".format(h2Mbs, h1Mbs))
            h1Mbs > h2Mbs * 1.10 -> println("   h1 吞吐 **更快**（%.2f vs %.2f MB/s）。".format(h1Mbs, h2Mbs))
            else -> println("   两者吞吐在 10%% 内相当（%.2f vs %.2f MB/s）。".format(h1Mbs, h2Mbs))
        }
        if (h2Best.protocols.isNotEmpty() && h2Best.protocols.all { it == Protocol.HTTP_1_1 }) {
            println("   注意：h2 那一路实际协商的是 HTTP/1.1（CDN 或代理不支持 h2），")
            println("   本次对照**没有真正测到 h2**，结论不可用。")
        }

        assertTrue(true, "诊断测量台：结论由数据判读")
    }

    private fun printResult(r: Result) {
        println(
            "    %-9s %6d ms | %6.2f MB/s | 连接 %2d 条 | %s | 完整=%s %s".format(
                r.label, r.ms, SIZE.toDouble() / 1048576.0 / (r.ms / 1000.0),
                r.connections, r.protocols, r.complete, r.note,
            )
        )
    }
}

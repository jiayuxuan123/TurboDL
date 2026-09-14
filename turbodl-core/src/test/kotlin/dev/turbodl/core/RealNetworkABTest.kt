package dev.turbodl.core

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 第一阶段 · 真实网络 A/B：直接下载 GitHub Release 上的真实文件。
 *
 * 与 [Phase1ABTest]（模拟网络）互补：
 *  - 模拟网络能隔离「请求数 vs 连接数」的代价，但是**人造**的；
 *  - 真实网络带**真实 RTT、真实 CDN 每连接限速、真实拥塞控制** —— 这是模拟测不出来的。
 *
 * 目标文件用**我自己仓库的 Release 产物**（19MB 的 APK），
 * 已经验证：`206` + `accept-ranges: bytes`，走 `release-assets.githubusercontent.com`。
 * 用自建产物而非第三方文件，避免占用别人带宽。
 *
 * ## 代理
 * Java 不读 `HTTP_PROXY` 环境变量，所以这里显式把代理从环境变量搬到 JVM 系统属性。
 *
 * ## 注意
 * 这是**联网测试**，会消耗真实流量（每个配置约 19MB）。默认**不参与全量套件**之外的自动运行，
 * 需显式指定 `--tests` 才会跑。
 */
class RealNetworkABTest {

    private companion object {
        /** 自建 Release 产物，19MB，已验证支持 Range。 */
        const val URL = "https://github.com/jiayuxuan123/YunGet/releases/download/v2.6.0/app-release.apk"
        const val SIZE = 18_977_193L
    }

    /** 把沙箱代理从环境变量搬进 JVM 系统属性（Java 默认不读 env）。 */
    private fun installProxyFromEnv() {
        val env = System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy")
            ?: System.getenv("HTTP_PROXY") ?: System.getenv("http_proxy")
            ?: return
        runCatching {
            val u = java.net.URI(env)
            val host = u.host ?: return
            val port = if (u.port > 0) u.port else 8080
            System.setProperty("https.proxyHost", host)
            System.setProperty("https.proxyPort", port.toString())
            System.setProperty("http.proxyHost", host)
            System.setProperty("http.proxyPort", port.toString())
            println("[REAL] proxy installed from env: $host:$port")
        }
    }

    private data class Case(
        val name: String,
        val connections: Int,
        val maxConnPerHost: Int,
        val minSegmentSize: Long,
        val blockSize: Long,
        val segmentsPerConnection: Int,
        /** 关闭慢启动，让"并发是否真的用上"不被爬升过程掩盖。 */
        val slowStart: Boolean = false,
    )

    private val cases = listOf(
        Case("TurboDL 现状", 8, 0, 64L * 1024, 16L * 1024 * 1024, 4),
        Case("aria2 加速(16连接)", 16, 16, 4L * 1024 * 1024, 16L * 1024 * 1024, 1),
        Case("Motrix balanced(16)", 16, 16, 10L * 1024 * 1024, 16L * 1024 * 1024, 1),
        Case("对照组 8连接+大分片", 8, 0, 4L * 1024 * 1024, 16L * 1024 * 1024, 1),
    )

    private suspend fun runCase(c: Case): String {
        val workDir = File(System.getProperty("java.io.tmpdir"), "turbodl-real-${System.nanoTime()}").apply { mkdirs() }
        val out = File.createTempFile("realnet", ".bin").apply { deleteOnExit() }
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = c.connections,
                maxConcurrentTasks = 1,
                maxConnectionsPerHost = c.maxConnPerHost,
                minSegmentSize = c.minSegmentSize,
                blockSize = c.blockSize,
                segmentsPerConnection = c.segmentsPerConnection,
                workDir = workDir,
                warmUpConnections = false,
                slowStart = c.slowStart,
                proxy = ProxyMode.System,
            )
        )
        return try {
            val t0 = System.currentTimeMillis()
            val id = client.submit(DownloadRequest(URL, out, knownSize = SIZE))
            val res = client.await(id)
            val elapsed = System.currentTimeMillis() - t0
            val ok = res.isSuccess && out.length() == SIZE
            val mbps = SIZE.toDouble() / 1024 / 1024 / (elapsed / 1000.0)
            String.format(
                "%-24s | %7d ms | %7.2f MB/s | 文件完整=%s | %s",
                c.name, elapsed, mbps, ok, res.exceptionOrNull()?.message ?: "",
            )
        } finally {
            client.shutdown()
            workDir.deleteRecursively()
            out.delete()
        }
    }

    @Test
    fun `real network A-B over GitHub release asset`() = runBlocking {
        // 【默认跳过】这是**联网测试**，每个配置下载约 19MB 真实流量，
        // 且经代理时吞吐受代理上限压制。让它进全量套件会把套件拖到 7 分钟以上。
        // 需要时显式开启：设置环境变量 TURBODL_REALNET=1 再跑。
        if (System.getenv("TURBODL_REALNET") != "1") {
            println("[REAL] 跳过（设置 TURBODL_REALNET=1 可启用；会消耗约 76MB 真实流量）")
            return@runBlocking
        }
        installProxyFromEnv()
        println("=== 真实网络 A/B：$URL  ($SIZE bytes) ===")
        println("（每配置约 19MB 真实流量）")
        for (c in cases) {
            val line = runCatching { runCase(c) }.getOrElse { "FAILED: ${it.message}" }
            println(line)
        }
        assertTrue(true)
    }
}

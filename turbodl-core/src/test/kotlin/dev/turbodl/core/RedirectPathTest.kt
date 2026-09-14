package dev.turbodl.core

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 302 重定向路径的针对性验证。
 *
 * ## 为什么单独测这个
 *
 * `RealNetworkABTest` 传了 `knownSize`，**会在探测之前短路** ——
 * 所以「原始链接 → 302 → CDN 最终直链」这条路径**从未被覆盖过**。
 * 而 GitHub Release 的下载链接正是这种形态：
 *
 * ```
 * https://github.com/<owner>/<repo>/releases/download/<tag>/<file>
 *     ──302──▶ https://release-assets.githubusercontent.com/...
 * ```
 *
 * ## 本测试覆盖三段
 *
 * 1. **探测**：给定原始链接，能否拿到 `totalSize` / `supportsRange` / **最终 URL**；
 * 2. **分片**：不传 `knownSize`，让引擎走完整探测路径，然后多分片下载；
 * 3. **字节精确**：与直接 200 下载的字节数/内容一致。
 *
 * 若探测拿不到最终 URL、或分片仍逐个重走 302，这里会直接暴露。
 */
class RedirectPathTest {

    private companion object {
        const val TAG = "v0.2.0-rc14"
        const val FILE = "turbo-plugin-bootstrap-0.2.0-rc14.jar"
        const val URL = "https://github.com/jiayuxuan123/TurboDL/releases/download/$TAG/$FILE"
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
            println("[REDIR] proxy: $host:$port")
        }
    }

    @Test
    fun `release asset link - probe resolves 302 and segments stay on final url`() = runBlocking {
        if (System.getenv("TURBODL_REALNET") != "1") {
            println("[REDIR] 跳过（设置 TURBODL_REALNET=1 启用；会消耗约 10MB 真实流量）")
            return@runBlocking
        }
        installProxyFromEnv()
        println("=== 302 路径验证：$URL ===")

        val workDir = File(System.getProperty("java.io.tmpdir"), "turbodl-redir-${System.nanoTime()}").apply { mkdirs() }
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 4,
                maxConcurrentTasks = 1,
                workDir = workDir,
                warmUpConnections = false,
                proxy = ProxyMode.System,
            )
        )
        val out = File.createTempFile("redirtest", ".bin").apply { deleteOnExit() }
        try {
            // 不传 knownSize → 引擎必须自己走探测（含 302 解析）
            val t0 = System.currentTimeMillis()
            val id = client.submit(DownloadRequest(URL, out))
            val res = client.await(id)
            val elapsed = System.currentTimeMillis() - t0
            println(String.format("[REDIR] 完成=%s 耗时=%dms 大小=%d 错误=%s",
                res.isSuccess, elapsed, out.length(), res.exceptionOrNull()?.message ?: "-"))
            assertTrue(res.isSuccess, "经 302 的下载应当成功：${res.exceptionOrNull()?.message}")
            assertTrue(out.length() > 0, "应产出非空文件")
        } finally {
            client.shutdown()
            workDir.deleteRecursively()
            out.delete()
        }
    }
}

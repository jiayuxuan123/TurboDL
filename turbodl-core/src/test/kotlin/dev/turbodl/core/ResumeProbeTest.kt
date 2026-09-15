package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 回归测试：**「已知大小 → 跳过探测」不得把断点续传一起跳过掉**。
 *
 * ## 背景
 *
 * `skipProbeWhenSizeKnown = true`（默认）且调用方已知大小时，引擎**整段跳过探测**，
 * 用一个乐观的 `ProbeResult(knownSize, supportsRange = true, request.url)` 代替探测结果
 * —— 它的 `etag` / `lastModified` 都是 **null**，于是
 * [SegmentDownloader.ProbeResult.isWeak] **必然为 true**。
 *
 * 而续传校验规定「弱校验器不足以支撑安全续传」→ 只要目录里有旧分片就**无条件删光**。
 * 两者叠加的后果：**网盘类链接（已知大小）的断点续传静默失效，每次都从 0 重下**
 * —— 即使服务器明明提供了 ETag。1.3GB / 1.5MB/s 的场景下，这就是十几分钟的重复下载。
 *
 * **注意这不是"弱校验器策略"本身错了**：那条规定是对的（防同大小不同内容的静默损坏）。
 * 错的是"跳过探测"**人为制造**了一个弱校验器。故修法是：
 * **只要有旧分片要续，就不跳过探测**。
 *
 * ## 本测试守两条
 *
 * 1. 服务器**提供** ETag 时：旧分片必须被复用（续传有效）；
 * 2. 服务器**不提供** ETag/Last-Modified 时：旧分片必须被丢弃（安全性不退化）—— 对照组。
 */
class ResumeProbeTest {

    private class RangeServer(
        private val payload: ByteArray,
        /** 强校验器：给了就应能安全续传；不给则应丢弃旧分片。 */
        private val etag: String? = null,
        private val lastModified: String? = "Wed, 21 Oct 2026 07:28:00 GMT",
    ) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        /** 实际吐出的响应体字节数（不含头部）——用来判断旧分片到底有没有被重下。 */
        val bodyBytes = AtomicLong(0)

        init {
            server.createContext("/f.bin") { ex: HttpExchange ->
                val size = payload.size
                etag?.let { ex.responseHeaders.add("ETag", it) }
                lastModified?.let { ex.responseHeaders.add("Last-Modified", it) }
                ex.responseHeaders.add("Content-Type", "application/octet-stream")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                val m = ex.requestHeaders.getFirst("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                if (m == null) {
                    bodyBytes.addAndGet(size.toLong())
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
                bodyBytes.addAndGet(len.toLong())
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { it.write(payload, s, len) }
            }
            server.executor = Executors.newFixedThreadPool(16)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    private companion object {
        const val SIZE = 2 * 1024 * 1024
        /** 与 effBlock 一致：4 连接 × spc4 → 16 段 → 2MB/16 = 128KB。 */
        const val BLOCK = 128 * 1024
        const val KEY = "resume-probe-task"
    }

    /** 铺好「上一次下载留下的一个完整分片 + 匹配的校验器」，返回待下载文件。 */
    private fun stageOldPart(payload: ByteArray, validator: String): Pair<File, File> {
        val workDir = File(System.getProperty("java.io.tmpdir"), "turbodl-resume-${System.nanoTime()}")
        workDir.mkdirs()
        val safe = "key_" + KEY.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        val chunkDir = File(workDir, safe).apply { mkdirs() }
        File(chunkDir, "seg_0_${BLOCK - 1}.part").writeBytes(payload.copyOfRange(0, BLOCK))
        File(chunkDir, ".validator").writeText(validator)
        return workDir to chunkDir
    }

    private fun cfg(workDir: File) = TurboConfig(
        maxConnectionsPerTask = 4,
        maxConcurrentTasks = 1,
        warmUpConnections = false,
        slowStart = false,
        workDir = workDir,
    )

    @Test
    fun `known size resume reuses old part when server offers a strong validator`() = runBlocking {
        val payload = ByteArray(SIZE) { ((it * 17 + 9) % 256).toByte() }
        val srv = RangeServer(payload, etag = "\"v1\"")
        // 与 probe 将算出的 validator 完全一致（强校验器 → 无 weak 标记）
        val (workDir, _) = stageOldPart(
            payload,
            "len=$SIZE|etag=\"v1\"|lm=Wed, 21 Oct 2026 07:28:00 GMT",
        )
        val client = TurboClient(cfg(workDir))
        val out = File.createTempFile("resume-out", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(
                DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out, stableKey = KEY, knownSize = SIZE.toLong())
            )
            assertTrue(client.await(id).isSuccess)
            assertTrue(out.readBytes().contentEquals(payload), "复用旧分片后内容必须仍逐字节一致")

            val served = srv.bodyBytes.get()
            println("[RESUME] 有强校验器：服务器共吐字节=$served（完整文件=$SIZE，已存分片=$BLOCK）")
            // 已完成的 128KB 分片**不应**被重下；只允许探测那 1 字节的开销。
            assertTrue(
                served <= SIZE - BLOCK + 4096,
                "服务器吐了 $served 字节：说明已完成的 128KB 分片被丢弃重下了。" +
                    "「已知大小 → 跳过探测」把强校验器降级成了弱校验器，续传因此失效。"
            )
        } finally {
            client.shutdown(); srv.stop(); out.delete(); workDir.deleteRecursively()
        }
    }

    @Test
    fun `known size resume still discards parts when server has no strong validator`() = runBlocking {
        val payload = ByteArray(SIZE) { ((it * 23 + 4) % 256).toByte() }
        // 对照组：服务器**不给** ETag / Last-Modified → 只能拿到弱校验器。
        val srv = RangeServer(payload, etag = null, lastModified = null)
        val (workDir, _) = stageOldPart(payload, "len=$SIZE|weak")
        val client = TurboClient(cfg(workDir))
        val out = File.createTempFile("resume-out2", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(
                DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out, stableKey = KEY, knownSize = SIZE.toLong())
            )
            assertTrue(client.await(id).isSuccess)
            assertTrue(out.readBytes().contentEquals(payload))

            val served = srv.bodyBytes.get()
            println("[RESUME] 无强校验器（对照组）：服务器共吐字节=$served（完整文件=$SIZE）")
            // 安全底线：弱校验器不足以证明旧分片属于当前版本 → 必须丢弃重下。
            assertTrue(
                served >= SIZE,
                "弱校验器场景下服务器只吐了 $served 字节（完整=$SIZE）：" +
                    "旧分片被复用了——这会让「同大小不同内容」的新文件合并出损坏结果。" +
                    "本次修复**不得**削弱这条安全性。"
            )
        } finally {
            client.shutdown(); srv.stop(); out.delete(); workDir.deleteRecursively()
        }
    }
}

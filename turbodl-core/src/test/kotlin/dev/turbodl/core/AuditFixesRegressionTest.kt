package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 审计缺陷修复的端到端回归测试。
 *
 * 全部跑在 `127.0.0.1` 本地回环，payload 都是 KB~MB 级小文件，
 * **不访问外网、不消耗任何真实流量**。
 *
 * 覆盖：
 *  - P0-2  200 响应不再被"按 206 处理"（旧实现在 CL 小于「区间长+起点」时会把文件开头
 *          写到分片中部，产生错位数据，且长度校验恰好通过 → 静默损坏）；
 *  - P0-1  弱校验器（只有 Content-Length、无 ETag/Last-Modified）不足以支撑安全续传：
 *          服务器换了同样大小的新文件时，旧分片必须被丢弃（`trustWeakValidator` 控制）；
 *  - P0-4  探测判据：`bytes=0-0` 被 CDN 退化处理回 200 时，须用 `bytes=0-` 复探，
 *          否则 supportsRange=false 会让多线程彻底退化为单流；
 *  - P2-5  206 的 Content-Range 起点与我们请求的不符时不得写入任何字节。
 */
class AuditFixesRegressionTest {

    /** 一个可编程的本地 Range 服务器。 */
    private class Server(private val handler: (HttpExchange, String?) -> Unit) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port

        init {
            server.createContext("/f.bin") { ex -> handler(ex, ex.requestHeaders.getFirst("Range")) }
            server.executor = Executors.newFixedThreadPool(16)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    private fun rangeBounds(range: String?, total: Int): Pair<Int, Int>? {
        val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) } ?: return null
        val s = m.groupValues[1].toInt()
        val e = m.groupValues[2].toIntOrNull() ?: (total - 1)
        return s to e
    }

    /** 正常返回 206 的服务器（作为对照）。 */
    private fun properRangeServer(payload: ByteArray): Server = Server { ex, range ->
        val total = payload.size
        val b = rangeBounds(range, total)
        if (b == null) {
            ex.sendResponseHeaders(200, total.toLong())
            ex.responseBody.use { it.write(payload) }
        } else {
            val (s, e) = b
            val len = e - s + 1
            ex.responseHeaders.add("Content-Range", "bytes $s-$e/$total")
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            ex.sendResponseHeaders(206, len.toLong())
            ex.responseBody.use { it.write(payload, s, len) }
        }
    }

    // ------------------------------------------------------------------
    // P0-2：200 响应 + 误导性 Content-Length
    // ------------------------------------------------------------------

    /**
     * 复刻 B2 的损坏场景：服务器对任何 Range 请求都回 **200**，
     * 且 `Content-Length` 声明为**请求区间的长度**，但实际 body 是**文件从第 0 字节开始**的内容。
     *
     * 旧代码：
     * ```
     * val cl = resp.header("Content-Length")?.toLongOrNull()
     * val isWholeFile = cl == null || cl >= expected + start
     * if (isWholeFile) RANGE_IGNORED
     * else writeSlice(body, partFile, seekPos = existing, expected - existing)   // ← 错位写入
     * ```
     * 当 `start > 0` 时 `cl (= expected) < expected + start` → 走 else 分支 →
     * 把「文件开头的 expected-existing 字节」写到分片偏移 existing 处 → 数据错位，
     * 而 `existing + written == expected` 的长度校验**仍然通过** → 损坏文件静默落地。
     *
     * 修复后：200 一律 RANGE_IGNORED → 累计容忍阈值后整文件单流回退 → 输出字节精确。
     *
     * 用 `knownSize` 让探测被跳过（默认 skipProbeWhenSizeKnown=true），
     * 从而**强制进入分片路径**，确保真的走到这段逻辑。
     */
    @Test
    fun `200 with misleading content-length never corrupts the output`() = runBlocking {
        val size = 4 * 1024 * 1024
        // 用可识别的模式：任何错位都会让最终字节比对失败
        val payload = ByteArray(size) { ((it * 31 + 11) % 251).toByte() }
        val srv = Server { ex, range ->
            val total = payload.size
            val b = rangeBounds(range, total)
            if (b == null) {
                ex.sendResponseHeaders(200, total.toLong())
                ex.responseBody.use { it.write(payload) }
            } else {
                val (s, e) = b
                val len = e - s + 1
                // 畸形响应：声明长度 = 区间长度，body 却是「文件从 0 开始」的 len 字节
                ex.sendResponseHeaders(200, len.toLong())
                ex.responseBody.use { it.write(payload, 0, len) }
            }
        }
        val workDir = File(System.getProperty("java.io.tmpdir"), "turbodl-audit-b2-${System.nanoTime()}")
        workDir.mkdirs()
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 8,
                maxConcurrentTasks = 1,
                workDir = workDir,
                warmUpConnections = false,
            )
        )
        val out = File.createTempFile("b2mislead", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:${srv.port}/f.bin",
                    destination = out,
                    knownSize = size.toLong(),   // 跳过探测，强制走分片路径
                )
            )
            assertTrue(client.await(id).isSuccess, "应通过整文件回退完成，而不是失败")
            assertEquals(size.toLong(), out.length())
            assertTrue(payload.contentEquals(out.readBytes()), "输出必须与源文件字节精确一致（不得错位）")
        } finally {
            client.shutdown()
            srv.stop()
            workDir.deleteRecursively()
            out.delete()
        }
    }

    // ------------------------------------------------------------------
    // P0-1：弱校验器与「同尺寸换文件」
    // ------------------------------------------------------------------

    /**
     * 用 `connections=1` + 32KB 文件构造**单块**布局，
     * 以便精确预置一个覆盖全区的旧分片来模拟「上次下载留下的断点」。
     */
    private fun singleBlockSetup(size: Int): Triple<File, File, String> {
        val workDir = File(System.getProperty("java.io.tmpdir"), "turbodl-audit-b1-${System.nanoTime()}")
        workDir.mkdirs()
        val stableKey = "audit-b1"
        val safe = "key_" + stableKey.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
        val chunkDir = File(workDir, safe).apply { mkdirs() }
        // 服务器不提供 ETag/Last-Modified → 弱校验器；探测被跳过时 validator = "len=<size>|weak"
        File(chunkDir, ".validator").writeText("len=$size|weak")
        // 预置一个覆盖全区的「旧分片」，内容全是垃圾（模拟服务器已换文件后的陈旧数据）
        File(chunkDir, "seg_0_${size - 1}.part").writeBytes(ByteArray(size) { 0x5A })
        return Triple(workDir, chunkDir, stableKey)
    }

    @Test
    fun `weak validator discards stale segments by default`() = runBlocking {
        val size = 32 * 1024
        val payload = ByteArray(size) { ((it * 7 + 3) % 241).toByte() }
        val srv = properRangeServer(payload)
        val (workDir, chunkDir, stableKey) = singleBlockSetup(size)

        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 1,     // 保证只切出 1 块，预置分片才可能被复用
                maxConcurrentTasks = 1,
                workDir = workDir,
                warmUpConnections = false,
                trustWeakValidator = false,    // 默认：正确性优先
            )
        )
        val out = File.createTempFile("b1a", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:${srv.port}/f.bin",
                    destination = out,
                    knownSize = size.toLong(),
                    stableKey = stableKey,
                )
            )
            assertTrue(client.await(id).isSuccess)
            assertEquals(size.toLong(), out.length())
            assertTrue(
                payload.contentEquals(out.readBytes()),
                "弱校验器下旧分片必须被丢弃重下；若保留了垃圾分片则会得到 0x5A 填充的损坏文件",
            )
        } finally {
            client.shutdown()
            srv.stop()
            workDir.deleteRecursively()
            out.delete()
        }
    }

    /**
     * 与上一个用例成对：显式放开 `trustWeakValidator` 时**保留**旧分片
     * —— 这正是旧实现在弱校验器下的默认行为，也是静默损坏的来源。
     *
     * 本用例把该风险固化成断言，既证明开关有效，也证明「默认关闭」确有必要。
     */
    @Test
    fun `trusting weak validator keeps stale segments and yields the corrupt result`() = runBlocking {
        val size = 32 * 1024
        val payload = ByteArray(size) { ((it * 7 + 3) % 241).toByte() }
        val srv = properRangeServer(payload)
        val (workDir, _, stableKey) = singleBlockSetup(size)

        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 1,
                maxConcurrentTasks = 1,
                workDir = workDir,
                warmUpConnections = false,
                trustWeakValidator = true,     // 省流量优先：接受风险
            )
        )
        val out = File.createTempFile("b1b", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:${srv.port}/f.bin",
                    destination = out,
                    knownSize = size.toLong(),
                    stableKey = stableKey,
                )
            )
            assertTrue(client.await(id).isSuccess, "任务会「成功」，这恰恰是静默损坏的可怕之处")
            assertEquals(size.toLong(), out.length(), "长度校验会通过——因为它只比大小")
            assertNotEquals(
                payload.toList(), out.readBytes().toList(),
                "放行弱校验器时旧分片被复用，输出即损坏（这正是默认必须关闭该开关的原因）",
            )
        } finally {
            client.shutdown()
            srv.stop()
            workDir.deleteRecursively()
            out.delete()
        }
    }

    // ------------------------------------------------------------------
    // P0-4：退化探测区间导致多线程失效
    // ------------------------------------------------------------------

    /**
     * 复刻 L2：服务器对 `bytes=0-0` 这种**零长度退化区间**直接回 200（且不给 Accept-Ranges），
     * 但对 `bytes=0-` 及其它正常区间正确回 206。
     *
     * 旧判据 `if (supportsRange || totalSize > 0) return r` 会因为 totalSize>0 直接返回，
     * 带着 supportsRange=false 交给上层 → **整文件单流，多线程彻底失效**。
     *
     * 修复后：用 `bytes=0-` 复探拿到 206 → supportsRange=true → 正常分片。
     * 断言「发生过多次真实分片请求」以证明多线程确实生效。
     */
    @Test
    fun `degenerate zero-length range probe falls back to non-degenerate re-probe`() = runBlocking {
        val size = 2 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 17 + 5) % 253).toByte() }
        val segmentRequests = AtomicInteger(0)
        val srv = Server { ex, range ->
            val total = payload.size
            val degenerate = range == "bytes=0-0"
            val b = if (degenerate) null else rangeBounds(range, total)
            if (degenerate || b == null) {
                // 退化区间（bytes=0-0）被该 CDN 特殊处理：直接回 200，且不带 Accept-Ranges
                ex.sendResponseHeaders(200, total.toLong())
                ex.responseBody.use { it.write(payload) }
            } else {
                val (s, e) = b
                val len = e - s + 1
                // 只有「非退化」的正常区间才计入分片请求
                segmentRequests.incrementAndGet()
                ex.responseHeaders.add("Content-Range", "bytes $s-$e/$total")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { it.write(payload, s, len) }
            }
        }
        val workDir = File(System.getProperty("java.io.tmpdir"), "turbodl-audit-l2-${System.nanoTime()}")
        workDir.mkdirs()
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 4,
                maxConcurrentTasks = 1,
                workDir = workDir,
                warmUpConnections = false,
            )
        )
        val out = File.createTempFile("l2probe", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
            assertTrue(client.await(id).isSuccess)
            assertEquals(size.toLong(), out.length())
            assertTrue(payload.contentEquals(out.readBytes()))
            assertTrue(
                segmentRequests.get() >= 2,
                "探测到 200 后必须用 bytes=0- 复探并启用多分片；实际分片请求数=${segmentRequests.get()}（1 或 0 表示退化成了单流）",
            )
        } finally {
            client.shutdown()
            srv.stop()
            workDir.deleteRecursively()
            out.delete()
        }
    }

    // ------------------------------------------------------------------
    // P2-5：Content-Range 起点强校验
    // ------------------------------------------------------------------

    /**
     * 服务器对所有 Range 请求都回 206，但把 `Content-Range` 起点**篡改为 0**。
     *
     * 旧实现只比对写入长度，不校验区间起点 → 会把「文件开头」的数据写到请求区间的偏移处 → 错位。
     * 修复后：起点不符 → `RANGE_IGNORED`，且**一个字节都不写**。
     */
    @Test
    fun `206 with mismatched content-range start is rejected without writing`() = runBlocking {
        val payload = ByteArray(64 * 1024) { ((it * 3 + 1) % 250).toByte() }
        val srv = Server { ex, range ->
            val total = payload.size
            val b = rangeBounds(range, total)
            if (b == null) {
                ex.sendResponseHeaders(200, total.toLong())
                ex.responseBody.use { it.write(payload) }
            } else {
                val (s, e) = b
                val len = e - s + 1
                // 篡改：无论请求哪个区间，Content-Range 都声称从 0 开始
                ex.responseHeaders.add("Content-Range", "bytes 0-${len - 1}/$total")
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { it.write(payload, 0, len) }
            }
        }
        val dl = SegmentDownloader({ okhttp3.OkHttpClient() })
        val part = File.createTempFile("crange", ".part").apply { deleteOnExit() }
        part.delete()   // 确保从「无既有数据」开始
        try {
            val res = dl.downloadSegment(
                taskId = 1L,
                url = "http://127.0.0.1:${srv.port}/f.bin",
                start = 16_384,
                end = 24_575,
                partFile = part,
                headers = emptyMap(),
            ) { _ -> }
            assertEquals(
                SegmentResult.RANGE_IGNORED, res,
                "Content-Range 起点与请求不符时必须拒绝，交由上层重投/回退",
            )
            assertTrue(
                !part.exists() || part.length() == 0L,
                "区间不符时不得写入任何字节，实际写入了 ${if (part.exists()) part.length() else 0} 字节",
            )
        } finally {
            srv.stop()
            part.delete()
        }
    }
}

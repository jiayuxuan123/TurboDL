package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertFalse
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
        /**
         * 模拟「CDN 拒绝**小**区间请求」：长度在 2..64KB+1 的请求回 403
         * （探测用的 1 字节区间仍然放行）。用于验证「指纹取不到 → 应该保留旧分片」。
         */
        private val rejectSmallRanges: Boolean = false,
        private val rejectRangeStarts: LongRange? = null,
    ) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        /** 实际吐出的响应体字节数（不含头部）——用来判断旧分片到底有没有被重下。 */
        val bodyBytes = AtomicLong(0)
        val ranges = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, Int>>()

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
                if (
                    rejectRangeStarts != null &&
                    s.toLong() in rejectRangeStarts &&
                    e - s + 1 <= 64 * 1024
                ) {
                    ex.sendResponseHeaders(403, -1)
                    ex.close()
                    return@createContext
                }
                val end = minOf(e, size - 1)
                val len = end - s + 1
                // 拒绝"小但非退化"的区间（模拟 CDN 对指纹请求的拒绝）；探测的 bytes=0-0 仍放行。
                if (rejectSmallRanges && len in 2..(64 * 1024 + 1)) {
                    ex.sendResponseHeaders(403, -1); ex.close(); return@createContext
                }
                ex.responseHeaders.add("Content-Range", "bytes $s-$end/$size")
                ranges.add(s to end)
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

    /**
     * 铺好「上一次下载留下的第一个分片 + 匹配的校验器」，返回 (workDir, chunkDir)。
     *
     * ★ 存储结构已变：数据写在**目标文件**里、进度记在侧车，不再是 `seg_*.part`。
     *   所以这里要连目标文件一起铺（否则引擎会判定"文件不够长 → 进度失效"）。
     */
    private fun stageOldPart(
        payload: ByteArray,
        validator: String,
        dest: File,
        completedPieces: Int = 1,
        blockBytes: Int = BLOCK,
    ): Pair<File, File> {
        val workDir = File(System.getProperty("java.io.tmpdir"), "turbodl-resume-${System.nanoTime()}")
        workDir.mkdirs()
        val safe = "key_" + KEY.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        val chunkDir = File(workDir, safe).apply { mkdirs() }
        File(chunkDir, ".validator").writeText(validator)

        val store = SegmentStore(dest = dest, stateFile = File(chunkDir, "progress.tdlp"), total = SIZE.toLong())
        store.open()
        try {
            val pieces = store.buildGrid(blockBytes.toLong())
            repeat(completedPieces.coerceIn(0, pieces.size)) { index ->
                val start = pieces[index].start.toInt()
                val length = (pieces[index].end - pieces[index].start + 1).toInt()
                val bytes = payload.copyOfRange(start, start + length)
                store.writeAt(start.toLong(), bytes, 0, bytes.size)
                pieces[index].written = length.toLong()
            }
            store.persist(pieces)
        } finally {
            store.close()
        }
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
        val out = File.createTempFile("resume-out", ".bin").apply { deleteOnExit() }
        // 与 probe 将算出的 validator 完全一致（强校验器 → 无 weak 标记）
        val (workDir, _) = stageOldPart(
            payload,
            "len=$SIZE|etag=\"v1\"|lm=Wed, 21 Oct 2026 07:28:00 GMT",
            out,
        )
        val client = TurboClient(cfg(workDir))
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
    fun `weak validator resume reuses parts when content fingerprint matches`() = runBlocking {
        val payload = ByteArray(SIZE) { ((it * 23 + 4) % 256).toByte() }
        // 服务器**不给** ETag / Last-Modified → 只能拿到弱校验器（夸克等网盘 CDN 的典型行为）。
        val srv = RangeServer(payload, etag = null, lastModified = null)
        val out = File.createTempFile("resume-out2", ".bin").apply { deleteOnExit() }
        val (workDir, _) = stageOldPart(payload, "len=$SIZE|weak", out)
        val client = TurboClient(cfg(workDir))
        try {
            val id = client.submit(
                DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out, stableKey = KEY, knownSize = SIZE.toLong())
            )
            assertTrue(client.await(id).isSuccess)
            assertTrue(out.readBytes().contentEquals(payload), "复用旧分片后内容必须仍逐字节一致")

            val served = srv.bodyBytes.get()
            println("[RESUME] 弱校验器 + 指纹一致：服务器共吐字节=$served（完整=$SIZE，已存分片=$BLOCK）")
            // 【本次修复】旧行为是"弱校验器 → 一律丢弃 → 从 0 重下"，用户实报：
            // 夸克链接一暂停就整包重下。现在改用内容指纹（前 64KB 比对），一致就该复用。
            assertTrue(
                served <= SIZE - BLOCK + (512 * 1024),
                "服务器吐了 $served 字节：弱校验器场景仍然丢弃了旧分片（应为指纹一致 → 复用）。" +
                    "这正是『暂停就重新下载』的根因。"
            )
        } finally {
            client.shutdown(); srv.stop(); out.delete(); workDir.deleteRecursively()
        }
    }

    @Test
    fun `zero byte checkpoint does not block checked resume`() = runBlocking {
        val payload = ByteArray(SIZE) { ((it * 13 + 6) % 256).toByte() }
        val srv = RangeServer(payload, etag = null, lastModified = null, rejectSmallRanges = true)
        val out = File.createTempFile("resume-empty", ".bin")
        val (workDir, _) = stageOldPart(payload, "len=$SIZE|weak", out, completedPieces = 0)
        val client = TurboClient(cfg(workDir))
        try {
            val id = client.submit(
                DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out, stableKey = KEY, knownSize = SIZE.toLong()),
                UnverifiableResumePolicy.FAIL,
            )
            assertTrue(client.await(id).isSuccess)
            assertTrue(out.readBytes().contentEquals(payload))
        } finally {
            client.shutdown(); srv.stop(); out.delete(); workDir.deleteRecursively()
        }
    }

    @Test
    fun `legacy progress is adopted after its saved bytes match the source`() = runBlocking {
        val payload = ByteArray(SIZE) { ((it * 17 + 9) % 256).toByte() }
        val srv = RangeServer(payload, etag = "\"v1\"")
        val out = File.createTempFile("resume-legacy-adopt", ".bin")
        val (workDir, chunkDir) = stageOldPart(
            payload, "len=$SIZE|etag=\"v1\"|lm=Wed, 21 Oct 2026 07:28:00 GMT", out,
        )
        java.io.DataOutputStream(File(chunkDir, "progress.tdlp").outputStream()).use { state ->
            state.writeBytes("TDLP"); state.writeInt(1); state.writeLong(SIZE.toLong()); state.writeInt(2)
            state.writeLong(0); state.writeLong((SIZE / 2 - 1).toLong()); state.writeLong(BLOCK.toLong())
            state.writeLong((SIZE / 2).toLong()); state.writeLong((SIZE - 1).toLong()); state.writeLong(0)
        }
        val client = TurboClient(cfg(workDir))
        try {
            val id = client.submit(DownloadRequest(
                "http://127.0.0.1:${srv.port}/f.bin", out, stableKey = KEY, knownSize = SIZE.toLong(),
            ))
            assertTrue(client.await(id).isSuccess)
            assertTrue(out.readBytes().contentEquals(payload))
            assertTrue(srv.ranges.none { (start, end) -> start == 0 && end - start + 1 > 64 * 1024 },
                "legacy adoption should not fetch the saved block again")
        } finally {
            client.shutdown(); srv.stop(); out.delete(); workDir.deleteRecursively()
        }
    }

    @Test
    fun `legacy progress without identity remains intact even with matching strong etag`() = runBlocking {
        val payload = ByteArray(SIZE) { ((it * 13 + 6) % 256).toByte() }
        val srv = RangeServer(payload, etag = "\"v1\"", rejectSmallRanges = true)
        val out = File.createTempFile("resume-legacy-v1", ".bin")
        val (workDir, chunkDir) = stageOldPart(
            payload, "len=$SIZE|etag=\"v1\"|lm=Wed, 21 Oct 2026 07:28:00 GMT", out,
        )
        val progress = File(chunkDir, "progress.tdlp")
        java.io.DataOutputStream(progress.outputStream()).use { state ->
            state.writeBytes("TDLP"); state.writeInt(1); state.writeLong(SIZE.toLong()); state.writeInt(2)
            state.writeLong(0); state.writeLong((SIZE / 2 - 1).toLong()); state.writeLong(BLOCK.toLong())
            state.writeLong((SIZE / 2).toLong()); state.writeLong((SIZE - 1).toLong()); state.writeLong(0)
        }
        val beforeProgress = progress.readBytes()
        val beforeOutput = out.readBytes()
        val client = TurboClient(cfg(workDir))
        try {
            val id = client.submit(DownloadRequest(
                "http://127.0.0.1:${srv.port}/f.bin", out, stableKey = KEY, knownSize = SIZE.toLong(),
            ))
            assertTrue(client.await(id).exceptionOrNull() is ResumeVerificationException)
            assertTrue(progress.readBytes().contentEquals(beforeProgress))
            assertTrue(out.readBytes().contentEquals(beforeOutput))
        } finally {
            client.shutdown(); srv.stop(); out.delete(); workDir.deleteRecursively()
        }
    }

    @Test
    fun `unverifiable resume can fail without destroying saved bytes`() = runBlocking {
        val old = ByteArray(SIZE) { ((it * 53 + 6) % 256).toByte() }
        val replacement = ByteArray(SIZE) { ((it * 37 + 3) % 256).toByte() }
        val srv = RangeServer(replacement, etag = null, lastModified = null, rejectSmallRanges = true)
        val out = File.createTempFile("resume-strict", ".bin")
        val (workDir, chunkDir) = stageOldPart(old, "len=$SIZE|weak", out)
        val sidecar = File(chunkDir, "progress.tdlp")
        val savedProgress = sidecar.readBytes()
        val savedPrefix = out.readBytes().copyOfRange(0, BLOCK)
        val client = TurboClient(cfg(workDir))
        try {
            val id = client.submit(
                DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out, stableKey = KEY, knownSize = SIZE.toLong()),
                UnverifiableResumePolicy.FAIL,
            )
            val result = client.await(id)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is ResumeVerificationException)
            assertTrue(sidecar.readBytes().contentEquals(savedProgress))
            assertTrue(out.readBytes().copyOfRange(0, BLOCK).contentEquals(savedPrefix))
            assertTrue(client.resumeAsync(id))
            assertTrue(client.await(id).exceptionOrNull() is ResumeVerificationException)
            assertTrue(sidecar.readBytes().contentEquals(savedProgress))
        } finally {
            client.shutdown(); srv.stop(); out.delete(); workDir.deleteRecursively()
        }
    }

    @Test
    fun `verification policy allows a matching resume fingerprint`() = runBlocking {
        val payload = ByteArray(SIZE) { ((it * 53 + 6) % 256).toByte() }
        val srv = RangeServer(payload, etag = null, lastModified = null)
        val out = File.createTempFile("resume-checkable", ".bin")
        val (workDir, _) = stageOldPart(payload, "len=$SIZE|weak", out)
        val client = TurboClient(cfg(workDir))
        try {
            val id = client.submit(
                DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out, stableKey = KEY, knownSize = SIZE.toLong()),
                UnverifiableResumePolicy.FAIL,
            )
            assertTrue(client.await(id).isSuccess)
            assertTrue(out.readBytes().contentEquals(payload))
        } finally {
            client.shutdown(); srv.stop(); out.delete(); workDir.deleteRecursively()
        }
    }

    @Test
    fun `weak validator resume keeps parts when fingerprint is unavailable`() = runBlocking {
        // 场景：服务器不给强校验器，且**指纹请求取不到**（区间被拒/异常）。
        // 【必须保留】拿不到指纹只是"无法取证"，不是"内容变了"的证据 ——
        // 旧实现把两者混为一谈，于是"一次指纹请求没成功"就把用户已下载的 GB 级数据全删掉，
        // 表现为"暂停后仍然从头下载"。
        val payload = ByteArray(SIZE) { ((it * 53 + 6) % 256).toByte() }
        val srv = RangeServer(payload, etag = null, lastModified = null, rejectSmallRanges = true)
        val out = File.createTempFile("resume-out4", ".bin").apply { deleteOnExit() }
        val (workDir, _) = stageOldPart(payload, "len=$SIZE|weak", out)
        val client = TurboClient(cfg(workDir))
        try {
            val id = client.submit(
                DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out, stableKey = KEY, knownSize = SIZE.toLong())
            )
            assertTrue(client.await(id).isSuccess)
            assertTrue(out.readBytes().contentEquals(payload))

            val served = srv.bodyBytes.get()
            println("[RESUME] 弱校验器 + 指纹不可用：服务器共吐字节=$served（完整=$SIZE，已存分片=$BLOCK）")
            assertTrue(
                served <= SIZE - BLOCK + (128 * 1024),
                "服务器吐了 $served 字节：指纹**取不到**时旧分片被丢弃了。" +
                    "无法取证 ≠ 内容已变 —— 这种情形必须保留，否则用户一暂停就白下整个文件。"
            )
        } finally {
            client.shutdown(); srv.stop(); out.delete(); workDir.deleteRecursively()
        }
    }

    @Test
    fun `weak validator resume still discards parts when fingerprint mismatches`() = runBlocking {
        // 安全底线：服务器换了一个**同样大小、但内容不同**的文件（弱校验器唯一防不住的场景）。
        // 指纹比对必须发现它 → 丢弃旧分片重下，绝不能合并出新旧混杂的损坏文件。
        val stale = ByteArray(SIZE) { ((it * 23 + 4) % 256).toByte() }      // 旧文件内容
        val fresh = ByteArray(SIZE) { ((it * 91 + 77) % 256).toByte() }     // 新文件：同大小、不同内容
        val srv = RangeServer(fresh, etag = null, lastModified = null)
        // 目录里放的是**旧文件**的分片
        val out = File.createTempFile("resume-out3", ".bin").apply { deleteOnExit() }
        val (workDir, _) = stageOldPart(stale, "len=$SIZE|weak", out)
        val client = TurboClient(cfg(workDir))
        try {
            val id = client.submit(
                DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out, stableKey = KEY, knownSize = SIZE.toLong())
            )
            assertTrue(client.await(id).isSuccess)
            val bytes = out.readBytes()
            assertTrue(bytes.contentEquals(fresh), "同大小换文件时必须整体重下，不能复用旧分片")

            val served = srv.bodyBytes.get()
            println("[RESUME] 弱校验器 + 指纹不一致：服务器共吐字节=$served（完整=$SIZE）")
            assertTrue(
                served >= SIZE,
                "服务器只吐了 $served 字节（完整=$SIZE）：旧分片被复用了 —— " +
                    "这会让「同大小不同内容」的新文件合并出损坏结果。指纹校验必须拦住它。"
            )
        } finally {
            client.shutdown(); srv.stop(); out.delete(); workDir.deleteRecursively()
        }
    }

    @Test
    fun `changed strong etag cannot be overruled by sampled bytes`() = runBlocking {
        val stale = ByteArray(SIZE) { ((it * 37 + 11) % 256).toByte() }
        val fresh = stale.copyOf().also { it[128 * 1024] = (it[128 * 1024] + 1).toByte() }
        val srv = RangeServer(fresh, etag = "\"v2\"")
        val out = File.createTempFile("resume-strong-unsampled", ".bin")
        val (workDir, chunkDir) = stageOldPart(
            stale, "len=$SIZE|etag=\"v1\"|lm=Wed, 21 Oct 2026 07:28:00 GMT", out,
            blockBytes = 512 * 1024,
        )
        val original = out.readBytes()
        val oldProgress = File(chunkDir, "progress.tdlp").readBytes()
        val client = TurboClient(cfg(workDir))
        try {
            val id = client.submit(DownloadRequest(
                "http://127.0.0.1:${srv.port}/f.bin", out, stableKey = KEY, knownSize = SIZE.toLong(),
            ))
            assertTrue(client.await(id).exceptionOrNull() is ResumeVerificationException)
            assertTrue(out.readBytes().contentEquals(original), "unverified progress must remain intact")
            assertTrue(File(chunkDir, "progress.tdlp").readBytes().contentEquals(oldProgress))
        } finally {
            client.shutdown(); srv.stop(); out.delete(); workDir.deleteRecursively()
        }
    }

    @Test
    fun `strong validator change is not cleared by a matching first block prefix`() = runBlocking {
        val stale = ByteArray(SIZE) { ((it * 37 + 11) % 256).toByte() }
        val fresh = stale.copyOf().also { bytes ->
            // Keep the 64 KiB fingerprint prefix identical while changing the
            // rest of the already-written 128 KiB block. A strong ETag change
            // must not be overridden by a single-prefix match.
            for (index in 64 * 1024 until BLOCK) {
                bytes[index] = (bytes[index] + 1).toByte()
            }
        }
        val srv = RangeServer(fresh, etag = "\"v2\"")
        val out = File.createTempFile("resume-strong-validator", ".bin").apply { deleteOnExit() }
        val (workDir, _) = stageOldPart(
            stale,
            "len=$SIZE|etag=\"v1\"|lm=Wed, 21 Oct 2026 07:28:00 GMT",
            out,
        )
        val client = TurboClient(cfg(workDir))
        try {
            val id = client.submit(
                DownloadRequest(
                    "http://127.0.0.1:${srv.port}/f.bin",
                    out,
                    stableKey = KEY,
                    knownSize = SIZE.toLong(),
                )
            )
            assertTrue(client.await(id).isSuccess)
            assertTrue(
                out.readBytes().contentEquals(fresh),
                "强校验器已变化且块内非前缀字节不同，旧分片不得因前 64 KiB 相同而被复用",
            )
            assertTrue(
                srv.bodyBytes.get() >= SIZE,
                "检测到强校验器变化后必须整体重下；服务器只发了 ${srv.bodyBytes.get()} / $SIZE",
            )
        } finally {
            client.shutdown(); srv.stop(); out.delete(); workDir.deleteRecursively()
        }
    }

    @Test
    fun `weak ETag is fingerprinted even when the tag text is unchanged`() = runBlocking {
        val stale = ByteArray(SIZE) { ((it * 67 + 3) % 256).toByte() }
        val fresh = ByteArray(SIZE) { ((it * 89 + 41) % 256).toByte() }
        val srv = RangeServer(fresh, etag = "W/\"same-weak-tag\"", lastModified = null)
        val out = File.createTempFile("resume-weak-etag", ".bin").apply { deleteOnExit() }
        val (workDir, _) = stageOldPart(
            stale,
            "len=$SIZE|etag=W/\"same-weak-tag\"|weak",
            out,
        )
        val client = TurboClient(cfg(workDir))
        try {
            val id = client.submit(
                DownloadRequest(
                    "http://127.0.0.1:${srv.port}/f.bin",
                    out,
                    stableKey = KEY,
                    knownSize = SIZE.toLong(),
                )
            )
            assertTrue(client.await(id).isSuccess)
            assertTrue(
                out.readBytes().contentEquals(fresh),
                "W/ 弱 ETag 不能单独证明内容未变，必须走内容指纹",
            )
            assertTrue(
                srv.bodyBytes.get() >= SIZE,
                "弱 ETag 且内容不同时必须整体重下；服务器只发了 ${srv.bodyBytes.get()} / $SIZE",
            )
        } finally {
            client.shutdown(); srv.stop(); out.delete(); workDir.deleteRecursively()
        }
    }

    @Test
    fun `weak ETag with an unchanged Last-Modified still requires fingerprinting`() = runBlocking {
        val stale = ByteArray(SIZE) { ((it * 17 + 61) % 256).toByte() }
        val fresh = ByteArray(SIZE) { ((it * 101 + 13) % 256).toByte() }
        val lastModified = "Wed, 04 Nov 2026 08:00:00 GMT"
        val srv = RangeServer(
            fresh,
            etag = "W/\"same-with-date\"",
            lastModified = lastModified,
        )
        val out = File.createTempFile("resume-weak-etag-date", ".bin").apply { deleteOnExit() }
        val (workDir, _) = stageOldPart(
            stale,
            "len=$SIZE|etag=W/\"same-with-date\"|lm=$lastModified|weak",
            out,
        )
        val client = TurboClient(cfg(workDir))
        try {
            val id = client.submit(
                DownloadRequest(
                    "http://127.0.0.1:${srv.port}/f.bin",
                    out,
                    stableKey = KEY,
                    knownSize = SIZE.toLong(),
                )
            )
            assertTrue(client.await(id).isSuccess)
            assertTrue(out.readBytes().contentEquals(fresh))
            assertTrue(
                srv.bodyBytes.get() >= SIZE,
                "W/ ETag + Last-Modified 仍不是强校验器，内容不同必须重下",
            )
        } finally {
            client.shutdown(); srv.stop(); out.delete(); workDir.deleteRecursively()
        }
    }

    @Test
    fun `missing validator with old parts is never treated as an implicit match`() = runBlocking {
        val stale = ByteArray(SIZE) { ((it * 19 + 7) % 256).toByte() }
        val fresh = ByteArray(SIZE) { ((it * 43 + 81) % 256).toByte() }
        val srv = RangeServer(fresh, etag = "\"fresh\"")
        val out = File.createTempFile("resume-missing-validator", ".bin").apply { deleteOnExit() }
        val (workDir, chunkDir) = stageOldPart(
            stale,
            "len=$SIZE|etag=\"stale\"",
            out,
        )
        File(chunkDir, ".validator").delete()
        val client = TurboClient(cfg(workDir))
        try {
            val id = client.submit(
                DownloadRequest(
                    "http://127.0.0.1:${srv.port}/f.bin",
                    out,
                    stableKey = KEY,
                    knownSize = SIZE.toLong(),
                )
            )
            assertTrue(client.await(id).isSuccess)
            assertTrue(out.readBytes().contentEquals(fresh))
            assertTrue(
                srv.bodyBytes.get() >= SIZE,
                "缺少 .validator 时旧分片身份未知，必须重新下载",
            )
        } finally {
            client.shutdown(); srv.stop(); out.delete(); workDir.deleteRecursively()
        }
    }

    @Test
    fun `backup-only progress is fingerprinted and a mismatch removes the backup`() = runBlocking {
        val stale = ByteArray(SIZE) { ((it * 13 + 5) % 256).toByte() }
        val fresh = ByteArray(SIZE) { ((it * 71 + 23) % 256).toByte() }
        val srv = RangeServer(fresh, etag = null, lastModified = null)
        val out = File.createTempFile("resume-backup-only", ".bin").apply { deleteOnExit() }
        val (workDir, chunkDir) = stageOldPart(stale, "len=$SIZE|weak", out)
        val primary = File(chunkDir, "progress.tdlp")
        val backup = File(chunkDir, "progress.tdlp.bak")
        assertTrue(primary.renameTo(backup))
        val client = TurboClient(cfg(workDir))
        try {
            val id = client.submit(
                DownloadRequest(
                    "http://127.0.0.1:${srv.port}/f.bin",
                    out,
                    stableKey = KEY,
                    knownSize = SIZE.toLong(),
                )
            )
            assertTrue(client.await(id).isSuccess)
            assertTrue(out.readBytes().contentEquals(fresh))
            assertFalse(backup.exists(), "丢弃旧进度时必须同时删除 .bak")
        } finally {
            client.shutdown(); srv.stop(); out.delete(); workDir.deleteRecursively()
        }
    }

    @Test
    fun `one mismatching sampled piece cannot be hidden by another matching piece`() = runBlocking {
        val stale = ByteArray(SIZE) { ((it * 31 + 17) % 256).toByte() }
        val fresh = stale.copyOf().also { bytes ->
            for (index in 64 * 1024 until BLOCK) {
                bytes[index] = (bytes[index] + 9).toByte()
            }
        }
        val srv = RangeServer(fresh, etag = null, lastModified = null)
        val out = File.createTempFile("resume-two-pieces", ".bin").apply { deleteOnExit() }
        val (workDir, _) = stageOldPart(
            stale,
            "len=$SIZE|weak",
            out,
            completedPieces = 2,
        )
        val client = TurboClient(cfg(workDir))
        try {
            val id = client.submit(
                DownloadRequest(
                    "http://127.0.0.1:${srv.port}/f.bin",
                    out,
                    stableKey = KEY,
                    knownSize = SIZE.toLong(),
                )
            )
            assertTrue(client.await(id).isSuccess)
            assertTrue(
                out.readBytes().contentEquals(fresh),
                "第一片已确认不一致，第二片相同不能把整体判回 MATCH",
            )
            assertTrue(srv.bodyBytes.get() >= SIZE)
        } finally {
            client.shutdown(); srv.stop(); out.delete(); workDir.deleteRecursively()
        }
    }

    @Test
    fun `a changed interior piece is detected when outer pieces are unchanged`() = runBlocking {
        val stale = ByteArray(SIZE) { ((it * 7 + 29) % 256).toByte() }
        val fresh = stale.copyOf().also { bytes ->
            val changed = 2 * BLOCK
            for (index in changed until changed + BLOCK) {
                bytes[index] = (bytes[index] + 37).toByte()
            }
        }
        val srv = RangeServer(fresh, etag = null, lastModified = null)
        val out = File.createTempFile("resume-interior-piece", ".bin").apply { deleteOnExit() }
        val (workDir, _) = stageOldPart(
            stale,
            "len=$SIZE|weak",
            out,
            completedPieces = 4,
        )
        val client = TurboClient(cfg(workDir))
        try {
            val id = client.submit(
                DownloadRequest(
                    "http://127.0.0.1:${srv.port}/f.bin",
                    out,
                    stableKey = KEY,
                    knownSize = SIZE.toLong(),
                )
            )
            assertTrue(client.await(id).isSuccess)
            assertTrue(
                out.readBytes().contentEquals(fresh),
                "即使首/中/尾样本相同，任何已完成分片出现变化也必须整体重下",
            )
            assertTrue(srv.bodyBytes.get() >= SIZE)
        } finally {
            client.shutdown(); srv.stop(); out.delete(); workDir.deleteRecursively()
        }
    }

    @Test
    fun `partial fingerprint failure cannot be upgraded to a full match`() = runBlocking {
        val stale = ByteArray(SIZE) { ((it * 61 + 19) % 256).toByte() }
        val fresh = stale.copyOf().also { bytes ->
            val changed = 2 * BLOCK
            for (index in changed until changed + BLOCK) {
                bytes[index] = (bytes[index] + 73).toByte()
            }
        }
        val srv = RangeServer(
            fresh,
            etag = null,
            lastModified = null,
            rejectRangeStarts = (2L * BLOCK) until (3L * BLOCK),
        )
        val out = File.createTempFile("resume-partial-fingerprint", ".bin").apply { deleteOnExit() }
        val (workDir, _) = stageOldPart(
            stale,
            "len=$SIZE|weak",
            out,
            completedPieces = 4,
        )
        val client = TurboClient(cfg(workDir))
        try {
            val id = client.submit(
                DownloadRequest(
                    "http://127.0.0.1:${srv.port}/f.bin",
                    out,
                    stableKey = KEY,
                    knownSize = SIZE.toLong(),
                )
            )
            assertTrue(client.await(id).isSuccess)
            assertTrue(out.readBytes().contentEquals(fresh))
            assertTrue(
                srv.bodyBytes.get() >= SIZE,
                "部分指纹请求失败时不得把其余匹配样本升级为 MATCH",
            )
        } finally {
            client.shutdown(); srv.stop(); out.delete(); workDir.deleteRecursively()
        }
    }
}

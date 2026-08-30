package dev.turbodl.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/** 单分片下载结果。 */
internal enum class SegmentResult {
    /** 分片按请求的 Range 完整写入。 */
    OK,

    /** 服务器忽略 Range（返回 200 整文件 / Content-Length 与请求区间不符）→ 交由上层回退单流。 */
    RANGE_IGNORED,

    /** 结构性失败（HTML 错误页 / 状态码异常 / 写入字节不足）。 */
    FAILED,

    /** 遇到 429 / 503：服务器过载信号 → 上层可下调并发。 */
    THROTTLED,
}

/**
 * 单连接分片下载器（HTTP Range）。
 *
 * 关键正确性保障（吸收各下载器踩坑经验）：
 *  - **Range 篡改校验**：206 也校验「实际写入字节 == 请求区间长度」；200 则按 Content-Length 判断
 *    是否为整文件（服务器忽略 Range），是则返回 RANGE_IGNORED 交上层回退，绝不在单分片里下整文件；
 *  - **HTML 错误页拦截**：Content-Type=text/html 直接判失败（防盗链 / 过期链接返回的广告页）；
 *  - **429/503 上报**：返回 THROTTLED，供上层做「仅在过载时下调并发」的背压；
 *  - **严格截断**：服务器多返回的字节按预期长度截断，文件永不膨胀；
 *  - **任务级取消**：登记 Call，暂停/取消时立即 cancel 阻塞 IO。
 */
internal class SegmentDownloader(
    private val clientProvider: () -> OkHttpClient,
    /** 整文件/探测专用客户端（允许 h2）；null 时回退用 [clientProvider]。 */
    private val streamClientProvider: (() -> OkHttpClient)? = null,
    /** IO 缓冲区大小（字节）；默认 1MB。过小会在高吞吐时产生大量回调开销。 */
    private val bufferSizeProvider: () -> Int = { DEFAULT_BUFFER },
) {

    private val client get() = clientProvider()
    private val streamClient get() = (streamClientProvider ?: clientProvider)()
    private val activeCalls = ConcurrentHashMap<Long, MutableSet<okhttp3.Call>>()

    fun cancelCalls(taskId: Long) {
        activeCalls.remove(taskId)?.forEach { runCatching { it.cancel() } }
    }

    companion object {
        private const val DEFAULT_BUFFER = 1024 * 1024
    }

    /**
     * 连接预热 / DNS 预解析：对目标 URL 并发发起若干极小 Range 请求（bytes=0-0），
     * 触发 DNS 解析 + TCP/TLS 握手并把连接留在连接池里（OkHttp keep-alive）。
     * 后续正式分片下载时可直接复用，无需串行等待解析/握手。
     * 失败沉默忽略（预热仅优化，不影响正确性）。
     */
    suspend fun warmUp(url: String, headers: Map<String, String>, connections: Int, timeoutMs: Long = 8_000): Unit =
        coroutineScope {
            val n = connections.coerceIn(1, 32)
            // 预热必须有硬止时：否则服务器不响应时会沉在这里（read timeout 默认 60s），
            // 让任务卡在“看似下载中但什么都没发生”。预热失败不影响正确性。
            val warmClient = client.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            val jobs = (0 until n).map {
                async(Dispatchers.IO) {
                    runCatching {
                        val req = Request.Builder()
                            .url(url)
                            .header("Range", "bytes=0-0")
                            .apply { headers.forEach { (k, v) -> header(k, v) } }
                            .header("Accept-Encoding", "identity")
                            .get().build()
                        val call = warmClient.newCall(req)
                        // 读取并丢弃响应体，使连接完成并回到连接池复用（而非被弃置）。
                        try {
                            call.execute().use { resp -> resp.body?.byteStream()?.use { it.readBytes() } }
                        } finally {
                            if (!call.isCanceled()) runCatching { call.cancel() }
                        }
                    }
                }
            }
            jobs.awaitAll()
        }

    /** 探测结果：总大小、是否支持 Range、重定向后的最终 URL，以及服务器元数据（尽力而为，可为空）。 */
    data class ProbeResult(
        val totalSize: Long?,
        val supportsRange: Boolean,
        /** 跟随 3xx 重定向后的最终 URL（网盘原始链接常 302 到带签名的 CDN 临时直链）。 */
        val resolvedUrl: String,
        /** 强校验器 ETag（若服务器提供）。用于续传前校验文件是否已变更。 */
        val etag: String? = null,
        /** 弱校验器 Last-Modified（若服务器提供）。 */
        val lastModified: String? = null,
        /** 服务器建议的文件名（Content-Disposition，其次 URL 末段）。 */
        val suggestedFileName: String? = null,
        /** Content-Type（可用于补全扩展名等，纯信息）。 */
        val contentType: String? = null,
    ) {
        /**
         * 续传校验令牌：把「大小 + ETag + Last-Modified」压成一个字符串。
         * 只要服务器侧文件发生变化，该令牌就会变化 → 上层据此丢弃过期分片，避免合并出损坏文件。
         * 三者都拿不到时为空串，表示无法校验（此时按旧行为宽松续传）。
         */
        val validator: String
            get() = listOfNotNull(
                totalSize?.takeIf { it > 0 }?.let { "len=$it" },
                etag?.takeIf { it.isNotBlank() }?.let { "etag=$it" },
                lastModified?.takeIf { it.isNotBlank() }?.let { "lm=$it" },
            ).joinToString("|")
    }

    /**
     * 从 Content-Disposition 解析文件名（优先 RFC 5987 的 filename*，其次 filename），
     * 解析失败返回 null。会剥除路径分隔符，避免目录穿越。
     */
    private fun parseContentDisposition(value: String?): String? {
        if (value.isNullOrBlank()) return null
        // filename*=UTF-8''%E4%B8%AD%E6%96%87.zip
        Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE)
            .find(value)?.let { m ->
                val charset = m.groupValues[1].trim().ifBlank { "UTF-8" }
                val raw = m.groupValues[2].trim().trim('"')
                runCatching { java.net.URLDecoder.decode(raw, charset) }.getOrNull()
                    ?.let { return sanitizeFileName(it) }
            }
        // filename="xxx.zip" 或 filename=xxx.zip
        Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(value)?.let { m ->
                return sanitizeFileName(m.groupValues[1].trim())
            }
        return null
    }

    /** 剥除路径分隔符与非法字符，仅保留安全的文件名。 */
    private fun sanitizeFileName(name: String): String? {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        if (base.isBlank() || base == "." || base == "..") return null
        return base.map { if (it in "\\/:*?\"<>|" || it.code < 0x20) '_' else it }.joinToString("")
    }

    /**
     * 探测总大小与 Range 支持，并返回重定向后的最终 URL。
     *
     * 关键：网盘/更新等原始链接常返回 302 跳到**带签名的 CDN 临时直链**，
     * 只有这个临时直链才支持 Range 多线程；若后续分片仍请求原始链接，
     * 每个连接都要再走一次 302（可能命中不同节点/签名、甚至被限流），
     * 表现为「显示下载中但线程/字节都不动，最后 Whole-file download failed」。
     * 因此这里用 OkHttp 自动跟随重定向后的 [okhttp3.Response.request] URL 作为最终地址，
     * 交由上层对该稳定地址做多线程分片。
     */
    suspend fun probe(url: String, headers: Map<String, String>, timeoutMs: Long = 0): ProbeResult =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                // identity 在自定义头之后设置，确保总是生效（防止调用方传入 Accept-Encoding: gzip 覆盖）：
                // 若 gzip 透明解压，实际写入字节会与 Content-Range 不一致 → 大小校验失败。
                .header("Accept-Encoding", "identity")
                .get().build()
            // 关键：用 OkHttp 自己的 callTimeout 做**硬止时**。
            // 协程的 withTimeout 无法中断阻塞中的 call.execute()（服务器接受连接但不响应时，
            // connect+read 可叠加到近分钟），callTimeout 覆盖整个请求生命周期，能真正卡住。
            val effClient = if (timeoutMs > 0)
                client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            else client
            val call = effClient.newCall(req)
            val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { resp ->
                    // resp.request.url 是跟随所有 3xx 之后的最终地址（原始链接 302→CDN 临时直链）。
                    val finalUrl = resp.request.url.toString()
                    // 元数据均为“尽力而为”：拿不到不影响下载，仅用于续传校验与命名。
                    val etag = resp.header("ETag")?.trim()?.takeIf { it.isNotBlank() }
                    val lastMod = resp.header("Last-Modified")?.trim()?.takeIf { it.isNotBlank() }
                    val ctype = resp.header("Content-Type")?.trim()?.takeIf { it.isNotBlank() }
                    val suggested = parseContentDisposition(resp.header("Content-Disposition"))
                        ?: sanitizeFileName(
                            finalUrl.substringBefore('?').substringAfterLast('/')
                                .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                        )
                    if (resp.header("Content-Type").orEmpty().contains("text/html", true)) {
                        return@use ProbeResult(null, false, finalUrl, etag, lastMod, suggested, ctype)
                    }
                    when (resp.code) {
                        206 -> {
                            val total = resp.header("Content-Range")
                                ?.substringAfter('/')?.toLongOrNull()
                            ProbeResult(total, true, finalUrl, etag, lastMod, suggested, ctype)
                        }
                        200 -> {
                            // 未返回 206：多数为不支持 Range；但若服务器声明 Accept-Ranges: bytes，
                            // 则仍视为支持分片（部分 CDN 对 bytes=0-0 这种退化区间直接回 200，
                            // 旧逻辑会误判为不支持而白白退化成单线程）。
                            val total = resp.header("Content-Length")?.toLongOrNull()
                            val acceptRanges = resp.header("Accept-Ranges")
                                .orEmpty().contains("bytes", ignoreCase = true)
                            ProbeResult(total, acceptRanges, finalUrl, etag, lastMod, suggested, ctype)
                        }
                        else -> ProbeResult(null, false, finalUrl, etag, lastMod, suggested, ctype)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ProbeResult(null, false, url)
            } finally {
                runCatching { if (!call.isCanceled()) call.cancel() }
                handle?.dispose()
            }
        }

    /**
     * 带超时与重试的探测。
     *
     * 为什么需要：探测无界限等待时，connect(15s) + read(60s) 可能叠加到近分钟，
     * 期间 UI 已经进入 DOWNLOADING 但字节为 0，用户看到的就是“显示在下载但一直不动、
     * 连解析那一步都没做”。瞬时抖动下也不应直接退化为单线程，所以要重试。
     */
    suspend fun probeWithRetry(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        retries: Int,
    ): ProbeResult {
        var last = ProbeResult(null, false, url)
        repeat(retries + 1) { attempt ->
            val r = probe(url, headers, timeoutMs = timeoutMs)
            last = r
            // 拿到可用结果（支持 Range 或至少知道大小）即可返回
            if (r.supportsRange || (r.totalSize ?: -1L) > 0) return r
            if (attempt < retries) delay(500L * (attempt + 1))  // 递增退避
        }
        return last
    }

    /**
     * 下载 [start,end] 区间到 [partFile]（断点续传：已存在字节跳过）。
     * @param onBytes 每写入一段回调增量。
     */
    suspend fun downloadSegment(
        taskId: Long,
        url: String,
        start: Long,
        end: Long,
        partFile: File,
        headers: Map<String, String>,
        onBytes: suspend (Long) -> Unit,
        // 分片下载不再内部 withContext(Dispatchers.IO)：调度器已由调用方（SegmentScheduler）
        // 用 limitedParallelism 开好专用的阻塞 IO 池。若这里再切回共享的 Dispatchers.IO，
        // 会直接抵消专用池的作用，并重新受 max(64, cpus) 默认并行度限制。
    ): SegmentResult {
        val existing = partFile.length()
        val expected = end - start + 1
        if (existing >= expected) return SegmentResult.OK
        val from = start + existing

        val req = Request.Builder()
            .url(url)
            .header("Range", "bytes=$from-$end")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            // identity 在自定义头之后，确保不被覆盖：避免 gzip 透明解压破坏分片字节计数。
            .header("Accept-Encoding", "identity")
            .get().build()
        val call = client.newCall(req)
        activeCalls.getOrPut(taskId) { ConcurrentHashMap.newKeySet() }.add(call)
        val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        return try {
            call.execute().use { resp ->
                if (resp.header("Content-Type").orEmpty().contains("text/html", true)) {
                    return@use SegmentResult.FAILED
                }
                when (val code = resp.code) {
                    429, 503 -> SegmentResult.THROTTLED
                    206 -> {
                        val body = resp.body ?: return@use SegmentResult.FAILED
                        val written = writeSlice(body.byteStream(), partFile, existing, expected - existing, onBytes)
                        if (existing + written != expected) SegmentResult.FAILED else SegmentResult.OK
                    }
                    200 -> {
                        // 服务器忽略 Range：校验 Content-Length 是否 >= 整个区间（判定为整文件）
                        val cl = resp.header("Content-Length")?.toLongOrNull()
                        val isWholeFile = cl == null || cl >= expected + start
                        if (isWholeFile) {
                            SegmentResult.RANGE_IGNORED
                        } else {
                            // 少数情况 200 但只返回区间：按 206 处理，严格截断
                            val body = resp.body ?: return@use SegmentResult.FAILED
                            val written = writeSlice(body.byteStream(), partFile, existing, expected - existing, onBytes)
                            if (existing + written != expected) SegmentResult.FAILED else SegmentResult.OK
                        }
                    }
                    416 -> SegmentResult.OK  // Range 越界：通常该分片已完成
                    else -> {
                        if (code in 500..599) SegmentResult.THROTTLED else SegmentResult.FAILED
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (!coroutineContext.isActive) throw CancellationException("canceled", e)
            SegmentResult.FAILED
        } finally {
            activeCalls[taskId]?.remove(call)
            handle?.dispose()
        }
    }

    /** 单流整文件回退下载（服务器不支持 Range / 忽略 Range）。 */
    suspend fun downloadWhole(
        taskId: Long,
        url: String,
        outFile: File,
        headers: Map<String, String>,
        total: Long,
        onBytes: suspend (Long) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val existing = outFile.length()
        // 整文件回退不做断点（避免与 Range 不支持的服务器语义冲突）：从 0 重写
        if (existing > 0) outFile.delete()
        val req = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Accept-Encoding", "identity")
            .get().build()
        // 整文件单流：用允许 h2 的客户端（单流无多连接损失，且兼容仅支持 h2 的服务器）。
        val call = streamClient.newCall(req)
        activeCalls.getOrPut(taskId) { ConcurrentHashMap.newKeySet() }.add(call)
        val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (resp.header("Content-Type").orEmpty().contains("text/html", true)) {
                    throw IllegalStateException("下载失败：返回 HTML（链接失效/需要 Referer）")
                }
                if (!resp.isSuccessful) throw IllegalStateException("下载失败 HTTP ${resp.code}")
                val body = resp.body ?: return@use false
                val expected = if (total > 0) total else -1L
                var written = 0L
                RandomAccessFile(outFile, "rw").use { raf ->
                    raf.seek(0)
                    body.byteStream().use { input ->
                        val buf = ByteArray(bufferSizeProvider().coerceAtLeast(8 * 1024))
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            val allow = if (expected < 0) n.toLong() else min(n.toLong(), expected - written)
                            if (allow <= 0) break
                            raf.write(buf, 0, allow.toInt())
                            written += allow
                            onBytes(allow)
                            if (expected in 1..written) break
                        }
                    }
                }
                if (total > 0 && written < total) return@use false
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (!coroutineContext.isActive) throw CancellationException("canceled", e)
            false
        } finally {
            activeCalls[taskId]?.remove(call)
            handle?.dispose()
        }
    }

    private suspend fun writeSlice(
        input: java.io.InputStream,
        partFile: File,
        seekPos: Long,
        expected: Long,
        onBytes: suspend (Long) -> Unit,
    ): Long {
        var written = 0L
        RandomAccessFile(partFile, "rw").use { raf ->
            raf.seek(seekPos)
            val buf = ByteArray(bufferSizeProvider().coerceAtLeast(8 * 1024))
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                val allow = min(n.toLong(), expected - written)
                if (allow <= 0) break
                raf.write(buf, 0, allow.toInt())
                written += allow
                onBytes(allow)
                if (written >= expected) break
            }
        }
        return written
    }
}

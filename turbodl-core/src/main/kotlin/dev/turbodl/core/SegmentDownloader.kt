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

    /**
     * 各任务**已学习到的重定向终址**（原始链 → CDN 临时直链）。
     *
     * 【为什么需要】走「已知大小 → 跳过探测」这条快路径时，探测没有发生，
     * `effectiveUrl` 就等于**原始 URL** —— 于是**每个分片都要自己跟一次 302**。
     * 网盘直链配 128 连接就是 128 次多余的重定向往返，而且各分片可能落到不同 CDN 节点。
     * 相当于用「省掉 2 次探测请求」换来了「N 次重定向」。
     *
     * 【学习方式】任何一次分片响应都带回了**跟随重定向之后的最终地址**
     * （OkHttp 的 `resp.request.url`），把它记住即可，**不额外发任何请求**。
     *
     * 【自愈】失败时立即遗忘（见 [forgetResolvedUrl]）：若记住的是带签名、有时效的
     * CDN 直链且已过期，下一次尝试会自动回到原始 URL 重新解析，而不是抱着失效地址反复撞。
     */
    private val resolvedUrlByTask = ConcurrentHashMap<Long, String>()

    /** 取本次分片实际应请求的地址：优先用已学习到的终址，否则回落到原始 URL。 */
    private fun urlFor(taskId: Long, original: String): String =
        resolvedUrlByTask[taskId] ?: original

    /** 失败时遗忘已学习的终址，让下次尝试重新走原始 URL 解析（应对签名过期）。 */
    private fun forgetResolvedUrl(taskId: Long) {
        resolvedUrlByTask.remove(taskId)
    }

    fun cancelCalls(taskId: Long) {
        resolvedUrlByTask.remove(taskId)
        activeCalls.remove(taskId)?.forEach { runCatching { it.cancel() } }
    }

    /**
     * 任务结束（正常完成/失败/暂停）时释放任务级状态。
     *
     * [activeCalls] 与 [resolvedUrlByTask] 都是按 taskId 的映射，而旧实现只在
     * [cancelCalls]（暂停 / 卡死恢复）里清理 —— **正常完成的任务会永久残留一条空集合**，
     * 加上本次新增的终址记录，就是每个任务两条永不回收的条目。下载器 App 一开就是几百个任务，
     * 属于**无界增长**。故在任务终点统一清理。
     *
     * 只清记录、**不 cancel 在飞请求**：取消是 [cancelCalls] 的职责；此刻任务已结束，
     * 残留的请求也已随协程作用域一同取消。
     */
    fun releaseTask(taskId: Long) {
        resolvedUrlByTask.remove(taskId)
        activeCalls.remove(taskId)
    }

    companion object {
        private const val DEFAULT_BUFFER = 1024 * 1024

        /** 最小探测区间：代价最低，但部分 CDN 对「零长度区间」直接回 200（见 [probeWithRetry]）。 */
        private const val RANGE_MIN = "bytes=0-0"

        /** 非退化区间（从 0 到结尾）：用于复探服务器是否**真的**支持 Range。 */
        private const val RANGE_FROM_START = "bytes=0-"
    }

    /**
     * 连接预热 / DNS 预解析：对目标 URL 并发发起若干极小 Range 请求（bytes=0-0），
     * 触发 DNS 解析 + TCP/TLS 握手并把连接留在连接池里（OkHttp keep-alive）。
     * 后续正式分片下载时可直接复用，无需串行等待解析/握手。
     * 失败沉默忽略（预热仅优化，不影响正确性）。
     *
     * 【为什么第 1 个请求要单独先发】跳过探测时传进来的 `url` 是**原始链接**
     * （网盘原始链 → 302 → CDN 直链）。若 n 个预热请求并发发出，**每一个都要各自跟一次 302**，
     * 而且每个都多建一条到前端主机的连接。先单发一个把终址学到，剩下的就能直接打 CDN。
     * 预热本身是异步、不阻塞下载开始的，所以这里多一个阶段**不影响启动延迟**。
     */
    suspend fun warmUp(
        url: String,
        headers: Map<String, String>,
        connections: Int,
        timeoutMs: Long = 8_000,
        taskId: Long? = null,
    ): Unit =
        coroutineScope {
            val n = connections.coerceIn(1, 32)
            // 预热必须有硬止时：否则服务器不响应时会沉在这里（read timeout 默认 60s），
            // 让任务卡在“看似下载中但什么都没发生”。预热失败不影响正确性。
            val warmClient = client.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            suspend fun warmOnce(target: String) = withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder()
                        .url(target)
                        .header("Range", "bytes=0-0")
                        .apply { headers.forEach { (k, v) -> header(k, v) } }
                        .header("Accept-Encoding", "identity")
                        .get().build()
                    val call = warmClient.newCall(req)
                    // 读取并丢弃响应体，使连接完成并回到连接池复用（而非被弃置）。
                    try {
                        call.execute().use { resp ->
                            // 顺便学习重定向终址，供后续预热请求与所有分片复用。
                            if (taskId != null && (resp.isSuccessful || resp.code == 416)) {
                                val finalUrl = resp.request.url.toString()
                                if (finalUrl != target) resolvedUrlByTask[taskId] = finalUrl
                            }
                            resp.body?.byteStream()?.use { it.readBytes() }
                        }
                    } finally {
                        if (!call.isCanceled()) runCatching { call.cancel() }
                    }
                }
            }
            warmOnce(url)
            val learned = taskId?.let { resolvedUrlByTask[it] } ?: url
            val jobs = (1 until n).map {
                async(Dispatchers.IO) { warmOnce(learned) }
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
         * 三者都拿不到时为空串，表示无法校验。
         *
         * 注意末尾的 `weak` 标记：见 [isWeak]。
         */
        val validator: String
            get() = listOfNotNull(
                totalSize?.takeIf { it > 0 }?.let { "len=$it" },
                etag?.takeIf { it.isNotBlank() }?.let { "etag=$it" },
                lastModified?.takeIf { it.isNotBlank() }?.let { "lm=$it" },
                "weak".takeIf { isWeak },
            ).joinToString("|")

        /**
         * 是否为**弱校验器**：只拿到大小，没有 ETag / Last-Modified。
         *
         * 大量 CDN 只回 `Content-Length`。此时 `validator` 退化为 `len=N`，
         * 而「服务器换了一个**同样大小**的新文件」这个场景下令牌**不会变化**，
         * 旧分片会被误判为「当前版本」而复用 → 合并出新旧混杂的损坏文件，
         * 且最终的长度校验会恰好通过（大小一致）。
         *
         * 因此弱校验器**不足以支撑安全续传**：上层应据此丢弃旧分片（或做内容指纹校验）。
         */
        val isWeak: Boolean
            get() = etag.isNullOrBlank() && lastModified.isNullOrBlank()
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
    suspend fun probe(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long = 0,
        /** Range 头的值。默认 `bytes=0-0`（最小探测）；复探时用 `bytes=0-`。 */
        rangeSpec: String = RANGE_MIN,
    ): ProbeResult =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("Range", rangeSpec)
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
     * 取 [start, start+len) 区间的字节，用于**续传内容指纹校验**（失败/不支持 Range 返回 null）。
     *
     * 为什么不复用 [probe]：probe 只读响应头，这里要读**字节**。
     * 用途见 `BuiltinHttpBackend` 的续传校验：服务器只给弱校验器（无 ETag）时，
     * 用「已存分片的前若干字节」与服务器同区间逐字节比对，来代替"一律丢弃旧分片"。
     */
    suspend fun fetchPrefix(
        url: String,
        headers: Map<String, String>,
        start: Long,
        len: Int,
        timeoutMs: Long,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (len <= 0) return@withContext null
        val end = start + len - 1
        val req = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$end")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            // 与分片请求一致：禁止透明解压，否则比对的是压缩后的字节。
            .header("Accept-Encoding", "identity")
            .get().build()
        val effClient = if (timeoutMs > 0)
            client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build() else client
        val call = effClient.newCall(req)
        try {
            call.execute().use { resp ->
                // 只认 206（精确区间）；start==0 时允许 200（部分服务器对 bytes=0- 回整文件）。
                if (resp.code != 206 && !(resp.code == 200 && start == 0L)) return@use null
                val body = resp.body ?: return@use null
                val buf = ByteArray(len)
                var off = 0
                body.byteStream().use { ins ->
                    while (off < len) {
                        val n = ins.read(buf, off, len - off)
                        if (n <= 0) break
                        off += n
                    }
                }
                if (off <= 0) null else buf.copyOf(off)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            runCatching { if (!call.isCanceled()) call.cancel() }
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
        // `bytes=0-` 复探**全局只做一次** —— 对同一个服务器，它不会改变结论，
        // 每轮重试都复探一次纯属浪费（旧实现在这里白发了一倍请求）。
        var reprobed = false
        repeat(retries + 1) { attempt ->
            // ① 最小探测（bytes=0-0）：代价最低，健康服务器回 206 即可确认支持分片。
            val r = probe(url, headers, timeoutMs = timeoutMs, rangeSpec = RANGE_MIN)
            last = r
            if (r.supportsRange) return r

            // ② 【快速失败】服务器回的是网页（HTML）→ 这不是"瞬时失败"，是**地址本身不可下载**。
            // 重试再多次也还是网页，只会让用户多等好几秒。直接返回，让上层给出准确原因。
            if (r.contentType.orEmpty().contains("text/html", ignoreCase = true)) return r

            // ③ 没拿到 Range 支持、但拿到了大小 → 用「非退化区间」复探一次。
            //
            // 【为什么必须有这一步】部分 CDN 对 `bytes=0-0` 这种零长度区间特殊处理，
            // 直接回 200 + Content-Length，且**不带** Accept-Ranges。
            // 旧判据 `supportsRange || totalSize > 0` 会因为 totalSize>0 而**直接返回**，
            // 带着 supportsRange=false 交给上层 → 退化为整文件单流 → 多线程彻底失效。
            // 换成 `bytes=0-`（从 0 到结尾）往往就能拿到正确的 206 + Content-Range。
            // aria2 的探测正是用 `bytes=0-` 而非 `bytes=0-0`。
            if (!reprobed && (r.totalSize ?: -1L) > 0) {
                reprobed = true
                val r2 = probe(url, headers, timeoutMs = timeoutMs, rangeSpec = RANGE_FROM_START)
                if (r2.supportsRange) return r2
                if (r2.contentType.orEmpty().contains("text/html", ignoreCase = true)) return r2
                // 复探仍不支持：保留两次探测中信息更全的一份（大小/元数据）。
                last = r2.copy(totalSize = r2.totalSize ?: r.totalSize)

                // ④ 【快速失败，本修复的核心】两次探测都得到「200 + 已知大小 + 不支持 Range」
                // → 这是**确定性结论**：该服务器就是不支持分片。
                //
                // 重试不会改变它，只会让"解析"白白多花一整轮（2 次请求 + 500ms 退避）。
                // 旧实现会把这个 repeat 跑满 —— 这正是「10MB/6MB 小文件解析也慢」的根因：
                // 普通直链多走 2~4 次无效请求，而网盘因为调用方已知大小被
                // `skipProbeWhenSizeKnown` 跳过探测，反而更快。
                if ((last.totalSize ?: -1L) > 0) return last
            }

            // 到这里只剩**真正的瞬时失败**（超时 / 连接错误 / 5xx / 拿不到大小）
            // —— 这类才值得退避重试。指数退避而非线性：给服务器喘息时间，也避免弱网下密集重探。
            if (attempt < retries) delay(500L * (1L shl attempt))
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

        // 优先用已学习到的重定向终址；没有就先用原始 URL（这一次会跟 302，并把终址记下来）。
        val target = urlFor(taskId, url)
        val req = Request.Builder()
            .url(target)
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
                // 学习重定向终址：`resp.request.url` 是**跟随所有 3xx 之后**的最终地址。
                // 只在响应可用时记（成功或 416），避免把错误页/拦截页的地址当成终址。
                if (resp.isSuccessful || resp.code == 416) {
                    val finalUrl = resp.request.url.toString()
                    if (finalUrl != target) resolvedUrlByTask[taskId] = finalUrl
                }
                if (resp.header("Content-Type").orEmpty().contains("text/html", true)) {
                    // 网页错误页常见于「签名直链已过期/被拦截」→ 遗忘终址，
                    // 让下一次尝试回到原始 URL 重新解析，而不是抱着失效地址反复撞。
                    forgetResolvedUrl(taskId)
                    return@use SegmentResult.FAILED
                }
                when (val code = resp.code) {
                    429, 503 -> SegmentResult.THROTTLED
                    206 -> {
                        // 【P0-2 配套】强校验 Content-Range：服务器返回的区间必须正好是我们请求的起点。
                        // 若不符（代理/网关篡改、命中其它对象、CDN 返回了别的区间），
                        // 按 expected 长度写入会在分片内造成**数据错位**——宁可回投重试也不写入。
                        val crStart = parseContentRangeStart(resp.header("Content-Range"))
                        if (crStart != null && crStart != from) {
                            return@use SegmentResult.RANGE_IGNORED
                        }
                        val body = resp.body ?: return@use SegmentResult.FAILED
                        val written = writeSlice(body.byteStream(), partFile, existing, expected - existing, onBytes)
                        if (existing + written != expected) SegmentResult.FAILED else SegmentResult.OK
                    }
                    200 -> {
                        // 【P0-2 修复】200 = 服务器忽略/拒绝 Range，body 语义上是「整个资源」。
                        //
                        // 绝不按 206 处理：body 几乎必然从**文件第 0 字节**开始，
                        // 而 writeSlice 的 seekPos = existing（分片内偏移，可能与 0 相差很大），
                        // 于是「文件开头」被写进分片中部 → 数据错位 + 空洞，
                        // 而 existing + written == expected 的长度校验**仍会通过** → 损坏文件静默落地。
                        //
                        // 一律交上层回投重试；累计超过 RANGE_IGNORED_TOLERANCE 后触发整文件单流回退。
                        // （若服务器真回了小于请求区间的 200，那多半是代理篡改/错误页，重试比猜测语义更安全。）
                        SegmentResult.RANGE_IGNORED
                    }
                    416 -> SegmentResult.OK  // Range 越界：通常该分片已完成
                    else -> {
                        // 401/403/410 是「授权/时效」类信号：记住的 CDN 直链很可能已过期，
                        // 遗忘它 → 下次重新走原始 URL 解析（自愈），而不是一直撞同一个失效地址。
                        if (code == 401 || code == 403 || code == 410) forgetResolvedUrl(taskId)
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
                    // 【文案修正】旧文案「链接失效/需要 Referer」会把**地理封锁 / 反爬拦截**误导成
                    // "链接坏了或需要 Referer"。实测（OVH 从中国访问）：服务器返回的是 HTML 拦截页，
                    // 既没有失效也不需要 Referer，真正原因是按来源地区/频率做了拦截。
                    //
                    // 带上 HTTP 状态码与 Content-Type：这是**唯一能区分**下面几种情况的线索 ——
                    //   · 403/503 + HTML → 反爬/区域/频率拦截（换 IP、降并发、加 Referer 才可能通）
                    //   · 200 + HTML → 地址指向的是网页本身，不是文件
                    //   · 404/410 + HTML → 链接（很可能）真的失效了
                    val code = resp.code
                    val hint = when {
                        code == 403 || code == 503 || code == 429 ->
                            "服务器返回拦截页（常见于按地区/频率/来源的封锁）——不是链接失效，也不一定需要 Referer"
                        code == 404 || code == 410 -> "链接很可能已失效"
                        code == 200 -> "该地址返回的是网页而非文件：可能是分享页/播放页，或需要登录态"
                        else -> "服务器返回了网页而不是文件"
                    }
                    throw IllegalStateException("下载失败：服务器返回 HTML（HTTP $code）。$hint")
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

    /**
     * 解析 `Content-Range: bytes <from>-<to>/<total>` 的起点 `<from>`。
     * 无法解析（缺头、格式异常、`*`）返回 null —— 此时不做强校验，以免误杀正常响应。
     */
    private fun parseContentRangeStart(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val spec = value.substringBefore('/').trim().removePrefix("bytes").trim()
        return spec.substringBefore('-').trim().toLongOrNull()
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

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

    /**
     * 授权/时效类失败（401 / 403 / 410）：**不是**"你太快了"的信号。
     *
     * 【为什么必须与 [FAILED] 分开】403 常见于签名直链过期或被反盗链拦截 ——
     * 此时正确响应是**重新解析地址后重试**，而不是降低并发。
     * 若与普通失败混在一起，会触发背压降并发，把速度白白压下去却解决不了 403
     * （aria2-next 对此有明确说明："HTTP 403 does not estimate server capacity"）。
     * 上层据此走"遗忘终址 + 重试"路径，且**不计入**背压的连续失败计数。
     */
    AUTH_EXPIRED,
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
    /**
     * 分片写入是否走缓冲流（P12 开关）。
     *
     * 与 [bufferSizeProvider] 同样用 provider 而不是把整个 config 传进来：
     * 本类只需要这一个布尔量，注入 config 会扩大它与配置结构的耦合面。
     * 默认 false = 直写（实测结论见 [TurboConfig.bufferedSegmentWrite]）。
     */
    private val bufferedWriteProvider: () -> Boolean = { false },
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

    /**
     * 最近一次整文件下载失败的具体原因（按 taskId）。
     *
     * [downloadWhole] 的返回值是 `Boolean`，天然丢信息。上层（`BuiltinHttpBackend`）在
     * `false` 时只能给一句笼统的「服务器不支持 Range、无响应或响应异常」——
     * 而真正的原因可能是"连接被重置"、"声明长度大于实际"、"读超时"，
     * 三者对用户意味着完全不同的下一步。这里把原因记下来供上层取用，用完即清。
     */
    private val failureByTask = ConcurrentHashMap<Long, String>()

    /**
     * 服务器给出的 `Retry-After`（毫秒，按 taskId）。
     *
     * 429/503 常常带这个头（RFC 9110 §10.2.3），值是"建议等多久"或一个 HTTP 日期。
     * 忽略它只能用盲目的指数退避：等太短会继续被拒（白烧重试预算），等太长则白白拖慢。
     * aria2 与 curl 都读这个头，这里对齐。
     */
    private val retryAfterByTask = ConcurrentHashMap<Long, Long>()

    /** 取出（并清掉）服务器建议的重试等待；没有则返回 null。 */
    fun takeRetryAfterMs(taskId: Long): Long? = retryAfterByTask.remove(taskId)

    /**
     * 解析 `Retry-After`：既支持秒数，也支持 HTTP 日期。
     *
     * @return 毫秒（已夹到 [0, 120s]）；无法解析返回 null。
     */
    private fun parseRetryAfter(value: String?): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        raw.toLongOrNull()?.let { secs ->
            // 秒数形式：0 是合法的（"立刻重试"）。
            return (secs * 1000L).coerceIn(0L, MAX_RETRY_AFTER_MS)
        }
        val date = runCatching {
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .parse(raw, java.time.Instant::from)
        }.getOrNull() ?: return null
        val delta = date.toEpochMilli() - System.currentTimeMillis()
        return delta.coerceIn(0L, MAX_RETRY_AFTER_MS)
    }

    /** 取出（并清掉）最近一次整文件下载的失败原因；没有则返回 null。 */
    fun takeWholeError(taskId: Long): String? = failureByTask.remove(taskId)

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

        /**
         * 开启 [TurboConfig.bufferedSegmentWrite] 时的写缓冲大小（64KB）。
         *
         * 与读缓冲（按并发摊薄，最大 1MB）**不同量级是有意的**：写缓冲只是减少
         * `write` 系统调用次数，64KB 已远超收益拐点；再大只会推迟落盘、放大中断时的损失。
         * 而且每个在飞分片都会占一份，所以它同样受内存预算的约束。
         */
        private const val WRITE_BUFFER_BYTES = 64 * 1024

        /** `Retry-After` 的上限：超过两分钟就不值得干等，宁可退避重投（可能换到别的节点）。 */
        private const val MAX_RETRY_AFTER_MS = 120_000L

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
        /**
         * UA 协商结果：被拦截时换上的那个 User-Agent；未协商（默认 UA 就通）时为 null。
         *
         * 见 [UA_FALLBACKS]。非 null 时调用方**必须**把它带进这个任务的后续所有请求
         * （分片 / 整文件 / 续传指纹），否则协商白做 —— 分片仍会用默认 UA 撞同一堵墙。
         */
        val negotiatedUserAgent: String? = null,
        /**
         * 探测那一次请求的 HTTP 状态码；请求本身失败（超时/连接不上）时为 0。
         *
         * 只用于**判断要不要换 UA**（401/403/HTML 拦截页）与诊断，不参与下载决策。
         */
        val statusCode: Int = 0,
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
         * 用于 `If-Range` 的**强**校验器；没有则返回 null。
         *
         * 规则（RFC 9110 §13.1.5）：
         *  - `If-Range` **只接受强校验器**。弱 ETag（`W/"..."`）会被服务器忽略，
         *    发了等于没发，还会让人误以为有保护 → 显式排除。
         *  - 强 ETag 优先；没有强 ETag 时才退回 Last-Modified
         *    （HTTP 日期精度到秒，且 CDN 可能改写，属较弱的保护，但聊胜于无）。
         */
        val strongIfRange: String?
            get() {
                val e = etag?.takeIf { it.isNotBlank() }
                if (e != null && !e.startsWith("W/", ignoreCase = true)) return e
                return lastModified?.takeIf { it.isNotBlank() }
            }

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
        /** 覆盖 UA（UA 协商用）；null 表示沿用 [headers] 或配置里的默认值。 */
        userAgentOverride: String? = null,
    ): ProbeResult =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("Range", rangeSpec)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                // 协商用的 UA 要在 headers **之后**设置：headers 里带的那份不生效时，
                // 这里的覆盖才是这次探测真正发出去的 UA。
                .apply { if (userAgentOverride != null) header("User-Agent", userAgentOverride) }
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
                        return@use ProbeResult(null, false, finalUrl, etag, lastMod, suggested, ctype,
                            statusCode = resp.code)
                    }
                    when (resp.code) {
                        206 -> {
                            val total = resp.header("Content-Range")
                                ?.substringAfter('/')?.toLongOrNull()
                            ProbeResult(total, true, finalUrl, etag, lastMod, suggested, ctype,
                                statusCode = resp.code)
                        }
                        200 -> {
                            // 未返回 206：多数为不支持 Range；但若服务器声明 Accept-Ranges: bytes，
                            // 则仍视为支持分片（部分 CDN 对 bytes=0-0 这种退化区间直接回 200，
                            // 旧逻辑会误判为不支持而白白退化成单线程）。
                            val total = resp.header("Content-Length")?.toLongOrNull()
                            val acceptRanges = resp.header("Accept-Ranges")
                                .orEmpty().contains("bytes", ignoreCase = true)
                            ProbeResult(total, acceptRanges, finalUrl, etag, lastMod, suggested, ctype,
                                statusCode = resp.code)
                        }
                        else -> ProbeResult(null, false, finalUrl, etag, lastMod, suggested, ctype,
                            statusCode = resp.code)
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
     * 被拦截时的 UA 候选梯子（**降序尝试，第一个能过就是它**）。
     *
     * ## 为什么需要这个（真实网络实测，2026-10-18）
     *
     * 国内镜像站的反爬**按 UA 分档**，而且方向与直觉相反：**浏览器 UA 被拦，curl 类 UA 放行**。
     * 同一条链接、同一个程序，只换 UA：
     *
     * | 站点 | 浏览器 UA | curl UA |
     * |---|---|---|
     * | 清华 | 403 + HTML | 206，正常下载 |
     * | 北外 | 403 + HTML | 206 |
     * | 中科大 | 200 + "Verifying your browser"（JS 挑战页） | 206 |
     * | 南大 | 被拦 | 206 |
     *
     * 而 [TurboConfig.userAgent] 默认就是 Chrome 的浏览器 UA（那是为了应对"只认浏览器 UA"的
     * CDN 才这么定的）。于是默认配置在这些站上**一个字节都下不了**，而 curl 跑同一链接飞快 ——
     * 用户看到的"真实网络下怎么比 curl 慢这么多"，根子在这里。
     *
     * ## 顺序的依据
     *
     * 1. 先保持默认（浏览器 UA）不变 —— 绝大多数 CDN 与网盘直链吃这一套；
     * 2. 再退到 `curl/x.y.z`：镜像站/开源基础设施最常见的白名单，也是本机 curl 实测能通的；
     * 3. 最后退到 `aria2/x.y.z`：同样的基础设施类 UA，覆盖把 curl 单独拉黑的情况。
     *
     * 换 UA 只影响"我们怎么介绍自己"，不改变请求内容、不带 Cookie、不伪造别的头 ——
     * 这不是绕过访问控制，而是换一个对方本来就允许的工具身份。
     */
    private val uaFallbacks: List<String> = listOf(
        "curl/8.21.0",
        "aria2/1.37.0",
    )

    /**
     * UA 协商：默认 UA 被拦时，依次换 [uaFallbacks] 重探，**第一个能拿到 Range 的就采用**。
     *
     * ## 为什么做在探测阶段
     *
     * 拦截在探测那一次请求上就能看出来（403/HTML 挑战页，代价是一次 `bytes=0-0` 的极小请求）。
     * 若等分片阶段才发现，等于让几十条连接各自撞一遍墙，再逐条重试 —— 既慢又把重试配额烧光。
     *
     * ## 只换能确定的
     *
     * 只有当探测**明确拿到 HTML（拦截页/挑战页）或 401/403** 时才换 UA。
     * 超时、连接失败、5xx 一律不换 —— 那些是网络或服务端故障，换 UA 不会好，
     * 只会白白多打几个请求、拖慢正常的失败路径。
     *
     * 用户自己显式指定过 UA（`headers["User-Agent"]`）时**不协商**：那是他的判断，不该被覆盖。
     */
    private suspend fun negotiateUserAgent(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        initial: ProbeResult,
    ): ProbeResult {
        val explicit = headers.keys.any { it.equals("User-Agent", ignoreCase = true) }
        if (explicit) return initial
        // 只处理"被拦"这一类：HTML 响应，或授权类的 401/403。
        val blocked = initial.contentType.orEmpty().contains("text/html", ignoreCase = true) ||
            initial.statusCode == 401 || initial.statusCode == 403
        if (!blocked) return initial
        for (ua in uaFallbacks) {
            val r = probe(url, headers, timeoutMs = timeoutMs, rangeSpec = RANGE_MIN, userAgentOverride = ua)
            // 换到 UA 之后必须**真的能下**才算数：拿到 Range 支持，或至少不再是 HTML/401/403。
            val stillBlocked = r.contentType.orEmpty().contains("text/html", ignoreCase = true) ||
                r.statusCode == 401 || r.statusCode == 403
            if (!stillBlocked && (r.supportsRange || (r.totalSize ?: -1L) > 0)) {
                return r.copy(negotiatedUserAgent = ua)
            }
        }
        return initial
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
        // UA 协商只做一次（结论对一个域名是稳定的），协商出结果就贯穿整个任务。
        var negotiated: String? = null
        // `bytes=0-` 复探**全局只做一次** —— 对同一个服务器，它不会改变结论，
        // 每轮重试都复探一次纯属浪费（旧实现在这里白发了一倍请求）。
        var reprobed = false
        repeat(retries + 1) { attempt ->
            // ① 最小探测（bytes=0-0）：代价最低，健康服务器回 206 即可确认支持分片。
            // 已协商出 UA 时用它 —— 否则这一轮又会用默认 UA 撞同一堵墙。
            val r = probe(url, headers, timeoutMs = timeoutMs, rangeSpec = RANGE_MIN,
                userAgentOverride = negotiated)
            last = r
            if (r.supportsRange) return r

            // ② 【UA 协商】被拦（HTML 拦截页 / 401 / 403）时换 UA 再探。
            //
            // 【为什么必须在"快速失败"之前】真实网络实测：国内镜像站按 UA 分档拦截，
            // 浏览器 UA 被拦、curl 类 UA 放行。默认 UA 是浏览器 UA，于是默认配置在这些站上
            // 零字节失败，而 curl 跑同一链接飞快（见 [uaFallbacks] 的表）。
            // 这里只多花最多 1~2 个 `bytes=0-0` 的极小请求，就能把"完全下不了"变成"正常下载"。
            if (r.statusCode == 401 || r.statusCode == 403 ||
                r.contentType.orEmpty().contains("text/html", ignoreCase = true)
            ) {
                val n = negotiateUserAgent(url, headers, timeoutMs, r)
                if (n.negotiatedUserAgent != null) {
                    negotiated = n.negotiatedUserAgent
                    last = n
                    if (n.supportsRange) return n
                } else {
                    // 协商没过：维持原有语义 —— HTML 是确定性结论，直接返回让上层报准确原因。
                    if (r.contentType.orEmpty().contains("text/html", ignoreCase = true)) return r
                }
            }

            // ②' 【快速失败】服务器回的是网页（HTML）→ 这不是"瞬时失败"，是**地址本身不可下载**。
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
                // 复探**必须**带上协商出的 UA：否则「换 UA 通过了 → 复探又用回默认 UA → 再次被拦」，
                // 前面那次协商白做，还会把结论误判成"不支持 Range"（真实镜像站上就是这么发生的）。
                val r2 = probe(url, headers, timeoutMs = timeoutMs, rangeSpec = RANGE_FROM_START,
                    userAgentOverride = negotiated)
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
     * 下载一个分片并**按偏移直接写进目标文件**（不建临时分片文件）。
     *
     * 与 [downloadSegment] 的区别只有"写到哪里"：
     *  - 旧路径：写 `seg_x_y.part`，下完还要 [PartMerger] 合并一遍；
     *  - 本方法：调用方给一个 [writeAt]，直接落到目标文件的绝对偏移上。
     *
     * 为什么这件事值得单独一个方法（实测数据见 [SegmentStore] 的注释）：
     * 16MB 切 512 片时，仅"创建 512 个文件"就要 627ms，而同样数据按偏移写一个文件只要 11ms。
     * 高并发下这部分**就是**全部耗时，网络根本没成为限制。
     *
     * @param alreadyWritten 该分片已完成的字节数（来自进度记录）
     * @param writeAt (绝对偏移, 缓冲, 起始, 长度) -> 实际写入字节数
     * @param buffer 调用方复用的读缓冲 —— 每个分片新建一个 1MB 数组在高并发下是纯 GC 负担
     * @param onProgress 每写入一段回调（用于更新进度记录与总进度），参数是**本次新增**字节数
     */
    suspend fun downloadSegmentInto(
        taskId: Long,
        url: String,
        pieceStart: Long,
        endProvider: () -> Long,
        alreadyWritten: Long,
        writeAt: (position: Long, buf: ByteArray, off: Int, len: Int) -> Int,
        headers: Map<String, String>,
        onProgress: suspend (newlyWritten: Long, totalWritten: Long) -> Unit,
        buffer: ByteArray,
        ifRange: String? = null,
        /** 请求发出后把句柄交给调用方（卡死守护据此"只掐这一条"）。 */
        onCallStarted: ((okhttp3.Call) -> Unit)? = null,
        /**
         * 本次尝试**自己的**失败原因（可选）。
         *
         * 【为什么必须单独回报】`failureByTask` 是任务级的**单槽**：128 条连接同时失败时，
         * 它只会留下最后写进去的那一条。实测后果是错误信息指错人：
         * 「分片 8978432-9043967 重试 6 次仍失败（IOException: Canceled）」——
         * 那个 "Canceled" 其实是**别的**分片被连坐打断时写进去的，
         * 耗尽重试的那一片真正的原因是读超时。调用方拿到本回调后，
         * 就能把"这一片自己的原因"记在分片身上，最终报告才说的是实话。
         */
        onFailure: ((String) -> Unit)? = null,
        /**
         * 直接缓冲区写入口（可选）。给了就走零拷贝路径，没给回落到 [writeAt]。
         *
         * 之所以做成可选而不是必选：调用方（插件后端）可能只有 `File` 层面的写能力。
         */
        writeBufferAt: ((position: Long, src: java.nio.ByteBuffer) -> Int)? = null,
        directBuffer: java.nio.ByteBuffer? = null,
    ): SegmentResult {
        fun reportFailure(reason: String) {
            failureByTask[taskId] = reason
            onFailure?.invoke(reason)
        }
        var end = endProvider()
        var expected = end - pieceStart + 1
        var done = alreadyWritten.coerceAtLeast(0)
        if (done >= expected) return SegmentResult.OK
        val from = pieceStart + done

        val target = urlFor(taskId, url)
        val req = Request.Builder()
            .url(target)
            .header("Range", "bytes=$from-$end")
            .apply { if (!ifRange.isNullOrBlank()) header("If-Range", ifRange) }
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Accept-Encoding", "identity")
            .get().build()
        val call = client.newCall(req)
        activeCalls.getOrPut(taskId) { ConcurrentHashMap.newKeySet() }.add(call)
        onCallStarted?.invoke(call)
        val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        return try {
            call.execute().use { resp ->
                if (resp.isSuccessful || resp.code == 416) {
                    val finalUrl = resp.request.url.toString()
                    if (finalUrl != target) resolvedUrlByTask[taskId] = finalUrl
                }
                if (resp.header("Content-Type").orEmpty().contains("text/html", true)) {
                    forgetResolvedUrl(taskId)
                    return@use SegmentResult.FAILED
                }
                when (val code = resp.code) {
                    429, 503 -> {
                        // 服务器说了"多久之后再来"就听它的：盲目退避要么继续被拒，要么白等。
                        parseRetryAfter(resp.header("Retry-After"))
                            ?.let { retryAfterByTask[taskId] = it }
                        SegmentResult.THROTTLED
                    }
                    206 -> {
                        val crStart = parseContentRangeStart(resp.header("Content-Range"))
                        if (crStart != null && crStart != from) return@use SegmentResult.RANGE_IGNORED
                        val body = resp.body ?: return@use SegmentResult.FAILED
                        // 收尾托管可能已缩短区间：以**当前**末端为准，多读到的丢弃。
                        end = endProvider()
                        expected = end - pieceStart + 1
                        if (done >= expected) return@use SegmentResult.OK
                        val written = if (writeBufferAt != null && directBuffer != null) {
                            copyToStoreDirect(
                                body.byteStream(), pieceStart, done, expected - done,
                                writeBufferAt, directBuffer, onProgress,
                            )
                        } else {
                            require(buffer.isNotEmpty()) {
                                "既没有 writeBufferAt 也没有读缓冲：无法写入"
                            }
                            copyToStore(
                                body.byteStream(), pieceStart, done, expected - done,
                                writeAt, buffer, onProgress,
                            )
                        }
                        // done 必须跟着实际写入量推进：调用方靠它更新进度记录。
                        done += written
                        if (done < expected) {
                            // `done` 已由 copyToStore 的 onProgress 推进到实际写入位置。
                            reportFailure(
                                "连接提前结束：分片 $pieceStart-${pieceStart + expected - 1} 只收到 " +
                                    "$written / ${expected - alreadyWritten} 字节"
                            )
                            SegmentResult.FAILED
                        } else SegmentResult.OK
                    }
                    200 -> SegmentResult.RANGE_IGNORED
                    416 -> SegmentResult.OK
                    else -> {
                        if (code == 401 || code == 403 || code == 410) {
                            forgetResolvedUrl(taskId)
                            SegmentResult.AUTH_EXPIRED
                        } else if (code in 500..599) {
                            SegmentResult.THROTTLED
                        } else {
                            SegmentResult.FAILED
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (!coroutineContext.isActive) throw CancellationException("canceled", e)
            reportFailure(describeIoFailure(e))
            SegmentResult.FAILED
        } finally {
            activeCalls[taskId]?.remove(call)
            handle?.dispose()
        }
    }

    /**
     * 读 socket → 按偏移写目标文件。
     *
     * @return 本次新写入的字节数
     */
    /**
     * 读 socket → 按偏移写目标文件（**直接缓冲区**路径，省一次整块拷贝）。
     *
     * `Channels.newChannel(InputStream)` 会把 socket 的字节直接读进 [ByteBuffer]，
     * 然后 [writeBufferAt] 把同一个缓冲区写给 FileChannel。中间不产生 `byte[]`，
     * 也不触发 `FileChannel` 对堆缓冲的"复制到临时直接缓冲"。
     */
    private suspend fun copyToStoreDirect(
        input: java.io.InputStream,
        pieceStart: Long,
        alreadyDone: Long,
        expected: Long,
        writeBufferAt: (position: Long, src: java.nio.ByteBuffer) -> Int,
        buffer: java.nio.ByteBuffer,
        onProgress: suspend (newlyWritten: Long, totalWritten: Long) -> Unit,
    ): Long {
        val src = java.nio.channels.Channels.newChannel(input)
        var written = 0L
        var totalWritten = alreadyDone
        while (true) {
            buffer.clear()
            val n = src.read(buffer)
            if (n <= 0) break
            buffer.flip()
            val allow = min(n.toLong(), expected - written)
            if (allow <= 0) break
            buffer.limit((buffer.position() + allow).toInt())
            var pos = pieceStart + alreadyDone + written
            while (buffer.hasRemaining()) {
                val w = writeBufferAt(pos, buffer)
                if (w <= 0) return written
                pos += w
            }
            written += allow
            totalWritten += allow
            onProgress(allow, totalWritten)
            if (written >= expected) break
        }
        return written
    }

    private suspend fun copyToStore(
        input: java.io.InputStream,
        pieceStart: Long,
        alreadyDone: Long,
        expected: Long,
        writeAt: (position: Long, buf: ByteArray, off: Int, len: Int) -> Int,
        buffer: ByteArray,
        onProgress: suspend (newlyWritten: Long, totalWritten: Long) -> Unit,
    ): Long {
        var written = 0L
        var totalWritten = alreadyDone
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            val allow = min(n.toLong(), expected - written)
            if (allow <= 0) break
            // 分段写：writeAt 可能一次没写完（理论上位置写会写满，但不假设）。
            var off = 0
            var left = allow.toInt()
            var pos = pieceStart + alreadyDone + written
            while (left > 0) {
                val w = writeAt(pos, buffer, off, left)
                if (w <= 0) return written
                off += w
                left -= w
                pos += w
            }
            written += allow
            totalWritten += allow
            onProgress(allow, totalWritten)
            if (written >= expected) break
        }
        return written
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
    ): SegmentResult = downloadSegment(taskId, url, start, { end }, partFile, headers, onBytes)

    /**
     * 同上，但区间末端由 [endProvider] 现读 —— 用于**收尾托管**：
     * 分片在下载途中可能被"让出后半段"，此处每次检查都能拿到最新区间，
     * 从而在到达新末端时提前收工（不把已让出的部分也下下来）。
     *
     * 注意 HTTP 请求头里的 `Range` 仍按**首次读取**的值发送：这是刻意的 ——
     * 若托管发生在请求已发出之后，本次仍会读完原区间，此时：
     *  - 原分片写入到 `min(实际末端, 现区间末端)` 就停（见 writeSlice 的 limit）；
     *  - 让出去的部分由新分片重新请求，内容一致（同一文件的同一区间），
     *    重复下载少量字节换来"零等待"，且不会产生错误数据。
     */
    suspend fun downloadSegment(
        taskId: Long,
        url: String,
        start: Long,
        endProvider: () -> Long,
        partFile: File,
        headers: Map<String, String>,
        onBytes: suspend (Long) -> Unit,
        /**
         * `If-Range` 校验器（可选）：有强 ETag 时优先用它，否则用 Last-Modified。
         *
         * 【为什么必须带】分片下载是**多个请求拼一个文件**。若中途 CDN 换了内容
         * （网盘重新上传、源站更新），后续分片会取到新版本的字节，
         * 与已下的旧版本字节拼在一起 —— 得到一个**新旧混杂**的文件，
         * 而长度校验仍会通过（大小常常一致）。带 If-Range 后服务器会
         * 在内容不匹配时返回 200（整文件）而非 206，上层据此走 RANGE_IGNORED 重试，
         * 不会写入错误数据。
         *
         * 注意 If-Range 只接受**强校验器**（`W/` 开头的弱 ETag 会被忽略），
         * 故调用方需按 [strongIfRange] 的规则挑选。
         */
        ifRange: String? = null,
        /**
         * 本次分片读缓冲大小（字节）。**由调度器按并发数摊薄后传入**（见
         * [TurboConfig.effectiveIoBufferSize]）：同一任务会同时跑 `workers` 个分片，
         * 每个都按 [TurboConfig.ioBufferSize] 独占一个缓冲的话，
         * 256 连接 = 256MB = Android 默认整堆（2026-10-10 真机 OOM 事故）。
         *
         * null 时回落到构造时注入的 [bufferSizeProvider]（整文件单流等低并发场景）。
         */
        bufferSize: Int? = null,
    ): SegmentResult {
        var end = endProvider()
        val existing = partFile.length()
        var expected = end - start + 1
        if (existing >= expected) return SegmentResult.OK
        val from = start + existing

        // 优先用已学习到的重定向终址；没有就先用原始 URL（这一次会跟 302，并把终址记下来）。
        val target = urlFor(taskId, url)
        val req = Request.Builder()
            .url(target)
            .header("Range", "bytes=$from-$end")
            .apply { if (!ifRange.isNullOrBlank()) header("If-Range", ifRange) }
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
                        // 收尾托管可能在请求发出后缩短了区间：以**当前**末端为准，
                        // 多读到的字节直接丢弃（见 writeSlice 的 limit 参数），
                        // 让出去的部分由新分片重新取，内容一致。
                        end = endProvider()
                        expected = end - start + 1
                        if (existing >= expected) return@use SegmentResult.OK
                        val written = writeSlice(
                            body.byteStream(), partFile, existing, expected - existing, onBytes,
                            bufferSize,
                            // P12 开关：默认直写（见 TurboConfig.bufferedSegmentWrite 的实测结论）。
                            bufferedWriteProvider(),
                        )
                        if (existing + written != expected) {
                            // 记录**具体差多少**：这是区分"服务器短读"与"本地写失败"的唯一线索。
                            // 上层把这条拼进最终错误，用户才知道该重试还是该换源。
                            failureByTask[taskId] =
                                "连接提前结束：分片 $start-${start + expected - 1} 只收到 " +
                                    "$written / ${expected - existing} 字节"
                            SegmentResult.FAILED
                        } else SegmentResult.OK
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
                        //
                        // 单独归为 AUTH_EXPIRED（而非 FAILED）：403 **不是**"并发太高"的信号，
                        // 若混进普通失败会触发背压降并发 —— 既解决不了 403，又白掉速度。
                        if (code == 401 || code == 403 || code == 410) {
                            forgetResolvedUrl(taskId)
                            SegmentResult.AUTH_EXPIRED
                        } else if (code in 500..599) {
                            SegmentResult.THROTTLED
                        } else {
                            SegmentResult.FAILED
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (!coroutineContext.isActive) throw CancellationException("canceled", e)
            // 同上：把 IO 层的原因留给上层拼进最终错误。
            failureByTask[taskId] = describeIoFailure(e)
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
        // 每次进入都先清掉上一次的原因，避免把上一轮的错误带给这一轮。
        failureByTask.remove(taskId)
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
                if (total > 0 && written < total) {
                    // 没抛异常但字节不够：服务器正常收尾，只是发少了。
                    failureByTask[taskId] = "连接提前结束：只收到 $written / $total 字节"
                    return@use false
                }
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (!coroutineContext.isActive) throw CancellationException("canceled", e)
            // 【带回原因】以前这里直接 `false`，上层只能给一句
            // 「整文件下载失败（服务器不支持 Range、无响应或响应异常）」——
            // 而真实原因可能是「连接被重置」「读到一半 EOF（声明长度 > 实际）」「超时」。
            // 这三类对用户意味着完全不同的下一步（重试 / 换源 / 等网络），
            // 压成同一句话等于把可行动的信息扔掉。
            failureByTask[taskId] = describeIoFailure(e)
            false
        } finally {
            activeCalls[taskId]?.remove(call)
            handle?.dispose()
        }
    }

    /**
     * 把整文件下载的 IO 异常翻译成一句能行动的话。
     *
     * 分类依据（都是实测过的真实形态，见 `_audit/fault_server.py` 的注入端点）：
     * - `unexpected end of stream` / `EOF` → 服务器声明了长度但提前断开（短读）；
     * - `Connection reset` / `SocketException` → 连接被中途重置（RST）；
     * - `timeout` → 读超时（服务器不再发数据）；
     * - 其余保留原文（`e::class.simpleName: message`），不做无根据的推断。
     */
    private fun describeIoFailure(e: IOException): String {
        val raw = (e.message ?: "").lowercase()
        val name = e::class.simpleName ?: "IOException"
        return when {
            "unexpected end of stream" in raw || "eof" in raw ->
                "连接被提前关闭：服务器声明的长度与实际发出的字节不符（读到一半就断了）"
            "connection reset" in raw || "socketexception" in raw || "broken pipe" in raw ->
                "连接被对端重置"
            "timeout" in raw || "timed out" in raw ->
                "读取超时（服务器长时间没有发数据）"
            else -> "$name: ${e.message ?: "无法读取响应体"}"
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
        /** 按并发摊薄后的缓冲大小；null = 用 provider（见 [downloadSegment] 的说明）。 */
        bufferSize: Int? = null,
        /**
         * 写入是否走 [BufferedOutputStream]（P12 评估项，默认 false）。
         *
         * 【为什么默认关闭】见 [TurboConfig.bufferedSegmentWrite] 的记录：实测在本机回环
         * 128 连接档没有可复现的收益，而它会把"已写入长度"的含义从"已落盘"变成"已交给 OS 缓冲"，
         * 与断点续传的语义耦合。开关留着是为了能在更快的链路上复测。
         */
        bufferedWrite: Boolean = false,
    ): Long {
        var written = 0L
        RandomAccessFile(partFile, "rw").use { raf ->
            raf.seek(seekPos)
            val buf = ByteArray(
                (bufferSize ?: bufferSizeProvider()).coerceAtLeast(TurboConfig.MIN_IO_BUFFER_BYTES)
            )
            if (!bufferedWrite) {
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
                return written
            }
            // 【flush 的时机就是 onBytes 的时机】先 flush 再回调，保证回调发生时
            // 字节确实已经落到文件里 —— 断点续传读的是文件长度，若缓冲未刷就回调，
            // 中途被杀会留下"进度说下了但文件没有"的缺口。
            java.io.BufferedOutputStream(java.io.FileOutputStream(raf.fd), WRITE_BUFFER_BYTES).use { out ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    val allow = min(n.toLong(), expected - written)
                    if (allow <= 0) break
                    out.write(buf, 0, allow.toInt())
                    out.flush()
                    written += allow
                    onBytes(allow)
                    if (written >= expected) break
                }
            }
        }
        return written
    }
}

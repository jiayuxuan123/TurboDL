package dev.turbodl.plugin.hls

import dev.turbodl.core.BackendContext
import dev.turbodl.core.BackendResult
import dev.turbodl.core.DownloadBackend
import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.TurboHttpClients
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * HLS VOD backend.
 *
 * Handles an HTTP(S) M3U8 request as a protocol rather than letting the generic HTTP backend
 * download the playlist text. It resolves a master playlist to its highest-bandwidth variant,
 * validates a static VOD media playlist, downloads its segments concurrently, optionally
 * decrypts AES-128 CBC segments, and returns the ordered segment parts to TurboClient for its
 * normal merge/state/integrity pipeline.
 *
 * Intentional scope boundary:
 *  - supported: HTTP(S), master/media playlists, VOD ENDLIST, EXTINF, relative URIs,
 *    EXT-X-BYTERANGE, AES-128 identity keys;
 *  - rejected explicitly: live/event streams, DRM/SAMPLE-AES, fMP4 EXT-X-MAP, discontinuities,
 *    nested masters, non-HTTP URIs.
 *
 * This produces a concatenated transport/media stream (normally .ts). It does not pretend to
 * remux into MP4; packaging/remuxing belongs to a separate post-processing plugin.
 */
class HlsBackend : DownloadBackend {
    override val name: String = "hls-vod"

    override fun supports(request: DownloadRequest): Boolean = runCatching {
        val uri = URI(request.url)
        uri.scheme in setOf("http", "https") && uri.path.lowercase().endsWith(".m3u8")
    }.getOrDefault(false)

    override suspend fun download(context: BackendContext): BackendResult = coroutineScope {
        // 【禁用自动重定向】改由本插件逐跳校验（见 HlsRequestPolicy.redirectTarget）：
        // OkHttp 的自动跟随会直接跳到 Location，我们无从检查目标是否 HTTPS、
        // 也不会重新按"新来源"过滤敏感头 —— 一次 302 到明文 http 或第三方域，
        // 就会把凭证发出去，且后续下载全程都在那个地址上进行。
        val client = TurboHttpClients.create(context.config).newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        try {
            val playlist = resolvePlaylist(client, context)
            // Playlist manifests do not reliably expose a final byte total. Progress starts with
            // -1 and is made authoritative once all completed segment files are known.
            context.reportTotalSize(-1)
            val keyCache = ConcurrentHashMap<URI, kotlinx.coroutines.CompletableDeferred<ByteArray>>()
            preloadKeys(client, context, playlist.segments, keyCache)
            downloadSegments(client, context, playlist.segments, keyCache)
        } finally {
            // 【逐句兜底，别用一条裸顺序链】两点理由：
            // 1. `evictAll()` 会真的 close 每条空闲连接的 socket（TLS 还要写 close_notify），
            //    在 Android 主线程上会抛 NetworkOnMainThreadException —— 而 OkHttp 的
            //    `Util.closeQuietly` 只吞 IOException，RuntimeException 会原样穿透。
            //    它抛出时后面那句就不执行，连接池清理被跳过。
            // 2. **更严重的是 finally 抛出的异常会覆盖原始异常**：包括覆盖
            //    CancellationException —— 用户点「暂停」会被记成任务 FAILED 而不是 PAUSED。
            runCatching { client.dispatcher.executorService.shutdown() }
            runCatching { client.connectionPool.evictAll() }
        }
    }

    private suspend fun resolvePlaylist(client: OkHttpClient, context: BackendContext): HlsMediaPlaylist {
        val inputUri = URI(context.request.url)
        val initial = fetchText(client, context, inputUri)
        val mediaUri = HlsManifestParser.selectVariant(initial, inputUri) ?: inputUri
        val mediaText = if (mediaUri == inputUri) initial else fetchText(client, context, mediaUri)
        return HlsManifestParser.parseMedia(mediaText, mediaUri)
    }

    private suspend fun preloadKeys(
        client: OkHttpClient,
        context: BackendContext,
        segments: List<HlsSegment>,
        keyCache: ConcurrentHashMap<URI, kotlinx.coroutines.CompletableDeferred<ByteArray>>,
    ) {
        for (keyUri in segments.mapNotNull { it.encryption?.keyUri }.distinct()) {
            keyFor(client, context, keyUri, keyCache)
        }
    }

    private suspend fun downloadSegments(
        client: OkHttpClient,
        context: BackendContext,
        segments: List<HlsSegment>,
        keyCache: ConcurrentHashMap<URI, kotlinx.coroutines.CompletableDeferred<ByteArray>>,
    ): BackendResult = coroutineScope {
        val partsDir = File(context.workDir, "hls-segments").apply { mkdirs() }
        val concurrency = (context.request.connectionsOverride ?: context.config.maxConnectionsPerTask)
            .coerceIn(1, 256)
        val gate = Semaphore(concurrency)
        val activeConnections = AtomicInteger(0)
        val progressBytes = AtomicLong(completedBytes(partsDir, segments))
        context.reportProgress(progressBytes.get(), 0)

        segments.map { segment ->
            async {
                gate.withPermit {
                    downloadSegmentWithRetry(
                        client = client,
                        context = context,
                        partsDir = partsDir,
                        segment = segment,
                        keyCache = keyCache,
                        activeConnections = activeConnections,
                        progressBytes = progressBytes,
                    )
                }
            }
        }.awaitAll()

        val orderedParts = segments.map { segmentPart(partsDir, it.index) }
        val total = orderedParts.sumOf { it.length() }
        context.reportTotalSize(total)
        context.reportProgress(total, 0)
        BackendResult(orderedParts, total)
    }

    private suspend fun downloadSegmentWithRetry(
        client: OkHttpClient,
        context: BackendContext,
        partsDir: File,
        segment: HlsSegment,
        keyCache: ConcurrentHashMap<URI, kotlinx.coroutines.CompletableDeferred<ByteArray>>,
        activeConnections: AtomicInteger,
        progressBytes: AtomicLong,
    ) {
        val complete = segmentPart(partsDir, segment.index)
        if (complete.isFile && complete.length() > 0L) return

        val attempts = context.config.maxRetries + 1
        var lastFailure: Throwable? = null
        repeat(attempts) { attempt ->
            ensureBackendActive(context)
            val temp = File(partsDir, "${segment.index}.downloading")
            temp.delete()
            var attemptBytes = 0L
            try {
                fetchSegmentToFile(client, context, segment, temp, activeConnections) { delta ->
                    attemptBytes += delta
                    context.throttle(delta)
                    context.reportProgress(progressBytes.addAndGet(delta), activeConnections.get())
                }
                if (segment.encryption != null) {
                    val key = keyFor(client, context, segment.encryption.keyUri, keyCache)
                    decryptAes128(temp, complete, key, segment.encryption.iv)
                    temp.delete()
                } else if (!temp.renameTo(complete)) {
                    temp.copyTo(complete, overwrite = true)
                    temp.delete()
                }
                if (!complete.isFile || complete.length() <= 0L) {
                    throw IllegalStateException("HLS segment ${segment.index} was empty after download")
                }
                return
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                progressBytes.addAndGet(-attemptBytes)
                temp.delete()
                complete.delete()
                lastFailure = t
                if (attempt + 1 < attempts) delay(retryDelayMillis(attempt))
            }
        }
        throw IllegalStateException(
            "HLS segment ${segment.index} failed after $attempts attempt(s): ${lastFailure?.message}",
            lastFailure,
        )
    }

    private suspend fun fetchSegmentToFile(
        client: OkHttpClient,
        context: BackendContext,
        segment: HlsSegment,
        output: File,
        activeConnections: AtomicInteger,
        onBytes: suspend (Long) -> Unit,
    ) {
        val requestBuilder = requestBuilder(segment.uri, context.request)
        segment.byteRange?.let { range ->
            requestBuilder.header("Range", "bytes=${range.offset}-${range.offset + range.length - 1}")
        }
        val request = requestBuilder.build()
        execute(client, request, activeConnections) { response ->
            if (!response.isSuccessful) throw IllegalStateException("HLS segment ${segment.index} HTTP ${response.code}")
            if (segment.byteRange != null && response.code != 206) {
                throw IllegalStateException("HLS segment ${segment.index} ignored EXT-X-BYTERANGE")
            }
            val body = response.body ?: throw IllegalStateException("HLS segment ${segment.index} has no response body")
            FileOutputStream(output, false).use { file ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = 0L
                    while (true) {
                        ensureBackendActive(context)
                        val read = input.read(buffer)
                        if (read <= 0) break
                        // 单分片字节上限：分片是远端可控内容，无上限时一个异常响应能把磁盘写满。
                        written = HlsIo.checkedTotal(written, read.toLong())
                        file.write(buffer, 0, read)
                        onBytes(read.toLong())
                    }
                }
            }
            segment.byteRange?.let { range ->
                if (output.length() != range.length) {
                    throw IllegalStateException(
                        "HLS segment ${segment.index} byte range size mismatch: expected ${range.length}, got ${output.length()}",
                    )
                }
            }
        }
    }

    private suspend fun keyFor(
        client: OkHttpClient,
        context: BackendContext,
        uri: URI,
        cache: ConcurrentHashMap<URI, kotlinx.coroutines.CompletableDeferred<ByteArray>>,
    ): ByteArray {
        cache[uri]?.let { return it.await() }
        val pending = kotlinx.coroutines.CompletableDeferred<ByteArray>()
        val existing = cache.putIfAbsent(uri, pending)
        if (existing != null) return existing.await()
        try {
            val key = fetchBytes(client, context, uri)
            require(key.size == 16) { "HLS AES-128 key must be exactly 16 bytes, got ${key.size}" }
            pending.complete(key)
            return key
        } catch (t: Throwable) {
            pending.completeExceptionally(t)
            cache.remove(uri, pending)
            throw t
        }
    }

    private suspend fun fetchText(client: OkHttpClient, context: BackendContext, uri: URI): String =
        fetchBytes(client, context, uri).toString(Charsets.UTF_8)

    private suspend fun fetchBytes(client: OkHttpClient, context: BackendContext, uri: URI): ByteArray {
        val request = requestBuilder(uri, context.request).build()
        return execute(client, request, null) { response ->
            if (!response.isSuccessful) throw IllegalStateException("HLS resource $uri HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("HLS resource $uri has no response body")
            body.bytes().also { context.throttle(it.size.toLong()) }
        }
    }

    private fun requestBuilder(uri: URI, original: DownloadRequest): Request.Builder =
        HlsRequestPolicy.newRequestBuilder(
            targetUrl = uri.toString(),
            originUrl = original.url,
            headers = original.headers,
        )

    private suspend fun <T> execute(
        client: OkHttpClient,
        request: Request,
        activeConnections: AtomicInteger?,
        block: suspend (okhttp3.Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        // 【手动逐跳跟随重定向】客户端的自动跟随已关闭（见 download() 的说明），
        // 这里逐跳校验：每跳都要求 HTTPS，并按**新地址**重新计算敏感头
        // （跨域则不携带 Cookie/Authorization/Referer）。
        var current = request
        var hops = 0
        while (true) {
            val call = client.newCall(current)
            val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
            activeConnections?.incrementAndGet()
            val redirect: okhttp3.HttpUrl?
            try {
                call.execute().use { response ->
                    if (response.isRedirect) {
                        val here = response.request.url
                        redirect = HlsRequestPolicy.redirectTarget(
                            response.header("Location"), here,
                        )
                        if (redirect == null) {
                            throw IllegalStateException(
                                "HLS redirect not followed: target is not a valid HTTPS URL",
                            )
                        }
                    } else {
                        return@withContext block(response)
                    }
                }
            } finally {
                activeConnections?.decrementAndGet()
                handle?.dispose()
            }
            hops++
            if (hops > MAX_REDIRECTS) {
                throw IllegalStateException("HLS request exceeded $MAX_REDIRECTS redirects")
            }
            val target = redirect ?: throw IllegalStateException("HLS redirect without target")
            current = HlsRequestPolicy.newRequestBuilder(
                targetUrl = target.toString(),
                originUrl = request.url.toString(),
                headers = headersOf(request),
            ).build()
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("unreachable")
    }

    /** 取回请求上已设置的头（供重定向时重新按来源过滤）。 */
    private fun headersOf(request: Request): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (i in 0 until request.headers.size) {
            out[request.headers.name(i)] = request.headers.value(i)
        }
        return out
    }

    private fun decryptAes128(input: File, output: File, key: ByteArray, iv: ByteArray) {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val plain = cipher.doFinal(input.readBytes())
        FileOutputStream(output, false).use { it.write(plain) }
    }

    private fun completedBytes(partsDir: File, segments: List<HlsSegment>): Long =
        segments.sumOf { segmentPart(partsDir, it.index).takeIf { it.isFile }?.length() ?: 0L }

    private fun segmentPart(partsDir: File, index: Int): File = File(partsDir, "%06d.part".format(index))

    private fun ensureBackendActive(context: BackendContext) {
        if (!context.isActive()) throw CancellationException("HLS task is no longer active")
    }

    private fun retryDelayMillis(attempt: Int): Long = min(4_000L, 250L shl attempt.coerceAtMost(4))

    private companion object {
        const val BUFFER_SIZE = 128 * 1024

        /** 重定向上限：正常 HLS 最多 1~2 跳，超过即视为异常链路。 */
        const val MAX_REDIRECTS = 5
    }
}

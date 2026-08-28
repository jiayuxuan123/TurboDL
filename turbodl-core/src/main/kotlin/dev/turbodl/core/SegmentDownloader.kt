package dev.turbodl.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
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
internal class SegmentDownloader(private val clientProvider: () -> OkHttpClient) {

    private val client get() = clientProvider()
    private val activeCalls = ConcurrentHashMap<Long, MutableSet<okhttp3.Call>>()

    fun cancelCalls(taskId: Long) {
        activeCalls.remove(taskId)?.forEach { runCatching { it.cancel() } }
    }

    companion object {
        private const val BUFFER = 256 * 1024
    }

    /** 探测结果：总大小、是否支持 Range、重定向后的最终 URL。 */
    data class ProbeResult(
        val totalSize: Long?,
        val supportsRange: Boolean,
        /** 跟随 3xx 重定向后的最终 URL（网盘原始链接常 302 到带签名的 CDN 临时直链）。 */
        val resolvedUrl: String,
    )

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
    suspend fun probe(url: String, headers: Map<String, String>): ProbeResult =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                // identity 在自定义头之后设置，确保总是生效（防止调用方传入 Accept-Encoding: gzip 覆盖）：
                // 若 gzip 透明解压，实际写入字节会与 Content-Range 不一致 → 大小校验失败。
                .header("Accept-Encoding", "identity")
                .get().build()
            val call = client.newCall(req)
            val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { resp ->
                    // resp.request.url 是跟随所有 3xx 之后的最终地址（原始链接 302→CDN 临时直链）。
                    val finalUrl = resp.request.url.toString()
                    if (resp.header("Content-Type").orEmpty().contains("text/html", true)) {
                        return@use ProbeResult(null, false, finalUrl)
                    }
                    when (resp.code) {
                        206 -> {
                            val total = resp.header("Content-Range")
                                ?.substringAfter('/')?.toLongOrNull()
                            ProbeResult(total, true, finalUrl)
                        }
                        200 -> {
                            // 不支持 Range：返回整文件
                            val total = resp.header("Content-Length")?.toLongOrNull()
                            ProbeResult(total, false, finalUrl)
                        }
                        else -> ProbeResult(null, false, finalUrl)
                    }
                }
            } catch (e: Exception) {
                ProbeResult(null, false, url)
            } finally {
                handle?.dispose()
            }
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
    ): SegmentResult = withContext(Dispatchers.IO) {
        val existing = partFile.length()
        val expected = end - start + 1
        if (existing >= expected) return@withContext SegmentResult.OK
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
        try {
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
        val call = client.newCall(req)
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
                        val buf = ByteArray(BUFFER)
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
            val buf = ByteArray(BUFFER)
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

package dev.turbodl.core

import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext as currentCtx
import java.io.File

/**
 * Built-in multi-threaded HTTP/HTTPS download backend.
 *
 * This is the default backend that makes turbodl-core usable standalone (hybrid plan A):
 * it wraps the existing engine internals ([SegmentDownloader] + [SegmentScheduler] +
 * whole-file fallback). When the optional plugin runtime is present, a plugin backend
 * may override this one through the runtime's registry — but core never depends on the
 * runtime, and without it this backend is always used.
 *
 * The task state machine, event emission, merging and integrity checks remain in
 * [TurboClient]; this backend only produces the ordered parts to merge.
 */
internal class BuiltinHttpBackend(
    private val downloader: SegmentDownloader,
) : DownloadBackend {

    override val name: String = "builtin-http"

    override fun supports(request: DownloadRequest): Boolean {
        val u = request.url.lowercase()
        return u.startsWith("http://") || u.startsWith("https://")
    }

    override suspend fun download(context: BackendContext): BackendResult {
        val request = context.request
        val chunkDir = context.workDir
        val speedLimiter = SpeedLimiter { context.config.globalSpeedLimitBytesPerSec }

        // Probe size + Range support. 同时拿到重定向后的最终 URL。
        // 带超时与重试：探测无界限等待会让任务卡在“看似下载中但字节为 0”，
        // 且瞬时抖动不应直接退化成单线程整文件下载。
        val probe = downloader.probeWithRetry(
            request.url,
            request.headers,
            timeoutMs = context.config.probeTimeoutMs,
            retries = context.config.probeRetries,
        )
        val total = probe.totalSize ?: request.knownSize.takeIf { it > 0 } ?: -1L
        context.reportTotalSize(total)
        // 静默上报元数据（尽力而为，不影响下载）：宿主可用服务器建议名重命名、记录 MIME 等。
        runCatching {
            context.reportMetadata(
                suggestedFileName = probe.suggestedFileName,
                contentType = probe.contentType,
                etag = probe.etag,
                lastModified = probe.lastModified,
            )
        }
        // 关键：后续分片/整文件下载均使用重定向后的最终 URL（如网盘原始链 302→CDN 临时直链）。
        // 否则每个分片连接都重走 302，可能命中不同节点或被拒，表现为“显示下载中但字节/线程不动”。
        val effectiveUrl = probe.resolvedUrl.ifBlank { request.url }
        val supportsRange = probe.supportsRange

        // ---------- 续传校验（尽力而为，失败不阻断下载）----------
        // 思路参考 aria2 的 .aria2 控制文件与 IDM 的续传校验：把「大小+ETag+Last-Modified」
        // 写入分片目录旁的 .validator。下次续传前对比：不一致说明服务器侧文件已变更，
        // 旧分片不能再用（否则合并出一个新旧混杂的损坏文件，而且大小校验可能恰好通过）。
        // 服务器不提供任何校验器时 validator 为空串 → 不做强制，保持旧的宽松续传行为。
        run {
            val marker = File(chunkDir, ".validator")
            val now = probe.validator
            if (now.isNotEmpty()) {
                val prev = runCatching { if (marker.isFile) marker.readText().trim() else "" }.getOrDefault("")
                if (prev.isNotEmpty() && prev != now) {
                    // 文件已变更：静默丢弃过期分片，从头下（不报错，对用户透明）
                    chunkDir.listFiles()?.forEach { f -> runCatching { f.delete() } }
                    chunkDir.mkdirs()
                }
                runCatching { marker.writeText(now) }
            }
        }

        // Server does not support Range, or size unknown -> whole-file fallback (cannot segment).
        if (!supportsRange || total <= 0) {
            val outPart = File(chunkDir, "whole.part")
            var acc = 0L
            // 卡死守护：整文件单流路径也需要（否则服务器接受连接但不吐字节时，
            // 会一路阻塞到 readTimeout 甚至反复重试，表现为“显示下载中但永远不动”）。
            val progressed = java.util.concurrent.atomic.AtomicLong(0)
            val stallMs = context.config.stallTimeoutMs
            val watchdog = if (stallMs > 0) kotlinx.coroutines.CoroutineScope(currentCtx).launch {
                var lastBytes = -1L
                var lastChange = System.currentTimeMillis()
                while (context.isActive()) {
                    kotlinx.coroutines.delay(2000)
                    val cur = progressed.get()
                    val now = System.currentTimeMillis()
                    if (cur != lastBytes) { lastBytes = cur; lastChange = now; continue }
                    if (now - lastChange >= stallMs) {
                        // 主动断开：downloadWhole 会得到 IOException 并返回 false
                        downloader.cancelCalls(context.taskId)
                        break
                    }
                }
            } else null
            val ok = try {
                downloader.downloadWhole(context.taskId, effectiveUrl, outPart, request.headers, total) { d ->
                    context.throttle(d)
                    acc += d
                    progressed.set(acc)
                    if (!context.isActive()) return@downloadWhole
                    context.reportProgress(acc, 1)
                }
            } finally {
                watchdog?.cancel()
            }
            if (!ok) throw IllegalStateException(
                "整文件下载失败（服务器不支持 Range、无响应或响应异常）"
            )
            return BackendResult(listOf(outPart), if (total > 0) total else outPart.length())
        }

        val connections = (request.connectionsOverride ?: context.config.maxConnectionsPerTask).coerceIn(1, 256)

        // 连接预热 / DNS 预解析：正式分片前先对解析后的最终 URL 建好若干连接（并解析 DNS），
        // 填充连接池，避免分片启动时串行等待 DNS + TCP/TLS 握手。
        if (context.config.warmUpConnections) {
            // 预热并发与下载并发解耦：只需少量连接就能把 DNS/TLS 热起来。
            // 若按下载并发（如 64/128）并发预热，弱网/2.4GHz Wi-Fi 下会互相争抢信道，
            // 反而拖慢首字节时间，表现为“开头卡住”。
            val warmCount = minOf(
                context.config.warmUpConnectionCount.takeIf { it > 0 } ?: connections,
                context.config.warmUpMaxParallel,
            ).coerceAtLeast(1)
            runCatching {
                downloader.warmUp(
                    effectiveUrl, request.headers, warmCount,
                    timeoutMs = context.config.warmUpTimeoutMs,
                )
            }
        }

        val scheduler = SegmentScheduler(downloader, context.config, speedLimiter)
        // 真实并发数由调度器回报（而非直接用配置值），UI 才能看到实际跑满多少线程。
        val liveConnsRef = java.util.concurrent.atomic.AtomicInteger(connections)
        val outcome = scheduler.run(
            taskId = context.taskId,
            url = effectiveUrl,
            total = total,
            chunkDir = chunkDir,
            headers = request.headers,
            connections = connections,
            resumeFrom = 0L,
            onBytes = { _, abs -> context.reportProgress(abs, liveConnsRef.get()) },
            onConnections = { live -> liveConnsRef.set(live) },
            isActive = { context.isActive() },
        )

        return when (outcome) {
            is SegmentScheduler.Outcome.Completed ->
                BackendResult(scheduler.finalParts(chunkDir), total)

            is SegmentScheduler.Outcome.NeedWholeFallback -> {
                // Range 反复被忽略才会走到这里（单次偶发已在调度器重试层容忍）。
                // 保留已完成的分片，只删那些不完整/部分写入的，避免几百 MB 进度瞬间丢失后从 0 单流重下。
                // 但若本来就一个完整分片都没有（total 很小或服务器从头就不支持 Range），则直接整文件单流。
                val keptComplete = chunkDir.listFiles { f ->
                    f.name.startsWith("seg_") && f.name.endsWith(".part")
                }?.any { f ->
                    val name = f.name.removePrefix("seg_").removeSuffix(".part")
                    val s = name.substringBefore('_').toLongOrNull()
                    val e = name.substringAfter('_').toLongOrNull()
                    s != null && e != null && f.length() >= (e - s + 1)
                } ?: false

                if (keptComplete) {
                    // 有已完成分片：仍按分片模式完成（调度器下次会跳过已完成块），
                    // 不能直接整文件回退（会与已有分片混合）。重跑一次调度，让剩余块继续分片下载。
                    val retry = scheduler.run(
                        taskId = context.taskId, url = effectiveUrl, total = total,
                        chunkDir = chunkDir, headers = request.headers, connections = connections,
                        resumeFrom = 0L,
                        onBytes = { _, abs -> context.reportProgress(abs, liveConnsRef.get()) },
                        onConnections = { live -> liveConnsRef.set(live) },
                        isActive = { context.isActive() },
                    )
                    if (retry is SegmentScheduler.Outcome.Completed) {
                        return BackendResult(scheduler.finalParts(chunkDir), total)
                    }
                    // 仍不行：清空重新整文件下（兼容真不支持 Range 的服务器）。
                }
                chunkDir.deleteRecursively(); chunkDir.mkdirs()
                val outPart = File(chunkDir, "whole.part")
                var acc = 0L
                val ok = downloader.downloadWhole(context.taskId, effectiveUrl, outPart, request.headers, total) { d ->
                    context.throttle(d)
                    acc += d
                    if (!context.isActive()) return@downloadWhole
                    context.reportProgress(acc, 1)
                }
                if (!ok) throw IllegalStateException("Whole-file fallback download failed")
                BackendResult(listOf(outPart), total)
            }

            is SegmentScheduler.Outcome.Failed -> {
                if (!context.isActive()) throw kotlinx.coroutines.CancellationException("paused")
                throw IllegalStateException(outcome.reason)
            }
        }
    }
}

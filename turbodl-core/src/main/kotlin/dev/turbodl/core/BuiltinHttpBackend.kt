package dev.turbodl.core

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
        val probe = downloader.probe(request.url, request.headers)
        val total = probe.totalSize ?: request.knownSize.takeIf { it > 0 } ?: -1L
        context.reportTotalSize(total)
        // 关键：后续分片/整文件下载均使用重定向后的最终 URL（如网盘原始链 302→CDN 临时直链）。
        // 否则每个分片连接都重走 302，可能命中不同节点或被拒，表现为“显示下载中但字节/线程不动”。
        val effectiveUrl = probe.resolvedUrl.ifBlank { request.url }
        val supportsRange = probe.supportsRange

        // Server does not support Range, or size unknown -> whole-file fallback (cannot segment).
        if (!supportsRange || total <= 0) {
            val outPart = File(chunkDir, "whole.part")
            var acc = 0L
            val ok = downloader.downloadWhole(context.taskId, effectiveUrl, outPart, request.headers, total) { d ->
                context.throttle(d)
                acc += d
                if (!context.isActive()) return@downloadWhole
                context.reportProgress(acc, 1)
            }
            if (!ok) throw IllegalStateException("Whole-file download failed (server lacks Range support or bad response)")
            return BackendResult(listOf(outPart), if (total > 0) total else outPart.length())
        }

        val connections = (request.connectionsOverride ?: context.config.maxConnectionsPerTask).coerceIn(1, 256)

        // 连接预热 / DNS 预解析：正式分片前先对解析后的最终 URL 建好若干连接（并解析 DNS），
        // 填充连接池，避免分片启动时串行等待 DNS + TCP/TLS 握手。
        if (context.config.warmUpConnections) {
            val warmCount = context.config.warmUpConnectionCount.takeIf { it > 0 }
                ?: minOf(connections, 8)
            runCatching { downloader.warmUp(effectiveUrl, request.headers, warmCount) }
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

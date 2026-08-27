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

        // Probe size + Range support.
        val (probedSize, supportsRange) = downloader.probe(request.url, request.headers)
        val total = probedSize ?: request.knownSize.takeIf { it > 0 } ?: -1L
        context.reportTotalSize(total)

        // Server does not support Range, or size unknown -> whole-file fallback (cannot segment).
        if (!supportsRange || total <= 0) {
            val outPart = File(chunkDir, "whole.part")
            var acc = 0L
            val ok = downloader.downloadWhole(context.taskId, request.url, outPart, request.headers, total) { d ->
                context.throttle(d)
                acc += d
                if (!context.isActive()) return@downloadWhole
                context.reportProgress(acc, 1)
            }
            if (!ok) throw IllegalStateException("Whole-file download failed (server lacks Range support or bad response)")
            return BackendResult(listOf(outPart), if (total > 0) total else outPart.length())
        }

        val connections = (request.connectionsOverride ?: context.config.maxConnectionsPerTask).coerceIn(1, 256)
        val scheduler = SegmentScheduler(downloader, context.config, speedLimiter)
        // 真实并发数由调度器回报（而非直接用配置值），UI 才能看到实际跑满多少线程。
        val liveConnsRef = java.util.concurrent.atomic.AtomicInteger(connections)
        val outcome = scheduler.run(
            taskId = context.taskId,
            url = request.url,
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
                // Range ignored mid-flight -> clear parts and fall back to a single stream.
                chunkDir.deleteRecursively(); chunkDir.mkdirs()
                val outPart = File(chunkDir, "whole.part")
                var acc = 0L
                val ok = downloader.downloadWhole(context.taskId, request.url, outPart, request.headers, total) { d ->
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

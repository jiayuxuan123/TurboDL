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

    private companion object {
        /** 续传校验标记文件名。 */
        const val VALIDATOR_FILE = ".validator"
    }

    override fun supports(request: DownloadRequest): Boolean {
        val u = request.url.lowercase()
        return u.startsWith("http://") || u.startsWith("https://")
    }

    override suspend fun download(context: BackendContext): BackendResult {
        val request = context.request
        val chunkDir = context.workDir
        val speedLimiter = SpeedLimiter { context.config.globalSpeedLimitBytesPerSec }

        // ---------- 探测：必须快，且不能阻塞下载开始 ----------
        // 设计取舍：探测只为拿到大小/Range/重定向地址，它**不传输数据**。
        // 旧实现 20s × 3 次 + 退避 ≈ 最坏 61s 全程阻塞，用户看到的就是“解析卡一分钟”。
        // 现在：调用方已知大小时直接跳过探测；否则单次 6s、最多 1 次重试。
        val knownSize = request.knownSize.takeIf { it > 0 } ?: -1L
        // 目录里是否已有**可续传**的分片（非空 seg_*.part）。
        val hasResumableParts = chunkDir.listFiles()?.any { f ->
            f.isFile && f.name.startsWith("seg_") && f.name.endsWith(".part") && f.length() > 0
        } == true
        // 【续传场景不得跳过探测】跳过探测就拿不到 ETag / Last-Modified，
        // `ProbeResult` 的 validator 会退化成 `len=N|weak`（[SegmentDownloader.ProbeResult.isWeak]），
        // 而下面的续传校验规定"弱校验器不足以支撑安全续传"→ **无条件下丢弃全部旧分片**。
        // 结果：网盘类链接（调用方已知大小、跳过探测）的断点续传**静默失效**，每次都从 0 重下。
        // 代价对比很直白：多一次探测请求 vs 重下整个文件（1.3GB @1.5MB/s 就是十几分钟）。
        // 故：**只要有旧分片要续，就老老实实探测一次**，把强校验器拿回来。
        val canSkipProbe = context.config.skipProbeWhenSizeKnown &&
            knownSize > 0 && !hasResumableParts
        val probe = if (canSkipProbe) {
            // 已知大小：乐观假设支持 Range 直接开工。若实际不支持，
            // 首个分片会拿到 200 整文件并走已有的 RANGE_IGNORED 回退链路，正确性不受影响。
            SegmentDownloader.ProbeResult(knownSize, true, request.url)
        } else {
            downloader.probeWithRetry(
                request.url,
                request.headers,
                timeoutMs = context.config.probeTimeoutMs,
                retries = context.config.probeRetries,
            )
        }
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

        // ---------- 续传校验（P0-1 修复）----------
        // 思路参考 aria2 的 .aria2 控制文件与 IDM 的续传校验：把「大小+ETag+Last-Modified」
        // 写入分片目录旁的 .validator。下次续传前对比：不一致说明服务器侧文件已变更，
        // 旧分片不能再用（否则合并出一个新旧混杂的损坏文件，而且大小校验可能恰好通过）。
        //
        // 旧实现有三个问题：
        //  1) 删除循环 listFiles() 会把 .validator 自身一起删掉（侥幸随后重写，但语义混乱）；
        //  2) now 为空时**什么都不做** → 上一轮的旧 .validator 残留在目录里，
        //     下次续传拿它当"当前版本"比对，判定失真；
        //  3) 最致命：服务器只回 Content-Length（无 ETag / Last-Modified）时
        //     validator 退化为 `len=N`。此时服务器换了一个**同样大小**的新文件，
        //     令牌不变 → 旧分片被判为"当前版本"复用 → 合并出损坏文件，
        //     且最终长度校验恰好通过（大小一致）→ **静默损坏**。
        //
        // 现在的策略：
        //  - validator 带 `weak` 标记（见 ProbeResult.isWeak），弱校验器可被识别；
        //  - 弱校验器不足以支撑安全续传：目录里已有旧分片时丢弃重下
        //    （由 config.trustWeakValidator 显式放开；默认 false = 正确性优先）；
        //  - 强校验器变化 → 丢弃旧分片（原行为）；
        //  - validator 必须在**任何分片写入之前**落盘；now 为空时清掉旧文件而非留着。
        run {
            val marker = File(chunkDir, VALIDATOR_FILE)
            val now = probe.validator
            val prev = runCatching { if (marker.isFile) marker.readText().trim() else "" }.getOrDefault("")

            val changed = prev.isNotEmpty() && prev != now
            val weakButResuming = probe.isWeak && !context.config.trustWeakValidator
            val hasOldParts = hasResumableParts

            if (changed || (weakButResuming && hasOldParts)) {
                // 丢弃过期/无法安全校验的分片，从头下（不报错，对用户透明）。
                // 排除 .validator 自身，避免"删了又写"的脆弱时序。
                chunkDir.listFiles()?.forEach { f ->
                    if (f.name != VALIDATOR_FILE) runCatching { f.delete() }
                }
                chunkDir.mkdirs()
            }

            // 时序：validator 必须先于任何分片写入落盘。now 为空时删除旧文件，不留残留。
            runCatching {
                if (now.isEmpty()) marker.delete() else marker.writeText(now)
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
            // 关键：预热**不得阻塞下载开始**。旧实现会等到预热全部完成（最坏 8s）才开工，
            // 叠加在探测之后就是用户感知到的“解析很慢”。现在后台异步跑：
            // 分片立即开始，预热建好的连接会自然进连接池被后续分片复用。
            kotlinx.coroutines.CoroutineScope(currentCtx).launch {
                runCatching {
                    downloader.warmUp(
                        effectiveUrl, request.headers, warmCount,
                        timeoutMs = context.config.warmUpTimeoutMs,
                        taskId = context.taskId,
                    )
                }
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
                // 【P0-3 修复】deleteRecursively 会连 .validator 一起删掉，
                // 导致整文件回退后目录里没有任何「这一版文件是谁」的标记 ——
                // 下次进入本函数时 prev 为空，校验全部退化为「无信息」，续传逻辑被永久绕过。
                // 故先快照，重建目录后补写回去。
                val validatorSnapshot = probe.validator
                chunkDir.deleteRecursively(); chunkDir.mkdirs()
                if (validatorSnapshot.isNotEmpty()) {
                    runCatching { File(chunkDir, VALIDATOR_FILE).writeText(validatorSnapshot) }
                }
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

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
    /**
     * 配置观察者：每次 [download] 开始时把 `BackendContext.config` 交给它。
     *
     * 【为什么需要这个钩子】本类作为**插件后端**注册时（`TurboBackends.builtinHttp`），
     * 它持有的 OkHttpClient 是在插件安装那一刻按当时的 config 构建的，此后
     * `TurboClient.updateConfig()` **只重建 core 自己那两个 client**——而走插件路由时
     * core 那个 downloader 根本不被使用。结果：改了代理 / DoH / 忽略SSL / 超时
     * **不重启进程就不生效**（设置页却写着"动态生效"）。
     *
     * 这里把"最新 config"喂给客户端持有器，由它按传输层签名决定是否重建。
     * 默认 null = 不关心（core 独立使用时的行为不变）。
     */
    private val onConfigSeen: ((TurboConfig) -> Unit)? = null,
) : DownloadBackend {

    override val name: String = "builtin-http"

    override fun supports(request: DownloadRequest): Boolean {
        val u = request.url.lowercase()
        return u.startsWith("http://") || u.startsWith("https://")
    }

    override suspend fun download(context: BackendContext): BackendResult {
        // 先让客户端持有器看到最新配置：传输层设置（代理/DNS/TLS/超时）变了就在此重建。
        onConfigSeen?.invoke(context.config)
        val request = context.request
        val chunkDir = context.workDir
        val speedLimiter = SpeedLimiter { context.config.globalSpeedLimitBytesPerSec }

        // ---------- 探测：必须快，且不能阻塞下载开始 ----------
        // 设计取舍：探测只为拿到大小/Range/重定向地址，它**不传输数据**。
        // 旧实现 20s × 3 次 + 退避 ≈ 最坏 61s 全程阻塞，用户看到的就是“解析卡一分钟”。
        // 现在：调用方已知大小时直接跳过探测；否则单次 6s、最多 1 次重试。
        val knownSize = request.knownSize.takeIf { it > 0 } ?: -1L
        // 是否已有**可续传**的进度。
        //
        // 旧结构看的是"目录里有没有非空的 seg_*.part"；现在字节直接写在目标文件里，
        // 进度记在侧车（progress.tdlp）上。这一处只需要回答"要不要为续传而探测"，
        // 所以看侧车在不在就够 —— 真正的有效性由 SegmentStore.loadOrCreate 判定
        // （它还会核对总大小与目标文件长度）。
        val hasResumableParts = File(chunkDir, PROGRESS_FILE).isFile
        // 【续传场景不得跳过探测】跳过探测就拿不到 ETag / Last-Modified，
        // `ProbeResult` 的 validator 会退化成 `len=N|weak`（[SegmentDownloader.ProbeResult.isWeak]），
        // 而下面的续传校验规定"弱校验器不足以支撑安全续传"→ **无条件下丢弃全部旧分片**。
        // 结果：网盘类链接（调用方已知大小、跳过探测）的断点续传**静默失效**，每次都从 0 重下。
        // 代价对比很直白：多一次探测请求 vs 重下整个文件（1.3GB @1.5MB/s 就是十几分钟）。
        // 故：**只要有旧分片要续，就老老实实探测一次**，把强校验器拿回来。
        val canSkipProbe = context.config.skipProbeWhenSizeKnown &&
            knownSize > 0 && !hasResumableParts
        // 【阶段计时】探测耗时：宿主可用它区分「解析慢」是探测慢（此值大）还是首连接慢（此值≈0）。
        val probeStartAt = System.currentTimeMillis()
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

        // ---------- 分片存储：按偏移直写目标文件 ----------
        // 侧车跟着分片目录走（该目录由 stableKey 决定，因此多次 submit 复用同一份进度）。
        // 放在续传校验之前：校验本身要读目标文件里的已写字节做内容指纹。
        val store = SegmentStore(
            dest = request.destination,
            stateFile = File(chunkDir, PROGRESS_FILE),
            total = total,
        )
        store.open()
        val probeMs = System.currentTimeMillis() - probeStartAt
        // 续传判定结果（由 prepareResume 填写，随后随 Metadata 一起上报给宿主）。
        var resumeNote: String
        // 关键：后续分片/整文件下载均使用重定向后的最终 URL（如网盘原始链 302→CDN 临时直链）。
        // 否则每个分片连接都重走 302，可能命中不同节点或被拒，表现为“显示下载中但字节/线程不动”。
        val effectiveUrl = probe.resolvedUrl.ifBlank { request.url }
        val supportsRange = probe.supportsRange
        // ---------- UA 协商结果贯穿整个任务 ----------
        // 探测阶段若因默认 UA 被拦（403 / HTML 挑战页）换到了别的 UA，这里把它接过去，
        // **后续每一个请求都用它**：分片、整文件回退、连接预热、续传指纹校验。
        // 只在探测里换、分片还用默认 UA 的话，等于让几十条连接各自再撞一次墙 ——
        // 协商就白做了（上一轮实现漏在这里，真机上等于没生效）。
        //
        // 用 request.headers 的副本而不是原地改：`DownloadRequest` 是调用方的数据，
        // 改它会把一次协商泄漏给同一进程里的其它任务。
        val taskHeaders: Map<String, String> =
            probe.negotiatedUserAgent?.let { ua ->
                request.headers.toMutableMap().apply { put("User-Agent", ua) }
            } ?: request.headers

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
        //  - 弱校验器**不再一律丢弃**（旧行为会把"暂停→续传"整个废掉，用户实报：
        //    夸克链接暂停后从 0 重下）：改为**内容指纹校验** —— 用已存分片的前若干字节
        //    与服务器同区间逐字节比对；一致则保留旧分片（续传可用），不一致/取不到才丢弃。
        //    这样既保住"防同大小不同内容静默损坏"的安全底线，又让续传真正可用。
        //  - 强校验器变化 → 丢弃旧分片（原行为）；
        //  - validator 必须在**任何分片写入之前**落盘；now 为空时清掉旧文件而非留着。
        resumeNote = prepareResume(
            chunkDir = chunkDir,
            probe = probe,
            store = store,
            total = total,
            effectiveUrl = effectiveUrl,
            taskHeaders = taskHeaders,
            destination = request.destination,
            hasOldParts = hasResumableParts,
            trustWeakValidator = context.config.trustWeakValidator,
        )

        // 静默上报元数据（尽力而为，不影响下载）：宿主可用服务器建议名重命名、记录 MIME 等。
        // - probeMs   ：探测耗时（大=探测慢；≈0 跳过探测而首字节慢=慢在首连接）
        // - resumeNote：续传判定结果（parts/weak/lenChanged/validDiff/print/discard），用于定位"断点续传为什么不生效"
        runCatching {
            context.reportMetadata(
                suggestedFileName = probe.suggestedFileName,
                contentType = probe.contentType,
                etag = probe.etag,
                lastModified = probe.lastModified,
                probeMs = probeMs,
                resumeNote = resumeNote,
            )
        }

        return try {
            downloadWithStore(context, store, probe, total, supportsRange, effectiveUrl, taskHeaders, chunkDir)
        } finally {
            // 通道必须关：Windows 下不关会让目标文件一直被占住，用户挪不走也删不掉。
            store.close()
        }
    }

    /**
     * 续传校验：判定旧进度还能不能接着用，并落盘这次的有效令牌。
     *
     * 从 [download] 拆出来，是因为它是这个后端里分支最多的一段；它只需要几个输入、
     * 产出一句可读的判定说明，拆开后 [download] 的主线只剩
     * 「探测 → 建存储 → 校验续传 → 交给主体」四步。
     *
     * @return 判定说明（`parts=… weak=… lenChanged=… validDiff=… print=… discard=…`），
     *         由调用方随 Metadata 上报，用来回答"断点续传为什么没生效"。
     */
    private suspend fun prepareResume(
        chunkDir: File,
        probe: SegmentDownloader.ProbeResult,
        store: SegmentStore,
        total: Long,
        effectiveUrl: String,
        taskHeaders: Map<String, String>,
        destination: File,
        hasOldParts: Boolean,
        trustWeakValidator: Boolean,
    ): String {
        val marker = File(chunkDir, VALIDATOR_FILE)
        val now = probe.validator
        val prev = runCatching { if (marker.isFile) marker.readText().trim() else "" }.getOrDefault("")

        // 【⛔ 真实事故，2026-09-28】这里曾把「探测失败」误判成「内容已变」并删光分片：
        //
        //   夸克直链失效（超 3 小时）→ 探测拿不到 totalSize/etag/lastModified
        //   → `now = "weak"`（残缺令牌）
        //   → 旧值 `prev = "len=1447815647|weak"` 与之不等
        //   → `changed = true` → **删掉用户已下的 32 个分片 / 14.75MB**
        //
        // 判据错在把两个不同的量当成一回事：
        //   · "服务器确实换了文件"（确证）—— 该删；
        //   · "探测没拿到信息"（**无法取证**）—— 绝不能删，否则就是拿失败当证据。
        //
        // 因此 `changed` 现在**要求 `now` 本身是完整可用的令牌**：
        // 至少要含 `len=` 或 `etag=`/`lm=` 之一；只有 `weak` 或空串一律不算"变了"。
        val nowUsable = now.contains("len=") || now.contains("etag=") || now.contains("lm=")
        val weakButResuming = probe.isWeak && !trustWeakValidator
        // hasOldParts 由调用方传入

        // 探测失败（令牌残缺）时：保留旧分片，但**不假装能续传** ——
        // 让本次下载以可读原因失败（大概率是链接过期），用户重新解析链接即可继续。
        // 数据留着，比"删干净从头下"对用户友好得多。
        val probeDegraded = prev.isNotEmpty() && !nowUsable

        // 内容指纹：仅在「弱校验器 + 有旧进度 + 令牌本身没变」时才需要（其余分支已定性）。
        // 【三值判定，关键】把"取不到指纹"与"内容确实不同"**分开**：
        //   MATCH        → 保留旧分片（续传可用）
        //   MISMATCH     → 确证内容不同 → 丢弃（安全底线）
        //   UNVERIFIABLE → 取不到（状态码异常/请求失败/无可读分片）→ **保留**
        // 旧写法把后两者混为一谈，于是"一次指纹请求没成功"就丢掉用户已下载的 GB 级数据 ——
        // 用户实报的"暂停还是从头下"极可能就是这一支。
        // 【⛔ 2026-10-09 用户实报「点暂停有时进度归零重下、有时又正常」——就是这一支】
        //
        // 旧判据是 `changed = prev != now && nowUsable`：只要令牌**不完全相同**就认定"文件变了"→ 清空分片
        // 从头下。但夸克这类直链挂在多节点 CDN 后面，**每次续传可能落到不同节点、拿到不同的
        // ETag/Last-Modified**，长度却完全一致。于是同一份文件被反复判定为"变了"：
        // 续传时命中就正常，没命中就从头下 —— 表现就是"时好时坏、进度条回到最初"。
        //
        // 正确的三层判据（按证据强度）：
        //   · **长度变了** → 确证换了文件 → 丢弃（唯一可以直接丢弃的证据）；
        //   · 长度一致但令牌不同 → 不足以定性 → 交**内容指纹**逐字节裁决（真正的安全底线）；
        //   · 弱校验器 → 同样交内容指纹；
        //   · 探测拿不到有效令牌（probeDegraded）→ 无法取证 → **保留**。
        val lenOf = fun(s: String): String? =
            Regex("""(?:^|\|)len=(\d+)""").find(s)?.groupValues?.get(1)
        val prevLen = lenOf(prev)
        val nowLen = lenOf(now)
        val lenChanged = prevLen != null && nowLen != null && prevLen != nowLen
        val validatorDiffers = prev.isNotEmpty() && prev != now && nowUsable && !lenChanged

        val needFingerprint = hasOldParts && !probeDegraded && !lenChanged &&
            (weakButResuming || validatorDiffers)
        val decision = if (needFingerprint) {
            verifyResumeFingerprint(store, total, effectiveUrl, taskHeaders)
        } else {
            ResumeDecision.NOT_ATTEMPTED
        }

        val discard = lenChanged || decision == ResumeDecision.MISMATCH
        if (discard) {
            // 丢弃过期/无法安全校验的进度，从头写（不报错，对用户透明）。
            // 现在只有两样东西要清：侧车进度与目标文件本身。
            runCatching { File(chunkDir, PROGRESS_FILE).delete() }
            runCatching { destination.delete() }
        }
        val note = "parts=$hasOldParts weak=$weakButResuming lenChanged=$lenChanged " +
            "validDiff=$validatorDiffers " +
            "print=$decision discard=$discard" +
            if (probeDegraded) " probeDegraded=true(探测失效,已保留旧分片)" else ""

        // 时序：validator 必须先于任何分片写入落盘。
        // 【不能覆盖成残缺值】probeDegraded 时 `now="weak"` 之类是**没有信息**，
        // 用它盖掉 `len=1447815647|weak` 会把"文件多大"这个已知事实也丢掉 ——
        // 下次探测再失败就又变成 changed。故此时**保留旧令牌不动**。
        runCatching {
            when {
                probeDegraded -> Unit          // 保留旧令牌（现状更完整）
                now.isEmpty() -> marker.delete() // 探测明确「无校验器」：清掉残留，避免下次误比
                else -> marker.writeText(now)
            }
        }
        return note
    }

    /**
     * 主体：分片直写 / 整文件回退 / 结果解释。
     *
     * 从 [download] 拆出来，是为了让那边的 `try/finally` 只负责 store 的生命周期，
     * 不必把整段下载逻辑再缩进一层。
     */
    private suspend fun downloadWithStore(
        context: BackendContext,
        store: SegmentStore,
        probe: SegmentDownloader.ProbeResult,
        total: Long,
        supportsRange: Boolean,
        effectiveUrl: String,
        taskHeaders: Map<String, String>,
        chunkDir: File,
    ): BackendResult {
        val request = context.request
        val speedLimiter = SpeedLimiter { context.config.globalSpeedLimitBytesPerSec }

        // Server does not support Range, or size unknown -> whole-file fallback (cannot segment).
        if (!supportsRange || total <= 0) {
            // 【本版变化】整文件单流也**直接写目标文件**，不再写 whole.part 再合并：
            // 少一次全量拷贝，也少一个"下载完成但还在拷文件"的阶段。
            val outPart = request.destination
            outPart.parentFile?.mkdirs()
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
                downloader.downloadWhole(context.taskId, effectiveUrl, outPart, taskHeaders, total) { d ->
                    context.throttle(d)
                    acc += d
                    progressed.set(acc)
                    if (!context.isActive()) return@downloadWhole
                    context.reportProgress(acc, 1)
                }
            } finally {
                watchdog?.cancel()
            }
            if (!ok) {
                // 优先报**具体原因**（连接被重置 / 声明长度不符 / 读超时），
                // 只有在拿不到原因时才退回那句笼统的兜底文案。
                val detail = downloader.takeWholeError(context.taskId)
                throw IllegalStateException(
                    if (detail != null) "整文件下载失败：$detail"
                    else "整文件下载失败（服务器不支持 Range、无响应或响应异常）"
                )
            }
            return BackendResult(listOf(outPart), if (total > 0) total else outPart.length())
        }

        // Reserve the logical length once before parallel positional writes.
        // Repeated EOF extension is serialized by the filesystem and becomes a
        // measurable bottleneck when 128/256 connections all write new ranges.
        store.ensureLength()

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
            // 关键：预热**不得阻塞下载开始**。分片立即开始，预热建好的连接会自然进连接池被复用。
            kotlinx.coroutines.CoroutineScope(currentCtx).launch {
                runCatching {
                    downloader.warmUp(
                        effectiveUrl, taskHeaders, warmCount,
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
            store = store,
            headers = taskHeaders,
            connections = connections,
            onBytes = { _, abs -> context.reportProgress(abs, liveConnsRef.get()) },
            onConnections = { live -> liveConnsRef.set(live) },
            isActive = { context.isActive() },
            ifRange = probe.strongIfRange,
        )

        return when (outcome) {
            is SegmentScheduler.Outcome.Completed -> {
                // 数据从第一片起就写在目标文件里 —— 没有合并阶段，直接发布。
                BackendResult(listOf(request.destination), total)
            }

            is SegmentScheduler.Outcome.NeedWholeFallback -> {
                // Range 反复被忽略：只能整文件单流。
                //
                // 直写模式下"保留已完成分片"不再成立 —— 整文件下载会从 0 覆盖整个文件，
                // 与已写入的片段混合只会得到无意义的结果。所以这里清空进度重来。
                // （旧实现能保留，是因为它写的是互相独立的临时文件；那是另一套结构的红利，
                //   而它付出的代价是高并发下的 57 倍 I/O，见 SegmentStore 的实测表。）
                runCatching { File(chunkDir, PROGRESS_FILE).delete() }
                store.reset()

                val outPart = request.destination
                var acc = 0L
                val ok = downloader.downloadWhole(context.taskId, effectiveUrl, outPart, taskHeaders, total) { d ->
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
                // 若是"分片读到的字节不够"这类具体原因，把它接在调度器给的原因后面 ——
                // 「重试 6 次仍失败」本身不告诉用户为什么失败。
                // 调度器如今会把**出事那一片自己的**原因带在 reason 里（见 Segment.lastFailure），
                // 只有它还缺细节时才补任务级原因 —— 否则会出现两层括号，
                // 且可能把别的分片的原因（单槽 race）贴到真正失败的分片后面。
                val detail = downloader.takeWholeError(context.taskId)
                val suffix = if (detail != null && !outcome.reason.contains('（')) "（$detail）" else ""
                throw IllegalStateException(outcome.reason + suffix)
            }
        }
    }

    /**
     * 弱校验器续传的**内容指纹校验**（三值返回）。
     *
     * 取「起始位置最小且已写入 > 0」的分片，从**目标文件**的对应偏移读前
     * [FINGERPRINT_BYTES] 字节，向服务器请求同一区间并逐字节比对：
     *  - [ResumeDecision.MATCH]        → 旧数据确实属于当前版本 → 保留（续传可用）
     *  - [ResumeDecision.MISMATCH]     → 拿到了字节但内容不同 → 丢弃（安全底线）
     *  - [ResumeDecision.UNVERIFIABLE] → 没拿到字节（状态码异常/请求失败/无可读分片）→ **保留**
     *
     * 【为什么必须区分后两者】旧实现把"没拿到"当成"不一致"，于是**一次指纹请求没成功，
     * 就把用户已下载的 GB 级数据全删了** —— 这正是"断点续传还是不生效"最可能的来源。
     * 拿不到指纹只是"无法取证"，并不是"内容变了"的证据。
     *
     * 成本：一次 64KB 的 Range 请求（相比整包重下可忽略）。
     */
    private suspend fun verifyResumeFingerprint(
        store: SegmentStore,
        total: Long,
        url: String,
        headers: Map<String, String>,
    ): ResumeDecision {
        val pieces = store.peek(total) ?: return ResumeDecision.UNVERIFIABLE
        // 依次尝试最多 2 个候选分片（一个 CDN 节点偶发异常不该否掉整个续传）。
        val candidates = pieces
            .filter { it.written > 0 }
            .sortedBy { it.start }
            .take(2)
        if (candidates.isEmpty()) return ResumeDecision.UNVERIFIABLE

        var gotBytes = false
        for (piece in candidates) {
            val len = minOf(FINGERPRINT_BYTES.toLong(), piece.written).toInt()
            if (len <= 0) continue
            val local = runCatching { store.readAt(piece.start, len) }.getOrNull() ?: continue
            if (local.size < len) continue

            val remote = downloader.fetchPrefix(url, headers, piece.start, len, timeoutMs = 10_000)
                ?: continue
            if (remote.size < len) continue
            gotBytes = true
            if (remote.copyOf(len).contentEquals(local)) return ResumeDecision.MATCH
        }
        // 拿到过字节但都不一致 → 确证不匹配；一次都没拿到 → 无法取证。
        return if (gotBytes) ResumeDecision.MISMATCH else ResumeDecision.UNVERIFIABLE
    }

    /** 续传指纹校验的判定结果（见 [verifyResumeFingerprint]）。 */
    private enum class ResumeDecision { MATCH, MISMATCH, UNVERIFIABLE, NOT_ATTEMPTED }

    private companion object {
        /** 续传校验标记文件名。 */
        const val VALIDATOR_FILE = ".validator"

        /**
         * 分片进度侧车文件名（放在分片目录里）。
         *
         * 内容体量很小（几百片约十几 KB），记录每个分片的 start/end/written。
         * 它的存在即代表"这个任务有可续传进度"，见 `hasResumableParts`。
         */
        const val PROGRESS_FILE = "progress.tdlp"

        /** 续传内容指纹长度：64KB 足以区分不同文件，又不至于成为负担。 */
        const val FINGERPRINT_BYTES = 64 * 1024
    }
}

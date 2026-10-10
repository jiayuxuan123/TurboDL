package dev.turbodl.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * TurboDL 下载引擎入口（SDK 门面）。
 *
 * 用法：
 * ```
 * val client = TurboClient(TurboConfig(maxConnectionsPerTask = 16))
 * val id = client.submit(DownloadRequest(url, File("out.bin")))
 * client.events.collect { ... }   // 观测事件
 * client.await(id)                 // 挂起直到完成
 * client.shutdown()
 * ```
 *
 * 线程/协程安全；所有下载在内部 SupervisorJob 作用域中执行。
 * 本类**不含任何插件/扩展点代码**；[events] 仅作为观测流。
 */
class TurboClient(config: TurboConfig = TurboConfig()) {

    @Volatile
    var config: TurboConfig = config
        private set

    /** 运行时热更新配置（限速、并发等即时生效；已在跑的任务连接数不回缩）。 */
    fun updateConfig(newConfig: TurboConfig) {
        this.config = newConfig
        segmentClient = HttpClientFactory.build(newConfig, HttpClientFactory.ProtocolPreference.H1_ONLY)
        // 只重建已创建过的 h2 客户端：未创建说明从未走过整文件回退，
        // 保持"未创建"状态即可，别为了更新配置把它提前唤醒（白付一次 build）。
        if (streamClientRef != null) {
            streamClientRef = HttpClientFactory.build(newConfig, HttpClientFactory.ProtocolPreference.ALLOW_H2)
        }
    }

    // 双客户端：分片并发用 H1_ONLY（避免 h2 多路复用抹平多连接）；
    // 探测/整文件单流回退用 ALLOW_H2（单流无多连接损失，且兼容仅支持 h2 的服务器）。
    // FORCE_HTTP1/FORCE_HTTP2 策略下两个客户端实际协议相同（均由 effectiveHttpVersionPolicy 决定）。
    @Volatile
    private var segmentClient = HttpClientFactory.build(config, HttpClientFactory.ProtocolPreference.H1_ONLY)

    /**
     * h2 客户端**惰性创建**。
     *
     * 【为什么不等价于急切构建】它只在整文件回退（[SegmentDownloader.downloadWhole]）时被用到，
     * 而分片下载这条主路径 100% 走 [segmentClient]。急切构建等于给每个 TurboClient 白付一次
     * OkHttpClient 构建成本（Dispatcher + ConnectionPool + 拦截器链 + 相关类首次加载），
     * 实测本地回环下"启动→首字节"里就有这部分开销；而绝大多数任务永远用不到它。
     *
     * 用 @Volatile 引用而非 `by lazy`：既保持惰性，又允许 [updateConfig] 重建与
     * [shutdown] 判断"是否创建过"（`by lazy` 无法安全地探测初始化状态）。
     */
    @Volatile
    private var streamClientRef: okhttp3.OkHttpClient? = null

    /** 取 h2 客户端，未创建则创建（双重检查：正常路径无锁，仅首次建时有竞争）。 */
    private fun streamClientOrCreate(): okhttp3.OkHttpClient {
        streamClientRef?.let { return it }
        synchronized(this) {
            streamClientRef?.let { return it }
            return HttpClientFactory.build(config, HttpClientFactory.ProtocolPreference.ALLOW_H2)
                .also { streamClientRef = it }
        }
    }

    // 分片下载器用 H1 客户端；整文件/探测下载器用允许 h2 的客户端（后者惰性创建）。
    // 缓冲区大小从配置读取（默认 1MB）：过小会在高吞吐时产生大量 read/回调开销。
    private val downloader = SegmentDownloader(
        { segmentClient },
        { streamClientOrCreate() },
        { config.ioBufferSize },
        { config.bufferedSegmentWrite },
    )
    private val speedLimiter = SpeedLimiter { config.globalSpeedLimitBytesPerSec }

    /** Built-in HTTP backend; always available so core works standalone. */
    private val builtinBackend: DownloadBackend = BuiltinHttpBackend(downloader)

    /**
     * Optional backend resolver. Null unless the optional plugin runtime installs one
     * (its BackendRegistry). core never depends on the runtime; when null, the built-in
     * backend is always used.
     *
     * NOTE: reserved — the runtime sets this so plugin backends can override the built-in
     * one or add protocols. This is the only hook; core has no knowledge of the registry type.
     */
    @Volatile
    var backendResolver: BackendResolver? = null

    /** Resolve the backend for a request: plugin resolver first, then built-in fallback. */
    private fun backendFor(request: DownloadRequest): DownloadBackend =
        backendResolver?.resolve(request) ?: builtinBackend

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val idGen = AtomicLong(0)

    private val _events = MutableSharedFlow<TurboEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<TurboEvent> = _events.asSharedFlow()

    private val _progress = MutableStateFlow<Map<Long, TaskProgress>>(emptyMap())
    val progress: StateFlow<Map<Long, TaskProgress>> = _progress.asStateFlow()

    private val jobs = ConcurrentHashMap<Long, Job>()
    private val completions = ConcurrentHashMap<Long, CompletableDeferred<Result<File>>>()
    private val requests = ConcurrentHashMap<Long, DownloadRequest>()

    /** 并发任务槽。 */
    private val activeCount = AtomicInteger(0)

    /** 提交下载任务，立即入队并异步开始。返回任务 id。 */
    fun submit(request: DownloadRequest): Long {
        val id = idGen.incrementAndGet()
        requests[id] = request
        completions[id] = CompletableDeferred()
        emit(TurboEvent.Created(id, request))
        setState(id, request, TaskState.QUEUED)

        val job = scope.launch {
            try {
                awaitSlot(id)
                if (coroutineContext[Job]?.isActive != true) return@launch
                activeCount.incrementAndGet()
                try {
                    runTask(id, request)
                } finally {
                    activeCount.decrementAndGet()
                    // 释放任务级的下载器状态（在飞 Call 登记 + 已学到的重定向终址）。
                    // 不释放的话每完成一个任务就留两条永不回收的条目 → 长跑进程无界增长。
                    downloader.releaseTask(id)
                }
            } catch (e: CancellationException) {
                completions[id]?.complete(Result.failure(e))
                setState(id, request, TaskState.CANCELED)
            } catch (e: Exception) {
                emit(TurboEvent.Failed(id, e.message ?: e.javaClass.simpleName))
                updateProgress(id) { it.copy(state = TaskState.FAILED, error = e.message) }
                completions[id]?.complete(Result.failure(e))
            }
        }
        jobs[id] = job
        return id
    }

    /** 挂起直到任务结束，返回结果（成功=文件，失败=异常）。 */
    suspend fun await(id: Long): Result<File> =
        completions[id]?.await() ?: Result.failure(IllegalArgumentException("未知任务 $id"))

    /** 暂停任务（保留断点）。 */
    fun pause(id: Long) {
        downloader.cancelCalls(id)
        val job = jobs.remove(id)
        scope.launch {
            job?.let { runCatching { it.cancelAndJoin() } }
            requests[id]?.let { setState(id, it, TaskState.PAUSED) }
        }
    }

    /**
     * 恢复已暂停的任务（断点续传）。
     *
     * 修复竞态：旧实现 pause 异步 cancelAndJoin，resume 可能在旧 job 真正退出前就启动新 job，
     * 两个协程短暂并发写同一 chunkDir（分片文件互相覆盖）。现在启动前先 join 旧 job。
     */
    fun resume(id: Long): Boolean {
        val req = requests[id] ?: return false
        completions.putIfAbsent(id, CompletableDeferred())
        val job = scope.launch {
            // 先确保旧 job 已彻底退出，避免两个下载协程同时写分片目录。
            jobs.remove(id)?.let { runCatching { it.cancelAndJoin() } }
            try {
                awaitSlot(id)
                if (coroutineContext[Job]?.isActive != true) return@launch
                activeCount.incrementAndGet()
                try { runTask(id, req) } finally { activeCount.decrementAndGet(); downloader.releaseTask(id) }
            } catch (e: CancellationException) {
                setState(id, req, TaskState.PAUSED)
            } catch (e: Exception) {
                emit(TurboEvent.Failed(id, e.message ?: e.javaClass.simpleName))
                completions[id]?.complete(Result.failure(e))
            }
        }
        // 若已有活动 job，不重复启动（新 job 会在上方 join 阶段自行退出）。
        val prev = jobs.putIfAbsent(id, job)
        if (prev != null) { job.cancel(); return false }
        return true
    }

    /** 取消任务并清理临时分片。deleteOutput=true 同时删除已保存的目标文件。 */
    fun cancel(id: Long, deleteOutput: Boolean = false) {
        downloader.cancelCalls(id)
        val job = jobs.remove(id)
        scope.launch {
            job?.let { runCatching { it.cancelAndJoin() } }
            chunkDirOf(id).deleteRecursively()
            if (deleteOutput) requests[id]?.destination?.delete()
            requests[id]?.let { setState(id, it, TaskState.CANCELED) }
            completions[id]?.complete(Result.failure(CancellationException("canceled")))
        }
    }

    /**
     * 关闭引擎，取消所有任务并释放资源。
     *
     * ## 为什么每个释放动作各自 `runCatching`
     *
     * 这里原先是一条裸顺序链，**第一个抛出的异常会截断后面全部清理**：
     * `connectionPool.evictAll()` 不是"清空列表"这么无害 —— 它真的 close 每条空闲连接的 socket，
     * 而关闭 TLS 连接要写 `close_notify`（一次网络写）。OkHttp 的 `Util.closeQuietly` 只吞
     * `IOException`，**`NetworkOnMainThreadException` 会原样穿透**（Android 宿主在
     * `ViewModel.onCleared()` 这类主线程回调里调本方法时就会遇到）。
     * 结果：不仅崩到调用方，`segmentClient` 的 Dispatcher 线程池也**永远不会被关** ——
     * 一次崩溃换来一份永久泄漏。
     *
     * 现在：先关两个线程池（纯内存操作，不会失败），再各自兜底地清连接池，
     * 任何一个失败都不影响其余步骤。Android 宿主仍应在后台线程调用本方法（关 socket 是网络 I/O），
     * 但即便误在主线程调用，也不会再截断清理链。
     */
    fun shutdown() {
        scope.coroutineContext[Job]?.cancel()
        // 惰性客户端可能从未创建：没创建过就没什么可关的，别在这里把它唤醒。
        streamClientRef?.let { sc ->
            runCatching { sc.dispatcher.executorService.shutdown() }
            runCatching { sc.connectionPool.evictAll() }
        }
        runCatching { segmentClient.dispatcher.executorService.shutdown() }
        runCatching { segmentClient.connectionPool.evictAll() }
    }

    // ---------- 内部 ----------

    private suspend fun awaitSlot(id: Long) {
        while (coroutineContext[Job]?.isActive == true &&
            activeCount.get() >= config.maxConcurrentTasks
        ) {
            delay(200)
        }
    }

    private suspend fun runTask(id: Long, request: DownloadRequest) {
        setState(id, request, TaskState.PROBING)

        val chunkDir = chunkDirOf(id).apply { mkdirs() }
        val downloadedRef = AtomicLong(0)
        val speedRec = SpeedRecorder()
        val totalRef = AtomicLong(-1)
        /** 上次进度上报时间（节流用）。 */
        val lastProgressAt = AtomicLong(0)
        val liveConnsRef = AtomicInteger(request.connectionsOverride ?: config.maxConnectionsPerTask)
        val taskJob = coroutineContext[Job]

        // Backend context: bridges a DownloadBackend to the engine's progress/state/rate-limit
        // without exposing any client internals. The state machine, event emission, merging and
        // integrity checks all stay here in TurboClient.
        val context = object : BackendContext {
            override val taskId: Long = id
            override val request: DownloadRequest = request
            override val workDir: File = chunkDir
            override val config: TurboConfig get() = this@TurboClient.config
            override fun isActive(): Boolean = taskJob?.isActive == true
            override suspend fun throttle(bytes: Long) = speedLimiter.awaitAllow(bytes)
            override fun reportTotalSize(total: Long) { totalRef.set(total) }
            override fun reportMetadata(
                suggestedFileName: String?,
                contentType: String?,
                etag: String?,
                lastModified: String?,
                probeMs: Long,
                resumeNote: String,
            ) {
                // 静默发一个元数据事件：宿主可选择消费（重命名/补扩展名），不消费也不影响下载。
                emit(
                    TurboEvent.Metadata(
                        taskId = id,
                        suggestedFileName = suggestedFileName,
                        contentType = contentType,
                        etag = etag,
                        lastModified = lastModified,
                        totalBytes = totalRef.get(),
                        supportsRange = totalRef.get() > 0,
                        resolvedUrl = request.url,
                        probeMs = probeMs,
                        resumeNote = resumeNote,
                    )
                )
            }
            override suspend fun reportProgress(absoluteBytes: Long, activeConnections: Int) {
                downloadedRef.set(absoluteBytes)
                liveConnsRef.set(activeConnections)
                // 进度上报节流：每次上报都要重建进度 Map + 启协程发事件，宿主侧还可能写库/刷 UI。
                // 高速多连接下不节流会每秒上千次，把 CPU 耗在上报而非搜数据——直接压低吞吐。
                val interval = this@TurboClient.config.progressIntervalMs
                if (interval > 0) {
                    val now = System.currentTimeMillis()
                    val last = lastProgressAt.get()
                    if (now - last < interval) return
                    if (!lastProgressAt.compareAndSet(last, now)) return
                }
                val total = totalRef.get()
                val speed = speedRec.sample(absoluteBytes)
                val eta = if (speed != null && speed > 0 && total > 0) (total - absoluteBytes) * 1000 / speed else -1L
                updateProgress(id) {
                    it.copy(
                        state = TaskState.DOWNLOADING,
                        downloadedBytes = absoluteBytes,
                        totalBytes = total,
                        speedBytesPerSec = speed ?: it.speedBytesPerSec,
                        activeConnections = activeConnections,
                        etaMillis = eta,
                    )
                }
            }
        }

        setState(id, request, TaskState.DOWNLOADING)

        // Delegate the protocol layer to the resolved backend (built-in HTTP by default;
        // a plugin backend when the optional runtime installs a resolver).
        val backend = backendFor(request)
        val result = backend.download(context)
        finish(id, request, result.orderedParts, result.totalBytes, result.partOffsets)
    }

    private suspend fun finish(
        id: Long,
        request: DownloadRequest,
        parts: List<File>,
        total: Long,
        offsets: List<Long>? = null,
    ) {
        updateProgress(id) { it.copy(state = TaskState.MERGING) }
        val dest = request.destination
        // 【预先合并命中】后端已把完整数据写进目标文件（overlap merge），
        // parts 就是目标文件本身 —— 此时**不能再合并一次**（那是把文件拷到自己身上）。
        // 只做大小校验即可。
        val alreadyMerged = parts.size == 1 && parts[0].canonicalFile == dest.canonicalFile
        if (!alreadyMerged) {
            for (p in parts) {
                if (!p.exists() || p.length() <= 0) throw IllegalStateException("分片缺失/为空：$p")
            }
            dest.parentFile?.mkdirs()
            // 合并时上报进度：GB 级文件拼接可能耗时数十秒，UI 不再卡在 MERGING 无进度。
            var lastReport = 0L
            val ok = PartMerger.merge(parts, dest, onProgress = { mergedBytes, totalBytes ->
                val now = System.currentTimeMillis()
                if (now - lastReport >= 200) {
                    lastReport = now
                    updateProgress(id) {
                        it.copy(state = TaskState.MERGING, downloadedBytes = mergedBytes, totalBytes = totalBytes)
                    }
                }
            }, offsets = offsets)
            if (!ok) throw IllegalStateException("合并分片失败")
        }
        if (total > 0 && dest.length() != total) {
            throw IllegalStateException("大小校验失败：期望 $total，实际 ${dest.length()}")
        }
        chunkDirOf(id).deleteRecursively()
        updateProgress(id) {
            it.copy(state = TaskState.COMPLETED, downloadedBytes = dest.length(), totalBytes = dest.length())
        }
        emit(TurboEvent.Completed(id, dest, dest.length()))
        completions[id]?.complete(Result.success(dest))
    }

    private fun chunkDirOf(id: Long): File {
        val base = config.workDir ?: File(System.getProperty("java.io.tmpdir"), "turbodl")
        val req = requests[id]
        // 优先用调用方提供的稳定键（如 Room 任务 id），使同一业务任务多次 submit/resume 复用同一分片目录；
        // 为空则回退到内部自增 id（行为与旧版一致）。
        val key = req?.stableKey?.takeIf { it.isNotBlank() }?.let { sanitizeKey(it) } ?: "task_$id"
        return File(base, key)
    }

    /** 将稳定键规整为安全目录名（避免路径分隔符/非法字符）。 */
    private fun sanitizeKey(raw: String): String =
        "key_" + raw.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")

    private fun emit(event: TurboEvent) {
        scope.launch { _events.emit(event) }
    }

    private fun setState(id: Long, request: DownloadRequest, state: TaskState) {
        updateProgress(id) { it.copy(state = state) }
        emit(TurboEvent.StateChanged(id, state))
    }

    private fun updateProgress(id: Long, transform: (TaskProgress) -> TaskProgress) {
        var next: TaskProgress? = null
        _progress.update { map ->
            val cur = map[id] ?: TaskProgress(
                taskId = id, state = TaskState.QUEUED,
                downloadedBytes = 0, totalBytes = -1,
                speedBytesPerSec = 0, activeConnections = 0, etaMillis = -1,
            )
            val n = transform(cur)
            next = n
            map + (id to n)
        }
        // 事件在 update 外发：StateFlow.update 的 lambda 在竞争时会被**重复执行**，
        // 旧实现在 lambda 内 emit 会在多连接高频上报时发出重复/乱序事件，并白白多启协程。
        next?.let { emit(TurboEvent.Progress(id, it)) }
    }

    /**
     * 速度采样 + EWMA 平滑：每 500ms 计算一次瞬时速率，并用指数加权移动平均平滑，
     * 避免多分片并发下瞬时速率剧烈抖动导致 ETA 在大范围内跳变。
     */
    private class SpeedRecorder {
        private var lastBytes = 0L
        private var lastTime = System.currentTimeMillis()
        private var ewma = 0.0
        private var seeded = false
        @Synchronized
        fun sample(total: Long): Long? {
            val now = System.currentTimeMillis()
            val dt = now - lastTime
            if (dt >= 500) {
                val inst = if (dt > 0) ((total - lastBytes) * 1000.0 / dt).coerceAtLeast(0.0) else 0.0
                lastBytes = total; lastTime = now
                // EWMA 平滑（α=0.3）：既保留足够响应速度，又抑制分片抖动。
                ewma = if (!seeded) { seeded = true; inst } else 0.3 * inst + 0.7 * ewma
                return ewma.toLong().coerceAtLeast(0)
            }
            return null
        }
    }
}

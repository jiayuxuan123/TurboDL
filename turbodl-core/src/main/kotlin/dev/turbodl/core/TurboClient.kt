package dev.turbodl.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
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
        if (closed.get()) return
        this.config = newConfig
        val oldSegment = segmentClient
        segmentClient = HttpClientFactory.build(newConfig, HttpClientFactory.ProtocolPreference.H1_ONLY)
        retireClient(oldSegment)
        // 只重建已创建过的 h2 客户端：未创建说明从未走过整文件回退，
        // 保持"未创建"状态即可，别为了更新配置把它提前唤醒（白付一次 build）。
        if (streamClientRef != null) {
            val oldStream = streamClientRef
            streamClientRef = HttpClientFactory.build(newConfig, HttpClientFactory.ProtocolPreference.ALLOW_H2)
            oldStream?.let { retireClient(it) }
        }
        // Raising maxConcurrentTasks should wake queued tasks immediately.
        scope.launch {
            slotLock.withLock { grantSlotsLocked() }
            connectionBudget.limitChanged()
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
    private val connectionBudget = ConnectionBudget { config.maxTotalConnections }

    /** Built-in HTTP backend; always available so core works standalone. */
    private val builtinBackend: DownloadBackend =
        BuiltinHttpBackend(downloader, connectionBudget = connectionBudget)

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

    private val closed = AtomicBoolean(false)
    private val pauseRequested = ConcurrentHashMap.newKeySet<Long>()
    private val pauseCleanup = ConcurrentHashMap<Long, Job>()
    private val cancelRequested = ConcurrentHashMap.newKeySet<Long>()
    private val cancelCleanup = ConcurrentHashMap<Long, Job>()

    /**
     * 公平任务槽。
     *
     * 旧实现先读 `activeCount` 再在协程里自增，多个 submit 可以同时通过检查，
     * 实际并发会超过 [TurboConfig.maxConcurrentTasks]。这里用锁保护“计数 + FIFO
     * 等待队列”，并在配置调大时立即唤醒等待者。
     */
    private class SlotWaiter {
        val deferred = CompletableDeferred<Unit>()
        @Volatile var granted = false
    }

    private val slotLock = Mutex()
    private val slotWaiters = java.util.ArrayDeque<SlotWaiter>()
    private var activeSlots = 0

    /** 提交下载任务，立即入队并异步开始。返回任务 id。 */
    fun submit(request: DownloadRequest): Long {
        check(!closed.get()) { "TurboClient 已关闭" }
        val id = idGen.incrementAndGet()
        requests[id] = request
        completions[id] = CompletableDeferred()
        emit(TurboEvent.Created(id, request))
        setState(id, request, TaskState.QUEUED)
        // Registering can race with shutdown(): shutdown iterates the current
        // completion map, so a task added after that sweep would otherwise
        // leave its await() pending forever.
        if (closed.get()) {
            completions.remove(id)
            requests.remove(id)
            _progress.update { it - id }
            throw IllegalStateException("TurboClient 已关闭")
        }

        val job = scope.launch {
            try {
                awaitSlot()
                if (coroutineContext[Job]?.isActive != true) return@launch
                try {
                    runTask(id, request)
                } finally {
                    releaseSlot()
                    // 释放任务级的下载器状态（在飞 Call 登记 + 已学到的重定向终址）。
                    // 不释放的话每完成一个任务就留两条永不回收的条目 → 长跑进程无界增长。
                    downloader.releaseTask(id)
                }
            } catch (e: CancellationException) {
                completions[id]?.complete(Result.failure(e))
                setState(
                    id,
                    request,
                    if (pauseRequested.remove(id)) TaskState.PAUSED else TaskState.CANCELED,
                )
            } catch (e: Exception) {
                // 【归因优先】0.2.3.0 让 SegmentDownloader 在分片层记录了"真实原因"
                // （HTTP 状态码 / 连接被重置 / 只回了半截 body …），因为那里才拿得到
                // 响应信息，异常冒到这一层时只剩一句泛化的 message（"failed to
                // connect"），对排查没有任何帮助。
                // takeWholeError 会**移除**记录，所以必须只调一次并复用结果。
                val reason = downloader.takeWholeError(id)
                    ?: e.message
                    ?: e.javaClass.simpleName
                emit(TurboEvent.Failed(id, reason))
                updateProgress(id) { it.copy(state = TaskState.FAILED, error = reason) }
                completions[id]?.complete(Result.failure(e))
            } finally {
                jobs.remove(id, coroutineContext[Job])
            }
        }
        jobs[id] = job
        // A tiny task can finish before the map write above. Do not retain a
        // completed Job forever; the finally in the coroutine may already have
        // run before this entry became visible.
        if (job.isCompleted) jobs.remove(id, job)
        return id
    }

    /** 挂起直到任务结束，返回结果（成功=文件，失败=异常）。 */
    suspend fun await(id: Long): Result<File> =
        completions[id]?.await() ?: Result.failure(IllegalArgumentException("未知任务 $id"))

    /** 暂停任务（保留断点）。 */
    fun pause(id: Long) {
        if (closed.get()) return
        if (cancelRequested.contains(id) || cancelCleanup.containsKey(id)) return
        val request = requests[id] ?: return
        val current = progress.value[id]?.state
        if (current == TaskState.COMPLETED || current == TaskState.FAILED || current == TaskState.CANCELED) return
        pauseRequested.add(id)
        downloader.cancelCalls(id)
        val job = jobs.remove(id)
        val cleanup = scope.launch {
            job?.let { runCatching { it.cancelAndJoin() } }
            if (!cancelRequested.contains(id)) {
                requests[id]?.let { setState(id, it, TaskState.PAUSED) }
            }
            pauseCleanup.remove(id, coroutineContext[Job])
        }
        pauseCleanup[id] = cleanup
        if (cleanup.isCompleted) pauseCleanup.remove(id, cleanup)
    }

    /**
     * 恢复已暂停的任务（断点续传）。
     *
     * ## 竞态：join 必须在 launch 之前**同步**完成
     *
     * 原实现把 `jobs.remove(id)?.cancelAndJoin()` 放在 `scope.launch` **内部**，于是：
     *
     * ```
     * resume() → launch(协程尚未被调度) → putIfAbsent 同步执行 → 旧 job 还在表里
     *          → 返回 false 并 cancel 掉刚 launch 的协程 → 续传永远启动不了
     * ```
     *
     * 那个协程要等调度器给它时间才会跑到 `jobs.remove`，而 `putIfAbsent` 是同步的 ——
     * 所以「join 旧 job」从来没有在 `putIfAbsent` 之前发生。
     * 实测（`BuiltinBackendE2ETest`）：`pause` 后 300ms 调 `resume`，
     * 任务**永远到不了终态**，端到端测试超时 60s。
     *
     * 现在顺序是：同步 join 掉旧 job → putIfAbsent 占位 → 再 launch。
     * 于是 `putIfAbsent` 看到的一定是「没有活动 job」，`resume` 也就能如实返回成功。
     */
    fun resume(id: Long): Boolean {
        if (closed.get()) return false
        val req = requests[id] ?: return false

        // 同步等待旧 job 退出：它会写下 PAUSED 并归还分片目录，之后才允许新 job 接手。
        // 这里必须用 runBlocking 而不是 launch —— 正确性依赖「join 已经完成」，
        // 而 launch 做不到同步保证，那正是这个竞态的来源。
        runBlocking {
            cancelCleanup.remove(id)?.let { runCatching { it.join() } }
            pauseCleanup.remove(id)?.let { runCatching { it.join() } }
            jobs.remove(id)?.let { runCatching { it.cancelAndJoin() } }
        }
        if (closed.get()) return false
        cancelRequested.remove(id)
        // A paused task completed its old Deferred with CancellationException.
        // Replace it only after the old job is gone so await() after resume()
        // observes the resumed task, not the stale pause result.
        pauseRequested.remove(id)
        completions[id] = CompletableDeferred()

        val job = scope.launch {
            try {
                awaitSlot()
                if (coroutineContext[Job]?.isActive != true) return@launch
                try { runTask(id, req) } finally { releaseSlot(); downloader.releaseTask(id) }
            } catch (e: CancellationException) {
                setState(id, req, TaskState.PAUSED)
            } catch (e: Exception) {
                // 同 start()：优先用分片层记录的真实原因
                emit(
                    TurboEvent.Failed(
                        id,
                        downloader.takeWholeError(id)
                            ?: e.message
                            ?: e.javaClass.simpleName,
                    ),
                )
                completions[id]?.complete(Result.failure(e))
            } finally {
                jobs.remove(id, coroutineContext[Job])
            }
        }
        // 若已有活动 job，不重复启动（新 job 会在上方 join 阶段自行退出）。
        val prev = jobs.putIfAbsent(id, job)
        if (prev != null) { job.cancel(); return false }
        if (job.isCompleted) jobs.remove(id, job)
        return true
    }

    /** 取消任务并清理临时分片。deleteOutput=true 同时删除已保存的目标文件。 */
    fun cancel(id: Long, deleteOutput: Boolean = false) {
        if (closed.get()) return
        if (requests[id] == null || cancelCleanup.containsKey(id)) return
        cancelRequested.add(id)
        pauseRequested.remove(id)
        downloader.cancelCalls(id)
        val job = jobs.remove(id)
        val pausedCleanup = pauseCleanup.remove(id)
        val cleanup = scope.launch {
            // A pause may still be joining the old job and setting PAUSED.
            // Finish that transition first, otherwise it can overwrite CANCELED.
            pausedCleanup?.let { runCatching { it.cancelAndJoin() } }
            job?.let { runCatching { it.cancelAndJoin() } }
            chunkDirOf(id).deleteRecursively()
            if (deleteOutput) requests[id]?.destination?.delete()
            requests[id]?.let { setState(id, it, TaskState.CANCELED) }
            completions[id]?.complete(Result.failure(CancellationException("canceled")))
            cancelRequested.remove(id)
            cancelCleanup.remove(id, coroutineContext[Job])
        }
        cancelCleanup[id] = cleanup
        if (cleanup.isCompleted) cancelCleanup.remove(id, cleanup)
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
        if (!closed.compareAndSet(false, true)) return
        scope.coroutineContext[Job]?.cancel()
        // 惰性客户端可能从未创建：没创建过就没什么可关的，别在这里把它唤醒。
        streamClientRef?.let { sc ->
            runCatching { sc.dispatcher.executorService.shutdown() }
            runCatching { sc.connectionPool.evictAll() }
        }
        runCatching { segmentClient.dispatcher.executorService.shutdown() }
        runCatching { segmentClient.connectionPool.evictAll() }
        val cancellation = CancellationException("TurboClient 已关闭")
        completions.values.forEach { it.complete(Result.failure(cancellation)) }
    }

    // ---------- 内部 ----------

    private suspend fun awaitSlot() {
        var waiter: SlotWaiter? = null
        slotLock.withLock {
            if (activeSlots < config.maxConcurrentTasks) {
                activeSlots++
            } else {
                waiter = SlotWaiter().also { slotWaiters.addLast(it) }
            }
        }
        val queued = waiter ?: return
        try {
            queued.deferred.await()
        } catch (e: CancellationException) {
            slotLock.withLock { slotWaiters.remove(queued) }
            // The waiter may have been granted between cancellation and cleanup.
            if (queued.granted) releaseSlot()
            throw e
        }
    }

    /** Return a task slot and grant the oldest waiter(s) allowed by current config. */
    private suspend fun releaseSlot() {
        slotLock.withLock {
            activeSlots = (activeSlots - 1).coerceAtLeast(0)
            grantSlotsLocked()
        }
    }

    private fun grantSlotsLocked() {
        while (activeSlots < config.maxConcurrentTasks) {
            val waiter = slotWaiters.pollFirst() ?: break
            activeSlots++
            waiter.granted = true
            waiter.deferred.complete(Unit)
        }
    }

    private fun retireClient(client: okhttp3.OkHttpClient) {
        // updateConfig may run while synchronous calls are still in flight.
        // Shutting down the dispatcher only rejects future async work; evictAll
        // closes idle sockets, while active calls retain their own connection.
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
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

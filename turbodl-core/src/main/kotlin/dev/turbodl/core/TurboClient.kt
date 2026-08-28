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
        httpClient = HttpClientFactory.build(newConfig)
    }

    @Volatile
    private var httpClient = HttpClientFactory.build(config)

    private val downloader = SegmentDownloader { httpClient }
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
                try { runTask(id, req) } finally { activeCount.decrementAndGet() }
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

    /** 关闭引擎，取消所有任务并释放资源。 */
    fun shutdown() {
        scope.coroutineContext[Job]?.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
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
            override suspend fun reportProgress(absoluteBytes: Long, activeConnections: Int) {
                downloadedRef.set(absoluteBytes)
                liveConnsRef.set(activeConnections)
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
        finish(id, request, result.orderedParts, result.totalBytes)
    }

    private suspend fun finish(id: Long, request: DownloadRequest, parts: List<File>, total: Long) {
        updateProgress(id) { it.copy(state = TaskState.MERGING) }
        for (p in parts) {
            if (!p.exists() || p.length() <= 0) throw IllegalStateException("分片缺失/为空：$p")
        }
        val dest = request.destination
        dest.parentFile?.mkdirs()
        // 合并时上报进度：GB 级文件拼接可能耗时数十秒，UI 不再卡在 MERGING 无进度。
        var lastReport = 0L
        val ok = PartMerger.merge(parts, dest) { mergedBytes, totalBytes ->
            val now = System.currentTimeMillis()
            if (now - lastReport >= 200) {
                lastReport = now
                updateProgress(id) {
                    it.copy(state = TaskState.MERGING, downloadedBytes = mergedBytes, totalBytes = totalBytes)
                }
            }
        }
        if (!ok) throw IllegalStateException("合并分片失败")
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
        _progress.update { map ->
            val cur = map[id] ?: TaskProgress(
                taskId = id, state = TaskState.QUEUED,
                downloadedBytes = 0, totalBytes = -1,
                speedBytesPerSec = 0, activeConnections = 0, etaMillis = -1,
            )
            val next = transform(cur)
            emit(TurboEvent.Progress(id, next))
            map + (id to next)
        }
    }

    /** 速度采样：每 500ms 计算一次平均速率。 */
    private class SpeedRecorder {
        private var lastBytes = 0L
        private var lastTime = System.currentTimeMillis()
        @Synchronized
        fun sample(total: Long): Long? {
            val now = System.currentTimeMillis()
            val dt = now - lastTime
            if (dt >= 500) {
                val speed = if (dt > 0) ((total - lastBytes) * 1000 / dt).coerceAtLeast(0) else 0
                lastBytes = total; lastTime = now
                return speed
            }
            return null
        }
    }
}

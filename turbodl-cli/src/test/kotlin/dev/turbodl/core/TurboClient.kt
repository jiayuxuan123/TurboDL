@file:Suppress("DEPRECATION")

package dev.turbodl.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * CLI 契约测试专用的 TurboClient 影子实现。
 *
 * 测试进程把本类目录放在 classpath 最前面，因此 CLI 的二进制代码不改一行，
 * 仍会调用这里，并把解析后的 TurboConfig 捕获为可断言的文件。
 */
class TurboClient(@Suppress("UNUSED_PARAMETER") config: TurboConfig = TurboConfig()) {
    private val capturedConfig = config
    private val mutableProgress = MutableStateFlow<Map<Long, TaskProgress>>(emptyMap())
    private val mutableEvents = MutableSharedFlow<TurboEvent>()
    private val destinations = ConcurrentHashMap<Long, File>()
    private val failures = ConcurrentHashMap<Long, Boolean>()
    private val nextId = AtomicLong(0)

    init {
        instances.incrementAndGet()
    }

    val events: SharedFlow<TurboEvent> = mutableEvents.asSharedFlow()
    val progress: StateFlow<Map<Long, TaskProgress>> = mutableProgress.asStateFlow()

    fun submit(request: DownloadRequest): Long = submit(request, File("."))

    fun submit(request: DownloadRequest, workDir: File): Long {
        val id = nextId.incrementAndGet()
        val out = request.destination.absoluteFile
        out.parentFile?.mkdirs()
        out.writeBytes(byteArrayOf())
        destinations[id] = out
        failures[id] = System.getenv("TURBODL_TEST_PROGRESS_MODE") == "failure"
        capture(request, out, id, workDir)

        when {
            failures[id] == true -> setProgress(id, TaskState.FAILED)
            System.getenv("TURBODL_TEST_PROGRESS_MODE") == "stream" -> {
                setProgress(id, TaskState.DOWNLOADING)
                thread(isDaemon = true, name = "turbodl-cli-fake-progress-$id") {
                    repeat(8) {
                        Thread.sleep(60)
                        setProgress(id, TaskState.DOWNLOADING)
                    }
                    setProgress(id, TaskState.COMPLETED)
                }
            }
            else -> setProgress(id, TaskState.COMPLETED)
        }
        return id
    }

    suspend fun await(id: Long): Result<File> =
        if (failures[id] == true) {
            Result.failure(IllegalStateException("synthetic CLI failure"))
        } else {
            Result.success(destinations[id] ?: error("destination not captured for $id"))
        }

    fun shutdown() = Unit

    private fun setProgress(id: Long, state: TaskState) {
        val current = mutableProgress.value
        mutableProgress.value = current + (
            id to TaskProgress(
                taskId = id,
                state = state,
                downloadedBytes = 0,
                totalBytes = 0,
                speedBytesPerSec = 0,
                activeConnections = 0,
                etaMillis = -1,
            )
        )
    }

    private fun capture(request: DownloadRequest, out: File, id: Long, workDir: File) {
        val path = System.getenv("TURBODL_TEST_CAPTURE") ?: return
        synchronized(CAPTURE_LOCK) {
            Properties().apply {
                setProperty("config.maxConnectionsPerTask", capturedConfig.maxConnectionsPerTask.toString())
                setProperty("config.maxTotalConnections", capturedConfig.maxTotalConnections.toString())
                setProperty("config.maxRetries", capturedConfig.maxRetries.toString())
                setProperty("config.maxConcurrentTasks", capturedConfig.maxConcurrentTasks.toString())
                setProperty("config.tailAssist", capturedConfig.tailAssist.toString())
                setProperty("config.overlapMerge", capturedConfig.overlapMerge.toString())
                setProperty("config.globalSpeedLimitBytesPerSec", capturedConfig.globalSpeedLimitBytesPerSec.toString())
                setProperty("config.connectTimeoutMs", capturedConfig.connectTimeoutMs.toString())
                setProperty("config.readTimeoutMs", capturedConfig.readTimeoutMs.toString())
                setProperty("client.instances", instances.get().toString())
                setProperty("request.taskId", id.toString())
                setProperty("request.url", request.url)
                setProperty("request.destination", out.absolutePath)
                setProperty("request.stableKey", request.stableKey.orEmpty())
                setProperty("request.expectedSha256", request.expectedSha256.orEmpty())
                setProperty("request.mirrors", request.mirrors.joinToString("|"))
                setProperty("request.workDir", workDir.absolutePath)
                setProperty("request.knownSize", request.knownSize.toString())
                setProperty("request.headers", request.headers.entries.joinToString("|") { "${it.key}=${it.value}" })
            }.store(File(path).outputStream(), null)
        }
    }

    private companion object {
        val instances = AtomicInteger(0)
        val CAPTURE_LOCK = Any()
    }
}

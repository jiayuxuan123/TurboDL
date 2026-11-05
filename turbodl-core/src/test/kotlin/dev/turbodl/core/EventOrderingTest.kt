package dev.turbodl.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventOrderingTest {
    @Test
    fun `state and terminal events are delivered in lifecycle order`() = runBlocking {
        withTimeout(10_000) {
            val events = mutableListOf<TurboEvent>()
            val backend = object : DownloadBackend {
                override val name: String = "event-order"
                override fun supports(request: DownloadRequest): Boolean = request.url.startsWith("event-order://")

                override suspend fun download(context: BackendContext): BackendResult {
                    val destination = context.request.destination
                    destination.writeBytes(byteArrayOf(1, 2, 3))
                    context.reportTotalSize(destination.length())
                    context.reportProgress(destination.length(), 1)
                    return BackendResult(listOf(destination), destination.length())
                }
            }
            val client = TurboClient(TurboConfig(maxConcurrentTasks = 1))
            client.backendResolver = BackendResolver { backend }
            val terminalDelivered = CompletableDeferred<Unit>()
            val collector = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                client.events.collect { event ->
                    synchronized(events) { events += event }
                    if (event is TurboEvent.Completed || event is TurboEvent.Failed) {
                        terminalDelivered.complete(Unit)
                    }
                }
            }
            try {
                val id = client.submit(
                    DownloadRequest(
                        url = "event-order://file",
                        destination = File.createTempFile("event-order", ".bin").apply { deleteOnExit() },
                    )
                )
                assertTrue(client.await(id).isSuccess)
                terminalDelivered.await()
                collector.cancelAndJoin()
                val states = synchronized(events) { events.filterIsInstance<TurboEvent.StateChanged>() }
                    .filter { it.taskId == id }
                    .map { it.state }
                assertEquals(
                    listOf(
                        TaskState.QUEUED,
                        TaskState.PROBING,
                        TaskState.DOWNLOADING,
                        TaskState.MERGING,
                        TaskState.COMPLETED,
                    ),
                    states,
                )
                val terminalIndex = synchronized(events) { events.indexOfLast { it is TurboEvent.Completed } }
                val completedStateIndex = synchronized(events) {
                    events.indexOfLast { it is TurboEvent.StateChanged && it.state == TaskState.COMPLETED }
                }
                assertTrue(
                    completedStateIndex in 0 until terminalIndex,
                    "Completed event must follow StateChanged(COMPLETED)",
                )
            } finally {
                client.shutdown()
            }
        }
    }

    @Test
    fun `failure exposes a terminal state change before the failed event`() = runBlocking {
        withTimeout(10_000) {
            val events = mutableListOf<TurboEvent>()
            val backend = object : DownloadBackend {
                override val name: String = "event-failure"
                override fun supports(request: DownloadRequest): Boolean =
                    request.url.startsWith("event-failure://")

                override suspend fun download(context: BackendContext): BackendResult {
                    throw IllegalStateException("backend failed")
                }
            }
            val client = TurboClient(TurboConfig(maxConcurrentTasks = 1))
            client.backendResolver = BackendResolver { backend }
            val terminalDelivered = CompletableDeferred<Unit>()
            val collector = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                client.events.collect { event ->
                    synchronized(events) { events += event }
                    if (event is TurboEvent.Failed) terminalDelivered.complete(Unit)
                }
            }
            try {
                val id = client.submit(
                    DownloadRequest(
                        url = "event-failure://file",
                        destination = File.createTempFile("event-failure", ".bin").apply { deleteOnExit() },
                    )
                )
                assertTrue(client.await(id).isFailure)
                terminalDelivered.await()
                collector.cancelAndJoin()
                val states = synchronized(events) {
                    events.filterIsInstance<TurboEvent.StateChanged>()
                }.filter { it.taskId == id }.map { it.state }
                assertEquals(
                    listOf(TaskState.QUEUED, TaskState.PROBING, TaskState.DOWNLOADING, TaskState.FAILED),
                    states,
                )
                val failedEventIndex = synchronized(events) { events.indexOfLast { it is TurboEvent.Failed } }
                val failedStateIndex = synchronized(events) {
                    events.indexOfLast { it is TurboEvent.StateChanged && it.state == TaskState.FAILED }
                }
                assertTrue(failedStateIndex in 0 until failedEventIndex)
            } finally {
                client.shutdown()
            }
        }
    }

    @Test
    fun `resume failure emits FAILED state before the failed event`() = runBlocking {
        withTimeout(15_000) {
            val events = mutableListOf<TurboEvent>()
            val attempts = java.util.concurrent.atomic.AtomicInteger(0)
            val backend = object : DownloadBackend {
                override val name: String = "event-resume-failure"
                override fun supports(request: DownloadRequest): Boolean =
                    request.url.startsWith("event-resume-failure://")

                override suspend fun download(context: BackendContext): BackendResult {
                    if (attempts.incrementAndGet() == 1) {
                        while (context.isActive()) delay(25)
                        throw kotlinx.coroutines.CancellationException("paused")
                    }
                    throw IllegalStateException("resume backend failed")
                }
            }
            val client = TurboClient(TurboConfig(maxConcurrentTasks = 1))
            client.backendResolver = BackendResolver { backend }
            val collector = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                client.events.collect { event -> synchronized(events) { events += event } }
            }
            try {
                val id = client.submit(
                    DownloadRequest(
                        url = "event-resume-failure://file",
                        destination = File.createTempFile("event-resume-failure", ".bin")
                            .apply { deleteOnExit() },
                    )
                )
                client.pause(id)
                withTimeout(5_000) {
                    while (client.progress.value[id]?.state != TaskState.PAUSED) delay(25)
                }
                assertTrue(client.resume(id), "暂停后应能启动续传 job")
                assertTrue(client.await(id).isFailure, "续传后端异常必须让任务失败")
                withTimeout(5_000) {
                    while (synchronized(events) { events.none { it is TurboEvent.Failed && it.taskId == id } }) {
                        delay(25)
                    }
                }
                val failedIndex = synchronized(events) {
                    events.indexOfLast { it is TurboEvent.Failed && it.taskId == id }
                }
                val failedStateIndex = synchronized(events) {
                    events.indexOfLast {
                        it is TurboEvent.StateChanged &&
                            it.taskId == id &&
                            it.state == TaskState.FAILED
                    }
                }
                assertTrue(
                    failedStateIndex in 0 until failedIndex,
                    "resume 失败必须先发 StateChanged(FAILED)，再发 Failed；实际顺序 ${synchronized(events) { events.map { it::class.simpleName } }}",
                )
            } finally {
                collector.cancelAndJoin()
                client.shutdown()
            }
        }
    }

    @Test
    fun `late backend progress cannot resurrect a terminal task`() = runBlocking {
        withTimeout(10_000) {
            lateinit var captured: BackendContext
            val backend = object : DownloadBackend {
                override val name: String = "late-progress"
                override fun supports(request: DownloadRequest): Boolean =
                    request.url.startsWith("late-progress://")

                override suspend fun download(context: BackendContext): BackendResult {
                    captured = context
                    val destination = context.request.destination
                    destination.writeBytes(byteArrayOf(1, 2, 3))
                    context.reportTotalSize(3)
                    context.reportProgress(3, 1)
                    return BackendResult(listOf(destination), 3)
                }
            }
            val client = TurboClient(TurboConfig(maxConcurrentTasks = 1))
            client.backendResolver = BackendResolver { backend }
            try {
                val id = client.submit(
                    DownloadRequest(
                        url = "late-progress://file",
                        destination = File.createTempFile("late-progress", ".bin")
                            .apply { deleteOnExit() },
                    )
                )
                assertTrue(client.await(id).isSuccess)
                assertEquals(TaskState.COMPLETED, client.progress.value[id]?.state)

                captured.reportProgress(1, 255)
                delay(100)

                val final = client.progress.value[id]
                assertEquals(TaskState.COMPLETED, final?.state, "迟到进度不能把终态改回 DOWNLOADING")
                assertEquals(3L, final?.downloadedBytes, "迟到进度不能倒退已完成字节")
            } finally {
                client.shutdown()
            }
        }
    }

    @Test
    fun `bounded event queue drops progress under slow consumption but retains terminal`() = runBlocking {
        withTimeout(10_000) {
            val queue = BoundedEventQueue(capacity = 8)
            val firstProgress = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val seen = mutableListOf<TurboEvent>()
            val consumer = launch(Dispatchers.Default) {
                queue.consume { event ->
                    synchronized(seen) { seen += event }
                    if (event is TurboEvent.Progress) {
                        firstProgress.complete(Unit)
                        release.await()
                    }
                }
            }
            val request = DownloadRequest(
                url = "event-buffer://file",
                destination = File.createTempFile("event-buffer", ".bin").apply { deleteOnExit() },
            )
            queue.offer(TurboEvent.Created(1, request))
            queue.offer(TurboEvent.Progress(1, progress(1, 0)))
            firstProgress.await()

            repeat(100) { index ->
                queue.offer(TurboEvent.Progress(1, progress(1, index.toLong() + 1)))
            }
            queue.offer(TurboEvent.Completed(1, request.destination, 101))
            assertTrue(queue.droppedProgressCount() > 0, "慢消费者时进度事件必须有界丢弃")
            assertTrue(queue.queuedCount() <= 8, "staging queue 不能超过容量")

            release.complete(Unit)
            withTimeout(5_000) {
                while (synchronized(seen) { seen.none { it is TurboEvent.Completed } }) {
                    delay(10)
                }
            }
            assertTrue(synchronized(seen) { seen.last() is TurboEvent.Completed })
            queue.close()
            consumer.cancelAndJoin()
        }
    }

    private fun progress(taskId: Long, downloaded: Long): TaskProgress =
        TaskProgress(
            taskId = taskId,
            state = TaskState.DOWNLOADING,
            downloadedBytes = downloaded,
            totalBytes = 101,
            speedBytesPerSec = 0,
            activeConnections = 1,
            etaMillis = -1,
        )
}

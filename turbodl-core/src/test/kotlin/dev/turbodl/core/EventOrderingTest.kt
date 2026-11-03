package dev.turbodl.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
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
}

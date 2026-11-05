package dev.turbodl.core

import kotlinx.coroutines.channels.Channel
import java.util.ArrayDeque

/**
 * Bounded staging queue between the engine and [TurboClient.events].
 *
 * `MutableSharedFlow.emit` can suspend when a host stops consuming events.
 * The old UNLIMITED channel then grew forever. This queue keeps the public
 * event stream ordered while making the backlog bounded; progress and
 * metadata are the first events sacrificed under sustained backpressure.
 * State transitions and terminal events are retained whenever possible.
 */
internal class BoundedEventQueue(
    private val capacity: Int = 2048,
) {
    init {
        require(capacity > 0) { "event queue capacity must be positive" }
    }

    private val lock = Any()
    private val buffer = ArrayDeque<TurboEvent>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var closed = false
    private var droppedProgress = 0L
    private var droppedNonProgress = 0L

    /** Add an event without blocking a download worker. */
    fun offer(event: TurboEvent): Boolean {
        synchronized(lock) {
            if (closed) return false
            if (buffer.size >= capacity) {
                val removedLowPriority = removeFirstLowPriorityLocked()
                when {
                    removedLowPriority -> droppedProgress++
                    event is TurboEvent.Progress || event is TurboEvent.Metadata -> {
                        droppedProgress++
                        return false
                    }
                    else -> {
                        // A pathological stream of state events can still
                        // overflow; bound memory even when no progress exists.
                        buffer.removeFirst()
                        droppedNonProgress++
                    }
                }
            }
            buffer.addLast(event)
        }
        signal.trySend(Unit)
        return true
    }

    /** Stop accepting events and wake the consumer. */
    fun close() {
        synchronized(lock) { closed = true }
        signal.close()
    }

    /** Consume events in insertion order until [close] is called. */
    suspend fun consume(sink: suspend (TurboEvent) -> Unit) {
        for (unused in signal) {
            while (true) {
                val event = synchronized(lock) {
                    if (buffer.isEmpty()) null else buffer.removeFirst()
                } ?: break
                sink(event)
            }
        }
    }

    internal fun queuedCount(): Int = synchronized(lock) { buffer.size }

    internal fun droppedProgressCount(): Long = synchronized(lock) { droppedProgress }

    internal fun droppedNonProgressCount(): Long = synchronized(lock) { droppedNonProgress }

    private fun removeFirstLowPriorityLocked(): Boolean {
        val iterator = buffer.iterator()
        while (iterator.hasNext()) {
            when (iterator.next()) {
                is TurboEvent.Progress, is TurboEvent.Metadata -> {
                    iterator.remove()
                    return true
                }
                else -> Unit
            }
        }
        return false
    }
}

package dev.turbodl.core

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/** Broadcasts final drain without losing a notification between inspection and wait. */
internal class SchedulerSignal {
    private val current = AtomicReference<CompletableDeferred<Unit>?>(null)

    fun observe(): CompletableDeferred<Unit> {
        while (true) {
            current.get()?.let { return it }
            val next = CompletableDeferred<Unit>()
            if (current.compareAndSet(null, next)) return next
        }
    }

    fun signal() { current.getAndSet(null)?.complete(Unit) }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun await(ticket: CompletableDeferred<Unit>, maximumDelayMs: Long) {
        select<Unit> {
            ticket.onAwait { }
            onTimeout(maximumDelayMs) { }
        }
    }
}

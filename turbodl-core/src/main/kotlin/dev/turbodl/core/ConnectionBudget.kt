package dev.turbodl.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide connection budget shared by all built-in HTTP tasks.
 *
 * `maxConnectionsPerTask` alone lets N concurrent tasks multiply the connection
 * count by N. This budget grants a task only the permits currently available;
 * the scheduler then runs with that effective count.
 */
internal class ConnectionBudget(
    private val limitProvider: () -> Int,
) {
    internal class Lease(
        val permits: Int,
    ) {
        internal val released = AtomicBoolean(false)
    }

    private class Waiter(
        val requested: Int,
        val result: CompletableDeferred<Lease> = CompletableDeferred(),
    ) {
        @Volatile
        var grantedLease: Lease? = null
    }

    private val lock = Mutex()
    private val waiters = ArrayDeque<Waiter>()
    private var activePermits = 0

    suspend fun acquire(requested: Int): Lease {
        val wanted = requested.coerceAtLeast(1)
        val limit = limitProvider()
        if (limit <= 0) return Lease(wanted)

        var immediate: Lease? = null
        var waiter: Waiter? = null
        lock.withLock {
            if (activePermits < limit) {
                val granted = minOf(wanted, limit - activePermits)
                activePermits += granted
                immediate = Lease(granted)
            } else {
                waiter = Waiter(wanted).also { waiters.addLast(it) }
            }
        }
        immediate?.let { return it }

        val queued = waiter ?: return Lease(1)
        try {
            return queued.result.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            lock.withLock { waiters.remove(queued) }
            queued.grantedLease?.let { release(it) }
            throw e
        }
    }

    suspend fun release(lease: Lease) {
        if (!lease.released.compareAndSet(false, true)) return
        val limit = limitProvider()
        lock.withLock {
            activePermits = (activePermits - lease.permits).coerceAtLeast(0)
            if (limit > 0) grantWaitersLocked(limit)
        }
    }

    /** Wake queued tasks when the configured global limit increases. */
    suspend fun limitChanged() {
        val limit = limitProvider()
        if (limit <= 0) return
        lock.withLock { grantWaitersLocked(limit) }
    }

    private fun grantWaitersLocked(limit: Int) {
        while (waiters.isNotEmpty() && activePermits < limit) {
            val waiter = waiters.pollFirst() ?: break
            val granted = minOf(waiter.requested, limit - activePermits)
            activePermits += granted
            val lease = Lease(granted)
            waiter.grantedLease = lease
            waiter.result.complete(lease)
        }
    }
}

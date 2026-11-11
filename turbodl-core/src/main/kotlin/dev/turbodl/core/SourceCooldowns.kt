package dev.turbodl.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** Task groups share cooldown and a fair, gradual recovery after source throttling. */
internal class SourceCooldowns(private val maxEntries: Int = 512) {
    private class Entry {
        var deadline = 0L
        var active = 0
        var limit = Int.MAX_VALUE
        var epoch = 0L
        var successes = 0
        val waiters = ArrayDeque<Any>()
    }
    private val entries = LinkedHashMap<String, Entry>()

    internal class Lease(private val finish: (Boolean) -> Unit) {
        private val released = AtomicBoolean()
        fun release(successful: Boolean = false) {
            if (released.compareAndSet(false, true)) finish(successful)
        }
    }

    private fun key(url: String, group: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        return "${parsed.scheme}://${parsed.host}:${parsed.port}\u0000$group"
    }

    private fun entry(key: String, now: Long): Entry? {
        entries[key]?.let { return it }
        if (entries.size >= maxEntries) {
            entries.entries.removeAll { it.value.active == 0 && it.value.waiters.isEmpty() && it.value.deadline <= now }
        }
        if (entries.size >= maxEntries) return null
        return Entry().also { entries[key] = it }
    }

    @Synchronized fun defer(url: String, group: String, millis: Long, recoverConcurrency: Boolean = true,
        requestedConcurrency: Int = 1): Boolean {
        val key = key(url, group) ?: return false
        if (millis > 7L * 24 * 60 * 60 * 1000) return false
        val now = System.nanoTime()
        val state = entry(key, now) ?: return false
        if (state.deadline <= now && recoverConcurrency) {
            // Use one task's configured ceiling, not the combined in-flight fanout.
            state.limit = maxOf(1, minOf(state.limit, requestedConcurrency.coerceAtLeast(1)) / 2)
            state.epoch++
            state.successes = 0
        }
        state.deadline = maxOf(state.deadline, now + millis.coerceAtLeast(1) * 1_000_000L)
        return true
    }

    @Synchronized private fun remaining(url: String, group: String): Long {
        val key = key(url, group) ?: return 0
        return ((entries[key]?.deadline ?: 0) - System.nanoTime()).coerceAtLeast(0)
    }

    fun isCooling(url: String, group: String): Boolean = remaining(url, group) > 0

    suspend fun await(url: String, group: String) {
        while (true) {
            coroutineContext.ensureActive()
            val nanos = remaining(url, group)
            if (nanos > 120_000L * 1_000_000) throw SourceCooldownDeferred(nanos / 1_000_000)
            if (nanos <= 0) return
            delay(((nanos + 999_999) / 1_000_000).coerceAtMost(200))
        }
    }

    suspend fun acquire(url: String, group: String, recoverConcurrency: Boolean = true): Lease {
        if (!recoverConcurrency) {
            await(url, group)
            return Lease {}
        }
        val key = key(url, group) ?: return Lease {}
        val ticket = Any()
        val state = synchronized(this) {
            val found = entry(key, System.nanoTime()) ?: return Lease {}
            if (found.limit == Int.MAX_VALUE && found.deadline <= System.nanoTime()) {
                found.active++
                return lease(found, found.epoch)
            }
            found.waiters.addLast(ticket)
            found
        }
        var acquired = false
        try {
            while (true) {
                coroutineContext.ensureActive()
                val nanos = remaining(url, group)
                if (nanos > 120_000L * 1_000_000) throw SourceCooldownDeferred(nanos / 1_000_000)
                val epoch = synchronized(this) {
                    if (state.deadline <= System.nanoTime() && state.active < state.limit && state.waiters.peekFirst() === ticket) {
                        state.waiters.removeFirst()
                        state.active++
                        acquired = true
                        state.epoch
                    } else null
                }
                if (epoch != null) return lease(state, epoch)
                delay(10)
            }
        } finally {
            if (!acquired) synchronized(this) { state.waiters.remove(ticket) }
        }
    }

    private fun lease(state: Entry, epoch: Long) = Lease { successful ->
        synchronized(this) {
            state.active--
            if (successful && state.epoch == epoch && state.deadline <= System.nanoTime() && state.limit < Int.MAX_VALUE) {
                state.successes++
                if (state.successes >= 8) {
                    state.successes = 0
                    state.limit++
                }
            }
        }
    }
}

internal class SourceCooldownDeferred(val retryAfterMs: Long) :
    java.io.IOException("来源要求较长时间限流，已保留断点，请稍后重试")

package dev.turbodl.plugin.runtime

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Disposer — a LIFO cleanup registry bound to a plugin's lifetime.
 *
 * Every side effect a plugin creates (event subscriptions, extension-point registrations,
 * service registrations, background jobs, ...) should register a matching cleanup callback
 * here. On unload the host runs ALL disposers in reverse order, guaranteeing no leaked
 * listeners/registrations/resources remain.
 *
 * Design note (inspired by Cordis-style disposer chains, reimplemented from scratch):
 * disposers are collected during onLoad and drained exactly once on unload; draining is
 * idempotent and isolates individual disposer exceptions so one failing cleanup cannot
 * prevent the rest from running.
 */
class Disposer {
    private val callbacks = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var disposed = false

    /** Register a cleanup callback. Returns a handle that can cancel just this one early. */
    fun register(cleanup: () -> Unit): Registration {
        if (disposed) {
            // Registering after disposal: run immediately to avoid a silent leak.
            runCatching { cleanup() }
            return Registration {}
        }
        callbacks.add(cleanup)
        return Registration { callbacks.remove(cleanup) }
    }

    /** True once [dispose] has been invoked. */
    val isDisposed: Boolean get() = disposed

    /**
     * Run all registered cleanups in reverse (LIFO) order, exactly once.
     * Individual failures are collected and returned; they never abort the chain.
     */
    fun dispose(): List<Throwable> {
        if (disposed) return emptyList()
        disposed = true
        val errors = mutableListOf<Throwable>()
        val snapshot = callbacks.toList().asReversed()
        callbacks.clear()
        for (cb in snapshot) {
            runCatching { cb() }.exceptionOrNull()?.let { errors.add(it) }
        }
        return errors
    }

    /** Handle returned by [register]; lets a caller undo a single registration early. */
    fun interface Registration {
        fun cancel()
    }
}

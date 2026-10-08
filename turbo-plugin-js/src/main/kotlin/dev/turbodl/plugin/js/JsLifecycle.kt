package dev.turbodl.plugin.js

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The unload state machine of a single JS plugin instance.
 *
 * ## Why a state machine instead of just closing the engine
 * A QuickJS runtime is native memory reachable only from the thread that drives it. Calling
 * `close()` while JavaScript is executing is a use-after-free candidate — the binding callbacks
 * that JS is inside of would outlive the context. So unload is a *sequence*, not an action:
 *
 * ```
 * ACTIVE ──stop()──▶ STOPPING ──(no new invocations accepted)──▶ DRAINING ──(in-flight == 0)──▶ DISPOSED
 * ```
 *
 *  - [ACTIVE] — invocations accepted, host capabilities served, timers scheduled.
 *  - [STOPPING] — the decision point. Every *new* invocation is rejected immediately (fast, never
 *    queued), so drain has a real deadline instead of an ever-growing backlog. Already-running JS
 *    keeps its host access so it can finish cleanly rather than half-applying a side effect.
 *  - [DRAINING] — waiting for the in-flight count to reach zero. Timers/listeners are cancelled at
 *    entry, so nothing new can arrive to refill the count.
 *  - [DISPOSED] — the runtime/context is closed and the executor shut down. Terminal.
 *
 * A drain that does not finish inside the budget is **not** silent: [drain] returns false and the
 * owner reports it as a diagnostic and refuses to dispose (leaking an engine is always better than
 * freeing memory JS is still reading).
 */
internal enum class JsLifecycleState {
    ACTIVE,
    STOPPING,
    DRAINING,
    DISPOSED,
    ;

    /** True only while new JS invocations may be started. */
    val acceptsInvocations: Boolean get() = this == ACTIVE
}

/**
 * Guard holding the [JsLifecycleState] plus two busy counters.
 *
 * [begin] is the only gate: it atomically takes a permit or refuses with the reason why. Callers
 * must pair every successful [begin] with [end] in a `finally`.
 *
 * ## Why two counters, not one
 * A JS call has two different lifetimes: the **caller's** (the thread that asked for the call and
 * waits up to its budget for it) and the **engine worker's** (the task actually sitting on the JS
 * thread). They part company exactly once: an invocation that times out is abandoned by its caller
 * while the worker is still inside `evaluate`. Counting only the caller would let [drain] return
 * true with JavaScript still executing, and the next thing after a successful drain is
 * `QuickJs.close()` — the use-after-free this whole state machine exists to prevent.
 *
 * So [beginEngineTask] brackets the worker task itself, and [drain] waits for both counters.
 * A task that is queued but not yet started is not counted, which is safe for a different reason:
 * every engine touch — including the close — runs on that same single-thread executor, so a queued
 * task is always ordered before the close task.
 */
internal class JsInvocationGuard(private val onQuiescent: (() -> Unit)? = null) {

    private val stateRef = AtomicReference(JsLifecycleState.ACTIVE)
    private val inFlight = AtomicInteger(0)
    private val engineBusy = AtomicInteger(0)

    val state: JsLifecycleState get() = stateRef.get()

    /** Number of invocations currently claimed by their callers. */
    val inFlightCount: Int get() = inFlight.get()

    /** Number of worker tasks currently on the JS thread (may exceed [inFlightCount] after timeouts). */
    val engineBusyCount: Int get() = engineBusy.get()

    /** Total work the engine has not finished with: the only quantity a drain may wait on. */
    val busyCount: Int get() = inFlight.get() + engineBusy.get()

    /**
     * Try to enter JS. Returns false once the instance is no longer [ACTIVE], in which case the
     * caller must not touch the runtime.
     *
     * Counting then re-checking the state closes the race where `stop()` runs between the two: if
     * the state moved, we undo the count and report refusal.
     *
     * [allowStopping] admits exactly one kind of caller: the script's own `onDestroy` hook, which by
     * definition runs after new work was refused. It is only safe because the caller has already
     * drained to zero in-flight invocations and cancelled the timers that could add more.
     */
    fun begin(allowStopping: Boolean = false): Boolean {
        val current = stateRef.get()
        if (!admits(current, allowStopping)) return false
        inFlight.incrementAndGet()
        val after = stateRef.get()
        if (admits(after, allowStopping)) return true
        release()
        return false
    }

    private fun admits(state: JsLifecycleState, allowStopping: Boolean): Boolean =
        state.acceptsInvocations || (allowStopping && state != JsLifecycleState.DISPOSED)

    /** Leave JS. Safe to call exactly once per successful [begin]. */
    fun end() {
        release()
    }

    /**
     * Enter the engine worker. Called by the task itself (never by a caller), so an abandoned
     * invocation keeps its engine counted until the JS thread really leaves it.
     */
    fun beginEngineTask() {
        engineBusy.incrementAndGet()
    }

    /** Leave the engine worker; pairs one [beginEngineTask]. */
    fun endEngineTask() {
        if (engineBusy.decrementAndGet() == 0 && inFlight.get() == 0) onQuiescent?.invoke()
    }

    private fun release() {
        if (inFlight.decrementAndGet() == 0 && engineBusy.get() == 0) onQuiescent?.invoke()
    }

    /**
     * ACTIVE → STOPPING. Idempotent; returns the resulting state.
     * Once this returns, no [begin] can succeed.
     */
    fun markStopping(): JsLifecycleState {
        stateRef.compareAndSet(JsLifecycleState.ACTIVE, JsLifecycleState.STOPPING)
        return stateRef.get()
    }

    /**
     * STOPPING → DRAINING, then wait up to [timeoutMillis] for callers *and* engine workers to
     * finish. Returns true when drained (safe to dispose), false while JS is still executing.
     */
    fun drain(timeoutMillis: Long): Boolean {
        stateRef.compareAndSet(JsLifecycleState.STOPPING, JsLifecycleState.DRAINING)
        stateRef.compareAndSet(JsLifecycleState.ACTIVE, JsLifecycleState.DRAINING)
        val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(0)
        while (busyCount > 0) {
            if (System.currentTimeMillis() >= deadline) return false
            try {
                Thread.sleep(5)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return busyCount == 0
            }
        }
        return true
    }

    /** DRAINING → DISPOSED. Callers must only do this after a successful [drain]. */
    fun markDisposed() {
        stateRef.set(JsLifecycleState.DISPOSED)
    }
}

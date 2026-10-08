package dev.turbodl.plugin.js

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.FunctionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * What the host tells a script about itself before it runs: which id it is registered under, where
 * its source came from, and the manifest attributes the loader was given.
 *
 * All three are plain values, which is the point: the script gets *facts about itself*, never a
 * handle on the host. `attributes` is passed through as a string map because that is what
 * [dev.turbodl.plugin.runtime.PluginSource.attributes] is — a script reading `plugin.manifest`
 * sees data, not a Kotlin object.
 */
internal data class JsScriptIdentity(
    val pluginId: String,
    val sourceUri: String,
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * What a JS plugin may ask the host to do, behind ONE dispatch surface.
 *
 * Each capability lives in its own file with its own Kotlin interface, JS API, input/output types
 * and error model, but the boundary itself exposes only [dispatch] — because that is exactly what
 * the ABI's `__turbodlCall(path, json)` needs, and one funnel is one place to audit permissions.
 */
internal interface JsHostApi {

    /**
     * Serve one host call.
     *
     * @param path capability path, e.g. `http.request`.
     * @param payloadJson JSON text of the argument object, or null for a no-argument call.
     * @return a value inside the [JsValueCodec] domain (the caller encodes the envelope).
     * @throws JsAbi.AbiException with a stable code when the call is refused or fails.
     */
    fun dispatch(path: String, payloadJson: String?): Any?

    /**
     * The `host.log` fast path from JS — plain text, no JSON round-trip. Must never throw.
     *
     * Separate from [log] on purpose: this is the JS-facing door, so it is where the LOG grant is
     * enforced, while the host's own diagnostics stay unconditional.
     */
    fun logFromJs(level: String, text: String)

    /** The host's own diagnostic line for this instance — never capability-gated. Must never throw. */
    fun log(level: String, text: String)

    /**
     * Release what this host instance owns beyond a single call (OkHttp clients, timer tasks).
     * Called from the disposer, after JS has drained and before the runtime is closed.
     */
    fun shutdown()
}

/**
 * What a JS-owned resource needs back from its plugin instance: the ability to call into JS, and
 * the knowledge of whether that is still allowed.
 *
 * Deliberately narrow. A capability implementation cannot reach the QuickJS handle, so
 * "host calls are coarse-grained and unload-safe" is a type-level guarantee rather than a habit.
 */
internal interface JsInstanceLink {
    val pluginId: String
    val jsConfig: JsPluginConfig

    /** Lifecycle state name, for diagnostics. */
    val lifecycleState: String

    /** Whether new JS invocations are still accepted (false once STOPPING begins). */
    fun acceptsInvocations(): Boolean

    /**
     * Invoke a registered JS callback with [payload] (a JSON-domain value) and return its decoded
     * result. Throws [JsAbi.AbiException] with code `gone` / `timeout` / `plugin` on refusal.
     */
    fun invokeCallback(jsCallbackId: Long, payload: Any?): Any?
}

/**
 * One JS plugin's QuickJS instance — the only place the native runtime is touched.
 *
 * ## Isolation: one instance per plugin, never shared
 * `quickjs-kt` creates a runtime *together with* its context and exposes no separate context
 * handle, so per-plugin context isolation is realized as per-plugin instances: globals, heap,
 * limits and interrupts cannot cross between scripts. The cost is a small native allocation per
 * plugin, which for a downloader's handful of plugins is the right trade against a shared-context
 * blast radius.
 *
 * ## Threading: one dedicated thread owns the engine
 * QuickJS is not safe for concurrent evaluation, and binding callbacks fire on whichever thread is
 * evaluating. A single-thread executor per plugin gives (a) plugins never block each other,
 * (b) close() cannot race an evaluation, (c) a thread name that makes a stuck plugin legible in a
 * thread dump.
 *
 * Re-entrancy is refused explicitly: a host capability that called back into JS *while* its own
 * binding callback was running would submit onto the very thread it is blocking — a deadlock. The
 * owner thread is therefore recorded and [submit] fails fast with `unsupported` instead.
 *
 * ## Timeout / interrupt: two bounds, engine first
 *  1. `evaluationTimeoutMillis` — the engine's own cap, counting *JavaScript* time only;
 *  2. a watchdog that calls [QuickJs.interruptEvaluation] from another thread once an invocation's
 *     total budget is spent (JS time + host time, queue wait included).
 * The deadline is fixed at submit, but the watchdog is armed by the worker task itself, and only
 * once that task owns the engine — so a queued invocation can never interrupt the invocation
 * currently holding the engine, and a caller that times out only abandons its own future.
 * Both bounds are verified in this repo to kill `while(true){}` while leaving the instance usable
 * afterwards, so a runaway invocation costs a call, not the plugin.
 *
 * ## Close discipline
 * [close] refuses to run while an invocation is in flight. That refusal is the point: it turns
 * "remember to drain before close" from a convention into an enforced precondition, which is what
 * makes a concurrent unload + invocation safe rather than merely unlikely.
 */
internal class JsRuntime(
    override val pluginId: String,
    override val jsConfig: JsPluginConfig,
    private val hostProvider: () -> JsHostApi,
    private val registrar: JsRegistrar,
) : JsInstanceLink, JsCallbackInvoker, AutoCloseable {

    /** Alias so capability-facing code reads naturally. */
    val config: JsPluginConfig get() = jsConfig

    /** What JS may register with the host; implemented by the extension bridge. */
    fun interface JsRegistrar {
        /** @return the data handed back to the script (its registration handle). */
        fun register(kind: String, jsCallbackId: Long, optionsJson: String?): Any?
    }

    private val factory: ThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, "turbo-js-$pluginId").apply { isDaemon = true }
    }

    /** Owns the native runtime: every evaluation, and the close, happen on this single thread. */
    private val jsExecutor = Executors.newSingleThreadExecutor(factory)

    /** The thread currently owning the engine; set by each task so re-entrancy is detectable. */
    @Volatile
    private var ownerThread: Thread? = null

    @Volatile
    private var engine: QuickJs? = null

    /** Watchdog pool used to interrupt overruns from another thread; assigned by the manager. */
    var watchdog: ScheduledExecutorService? = null

    private val guard = JsInvocationGuard()
    private val registrationsSeen = AtomicLong(0)

    override val lifecycleState: String get() = guard.state.name
    override fun acceptsInvocations(): Boolean = guard.state.acceptsInvocations
    val inFlight: Int get() = guard.inFlightCount

    /**
     * Worker tasks still holding the engine, including those whose caller has already given up on a
     * timeout. This — not [inFlight] — is what makes a drain honest.
     */
    val engineBusy: Int get() = guard.engineBusyCount
    val busyCount: Int get() = guard.busyCount
    val isAlive: Boolean get() = engine?.let { !it.isClosed } == true

    // ------------------------------------------------------------------ start

    /**
     * Create the runtime, apply the resource ceilings, install the ABI primitives, then evaluate
     * the shim followed by the plugin source.
     *
     * [identity] is seeded into the shim's `plugin` object *after* the ABI loads but *before* the
     * script runs, because the script's registration calls must already see their own plugin id and
     * manifest — a parser that logs `host.log.info(plugin.id)` should not need to know the loader
     * passed it separately.
     *
     * A failure here is a load failure: nothing has been registered and no invocation handed out,
     * so the caller may [close] immediately and safely.
     */
    fun start(scriptSource: String, scriptName: String, identity: JsScriptIdentity) {
        val q = try {
            QuickJs.create(jobDispatcher = Dispatchers.IO)
        } catch (t: Throwable) {
            throw JsAbi.AbiException(JsAbi.Code.INTERNAL, "failed to create a QuickJS runtime for '$pluginId': ${t.message}", t)
        }
        engine = q
        // Ceilings before a single line of plugin code runs: an unbounded first statement is the
        // one a hostile script uses to bypass every later check.
        try {
            q.memoryLimit = config.memoryLimitBytes
            q.maxStackSize = config.maxStackSizeBytes
            q.evaluationTimeoutMillis = config.evaluationTimeoutMillis
        } catch (t: Throwable) {
            runCatching { q.close() }
            throw JsAbi.AbiException(JsAbi.Code.INTERNAL, "failed to configure the QuickJS runtime of '$pluginId': ${t.message}", t)
        }
        installBindings(q)
        evaluateRaw(q, JsAbi.shimSource(), "turbodl-abi.js", config.evaluationTimeoutMillis)
        // `plugin.id` / `plugin.manifest` are plain data — never a Kotlin object graph.
        evaluateRaw(
            q,
            "plugin.id = ${JsJson.encodeString(identity.pluginId)};" +
                "plugin.sourceUri = ${JsJson.encodeString(identity.sourceUri)};" +
                "plugin.manifest = ${JsValueCodec.encodeForJs(identity.attributes)};",
            "turbodl-identity.js",
            config.evaluationTimeoutMillis,
        )
        evaluateRaw(q, scriptSource, scriptName, config.evaluationTimeoutMillis)
    }

    private fun installBindings(q: QuickJs) {
        // JS -> host capability. Coarse-grained by construction: one call, one complete result —
        // no handles, no streams, no per-chunk callbacks.
        q.defineBinding(
            "__turbodlCall",
            FunctionBinding { args ->
                val path = args.getOrNull(0) as? String
                    ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "__turbodlCall requires a path")
                val payload = args.getOrNull(1) as? String
                try {
                    JsAbi.ok(hostProvider().dispatch(path, payload))
                } catch (t: Throwable) {
                    JsAbi.envelopeForFailure(t)
                }
            },
        )
        // JS -> extension registration (parser / hooks / event listener).
        q.defineBinding(
            "__turbodlRegister",
            FunctionBinding { args ->
                val kind = args.getOrNull(0) as? String
                    ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "__turbodlRegister requires a kind")
                val cbId = (args.getOrNull(1) as? Number)?.toLong()
                    ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "__turbodlRegister requires a callback id")
                val options = args.getOrNull(2) as? String
                registrationsSeen.incrementAndGet()
                try {
                    JsAbi.ok(registrar.register(kind, cbId, options))
                } catch (t: Throwable) {
                    JsAbi.envelopeForFailure(t)
                }
            },
        )
        // Log fast path: plain text, and it must never break a plugin whatever the sink does.
        // `logFromJs` is the capability-checked door; the binding stays wrapped so even a host-side
        // surprise cannot turn a log line into a plugin failure.
        q.defineBinding(
            "__turbodlLog",
            FunctionBinding { args ->
                runCatching {
                    hostProvider().logFromJs(args.getOrNull(0) as? String ?: "info", args.getOrNull(1) as? String ?: "")
                }
                true
            },
        )
    }

    // ------------------------------------------------------------------ evaluation core

    private fun evaluateRaw(q: QuickJs, code: String, filename: String, budgetMillis: Long): Any? =
        submit(filename, budgetMillis) { engine -> engine.evaluate<Any?>(code, filename, false) }

    private fun <T> submit(label: String, budgetMillis: Long, block: suspend (QuickJs) -> T): T {
        val q = requireAlive()
        // Re-entrancy guard: the owner thread is already inside `block`, so a submit from there
        // would queue behind itself and hang. Host capabilities must never call JS synchronously.
        if (ownerThread != null && Thread.currentThread() === ownerThread) {
            throw JsAbi.AbiException(
                JsAbi.Code.UNSUPPORTED,
                "JS invocation '$label' attempted to re-enter the runtime from inside a host callback",
            )
        }
        if (jsExecutor.isShutdown) {
            throw JsAbi.AbiException(JsAbi.Code.GONE, "the JS runtime of '$pluginId' is disposed")
        }
        // The budget starts here — time queued behind an earlier invocation counts against it — but
        // the right to interrupt belongs to the task, and only from the moment the task actually
        // owns the engine. A watchdog armed at submit time would fire while this task still sits in
        // the queue and could kill a *different* invocation that legitimately holds the engine; a
        // caller-side timeout interrupt has the same defect. So the deadline is fixed here, and the
        // task arms its own watchdog for whatever budget remains when it really starts.
        val budget = budgetMillis.coerceAtLeast(1)
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget)
        val future = jsExecutor.submit(Callable {
            // The engine is busy for exactly as long as this task runs — independent of whether the
            // caller is still waiting. A timed-out invocation is abandoned by its caller but keeps
            // the worker counted until the JS thread really returns, so a later drain cannot declare
            // the instance quiescent while JS is still executing (that close() would be UAF).
            guard.beginEngineTask()
            ownerThread = Thread.currentThread()
            var interrupt: ScheduledFuture<*>? = null
            try {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) {
                    // The budget was exhausted while this task sat in the queue: fail without
                    // touching QuickJS. Evaluating now would spend someone else's engine; firing
                    // interruptEvaluation now would hit whatever invocation actually holds it.
                    throw JsAbi.AbiException(
                        JsAbi.Code.TIMEOUT,
                        "JS invocation '$label' exhausted its ${budget}ms budget while queued and never started",
                    )
                }
                val timer = watchdog
                if (timer != null) {
                    // Outer bound owned by THIS task: interrupt from another thread once this
                    // task's remaining budget is spent. QuickJS honors it even inside busy JS.
                    interrupt = timer.schedule(
                        { runCatching { q.interruptEvaluation() } },
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1),
                        TimeUnit.MILLISECONDS,
                    )
                }
                runBlocking { block(q) }
            } finally {
                interrupt?.cancel(false)
                ownerThread = null
                guard.endEngineTask()
            }
        })
        try {
            val waitNanos = (deadlineNanos - System.nanoTime()).coerceAtLeast(0)
            return future.get(TimeUnit.NANOSECONDS.toMillis(waitNanos) + ENGINE_GRACE_MILLIS, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            // The caller gave up; the engine belongs to the task that is actually running — possibly
            // a queued one that has yet to start and whose own deadline check will refuse it. This
            // path must therefore never call interruptEvaluation(): the task-armed watchdog (or the
            // engine's own evaluation timeout) is what stops runaway work.
            future.cancel(true)
            throw JsAbi.AbiException(JsAbi.Code.TIMEOUT, "JS invocation '$label' exceeded its ${budget}ms budget", e)
        } catch (e: ExecutionException) {
            throw unwrap(e.cause ?: e, label)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            throw JsAbi.AbiException(JsAbi.Code.INTERRUPTED, "JS invocation '$label' was interrupted", e)
        }
    }

    /** Normalize whatever the engine or JS threw into one ABI-coded failure. */
    private fun unwrap(t: Throwable, label: String): JsAbi.AbiException = when (t) {
        is JsAbi.AbiException -> t
        is QuickJsInterruptedException ->
            JsAbi.AbiException(JsAbi.Code.TIMEOUT, "JS evaluation '$label' was interrupted (timeout)", t)
        // A plugin's own throw is a plugin fault, never an engine fault — except heap exhaustion,
        // which the engine reports as a bare, message-less exception (see [engineFault]).
        is QuickJsException -> engineFault(t, label)
        is JsPermissionException -> JsAbi.AbiException(JsAbi.Code.PERMISSION, t.message ?: "permission denied", t)
        is JsIoLimits.JsLimitException -> JsAbi.AbiException(JsAbi.Code.LIMIT, t.message ?: "byte limit exceeded", t)
        else -> JsAbi.AbiException(JsAbi.Code.INTERNAL, "JS invocation '$label' failed: ${firstLine(t.message ?: t.javaClass.simpleName)}", t)
    }

    /**
     * Turn an engine exception into a coded, *readable* failure.
     *
     * Heap exhaustion is the one engine fault a plugin cannot describe: quickjs-kt raises it as a
     * `QuickJsException` whose message is the literal text `null` and whose stack and file name are
     * null, and every *later* evaluation on that context then fails with
     * `InternalError: <NO_MESSAGE>` (both verified against the bundled native library). So the fault
     * is identified from the engine's own accounting rather than from its message — checked first,
     * because a plugin error thrown while sitting at the ceiling is overwhelmingly likely to *be* the
     * ceiling — and reported with the code (`limit`) that tells a host which knob to raise.
     */
    private fun engineFault(t: QuickJsException, label: String): JsAbi.AbiException {
        val usage = runCatching { requireAlive().memoryUsage.mallocSize }.getOrDefault(-1L)
        val limit = config.memoryLimitBytes
        val atCeiling = usage >= 0 && usage >= limit - (limit / 20)
        if (atCeiling) {
            return JsAbi.AbiException(
                JsAbi.Code.LIMIT,
                "JS error in '$label': this plugin's heap is at ${usage}B of its ${limit}B " +
                    "memoryLimitBytes, so the ceiling was reached (raise memoryLimitBytes, or catch " +
                    "the allocation failure in JS — an escaped heap breach wedges the context)",
                t,
            )
        }
        val text = usefulMessage(t)
        return JsAbi.AbiException(
            JsAbi.Code.PLUGIN,
            if (text.isNotBlank()) "JS error in '$label': $text"
            else "JS error in '$label': the engine reported no message (heap ${usage}B of ${limit}B)",
            t,
        )
    }

    /** The engine's own description, minus the placeholders it emits for a missing message. */
    private fun usefulMessage(t: QuickJsException): String {
        val candidates = listOf(t.message, t.stack, listOfNotNull(t.fileName, t.lineNumber?.toString()).joinToString(":"))
        return candidates.asSequence()
            .map { firstLine(it) }
            .firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) && "<NO_MESSAGE>" !in it.uppercase() }
            ?: ""
    }

    private fun firstLine(text: String?): String {
        if (text == null) return ""
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        return line.take(400)
    }

    // ------------------------------------------------------------------ invocation API

    /**
     * Run [body] as one host→JS invocation (extension callback, lifecycle hook, timer).
     *
     * Refuses immediately when the instance is not ACTIVE — that is what lets a drain finish, and
     * what gives a consumer racing an unload a clean `gone` instead of a hang.
     *
     * [allowStopping] is only for the script's own `onDestroy`, which by contract runs after the
     * instance stopped accepting new work and after in-flight invocations drained.
     */
    fun <T> invocation(label: String, allowStopping: Boolean = false, body: (QuickJs) -> T): T {
        if (!guard.begin(allowStopping)) {
            throw JsAbi.AbiException(
                JsAbi.Code.GONE,
                "JS plugin '$pluginId' is ${guard.state.name.lowercase()} and no longer accepts invocations ('$label')",
            )
        }
        try {
            return body(requireAlive())
        } finally {
            guard.end()
        }
    }

    private fun requireAlive(): QuickJs =
        engine?.takeIf { !it.isClosed }
            ?: throw JsAbi.AbiException(JsAbi.Code.GONE, "the JS runtime of '$pluginId' is disposed")

    /** Evaluate a JS expression, returning the raw engine value (test/diagnostic surface). */
    fun evaluateAny(code: String, filename: String = "eval.js", budgetMillis: Long = config.invocationTimeoutMillis): Any? =
        invocation(filename) { q -> evaluateRaw(q, code, filename, budgetMillis) }

    /** Evaluate a JS expression whose result must be text (the ABI's envelope returns). */
    private fun evaluateAsText(q: QuickJs, code: String, filename: String, budgetMillis: Long): String =
        evaluateRaw(q, code, filename, budgetMillis).let { raw ->
            raw as? String
                ?: throw JsAbi.AbiException(JsAbi.Code.PLUGIN, "ABI step '$filename' returned a non-text result")
        }

    /** Run the ABI's seal step: executes `onInit`, reports the declared registration surface. */
    fun seal(budgetMillis: Long = config.invocationTimeoutMillis): JsSealReport =
        invocation("seal") { q ->
            JsSealReport.parse(evaluateAsText(q, "__turboSeal()", "abi-seal.js", budgetMillis))
        }

    /** Ask the script for its `plugin.defineMeta` values (name/version), read as data. */
    fun readStringGlobal(name: String): String? =
        runCatching { evaluateAny("typeof $name === 'string' ? $name : null", "read-$name.js") as? String }.getOrNull()

    /**
     * Run the script's `onDestroy` hook, if it declared one.
     *
     * This is the only invocation allowed after STOPPING: the hook exists precisely to let a script
     * release what the host cannot (its own closures, an external session it opened through
     * `host.http`). It is called once, after in-flight invocations drained and timers were
     * cancelled, so it cannot race normal plugin work.
     *
     * A throwing hook is reported but never blocks disposal — otherwise a broken script could make
     * unload fail and leak the engine.
     *
     * @return null when there was no hook, otherwise the failure message.
     */
    fun invokeDestroy(callbackId: Long): String? {
        val outcome = runCatching {
            invocation("onDestroy", allowStopping = true) { q ->
                // Decoded, not just evaluated: the ABI reports a *plugin* throw inside the returned
                // envelope, so reading the text alone would make a failing onDestroy look like a
                // successful one.
                JsAbi.decodeCallbackResult(evaluateAsText(q, "__turboDispatchCallback($callbackId, null)", "abi-destroy.js", config.invocationTimeoutMillis))
            }
        }
        val t = outcome.exceptionOrNull() ?: return null
        return firstLine(t.message ?: t.javaClass.simpleName)
    }

    /** Release every JS callback the script registered, then drop the host-side view of them. */
    fun releaseCallbacks(callbackIds: List<Long>) {
        if (callbackIds.isEmpty()) return
        val code = buildString {
            append("(function(){var ids=[")
            callbackIds.joinTo(this, ",")
            append("];for(var i=0;i<ids.length;i++){try{__turboReleaseCallback(ids[i]);}catch(e){}}return ids.length;})()")
        }
        // Best effort: once STOPPING, invocations are refused and JS state goes away with the
        // context anyway, so a refusal here is normal, not an error.
        runCatching { invocation("releaseCallbacks") { q -> evaluateRaw(q, code, "abi-release.js", config.invocationTimeoutMillis) } }
    }

    // ------------------------------------------------------------------ JsCallbackInvoker

    /**
     * Invoke a JS callback registered by the script and return the **envelope text** it produced.
     * A *plugin* failure is inside that envelope; an *engine* failure throws.
     */
    override fun dispatch(jsCallbackId: Long, payloadJson: String?): String {
        val code = StringBuilder(64 + (payloadJson?.length ?: 4))
            .append("__turboDispatchCallback(")
            .append(jsCallbackId)
            .append(", ")
            .append(if (payloadJson == null) "null" else JsJson.encodeString(payloadJson))
            .append(')')
            .toString()
        return invocation("callback:$jsCallbackId") { q ->
            evaluateAsText(q, code, "abi-dispatch.js", config.invocationTimeoutMillis)
        }
    }

    override fun release(jsCallbackId: Long) {
        // Once stopping, JS state is discarded wholesale; a release call would only be refused.
        if (!acceptsInvocations()) return
        runCatching {
            invocation("release:$jsCallbackId") { q ->
                evaluateRaw(q, "__turboReleaseCallback($jsCallbackId)", "abi-release.js", config.invocationTimeoutMillis)
            }
        }
    }

    override fun invokeCallback(jsCallbackId: Long, payload: Any?): Any? {
        val encoded = if (payload == null) null else JsJson.encode(JsValueCodec.requireValid(payload, "callback payload"))
        return JsAbi.decodeCallbackResult(dispatch(jsCallbackId, encoded))
    }

    // ------------------------------------------------------------------ unload

    /** ACTIVE → STOPPING: no further invocations accepted. Idempotent; returns the new state. */
    fun markStopping(): JsLifecycleState {
        val state = guard.markStopping()
        // A stuck evaluation must not hold unload hostage: ask the engine to stop mid-JS.
        if (!state.acceptsInvocations) runCatching { engine?.interruptEvaluation() }
        return state
    }

    /** @return false when JS is still executing, in which case disposal is forbidden. */
    fun drainInFlight(timeoutMillis: Long): Boolean = guard.drain(timeoutMillis)

    /** How many registrations the script made (diagnostics). */
    fun registrationCount(): Long = registrationsSeen.get()

    /** Live JS memory, for diagnostics. Returns -1 when the engine is already gone. */
    fun memoryUsed(): Long = runCatching { requireAlive().memoryUsage.mallocSize }.getOrDefault(-1L)

    /**
     * Dispose the engine, then its owning thread.
     *
     * Refuses to run while an invocation is in flight: that precondition is what makes a
     * concurrent invoke + unload safe instead of merely unlikely.
     */
    override fun close() {
        if (guard.state != JsLifecycleState.DISPOSED && !guard.drain(0)) {
            throw IllegalStateException(
                "refusing to close the JS runtime of '$pluginId' with ${guard.busyCount} unit(s) in flight " +
                    "(${guard.inFlightCount} caller(s), ${guard.engineBusyCount} engine worker task(s)); " +
                    "markStopping() + drainInFlight() must complete first",
            )
        }
        guard.markDisposed()
        val q = engine
        engine = null
        if (q != null && !q.isClosed) {
            // Close on the owner thread: QuickJS guards close-vs-callback on that thread's state.
            runCatching {
                if (!jsExecutor.isShutdown) {
                    jsExecutor.submit(Callable {
                        ownerThread = Thread.currentThread()
                        runCatching { q.close() }
                    }).get(CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                } else {
                    runCatching { q.close() }
                }
            }
        }
        jsExecutor.shutdownNow()
    }

    private companion object {
        /** Lets the engine's own timeout and the task-armed watchdog fire first, so the plugin sees a clean interrupt. */
        const val ENGINE_GRACE_MILLIS = 1_000L
        const val CLOSE_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * The script's declared surface, as reported by the ABI after `onInit`.
 *
 * Strict parsing on purpose: a malformed seal means the shim did not run (a truncated or hostile
 * script), and that must fail the load rather than yield a plugin that silently has no
 * capabilities.
 */
internal data class JsSealReport(
    val abiMajor: Long,
    val abiVersion: String,
    val registrations: List<Declared>,
    val destroyCallbackId: Long?,
    val errors: List<String>,
    /** What the script asked for via `plugin.requires`. */
    val requiredAbiMajor: Long,
    val requestedPermissions: List<String>,
    /** `plugin.defineMeta` output, as plain data. */
    val meta: Map<String, Any?>,
) {
    data class Declared(val kind: String, val handle: Any?)

    val abiCompatible: Boolean get() = abiMajor == JsAbi.ABI_MAJOR.toLong() && requiredAbiMajor == JsAbi.ABI_MAJOR.toLong()

    val declaresDestroy: Boolean get() = destroyCallbackId != null

    companion object {
        fun parse(text: String): JsSealReport {
            val decoded = try {
                JsJson.decode(text)
            } catch (t: Throwable) {
                throw JsAbi.AbiException(JsAbi.Code.PLUGIN, "the ABI seal reported malformed data: ${t.message}", t)
            }
            val map = decoded as? Map<*, *>
                ?: throw JsAbi.AbiException(JsAbi.Code.PLUGIN, "the ABI seal did not report an object")
            val declared = (map["registrations"] as? List<*>).orEmpty().mapNotNull { entry ->
                val m = entry as? Map<*, *> ?: return@mapNotNull null
                val kind = m["kind"] as? String ?: return@mapNotNull null
                Declared(kind, m["handle"])
            }
            val requires = map["requires"] as? Map<*, *>
            @Suppress("UNCHECKED_CAST")
            return JsSealReport(
                abiMajor = (map["abiMajor"] as? Number)?.toLong() ?: -1L,
                abiVersion = map["abiVersion"] as? String ?: "?",
                registrations = declared,
                destroyCallbackId = (map["destroyCb"] as? Number)?.toLong(),
                errors = (map["errors"] as? List<*>).orEmpty().mapNotNull { it as? String },
                requiredAbiMajor = (requires?.get("abiMajor") as? Number)?.toLong() ?: JsAbi.ABI_MAJOR.toLong(),
                requestedPermissions = (requires?.get("permissions") as? List<*>).orEmpty().mapNotNull { it as? String },
                meta = (map["meta"] as? Map<*, *>)?.let { source ->
                    buildMap(source.size) { for (e in source.entries) put(e.key?.toString() ?: "", e.value) }
                } ?: emptyMap(),
            )
        }
    }
}

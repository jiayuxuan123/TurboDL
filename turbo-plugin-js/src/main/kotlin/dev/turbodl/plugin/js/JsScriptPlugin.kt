package dev.turbodl.plugin.js

import dev.turbodl.plugin.runtime.Plugin
import dev.turbodl.plugin.runtime.PluginContext
import java.util.concurrent.atomic.AtomicReference

/**
 * One loaded JS script, seen as a [Plugin].
 *
 * This is the adapter that makes "a JS file" a first-class TurboDL plugin: it has an id, runs the
 * ordinary `onLoad`/`onUnload` lifecycle, and its registrations land in the normal extension
 * registry. Everything JS-specific sits behind it — a consumer of `LINK_PARSER` cannot tell a JS
 * parser from a Kotlin one, which is what "peer of turbo-plugin-hls" means in practice.
 *
 * ## Lifecycle
 * ```
 * onLoad  : sandbox → host → bridge → runtime → evaluate(shim → identity → script) → seal()
 *           → ABI / capability handshake → track
 * dispose : STOPPING (refuse new invocations, interrupt stuck JS) → neutralize registrations
 *           → cancel timers + close OkHttp → drain in-flight → onDestroy → release callbacks
 *           → close runtime (dispose context, then thread) → disk cleanup → DISPOSED
 * ```
 *
 * ## Failure posture
 * A throw out of `onLoad` is the host's rollback signal: whatever the script registered before
 * failing is removed by the disposer chain the host drains, and the runtime is closed here so a
 * half-loaded script cannot leave a native runtime or an HTTP client behind. [dispose] never throws.
 *
 * A drain that does not finish inside [JsPluginConfig.drainTimeoutMillis] **refuses** to close the
 * engine. Leaking one QuickJS runtime is recoverable; freeing memory that JavaScript is still reading
 * is not. Refusals are counted by [JsPluginManager.leakedCount] so a host can see them.
 */
class JsScriptPlugin internal constructor(
    override val id: String,
    override val name: String,
    private val sourceUri: String,
    private val scriptSource: String,
    private val scriptName: String,
    private val manager: JsPluginManager,
    private val attributes: Map<String, String>,
    /** Teardown diagnostics; supplied by the loader, because `context.log` is gone by then. */
    private val logSink: (String, Throwable?) -> Unit,
    /**
     * This instance's policy: the loader config **narrowed** by what the source asked for. Never
     * widened — [JsPluginLoaderPlugin] intersects, so a source listing `http,storage` on a loader
     * that grants `http,crypto` ends up with `http` only.
     */
    private val config: JsPluginConfig,
    /** Loader-wide priority for registrations that state none; null → the bridge's convention value. */
    private val defaultExtensionPriority: Int?,
) : Plugin {

    private val runtimeRef = AtomicReference<JsRuntime?>()
    private val hostRef = AtomicReference<JsHost?>()
    private val bridgeRef = AtomicReference<JsExtensionBridge?>()
    private val sandboxRef = AtomicReference<JsSandbox?>()
    private val sealRef = AtomicReference<JsSealReport?>()

    /** Set once, from the seal report: the script's `onDestroy` callback, if it declared one. */
    @Volatile
    private var destroyCallbackId: Long? = null

    @Volatile
    private var disposed = false

    /** `ACTIVE` / `STOPPING` / `DRAINING` / `DISPOSED`, or `UNSTARTED` before `onLoad` finished. */
    val lifecycleState: String
        get() = runtimeRef.get()?.lifecycleState
            ?: if (disposed) JsLifecycleState.DISPOSED.name else "UNSTARTED"

    /** What the script registered, as `kind#handle@priority` text — the JS diagnostics snapshot. */
    fun registrations(): List<String> = bridgeRef.get()?.describe() ?: emptyList()

    fun abiVersion(): String? = sealRef.get()?.abiVersion

    /** Live JS heap in bytes, or -1 when the instance is gone. */
    fun memoryUsed(): Long = runtimeRef.get()?.memoryUsed() ?: -1L

    /** In-flight JS invocations right now; a host can use this to decide when it is safe to unload. */
    fun inFlight(): Int = runtimeRef.get()?.inFlight ?: 0

    /**
     * Engine worker tasks still executing JS, including those whose caller already timed out. This is
     * what a drain actually waits on (together with [inFlight]), so a host diagnosing a refused
     * unload needs to see it separately.
     */
    fun engineBusy(): Int = runtimeRef.get()?.engineBusy ?: 0

    /** Host capability ids this instance may use — the intersection, as JS sees it. */
    fun grantedPermissions(): Set<JsCapability> = config.permissions

    override fun onLoad(context: PluginContext) {
        val sandbox = manager.newSandbox(id, config)
        sandboxRef.set(sandbox)

        // Runtime ⇄ host are mutually referential (a timer must reach JS, JS must reach capabilities)
        // and JS can call a capability *during* script evaluation, so both are installed through
        // atomic holders before the first line of plugin code runs.
        val host = JsHost(
            pluginId = id,
            config = config,
            engineConfig = manager.engineConfig,
            sandbox = sandbox,
            sink = { level, text -> context.log("[$level] $text", null) },
            linkProvider = { runtimeRef.get() },
            scheduler = manager.timerScheduler(),
        )
        hostRef.set(host)

        val runtime = JsRuntime(
            pluginId = id,
            jsConfig = config,
            hostProvider = { host },
            registrar = JsRuntime.JsRegistrar { kind, jsCallbackId, optionsJson ->
                val bridge = bridgeRef.get()
                    ?: throw JsAbi.AbiException(
                        JsAbi.Code.INTERNAL,
                        "a '$kind' registration arrived before the extension bridge was ready",
                    )
                bridge.install(kind, jsCallbackId, optionsJson)
            },
        )
        // The watchdog pool never runs plugin work, so an interrupt cannot queue behind the JS it
        // has to stop (timer callbacks are scheduled on the *other* pool).
        runtime.watchdog = manager.watchdogScheduler()
        runtimeRef.set(runtime)

        try {
            val bridge = JsExtensionBridge(
                pluginId = id,
                context = context,
                sandbox = sandbox,
                linkProvider = { runtime },
                loaderDefaultPriority = defaultExtensionPriority,
            )
            bridgeRef.set(bridge)

            runtime.start(scriptSource, scriptName, JsScriptIdentity(id, sourceUri, attributes))
            val report = runtime.seal()
            sealRef.set(report)
            handshake(report, context)
            destroyCallbackId = report.destroyCallbackId

            manager.track(this)
            context.log(
                "loaded '$scriptName' from '${describeSourceUri(sourceUri)}' (ABI ${report.abiVersion}, " +
                    "${bridge.activeCount()} registration(s), grants: ${config.permissions.names()})",
                null,
            )
        } catch (t: Throwable) {
            // A failed load must leave no native runtime, HTTP client, or plugin directory behind.
            runCatching { host.shutdown() }
            runCatching { runtime.close() }
            runtimeRef.set(null)
            hostRef.set(null)
            bridgeRef.set(null)
            runCatching { sandbox.deleteRecursively() }
            throw t
        }
    }

    /**
     * The handshake, run against the seal report — the last moment at which nothing is yet visible to
     * consumers, so a mismatch costs one load rather than leaving a half-registered plugin routed.
     *
     * Three checks, each with a distinct code so an author knows what to change:
     *  1. **ABI major** — declared by the script (`plugin.requires`) *and* reported by the shim; a
     *     mismatch means the registrations about to be accepted have no defined meaning
     *     (`unsupported`).
     *  2. **onInit** — the script's own init threw, so its declared surface is incomplete (`plugin`:
     *     the author's bug, not the host's).
     *  3. **Capabilities** — a script may *ask* for more than the loader grants; asking never works,
     *     and failing here is better than failing mid-download on a denied call (`permission`).
     */
    private fun handshake(report: JsSealReport, context: PluginContext) {
        if (!report.abiCompatible) {
            throw JsAbi.AbiException(
                JsAbi.Code.UNSUPPORTED,
                "'$id' needs ABI major ${report.requiredAbiMajor} but its shim reports ${report.abiMajor}; " +
                    "this loader implements major ${JsAbi.ABI_MAJOR} (ABI ${JsAbi.ABI_VERSION})",
            )
        }
        if (report.errors.isNotEmpty()) {
            throw JsAbi.AbiException(JsAbi.Code.PLUGIN, "'$id' failed in onInit: ${report.errors.joinToString("; ")}")
        }
        val unmet = report.requestedPermissions.mapNotNull { name ->
            val capability = JsCapability.entries.firstOrNull { it.jsName == name.lowercase() }
            when {
                capability == null -> "unknown capability '$name'"
                capability !in config.permissions -> "'${capability.jsName}'"
                else -> null
            }
        }
        if (unmet.isNotEmpty()) {
            throw JsAbi.AbiException(
                JsAbi.Code.PERMISSION,
                "'$id' needs ${unmet.joinToString(", ")}, which this loader does not grant (granted: " +
                    config.permissions.names().ifEmpty { "none" } + ")",
            )
        }
    }

    /**
     * The unload sequence, in order. Idempotent and non-throwing.
     *
     * @param report diagnostics sink — [PluginContext.log] is no longer usable by the time the host
     *   reaches teardown, so the loader supplies the sink.
     */
    fun dispose(report: (String, Throwable?) -> Unit = logSink) {
        if (disposed) return
        disposed = true
        val runtime = runtimeRef.get()
        val host = hostRef.get()
        val bridge = bridgeRef.get()
        val sandbox = sandboxRef.get()

        // 1. STOPPING: refuse new invocations, and interrupt anything stuck inside JS.
        runCatching { runtime?.markStopping() }.onFailure { report("markStopping() failed for '$id'", it) }

        // 2. Neutralize registrations: a consumer racing the unload gets a clean no-op (parser → null,
        //    hook → input unchanged) instead of queueing onto an instance that is leaving.
        val callbackIds = runCatching { bridge?.deactivateAll() }.getOrNull() ?: emptyList()

        // 3. Cancel timers and release the HTTP client BEFORE draining — otherwise a timer could add
        //    in-flight work during the drain and the deadline would move every time we looked.
        runCatching { host?.shutdown() }.onFailure { report("host shutdown of '$id' failed", it) }

        // 4. Drain. This waits on callers *and* engine worker tasks: an invocation that timed out has
        //    no caller any more but its JS is still executing, and closing under that is the
        //    use-after-free the whole state machine exists to prevent.
        val drained = runtime?.drainInFlight(config.drainTimeoutMillis) ?: true
        if (!drained) {
            report(
                "JS plugin '$id' still had ${runtime?.busyCount ?: 0} unit(s) of engine work outstanding " +
                    "(${runtime?.inFlight ?: 0} caller(s), ${runtime?.engineBusy ?: 0} worker task(s)) after " +
                    "${config.drainTimeoutMillis}ms, so its QuickJS runtime was NOT closed — freeing memory " +
                    "JS is still reading is worse than leaking it. The thread and heap stay allocated until " +
                    "the process exits; look for an unbounded loop or a long host.time.sleep.",
                null,
            )
            manager.recordLeak()
        } else {
            // 5. The script's own cleanup — the only invocation admitted once STOPPING.
            destroyCallbackId?.let { cb ->
                runtime?.invokeDestroy(cb)?.let { msg -> report("onDestroy of '$id' failed: $msg", null) }
            }
            // 6. Drop the JS side of every callback we handed out.
            runtime?.releaseCallbacks(callbackIds)
        }

        // 7. Dispose the context, then its thread. [JsRuntime.close] re-checks the drain itself, so an
        //    instance whose drain failed cannot be freed here either.
        val closeFailure = runCatching { runtime?.close() }.exceptionOrNull()
        if (closeFailure != null) report("closing the JS runtime of '$id' was refused: ${closeFailure.message}", null)
        runtimeRef.set(null)
        hostRef.set(null)
        bridgeRef.set(null)

        // 8. Disk. Downloads are scratch and always go; `host.storage` is the plugin's own state and
        //    only goes when the host asked for a full purge (so a reinstall finds its session token).
        runCatching { sandbox?.clearDownloads() }.onFailure { report("cleaning downloads of '$id' failed", it) }
        if (manager.purgeSandboxOnUnload) {
            runCatching { sandbox?.deleteRecursively() }.onFailure { report("purging the sandbox of '$id' failed", it) }
        }

        manager.untrack(id)
        report("JS plugin '$id' unloaded (${if (drained) JsLifecycleState.DISPOSED.name else "RUNTIME LEAKED"})", null)
    }

    /**
     * `Plugin.onUnload` — the host's teardown entry point.
     *
     * The disposer [onLoad] wired (extension registrations, event subscription) is drained *after*
     * this returns, which is why the JS-side teardown happens here and the disposer is the backstop
     * rather than the mechanism.
     */
    override fun onUnload() = dispose(logSink)

    /**
     * Where this script came from — the `PluginSource.uri` the host passed to `loadSource`.
     *
     * Exposed because a host that manages plugins (an app with an "installed plugins" screen, a
     * marketplace, a diagnostics page) has to be able to say *which file* a running plugin is, and
     * until now that string was private to this class. Reading it is harmless: the value is what the
     * host already handed in.
     */
    val source: String get() = sourceUri

    /** The script's display name (its file name, or the name the source declared). */
    val scriptFileName: String get() = scriptName

    /**
     * Metadata a plugin manager needs to list this plugin: id, name, source, state and what it
     * registered. Deliberately **not** a `Map<String, Any?>` like [diagnostics] — a manager renders
     * these fields, and a typed snapshot cannot silently lose a key when someone edits a map literal.
     */
    data class Info(
        val id: String,
        val name: String,
        val source: String,
        val scriptFileName: String,
        /** `UNSTARTED` / `ACTIVE` / `STOPPING` / `DRAINING` / `DISPOSED`. */
        val state: String,
        /** ABI version the script sealed against, or null when it never got that far. */
        val abiVersion: String?,
        val registrations: List<String>,
        val grantedPermissions: List<String>,
        val memoryUsedBytes: Long,
        val inFlight: Int,
    )

    /** A typed snapshot for host-side plugin management. See [Info] for why it is not a map. */
    fun info(): Info = Info(
        id = id,
        name = name,
        source = sourceUri,
        scriptFileName = scriptName,
        state = lifecycleState,
        abiVersion = abiVersion(),
        registrations = registrations(),
        grantedPermissions = config.permissions.map { it.jsName }.sorted(),
        memoryUsedBytes = memoryUsed(),
        inFlight = inFlight(),
    )

    internal fun diagnostics(): Map<String, Any?> = mapOf(
        "id" to id,
        "source" to sourceUri,
        "state" to lifecycleState,
        "abi" to (abiVersion() ?: "-"),
        "registrations" to registrations(),
        "inFlight" to inFlight(),
        "engineBusy" to engineBusy(),
        "jsHeapBytes" to memoryUsed(),
        "grants" to config.permissions.names(),
    )

    override fun toString(): String = "JsScriptPlugin($id, from='$sourceUri', state=$lifecycleState)"
}

/** Capabilities as a comma-joined list of JS-facing names, for logs and errors. */
private fun Set<JsCapability>.names(): String = joinToString(",") { it.jsName }

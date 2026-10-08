package dev.turbodl.plugin.js

import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.TurboEvent
import dev.turbodl.plugin.runtime.PluginContext
import dev.turbodl.plugin.runtime.ext.ExtensionPoints
import dev.turbodl.plugin.runtime.ext.LinkParser
import dev.turbodl.plugin.runtime.ext.TaskPostHook
import dev.turbodl.plugin.runtime.ext.TaskPreHook
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The JS→TurboDL direction: turn one `plugin.registerParser(...)` call into a real extension-point
 * implementation registered in the host's registry.
 *
 * ## Shape of one invocation
 * ```
 * consumer ──▶ JsLinkParser.parse(raw)                       // Kotlin contract, caller's thread
 *              link.invokeCallback(cbId, payload)             // refused once the instance is STOPPING
 *              QuickJS `__turboDispatchCallback(id, json)`    // on the plugin's own JS thread
 *              script returns a JS value → JSON envelope → JsValueCodec → DownloadRequest
 * ```
 * Data crosses as JSON text in both directions and the JS function never does. Consumers see an
 * ordinary `LinkParser`, never "a JS thing" — which is what lets Kotlin and JS plugins coexist in
 * the same extension point with the same priority rules.
 *
 * ## Failure semantics are per contract, not global
 * A throwing script must never be able to fail a download, so "JS failed" is mapped onto the meaning
 * the extension point already has:
 *  - [LinkParser] → `null` — §6: "MUST return null (not throw) for input it does not handle, so the
 *    router can try the next parser". A broken parser behaves exactly like a non-matching one.
 *  - [TaskPreHook] → the input request unchanged — §6: "return the input unchanged to no-op".
 *  - [TaskPostHook] and the event listener → logged and swallowed — §8: listeners must not throw.
 *
 * Every failure is logged with the plugin id and the ABI error code, so "my parser stopped matching"
 * is debuggable rather than silent.
 *
 * ## Concurrency
 * §6: implementations "MUST tolerate being called concurrently". [JsRuntime] serializes every
 * invocation onto one JS thread, so concurrent consumers queue instead of corrupting the context;
 * nothing here holds a lock across a JS call.
 *
 * ## Unloading one registration
 * `PluginContext` deliberately exposes no per-registration cancel (registrations are removed as a set
 * when the plugin unloads, through its disposer). So [deactivate] neutralizes a registration: its
 * callback is released and the adapter short-circuits to its own no-op, making the entry inert and
 * cheap. The registry entry itself disappears when the plugin is unloaded — recorded as a known
 * limitation in the README rather than worked around by reaching into `ExtensionRegistry`, which
 * would mean handing a Kotlin internals object to JS-owned code.
 */
internal class JsExtensionBridge(
    private val pluginId: String,
    private val context: PluginContext,
    private val sandbox: JsSandbox,
    private val linkProvider: () -> JsInstanceLink?,
    /**
     * Priority applied when a script does not set `impl.priority`. The loader can pass a value so a
     * host demotes *all* JS plugins at once (e.g. below a trusted Kotlin adapter) without editing
     * scripts; null keeps the [DEFAULT_PRIORITY] convention value.
     */
    private val loaderDefaultPriority: Int? = null,
) {

    /** One registration the script made, plus the flag that makes teardown per-registration. */
    private class Registration(
        val handle: Long,
        val kind: String,
        val jsCallbackId: Long,
        val priority: Int,
    ) {
        val active = AtomicBoolean(true)
    }

    private val byHandle = ConcurrentHashMap<Long, Registration>()
    private val nextHandle = AtomicLong(1)

    /**
     * Serve one `__turbodlRegister(kind, cbId, opts)`.
     *
     * @return data for the script — `{handle, kind, priority}`, all plain values. The
     *   [Registration] object never leaves this class.
     */
    fun install(kind: String, jsCallbackId: Long, optionsJson: String?): Map<String, Any?> {
        val options = parseOptions(optionsJson)
        // Precedence: the script's own `impl.priority`, then the loader-wide default, then the
        // convention value. A host demoting all JS plugins must not be able to override a script
        // that stated a priority deliberately, and a stated one must never be silently dropped.
        // Strict on range: a stated-but-out-of-range priority is a bug in the script, and falling
        // back to the default here would make the plugin route at a number its author never chose.
        val scriptPriority = JsValueCodec.asIntInRange(options["priority"], PRIORITY_RANGE, "registration priority")
        val priority = scriptPriority ?: loaderDefaultPriority ?: DEFAULT_PRIORITY
        val reg = Registration(nextHandle.getAndIncrement(), kind, jsCallbackId, priority)
        when (kind) {
            "parser" -> context.registerExtension(ExtensionPoints.LINK_PARSER, parserFor(reg), priority)
            "preHook" -> context.registerExtension(ExtensionPoints.TASK_PRE_HOOK, preHookFor(reg), priority)
            "postHook" -> context.registerExtension(ExtensionPoints.TASK_POST_HOOK, postHookFor(reg), priority)
            "event" -> context.onEvent(eventListenerFor(reg))
            "backend" -> throw JsAbi.AbiException(
                JsAbi.Code.UNSUPPORTED,
                "JS plugins may not register a DownloadBackend: the byte/chunk plane stays in Kotlin " +
                    "(host.http.downloadToFile gives a script the finished file instead)",
            )
            "service" -> throw JsAbi.AbiException(
                JsAbi.Code.UNSUPPORTED,
                "JS plugins may not publish host services; a service is a Kotlin object graph",
            )
            else -> throw JsAbi.AbiException(
                JsAbi.Code.VALIDATION,
                "unknown registration kind '$kind' (parser | preHook | postHook | event)",
            )
        }
        byHandle[reg.handle] = reg
        return mapOf("handle" to reg.handle, "kind" to kind, "priority" to priority.toLong())
    }

    /** Live registrations (diagnostics / unload reporting). */
    fun activeCount(): Int = byHandle.values.count { it.active.get() }

    fun describe(): List<String> = byHandle.values.map { "${it.kind}#${it.handle}@${it.priority}" }.sorted()

    /**
     * Neutralize every registration and return the JS callback ids to release.
     *
     * Runs *before* the drain so a consumer that races the unload gets a clean no-op (`null` from a
     * parser, the input back from a hook) rather than blocking on an instance that is going away.
     */
    fun deactivateAll(): List<Long> {
        val ids = byHandle.values.filter { it.active.getAndSet(false) }.map { it.jsCallbackId }
        byHandle.clear()
        return ids
    }

    // ------------------------------------------------------------------ adapters

    private fun parserFor(reg: Registration): LinkParser = LinkParser { raw ->
        if (!reg.active.get()) return@LinkParser null
        // The *result* is plugin-authored data, so validating it is as much a plugin fault as
        // throwing is: `LinkParser.parse` must return null, never propagate (CONVENTION.md §6).
        val result = try {
            when (val call = call("parse", reg, mapOf("input" to raw))) {
                is CallFailure -> {
                    context.log("parser #${reg.handle} failed (${call.code}): ${call.message} — falling through to the next parser")
                    return@LinkParser null
                }
                is CallSuccess -> call.value
            }
        } catch (t: Throwable) {
            context.log("parser #${reg.handle} returned unusable data (${t.message}) — falling through to the next parser")
            return@LinkParser null
        }
        try {
            toRequests(result, reg)
        } catch (t: JsAbi.AbiException) {
            context.log("parser #${reg.handle} returned an invalid request (${t.code}): ${t.message} — falling through to the next parser")
            null
        } catch (t: JsPermissionException) {
            context.log("parser #${reg.handle} returned a refused request (${t.message}) — falling through to the next parser")
            null
        } catch (t: JsValueCodec.JsCodecException) {
            context.log("parser #${reg.handle} returned unbridgeable data (${t.message}) — falling through to the next parser")
            null
        }
    }

    private fun preHookFor(reg: Registration): TaskPreHook = TaskPreHook { request ->
        if (!reg.active.get()) return@TaskPreHook request
        val result = call("beforeSubmit", reg, mapOf("request" to JsRequests.toMap(request, sandbox)))
        when {
            result is CallFailure -> {
                context.log("pre-hook #${reg.handle} failed (${result.code}): ${result.message} — request left unchanged")
                request
            }
            // `undefined`/null from the script means "no change"; anything else is a new descriptor.
            result is CallSuccess && result.value == null -> request
            result is CallSuccess -> try {
                // `request` is passed as the round-trip reference: a hook legitimately echoes the
                // destination it was given while changing headers, and that path was the host's
                // choice, not the script's. See JsRequests.fromMap.
                JsRequests.fromMap(result.value, sandbox, "request returned by pre-hook #${reg.handle}", request)
            } catch (t: JsAbi.AbiException) {
                context.log("pre-hook #${reg.handle} returned an invalid request (${t.code}): ${t.message} — request left unchanged")
                request
            } catch (t: JsValueCodec.JsCodecException) {
                // `headers`/`tags` are validated by the codec, which speaks its own exception. An
                // uncaught one here would land in TurboClient's submit path — the exact thing §6
                // forbids for a plugin.
                context.log("pre-hook #${reg.handle} returned unbridgeable data (${t.message}) — request left unchanged")
                request
            } catch (t: JsPermissionException) {
                context.log("pre-hook #${reg.handle} returned a refused request (${t.message}) — request left unchanged")
                request
            }
            else -> request
        }
    }

    private fun postHookFor(reg: Registration): TaskPostHook = TaskPostHook { request, success, detail ->
        if (!reg.active.get()) return@TaskPostHook
        val payload = mapOf(
            "request" to JsRequests.toMap(request, sandbox),
            "success" to success,
            "detail" to detail,
        )
        val result = call("afterFinish", reg, payload)
        if (result is CallFailure) {
            context.log("post-hook #${reg.handle} failed (${result.code}): ${result.message} — ignored")
        }
    }

    private fun eventListenerFor(reg: Registration): (TurboEvent) -> Unit = { event ->
        // Observation only: a script cannot influence the engine from here, and a throwing listener
        // can never break the event pump — every failure is converted below, so no `throw` escapes.
        // The invocation is deliberately synchronous and bounded: a slow listener holds the pump for
        // at most one invocationTimeoutMillis budget. That is the documented contract (bounded
        // observer); full async delivery would trade that bounded pause for unbounded event
        // reordering plus a second lifecycle to audit.
        if (reg.active.get()) {
            val result = call("onEvent", reg, eventToMap(event))
            if (result is CallFailure) {
                context.log("event listener #${reg.handle} failed (${result.code}): ${result.message} — ignored")
            }
        }
    }

    // ------------------------------------------------------------------ shared plumbing

    private sealed interface CallResult
    private class CallSuccess(val value: Any?) : CallResult
    private class CallFailure(val code: String, val message: String) : CallResult

    /**
     * Invoke the script's callback, converting *every* failure into a tagged result.
     *
     * A `GONE` here is the expected outcome while an instance is stopping, which is why it is logged
     * at the adapter level and never re-thrown: the callers of these extension points (TurboClient's
     * submit path, the event pump) must not see an exception from a plugin that is simply leaving.
     */
    private fun call(label: String, reg: Registration, payload: Any?): CallResult = try {
        val link = linkProvider()
            ?: return CallFailure(JsAbi.Code.GONE, "the JS runtime is gone")
        CallSuccess(link.invokeCallback(reg.jsCallbackId, payload))
    } catch (t: JsAbi.AbiException) {
        CallFailure(t.code, t.message ?: label)
    } catch (t: JsValueCodec.JsCodecException) {
        CallFailure(JsAbi.Code.VALIDATION, t.message ?: "codec error")
    } catch (t: Throwable) {
        CallFailure(JsAbi.Code.INTERNAL, t.message ?: t.javaClass.simpleName)
    }

    /** A parser result is `null` (no match), a single descriptor, or a list of them. */
    private fun toRequests(value: Any?, reg: Registration): List<DownloadRequest>? {
        if (value == null) return null
        val items = (value as? List<*>) ?: return listOf(single(value, reg))
        if (items.isEmpty()) return null
        JsRequests.checkCount(items.size)
        return items.map { single(it, reg) }
    }

    private fun single(value: Any?, reg: Registration): DownloadRequest =
        JsRequests.fromMap(value, sandbox, "request #${reg.handle} returned by the parser")

    private fun parseOptions(optionsJson: String?): Map<*, *> {
        if (optionsJson.isNullOrEmpty() || optionsJson == "null") return emptyMap<String, Any?>()
        val decoded = try {
            JsValueCodec.decodeFromJs(optionsJson, "registration options")
        } catch (t: JsValueCodec.JsCodecException) {
            throw JsAbi.AbiException(JsAbi.Code.VALIDATION, t.message ?: "invalid registration options", t)
        }
        return decoded as? Map<*, *>
            ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "registration options must be an object")
    }

    override fun toString(): String = "JsBridge($pluginId, active=${activeCount()})"

    private companion object {
        /**
         * Default priority for JS registrations. `CONVENTION.md` §6: base plugins `0`, HLS `100`,
         * adapters commonly `200`. A JS plugin is by construction an adapter (it recognizes links of
         * its own), so `200` matches intent without letting JS outrank a Kotlin plugin that asked for
         * more. A script may override it per registration via `impl.priority`.
         */
        const val DEFAULT_PRIORITY = 200
        val PRIORITY_RANGE: IntRange = -10_000..10_000
    }
}

/**
 * `DownloadRequest` ⇄ the JS descriptor shape, in one place.
 *
 * ## Why a hand-rolled mapping
 * [DownloadRequest] holds a `java.io.File`, an `Int?` and two string maps. Handing that graph to JS
 * would break the module's first rule (no Kotlin internals in the script world) and would pin the
 * ABI to core's field names. So the JS contract is a *documented JSON shape* and this object is the
 * only code that knows both sides:
 *
 * ```js
 * { url, destination?, fileName?, headers?, knownSize?, connections?, stableKey?, tags? }
 * ```
 *
 * On the way **in** `destination` is an absolute path *string* (never a `File`); on the way **back**
 * it is a bare file name, or a path only if the loader enabled
 * [JsPluginConfig.allowExternalDestination]. Everything else is a string, number or string map —
 * inside the [JsValueCodec] domain by construction.
 *
 * ## Bounds
 * Descriptors are plugin-authored data, so they are validated like any other untrusted input: a
 * missing `url`, a `connections` outside 1..256, or a non-http scheme fails the whole result rather
 * than applying it partially. A parser that returns one bad entry is a broken parser; silently
 * dropping that entry would change the download list, which is the one thing a parser must not do.
 */
internal object JsRequests {

    private const val MAX_URL_CHARS = 8 * 1024
    private const val MAX_REQUESTS = 256

    /** Same ceiling as `TurboConfig.maxConnectionsPerTask`; a script may not exceed the engine. */
    private val CONNECTIONS_RANGE: IntRange = 1..256

    /** Kotlin → JS. */
    fun toMap(request: DownloadRequest, sandbox: JsSandbox): Map<String, Any?> = mapOf(
        "url" to request.url,
        // A path string, and a hook that round-trips it unchanged still goes through the containment
        // check on the way back — so a hook cannot launder an outside path into the sandbox.
        "destination" to request.destination.absolutePath,
        "fileName" to request.destination.name,
        "headers" to request.headers,
        "knownSize" to request.knownSize,
        "connections" to request.connectionsOverride,
        "stableKey" to request.stableKey,
        "tags" to request.tags,
        // The plugin's own directory, so a script can explain where its relative names land.
        "sandbox" to sandbox.root.absolutePath,
    )

    /**
     * JS → Kotlin for one descriptor.
     *
     * @param roundTrip the engine's own request when this descriptor came *from* the host (a pre-hook
     *   result). Its `destination` is accepted verbatim, because the host chose it: without that edge,
     *   a hook that only edits headers would be rejected for echoing the absolute path it was handed —
     *   a silent no-op that looks like a working plugin. A parser passes no reference, so its script
     *   cannot name any path outside its sandbox.
     * @throws JsAbi.AbiException on a shape or bounds violation.
     */
    fun fromMap(value: Any?, sandbox: JsSandbox, label: String, roundTrip: DownloadRequest? = null): DownloadRequest {
        val obj = value as? Map<*, *>
            ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "$label must be an object")
        val url = (obj["url"] as? String)?.trim().orEmpty()
        if (url.isEmpty() || url.length > MAX_URL_CHARS) {
            throw JsAbi.AbiException(
                JsAbi.Code.VALIDATION,
                "$label.url must be a non-empty string of at most $MAX_URL_CHARS characters",
            )
        }
        // Scheme checked here, not left to the transport: a parser returning `file:///etc/passwd`
        // should fail as a plugin error instead of starting an engine task for it.
        JsRequestPolicy.parseUrl(url)
        val headers = JsValueCodec.asStringMap(obj["headers"], "$label.headers")
        val tags = JsValueCodec.asStringMap(obj["tags"], "$label.tags")
        val knownSize = JsValueCodec.asLongOrNull(obj["knownSize"]) ?: -1L
        // Strict: `connections: 99999` must be a `validation` failure, not a quiet fall back to the
        // engine default — a script that asked for 99999 threads and got 8 has no way to notice.
        val connections = JsValueCodec.asIntInRange(obj["connections"], CONNECTIONS_RANGE, "$label.connections")
        val stableKey = (obj["stableKey"] as? String)?.takeIf { it.isNotBlank() }?.take(200)
        val suggested = (obj["fileName"] as? String) ?: url.substringAfterLast('/').ifBlank { "download.bin" }
        val echoed = JsValueCodec.asStringOrNull(obj["destination"])
        // Verbatim echo of the destination the host handed out keeps the host's own file; anything
        // else goes back through the sandbox policy, so only the *unchanged* path is trusted.
        val destination = if (roundTrip != null && echoed != null && echoed == roundTrip.destination.absolutePath) {
            roundTrip.destination
        } else {
            // The sandbox policy speaks JsPermissionException; the extension adapters only catch ABI
            // failures, so translate here rather than letting a policy refusal escape into the
            // engine's submit path.
            try {
                sandbox.resolveDestination(echoed, suggested)
            } catch (e: JsPermissionException) {
                throw JsAbi.AbiException(JsAbi.Code.PERMISSION, e.message ?: "destination refused", e)
            }
        }
        return DownloadRequest(
            url = url,
            destination = destination,
            headers = headers,
            knownSize = knownSize,
            connectionsOverride = connections,
            stableKey = stableKey,
            tags = tags,
        )
    }

    /** Ceiling on how many requests one parse may return. */
    fun checkCount(size: Int) {
        if (size > MAX_REQUESTS) {
            throw JsAbi.AbiException(JsAbi.Code.LIMIT, "a parser returned $size requests; the limit is $MAX_REQUESTS")
        }
    }
}

/** `TurboEvent` → a flat, JSON-domain snapshot. */
internal fun eventToMap(event: TurboEvent): Map<String, Any?> {
    val base = mapOf("type" to event.javaClass.simpleName, "taskId" to event.taskId)
    return when (event) {
        is TurboEvent.Created -> base + mapOf("url" to event.request.url)
        is TurboEvent.StateChanged -> base + mapOf("state" to event.state.name)
        is TurboEvent.Progress -> base + mapOf(
            "state" to event.progress.state.name,
            "downloadedBytes" to event.progress.downloadedBytes,
            "totalBytes" to event.progress.totalBytes,
            "speedBytesPerSec" to event.progress.speedBytesPerSec,
            "activeConnections" to event.progress.activeConnections,
            "etaMillis" to event.progress.etaMillis,
            "percent" to event.progress.percent.toLong(),
            "error" to event.progress.error,
        )
        is TurboEvent.Completed -> base + mapOf("file" to event.file.absolutePath, "totalBytes" to event.totalBytes)
        is TurboEvent.Failed -> base + mapOf("reason" to event.reason)
        is TurboEvent.Metadata -> base + mapOf(
            "suggestedFileName" to event.suggestedFileName,
            "contentType" to event.contentType,
            "etag" to event.etag,
            "lastModified" to event.lastModified,
            "totalBytes" to event.totalBytes,
            "supportsRange" to event.supportsRange,
            "resolvedUrl" to event.resolvedUrl,
            "probeMs" to event.probeMs,
        )
    }
}

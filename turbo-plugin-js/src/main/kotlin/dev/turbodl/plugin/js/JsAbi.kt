package dev.turbodl.plugin.js

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The Kotlin half of the JS plugin ABI: envelope protocol and the ABI shim source.
 *
 * ## Why an envelope instead of exceptions
 * Two failure channels cross this boundary and must not be confused:
 *  - **Host fault** — the capability itself failed (denied, over limit, network error). The host
 *    throws [JsAbi.AbiException]; the binding layer turns it into `{ok:false,error:{code,message}}`
 *    so JS receives a catchable `Error` carrying a stable `code`.
 *  - **Plugin fault** — the script threw. QuickJS surfaces that as an exception out of `evaluate`,
 *    which the invoker converts into a host-visible failure *without* tearing the instance down.
 *
 * Keeping both as data means a plugin bug can never be mistaken for an engine failure, and `code`
 * is a contract we can evolve without depending on message text.
 */
internal object JsAbi {

    /** Major version of the JS-facing ABI (negotiated against TurboDL `ApiVersion.major`). */
    const val ABI_MAJOR: Int = 1
    const val ABI_VERSION: String = "1.0.0"

    /** Resource path of the shim, shipped inside the module jar. */
    private const val SHIM_RESOURCE = "abi.js"

    /** Error codes stable across releases — part of the plugin-facing contract. */
    object Code {
        const val VALIDATION = "validation"
        const val PERMISSION = "permission"
        const val LIMIT = "limit"
        const val NOT_FOUND = "not_found"
        const val GONE = "gone"
        const val UNSUPPORTED = "unsupported"
        const val NETWORK = "network"
        const val TIMEOUT = "timeout"
        const val INTERRUPTED = "interrupted"
        const val INTERNAL = "internal"
        const val PLUGIN = "plugin"
    }

    /** `{ok:true, data:<value>}` as JSON text. */
    fun ok(data: Any?): String = JsJson.encode(mapOf("ok" to true, "data" to JsValueCodec.requireValid(data, "data")))

    /** `{ok:false, error:{code,message}}` as JSON text. */
    fun error(code: String, message: String): String =
        JsJson.encode(mapOf("ok" to false, "error" to mapOf("code" to code, "message" to message)))

    /** Failure carrying a stable ABI error code, for capability implementations to throw. */
    class AbiException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Decode the envelope a JS callback returned from `__turboDispatchCallback`.
     *
     * Note the asymmetry with the host→JS direction: a *plugin* failure arrives **through the
     * return channel** (the ABI catches it in JS and reports it), so it must be re-thrown here as a
     * coded exception rather than being treated as a successful null. The extension adapters decide
     * what a plugin failure means for their contract — a parser that failed returns null so the next
     * parser runs, a hook that failed leaves the request unchanged.
     */
    fun decodeCallbackResult(envelope: String): Any? {
        val decoded = try {
            JsJson.decode(envelope)
        } catch (t: Throwable) {
            throw AbiException(Code.PLUGIN, "a plugin callback returned a malformed result: ${t.message}", t)
        }
        val map = decoded as? Map<*, *>
            ?: throw AbiException(Code.PLUGIN, "a plugin callback did not return an envelope")
        return if (map["ok"] == true) {
            map["data"]
        } else {
            val err = map["error"] as? Map<*, *>
            val code = err?.get("code") as? String ?: Code.PLUGIN
            val message = err?.get("message") as? String ?: "the plugin callback failed"
            throw AbiException(code, message)
        }
    }

    /** Map any thrown host failure onto an envelope, preserving codes we know about. */
    fun envelopeForFailure(t: Throwable): String {
        val code = when (t) {
            is AbiException -> t.code
            is JsPermissionException -> Code.PERMISSION
            is JsIoLimits.JsLimitException -> Code.LIMIT
            is JsValueCodec.JsCodecException -> Code.VALIDATION
            is IllegalArgumentException -> Code.VALIDATION
            is java.io.IOException -> Code.NETWORK
            is java.util.concurrent.TimeoutException -> Code.TIMEOUT
            else -> Code.INTERNAL
        }
        val message = (t.message ?: t.javaClass.simpleName).take(1000)
        return error(code, message)
    }

    private var cachedShim: String? = null

    /**
     * The ABI shim source. Read once and reused for every plugin instance: the *text* is shared,
     * the *evaluation* happens per context, so instances cannot see each other's state.
     */
    fun shimSource(): String = cachedShim ?: synchronized(this) {
        cachedShim ?: run {
            val stream = JsAbi::class.java.getResourceAsStream(SHIM_RESOURCE)
                ?: throw IllegalStateException(
                    "missing JS ABI resource '$SHIM_RESOURCE' — the turbo-plugin-js jar is incomplete",
                )
            val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            require(text.contains("__turboSeal")) { "JS ABI resource looks truncated" }
            cachedShim = text
            text
        }
    }
}

/**
 * Kotlin's view of "call a JS callback, release a JS callback" — implemented by the runtime that
 * owns the QuickJS instance, injected into [JsCallbackRegistry] so the registry stays engine-free
 * and testable.
 */
internal interface JsCallbackInvoker {
    /** Invoke JS `__turboDispatchCallback(id, payloadJson)` and return the envelope text. */
    fun dispatch(jsCallbackId: Long, payloadJson: String?): String

    /** Ask JS to drop the callback (best effort; a dead instance simply throws). */
    fun release(jsCallbackId: Long)
}

/**
 * Host-side registry of callbacks the JS side owns.
 *
 * A JS function never crosses into Kotlin (QuickJS hands one over as an opaque, un-invocable
 * handle), so the ABI keeps the function in JS and gives Kotlin an id. This registry is what makes
 * that id meaningful host-side: it records *which extension registration* the callback belongs to,
 * so unregistering one extension releases exactly its own callbacks and nothing else.
 */
internal class JsCallbackRegistry(
    private val pluginId: String,
    private val invoker: JsCallbackInvoker,
) {
    private val live = ConcurrentHashMap<Long, Handle>()
    private val nextId = AtomicLong(1)

    /** A host-side registration bound to a JS callback id. */
    class Handle(val id: Long, val jsCallbackId: Long, val kind: String)

    /** Allocate a registration id for a JS callback. */
    fun register(jsCallbackId: Long, kind: String): Handle {
        val id = nextId.getAndIncrement()
        val handle = Handle(id, jsCallbackId, kind)
        live[id] = handle
        return handle
    }

    /**
     * Invoke the JS callback behind [handle] with [payload] and return the decoded result.
     *
     * A released handle raises [JsAbi.Code.GONE] rather than quietly returning null: callers must be
     * able to distinguish "the plugin declined" from "the plugin was already unloaded".
     */
    fun invoke(handle: Handle, payload: Any?): Any? {
        if (!live.containsKey(handle.id)) {
            throw JsAbi.AbiException(JsAbi.Code.GONE, "registration ${handle.id} ('${handle.kind}') is released")
        }
        val envelope = invoker.dispatch(handle.jsCallbackId, encodePayload(payload))
        return JsAbi.decodeCallbackResult(envelope)
    }

    private fun encodePayload(payload: Any?): String? =
        if (payload == null) null else JsJson.encode(JsValueCodec.requireValid(payload, "payload"))

    /** Drop one registration (extension unregistered). */
    fun release(handle: Handle) {
        live.remove(handle.id)
        runCatching { invoker.release(handle.jsCallbackId) }
    }

    /**
     * Drop every registration (unload). Returns the JS callback ids so the caller can also drop
     * them engine-side; invoker errors are ignored because the runtime is going away anyway.
     */
    fun releaseAll(): List<Long> {
        val jsIds = live.values.map { it.jsCallbackId }.distinct()
        live.clear()
        return jsIds
    }

    fun liveCount(): Int = live.size

    override fun toString(): String = "JsCallbacks($pluginId, live=${live.size})"
}

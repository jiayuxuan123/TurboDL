package dev.turbodl.plugin.js

import com.dokar.quickjs.binding.JsObject

/**
 * JsValueCodec — the ONE place Kotlin↔JS value conversion happens.
 *
 * Every bridge, host capability and lifecycle callback routes its data through here. Ad-hoc
 * `as`/`toString()`/`JSON.parse` scattered across bridges is what turns an FFI boundary into a
 * set of subtly different, individually-auditable conversion rules; this file is the single
 * auditable surface.
 *
 * ## Two halves, deliberately asymmetric
 *
 *  - **Kotlin → JS** (`toJsArgument`): a JSON *string* is passed as a string, and JS calls
 *    `JSON.parse`. Text crosses the JNI boundary as one value; no per-field object graph is built
 *    host-side, so there is no per-field leak surface and no marshalling recursion in Kotlin.
 *  - **JS → Kotlin** (`fromJsValue`): QuickJS hands plain data as Long/Double/String/Boolean,
 *    arrays as `ArrayList`, and everything else (objects, functions, Date, RegExp) as an opaque
 *    [JsObject]. Functions and exotic objects are **rejected**, not coerced — a JS callback must
 *    travel as a registered id (see [JsAbi]) or not at all.
 *
 * ## Value domain (closed on purpose)
 * null, Boolean, Long, Double, String, List<Any?>, Map<String, Any?>
 *
 * Anything outside it throws [JsCodecException]. That is the point: the day someone wants to hand
 * JS a `File` or a `DownloadRequest` directly, this throws instead of silently exposing a JVM
 * object graph — which is exactly the "no Kotlin internals in JS" rule, enforced by the codec
 * rather than by review.
 */
internal object JsValueCodec {

    /** Values larger than this are almost certainly a mistake (or an attack), not a plugin payload. */
    const val MAX_STRING_CHARS: Int = 4 * 1024 * 1024

    class JsCodecException(message: String, cause: Throwable? = null) : Exception(message, cause)

    // ---------- Kotlin → JS ----------

    /**
     * Render [value] as a JavaScript **expression literal** suitable for splicing into an
     * `evaluate()` call. Strings/numbers/booleans/null become literals; structured data becomes a
     * JSON literal (identical syntax, so JS `JSON.parse` on it is the documented decode path).
     */
    fun toJsLiteral(value: Any?): String = JsJson.encode(value)

    /**
     * Encode [value] for a JS-side host/bridge call: compact JSON text.
     * The receiving JS code is always `JSON.parse(<this>)`, never a spliced object graph.
     */
    fun encodeForJs(value: Any?): String = JsJson.encode(requireValid(value, "outbound"))

    /** A ready-to-evaluate expression that yields the encoded payload, or `null` when there is none. */
    fun encodeArgumentForJs(value: Any?): String =
        if (value == null) "null" else JsJson.encodeString(encodeForJs(value))

    // ---------- JS → Kotlin ----------

    /**
     * Convert a raw value delivered by QuickJS (binding argument or `evaluate` result) into the
     * closed value domain. Throws [JsCodecException] for anything not representable.
     */
    fun fromJsValue(raw: Any?, field: String = "value"): Any? = when (raw) {
        null -> null
        is Boolean, is Long, is Int -> raw
        // QuickJS can hand back a raw NaN/Infinity (JS has them natively); the bridge's value domain
        // is JSON's, so they are refused here exactly as they are on the Kotlin-producer side.
        is Double -> if (raw.isFinite()) raw else throw JsCodecException("$field is a non-finite number ($raw); the bridge carries only finite doubles")
        is String -> {
            if (raw.length > MAX_STRING_CHARS) throw JsCodecException("$field exceeds ${MAX_STRING_CHARS} chars")
            raw
        }
        is ArrayList<*> -> raw.map { fromJsValue(it, "$field[]") }
        is List<*> -> raw.map { fromJsValue(it, "$field[]") }
        is JsObject -> throw JsCodecException(
            "$field is a JS object/function; the bridge passes data as JSON text — " +
                "return JSON.stringify(...) or a registered callback id instead",
        )
        is Map<*, *> -> {
            val out = LinkedHashMap<String, Any?>(raw.size)
            for ((key, value) in raw) {
                val name = key?.toString() ?: throw JsCodecException("$field has a null key")
                out[name] = fromJsValue(value, "$field.$name")
            }
            out
        }
        else -> throw JsCodecException("$field has unsupported JS type ${raw::class.simpleName}")
    }

    /**
     * Decode JSON text coming *from* JS into the value domain. Validates shape strictly, so a
     * plugin that returns garbage fails here with a clear error rather than at some later cast.
     */
    fun decodeFromJs(text: String?, field: String = "result"): Any? {
        if (text.isNullOrEmpty()) return null
        val parsed = try {
            JsJson.decode(text)
        } catch (t: Throwable) {
            throw JsCodecException("$field is not valid JSON: ${t.message}", t)
        }
        return requireValid(parsed, field)
    }

    // ---------- shared validation ----------

    /**
     * Validate that [value] is inside the closed domain, on the way **in** to either direction.
     * Kotlin-side producers (host capability results, event snapshots) get the same guarantee as
     * JS-side ones, so both halves of the bridge can rely on it.
     */
    fun requireValid(value: Any?, field: String = "value"): Any? = when (value) {
        null, is Boolean, is Long, is Int, is String -> {
            if (value is String && value.length > MAX_STRING_CHARS) {
                throw JsCodecException("$field exceeds ${MAX_STRING_CHARS} chars")
            }
            value
        }
        // JSON has no NaN/Infinity and silently emitting `null` would change the value a plugin
        // receives without anyone failing. The bridge's rule is strict refusal at the boundary —
        // the same posture as the decode direction, which rejects a `1e999` overflow.
        is Double -> if (value.isFinite()) value else throw JsCodecException("$field is a non-finite number ($value); the bridge carries only finite doubles")
        is List<*> -> value.map { requireValid(it, "$field[]") }
        is Map<*, *> -> value.entries.associate { (k, v) ->
            (k as? String ?: throw JsCodecException("$field map keys must be strings")) to requireValid(v, "$field.$k")
        }
        else -> throw JsCodecException("$field of type ${value::class.simpleName} is not bridgeable")
    }

    /**
     * Coerce a decoded value to String, or null. Used where an API surface declares a field as
     * "string-ish" and tolerating a JSON number (e.g. a status code in a header map) is friendlier
     * than failing the whole call.
     */
    fun asStringOrNull(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> null
    }

    fun asStringMap(value: Any?, field: String): Map<String, String> {
        if (value == null) return emptyMap()
        val map = value as? Map<*, *> ?: throw JsCodecException("$field must be an object of string keys")
        return buildMap(map.size) {
            for ((k, v) in map) {
                val key = k as? String ?: throw JsCodecException("$field key must be a string")
                val text = asStringOrNull(v) ?: throw JsCodecException("$field['$key'] must be a string")
                put(key, text)
            }
        }
    }

    fun asLongOrNull(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Double -> if (value.isFinite()) value.toLong() else null
        is String -> value.toLongOrNull()
        else -> null
    }

    /**
     * Strict range check for fields where **"present but invalid" must be an error, not a silent
     * fallback**. A lenient `?.takeIf { it in range }` cannot express that: a script that asks for
     * `connections: 99999` or `priority: 20000` gets `null`, which the caller reads as "the author
     * stated nothing" and replaces with its own default — the plugin then runs with a number it
     * never chose, and has no way to find out.
     *
     * Here an absent value (`null`) still means "use the default"; anything else that is not a
     * finite number inside [range] throws [JsCodecException], which the dispatch layer renders as
     * the `validation` code.
     */
    fun asIntInRange(value: Any?, range: IntRange, field: String): Int? {
        if (value == null) return null
        val coerced = asLongOrNull(value)?.toInt()
            ?: throw JsCodecException("$field must be a number, got ${describeType(value)}")
        if (coerced !in range) {
            throw JsCodecException("$field must be within ${range.first}..${range.last}, got $coerced")
        }
        return coerced
    }

    private fun describeType(value: Any?): String = when (value) {
        is String -> "string '$value'"
        is Double -> if (value.isFinite()) "number $value" else "non-finite number $value"
        null -> "null"
        else -> value.javaClass.simpleName
    }

    fun asBooleanOrNull(value: Any?): Boolean? = value as? Boolean

    fun asListOrNull(value: Any?): List<Any?>? = value as? List<*>
}

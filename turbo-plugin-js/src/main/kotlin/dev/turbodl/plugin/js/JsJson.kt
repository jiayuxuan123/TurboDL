package dev.turbodl.plugin.js

/**
 * Minimal strict JSON reader/writer for the JS bridge.
 *
 * ## Why hand-rolled
 * The bridge's wire format is JSON text, and it crosses a **trust boundary in both directions**:
 * JS is untrusted third-party code, and HTTP responses it hands back are remote-controlled. So the
 * parser must be exactly as strict as we need — reject trailing garbage, reject non-finite numbers,
 * cap input size and nesting depth — and it must not add a runtime dependency to a published
 * artifact (`docs/plugins/CONVENTION.md` §9: pin versions, don't pull unvetted transitive deps).
 *
 * Supported value domain matches [JsValueCodec]: null, Boolean, Long, Double, String,
 * `List<Any?>`, `Map<String, Any?>`. Numbers decode to Long when integral and Double otherwise,
 * which is the same mapping QuickJS values already arrive with, so the codec has one rule instead
 * of two.
 */
internal object JsJson {

    /** Refuse anything larger than this before even allocating a tree (see [JsIoLimits]). */
    const val MAX_TEXT_CHARS: Int = 4 * 1024 * 1024

    /**
     * Nested depth cap. Legitimate plugin payloads (a parser result, an HTTP response descriptor)
     * stay far below this; a runaway nesting is a stack-overflow attempt on the parse thread.
     */
    private const val MAX_DEPTH = 64

    /** Encode a [JsValueCodec]-domain value as compact JSON. */
    fun encode(value: Any?): String {
        val sb = StringBuilder(64)
        writeValue(sb, value, 0)
        return sb.toString()
    }

    /** Encode a string as a JSON string literal — also a valid JavaScript string literal. */
    fun encodeString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        writeString(sb, value)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: Any?, depth: Int) {
        if (depth > MAX_DEPTH) throw IllegalArgumentException("json nesting too deep")
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Number -> writeNumber(sb, value)
            is String -> writeString(sb, value)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k?.toString() ?: "null")
                    sb.append(':')
                    writeValue(sb, v, depth + 1)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(sb, v, depth + 1)
                }
                sb.append(']')
            }
            is Array<*> -> writeValue(sb, value.asList(), depth)
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeNumber(sb: StringBuilder, value: Number) {
        val d = value.toDouble()
        // JSON has no NaN/Infinity, and emitting `null` would change the value a plugin receives
        // without anyone failing. [JsValueCodec.requireValid] rejects these on the way in; if one
        // still reaches the writer it is a codec bug, so it fails loudly instead of mutating data.
        if (d.isNaN() || d.isInfinite()) {
            throw IllegalArgumentException("non-finite number '$value' reached JSON encoding; JsValueCodec.requireValid must reject it first")
        }
        sb.append(if (value is Long || value is Int) value.toString() else value.toString())
    }

    private fun writeString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                // U+2028/U+2029 are valid inside JSON strings but are line terminators in some JS
                // engines' source text; escaping them keeps every encoded string embeddable in a
                // JS literal without changing its meaning.
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> if (ch < ' ') sb.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    /** Parse strict JSON; throws [IllegalArgumentException] on anything malformed or oversized. */
    fun decode(text: String): Any? {
        if (text.length > MAX_TEXT_CHARS) throw IllegalArgumentException("json text too large")
        val p = Parser(text)
        p.skipWs()
        val value = p.readValue(0)
        p.skipWs()
        if (!p.end) throw IllegalArgumentException("unexpected trailing characters at index ${p.index}")
        return value
    }

    private class Parser(private val s: String) {
        var index = 0
        val end: Boolean get() = index >= s.length

        fun skipWs() {
            while (index < s.length && when (s[index]) {
                ' ', '\t', '\n', '\r' -> true
                else -> false
            }
            ) index++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw IllegalArgumentException("json nesting too deep")
            if (end) throw IllegalArgumentException("unexpected end of json")
            return when (val c = s[index]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> {
                    expect("true")
                    true
                }
                'f' -> {
                    expect("false")
                    false
                }
                'n' -> {
                    expect("null")
                    null
                }
                else -> if (c == '-' || c.isDigit()) readNumber() else throw IllegalArgumentException(
                    "unexpected character '$c' at index $index",
                )
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            index++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (index < s.length && s[index] == '}') {
                index++
                return map
            }
            while (true) {
                skipWs()
                if (index >= s.length || s[index] != '"') throw IllegalArgumentException("expected string key at index $index")
                val key = readString()
                skipWs()
                if (index >= s.length || s[index] != ':') throw IllegalArgumentException("expected ':' at index $index")
                index++
                skipWs()
                map[key] = readValue(depth + 1)
                skipWs()
                if (index >= s.length) throw IllegalArgumentException("unterminated object")
                when (s[index]) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return map
                    }
                    else -> throw IllegalArgumentException("expected ',' or '}' at index $index")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            index++ // '['
            val list = ArrayList<Any?>()
            skipWs()
            if (index < s.length && s[index] == ']') {
                index++
                return list
            }
            while (true) {
                skipWs()
                list.add(readValue(depth + 1))
                skipWs()
                if (index >= s.length) throw IllegalArgumentException("unterminated array")
                when (s[index]) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return list
                    }
                    else -> throw IllegalArgumentException("expected ',' or ']' at index $index")
                }
            }
        }

        private fun readString(): String {
            index++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (index >= s.length) throw IllegalArgumentException("unterminated string")
                val c = s[index]
                if (c == '"') {
                    index++
                    return sb.toString()
                }
                if (c == '\\') {
                    index++
                    if (index >= s.length) throw IllegalArgumentException("unterminated escape")
                    when (val e = s[index]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            require(index + 4 < s.length) { "truncated \\u escape at index $index" }
                            val hex = s.substring(index + 1, index + 5)
                            val cp = hex.toIntOrNull(16) ?: throw IllegalArgumentException("bad \\u escape '$hex'")
                            sb.append(cp.toChar())
                            index += 4
                        }
                        else -> throw IllegalArgumentException("bad escape '\\$e' at index ${index - 1}")
                    }
                    index++
                } else {
                    if (c < '\u0020') throw IllegalArgumentException("unescaped control character at index $index")
                    sb.append(c)
                    index++
                }
            }
        }

        private fun readNumber(): Any? {
            val start = index
            if (index < s.length && s[index] == '-') index++
            // RFC 8259 forbids a leading zero before another digit. Rejecting it matters because this
            // text is *plugin-authored* on the inbound path: a lenient reader would accept `011` as 11
            // where every real JS engine's JSON.parse rejects the document, so the two parsers would
            // disagree about what a plugin asked for.
            if (index < s.length && s[index] == '0' && index + 1 < s.length && s[index + 1].isDigit()) {
                throw IllegalArgumentException("number with a leading zero at index $index")
            }
            while (index < s.length && s[index].isDigit()) index++
            var integral = true
            if (index < s.length && s[index] == '.') {
                integral = false
                index++
                while (index < s.length && s[index].isDigit()) index++
            }
            if (index < s.length && (s[index] == 'e' || s[index] == 'E')) {
                integral = false
                index++
                if (index < s.length && (s[index] == '+' || s[index] == '-')) index++
                while (index < s.length && s[index].isDigit()) index++
            }
            val text = s.substring(start, index)
            if (text.isEmpty() || text == "-") throw IllegalArgumentException("invalid number at index $start")
            if (integral) {
                val l = text.toLongOrNull()
                if (l != null) return l
            }
            // An over-range exponent (`1e999`) parses to Infinity rather than failing; this parser's
            // contract (see this file's header) is to reject non-finite numbers instead of handing
            // one across the bridge.
            val d = text.toDoubleOrNull() ?: throw IllegalArgumentException("invalid number '$text'")
            if (d.isNaN() || d.isInfinite()) throw IllegalArgumentException("non-finite number '$text' at index $start")
            return d
        }

        private fun expect(literal: String) {
            require(index + literal.length <= s.length && s.regionMatches(index, literal, 0, literal.length)) {
                "invalid token at index $index"
            }
            index += literal.length
        }
    }
}

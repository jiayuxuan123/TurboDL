package dev.turbodl.plugin.js

import com.dokar.quickjs.binding.JsObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The codec is the single auditable conversion surface, so its contract is tested directly rather
 * than only through bridges: the value domain is *closed*, un-representable input throws here, and
 * nothing that could expose a JVM object graph survives. Everything is plain Kotlin values, so
 * these cases also run on a machine without the QuickJS native library.
 */
class JsValueCodecTest {

    // ------------------------------------------------------------------ inbound (JS -> Kotlin)

    @Test
    fun `the value domain round-trips exactly`() {
        val value = mapOf(
            "nil" to null,
            "flag" to true,
            "count" to 7L,
            "ratio" to 1.5,
            "text" to "héllo",
            "list" to listOf(1L, "two", false, null),
            "nested" to mapOf("deep" to listOf(mapOf("ok" to true))),
        )
        assertEquals(value, JsValueCodec.decodeFromJs(JsValueCodec.encodeForJs(value)))
    }

    @Test
    fun `an opaque js object is rejected rather than coerced`() {
        // QuickJS hands functions, Date, RegExp and plain objects to Kotlin as JsObject — which is also
        // a java.util.Map, so the rejection arm has to come first or a live JS reference would be
        // retained in Kotlin under the guise of "just data".
        val raw = JsObject(mapOf("fn" to "function () {}"))
        val error = assertFailsWith<JsValueCodec.JsCodecException> { JsValueCodec.fromJsValue(raw, "result") }
        assertTrue("JSON.stringify" in (error.message ?: ""), "the error must tell an author the fix: ${error.message}")
        // Rejection must survive nesting, or the rule is only skin-deep.
        assertFailsWith<JsValueCodec.JsCodecException> { JsValueCodec.fromJsValue(listOf(raw), "results") }
    }

    @Test
    fun `a jvm object cannot be handed to js`() {
        // A producer that tries to pass a File or a byte array gets a hard stop, not a `.toString()`
        // that would quietly turn a Kotlin internal into a JS string.
        assertFailsWith<JsValueCodec.JsCodecException> { JsValueCodec.encodeForJs(mapOf("f" to java.io.File("/tmp/x"))) }
        assertFailsWith<JsValueCodec.JsCodecException> { JsValueCodec.requireValid(byteArrayOf(1, 2), "bytes") }
    }

    @Test
    fun `oversized strings are refused on both sides`() {
        val huge = "x".repeat(JsValueCodec.MAX_STRING_CHARS + 1)
        assertFailsWith<JsValueCodec.JsCodecException> { JsValueCodec.fromJsValue(huge, "body") }
        assertFailsWith<JsValueCodec.JsCodecException> { JsValueCodec.requireValid(huge, "body") }
        // Just under the cap must still work, or the ceiling is off by one in production.
        val near = "x".repeat(JsValueCodec.MAX_STRING_CHARS - 8)
        assertEquals(near.length, (JsValueCodec.requireValid(near, "body") as String).length)
    }

    @Test
    fun `a map with a non-string key is refused, not stringified`() {
        assertFailsWith<JsValueCodec.JsCodecException> { JsValueCodec.requireValid(mapOf(1L to "a"), "headers") }
        assertEquals("a", JsValueCodec.asStringMap(mapOf("k" to "a"), "headers")["k"])
        assertFailsWith<JsValueCodec.JsCodecException> { JsValueCodec.asStringMap(listOf("a"), "headers") }
        assertFailsWith<JsValueCodec.JsCodecException> { JsValueCodec.asStringMap(mapOf("k" to listOf(1)), "headers") }
        assertTrue(JsValueCodec.asStringMap(null, "headers").isEmpty(), "an absent map is empty, not an error")
    }

    // ------------------------------------------------------------------ outbound (Kotlin -> JS)

    @Test
    fun `non-finite numbers are refused, not silently rewritten`() {
        // NaN/Infinity are legal Kotlin doubles and illegal JSON. Mapping them to `null` would change
        // the value a plugin receives without anyone failing; the bridge refuses them at the boundary
        // instead — the same strictness as the decode direction, which rejects a `1e999` overflow.
        val refused = assertFailsWith(JsValueCodec.JsCodecException::class) {
            JsValueCodec.encodeForJs(mapOf("a" to Double.NaN, "b" to Double.POSITIVE_INFINITY))
        }
        assertTrue("non-finite" in (refused.message ?: ""), "the error must name the fault: ${refused.message}")
        assertFailsWith(JsValueCodec.JsCodecException::class) { JsValueCodec.requireValid(Double.NaN, "x") }
        assertFailsWith(JsValueCodec.JsCodecException::class) { JsValueCodec.requireValid(Double.NEGATIVE_INFINITY, "y") }
        assertFailsWith(JsValueCodec.JsCodecException::class) { JsValueCodec.fromJsValue(Double.NaN, "z") }
        // And a plugin-side NaN still coerces to "no number" rather than a bogus Long — that is the
        // tolerant *reader* contract (null = not a number), untouched by the encoder's strictness.
        assertNull(JsValueCodec.asLongOrNull(Double.NaN))
        assertNull(JsValueCodec.asLongOrNull(Double.POSITIVE_INFINITY))
        assertEquals(3L, JsValueCodec.asLongOrNull(3.7))
        assertEquals(3, JsValueCodec.asIntInRange(3.0, -10_000..10_000, "priority"))
        assertNull(JsValueCodec.asIntInRange(null, -10_000..10_000, "priority"), "absent means 'use the default'")
        // Stated-but-out-of-range is a script bug, so it must throw rather than silently fall back:
        // a plugin routed at a number its author never chose is undetectable from the log.
        val over = assertFailsWith(JsValueCodec.JsCodecException::class) {
            JsValueCodec.asIntInRange(99_999, -10_000..10_000, "registration priority")
        }
        assertTrue("registration priority" in (over.message ?: ""), "the field name must be named: ${over.message}")
        assertTrue("-10000..10000" in (over.message ?: ""), "the accepted range must be stated: ${over.message}")
        assertFailsWith(JsValueCodec.JsCodecException::class) {
            JsValueCodec.asIntInRange("many", 1..256, "connections")
        }
        assertEquals(8, JsValueCodec.asIntInRange("8", 1..256, "connections"), "a numeric string is tolerable, as elsewhere")
        assertFailsWith(JsValueCodec.JsCodecException::class) {
            JsValueCodec.asIntInRange(Double.NaN, 1..256, "connections")
        }
        assertEquals("42", JsValueCodec.asStringOrNull(42L), "a number in a string field is tolerable")
        assertNull(JsValueCodec.asStringOrNull(listOf(1)))
    }

    @Test
    fun `an argument is encoded as a js string literal ready for json-parse`() {
        val expr = JsValueCodec.encodeArgumentForJs(mapOf("url" to "http://x/\"y\""))
        assertTrue(expr.startsWith("\"") && expr.endsWith("\""), "must be one JS literal: $expr")
        // The literal decodes back to the JSON *text* of the payload — the documented JS path is
        // `JSON.parse(...)`, never a spliced object graph.
        val inner = JsValueCodec.decodeFromJs(JsJson.decode(expr) as String, "arg") as Map<*, *>
        assertEquals("http://x/\"y\"", inner["url"])
        assertEquals("null", JsValueCodec.encodeArgumentForJs(null))
    }

    // ------------------------------------------------------------------ json reader/writer

    @Test
    fun `the parser is strict about shape`() {
        assertFailsWith<IllegalArgumentException> { JsJson.decode("""{"a":1} trailing""") }
        assertFailsWith<IllegalArgumentException> { JsJson.decode("[1,2,]") }
        assertFailsWith<IllegalArgumentException> { JsJson.decode("{'a':1}") }
        assertFailsWith<IllegalArgumentException> { JsJson.decode("\"unterminated") }
        assertFailsWith<IllegalArgumentException> { JsJson.decode("{\"a\":}") }
        assertFailsWith<IllegalArgumentException> { JsJson.decode("{\"a\":0x10}") }
        assertFailsWith<IllegalArgumentException> { JsJson.decode("") }
        // A raw control character inside a string is not JSON; only escaped forms are.
        assertFailsWith<IllegalArgumentException> { JsJson.decode("\"a\tb\"") }
        // Two top-level values are not one document.
        assertFailsWith<IllegalArgumentException> { JsJson.decode("1 2") }
        assertEquals(1L, JsJson.decode("1"))
        assertEquals(12L, JsJson.decode("12"), "integral numbers stay Long so there is one numeric rule")
        assertEquals(1.5, JsJson.decode("1.5"))
        assertEquals(0.001, JsJson.decode("1e-3"))
        assertEquals(-7L, JsJson.decode("-7"))
        assertNull(JsJson.decode("null"))
        assertEquals("a\nb", JsJson.decode("\"a\\nb\""))
        assertEquals("\u2028", JsJson.decode("\"\\u2028\""))
        assertEquals("/", JsJson.decode("\"\\/\""))
    }

    @Test
    fun `malformed numbers are rejected`() {
        assertFailsWith<IllegalArgumentException> { JsJson.decode("01") }
        assertFailsWith<IllegalArgumentException> { JsJson.decode("-") }
        assertFailsWith<IllegalArgumentException> { JsJson.decode("{\"a\":1e}") }
    }

    @Test
    fun `nesting and size are capped before a tree is built`() {
        // Deep nesting is a stack-overflow attempt on the parse thread and an oversized text is a
        // memory attack; both must fail as ordinary errors the bridge turns into `validation`/`limit`.
        val nested = "[".repeat(300) + "]".repeat(300)
        assertFailsWith<IllegalArgumentException> { JsJson.decode(nested) }
        assertFailsWith<IllegalArgumentException> { JsJson.decode("\"" + "x".repeat(JsJson.MAX_TEXT_CHARS) + "\"") }
        // Writing is capped too, so a host producer cannot recurse its own thread.
        var deep: Any? = 1L
        repeat(300) { deep = listOf(deep) }
        assertFailsWith<IllegalArgumentException> { JsValueCodec.toJsLiteral(deep) }
        // A legitimate depth well under the cap still parses.
        assertEquals(1L, ((JsValueCodec.decodeFromJs("[[[1]]]", "r") as List<*>)
            .let { (((it.first() as List<*>).first() as List<*>).first()) }))
    }

    @Test
    fun `escapes needed for embedding in js source are preserved`() {
        val text = "quote\" back\\ newline\n tab\t unit\u2028sep sep\u2029end \u0001"
        val encoded = JsJson.encodeString(text)
        assertTrue("\\u2028" in encoded && "\\u2029" in encoded, "line terminators must be escaped to stay valid JS source: $encoded")
        assertTrue("\\u0001" in encoded, "control characters must be escaped: $encoded")
        assertFalse("\u2028" in encoded, "the raw character must not survive into the literal")
        assertEquals(text, JsJson.decode(encoded))
    }

    @Test
    fun `an empty or absent js result decodes to null but whitespace is a contract violation`() {
        // A void hook returns nothing; the codec must read that as "no value", not a plugin error.
        assertNull(JsValueCodec.decodeFromJs(null))
        assertNull(JsValueCodec.decodeFromJs(""))
        // Anything else that is not one JSON document is a real bug and must not be papered over.
        assertFailsWith<JsValueCodec.JsCodecException> { JsValueCodec.decodeFromJs("   ", "result") }
        assertFailsWith<JsValueCodec.JsCodecException> { JsValueCodec.decodeFromJs("{not json", "result") }
    }
}

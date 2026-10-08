package dev.turbodl.plugin.js

import dev.turbodl.core.DownloadRequest
import dev.turbodl.plugin.runtime.Plugin
import dev.turbodl.plugin.runtime.PluginContext
import dev.turbodl.plugin.runtime.PluginHost
import dev.turbodl.plugin.runtime.PluginSource
import dev.turbodl.plugin.runtime.ext.ExtensionPoints
import dev.turbodl.plugin.runtime.ext.LinkParser
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Routing: what happens when more than one plugin claims the same link, and when a Kotlin plugin and
 * a JS plugin sit in the same list. The JS loader is a *peer* of `turbo-plugin-hls`, so a JS parser
 * must be ordered, declined, and removed by the ordinary kernel rules — never through a JS-specific
 * path. These cases cover the two claims the module lives on: uninstalling `loader.js` leaves a
 * compiled plugin untouched, and a plugin-authored bad result degrades to `null` instead of throwing
 * into the router (CONVENTION.md §6).
 */
class JsRoutingTest {

    private fun host(): Pair<PluginHost, MutableList<String>> {
        val logs = mutableListOf<String>()
        return PluginHost(logger = { msg, e -> logs.add(msg + (e?.let { " — ${it.message}" } ?: "")) }) to logs
    }

    /**
     * A parser that matches one scheme prefix. [priority] is spliced verbatim so a case can state
     * `priority: 900,` or leave the loader default in place.
     */
    private fun parser(scheme: String, fileName: String, priority: String) = """
        plugin.registerParser({$priority parse: function (input) {
          var raw = (input && input.input !== undefined) ? String(input.input) : String(input);
          if (raw.indexOf('$scheme://') !== 0) return null;
          return [{ url: 'https://cdn.example/' + raw.slice(${scheme.length + 3}), fileName: '$fileName' }];
        }});
    """.trimIndent()

    /** The way a consumer routes: walk the ordered list, take the first parser that matched. */
    private fun List<LinkParser>.route(link: String): DownloadRequest? =
        firstNotNullOfOrNull { it.parse(link) }?.firstOrNull()

    @Test
    fun `the highest-priority js parser wins and a declined link falls through to the next`() {
        JsEngineProbe.requireEngine()
        val (h, logs) = host()
        h.install(JsPluginLoaderPlugin())
        // Load the low-priority one first, so a passing assertion cannot be explained by insertion order.
        h.loadSource(PluginSource("js", "inline:" + parser("beta", "beta.bin", "priority: 100,"), mapOf("pluginId" to "parser.beta")))
        h.loadSource(PluginSource("js", "inline:" + parser("alpha", "alpha.bin", "priority: 900,"), mapOf("pluginId" to "parser.alpha")))

        assertEquals(
            listOf(900, 100),
            h.extensions.registrations(ExtensionPoints.LINK_PARSER).map { it.priority },
            "the registry must order by priority, not insertion",
        )

        val parsers = h.extensions.all(ExtensionPoints.LINK_PARSER)
        // The highest-priority parser is the one a router consults first.
        assertEquals("https://cdn.example/one", parsers.first().parse("alpha://one")!!.single().url)
        assertEquals("alpha.bin", parsers.first().parse("alpha://one")!!.single().destination.name)

        // The alpha parser declines a beta link, so walking the list lands on the beta parser.
        assertEquals(null, parsers.first().parse("beta://two"), "a non-matching link must be declined, not guessed")
        assertEquals("beta.bin", parsers.route("beta://two")!!.destination.name)
        assertEquals("https://cdn.example/two", parsers.route("beta://two")!!.url)

        // An unrelated link matches nothing, so core's own handling stays reachable.
        assertEquals(null, parsers.route("magnet:?xt=urn:btih:abc"), "logs=$logs")
        h.shutdown()
    }

    @Test
    fun `a kotlin parser and a js parser are ordered by priority alone`() {
        JsEngineProbe.requireEngine()
        val (h, logs) = host()
        // Stands in for a compiled system plugin's parser (turbo-plugin-hls registers this exact shape).
        h.install(object : Plugin {
            override val id = "parser.kotlin"
            override fun onLoad(context: PluginContext) {
                context.registerExtension(
                    ExtensionPoints.LINK_PARSER,
                    LinkParser { raw ->
                        if (!raw.startsWith("kt://")) return@LinkParser null
                        listOf(DownloadRequest("https://kt.example/" + raw.drop(5), File("downloads", "kt.bin")))
                    },
                    priority = 500,
                )
            }
        })
        h.install(JsPluginLoaderPlugin())
        h.loadSource(PluginSource("js", "inline:" + parser("js", "js.bin", ""), mapOf("pluginId" to "parser.js")))

        // JS defaults to 200 — the adapter band — below a Kotlin plugin that asked for 500. The ordering
        // rule is the kernel's, so the language of the implementation is irrelevant to it.
        assertEquals(
            listOf("parser.kotlin" to 500, "parser.js" to 200),
            h.extensions.registrations(ExtensionPoints.LINK_PARSER).map { it.ownerPluginId to it.priority },
            "logs=$logs",
        )

        val parsers = h.extensions.all(ExtensionPoints.LINK_PARSER)
        assertEquals("kt.bin", parsers.route("kt://a")!!.destination.name)
        assertEquals("js.bin", parsers.route("js://b")!!.destination.name)

        // Removing the optional JS provider must leave the compiled plugin routed and working.
        h.uninstall("loader.js")
        assertEquals(listOf("parser.kotlin"), h.extensions.registrations(ExtensionPoints.LINK_PARSER).map { it.ownerPluginId })
        val after = h.extensions.all(ExtensionPoints.LINK_PARSER)
        assertEquals("kt.bin", after.route("kt://c")!!.destination.name)
        assertEquals(null, after.route("js://c"), "a departed js plugin must not route")
        assertFalse(logs.any { "RUNTIME LEAKED" in it }, "cascade must dispose: $logs")
        h.shutdown()
    }

    @Test
    fun `a plugin-authored bad result degrades to null and stays diagnosable`() {
        JsEngineProbe.requireEngine()
        val (h, logs) = host()
        h.install(JsPluginLoaderPlugin())
        // Five broken shapes, one per failure mode the validator has to cover.
        h.loadSource(
            PluginSource(
                "js",
                "inline:" + """
                plugin.registerParser({priority: 900, parse: function () { return {}; }});
                plugin.registerParser({priority: 890, parse: function () { return 42; }});
                plugin.registerParser({priority: 880, parse: function () { return {url: 'file:///etc/passwd', fileName: 'a.bin'}; }});
                plugin.registerParser({priority: 870, parse: function () { throw new Error('script bug'); }});
                plugin.registerParser({priority: 860, parse: function () { return 'just a string'; }});
                """,
                mapOf("pluginId" to "parser.junk"),
            ),
        )
        h.loadSource(PluginSource("js", "inline:" + parser("junk", "good.bin", ""), mapOf("pluginId" to "parser.good")))

        val parsers = h.extensions.all(ExtensionPoints.LINK_PARSER)
        assertEquals(6, parsers.size)
        // `LinkParser.parse` must return null rather than propagate (CONVENTION.md §6), so one broken
        // parser costs only its own position in the route: the walk reaches the parser that works.
        val outcomes = parsers.map { runCatching { it.parse("junk://x") }.fold({ if (it == null) "null" else "ok" }, { "THREW:${it.javaClass.simpleName}" }) }
        assertEquals(List(5) { "null" } + listOf("ok"), outcomes, "logs=$logs")
        val reported = logs.count { "falling through to the next parser" in it }
        assertEquals(5, reported, "every junk shape must be reported on the first pass: $logs")
        assertEquals("good.bin", parsers.route("junk://y")!!.destination.name)

        // Each failure mode says what it was, so an author can tell "bad descriptor" from "my script threw".
        assertTrue(logs.any { "invalid request" in it }, "a shape violation must be named: $logs")
        assertTrue(logs.any { "parser #" in it && "failed (" in it }, "a script throw must be named: $logs")
        assertTrue(logs.count { "file:///etc/passwd" in it } >= 1, "a refused scheme should say so: $logs")
        // And none of them became a request the engine could act on.
        assertFalse(parsers.any { it.parse("junk://z")?.any { r -> !r.url.startsWith("https://cdn.example/") } == true })
        h.shutdown()
    }

    @Test
    fun `a stated-but-out-of-range priority is refused instead of silently defaulted`() {
        JsEngineProbe.requireEngine()
        val (h, logs) = host()
        h.install(JsPluginLoaderPlugin())
        // `plugin.registerParser` goes through nativeRegister, so a refusal surfaces as a *plugin*
        // throw during evaluation — the plugin never reaches a routed state.
        val ids = h.loadSource(
            PluginSource(
                "js",
                "inline:" + parser("huge", "huge.bin", "priority: 20000,"),
                mapOf("pluginId" to "parser.huge"),
            ),
        )
        // `loadSource` returns the ids it *installed*; a failed onLoad is reported by the plugin's
        // state, so that — not the id list — is the observable contract here.
        assertEquals(listOf("parser.huge"), ids, "the loader installs the plugin, the handshake fails it")
        assertEquals(
            emptyList<String>(),
            h.extensions.registrations(ExtensionPoints.LINK_PARSER).map { it.ownerPluginId },
            "a refused registration must leave nothing routed",
        )
        val state = h.diagnostics().plugins.firstOrNull { it.id == "parser.huge" }
        assertEquals("FAILED", state?.state?.name, "the host must mark the load failed: ${state?.state}")
        assertTrue(
            logs.any { "registration priority" in it && "-10000..10000" in it },
            "the refusal must name the field and the accepted range: $logs",
        )
        h.shutdown()
    }

    @Test
    fun `an out-of-range connections value fails the descriptor not the download`() {
        JsEngineProbe.requireEngine()
        val (h, logs) = host()
        h.install(JsPluginLoaderPlugin())
        h.loadSource(
            PluginSource(
                "js",
                "inline:" + """
                plugin.registerParser({parse: function () {
                  return { url: 'https://cdn.example/big.bin', fileName: 'big.bin', connections: 99999 };
                }});
                """.trimIndent(),
                mapOf("pluginId" to "parser.conn"),
            ),
        )
        val parsers = h.extensions.all(ExtensionPoints.LINK_PARSER)
        // The engine's own ceiling is 1..256; a script asking for 99999 must not get a silently
        // defaulted request, and must not throw into the router either (CONVENTION.md §6).
        assertEquals(null, parsers.single().parse("anything"), "an invalid connections value voids the descriptor")
        assertTrue(
            logs.any { "connections" in it && "1..256" in it && "falling through" in it },
            "the refusal must name the field, the range, and the fall-through: $logs",
        )
        assertFalse(logs.any { "RUNTIME LEAKED" in it }, "logs=$logs")
        h.shutdown()
    }

    @Test
    fun `an unload racing live routing never surfaces an exception to the caller`() {
        JsEngineProbe.requireEngine()
        val (h, logs) = host()
        h.install(JsPluginLoaderPlugin())
        // Per-call work, so an instance is genuinely mid-invocation when the uninstall lands.
        fun script(id: String) = """
            plugin.registerParser({parse: function (input) {
              var raw = (input && input.input !== undefined) ? String(input.input) : String(input);
              var pad = '';
              for (var i = 0; i < 400; i++) pad += 'x$id';
              if (raw.indexOf('$id://') !== 0) return null;
              return [{ url: 'https://cdn.example/' + raw.slice(${id.length + 3}), fileName: '$id.bin' }];
            }});
        """.trimIndent()
        h.loadSource(PluginSource("js", "inline:" + script("a"), mapOf("pluginId" to "parser.a")))
        h.loadSource(PluginSource("js", "inline:" + script("b"), mapOf("pluginId" to "parser.b")))
        val parsers = h.extensions.all(ExtensionPoints.LINK_PARSER)
        assertEquals(2, parsers.size)

        val outcomes = CopyOnWriteArrayList<Pair<String, String?>>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val threads = (0 until 4).flatMap { index ->
            listOf("a", "b").map { target ->
                Thread {
                    repeat(25) { n ->
                        val link = "$target://clip$index-$n"
                        try {
                            outcomes.add(link to parsers.route(link)?.destination?.name)
                        } catch (t: Throwable) {
                            failures.add(t)
                            outcomes.add(link to "THREW")
                        }
                    }
                }.apply { name = "route-$target-$index" }
            }
        }
        threads.forEach { it.start() }
        Thread.sleep(10)
        h.uninstall("parser.b")
        threads.forEach { it.join(60_000) }
        assertTrue(threads.none { it.isAlive }, "no routing thread may block on an unload")

        // A plugin that is leaving must be invisible as a failure, never as an exception.
        assertEquals(emptyList<Pair<String, String?>>(), outcomes.filter { it.second == "THREW" }, "first fault: ${failures.firstOrNull()}")
        // The survivor answered every one of its links, including while the other instance drained —
        // equal default priorities keep insertion order, so `parser.a` is always consulted first.
        val a = outcomes.filter { it.first.startsWith("a://") }
        assertTrue(a.isNotEmpty())
        assertTrue(a.all { it.second == "a.bin" }, "an a-link must always resolve while parser.a is loaded: $a")
        // The departing one either served its own link or declined it as it was torn down — the two
        // documented outcomes. Resolving someone else's link would be cross-talk between instances.
        val b = outcomes.filter { it.first.startsWith("b://") }
        assertTrue(b.isNotEmpty())
        assertTrue(b.all { it.second == "b.bin" || it.second == null }, "b-links must resolve to b.bin or nothing: $b")
        assertTrue(outcomes.none { (link, name) -> name != null && link.substringBefore("://") + ".bin" != name }, "no link may be answered by the wrong instance: $outcomes")

        // After teardown only the survivor is routed, and it is still correct.
        assertEquals(listOf("parser.a"), h.extensions.registrations(ExtensionPoints.LINK_PARSER).map { it.ownerPluginId })
        assertEquals("a.bin", h.extensions.all(ExtensionPoints.LINK_PARSER).route("a://final")!!.destination.name)
        assertFalse(logs.any { "RUNTIME LEAKED" in it }, "unload under traffic must still dispose: $logs")
        h.shutdown()
    }
}

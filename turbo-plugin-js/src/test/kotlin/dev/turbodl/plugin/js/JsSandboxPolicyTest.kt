package dev.turbodl.plugin.js

import dev.turbodl.core.TurboConfig
import dev.turbodl.plugin.runtime.PluginHost
import dev.turbodl.plugin.runtime.PluginSource
import dev.turbodl.plugin.runtime.PluginState
import dev.turbodl.plugin.runtime.ext.ExtensionPoints
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Where a JS plugin may put bytes on disk, and what it may register. Both are loader policy, never
 * script policy: a parser hands back a file *name* by default, and the two extension kinds that would
 * put a Kotlin object graph or a byte plane behind a script are refused outright.
 */
class JsSandboxPolicyTest {

    private fun host(logs: MutableList<String>) = PluginHost(logger = { msg, e -> logs.add(msg + (e?.let { " — ${it.message}" } ?: "")) })

    /**
     * Embed [value] as a JS string literal.
     *
     * A raw interpolation is not portable: a Windows path contains backslashes, and inside a JS
     * single-quoted string `\U` / `\t` are *escapes* — the script would receive a mangled path
     * (with a TAB and a backspace in it) instead of the one the test meant to hand it. Escaping
     * backslashes first keeps the test testing "an outside destination", not "a garbage string".
     */
    private fun jsString(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun parserScript(destination: String) = """
        plugin.registerParser({parse: function () {
          return [{ url: 'https://cdn.example/x.bin', ${if (destination.isEmpty()) "" else "destination: ${jsString(destination)},"} fileName: 'named.bin' }];
        }});
    """.trimIndent()

    @Test
    fun `a parser may name a file but not choose a directory`() {
        JsEngineProbe.requireEngine()
        val root = jsTempDir("turbo-js-policy")
        val logs = mutableListOf<String>()
        try {
            val h = host(logs)
            h.install(JsPluginLoaderPlugin(TurboConfig(), JsPluginConfig(sandboxRoot = root)))
            h.loadSource(PluginSource("js", "inline:" + parserScript(""), mapOf("pluginId" to "parser.name")))
            val request = h.extensions.all(ExtensionPoints.LINK_PARSER).single().parse("go")!!.single()
            assertEquals("named.bin", request.destination.name)
            assertTrue(request.destination.absolutePath.startsWith(sandboxDir(root, "parser.name").absolutePath), "actual: ${request.destination}")
            assertTrue("downloads" in request.destination.absolutePath, "relative names land in the plugin's own downloads dir: ${request.destination}")
            h.shutdown()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an outside destination is refused unless the host opts in`() {
        JsEngineProbe.requireEngine()
        val root = jsTempDir("turbo-js-ext")
        val outside = File(root, "outside.bin").absolutePath
        val logs = mutableListOf<String>()
        try {
            // Default: sandbox-only. A script cannot direct the engine to write anywhere it likes.
            val h = host(logs)
            h.install(JsPluginLoaderPlugin(TurboConfig(), JsPluginConfig(sandboxRoot = root)))
            h.loadSource(PluginSource("js", "inline:" + parserScript(outside), mapOf("pluginId" to "parser.confined")))
            val parsers = h.extensions.all(ExtensionPoints.LINK_PARSER)
            assertEquals(1, parsers.size)
            assertEquals(null, parsers.single().parse("go"), "a refused destination must cost the route, not the host")
            assertTrue(logs.any { "outside the plugin sandbox" in it }, "the refusal must say why: $logs")
            assertFalse(File(outside).exists(), "and nothing may have been created there")
            h.shutdown()

            // Opt-in: a host that genuinely means "write where the user picked" flips one flag.
            val h2 = host(mutableListOf())
            h2.install(JsPluginLoaderPlugin(TurboConfig(), JsPluginConfig(sandboxRoot = root, allowExternalDestination = true)))
            h2.loadSource(PluginSource("js", "inline:" + parserScript(outside), mapOf("pluginId" to "parser.open")))
            val granted = h2.extensions.all(ExtensionPoints.LINK_PARSER).single().parse("go")!!.single()
            assertEquals(File(outside), granted.destination, "the flag must take effect verbatim")
            h2.shutdown()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `storage survives unload unless the host asked for a purge`() {
        JsEngineProbe.requireEngine()
        val grants = JsPluginConfig(permissions = JsCapability.defaultGrant() + JsCapability.STORAGE)

        // Default: a plugin's own state is its state — a reinstall finding its session token is the
        // behaviour downstream hosts rely on.
        val keep = jsTempDir("turbo-js-keep")
        try {
            val logs = mutableListOf<String>()
            val h = host(logs)
            h.install(JsPluginLoaderPlugin(TurboConfig(), grants.copy(sandboxRoot = keep)))
            h.loadSource(
                PluginSource("js", "inline:" + "host.storage.set('token', 'abc'); plugin.registerParser({parse: function(){ return null; }});", mapOf("pluginId" to "parser.keep")),
            )
            val stored = sandboxDir(keep, "parser.keep").resolve("storage/token")
            assertTrue(stored.isFile, "state must be written under the plugin's own directory: ${stored.absolutePath}")
            h.uninstall("parser.keep")
            assertTrue(stored.isFile, "unloading must not delete a plugin's stored state: $logs")
            assertFalse(sandboxDir(keep, "parser.keep").resolve("downloads").listFiles()?.isNotEmpty() ?: false, "downloads are scratch and must be cleaned")
            h.shutdown()
        } finally {
            keep.deleteRecursively()
        }

        // Opt-in purge: a kiosk host that requires "leave nothing behind" gets exactly that.
        val purge = jsTempDir("turbo-js-purge")
        try {
            val logs = mutableListOf<String>()
            val h = host(logs)
            h.install(JsPluginLoaderPlugin(TurboConfig(), grants.copy(sandboxRoot = purge), purgeSandboxOnUnload = true))
            h.loadSource(
                PluginSource("js", "inline:" + "host.storage.set('token', 'abc'); plugin.registerParser({parse: function(){ return null; }});", mapOf("pluginId" to "parser.purged")),
            )
            assertTrue(sandboxDir(purge, "parser.purged").resolve("storage/token").isFile, "the write must happen before the purge decision")
            h.uninstall("loader.js")
            assertFalse(sandboxDir(purge, "parser.purged").exists(), "a purge host must find nothing left: $logs")
            h.shutdown()
        } finally {
            purge.deleteRecursively()
        }
    }

    @Test
    fun `a plugin may not register a backend, a service, or an unknown kind`() {
        JsEngineProbe.requireEngine()
        val logs = mutableListOf<String>()
        val h = host(logs)
        h.install(JsPluginLoaderPlugin())
        // `__turbodlRegister` is the raw primitive and answers with an envelope rather than throwing —
        // that is `abi.js`'s contract (`nativeRegister` is what turns ok:false into a catchable error).
        // So the assertions are made on the envelope AND on what the host ended up with.
        //
        // The two refusal codes are deliberately different: a kind the ABI closes by design (a backend
        // would put the byte plane behind a script; a service is a Kotlin object graph) is
        // `unsupported`, while a name that is simply not a kind is `validation`. An author gets a
        // different next step for "this is not available to JS" than for "you typo'd".
        val cases = listOf(
            Triple("backend", "unsupported", "DownloadBackend"),
            Triple("service", "unsupported", "service"),
            Triple("nonsense", "validation", "unknown registration kind"),
        )
        cases.forEach { (name, expectedCode, expectedInMessage) ->
            h.loadSource(
                PluginSource(
                    "js",
                    "inline:" + "var env = JSON.parse(__turbodlRegister('$name', 1, '{}'));" +
                        "host.log.info('RE.$name:' + env.ok + '|' + env.error.code + '|' + String(env.error.message).slice(0, 80));",
                    mapOf("pluginId" to "parser.reject.$name"),
                ),
            )
            // The refusal has to be readable by the author, not just effective: code and message both
            // travel in the envelope the script receives. Match only the plugin's own info line.
            val line = logs.first { it.startsWith("[parser.reject.$name] [info] RE.$name:") }
                .substringAfter("RE.$name:").trim()
            assertTrue(line.startsWith("false|$expectedCode|"), "$name must be refused as '$expectedCode': $line")
            assertTrue(expectedInMessage in line, "the refusal must name the concept for '$name': $line")
        }
        // Nothing at all was routed or published by three failed registration attempts.
        assertTrue(h.extensions.all(ExtensionPoints.LINK_PARSER).isEmpty(), "a refused kind must leave nothing routed")
        assertTrue(h.extensions.all(ExtensionPoints.TASK_PRE_HOOK).isEmpty())
        assertTrue(h.extensions.all(ExtensionPoints.TASK_POST_HOOK).isEmpty())
        assertEquals(setOf("loader.js"), h.services.snapshot().keys, "a script cannot publish a service")

        // And an author who propagates the refusal — which is what `plugin.*` does for you — fails the
        // load cleanly instead of ending up with a loaded-but-empty plugin.
        h.loadSource(
            PluginSource(
                "js",
                "inline:" + "var env = JSON.parse(__turbodlRegister('backend', 1, '{}')); if (!env.ok) throw new Error('backend refused: ' + env.error.code);",
                mapOf("pluginId" to "parser.propagate"),
            ),
        )
        val info = h.diagnostics().plugins.single { it.id == "parser.propagate" }
        assertEquals(PluginState.FAILED, info.state, "a propagated registration refusal must fail the load")
        assertTrue((info.error ?: "").contains("backend refused"), "actual: ${info.error}")
        h.shutdown()
    }

    @Test
    fun `one plugin id gets one sandbox and two ids never share it`() {
        JsEngineProbe.requireEngine()
        val root = jsTempDir("turbo-js-share")
        try {
            val logs = mutableListOf<String>()
            val h = host(logs)
            h.install(
                JsPluginLoaderPlugin(
                    TurboConfig(),
                    JsPluginConfig(sandboxRoot = root, permissions = JsCapability.defaultGrant() + JsCapability.STORAGE),
                ),
            )
            h.loadSource(PluginSource("js", "inline:host.storage.set('who', 'a');", mapOf("pluginId" to "parser.sa")))
            h.loadSource(PluginSource("js", "inline:host.storage.set('who', 'b');", mapOf("pluginId" to "parser.sb")))
            // Same key, two owners, two files — the quota and the contents are both per plugin. Storage
            // holds the JSON encoding of the value, which is why the file content is a quoted string.
            // Directory names carry a digest suffix (see JsSandbox), so they are derived, never guessed.
            assertEquals("\"a\"", File(sandboxDir(root, "parser.sa"), "storage/who").readText().trim(), "the first plugin's value must live in its own directory")
            assertEquals("\"b\"", File(sandboxDir(root, "parser.sb"), "storage/who").readText().trim(), "and the second must not overwrite it")
            h.shutdown()
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sandboxDir(root: File, pluginId: String): File = File(root, JsSandbox.dirNameFor(pluginId))

    @Test
    fun `plugin ids that sanitize to the same characters never share a sandbox`() {
        // Lossy sanitization alone would map all three of these onto `parser_a` — and hosts may
        // override a plugin id with arbitrary characters via the source's pluginId attribute. The
        // digest suffix must keep them apart while leaving the sanitized prefix recognizable.
        val colon = JsSandbox.dirNameFor("parser:a")
        val slash = JsSandbox.dirNameFor("parser/a")
        val plain = JsSandbox.dirNameFor("parser_a")
        assertTrue(colon.startsWith("parser_a_"), "the sanitized prefix must stay recognizable: $colon")
        assertEquals(colon.substringBeforeLast('_'), slash.substringBeforeLast('_'), "sanitization must indeed be lossy, or this test proves nothing")
        assertEquals(slash.substringBeforeLast('_'), plain.substringBeforeLast('_'))
        assertNotEquals(colon, slash, "distinct plugin ids must get distinct sandbox directories")
        assertNotEquals(colon, plain, "distinct plugin ids must get distinct sandbox directories")
        assertNotEquals(slash, plain, "distinct plugin ids must get distinct sandbox directories")
    }
}

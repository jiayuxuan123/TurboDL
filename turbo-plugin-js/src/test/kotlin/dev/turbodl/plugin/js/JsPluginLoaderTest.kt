package dev.turbodl.plugin.js

import dev.turbodl.core.TurboConfig
import dev.turbodl.plugin.runtime.PluginHost
import dev.turbodl.plugin.runtime.PluginLoaderProvider
import dev.turbodl.plugin.runtime.PluginSource
import dev.turbodl.plugin.runtime.PluginState
import dev.turbodl.plugin.runtime.ext.ExtensionPoints
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for the JS provider, driven through the real kernel: a
 * [JsPluginLoaderPlugin] is installed, scripts are dispatched with
 * [PluginHost.loadSource], and assertions are made on what the *host* can see — extension
 * registrations, services, diagnostics — not on JS internals.
 *
 * Every case that touches QuickJS starts with [JsEngineProbe.requireEngine], a JUnit assumption, so a
 * platform without a bundled native library is reported SKIPPED rather than passing a test that never
 * ran or failing with a confusing UnsatisfiedLinkError.
 */
class JsPluginLoaderTest {

    private fun loader(
        engineConfig: TurboConfig = TurboConfig(),
        jsConfig: JsPluginConfig = JsPluginConfig(),
        priority: Int? = null,
    ): JsPluginLoaderPlugin = JsPluginLoaderPlugin(engineConfig, jsConfig, priority)

    private fun host(): PluginHost = PluginHost(logger = { msg, e -> println("[host] $msg${e?.let { " — ${it.message}" } ?: ""}") })

    @Test
    fun `loader registers the provider extension point and a service`() {
        JsEngineProbe.requireEngine()
        val h = host()
        h.install(loader())
        val providers = h.extensions.all(dev.turbodl.plugin.runtime.PluginLoaderProvider.KEY)
        assertTrue(providers.any { it.loaderId == "js" }, "js loader must be registered, got $providers")
        assertTrue(h.services.has("loader.js"), "loader.js service must be published")
        h.shutdown()
    }

    @Test
    fun `canLoad accepts only js sources`() {
        JsEngineProbe.requireEngine()
        val h = host()
        h.install(loader())
        val provider = h.extensions.all(dev.turbodl.plugin.runtime.PluginLoaderProvider.KEY).first()
        assertTrue(provider.canLoad(PluginSource("js", "inline:1")))
        assertFalse(provider.canLoad(PluginSource("kotlin-class", "dev.x.Y")))
        assertFalse(provider.canLoad(PluginSource("jar", "file:/a.jar")))
        h.shutdown()
    }

    @Test
    fun `loadSource installs a script plugin and routes its parser`() {
        JsEngineProbe.requireEngine()
        val h = host()
        h.install(loader())
        val script = """
            plugin.defineMeta({ name: 'demo-parser' });
            plugin.registerParser({
              parse: function (input) {
                var raw = input && input.input !== undefined ? input.input : String(input);
                if (raw.indexOf('demo://') !== 0) return null;
                return [{ url: 'https://example.com/' + raw.slice(7), fileName: 'clip.bin' }];
              }
            });
        """.trimIndent()
        val ids = h.loadSource(PluginSource("js", "inline:$script", mapOf("pluginId" to "parser.demo")))
        assertEquals(listOf("parser.demo"), ids, "the produced plugin id must be reported")

        val parsers = h.extensions.all(ExtensionPoints.LINK_PARSER)
        assertEquals(1, parsers.size, "the JS parser must be visible to consumers")

        val requests = parsers.first().parse("demo://movie.mp4")
        assertNotNull(requests, "a matching link must parse")
        assertEquals(1, requests!!.size)
        assertEquals("https://example.com/movie.mp4", requests[0].url)
        // Destination policy: a script gets a file name, the host chooses the directory.
        assertTrue(requests[0].destination.absolutePath.contains(File("downloads").name), "actual: ${requests[0].destination}")

        // Non-matching input returns null, so the router can try the next parser.
        assertEquals(null, parsers.first().parse("https://other.example/x.bin"))
        h.shutdown()
    }

    @Test
    fun `uninstalling a script plugin removes its parser from the registry`() {
        JsEngineProbe.requireEngine()
        val h = host()
        h.install(loader())
        h.loadSource(PluginSource("js", "inline:plugin.registerParser({parse: function(){return null;}});", mapOf("pluginId" to "parser.gone")))
        assertEquals(1, h.extensions.all(ExtensionPoints.LINK_PARSER).size)
        h.uninstall("parser.gone")
        assertEquals(0, h.extensions.all(ExtensionPoints.LINK_PARSER).size, "unloaded plugin must leave no parser routed")
        h.shutdown()
    }

    @Test
    fun `uninstalling the loader cascades to every script it produced`() {
        JsEngineProbe.requireEngine()
        val h = host()
        h.install(loader())
        val body = "plugin.registerTaskPreHook({ beforeSubmit: function (p) { return p.request; } });"
        h.loadSource(PluginSource("js", "inline:$body", mapOf("pluginId" to "hook.a")))
        h.loadSource(PluginSource("js", "inline:$body", mapOf("pluginId" to "hook.b")))
        assertEquals(2, h.extensions.all(ExtensionPoints.TASK_PRE_HOOK).size)

        h.uninstall("loader.js")
        assertEquals(0, h.extensions.all(ExtensionPoints.TASK_PRE_HOOK).size, "cascade must unload produced plugins")
        assertFalse(h.services.has("loader.js"))
        h.shutdown()
    }

    @Test
    fun `a script that throws at load is FAILED and leaves nothing routed`() {
        JsEngineProbe.requireEngine()
        val h = host()
        h.install(loader())
        val ids = h.loadSource(
            PluginSource("js", "inline:plugin.registerParser({parse:function(){return [];}}); throw new Error('boom at load');", mapOf("pluginId" to "parser.bad")),
        )
        // The host catches onLoad failures and marks the plugin FAILED; loadSource still reports ids.
        assertEquals(listOf("parser.bad"), ids)
        val info = h.diagnostics().plugins.first { it.id == "parser.bad" }
        assertEquals(dev.turbodl.plugin.runtime.PluginState.FAILED, info.state, "error=${info.error}")
        assertEquals(0, h.extensions.all(ExtensionPoints.LINK_PARSER).size, "a failed load must roll back its registrations")
        h.shutdown()
    }

    @Test
    fun `an unreadable source yields no plugin and does not throw`() {
        JsEngineProbe.requireEngine()
        val h = host()
        h.install(loader())
        val ids = h.loadSource(PluginSource("js", "/definitely/not/here/plugin.js"))
        assertTrue(ids.isEmpty(), "a missing script must produce nothing, got $ids")
        h.shutdown()
    }

    @Test
    fun `priority override lets a host demote all js registrations`() {
        JsEngineProbe.requireEngine()
        val h = host()
        h.install(loader(priority = 50))
        h.loadSource(PluginSource("js", "inline:plugin.registerParser({parse:function(){return null;}});", mapOf("pluginId" to "parser.low")))
        val reg = h.extensions.registrations(ExtensionPoints.LINK_PARSER).first()
        assertEquals(50, reg.priority, "loader default priority must win when the script states none")
        h.shutdown()
    }

    @Test
    fun `a script may state its own priority`() {
        JsEngineProbe.requireEngine()
        val h = host()
        h.install(loader())
        h.loadSource(
            PluginSource(
                "js",
                "inline:plugin.registerParser({ priority: 300, parse: function () { return null; } });",
                mapOf("pluginId" to "parser.high"),
            ),
        )
        assertEquals(300, h.extensions.registrations(ExtensionPoints.LINK_PARSER).first().priority)
        h.shutdown()
    }

    @Test
    fun `a script asking for an ungranted capability is refused at load`() {
        JsEngineProbe.requireEngine()
        val h = host()
        // Loader grants the default set (no storage); the script asks for storage.
        h.install(loader(jsConfig = JsPluginConfig(permissions = setOf(JsCapability.HTTP, JsCapability.CRYPTO, JsCapability.LOG))))
        h.loadSource(
            PluginSource(
                "js",
                "inline:plugin.requires({ permissions: ['storage'] });",
                mapOf("pluginId" to "parser.greedy"),
            ),
        )
        val info = h.diagnostics().plugins.first { it.id == "parser.greedy" }
        assertEquals(dev.turbodl.plugin.runtime.PluginState.FAILED, info.state)
        assertTrue(info.error!!.contains("storage"), "error should name the missing capability: ${info.error}")
        h.shutdown()
    }

    @Test
    fun `source permissions narrow the grant and are never widened`() {
        JsEngineProbe.requireEngine()
        val h = host()
        h.install(loader(jsConfig = JsPluginConfig(permissions = setOf(JsCapability.HTTP, JsCapability.CRYPTO))))
        // Asks for http + storage; storage is not granted → narrowing yields http only.
        h.loadSource(
            PluginSource(
                "js",
                "inline:plugin.requires({ permissions: ['http'] }); plugin.registerParser({parse:function(){return null;}});",
                mapOf("pluginId" to "parser.narrow", "permissions" to "http,storage"),
            ),
        )
        val info = h.diagnostics().plugins.first { it.id == "parser.narrow" }
        assertEquals(dev.turbodl.plugin.runtime.PluginState.LOADED, info.state, "error=${info.error}")
        // crypto was granted by the loader but the source asked only for http, so crypto is out.
        val probe = h.loadSource(
            PluginSource(
                "js",
                "inline:try { host.crypto.digest('SHA-256', 'x'); plugin.registerParser({parse:function(){return null;}}); } catch (e) { throw new Error('digest refused: ' + e.code); }",
                mapOf("pluginId" to "parser.probe", "permissions" to "http"),
            ),
        )
        assertEquals(listOf("parser.probe"), probe)
        val probeInfo = h.diagnostics().plugins.first { it.id == "parser.probe" }
        assertEquals(dev.turbodl.plugin.runtime.PluginState.FAILED, probeInfo.state)
        assertTrue(probeInfo.error!!.contains("permission"), "actual: ${probeInfo.error}")
        h.shutdown()
    }

    @Test
    fun `js identity is visible to the script as data only`() {
        JsEngineProbe.requireEngine()
        val h = host()
        h.install(loader())
        val script = """
            plugin.registerParser({
              parse: function (input) {
                if (typeof plugin.id !== 'string') throw new Error('plugin.id must be a string');
                if (plugin.manifest === undefined) throw new Error('manifest must be seeded');
                var exposed = typeof host.PluginContext;
                if (exposed !== 'undefined') throw new Error('kotlin internals leaked: ' + exposed);
                return [{ url: 'https://example.com/' + plugin.id, fileName: 'x.bin' }];
              }
            });
        """.trimIndent()
        h.loadSource(PluginSource("js", "inline:$script", mapOf("pluginId" to "parser.identity")))
        val parser = h.extensions.all(ExtensionPoints.LINK_PARSER).single()
        val requests = parser.parse("anything")
        assertEquals("https://example.com/parser.identity", requests!!.single().url)
        h.shutdown()
    }
}

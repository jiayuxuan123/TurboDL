package dev.turbodl.plugin.runtime

import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.TaskState
import dev.turbodl.core.TurboEvent
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginHostTest {

    private fun req() = DownloadRequest("http://x/y", File("y"))

    @Test
    fun lifecycleLoadsAndUnloadsDrainingDisposers() {
        val host = PluginHost(logger = { _, _ -> })
        var loaded = false
        var cleaned = false

        val plugin = object : Plugin {
            override val id = "p1"
            override fun onLoad(context: PluginContext) {
                loaded = true
                context.disposer.register { cleaned = true }
            }
        }
        host.install(plugin)
        assertTrue(loaded, "onLoad should run")
        assertEquals(PluginState.LOADED, host.diagnostics().plugins.first().state)

        host.uninstall("p1")
        assertTrue(cleaned, "disposer must run on unload")
        assertTrue(host.diagnostics().plugins.isEmpty(), "plugin removed after uninstall")
    }

    @Test
    fun dependencyGatingLoadsInAnyOrder() {
        val host = PluginHost(logger = { _, _ -> })
        val loadOrder = mutableListOf<String>()

        // consumer depends on service "svc" provided by provider
        val consumer = object : Plugin {
            override val id = "consumer"
            override val dependencies = setOf("svc")
            override fun onLoad(context: PluginContext) { loadOrder.add("consumer") }
        }
        val provider = object : Plugin {
            override val id = "provider"
            override fun onLoad(context: PluginContext) {
                loadOrder.add("provider")
                context.registerService("svc", "hello")
            }
        }

        // install consumer FIRST (deps missing) then provider — consumer should load after.
        host.install(consumer)
        assertEquals(PluginState.WAITING, host.diagnostics().plugins.first { it.id == "consumer" }.state)
        host.install(provider)

        assertEquals(listOf("provider", "consumer"), loadOrder)
        assertEquals(PluginState.LOADED, host.diagnostics().plugins.first { it.id == "consumer" }.state)
    }

    @Test
    fun missingDependencyKeepsPluginWaiting() {
        val host = PluginHost(logger = { _, _ -> })
        val p = object : Plugin {
            override val id = "needsX"
            override val dependencies = setOf("x")
            override fun onLoad(context: PluginContext) {}
        }
        host.install(p)
        val info = host.diagnostics().plugins.first()
        assertEquals(PluginState.WAITING, info.state)
        assertEquals(setOf("x"), info.missingDependencies)
    }

    @Test
    fun eventListenerExceptionIsIsolated() {
        val host = PluginHost(logger = { _, _ -> })
        var goodReceived = 0

        host.install(object : Plugin {
            override val id = "bad"
            override fun onLoad(context: PluginContext) {
                context.onEvent { throw RuntimeException("boom") }
            }
        })
        host.install(object : Plugin {
            override val id = "good"
            override fun onLoad(context: PluginContext) {
                context.onEvent { goodReceived++ }
            }
        })

        // A throwing listener must not prevent the other from receiving, nor propagate.
        host.publishEvent(TurboEvent.StateChanged(1, TaskState.DOWNLOADING))
        assertEquals(1, goodReceived)
    }

    @Test
    fun requestInterceptorsChainAndAreRemovedOnUnload() {
        val host = PluginHost(logger = { _, _ -> })
        host.install(object : Plugin {
            override val id = "hdr"
            override fun onLoad(context: PluginContext) {
                context.interceptRequest { it.copy(headers = it.headers + ("X-Test" to "1")) }
            }
        })
        val out = host.applyRequestInterceptors(req())
        assertEquals("1", out.headers["X-Test"])

        host.uninstall("hdr")
        val out2 = host.applyRequestInterceptors(req())
        assertNull(out2.headers["X-Test"], "interceptor must be removed after unload")
    }

    @Test
    fun extensionRegistryOrdersByPriorityAndCleansUp() {
        val host = PluginHost(logger = { _, _ -> })
        val key = ExtensionPointKey.of<() -> String>("test.ep")

        host.install(object : Plugin {
            override val id = "lowhigh"
            override fun onLoad(context: PluginContext) {
                context.registerExtension(key, { "low" }, priority = 1)
                context.registerExtension(key, { "high" }, priority = 10)
            }
        })
        val impls = host.extensions.all(key).map { it() }
        assertEquals(listOf("high", "low"), impls)

        host.uninstall("lowhigh")
        assertTrue(host.extensions.all(key).isEmpty(), "extensions removed on unload")
    }

    @Test
    fun onLoadFailureRollsBackPartialRegistrations() {
        val host = PluginHost(logger = { _, _ -> })
        host.install(object : Plugin {
            override val id = "halfbroken"
            override fun onLoad(context: PluginContext) {
                context.registerService("halfsvc", Any())
                throw RuntimeException("fail after partial registration")
            }
        })
        val info = host.diagnostics().plugins.first()
        assertEquals(PluginState.FAILED, info.state)
        // service registered before the failure must be rolled back by disposer.
        assertFalse(host.services.has("halfsvc"), "partial service must be rolled back")
    }

    @Test
    fun pluginLoaderProviderIsTheOnlyKernelKnownExtensionPoint() {
        // Sanity: kernel exposes PluginLoaderProvider.KEY; nothing is auto-registered.
        val host = PluginHost(logger = { _, _ -> })
        assertTrue(host.extensions.all(PluginLoaderProvider.KEY).isEmpty())
    }
}

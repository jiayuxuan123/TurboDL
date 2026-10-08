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

    @Test
    fun incompatiblePluginIsRejectedBeforeOnLoad() {
        val host = PluginHost(logger = { _, _ -> })
        var loaded = false
        // Require a MAJOR higher than the host: must be rejected, onLoad must never run.
        host.install(object : Plugin {
            override val id = "future"
            override val requiredApiVersion =
                dev.turbodl.core.ApiVersion(dev.turbodl.core.ApiVersion.CURRENT.major + 1, 0, 0)
            override fun onLoad(context: PluginContext) { loaded = true }
        })
        val info = host.diagnostics().plugins.first()
        assertEquals(PluginState.INCOMPATIBLE, info.state)
        assertFalse(loaded, "onLoad must not run for an incompatible plugin")
    }

    @Test
    fun compatiblePluginSeesHostApiVersion() {
        val host = PluginHost(logger = { _, _ -> })
        var seen: dev.turbodl.core.ApiVersion? = null
        host.install(object : Plugin {
            override val id = "compat"
            override val requiredApiVersion = dev.turbodl.core.ApiVersion(1, 0, 0)
            override fun onLoad(context: PluginContext) { seen = context.apiVersion }
        })
        assertEquals(PluginState.LOADED, host.diagnostics().plugins.first().state)
        assertEquals(dev.turbodl.core.ApiVersion.CURRENT, seen)
    }

    // ---------- PluginSource dispatch ----------

    private class RecordingLoader(
        override val loaderId: String,
        val kinds: Set<String>,
        val produced: MutableList<Plugin>,
        val onLoadError: (() -> Unit)? = null,
        /** Ignore the uri filter — for a loader that legitimately hands back a whole batch. */
        val alwaysProduceAll: Boolean = false,
    ) : PluginLoaderProvider {
        var canLoadCalls = 0
        var loadCalls = 0
        override fun canLoad(source: PluginSource): Boolean {
            canLoadCalls++
            onLoadError?.invoke()
            return source.kind in kinds
        }

        override fun load(source: PluginSource): List<Plugin> {
            loadCalls++
            return if (alwaysProduceAll) produced.toList() else produced.filter { it.id == source.uri || produced.size == 1 }
        }
    }

    private fun loaderPlugin(
        id: String,
        loader: PluginLoaderProvider,
        priority: Int = 0,
    ): Plugin = object : Plugin {
        override val id = id
        override fun onLoad(context: PluginContext) {
            context.registerExtension(PluginLoaderProvider.KEY, loader, priority)
        }
    }

    @Test
    fun loadSourceDispatchesToMatchingLoaderAndInstalls() {
        val host = PluginHost(logger = { _, _ -> })
        val child = object : Plugin {
            override val id = "js.demo"
            var loaded = false
            override fun onLoad(context: PluginContext) { loaded = true }
        }
        val loader = RecordingLoader("js", setOf("js"), mutableListOf(child))
        host.install(loaderPlugin("loader.js", loader))

        val ids = host.loadSource(PluginSource(kind = "js", uri = "js.demo"))

        assertEquals(listOf("js.demo"), ids)
        assertEquals(1, loader.loadCalls)
        assertTrue(host.diagnostics().plugins.any { it.id == "js.demo" && it.state == PluginState.LOADED })
    }

    @Test
    fun loadSourceReturnsEmptyWhenNothingMatches() {
        val host = PluginHost(logger = { _, _ -> })
        val loader = RecordingLoader("js", setOf("js"), mutableListOf())
        host.install(loaderPlugin("loader.js", loader))

        assertTrue(host.loadSource(PluginSource(kind = "kotlin-class", uri = "X")).isEmpty())
        assertEquals(0, loader.loadCalls, "a loader that cannot handle the source must not be asked to load")
    }

    @Test
    fun loadSourceWithoutAnyLoaderIsDiagnosedNotThrown() {
        val host = PluginHost(logger = { _, _ -> })
        assertTrue(host.loadSource(PluginSource(kind = "js", uri = "y")).isEmpty())
    }

    @Test
    fun loadSourceSelectsHighestPriorityLoader() {
        val host = PluginHost(logger = { _, _ -> })
        val low = RecordingLoader("low", setOf("js"), mutableListOf(object : Plugin {
                override val id = "from.low"
                override fun onLoad(context: PluginContext) {}
            }))
        val high = RecordingLoader("high", setOf("js"), mutableListOf(object : Plugin {
                override val id = "from.high"
                override fun onLoad(context: PluginContext) {}
            }))
        host.install(loaderPlugin("loader.low", low, priority = 1))
        host.install(loaderPlugin("loader.high", high, priority = 10))

        val ids = host.loadSource(PluginSource(kind = "js", uri = "z"))
        assertEquals(listOf("from.high"), ids, "highest-priority matching loader wins")
        assertEquals(0, low.loadCalls)
    }

    @Test
    fun loadSourceIsolatesThrowingLoaderAndFallsThrough() {
        val host = PluginHost(logger = { _, _ -> })
        val broken = RecordingLoader(
            loaderId = "broken",
            kinds = setOf("js"),
            produced = mutableListOf(),
            onLoadError = { throw RuntimeException("loader is sick") },
        )
        val good = RecordingLoader("good", setOf("js"), mutableListOf(object : Plugin {
                override val id = "ok"
                override fun onLoad(context: PluginContext) {}
            }))
        host.install(loaderPlugin("loader.broken", broken, priority = 100))
        host.install(loaderPlugin("loader.good", good))

        val ids = host.loadSource(PluginSource(kind = "js", uri = "z"))
        assertEquals(listOf("ok"), ids, "a throwing canLoad must not abort dispatch")
        assertEquals(1, broken.canLoadCalls)
    }

    @Test
    fun uninstallingLoaderCascadesToPluginsItProduced() {
        val host = PluginHost(logger = { _, _ -> })
        val key = ExtensionPointKey.of<() -> String>("test.ep")
        var cleaned = false
        val child = object : Plugin {
            override val id = "js.child"
            override fun onLoad(context: PluginContext) {
                context.registerExtension(key, { "from-js" })
                context.disposer.register { cleaned = true }
            }
        }
        host.install(loaderPlugin("loader.js", RecordingLoader("js", setOf("js"), mutableListOf(child))))
        host.loadSource(PluginSource(kind = "js", uri = "js.child"))
        assertEquals("from-js", host.extensions.all(key).firstOrNull()?.invoke())

        host.uninstall("loader.js")

        assertTrue(cleaned, "child disposer must run when its loader is unloaded")
        assertTrue(host.extensions.all(key).isEmpty(), "child extension must be gone")
        assertTrue(host.diagnostics().plugins.isEmpty(), "both loader and child removed")
    }

    @Test
    fun uninstallingChildDoesNotUnloadItsLoader() {
        val host = PluginHost(logger = { _, _ -> })
        val loader = RecordingLoader("js", setOf("js"), mutableListOf(object : Plugin {
                override val id = "js.a"
                override fun onLoad(context: PluginContext) {}
            }))
        host.install(loaderPlugin("loader.js", loader))
        host.loadSource(PluginSource(kind = "js", uri = "js.a"))

        host.uninstall("js.a")

        assertTrue(host.diagnostics().plugins.map { it.id }.contains("loader.js"))
        // And the loader can still serve a fresh source afterwards.
        assertEquals(listOf("js.a"), host.loadSource(PluginSource(kind = "js", uri = "js.a")))
    }

    /**
     * Ownership must follow the install that actually happened.
     *
     * A loader may hand back a plugin whose id is already in the host — [PluginHost.loadSource]
     * deliberately skips that duplicate rather than replacing someone else's plugin. If the skipped
     * id were still recorded as "produced by this loader", uninstalling the loader would tear down a
     * plugin it never installed, so the returned list and the ownership edge must both cover only the
     * ids newly installed by this call.
     */
    @Test
    fun loadSourceDoesNotClaimOwnershipOfAPluginItDidNotInstall() {
        val host = PluginHost(logger = { _, _ -> })
        var foreignUnloaded = false
        val foreign = object : Plugin {
            override val id = "js.dup"
            override fun onLoad(context: PluginContext) {}
            override fun onUnload() { foreignUnloaded = true }
        }
        val loader = RecordingLoader("js", setOf("js"), mutableListOf(foreign))
        host.install(loaderPlugin("loader.js", loader))
        // Installed by someone else entirely — not through this loader.
        host.install(foreign)

        val ids = host.loadSource(PluginSource(kind = "js", uri = "js.dup"))

        assertTrue(ids.isEmpty(), "a skipped duplicate must not be reported as installed by this source: $ids")

        host.uninstall("loader.js")

        assertFalse(foreignUnloaded, "uninstalling the loader must not cascade to a plugin it never installed")
        assertTrue(host.diagnostics().plugins.map { it.id }.contains("js.dup"), "and the foreign plugin must still be present")
    }

    /** The same edge, seen from the other side: a *fresh* id from the same call is still owned. */
    @Test
    fun loadSourceClaimsOnlyFreshIdsWhenSomeAreDuplicates() {
        val host = PluginHost(logger = { _, _ -> })
        var freshUnloaded = false
        var foreignUnloaded = false
        val fresh = object : Plugin {
            override val id = "js.fresh"
            override fun onLoad(context: PluginContext) {}
            override fun onUnload() { freshUnloaded = true }
        }
        val foreign = object : Plugin {
            override val id = "js.dup"
            override fun onLoad(context: PluginContext) {}
            override fun onUnload() { foreignUnloaded = true }
        }
        val loader = RecordingLoader("js", setOf("js"), mutableListOf(foreign, fresh), alwaysProduceAll = true)
        host.install(loaderPlugin("loader.js", loader))
        host.install(foreign)

        val ids = host.loadSource(PluginSource(kind = "js", uri = "js.two"))

        assertEquals(listOf("js.fresh"), ids, "only the newly installed id may be reported")

        host.uninstall("loader.js")

        assertTrue(freshUnloaded, "the id this loader really installed must cascade")
        assertFalse(foreignUnloaded, "the id it skipped must not")
    }

    // ---------- teardown concurrency ----------

    /**
     * A slow plugin's unload must not park the kernel lock.
     *
     * Teardown legitimately blocks (a JS drain, host I/O, a synchronous engine close). If that ran
     * under [PluginHost]'s lock, every other thread's install/uninstall would stall behind one
     * plugin's shutdown. This test holds a plugin inside `onUnload`, then proves an unrelated install
     * on another thread still completes promptly.
     */
    @Test
    fun aBlockingUnloadDoesNotStallOtherInstalls() {
        val host = PluginHost(logger = { _, _ -> })
        val inside = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val slow = object : Plugin {
            override val id = "slow"
            override fun onLoad(context: PluginContext) {}
            override fun onUnload() {
                inside.countDown()
                release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        host.install(slow)

        val unloader = Thread { host.uninstall("slow") }.apply { isDaemon = true; start() }
        assertTrue(inside.await(5, java.util.concurrent.TimeUnit.SECONDS), "the slow unload must have started")

        // With teardown outside the lock, this returns immediately; if it were held, we would block
        // here until `release` opens (up to 10s) and the assertion below would time out instead.
        val other = object : Plugin {
            override val id = "other"
            override fun onLoad(context: PluginContext) {}
        }
        val done = java.util.concurrent.CountDownLatch(1)
        Thread { host.install(other); done.countDown() }.apply { isDaemon = true; start() }
        assertTrue(
            done.await(2, java.util.concurrent.TimeUnit.SECONDS),
            "an install must not wait for an unrelated plugin's teardown",
        )
        assertTrue(host.diagnostics().plugins.any { it.id == "other" }, "the unrelated plugin must be installed")

        release.countDown()
        unloader.join(5_000)
        assertFalse(host.diagnostics().plugins.any { it.id == "slow" }, "the slow plugin is gone once teardown finishes")
    }

    /** Reverse install order for [PluginHost.shutdown] must follow the install sequence, not hash order. */
    @Test
    fun shutdownUnloadsInReverseInstallOrder() {
        val host = PluginHost(logger = { _, _ -> })
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        for (id in listOf("first", "second", "third", "fourth")) {
            host.install(object : Plugin {
                override val id = id
                override fun onLoad(context: PluginContext) {}
                override fun onUnload() { order.add(id) }
            })
        }
        host.shutdown()
        assertEquals(
            listOf("fourth", "third", "second", "first"),
            order.toList(),
            "shutdown must unload strictly last-installed-first, not in map iteration order",
        )
    }

    /** Once shutdown begins the host is terminal: no plugin may slip into the registry afterwards. */
    @Test
    fun installAfterShutdownIsRefused() {
        val host = PluginHost(logger = { _, _ -> })
        host.install(object : Plugin {
            override val id = "p"
            override fun onLoad(context: PluginContext) {}
        })
        host.shutdown()
        host.install(object : Plugin {
            override val id = "late"
            override fun onLoad(context: PluginContext) {}
        })
        assertTrue(host.diagnostics().plugins.isEmpty(), "a post-shutdown install must be refused")
    }
}

package dev.turbodl.plugin.runtime

import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.TurboEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * PluginHost — the plugin runtime kernel.
 *
 * Responsibilities (and ONLY these; no business logic, no built-in loaders/backends/parsers):
 *  - plugin lifecycle scheduling (dependency-gated onLoad, onUnload);
 *  - disposer draining on unload (no leaked listeners/registrations/services);
 *  - the extension-point registry (incl. the sole kernel-known [PluginLoaderProvider]);
 *  - the type-safe [EventBus] (observation + submit-time interception);
 *  - the lightweight [ServiceRegistry] + dependency resolution;
 *  - a diagnostics snapshot API.
 *
 * The host does NOT touch turbodl-core's TurboClient directly; wiring the bus to a client is the
 * job of an integration layer (e.g. turbo-plugin-bootstrap). The host only depends on core's
 * public data models ([TurboEvent], [DownloadRequest]).
 *
 * Thread-safety: registration maps are concurrent; load/unload are synchronized to keep the
 * dependency-resolution loop consistent.
 */
class PluginHost(
    private val logger: (String, Throwable?) -> Unit = { msg, e ->
        if (e != null) System.err.println("[TurboDL-plugin] $msg: ${e.message}") else println("[TurboDL-plugin] $msg")
    },
) {
    val services = ServiceRegistry()
    val extensions = ExtensionRegistry()
    val eventBus = EventBus(logger)

    private val lock = Any()
    private val registered = ConcurrentHashMap<String, Managed>()

    /**
     * Ids whose teardown is in progress but not yet finished.
     *
     * Teardown deliberately does **not** hold [lock] across `onUnload` + the disposer drain: a
     * plugin's unload may block for seconds (JS drain, host I/O, a synchronous engine close), and
     * parking the kernel lock for that long would stall every other thread's install/uninstall
     * behind one plugin's shutdown. To keep the "at most one live instance per id" guarantee
     * without the lock, an id is claimed here while it unloads; [install] refuses an id that is
     * mid-teardown rather than racing a second instance into the registry.
     *
     * (Dependency *resolution* — [tryLoadWaiting] — still runs under the lock: it is the loop that
     * decides load order and must observe one consistent registry snapshot.)
     */
    private val unloading = ConcurrentHashMap.newKeySet<String>()

    /** Monotonic install counter — the only honest source of "reverse install order". */
    private var nextSeq = 0L

    /** Set once [shutdown] begins; the host is terminal, so no further installs are accepted. */
    private var shuttingDown = false

    /**
     * ownerPluginId → ids of the plugins that owner's loader produced (via [loadSource]).
     *
     * Language-agnostic ownership bookkeeping: whoever materializes plugins out of a
     * [PluginSource] owns their lifetime — attributed to the plugin that registered the
     * [PluginLoaderProvider] (the registration's owner), not to a loader-chosen id, so no naming
     * convention is required. Unloading a loader therefore cascades to its children, which is what
     * makes "unload the JS provider" safe: otherwise the produced plugins would stay LOADED, still
     * routed to by consumers, holding engine resources with no owner left.
     */
    private val producedBy = ConcurrentHashMap<String, MutableSet<String>>()

    private class Managed(
        val plugin: Plugin,
        var state: PluginState,
        val seq: Long,
        val disposer: Disposer = Disposer(),
        var error: String? = null,
    )

    // ---------- public API ----------

    /**
     * Register a plugin. If its dependencies are already satisfied it loads immediately;
     * otherwise it enters WAITING and loads automatically once they appear.
     */
    fun install(plugin: Plugin) {
        synchronized(lock) {
            if (shuttingDown) {
                logger("plugin '${plugin.id}' rejected: the host has been shut down", null)
                return
            }
            if (registered.containsKey(plugin.id) || plugin.id in unloading) {
                logger("plugin '${plugin.id}' already installed; ignoring", null)
                return
            }
            registered[plugin.id] = Managed(plugin, PluginState.WAITING, nextSeq++)
            tryLoadWaiting()
        }
    }

    /**
     * Dispatch a [PluginSource] through the registered [PluginLoaderProvider] implementations
     * (highest priority first, first `canLoad` match wins), install the plugins the loader
     * produces through the normal lifecycle, and return their ids.
     *
     * This closes the `PluginSource → loader → Plugin → host` loop generically: the kernel
     * never interprets the source itself, it only routes. A loader that produces no plugin
     * (no match, bad source, load error) yields an empty list and a diagnostic; a load error
     * inside a provider is isolated here so one broken loader cannot corrupt the host.
     *
     * Ownership: the produced plugins are recorded against the plugin that registered the
     * matching [PluginLoaderProvider], so [uninstall] of that plugin cascades to them. Nothing
     * depends on how a loader names its [PluginLoaderProvider.loaderId].
     *
     * Reloading a source whose plugin id is already installed is rejected by [install]
     * (duplicate ids are ignored); uninstall the existing id first if you intend a reload.
     */
    fun loadSource(source: PluginSource): List<String> = synchronized(lock) {
        val match = extensions.registrations(PluginLoaderProvider.KEY).firstOrNull { reg ->
            runCatching { reg.instance.canLoad(source) }.getOrElse { t ->
                logger("loader '${reg.instance.loaderId}' canLoad threw for '${source.uri}'; skipping", t)
                false
            }
        }
        if (match == null) {
            logger("no plugin loader accepts source kind='${source.kind}' uri='${source.uri}'", null)
            return emptyList()
        }
        val loader = match.instance
        val produced = runCatching { loader.load(source) }.getOrElse { t ->
            logger("loader '${loader.loaderId}' failed to load '${source.uri}'", t)
            return emptyList()
        }
        if (produced.isEmpty()) {
            logger("loader '${loader.loaderId}' produced no plugins for '${source.uri}'", null)
            return emptyList()
        }
        // Ownership must follow the install that actually happened. `installAll` deliberately skips a
        // duplicate id — and a loader may legitimately hand back a plugin whose id is already in the
        // host — so recording `produced.map { it.id }` unconditionally would attribute an *existing*,
        // unrelated plugin to this loader and let `uninstall(loaderId)` remove a plugin it never
        // installed. Only ids this call newly installed are recorded.
        val installed = installNew(produced)
        if (installed.isNotEmpty()) {
            producedBy.getOrPut(match.ownerPluginId) { ConcurrentHashMap.newKeySet() }.addAll(installed)
        }
        installed
    }

    /** Install several plugins, then resolve dependencies once. Order-independent. */
    fun installAll(plugins: Iterable<Plugin>) {
        synchronized(lock) { installNew(plugins) }
    }

    /**
     * Register [plugins] that are not already present, resolving dependencies once at the end.
     *
     * @return the ids actually installed, in argument order. Duplicates are logged and excluded —
     *         the caller must not attribute them to itself.
     */
    private fun installNew(plugins: Iterable<Plugin>): List<String> = synchronized(lock) {
        val added = mutableListOf<String>()
        if (shuttingDown) {
            logger("refusing to install plugins: the host has been shut down", null)
            return@synchronized added
        }
        for (p in plugins) {
            if (registered.containsKey(p.id) || p.id in unloading) {
                logger("plugin '${p.id}' already installed; ignoring", null)
                continue
            }
            registered[p.id] = Managed(p, PluginState.WAITING, nextSeq++)
            added += p.id
        }
        tryLoadWaiting()
        added
    }

    /**
     * Unload a single plugin: onUnload + disposer drain (removes all its side effects).
     *
     * If [pluginId] is a loader that produced plugins through [loadSource], its children are
     * unloaded first (reverse install order) so a loader never leaves owned plugins reachable
     * while it releases the resources they depend on.
     *
     * Registry bookkeeping happens under [lock], but `onUnload` + the drain run **outside** it:
     * a plugin's teardown can block for seconds (JS drain, host I/O, a synchronous engine close),
     * and parking the kernel lock for that long would stall every other thread's install/uninstall
     * behind one plugin's shutdown. The id is claimed in [unloading] for the duration, so a
     * concurrent install or a second uninstall of the same id is refused rather than racing a
     * duplicate teardown; the registry entry itself stays until teardown finishes, so children
     * can still resolve their loader while they unload.
     */
    fun uninstall(pluginId: String) {
        val m: Managed
        val children: List<String>
        synchronized(lock) {
            m = registered[pluginId] ?: return
            if (!unloading.add(pluginId)) return // already being torn down
            // Children in true reverse install order (by sequence, not hash iteration order).
            children = producedBy.remove(pluginId)?.toList().orEmpty()
                .filter { it != pluginId }
                .sortedByDescending { registered[it]?.seq ?: Long.MIN_VALUE }
            // This plugin may itself be someone's child; drop that edge.
            producedBy.values.forEach { it.remove(pluginId) }
        }
        try {
            for (childId in children) uninstall(childId)
            unloadManaged(m)
        } finally {
            synchronized(lock) { registered.remove(pluginId) }
            unloading.remove(pluginId)
        }
    }

    /**
     * Unload every plugin in reverse install order, then drop all bookkeeping.
     *
     * Like [uninstall], teardown runs outside [lock]; the registry is snapshotted and cleared in
     * one critical section so the (potentially slow) unloads never park the lock. Once shutdown
     * has begun, [install] refuses new plugins — the host is terminal after this.
     */
    fun shutdown() {
        val all = synchronized(lock) {
            shuttingDown = true
            val snapshot = registered.values.sortedByDescending { it.seq }
            unloading.addAll(snapshot.map { it.plugin.id })
            registered.clear()
            producedBy.clear()
            snapshot
        }
        for (m in all) {
            try {
                unloadManaged(m)
            } finally {
                unloading.remove(m.plugin.id)
            }
        }
    }

    /** Publish an engine event onto the bus (used by an integration layer bridging a client). */
    fun publishEvent(event: TurboEvent) = eventBus.publish(event)

    /** Run submit-time interceptors over a request (used by an integration layer at submit). */
    fun applyRequestInterceptors(request: DownloadRequest): DownloadRequest =
        eventBus.applyInterceptors(request)

    /** Diagnostic snapshot for logging/troubleshooting. */
    fun diagnostics(): DiagnosticsSnapshot {
        val infos = registered.values.map { it.toInfo() }
        return DiagnosticsSnapshot(
            plugins = infos,
            waitingPlugins = infos.filter { it.state == PluginState.WAITING },
            extensionPoints = extensions.snapshot(),
            services = services.snapshot(),
            eventListeners = eventBus.listenerCount(),
            requestInterceptors = eventBus.interceptorCount(),
        )
    }

    // ---------- internals ----------

    private fun Managed.toInfo() = PluginInfo(
        id = plugin.id,
        name = plugin.name,
        state = state,
        declaredDependencies = plugin.dependencies,
        missingDependencies = services.missing(plugin.dependencies),
        error = error,
    )

    /**
     * Iteratively load any WAITING plugin whose dependencies are now satisfied. Loading a plugin
     * may register new services, which may unblock others — so we loop until no progress.
     */
    private fun tryLoadWaiting() {
        var progressed = true
        while (progressed) {
            progressed = false
            for (m in registered.values) {
                if (m.state != PluginState.WAITING) continue
                // Version handshake first: a plugin that needs a newer/incompatible API is
                // rejected loudly here instead of being run against a mismatched host.
                val required = m.plugin.requiredApiVersion
                if (!dev.turbodl.core.ApiVersion.CURRENT.satisfies(required)) {
                    m.state = PluginState.INCOMPATIBLE
                    m.error = "requires API $required, host is ${dev.turbodl.core.ApiVersion.CURRENT}"
                    logger(
                        "plugin '${m.plugin.id}' is INCOMPATIBLE: requires API $required, " +
                            "host is ${dev.turbodl.core.ApiVersion.CURRENT}",
                        null,
                    )
                    continue
                }
                val missing = services.missing(m.plugin.dependencies)
                if (missing.isEmpty()) {
                    loadManaged(m)
                    progressed = true
                }
            }
        }
        // Report still-waiting plugins (missing deps) as warnings.
        registered.values.filter { it.state == PluginState.WAITING }.forEach {
            val missing = services.missing(it.plugin.dependencies)
            logger("plugin '${it.plugin.id}' waiting for missing dependencies: $missing", null)
        }
    }

    private fun loadManaged(m: Managed) {
        val ctx = DefaultPluginContext(m.plugin.id, m.disposer)
        // Mark LOADED before invoking onLoad so a re-entrant tryLoadWaiting (triggered when the
        // plugin registers a service during its own onLoad) does not load this plugin again.
        m.state = PluginState.LOADED
        try {
            m.plugin.onLoad(ctx)
            logger("plugin '${m.plugin.id}' loaded", null)
        } catch (t: Throwable) {
            m.error = t.message ?: t.javaClass.simpleName
            m.state = PluginState.FAILED
            logger("plugin '${m.plugin.id}' onLoad failed; draining partial registrations", t)
            // Roll back any partial side effects registered before the failure.
            m.disposer.dispose()
        }
    }

    private fun unloadManaged(m: Managed) {
        if (m.state == PluginState.UNLOADED) return
        runCatching { m.plugin.onUnload() }
            .exceptionOrNull()?.let { logger("plugin '${m.plugin.id}' onUnload threw", it) }
        val errors = m.disposer.dispose()
        errors.forEach { logger("disposer of plugin '${m.plugin.id}' threw during cleanup", it) }
        m.state = PluginState.UNLOADED
        logger("plugin '${m.plugin.id}' unloaded", null)
    }

    /** Context implementation: every registration is auto-wired into the plugin's disposer. */
    private inner class DefaultPluginContext(
        override val pluginId: String,
        override val disposer: Disposer,
    ) : PluginContext {

        override val apiVersion: dev.turbodl.core.ApiVersion = dev.turbodl.core.ApiVersion.CURRENT

        override fun registerService(id: String, instance: Any) {
            services.register(id, instance, pluginId)
            disposer.register { services.unregister(id) }
            // A new service may unblock waiting plugins.
            tryLoadWaiting()
        }

        override fun <T : Any> service(id: String, type: Class<T>): T? = services.get(id, type)

        override fun onEvent(listener: (TurboEvent) -> Unit) {
            val sub = eventBus.subscribe(pluginId, listener)
            disposer.register { sub.cancel() }
        }

        override fun interceptRequest(interceptor: (DownloadRequest) -> DownloadRequest) {
            val sub = eventBus.intercept(pluginId, interceptor)
            disposer.register { sub.cancel() }
        }

        override fun <T : Any> registerExtension(key: ExtensionPointKey<T>, instance: T, priority: Int) {
            val reg = extensions.register(key, pluginId, instance, priority)
            disposer.register { reg.cancel() }
        }

        override fun <T : Any> extensions(key: ExtensionPointKey<T>): List<T> = extensions.all(key)

        override fun log(message: String, error: Throwable?) = logger("[$pluginId] $message", error)
    }
}

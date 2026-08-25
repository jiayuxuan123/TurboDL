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

    private class Managed(
        val plugin: Plugin,
        var state: PluginState,
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
            if (registered.containsKey(plugin.id)) {
                logger("plugin '${plugin.id}' already installed; ignoring", null)
                return
            }
            registered[plugin.id] = Managed(plugin, PluginState.WAITING)
            tryLoadWaiting()
        }
    }

    /** Install several plugins, then resolve dependencies once. Order-independent. */
    fun installAll(plugins: Iterable<Plugin>) {
        synchronized(lock) {
            for (p in plugins) {
                if (registered.containsKey(p.id)) {
                    logger("plugin '${p.id}' already installed; ignoring", null)
                    continue
                }
                registered[p.id] = Managed(p, PluginState.WAITING)
            }
            tryLoadWaiting()
        }
    }

    /** Unload a single plugin: onUnload + drain disposer (removes all its side effects). */
    fun uninstall(pluginId: String) {
        synchronized(lock) {
            val m = registered[pluginId] ?: return
            unloadManaged(m)
            registered.remove(pluginId)
        }
    }

    /** Unload every plugin in reverse install order. */
    fun shutdown() {
        synchronized(lock) {
            registered.values.reversed().forEach { unloadManaged(it) }
            registered.clear()
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

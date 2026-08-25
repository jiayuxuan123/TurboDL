package dev.turbodl.plugin.runtime

/**
 * A TurboDL plugin.
 *
 * Everything except the kernel itself is a plugin: the Kotlin plugin loader is a plugin, a
 * future JS runtime is a plugin, download backends/link parsers/adapters are all plugins.
 * The kernel only knows this interface plus the extension-point/service/event machinery.
 *
 * Lifecycle:
 *  - [onLoad] runs once, after all declared [dependencies] are satisfied. Register services,
 *    event listeners and extension-point implementations here, and register a matching
 *    cleanup for each via [PluginContext.disposer].
 *  - [onUnload] runs once on removal. The host ALSO drains the plugin's disposer chain
 *    automatically, so a well-behaved plugin needs no manual teardown here — but it may do
 *    extra work if desired.
 *
 * NOTE: reserved — JS plugins will be adapted to this same lifecycle by an external JS
 * provider; the kernel does not special-case JS. Only Kotlin-native plugins are supported now.
 */
interface Plugin {
    /** Stable unique id, e.g. "loader.kotlin", "backend.http", "adapter.cordis". */
    val id: String

    /** Human-readable name for diagnostics. */
    val name: String get() = id

    /** Service ids this plugin needs before it can load. Empty = no dependencies. */
    val dependencies: Set<String> get() = emptySet()

    /** Called once when the plugin is activated (dependencies already satisfied). */
    fun onLoad(context: PluginContext)

    /** Called once when the plugin is being removed (before disposer chain is drained). */
    fun onUnload() {}
}

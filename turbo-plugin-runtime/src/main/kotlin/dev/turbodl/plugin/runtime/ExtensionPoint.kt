package dev.turbodl.plugin.runtime

/**
 * A type-safe key identifying an extension point.
 *
 * An extension point is a contract (a Kotlin interface [T]) that plugins may provide one or
 * more implementations of. The kernel does not know or care what the contract means — it only
 * stores implementations keyed by [ExtensionPointKey] and hands them back to consumers.
 *
 * The kernel ships NO built-in extension points beyond [PluginLoaderProvider]. Business
 * extension points (DownloadBackend routing, LinkParser, TaskPreHook, TaskPostHook, ...) are
 * declared by whoever needs them and registered by plugins.
 *
 * @param id stable string id used in diagnostics/logging
 * @param type the contract interface class
 */
class ExtensionPointKey<T : Any>(
    val id: String,
    val type: Class<T>,
) {
    override fun equals(other: Any?): Boolean =
        other is ExtensionPointKey<*> && other.id == id && other.type == type

    override fun hashCode(): Int = 31 * id.hashCode() + type.hashCode()
    override fun toString(): String = "ExtensionPoint($id: ${type.name})"

    companion object {
        inline fun <reified T : Any> of(id: String): ExtensionPointKey<T> =
            ExtensionPointKey(id, T::class.java)
    }
}

/**
 * One registered extension implementation, with an owner plugin id and an optional priority.
 * Higher [priority] wins when consumers ask for "the" implementation (e.g. backend routing).
 */
data class ExtensionRegistration<T : Any>(
    val key: ExtensionPointKey<T>,
    val ownerPluginId: String,
    val priority: Int,
    val instance: T,
)

/**
 * PluginLoaderProvider — the ONLY extension point the kernel is aware of by name.
 *
 * A plugin loader knows how to turn some source (a Kotlin class, a JAR, a JS script, ...) into
 * [Plugin] instances. The Kotlin class-based loader itself is just a plugin that registers an
 * implementation of this extension point; the kernel never embeds any loader.
 *
 * NOTE: a JS provider ships as the external `turbo-plugin-js` plugin and implements this interface
 * like any other provider. The kernel has no knowledge of JS and pulls no JS engine dependency into
 * the runtime kernel.
 */
interface PluginLoaderProvider {
    /**
     * Loader id for diagnostics (e.g. "kotlin", "js").
     *
     * [PluginHost.loadSource] records the plugins a loader produces under this id, and
     * [PluginHost.uninstall] cascades to them by matching the registration's owner plugin — so
     * loaders are free to pick any id, and unloading the plugin that registered a provider never
     * leaves its produced plugins reachable.
     */
    val loaderId: String

    /** Whether this loader can handle the given source descriptor. */
    fun canLoad(source: PluginSource): Boolean

    /** Instantiate plugin(s) from the source. May be empty if nothing matched. */
    fun load(source: PluginSource): List<Plugin>

    companion object {
        val KEY: ExtensionPointKey<PluginLoaderProvider> =
            ExtensionPointKey.of("turbo.pluginLoaderProvider")
    }
}

/**
 * Opaque descriptor of "where a plugin comes from". Kept intentionally minimal and open so
 * different loaders can interpret it (a class name, a file path, a script URL, ...).
 *
 * NOTE: the JS provider interprets [uri] as a script location (`file:` path, `inline:`, `data:`);
 * the kernel does not interpret these fields itself.
 */
data class PluginSource(
    /** Free-form kind hint, e.g. "kotlin-class", "jar", "js". */
    val kind: String,
    /** Location/identifier the matching loader understands. */
    val uri: String,
    /** Optional extra attributes for the loader. */
    val attributes: Map<String, String> = emptyMap(),
)

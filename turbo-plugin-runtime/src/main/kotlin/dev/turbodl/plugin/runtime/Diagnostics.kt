package dev.turbodl.plugin.runtime

/** Lifecycle state of a plugin inside the host. */
enum class PluginState {
    /** Registered but waiting for one or more dependency services. */
    WAITING,
    /** onLoad completed successfully; active. */
    LOADED,
    /** onLoad threw; inactive. */
    FAILED,
    /** Unloaded and disposer drained. */
    UNLOADED,
}

/** Snapshot of one plugin for diagnostics. */
data class PluginInfo(
    val id: String,
    val name: String,
    val state: PluginState,
    val declaredDependencies: Set<String>,
    val missingDependencies: Set<String>,
    val error: String? = null,
)

/**
 * Full diagnostic snapshot of the host for logging/troubleshooting:
 * loaded plugins & states, plugins waiting on dependencies, all registered extension-point
 * implementations, and the service registry.
 */
data class DiagnosticsSnapshot(
    val plugins: List<PluginInfo>,
    val waitingPlugins: List<PluginInfo>,
    /** extension-point id -> list of (ownerPluginId, priority). */
    val extensionPoints: Map<String, List<Pair<String, Int>>>,
    /** serviceId -> ownerPluginId. */
    val services: Map<String, String>,
    val eventListeners: Int,
    val requestInterceptors: Int,
) {
    /** Human-readable multi-line report. */
    fun render(): String = buildString {
        appendLine("== TurboDL Plugin Host Diagnostics ==")
        appendLine("Plugins (${plugins.size}):")
        plugins.forEach { p ->
            append("  - ${p.id} [${p.state}]")
            if (p.missingDependencies.isNotEmpty()) append(" missing=${p.missingDependencies}")
            if (p.error != null) append(" error=${p.error}")
            appendLine()
        }
        if (waitingPlugins.isNotEmpty()) {
            appendLine("Waiting on dependencies (${waitingPlugins.size}):")
            waitingPlugins.forEach { appendLine("  - ${it.id} needs ${it.missingDependencies}") }
        }
        appendLine("Extension points (${extensionPoints.size}):")
        extensionPoints.forEach { (id, impls) -> appendLine("  - $id -> $impls") }
        appendLine("Services (${services.size}):")
        services.forEach { (id, owner) -> appendLine("  - $id (by $owner)") }
        appendLine("Event listeners: $eventListeners, request interceptors: $requestInterceptors")
    }
}

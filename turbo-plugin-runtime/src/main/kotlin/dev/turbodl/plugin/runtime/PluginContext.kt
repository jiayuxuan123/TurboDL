package dev.turbodl.plugin.runtime

import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.TurboEvent

/**
 * The capability surface handed to a plugin during [Plugin.onLoad].
 *
 * Everything a plugin does through this context is automatically tracked for cleanup: service
 * registrations, event subscriptions and extension-point registrations each return handles that
 * are ALSO wired into the plugin's [disposer], so unloading the plugin removes every side effect
 * even if the plugin forgot to clean up manually.
 *
 * NOTE: reserved — a future JS provider will expose a mirror of this surface to scripts; the
 * kernel keeps the surface small and serialization-friendly at the boundary.
 */
interface PluginContext {
    /** This plugin's id. */
    val pluginId: String

    /** The host's current TurboDL public API version (for optional feature detection). */
    val apiVersion: dev.turbodl.core.ApiVersion

    /** Per-plugin cleanup chain, drained on unload (LIFO). */
    val disposer: Disposer

    // ---- Services ----

    /** Register a service; auto-unregistered on unload. */
    fun registerService(id: String, instance: Any)

    /** Look up a service by id (typed), or null. */
    fun <T : Any> service(id: String, type: Class<T>): T?

    // ---- Events ----

    /** Subscribe to engine events; auto-unsubscribed on unload. */
    fun onEvent(listener: (TurboEvent) -> Unit)

    /** Register a submit-time request interceptor; auto-removed on unload. */
    fun interceptRequest(interceptor: (DownloadRequest) -> DownloadRequest)

    // ---- Extension points ----

    /** Register an extension-point implementation; auto-removed on unload. */
    fun <T : Any> registerExtension(key: ExtensionPointKey<T>, instance: T, priority: Int = 0)

    /** Query all implementations of an extension point (highest priority first). */
    fun <T : Any> extensions(key: ExtensionPointKey<T>): List<T>

    /** Structured logging hook (host-provided). */
    fun log(message: String, error: Throwable? = null)
}

inline fun <reified T : Any> PluginContext.service(id: String): T? = service(id, T::class.java)

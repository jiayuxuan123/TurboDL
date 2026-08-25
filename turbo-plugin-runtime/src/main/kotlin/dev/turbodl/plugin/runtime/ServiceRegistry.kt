package dev.turbodl.plugin.runtime

import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight service registry with dependency resolution.
 *
 * Plugins publish services by id; plugins may declare dependencies on service ids and are only
 * loaded once every dependency is present. This is deliberately NOT a heavyweight IoC container
 * — just a concurrent id→instance map plus a "who is waiting for what" bookkeeping used by the
 * host to gate plugin loading.
 *
 * NOTE: reserved — a future JS provider may read this registry to expose services to scripts;
 * the accessor surface is intentionally simple and serialization-friendly at the boundary.
 */
class ServiceRegistry {
    private val services = ConcurrentHashMap<String, Any>()
    private val owners = ConcurrentHashMap<String, String>() // serviceId -> owning pluginId

    /** Register a service instance under [id], owned by [ownerPluginId]. */
    fun register(id: String, instance: Any, ownerPluginId: String) {
        require(services.putIfAbsent(id, instance) == null) { "Service already registered: $id" }
        owners[id] = ownerPluginId
    }

    /** Remove a service (used by disposer on unload). */
    fun unregister(id: String) {
        services.remove(id)
        owners.remove(id)
    }

    /** Whether a service id is present. */
    fun has(id: String): Boolean = services.containsKey(id)

    /** Get a service by id, or null. */
    fun getOrNull(id: String): Any? = services[id]

    /** Get a typed service by id, or null if absent/mismatched. */
    fun <T : Any> get(id: String, type: Class<T>): T? =
        services[id]?.takeIf { type.isInstance(it) }?.let { type.cast(it) }

    inline fun <reified T : Any> get(id: String): T? = get(id, T::class.java)

    /** Snapshot of registered service ids and their owners (for diagnostics). */
    fun snapshot(): Map<String, String> = HashMap(owners)

    /** Which of [required] are still missing. */
    fun missing(required: Set<String>): Set<String> = required.filterNot { services.containsKey(it) }.toSet()
}

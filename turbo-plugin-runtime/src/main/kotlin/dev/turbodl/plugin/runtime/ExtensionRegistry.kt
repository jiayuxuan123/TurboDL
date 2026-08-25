package dev.turbodl.plugin.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Registry of extension-point implementations.
 *
 * The kernel stores implementations keyed by [ExtensionPointKey] without understanding their
 * meaning. Consumers query all implementations, or the highest-priority one. Business modules
 * (e.g. a backend router) build their behavior on top of these queries.
 */
class ExtensionRegistry {
    private val byKey = ConcurrentHashMap<ExtensionPointKey<*>, CopyOnWriteArrayList<ExtensionRegistration<*>>>()

    /** Register [instance] for extension point [key], owned by [ownerPluginId]. */
    fun <T : Any> register(
        key: ExtensionPointKey<T>,
        ownerPluginId: String,
        instance: T,
        priority: Int = 0,
    ): Registration {
        val reg = ExtensionRegistration(key, ownerPluginId, priority, instance)
        val list = byKey.getOrPut(key) { CopyOnWriteArrayList() }
        list.add(reg)
        return Registration { list.remove(reg) }
    }

    /** All implementations for [key], highest priority first. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> all(key: ExtensionPointKey<T>): List<T> {
        val list = byKey[key] ?: return emptyList()
        return list.sortedByDescending { it.priority }.map { it.instance as T }
    }

    /** Highest-priority implementation for [key], or null. */
    fun <T : Any> highest(key: ExtensionPointKey<T>): T? = all(key).firstOrNull()

    /** Diagnostics: extension-point id → list of (ownerPluginId, priority). */
    fun snapshot(): Map<String, List<Pair<String, Int>>> =
        byKey.entries.associate { (k, v) ->
            k.id to v.map { it.ownerPluginId to it.priority }
        }

    fun interface Registration {
        fun cancel()
    }
}

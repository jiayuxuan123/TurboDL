package dev.turbodl.plugin.bootstrap

import dev.turbodl.core.DownloadBackend
import dev.turbodl.core.TurboBackends
import dev.turbodl.core.TurboConfig
import dev.turbodl.plugin.runtime.Plugin
import dev.turbodl.plugin.runtime.PluginContext
import dev.turbodl.plugin.runtime.PluginLoaderProvider
import dev.turbodl.plugin.runtime.PluginSource
import dev.turbodl.plugin.runtime.ext.ExtensionPoints

/**
 * Base plugins packaged by the bootstrap module for out-of-the-box usage.
 *
 * These are ORDINARY plugins — nothing here is part of the runtime kernel. Advanced users can
 * skip bootstrap entirely and install their own equivalents (or none). They are provided only as
 * a convenience so a typical user does not have to hand-wire the basics.
 */

/**
 * The Kotlin-native plugin loader, itself a plugin implementing [PluginLoaderProvider].
 *
 * It "loads" a plugin from a [PluginSource] of kind "kotlin-class"/"kotlin-instance". In this
 * iteration it supports the common case where the source already carries a ready [Plugin]
 * instance via attributes handled by the caller; class-name reflection loading is a thin,
 * optional convenience. The kernel embeds NO loader — this one lives in bootstrap.
 *
 * NOTE: reserved — a JS loader is provided by a separate external plugin implementing the same
 * [PluginLoaderProvider] extension point; the kernel and this Kotlin loader know nothing of JS.
 */
class KotlinPluginLoaderPlugin : Plugin {
    override val id = "loader.kotlin"
    override val name = "Kotlin Plugin Loader"

    override fun onLoad(context: PluginContext) {
        val provider = object : PluginLoaderProvider {
            override val loaderId = "kotlin"
            override fun canLoad(source: PluginSource): Boolean =
                source.kind == "kotlin-class"
            override fun load(source: PluginSource): List<Plugin> {
                // Convenience: instantiate a no-arg Plugin subclass by fully-qualified class name.
                return try {
                    val clazz = Class.forName(source.uri)
                    val instance = clazz.getDeclaredConstructor().newInstance()
                    if (instance is Plugin) listOf(instance) else {
                        context.log("class ${source.uri} is not a Plugin"); emptyList()
                    }
                } catch (t: Throwable) {
                    context.log("failed to load kotlin plugin ${source.uri}", t)
                    emptyList()
                }
            }
        }
        context.registerExtension(PluginLoaderProvider.KEY, provider)
        // Advertise the loader as a service so dependent plugins can gate on it if desired.
        context.registerService("loader.kotlin", provider)
    }
}

/**
 * HTTP download backend, packaged as an ordinary plugin.
 *
 * Registers core's built-in multi-threaded HTTP engine as a routed [DownloadBackend] at the
 * [ExtensionPoints.DOWNLOAD_BACKEND] extension point. When present, [BackendRegistry] routes
 * http(s) tasks to it; when absent (and no other backend matches), TurboClient still uses its
 * own internal built-in backend, so downloads keep working.
 *
 * @param priority routing priority; a higher-priority plugin backend overrides this one.
 */
class HttpBackendPlugin(
    private val clientConfig: TurboConfig = TurboConfig(),
    private val priority: Int = 0,
) : Plugin {
    override val id = "backend.http"
    override val name = "HTTP Download Backend"

    override fun onLoad(context: PluginContext) {
        val backend: DownloadBackend = TurboBackends.builtinHttp(clientConfig)
        context.registerExtension(ExtensionPoints.DOWNLOAD_BACKEND, backend, priority)
        context.registerService("backend.http", backend)
    }
}

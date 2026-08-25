package dev.turbodl.plugin.bootstrap

import dev.turbodl.core.TurboClient
import dev.turbodl.core.TurboConfig
import dev.turbodl.plugin.runtime.Plugin
import dev.turbodl.plugin.runtime.PluginHost
import dev.turbodl.plugin.runtime.ext.BackendRegistry

/**
 * TurboBootstrap — optional convenience wiring for out-of-the-box usage.
 *
 * It:
 *  1. creates a [PluginHost] and a [TurboClient];
 *  2. wires the host's [BackendRegistry] into the client as its BackendResolver, so plugin
 *     backends can route/override (the client still falls back to its built-in backend when no
 *     plugin backend matches);
 *  3. bridges the client's event stream onto the host's EventBus, and routes submissions through
 *     the host's request interceptors + pre-hooks;
 *  4. installs the base plugins (Kotlin loader + HTTP backend) unless disabled.
 *
 * This module is NOT a mandatory dependency. Advanced users may construct [PluginHost] and
 * [TurboClient] themselves and wire only what they need, in any order.
 *
 * NOTE: bootstrap intentionally contains no business logic beyond wiring; adapters, JS provider,
 * HLS, etc. remain external plugins.
 */
class TurboBootstrap private constructor(
    val host: PluginHost,
    val client: TurboClient,
) {
    companion object {
        /**
         * Build a ready-to-use bootstrap.
         *
         * @param config engine config for the client.
         * @param installBasePlugins install Kotlin loader + HTTP backend plugins (default true).
         * @param extraPlugins additional plugins to install after the base ones.
         */
        fun create(
            config: TurboConfig = TurboConfig(),
            installBasePlugins: Boolean = true,
            extraPlugins: List<Plugin> = emptyList(),
        ): TurboBootstrap {
            val host = PluginHost()
            val client = TurboClient(config)

            // (2) Route downloads through plugin backends; null → client's built-in fallback.
            client.backendResolver = BackendRegistry(host.extensions)

            val boot = TurboBootstrap(host, client)

            // (4) Install base plugins first (order-independent thanks to dependency gating).
            if (installBasePlugins) {
                host.installAll(listOf(KotlinPluginLoaderPlugin(), HttpBackendPlugin(config)))
            }
            host.installAll(extraPlugins)

            return boot
        }
    }

    /** Convenience: install more plugins after creation. */
    fun install(vararg plugins: Plugin) = host.installAll(plugins.toList())

    /** Print a diagnostics report (loaded plugins, waiting deps, extension points, services). */
    fun diagnosticsReport(): String = host.diagnostics().render()

    /** Shut down both the plugin host (drains all disposers) and the download client. */
    fun shutdown() {
        host.shutdown()
        client.shutdown()
    }
}

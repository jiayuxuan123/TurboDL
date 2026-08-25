package dev.turbodl.plugin.runtime.ext

import dev.turbodl.core.BackendResolver
import dev.turbodl.core.DownloadBackend
import dev.turbodl.core.DownloadRequest
import dev.turbodl.plugin.runtime.ExtensionRegistry

/**
 * BackendRegistry — routes a request to a plugin-provided [DownloadBackend].
 *
 * This is the runtime-side glue that fulfils core's [BackendResolver] fun-interface. It queries
 * the [ExtensionRegistry] for all [ExtensionPoints.DOWNLOAD_BACKEND] implementations (already
 * ordered highest-priority-first) and returns the first whose [DownloadBackend.supports] matches.
 *
 * Hybrid A+B behavior:
 *  - returning null means "no plugin backend matched" → TurboClient falls back to its built-in
 *    HTTP backend (plan A: core usable standalone);
 *  - a plugin backend with higher priority than the implicit built-in effectively OVERRIDES it
 *    (plan B: everything can be a plugin), and new protocols are added by registering new
 *    backends (plan B extensibility).
 *
 * IMPORTANT: this class lives in the runtime module. core NEVER references it; it is only wired
 * in when the optional runtime is present, by assigning an instance to TurboClient.backendResolver.
 */
class BackendRegistry(private val extensions: ExtensionRegistry) : BackendResolver {
    override fun resolve(request: DownloadRequest): DownloadBackend? =
        extensions.all(ExtensionPoints.DOWNLOAD_BACKEND)
            .firstOrNull { it.supports(request) }
}

package dev.turbodl.plugin.runtime.ext

import dev.turbodl.core.CheckedBackendResolver
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
class BackendRegistry @JvmOverloads constructor(
    private val extensions: ExtensionRegistry,
    private val onPluginError: (ownerPluginId: String, operation: String, error: Throwable) -> Unit =
        { _, _, _ -> },
) : CheckedBackendResolver {
    @Suppress("DEPRECATION")
    override fun resolve(request: DownloadRequest): DownloadBackend? = resolve(request, false)

    override fun resolveChecked(request: DownloadRequest): DownloadBackend? = resolve(request, true)

    @Suppress("DEPRECATION")
    private fun resolve(request: DownloadRequest, failOnError: Boolean): DownloadBackend? {
        for (registration in extensions.registrations(ExtensionPoints.DOWNLOAD_BACKEND)) {
            val supported = try {
                registration.instance.supports(request)
            } catch (t: Throwable) {
                // One malformed plugin must never be able to disable every
                // built-in download. VM-fatal conditions still propagate.
                if (t is VirtualMachineError || t is ThreadDeath) throw t
                if (t is InterruptedException) Thread.currentThread().interrupt()
                try {
                    onPluginError(registration.ownerPluginId, "supports", t)
                } catch (reportingFailure: Throwable) {
                    if (reportingFailure is VirtualMachineError || reportingFailure is ThreadDeath) {
                        throw reportingFailure
                    }
                    if (reportingFailure is InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                if (failOnError) {
                    throw IllegalStateException("Backend selection failed for plugin ${registration.ownerPluginId}", t)
                }
                false
            }
            if (supported) return registration.instance
        }
        return null
    }
}

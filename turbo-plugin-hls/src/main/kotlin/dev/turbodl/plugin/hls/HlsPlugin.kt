package dev.turbodl.plugin.hls

import dev.turbodl.core.DownloadBackend
import dev.turbodl.plugin.runtime.Plugin
import dev.turbodl.plugin.runtime.PluginContext
import dev.turbodl.plugin.runtime.ext.ExtensionPoints
import dev.turbodl.plugin.runtime.ext.ProtocolClaim

/**
 * HLS protocol adapter, packaged as an ordinary plugin.
 *
 * Registers an [HlsBackend] at the [ExtensionPoints.DOWNLOAD_BACKEND] extension point so
 * BackendRegistry routes `*.m3u8` HTTP(S) tasks to it, and DECLARES the `hls` protocol at
 * [ExtensionPoints.PROTOCOL_HANDLER] so the host's load-time scheme index knows this plugin
 * handles HLS. Nothing here is part of the runtime kernel; this is a plain optional plugin the
 * user chooses to install.
 *
 * Priority note: HLS registers at a HIGHER default priority than the plain HTTP backend so that
 * an `.m3u8` URL is treated as a stream to assemble rather than a text file to save. The HTTP
 * backend still wins for every non-m3u8 URL because [HlsBackend.supports] only matches m3u8.
 *
 * Declaration vs. routing (deliberately two parallel facts):
 *  - routing stays with the [ExtensionPoints.DOWNLOAD_BACKEND] predicate above — [HlsBackend.supports]
 *    is what decides that an `.m3u8` request is ours;
 *  - the `hls` claim is the load-time declaration for the scheme index / market / diagnostics.
 *    HLS is a CONTENT protocol, not a URL scheme: an `.m3u8` URL's scheme is `http(s)`, so it is
 *    never resolved to this claim by scheme — see [dev.turbodl.plugin.runtime.ext.ProtocolRegistry.resolve].
 *    The claim is not a lie about that; it answers "who declares hls?", not "which URL scheme is this?".
 *
 * NOTE: another provider or a third-party shim could register alternative protocol backends the
 * same way; the kernel remains unaware of HLS specifics. `turbo-plugin-js` is a peer system plugin
 * (a loader) and deliberately does NOT register a backend — byte-plane contracts stay in Kotlin.
 */
class HlsPlugin(
    private val priority: Int = 100,
) : Plugin {
    override val id: String = "backend.hls"
    override val name: String = "HLS VOD Backend"

    override fun onLoad(context: PluginContext) {
        val backend: DownloadBackend = HlsBackend()
        context.registerExtension(ExtensionPoints.DOWNLOAD_BACKEND, backend, priority)
        // Incremental declaration: does NOT change routing (still the line above), grants no
        // permission, and adds no request-time behavior. It only makes "hls → backend.hls"
        // answerable at load time. `priority` matches the backend registration so the scheme
        // index and the backend router order the same way.
        context.registerExtension(
            ExtensionPoints.PROTOCOL_HANDLER,
            ProtocolClaim(scheme = "hls", pluginId = id, priority = priority, label = "hls-vod"),
            priority,
        )
        context.registerService(id, backend)
        context.log("registered HLS VOD backend at priority $priority (declares protocol 'hls')")
    }
}

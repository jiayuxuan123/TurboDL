package dev.turbodl.plugin.hls

import dev.turbodl.core.DownloadBackend
import dev.turbodl.plugin.runtime.Plugin
import dev.turbodl.plugin.runtime.PluginContext
import dev.turbodl.plugin.runtime.ext.ExtensionPoints

/**
 * HLS protocol adapter, packaged as an ordinary plugin.
 *
 * Registers an [HlsBackend] at the [ExtensionPoints.DOWNLOAD_BACKEND] extension point so
 * BackendRegistry routes `*.m3u8` HTTP(S) tasks to it. Nothing here is part of the runtime
 * kernel; this is a plain optional plugin the user chooses to install.
 *
 * Priority note: HLS registers at a HIGHER default priority than the plain HTTP backend so that
 * an `.m3u8` URL is treated as a stream to assemble rather than a text file to save. The HTTP
 * backend still wins for every non-m3u8 URL because [HlsBackend.supports] only matches m3u8.
 *
 * NOTE: reserved — a future JS Provider or third-party shim could register alternative protocol
 * backends the same way; the kernel remains unaware of HLS specifics.
 */
class HlsPlugin(
    private val priority: Int = 100,
) : Plugin {
    override val id: String = "backend.hls"
    override val name: String = "HLS VOD Backend"

    override fun onLoad(context: PluginContext) {
        val backend: DownloadBackend = HlsBackend()
        context.registerExtension(ExtensionPoints.DOWNLOAD_BACKEND, backend, priority)
        context.registerService(id, backend)
        context.log("registered HLS VOD backend at priority $priority")
    }
}

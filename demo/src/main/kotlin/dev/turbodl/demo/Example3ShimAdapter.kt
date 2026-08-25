package dev.turbodl.demo

import dev.turbodl.core.BackendContext
import dev.turbodl.core.BackendResult
import dev.turbodl.core.DownloadBackend
import dev.turbodl.core.DownloadRequest
import dev.turbodl.plugin.runtime.Plugin
import dev.turbodl.plugin.runtime.PluginContext
import dev.turbodl.plugin.runtime.PluginHost
import dev.turbodl.plugin.runtime.ext.ExtensionPoints
import dev.turbodl.plugin.runtime.ext.LinkParser
import java.io.File

/**
 * Example 3 — a Shim adapter TEMPLATE.
 *
 * A "shim" wraps some EXTERNAL downloader/library (a third-party engine, a cloud SDK, a legacy
 * downloader) and exposes it to TurboDL through the public extension points, WITHOUT TurboDL
 * knowing anything about that external system. This file is a copy-paste skeleton: the external
 * calls are represented by a small fake interface so it compiles and runs offline. Replace the
 * fake with a real SDK to build an actual adapter.
 *
 * It shows the two extension points a protocol/service adapter typically implements:
 *  - LINK_PARSER: translate an external link format into TurboDL DownloadRequest(s);
 *  - DOWNLOAD_BACKEND: fetch bytes via the external system and hand ordered parts back to the
 *    engine for its normal merge/state/integrity pipeline.
 *
 * NOTE: this template intentionally contains NO real third-party integration. It only maps the
 * TurboDL contracts onto a placeholder `ExternalDownloader` so you can see exactly which methods
 * to fill in. See docs/plugins for the full guide.
 */

// ---- Placeholder for "some external system" you are adapting. Replace with a real SDK. ----

/** Stand-in for a third-party downloader/library you want to bridge into TurboDL. */
interface ExternalDownloader {
    /** Whether this external system recognizes the given raw link. */
    fun recognizes(link: String): Boolean

    /** Resolve an external link into one or more concrete HTTP(S) URLs. */
    fun resolve(link: String): List<String>

    /** Fetch [url] fully into [target] (blocking). Real adapters stream/chunk instead. */
    fun fetch(url: String, target: File)
}

/** A trivial fake so the template compiles and runs offline. */
private class FakeExternalDownloader : ExternalDownloader {
    override fun recognizes(link: String): Boolean = link.startsWith("myapp://")
    override fun resolve(link: String): List<String> = listOf("https://cdn.example.com/${link.removePrefix("myapp://")}")
    override fun fetch(url: String, target: File) {
        // Real adapter would call the external SDK here. The fake writes deterministic bytes.
        target.writeBytes("bytes for $url".toByteArray())
    }
}

/**
 * The shim plugin: registers a LinkParser and a DownloadBackend that both delegate to the
 * external system. Priority is set high so shim-owned schemes win over the built-in HTTP backend.
 */
class ShimAdapterPlugin(
    private val external: ExternalDownloader = FakeExternalDownloader(),
    private val priority: Int = 200,
) : Plugin {
    override val id = "adapter.shim-template"
    override val name = "Shim Adapter Template"

    override fun onLoad(context: PluginContext) {
        // (1) LINK_PARSER: myapp://foo -> concrete DownloadRequest(s).
        val parser = LinkParser { rawInput ->
            if (!external.recognizes(rawInput)) return@LinkParser null
            external.resolve(rawInput).map { url ->
                DownloadRequest(url = url, destination = File(defaultNameFor(url)))
            }
        }
        context.registerExtension(ExtensionPoints.LINK_PARSER, parser, priority)

        // (2) DOWNLOAD_BACKEND: fetch via the external system, return ordered parts to merge.
        val backend = ShimBackend(external)
        context.registerExtension(ExtensionPoints.DOWNLOAD_BACKEND, backend, priority)

        context.log("shim adapter template registered (parser + backend) at priority $priority")
    }

    private fun defaultNameFor(url: String): String = url.substringAfterLast('/').ifEmpty { "download.bin" }
}

/**
 * Backend half of the shim. It delegates byte fetching to the external system, then returns a
 * single ordered part so TurboClient runs its normal merge/state/integrity pipeline.
 *
 * A real adapter that supports segmented transfers would return multiple ordered parts and report
 * incremental progress via [BackendContext.reportProgress] and rate-limit via
 * [BackendContext.throttle].
 */
class ShimBackend(private val external: ExternalDownloader) : DownloadBackend {
    override val name: String = "shim-template"

    // supports() decides routing. Here: only URLs the external system can (re)fetch. Adjust to
    // your scheme. Note LinkParser already turned myapp:// into https://, so a real adapter would
    // typically match on a marker header or a dedicated scheme instead.
    override fun supports(request: DownloadRequest): Boolean =
        request.headers["X-Shim-Adapter"] == "template"

    override suspend fun download(context: BackendContext): BackendResult {
        val part = File(context.workDir, "shim.part")
        // Blocking external fetch shown for brevity; real adapters should stream and honor
        // context.isActive()/throttle() for cancellation and global speed limiting.
        external.fetch(context.request.url, part)
        val size = part.length()
        context.reportTotalSize(size)
        context.reportProgress(size, 1)
        return BackendResult(listOf(part), size)
    }
}

/** Runs example 3, showing the parser and backend registrations (offline, using the fake). */
fun runShimAdapterExample() {
    println("=== Example 3: Shim adapter template ===")
    val host = PluginHost()
    host.install(ShimAdapterPlugin())
    println(host.diagnostics().render())

    // Show the LINK_PARSER turning an external link into concrete requests.
    val parsers = host.extensions.all(ExtensionPoints.LINK_PARSER)
    val parsed = parsers.firstNotNullOfOrNull { it.parse("myapp://video/clip.ts") }
    println("parsed myapp:// link -> ${parsed?.map { it.url }}")

    host.shutdown()
}

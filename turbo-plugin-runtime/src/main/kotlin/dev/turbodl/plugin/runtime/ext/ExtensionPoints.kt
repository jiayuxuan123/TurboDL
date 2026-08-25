package dev.turbodl.plugin.runtime.ext

import dev.turbodl.core.DownloadBackend
import dev.turbodl.core.DownloadRequest
import dev.turbodl.plugin.runtime.ExtensionPointKey

/**
 * Business extension points (contracts only — no implementations here).
 *
 * These are the well-known contracts that plugins implement to extend the download pipeline.
 * The runtime KERNEL does not know these by name; they are declared here in an `ext` layer that
 * business plugins and the bootstrap integration share. The kernel only knows
 * [dev.turbodl.plugin.runtime.PluginLoaderProvider].
 *
 * NOTE: reserved — future JS Provider / third-party shim adapter plugins may implement any of
 * these extension points. No implementation is provided in this iteration.
 */
object ExtensionPoints {

    /**
     * Download backend routing extension point.
     *
     * A plugin registers a [DownloadBackend] here to add a protocol or override the built-in
     * HTTP backend. The [BackendRegistry] (installed into TurboClient as a BackendResolver)
     * selects the highest-priority backend whose [DownloadBackend.supports] returns true.
     */
    val DOWNLOAD_BACKEND: ExtensionPointKey<DownloadBackend> =
        ExtensionPointKey.of("turbo.downloadBackend")

    /**
     * Link parser / pre-processing extension point.
     *
     * Turns a raw user input (a share link, a page URL, a magnet, ...) into one or more concrete
     * [DownloadRequest]s. Runs before submission. Multiple parsers may match; the router tries
     * them by priority until one returns a non-null result.
     */
    val LINK_PARSER: ExtensionPointKey<LinkParser> =
        ExtensionPointKey.of("turbo.linkParser")

    /**
     * Task pre-hook: inspect / rewrite a [DownloadRequest] just before it is submitted.
     * (Header injection, path policy, filtering, ...). Distinct from EventBus interceptors in
     * that hooks are ordered, declarative extension-point implementations.
     */
    val TASK_PRE_HOOK: ExtensionPointKey<TaskPreHook> =
        ExtensionPointKey.of("turbo.taskPreHook")

    /**
     * Task post-hook: run after a task terminates (completed / failed) for side effects such as
     * checksum verification, unpacking, notifications, cleanup of temporary cloud transfers, ...
     */
    val TASK_POST_HOOK: ExtensionPointKey<TaskPostHook> =
        ExtensionPointKey.of("turbo.taskPostHook")
}

/**
 * Link parser extension point contract.
 *
 * NOTE: reserved — shim adapters (e.g. bridging a third-party downloader's link recognizers)
 * implement this to translate external link formats into TurboDL [DownloadRequest]s.
 */
fun interface LinkParser {
    /**
     * Try to parse [rawInput] into concrete download requests. Return null (or empty) if this
     * parser does not handle the input, so the router can try the next one.
     */
    fun parse(rawInput: String): List<DownloadRequest>?
}

/**
 * Task pre-processing hook contract. Return the (possibly modified) request; return the input
 * unchanged to no-op. Throwing is isolated by the caller and treated as "no change".
 */
fun interface TaskPreHook {
    fun beforeSubmit(request: DownloadRequest): DownloadRequest
}

/**
 * Task post-processing hook contract. [success] indicates completion vs failure; [detail] is a
 * file path on success or an error message on failure.
 */
fun interface TaskPostHook {
    fun afterFinish(request: DownloadRequest, success: Boolean, detail: String?)
}

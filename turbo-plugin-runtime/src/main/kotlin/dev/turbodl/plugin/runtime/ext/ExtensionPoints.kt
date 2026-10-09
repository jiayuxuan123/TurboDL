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
 * NOTE: external loaders and shims implement these contracts through the registry. `turbo-plugin-js`
 * bridges `LinkParser` and the two task hooks for scripts; `DOWNLOAD_BACKEND` stays Kotlin-only, and
 * no implementation of it is provided by the runtime itself.
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

    /**
     * Protocol declaration extension point.
     *
     * A plugin registers one [ProtocolClaim] per protocol/scheme it can handle ("hls", "magnet",
     * "ftp", "ed2k", ...). One plugin MAY declare several — the ecosystem's model is "one plugin,
     * many protocols", not "one protocol, one plugin", so a single magnet/BT/eD2K plugin can own
     * its whole family of schemes.
     *
     * This is a DECLARATION, not a grant and not a routing rule:
     *  - it does not change what handles a download — [DOWNLOAD_BACKEND] predicates still decide
     *    that, so a claim by itself routes nothing;
     *  - it confers no permission of any kind (see the Convention: capabilities/declarations are
     *    labels, permissions are enforced by the host's own boundaries);
     *  - it is the load-time answer to "who declares this scheme?", consumed by
     *    [ProtocolRegistry] for diagnostics, market filtering and pre-download discovery.
     *
     * Multiple plugins MAY claim the same scheme; [ProtocolRegistry] orders claimants by
     * priority (highest first) and exposes the overlap through `conflicts()`.
     */
    val PROTOCOL_HANDLER: ExtensionPointKey<ProtocolClaim> =
        ExtensionPointKey.of("turbo.protocolHandler")
}

/**
 * A plugin's declaration that it handles one protocol/scheme.
 *
 * Registered at [ExtensionPoints.PROTOCOL_HANDLER], normally right next to the plugin's real
 * routing registration (e.g. its [dev.turbodl.core.DownloadBackend]) so the two never drift
 * apart. [ProtocolRegistry] turns the registered claims into a load-time scheme index.
 *
 * @param scheme protocol/scheme name, lowercase ("hls", "magnet", "ftp", "webdavs"). The
 *   registry normalizes it (trim + lowercase) and ignores a value that does not normalize to a
 *   valid protocol token, so a malformed claim can never throw at query time.
 * @param pluginId the plugin making the declaration. Filled by the declaring plugin; when blank
 *   the registry falls back to the plugin that actually registered the claim.
 * @param priority ordering among multiple claimants of the same scheme (higher wins). It SHOULD
 *   equal the `priority` passed to `PluginContext.registerExtension` so the generic extension
 *   registry and the scheme index agree on order.
 * @param label optional free-form origin/provenance note ("hls", "aria2-adapter", ...) surfaced
 *   in diagnostics and market listings. Never interpreted by the runtime.
 */
data class ProtocolClaim(
    /** 归一化后的 scheme，小写，如 "magnet" / "ftp" / "webdav"。 */
    val scheme: String,
    /** 声明它的插件 id。 */
    val pluginId: String,
    /** 同一 scheme 多方声明时的优先级（大的先）。 */
    val priority: Int,
    /** 可选：说明这个声明从哪来（"hls" / "aria2-adapter"…），用于诊断与市场展示。 */
    val label: String = "",
)

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

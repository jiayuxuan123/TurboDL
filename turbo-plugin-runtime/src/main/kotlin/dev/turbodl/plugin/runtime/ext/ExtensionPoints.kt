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
     * 文件处理器挂载点（P16）。
     *
     * ## 命名空间为什么是 `dev.turbodl` 而不是宿主名
     *
     * 这个挂载点属于 **TurboDL SDK 的公共契约** —— 任何用这个 SDK 的人都可以
     * 实现它，不只是云取。写成宿主名（`yung.cap.*`）等于宣布"只有云取认这个点"，
     * 把宿主概念泄漏进公共接口，那正是"SDK 变成某个应用的专用内核"的开始。
     *
     * 约定：SDK 自己的挂载点一律用 `dev.turbodl.cap.*`；宿主若要加自己的挂载点，
     * 用自己的命名空间（云取是 `yung.cap.*`）。
     */
    val FILE_HANDLER: ExtensionPointKey<FileHandler> =
        ExtensionPointKey.of("dev.turbodl.cap.file.handler")

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

/**
 * 下载完成后的**文件处理器**（P16；设计文档第 4、17 章）。
 *
 * ## 与 [TaskPostHook] 的区别
 *
 * 这两个看起来都在"下载结束后做事"，但它们拿到的**能力**不同：
 *
 *  - [TaskPostHook] 拿到 `(request, success, detail)`：一个**字符串**。
 *    它适合算校验和、解压、清理网盘临时转存、弹通知 —— 这些都只需要知道"成了没成"。
 *  - [FileHandler] 拿到一个 [HandledFile]：**宿主已经验过、准备落盘的最终文件**，
 *    并且带 MIME 与大小。它适合"交给别的 App 处理"：起播放器、导入相册、丢进某个目录。
 *
 * 换成 `TaskPostHook` 做播放器要重新解析 MIME、重新拼文件路径、还要自己判断文件是否存在 ——
 * 那些宿主都已经算好了。把它做成独立挂载点，是为了不让每个处理器插件都重写一遍。
 *
 * ## 为什么需要一个"能不能处理"的判断
 *
 * [matches] 让宿主**先问再交**：文件类型不对时（处理器只认视频、来了张图片）直接
 * 返回 false，不做任何事。少了这一步，处理器要么被迫处理一切（收到无关文件），
 * 要么自己再实现一遍类型判断（和宿主的判断逻辑必然对不上）。
 *
 * ## 执行时机与失败语义
 *
 * 宿主在**文件落盘之后**调用。处理器抛异常时：**记录并继续**——
 * 一个播放器打不开，不该让"下载已完成"变成失败，更不该删掉已下好的文件。
 */
interface FileHandler {

    /** 这个处理器 id（用于诊断与界面展示）。 */
    val id: String

    /** 人类可读名。 */
    val name: String

    /**
     * 能否处理这个文件。
     *
     * @return true 时宿主才会调用 [handle]
     */
    fun matches(file: HandledFile): Boolean

    /**
     * 处理文件。
     *
     * @return 给用户看的一句话（会进日志，可能展示在通知里）；不必成功
     */
    fun handle(file: HandledFile): String
}

/**
 * 交给 [FileHandler] 的文件描述。
 *
 * 只有**只读**信息，不给写权限 —— 处理器要做的是"把这个文件交给别的东西"，
 * 不需要、也不该能改内容。
 */
data class HandledFile(
    /** 任务 id（宿主内部，用于关联与诊断）。 */
    val taskId: Long,
    /** 落盘后的绝对路径。 */
    val absolutePath: String,
    /** 文件名（不含目录）。 */
    val fileName: String,
    /** 字节数；未知为 0。 */
    val sizeBytes: Long,
    /** MIME 类型；宿主未能判定时为空串（**不要猜** —— 猜错会让处理器选错）。 */
    val mimeType: String,
    /** 扩展名（小写，不含点）；无扩展名时为空串。 */
    val extension: String,
    /** 原始下载地址（诊断用，可能含 token —— 记录它时必须脱敏）。 */
    val sourceUrl: String,
)

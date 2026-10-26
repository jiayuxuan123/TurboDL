/*
 * TurboDL — 多线程下载引擎
 *
 * Copyright (C) 2026 TurboDL contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.turbodl.plugin.parser

import dev.turbodl.core.DownloadRequest
import java.io.File

/**
 * 一条被解析出来的媒体。
 *
 * 与直接拼 [DownloadRequest] 的区别：解析阶段**还不知道目标目录**（`destination` 由宿主
 * 按自己的下载路径策略决定），所以这里只描述"要下什么、叫什么名字、带什么头"，
 * 由 [PlatformParsers] 在交给宿主时补上 destination。
 *
 * @param url 直链（http/https）
 * @param fileName 建议文件名。宿主的路径策略仍可改写它，这里只是"最合理的名字"。
 * @param headers 这次下载必须带的请求头（Referer / UA / Cookie）。
 *   各平台都校验防盗链，缺了直接 403 —— 这些头是解析那一刻才知道的，
 *   所以必须跟着媒体一起往上传，不能指望下载器自己拼一套默认值。
 * @param tags 只用于展示与诊断的字符串表（`quality` / `platform` / `title`…）。
 */
data class ParsedMedia(
    val url: String,
    val fileName: String,
    val headers: Map<String, String> = emptyMap(),
    val tags: Map<String, String> = emptyMap(),
)

/**
 * 一次解析的产物。
 *
 * @param platform 平台标识（小写，如 `weibo`），用于诊断与插件市场的展示。
 * @param medias 解析出的全部媒体；空列表等价于"没有解析到"。
 */
data class ParsedPost(
    val platform: String,
    val medias: List<ParsedMedia>,
) {
    /** 作品信息（标题/作者/封面…）—— 从第一条媒体的 tags 里取，约定见 [ParsedMedia.tags]。 */
    fun tag(key: String): String = medias.firstOrNull()?.tags?.get(key).orEmpty()
}

/**
 * 平台解析器的契约。
 *
 * ## 为什么返回类型是 `ParsedPost?` 而不是 `List<DownloadRequest>?`
 *
 * 引擎的 [dev.turbodl.plugin.runtime.ext.LinkParser] 契约要求返回 `DownloadRequest`，
 * 而 `DownloadRequest` **必须**有一个 `destination: File`。解析阶段宿主还没决定
 * 这个文件该落在哪（下载目录策略、重名处理、任务分组都在宿主手里），
 * 由插件凭空造一个路径就是在替宿主做决定 —— 那份决定必然是错的。
 *
 * 所以插件这一层只产 [ParsedPost]（"要下什么"），落点由 [PlatformParsers] 转换时补。
 */
interface PlatformParser {

    /** 平台标识（小写）。 */
    val platform: String

    /** 展示名。 */
    val displayName: String

    /**
     * 这个解析器是否认领该输入。
     *
     * 必须是**纯字符串判断**、不联网：注册表要在不产生任何网络请求的前提下
     * 决定"该问谁"，否则粘一个无关链接就会打七次网络。
     */
    fun matches(raw: String): Boolean

    /**
     * 解析。
     *
     * @return 解析结果；不认领、内容不存在、需要登录、平台改版都返回 null。
     *   **不要抛异常** —— 一个平台失效不该让整条解析链路炸掉。
     */
    fun parse(raw: String): ParsedPost?
}

/**
 * [PlatformParser] → 引擎 [dev.turbodl.plugin.runtime.ext.LinkParser] 的薄适配。
 *
 * 它把 [ParsedPost] 里"还没有落点"的媒体补成完整的 [DownloadRequest]。
 * 落点用 `sanitizeFileName` 清洗过的文件名放在宿主给的工作目录下 —— 这是
 * **插件能安全给出的最中性答案**：宿主拿到 `DownloadRequest` 后仍会走自己的
 * 路径策略（重名、分类目录、SAF），所以这里的目录只是一个合法的默认值。
 *
 * ## 文件名清洗为什么必须做
 *
 * 平台标题里会出现 `/`、`:`、`?`、换行，甚至 `..`。直接拼进路径会写到工作目录之外
 * （目录穿越），在 Android 上还会因为非法字符让 MediaStore 拒绝入库。
 */
internal fun ParsedMedia.toDownloadRequest(workDir: File): DownloadRequest =
    DownloadRequest(
        url = url,
        destination = File(workDir, sanitizeFileName(fileName)),
        headers = headers,
        knownSize = -1L,
        connectionsOverride = null,
        stableKey = null,
        tags = tags,
    )

/**
 * 清洗文件名：只留末段、替换非法字符、拦住隐藏文件、限长。
 *
 * 保留扩展名（末段 `.xxx`），因为播放器与 MediaStore 都靠它判类型。
 *
 * 两步的顺序不能反：
 * 1. **先按分隔符取末段** —— 平台标题里出现 `a/b.mp4` 时，正确结果是 `b.mp4`
 *    （用户看到的名字就是末段），而不是把 `/` 换成 `_` 得到一个 `a_b.mp4`；
 * 2. **再替换剩余的非法字符** —— Windows/Android 都会拒收 `:`、`?`、`*` 这些，
 *    MediaStore 的入库也会失败（表现为"下载完成了但相册里没有"）。
 */
internal fun sanitizeFileName(raw: String): String {
    val base = raw.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = base
        .map { ch ->
            when {
                ch == ':' || ch == '*' || ch == '?' || ch == '"' ||
                    ch == '<' || ch == '>' || ch == '|' -> '_'
                ch.code < 0x20 -> '_'
                else -> ch
            }
        }
        .joinToString("")
        .trim()
        .trimStart('.')          // 隐藏文件 + `..` 都被这一步挡住
    val safe = cleaned.ifBlank { "download.bin" }
    // 文件名上限：主流文件系统是 255 字节，中文按 3 字节算，留到 120 字符足够
    return if (safe.length <= 120) safe else safe.take(120)
}

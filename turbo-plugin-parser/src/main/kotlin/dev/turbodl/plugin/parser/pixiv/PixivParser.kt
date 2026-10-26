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

package dev.turbodl.plugin.parser.pixiv

import dev.turbodl.plugin.parser.MiniJson
import dev.turbodl.plugin.parser.ParsedMedia
import dev.turbodl.plugin.parser.ParsedPost
import dev.turbodl.plugin.parser.ParserHttp
import dev.turbodl.plugin.parser.PlatformParser
import dev.turbodl.plugin.parser.arr
import dev.turbodl.plugin.parser.bool
import dev.turbodl.plugin.parser.long
import dev.turbodl.plugin.parser.obj
import dev.turbodl.plugin.parser.str

/**
 * Pixiv 插画 / 漫画解析，产出每页的**原图直链**。
 *
 * ## 两个公开 ajax 接口
 *
 * 1. `ajax/illust/{id}` —— 标题 / 作者 / 作品类型
 * 2. `ajax/illust/{id}/pages` —— 每一页的 `urls.original`
 *
 * 游客身份就能拿到**一部分**公开作品；R18 与受限作品必须登录，
 * 服务端会返回 `error`。解析器本身不持有 Cookie（宿主若有登录态，
 * 应由宿主自己实现带 Cookie 的解析器并注册更高优先级）。
 *
 * ## 已知限制（刻意不做，不是遗漏）
 *
 * - **动图（ugoira）不支持**：它是 zip + 帧时长表，要合成 GIF/视频得靠图像处理。
 *   这里明确返回 null（并记日志），而不是产出一个打不开的文件。
 * - **小说不支持**：不在本解析器的识别范围内。
 *
 * ## 防盗链
 *
 * `i.pximg.net` 只校验 `Referer`，缺了**一律 403**。所以每一条媒体的请求头里
 * 都必须带 `Referer: https://www.pixiv.net/` —— 这是 Pixiv 解析最容易被忘掉的一步。
 */
object PixivParser : PlatformParser {

    override val platform: String = "pixiv"
    override val displayName: String = "Pixiv"

    private const val REFERER = "https://www.pixiv.net/"
    private const val API = "https://www.pixiv.net/ajax/illust"

    private val ARTWORK = Regex("""artworks/(\d{5,})""", RegexOption.IGNORE_CASE)
    private val ILLUST_ID_PARAM = Regex("""illust_id=(\d{5,})""", RegexOption.IGNORE_CASE)
    /** 直链形态：`.../<id>_p0.jpg` —— 用户常直接把右键拿到的图片地址粘过来。 */
    private val IMAGE_NAME = Regex("""/(\d{6,})_p\d+\.""")
    private val BARE_ID = Regex("""\b(\d{6,})\b""")

    /**
     * 链接识别：**域名判断在前**。
     *
     * 裸数字那段（[BARE_ID]）尤其危险 —— 任何链接里带 6 位以上数字都会被认领，
     * 所以它只在确认是 pixiv 域名（或 pximg 直链）之后才用。
     */
    override fun matches(raw: String): Boolean {
        val isPixiv = raw.contains("pixiv.net", ignoreCase = true) ||
            raw.contains("pximg.net", ignoreCase = true)
        if (!isPixiv) return false
        return extractId(raw) != null
    }

    override fun parse(raw: String): ParsedPost? {
        val id = extractId(raw) ?: return null

        val info = fetchJson("$API/$id") ?: return null
        if (info.bool("error") == true) return null

        val body = info.obj("body") ?: return null

        // 动图：zip + 帧表，要合成得靠图像处理，本期不支持
        if (body.long("illustType") == 2L) return null

        val pages = fetchJson("$API/$id/pages") ?: return null
        if (pages.bool("error") == true) return null
        val entries = pages.arr("body") ?: return null

        val title = body.str("title").orEmpty()
        val author = body.str("userName").orEmpty()
        val cover = body.obj("urls")?.str("regular").orEmpty()

        val meta = buildMap {
            put("platform", platform)
            if (title.isNotBlank()) put("title", title)
            if (author.isNotBlank()) put("author", author)
            if (cover.isNotBlank()) put("cover", cover)
            put("pageUrl", "https://www.pixiv.net/artworks/$id")
        }

        val total = entries.size
        val medias = entries.mapIndexedNotNull { index, entry ->
            @Suppress("UNCHECKED_CAST")
            val page = entry as? Map<String, Any?> ?: return@mapIndexedNotNull null
            val original = page.obj("urls")?.str("original") ?: return@mapIndexedNotNull null
            val ext = original.substringAfterLast('.', "jpg").takeIf { it.length in 2..5 } ?: "jpg"
            ParsedMedia(
                url = original,
                fileName = "pixiv_${id}_p$index.$ext",
                headers = mediaHeaders(),
                tags = mapOf(
                    "quality" to if (total > 1) "P${index + 1}/$total" else "原图",
                    "kind" to "image",
                ),
            )
        }

        if (medias.isEmpty()) return null

        return ParsedPost(
            platform = platform,
            medias = medias.mapIndexed { index, media ->
                if (index == 0) media.copy(tags = media.tags + meta) else media
            },
        )
    }

    private fun fetchJson(url: String): Map<String, Any?>? {
        val response = ParserHttp.getText(
            url = url,
            headers = mapOf(
                "User-Agent" to ParserHttp.UA_CHROME,
                "Referer" to REFERER,
                "Accept" to "application/json",
            ),
        ) ?: return null
        if (!response.ok) return null
        return MiniJson.parseObject(response.body)
    }

    /**
     * 直链 CDN 只校验 Referer；带上 UA 是为了绕开少数按 UA 做的软风控。
     */
    private fun mediaHeaders(): Map<String, String> = mapOf(
        "Referer" to REFERER,
        "User-Agent" to ParserHttp.UA_CHROME,
    )

    internal fun extractId(raw: String): String? {
        ARTWORK.find(raw)?.let { return it.groupValues[1] }
        ILLUST_ID_PARAM.find(raw)?.let { return it.groupValues[1] }
        IMAGE_NAME.find(raw)?.let { return it.groupValues[1] }
        return BARE_ID.find(raw)?.groupValues?.get(1)
    }
}

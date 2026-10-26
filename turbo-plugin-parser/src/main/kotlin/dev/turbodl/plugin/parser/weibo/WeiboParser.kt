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

package dev.turbodl.plugin.parser.weibo

import dev.turbodl.plugin.parser.MiniJson
import dev.turbodl.plugin.parser.ParsedMedia
import dev.turbodl.plugin.parser.ParsedPost
import dev.turbodl.plugin.parser.ParserHttp
import dev.turbodl.plugin.parser.PlatformParser
import dev.turbodl.plugin.parser.arr
import dev.turbodl.plugin.parser.obj
import dev.turbodl.plugin.parser.str
import java.net.URLEncoder

/**
 * 微博视频 / 图集解析。
 *
 * ## 为什么走 m.weibo.cn 而不是 weibo.com
 *
 * PC 站（`weibo.com`）的页面要靠 Cookie 才能看到完整内容，还会渲染一堆脚本；
 * 移动站的 `statuses/show` 接口**匿名就返回完整 JSON**，媒体直链也在里面。
 * 所以无论用户给的是哪种链接形态，都统一换成这个接口。
 *
 * ## 链接形态
 *
 * - `https://weibo.com/<uid>/<bid>` —— bid 是 62 进制短码，接口也接受，不必转成数字 mid
 * - `https://m.weibo.cn/detail/<mid>` / `/status/<mid>`
 * - 分享文案里裹着的长数字 mid
 *
 * ## 已知限制
 *
 * - **付费/私密微博**拿不到（接口返回 error），这里如实返回 null 而不是猜；
 * - **长文（头条文章）**的正文在另一个接口里，本解析器只取微博正文里的媒体。
 */
object WeiboParser : PlatformParser {

    override val platform: String = "weibo"
    override val displayName: String = "微博"

    private const val REFERER = "https://m.weibo.cn/"

    /** 链接识别：纯字符串，不联网。 */
    private val LINK_PATTERNS = listOf(
        Regex("""weibo\.cn/(?:detail|status)/(\d{10,})""", RegexOption.IGNORE_CASE),
        // weibo.com/<uid>/<bid>：结尾不能要求 `?` 或行尾 —— 分享文案里这个链接后面
        // 常直接跟中文或空格，用 `(?:\?|$)` 会让最常见的粘贴形态识别失败。
        Regex("""weibo\.com/(?:\d+/)?([A-Za-z0-9]{9,12})""", RegexOption.IGNORE_CASE),
    )

    /** 兜底：分享文案里的裸长数字（15 位以上的 mid）。 */
    private val BARE_MID = Regex("""/(\d{15,})""")

    /**
     * 链接识别：纯字符串，不联网。
     *
     * **域名判断在前** —— 只按路径（`/status/<数字>`）匹配会把任何站点的同形路径
     * 都认成微博，然后去请求微博接口，用户看到的是"解析失败"而不是"这不是微博链接"。
     */
    override fun matches(raw: String): Boolean {
        if (!raw.contains("weibo.", ignoreCase = true) &&
            !raw.contains("weibo.cn", ignoreCase = true)
        ) {
            // 允许"没有域名的裸 mid"这种形态，但要求它带斜杠开头（分享文案里的路径片段）
            return BARE_MID.containsMatchIn(raw) && raw.length < 200
        }
        return extractId(raw) != null
    }

    override fun parse(raw: String): ParsedPost? {
        val id = extractId(raw) ?: return null

        val api = "https://m.weibo.cn/statuses/show?id=" +
            URLEncoder.encode(id, "UTF-8")

        val response = ParserHttp.getText(
            url = api,
            headers = mapOf(
                "User-Agent" to ParserHttp.UA_MOBILE_SAFARI,
                "Accept" to "application/json, text/plain, */*",
                "Referer" to REFERER,
            ),
        ) ?: return null
        if (!response.ok) return null

        val root = runCatching { MiniJson.parseObject(response.body) }.getOrNull() ?: return null
        // 接口偶尔把正文套在 data 里一层
        val body = root.obj("data") ?: root

        return buildPost(body)
    }

    private fun buildPost(data: Map<String, Any?>): ParsedPost? {
        val mid = data.str("id") ?: data.str("mid") ?: "weibo"
        val user = data.obj("user")
        val author = user?.str("screen_name").orEmpty()
        val pageInfo = data.obj("page_info")

        val meta = buildMap {
            put("platform", platform)
            if (author.isNotBlank()) {
                put("title", "$author 的微博")
                put("author", author)
            }
            pageInfo?.obj("page_pic")?.str("url")?.let { put("cover", it) }
            // 正文里带 HTML 标签（<a>、<br>），展示前必须去掉
            stripHtml(data.str("text").orEmpty()).takeIf { it.isNotBlank() }?.let { put("text", it) }
            put("pageUrl", "https://m.weibo.cn/detail/$mid")
        }

        val medias = mutableListOf<ParsedMedia>()

        // 1) 视频：urls 的 key 形如 mp4_1080p / mp4_720p / mp4_hd，取质量最高的那个
        val urls = pageInfo?.obj("urls")
        if (urls != null) {
            val preferred = listOf("mp4_1080p", "mp4_720p", "mp4_hd", "mp4_sd", "mp4_ld")
            for (key in preferred) {
                val url = urls.str(key) ?: continue
                medias += ParsedMedia(
                    url = url,
                    fileName = "weibo_${mid}_${key.removePrefix("mp4_")}.mp4",
                    headers = mediaHeaders(),
                    tags = mapOf(
                        "quality" to key.removePrefix("mp4_").uppercase(),
                        "kind" to "video",
                    ),
                )
                break
            }
        }

        // 2) 图集（含 9 宫格）
        val pics = data.arr("pics").orEmpty()
        pics.forEachIndexed { index, item ->
            @Suppress("UNCHECKED_CAST")
            val pic = item as? Map<String, Any?> ?: return@forEachIndexed
            val raw = pic.obj("large")?.str("url") ?: pic.str("url") ?: return@forEachIndexed
            medias += ParsedMedia(
                url = upgradeImage(raw),
                fileName = "weibo_${mid}_${index + 1}.jpg",
                headers = mediaHeaders(),
                tags = mapOf("quality" to "原图", "kind" to "image"),
            )
        }

        if (medias.isEmpty()) return null

        // 作品信息挂在第一条上：宿主按"第一条的 tags 是整批的元信息"来读
        return ParsedPost(
            platform = platform,
            medias = medias.mapIndexed { index, media ->
                if (index == 0) media.copy(tags = media.tags + meta) else media
            },
        )
    }

    /**
     * 缩略图 → 原图。
     *
     * 微博的图片地址有两种形态：`/orj360/`、`/thumb150/` 是缩略图，
     * `/large/` 是原图。这是社区通用的替换（服务端对同一张图的多套尺寸用路径区分），
     * 换成 large 才能拿到不糊的图。
     */
    internal fun upgradeImage(url: String): String =
        url.replace("/orj360/", "/large/").replace("/thumb150/", "/large/")

    /** 媒体请求头：CDN 校验 Referer 与 UA，缺了一样 403。 */
    private fun mediaHeaders(): Map<String, String> = mapOf(
        "Referer" to REFERER,
        "User-Agent" to ParserHttp.UA_MOBILE_SAFARI,
    )

    internal fun extractId(raw: String): String? {
        for (pattern in LINK_PATTERNS) {
            val match = pattern.find(raw) ?: continue
            return match.groupValues[1]
        }
        return BARE_MID.find(raw)?.groupValues?.get(1)
    }

    /** 去掉微博正文里的 HTML 标签与实体。 */
    internal fun stripHtml(text: String): String =
        text.replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
}

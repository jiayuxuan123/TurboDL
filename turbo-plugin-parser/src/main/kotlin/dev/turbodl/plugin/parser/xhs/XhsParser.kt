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

package dev.turbodl.plugin.parser.xhs

import dev.turbodl.plugin.parser.MiniJson
import dev.turbodl.plugin.parser.ParsedMedia
import dev.turbodl.plugin.parser.ParsedPost
import dev.turbodl.plugin.parser.ParserHttp
import dev.turbodl.plugin.parser.PlatformParser
import dev.turbodl.plugin.parser.arr
import dev.turbodl.plugin.parser.long
import dev.turbodl.plugin.parser.obj
import dev.turbodl.plugin.parser.str

/**
 * 小红书笔记解析（图片 / 视频）。
 *
 * ## 数据来源
 *
 * 网页的分享页把完整数据内嵌在 `window.__INITIAL_STATE__` 里，不需要额外接口。
 * 但那份状态是**嵌套结构各版本都不一样**的（有时 `note.noteDetailMap[id].note`，
 * 有时更浅），所以这里不写死路径，而是**按特征字段做有界搜索**：
 * 同时带 `imageList` 或 `video`、且带 `noteId`/`id` 的那个对象就是目标。
 *
 * ## 为什么匿名大多解析不了
 *
 * 实测 2026-10：未登录访问笔记页会被 302 到 `/login`（explore 路径）或
 * `/website-login/captcha`（discovery 路径），页面里没有 `__INITIAL_STATE__`。
 * 所以这个解析器**匿名会失败**，但宿主可以用带登录态的客户端重试 ——
 * 解析器本身不持有任何 Cookie。
 *
 * ## 视频地址的两条路
 *
 * 1. `video.media.stream.h264[]` 每项有 `masterUrl`，取码率最高的那档（数组末尾）；
 * 2. 兜底 `video.consumer.originVideoKey`，拼 `https://sns-video-bd.xhscdn.com/<key>`。
 *
 * 后者是"原始视频"的 key（无水印），前者是转码后的流 —— 优先用前者是因为它的
 * 可用性更稳定；只有它缺失时才退回 key 拼接。
 */
object XhsParser : PlatformParser {

    override val platform: String = "xhs"
    override val displayName: String = "小红书"

    private const val REFERER = "https://www.xiaohongshu.com/"
    private const val ORIGIN = "https://www.xiaohongshu.com"

    private val NOTE_ID = Regex("""(?:explore|discovery/item)/([0-9a-f]{24})""", RegexOption.IGNORE_CASE)
    private val SHORT_LINK = Regex("""xhslink\.(?:com|cn)/([A-Za-z0-9._?%&+=/#@\-]+)""", RegexOption.IGNORE_CASE)

    /**
     * 链接识别：**必须先确认域名**。
     *
     * 只匹配 `explore/<24 位十六进制>` 会把任何站点的同形路径都认成小红书
     * （`example.com/explore/65f0…` 也符合），然后去请求小红书的页面 —— 用户看到的
     * 是"解析失败"，而真正的原因是识别错了站。所以域名判断在路径判断之前。
     */
    override fun matches(raw: String): Boolean {
        if (SHORT_LINK.containsMatchIn(raw)) return true
        if (!raw.contains("xiaohongshu.com", ignoreCase = true)) return false
        return NOTE_ID.containsMatchIn(raw)
    }

    override fun parse(raw: String): ParsedPost? {
        val pageUrl = resolvePageUrl(raw) ?: return null

        val response = ParserHttp.getText(
            url = pageUrl,
            headers = mapOf(
                "User-Agent" to ParserHttp.UA_CHROME,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to REFERER,
            ),
        ) ?: return null
        if (!response.ok) return null

        val state = readInitialState(response.body) ?: return null
        val note = findNote(state) ?: return null
        return buildPost(note)
    }

    /**
     * 分享短链 → 真实笔记页。
     *
     * `xhslink.com/xxx` 会 302 到真正的 `xiaohongshu.com/explore/...`。
     * 我们自己不跟随重定向（见 [ParserHttp]），所以这里读 `Location`。
     */
    internal fun resolvePageUrl(raw: String): String? {
        NOTE_ID.find(raw)?.let { return "$ORIGIN/explore/${it.groupValues[1]}" }

        val short = SHORT_LINK.find(raw) ?: return null
        val shortUrl = "http://xhslink.com/${short.groupValues[1]}"
        val hop = ParserHttp.getText(
            url = shortUrl,
            headers = mapOf("User-Agent" to ParserHttp.UA_CHROME),
            maxBytes = 64 * 1024,
        ) ?: return null
        val location = hop.location ?: hop.finalUrl
        // 短链展开后可能又是一个短链（极少），用正则再认一次 noteId
        return NOTE_ID.find(location)?.let { "$ORIGIN/explore/${it.groupValues[1]}" }
            ?: location.takeIf { it.contains("xiaohongshu.com") }
    }

    /**
     * 从页面 HTML 里挖出 `window.__INITIAL_STATE__` 的 JSON。
     *
     * 页面里写的是 JS 对象字面量而不是严格 JSON：值里会出现裸 `undefined`。
     * 直接换掉（这是社区一致的解法）比写一个 JS 字面量解析器划算得多 ——
     * 而且不会误伤：字符串里的 `undefined` 会被引号包住，替换成 `null` 也只影响值。
     */
    internal fun readInitialState(html: String): Map<String, Any?>? {
        val marker = "__INITIAL_STATE__"
        val at = html.indexOf(marker)
        if (at < 0) return null

        val eq = html.indexOf('=', at)
        if (eq < 0) return null

        var start = eq + 1
        while (start < html.length && html[start].isWhitespace()) start++

        val end = html.indexOf("</script>", start)
        if (end < 0) return null

        val raw = html.substring(start, end)
            .replace(Regex("""\bundefined\b"""), "null")

        return MiniJson.parseObject(raw)
    }

    /**
     * 有界 DFS 找笔记对象。
     *
     * 状态树很深，而且不同版本的层级不同，写死路径会在改版时静默失效。
     * 按"有 imageList 或 video，且有 noteId 或 id"这条特征找，改版时也能命中。
     *
     * 上限 500 个节点：状态里还有推荐流、用户信息等大量无关对象，
     * 不设上限就是给一个"页面变大后解析卡住主线程"的机会。
     */
    internal fun findNote(state: Map<String, Any?>): Map<String, Any?>? {
        val stack = ArrayDeque<Any?>()
        stack.addLast(state)
        var visited = 0

        while (stack.isNotEmpty() && visited < 500) {
            val current = stack.removeLast()
            visited++
            val map = current as? Map<*, *> ?: continue

            @Suppress("UNCHECKED_CAST")
            val node = map as Map<String, Any?>
            val hasMedia = node.obj("imageList") != null || node.obj("video") != null
            val hasId = node.str("noteId") != null || node.str("id") != null
            if (hasMedia && hasId) return node

            for (value in node.values) {
                when (value) {
                    is Map<*, *> -> stack.addLast(value)
                    is List<*> -> value.forEach { stack.addLast(it) }
                }
            }
        }
        return null
    }

    private fun buildPost(note: Map<String, Any?>): ParsedPost? {
        val noteId = note.str("noteId") ?: note.str("id") ?: "note"
        val user = note.obj("user")
        val images = note.arr("imageList").orEmpty()

        val meta = buildMap {
            put("platform", platform)
            (note.str("title") ?: note.str("desc"))?.takeIf { it.isNotBlank() }?.let { put("title", it) }
            (user?.str("nickname") ?: user?.str("nickName"))?.let { put("author", it) }
            coverOf(images.firstOrNull())?.let { put("cover", it) }
            note.str("desc")?.takeIf { it.isNotBlank() }?.let { put("text", it) }
            if (note.long("time") != null) put("timestampSeconds", note.long("time").toString())
            put("pageUrl", "$ORIGIN/explore/$noteId")
        }

        val medias = mutableListOf<ParsedMedia>()

        // 1) 视频
        videoUrlOf(note)?.let { url ->
            medias += ParsedMedia(
                url = url,
                fileName = "xhs_${noteId}.mp4",
                headers = mediaHeaders(),
                tags = mapOf("quality" to "无水印", "kind" to "video"),
            )
        }

        // 2) 图片：逐张给原图
        images.forEachIndexed { index, item ->
            val url = coverOf(item) ?: return@forEachIndexed
            medias += ParsedMedia(
                url = url,
                fileName = "xhs_${noteId}_${index + 1}.jpg",
                headers = mediaHeaders(),
                tags = mapOf("quality" to "原图", "kind" to "image"),
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

    /** 图片 URL：优先 `urlDefault`（默认尺寸，稳定），其次 `url`/`urlPre`。 */
    internal fun coverOf(item: Any?): String? {
        @Suppress("UNCHECKED_CAST")
        val node = item as? Map<String, Any?> ?: return null
        node.str("urlDefault")?.let { return it }
        node.str("url")?.let { return it }
        node.str("urlPre")?.let { return it }
        // 部分版本把地址放在 infoList 里，带 imageScene 标记
        return node.arr("infoList")?.firstNotNullOfOrNull { entry ->
            (entry as? Map<*, *>)?.get("url") as? String
        }
    }

    /**
     * 视频直链。
     *
     * 优先取 `stream.h264` 的最后一档（码率最高）；缺失时退回 `originVideoKey` 拼接。
     */
    internal fun videoUrlOf(note: Map<String, Any?>): String? {
        val video = note.obj("video") ?: return null

        val h264 = video.obj("media")?.obj("stream")?.arr("h264").orEmpty()
        @Suppress("UNCHECKED_CAST")
        val best = (h264.lastOrNull() as? Map<String, Any?>)?.str("masterUrl")
        if (best != null) return best

        val key = video.obj("consumer")?.str("originVideoKey")
        return key?.takeIf { it.isNotBlank() }?.let { "https://sns-video-bd.xhscdn.com/$it" }
    }

    private fun mediaHeaders(): Map<String, String> = mapOf(
        "Referer" to REFERER,
        "User-Agent" to ParserHttp.UA_CHROME,
    )
}

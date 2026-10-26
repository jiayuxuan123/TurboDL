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
import dev.turbodl.plugin.parser.pixiv.PixivParser
import dev.turbodl.plugin.parser.weibo.WeiboParser
import dev.turbodl.plugin.parser.xhs.XhsParser
import dev.turbodl.plugin.runtime.Plugin
import dev.turbodl.plugin.runtime.PluginContext
import dev.turbodl.plugin.runtime.ext.ExtensionPoints
import dev.turbodl.plugin.runtime.ext.LinkParser
import dev.turbodl.plugin.runtime.ext.ProtocolClaim
import java.io.File

/**
 * 视频平台链接解析插件（微博 / 小红书 / Pixiv）。
 *
 * ## 与 HLS 插件同类
 *
 * 这是**引擎级原生插件**：一个普通 `Plugin`，只依赖 core 与 runtime 的公共契约，
 * 不碰宿主、不碰 UI、不持有凭证。它做的事就是往
 * [ExtensionPoints.LINK_PARSER] 注册一个解析器，和 `HlsPlugin` 往
 * [ExtensionPoints.DOWNLOAD_BACKEND] 注册后端是同一套机制。
 *
 * 引擎内核**完全不知道**微博/小红书/Pixiv 的存在 —— 这正是插件化的意义：
 * 平台改版只需要换这个插件，不需要动引擎。
 *
 * ## 为什么注册一个解析器而不是三个
 *
 * 三个平台共用同一份"链接 → 媒体"的编排（判重、清洗文件名、补 destination）。
 * 注册三个各自的 [LinkParser] 会让这段编排复制三遍，而它们唯一不同的是
 * `matches` / `parse` 两个方法。这里用 [PlatformParsers] 做一次分发，
 * 注册一个 parser，priority 按平台各自的最大值取。
 *
 * 但 `PROTOCOL_HANDLER` 上仍然**每个平台一条声明** —— 那个扩展点的语义是
 * "谁声明这个 scheme"，一条笼统的声明回答不了 "weibo 归谁"。
 *
 * ## 优先级
 *
 * 默认 150，低于 HLS 的 100？不 —— 数字越大越优先（见 `ExtensionRegistry.highest`）。
 * HLS 用 100，这里用 150：解析器只在**链接认领**时介入（`matches` 为假立刻返回 null），
 * 与 HLS 的后端路由不是同一维度，不会互相抢占。取 150 是为了高于"通用模板"
 * 这类兜底解析器（demo 里用 200 的那个是外接系统的 shim，真装了它也应该先问它）。
 *
 * @param priority 注册优先级；宿主可以在装配时覆盖
 */
class PlatformParserPlugin(
    private val priority: Int = 150,
) : Plugin {

    override val id: String = "parser.platforms"
    override val name: String = "Platform Link Parsers"

    override fun onLoad(context: PluginContext) {
        val parsers: List<PlatformParser> = listOf(WeiboParser, XhsParser, PixivParser)
        val workDir = parserWorkDir()

        val linkParser = LinkParser { rawInput ->
            PlatformParsers.parse(rawInput, parsers, workDir)
        }
        context.registerExtension(ExtensionPoints.LINK_PARSER, linkParser, priority)

        // 每个平台一条声明：`PROTOCOL_HANDLER` 回答的是"谁声明这个 scheme"，
        // 一条笼统的声明回答不了 "weibo 归谁"。
        for (parser in parsers) {
            context.registerExtension(
                ExtensionPoints.PROTOCOL_HANDLER,
                ProtocolClaim(
                    scheme = parser.platform,
                    pluginId = id,
                    priority = priority,
                    label = parser.displayName,
                ),
                priority,
            )
        }

        context.log(
            "registered ${parsers.size} platform link parsers at priority $priority: " +
                parsers.joinToString { it.platform },
        )
    }
}

/**
 * 解析阶段用的落点目录。
 *
 * ## 为什么是临时目录而不是插件数据目录
 *
 * `PluginContext` 不提供文件系统访问（它只有服务/扩展点/日志）—— 这是刻意的：
 * 插件拿不到路径就写不出文件，越权面小得多。
 *
 * 而 [LinkParser] 契约要求返回的 [DownloadRequest] 必须带一个 `destination: File`。
 * 解析阶段宿主**还没决定**这个文件该落在哪（下载目录策略、重名处理、任务分组都在宿主手里），
 * 所以这里给一个合法的临时路径：**宿主拿到请求后会用自己的路径策略覆盖它**。
 * 选 `java.io.tmpdir` 而不是用户可见目录，是因为万一某个宿主真的按这个路径落盘，
 * 它落在临时区而不是污染用户的下载目录。
 */
private fun parserWorkDir(): File =
    File(System.getProperty("java.io.tmpdir") ?: ".", "turbodl-parser").apply { mkdirs() }

/**
 * 平台解析器的分发与结果转换。
 *
 * ## 契约要点（引擎 [LinkParser]）
 *
 * - **不认领就返回 null**，不要抛异常：路由器靠 null 决定"问下一个"。
 *   一个平台改版抛出的 NPE 不该让整条链路炸掉，所以这里对每个解析器都做了隔离。
 * - 返回空列表与返回 null 等价，但**空列表更容易被误读成"解析成功但没东西"**，
 *   所以统一收敛成 null。
 * - 一次最多 [MAX_REQUESTS] 条（引擎侧的上限是 256，这里自己先收口，
 *   平台一个图集几十张图是正常的，几百张说明解析跑飞了）。
 */
internal object PlatformParsers {

    /** 单次解析的媒体上限。 */
    const val MAX_REQUESTS: Int = 256

    /**
     * 解析并把结果转成 [DownloadRequest]。
     *
     * @param workDir 落点目录 —— 只是"合法的默认值"，宿主拿到请求后会走自己的路径策略
     * @return 引擎请求列表；不认领或解析失败返回 null
     */
    fun parse(
        rawInput: String,
        parsers: List<PlatformParser>,
        workDir: File,
    ): List<DownloadRequest>? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null

        // 先做纯字符串的认领判断：能在不联网的前提下排除掉所有不相关的平台。
        // 直接对每个解析器调 parse 会为一条无关链接打出三四次网络请求。
        for (parser in parsers) {
            val claimed = runCatching { parser.matches(trimmed) }.getOrDefault(false)
            if (!claimed) continue

            val post = runCatching { parser.parse(trimmed) }.getOrNull() ?: continue
            if (post.medias.isEmpty()) continue

            return post.medias
                .take(MAX_REQUESTS)
                .map { it.toDownloadRequest(workDir) }
        }
        return null
    }
}

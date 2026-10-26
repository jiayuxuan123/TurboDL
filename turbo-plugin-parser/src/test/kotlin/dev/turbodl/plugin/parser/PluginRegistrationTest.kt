package dev.turbodl.plugin.parser

import dev.turbodl.core.TurboConfig
import dev.turbodl.plugin.bootstrap.TurboBootstrap
import dev.turbodl.plugin.hls.HlsPlugin
import dev.turbodl.plugin.runtime.ext.ExtensionPoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 端到端：插件装进真的 `TurboBootstrap` 之后，扩展点上到底有什么。
 *
 * ## 为什么必须有这个测试
 *
 * 单元测试能证明"解析器本身工作"，但证明不了**它被注册了**。而这正是本项目
 * 反复踩到的坑：机制完整、接线缺失 —— 解析器写得再好，没有注册到扩展点，
 * 宿主查询时就是空的，表现成"插件装了但没用"。
 *
 * 所以这里不测解析逻辑（那是 PlatformLinkTest / PlatformParserTest 的事），
 * 只测三件事：
 *  1. 插件能被装进 bootstrap；
 *  2. `LINK_PARSER` 上真的出现了一个解析器；
 *  3. 三个平台都在 `PROTOCOL_HANDLER` 上各有一条声明。
 *
 * 全程不联网：`LINK_PARSER` 的注册与 `PROTOCOL_HANDLER` 的声明都是装载期完成的，
 * 一次网络请求都不该发生。
 */
class PluginRegistrationTest {

    private fun bootstrapWithParser(): TurboBootstrap = TurboBootstrap.create(
        config = TurboConfig(),
        extraPlugins = listOf(HlsPlugin(), PlatformParserPlugin()),
    )

    @Test
    fun `解析器被注册到 LINK_PARSER 扩展点`() {
        val bootstrap = bootstrapWithParser()
        try {
            val parsers = bootstrap.host.extensions.registrations(ExtensionPoints.LINK_PARSER)
            assertEquals(
                1,
                parsers.size,
                "应当只有 PlatformParserPlugin 注册的一个解析器，实际 ${parsers.map { it.ownerPluginId }}",
            )
            assertEquals("parser.platforms", parsers.first().ownerPluginId)
        } finally {
            bootstrap.shutdown()
        }
    }

    @Test
    fun `三个平台各自在 PROTOCOL_HANDLER 上有声明`() {
        val bootstrap = bootstrapWithParser()
        try {
            val claims = bootstrap.host.extensions
                .registrations(ExtensionPoints.PROTOCOL_HANDLER)
                .map { it.instance }
                .filter { it.pluginId == "parser.platforms" }
                .map { it.scheme }
                .toSet()
            assertEquals(
                setOf("weibo", "xhs", "pixiv"),
                claims,
                "每个平台要有一条独立声明 —— 一条笼统的声明回答不了 'weibo 归谁'",
            )
        } finally {
            bootstrap.shutdown()
        }
    }

    @Test
    fun `不认领的链接返回 null 而不是空列表`() {
        val bootstrap = bootstrapWithParser()
        try {
            val parser = bootstrap.host.extensions
                .registrations(ExtensionPoints.LINK_PARSER)
                .first().instance

            // 空列表会被误读成"解析成功但没东西"，null 才是"不是我的链接"
            assertNull(parser.parse("https://example.com/whatever"))
            assertNull(parser.parse(""))
            assertNull(parser.parse("   "))
        } finally {
            bootstrap.shutdown()
        }
    }

    @Test
    fun `认领但平台不可达时返回 null 而不是抛异常`() {
        val bootstrap = bootstrapWithParser()
        try {
            val parser = bootstrap.host.extensions
                .registrations(ExtensionPoints.LINK_PARSER)
                .first().instance

            // 这些域名指向真实站点，但测试环境可能没网 —— 无论哪种情况都必须是 null，
            // 不能把网络异常抛给路由器（那会让整条解析链路炸掉）
            val result = parser.parse("https://m.weibo.cn/detail/4900000000000000")
            assertTrue(result == null || result.isEmpty(), "不可达时应返回 null/空，实际 $result")
        } finally {
            bootstrap.shutdown()
        }
    }

    @Test
    fun `HLS 插件与解析器插件能共存`() {
        val bootstrap = bootstrapWithParser()
        try {
            val backends = bootstrap.host.extensions
                .registrations(ExtensionPoints.DOWNLOAD_BACKEND)
                .map { it.ownerPluginId }
            assertTrue("backend.hls" in backends, "HLS 后端应仍在，实际 $backends")

            val parsers = bootstrap.host.extensions.registrations(ExtensionPoints.LINK_PARSER)
            assertEquals(1, parsers.size, "两个插件互不干扰")
        } finally {
            bootstrap.shutdown()
        }
    }

    @Test
    fun `卸载插件后扩展点清空`() {
        val bootstrap = bootstrapWithParser()
        try {
            assertNotNull(
                bootstrap.host.extensions.registrations(ExtensionPoints.LINK_PARSER).firstOrNull(),
                "装载后应有一条",
            )
            bootstrap.host.uninstall("parser.platforms")
            assertEquals(
                0,
                bootstrap.host.extensions.registrations(ExtensionPoints.LINK_PARSER).size,
                "卸载后不该留下注册（否则重启加载会累积重复）",
            )
        } finally {
            bootstrap.shutdown()
        }
    }
}

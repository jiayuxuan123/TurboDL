package dev.turbodl.plugin.parser

import dev.turbodl.plugin.parser.pixiv.PixivParser
import dev.turbodl.plugin.parser.weibo.WeiboParser
import dev.turbodl.plugin.parser.xhs.XhsParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 各平台的**链接识别**（纯字符串，不联网）。
 *
 * 为什么这一层值得细测：识别错了会有两种静默故障 ——
 * 该认的没认（表现为"这个链接解析不了"），以及**不该认的认了**
 * （表现为"粘了个别的链接，App 却去请求了微博，然后报错"）。
 * 后者更糟：用户看不出原因。
 */
class PlatformLinkTest {

    // ------------------------------------------------------------ 微博

    @Test
    fun `微博 PC 站链接`() {
        assertEquals("AbCdEfGhIj", WeiboParser.extractId("https://weibo.com/1234567890/AbCdEfGhIj"))
    }

    @Test
    fun `微博链接后面跟中文也能识别`() {
        // 分享文案最常见的形态：链接后面直接接中文，中间没有空格
        assertEquals(
            "AbCdEfGhIj",
            WeiboParser.extractId("https://weibo.com/1234567890/AbCdEfGhIj看看这个"),
        )
    }

    @Test
    fun `微博移动站 detail`() {
        assertEquals("4900000000000000", WeiboParser.extractId("https://m.weibo.cn/detail/4900000000000000"))
    }

    @Test
    fun `微博移动站 status`() {
        assertEquals("4900000000000000", WeiboParser.extractId("https://m.weibo.cn/status/4900000000000000"))
    }

    @Test
    fun `微博分享文案里裹着长数字`() {
        val text = "分享一条微博 https://m.weibo.cn/x/4900000000000001 快来看"
        assertEquals("4900000000000001", WeiboParser.extractId(text))
    }

    @Test
    fun `非微博链接不被认领`() {
        assertFalse(WeiboParser.matches("https://example.com/post/1"))
        assertFalse(WeiboParser.matches("https://www.bilibili.com/video/BV1xx"))
        assertNull(WeiboParser.extractId(""))
    }

    @Test
    fun `微博正文去 HTML 标签与实体`() {
        assertEquals("a b", WeiboParser.stripHtml("<span>a</span>&nbsp;b"))
        assertEquals("A & B", WeiboParser.stripHtml("A &amp; B"))
        assertEquals("", WeiboParser.stripHtml(""))
    }

    @Test
    fun `微博缩略图换成原图`() {
        assertEquals(
            "https://wx1.sinaimg.cn/large/abc.jpg",
            WeiboParser.upgradeImage("https://wx1.sinaimg.cn/orj360/abc.jpg"),
        )
        assertEquals(
            "https://wx1.sinaimg.cn/large/abc.jpg",
            WeiboParser.upgradeImage("https://wx1.sinaimg.cn/thumb150/abc.jpg"),
        )
        // 已经是 large 的不动
        assertEquals(
            "https://wx1.sinaimg.cn/large/abc.jpg",
            WeiboParser.upgradeImage("https://wx1.sinaimg.cn/large/abc.jpg"),
        )
    }

    // ------------------------------------------------------------ 小红书

    @Test
    fun `小红书 explore 链接`() {
        assertTrue(XhsParser.matches("https://www.xiaohongshu.com/explore/65f0000000000000000000aa"))
    }

    @Test
    fun `小红书 discovery 链接`() {
        assertTrue(XhsParser.matches("https://www.xiaohongshu.com/discovery/item/65f0000000000000000000aa"))
    }

    @Test
    fun `小红书短链`() {
        assertTrue(XhsParser.matches("http://xhslink.com/aBcDeF"))
        assertTrue(XhsParser.matches("http://xhslink.cn/aBcDeF"))
    }

    @Test
    fun `小红书链接带查询串也能认`() {
        assertTrue(
            XhsParser.matches(
                "https://www.xiaohongshu.com/explore/65f0000000000000000000aa?xsec_token=ABC&xsec_source=pc_feed",
            ),
        )
    }

    @Test
    fun `非小红书链接不被认领`() {
        assertFalse(XhsParser.matches("https://example.com/x"))
        // 24 位十六进制但是别的站点
        assertFalse(XhsParser.matches("https://example.com/explore/65f0000000000000000000aa"))
    }

    @Test
    fun `小红书页面里没有状态时不崩`() {
        assertNull(XhsParser.readInitialState("<html>请登录</html>"))
        assertNull(XhsParser.readInitialState(""))
    }

    @Test
    fun `小红书状态里的 undefined 被当成 null`() {
        val html = """<script>window.__INITIAL_STATE__={"a":undefined,"b":1}</script>"""
        val state = assertNotNull(XhsParser.readInitialState(html))
        assertNull(state["a"])
        assertEquals(1L, state["b"])
    }

    // ------------------------------------------------------------ Pixiv

    @Test
    fun `Pixiv artworks 链接`() {
        assertEquals("123456789", PixivParser.extractId("https://www.pixiv.net/artworks/123456789"))
    }

    @Test
    fun `Pixiv illust_id 参数`() {
        assertEquals(
            "123456789",
            PixivParser.extractId("https://www.pixiv.net/member_illust.php?illust_id=123456789"),
        )
    }

    @Test
    fun `Pixiv 原图直链也能提取 id`() {
        assertEquals(
            "123456789",
            PixivParser.extractId(
                "https://i.pximg.net/img-original/img/2026/01/01/00/00/00/123456789_p0.png",
            ),
        )
    }

    @Test
    fun `非 Pixiv 链接不被认领`() {
        assertFalse(PixivParser.matches("https://example.com/x"))
        // 短数字不该被当成作品 id
        assertFalse(PixivParser.matches("https://example.com/p/12345"))
    }
}

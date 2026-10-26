package dev.turbodl.plugin.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 文件名清洗与媒体→请求的转换。
 *
 * 这些看着"只是字符串处理"，但每条都对应一种真实故障：
 * 平台标题里带 `/` 会写进子目录、带 `..` 会逃出工作目录、
 * Android 的 MediaStore 会因为非法字符拒绝入库。
 */
class PlatformParserTest {

    @Test
    fun `文件名里的路径分隔符只留末段`() {
        // 平台标题里出现 `a/b.mp4` 时，正确结果是 `b.mp4` ——
        // 而不是把 `/` 换成 `_` 得到一个 `a_b.mp4`（那不是用户看到的名字）
        assertEquals("b.mp4", sanitizeFileName("a/b.mp4"))
        assertEquals("b.mp4", sanitizeFileName("a\\b.mp4"))
    }

    @Test
    fun `文件名里的 Windows 非法字符被替换`() {
        // 先取末段（`h/i.mp4` → `i.mp4`），剩下的非法字符逐个替换
        assertEquals("a_b_c_d_e_f_g_h_i.mp4", sanitizeFileName("a:b*c?d\"e<f>g|h_i.mp4"))
    }

    @Test
    fun `控制字符被替换`() {
        assertEquals("a_b.mp4", sanitizeFileName("a\u0001b.mp4"))
    }

    @Test
    fun `目录穿越被挡住`() {
        // `../` 里的 `/` 先被替换，再 trimStart('.') 吃掉前导点
        val cleaned = sanitizeFileName("../../etc/passwd")
        assertTrue(!cleaned.contains('/'), "清洗结果不能含分隔符：$cleaned")
        assertTrue(!cleaned.contains(".."), "清洗结果不能含 ..：$cleaned")
    }

    @Test
    fun `只有点的文件名不会变成隐藏文件或上级目录`() {
        val cleaned = sanitizeFileName("...")
        assertTrue(!cleaned.startsWith("."), "不能以点开头：$cleaned")
        assertTrue(cleaned.isNotBlank(), "不能变成空：'$cleaned'")
        assertEquals("download.bin", cleaned)
    }

    @Test
    fun `空文件名退回默认值`() {
        assertEquals("download.bin", sanitizeFileName(""))
        assertEquals("download.bin", sanitizeFileName("   "))
    }

    @Test
    fun `超长文件名被截断`() {
        val long = "字".repeat(300) + ".mp4"
        val cleaned = sanitizeFileName(long)
        assertTrue(cleaned.length <= 120, "长度应 <= 120，实际 ${cleaned.length}")
    }

    @Test
    fun `尾部路径段被取作文件名`() {
        assertEquals("c.mp4", sanitizeFileName("a/b/c.mp4"))
    }

    @Test
    fun `媒体转成请求时保留请求头与 tags`() {
        val media = ParsedMedia(
            url = "https://cdn.example/a.mp4",
            fileName = "a.mp4",
            headers = mapOf("Referer" to "https://weibo.com/"),
            tags = mapOf("quality" to "720P"),
        )
        val request = media.toDownloadRequest(java.io.File("/tmp"))
        assertEquals("https://cdn.example/a.mp4", request.url)
        assertEquals("https://weibo.com/", request.headers["Referer"])
        assertEquals("720P", request.tags["quality"])
        assertEquals("a.mp4", request.destination.name)
    }

    @Test
    fun `媒体转成请求时清洗文件名`() {
        val media = ParsedMedia(url = "https://cdn.example/x", fileName = "../evil/../../a.mp4")
        val request = media.toDownloadRequest(java.io.File("/tmp"))
        assertEquals("a.mp4", request.destination.name)
        assertEquals("/tmp", request.destination.parentFile?.path?.replace('\\', '/'))
    }

    @Test
    fun `空输入返回 null`() {
        val result = PlatformParsers.parse("   ", emptyList(), java.io.File("/tmp"))
        assertNull(result)
    }

    @Test
    fun `没有解析器认领时返回 null`() {
        val result = PlatformParsers.parse(
            "https://example.com/x",
            listOf(NoOpParser),
            java.io.File("/tmp"),
        )
        assertNull(result)
    }

    @Test
    fun `认领但解析失败时返回 null`() {
        val result = PlatformParsers.parse(
            "https://example.com/x",
            listOf(BrokenParser),
            java.io.File("/tmp"),
        )
        assertNull(result)
    }

    @Test
    fun `解析器抛异常不会让整条链路炸掉`() {
        val result = PlatformParsers.parse(
            "https://example.com/x",
            listOf(ThrowingParser),
            java.io.File("/tmp"),
        )
        assertNull(result)
    }

    @Test
    fun `认领后返回媒体`() {
        val result = PlatformParsers.parse(
            "https://example.com/x",
            listOf(OkParser),
            java.io.File("/tmp"),
        )
        assertEquals(1, result?.size)
        assertEquals("https://cdn.example/a.jpg", result?.get(0)?.url)
    }

    @Test
    fun `媒体数超过上限时被截断`() {
        val many = (1..(PlatformParsers.MAX_REQUESTS + 50)).map {
            ParsedMedia(url = "https://cdn.example/$it.jpg", fileName = "$it.jpg")
        }
        val result = PlatformParsers.parse(
            "https://example.com/many",
            listOf(ManyParser(many)),
            java.io.File("/tmp"),
        )
        assertEquals(PlatformParsers.MAX_REQUESTS, result?.size)
    }

    @Test
    fun `不认领的解析器不会被调用 parse`() {
        val spy = SpyParser(claims = false)
        PlatformParsers.parse("https://example.com/x", listOf(spy), java.io.File("/tmp"))
        assertEquals(0, spy.parseCalls, "matches 为假的解析器不该被调 parse（否则每条无关链接都在打网络）")
    }

    private object NoOpParser : PlatformParser {
        override val platform = "noop"
        override val displayName = "NoOp"
        override fun matches(raw: String) = false
        override fun parse(raw: String): ParsedPost? = null
    }

    private object BrokenParser : PlatformParser {
        override val platform = "broken"
        override val displayName = "Broken"
        override fun matches(raw: String) = true
        override fun parse(raw: String): ParsedPost? = null
    }

    private object ThrowingParser : PlatformParser {
        override val platform = "boom"
        override val displayName = "Boom"
        override fun matches(raw: String) = true
        override fun parse(raw: String): ParsedPost? = throw IllegalStateException("平台改版了")
    }

    private object OkParser : PlatformParser {
        override val platform = "ok"
        override val displayName = "Ok"
        override fun matches(raw: String) = true
        override fun parse(raw: String) = ParsedPost(
            platform = platform,
            medias = listOf(ParsedMedia("https://cdn.example/a.jpg", "a.jpg")),
        )
    }

    private class ManyParser(private val medias: List<ParsedMedia>) : PlatformParser {
        override val platform = "many"
        override val displayName = "Many"
        override fun matches(raw: String) = true
        override fun parse(raw: String) = ParsedPost(platform, medias)
    }

    private class SpyParser(private val claims: Boolean) : PlatformParser {
        var parseCalls = 0
        override val platform = "spy"
        override val displayName = "Spy"
        override fun matches(raw: String) = claims
        override fun parse(raw: String): ParsedPost? {
            parseCalls++
            return null
        }
    }
}

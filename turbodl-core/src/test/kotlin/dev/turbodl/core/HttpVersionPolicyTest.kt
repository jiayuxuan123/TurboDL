package dev.turbodl.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HTTP 版本策略的回归测试：把 [TurboConfig.forceHttp1] / [HttpVersionPolicy] 的**决策**
 * 与它实际产生的**连接行为**钉住。
 *
 * ## 为什么要分成两层
 *
 * 原来只有"配置解析"这一层（policy 字符串 → 枚举），测不出任何真实行为 ——
 * 枚举映射对了，协议偏好照样可能没落到 OkHttp 上。
 * P14 实测发现**旧注释对 h2 的归因是错的**（见 [TurboConfig.forceHttp1]），
 * 所以这里补上"决策 → OkHttp 实际允许的协议列表"这一层，
 * 让"改策略会怎样"变得可验证，而不是只能靠读注释。
 *
 * 真正的吞吐对照是联网测量（`HttpVersionComparisonTest`，opt-in）；
 * 本测试只管**确定性的机制**，因此进默认套件。
 */
class HttpVersionPolicyTest {

    @Test
    fun `default is AUTO`() {
        assertEquals(HttpVersionPolicy.AUTO, TurboConfig().effectiveHttpVersionPolicy)
    }

    @Test
    fun `legacy forceHttp1 false maps to FORCE_HTTP2 when policy is AUTO`() {
        // 旧调用方只设 forceHttp1=false（未设 policy）时，应等同要求 h2。
        val cfg = TurboConfig(forceHttp1 = false)
        assertEquals(HttpVersionPolicy.FORCE_HTTP2, cfg.effectiveHttpVersionPolicy)
    }

    @Test
    fun `legacy forceHttp1 true maps to AUTO`() {
        val cfg = TurboConfig(forceHttp1 = true)
        assertEquals(HttpVersionPolicy.AUTO, cfg.effectiveHttpVersionPolicy)
    }

    @Test
    fun `explicit policy overrides legacy field`() {
        // 显式设 policy 时以其为准，忽略 forceHttp1。
        val cfg = TurboConfig(forceHttp1 = false, httpVersionPolicy = HttpVersionPolicy.FORCE_HTTP1)
        assertEquals(HttpVersionPolicy.FORCE_HTTP1, cfg.effectiveHttpVersionPolicy)
    }

    /**
     * 决策 → 实际协议列表：默认（AUTO）下，**分片链路**必须只允许 HTTP/1.1。
     *
     * 这条是 `forceHttp1` 默认值的真正含义所在：分片走 h1 是为了让每个分片各占一条连接
     * （`SlicedConnectionCountTest` 验证了 h1 确实各建各的连接）。
     */
    @Test
    fun `AUTO keeps segment chain on HTTP1 only`() {
        val cfg = TurboConfig()
        val protocols = HttpClientFactory.segmentProtocolsFor(cfg)
        assertEquals(
            listOf(okhttp3.Protocol.HTTP_1_1), protocols,
            "AUTO 下分片链路必须只允许 h1；否则多分片会被 h2 多路复用抹平收益",
        )
    }

    /** AUTO 下**单流/探测链路**允许 h2：单流没有多连接损失，且兼容仅支持 h2 的服务器。 */
    @Test
    fun `AUTO allows h2 on the single-stream chain`() {
        val cfg = TurboConfig()
        val protocols = HttpClientFactory.streamProtocolsFor(cfg)
        assertTrue(
            protocols.contains(okhttp3.Protocol.HTTP_2),
            "AUTO 下探测/整文件单流链路应允许 h2（兼容仅 h2 的服务器），实际 $protocols",
        )
        assertEquals(okhttp3.Protocol.HTTP_2, protocols.first(), "应优先尝试 h2")
    }

    @Test
    fun `FORCE_HTTP1 removes h2 from both chains`() {
        val cfg = TurboConfig(httpVersionPolicy = HttpVersionPolicy.FORCE_HTTP1)
        assertEquals(listOf(okhttp3.Protocol.HTTP_1_1), HttpClientFactory.segmentProtocolsFor(cfg))
        assertEquals(
            listOf(okhttp3.Protocol.HTTP_1_1), HttpClientFactory.streamProtocolsFor(cfg),
            "FORCE_HTTP1 下连单流链路也不应允许 h2",
        )
    }

    @Test
    fun `FORCE_HTTP2 enables h2 on the segment chain`() {
        val cfg = TurboConfig(httpVersionPolicy = HttpVersionPolicy.FORCE_HTTP2)
        val protocols = HttpClientFactory.segmentProtocolsFor(cfg)
        assertTrue(
            protocols.contains(okhttp3.Protocol.HTTP_2),
            "FORCE_HTTP2 下分片链路应允许 h2（复测与对照用），实际 $protocols",
        )
    }

    /**
     * 默认值守卫：P14 的实测**不支持**改默认值（见 [TurboConfig.forceHttp1] 的记录）——
     * 那一次测量里 h2 更快，但差异可归因于单个 CDN 惩罚高并发，不能外推。
     * 若有人要改默认，应先提供多 CDN、带丢包链路的新证据。
     */
    @Test
    fun `default stays conservative until multi-CDN evidence exists`() {
        val cfg = TurboConfig()
        assertTrue(cfg.forceHttp1, "默认应保持 h1（保守）；改默认需先补多 CDN 的对照证据")
        assertFalse(
            HttpClientFactory.segmentProtocolsFor(cfg).contains(okhttp3.Protocol.HTTP_2),
            "默认下分片链路不得协商 h2",
        )
    }
}

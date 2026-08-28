package dev.turbodl.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * HTTP 版本策略解析回归测试：验证 AUTO / FORCE_* 与旧 forceHttp1 字段的兼容映射。
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
}

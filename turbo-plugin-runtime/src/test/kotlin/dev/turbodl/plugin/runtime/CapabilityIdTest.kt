package dev.turbodl.plugin.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Capability id format, versioning, and compatibility (design doc §12.1–12.4).
 *
 * The rules under test are contracts *between* plugins, so the interesting cases are the ones
 * where a wrong answer would let one plugin impersonate another or silently break a consumer.
 */
class CapabilityIdTest {

    private val legacy = setOf("http", "crypto", "log", "storage", "time", "env", "timer")

    // ---------------------------------------------- §12.1 命名空间

    @Test
    fun `接受带命名空间与版本的标识`() {
        val r = CapabilityId.parse("dev.turbodl.cap.download.engine@1.2.3")
        assertTrue(r is CapabilityId.Result.Ok)
        val p = (r as CapabilityId.Result.Ok).parsed
        assertEquals("dev.turbodl", p.namespace)
        assertEquals("download.engine", p.name)
        assertEquals("1.2.3", p.version.toString())
    }

    @Test
    fun `接受单标签命名空间`() {
        val r = CapabilityId.parse("yung.cap.storage@1.0.0")
        assertTrue(r is CapabilityId.Result.Ok)
        assertEquals("yung", (r as CapabilityId.Result.Ok).parsed.namespace)
    }

    @Test
    fun `拒绝没有命名空间的裸名`() {
        // 设计文档明确点名的三个例子
        for (bare in listOf("download.engine", "cloud.parser", "ai.provider")) {
            val r = CapabilityId.parse(bare)
            assertTrue(r is CapabilityId.Result.Invalid, "`$bare` 必须被拒（裸名没有归属，无法判断是谁的契约）")
        }
    }

    @Test
    fun `未登记的裸名不算旧格式`() {
        // 关键：只有"迁移期已知的几个名字"才当旧格式。
        // 否则随便写个新裸名就能绕过整个命名空间规则。
        val r = CapabilityId.parse("myNewThing", legacy)
        assertTrue(r is CapabilityId.Result.Invalid, "未登记的裸名不能当 legacy 放过")
    }

    @Test
    fun `已登记的裸名按旧格式读并标记`() {
        val r = CapabilityId.parse("http", legacy)
        assertTrue(r is CapabilityId.Result.Legacy, "迁移期内旧名字仍可读")
    }

    @Test
    fun `缺少 cap 中缀被拒`() {
        val r = CapabilityId.parse("dev.turbodl.download.engine@1.0.0")
        assertTrue(r is CapabilityId.Result.Invalid)
        assertTrue((r as CapabilityId.Result.Invalid).reason.contains(".cap."))
    }

    @Test
    fun `大写被拒`() {
        assertTrue(CapabilityId.parse("dev.TurboDL.cap.storage@1.0.0") is CapabilityId.Result.Invalid)
    }

    @Test
    fun `空名字与坏命名空间被拒`() {
        assertTrue(CapabilityId.parse("") is CapabilityId.Result.Invalid)
        assertTrue(CapabilityId.parse("dev..cap.x@1.0.0") is CapabilityId.Result.Invalid)
        assertTrue(CapabilityId.parse(".cap.x@1.0.0") is CapabilityId.Result.Invalid)
        assertTrue(CapabilityId.parse("dev.turbodl.cap.@1.0.0") is CapabilityId.Result.Invalid)
        assertTrue(CapabilityId.parse("9dev.cap.x@1.0.0") is CapabilityId.Result.Invalid)
    }

    // ---------------------------------------------- §12.2 语义版本

    @Test
    fun `版本必须是三段式`() {
        assertTrue(CapabilityId.parse("yung.cap.x@1.2") is CapabilityId.Result.Invalid)
        assertTrue(CapabilityId.parse("yung.cap.x@1") is CapabilityId.Result.Invalid)
        assertTrue(CapabilityId.parse("yung.cap.x@v1.2.3") is CapabilityId.Result.Invalid)
        // 预发布后缀不是合法契约版本（契约要么是某一版，要么不是）
        assertTrue(CapabilityId.parse("yung.cap.x@1.2.3-rc1") is CapabilityId.Result.Invalid)
        assertTrue(CapabilityId.parse("yung.cap.x@") is CapabilityId.Result.Invalid)
    }

    @Test
    fun `前导零的版本被拒`() {
        // "1.2.03" 与 "1.2.3" 若被当成同一个版本，两个不同契约会互相冒充
        assertTrue(CapabilityId.parse("yung.cap.x@1.2.03") is CapabilityId.Result.Invalid)
        assertTrue(CapabilityId.parse("yung.cap.x@01.2.3") is CapabilityId.Result.Invalid)
    }

    @Test
    fun `没有版本的分段标识被拒`() {
        assertTrue(CapabilityId.parse("yung.cap.storage") is CapabilityId.Result.Invalid)
    }

    // ---------------------------------------------- §12.2 兼容判断

    @Test
    fun `同 MAJOR 且版本够新才算兼容`() {
        fun ok(req: String, prov: String): Boolean {
            val a = (CapabilityId.parse(req) as CapabilityId.Result.Ok).parsed
            val b = (CapabilityId.parse(prov) as CapabilityId.Result.Ok).parsed
            return CapabilityId.compatible(a, b)
        }
        assertTrue(ok("yung.cap.x@1.2.0", "yung.cap.x@1.2.0"))
        assertTrue(ok("yung.cap.x@1.2.0", "yung.cap.x@1.3.0"), "提供方版本更高应当兼容")
        assertFalse(ok("yung.cap.x@1.3.0", "yung.cap.x@1.2.0"), "提供方版本更低不满足要求")
        assertFalse(ok("yung.cap.x@1.0.0", "yung.cap.x@2.0.0"), "MAJOR 不同一律不自动兼容")
        assertFalse(ok("yung.cap.x@1.0.0", "yung.cap.y@1.0.0"), "不同能力不兼容")
        assertFalse(ok("yung.cap.x@1.0.0", "dev.turbodl.cap.x@1.0.0"), "不同命名空间不兼容")
    }

    // ---------------------------------------------- 核心命名空间

    @Test
    fun `引擎定义的能力都合规范`() {
        for (id in TurboCapabilities.ALL) {
            val r = CapabilityId.parse(id)
            assertTrue(r is CapabilityId.Result.Ok, "引擎常量 `$id` 自己就不合规范：$r")
            assertTrue((r as CapabilityId.Result.Ok).parsed.isReserved, "引擎能力必须在保留命名空间内")
        }
    }

    @Test
    fun `保留命名空间不可被第三方占用`() {
        val third = (CapabilityId.parse("com.example.cap.thing@1.0.0") as CapabilityId.Result.Ok).parsed
        assertFalse(third.isReserved, "第三方能力不在保留命名空间")
        // SDK 保留的是**自己**的命名空间
        val reserved = (CapabilityId.parse("dev.turbodl.cap.thing@1.0.0") as CapabilityId.Result.Ok).parsed
        assertTrue(reserved.isReserved, "dev.turbodl 是 SDK 自己的命名空间")
    }

    /**
     * SDK 的保留名单里**不允许出现宿主名**。
     *
     * TurboDL 是独立开源 SDK，不是某个应用的专用内核。把宿主的名字写进 SDK 的
     * 公共保留名单，等于让 SDK 的公共面依赖那一个应用 —— 别的开发者用这个 SDK
     * 时会先读到「yung 是保留的」，那对他毫无意义。
     */
    @Test
    fun `SDK 的保留名单不含任何宿主名`() {
        assertFalse(
            CapabilityId.RESERVED_NAMESPACES.any { it == "yung" },
            "SDK 的保留命名空间里出现了宿主名（应当由宿主自己保留）：${CapabilityId.RESERVED_NAMESPACES}",
        )
        assertTrue(
            "dev.turbodl" in CapabilityId.RESERVED_NAMESPACES,
            "SDK 必须保留自己的命名空间",
        )
    }

    @Test
    fun `canonical 形式稳定`() {
        val p = (CapabilityId.parse("  dev.turbodl.cap.storage@1.0.0  ") as CapabilityId.Result.Ok).parsed
        assertEquals("dev.turbodl.cap.storage@1.0.0", p.canonical)
        assertEquals(p, CapabilityId.parse(p.canonical).let { (it as CapabilityId.Result.Ok).parsed })
    }
}

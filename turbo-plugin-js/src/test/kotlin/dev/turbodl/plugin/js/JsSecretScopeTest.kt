package dev.turbodl.plugin.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 凭据命名空间的隔离（P2）。
 *
 * 这是这一项**唯一真正要害**的地方：凭据是这套系统里最敏感的数据，
 * 而"插件只能看自己的凭据"这条验收标准，全靠这里的键名合成与剥离正确。
 *
 * 最容易写错的两点，下面各有一条测试：
 *
 *  1. 只判断 `startsWith(ownerId)` —— 插件 `a` 会读到插件 `ab` 的数据（互为前缀）；
 *  2. 分隔符用了插件 id 里可能出现的字符 —— 插件 `a.b` 的键 `c` 与插件 `a` 的键 `b.c` 撞车。
 */
class JsSecretScopeTest {

    // ---------------------------------------------- 键的合成与剥离

    @Test
    fun `键名往返一致`() {
        val scope = JsSecretScope.of("parser.example")
        val storage = scope.storageKeyFor("refresh_token")
        assertEquals("parser.example\u001frefresh_token", storage)
        assertEquals("refresh_token", scope.keyOf(storage))
    }

    @Test
    fun `不属于本命名空间返回 null`() {
        val scope = JsSecretScope.of("alpha")
        assertNull(scope.keyOf("beta\u001fkey"), "别的插件的键不该被识别")
        assertNull(scope.keyOf("alpha"), "只有 id 没有分隔符不算")
        assertNull(scope.keyOf(""), "空串不算")
    }

    @Test
    fun `互为前缀的插件 id 不会互相读到`() {
        // 关键用例：插件 alpha 与 alpha2 互为前缀。
        // 若剥离只做 startsWith(id)，alpha 会读到 alpha2 的数据。
        val alpha = JsSecretScope.of("alpha")
        val alpha2 = JsSecretScope.of("alpha2")

        val a2Storage = alpha2.storageKeyFor("token")
        assertNull(
            alpha.keyOf(a2Storage),
            "alpha 不能读到 alpha2 的键 —— 这是只判断 startsWith 会犯的错",
        )
        assertEquals("token", alpha2.keyOf(a2Storage), "alpha2 自己能正常读")
    }

    @Test
    fun `点号分隔的 id 不会撞车`() {
        // 若分隔符用 '.'，插件 "a.b" 的键 "c" 会与插件 "a" 的键 "b.c" 撞成同一个存储键。
        val a = JsSecretScope.of("a")
        val ab = JsSecretScope.of("a.b")

        val key1 = a.storageKeyFor("b.c")
        val key2 = ab.storageKeyFor("c")
        assertTrue(
            key1 != key2,
            "不同插件的不同键绝不能落到同一个存储键（用点号分隔符就会撞：$key1 vs $key2）",
        )
    }

    @Test
    fun `键名里带点号是允许的`() {
        val scope = JsSecretScope.of("parser.example")
        assertEquals("token.a", scope.keyOf(scope.storageKeyFor("token.a")))
    }

    // ---------------------------------------------- 键名校验

    @Test
    fun `空键名被拒`() {
        assertNotNull(JsSecretScope.of("p").validateKey(""), "空键名必须被拒")
    }

    @Test
    fun `超长键名被拒`() {
        val long = "k".repeat(JsSecretScope.MAX_KEY_CHARS + 1)
        assertNotNull(JsSecretScope.of("p").validateKey(long), "超长键名必须被拒")
        assertNull(
            JsSecretScope.of("p").validateKey("k".repeat(JsSecretScope.MAX_KEY_CHARS)),
            "刚好到上限应当接受",
        )
    }

    @Test
    fun `键名不能含保留分隔符`() {
        // 否则插件可以自己拼出一个越界的存储键
        val evil = "key" + JsSecretScope.SEP + "other"
        assertNotNull(
            JsSecretScope.of("p").validateKey(evil),
            "键名含保留字符必须被拒，否则插件能自己拼出跨命名空间的键",
        )
    }

    @Test
    fun `正常键名通过校验`() {
        val scope = JsSecretScope.of("p")
        for (ok in listOf("token", "refresh_token", "cookie.jar", "a-b-c", "k1")) {
            assertNull(scope.validateKey(ok), "`$ok` 是正常键名，不该被拒")
        }
    }

    // ---------------------------------------------- ownerId 校验

    @Test
    fun `ownerId 不能为空或含分隔符`() {
        assertTrue(
            runCatching { JsSecretScope.of("") }.isFailure,
            "空 ownerId 必须被拒",
        )
        assertTrue(
            runCatching { JsSecretScope.of("bad\u001fid") }.isFailure,
            "含分隔符的 ownerId 必须被拒（会让命名空间失效）",
        )
    }

    // ---------------------------------------------- 未注入后端时的行为

    @Test
    fun `未注入后端时读返回 null 写抛异常`() {
        val store = JsSecretStore.Unavailable
        val scope = JsSecretScope.of("p")

        assertFalse(store.isAvailable(), "未注入后端时应当报告不可用")
        assertNull(
            store.get(scope, "k"),
            "读返回 null（等同于「没有这个键」）—— 读失败没有别的可做",
        )
        assertTrue(
            runCatching { store.put(scope, "k", "v") }.isFailure,
            "写必须抛异常而不是静默丢弃 —— 静默丢弃会表现为「登录状态随机丢失」，极难排查",
        )
        assertFalse(store.remove(scope, "k"), "删返回 false")
        assertTrue(store.keys(scope).isEmpty(), "列举返回空")
        store.purgeOwner(scope)  // 不抛即可
    }
}

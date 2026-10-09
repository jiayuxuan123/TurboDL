package dev.turbodl.plugin.js

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [JsScriptValidator] 的行为：**只解析、不执行**。
 *
 * 这一层是给"用户自己写/粘贴脚本"用的前置检查（App 内编辑器、导入对话框、市场投稿）。
 * 它必须在**不产生副作用**的前提下回答两件事：这个脚本能不能被解析？它看起来是不是一个
 * TurboDL 插件？所以最要紧的用例是"脚本不该被执行"——如果实现里不小心真跑了脚本，
 * 下面 `doesNotExecuteTheScript` 会立刻发现。
 *
 * 引擎不可用的平台上这些用例按 JUnit assumption 跳过（与 `JsPluginLoaderTest` 同一约定），
 * 不伪装成通过。
 */
class JsScriptValidatorTest {

    private fun validate(src: String): JsScriptValidator.Report = runBlocking {
        JsScriptValidator.validate(src)
    }

    /**
     * 引擎不可用时按 JUnit assumption **跳过**（报 SKIPPED），不伪装成通过。
     * 与 `JsPluginLoaderTest` 同一约定 —— 见 `JsEngineProbe` 的说明。
     */
    private fun assumeEngine() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            JsEngineProbe.engineAvailable(),
            "QuickJS 原生库在本平台不可用，跳过",
        )
    }

    // ---------------------------------------------------------------- 解析

    @Test
    fun `accepts a well formed plugin`() {
        assumeEngine()
        val src = """
            plugin.requires({ permissions: ['http', 'crypto'] });
            plugin.defineMeta({ name: 'Example', version: '1.0.0' });
            plugin.registerParser({
              parse: function (input) {
                if (input.indexOf('example://') !== 0) return null;
                return [{ url: 'https://cdn.example/x.bin', fileName: 'x.bin' }];
              }
            });
        """.trimIndent()
        val r = validate(src)
        assertTrue(r.ok, "合法脚本应当通过：${r.syntaxError}")
        assertNull(r.syntaxError)
        // 按名字排序而不是声明顺序：同一份脚本两次校验得到同一个列表，
        // 界面与快照都稳定（实现里的 distinct().sorted()）
        assertEquals(listOf("crypto", "http"), r.declaredPermissions)
        assertEquals(listOf("parser"), r.registrationKinds)
        assertTrue(r.warnings.isEmpty(), "合法脚本不该有警告，实得 ${r.warnings}")
    }

    @Test
    fun `reports a syntax error with the engine message`() {
        assumeEngine()
        val r = validate("plugin.registerParser({ parse: function () { ")
        assertFalse(r.ok, "语法错误必须被识别")
        assertNotNull(r.syntaxError)
    }

    @Test
    fun `rejects an empty script`() {
        val r = validate("   \n  ")
        assertFalse(r.ok)
        assertTrue(r.syntaxError!!.contains("空"))
    }

    /**
     * **最要紧的一条**：校验器绝不能执行脚本。
     *
     * 脚本顶层的副作用（这里用一个死循环）如果在解析阶段跑起来，调用方就会被挂住；
     * 换成 `while(true){}` 时更糟——那说明校验器其实在"加载并运行"用户代码。
     * 这个脚本只用顶层赋值，解析器必须能编译它但一行都不执行。
     */
    @Test
    fun `does not execute the script`() {
        assumeEngine()
        // 顶层直接死循环：真执行了就会超时/中断，而"只解析"应当瞬间返回且 ok=true
        val r = validate("while (true) {}")
        assertTrue(r.ok, "只解析的话死循环语法是合法的；实得 ${r.syntaxError}")
        assertNull(r.syntaxError)
    }

    @Test
    fun `counts lines for an editor gutter`() {
        val r = validate("var a = 1;\nvar b = 2;\nvar c = 3;")
        assertEquals(3, r.lineCount)
    }

    // ---------------------------------------------------------------- 提醒项

    @Test
    fun `warns when nothing is registered`() {
        assumeEngine()
        val r = validate("var x = 1;")
        assertTrue(r.ok, "不注册任何东西不是语法错误")
        assertTrue(r.warnings.any { it.contains("register") }, "应当提醒它什么也不做：${r.warnings}")
    }

    @Test
    fun `warns when permissions are not declared`() {
        assumeEngine()
        val r = validate("plugin.registerParser({ parse: function () { return null; } });")
        assertTrue(r.ok)
        assertTrue(r.warnings.any { it.contains("requires") }, "应当提醒没声明权限：${r.warnings}")
    }

    @Test
    fun `collects every registration kind it sees`() {
        assumeEngine()
        val src = """
            plugin.requires({ permissions: ['http'] });
            plugin.registerParser({ parse: function () { return null; } });
            plugin.registerTaskPreHook({ run: function (t) { return t; } });
            plugin.onEvent(function () {});
        """.trimIndent()
        val r = validate(src)
        assertTrue(r.ok, r.syntaxError)
        assertEquals(listOf("parser", "preHook", "event"), r.registrationKinds)
    }

    @Test
    fun `declared permissions are deduplicated and sorted`() {
        assumeEngine()
        val r = validate(
            "plugin.requires({ permissions: ['time', 'http', 'time'] });\n" +
                "plugin.registerParser({ parse: function () { return null; } });"
        )
        assertEquals(listOf("http", "time"), r.declaredPermissions)
    }

    @Test
    fun `an oversized script is rejected before parsing`() {
        val huge = "// " + "x".repeat(600 * 1024)
        val r = validate(huge)
        assertFalse(r.ok)
        assertTrue(r.syntaxError!!.contains("KiB"), "应当在解析前就按体积拒绝：${r.syntaxError}")
    }
}

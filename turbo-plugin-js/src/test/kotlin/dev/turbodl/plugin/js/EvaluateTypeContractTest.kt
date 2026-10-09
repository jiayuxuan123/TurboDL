package dev.turbodl.plugin.js

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsInterruptedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 证明 YunGet 的 JsEngineProbe 之所以在真机上误报"求值失败"，是探测本身写错了类型。
 *
 * 背景：2.7.0 真机自检显示「加载原生库=通过、求值 + JSON 往返=失败、中断后实例仍可用=失败」。
 * 若引擎真的坏了，下面第一个用例就会失败。它在这里通过，说明引擎正常 ——
 * 问题出在**请求的类型**上：JS 数字是 double，而引擎的转换表没有 Double→String。
 *
 * 这些用例同时是"探测该怎么写"的活文档：一律 evaluate<Any?>，再自己判数值。
 *
 * 【两处易踩的坑，都在这份文件里踩过】
 *  1. `evaluate` 是 `suspend inline fun <reified T>`：inline 必须在调用点展开，
 *     不能塞进 `runCatching {}` 这类非 suspend lambda，也不能直接写在 try/catch 里 ——
 *     编译器会报"无法推断类型参数"。包一层普通 suspend 函数即可。
 *  2. `kotlin.test.assertTrue` 的签名是 `(actual: Boolean, message: String?)` ——
 *     **message 在后**，与 JUnit 的 `assertTrue(message, condition)` 相反。顺序写反会报
 *     "None of the following candidates is applicable"，而不是一条好懂的提示。
 */
class EvaluateTypeContractTest {

    private suspend fun evalString(q: QuickJs, code: String, file: String): String =
        q.evaluate<String>(code, file)

    private suspend fun evalAny(q: QuickJs, code: String, file: String): Any? =
        q.evaluate<Any?>(code, file)

    /** 跑一段代码并返回抛出的异常（没抛返回 null）。 */
    private suspend fun capture(block: suspend () -> Any?): Throwable? = try {
        block()
        null
    } catch (t: Throwable) {
        t
    }

    @Test
    fun `请求 String 接住 JS 数字会抛类型转换错`() = runBlocking {
        val q = QuickJs.create(Dispatchers.IO)
        try {
            val thrown = capture { evalString(q, """JSON.parse('{"a":1}').a + 1""", "t.js") }
            // 这正是真机上被误读成"引擎坏了"的那条异常
            assertNotNull(thrown, "期望类型转换失败，但求值成功了")
            assertTrue(
                thrown.message.orEmpty().contains("No such type converter"),
                "期望类型转换失败，实际=${thrown.message}",
            )
        } finally {
            q.close()
        }
    }

    @Test
    fun `请求 Any 再自己判数值则一切正常`() = runBlocking {
        val q = QuickJs.create(Dispatchers.IO)
        try {
            val v = evalAny(q, """JSON.parse('{"a":1}').a + 1""", "t.js")
            assertEquals(2.0, (v as Number).toDouble())
        } finally {
            q.close()
        }
    }

    @Test
    fun `中断确实是 QuickJsInterruptedException 而不是随便什么异常`() = runBlocking {
        val q = QuickJs.create(Dispatchers.IO)
        try {
            q.evaluationTimeoutMillis = 1_000
            val thrown = capture { evalAny(q, "while(true){}", "loop.js") }
            assertTrue(
                thrown is QuickJsInterruptedException,
                "期望 QuickJsInterruptedException，实际=$thrown",
            )
            // 中断之后实例仍可用 —— 探测里那一步的依据
            assertEquals(2.0, (evalAny(q, "1 + 1", "after.js") as Number).toDouble())
        } finally {
            q.close()
        }
    }

    @Test
    fun `内存上限触发的是带 memory 字样的错误`() = runBlocking {
        val q = QuickJs.create(Dispatchers.IO)
        try {
            q.memoryLimit = 8L * 1024 * 1024
            q.maxStackSize = 256L * 1024
            q.evaluationTimeoutMillis = 5_000
            val thrown = capture {
                evalAny(q, "var a = []; while(true) { a.push(new Array(10000).fill('x')); }", "mem.js")
            }
            assertNotNull(thrown, "无界分配没有报错")
            val msg = (thrown.message.orEmpty() + " " + thrown.javaClass.simpleName).lowercase()
            val isMemoryOrInterrupt = thrown is QuickJsInterruptedException ||
                "memory" in msg || "heap" in msg
            assertTrue(isMemoryOrInterrupt, "消息里应能认出是内存/中断，实际=${thrown.message}")
        } finally {
            q.close()
        }
    }
}

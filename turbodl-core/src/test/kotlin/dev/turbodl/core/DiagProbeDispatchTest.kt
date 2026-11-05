package dev.turbodl.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 探活的**调度器安全**测试。
 *
 * ## 为什么必须有这个测试
 *
 * 真实缺陷（用户实测）：在界面里点诊断，报
 * `NetworkOnMainThreadException: 无法连接 <链接>` ——
 * 看起来像"链接不可用"，实际是**探测请求跑在了主线程上**。
 *
 * 这类问题的可怕之处在于**它伪装成别的问题**：
 * 用户会去换链接、怀疑网盘，而真正的原因与链接无关。
 *
 * 原实现 `checkReachable` 内部是阻塞的 `call.execute()`，是否安全**取决于调用方
 * 在哪条线程上调**—— 这种"契约靠自觉"的设计迟早会被破坏（事实上已经被破坏了）。
 * 现在有了 `checkReachableAsync`（内部切 IO），调用方不必再操心上下文。
 *
 * 为不依赖外网，用**必然失败的地址**（保留测试网段）驱动探测：重点验证"不抛错、返回可读原因"。
 *
 * ## ⚠️ 本测试能证明什么、不能证明什么
 *
 * `NetworkOnMainThreadException` 是 **Android 运行时专有**的检查：
 * 桌面 JVM 上在任何线程做阻塞 IO 都不会抛它。因此本测试**无法真正复现**那条异常 ——
 * 真正的复现需要真机 instrumentation 测试。
 *
 * 它能守住的是**修复后的契约**：挂起版入口存在、不把异常抛给调用方、
 * 从"模拟主线程"（单线程调度器）调用不会自己崩、出站预检生效。
 *
 * 真正消除隐患的是 `checkReachableAsync` 内的 `withContext(Dispatchers.IO)` ——
 * 那是**结构上**的保证（调度器写死在实现里），不再依赖调用方自觉。
 */
class DiagProbeDispatchTest {

    // ---------------------------------------------------------------- 调度器安全

    @Test
    fun asyncProbeDoesNotBlockCallerThread() = runBlocking {
        // 单线程调度器模拟 Android 主线程：所有协程体都在**同一条线程**上跑。
        // 若探活仍在调用方线程做阻塞 IO，这里就是 NetworkOnMainThreadException 的等价场景。
        //
        // 注意：不能直接用 Dispatchers.Main —— 纯 JVM 测试没有 Main 实现（缺 Android 运行时），
        // 而且这里要验的是"调用方线程不被阻塞"，与 Main 的具体实现无关。
        val callerThread = Thread.currentThread().name
        val single = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "simulated-main").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        var threw: Throwable? = null
        val startedAt = System.currentTimeMillis()
        val result = try {
            withContext(single) {
                // 断言协程体确实跑在模拟主线程上（否则本测试没有意义）
                val here = Thread.currentThread().name
                assertTrue(
                    here != callerThread,
                    "协程体应在模拟主线程上执行（实际仍在 $here）",
                )
                // TEST-NET-1（RFC 5737）：保留给文档用，必定不可达 —— 但**不能崩**
                TurboDiagnostics.checkReachableAsync("https://192.0.2.1/nonexistent.bin")
            }
        } catch (t: Throwable) {
            threw = t
            null
        } finally {
            runCatching { single.close() }
        }

        assertTrue(
            threw == null,
            "挂起版探活不得把异常抛给调用方（实际抛出 ${threw?.let { it::class.simpleName }}）——" +
                " 它在 Android 主线程上就是这个样子被误报成'链接不可用'的",
        )
        assertTrue(
            result is String,
            "不可达地址应返回**可读原因文本**而不是抛异常（实际：$result）",
        )
        assertTrue(
            System.currentTimeMillis() - startedAt < 12_000,
            "不可达诊断必须有硬上限，不能把界面挂住",
        )
    }

    // ---------------------------------------------------------------- 出站预检

    @Test
    fun rejectsNonHttpScheme() = runBlocking {
        val reason = TurboDiagnostics.checkReachableAsync("ftp://example.com/file.bin")
        assertTrue(
            reason != null && reason.contains("http"),
            "非 http/https 协议必须被拒（实际：$reason）",
        )
    }

    @Test
    fun rejectsLoopbackAddress() = runBlocking {
        val reason = TurboDiagnostics.checkReachableAsync("http://127.0.0.1:8080/file.bin")
        assertTrue(
            reason != null && (reason.contains("内网") || reason.contains("本机") || reason.contains("保留")),
            "环回地址必须被拒（实际：$reason）",
        )
    }

    @Test
    fun rejectsPrivateAddress() = runBlocking {
        val reason = TurboDiagnostics.checkReachableAsync("http://192.168.1.1/file.bin")
        assertTrue(
            reason != null && (reason.contains("内网") || reason.contains("本机") || reason.contains("保留")),
            "私有地址必须被拒（实际：$reason）",
        )
    }

    @Test
    fun rejectsLinkLocalAddress() = runBlocking {
        val reason = TurboDiagnostics.checkReachableAsync("http://169.254.169.254/latest/meta-data/")
        assertTrue(
            reason != null,
            "链路本地地址（含云元数据端点）必须被拒（实际：$reason）",
        )
    }

    @Test
    fun rejectsMalformedUrl() = runBlocking {
        val reason = TurboDiagnostics.checkReachableAsync("这不是一个链接")
        assertTrue(
            reason != null,
            "非法地址应返回可读原因而不是抛异常（实际：$reason）",
        )
    }
}

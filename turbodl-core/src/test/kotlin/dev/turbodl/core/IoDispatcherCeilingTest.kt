package dev.turbodl.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * 并发天花板探针：`Dispatchers.IO.limitedParallelism(n)` 到底能真的跑满 n 吗？
 *
 * ## 为什么必须验证这件事
 *
 * 引擎里有一条关键注释：
 * ```
 * // Dispatchers.IO 的默认并行度是 max(64, CPU 核数)。分片下载是阻塞式 socket read，
 * // 一个分片占死一个线程；因此设 128 连接时实际最多只有 ~64 个能同时搜数据。
 * // 故为本任务开专用调度器，并行度 = workers + 少量余量
 * val ioDispatcher = Dispatchers.IO.limitedParallelism(workers + 4)
 * ```
 *
 * **这段推理与 `limitedParallelism` 的实际语义不符**：它不创建线程池，
 * 只是给**同一个共享池**加一层信号量视图。所以池本身仍是 64 个线程，
 * `limitedParallelism(132)` 只会把可用线程数**压到 64**，并不会把线程数提到 132。
 *
 * 这个探针直接测量两件事：`limitedParallelism(n)` 与专用线程池各自的实际峰值并发。
 * 结果决定高连接数下的吞吐是「网络到顶」还是「引擎自己的线程池到顶」。
 *
 * 默认跳过（`TURBODL_BENCH=1` 启用）：它测的是运行时行为，不是回归断言。
 */
class IoDispatcherCeilingTest {

    private companion object {
        const val TASKS = 128
        const val HOLD_MS = 200L
    }

    /** 在给定 dispatcher 上并发 N 个「阻塞 200ms」的任务，返回实际峰值并发。 */
    private fun peakConcurrency(
        n: Int,
        run: suspend (suspend (Int) -> Unit) -> Unit,
    ): Int = runBlocking {
        val peak = AtomicInteger(0)
        val cur = AtomicInteger(0)
        val body: suspend (Int) -> Unit = {
            val c = cur.incrementAndGet()
            peak.updateAndGet { maxOf(it, c) }
            Thread.sleep(HOLD_MS)
            cur.decrementAndGet()
        }
        run(body)
        peak.get()
    }

    @Test
    fun `limitedParallelism does not create threads`() {
        if (System.getenv("TURBODL_BENCH") != "1") {
            println("[IO-CEILING] 跳过（设置 TURBODL_BENCH=1 启用）")
            return
        }
        val cores = Runtime.getRuntime().availableProcessors()

        val limited = peakConcurrency(TASKS) { body ->
            withContext(Dispatchers.IO.limitedParallelism(TASKS + 4)) {
                (1..TASKS).map { async { body(it) } }.awaitAll()
            }
        }

        val pool = Executors.newFixedThreadPool(TASKS + 4) { r ->
            Thread(r, "turbodl-probe").apply { isDaemon = true }
        }
        val dedicated = peakConcurrency(TASKS) { body ->
            withContext(pool.asCoroutineDispatcher()) {
                (1..TASKS).map { async { body(it) } }.awaitAll()
            }
        }
        pool.shutdown()

        println("================================================================")
        println("[IO-CEILING] CPU 核数 = $cores")
        println("[IO-CEILING] Dispatchers.IO 默认并行度 ≈ ${maxOf(64, cores)}")
        println("[IO-CEILING] 请求并发 = $TASKS")
        println("[IO-CEILING] limitedParallelism(${TASKS + 4}) 实际峰值 = $limited")
        println("[IO-CEILING] 专用线程池(${TASKS + 4})   实际峰值 = $dedicated")
        println("================================================================")
    }
}

package dev.turbodl.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 读缓冲总预算回归测试。
 *
 * ## 事故背景（2026-10-10，真机）
 *
 * 用户报 `OutOfMemoryError`，堆已 100% 占满（`target footprint 268435456` = 256MiB），
 * 崩在 Okio 的 Watchdog 线程上 —— 看起来像网络问题，实际是缓冲把堆吃光了：
 *
 * 引擎旧实现是**每个在飞连接固定分配 `ioBufferSize`（默认 1MB）**。
 * 连接数是吞吐的关键手段（服务端按连接限速时，总吞吐 ≈ 单连接上限 × 连接数），
 * 上限 256，而 App 那边同一个版本正好把线程上限从 128 提到了 256：
 *
 * ```
 * 256 连接 × 1MB = 256MB = Android 默认整堆
 * ```
 *
 * 缓冲都是活对象（正被下载协程引用），GC 一个都回收不掉 → 连 64 字节都分不出来。
 *
 * ## 这些用例守什么
 *
 * 守的是「**总内存有上限**」这条不变量 —— 它必须对**任意配置组合**成立，
 * 包括引擎允许的最大连接数 × 最大并发任务数。这类不变量编译器不拦、单跑一遍也不报错，
 * 只有把边界值算出来才能发现，所以必须写成测试。
 *
 * 注意分母是 `maxConcurrentTasks × connections`：预算是**进程级**的，
 * 只按单任务连接数摊，多任务并行时仍会突破（5 任务 × 256 连接 = 1280 条连接）。
 */
class IoBufferBudgetTest {

    @Test
    fun `默认配置下低并发保持 1MB 不变`() {
        // App 默认：16 连接 × 1 个并发任务。32MB / 16 = 2MB，但不得超过 ioBufferSize（1MB）。
        // 这一条保证本修复**不改变日常场景的行为**（吞吐相关的历史调优结论仍然成立）。
        val appLike = TurboConfig(maxConcurrentTasks = 1)
        assertEquals(1024 * 1024, appLike.effectiveIoBufferSize(1))
        assertEquals(1024 * 1024, appLike.effectiveIoBufferSize(16))
        assertEquals(1024 * 1024, appLike.effectiveIoBufferSize(32))
    }

    @Test
    fun `事故场景：256 连接下总占用不超预算`() {
        // 这就是真机 OOM 的确切配置：单任务 256 连接（App 线程上限）。
        val config = TurboConfig(maxConnectionsPerTask = 256, maxConcurrentTasks = 1)
        val perConn = config.effectiveIoBufferSize(256)
        val total = perConn.toLong() * 256

        assertTrue(
            total <= config.ioBufferTotalBudgetBytes,
            "256 连接的总缓冲 $total 字节必须 ≤ 预算 ${config.ioBufferTotalBudgetBytes}",
        )
        // 摊下来仍有 128KB/连接 —— 读 socket 完全够用
        assertEquals(128 * 1024, perConn)
    }

    @Test
    fun `多任务并行时总占用仍不超预算`() {
        // App 允许 5 个任务同时下载、每个 256 连接 = 1280 条连接。
        // 若分母只算单任务连接数（256），1280 × 128KB = 160MB，照样 OOM。
        val config = TurboConfig(maxConnectionsPerTask = 256, maxConcurrentTasks = 5)
        val perConn = config.effectiveIoBufferSize(256)
        val total = perConn.toLong() * 256 * 5

        assertTrue(
            total <= config.ioBufferTotalBudgetBytes,
            "5 任务 × 256 连接的总缓冲 $total 必须 ≤ 预算 ${config.ioBufferTotalBudgetBytes}",
        )
    }

    @Test
    fun `任意可达配置下总占用都封顶在预算内`() {
        // 扫全部组合：连接数 1..256 × 并发任务 1..64（引擎允许的范围）
        val config = TurboConfig()
        var worst = 0L
        var worstCase = ""
        var worstAppReachable = 0L
        for (tasks in 1..64) {
            for (conns in 1..256) {
                val c = config.copy(maxConcurrentTasks = tasks, maxConnectionsPerTask = conns)
                val perConn = c.effectiveIoBufferSize(conns)
                val total = perConn.toLong() * conns * tasks
                if (total > worst) {
                    worst = total
                    worstCase = "任务=$tasks 连接=$conns 单连接=$perConn"
                }
                // App 可达范围：任务 ≤ 8（设置页上限）× 连接 ≤ 256（线程上限）。
                // 这一段是**用户真能设出来**的，必须严格不超预算。
                if (tasks <= 8 && total > worstAppReachable) worstAppReachable = total

                assertTrue(
                    perConn >= TurboConfig.MIN_IO_BUFFER_BYTES,
                    "任务=$tasks 连接=$conns 时单连接缓冲低于硬下限",
                )
                if (tasks <= 8) {
                    assertTrue(
                        total <= config.ioBufferTotalBudgetBytes,
                        "任务=$tasks 连接=$conns 时总缓冲 $total 超出预算（App 可达，必须封顶）",
                    )
                }
            }
        }

        assertTrue(
            worstAppReachable <= config.ioBufferTotalBudgetBytes,
            "App 可达的最坏总占用 $worstAppReachable 超出预算",
        )

        // 引擎级极端配置（64 任务 × 256 连接 = 16384 条连接）会触到 8KB 保底，
        // 此时总量由保底决定：16384 × 8KB = 128MB。仍**远低于** Android 默认 256MB 堆，
        // 且这种配置在 App 里设不出来（并发任务上限 8）。
        // 把它钉住是为了让"保底之上到底能到多大"始终是可知的，而不是一句"反正不会 OOM"。
        val floorWorst = TurboConfig.MIN_IO_BUFFER_BYTES.toLong() * 64 * 256
        assertTrue(
            worst <= floorWorst,
            "最坏总占用 $worst 超过保底推算的上界 $floorWorst（$worstCase）",
        )
        assertTrue(
            worst < 256L * 1024 * 1024,
            "最坏总占用 $worst 不该逼近 256MB 堆上限（$worstCase）",
        )
    }

    @Test
    fun `预算上限约束 ioBufferSize`() {
        // ioBufferSize 是**用户显式设的上限**：任何时候都不得被超过。
        val small = TurboConfig(ioBufferSize = 64 * 1024, ioBufferTotalBudgetBytes = 32 * 1024 * 1024)
        // 低并发：32MB / (2 连接 × 默认 3 任务) = 5.3MB，被 ioBufferSize 压到 64KB
        assertEquals(64 * 1024, small.effectiveIoBufferSize(2), "不得超过 ioBufferSize")
        // 高并发：32MB / (256 × 3) = 42.6KB —— 低于 ioBufferSize，取摊薄值
        assertEquals(32 * 1024 * 1024 / (256 * 3), small.effectiveIoBufferSize(256))
    }

    @Test
    fun `连接数为 0 或负数时按 1 处理，不抛异常`() {
        // 防御性：调用方传 0 不该除零，也不该拿到 0 长度缓冲（ByteArray(0) 会让读循环空转）
        val config = TurboConfig(maxConcurrentTasks = 1)
        assertEquals(1024 * 1024, config.effectiveIoBufferSize(0))
        assertEquals(1024 * 1024, config.effectiveIoBufferSize(-5))
    }

    @Test
    fun `预算小于单连接上限的配置被拒绝`() {
        // 语义混乱的组合（"预算比单个连接上限还小"）应在构造时就报错，而不是运行时困惑
        assertFailsWith<IllegalArgumentException> {
            TurboConfig(ioBufferSize = 1024 * 1024, ioBufferTotalBudgetBytes = 512 * 1024)
        }
    }

    /**
     * P13：摊薄算法是**唯一实现**，两个调用方只传各自的单连接上限。
     *
     * 背景：这段算法原先在引擎与 App 兜底引擎里各写了一份（0.2.0.8 修 OOM 时留下），
     * 逐字相同、只有上限不同（引擎 1MB / App 256KB）。收敛后两面共用
     * [TurboConfig.budgetedIoBufferSize]，本测试钉住"同一算法、不同上限"都给出正确结果 ——
     * 否则将来有人只改一处，另一次调用会静默走样（这类不变量出问题就是 OOM）。
     */
    @Test
    fun `shared budget helper honours both per-connection caps`() {
        val budget = 32 * 1024 * 1024

        // 引擎口径：上限 1MB。低并发触上限；高并发取摊薄值。
        assertEquals(
            1024 * 1024,
            TurboConfig.budgetedIoBufferSize(budget, 1024 * 1024, connections = 16, concurrentTasks = 1),
            "低并发时应取引擎的上限 1MB",
        )
        assertEquals(
            budget / (256 * 1),
            TurboConfig.budgetedIoBufferSize(budget, 1024 * 1024, connections = 256, concurrentTasks = 1),
            "256 连接时取摊薄值",
        )

        // App 兜底引擎口径：上限 256KB，同一个连接数下上限更低。
        assertEquals(
            256 * 1024,
            TurboConfig.budgetedIoBufferSize(budget, 256 * 1024, connections = 16, concurrentTasks = 1),
            "低并发时应取 App 引擎的上限 256KB",
        )
        assertEquals(
            TurboConfig.budgetedIoBufferSize(budget, 1024 * 1024, connections = 256, concurrentTasks = 5),
            TurboConfig.budgetedIoBufferSize(budget, 256 * 1024, connections = 256, concurrentTasks = 5),
            "高并发时摊薄值低于两个上限，因此两个口径的结果必须相同",
        )

        // 下限保底：荒谬配置下不低于 8KB（ByteArray(0) 会让读循环空转）。
        assertEquals(
            TurboConfig.MIN_IO_BUFFER_BYTES,
            TurboConfig.budgetedIoBufferSize(budget, 1024 * 1024, connections = 65536, concurrentTasks = 64),
            "摊薄到下限时应保底 8KB，不能返回 0",
        )
        // 非法入参按 1 处理，不除零。
        assertEquals(
            1024 * 1024,
            TurboConfig.budgetedIoBufferSize(budget, 1024 * 1024, connections = 0, concurrentTasks = 0),
        )
    }
}

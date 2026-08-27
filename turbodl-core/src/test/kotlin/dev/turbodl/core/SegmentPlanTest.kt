package dev.turbodl.core

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * 分块规划回归测试：验证「固定 N 连接必须全部有活干」。
 *
 * 背景缺陷：旧实现用固定 blockSize（1MB）预分块，小文件会出现「块数 ≪ 连接数」——
 * 20MB 文件只切 20 块，但开了 64 连接，44 个 worker 一上来 poll() 为空立即退出，
 * 实际并发远低于设定值，表现像单线程。
 *
 * 修复后：块数按 连接数 × segmentsPerConnection 反推，保证每个连接都能领到块。
 */
class SegmentPlanTest {

    /**
     * 复刻调度器的块大小推导逻辑（与 SegmentScheduler 保持一致）。
     * 单独抽出便于在不发起真实网络请求的情况下验证规划结果。
     */
    private fun planBlockCount(total: Long, workers: Int, config: TurboConfig): Int {
        val targetSegments = workers.toLong() *
            config.segmentsPerConnection.coerceIn(1, 64).toLong()
        val byConnections = if (targetSegments > 0) total / targetSegments else total
        val effBlock = byConnections.coerceIn(1L, config.blockSize)
            .coerceAtLeast(minOf(config.minSegmentSize, maxOf(1L, total / workers)))
        var count = 0
        var s = 0L
        while (s < total) {
            val e = minOf(s + effBlock - 1, total - 1)
            count++
            s = e + 1
        }
        return count
    }

    @Test
    fun `20MB with 64 connections gives every connection work`() {
        val total = 20L * 1024 * 1024
        val workers = 64
        val blocks = planBlockCount(total, workers, TurboConfig(maxConnectionsPerTask = workers))
        // 关键断言：块数必须 >= 连接数，否则部分连接空转退出（旧实现此处为 20 < 64）
        assertTrue(
            blocks >= workers,
            "20MB/64 连接应至少切出 64 块以喂满所有连接，实际 $blocks"
        )
    }

    @Test
    fun `256 connections on a small file still saturates`() {
        val total = 8L * 1024 * 1024   // 8MB
        val workers = 256
        val blocks = planBlockCount(total, workers, TurboConfig(maxConnectionsPerTask = workers))
        assertTrue(
            blocks >= workers,
            "8MB/256 连接应至少切出 256 块，实际 $blocks"
        )
    }

    @Test
    fun `large file caps block size at blockSize`() {
        val total = 100L * 1024 * 1024 * 1024   // 100GB
        val workers = 8
        val cfg = TurboConfig(maxConnectionsPerTask = workers)
        val blocks = planBlockCount(total, workers, cfg)
        // 单块不得超过 blockSize，因此块数应远多于连接数
        assertTrue(blocks > workers, "大文件块数应远多于连接数，实际 $blocks")
        val impliedBlock = total / blocks
        assertTrue(
            impliedBlock <= cfg.blockSize,
            "单块大小不得超过 blockSize=${cfg.blockSize}，实际约 $impliedBlock"
        )
    }

    @Test
    fun `tiny file does not explode into absurd fragments`() {
        val total = 300L * 1024   // 300KB，小于 minSegmentSize*连接数
        val workers = 64
        val blocks = planBlockCount(total, workers, TurboConfig(maxConnectionsPerTask = workers))
        // 小文件允许块数少于连接数（切不开），但不能碎成上千片
        assertTrue(blocks in 1..workers * 2, "小文件块数应受控，实际 $blocks")
    }

    @Test
    fun `segmentsPerConnection scales block count`() {
        val total = 64L * 1024 * 1024
        val workers = 16
        val few = planBlockCount(total, workers, TurboConfig(maxConnectionsPerTask = workers, segmentsPerConnection = 1))
        val many = planBlockCount(total, workers, TurboConfig(maxConnectionsPerTask = workers, segmentsPerConnection = 8))
        assertTrue(many > few, "segmentsPerConnection 增大应切出更多块（$many vs $few）")
        assertEquals(true, few >= workers, "即使 segmentsPerConnection=1 也应至少每连接一块")
    }
}

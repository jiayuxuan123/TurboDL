package dev.turbodl.core

import kotlinx.coroutines.delay

/**
 * 全局速度限制器（令牌桶）。
 *
 * 所有任务共享一个实例，合计吞吐不超过 [TurboConfig.globalSpeedLimitBytesPerSec]。
 * 0 = 不限速（[awaitAllow] 立即返回）。
 *
 * 参考 aria2 `--max-overall-download-limit` / Motrix 的全局限速思想。
 */
internal class SpeedLimiter(private val limitProvider: () -> Long) {

    @Volatile private var tokens = 0.0
    @Volatile private var lastRefillNanos = 0L

    private fun refill(limit: Long) {
        synchronized(this) {
            val now = System.nanoTime()
            if (lastRefillNanos == 0L) {
                // 惰性初始化：首次调用才开始计时，避免构造到首次下载之间累积成巨大突发额度
                lastRefillNanos = now
                return
            }
            val elapsedSec = (now - lastRefillNanos).coerceAtLeast(0) / 1_000_000_000.0
            lastRefillNanos = now
            tokens = minOf(limit.toDouble(), tokens + elapsedSec * limit)
        }
    }

    /** 消费 [bytes] 字节额度；额度不足时挂起等待。 */
    suspend fun awaitAllow(bytes: Long) {
        val limit = limitProvider().coerceAtLeast(0)
        if (limit <= 0) return
        while (true) {
            val waitMs = synchronized(this) {
                refill(limit)
                if (bytes <= tokens) {
                    tokens -= bytes
                    return
                }
                ((bytes - tokens) * 1000.0 / limit).toLong().coerceIn(1, 200)
            }
            delay(waitMs)
        }
    }
}

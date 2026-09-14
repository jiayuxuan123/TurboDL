package dev.turbodl.core

import kotlinx.coroutines.delay

/**
 * 全局速度限制器（令牌桶）。
 *
 * 所有任务共享一个实例，合计吞吐不超过 [TurboConfig.globalSpeedLimitBytesPerSec]。
 * 0 = 不限速（[awaitAllow] 立即返回）。
 *
 * 参考 aria2 `--max-overall-download-limit` / Motrix 的全局限速思想。
 *
 * ## 曾经的缺陷（P0-5，已修）
 *
 * 旧实现在算等待时间时做了 `coerceIn(1, 200)`，把等待**截断到 200ms 上限**，
 * 然后靠 `while(true)` 反复醒来重试。后果：
 *
 * ```
 * limit = 100KB/s, 单次 bytes = 1MB
 * 真实需要等待 ≈ (1048576 - tokens) * 1000 / 102400 ≈ 10240ms
 * 被截成 200ms → 循环 51 次，每次都做一次 synchronized + delay
 * ```
 *
 * 在 64 分片并发时，64 个协程都在抢同一把锁、反复唤醒 → **接近忙等**，
 * 限速场景下的 CPU 几乎全花在锁竞争与协程调度上，而不是下载。
 *
 * 修正：**该等多久就等多久**，不做小步轮询。
 *
 * ## 关于 [MAX_WAIT_MS]
 *
 * 保留一个远大于 200ms 的单次等待上限，目的**不是轮询**，而是让「用户在下载过程中
 * 修改限速值」能在数秒内被感知（睡死前先醒来重读一次配置）。
 * 触发该上限后循环会重新 `limitProvider()`，因此配置变更即时生效。
 */
internal class SpeedLimiter(private val limitProvider: () -> Long) {

    private val lock = Any()

    private var tokens = 0.0
    private var lastRefillNanos = 0L

    companion object {
        /**
         * 单次等待上限。仅用于让「配置变更」被及时感知，不是为了轮询。
         * 取值远大于旧实现的 200ms —— 那是缺陷的根源。
         */
        private const val MAX_WAIT_MS = 5_000L
    }

    private fun refill(limit: Long) {
        val now = System.nanoTime()
        if (lastRefillNanos == 0L) {
            // 惰性初始化：首次调用即把桶填满（允许一个突发），
            // 否则首块数据会因为 tokens=0 而白等一整个周期。
            lastRefillNanos = now
            tokens = limit.toDouble()
            return
        }
        val elapsedSec = (now - lastRefillNanos).coerceAtLeast(0) / 1_000_000_000.0
        lastRefillNanos = now
        // 桶容量 = 1 秒的额度（经典令牌桶：允许最多 1 秒的突发）
        tokens = minOf(limit.toDouble(), tokens + elapsedSec * limit)
    }

    /** 消费 [bytes] 字节额度；额度不足时挂起等待。 */
    suspend fun awaitAllow(bytes: Long) {
        if (bytes <= 0) return
        val limit = limitProvider().coerceAtLeast(0)
        if (limit <= 0) return   // 不限速：立即返回，不碰锁
        while (true) {
            val waitMs = synchronized(lock) {
                refill(limit)
                if (bytes <= tokens) {
                    tokens -= bytes
                    return
                }
                // 精确算出还要多久才能攒够差额；**不截断**，只设一个宽松上限。
                val deficit = bytes - tokens
                (deficit * 1000.0 / limit).toLong().coerceAtLeast(1L).coerceAtMost(MAX_WAIT_MS)
            }
            delay(waitMs)
        }
    }

    /**
     * 有界等待版本：在 [maxWaitMs] 内尽量消费额度，超时则**放行**。
     *
     * 用于「限速不应把任务卡死」的场景（例如任务即将结束、剩余尾块很小）。
     * 与 [awaitAllow] 的区别是它保证不会无限挂起。
     */
    suspend fun awaitAllowBounded(bytes: Long, maxWaitMs: Long) {
        if (bytes <= 0 || maxWaitMs <= 0) return
        val deadline = System.nanoTime() + maxWaitMs * 1_000_000L
        while (true) {
            val limit = limitProvider().coerceAtLeast(0)
            if (limit <= 0) return
            val waitMs = synchronized(lock) {
                refill(limit)
                if (bytes <= tokens) {
                    tokens -= bytes
                    return
                }
                val deficit = bytes - tokens
                (deficit * 1000.0 / limit).toLong().coerceAtLeast(1L).coerceAtMost(MAX_WAIT_MS)
            }
            val remainMs = (deadline - System.nanoTime()) / 1_000_000L
            if (remainMs <= 0) return   // 超时放行：宁可略超限速，也不要把任务卡死
            delay(minOf(waitMs, remainMs))
        }
    }
}

package dev.turbodl.core

import kotlin.math.max
import kotlin.math.min

/**
 * 在不越过用户设定值的前提下，寻找「够用的最低并发档」并持有它。
 *
 * ## 为什么只往下找腿（而不是接管爬升）
 *
 * 用户设定值是**上限**，不是目标：实测回环 16 连接 28.3 MB/s、128 连接只有 6.2 MB/s，
 * 而真实链路上也存在「加连接不再变快」的聚合限速。慢启动负责**爬到**设定值（保留原有快速爬升），
 * 本控制器负责在此之后判断「多出来的连接到底有没有换来吞吐」。
 *
 * 这样分工的一个直接好处：**不会拿单连接速度当降档理由**。
 * 服务端按每连接独立限速时（例如每连接 12KB/s），单连接速度本来就很低，
 * 但总吞吐 = 单连接 × 连接数 —— 此时降档会被本控制器拒绝（吞吐掉得太多），档位保持在设定值。
 *
 * ## 判定规则
 *
 * - **基线**：爬到上限后先测 [requiredSamples] 个窗口，得到该档位的总吞吐。
 * - **下探**：探到当前档位的一半；总吞吐保留基线的 [KEEP_RATIO]（90%）以上 → 接受并继续下探；
 *   掉得更多 → 拒绝、恢复原档并进入持有期。
 * - **恢复**：持有期结束后重新测基线再探（网络条件会变），所以不会永久锁死在某一档。
 * - 反向（向上）只在「当前档位低于上限」时才探，且要求吞吐至少提升 [GAIN_RATIO]（10%）。
 *
 * ## 与背压的关系
 *
 * 429/503 与连续失败触发的背压**优先**：调度器调用 [resetTo] 把档位与上限一起降到背压后的值，
 * 并清除基线。本控制器随后从那个更低的档位重新学习，不会立刻爬回被拒的档。
 *
 * ## 已知边界
 *
 * - 多任务并行时总吞吐会受其它任务挤占，窗口可能既不属于本档也不属于上一档。
 *   因此要求连续多个窗口取中位数、且只接受「明确更好/明确更差」的档位，宁可不动。
 * - 若用户在配置里启用了全局限速，吞吐被自己封顶，此时**不应**学习（调度器直接不创建本控制器）。
 */
internal class AdaptiveConcurrencyController(
    private val maximumConcurrency: Int,
    initialConcurrency: Int = maximumConcurrency,
    probeDownFirst: Boolean = true,
    /**
     * 下探的下限（默认 `min(上限, 4)`）。
     *
     * 【为什么不能探到 1】连接数并不只是带宽倍率，它还是**应对连接速度不均的余量**：
     * `SkewSweepTest` 实测「8 条连接里 1 条慢 10 倍」时，多块（32 块）比少块（8 块）快 3.78 倍，
     * 而工作窃取要靠「块数 > 连接数」才有东西可偷。探到 1 连接等于把这份余量扔掉，
     * 真实 CDN 恰恰是不均的 —— 服务端总带宽封顶时，多一点连接不花什么钱，
     * 却能在某个节点变慢时顶上。取 4 与慢启动的初始下限保持同一口径。
     */
    private val minimumConcurrency: Int = min(maximumConcurrency, 4),
    private val requiredSamples: Int = 2,
    private val holdWindowsAfterReject: Int = 16,
    private val holdWindowsAfterAccept: Int = 4,
) {
    /** 控制器意图。scheduler 负责真正落 desired 与 ceiling。 */
    data class Decision(
        /** 要设置的并发目标（null = 本窗口不改）。 */
        val target: Int? = null,
        /** 建议的并发上限（null = 不改）；仅在「接受更低档」时收紧。 */
        val suggestedCeiling: Int? = null,
        /** 是否处于下探/上探中（此时调度器必须暂停旧的比例爬升，避免互相打架）。 */
        val probing: Boolean = false,
    )

    private enum class Phase { WAIT_RAMP, BASELINE, PROBE, HOLD }
    private enum class Direction { DOWN, UP }

    private var phase = Phase.WAIT_RAMP
    private var direction = if (probeDownFirst) Direction.DOWN else Direction.UP
    private var level = initialConcurrency.coerceIn(1, maximumConcurrency)
    private var baseline = 0.0
    private var probeLevel = 0
    private val samples = ArrayList<Double>()
    private var hold = 0

    /**
     * 最近一次被拒的档位。
     *
     * 【为什么需要】下探失败的档位在几秒后可能被反复重探，每次都让并发短暂减半 ——
     * 用户会看到周期性的掉速。记住它，下次直接换个方向，避免重复付这份代价。
     * 网络条件仍可能变化（例如从聚合限速换成每连接限速），所以它只影响**紧接着**那一轮，
     * 每次 HOLD 结束重新测基线时清空。
     */
    private var rejectedLevel = 0

    init {
        require(maximumConcurrency >= 1)
        require(requiredSamples > 0 && holdWindowsAfterReject > 0 && holdWindowsAfterAccept >= 0)
    }

    /** 当前认为合适的档位（等于「已接受的并发上限」）。 */
    val acceptedLevel: Int get() = level

    fun isProbing(): Boolean = phase == Phase.PROBE

    /**
     * 背压（429/503 / 连续失败）优先：档位与上限一并降到背压后的值，并清除旧基线。
     * 之后从该档位重新学习，不会立刻爬回被拒的档。
     */
    fun resetTo(concurrency: Int) {
        level = concurrency.coerceIn(1, maximumConcurrency)
        phase = Phase.WAIT_RAMP
        direction = Direction.DOWN
        baseline = 0.0
        probeLevel = 0
        samples.clear()
        hold = 0
        rejectedLevel = 0
    }

    /**
     * 喂入一个**合格**窗口的总吞吐。
     *
     * @param throughput 本窗口实际写入字节 / 窗口时长（字节/秒）
     * @param rampDone 旧爬升是否已经到达上限（未到顶时不学习，避免与爬升抢方向）
     * @param concurrencyLimit 当前允许的上限（含背压与已接受档位）
     */
    fun observe(throughput: Double, rampDone: Boolean, concurrencyLimit: Int): Decision {
        if (!throughput.isFinite() || throughput <= 0.0) {
            samples.clear()
            return Decision(probing = phase == Phase.PROBE)
        }
        val cap = concurrencyLimit.coerceIn(1, maximumConcurrency)
        if (level > cap) {
            // 上限被压低（背压或服务端限制）：立即对齐，重新学习。
            level = cap
            phase = Phase.WAIT_RAMP
            baseline = 0.0
            probeLevel = 0
            samples.clear()
            hold = 0
            return Decision(target = cap, suggestedCeiling = cap)
        }

        when (phase) {
            Phase.WAIT_RAMP -> {
                if (!rampDone) return Decision()
                phase = Phase.BASELINE
                samples.clear()
                baseline = 0.0
                return Decision()
            }

            Phase.BASELINE -> {
                samples += throughput
                if (samples.size < requiredSamples) return Decision()
                baseline = median(samples)
                samples.clear()
                return beginProbe(cap)
            }

            Phase.PROBE -> {
                samples += throughput
                if (samples.size < requiredSamples) return Decision()
                val measured = median(samples)
                samples.clear()
                val worthwhile = when (direction) {
                    Direction.DOWN -> measured >= baseline * KEEP_RATIO
                    Direction.UP -> measured >= baseline * GAIN_RATIO
                }
                if (!worthwhile) {
                    // 该方向没有收益：回到已接受的档位，持有若干窗口后再重新测量。
                    rejectedLevel = probeLevel
                    probeLevel = 0
                    phase = Phase.HOLD
                    hold = holdWindowsAfterReject
                    return Decision(target = level, suggestedCeiling = level)
                }
                level = probeLevel
                rejectedLevel = 0
                baseline = measured
                return beginProbe(cap)
            }

            Phase.HOLD -> {
                if (hold > 0) {
                    hold--
                    return Decision()
                }
                // 网络条件会变：重新测一轮基线再决定，避免永久停在某一档。
                phase = Phase.BASELINE
                samples.clear()
                baseline = 0.0
                rejectedLevel = 0
                return Decision()
            }
        }
    }

    private fun beginProbe(cap: Int): Decision {
        // 方向失效时翻转：到顶不能再上探，触底不能再下探。
        if (direction == Direction.UP && level >= cap) direction = Direction.DOWN
        if (direction == Direction.DOWN && level <= minimumConcurrency) direction = Direction.UP

        var next = when (direction) {
            Direction.DOWN -> if (level <= minimumConcurrency) 0 else max(minimumConcurrency, level / 2)
            Direction.UP -> if (level >= cap) 0 else min(cap, level * 2)
        }
        // 刚被拒的档位不重探：否则会周期性掉速（见 rejectedLevel 的说明）。
        if (next == rejectedLevel && direction == Direction.DOWN) {
            val lower = if (next <= minimumConcurrency) 0 else max(minimumConcurrency, next / 2)
            next = lower
        }
        if (next <= 0 || next == level) {
            // 没有可探的方向：停在当前档位。
            phase = Phase.HOLD
            hold = holdWindowsAfterAccept
            probeLevel = 0
            return Decision()
        }
        probeLevel = next
        phase = Phase.PROBE
        samples.clear()
        // 【只在下探时收紧上限】上探时若也收紧到当前档位，调度器会把 target 夹回上限，
        // 上探永远无法生效（实测：并发卡在原档不涨）。
        val ceilingHint = if (direction == Direction.DOWN) level else null
        return Decision(target = next, suggestedCeiling = ceilingHint, probing = true)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private companion object {
        /** 下探时至少要保住基线的这个比例，才认为「少一半连接没有损失」。 */
        const val KEEP_RATIO = 0.90

        /** 上探时总吞吐至少要提升这个比例，才认为「多一倍连接确实有用」。 */
        const val GAIN_RATIO = 1.10
    }
}

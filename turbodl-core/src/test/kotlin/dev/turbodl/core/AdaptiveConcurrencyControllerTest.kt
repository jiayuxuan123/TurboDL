package dev.turbodl.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 并发收敛控制器的纯策略测试：不联网、不起服务器，只验证「总吞吐 → 档位决策」。
 *
 * 控制器的分工：慢启动负责爬到用户设定值，它只负责在那之后寻找「够用的最低档」。
 * 因此所有用例都以 [rampDone] = true 喂入（爬升已完成），这与真实调用场景一致。
 *
 * 两种必须区分开的服务端行为：
 *  - 每连接独立限速 12KB/s → 总吞吐随并发提升，下探必须被拒（否则白丢吞吐）；
 *  - 聚合限速/封顶 → 加连接没有收益，下探应被接受，档位降下来。
 */
class AdaptiveConcurrencyControllerTest {

    /** 控制器需要连续 N 个合格窗口才形成判断；本助手喂满一轮并返回该轮决策。 */
    private fun feed(
        c: AdaptiveConcurrencyController,
        throughput: Double,
        limit: Int = 64,
        windows: Int = 2,
    ): AdaptiveConcurrencyController.Decision {
        var last = AdaptiveConcurrencyController.Decision()
        repeat(windows) { last = c.observe(throughput, rampDone = true, concurrencyLimit = limit) }
        return last
    }

    /**
     * 推进到「已测出基线、并给出第一个下探候选」。
     *
     * 注意阶段推进需要的窗口数：第 1 个窗口只是从 WAIT_RAMP 进入 BASELINE，
     * 再用 [requiredSamples] 个窗口攒够基线，之后才给出候选。
     */
    private fun startProbing(c: AdaptiveConcurrencyController, throughput: Double, limit: Int = 64) =
        feed(c, throughput, limit, windows = 3)

    @Test
    fun `per-connection cap rejects descent so throughput is not thrown away`() {
        // 每连接 12KB/s：32 连接 ≈ 384KB/s，16 连接只有约 192KB/s（掉一半）
        val c = AdaptiveConcurrencyController(maximumConcurrency = 32)
        val probe = startProbing(c, 384_000.0)
        assertEquals(16, probe.target, "爬满后应先下探一半并发")

        val rejected = feed(c, 192_000.0)
        assertEquals(32, rejected.target, "降档让吞吐腰斩时必须恢复原档")
        assertEquals(32, c.acceptedLevel, "被拒后档位应保持在设定值")
    }
    @Test
    fun `aggregate cap accepts descent and tightens ceiling`() {
        // 聚合封顶 2MB/s：32 连接与 16 连接吞吐一样
        val c = AdaptiveConcurrencyController(maximumConcurrency = 32)
        startProbing(c, 2_000_000.0)

        val accepted = feed(c, 2_000_000.0)
        assertEquals(16, c.acceptedLevel, "少一半连接没有损失，应接受更低档")
        assertEquals(16, accepted.suggestedCeiling, "接受更低档后应把上限收到该档")
        assertEquals(8, accepted.target, "接受 16 之后应继续向下寻找更省的档位")
    }

    @Test
    fun `does not learn until ramp finished`() {
        val c = AdaptiveConcurrencyController(maximumConcurrency = 32)
        val d = c.observe(500_000.0, rampDone = false, concurrencyLimit = 64)
        assertNull(d.target, "爬升未完成时不应给出任何档位决策")
        assertEquals(32, c.acceptedLevel)
    }

    @Test
    fun `rejected probe is not retried immediately`() {
        // 拒绝后进入持有期：紧接着的若干窗口不应再次下探
        val c = AdaptiveConcurrencyController(maximumConcurrency = 32, holdWindowsAfterReject = 12)
        startProbing(c, 3_200_000.0)
        val rejected = feed(c, 1_000_000.0)
        assertEquals(32, rejected.target, "吞吐腰斩时应恢复原档")

        var probedAgain = false
        repeat(6) {
            val d = feed(c, 3_200_000.0)
            if (d.target != null && d.target!! < 32) probedAgain = true
        }
        assertTrue(!probedAgain, "持有期内不得反复下探（那会造成周期性掉速）")
    }

    @Test
    fun `after hold it measures again so it is not locked forever`() {
        val c = AdaptiveConcurrencyController(
            maximumConcurrency = 32,
            holdWindowsAfterReject = 2,
        )
        startProbing(c, 3_200_000.0)
        feed(c, 1_000_000.0)   // 拒绝

        // 走完持有期 + 重新测基线，应能再次给出候选（网络可能已经变了）
        var probedAgain = false
        repeat(12) {
            val d = feed(c, 3_200_000.0)
            if (d.target != null) probedAgain = true
        }
        assertTrue(probedAgain, "持有期结束后必须重新测量，不能永久停在某一档")
    }

    @Test
    fun `backpressure reset aligns level and restarts learning`() {
        val c = AdaptiveConcurrencyController(maximumConcurrency = 64)
        startProbing(c, 5_000_000.0)
        c.resetTo(8)
        assertEquals(8, c.acceptedLevel, "背压后档位应重置为背压值")

        val d = c.observe(800_000.0, rampDone = true, concurrencyLimit = 8)
        assertTrue(d.target == null || d.target!! <= 8, "不得越过背压上限，实际 ${d.target}")
    }

    @Test
    fun `zero and non-finite throughput never steer decisions`() {
        val c = AdaptiveConcurrencyController(maximumConcurrency = 32)
        assertNull(c.observe(Double.NaN, rampDone = true, concurrencyLimit = 64).target)
        assertNull(c.observe(0.0, rampDone = true, concurrencyLimit = 64).target)
        assertNull(c.observe(-1.0, rampDone = true, concurrencyLimit = 64).target)
        assertEquals(32, c.acceptedLevel, "无效样本不得改变档位")
    }

    @Test
    fun `ceiling drop below level is followed immediately`() {
        val c = AdaptiveConcurrencyController(maximumConcurrency = 64)
        startProbing(c, 4_000_000.0)
        val d = c.observe(1_000_000.0, rampDone = true, concurrencyLimit = 16)
        assertTrue(d.target != null && d.target!! <= 16, "上限低于当前档位时必须跟下来")
        assertTrue(c.acceptedLevel <= 16)
    }

    @Test
    fun `repeated descent stops at the floor instead of one connection`() {
        // 极端：无论多低吞吐都一样（纯聚合封顶），应一路降到下限并停住。
        // 下限是 4 而不是 1 —— 连接数同时是「应对连接速度不均」的余量（见控制器 minimumConcurrency 注释）。
        val c = AdaptiveConcurrencyController(maximumConcurrency = 16, holdWindowsAfterReject = 1)
        var level = 16
        repeat(40) {
            val d = feed(c, 1_000_000.0)
            d.target?.let { level = it }
        }
        assertTrue(level >= 4, "档位不得降到 4 以下，实际 $level")
        assertTrue(c.acceptedLevel >= 4, "已接受档位不得低于下限，实际 ${c.acceptedLevel}")
    }
}

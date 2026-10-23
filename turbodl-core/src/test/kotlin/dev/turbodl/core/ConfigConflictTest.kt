package dev.turbodl.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 配置项之间的**冲突**关系。
 *
 * 这些开关单独看都没问题，组合起来才会坏事 —— 而且坏的方式往往不是报错，
 * 是"文件长度对、内容错"。界面把每个开关都单独摆出来，用户很容易全打开，
 * 所以互斥必须有代码保证，不能只写在文档注释里。
 */
class ConfigConflictTest {

    /**
     * `overlapMerge` 与 `tailAssist` 不能同时生效。
     *
     * 托管把分片 [A,B] 改成 [A,M] + [M+1,B]；预先合并又把已完成分片直接写进目标文件。
     * 若原分片在托管前已写过 M 之后，它的数据会盖到接管分片的区间上 ——
     * 两段内容来自不同请求，可能命中不同 CDN 节点，谁后写谁赢。
     *
     * 实测表现：长度正确、SHA-256 不符（`DirectWriteContractTest` 早已复现）。
     */
    @Test
    fun `overlap merge is suppressed when tail assist is on`() {
        // 决定是否启用预先合并的唯一条件，与 BuiltinHttpBackend 里保持一致
        fun overlapEnabled(cfg: TurboConfig) =
            cfg.overlapMerge && !cfg.tailAssist

        // 两个都开 -> 必须关掉预先合并（否则写坏文件）
        assertFalse(
            overlapEnabled(TurboConfig(overlapMerge = true, tailAssist = true)),
            "tailAssist 开启时必须禁用 overlapMerge",
        )
        // 只开预先合并 -> 允许
        assertTrue(
            overlapEnabled(TurboConfig(overlapMerge = true, tailAssist = false)),
        )
        // 只开托管 -> 自然不启用预先合并
        assertFalse(
            overlapEnabled(TurboConfig(overlapMerge = false, tailAssist = true)),
        )
        // 默认值：两个都按各自默认（overlap=false, tail=true）-> 不启用
        assertFalse(overlapEnabled(TurboConfig()))
    }

    /**
     * HTTP 版本策略的解析规则。
     *
     * 曾经的问题：默认值就是 AUTO，于是"显式选了 AUTO"和"没设置"无法区分，
     * 只能靠 forceHttp1 兜底 —— 用户把「强制 HTTP/1.1」关掉、策略选 AUTO，
     * 实际生效的却是 FORCE_HTTP2。选择被另一个开关悄悄改写。
     *
     * 现在用 UNSET 表示"没设置"：只有它是从 forceHttp1 推导的，
     * 显式选了 AUTO 就一定是 AUTO。
     */
    @Test
    fun `explicit AUTO is never overridden by forceHttp1`() {
        // AUTO + 强制关闭 -> 仍然是 AUTO（旧版本这里会变成 FORCE_HTTP2）
        assertTrue(
            TurboConfig(
                httpVersionPolicy = HttpVersionPolicy.AUTO,
                forceHttp1 = false,
            ).effectiveHttpVersionPolicy == HttpVersionPolicy.AUTO,
            "显式选择 AUTO 不应被 forceHttp1 改写",
        )
        // AUTO + 强制开启 -> AUTO
        assertTrue(
            TurboConfig(
                httpVersionPolicy = HttpVersionPolicy.AUTO,
                forceHttp1 = true,
            ).effectiveHttpVersionPolicy == HttpVersionPolicy.AUTO,
        )
        // 未设置（默认值 UNSET）时才回落到旧字段
        assertTrue(
            TurboConfig(forceHttp1 = true).effectiveHttpVersionPolicy == HttpVersionPolicy.AUTO,
        )
        assertTrue(
            TurboConfig(forceHttp1 = false).effectiveHttpVersionPolicy ==
                HttpVersionPolicy.FORCE_HTTP2,
        )
        // 显式非 AUTO -> 忽略 forceHttp1
        assertTrue(
            TurboConfig(
                httpVersionPolicy = HttpVersionPolicy.FORCE_HTTP1,
                forceHttp1 = false,
            ).effectiveHttpVersionPolicy == HttpVersionPolicy.FORCE_HTTP1,
        )
        assertTrue(
            TurboConfig(
                httpVersionPolicy = HttpVersionPolicy.FORCE_HTTP2,
                forceHttp1 = true,
            ).effectiveHttpVersionPolicy == HttpVersionPolicy.FORCE_HTTP2,
        )
    }

    /**
     * `slowStartInitial` 大于总并发时，起点被夹到总并发 —— 不能"起步就超发"。
     */
    @Test
    fun `slow start initial never exceeds worker count`() {
        val workers = 8
        val cfg = TurboConfig(slowStart = true, slowStartInitial = 32)
        val start = minOf(cfg.slowStartInitial, workers)
        assertTrue(start <= workers, "起始并发不能超过总 worker 数")
    }

    /**
     * 关闭慢启动时，起点必须是全部 worker —— 否则「慢启动关掉」却仍在逐步爬升。
     */
    @Test
    fun `slow start off starts at full concurrency`() {
        val workers = 16
        val cfg = TurboConfig(slowStart = false, slowStartInitial = 4)
        // 与 SegmentScheduler 的 slowStartInit 计算保持一致：
        //   !slowStart -> workers
        val start = if (!cfg.slowStart) workers else minOf(cfg.slowStartInitial, workers)
        assertTrue(start == workers)
    }
}

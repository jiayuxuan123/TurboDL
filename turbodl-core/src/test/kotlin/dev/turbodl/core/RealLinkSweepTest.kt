package dev.turbodl.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 【病因确认 · 真实链路版】在**你自己的链接**上扫连接数，判定服务端用的是哪种限速模型。
 *
 * `ConnectionSweepTest` 用模拟服务端证明的是"三种模型各自长什么样、TurboDL 能不能自愈"；
 * 但**对方到底是哪一种，只能在真实链路上测**。这个测量台就是为那一步准备的：
 * 只改连接数、其他配置完全不变，跑固定时长的**窗口**（不是整文件），对比吞吐。
 *
 * ## 用法
 *
 * ```
 * TURBODL_REALNET=1 \
 * TURBODL_SWEEP_URL="https://…/xxx" \
 * TURBODL_SWEEP_SIZE=1395864371 \      # 已知大小（网盘解析结果）；不给则探测
 * TURBODL_SWEEP_HEADERS="Cookie:a=b;Referer:https://…"   # 网盘直链通常需要
 * TURBODL_SWEEP_SECS=20 \
 * TURBODL_SWEEP_CONNS=8,16,32,64,128 \
 * ./gradlew :turbodl-core:test --tests "*RealLinkSweepTest*"
 * ```
 *
 * ## 判读表（这才是这个测试的产出）
 *
 * | 吞吐随连接数 | 结论 | 该往哪改 |
 * |---|---|---|
 * | 线性上升 | 服务端**每连接限速** | 加连接，直到收益饱和 |
 * | 基本持平 | 服务端**按 IP/账户聚合限速** | 加连接无用 → 该**降**到刚好够用的档位，省请求与握手 |
 * | 先升后降 / 高点后塌陷 | 服务端**对并发有惩罚**（拒绝或降级） | 必须**自适应降到拐点**，这正是 aria2 限 16 的理由 |
 *
 * 附加观测：`峰值并发`（是否真的跑满 N）、`不增长时长`（涓流/卡死信号）。
 */
class RealLinkSweepTest {

    private companion object {
        fun env(k: String, d: String? = null) = System.getenv(k) ?: d
    }

    @Test
    fun `sweep connection count on a real link`() = runBlocking {
        if (System.getenv("TURBODL_REALNET") != "1") {
            println("[SWEEP-REAL] 跳过（需 TURBODL_REALNET=1 + TURBODL_SWEEP_URL=…）")
            return@runBlocking
        }
        val url = env("TURBODL_SWEEP_URL")
        if (url.isNullOrBlank()) {
            println("[SWEEP-REAL] 未提供 TURBODL_SWEEP_URL，跳过")
            return@runBlocking
        }
        val knownSize = env("TURBODL_SWEEP_SIZE")?.toLongOrNull() ?: -1L
        val secs = env("TURBODL_SWEEP_SECS")?.toLongOrNull() ?: 20L
        val conns = (env("TURBODL_SWEEP_CONNS") ?: "8,16,32,64,128")
            .split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }
        val headers = (env("TURBODL_SWEEP_HEADERS") ?: "").split(';')
            .mapNotNull { part ->
                val i = part.indexOf(':')
                if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }.toMap()

        println("=== 真实链路连接数扫描 ===")
        println("URL        : ${url.take(120)}")
        println("已知大小   : ${if (knownSize > 0) "$knownSize 字节" else "未提供（走探测）"}")
        println("单格时长   : ${secs}s   连接数档位: ${conns.joinToString("/")}")
        println("自定义请求头: ${if (headers.isEmpty()) "（无）" else headers.keys.joinToString("/")}")
        println("")
        println("%-8s %14s %12s %10s".format("连接数", "窗口吞吐", "峰值速度", "峰值并发"))

        // 【单一实现】直接复用发布出去的 TurboDiagnostics —— 测试与产品同一份代码，不会漂移。
        val results = TurboDiagnostics.sweepConnections(
            url = url,
            headers = headers,
            knownSize = knownSize,
            tiers = conns,
            windowMs = secs * 1000,
            gapMs = 1000,
            workDir = File(System.getProperty("java.io.tmpdir"), "turbodl-sweep"),
        )
        for (r in results) {
            println(
                "%-8d %11.2f MB/s %9s %10d".format(
                    r.connections, r.mbPerSec,
                    if (r.error != null) "—" else "—", r.peakConnections
                )
            )
            println("         ${r}")
        }

        println("")
        println("=== 判读 ===")
        println(TurboDiagnostics.interpret(results))
        assertTrue(true, "测量台：不做断言，结果由人判读")
    }
}

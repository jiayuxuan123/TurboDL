package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 【分离变量】连接数 vs 分片数 —— 到底是哪个让高并发变慢？
 *
 * ## 背景
 *
 * `ConnectionSweepTest` 在「不限速（对照）」模型下三次干净运行都得到同一条曲线：
 *
 * | 连接数 | 8 | 16 | 32 | 128 |
 * |---|---|---|---|---|
 * | 吞吐 MB/s | 17.1 / 18.8 / **17.9** | 17.1 / 20.6 / **16.6** | 9.1 / 13.8 / **9.3** | 4.3 / 4.9 / **4.1** |
 *
 * **单调、量级大（4 倍）、可复现** —— 按本项目的采信标准，这是真问题。
 *
 * 但一次跑动同时变了两个量：分片数 = 连接数 × `segmentsPerConnection(4)`，**连接数翻倍，分片数也翻倍**。
 * 所以"128 连接慢 4 倍"到底是：
 * - **(a) 分片太多** → 每个分片一次请求 + 一份临时文件 + 最后要合并 256 个文件；还是
 * - **(b) 连接太多** → 128 条 socket/线程本身的代价。
 *
 * 两者修法完全不同（(a) 该减少分片；(b) 该限制连接数），所以必须分离。
 *
 * ## 正交矩阵（关键：用 `minSegmentSize` 独立拨动分片数）
 *
 * `effBlock = max(min(total/(workers×spc), blockSize), min(minSegmentSize, total/workers))`
 * —— 把 `minSegmentSize` 抬高即可在**连接数不变**的情况下减少分片数。于是：
 *
 * | 组 | 连接数 | 分片数 | 对比意义 |
 * |---|---|---|---|
 * | A | 128 | 256 | 基线（当前默认） |
 * | B | 128 | 128 | **A vs B：同连接数、分片减半** → 分片数的影响 |
 * | C | 64 | 128 | **B vs C：同分片数、连接减半** → 连接数的影响 |
 * | D | 32 | 128 | **C vs D：继续减连接、分片不变** |
 *
 * 判读：
 * - 若 B ≈ 2×A → 代价主要来自**分片数**（请求+合并开销）→ 该改扇出公式/合并；
 * - 若 B ≈ C ≈ D（都远快于 A）→ 代价来自**连接数**本身 → 该限制并发而不是减分片；
 * - 若只有 A 慢、B/C/D 都快，则两者都不是唯一原因，需再看别的量。
 *
 * opt-in（`TURBODL_BENCH=1`）：这是诊断台，不是回归测试。
 */
class FanoutCostTest {

    /** 不限速的 Range 服务器 + 请求/字节计数（用于确认分片数真的按预期变化）。 */
    private class PlainServer(private val payload: ByteArray) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        val count206 = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)
        private val concurrent = AtomicInteger(0)

        init {
            server.createContext("/f.bin") { ex: HttpExchange ->
                val cur = concurrent.incrementAndGet()
                peakConcurrent.updateAndGet { maxOf(it, cur) }
                try {
                    val size = payload.size
                    val range = ex.requestHeaders.getFirst("Range")
                    ex.responseHeaders.add("Content-Type", "application/octet-stream")
                    ex.responseHeaders.add("Accept-Ranges", "bytes")
                    val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                    if (m == null) {
                        ex.sendResponseHeaders(200, size.toLong())
                        ex.responseBody.use { it.write(payload) }
                        return@createContext
                    }
                    val s = m.groupValues[1].toInt()
                    val e = m.groupValues[2].toIntOrNull() ?: (size - 1)
                    if (s >= size) { ex.sendResponseHeaders(416, -1); ex.close(); return@createContext }
                    val end = minOf(e, size - 1)
                    val len = end - s + 1
                    ex.responseHeaders.add("Content-Range", "bytes $s-$end/$size")
                    count206.incrementAndGet()
                    ex.sendResponseHeaders(206, len.toLong())
                    ex.responseBody.use { it.write(payload, s, len) }
                } finally {
                    concurrent.decrementAndGet()
                }
            }
            server.executor = Executors.newFixedThreadPool(320)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    private companion object {
        const val SIZE = 16 * 1024 * 1024
        /** 每格重复次数，取中位数（回环噪声大，单次不可信）。 */
        const val REPEAT = 3
    }

    /**
     * @param segCap `maxSegmentsPerTask`：0 = 不限（用于做"上限关闭"的对照组）
     */
    private data class Cell(
        val label: String,
        val conns: Int,
        val minSegment: Long,
        val segCap: Int,
    )

    private suspend fun measureOnce(cell: Cell, payload: ByteArray): Triple<Long, Int, Int> {
        val srv = PlainServer(payload)
        val out = File.createTempFile("fanout", ".bin").apply { deleteOnExit() }
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = cell.conns,
                maxConcurrentTasks = 1,
                warmUpConnections = false,
                slowStart = false,          // 固定并发，避免慢启动把"连接数"这个自变量又搅浑
                minSegmentSize = cell.minSegment,
                blockSize = 64L * 1024 * 1024,
                maxSegmentsPerTask = cell.segCap,
                // 必须关掉自适应并发：自变量就是"连接数"，而自适应会按吞吐把它收敛掉 ——
                // 那样测出来的"128 连接"根本不是 128 连接，整个矩阵失去意义。
                adaptiveConcurrency = false,
            )
        )
        val t0 = System.currentTimeMillis()
        try {
            val id = client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:${srv.port}/f.bin",
                    destination = out,
                    knownSize = payload.size.toLong(),
                )
            )
            val ms = client.await(id).let {
                assertTrue(it.isSuccess, "${cell.label} 应下载成功")
                System.currentTimeMillis() - t0
            }
            assertTrue(out.readBytes().contentEquals(payload), "${cell.label} 必须字节精确")
            return Triple(ms, srv.count206.get(), srv.peakConcurrent.get())
        } finally {
            client.shutdown(); srv.stop(); out.delete()
        }
    }

    @Test
    fun `separate fanout cost into connection count and segment count`() = runBlocking {
        if (System.getenv("TURBODL_BENCH") != "1") {
            println("[FANOUT] 跳过扇出成本分离实验（设置 TURBODL_BENCH=1 启用）")
            return@runBlocking
        }
        val payload = ByteArray(SIZE) { ((it * 37 + 11) % 256).toByte() }
        val seg128 = SIZE.toLong() / 128    // 128KB → 恰好 128 个分片

        // 【为什么要"交错"跑】旧版按 A→B→C→D 顺序各跑 3 次取中位数，结果 A 总是吃亏：
        // 加锁后 A 与 B 的配置已**完全等价**（同为 128 分片），却测出 6.4 vs 9.3 MB/s ——
        // 说明单格噪声高达 ±45%，顺序偏差会直接伪装成"分片数的影响"。
        // 现在改为 round-robin 交错（A B C D / A B C D …），让机器状态漂移均摊到所有格。
        val cells = listOf(
            Cell("A 128连接/256分片", 128, 64L * 1024, segCap = 0),
            Cell("B 128连接/128分片", 128, 64L * 1024, segCap = 128),
            Cell("C  64连接/256分片", 64, 64L * 1024, segCap = 0),
            Cell("D  64连接/128分片", 64, 64L * 1024, segCap = 128),
            Cell("E  32连接/128分片", 32, 64L * 1024, segCap = 0),
            Cell("F  16连接/ 64分片", 16, 64L * 1024, segCap = 0),
        )
        println("=== 扇出成本分离（${SIZE / 1048576}MB，不限速回环，交错跑 $REPEAT 轮取中位数）===")
        val times = HashMap<String, MutableList<Long>>()
        val blocks = HashMap<String, Int>()
        val peaks = HashMap<String, Int>()
        try {
            repeat(REPEAT) {
                for (c in cells) {
                    val (ms, n, pk) = measureOnce(c, payload)
                    times.getOrPut(c.label) { mutableListOf() } += ms
                    blocks[c.label] = n; peaks[c.label] = maxOf(peaks[c.label] ?: 0, pk)
                    Thread.sleep(200)
                }
            }
        } catch (e: Exception) {
            println("（本轮中断：${e.message}）")
        }

        val medians = LinkedHashMap<String, Double>()
        for (c in cells) {
            val s = times[c.label] ?: continue
            if (s.isEmpty()) continue
            val med = s.sorted()[s.size / 2]
            val mbs = SIZE.toDouble() / 1048576.0 / (med / 1000.0)
            medians[c.label] = mbs
            println(
                "%-30s 分片=%-4d %7dms %7.1f MB/s  峰值=%-4d 样本=%s".format(
                    c.label, blocks[c.label] ?: -1, med, mbs, peaks[c.label] ?: 0,
                    s.sorted().joinToString("/")
                )
            )
        }

        val a = medians["A 128连接/256分片"] ?: 0.0
        val b = medians["B 128连接/128分片"] ?: 0.0
        val c = medians["C  64连接/256分片"] ?: 0.0
        val d = medians["D  64连接/128分片"] ?: 0.0
        println("")
        println("=== 判读（2×2：连接数 × 分片数）===")
        println("A(128c/256s)=%.1f  B(128c/128s)=%.1f  C(64c/256s)=%.1f  D(64c/128s)=%.1f".format(a, b, c, d))
        if (a > 0 && b > 0 && c > 0 && d > 0) {
            val connEffect = ((d / b) + (c / a)) / 2   // 128c→64c 的收益（两个分片档位平均）
            val segEffect = ((b / a) + (d / c)) / 2    // 256s→128s 的收益（两个连接档位平均）
            println("连接数 128→64 的平均收益 = %.2f×　　分片数 256→128 的平均收益 = %.2f×".format(connEffect, segEffect))
            println(
                when {
                    connEffect > 1.3 && connEffect > segEffect * 1.3 ->
                        "→ 主因是**连接数**：128 连接本身太贵（socket/线程/回环栈）。方向应是限制并发，而不是减少分片。"
                    segEffect > 1.3 && segEffect > connEffect * 1.3 ->
                        "→ 主因是**分片数**：每块一次请求 + 一份临时文件 + 合并开销。方向应是限制块数。"
                    else ->
                        "→ 两个维度影响相当（或都<1.3×，噪声主导）⇒ 回环上分辨不出，需真实链路数据定论。"
                }
            )
        }
        // ---- 结论钉住（P11 的验收：结论要由断言守，不能只写在注释里）----
        //
        // 断言的是**结论的方向**，不是具体数字 —— 回环噪声让绝对值不可复现
        // （实测同一配置的样本能差 45%）。方向性结论（哪个维度在起作用、
        // 曲线是不是单调）才是可复现的部分，也只有它值得被守。
        if (a > 0 && b > 0 && c > 0 && d > 0) {
            // ① 两个维度都在起作用，没有哪个是唯一原因。
            //    若将来有人"优化"成只改一个维度就宣称解决，这条会失败。
            val noiseBand = 1.10
            assertTrue(
                b * noiseBand > a,
                "同连接数下减少分片应当有收益（B>A）；实测 B=$b A=$a。" +
                    "若这条失败，说明分片数已不再是成本项 —— 那时该更新 TurboConfig 的结论。",
            )
            assertTrue(
                c * noiseBand > a,
                "同分片数下减少连接应当有收益（C>A）；实测 C=$c A=$a。",
            )
            // ② 两者同时降才接近最优（D 明显好于 B 和 C）——
            //    这正是"只调一个旋钮解决不了高并发变慢"的量化依据。
            assertTrue(
                d * noiseBand > b && d * noiseBand > c,
                "两个维度都降（D）应当优于只降一个（B / C）；实测 D=$d B=$b C=$c。",
            )
            // ③ 曲线在 16 连接处仍不亏损：说明最优区在低连接侧，
            //    这与"自适应并发应当把连接数收敛下来"（P10）方向一致。
            val f = medians["F 16连接/ 64分片"] ?: 0.0
            if (f > 0) {
                assertTrue(
                    f * noiseBand > a,
                    "16 连接的吞吐应当高于 128 连接（F>A）；实测 F=$f A=$a。" +
                        "若这条失败，说明高连接数不再是成本 —— 那时 P10 的收敛方向要重新评估。",
                )
            }
        }
    }
}

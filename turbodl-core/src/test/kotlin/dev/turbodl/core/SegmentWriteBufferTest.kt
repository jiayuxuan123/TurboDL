package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P12：**分片写入要不要加缓冲？** —— 用实测回答，而不是靠"减少系统调用"的直觉。
 *
 * ## 为什么要做成测量台而不是直接改
 *
 * "裸 `RandomAccessFile` 直写会有系统调用开销"听起来像显然的优化点。但：
 *  - 回环的写路径几乎不阻塞（页缓存），减少 `write` 次数省不出时间；
 *  - 真实瓶颈在磁盘时，缓冲的收益取决于文件系统与介质，引擎不该一概而论；
 *  - 而**代价是确定的**：缓冲把"已写入长度"从"已落盘"变成"已交给 OS 缓冲"，
 *    断点续传靠文件长度（`SegmentScheduler.verifyCoverage`）判断进度，所以必须每次
 *    回调前 flush —— 那又抵消掉大部分缓冲意义。
 *
 * 所以先量，再决定默认值。开关是 [TurboConfig.bufferedSegmentWrite]，默认 false。
 *
 * ## 采信标准（沿用本项目既有规矩）
 *
 * - **交错运行**（A B / A B …）而不是 A 全跑完再跑 B —— 机器状态漂移会伪装成差异
 *   （`FanoutCostTest` 的记录显示单格噪声可达 ±45%）；
 * - 每档多轮，取**中位数**；
 * - 强制 `adaptiveConcurrency = false`：本测量要固定并发，否则并发自适应会变成第二个自变量。
 *
 * 用 `TURBODL_BENCH=1` 启用（默认套件跳过）。
 */
class SegmentWriteBufferTest {

    /** 不限速的 Range 服务器：写入侧的差异必须来自客户端，不能是服务端限速造成。 */
    private class PlainServer(private val payload: ByteArray, port: Int) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        val port: Int = server.address.port
        private val concurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        init {
            server.createContext("/f.bin") { ex: HttpExchange -> handle(ex) }
            server.executor = Executors.newFixedThreadPool(320)
            server.start()
        }

        private fun handle(ex: HttpExchange) {
            val cur = concurrent.incrementAndGet()
            peakConcurrent.updateAndGet { max(it, cur) }
            try {
                val size = payload.size
                val m = ex.requestHeaders.getFirst("Range")
                    ?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                if (m == null) {
                    ex.sendResponseHeaders(200, size.toLong())
                    ex.responseBody.use { it.write(payload) }
                    return
                }
                val s = m.groupValues[1].toInt()
                val e = m.groupValues[2].toIntOrNull() ?: (size - 1)
                val end = minOf(e, size - 1)
                if (s > end) { ex.sendResponseHeaders(416, -1); ex.close(); return }
                val len = end - s + 1
                ex.responseHeaders.add("Content-Range", "bytes $s-$end/$size")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { out: OutputStream -> out.write(payload, s, len) }
            } finally {
                concurrent.decrementAndGet()
            }
        }

        fun stop() = server.stop(0)
    }

    private companion object {
        const val SIZE = 16 * 1024 * 1024

        /** 128 连接 / 64KB 分片：分片数最多、每片最小的组合 —— 写入开销最容易被放大。 */
        const val CONNECTIONS = 128

        /** 每档轮数：取中位数，交错运行。 */
        const val ROUNDS = 3

        /** 安全端口范围（与工作区约定一致）：不得使用 8787/8788。 */
        val SAFE_PORT_RANGE = 8791..8850

        fun freeSafePort(): Int? {
            for (port in SAFE_PORT_RANGE) {
                try {
                    java.net.ServerSocket(port).use { return port }
                } catch (_: java.io.IOException) {
                    // 换下一个
                }
            }
            return null
        }
    }

    /** 跑一次，返回耗时（毫秒）；失败抛异常而不是返回一个会污染中位数的数字。 */
    private suspend fun measureOnce(
        payload: ByteArray,
        buffered: Boolean,
        port: Int,
    ): Long {
        val srv = PlainServer(payload, port)
        val out = File.createTempFile("writebuf", ".bin").apply { deleteOnExit() }
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = CONNECTIONS,
                maxConcurrentTasks = 1,
                warmUpConnections = false,
                slowStart = false,
                // 本测量的自变量只有一个：写入是否缓冲。自适应会引入第二个自变量。
                adaptiveConcurrency = false,
                minSegmentSize = 64L * 1024,
                blockSize = 64L * 1024,     // 强制 64KB 分片 → 256 片
                bufferedSegmentWrite = buffered,
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
            val res = client.await(id)
            assertTrue(res.isSuccess, "buffered=$buffered 应下载成功：${res.exceptionOrNull()?.message}")
            assertTrue(out.readBytes().contentEquals(payload), "buffered=$buffered 必须字节精确")
            return System.currentTimeMillis() - t0
        } finally {
            client.shutdown()
            srv.stop()
            out.delete()
        }
    }

    @Test
    fun `buffered write vs direct write at 128 connections`() = runBlocking {
        if (System.getenv("TURBODL_BENCH") != "1") {
            println("[WRITEBUF] 跳过写缓冲 A/B（设置 TURBODL_BENCH=1 启用）")
            return@runBlocking
        }
        val port = freeSafePort() ?: run {
            println("[WRITEBUF] 安全端口全部占用，跳过（8791-8850）")
            return@runBlocking
        }
        val payload = ByteArray(SIZE) { ((it * 41 + 13) % 256).toByte() }

        val direct = mutableListOf<Long>()
        val buffered = mutableListOf<Long>()
        println("=== 写缓冲 A/B：${SIZE / 1048576}MB / $CONNECTIONS 连接 / 64KB 分片 / 交错 $ROUNDS 轮 ===")
        // 交错：每轮里 direct 与 buffered 各跑一次，让机器状态漂移均摊到两边
        repeat(ROUNDS) { round ->
            val d = measureOnce(payload, buffered = false, port = port)
            direct += d
            val b = measureOnce(payload, buffered = true, port = port)
            buffered += b
            println("  轮 ${round + 1}: 直写 ${d}ms   缓冲写 ${b}ms")
            Thread.sleep(200)
        }

        val dm = direct.sorted()[direct.size / 2]
        val bm = buffered.sorted()[buffered.size / 2]
        val dMbs = SIZE.toDouble() / 1048576.0 / (dm / 1000.0)
        val bMbs = SIZE.toDouble() / 1048576.0 / (bm / 1000.0)
        val delta = (bm - dm).toDouble() / dm * 100.0

        println("")
        println("  直写   中位数 ${dm}ms  %.1f MB/s   样本=%s".format(dMbs, direct.sorted().joinToString("/")))
        println("  缓冲写 中位数 ${bm}ms  %.1f MB/s   样本=%s".format(bMbs, buffered.sorted().joinToString("/")))
        println("  差异：%.1f%%（正数=缓冲更慢）".format(delta))
        println("")
        println(
            when {
                delta < -10.0 -> "→ 缓冲写**明显更快**（>10%）：值得考虑改默认值，但需先在真实磁盘上复测。"
                delta > 10.0 -> "→ 缓冲写**明显更慢**（>10%）：维持直写默认。"
                else -> "→ 两者差异在噪声范围内（<10%）：维持直写默认 —— 它语义更简单，且不改变断点续传的\"已落盘\"含义。"
            }
        )

        // 正确性不变量：两种写法都必须字节精确（上面 measureOnce 里已断言）。
        assertEquals(SIZE.toLong(), SIZE.toLong(), "占位断言：正确性由 measureOnce 保证")
    }
}

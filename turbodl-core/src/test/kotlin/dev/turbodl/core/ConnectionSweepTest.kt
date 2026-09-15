package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 【第一阶段第一步：病因确认】连接数扫描 —— 用**三种服务端限速模型**分离病因。
 *
 * ## 为什么要做这个
 *
 * 用户实机（YunGet）观测：**1.3GB 文件 / 128 线程 / 1.5 MB/s → 每连接仅 ~12 KB/s**。
 * 这既可能是「分片粒度不对」，也可能是「连接数过高被服务器惩罚」。**两者的修法完全相反**：
 * 前者要多分片，后者要**少**连接。
 *
 * 关键点：**「加连接有没有用」完全取决于服务端如何限速**，而引擎事先不知道服务端是哪种。
 * 三种模型的结论互斥：
 *
 * | 服务端模型 | 加连接的后果 |
 * |---|---|
 * | 每连接限速（如 200KB/s/连接） | 吞吐 **∝ 连接数**，多多益善 |
 * | 按 IP/账户聚合限速（总额 2MB/s） | 吞吐**封顶**，加连接只增加请求开销 |
 * | 单 host 并发硬上限（>16 并发直接 503） | 加连接**触发拒绝 → 重试风暴 → 吞吐塌陷** |
 *
 * aria2 的 `max-connection-per-server` **默认 1、上限 16**，就是因为现实中第三种模型很常见。
 * 本测量台把三种模型都跑一遍，回答的是：**TurboDL 在每种模型下的实际表现如何、
 * 以及在「聚合限速」和「并发惩罚」下它能不能自己收敛回来**（背压是否有效）。
 *
 * ## 这不是"证明夸克是哪种" 
 *
 * 服务端模型是**我模拟的**，所以本测试**不能**判定夸克用的是哪一种。
 * 它能提供的是：① 每条曲线长什么样 → 真实链路上该看什么信号；
 * ② TurboDL 在 hostile 限流下的**自愈能力**（这是产品问题，与对方是哪种模型无关）。
 *
 * 用 `TURBODL_BENCH=1` 启用（默认套件跳过）。
 */
class ConnectionSweepTest {

    /** 服务端限速模型。 */
    enum class LimitModel(val label: String) {
        /** 完全不限速：作为对照，暴露 TurboDL 自身的开销与天花板。 */
        UNLIMITED("不限速(对照)"),

        /** 每条连接各自限速 —— 加连接线性有效。 */
        PER_CONNECTION("每连接限速 100KB/s"),

        /** 按客户端 IP 聚合限速 —— 加连接没有收益，只增开销。 */
        PER_IP_AGGREGATE("IP 聚合限速 2MB/s"),

        /** 单 host 并发硬上限 16：超出直接 503 —— 加连接会导致拒绝+重试。 */
        PER_HOST_CAP_16("并发上限16(超出503)"),
    }

    /**
     * 简单速率限制器：把 `n` 字节的"发送许可"按 [bytesPerSec] 摊开。
     * 所有 acquire 串行排队（同一实例即等价于"一条聚合管道"），因此多线程共享一个实例
     * 恰好得到「总量固定」的语义；每请求一个实例则得到「每连接固定」的语义。
     */
    private class RateLimiter(private val bytesPerSec: Long) {
        private var nextFreeNanos = System.nanoTime()
        @Synchronized
        fun acquire(n: Int) {
            val now = System.nanoTime()
            val start = max(nextFreeNanos, now)
            val durNanos = n * 1_000_000_000L / bytesPerSec
            nextFreeNanos = start + durNanos
            val waitNanos = start - now
            if (waitNanos > 0) LockSupport.parkNanos(waitNanos)
        }
    }

    private class SweepServer(private val payload: ByteArray, private val model: LimitModel) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port

        val concurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)
        val count206 = AtomicInteger(0)
        val count503 = AtomicInteger(0)
        val countOther = AtomicInteger(0)

        /** 聚合限速器：整个服务器共用一个（就是"按 IP 聚合"的语义）。 */
        private val aggregate = RateLimiter(2_000_000)
        private val connRate = 100_000L

        init {
            server.createContext("/f.bin") { ex: HttpExchange ->
                val cur = concurrent.incrementAndGet()
                peakConcurrent.updateAndGet { max(it, cur) }
                try {
                    handle(ex)
                } finally {
                    concurrent.decrementAndGet()
                }
            }
            // 并发上限模型需要大量连接同时挂住（parkNanos 期间占线程）
            server.executor = Executors.newFixedThreadPool(320)
            server.start()
        }

        private fun handle(ex: HttpExchange) {
            val size = payload.size
            val range = ex.requestHeaders.getFirst("Range")
            ex.responseHeaders.add("Content-Type", "application/octet-stream")
            ex.responseHeaders.add("Accept-Ranges", "bytes")

            // 单 host 并发上限：超出即拒绝（这正是真实的"每服务器最大连接数"行为）
            if (model == LimitModel.PER_HOST_CAP_16 && concurrent.get() > 16) {
                count503.incrementAndGet()
                ex.sendResponseHeaders(503, -1)
                ex.close()
                return
            }

            val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
            if (m == null) {
                countOther.incrementAndGet()
                ex.sendResponseHeaders(200, size.toLong())
                ex.responseBody.use { writeThrottled(it, 0, size) }
                return
            }
            val s = m.groupValues[1].toInt()
            val e = m.groupValues[2].toIntOrNull() ?: (size - 1)
            if (s >= size) { ex.sendResponseHeaders(416, -1); ex.close(); return }
            val end = minOf(e, size - 1)
            val len = end - s + 1
            ex.responseHeaders.add("Content-Range", "bytes $s-$end/$size")
            count206.incrementAndGet()
            ex.sendResponseHeaders(206, len.toLong())
            ex.responseBody.use { writeThrottled(it, s, len) }
        }

        private fun writeThrottled(out: OutputStream, off: Int, len: Int) {
            val limiter = when (model) {
                LimitModel.UNLIMITED -> null
                LimitModel.PER_CONNECTION -> RateLimiter(connRate)
                LimitModel.PER_IP_AGGREGATE -> aggregate
                LimitModel.PER_HOST_CAP_16 -> aggregate
            }
            if (limiter == null) { out.write(payload, off, len); return }
            val chunk = 16 * 1024
            var written = 0
            while (written < len) {
                val n = minOf(chunk, len - written)
                limiter.acquire(n)
                out.write(payload, off + written, n)
                written += n
            }
        }

        fun stop() = server.stop(0)
    }

    private companion object {
        const val SIZE = 16 * 1024 * 1024
        /** 连接数档位：rc14 默认 16、用户实机用的 128，以及两端 */
        val CONNS = listOf(8, 16, 32, 128)
        /** 单格墙钟上限：保证测量台一定跑完，不会把后台任务挂死。 */
        const val CELL_TIMEOUT_MS = 90_000L
    }

    private suspend fun runCell(model: LimitModel, conns: Int, payload: ByteArray): Pair<String, String?> {
        val srv = SweepServer(payload, model)
        val out = File.createTempFile("sweep", ".bin").apply { deleteOnExit() }
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = conns,
                maxConcurrentTasks = 1,
                warmUpConnections = false,
                slowStart = false,
                maxRetries = 3,
            )
        )
        val t0 = System.currentTimeMillis()
        var failure: String? = null
        try {
            // knownSize 已知 → 跳过探测，避免探测请求混进 503/206 计数
            val id = client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:${srv.port}/f.bin",
                    destination = out,
                    knownSize = payload.size.toLong(),
                )
            )
            val res = withTimeoutOrNull(CELL_TIMEOUT_MS) { client.await(id) }
            when {
                res == null -> { failure = "TIMEOUT(${CELL_TIMEOUT_MS / 1000}s)"; client.cancel(id) }
                res.isFailure -> failure = res.exceptionOrNull()?.message ?: "failed"
                !out.readBytes().contentEquals(payload) -> failure = "内容不一致"
            }
        } catch (e: Exception) {
            failure = e.message ?: e.toString()
        } finally {
            client.shutdown()
            srv.stop()
        }
        val ms = System.currentTimeMillis() - t0
        val mbs = payload.size.toDouble() / 1048576.0 / (ms / 1000.0)
        val line = "%-24s N=%-4d %8dms %8.1f MB/s  峰值并发=%-4d 206=%-5d 503=%-5d %s".format(
            model.label, conns, ms, mbs, srv.peakConcurrent.get(), srv.count206.get(),
            srv.count503.get(), failure?.let { "❌ $it" } ?: "✅"
        )
        return line to failure
    }

    @Test
    fun `connection count sweep across three server limit models`() = runBlocking {
        if (System.getenv("TURBODL_BENCH") != "1") {
            println("[SWEEP] 跳过连接数扫描（设置 TURBODL_BENCH=1 启用）")
            return@runBlocking
        }
        val payload = ByteArray(SIZE) { ((it * 31 + 7) % 256).toByte() }
        println("=== 连接数扫描：${SIZE / 1048576}MB × 4 种服务端限速模型 × 连接数 ${CONNS.joinToString("/")} ===")
        println("（每格单次；本测量看的是**趋势**与自愈行为，不是绝对值）")

        val failures = mutableListOf<String>()
        val byModel = LinkedHashMap<LimitModel, MutableList<String>>()
        for (model in LimitModel.entries) {
            println("")
            println("--- ${model.label} ---")
            val rows = byModel.getOrPut(model) { mutableListOf() }
            for (n in CONNS) {
                val (line, failure) = runCell(model, n, payload)
                println(line)
                rows += line
                if (failure != null) failures += "${model.label} N=$n: $failure"
            }
        }

        println("")
        println("=== 汇总（吞吐 MB/s）===")
        val header = "%-24s %s".format("模型", CONNS.joinToString("") { "N=${it}".padStart(10) })
        println(header)
        for ((model, rows) in byModel) {
            val cells = rows.map { row ->
                Regex("([0-9.]+) MB/s").find(row)?.groupValues?.get(1)?.padStart(10) ?: "?".padStart(10)
            }
            println("%-24s %s".format(model.label, cells.joinToString("")))
        }

        // 正确性不变量：无论服务器怎么限流，都**必须能跑完且字节精确**。
        // 在"聚合限速/并发拒绝"下跑不完 = 产品缺陷（背压无法收敛），必须暴露出来。
        assertTrue(
            failures.isEmpty(),
            "以下组合未能正确完成：\n" + failures.joinToString("\n")
        )
    }
}

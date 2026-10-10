package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * P10 端到端回归：**静默限速**下引擎必须自己收敛。
 *
 * ## 与 `ConnectionSweepTest` 的分工
 *
 * `ConnectionSweepTest` 是**固定并发**诊断台（`slowStart=false`、自适应关闭），
 * 用来分离「服务端限速模型」这个自变量；它**不**验证自适应，因为并发本来是钉死的。
 * 本类反过来：让引擎自己决定并发，验证两种静默限速下的收敛行为。
 *
 * ## 两种模型必须给出相反结论
 *
 * | 服务端模型 | 期望行为 |
 * |---|---|
 * | 每连接独立限速 | 总吞吐随连接增加 → 下探导致腰斩 → **必须拒绝**，并发保持在设定值 |
 * | 全任务聚合限速（不报 429/503） | 加连接没有收益 → **应把并发降下来** |
 *
 * 这就是「只看每连接速度就降并发」会犯的错：第一种模型下那会白白丢掉吞吐。
 *
 * ## 为什么用「特性开关对照」而不是只比吞吐
 *
 * 聚合封顶下总吞吐与连接数**无关**，所以「比吞吐」这类断言即使特性完全失效也能通过。
 * 因此每处断言都直接看**稳态并发**，并拿同一配置关掉自适应的运行做对照。
 *
 * ## 分片数与并发比
 *
 * 控制器只在「不是收尾」的窗口学习（队列还有活可领）。引擎默认块数 = 连接数 × 4，
 * 所以测试文件必须够大，否则分片早早领完、窗口全部作废，特性看起来"没生效"。
 * 下面每个用例都按 `连接数 × 4 × 64KB` 反推文件大小。
 *
 * ## 端口约定
 *
 * 只绑定 127.0.0.1，端口从 [SAFE_PORT_RANGE]（8791–8850）里挑。
 * 绝不使用 8787/8788 —— 那是用户正在运行的服务实例，本测试不得靠近。
 */
class AdaptiveConcurrencyTest {

    private companion object {
        /** 安全端口范围（与工作区约定一致）：不得使用 8787/8788。 */
        val SAFE_PORT_RANGE = 8791..8850

        /** 选一个当下空闲的安全端口；全被占用则跳过本测试。 */
        fun freeSafePort(): Int? {
            for (port in SAFE_PORT_RANGE) {
                try {
                    ServerSocket(port).use { return port }
                } catch (_: java.io.IOException) {
                    // 换下一个
                }
            }
            return null
        }
    }

    /** 服务端限速模型。 */
    private enum class Model(val label: String) {
        /** 每条连接各自 96KB/s —— 加连接线性提升总吞吐。 */
        PER_CONNECTION("每连接 96KB/s"),

        /** 全任务聚合 256KB/s（不报错、不限并发）—— 加连接没有收益。 */
        TASK_AGGREGATE("任务聚合 256KB/s"),
    }

    /**
     * 静默限速服务器：正常返回 206，**从不**返回 429/503。
     *
     * 聚合模型的限速器是所有请求共享的实例，因此并发越高、每条分到的越少，
     * 总吞吐固定封顶 —— 这正是「服务端不报错但你已经到顶」的场景。
     */
    private class SilentLimitServer(
        private val payload: ByteArray,
        private val model: Model,
        port: Int,
    ) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        val port: Int = server.address.port
        val rangeRequests = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)
        private val concurrent = AtomicInteger(0)
        private val aggregate = RateLimiter(TASK_AGGREGATE_BPS)

        private companion object {
            const val PER_CONNECTION_BPS = 96_000L
            const val TASK_AGGREGATE_BPS = 256_000L
        }

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
                val range = ex.requestHeaders.getFirst("Range")
                val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                if (m == null) {
                    ex.sendResponseHeaders(200, size.toLong())
                    ex.responseBody.use { writeThrottled(it, 0, size) }
                    return
                }
                val s = m.groupValues[1].toInt()
                val e = m.groupValues[2].toIntOrNull() ?: (size - 1)
                val end = minOf(e, size - 1)
                if (s > end) { ex.sendResponseHeaders(416, -1); ex.close(); return }
                val len = end - s + 1
                ex.responseHeaders.add("Content-Range", "bytes $s-$end/$size")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                rangeRequests.incrementAndGet()
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { writeThrottled(it, s, len) }
            } finally {
                concurrent.decrementAndGet()
            }
        }

        private fun writeThrottled(out: OutputStream, off: Int, len: Int) {
            val limiter = when (model) {
                Model.PER_CONNECTION -> RateLimiter(PER_CONNECTION_BPS)
                Model.TASK_AGGREGATE -> aggregate
            }
            val chunk = 8 * 1024
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

    /** 把 n 字节的发送许可按速率摊开；同一实例被多请求共享即「聚合」语义。 */
    private class RateLimiter(private val bytesPerSec: Long) {
        private var nextFreeNanos = System.nanoTime()
        @Synchronized
        fun acquire(n: Int) {
            val now = System.nanoTime()
            val start = max(nextFreeNanos, now)
            nextFreeNanos = start + n * 1_000_000_000L / bytesPerSec
            val wait = start - now
            if (wait > 0) LockSupport.parkNanos(wait)
        }
    }

    private class Outcome(
        val ok: Boolean,
        val error: String?,
        val ms: Long,
        val bytes: Long,
        val tailConnections: Int,
        val requests: Int,
    )

    /**
     * 跑一次下载并返回结果。
     *
     * @param adaptive 是否启用被测特性（关掉即同配置对照）
     */
    private suspend fun runDownload(
        model: Model,
        connections: Int,
        payload: ByteArray,
        port: Int,
        adaptive: Boolean,
    ): Outcome = coroutineScope {
        val srv = SilentLimitServer(payload, model, port)
        val out = File.createTempFile("adaptive", ".bin").apply { deleteOnExit() }
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = connections,
                maxConcurrentTasks = 1,
                warmUpConnections = false,
                slowStart = true,
                adaptiveConcurrency = adaptive,
                stallTimeoutMs = 0,
            )
        )
        val tailMax = AtomicInteger(0)
        val tailStartAt = AtomicLong(0)
        val collector = launch {
            client.progress.collect { map ->
                map.values.forEach { p ->
                    if (p.state == TaskState.DOWNLOADING &&
                        tailStartAt.get() > 0 && System.currentTimeMillis() >= tailStartAt.get()
                    ) {
                        tailMax.updateAndGet { max(it, p.activeConnections) }
                    }
                }
            }
        }
        val t0 = System.currentTimeMillis()
        var error: String? = null
        try {
            val id = client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:${srv.port}/f.bin",
                    destination = out,
                    knownSize = payload.size.toLong(),
                )
            )
            // 前 5 秒是慢启动与基线测量期，之后才算稳态
            tailStartAt.set(t0 + 5_000)
            val res = client.await(id)
            if (res.isFailure) error = res.exceptionOrNull()?.message ?: "failed"
            else if (!out.readBytes().contentEquals(payload)) error = "内容不一致"
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            collector.cancel()
            client.shutdown()
            srv.stop()
            runCatching { out.delete() }
        }
        Outcome(
            ok = error == null,
            error = error,
            ms = System.currentTimeMillis() - t0,
            bytes = payload.size.toLong(),
            tailConnections = tailMax.get(),
            requests = srv.rangeRequests.get(),
        )
    }

    @Test
    fun `per-connection cap rejects descent and keeps concurrency`() = runBlocking {
        val port = freeSafePort() ?: run {
            println("[ADAPT] 安全端口全部占用，跳过（8791-8850）")
            return@runBlocking
        }
        // 8 连接 × 4 × 64KB = 2MB 起，取 4MB 让分片数（64）远多于连接数（8）
        val payload = ByteArray(4 * 1024 * 1024) { ((it * 17 + 5) % 256).toByte() }

        // 每连接 96KB/s：8 连接 ≈ 768KB/s，4 连接只有约 384KB/s。
        // 控制器一定会试着把 8 探到 4（找「够用的最低档」），那会让吞吐腰斩 → **必须被拒**。
        val run = runDownload(Model.PER_CONNECTION, 8, payload, port, adaptive = true)

        assertTrue(run.ok, "每连接限速下应完成下载：${run.error}")
        assertTrue(
            run.tailConnections >= 6,
            "下探会让吞吐腰斩，必须回到 8 连接；实际稳态 ${run.tailConnections}",
        )
        assertTrue(run.requests > 0, "应有实际的分片请求")
    }

    @Test
    fun `aggregate cap lowers concurrency compared with adaptive off`() = runBlocking {
        val port = freeSafePort() ?: run {
            println("[ADAPT] 安全端口全部占用，跳过（8791-8850）")
            return@runBlocking
        }
        // 16 连接 × 4 × 64KB = 4MB：分片数（64）是连接数的 4 倍，足够控制器在非收尾期采样
        val payload = ByteArray(4 * 1024 * 1024) { ((it * 29 + 11) % 256).toByte() }

        // 聚合封顶 256KB/s：16 连接能提供的带宽远高于它，多开连接不换来任何吞吐。
        val off = runDownload(Model.TASK_AGGREGATE, 16, payload, port, adaptive = false)
        val on = runDownload(Model.TASK_AGGREGATE, 16, payload, port, adaptive = true)

        assertTrue(off.ok, "聚合限速·关自适应应完成：${off.error}")
        assertTrue(on.ok, "聚合限速·开自适应应完成：${on.error}")

        // 对照：关掉特性时慢启动会一路爬到 16
        assertTrue(
            off.tailConnections >= 12,
            "对照组（自适应关闭）应爬满到设定并发附近，实际 ${off.tailConnections}",
        )
        // 特性生效：封顶下应把并发降下来（下限 4 —— 连接数是应对速度不均的余量）
        assertTrue(
            on.tailConnections < off.tailConnections,
            "聚合封顶下自适应应把并发降下来：关=${off.tailConnections}，开=${on.tailConnections}",
        )
        assertTrue(
            on.tailConnections >= 4,
            "不得降到下限 4 以下，实际 ${on.tailConnections}",
        )
    }
}

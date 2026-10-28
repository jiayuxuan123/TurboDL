package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **背压之后并发必须能自己恢复** —— 这是「下到后面线程数掉到 1、速度归零」那个病的看护用例。
 *
 * ## 曾经的三向死锁
 *
 * 服务器回 429/503 是「你太快了」的信号，引擎会乘性减半并发，并把学到的上限（ceiling）一起压低。
 * 但恢复路径当时有三条，**每一条都被另一条挡住**：
 *
 * 1. `rampUpIfHealthy()` 在 `cur >= cap` 分支里，对「自适应开启」直接 `return`；
 * 2. 自适应控制器要等 `rampDone = desired >= workers` 才肯离开 WAIT_RAMP —— 而 desired 已经被压到 1；
 * 3. 于是 ceiling 永远不涨，desired 永远不涨。
 *
 * 实测（真实网络、Cloudflare、`-c 16`）：全程 `conns=1`，32MB 跑了 50 秒；
 * 同一条链路关掉自适应后 16 连接稳定跑满。这不是"保守"，是**卡死**。
 *
 * ## 本用例做什么
 *
 * 让服务器对**前若干个**分片请求回 503，之后正常。要求：
 *  - 任务最终成功（背压不该把任务拖死）；
 *  - 过程中观测到的**峰值并发**远大于 1（说明确实恢复过，而不是一路小并发跑完）。
 */
class BackpressureRecoveryDeadlockTest {

    private companion object {
        const val SIZE = 8 * 1024 * 1024
        /** 前多少个请求回 503：足以触发背压，但不足以让任务失败。 */
        const val REJECT_FIRST = 24
    }

    private class FlakyServer(private val payload: ByteArray) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        private val served = AtomicInteger(0)
        private val concurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)
        val rejected = AtomicInteger(0)

        init {
            server.createContext("/f.bin") { ex: HttpExchange ->
                val cur = concurrent.incrementAndGet()
                peakConcurrent.updateAndGet { maxOf(it, cur) }
                try {
                    // 每个响应慢一点（20ms）：否则 8MB 在回环上几十毫秒就跑完，
                    // 并发根本来不及建立，也来不及被采样 —— 那样测到的不是"恢复"而是"没跑起来"。
                    if (concurrent.get() > 0) Thread.sleep(40)
                    // 前 REJECT_FIRST 个请求：假装过载。
                    if (served.incrementAndGet() <= REJECT_FIRST) {
                        rejected.incrementAndGet()
                        ex.responseHeaders.add("Retry-After", "0")
                        ex.sendResponseHeaders(503, -1)
                        ex.close()
                        return@createContext
                    }
                    val size = payload.size
                    val range = ex.requestHeaders.getFirst("Range")
                    val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                    ex.responseHeaders.add("Content-Type", "application/octet-stream")
                    ex.responseHeaders.add("Accept-Ranges", "bytes")
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
                    ex.sendResponseHeaders(206, len.toLong())
                    ex.responseBody.use { it.write(payload, s, len) }
                } finally {
                    concurrent.decrementAndGet()
                }
            }
            server.executor = Executors.newFixedThreadPool(128)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    private fun tmpDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "bp-$tag-${System.nanoTime()}")
            .apply { mkdirs(); deleteOnExit() }

    /** 同一个场景跑两遍：自适应开 / 关。两边都必须恢复到高并发。 */
    private fun run(adaptive: Boolean) = runBlocking {
        val payload = ByteArray(SIZE) { (it * 37 + 11).toByte() }
        val srv = FlakyServer(payload)
        val workDir = tmpDir(if (adaptive) "on" else "off")
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 32,
                maxConcurrentTasks = 1,
                workDir = workDir,
                warmUpConnections = false,
                adaptiveConcurrency = adaptive,
                // 背压照常生效：这是本用例要测的那条路径
                backpressureConsecutiveFailures = 2,
                // 默认 200ms 节流：本用例要采样并发曲线，节流会把峰值抹平。
                progressIntervalMs = 0,
            )
        )
        val out = File.createTempFile("bp-out", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(
                DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out, knownSize = SIZE.toLong())
            )
            // 等待期间持续采样引擎上报的并发数
            var peakReported = 0
            val poller = launch {
                while (true) {
                    val c = client.progress.value[id]?.activeConnections ?: 0
                    if (c > peakReported) peakReported = c
                    delay(15)
                }
            }
            val r = client.await(id)
            poller.cancel()
            assertTrue(r.isSuccess, "背压不该让任务失败：${r.exceptionOrNull()?.message}")
            assertEquals(SIZE.toLong(), out.length(), "文件必须完整")
            println(
                "[backpressure] adaptive=$adaptive 被拒=${srv.rejected.get()} " +
                    "服务端峰值并发=${srv.peakConcurrent.get()} 引擎上报峰值=$peakReported"
            )
            assertTrue(
                peakReported >= 4,
                "恢复后并发应回到较高档位（实测峰值 $peakReported）—— " +
                    "若为 1 或 2，说明背压之后没有恢复路径",
            )
        } finally {
            runCatching { client.shutdown() }
            runCatching { srv.stop() }
            runCatching { workDir.deleteRecursively() }
            runCatching { out.delete() }
        }
    }

    @Test
    fun `concurrency recovers after backpressure with adaptive on`() = run(adaptive = true)

    @Test
    fun `concurrency recovers after backpressure with adaptive off`() = run(adaptive = false)
}

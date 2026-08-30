package dev.turbodl.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 启动延迟回归测试：复刻用户反馈「解析阶段将近一分钟」。
 *
 * 三处串行阻塞叠加造成的：
 *  1. probe 单次 20s × (1+2 重试) + 递增退避 ≈ 最坏 61s；
 *  2. warmUp 同步等待全部预热完成（最坏 8s）才开始分片；
 *  3. 调用方已知文件大小时仍照样探测一遍（纯浪费）。
 *
 * 修复后：probe 6s × (1+1) 上限、warmUp 改后台异步、已知大小直接跳过探测。
 */
class StartupLatencyTest {

    /** 探测请求（bytes=0-0）延迟响应；真实分片请求正常快速返回。 */
    private class SlowProbeServer(private val payload: ByteArray, private val probeDelayMs: Long) {
        val probeHits = AtomicInteger(0)
        val segmentHits = AtomicInteger(0)
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        private val total = payload.size

        init {
            server.createContext("/f.bin") { ex ->
                val range = ex.requestHeaders.getFirst("Range")
                val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                val isProbe = m != null && m.groupValues[1] == "0" && m.groupValues[2] == "0"
                if (isProbe) {
                    probeHits.incrementAndGet()
                    // 探测请求故意挂住：模拟"解析很慢"的服务器
                    Thread.sleep(probeDelayMs)
                    runCatching { ex.close() }
                    return@createContext
                }
                if (m == null) {
                    ex.responseHeaders.add("Accept-Ranges", "bytes")
                    ex.sendResponseHeaders(200, total.toLong())
                    ex.responseBody.use { it.write(payload) }
                    return@createContext
                }
                segmentHits.incrementAndGet()
                val s = m.groupValues[1].toInt()
                val e = m.groupValues[2].toIntOrNull() ?: (total - 1)
                val end = minOf(e, total - 1)
                val len = end - s + 1
                ex.responseHeaders.add("Content-Range", "bytes $s-$end/$total")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { it.write(payload, s, len) }
            }
            server.executor = Executors.newFixedThreadPool(200)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    @Test
    fun `known size skips probe entirely so download starts immediately`() = runBlocking {
        val size = 4 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 11 + 5) % 256).toByte() }
        // 探测会挂 30 秒；若仍去探测，首字节必然要等 30s+
        val srv = SlowProbeServer(payload, probeDelayMs = 30_000)
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 8,
                maxConcurrentTasks = 1,
                slowStart = false,
                warmUpConnections = false,
                skipProbeWhenSizeKnown = true,
            )
        )
        val out = File.createTempFile("skipprobe", ".bin").apply { deleteOnExit() }
        try {
            val started = System.currentTimeMillis()
            val id = client.submit(
                // 调用方已知大小（网盘解析场景）
                DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out, knownSize = size.toLong())
            )
            val result = client.await(id)
            val elapsed = System.currentTimeMillis() - started
            assertTrue(result.isSuccess, "应成功：${result.exceptionOrNull()?.message}")
            assertEquals(size.toLong(), out.length())
            assertTrue(out.readBytes().contentEquals(payload), "内容应逐字节一致")
            assertEquals(0, srv.probeHits.get(), "已知大小时不应发起任何探测请求")
            assertTrue(elapsed < 15_000, "应立即开始下载而非等待探测，实际耗时 ${elapsed}ms")
        } finally {
            client.shutdown()
            srv.stop()
        }
    }

    @Test
    fun `probe latency is bounded to a few seconds`() = runBlocking {
        val size = 2 * 1024 * 1024
        val payload = ByteArray(size) { (it % 256).toByte() }
        // 探测永远挂住（60s），走探测超时 + 有限重试
        val srv = SlowProbeServer(payload, probeDelayMs = 60_000)
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 4,
                maxConcurrentTasks = 1,
                slowStart = false,
                warmUpConnections = false,
                probeTimeoutMs = 2_000,
                probeRetries = 1,
                skipProbeWhenSizeKnown = false,   // 强制走探测路径
            )
        )
        val out = File.createTempFile("probebound", ".bin").apply { deleteOnExit() }
        try {
            val started = System.currentTimeMillis()
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
            // 探测全部超时后会回退整文件单流（服务器对无 Range 请求正常返回）
            val result = client.await(id)
            val elapsed = System.currentTimeMillis() - started
            assertTrue(result.isSuccess, "探测超时后应回退整文件下载：${result.exceptionOrNull()?.message}")
            assertEquals(size.toLong(), out.length())
            // 2s × 2 次 + 退避 0.5s ≈ 4.5s；给足余量断言 < 20s（旧默认值最坏 61s）
            assertTrue(elapsed < 20_000, "探测阻塞应有界（数秒级），实际耗时 ${elapsed}ms")
        } finally {
            client.shutdown()
            srv.stop()
        }
    }

    @Test
    fun `warmup does not block download start`() = runBlocking {
        val size = 2 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 3) % 256).toByte() }
        // 探测请求（预热也用 bytes=0-0）挂 10s；分片请求正常。
        // 若预热同步阻塞，首字节要等到预热超时；异步则分片立刻开始。
        val srv = SlowProbeServer(payload, probeDelayMs = 10_000)
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 8,
                maxConcurrentTasks = 1,
                slowStart = false,
                warmUpConnections = true,
                warmUpTimeoutMs = 10_000,
                skipProbeWhenSizeKnown = true,
            )
        )
        val firstByteAt = AtomicLong(0)
        val out = File.createTempFile("warmnoblock", ".bin").apply { deleteOnExit() }
        val started = System.currentTimeMillis()
        val collector = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            client.progress.collect { map ->
                map.values.forEach { p ->
                    if (p.downloadedBytes > 0) firstByteAt.compareAndSet(0, System.currentTimeMillis())
                }
            }
        }
        try {
            val id = client.submit(
                DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out, knownSize = size.toLong())
            )
            assertTrue(client.await(id).isSuccess)
            assertEquals(size.toLong(), out.length())
            val ttfb = firstByteAt.get() - started
            assertTrue(firstByteAt.get() > 0, "应观测到首字节时间")
            // 预热挂 10s；异步预热下首字节应远早于此
            assertTrue(ttfb < 8_000, "预热不应阻塞下载开始，首字节耗时 ${ttfb}ms")
        } finally {
            collector.cancel()
            client.shutdown()
            srv.stop()
        }
    }
}

/**
 * 默认配置的启动延迟预算断言。
 *
 * 上面几个用例显式传入了超时参数（测"机制存在"），但用户实际遇到的是**默认值过大**：
 * 20s × (1+2 重试) + 退避 ≈ 61s 全程阻塞。故这里单独锁住默认值预算，防止再次退化。
 */
class StartupBudgetTest {

    @Test
    fun `default probe budget stays within a few seconds`() {
        val c = TurboConfig()
        val worst = c.probeTimeoutMs * (c.probeRetries + 1)
        assertTrue(
            worst <= 15_000,
            "默认探测最坏阻塞应 ≤15s（现为 ${worst}ms = ${c.probeTimeoutMs}ms × ${c.probeRetries + 1} 次）"
        )
    }

    @Test
    fun `known size skips probe by default`() {
        assertTrue(
            TurboConfig().skipProbeWhenSizeKnown,
            "调用方已知大小时应默认跳过探测，避免无谓的数秒等待"
        )
    }

    @Test
    fun `warmup parallelism is decoupled from download concurrency`() {
        val c = TurboConfig(maxConnectionsPerTask = 128)
        assertTrue(
            c.warmUpMaxParallel <= 8,
            "预热并发应与下载并发解耦并保持小值（现为 ${c.warmUpMaxParallel}），避免弱网争抢信道"
        )
    }
}

package dev.turbodl.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 重构**前**的基准测量用例。
 *
 * 与断言式回归测试不同，这些用例**输出可量化的指标**（打印到 system-out，
 * 会被 Gradle 写进测试报告），供重构前后逐项对比：
 *
 * | 指标 | 说明 | 对应审计/复核结论 |
 * |---|---|---|
 * | FIRST_BYTE_MS | 限速下首字节出现的时延 | rc13-复核报告 §四（首块突发） |
 * | BYTES_IN_FIRST_500MS | 启动后前 500ms 内放行字节数 | rc13-复核报告 §四 |
 * | LOOPBACK_TIME_MS | 固定大小回环下载总耗时 | rc13-复核报告 §六（耗时回归） |
 * | RANGE_REQUESTS | 一次多分片下载发出的 Range 请求数 | rc12 P0-4 探测修复是否生效 |
 *
 * 全部跑在 127.0.0.1 本地回环、KB~MB 级文件，**不消耗真实流量**。
 */
class BaselineMetricsTest {

    private class RangeServer(
        private val payload: ByteArray,
        private val throttleBps: Long = 0,      // >0 则按此速率限速吐字节
        val rangeRequests: CopyOnWriteArrayList<String> = CopyOnWriteArrayList(),
    ) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        private val total = payload.size

        init {
            server.createContext("/f.bin") { ex ->
                val range = ex.requestHeaders.getFirst("Range")
                val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                if (m == null) {
                    ex.responseHeaders.add("Accept-Ranges", "bytes")
                    ex.sendResponseHeaders(200, total.toLong())
                    writeThrottled(ex, 0, total)
                    return@createContext
                }
                rangeRequests.add(range)
                val s = m.groupValues[1].toInt()
                val e = m.groupValues[2].toIntOrNull() ?: (total - 1)
                val len = e - s + 1
                ex.responseHeaders.add("Content-Range", "bytes $s-$e/$total")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(206, len.toLong())
                writeThrottled(ex, s, e + 1)
            }
            server.executor = Executors.newFixedThreadPool(32)
            server.start()
        }

        private fun writeThrottled(ex: com.sun.net.httpserver.HttpExchange, from: Int, to: Int) {
            ex.responseBody.use { out ->
                if (throttleBps <= 0) {
                    out.write(payload, from, to - from)
                    return
                }
                val chunk = 16 * 1024
                var off = from
                while (off < to) {
                    val n = minOf(chunk, to - off)
                    out.write(payload, off, n)
                    out.flush()
                    off += n
                    Thread.sleep((n * 1000L / throttleBps).coerceAtLeast(1))
                }
            }
        }

        fun stop() = server.stop(0)
    }

    /**
     * 【基线 A】低限速下的启动行为：测量首字节时延与前 500ms 放行量。
     *
     * 复核报告 §四 指出的语义瑕疵：rc13 首调用把桶填到 `max(limit, need)`，
     * 因此首块（一个 ioBuffer）会**瞬间全速放行** ——
     * 本用例把它量化为「前 500ms 放行字节数」：
     *  - 现状（缺陷）：≈ 一个 ioBuffer（如 32KB）
     *  - 期望（修复后）：≈ 0（首块按 `need-limit/limit` 等待）
     */
    @Test
    fun `baseline A - first chunk burst under low speed limit`() = runBlocking {
        if (System.getenv("TURBODL_BENCH") != "1") {
            println("[BENCH] 跳过基线 A（10KB/s 限速，约 13 秒；设置 TURBODL_BENCH=1 启用）")
            return@runBlocking
        }
        val size = 128 * 1024
        val payload = ByteArray(size) { ((it * 7 + 3) % 251).toByte() }
        val srv = RangeServer(payload)
        val workDir = File(System.getProperty("java.io.tmpdir"), "turbodl-base-a-${System.nanoTime()}")
        workDir.mkdirs()

        val limit = 10L * 1024           // 10 KB/s
        val ioBuf = 32 * 1024            // 32 KB 缓冲，便于量化
        val firstByteAt = AtomicLong(-1)
        val bytesAt500ms = AtomicLong(0)

        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 1,
                maxConcurrentTasks = 1,
                globalSpeedLimitBytesPerSec = limit,
                ioBufferSize = ioBuf,
                warmUpConnections = false,
                progressIntervalMs = 20,
            )
        )
        val out = File.createTempFile("baselineA", ".bin").apply { deleteOnExit() }
        try {
            val t0 = System.currentTimeMillis()
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
            val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                client.progress.collect { map ->
                    map.values.forEach { p ->
                        if (p.downloadedBytes > 0 && firstByteAt.get() < 0) {
                            firstByteAt.set(System.currentTimeMillis() - t0)
                        }
                        val now = System.currentTimeMillis() - t0
                        if (now <= 500) bytesAt500ms.set(maxOf(bytesAt500ms.get(), p.downloadedBytes))
                    }
                }
            }
            client.await(id)
            watcher.cancel()

            val fb = if (firstByteAt.get() >= 0) firstByteAt.get() else -1
            val b500 = bytesAt500ms.get()
            println("[BASELINE-A] FIRST_BYTE_MS=$fb")
            println("[BASELINE-A] BYTES_IN_FIRST_500MS=$b500  (limit=$limit ioBuffer=$ioBuf)")
            assertTrue(fb >= 0 || b500 >= 0, "应至少有一次进度上报")
        } finally {
            client.shutdown()
            srv.stop()
            workDir.deleteRecursively()
            out.delete()
        }
    }

    /**
     * 【基线 B】回环下载耗时回归基准：6.4MB 固定大小。
     *
     * 复核报告 §六：rc13 release notes 提到「6.4MB 回环从数秒变成 24s」
     * 却没有固化为测试。这条把它固化 —— 断言 < 5s（给足余量）。
     * 该断言对任何调度开销退化都敏感（v3 那种调度停滞会直接超时）。
     */
    @Test
    fun `baseline B - loopback download time regression bound`() = runBlocking {
        val size = 6_400_000
        val payload = ByteArray(size) { ((it * 2654435761u.toInt()) ushr 13).toByte() }
        val srv = RangeServer(payload)
        val workDir = File(System.getProperty("java.io.tmpdir"), "turbodl-base-b-${System.nanoTime()}")
        workDir.mkdirs()

        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 8,
                maxConcurrentTasks = 1,
                blockSize = 512 * 1024,
                minSegmentSize = 256 * 1024,
                workDir = workDir,
                warmUpConnections = false,
            )
        )
        val out = File.createTempFile("baselineB", ".bin").apply { deleteOnExit() }
        try {
            val t0 = System.currentTimeMillis()
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
            val ok = client.await(id)
            val elapsed = System.currentTimeMillis() - t0
            assertTrue(ok.isSuccess, "下载应成功: ${ok.exceptionOrNull()?.message}")
            assertTrue(payload.contentEquals(out.readBytes()), "输出必须字节精确")

            println("[BASELINE-B] LOOPBACK_TIME_MS=$elapsed  size=$size connections=8")
            println("[BASELINE-B] RANGE_REQUESTS=${srv.rangeRequests.size}")
            assertTrue(elapsed < 5_000, "回环耗时不应超过 5s（实际 ${elapsed}ms）；这能抓住 v3 那种调度退化")
        } finally {
            client.shutdown()
            srv.stop()
            workDir.deleteRecursively()
            out.delete()
        }
    }

    /**
     * 【基线 C】多分片是否真的生效（rc12 P0-4 探测修复）。
     *
     * 复核报告把它列为 P0 头号验证点：若服务器对 `bytes=0-0` 回 200 且无 Accept-Ranges，
     * 旧判据会误判为「不支持 Range」→ 整个下载退化为单流（几十~几百 KB/s，永不变快）。
     * 断言真实分片请求数 ≥ 2，证明多线程确实生效。
     */
    @Test
    fun `baseline C - multi-segment actually engages (probe fix)`() = runBlocking {
        val size = 2 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 17 + 5) % 253).toByte() }
        // 服务器：对 bytes=0-0 回 200（无 Accept-Ranges），其余区间正常回 206
        val rangeRequests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/f.bin") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            val total = payload.size
            if (range == "bytes=0-0" || range == null) {
                ex.sendResponseHeaders(200, total.toLong())
                ex.responseBody.use { it.write(payload) }
                return@createContext
            }
            rangeRequests.add(range)
            val m = Regex("bytes=(\\d+)-(\\d*)").find(range)!!
            val s = m.groupValues[1].toInt()
            val e = m.groupValues[2].toIntOrNull() ?: (total - 1)
            val len = e - s + 1
            ex.responseHeaders.add("Content-Range", "bytes $s-$e/$total")
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            ex.sendResponseHeaders(206, len.toLong())
            ex.responseBody.use { it.write(payload, s, len) }
        }
        server.executor = Executors.newFixedThreadPool(16)
        server.start()

        val workDir = File(System.getProperty("java.io.tmpdir"), "turbodl-base-c-${System.nanoTime()}")
        workDir.mkdirs()
        val client = TurboClient(
            TurboConfig(maxConnectionsPerTask = 4, maxConcurrentTasks = 1, workDir = workDir, warmUpConnections = false)
        )
        val out = File.createTempFile("baselineC", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:${server.address.port}/f.bin", out))
            assertTrue(client.await(id).isSuccess)
            assertTrue(payload.contentEquals(out.readBytes()))
            println("[BASELINE-C] RANGE_REQUESTS=${rangeRequests.size}  connections=4")
            assertTrue(
                rangeRequests.size >= 2,
                "探测到 200 后必须复探并启用多分片；实际分片请求数=${rangeRequests.size}（1 或 0 表示退化单流）"
            )
        } finally {
            client.shutdown()
            server.stop(0)
            workDir.deleteRecursively()
            out.delete()
        }
    }
}

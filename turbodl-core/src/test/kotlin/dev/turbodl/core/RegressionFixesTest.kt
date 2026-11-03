package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.File
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 本轮缺陷修复的端到端回归测试。
 *
 * 所有网络用例只访问 `127.0.0.1`；每个用例都有墙钟超时兜底。
 */
class RegressionFixesTest {

    private data class ByteRange(val start: Int, val endInclusive: Int) {
        val length: Int get() = endInclusive - start + 1
    }

    private class LocalServer(
        threads: Int = 64,
        handler: (HttpExchange) -> Unit,
    ) : AutoCloseable {
        private val executor: ExecutorService = Executors.newFixedThreadPool(threads)
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        val url: String
            get() = "http://127.0.0.1:${server.address.port}/f.bin"

        init {
            server.createContext("/f.bin") { exchange ->
                try {
                    handler(exchange)
                } catch (_: java.io.IOException) {
                    // 客户端取消或超时后关闭连接是测试里的正常路径。
                } finally {
                    runCatching { exchange.close() }
                }
            }
            server.executor = executor
            server.start()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun tempDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "turbodl-regression-$tag-${System.nanoTime()}")
            .apply { mkdirs() }

    private fun rangeFor(exchange: HttpExchange, total: Int): ByteRange? {
        val raw = exchange.requestHeaders.getFirst("Range") ?: return null
        val match = Regex("""bytes=(\d+)-(\d*)""").find(raw) ?: return null
        val start = match.groupValues[1].toInt()
        if (start >= total) return ByteRange(start, total - 1)
        val requestedEnd = match.groupValues[2].toIntOrNull() ?: (total - 1)
        return ByteRange(start, minOf(requestedEnd, total - 1))
    }

    private fun sendRange(exchange: HttpExchange, payload: ByteArray, range: ByteRange) {
        exchange.responseHeaders.add("Content-Type", "application/octet-stream")
        exchange.responseHeaders.add("Content-Range", "bytes ${range.start}-${range.endInclusive}/${payload.size}")
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        exchange.sendResponseHeaders(206, range.length.toLong())
        exchange.responseBody.use { it.write(payload, range.start, range.length) }
    }

    private fun assertCompleteGrid(pieces: List<SegmentStore.Piece>, total: Long, context: String) {
        assertTrue(pieces.isNotEmpty(), "$context：分片列表不能为空")
        assertEquals(0L, pieces.first().start, "$context：首片必须从 0 开始")
        assertEquals(total - 1, pieces.last().end, "$context：末片必须覆盖到文件末尾")
        assertEquals(total, pieces.sumOf { it.length }, "$context：分片长度总和必须等于文件大小")
        var expectedStart = 0L
        for (piece in pieces) {
            assertEquals(expectedStart, piece.start, "$context：分片之间出现了空洞或重叠")
            assertTrue(piece.end >= piece.start, "$context：分片结束位置早于开始位置")
            assertTrue(
                piece.written in 0..piece.length,
                "$context：written=${piece.written} 超出分片长度 ${piece.length}",
            )
            expectedStart = piece.end + 1
        }
        assertEquals(total, expectedStart, "$context：网格没有完整覆盖 [0, $total)")
    }

    // ------------------------------------------------------------------
    // 1. 206 缺少 Content-Range
    // ------------------------------------------------------------------

    @Test
    fun `206 without Content-Range never silently accepts mislabeled bytes`() = runBlocking {
        withTimeout(20_000) {
            val payload = ByteArray(2 * 1024 * 1024) { ((it * 31 + 7) % 251).toByte() }
            val rangeResponses = AtomicInteger(0)
            val wholeFileRequests = AtomicInteger(0)

            LocalServer { exchange ->
                val range = rangeFor(exchange, payload.size)
                if (range == null) {
                    wholeFileRequests.incrementAndGet()
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { it.write(payload) }
                    return@LocalServer
                }

                rangeResponses.incrementAndGet()
                // 故意不发送 Content-Range。body 长度与请求区间完全一致，
                // 这正旧实现会误当成“可信区间”并静默写入的场景。
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.sendResponseHeaders(206, range.length.toLong())
                exchange.responseBody.use { it.write(payload, range.start, range.length) }
            }.use { server ->
                val workDir = tempDir("missing-content-range")
                val output = File(workDir, "out.bin")
                val client = TurboClient(
                    TurboConfig(
                        maxConnectionsPerTask = 4,
                        maxConcurrentTasks = 1,
                        blockSize = 256 * 1024,
                        minSegmentSize = 64 * 1024,
                        warmUpConnections = false,
                        slowStart = false,
                        workDir = workDir,
                    )
                )
                try {
                    val id = client.submit(
                        DownloadRequest(
                            url = server.url,
                            destination = output,
                            knownSize = payload.size.toLong(),
                        )
                    )
                    val result = client.await(id)
                    assertTrue(
                        result.isSuccess,
                        "缺少 Content-Range 的 206 应被拒绝并经整文件回退完成；实际失败：${result.exceptionOrNull()?.message}",
                    )
                    assertTrue(
                        payload.contentEquals(output.readBytes()),
                        "最终内容不一致：说明可疑 206 的字节被写进了目标文件，而不是走回退/重试",
                    )
                    assertTrue(
                        wholeFileRequests.get() > 0,
                        "服务器没有收到无 Range 的整文件 GET：说明引擎静默接受了缺 Content-Range 的 206",
                    )
                    assertTrue(
                        rangeResponses.get() >= 3,
                        "只收到 ${rangeResponses.get()} 个 Range 请求：应当先拒绝坏 206，累计容错后触发整文件回退",
                    )
                } finally {
                    client.shutdown()
                    workDir.deleteRecursively()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 2. 普通 5xx 不是限流
    // ------------------------------------------------------------------

    @Test
    fun `plain HTTP 500 does not consume throttle backpressure budget`() = runBlocking {
        withTimeout(20_000) {
            val payload = ByteArray(1024 * 1024) { ((it * 13 + 5) % 241).toByte() }
            val rangeRequests = AtomicInteger(0)

            LocalServer { exchange ->
                val range = rangeFor(exchange, payload.size)
                if (range == null) {
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { it.write(payload) }
                    return@LocalServer
                }
                rangeRequests.incrementAndGet()
                exchange.sendResponseHeaders(500, -1)
            }.use { server ->
                val workDir = tempDir("plain-500")
                val output = File(workDir, "out.bin")
                val client = TurboClient(
                    TurboConfig(
                        maxConnectionsPerTask = 4,
                        maxConcurrentTasks = 1,
                        maxRetries = 0,
                        backpressureConsecutiveFailures = 1,
                        blockSize = 128 * 1024,
                        minSegmentSize = 64 * 1024,
                        warmUpConnections = false,
                        slowStart = false,
                        adaptiveConcurrency = false,
                        workDir = workDir,
                    )
                )
                try {
                    val id = client.submit(
                        DownloadRequest(
                            url = server.url,
                            destination = output,
                            knownSize = payload.size.toLong(),
                        )
                    )
                    val result = client.await(id)
                    assertTrue(result.isFailure, "服务器对每个分片都回 500，任务必须失败")
                    val reason = result.exceptionOrNull()?.message.orEmpty()
                    assertFalse(
                        reason.contains("持续被限流"),
                        "普通 HTTP 500 被错误归为 429/503 背压：$reason",
                    )
                    assertTrue(
                        reason.contains("HTTP 500"),
                        "失败原因应保留普通 5xx 状态码，便于区分服务器错误与限流；实际：$reason",
                    )
                    assertEquals(
                        TaskState.FAILED,
                        client.progress.value[id]?.state,
                        "失败事件发出后，任务进度也必须落到 FAILED",
                    )
                    assertTrue(rangeRequests.get() > 0, "测试没有实际触发 Range 500 路径")
                } finally {
                    client.shutdown()
                    workDir.deleteRecursively()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 3. 背压下限可以降到 1 路
    // ------------------------------------------------------------------

    @Test
    fun `backpressure can reduce concurrency to one admitted request`() = runBlocking {
        withTimeout(25_000) {
            val payload = ByteArray(2 * 1024 * 1024) { ((it * 47 + 11) % 253).toByte() }
            val activeHandlers = AtomicInteger(0)
            val peakAdmitted = AtomicInteger(0)
            val rejected = AtomicInteger(0)
            val successfulRanges = AtomicInteger(0)

            LocalServer(threads = 32) { exchange ->
                val range = rangeFor(exchange, payload.size)
                if (range == null) {
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { it.write(payload) }
                    return@LocalServer
                }

                val active = activeHandlers.incrementAndGet()
                try {
                    if (active > 1) {
                        rejected.incrementAndGet()
                        exchange.sendResponseHeaders(503, -1)
                        return@LocalServer
                    }

                    peakAdmitted.updateAndGet { maxOf(it, active) }
                    exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                    exchange.responseHeaders.add(
                        "Content-Range",
                        "bytes ${range.start}-${range.endInclusive}/${payload.size}",
                    )
                    exchange.responseHeaders.add("Accept-Ranges", "bytes")
                    exchange.sendResponseHeaders(206, range.length.toLong())
                    exchange.responseBody.use { output ->
                        var offset = range.start
                        val endExclusive = range.endInclusive + 1
                        while (offset < endExclusive) {
                            val count = minOf(16 * 1024, endExclusive - offset)
                            output.write(payload, offset, count)
                            output.flush()
                            offset += count
                            // 给并发请求一个重叠窗口，让服务端的“只允许 1 路”真实可观测。
                            Thread.sleep(15)
                        }
                    }
                    successfulRanges.incrementAndGet()
                } finally {
                    activeHandlers.decrementAndGet()
                }
            }.use { server ->
                val workDir = tempDir("backpressure-one")
                val output = File(workDir, "out.bin")
                val client = TurboClient(
                    TurboConfig(
                        maxConnectionsPerTask = 8,
                        maxConcurrentTasks = 1,
                        maxRetries = 5,
                        backpressureConsecutiveFailures = 1,
                        blockSize = 128 * 1024,
                        minSegmentSize = 64 * 1024,
                        segmentsPerConnection = 2,
                        warmUpConnections = false,
                        slowStart = false,
                        adaptiveConcurrency = false,
                        workDir = workDir,
                    )
                )
                try {
                    val id = client.submit(
                        DownloadRequest(
                            url = server.url,
                            destination = output,
                            knownSize = payload.size.toLong(),
                        )
                    )
                    val result = client.await(id)
                    assertTrue(
                        result.isSuccess,
                        "服务器只允许 1 路时任务仍应成功；失败说明背压没有降到 1：${result.exceptionOrNull()?.message}",
                    )
                    assertTrue(
                        payload.contentEquals(output.readBytes()),
                        "背压收敛后文件内容仍必须逐字节正确",
                    )
                    assertTrue(rejected.get() > 0, "测试没有触发 503，无法证明并发曾高于 1")
                    assertTrue(successfulRanges.get() > 0, "测试没有成功放行任何 Range 请求")
                    assertTrue(
                        peakAdmitted.get() <= 2,
                        "服务端实际放行的峰值并发为 ${peakAdmitted.get()}，说明并发没有收敛到 1 路附近",
                    )
                } finally {
                    client.shutdown()
                    workDir.deleteRecursively()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 4. 整文件回退必须截断旧尾部
    // ------------------------------------------------------------------

    @Test
    fun `whole-file fallback truncates an older longer destination`() = runBlocking {
        withTimeout(20_000) {
            val payload = ByteArray(1024 * 1024) { ((it * 19 + 3) % 239).toByte() }
            val requests = AtomicInteger(0)

            LocalServer { exchange ->
                requests.incrementAndGet()
                // 明确不支持 Range：不返回 Accept-Ranges，且忽略任何 Range 请求。
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }.use { server ->
                val workDir = tempDir("whole-truncate")
                val output = File(workDir, "out.bin")
                output.writeBytes(ByteArray(4 * 1024 * 1024) { 0x5A })
                assertEquals(4L * 1024 * 1024, output.length(), "前置条件：旧目标文件必须是 4MB")

                val client = TurboClient(
                    TurboConfig(
                        maxConnectionsPerTask = 4,
                        maxConcurrentTasks = 1,
                        warmUpConnections = false,
                        slowStart = false,
                        workDir = workDir,
                    )
                )
                try {
                    val id = client.submit(DownloadRequest(server.url, output))
                    val result = client.await(id)
                    assertTrue(
                        result.isSuccess,
                        "不支持 Range 时应走整文件回退；实际失败：${result.exceptionOrNull()?.message}",
                    )
                    assertEquals(
                        payload.size.toLong(),
                        output.length(),
                        "整文件回退后仍保留旧文件尾部：目标长度应为 ${payload.size}，实际 ${output.length()}",
                    )
                    assertTrue(
                        payload.contentEquals(output.readBytes()),
                        "整文件回退后内容不等于服务器内容：旧尾部或旧前缀仍被保留",
                    )
                    assertTrue(requests.get() > 0, "测试没有发出请求")
                } finally {
                    client.shutdown()
                    workDir.deleteRecursively()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 5. SegmentStore.persist 并发写
    // ------------------------------------------------------------------

    @Test
    fun `concurrent SegmentStore snapshots remain parseable and coherent`() = runBlocking {
        withTimeout(120_000) {
            val workDir = tempDir("persist-race")
            val total = 1024L * 1024
            val destination = File(workDir, "out.bin")
            val stateFile = File(workDir, "progress.tdlp")

            try {
                val writer = SegmentStore(destination, stateFile, total)
                writer.open()
                try {
                    writer.ensureLength()
                    val dispatcher = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
                    try {
                        coroutineScope {
                            (0 until 8).map { worker ->
                                async(dispatcher) {
                                    repeat(200) { iteration ->
                                        // 每个线程/轮次使用不同但完整覆盖 [0,total) 的网格，
                                        // 最终落盘快照必须是其中某一个完整版本，不能是多版本拼接。
                                        val step = 48L * 1024 +
                                            worker * 4096L +
                                            (iteration % 5) * 4096L
                                        writer.persist(writer.buildGrid(step))
                                        yield()
                                    }
                                }
                            }.awaitAll()
                        }
                    } finally {
                        dispatcher.close()
                    }
                } finally {
                    writer.close()
                }

                assertTrue(stateFile.isFile, "并发 persist 后主侧车文件应存在")
                val verifier = SegmentStore(destination, stateFile, total)
                verifier.open()
                try {
                    val peeked = assertNotNull(
                        verifier.peek(total),
                        "1600 次并发 persist 后侧车无法解析为有效快照",
                    )
                    assertCompleteGrid(peeked, total, "peek")

                    val loaded = verifier.loadOrCreate(128 * 1024)
                    assertCompleteGrid(loaded, total, "loadOrCreate")
                } finally {
                    verifier.close()
                }
            } finally {
                workDir.deleteRecursively()
            }
        }
    }

    // ------------------------------------------------------------------
    // 6. 不完整网格必须失效
    // ------------------------------------------------------------------

    @Test
    fun `incomplete sidecar grid is rejected on reopen`() = runBlocking {
        withTimeout(15_000) {
            val workDir = tempDir("incomplete-grid")
            val total = 1024L * 1024
            val destination = File(workDir, "out.bin")
            val stateFile = File(workDir, "progress.tdlp")

            try {
                val writer = SegmentStore(destination, stateFile, total)
                writer.open()
                try {
                    writer.ensureLength()
                    val firstHalf = writer.buildGrid(128 * 1024).take(4)
                    writer.persist(firstHalf)
                } finally {
                    writer.close()
                }
                assertFalse(
                    stateFile.exists(),
                    "persist 必须拒绝不完整网格，不能把它写成新的续传点",
                )

                // 读取端仍需防御旧版本或其他写入者留下的残缺侧车。
                DataOutputStream(FileOutputStream(stateFile)).use { out ->
                    out.write(byteArrayOf('T'.code.toByte(), 'D'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte()))
                    out.writeInt(1)
                    out.writeLong(total)
                    out.writeInt(4)
                    repeat(4) { index ->
                        val start = index.toLong() * 128 * 1024
                        out.writeLong(start)
                        out.writeLong(start + 128 * 1024 - 1)
                        out.writeLong(128 * 1024L)
                    }
                }
                assertTrue(stateFile.isFile, "前置条件：残缺侧车文件应已写出")

                val reopened = SegmentStore(destination, stateFile, total)
                reopened.open()
                try {
                    assertNull(
                        reopened.peek(total),
                        "只覆盖前半段的侧车被当成有效续传点：后续下载会永远跳过缺失的后半段",
                    )
                    val fresh = reopened.loadOrCreate(128 * 1024)
                    assertCompleteGrid(fresh, total, "不完整侧车重建")
                } finally {
                    reopened.close()
                }
            } finally {
                workDir.deleteRecursively()
            }
        }
    }

    // ------------------------------------------------------------------
    // 7. 全局限速器跨任务共享
    // ------------------------------------------------------------------

    @Test
    fun `global speed limit is shared by two concurrent tasks`() = runBlocking {
        withTimeout(30_000) {
            val taskSize = 2 * 1024 * 1024
            val payload = ByteArray(taskSize) { ((it * 29 + 17) % 251).toByte() }

            LocalServer(threads = 16) { exchange ->
                val range = rangeFor(exchange, payload.size)
                if (range == null) {
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { it.write(payload) }
                } else {
                    sendRange(exchange, payload, range)
                }
            }.use { server ->
                val workDir = tempDir("shared-global-limit")
                val limit = 512L * 1024
                val client = TurboClient(
                    TurboConfig(
                        maxConnectionsPerTask = 1,
                        maxConcurrentTasks = 2,
                        globalSpeedLimitBytesPerSec = limit,
                        blockSize = taskSize.toLong(),
                        minSegmentSize = 64 * 1024,
                        segmentsPerConnection = 1,
                        warmUpConnections = false,
                        slowStart = false,
                        adaptiveConcurrency = false,
                        ioBufferSize = 32 * 1024,
                        ioBufferTotalBudgetBytes = 32 * 1024,
                        workDir = workDir,
                    )
                )
                val outputA = File(workDir, "a.bin")
                val outputB = File(workDir, "b.bin")
                try {
                    val startedAt = System.nanoTime()
                    val idA = client.submit(
                        DownloadRequest(server.url, outputA, knownSize = taskSize.toLong())
                    )
                    val idB = client.submit(
                        DownloadRequest(server.url, outputB, knownSize = taskSize.toLong())
                    )
                    val results = coroutineScope {
                        listOf(
                            async { client.await(idA) },
                            async { client.await(idB) },
                        ).awaitAll()
                    }
                    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

                    results.forEachIndexed { index, result ->
                        assertTrue(
                            result.isSuccess,
                            "第 ${index + 1} 个任务失败：${result.exceptionOrNull()?.message}",
                        )
                    }
                    assertTrue(payload.contentEquals(outputA.readBytes()), "任务 A 内容不一致")
                    assertTrue(payload.contentEquals(outputB.readBytes()), "任务 B 内容不一致")

                    val expectedMs = (taskSize.toLong() * 2 * 1000) / limit
                    val minimumMs = expectedMs * 80 / 100
                    assertTrue(
                        elapsedMs >= minimumMs,
                        "两个任务总耗时 ${elapsedMs}ms，低于共享 ${limit}B/s 单桶的理论下限 80% (${minimumMs}ms)。" +
                            "耗时接近一半说明每个任务各自创建了限速桶，而不是共享全局限速器。",
                    )
                } finally {
                    client.shutdown()
                    workDir.deleteRecursively()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 8. SpeedLimiter 运行中改限速
    // ------------------------------------------------------------------

    @Test
    fun `SpeedLimiter observes runtime limit change while a caller is waiting`() = runBlocking {
        withTimeout(3_000) {
            val limit = AtomicLong(1024)
            val limiter = SpeedLimiter { limit.get() }

            // 先消耗首次懒初始化灌满的突发额度；否则 1MB 请求会立即通过，
            // 测试就无法覆盖“已经进入等待后修改限速”的路径。
            limiter.awaitAllow(1024)

            val waiter = launch(Dispatchers.Default) {
                limiter.awaitAllow(1024L * 1024)
            }
            delay(100)
            limit.set(0)

            val returned = withTimeoutOrNull(1_000) {
                waiter.join()
                true
            } ?: false
            assertTrue(
                returned,
                "等待中的 awaitAllow 在限速改为 0 后 1 秒内仍未返回：说明它仍按旧速率睡满等待周期，运行时修改限速没有即时生效",
            )
        }
    }
}

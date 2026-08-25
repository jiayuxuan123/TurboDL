package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用内嵌 HTTP 服务器验证下载引擎的字节级正确性与各回退路径。
 * 每个测试起一个可控行为的服务器（支持 Range / 忽略 Range / 偶发 503 / 不支持 Range）。
 */
class TurboClientTest {

    private lateinit var server: HttpServer
    private var port = 0
    private lateinit var payload: ByteArray
    private lateinit var tmpDir: File

    // 服务器行为开关
    @Volatile private var supportRange = true
    @Volatile private var ignoreRange = false      // 返回 200 整文件（模拟篡改 Range）
    @Volatile private var failFirstN = 0           // 前 N 个 Range 请求返回 503
    private val reqCount = java.util.concurrent.atomic.AtomicInteger(0)

    @BeforeTest
    fun setup() {
        // 6.4MB 伪随机数据（可被分成多段）
        payload = ByteArray(6_400_000) { (it * 2654435761u.toInt() ushr 13).toByte() }
        tmpDir = File(System.getProperty("java.io.tmpdir"), "turbodl-test-${System.nanoTime()}").apply { mkdirs() }

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { ex -> handle(ex) }
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(16)
        server.start()
        port = server.address.port
    }

    @AfterTest
    fun teardown() {
        server.stop(0)
        tmpDir.deleteRecursively()
    }

    private fun handle(ex: HttpExchange) {
        val range = ex.requestHeaders.getFirst("Range")
        // 探测请求（bytes=0-0）
        if (range == "bytes=0-0") {
            if (supportRange && !ignoreRange) {
                ex.responseHeaders.add("Content-Range", "bytes 0-0/${payload.size}")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(206, 1)
                ex.responseBody.use { it.write(payload, 0, 1) }
            } else {
                ex.responseHeaders.add("Content-Length", payload.size.toString())
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.use { it.write(payload) }
            }
            return
        }

        if (range != null && supportRange && !ignoreRange) {
            val n = reqCount.incrementAndGet()
            if (n <= failFirstN) {
                ex.sendResponseHeaders(503, -1)
                ex.close()
                return
            }
            val m = Regex("bytes=(\\d+)-(\\d*)").find(range)!!
            val start = m.groupValues[1].toLong()
            val end = m.groupValues[2].toLongOrNull() ?: (payload.size - 1L)
            val len = (end - start + 1).toInt()
            ex.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
            ex.sendResponseHeaders(206, len.toLong())
            ex.responseBody.use { it.write(payload, start.toInt(), len) }
            return
        }

        // 不支持 Range 或忽略 Range：始终返回 200 整文件
        ex.responseHeaders.add("Content-Length", payload.size.toString())
        ex.sendResponseHeaders(200, payload.size.toLong())
        ex.responseBody.use { it.write(payload) }
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun url() = "http://127.0.0.1:$port/file"

    @Test
    fun multiThreadRangeDownloadIsByteExact() = runBlocking {
        supportRange = true; ignoreRange = false; failFirstN = 0
        val client = TurboClient(TurboConfig(maxConnectionsPerTask = 8, blockSize = 512 * 1024, minSegmentSize = 256 * 1024))
        val out = File(tmpDir, "a.bin")
        val id = client.submit(DownloadRequest(url(), out))
        val result = client.await(id)
        client.shutdown()
        assertTrue(result.isSuccess, "下载应成功: ${result.exceptionOrNull()?.message}")
        assertEquals(payload.size.toLong(), out.length(), "文件大小应一致")
        assertEquals(sha256(payload), sha256(out.readBytes()), "内容应字节级一致")
    }

    @Test
    fun dynamicSegmentationByteExact() = runBlocking {
        supportRange = true; ignoreRange = false; failFirstN = 0
        val client = TurboClient(
            TurboConfig(maxConnectionsPerTask = 16, blockSize = 1024 * 1024, dynamicSegmentation = true, minSegmentSize = 256 * 1024)
        )
        val out = File(tmpDir, "dyn.bin")
        val result = client.await(client.submit(DownloadRequest(url(), out)))
        client.shutdown()
        assertTrue(result.isSuccess, "动态分段下载应成功: ${result.exceptionOrNull()?.message}")
        assertEquals(sha256(payload), sha256(out.readBytes()))
    }

    @Test
    fun fallbackToWholeWhenRangeUnsupported() = runBlocking {
        supportRange = false; ignoreRange = false; failFirstN = 0
        val client = TurboClient(TurboConfig(maxConnectionsPerTask = 8))
        val out = File(tmpDir, "whole.bin")
        val result = client.await(client.submit(DownloadRequest(url(), out)))
        client.shutdown()
        assertTrue(result.isSuccess, "不支持 Range 应回退整文件: ${result.exceptionOrNull()?.message}")
        assertEquals(sha256(payload), sha256(out.readBytes()))
    }

    @Test
    fun fallbackWhenServerTampersRangeReturningWholeFile() = runBlocking {
        // 探测说支持，但实际分段请求都被返回整文件（篡改 Range）→ 应检测并回退整文件
        supportRange = true; ignoreRange = true; failFirstN = 0
        val client = TurboClient(TurboConfig(maxConnectionsPerTask = 8))
        val out = File(tmpDir, "tamper.bin")
        val result = client.await(client.submit(DownloadRequest(url(), out, knownSize = payload.size.toLong())))
        client.shutdown()
        assertTrue(result.isSuccess, "Range 被篡改应回退整文件: ${result.exceptionOrNull()?.message}")
        assertEquals(sha256(payload), sha256(out.readBytes()))
    }

    @Test
    fun retriesTransient503WithoutFailingWholeTask() = runBlocking {
        // 前 5 个分片请求 503，应仅重试这些分片、整任务仍成功
        supportRange = true; ignoreRange = false; failFirstN = 5
        val client = TurboClient(TurboConfig(maxConnectionsPerTask = 8, maxRetries = 10, blockSize = 512 * 1024, minSegmentSize = 256 * 1024))
        val out = File(tmpDir, "retry.bin")
        val result = client.await(client.submit(DownloadRequest(url(), out)))
        client.shutdown()
        assertTrue(result.isSuccess, "瞬时 503 应重试成功: ${result.exceptionOrNull()?.message}")
        assertEquals(sha256(payload), sha256(out.readBytes()))
    }

    @Test
    fun globalSpeedLimitIsRoughlyRespected() = runBlocking {
        supportRange = true; ignoreRange = false; failFirstN = 0
        val limit = 2_000_000L // 2MB/s，6.4MB 至少约 3 秒
        val client = TurboClient(TurboConfig(maxConnectionsPerTask = 8, globalSpeedLimitBytesPerSec = limit))
        val out = File(tmpDir, "limited.bin")
        val t0 = System.currentTimeMillis()
        val result = client.await(client.submit(DownloadRequest(url(), out)))
        val elapsed = System.currentTimeMillis() - t0
        client.shutdown()
        assertTrue(result.isSuccess)
        assertEquals(sha256(payload), sha256(out.readBytes()))
        // 6.4MB / 2MB/s ≈ 3.2s；给宽松下限 2s（避免 CI 抖动误判）
        assertTrue(elapsed >= 2000, "限速应使耗时不低于 ~2s，实际 ${elapsed}ms")
    }
}

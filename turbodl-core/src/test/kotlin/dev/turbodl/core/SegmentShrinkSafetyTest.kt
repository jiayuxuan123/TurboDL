package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 收尾托管缩短在飞分片的 end 后，旧连接必须立刻停止写入尾部。
 *
 * 旧实现只在响应开始时读取一次 endProvider；新连接接手尾部后，旧连接仍可能
 * 把剩余 body 写进同一区间。内容相同时会掩盖问题，内容变化或 Range 异常时
 * 就会变成交错写。
 */
class SegmentShrinkSafetyTest {

    @Test
    fun `shrinking end stops the old writer at the handoff boundary`() = runBlocking {
        val total = 1024 * 1024
        val handoff = 768 * 1024
        val payload = ByteArray(total) { ((it * 19 + 7) % 256).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/f.bin") { ex: HttpExchange ->
            val range = ex.requestHeaders.getFirst("Range")
            val match = range?.let { Regex("bytes=(\\d+)-(\\d+)").find(it) }
            val start = match?.groupValues?.get(1)?.toInt() ?: 0
            val end = match?.groupValues?.get(2)?.toInt()?.coerceAtMost(total - 1) ?: (total - 1)
            val length = end - start + 1
            ex.responseHeaders.add("Content-Type", "application/octet-stream")
            ex.responseHeaders.add("Content-Range", "bytes $start-$end/$total")
            ex.sendResponseHeaders(206, length.toLong())
            runCatching {
                ex.responseBody.use { out ->
                    var pos = start
                    while (pos <= end) {
                        val n = minOf(64 * 1024, end - pos + 1)
                        out.write(payload, pos, n)
                        pos += n
                        Thread.sleep(20)
                    }
                }
            }
        }
        server.executor = Executors.newFixedThreadPool(4)
        server.start()

        val dir = File(System.getProperty("java.io.tmpdir"), "shrink-${System.nanoTime()}")
            .apply { mkdirs(); deleteOnExit() }
        val dest = File(dir, "out.bin")
        val store = SegmentStore(dest, File(dir, "progress.tdlp"), total.toLong())
        store.open()
        store.ensureLength()
        val end = AtomicLong(total.toLong())
        val writeLock = Any()
        val maxWrittenEnd = AtomicLong(0)
        val config = TurboConfig(
            maxConnectionsPerTask = 1,
            maxConcurrentTasks = 1,
            warmUpConnections = false,
            slowStart = false,
            adaptiveConcurrency = false,
            readTimeoutMs = 10_000,
        )
        val downloader = SegmentDownloader(
            clientProvider = { HttpClientFactory.build(config, HttpClientFactory.ProtocolPreference.H1_ONLY) },
            streamClientProvider = null,
        )
        var failure: String? = null
        try {
            val shrink = launch(Dispatchers.IO) {
                delay(180)
                synchronized(writeLock) {
                    end.set((handoff - 1).toLong())
                }
            }
            val result = downloader.downloadSegmentInto(
                taskId = 1,
                url = "http://127.0.0.1:${server.address.port}/f.bin",
                pieceStart = 0,
                endProvider = { end.get() },
                alreadyWritten = 0,
                writeAt = { pos, buf, off, len ->
                    val n = store.writeAt(pos, buf, off, len)
                    maxWrittenEnd.updateAndGet { maxOf(it, pos + n) }
                    n
                },
                headers = emptyMap(),
                onFailure = { failure = it },
                onProgress = { _, _ -> },
                buffer = ByteArray(64 * 1024),
                writeLock = writeLock,
            )
            shrink.join()
            assertEquals(SegmentResult.OK, result, failure ?: "没有失败原因")
            assertTrue(
                maxWrittenEnd.get() <= handoff,
                "旧连接写到了 ${maxWrittenEnd.get()}，让出边界是 $handoff",
            )
            assertTrue(
                store.readAt(handoff.toLong(), 4096).all { it == 0.toByte() },
                "让出的尾部不应被旧连接写入",
            )
        } finally {
            store.close()
            server.stop(0)
            dir.deleteRecursively()
        }
    }
}

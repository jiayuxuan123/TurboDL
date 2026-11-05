package dev.turbodl.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 多镜像来源池的端到端验证。
 *
 * 这两条测试锁住的是：
 *  1. 慢镜像不会分走大量分片（按实测速度择优，而不是轮询）；
 *  2. "同名字但内容/大小不同的镜像"必须被识别并剔除，不能写进目标文件。
 */
class MirrorPoolTest {

    @Test
    fun `per source cap is strict until a request completes`() {
        val pool = MirrorPool(
            urls = listOf("http://mirror-a/file", "http://mirror-b/file"),
            perMirrorInFlightCap = 1,
        )
        val first = pool.pickWithLimit()
        val second = pool.pickWithLimit()
        assertTrue(first != null && second != null)
        assertTrue(first != second, "两个来源应各拿到一次，而不是超发同一个来源")
        assertNull(pool.pickWithLimit(), "两个来源都达到上限时应让调度器等待")
        pool.report(first!!, ok = true, bytes = 1, elapsedMs = 1)
        assertEquals(first, pool.pickWithLimit(), "完成后该来源应重新可领取")
    }

    @Test
    fun `all sources in backoff still returns a fallback source`() {
        val urls = listOf("http://mirror-a/file", "http://mirror-b/file")
        val pool = MirrorPool(urls = urls, perMirrorInFlightCap = 1)
        urls.forEach { pool.report(it, ok = false, bytes = 0, elapsedMs = 1) }
        val picked = pool.pickWithLimit()
        assertTrue(picked != null, "全来源退避时不能永久返回 null 让 worker 饿死")
        assertTrue(picked in urls)
    }

    private class RangeServer(
        private val payload: ByteArray,
        private val chunkSleepMs: Long,
    ) {
        private val executor = Executors.newFixedThreadPool(64)
        val served = AtomicLong(0)
        val requests = AtomicLong(0)
        private val active = java.util.concurrent.atomic.AtomicInteger(0)
        val peak = java.util.concurrent.atomic.AtomicInteger(0)
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port

        init {
            server.createContext("/f.bin") { ex ->
                requests.incrementAndGet()
                val rangeHeader = ex.requestHeaders.getFirst("Range")
                val probeRequest = rangeHeader == "bytes=0-0" || rangeHeader == "bytes=0-"
                val now = if (probeRequest) active.get() else active.incrementAndGet()
                if (!probeRequest) peak.updateAndGet { maxOf(it, now) }
                try {
                    val range = rangeHeader
                    if (range == null) {
                        ex.responseHeaders.add("Accept-Ranges", "bytes")
                        ex.sendResponseHeaders(200, payload.size.toLong())
                        ex.responseBody.use { it.write(payload) }
                        served.addAndGet(payload.size.toLong())
                    } else {
                        val m = Regex("bytes=(\\d+)-(\\d*)").find(range)!!
                        val s = m.groupValues[1].toInt()
                        val e = m.groupValues[2].toIntOrNull() ?: (payload.size - 1)
                        val len = e - s + 1
                        ex.responseHeaders.add("Content-Range", "bytes $s-$e/${payload.size}")
                        ex.responseHeaders.add("Accept-Ranges", "bytes")
                        ex.sendResponseHeaders(206, len.toLong())
                        ex.responseBody.use { out ->
                            var off = s
                            val chunk = 16 * 1024
                            while (off <= e) {
                                val n = minOf(chunk, e - off + 1)
                                out.write(payload, off, n)
                                out.flush()
                                served.addAndGet(n.toLong())
                                off += n
                                if (chunkSleepMs > 0) Thread.sleep(chunkSleepMs)
                            }
                        }
                    }
                } finally {
                    if (!probeRequest) active.decrementAndGet()
                    runCatching { ex.close() }
                }
            }
            server.executor = executor
            server.start()
        }

        fun stop() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun cfg() = TurboConfig(
        maxConnectionsPerTask = 8,
        maxConcurrentTasks = 1,
        slowStart = false,
        adaptiveConcurrency = false,
        warmUpConnections = false,
    )

    @Test
    fun `mirror pool prefers the faster source`() = runBlocking {
        val size = 4 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 31 + 7) % 256).toByte() }
        // A 每 16KB 睡 12ms（≈1.3MB/s），B 不睡（本机 ≈ 数百 MB/s）
        val slow = RangeServer(payload, chunkSleepMs = 12)
        val fast = RangeServer(payload, chunkSleepMs = 0)
        val out = File.createTempFile("mirror-out", ".bin").apply { deleteOnExit() }
        val client = TurboClient(cfg())
        try {
            val id = client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:${slow.port}/f.bin",
                    mirrors = listOf("http://127.0.0.1:${fast.port}/f.bin"),
                    destination = out,
                )
            )
            withTimeout(120_000) { client.await(id) }.getOrThrow()
            assertTrue(out.readBytes().contentEquals(payload), "多镜像下载后内容必须逐字节一致")
            // 冷启动阶段每条来源都会被实测一次，但稳态应该明显偏向快的那个。
            val slowServed = slow.served.get()
            val fastServed = fast.served.get()
            assertTrue(
                fastServed > slowServed * 2,
                "快的镜像应该承担大部分流量：fast=$fastServed slow=$slowServed",
            )
        } finally {
            client.shutdown(); slow.stop(); fast.stop(); out.delete()
        }
    }

    @Test
    fun `mirror with a different file size is rejected`() = runBlocking {
        val size = 3 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 17 + 3) % 256).toByte() }
        // 冒牌镜像：长度只有一半，内容也不一样
        val impostorPayload = ByteArray(size / 2) { ((it * 91 + 11) % 256).toByte() }
        val good = RangeServer(payload, chunkSleepMs = 0)
        val impostor = RangeServer(impostorPayload, chunkSleepMs = 0)
        val out = File.createTempFile("mirror-out2", ".bin").apply { deleteOnExit() }
        val client = TurboClient(cfg())
        try {
            val id = client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:${good.port}/f.bin",
                    mirrors = listOf("http://127.0.0.1:${impostor.port}/f.bin"),
                    destination = out,
                )
            )
            withTimeout(120_000) { client.await(id) }.getOrThrow()
            assertEquals(size.toLong(), out.length())
            assertTrue(
                out.readBytes().contentEquals(payload),
                "冒牌镜像的字节绝不能写进目标文件（大小不一致必须被识别并换源）",
            )
        } finally {
            client.shutdown(); good.stop(); impostor.stop(); out.delete()
        }
    }

    @Test
    fun `max connections per host is applied independently to mirrors`() = runBlocking {
        val size = 4 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 37 + 9) % 256).toByte() }
        val first = RangeServer(payload, chunkSleepMs = 8)
        val second = RangeServer(payload, chunkSleepMs = 8)
        val out = File.createTempFile("mirror-cap", ".bin").apply { deleteOnExit() }
        val client = TurboClient(
            cfg().copy(
                maxConnectionsPerTask = 8,
                maxConnectionsPerHost = 2,
            )
        )
        try {
            val id = client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:${first.port}/f.bin",
                    mirrors = listOf("http://127.0.0.1:${second.port}/f.bin"),
                    destination = out,
                )
            )
            withTimeout(120_000) { client.await(id) }.getOrThrow()
            assertTrue(out.readBytes().contentEquals(payload))
            assertTrue(first.peak.get() <= 2, "主源峰值 ${first.peak.get()} 超过 per-host 上限 2")
            assertTrue(second.peak.get() <= 2, "镜像峰值 ${second.peak.get()} 超过 per-host 上限 2")
        } finally {
            client.shutdown(); first.stop(); second.stop(); out.delete()
        }
    }
}

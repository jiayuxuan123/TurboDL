package dev.turbodl.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 静默探测元数据 + 续传校验回归测试。
 *
 * 覆盖三条能力（均"尽力而为"，探测不出来也不影响下载）：
 *  1. 从 Content-Disposition 取服务器建议文件名（含 RFC 5987 filename* 中文名）；
 *  2. 200 + Accept-Ranges: bytes 也应识别为支持分片（旧逻辑只认 206，会白白退化成单线程）；
 *  3. ETag/Last-Modified 变更时静默丢弃过期分片，避免续传出损坏文件。
 */
class ProbeMetadataTest {

    @Test
    fun `fingerprint rejects a mislabeled range`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/f") { ex ->
            ex.responseHeaders.add("Content-Range", "bytes 1-32/128")
            ex.sendResponseHeaders(206, 32)
            ex.responseBody.use { it.write(ByteArray(32) { 7 }) }
        }
        server.start()
        val cfg = TurboConfig()
        val http = HttpClientFactory.build(cfg, HttpClientFactory.ProtocolPreference.ALLOW_H2)
        val downloader = SegmentDownloader({ http }, { http }, { cfg.ioBufferSize }, { false })
        try {
            assertNull(downloader.fetchPrefix("http://127.0.0.1:${server.address.port}/f", emptyMap(), 0, 32, 1000))
        } finally {
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll(); server.stop(0)
        }
    }

    @Test
    fun `legacy context still receives metadata when new method is absent`() = runBlocking {
        val payload = ByteArray(128 * 1024) { (it % 251).toByte() }
        val srv = MetaServer(payload, etag = "\"legacy\"")
        val root = kotlin.io.path.createTempDirectory("legacy-context").toFile()
        val out = File(root, "output.bin")
        var observed: String? = null
        val context = object : BackendContext {
            override val taskId = 1L
            override val request = DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out)
            override val workDir = File(root, "parts").apply { mkdirs() }
            override val config = TurboConfig(maxConnectionsPerTask = 1, warmUpConnections = false)
            override fun isActive() = true
            override suspend fun throttle(bytes: Long) = Unit
            override fun reportTotalSize(total: Long) = Unit
            override suspend fun reportProgress(absoluteBytes: Long, activeConnections: Int) = Unit
            override fun reportMetadata(
                suggestedFileName: String?, contentType: String?, etag: String?,
                lastModified: String?, probeMs: Long, resumeNote: String,
            ) { observed = etag }
            override fun reportResolvedMetadata(
                suggestedFileName: String?, contentType: String?, etag: String?,
                lastModified: String?, probeMs: Long, resumeNote: String,
                resolvedUrl: String?, supportsRange: Boolean?,
            ) { throw AbstractMethodError("old compiled context") }
        }
        val http = HttpClientFactory.build(context.config, HttpClientFactory.ProtocolPreference.ALLOW_H2)
        val downloader = SegmentDownloader({ http }, { http }, { context.config.ioBufferSize }, { false })
        try {
            BuiltinHttpBackend(downloader).download(context)
            assertEquals("\"legacy\"", observed)
            assertTrue(out.readBytes().contentEquals(payload))
        } finally {
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
            srv.stop(); root.deleteRecursively()
        }
    }

    private class MetaServer(
        @Volatile var payload: ByteArray,
        private val disposition: String? = null,
        /** true=对 bytes=0-0 返回 200+Accept-Ranges（部分 CDN 行为），false=正常 206 */
        private val plain200WithAcceptRanges: Boolean = false,
        @Volatile var etag: String? = null,
    ) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        val rangeRequests = AtomicInteger(0)

        init {
            server.createContext("/f.bin") { ex ->
                val data = payload
                val range = ex.requestHeaders.getFirst("Range")
                disposition?.let { ex.responseHeaders.add("Content-Disposition", it) }
                etag?.let { ex.responseHeaders.add("ETag", it) }
                ex.responseHeaders.add("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT")
                ex.responseHeaders.add("Content-Type", "application/octet-stream")

                val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                // 探测请求（bytes=0-0）：可配置成返回 200 + Accept-Ranges
                if (m != null && m.groupValues[1] == "0" && m.groupValues[2] == "0" && plain200WithAcceptRanges) {
                    ex.responseHeaders.add("Accept-Ranges", "bytes")
                    ex.sendResponseHeaders(200, data.size.toLong())
                    ex.responseBody.use { it.write(data) }
                    return@createContext
                }
                if (m == null) {
                    ex.responseHeaders.add("Accept-Ranges", "bytes")
                    ex.sendResponseHeaders(200, data.size.toLong())
                    ex.responseBody.use { it.write(data) }
                    return@createContext
                }
                rangeRequests.incrementAndGet()
                val s = m.groupValues[1].toInt()
                val e = m.groupValues[2].toIntOrNull() ?: (data.size - 1)
                if (s >= data.size) { ex.sendResponseHeaders(416, -1); ex.close(); return@createContext }
                val end = minOf(e, data.size - 1)
                val len = end - s + 1
                ex.responseHeaders.add("Content-Range", "bytes $s-$end/${data.size}")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { it.write(data, s, len) }
            }
            server.executor = Executors.newFixedThreadPool(64)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    @Test
    fun `metadata event carries server suggested filename from content-disposition`() = runBlocking {
        val size = 1024 * 512
        val payload = ByteArray(size) { (it % 251).toByte() }
        // RFC 5987 编码的中文文件名
        val srv = MetaServer(
            payload,
            disposition = "attachment; filename=\"fallback.bin\"; filename*=UTF-8''%E4%B8%AD%E6%96%87%E5%90%8D.zip",
            etag = "\"abc123\"",
        )
        val client = TurboClient(
            TurboConfig(maxConnectionsPerTask = 4, maxConcurrentTasks = 1, warmUpConnections = false)
        )
        val meta = AtomicReference<TurboEvent.Metadata?>(null)
        // 【必须 UNDISPATCHED】`events` 是 replay=0 的 SharedFlow：**订阅之前发出的事件会直接丢弃**。
        // 普通 `launch` 只是把协程排进队列，`submit()` 之后、收集器真正订阅之前事件就可能已经发出
        // —— 表现为本用例**偶发**断言失败（单独跑必过、混在全量套件里偶红），与被测逻辑无关。
        // UNDISPATCHED 会**在当前线程同步执行到第一个挂起点**，而 SharedFlow.collect 的订阅
        // 正是发生在挂起之前，因此 launch 返回时订阅一定已生效，竞态窗口被彻底关掉。
        val collector = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            client.events.collect { if (it is TurboEvent.Metadata) meta.set(it) }
        }
        val out = File.createTempFile("meta", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
            assertTrue(client.await(id).isSuccess)
            assertEquals(size.toLong(), out.length())
            val m = meta.get()
            assertNotNull(m, "应发出 Metadata 事件")
            assertEquals("中文名.zip", m.suggestedFileName, "应优先取 RFC 5987 的 filename*")
            assertEquals("\"abc123\"", m.etag)
            assertNotNull(m.lastModified)
            assertEquals("application/octet-stream", m.contentType)
        } finally {
            collector.cancel()
            client.shutdown()
            srv.stop()
        }
    }

    @Test
    fun `accept-ranges on 200 still enables multi-segment download`() = runBlocking {
        val size = 4 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 7 + 1) % 256).toByte() }
        // 探测返回 200 + Accept-Ranges: bytes（旧逻辑会误判为不支持 Range → 单线程）
        val srv = MetaServer(payload, plain200WithAcceptRanges = true)
        val client = TurboClient(
            TurboConfig(maxConnectionsPerTask = 8, maxConcurrentTasks = 1, warmUpConnections = false, slowStart = false)
        )
        val out = File.createTempFile("acceptranges", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
            assertTrue(client.await(id).isSuccess)
            assertEquals(size.toLong(), out.length())
            assertTrue(out.readBytes().contentEquals(payload), "内容应逐字节一致")
            // 关键：应确实走了分片 Range 请求（多于 1 个），而不是退化成单流整文件
            assertTrue(srv.rangeRequests.get() > 1, "应通过 Accept-Ranges 识别可分片，实际 Range 请求数 ${srv.rangeRequests.get()}")
        } finally {
            client.shutdown()
            srv.stop()
        }
    }

    @Test
    fun `metadata exposes the resolved redirect target and actual range support`() = runBlocking {
        val payload = ByteArray(256 * 1024) { (it % 199).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/start") { ex ->
            ex.responseHeaders.add("Location", "/final.bin")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/final.bin") { ex ->
            // This source advertises no Range support; metadata must not infer
            // `supportsRange` merely because the total is positive.
            ex.responseHeaders.add("ETag", "\"final-v1\"")
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
        }
        server.start()
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 4,
                maxConcurrentTasks = 1,
                warmUpConnections = false,
                slowStart = false,
            )
        )
        val meta = AtomicReference<TurboEvent.Metadata?>(null)
        val collector = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            client.events.collect { if (it is TurboEvent.Metadata) meta.set(it) }
        }
        val out = File.createTempFile("meta-redirect", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(
                DownloadRequest("http://127.0.0.1:${server.address.port}/start", out)
            )
            assertTrue(client.await(id).isSuccess)
            assertTrue(out.readBytes().contentEquals(payload))
            val m = meta.get()
            assertNotNull(m, "应发出 Metadata 事件")
            assertEquals(
                "http://127.0.0.1:${server.address.port}/final.bin",
                m.resolvedUrl,
                "Metadata 应携带探测/重定向后的最终 URL",
            )
            assertEquals(false, m.supportsRange, "无 Range 能力的源不能被总大小伪装成分片支持")
        } finally {
            collector.cancel()
            client.shutdown()
            server.stop(0)
        }
    }

    @Test
    fun `changed etag discards stale segments instead of merging corrupt file`() = runBlocking {
        val size = 2 * 1024 * 1024
        // 服务器当前提供 v2（全 0x22），ETag=v2
        val srv = MetaServer(ByteArray(size) { 0x22 }, etag = "\"v2\"")
        val workDir = File(System.getProperty("java.io.tmpdir"), "turbodl-validator-${System.nanoTime()}")
        workDir.mkdirs()
        val key = "validator-task"
        // 分片目录命名规则与 TurboClient.sanitizeKey 一致
        val safe = "key_" + key.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        val chunkDir = File(workDir, safe).apply { mkdirs() }

        // 预置“上一次下载遗留的旧分片”：全 0x11，且写入旧的校验器标记（ETag=v1）。
        // 分块大小：total/(workers×segmentsPerConnection) = 2MB/(4×4) = 128KB
        val block = 128 * 1024
        File(chunkDir, "seg_0_${block - 1}.part").writeBytes(ByteArray(block) { 0x11 })
        File(chunkDir, ".validator").writeText("len=$size|etag=\"v1\"|lm=Wed, 21 Oct 2026 07:28:00 GMT")

        val cfg = TurboConfig(
            maxConnectionsPerTask = 4, maxConcurrentTasks = 1,
            warmUpConnections = false, slowStart = false, workDir = workDir,
        )
        val client = TurboClient(cfg)
        val out = File.createTempFile("validator-out", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out, stableKey = key))
            assertTrue(client.await(id).isSuccess)
            val bytes = out.readBytes()
            assertEquals(size, bytes.size)
            // 关键：校验器变更应静默丢弃旧分片，结果必须是纯 v2；
            // 若无校验逻辑，前 128KB 会是旧的 0x11（损坏文件）。
            assertTrue(
                bytes.all { it == 0x22.toByte() },
                "ETag 变更后应丢弃过期分片全部重下；实际前部混入旧数据（首字节=0x${bytes[0].toInt().and(0xff).toString(16)}）"
            )
        } finally {
            client.shutdown()
            srv.stop()
            workDir.deleteRecursively()
        }
    }
}

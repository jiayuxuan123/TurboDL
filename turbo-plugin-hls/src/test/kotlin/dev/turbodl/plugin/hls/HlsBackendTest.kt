package dev.turbodl.plugin.hls

import com.sun.net.httpserver.HttpServer
import dev.turbodl.core.BackendContext
import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.TurboConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HlsBackendTest {

    private lateinit var server: HttpServer
    private var port = 0
    private lateinit var tmpDir: File
    private val routes = HashMap<String, Pair<String, ByteArray>>() // path -> (contentType, bytes)
    private val requestCounts = ConcurrentHashMap<String, AtomicInteger>()

    @BeforeTest
    fun setup() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "hls-be-${System.nanoTime()}").apply { mkdirs() }
        requestCounts.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            requestCounts.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            val entry = routes[path]
            if (entry == null) {
                ex.sendResponseHeaders(404, -1); ex.close(); return@createContext
            }
            val (type, bytes) = entry
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null) {
                val m = Regex("bytes=(\\d+)-(\\d+)").find(range)!!
                val s = m.groupValues[1].toInt(); val e = m.groupValues[2].toInt()
                val slice = bytes.copyOfRange(s, e + 1)
                ex.responseHeaders.add("Content-Type", type)
                ex.sendResponseHeaders(206, slice.size.toLong())
                ex.responseBody.use { it.write(slice) }
            } else {
                ex.responseHeaders.add("Content-Type", type)
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
        server.executor = Executors.newFixedThreadPool(8)
        server.start()
        port = server.address.port
    }

    @AfterTest
    fun teardown() {
        server.stop(0)
        tmpDir.deleteRecursively()
    }

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    private fun context(request: DownloadRequest): TestBackendContext {
        val work = File(tmpDir, "work-${System.nanoTime()}").apply { mkdirs() }
        return TestBackendContext(request, work)
    }

    @Test
    fun downloadsPlainVodSegmentsInOrder() = runBlocking {
        val seg0 = ByteArray(50_000) { (it % 251).toByte() }
        val seg1 = ByteArray(60_000) { ((it * 7) % 251).toByte() }
        val seg2 = ByteArray(40_000) { ((it * 13) % 251).toByte() }
        routes["/seg0.ts"] = "video/mp2t" to seg0
        routes["/seg1.ts"] = "video/mp2t" to seg1
        routes["/seg2.ts"] = "video/mp2t" to seg2
        routes["/media.m3u8"] = "application/vnd.apple.mpegurl" to """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:6.0,
            seg0.ts
            #EXTINF:6.0,
            seg1.ts
            #EXTINF:6.0,
            seg2.ts
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()

        val request = DownloadRequest(url("/media.m3u8"), File(tmpDir, "out.ts"))
        val ctx = context(request)
        val result = HlsBackend().download(ctx)

        assertEquals(3, result.orderedParts.size)
        val merged = result.orderedParts.fold(ByteArray(0)) { acc, f -> acc + f.readBytes() }
        assertTrue(merged.contentEquals(seg0 + seg1 + seg2), "segments must concatenate in playlist order")
        assertEquals((seg0.size + seg1.size + seg2.size).toLong(), result.totalBytes)
        assertEquals(result.totalBytes, ctx.lastTotal)
    }

    @Test
    fun downloadsFmp4InitMapFirstAndReusesItsCache() = runBlocking {
        val init = ByteArray(12_000) { ((it * 17) % 251).toByte() }
        val seg0 = ByteArray(30_000) { (it % 251).toByte() }
        val seg1 = ByteArray(25_000) { ((it * 11) % 251).toByte() }
        routes["/init.mp4"] = "video/mp4" to init
        routes["/seg0.m4s"] = "video/iso.segment" to seg0
        routes["/seg1.m4s"] = "video/iso.segment" to seg1
        routes["/fmp4.m3u8"] = "application/vnd.apple.mpegurl" to """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.0,
            seg0.m4s
            #EXTINF:6.0,
            seg1.m4s
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()

        val request = DownloadRequest(url("/fmp4.m3u8"), File(tmpDir, "out.mp4"))
        val ctx = context(request)
        val backend = HlsBackend()
        val first = backend.download(ctx)

        assertEquals(3, first.orderedParts.size)
        assertTrue(first.orderedParts[0].readBytes().contentEquals(init), "init map must be the first part")
        val merged = first.orderedParts.fold(ByteArray(0)) { acc, file -> acc + file.readBytes() }
        assertTrue(merged.contentEquals(init + seg0 + seg1), "init map and media segments must stay in order")
        assertEquals((init.size + seg0.size + seg1.size).toLong(), first.totalBytes)
        assertEquals(first.totalBytes, ctx.lastTotal)
        assertEquals(1, requestCounts.getValue("/init.mp4").get())

        val partsDir = File(ctx.workDir, "hls-segments")
        partsDir.listFiles()
            ?.filter { it.name.startsWith("seg-") && it.extension == "part" }
            ?.forEach { it.delete() }

        val second = backend.download(ctx)
        assertEquals(first.totalBytes, second.totalBytes)
        assertEquals(1, requestCounts.getValue("/init.mp4").get(), "second run must reuse the init map cache")
        assertTrue(second.orderedParts[0].readBytes().contentEquals(init))
    }

    @Test
    fun changedPlaylistDoesNotReuseOldSegmentIndexes() = runBlocking {
        val old0 = ByteArray(20_000) { 1 }
        val old1 = ByteArray(30_000) { 2 }
        val new0 = ByteArray(40_000) { 3 }
        val new1 = ByteArray(50_000) { 4 }

        fun media(first: String, second: String) = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            $first
            #EXTINF:6.0,
            $second
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()

        routes["/old-0.ts"] = "video/mp2t" to old0
        routes["/old-1.ts"] = "video/mp2t" to old1
        routes["/media.m3u8"] = "application/vnd.apple.mpegurl" to media("old-0.ts", "old-1.ts")

        val work = File(tmpDir, "resume-work").apply { mkdirs() }
        val request = DownloadRequest(url("/media.m3u8"), File(tmpDir, "out.ts"))
        val ctx = TestBackendContext(request, work)
        val backend = HlsBackend()

        val first = backend.download(ctx).orderedParts
            .fold(ByteArray(0)) { acc, file -> acc + file.readBytes() }
        assertTrue(first.contentEquals(old0 + old1))

        routes["/new-0.ts"] = "video/mp2t" to new0
        routes["/new-1.ts"] = "video/mp2t" to new1
        routes["/media.m3u8"] = "application/vnd.apple.mpegurl" to media("new-0.ts", "new-1.ts")

        val second = backend.download(ctx).orderedParts
            .fold(ByteArray(0)) { acc, file -> acc + file.readBytes() }
        assertTrue(
            second.contentEquals(new0 + new1),
            "a changed playlist must invalidate old indexed media parts",
        )
        assertEquals(1, requestCounts.getValue("/new-0.ts").get())
        assertEquals(1, requestCounts.getValue("/new-1.ts").get())
    }

    @Test
    fun oversizedPlaylistFailsAtTheBoundedReadInsteadOfAllocatingItAll() = runBlocking {
        val oversized = ByteArray(HlsIo.MAX_TEXT_BYTES.toInt() + 1) { '#'.code.toByte() }
        routes["/oversized.m3u8"] = "application/vnd.apple.mpegurl" to oversized

        val request = DownloadRequest(url("/oversized.m3u8"), File(tmpDir, "out.ts"))
        val error = assertFailsWith<IllegalStateException> {
            HlsBackend().download(context(request))
        }
        assertTrue(error.message.orEmpty().contains("response exceeds byte limit"))
    }

    @Test
    fun selectsHighestBandwidthVariantThenDownloads() = runBlocking {
        val seg = ByteArray(30_000) { (it % 251).toByte() }
        routes["/hi/seg0.ts"] = "video/mp2t" to seg
        routes["/hi/index.m3u8"] = "application/vnd.apple.mpegurl" to """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:6.0,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()
        routes["/master.m3u8"] = "application/vnd.apple.mpegurl" to """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=500000
            lo/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000
            hi/index.m3u8
        """.trimIndent().toByteArray()

        val request = DownloadRequest(url("/master.m3u8"), File(tmpDir, "out.ts"))
        val result = HlsBackend().download(context(request))
        assertEquals(1, result.orderedParts.size)
        assertTrue(result.orderedParts[0].readBytes().contentEquals(seg))
    }

    @Test
    fun fallsBackToAPlaylistMirrorWhenThePrimaryManifestFails() = runBlocking {
        val segment = ByteArray(24_000) { ((it * 19) % 251).toByte() }
        routes["/mirror/seg0.ts"] = "video/mp2t" to segment
        routes["/mirror/index.m3u8"] = "application/vnd.apple.mpegurl" to """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:6.0,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()

        val request = DownloadRequest(
            url = url("/missing/index.m3u8"),
            destination = File(tmpDir, "mirror.ts"),
            mirrors = listOf(url("/mirror/index.m3u8")),
        )
        val result = HlsBackend().download(context(request))
        assertTrue(result.orderedParts.single().readBytes().contentEquals(segment))
    }

    @Test
    fun decryptsAes128Segments() = runBlocking {
        val key = ByteArray(16) { (it + 1).toByte() }
        val iv = ByteArray(16) { (16 - it).toByte() }
        val plain0 = ByteArray(48_000) { (it % 251).toByte() }
        val plain1 = ByteArray(32_000) { ((it * 3) % 251).toByte() }
        routes["/enc.key"] = "application/octet-stream" to key
        routes["/e0.ts"] = "video/mp2t" to aesEncrypt(plain0, key, iv)
        routes["/e1.ts"] = "video/mp2t" to aesEncrypt(plain1, key, iv)
        val ivHex = "0x" + iv.joinToString("") { "%02x".format(it) }
        routes["/enc.m3u8"] = "application/vnd.apple.mpegurl" to """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-KEY:METHOD=AES-128,URI="enc.key",IV=$ivHex
            #EXTINF:6.0,
            e0.ts
            #EXTINF:6.0,
            e1.ts
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()

        val request = DownloadRequest(url("/enc.m3u8"), File(tmpDir, "out.ts"))
        val result = HlsBackend().download(context(request))
        val merged = result.orderedParts.fold(ByteArray(0)) { acc, f -> acc + f.readBytes() }
        assertTrue(merged.contentEquals(plain0 + plain1), "decrypted segments must match original plaintext")
    }

    @Test
    fun downloadsByteRangeSegments() = runBlocking {
        val full = ByteArray(10_000) { (it % 251).toByte() }
        routes["/media.ts"] = "video/mp2t" to full
        routes["/br.m3u8"] = "application/vnd.apple.mpegurl" to """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:6.0,
            #EXT-X-BYTERANGE:4000@0
            media.ts
            #EXTINF:6.0,
            #EXT-X-BYTERANGE:6000
            media.ts
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()

        val request = DownloadRequest(url("/br.m3u8"), File(tmpDir, "out.ts"))
        val result = HlsBackend().download(context(request))
        val merged = result.orderedParts.fold(ByteArray(0)) { acc, f -> acc + f.readBytes() }
        assertTrue(merged.contentEquals(full), "byte-range segments must reassemble the full media file")
    }

    @Test
    fun supportsOnlyM3u8Urls() {
        val backend = HlsBackend()
        assertTrue(backend.supports(DownloadRequest(url("/media.m3u8"), File(tmpDir, "x"))))
        assertTrue(!backend.supports(DownloadRequest(url("/video.mp4"), File(tmpDir, "x"))))
        assertTrue(!backend.supports(DownloadRequest("ftp://host/a.m3u8", File(tmpDir, "x"))))
    }

    private fun aesEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** Minimal in-test BackendContext capturing progress/size reports. */
    private class TestBackendContext(
        override val request: DownloadRequest,
        override val workDir: File,
    ) : BackendContext {
        override val taskId: Long = 1
        override val config: TurboConfig = TurboConfig(maxConnectionsPerTask = 4)
        @Volatile var lastTotal: Long = -1
        override fun isActive(): Boolean = true
        override suspend fun throttle(bytes: Long) {}
        override fun reportTotalSize(total: Long) { lastTotal = total }
        override suspend fun reportProgress(absoluteBytes: Long, activeConnections: Int) {}
    }
}

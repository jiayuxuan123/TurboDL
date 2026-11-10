package dev.turbodl.plugin.hls

import com.sun.net.httpserver.HttpServer
import dev.turbodl.core.BackendContext
import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.TurboConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HlsMaintenanceTest {
    private val key = ByteArray(16) { (it * 3).toByte() }
    private val iv = ByteArray(16) { (it + 1).toByte() }
    private fun encrypt(bytes: ByteArray): ByteArray = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(this@HlsMaintenanceTest.iv)); doFinal(bytes)
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()

    @Test fun `streaming AES preserves every byte at block and buffer boundaries`() {
        val directory = Files.createTempDirectory("hls-aes-boundary-").toFile()
        val buffers = HlsWorkBuffers()
        try {
            for (size in listOf(0, 1, 15, 16, 17, 131071, 131072, 131073, 1048576)) {
                val plain = ByteArray(size).also { Random(size.toLong()).nextBytes(it) }
                val input = File(directory, "in").apply { writeBytes(encrypt(plain)) }
                val output = File(directory, "out")
                HlsAes128.decrypt(input, output, key, iv, buffers) {}
                assertEquals(sha(plain), sha(output.readBytes()), "AES boundary $size")
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun `streaming AES rejects invalid padding and checks cancellation between chunks`() {
        val directory = Files.createTempDirectory("hls-aes-errors-").toFile()
        try {
            val input = File(directory, "in").apply { writeBytes(encrypt(ByteArray(7))) }
            val badIv = iv.copyOf().apply { this[15] = (this[15].toInt() xor 1).toByte() }
            assertFailsWith<BadPaddingException> { HlsAes128.decrypt(input, File(directory, "bad"), key, badIv, HlsWorkBuffers()) {} }
            input.writeBytes(encrypt(ByteArray(2 * 1024 * 1024)))
            val output = File(directory, "canceled")
            var checks = 0
            assertFailsWith<CancellationException> {
                HlsAes128.decrypt(input, output, key, iv, HlsWorkBuffers()) { if (++checks == 3) throw CancellationException("test cancellation") }
            }
            assertTrue(output.length() < 2 * 1024 * 1024, "cancellation must stop before decoding the whole file")
        } finally { directory.deleteRecursively() }
    }

    private fun context(root: File, url: String, config: TurboConfig, override: Int? = null) = object : BackendContext {
        override val taskId = 1L
        override val request = DownloadRequest(url, File(root, "out.ts"), connectionsOverride = override)
        override val workDir = File(root, "work").apply { mkdirs() }
        override val config = config
        override fun isActive() = true
        override suspend fun throttle(bytes: Long) {}
        override fun reportTotalSize(total: Long) {}
        override suspend fun reportProgress(absoluteBytes: Long, activeConnections: Int) {}
    }

    @Test fun `rotating keys preload in parallel within the request override`() = rotatingKeys(override = 2, hostLimit = 0, expectedPeak = 2)
    @Test fun `key preloading obeys an explicit host limit of one`() = rotatingKeys(override = 4, hostLimit = 1, expectedPeak = 1)

    private fun rotatingKeys(override: Int, hostLimit: Int, expectedPeak: Int) = runBlocking {
        val root = Files.createTempDirectory("hls-key-concurrency-").toFile()
        val routes = ConcurrentHashMap<String, ByteArray>()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val requests = AtomicInteger()
        val executor = Executors.newFixedThreadPool(8)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        val expected = ByteArray(1024).also { Random(81).nextBytes(it) }
        val manifest = StringBuilder("#EXTM3U\n")
        val ivText = iv.joinToString("") { "%02x".format(it) }
        repeat(8) { index ->
            routes["/key$index.key"] = key
            routes["/part$index.ts"] = encrypt(expected)
            manifest.append("#EXT-X-KEY:METHOD=AES-128,URI=\"key$index.key\",IV=0x$ivText\n#EXTINF:1,\npart$index.ts\n")
        }
        routes["/main.m3u8"] = manifest.append("#EXT-X-ENDLIST\n").toString().toByteArray()
        server.createContext("/") { exchange ->
            val isKey = exchange.requestURI.path.endsWith(".key")
            try {
                if (isKey) { requests.incrementAndGet(); val current = active.incrementAndGet(); peak.updateAndGet { maxOf(it, current) }; Thread.sleep(40) }
                val bytes = routes.getValue(exchange.requestURI.path)
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            } finally { if (isKey) active.decrementAndGet(); exchange.close() }
        }
        server.start()
        try {
            val result = HlsBackend().download(context(root, "http://127.0.0.1:${server.address.port}/main.m3u8", TurboConfig(maxConnectionsPerTask = 8, maxConnectionsPerHost = hostLimit), override))
            assertEquals(8, requests.get(), "each distinct key is fetched once")
            assertEquals(expectedPeak, peak.get())
            result.orderedParts.forEach { assertEquals(sha(expected), sha(it.readBytes())) }
        } finally { server.stop(0); executor.shutdownNow(); root.deleteRecursively() }
    }

    @Test fun `canceling a stalled key response closes the call before its read timeout`() = runBlocking {
        val root = Files.createTempDirectory("hls-key-cancel-").toFile()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/main.m3u8") { exchange ->
            val body = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"slow.key\"\n#EXTINF:1,\na.ts\n#EXT-X-ENDLIST\n".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/slow.key") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 16); exchange.responseBody.write(1); exchange.responseBody.flush(); started.countDown()
                release.await(5, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.start()
        val job = launch(Dispatchers.Default) { runCatching { HlsBackend().download(context(root, "http://127.0.0.1:${server.address.port}/main.m3u8", TurboConfig(readTimeoutMs = 5000))) } }
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            withTimeout(1500) { job.cancelAndJoin() }
        } finally { release.countDown(); job.cancelAndJoin(); server.stop(0); executor.shutdownNow(); root.deleteRecursively() }
    }

    @Test fun `invalid encrypted parts never leave a completed cache file`() = runBlocking {
        val root = Files.createTempDirectory("hls-bad-padding-").toFile()
        val badIv = iv.copyOf().apply { this[15] = (this[15].toInt() xor 1).toByte() }
        val ivText = badIv.joinToString("") { "%02x".format(it) }
        val manifest = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0x$ivText\n#EXTINF:1,\na.ts\n#EXT-X-ENDLIST\n".toByteArray()
        val executor = Executors.newFixedThreadPool(2)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            val bytes = when (exchange.requestURI.path) { "/main.m3u8" -> manifest; "/key" -> key; else -> encrypt(ByteArray(7)) }
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val context = context(root, "http://127.0.0.1:${server.address.port}/main.m3u8", TurboConfig(maxRetries = 0))
            assertFailsWith<IllegalStateException> { HlsBackend().download(context) }
            assertTrue(File(context.workDir, "hls-segments").listFiles().orEmpty().none { it.name.endsWith(".part") || it.name.endsWith(".decrypting") || it.name.endsWith(".downloading") })
        } finally { server.stop(0); executor.shutdownNow(); root.deleteRecursively() }
    }

    @Test fun `canceling a stalled media body removes partial files and permits a clean resume`() = runBlocking {
        val root = Files.createTempDirectory("hls-media-cancel-").toFile()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val payload = ByteArray(1024 * 1024).also { Random(89).nextBytes(it) }
        val executor = Executors.newFixedThreadPool(2)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/main.m3u8") { exchange ->
            val bytes = "#EXTM3U\n#EXTINF:1,\nslow.ts\n#EXT-X-ENDLIST\n".toByteArray()
            try {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        server.createContext("/slow.ts") { exchange ->
            try {
                exchange.sendResponseHeaders(200, payload.size.toLong())
                if (requests.incrementAndGet() == 1) {
                    exchange.responseBody.write(payload, 0, 1024)
                    exchange.responseBody.flush()
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                } else exchange.responseBody.use { it.write(payload) }
            } finally { exchange.close() }
        }
        server.start()
        val context = context(root, "http://127.0.0.1:${server.address.port}/main.m3u8", TurboConfig(readTimeoutMs = 5000))
        val job = launch(Dispatchers.Default) { runCatching { HlsBackend().download(context) } }
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            withTimeout(1500) { job.cancelAndJoin() }
            assertTrue(context.workDir.walkTopDown().none { it.name.endsWith(".downloading") || it.name.endsWith(".decrypting") || it.name.endsWith(".part") })
            release.countDown()
            val result = HlsBackend().download(context)
            assertEquals(1, result.orderedParts.size)
            assertEquals(sha(payload), sha(result.orderedParts.single().readBytes()))
            assertEquals(2, requests.get(), "an interrupted body cannot be mistaken for a completed cached segment")
        } finally {
            release.countDown(); job.cancelAndJoin(); server.stop(0); executor.shutdownNow(); root.deleteRecursively()
        }
    }
}

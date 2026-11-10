package dev.turbodl.plugin.hls

import com.sun.management.ThreadMXBean
import com.sun.net.httpserver.HttpServer
import dev.turbodl.core.BackendContext
import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.TurboConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private data class BenchCase(val name: String, val segments: Int, val bytes: Int, val encrypted: Boolean, val rotatingKeys: Boolean)
private data class Sample(val scenario: String, val round: Int, val elapsedMs: Double, val allocated: Long, val keyPeak: Int, val keyRequests: Int, val sha256: String)

/** Explicit local benchmark task; never contributes synthetic passes to the unit suite. */
fun main(args: Array<String>) = runBlocking {
    val rounds = args.getOrNull(1)?.toInt() ?: 5
    val root = Files.createTempDirectory("turbodl-maintenance-").toFile()
    val routes = ConcurrentHashMap<String, ByteArray>()
    val activeKeys = AtomicInteger()
    val peakKeys = AtomicInteger()
    val keyRequests = AtomicInteger()
    val executor = Executors.newFixedThreadPool(16)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.executor = executor
    server.createContext("/") { exchange ->
        val isKey = exchange.requestURI.path.endsWith(".key")
        try {
            if (isKey) {
                keyRequests.incrementAndGet()
                val current = activeKeys.incrementAndGet()
                peakKeys.updateAndGet { maxOf(it, current) }
                Thread.sleep(50)
            }
            val bytes = routes[exchange.requestURI.path]
            if (bytes == null) exchange.sendResponseHeaders(404, -1)
            else {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        } finally { if (isKey) activeKeys.decrementAndGet(); exchange.close() }
    }
    server.start()
    val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
    bean.isThreadAllocatedMemoryEnabled = true
    fun allocated(): Map<Long, Long> = bean.allThreadIds.associateWith { bean.getThreadAllocatedBytes(it).coerceAtLeast(0) }
    val samples = mutableListOf<Sample>()
    try {
        for (scenario in listOf(
            BenchCase("plain-many-parts", 256, 8192, false, false),
            BenchCase("aes-large-part", 1, 32 * 1024 * 1024, true, false),
            BenchCase("aes-rotating-keys", 8, 16384, true, true),
        )) {
            routes.clear()
            val expected = MessageDigest.getInstance("SHA-256")
            val manifest = StringBuilder("#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n")
            val iv = ByteArray(16) { it.toByte() }
            val ivText = iv.joinToString("") { "%02x".format(it) }
            repeat(scenario.segments) { index ->
                val plain = ByteArray(scenario.bytes).also { Random(731L + index).nextBytes(it) }
                expected.update(plain)
                val bytes = if (scenario.encrypted) {
                    val keyIndex = if (scenario.rotatingKeys) index else 0
                    val key = ByteArray(16).also { Random(331L + keyIndex).nextBytes(it) }
                    routes["/key$keyIndex.key"] = key
                    manifest.append("#EXT-X-KEY:METHOD=AES-128,URI=\"key$keyIndex.key\",IV=0x$ivText\n")
                    Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)); doFinal(plain)
                    }
                } else plain
                routes["/part$index.ts"] = bytes
                manifest.append("#EXTINF:1.0,\npart$index.ts\n")
            }
            manifest.append("#EXT-X-ENDLIST\n")
            routes["/main.m3u8"] = manifest.toString().toByteArray()
            val expectedHash = expected.digest().joinToString("") { "%02x".format(it) }
            for (round in -1 until rounds) {
                val directory = File(root, "${scenario.name}-$round").apply { mkdirs() }
                val request = DownloadRequest("http://127.0.0.1:${server.address.port}/main.m3u8", File(directory, "out.ts"))
                val context = object : BackendContext {
                    override val taskId = 1L
                    override val request = request
                    override val workDir = directory
                    override val config = TurboConfig(maxConnectionsPerTask = 4)
                    override fun isActive() = true
                    override suspend fun throttle(bytes: Long) {}
                    override fun reportTotalSize(total: Long) {}
                    override suspend fun reportProgress(absoluteBytes: Long, activeConnections: Int) {}
                }
                peakKeys.set(0); keyRequests.set(0)
                val before = allocated()
                val started = System.nanoTime()
                val result = HlsBackend().download(context)
                val elapsed = (System.nanoTime() - started) / 1_000_000.0
                val used = allocated().entries.sumOf { (id, count) -> (count - (before[id] ?: 0)).coerceAtLeast(0) }
                val digest = MessageDigest.getInstance("SHA-256")
                val hashBuffer = ByteArray(128 * 1024)
                result.orderedParts.forEach { file -> file.inputStream().use { input ->
                    while (true) { val count = input.read(hashBuffer); if (count < 0) break; digest.update(hashBuffer, 0, count) }
                } }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == expectedHash) { "incorrect bytes: ${scenario.name} round $round" }
                if (round >= 0) samples += Sample(scenario.name, round, elapsed, used, peakKeys.get(), keyRequests.get(), actual)
                directory.deleteRecursively()
                println("maintenance case=${scenario.name} round=$round ms=$elapsed allocated=$used keys=${keyRequests.get()} keyPeak=${peakKeys.get()} sha256=$actual")
            }
        }
        val output = File(args.first()).absoluteFile.apply { parentFile.mkdirs() }
        output.writeText(samples.joinToString(",\n", "[\n", "\n]\n") { sample ->
            "{\"scenario\":\"${sample.scenario}\",\"round\":${sample.round},\"elapsed_ms\":${sample.elapsedMs},\"allocated_bytes\":${sample.allocated},\"key_peak\":${sample.keyPeak},\"key_requests\":${sample.keyRequests},\"sha256\":\"${sample.sha256}\"}"
        })
        println("maintenance samples=${samples.size} output=$output")
    } finally {
        server.stop(0); executor.shutdownNow(); root.deleteRecursively()
    }
}

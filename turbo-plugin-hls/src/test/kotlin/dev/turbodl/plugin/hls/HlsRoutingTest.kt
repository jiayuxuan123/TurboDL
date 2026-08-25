package dev.turbodl.plugin.hls

import com.sun.net.httpserver.HttpServer
import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.TurboClient
import dev.turbodl.core.TurboConfig
import dev.turbodl.plugin.runtime.PluginHost
import dev.turbodl.plugin.runtime.ext.BackendRegistry
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end: install HlsPlugin into a PluginHost, wire its BackendRegistry into a real
 * TurboClient, and verify an .m3u8 task is routed to the HLS backend, downloaded, and merged
 * by the engine into a byte-exact concatenation — while a plain file URL still uses core's
 * built-in HTTP backend (HLS.supports only matches m3u8).
 */
class HlsRoutingTest {

    private lateinit var server: HttpServer
    private var port = 0
    private lateinit var tmpDir: File
    private val routes = HashMap<String, Pair<String, ByteArray>>()

    @BeforeTest
    fun setup() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "hls-route-${System.nanoTime()}").apply { mkdirs() }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val entry = routes[ex.requestURI.path]
            if (entry == null) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
            val (type, bytes) = entry
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null) {
                val m = Regex("bytes=(\\d+)-(\\d+)").find(range)!!
                val s = m.groupValues[1].toInt(); val e = m.groupValues[2].toInt().coerceAtMost(bytes.size - 1)
                ex.responseHeaders.add("Content-Type", type)
                ex.responseHeaders.add("Content-Range", "bytes $s-$e/${bytes.size}")
                ex.sendResponseHeaders(206, (e - s + 1).toLong())
                ex.responseBody.use { it.write(bytes, s, e - s + 1) }
            } else {
                ex.responseHeaders.add("Content-Type", type)
                ex.responseHeaders.add("Content-Length", bytes.size.toString())
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

    @Test
    fun m3u8RoutedToHlsAndMergedByEngine() = runBlocking {
        val seg0 = ByteArray(70_000) { (it % 251).toByte() }
        val seg1 = ByteArray(90_000) { ((it * 5) % 251).toByte() }
        routes["/s0.ts"] = "video/mp2t" to seg0
        routes["/s1.ts"] = "video/mp2t" to seg1
        routes["/v.m3u8"] = "application/vnd.apple.mpegurl" to """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:6.0,
            s0.ts
            #EXTINF:6.0,
            s1.ts
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()

        val host = PluginHost(logger = { _, _ -> })
        host.install(HlsPlugin())
        val client = TurboClient(TurboConfig(maxConnectionsPerTask = 4))
        client.backendResolver = BackendRegistry(host.extensions)

        val out = File(tmpDir, "merged.ts")
        val result = client.await(client.submit(DownloadRequest(url("/v.m3u8"), out)))
        client.shutdown()
        host.shutdown()

        assertTrue(result.isSuccess, "HLS routed download should succeed: ${result.exceptionOrNull()?.message}")
        assertEquals((seg0.size + seg1.size).toLong(), out.length())
        assertTrue(out.readBytes().contentEquals(seg0 + seg1), "engine must merge HLS segments in order")
    }

    @Test
    fun plainFileStillUsesBuiltinHttpBackend() = runBlocking {
        val payload = ByteArray(120_000) { (it % 251).toByte() }
        routes["/file.bin"] = "application/octet-stream" to payload

        val host = PluginHost(logger = { _, _ -> })
        host.install(HlsPlugin())
        val client = TurboClient(TurboConfig(maxConnectionsPerTask = 4))
        client.backendResolver = BackendRegistry(host.extensions)

        val out = File(tmpDir, "file.bin")
        val result = client.await(client.submit(DownloadRequest(url("/file.bin"), out)))
        client.shutdown()
        host.shutdown()

        assertTrue(result.isSuccess, "non-m3u8 download should fall back to built-in HTTP backend")
        assertEquals(payload.size.toLong(), out.length())
        assertTrue(out.readBytes().contentEquals(payload))
    }
}

package dev.turbodl.plugin.bootstrap

import com.sun.net.httpserver.HttpServer
import dev.turbodl.core.DownloadRequest
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
 * End-to-end wiring test for the bootstrap: a real download flows through
 * PluginHost -> BackendRegistry -> HttpBackendPlugin (routed) and produces a byte-exact file.
 */
class TurboBootstrapTest {

    private lateinit var server: HttpServer
    private var port = 0
    private lateinit var payload: ByteArray
    private lateinit var tmpDir: File

    @BeforeTest
    fun setup() {
        payload = ByteArray(2_000_000) { (it * 40503).toByte() }
        tmpDir = File(System.getProperty("java.io.tmpdir"), "turbo-boot-${System.nanoTime()}").apply { mkdirs() }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/f") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            if (range == "bytes=0-0") {
                ex.responseHeaders.add("Content-Range", "bytes 0-0/${payload.size}")
                ex.sendResponseHeaders(206, 1)
                ex.responseBody.use { it.write(payload, 0, 1) }
                return@createContext
            }
            if (range != null) {
                val m = Regex("bytes=(\\d+)-(\\d*)").find(range)!!
                val s = m.groupValues[1].toInt()
                val e = m.groupValues[2].toIntOrNull() ?: (payload.size - 1)
                ex.responseHeaders.add("Content-Range", "bytes $s-$e/${payload.size}")
                ex.sendResponseHeaders(206, (e - s + 1).toLong())
                ex.responseBody.use { it.write(payload, s, e - s + 1) }
                return@createContext
            }
            ex.responseHeaders.add("Content-Length", payload.size.toString())
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
        }
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        server.start()
        port = server.address.port
    }

    @AfterTest
    fun teardown() {
        server.stop(0)
        tmpDir.deleteRecursively()
    }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun downloadsThroughRoutedHttpBackendPlugin() = runBlocking {
        val boot = TurboBootstrap.create()
        // sanity: base plugins loaded
        val diag = boot.host.diagnostics()
        assertTrue(diag.services.containsKey("backend.http"), "http backend should be registered")
        assertTrue(diag.services.containsKey("loader.kotlin"), "kotlin loader should be registered")

        val out = File(tmpDir, "f.bin")
        val result = boot.client.await(boot.client.submit(DownloadRequest("http://127.0.0.1:$port/f", out)))
        boot.shutdown()

        assertTrue(result.isSuccess, "routed download should succeed: ${result.exceptionOrNull()?.message}")
        assertEquals(sha(payload), sha(out.readBytes()))
    }

    @Test
    fun unloadingBackendPluginRemovesItFromRegistry() {
        val boot = TurboBootstrap.create()
        assertTrue(boot.host.diagnostics().extensionPoints.containsKey("turbo.downloadBackend"))
        boot.host.uninstall("backend.http")
        // after unload, the extension point has no implementations (disposer cleaned it up)
        val impls = boot.host.diagnostics().extensionPoints["turbo.downloadBackend"] ?: emptyList()
        assertTrue(impls.isEmpty(), "backend extension must be removed after unload")
        boot.shutdown()
    }
}

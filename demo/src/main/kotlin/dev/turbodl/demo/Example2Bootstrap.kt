package dev.turbodl.demo

import com.sun.net.httpserver.HttpServer
import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.TurboConfig
import dev.turbodl.plugin.bootstrap.TurboBootstrap
import dev.turbodl.plugin.hls.HlsPlugin
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Example 2 — using the bootstrap module for out-of-the-box wiring.
 *
 * [TurboBootstrap.create] builds a PluginHost + TurboClient, installs the base plugins (Kotlin
 * loader + HTTP backend), and wires the plugin BackendRegistry into the client. Here we also
 * pass an extra plugin (HLS) to show how third-party plugins are added.
 *
 * A tiny local HTTP server stands in for a real download target so the example is self-contained.
 */
fun runBootstrapExample() = runBlocking {
    println("=== Example 2: bootstrap usage ===")

    val payload = ByteArray(256_000) { (it % 251).toByte() }
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/file.bin") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null) {
                val m = Regex("bytes=(\\d+)-(\\d*)").find(range)!!
                val s = m.groupValues[1].toInt()
                val e = m.groupValues[2].toIntOrNull() ?: (payload.size - 1)
                ex.responseHeaders.add("Content-Range", "bytes $s-$e/${payload.size}")
                ex.sendResponseHeaders(206, (e - s + 1).toLong())
                ex.responseBody.use { it.write(payload, s, e - s + 1) }
            } else {
                ex.responseHeaders.add("Content-Length", payload.size.toString())
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.use { it.write(payload) }
            }
        }
        executor = Executors.newFixedThreadPool(4)
        start()
    }
    val port = server.address.port

    // One-line setup: base plugins + an extra HLS plugin.
    val boot = TurboBootstrap.create(
        config = TurboConfig(maxConnectionsPerTask = 8),
        extraPlugins = listOf(HlsPlugin()),
    )
    println(boot.diagnosticsReport())

    val out = File.createTempFile("demo-bootstrap", ".bin").apply { deleteOnExit() }
    val result = boot.client.await(
        boot.client.submit(DownloadRequest("http://127.0.0.1:$port/file.bin", out)),
    )
    println("download success=${result.isSuccess}, bytes=${out.length()}")

    boot.shutdown()
    server.stop(0)
}

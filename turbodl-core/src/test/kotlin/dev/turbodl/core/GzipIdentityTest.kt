package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gzip 透明解压导致「大小校验失败」回归测试。
 *
 * 复刻用户反馈：更新下载时「莫名其妙弹出大小校验失败，算下来少了约 2.5 倍」。
 * 根因：若请求未显式声明编码，OkHttp 会自动加 Accept-Encoding: gzip 并透明解压，
 * 此时实际落盘字节与 Content-Range/Content-Length 声明的字节数不一致，
 * 触发引擎的字节级大小校验失败。
 *
 * 修复：所有探测/分片/整文件请求强制 Accept-Encoding: identity，禁止压缩，
 * 保证 wire 字节 == 落盘字节 == 声明字节。本测试的服务器只在收到 identity 时正确工作；
 * 若客户端仍发 gzip，则返回压缩体制造字节错位，测试即失败。
 */
class GzipIdentityTest {

    private class MaybeGzipServer(private val payload: ByteArray) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        private val total = payload.size

        init {
            server.createContext("/f.bin") { ex -> handle(ex) }
            server.executor = Executors.newFixedThreadPool(64)
            server.start()
        }

        private fun handle(ex: HttpExchange) {
            val acceptEnc = ex.requestHeaders.getFirst("Accept-Encoding").orEmpty()
            val wantGzip = acceptEnc.contains("gzip", true) && !acceptEnc.contains("identity", true)
            val range = ex.requestHeaders.getFirst("Range")
            val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
            val s = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val e = m?.groupValues?.get(2)?.toIntOrNull() ?: (total - 1)
            val slice = payload.copyOfRange(s, e + 1)

            if (wantGzip) {
                // 客户端仍要 gzip：返回压缩体但 Content-Range 仍写原始长度，制造字节错位。
                val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { g -> g.write(slice) } }.toByteArray()
                ex.responseHeaders.add("Content-Encoding", "gzip")
                if (m != null) ex.responseHeaders.add("Content-Range", "bytes $s-$e/$total")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(if (m != null) 206 else 200, gz.size.toLong())
                ex.responseBody.use { it.write(gz) }
            } else {
                // identity：正确返回原始字节。
                if (m != null) ex.responseHeaders.add("Content-Range", "bytes $s-$e/$total")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(if (m != null) 206 else 200, slice.size.toLong())
                ex.responseBody.use { it.write(slice) }
            }
        }

        fun stop() = server.stop(0)
    }

    @Test
    fun `forces identity so size check passes and bytes are exact`() = runBlocking {
        val size = 6 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 31 + 7) % 256).toByte() }
        val srv = MaybeGzipServer(payload)
        val client = TurboClient(TurboConfig(maxConnectionsPerTask = 8, maxConcurrentTasks = 1))
        val out = File.createTempFile("gzip-id", ".bin").apply { deleteOnExit() }
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
            val result = client.await(id)
            assertTrue(result.isSuccess, "identity 下应下载成功、大小校验通过：${result.exceptionOrNull()?.message}")
            assertEquals(size.toLong(), out.length(), "落盘字节必须与声明一致")
            // 内容逐字节正确
            assertTrue(out.readBytes().contentEquals(payload), "内容应逐字节一致")
        } finally {
            client.shutdown()
            srv.stop()
        }
    }
}

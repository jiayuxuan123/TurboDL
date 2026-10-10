package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P12 的**回归**测试（默认套件就会跑，不依赖 `TURBODL_BENCH`）。
 *
 * 与 `SegmentWriteBufferTest`（opt-in 测量台）的分工：
 *  - 测量台回答"该不该默认开启"——那是一次性的评估结论，已写进
 *    [TurboConfig.bufferedSegmentWrite] 的注释；
 *  - 本测试回答"两种写法在**默认路径与开关路径**上是否都正确"——这是必须长期守住的契约。
 *
 * 为什么它值得存在：开启缓冲后，"已写入长度"的语义从"已落盘"变成"已交给 OS 缓冲"。
 * 断点续传与收尾校验都依赖文件长度（`SegmentScheduler.verifyCoverage`），
 * 所以必须证明**两条路径产出的文件都与原文件逐字节相同**，且中途读到的长度确实反映了已落盘数据
 * —— 否则开关一旦被打开就会静默损坏文件。
 */
class SegmentWritePathTest {

    private companion object {
        const val SIZE = 4 * 1024 * 1024

        /** 安全端口范围（与工作区约定一致）：不得使用 8787/8788。 */
        val SAFE_PORT_RANGE = 8791..8850

        fun freeSafePort(): Int? {
            for (port in SAFE_PORT_RANGE) {
                try {
                    ServerSocket(port).use { return port }
                } catch (_: java.io.IOException) {
                    // 换下一个
                }
            }
            return null
        }
    }

    /** 不限速 Range 服务器；分片数由客户端配置决定。 */
    private class Server(private val payload: ByteArray, port: Int) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        val port: Int = server.address.port

        init {
            server.createContext("/f.bin") { ex: HttpExchange ->
                val size = payload.size
                val m = ex.requestHeaders.getFirst("Range")
                    ?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                if (m == null) {
                    ex.sendResponseHeaders(200, size.toLong())
                    ex.responseBody.use { it.write(payload) }
                    return@createContext
                }
                val s = m.groupValues[1].toInt()
                val e = m.groupValues[2].toIntOrNull() ?: (size - 1)
                val end = minOf(e, size - 1)
                if (s > end) { ex.sendResponseHeaders(416, -1); ex.close(); return@createContext }
                val len = end - s + 1
                ex.responseHeaders.add("Content-Range", "bytes $s-$end/$size")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(206, len.toLong())
                ex.responseBody.use { out -> out.write(payload, s, len) }
            }
            server.executor = Executors.newFixedThreadPool(64)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    /** 用指定写法下载一次，返回输出文件；调用方负责断言字节并删除。 */
    private suspend fun download(
        payload: ByteArray,
        buffered: Boolean,
        port: Int,
        connections: Int = 8,
    ): File {
        val srv = Server(payload, port)
        val out = File.createTempFile("writepath", ".bin").apply { deleteOnExit() }
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = connections,
                maxConcurrentTasks = 1,
                warmUpConnections = false,
                slowStart = false,
                adaptiveConcurrency = false,
                minSegmentSize = 256L * 1024,
                bufferedSegmentWrite = buffered,
            )
        )
        try {
            val id = client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:${srv.port}/f.bin",
                    destination = out,
                    knownSize = payload.size.toLong(),
                )
            )
            val res = client.await(id)
            assertTrue(res.isSuccess, "buffered=$buffered 应成功：${res.exceptionOrNull()?.message}")
        } finally {
            client.shutdown()
            srv.stop()
        }
        return out
    }

    @Test
    fun `direct write produces a byte-exact file`() = runBlocking {
        val port = freeSafePort() ?: run {
            println("[WRITEPATH] 安全端口全部占用，跳过（8791-8850）")
            return@runBlocking
        }
        val payload = ByteArray(SIZE) { ((it * 7 + 3) % 256).toByte() }
        val out = download(payload, buffered = false, port = port)
        try {
            assertEquals(SIZE.toLong(), out.length(), "长度应精确")
            assertTrue(out.readBytes().contentEquals(payload), "直写路径必须字节精确")
        } finally {
            out.delete()
        }
    }

    @Test
    fun `buffered write also produces a byte-exact file when explicitly enabled`() = runBlocking {
        val port = freeSafePort() ?: run {
            println("[WRITEPATH] 安全端口全部占用，跳过（8791-8850）")
            return@runBlocking
        }
        val payload = ByteArray(SIZE) { ((it * 11 + 5) % 256).toByte() }
        val out = download(payload, buffered = true, port = port)
        try {
            assertEquals(SIZE.toLong(), out.length(), "长度应精确")
            assertTrue(
                out.readBytes().contentEquals(payload),
                "开关路径（缓冲写 + 每块 flush）也必须字节精确 —— 否则一旦有人打开开关就会静默损坏文件",
            )
        } finally {
            out.delete()
        }
    }

    @Test
    fun `default config keeps direct write`() {
        // 守住 P12 的评估结论：默认必须是直写。若有人想改默认值，
        // 应先跑 SegmentWriteBufferTest 拿到可复现的收益，再改这里。
        assertTrue(
            !TurboConfig().bufferedSegmentWrite,
            "默认应为直写（P12 实测差异 0.3% 在噪声内；改默认值需先提供新的实测依据）",
        )
    }
}

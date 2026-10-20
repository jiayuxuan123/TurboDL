package dev.turbodl.core

import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 回归测试：**分片失败后必须尽快收尾，且错误要说明真实原因**。
 *
 * ## 背景（本地故障注入实测，2026-10-19）
 *
 * 两个真实网络里常见、旧实现表现很差的形态：
 *
 * 1. **服务器声明 N 字节，实际只发一半就断开**（CDN 边缘超时、中间代理掐断）。
 *    旧实现每个分片耗尽重试预算后才**设置**失败原因，但 worker 循环不看这个原因 ——
 *    其余分片继续各自走完重试，失败判定被**串行化**：实测 2 MiB / 128 KiB（16 片）
 *    **200 秒仍无终态**，期间进度条停在 0%。用户看到的就是「卡死」。
 * 2. **连接被对端重置**：最终报错是「服务器不支持 Range、无响应或响应异常」，
 *    与真实原因毫无关系。
 *
 * ## 为什么不用 com.sun.net.httpserver
 *
 * 它做不到"声明 N 字节但只发一半然后断开"：`sendResponseHeaders(len)` 之后若只写一半就
 * `close()`，它会**挂着等**（或补零），客户端要等到 `readTimeoutMs` 才失败。
 * 实测同一条链接用 CLI 4 秒失败、用这个 API 写的服务器要 200 秒 —— 差别全在服务器端。
 * 所以这里用裸 [ServerSocket] 自己拼 HTTP 响应，写完一半直接 `close()`，
 * 真正做到"确定性短读"。
 *
 * ## 守什么
 *
 * - **时间**：必须在重试预算内收尾（旧实现随分片数线性增长 → 分钟级）；
 * - **原因**：错误里能看出「声明长度与实际不符」/「连接被重置」，而不是笼统兜底文案。
 */
class FailureReportingTest {

    /**
     * 一个只会做一件事的 HTTP 服务器：对每个请求回 206 + 完整 Content-Length，
     * 但只发 [sendBytes] 字节就断开（[rstMode] 为真时用 RST 而非 FIN）。
     */
    private class TruncatingServer(
        private val payload: ByteArray,
        private val sendBytes: Int = 512,
        private val rstMode: Boolean = false,
    ) {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)
        val port: Int get() = server.localPort
        val requests = AtomicInteger(0)

        init {
            thread(isDaemon = true, name = "trunc-srv") {
                while (running.get()) {
                    val sock = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { handle(sock) }
                }
            }
        }

        private fun handle(sock: Socket) {
            try {
                sock.soTimeout = 3_000
                val input = sock.getInputStream().bufferedReader()
                var rangeStart = 0
                var rangeEnd = -1
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        val m = Regex("""bytes=(\d+)-(\d*)""").find(line)
                        if (m != null) {
                            rangeStart = m.groupValues[1].toInt()
                            rangeEnd = m.groupValues[2].toIntOrNull() ?: -1
                        }
                    }
                }
                requests.incrementAndGet()
                val end = if (rangeEnd >= 0) rangeEnd.coerceAtMost(payload.size - 1) else payload.size - 1
                val len = end - rangeStart + 1
                val header = buildString {
                    append("HTTP/1.1 206 Partial Content\r\n")
                    append("Content-Type: application/octet-stream\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Content-Range: bytes $rangeStart-$end/${payload.size}\r\n")
                    append("Content-Length: $len\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray()
                val out = sock.getOutputStream()
                out.write(header)
                // 只发一小部分就断：这是"确定性短读"的关键
                val take = minOf(sendBytes, len)
                out.write(payload, rangeStart, take)
                out.flush()
                if (rstMode) {
                    // SO_LINGER(1,0) → close 时发 RST 而不是 FIN
                    sock.setSoLinger(true, 0)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { sock.close() }
            }
        }

        fun stop() {
            running.set(false)
            runCatching { server.close() }
        }
    }

    private fun config() = TurboConfig(
        maxConnectionsPerTask = 4,
        probeTimeoutMs = 3_000,
        probeRetries = 1,
        maxRetries = 2,
        dynamicSegmentation = false,
        slowStart = false,
        tailAssist = false,
        stallTimeoutMs = 0,
        readTimeoutMs = 3_000,
    )

    private fun runAgainst(srv: TruncatingServer, dir: File): Pair<Boolean, String?> = runBlocking {
        val client = TurboClient(config())
        val id = client.submit(
            DownloadRequest(
                url = "http://127.0.0.1:${srv.port}/f.bin",
                destination = File(dir, "out.bin"),
                connectionsOverride = 4,
            )
        )
        val result = client.await(id)
        client.shutdown()
        result.isSuccess to result.exceptionOrNull()?.message
    }

    @Test
    fun `server that lies about length fails fast with an accurate reason`() {
        val payload = ByteArray(64 * 1024) { (it * 7 + 11).toByte() }
        val srv = TruncatingServer(payload, sendBytes = 512)
        val dir = createTempDirectory("trunc").toFile()
        try {
            val started = System.nanoTime()
            val (ok, error) = runAgainst(srv, dir)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(!ok, "声明长度与实际不符时必须失败，而不是挂住")
            assertTrue(
                elapsedMs < 60_000,
                "必须在重试预算内收尾（应为几秒）；旧实现会随分片数线性增长到分钟级：${elapsedMs}ms",
            )
            val msg = error.orEmpty()
            assertTrue(
                "字节" in msg || "提前" in msg || "断开" in msg || "长度" in msg,
                "错误里应说明「声明长度与实际不符」，实际是：$msg",
            )
            assertTrue(
                "不支持 Range" !in msg,
                "不该归因到 Range（那是另一类问题），实际是：$msg",
            )
        } finally {
            srv.stop()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `connection reset is reported as a reset, not a range problem`() {
        val payload = ByteArray(64 * 1024) { (it * 3 + 5).toByte() }
        val srv = TruncatingServer(payload, sendBytes = 512, rstMode = true)
        val dir = createTempDirectory("rst").toFile()
        try {
            val started = System.nanoTime()
            val (ok, error) = runAgainst(srv, dir)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(!ok, "连接中途被重置时必须失败")
            assertTrue(elapsedMs < 60_000, "必须在预算内收尾：${elapsedMs}ms")
            val msg = error.orEmpty()
            assertTrue(
                "重置" in msg || "提前" in msg || "断开" in msg || "字节" in msg,
                "错误里应能看出是连接问题，实际是：$msg",
            )
            assertTrue("不支持 Range" !in msg, "不该归因到 Range，实际是：$msg")
        } finally {
            srv.stop()
            dir.deleteRecursively()
        }
    }
}

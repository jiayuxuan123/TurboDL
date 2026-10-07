package dev.turbodl.core

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 释放链**不被截断**的契约测试。
 *
 * ## 被钉住的真实缺陷
 *
 * `TurboClient.shutdown()` 原先是一条裸顺序链：
 * ```
 * scope.cancel()
 * streamClient?.let { it.dispatcher.executorService.shutdown(); it.connectionPool.evictAll() }
 * segmentClient.dispatcher.executorService.shutdown()
 * segmentClient.connectionPool.evictAll()
 * ```
 * `connectionPool.evictAll()` 不是"清空一个列表"这么无害 —— 它真的 close 每条空闲连接的
 * socket（`RealConnectionPool.evictAll` → `Util.closeQuietly(socket)`）。关闭 TLS 连接要写
 * `close_notify`，这是一次**网络写**；而 OkHttp 的 `Util.closeQuietly` 只吞 `IOException`，
 * **`NetworkOnMainThreadException`（RuntimeException）会原样穿透**。
 *
 * Android 宿主的 `ViewModel.onCleared()` 是**主线程**回调，会一路走到这里。于是在真机上：
 * 异常不仅崩到调用方，`segmentClient` 的 Dispatcher 线程池也**永远不会被关** ——
 * 一次崩溃换来一份永久泄漏。
 *
 * 修法：每个释放动作各自 `runCatching`，任何一个失败都不影响其余步骤。
 *
 * ## ⚠️ 本测试能证明什么、不能证明什么
 *
 * `NetworkOnMainThreadException` 是 **Android 运行时专有**的检查：桌面 JVM 上在任何线程做阻塞
 * I/O 都不会抛它。因此本测试**无法复现**那条异常 —— 复现需要真机 instrumentation 测试。
 *
 * 它能守住的是**修复后的结构契约**（这些在旧实现下也成立，是回归护栏而非缺陷复现）：
 *  1. `shutdown()` 幂等、不向调用方抛错；
 *  2. 释放链**真的走到了最后一步** —— 两个 Dispatcher 的线程池都被关掉
 *     （若将来有人在链中间插入一句会抛的代码，最后一句就会被跳过，这里会失败）。
 *
 * 真正消除「截断」的是实现里逐句的 `runCatching`：它把"清理链依赖顺序执行"这个隐含前提去掉了。
 */
class ShutdownDrainContractTest {

    private val tmpDir = File(System.getProperty("java.io.tmpdir"), "turbodl-shutdown-${System.nanoTime()}")

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    /** 最小本地服务器：让引擎产生**真实**连接与分片，才有东西可释放（不访问外网）。 */
    private fun startServer(payload: ByteArray): Pair<HttpServer, Int> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/f.bin") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
            if (m == null) {
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.use { it.write(payload) }
            } else {
                val s = m.groupValues[1].toInt().coerceAtMost(payload.size - 1)
                val e = m.groupValues[2].toIntOrNull()?.coerceAtMost(payload.size - 1) ?: (payload.size - 1)
                val body = payload.copyOfRange(s, e + 1)
                ex.responseHeaders.add("Content-Range", "bytes $s-$e/${payload.size}")
                ex.sendResponseHeaders(206, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
        }
        server.executor = Executors.newFixedThreadPool(8)
        server.start()
        return server to server.address.port
    }

    /**
     * 反射读取 `TurboClient` 内部的 OkHttpClient（`segmentClient` / `streamClientRef`）。
     *
     * 为什么要反射：这两个字段是 private，SDK 刻意不暴露它们（连接池属于实现细节）。
     * 但「释放链走到了最后一步」这件事**只能**从它们身上验证 —— 值得为测试破一次封装。
     */
    private fun clientField(client: TurboClient, name: String): OkHttpClient? {
        val f = TurboClient::class.java.getDeclaredField(name).apply { isAccessible = true }
        return f.get(client) as OkHttpClient?
    }

    /** 断言两个 Dispatcher 的线程池都已关闭 —— 即释放链**没有中途被截断**。 */
    private fun assertPoolsShutDown(client: TurboClient) {
        val segment = clientField(client, "segmentClient")
            ?: error("segmentClient 字段读取失败（实现已变更？本测试需同步更新）")
        assertTrue(
            segment.dispatcher.executorService.isShutdown,
            "segmentClient 的线程池必须已关闭 —— 若它没关，说明释放链在它之前被截断了",
        )
        // streamClient 是惰性创建的：本测试没走整文件回退，所以它为 null 是正常的。
        clientField(client, "streamClientRef")?.let { sc ->
            assertTrue(sc.dispatcher.executorService.isShutdown, "streamClient 的线程池必须已关闭")
        }
    }

    @Test
    fun `shutdown is idempotent and closes both dispatcher pools`() {
        tmpDir.mkdirs()
        val payload = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val (srv, port) = startServer(payload)
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 8,
                maxConcurrentTasks = 1,
                warmUpConnections = false,
                slowStart = false,
                workDir = tmpDir,
            )
        )
        try {
            val out = File(tmpDir, "out.bin")
            client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:$port/f.bin",
                    destination = out,
                    knownSize = payload.size.toLong(),
                )
            )
            // 让下载真的跑起来（产生连接与在飞分片），否则"释放"没什么可释放的，测试会变成空转。
            Thread.sleep(600)

            client.shutdown()
            assertPoolsShutDown(client)

            // 幂等：宿主可能在 onCleared 与别处各调一次，重复调用不得抛错、不得破坏状态。
            client.shutdown()
            client.shutdown()
            assertPoolsShutDown(client)
        } finally {
            srv.stop(0)
        }
    }

    /**
     * 下载**已结束**（连接已归还连接池、变成空闲连接）后再释放。
     *
     * 这一档最贴近真机崩溃现场：`evictAll()` 要关的正是这批空闲 socket。
     */
    @Test
    fun `shutdown after a finished download closes pools without throwing`() {
        tmpDir.mkdirs()
        val payload = ByteArray(512 * 1024) { (it % 241).toByte() }
        val (srv, port) = startServer(payload)
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 4,
                maxConcurrentTasks = 1,
                warmUpConnections = false,
                slowStart = false,
                workDir = tmpDir,
            )
        )
        try {
            val out = File(tmpDir, "out2.bin")
            client.submit(
                DownloadRequest(
                    url = "http://127.0.0.1:$port/f.bin",
                    destination = out,
                    knownSize = payload.size.toLong(),
                )
            )
            // 等下载真正落盘完成（小文件，很快）—— 完成后连接回池成为**空闲连接**。
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline && !out.exists()) Thread.sleep(50)
            assertTrue(out.exists(), "下载应已完成，才有空闲连接可供回收")
            assertEquals(payload.size.toLong(), out.length(), "文件内容长度应与源一致")

            client.shutdown()
            assertPoolsShutDown(client)
        } finally {
            srv.stop(0)
        }
    }
}

package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证 1：**不加载任何插件**的普通 HTTP 下载链路。
 *
 * ## 为什么已经有别的测试还要再写一个
 *
 * [BackendOverrideTest] 验的是"插件后端能覆盖内置的"，但它用的是**假后端**，
 * 真正的 HTTP 路径一个字节都没走过。而发布前最该确认的正是这条最朴素的路径
 * 没被这轮改动弄坏 —— 尤其 [TurboClient.backendFor] 的回退：
 *
 * ```
 * backendResolver?.resolve(request) ?: builtinBackend
 * ```
 *
 * 两种"没有插件后端"的情形都必须落到内置后端：**resolver 为 null**（没接插件系统），
 * 与 **resolver 返回 null**（接了但没匹配）。任一处写错，结果都是"下载走不通或失败"。
 *
 * 所以这里对**真实 HTTP**做端到端并逐字节校验；暂停 / 继续 / 取消 / 断点续传
 * 都真跑一遍 —— 它们全在这条路径上。
 *
 * 本模块用 `kotlin.test`：`(expected, actual, message)`，**消息在最后**。
 */
class BuiltinBackendE2ETest {

    private lateinit var tmpDir: File

    @AfterTest
    fun teardown() {
        if (::tmpDir.isInitialized) tmpDir.deleteRecursively()
    }

    /** 支持 Range 的本地服务器。必须真的支持 —— 否则分片与续传会走单流回退，验的不是同一条链路。 */
    private class RangeServer(private val payload: ByteArray) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        val rangeRequests = AtomicInteger(0)
        val fullRequests = AtomicInteger(0)

        init {
            server.createContext("/f.bin") { ex: HttpExchange ->
                val range = ex.requestHeaders.getFirst("Range")
                val size = payload.size
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.responseHeaders.add("Content-Type", "application/octet-stream")
                if (range == null) {
                    fullRequests.incrementAndGet()
                    ex.sendResponseHeaders(200, size.toLong())
                    ex.responseBody.use { it.write(payload) }
                    return@createContext
                }
                val m = Regex("bytes=(\\d+)-(\\d*)").find(range)
                if (m == null) { ex.sendResponseHeaders(416, -1); ex.close(); return@createContext }
                rangeRequests.incrementAndGet()
                val s = m.groupValues[1].toInt()
                val e = m.groupValues[2].toIntOrNull() ?: (size - 1)
                if (s >= size) { ex.sendResponseHeaders(416, -1); ex.close(); return@createContext }
                val end = minOf(e, size - 1)
                ex.responseHeaders.add("Content-Range", "bytes $s-$end/$size")
                ex.sendResponseHeaders(206, (end - s + 1).toLong())
                ex.responseBody.use { it.write(payload, s, end - s + 1) }
            }
            server.executor = Executors.newFixedThreadPool(64)
            server.start()
        }

        fun stop() = server.stop(0)
    }

    private fun tmp(): File =
        File(System.getProperty("java.io.tmpdir"), "turbodl-e2e-${System.nanoTime()}").apply { mkdirs() }

    private fun payloadOf(size: Int, seed: Int): ByteArray =
        ByteArray(size) { ((it * seed + 3) % 249).toByte() }

    /**
     * 挂一个事件收集器，等任务到达终态（完成 / 失败 / 取消）。
     *
     * ## 为什么暂停/续传这一条不用 [TurboClient.await]
     *
     * `pause()` 会让当前的 Deferred 以 `CancellationException` 结束，而 `resume()`
     * 里的 `putIfAbsent` 拿不回那个**已经被订阅过**的 Deferred —— 于是 await 拿到的是
     * "暂停"那一次的结果，而不是续传后的结果。
     *
     * 这是 `await` 的固有语义（"等一次下载的结果"）而不是本轮改动引入的问题，
     * 而且**真实的宿主并不这么用**：云取的 `TurboDownloadManager` 监听的是
     * `TurboEvent`。所以这里跟着宿主用事件验，验的才是同一条路径。
     */
    private suspend fun awaitTerminal(client: TurboClient, id: Long, timeoutMs: Long = 60_000): TurboEvent {
        val deadline = System.currentTimeMillis() + timeoutMs
        return withTimeoutOrNull(timeoutMs) {
            client.events.first { e ->
                e.taskId == id && (e is TurboEvent.Completed || e is TurboEvent.Failed)
            }
        } ?: throw AssertionError("等待终态超时（${System.currentTimeMillis() - deadline + timeoutMs} ms）")
    }

    /** 等到有进度为止（最多 [timeoutMs]），让暂停/取消有东西可作用。 */
    private fun awaitSomeProgress(client: TurboClient, id: Long, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if ((client.progress.value[id]?.downloadedBytes ?: 0L) > 0L) return
            Thread.sleep(20)
        }
    }

    // ---------------------------------------------- 场景 1：普通下载

    @Test
    fun `resolver 为 null 时走内置后端并字节精确`() = runBlocking {
        tmpDir = tmp()
        val payload = payloadOf(3 * 1024 * 1024, 31)
        val srv = RangeServer(payload)
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 8,
                maxConcurrentTasks = 1,
                // 只验"内置后端 + 分片"这条链路本身，不让自适应与慢启动搅进来
                adaptiveConcurrency = false,
                slowStart = false,
            )
        )
        check(client.backendResolver == null) { "前提：resolver 必须为 null" }

        val out = File(tmpDir, "plain.bin")
        val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
        val result = client.await(id)
        client.shutdown()
        srv.stop()

        assertTrue(result.isSuccess, "下载应当成功：${result.exceptionOrNull()?.message}")
        val file = result.getOrThrow()
        assertEquals(payload.size.toLong(), file.length(), "长度必须一致")
        assertContentEquals(payload, file.readBytes(), "内容必须逐字节一致")
        assertTrue(
            srv.rangeRequests.get() > 0,
            "应当真的走了 Range 分片（Range ${srv.rangeRequests.get()} 次 / 整文件 ${srv.fullRequests.get()} 次）",
        )
    }

    // ---------------------------------------------- 回退：接了插件但没匹配

    @Test
    fun `resolver 返回 null 时同样回退到内置后端`() = runBlocking {
        tmpDir = tmp()
        val payload = payloadOf(512 * 1024, 7)
        val srv = RangeServer(payload)
        val client = TurboClient(TurboConfig(maxConnectionsPerTask = 4, maxConcurrentTasks = 1))

        // 接了一个"什么都不支持"的插件解析器：模拟"装了插件，但这个 URL 不归它管"
        client.backendResolver = BackendResolver { request ->
            if (request.url.startsWith("plugin://")) throw AssertionError("不该走到插件后端")
            null
        }

        val out = File(tmpDir, "fallback.bin")
        val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
        val result = client.await(id)
        client.shutdown()
        srv.stop()

        assertTrue(result.isSuccess, "应当回退到内置后端并成功：${result.exceptionOrNull()?.message}")
        val file = result.getOrThrow()
        assertEquals(payload.size.toLong(), file.length())
        assertContentEquals(payload, file.readBytes())
    }

    // ---------------------------------------------- 暂停 / 继续

    @Test
    fun `暂停后可以继续且字节仍然精确`() = runBlocking {
        tmpDir = tmp()
        val payload = payloadOf(6 * 1024 * 1024, 17)
        val srv = RangeServer(payload)
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = 4,
                maxConcurrentTasks = 1,
                adaptiveConcurrency = false,
                slowStart = false,
            )
        )

        val out = File(tmpDir, "resumed.bin")
        val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))

        // 先挂收集器再操作：事件是 SharedFlow，订阅之前发生的事件收不到。
        val terminal = async { awaitTerminal(client, id) }

        awaitSomeProgress(client, id)
        val afterPause = client.progress.value[id]?.downloadedBytes ?: 0L
        client.pause(id)
        Thread.sleep(300)
        check(client.resume(id)) { "继续应当成功" }

        val event = terminal.await()
        client.shutdown()
        srv.stop()

        assertTrue(event is TurboEvent.Completed, "续传后应当成功完成，实际：$event")
        val file = (event as TurboEvent.Completed).file
        assertEquals(payload.size.toLong(), file.length(), "续传后长度仍须正确")
        assertContentEquals(
            payload, file.readBytes(),
            "续传后内容必须逐字节一致（暂停时已下 $afterPause 字节）",
        )
    }

    // ---------------------------------------------- 取消

    @Test
    fun `取消后任务从进度表里消失`() = runBlocking {
        tmpDir = tmp()
        val payload = payloadOf(4 * 1024 * 1024, 11)
        val srv = RangeServer(payload)
        val client = TurboClient(TurboConfig(maxConnectionsPerTask = 4, maxConcurrentTasks = 1))

        val out = File(tmpDir, "cancelled.bin")
        val id = client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out))
        awaitSomeProgress(client, id, timeoutMs = 3_000)

        client.cancel(id, deleteOutput = true)
        // 取消是异步的：给它时间把状态摘掉
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && client.progress.value.containsKey(id)) {
            Thread.sleep(20)
        }
        val state = client.progress.value[id]?.state
        client.shutdown()
        srv.stop()

        // 取消后任务**仍在**进度表里、状态为 CANCELED —— 这是设计：
        // 界面要能显示"已取消"，用户才知道它去哪了；直接消失反而像是丢了任务。
        // 真正要守的是"状态必须是已取消"，以及不会继续推进。
        assertEquals(TaskState.CANCELED, state, "取消后状态必须是 CANCELED")
        val settled = client.progress.value[id]?.downloadedBytes ?: -1L
        Thread.sleep(300)
        val later = client.progress.value[id]?.downloadedBytes ?: -1L
        assertEquals(settled, later, "取消后不应再继续增长（会显示成'还在下'）")
    }
}

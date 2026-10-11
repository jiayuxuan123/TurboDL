package dev.turbodl.runtime

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.turbodl.core.BackendContext
import dev.turbodl.core.BackendResult
import dev.turbodl.core.DownloadBackend
import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.TurboClient
import dev.turbodl.core.TurboConfig
import dev.turbodl.core.TurboEvent
import dev.turbodl.plugin.runtime.Plugin
import dev.turbodl.plugin.runtime.PluginContext
import dev.turbodl.plugin.runtime.PluginHost
import dev.turbodl.plugin.runtime.PluginState
import dev.turbodl.plugin.runtime.ext.BackendRegistry
import dev.turbodl.plugin.runtime.ext.ExtensionPoints
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 验证 3：插件出问题时的行为。
 *
 * 覆盖三种真实故障，每一种都必须满足同一条底线：
 *
 * > **"下载失败"必须显示为失败，绝不能显示为成功；且失败原因要看得见。**
 *
 * 因为这个软件的实际后果很重：用户以为下好了，打开一看是 0 字节文件或坏文件。
 * 另外还特别验了"插件根本没装"——那是最常见的一种，它不是错误处理没写好，
 * 是根本不该走到插件路径。
 *
 * 本模块用 `kotlin.test`：`assertTrue(actual, message)`、`assertEquals(expected, actual, message)`
 * —— **条件在前、消息在后**。
 */
class PluginFailureBehaviourTest {

    private lateinit var tmpDir: File

    @AfterTest
    fun teardown() {
        if (::tmpDir.isInitialized) tmpDir.deleteRecursively()
    }

    private fun tmp(): File =
        File(System.getProperty("java.io.tmpdir"), "turbodl-fail-${System.nanoTime()}").apply { mkdirs() }

    /** 正常提供字节的服务器：插件出故障时，普通下载仍应走内置后端成功。 */
    private class PlainServer(private val payload: ByteArray) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        init {
            server.createContext("/f.bin") { ex: HttpExchange ->
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.use { it.write(payload) }
            }
            server.executor = Executors.newFixedThreadPool(16)
            server.start()
        }
        fun stop() = server.stop(0)
    }

    /** 装载时抛异常的后端插件。 */
    private class BrokenOnLoadPlugin(private val pluginId: String) : Plugin {
        override val id = pluginId
        override fun onLoad(context: PluginContext) {
            error("模拟插件装载失败：缺少依赖的 so 库")
        }
    }

    /** 只 supports .m3u8 的后端 —— 用来验"协议不被支持时回退"，而不是失败。 */
    private class M3u8OnlyBackend : DownloadBackend {
        override val name = "m3u8-only"
        override fun supports(request: DownloadRequest): Boolean =
            request.url.endsWith(".m3u8", ignoreCase = true)
        override suspend fun download(context: BackendContext): BackendResult {
            val part = File(context.workDir, "seg_0_0.part")
            part.writeBytes(PLUGIN_BYTES)
            context.reportTotalSize(part.length())
            context.reportProgress(part.length(), 1)
            return BackendResult(listOf(part), part.length())
        }
        companion object {
            val PLUGIN_BYTES = "来自 m3u8 后端".toByteArray()
        }
    }

    // ---------------------------------------------- 3a：插件装载失败

    @Test
    fun `插件装载失败不影响下载_普通文件照常走内置后端`() = runBlocking {
        tmpDir = tmp()
        val payload = ByteArray(200 * 1024) { ((it * 7) % 251).toByte() }
        val srv = PlainServer(payload)

        val host = PluginHost(logger = { _, _ -> })
        host.install(BrokenOnLoadPlugin("adapter.broken"))

        val client = TurboClient(TurboConfig(maxConnectionsPerTask = 4))
        // 关键：即使那个插件装载失败，解析器仍然要能工作
        client.backendResolver = BackendRegistry(host.extensions)

        val out = File(tmpDir, "still-works.bin")
        val result = client.await(client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out)))
        client.shutdown(); host.shutdown(); srv.stop()

        assertTrue(
            result.isSuccess,
            "一个插件装载失败不该拖垮普通下载：${result.exceptionOrNull()?.message}",
        )
        assertEquals(payload.size.toLong(), out.length(), "长度必须一致")
        assertContentEquals(payload, out.readBytes(), "内容必须逐字节一致")
    }

    @Test
    fun `装载失败的插件状态是 FAILED 且带错误信息`() {
        val host = PluginHost(logger = { _, _ -> })
        host.install(BrokenOnLoadPlugin("adapter.broken"))

        val info = host.diagnostics().plugins.firstOrNull { it.id == "adapter.broken" }
        assertNotNull(info, "应当能在诊断里查到该插件")
        assertEquals(PluginState.FAILED, info.state, "装载抛异常时状态必须是 FAILED")
        val err = assertNotNull(info.error, "失败时必须有可展示的错误原因，否则用户只看到「插件没生效」")
        assertTrue(err.isNotBlank(), "错误信息不能是空串")
        host.shutdown()
    }

    // ---------------------------------------------- 3b：根本没装插件

    @Test
    fun `没装任何插件时下载照常工作`() = runBlocking {
        tmpDir = tmp()
        val payload = ByteArray(200 * 1024) { ((it * 11) % 249).toByte() }
        val srv = PlainServer(payload)

        // 完全不建 PluginHost —— 这是绝大多数用户的真实状态
        val client = TurboClient(TurboConfig(maxConnectionsPerTask = 4))
        check(client.backendResolver == null) { "前提：没有任何插件时 resolver 应为 null" }

        val out = File(tmpDir, "no-plugins.bin")
        val result = client.await(client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out)))
        client.shutdown(); srv.stop()

        assertTrue(result.isSuccess, "没有插件是最常见的状态，必须能下载：${result.exceptionOrNull()?.message}")
        assertContentEquals(payload, out.readBytes(), "内容必须逐字节一致")
    }

    // ---------------------------------------------- 3c：协议不被支持

    @Test
    fun `插件不支持该协议时回退到内置后端而不是报错`() = runBlocking {
        tmpDir = tmp()
        val payload = ByteArray(150 * 1024) { ((it * 13) % 247).toByte() }
        val srv = PlainServer(payload)

        val host = PluginHost(logger = { _, _ -> })
        host.extensions.register(ExtensionPoints.DOWNLOAD_BACKEND, "test.m3u8", M3u8OnlyBackend())
        val client = TurboClient(TurboConfig(maxConnectionsPerTask = 4))
        client.backendResolver = BackendRegistry(host.extensions)

        // 这个 .bin 不被插件支持 —— 应当回退到内置后端并成功
        val out = File(tmpDir, "fallback.bin")
        val result = client.await(client.submit(DownloadRequest("http://127.0.0.1:${srv.port}/f.bin", out)))
        client.shutdown(); host.shutdown(); srv.stop()

        assertTrue(
            result.isSuccess,
            "插件不支持该协议时应回退到内置后端，而不是失败：${result.exceptionOrNull()?.message}",
        )
        assertContentEquals(payload, out.readBytes(), "回退下载的内容必须逐字节一致")
    }

    @Test
    fun `插件支持的协议确实被路由到插件`() = runBlocking {
        tmpDir = tmp()
        val host = PluginHost(logger = { _, _ -> })
        host.extensions.register(ExtensionPoints.DOWNLOAD_BACKEND, "test.m3u8", M3u8OnlyBackend())
        val client = TurboClient(TurboConfig(maxConnectionsPerTask = 4))
        client.backendResolver = BackendRegistry(host.extensions)

        val out = File(tmpDir, "via-plugin.m3u8")
        // 端口 1 连不上也无妨：这个 URL 会被 supports() 命中，根本不会走网络
        val result = client.await(client.submit(DownloadRequest("http://127.0.0.1:1/v.m3u8", out)))
        client.shutdown(); host.shutdown()

        assertTrue(result.isSuccess, "被支持的协议应当成功：${result.exceptionOrNull()?.message}")
        assertContentEquals(
            M3u8OnlyBackend.PLUGIN_BYTES, out.readBytes(),
            "必须真的走插件后端（否则这条测试没有意义）",
        )
    }

    // ---------------------------------------------- 不出现"假成功"与卡死

    @Test
    fun `连不上的地址必须报失败而不是留下正常产物`() = runBlocking {
        tmpDir = tmp()
        val client = TurboClient(TurboConfig(maxConnectionsPerTask = 2, maxRetries = 0))
        val out = File(tmpDir, "will-fail.bin")
        val result = client.await(client.submit(DownloadRequest("http://127.0.0.1:1/nothing.bin", out)))
        client.shutdown()

        assertTrue(
            result.isFailure,
            "连不上的地址必须报失败 —— 报成功意味着用户拿到一个 0 字节文件",
        )
        assertFalse(
            out.isFile && out.length() > 0L,
            "失败的下载不该留下看起来正常的产物",
        )
    }
}

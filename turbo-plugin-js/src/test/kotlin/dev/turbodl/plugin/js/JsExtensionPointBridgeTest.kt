package dev.turbodl.plugin.js

import dev.turbodl.plugin.runtime.PluginHost
import dev.turbodl.plugin.runtime.PluginSource
import dev.turbodl.plugin.runtime.PluginState
import dev.turbodl.plugin.runtime.ext.ExtensionPoints
import dev.turbodl.plugin.runtime.ext.HandledFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 两个新开放的扩展点在 JS 侧的桥接行为：`protocol`（纯声明）与 `fileHandler`（两个方法）。
 *
 * 与 `JsRoutingTest` 同一姿态：全部走真实内核（装 [JsPluginLoaderPlugin]、`loadSource`、从
 * `h.extensions` / `h.protocols` 取回实现），断言只落在**宿主能看到的东西**上，不碰 JS 内部。
 *
 * 这里最要紧的不是"能注册"，而是三条失败语义：
 *  1. `matches` 抛错 → `false`（宿主跳过这个处理器，而不是让下载失败）；
 *  2. `handle` 抛错 → 返回一句说明，**异常不穿透**；
 *  3. 非法 `scheme` → 注册期 `validation`（而不是"加载成功但查不到"）。
 *
 * 引擎不可用的平台上按 JUnit assumption 跳过（与 `JsPluginLoaderTest` 同一约定）。
 */
class JsExtensionPointBridgeTest {

    private fun host(logs: MutableList<String>) =
        PluginHost(logger = { msg, e -> logs.add(msg + (e?.let { " — ${it.message}" } ?: "")) })

    /** 一个 `HandledFile`，字段都是宿主会填的真值，用来验证桥接没有丢字段。 */
    private fun handledFile() = HandledFile(
        taskId = 42L,
        absolutePath = "/tmp/turbodl/movie.mp4",
        fileName = "movie.mp4",
        sizeBytes = 1_048_576L,
        mimeType = "video/mp4",
        extension = "mp4",
        sourceUrl = "https://cdn.example/movie.mp4?token=secret",
    )

    // ---------------------------------------------------------------- protocol

    @Test
    fun `a registered protocol claim is visible in the scheme index`() {
        JsEngineProbe.requireEngine()
        val logs = mutableListOf<String>()
        val h = host(logs)
        h.install(JsPluginLoaderPlugin())
        h.loadSource(
            PluginSource(
                "js",
                "inline:" + "plugin.registerProtocol({ scheme: 'acme', label: 'acme-adapter', priority: 700 });",
                mapOf("pluginId" to "backend.acme"),
            ),
        )

        // 通用扩展注册表里能看到它（priority 也照实登记）。
        val reg = h.extensions.registrations(ExtensionPoints.PROTOCOL_HANDLER).single()
        assertEquals("backend.acme", reg.ownerPluginId)
        assertEquals(700, reg.priority)

        // 且 scheme 索引查得到 —— 这才是这个扩展点的用途。
        val claim = h.protocols.claimsFor("acme").single()
        assertEquals("acme", claim.scheme)
        assertEquals("backend.acme", claim.pluginId, "声明者必须是宿主给的 id：logs=$logs")
        assertEquals("acme-adapter", claim.label)
        assertEquals(700, claim.priority)
        assertEquals("backend.acme", h.protocols.resolve("acme://share/1")?.pluginId)
        h.shutdown()
    }

    @Test
    fun `a claim is normalized like a url scheme and a plugin may declare several`() {
        JsEngineProbe.requireEngine()
        val logs = mutableListOf<String>()
        val h = host(logs)
        h.install(JsPluginLoaderPlugin())
        // "one plugin, many protocols" 是生态模型：一个脚本声明多个 scheme 必须被支持。
        // 大小写与空白由注册期归一化（registry 也会归一化，但在这里就做掉，查询才是可预期的）。
        h.loadSource(
            PluginSource(
                "js",
                "inline:" + """
                    plugin.registerProtocol({ scheme: '  ACME  ' });
                    plugin.registerProtocol({ scheme: 'acme+bt' });
                    plugin.registerProtocol({ scheme: 'ed2k' });
                """.trimIndent(),
                mapOf("pluginId" to "backend.p2p"),
            ),
        )
        assertEquals(3, h.extensions.all(ExtensionPoints.PROTOCOL_HANDLER).size)
        for (scheme in listOf("acme", "acme+bt", "ed2k")) {
            assertEquals("backend.p2p", h.protocols.claimsFor(scheme).single().pluginId, "scheme=$scheme")
        }
        assertEquals("acme", h.protocols.claimsFor("AcMe").single().scheme, "查询侧同样归一化")
        h.shutdown()
    }

    @Test
    fun `the declaring plugin id cannot be forged by the script`() {
        JsEngineProbe.requireEngine()
        val logs = mutableListOf<String>()
        val h = host(logs)
        h.install(JsPluginLoaderPlugin())
        // 脚本改写自己的 `plugin.id` 与 options 里的任何字段都无用：claim 的 pluginId 由桥接从
        // 宿主给的实例 id 填。市场与诊断展示的"声明者"不能是脚本自报的。
        h.loadSource(
            PluginSource(
                "js",
                "inline:" + """
                    plugin.id = 'net.core';
                    plugin.registerProtocol({ scheme: 'hls', pluginId: 'net.core' });
                """.trimIndent(),
                mapOf("pluginId" to "parser.liar"),
            ),
        )
        assertEquals("parser.liar", h.protocols.claimsFor("hls").single().pluginId)
        h.shutdown()
    }

    @Test
    fun `a claim without a usable scheme fails as validation at registration time`() {
        JsEngineProbe.requireEngine()
        val logs = mutableListOf<String>()
        val h = host(logs)
        h.install(JsPluginLoaderPlugin())
        // 四种坏输入：缺失、空串、非法字符、超长。前三种是作者手误，第四种是越界。
        // 用原语注册才能一次看到四种的**信封**（`plugin.registerProtocol` 会抛，见下一个用例）。
        h.loadSource(
            PluginSource(
                "js",
                "inline:" + """
                    var cases = [
                      '{}',
                      JSON.stringify({scheme: '   '}),
                      JSON.stringify({scheme: 'has space'}),
                      JSON.stringify({scheme: 'x'.repeat(40)})
                    ];
                    for (var i = 0; i < cases.length; i++) {
                      var env = JSON.parse(__turbodlRegister('protocol', 0, cases[i]));
                      host.log.info('RE.' + i + ':' + env.ok + '|' + env.error.code + '|' + String(env.error.message).slice(0, 60));
                    }
                """.trimIndent(),
                mapOf("pluginId" to "backend.badclaim"),
            ),
        )
        for (i in 0 until 4) {
            val line = logs.first { it.startsWith("[backend.badclaim] [info] RE.$i:") }
                .substringAfter("RE.$i:").trim()
            assertTrue(line.startsWith("false|validation|"), "case $i must be a validation refusal: $line")
            assertTrue("scheme" in line, "the refusal must name the field for case $i: $line")
        }
        // 一条都没登记，因此索引里既没有 scheme 也没有半成品条目。
        assertTrue(h.extensions.all(ExtensionPoints.PROTOCOL_HANDLER).isEmpty(), "logs=$logs")
        assertTrue(h.protocols.allClaims().isEmpty())
        h.shutdown()
    }

    @Test
    fun `a propagated claim refusal fails the load instead of leaving a silent plugin`() {
        JsEngineProbe.requireEngine()
        val logs = mutableListOf<String>()
        val h = host(logs)
        h.install(JsPluginLoaderPlugin())
        // `plugin.registerProtocol` 走 nativeRegister，失败会变成脚本可 catch 的 Error。不 catch
        // 就是一次干净的加载失败 —— 好于"装上了，但任何 scheme 都查不到它"。
        val ids = h.loadSource(
            PluginSource(
                "js",
                "inline:" + "plugin.registerProtocol({ label: 'no scheme here' });",
                mapOf("pluginId" to "backend.noscheme"),
            ),
        )
        assertEquals(listOf("backend.noscheme"), ids, "loader 负责安装，握手负责判定失败")
        val info = h.diagnostics().plugins.single { it.id == "backend.noscheme" }
        assertEquals(PluginState.FAILED, info.state, "error=${info.error}")
        assertTrue("scheme" in (info.error ?: ""), "错误必须点名字段：${info.error}")
        assertTrue(h.extensions.all(ExtensionPoints.PROTOCOL_HANDLER).isEmpty())
        h.shutdown()
    }

    // ---------------------------------------------------------------- file handler

    @Test
    fun `matches true and false are both bridged and the file fields reach the script`() {
        JsEngineProbe.requireEngine()
        val logs = mutableListOf<String>()
        val h = host(logs)
        h.install(JsPluginLoaderPlugin())
        // 只接 mp4，且把宿主给的字段拼进返回值 —— 一个字段没传到就会在这里露出来。
        h.loadSource(
            PluginSource(
                "js",
                "inline:" + """
                    plugin.registerFileHandler({
                      id: 'player',
                      name: 'Video Player',
                      matches: function (file) { return file.extension === 'mp4'; },
                      handle: function (file) {
                        return 'opened ' + file.fileName + ' (' + file.sizeBytes + 'B, ' + file.mimeType +
                               ', task ' + file.taskId + ', at ' + file.absolutePath + ', from ' + file.sourceUrl + ')';
                      }
                    });
                """.trimIndent(),
                mapOf("pluginId" to "handler.player"),
            ),
        )
        val handler = h.extensions.all(ExtensionPoints.FILE_HANDLER).single()
        assertEquals("handler.player:player", handler.id, "脚本自报的 id 带宿主前缀，不能冒充别的插件")
        assertEquals("Video Player", handler.name)

        assertTrue(handler.matches(handledFile()), "extension=mp4 must match: logs=$logs")
        assertFalse(
            handler.matches(handledFile().copy(fileName = "photo.png", extension = "png")),
            "extension=png must not match",
        )

        val message = handler.handle(handledFile())
        // 七个字段全部原样到达：路径与 sourceUrl 是原样传出的（理由见 JsExtensionBridge.handledFileToMap）。
        assertEquals(
            "opened movie.mp4 (1048576B, video/mp4, task 42, at /tmp/turbodl/movie.mp4, " +
                "from https://cdn.example/movie.mp4?token=secret)",
            message,
        )
        h.shutdown()
    }

    @Test
    fun `a throwing matches is false and a throwing handle returns a message instead of propagating`() {
        JsEngineProbe.requireEngine()
        val logs = mutableListOf<String>()
        val h = host(logs)
        h.install(JsPluginLoaderPlugin())
        h.loadSource(
            PluginSource(
                "js",
                "inline:" + """
                    plugin.registerFileHandler({
                      matches: function () { throw new Error('matches blew up'); },
                      handle: function (file) { throw new Error('handle blew up on ' + file.fileName); }
                    });
                """.trimIndent(),
                mapOf("pluginId" to "handler.broken"),
            ),
        )
        val handler = h.extensions.all(ExtensionPoints.FILE_HANDLER).single()

        // 挂载点的约定是"处理器出错不影响已完成的下载"，所以这两个方法都不许把异常交给宿主。
        val matched = runCatching { handler.matches(handledFile()) }
        assertEquals(true, matched.isSuccess, "matches 不得抛：${matched.exceptionOrNull()}")
        assertFalse(matched.getOrThrow(), "抛错的 matches 必须当作'不接这个文件'")

        val handled = runCatching { handler.handle(handledFile()) }
        assertEquals(true, handled.isSuccess, "handle 不得抛：${handled.exceptionOrNull()}")
        val message = handled.getOrThrow()
        assertTrue(message.isNotBlank(), "必须给出可展示的说明，而不是空串")
        assertTrue("movie.mp4" in message, "说明里应指出是哪个文件：$message")

        // 两条路径都要在日志里留下痕迹（带 code、handler id 与插件自己的错误文本），
        // 否则作者无从知道自己的处理器坏了。
        assertTrue(
            logs.any { "failed in matches()" in it && "handler.broken" in it && "matches blew up" in it },
            "matches 失败必须记日志并带上原因：$logs",
        )
        assertTrue(
            logs.any { "failed in handle()" in it && "handler.broken" in it && "handle blew up on movie.mp4" in it },
            "handle 失败必须记日志并带上原因：$logs",
        )
        h.shutdown()
    }

    @Test
    fun `a handler that returns no message still produces one and a missing method is refused`() {
        JsEngineProbe.requireEngine()
        val logs = mutableListOf<String>()
        val h = host(logs)
        h.install(JsPluginLoaderPlugin())
        h.loadSource(
            PluginSource(
                "js",
                "inline:" + """
                    plugin.registerFileHandler({
                      matches: function () { return 1; },
                      handle: function () { return undefined; }
                    });
                """.trimIndent(),
                mapOf("pluginId" to "handler.quiet"),
            ),
        )
        val handler = h.extensions.all(ExtensionPoints.FILE_HANDLER).single()
        // 数字 1 是 JS 的 truthy：matches 的闸门语义按 JS 判真（理由见 JsExtensionBridge.jsTruthy）。
        assertTrue(handler.matches(handledFile()))
        // `undefined` 不能被 String() 成字面量 "undefined" 塞进日志；宿主替它说一句实话。
        val message = handler.handle(handledFile())
        assertTrue(message.isNotBlank() && "undefined" !in message, "actual: $message")

        // 缺方法要在注册期就失败（`plugin.registerFileHandler` 自己检查），而不是等到调用时才发现。
        h.loadSource(
            PluginSource(
                "js",
                "inline:" + "plugin.registerFileHandler({ matches: function () { return true; } });",
                mapOf("pluginId" to "handler.incomplete"),
            ),
        )
        val info = h.diagnostics().plugins.single { it.id == "handler.incomplete" }
        assertEquals(PluginState.FAILED, info.state, "error=${info.error}")
        assertTrue("handle" in (info.error ?: ""), "错误应指出缺哪个方法：${info.error}")
        assertEquals(1, h.extensions.all(ExtensionPoints.FILE_HANDLER).size, "只有完整的那个被登记")
        h.shutdown()
    }

    // ---------------------------------------------------------------- unload

    @Test
    fun `unloading a plugin releases its protocol claim and its file handler`() {
        JsEngineProbe.requireEngine()
        val logs = mutableListOf<String>()
        val h = host(logs)
        h.install(JsPluginLoaderPlugin())
        val script = """
            plugin.registerProtocol({ scheme: 'gone' });
            plugin.registerFileHandler({
              matches: function () { return true; },
              handle: function (file) { return 'handled ' + file.fileName; }
            });
        """.trimIndent()
        h.loadSource(PluginSource("js", "inline:$script", mapOf("pluginId" to "plugin.a")))
        h.loadSource(PluginSource("js", "inline:$script", mapOf("pluginId" to "plugin.b")))
        assertEquals(2, h.extensions.all(ExtensionPoints.FILE_HANDLER).size)
        assertEquals(2, h.protocols.claimsFor("gone").size, "两个插件声明同一个 scheme 是可共存的")

        // 留住引用，验证"卸载后是惰性 no-op"而不是"抛异常"：这是 deactivateAll 的契约。
        val survivor = h.extensions.all(ExtensionPoints.FILE_HANDLER).first()
        h.uninstall("plugin.a")

        assertEquals(listOf("plugin.b"), h.extensions.registrations(ExtensionPoints.PROTOCOL_HANDLER).map { it.ownerPluginId })
        assertEquals(listOf("plugin.b"), h.protocols.claimsFor("gone").map { it.pluginId }, "卸载的声明必须从索引里消失")
        assertEquals(1, h.extensions.all(ExtensionPoints.FILE_HANDLER).size)

        // 离场的那个：matches → false，handle → 一句说明，都不许抛。
        val matched = runCatching { survivor.matches(handledFile()) }
        assertEquals(true, matched.isSuccess, "卸载后 matches 不得抛：${matched.exceptionOrNull()}")
        assertFalse(matched.getOrThrow(), "已卸载的处理器不得再接手文件")
        val handled = runCatching { survivor.handle(handledFile()) }
        assertEquals(true, handled.isSuccess, "卸载后 handle 不得抛：${handled.exceptionOrNull()}")
        assertTrue(handled.getOrThrow().isNotBlank(), "actual: ${handled.getOrThrow()}")

        assertFalse(logs.any { "RUNTIME LEAKED" in it }, "unload 必须走完 dispose：$logs")
        h.shutdown()
    }
}

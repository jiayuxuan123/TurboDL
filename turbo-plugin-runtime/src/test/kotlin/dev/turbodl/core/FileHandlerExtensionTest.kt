package dev.turbodl.core

import dev.turbodl.plugin.runtime.ExtensionPointKey
import dev.turbodl.plugin.runtime.ExtensionRegistry
import dev.turbodl.plugin.runtime.ext.ExtensionPoints
import dev.turbodl.plugin.runtime.ext.FileHandler
import dev.turbodl.plugin.runtime.ext.HandledFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 文件处理器挂载点（P16）。
 *
 * 这一项的价值：**下载完成后可以把文件交给插件处理**（播放器、导入相册…），
 * 而宿主不需要知道任何一个具体处理器的存在。
 *
 * ## 本模块的断言签名（别再搞混）
 *
 * 这个模块用 `kotlin.test`，签名是：
 * ```
 * assertTrue(actual: Boolean, message: String = "")
 * assertEquals(expected: T, actual: T, message: String = "")
 * ```
 * **消息在最后**。与 JUnit 的 `assertTrue(message, condition)` 正好相反 ——
 * 这个模块与 App 模块（用 JUnit，消息在前）的顺序是反的，两边都搞错过。
 */
class FileHandlerExtensionTest {

    private fun file(
        name: String = "a.mp4",
        mime: String = "video/mp4",
        ext: String = "mp4",
    ) = HandledFile(
        taskId = 1L,
        absolutePath = "/storage/emulated/0/Download/$name",
        fileName = name,
        sizeBytes = 1024,
        mimeType = mime,
        extension = ext,
        sourceUrl = "https://example.com/$name",
    )

    private class VideoPlayer : FileHandler {
        override val id = "handler.video-player"
        override val name = "视频播放器"
        override fun matches(file: HandledFile) =
            file.extension in setOf("mp4", "mkv", "webm")
        override fun handle(file: HandledFile) = "已用播放器打开 ${file.fileName}"
    }

    // ---------------------------------------------- 挂载点

    @Test
    fun `挂载点 id 符合命名空间规范`() {
        val id = ExtensionPoints.FILE_HANDLER.id
        assertTrue(
            id.contains(".cap."),
            "文件处理器是跨插件契约，必须带命名空间（P3）：$id",
        )
        assertEquals("dev.turbodl.cap.file.handler", id)
        assertFalse(id.startsWith("turbo."), "不能与引擎内部钩子共用前缀：$id")
        assertFalse(
            id.contains("yung"),
            "SDK 的公共挂载点里不能出现宿主名（`yung` 是云取的名字，" +
                "写进 SDK 等于告诉别的使用者『只有云取认这个点』）：$id",
        )
    }

    @Test
    fun `与 postHook 是两个不同的挂载点`() {
        // 二者能力不同：postHook 拿到的是字符串（成败 + 路径），
        // FileHandler 拿到的是已验过的最终文件（含 MIME、大小）。
        // 合成一个会让每个处理器都重写一遍宿主已经算好的东西。
        val postHook: ExtensionPointKey<*> = ExtensionPoints.TASK_POST_HOOK
        val fileHandler: ExtensionPointKey<*> = ExtensionPoints.FILE_HANDLER
        assertNotEquals(postHook, fileHandler)
        assertNotEquals<Class<*>>(
            postHook.type,
            fileHandler.type,
            "key 的 type 不同，挂载点才不会互相串",
        )
    }

    // ---------------------------------------------- 注册与释放

    @Test
    fun `注册后能被查到`() {
        val reg = ExtensionRegistry()
        reg.register(ExtensionPoints.FILE_HANDLER, "handler.video-player", VideoPlayer())

        val regs = reg.registrations(ExtensionPoints.FILE_HANDLER)
        assertEquals(1, regs.size)
        assertEquals("handler.video-player", regs.first().ownerPluginId)
    }

    @Test
    fun `注销后注册项不再存在`() {
        val reg = ExtensionRegistry()
        val handle = reg.register(ExtensionPoints.FILE_HANDLER, "handler.video-player", VideoPlayer())
        assertEquals(1, reg.registrations(ExtensionPoints.FILE_HANDLER).size)

        handle.cancel()
        assertEquals(
            0,
            reg.registrations(ExtensionPoints.FILE_HANDLER).size,
            "注销后必须真的从表里消失 —— 否则卸载的插件仍会收到文件",
        )
    }

    @Test
    fun `多个处理器按优先级排序`() {
        val reg = ExtensionRegistry()
        class Prio(private val myPrio: Int) : FileHandler {
            override val id = "h$myPrio"
            override val name = "处理器 $myPrio"
            override fun matches(file: HandledFile) = true
            override fun handle(file: HandledFile) = id
        }
        reg.register(ExtensionPoints.FILE_HANDLER, "low", Prio(1), priority = 1)
        reg.register(ExtensionPoints.FILE_HANDLER, "high", Prio(10), priority = 10)

        val order: List<String> =
            reg.registrations(ExtensionPoints.FILE_HANDLER).map { it.ownerPluginId }
        assertEquals(listOf("high", "low"), order, "高优先级应排在前面")
    }

    // ---------------------------------------------- matches 的作用

    @Test
    fun `matches 决定处理器会不会被调用`() {
        val p = VideoPlayer()
        assertTrue(p.matches(file()), "视频应被接受")
        assertFalse(
            p.matches(file(name = "a.png", mime = "image/png", ext = "png")),
            "图片不该被视频播放器接收 —— 少了这一步，处理器就得处理一切",
        )
    }

    @Test
    fun `空 MIME 是合法输入不得猜`() {
        // 宿主给不出 MIME 时传空串，处理器自己按扩展名判断。
        // 这里锁住"空 MIME 合法"，防止将来有人改成编一个默认值 ——
        // 猜错会让处理器选错（.mp4 判成 video/quicktime 之类）。
        val unknown = file(mime = "", ext = "bin")
        assertEquals("", unknown.mimeType)
        assertFalse(VideoPlayer().matches(unknown), "扩展名不在支持列表里就不该处理")
    }

    // ---------------------------------------------- HandledFile 是只读描述

    @Test
    fun `HandledFile 携带处理器判定所需的信息`() {
        val f = file()
        assertEquals("/storage/emulated/0/Download/a.mp4", f.absolutePath)
        assertEquals("a.mp4", f.fileName)
        assertEquals("video/mp4", f.mimeType)
        assertEquals("mp4", f.extension)
        assertEquals(1024L, f.sizeBytes)
    }
}

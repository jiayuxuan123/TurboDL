package dev.turbodl.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 分片存储的自身行为：进度记录的写入 / 读回 / 失效判定。
 *
 * 这层是续传的地基 —— 记录写不进去、或读回来对不上，续传就会静默退化成"从头下"。
 * 而"从头下"在用户看来只是"慢"，不会报错，所以只能靠这些用例兜住。
 */
class SegmentStoreTest {

    private fun tmpDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "tdlp-$tag-${System.nanoTime()}")
            .apply { mkdirs(); deleteOnExit() }

    private fun store(dir: File, total: Long) = SegmentStore(
        dest = File(dir, "out.bin"),
        stateFile = File(dir, "progress.tdlp"),
        total = total,
    )

    @Test
    fun `进度写进去能原样读回来`() {
        val dir = tmpDir("roundtrip")
        val s = store(dir, 1000)
        s.open()
        try {
            val pieces = s.buildGrid(300) // 300,300,300,100
            assertEquals(4, pieces.size)
            assertEquals(1000L, pieces.sumOf { it.length })

            // 写前两片
            val buf = ByteArray(300) { 7 }
            assertEquals(300, s.writeAt(0, buf, 0, 300))
            pieces[0].written = 300
            assertEquals(300, s.writeAt(300, buf, 0, 300))
            pieces[1].written = 300
            s.persist(pieces)
        } finally {
            s.close()
        }

        // 重新打开（模拟"下次续传"）
        val s2 = store(dir, 1000)
        s2.open()
        try {
            val loaded = assertNotNull(s2.peek(1000), "应当能读回进度")
            assertEquals(4, loaded.size)
            assertEquals(300L, loaded[0].written)
            assertEquals(300L, loaded[1].written)
            assertEquals(0L, loaded[2].written)
            assertEquals(600L, loaded.sumOf { it.written })
            assertEquals(0L, loaded[0].start)
            assertEquals(999L, loaded.last().end)   // 末片 end 是闭区间
        } finally {
            s2.close()
        }
    }

    @Test
    fun `目标文件被删掉时进度判定为失效`() {
        val dir = tmpDir("deleted")
        val s = store(dir, 500)
        s.open()
        try {
            val pieces = s.buildGrid(500)
            s.writeAt(0, ByteArray(500), 0, 500)
            pieces[0].written = 500
            s.persist(pieces)
        } finally {
            s.close()
        }
        // 用户把下了一半的文件删了：侧车还在，但数据没了 → 绝不能在这上面"续传"。
        val dest = File(dir, "out.bin")
        assertTrue(dest.delete(), "前提：目标文件确实被删掉")

        val s2 = store(dir, 500)
        s2.open()
        try {
            assertNull(s2.peek(500), "目标文件不在时不应认定有进度")
            val grid = s2.loadOrCreate(500)
            assertEquals(0L, grid.sumOf { it.written }, "loadOrCreate 必须给出全新网格")
        } finally {
            s2.close()
        }
    }

    @Test
    fun `总大小变了时旧进度作废`() {
        val dir = tmpDir("totalsize")
        val s = store(dir, 800)
        s.open()
        try {
            val pieces = s.buildGrid(400)
            s.writeAt(0, ByteArray(400), 0, 400)
            pieces[0].written = 400
            s.persist(pieces)
        } finally {
            s.close()
        }
        val s2 = store(dir, 900)   // 服务器上的文件大小变了
        s2.open()
        try {
            assertNull(s2.peek(900), "总大小不符时不该复用旧网格")
        } finally {
            s2.close()
        }
    }

    @Test
    fun `写入位置可以乱序且互不覆盖`() {
        val dir = tmpDir("scatter")
        val s = store(dir, 300)
        s.open()
        try {
            // 三个分片倒着写，验证位置写（pwrite）不共享通道游标。
            s.writeAt(200, ByteArray(100) { 3 }, 0, 100)
            s.writeAt(0, ByteArray(100) { 1 }, 0, 100)
            s.writeAt(100, ByteArray(100) { 2 }, 0, 100)
            val got = s.readAt(0, 300)
            assertEquals(300, got.size)
            assertEquals(1.toByte(), got[0])
            assertEquals(1.toByte(), got[99])
            assertEquals(2.toByte(), got[100])
            assertEquals(3.toByte(), got[200])
            assertEquals(3.toByte(), got[299])
        } finally {
            s.close()
        }
    }

    @Test
    fun `损坏的侧车不会让引擎崩溃`() {
        val dir = tmpDir("corrupt")
        File(dir, "progress.tdlp").writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val s = store(dir, 100)
        s.open()
        try {
            assertNull(s.peek(100), "坏记录应当被忽略")
            val grid = s.loadOrCreate(50)
            assertEquals(2, grid.size)
            assertEquals(100L, grid.sumOf { it.length })
        } finally {
            s.close()
        }
    }
}

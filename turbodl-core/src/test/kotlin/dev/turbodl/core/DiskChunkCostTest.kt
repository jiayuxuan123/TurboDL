package dev.turbodl.core

import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test

/**
 * 磁盘侧的微基准：分片文件的「创建 + 写入 + 关闭」到底要多少钱。
 *
 * ## 为什么单独测它
 *
 * `EngineOverheadTest` 显示：同样的 128~256 个并发 Range 请求，
 * 纯 OkHttp 能跑 160~210 MB/s，而引擎只有 20~28 MB/s。
 * 两者最大的差别之一是**引擎每个分片都要建一个文件、打开、写入、关闭**，
 * 下载完还要把它们再读一遍合并。
 *
 * 这个用例把那段开销单独量出来：同样大小、同样的分片数，只做文件 I/O。
 * 若它本身就吃掉几百毫秒，那么"每个分片一个文件"这个结构在高速链路上
 * 就是净损失 —— 这正是 aria2/IDM 选择**直接按偏移写目标文件**的原因。
 *
 * 默认跳过（`TURBODL_BENCH=1` 启用）。
 */
class DiskChunkCostTest {

    private companion object {
        const val TOTAL = 16 * 1024 * 1024
        const val REPEAT = 3
    }

    private fun writeSegments(dir: File, count: Int): Long {
        val block = TOTAL / count
        val buf = ByteArray(block)
        var bytes = 0L
        for (i in 0 until count) {
            val f = File(dir, "seg_${i * block}_${(i + 1) * block - 1}.part")
            RandomAccessFile(f, "rw").use { raf ->
                raf.seek(0)
                raf.write(buf)
                // 不 fsync：引擎的 writeSlice 也不 fsync（只 close）。
                // 加了 fsync 会把"每次落盘等待"算进来，那是测试自己引入的开销。
            }
            bytes += block
        }
        return bytes
    }

    private fun mergeSegments(dir: File, count: Int, dest: File): Long {
        val buf = ByteArray(256 * 1024)
        var total = 0L
        RandomAccessFile(dest, "rw").use { out ->
            for (i in 0 until count) {
                val block = TOTAL / count
                val f = File(dir, "seg_${i * block}_${(i + 1) * block - 1}.part")
                f.inputStream().use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        total += n
                    }
                }
            }
        }
        return total
    }

    /** 直接按偏移写同一个目标文件（aria2/IDM 的做法）。 */
    private fun pwriteDirect(dest: File, count: Int): Long {
        val block = TOTAL / count
        val buf = ByteArray(block)
        var bytes = 0L
        RandomAccessFile(dest, "rw").use { raf ->
            raf.setLength(TOTAL.toLong())
            for (i in 0 until count) {
                raf.seek(i.toLong() * block)
                raf.write(buf)
                bytes += block
            }
        }
        return bytes
    }

    @Test
    fun `chunk file cost`() {
        if (System.getenv("TURBODL_BENCH") != "1") {
            println("[DISK] 跳过（设置 TURBODL_BENCH=1 启用）")
            return
        }
        println("================================================================")
        println("[DISK] 数据量 ${TOTAL / 1024 / 1024}MB")
        for (count in listOf(64, 128, 512)) {
            val segMs = (1..REPEAT).map {
                val dir = File.createTempFile("segs", "").let { f -> f.delete(); f.mkdirs(); f }
                val t0 = System.nanoTime()
                writeSegments(dir, count)
                val ms = (System.nanoTime() - t0) / 1_000_000
                dir.deleteRecursively()
                ms
            }.sorted()[REPEAT / 2]

            val mergeMs = (1..REPEAT).map {
                val dir = File.createTempFile("segs", "").let { f -> f.delete(); f.mkdirs(); f }
                writeSegments(dir, count)
                val dest = File.createTempFile("merged", ".bin")
                val t0 = System.nanoTime()
                mergeSegments(dir, count, dest)
                val ms = (System.nanoTime() - t0) / 1_000_000
                dir.deleteRecursively(); dest.delete()
                ms
            }.sorted()[REPEAT / 2]

            val directMs = (1..REPEAT).map {
                val dest = File.createTempFile("direct", ".bin")
                val t0 = System.nanoTime()
                pwriteDirect(dest, count)
                val ms = (System.nanoTime() - t0) / 1_000_000
                dest.delete()
                ms
            }.sorted()[REPEAT / 2]

            println("  分片数 $count：")
            println("    分片文件 创建+写      : ${segMs}ms  (${TOTAL / 1024 / 1024 * 1000L / maxOf(segMs, 1)} MB/s)")
            println("    收尾合并 读+写        : ${mergeMs}ms  (${TOTAL / 1024 / 1024 * 1000L / maxOf(mergeMs, 1)} MB/s)")
            println("    直写目标文件(按偏移)  : ${directMs}ms  (${TOTAL / 1024 / 1024 * 1000L / maxOf(directMs, 1)} MB/s)")
            println("    → 分片方案合计        : ${segMs + mergeMs}ms   直写省掉 ${segMs + mergeMs - directMs}ms")
        }
        println("================================================================")
    }
}

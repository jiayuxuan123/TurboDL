package dev.turbodl.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * 分片合并与「下载中预先合并」。
 *
 * 本文件有两块能力：
 *  1. [PartMerger.merge] —— 传统的收尾合并（并行版）
 */
internal object PartMerger {

    /** 并行度上限：再高也只是抢同一个磁盘队列。 */
    private const val MAX_THREADS = 8

    /** 单线程处理的字节数下限：低于此值不值得并行。 */
    private const val MIN_BYTES_PER_THREAD = 8L * 1024 * 1024

    /**
     * 合并 [parts] 到 [target]。
     *
     * @param onProgress 可选进度回调：(已合并字节, 总字节)。
     * @param parallel 是否并行（默认并行；小文件/单核场景可关掉）
     * @param offsets 每个分片在目标文件中的起始偏移（与 [parts] 一一对应）。
     *   为 null 时按**累加**推导（等价于顺序拼接）。
     *
     * 【为什么允许显式偏移】收尾托管会让在飞分片提前收工，其文件长度可能超过让出点，
     * 与接手的分片**重叠**。显式偏移让重叠区间落到同一位置 → 写入幂等、结果仍正确；
     * 若按累加拼接，重叠会被算两次（长度校验失败）。
     */
    fun merge(
        parts: List<File>,
        target: File,
        onProgress: ((merged: Long, total: Long) -> Unit)? = null,
        parallel: Boolean = true,
        offsets: List<Long>? = null,
    ): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val hasOffsets = offsets != null && offsets.size == parts.size

        if (parts.size <= 1) {
            val ok = mergeSequential(parts, target, onProgress, offsets)
            if (ok) {
                val n = if (hasOffsets) offsets!!.maxOf { it } + parts[0].length() else parts[0].length()
                onProgress?.invoke(n, n)
            }
            return@runCatching ok
        }

        val spans = ArrayList<Pair<File, Long>>(parts.size)
        if (hasOffsets) {
            for (i in parts.indices) spans.add(parts[i] to offsets!![i])
        } else {
            var off = 0L
            for (p in parts) {
                spans.add(p to off)
                off += p.length()
            }
        }
        val totalBytes = if (hasOffsets) {
            spans.maxOf { (f, o) -> o + f.length() }
        } else {
            parts.sumOf { it.length() }
        }

        val threads = if (!parallel) 1 else {
            val byBytes = (totalBytes / MIN_BYTES_PER_THREAD).toInt().coerceAtLeast(1)
            minOf(MAX_THREADS, byBytes, parts.size)
        }

        val merged = if (threads <= 1) {
            mergeSequential(parts, target, onProgress, offsets)
            totalBytes
        } else {
            mergeParallel(target, spans, totalBytes, threads, onProgress)
        }
        merged >= totalBytes
    }.getOrDefault(false)

    /** 顺序合并（单线程回退路径）。 */
    private fun mergeSequential(
        parts: List<File>,
        target: File,
        onProgress: ((Long, Long) -> Unit)?,
        offsets: List<Long>? = null,
    ): Boolean = runCatching {
        val hasOffsets = offsets != null && offsets.size == parts.size
        val totalBytes = if (hasOffsets) {
            parts.indices.maxOf { offsets!![it] + parts[it].length() }
        } else {
            parts.sumOf { it.length() }
        }
        var merged = 0L
        RandomAccessFile(target, "rw").use { raf ->
            if (hasOffsets) raf.setLength(totalBytes)
            for (i in parts.indices) {
                val part = parts[i]
                if (hasOffsets) raf.seek(offsets!![i])
                FileInputStream(part).use { fis ->
                    fis.channel.use { inCh ->
                        val outCh = raf.channel
                        var pos = 0L
                        val size = inCh.size()
                        while (pos < size) {
                            val n = inCh.transferTo(pos, size - pos, outCh)
                            if (n <= 0) break
                            pos += n
                            merged += n
                            onProgress?.invoke(merged, totalBytes)
                        }
                    }
                }
            }
        }
        true
    }.getOrDefault(false)

    /** 并行合并：预分配目标文件，多线程各领一批分片按偏移直写。 */
    private fun mergeParallel(
        target: File,
        spans: List<Pair<File, Long>>,
        totalBytes: Long,
        threads: Int,
        onProgress: ((Long, Long) -> Unit)?,
    ): Long = runBlocking {
        RandomAccessFile(target, "rw").use { it.setLength(totalBytes) }

        val progress = java.util.concurrent.atomic.AtomicLong(0)
        val lastReport = java.util.concurrent.atomic.AtomicLong(0)
        val chunks = spans.chunked((spans.size + threads - 1) / threads)

        chunks.map { group ->
            async(Dispatchers.IO) {
                var written = 0L
                RandomAccessFile(target, "rw").use { raf ->
                    for ((part, offset) in group) {
                        raf.seek(offset)
                        FileInputStream(part).use { fis ->
                            fis.channel.use { inCh ->
                                val outCh = raf.channel
                                var pos = 0L
                                val size = inCh.size()
                                while (pos < size) {
                                    val n = inCh.transferTo(pos, size - pos, outCh)
                                    if (n <= 0) break
                                    pos += n
                                    written += n
                                    progress.addAndGet(n)
                                }
                            }
                        }
                        onProgress?.let { cb ->
                            val now = System.currentTimeMillis()
                            val last = lastReport.get()
                            if (now - last >= 200 && lastReport.compareAndSet(last, now)) {
                                cb(progress.get(), totalBytes)
                            }
                        }
                    }
                }
                written
            }
        }.awaitAll().sum()
    }
}

/**
 * 重叠合并器：**在下载过程中**把完成的分片写进临时文件，把合并 I/O
 * 摊到网络等待里，而不是全部堆在收尾。
 *
 * ## 为什么（2026-09-29 实测）
 *
 * 512MB / 64 连接 / 每连接 4MB/s：
 *
 * | 段 | 耗时 |
 * |---|---|
 * | 下载（网络瓶颈，磁盘空闲） | 2.54s |
 * | 合并（收尾，磁盘饱和） | 1.58s |
 *
 * 合并贵是因为它发生在**下载刚结束**：页缓存堆着 512MB 脏页待回写，
 * 紧接着又读 512MB + 写 512MB。单测佐证：冷合并 254 MB/s，刷盘后 1087 MB/s。
 *
 * 而下载阶段网络是瓶颈（约 200 MB/s），磁盘能跑 800+ MB/s —— 此时磁盘是闲的。
 * 把合并提前到这里，开销就能藏进网络等待。
 *
 * ## 为什么不是"直接写最终路径"（aria2-next 的做法）
 *
 * 那需要重写续传判定（分片文件是当前的进度凭证），而这条路径出过三次数据事故。
 * 本类**不改动任何数据布局与续传格式**：分片文件照旧，只是写入时机提前，
 * 且中间产物落在 `*.merging` 临时文件上，最终路径只在收尾时 rename 占位
 * （同文件系统 rename ≈ 0ms），保留"未完成不在目标位置留半成品"的既有保证。
 *
 * ## 正确性：按绝对偏移写，无需等待顺序
 *
 * 每个分片在文件中的位置就是它文件名里的 `start` —— 精确、无歧义。
 * 因此**任何完成的分片都可以立刻写**，不必等前面的分片先写完
 * （首版要求"严格连续才写"，结果慢分片会卡住整条链：实测只写进 94MB/512MB）。
 *
 * 收尾托管造成的区间**重叠**也因此天然安全：重叠部分落到同一偏移、
 * 内容相同 → 写入幂等。
 *
 * [writtenBytes] 只用于统计"已写入多少"（进度展示），不参与写入决策。
 */

package dev.turbodl.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** 分片合并工具：按顺序零拷贝拼接，支持进度回调（GB 级文件合并时 UI 不再无进度卡死）。 */
internal object PartMerger {
    /**
     * 合并 [parts] 到 [target]。
     * @param onProgress 可选进度回调：(已合并字节, 总字节)。总字节为所有分片长度之和。
     */
    fun merge(
        parts: List<File>,
        target: File,
        onProgress: ((merged: Long, total: Long) -> Unit)? = null,
    ): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val totalBytes = parts.sumOf { it.length() }
        var merged = 0L
        FileOutputStream(target).use { fos ->
            fos.channel.use { out ->
                parts.forEach { part ->
                    FileInputStream(part).use { fis ->
                        fis.channel.use { inCh ->
                            var pos = 0L
                            val size = inCh.size()
                            while (pos < size) {
                                val n = inCh.transferTo(pos, size - pos, out)
                                if (n <= 0) break
                                pos += n
                                merged += n
                                onProgress?.invoke(merged, totalBytes)
                            }
                        }
                    }
                }
            }
        }
        true
    }.getOrDefault(false)
}

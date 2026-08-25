package dev.turbodl.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** 分片合并工具：按顺序零拷贝拼接。 */
internal object PartMerger {
    fun merge(parts: List<File>, target: File): Boolean = runCatching {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { fos ->
            fos.channel.use { out ->
                parts.forEach { part ->
                    FileInputStream(part).use { fis ->
                        fis.channel.use { inCh ->
                            var pos = 0L
                            val size = inCh.size()
                            while (pos < size) pos += inCh.transferTo(pos, size - pos, out)
                        }
                    }
                }
            }
        }
        true
    }.getOrDefault(false)
}

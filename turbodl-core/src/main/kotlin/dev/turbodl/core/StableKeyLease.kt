package dev.turbodl.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide and cross-process ownership for a resumable task directory.
 *
 * Two tasks writing the same `progress.tdlp` and destination can both believe
 * their individual snapshots are valid while overwriting each other. The
 * in-process map gives a fail-fast error for sibling TurboClient instances;
 * the OS file lock also protects independent JVMs/CLI processes.
 */
internal class StableKeyLease private constructor(
    private val lockFile: RandomAccessFile,
    private val fileLock: FileLock,
    private val onRelease: () -> Unit,
) : AutoCloseable {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        runCatching { fileLock.release() }
        runCatching { lockFile.close() }
        onRelease()
    }

    companion object {
        private val active = ConcurrentHashMap<String, Long>()

        fun acquire(chunkDir: File, taskId: Long): StableKeyLease {
            chunkDir.mkdirs()
            val canonical = chunkDir.canonicalFile
            val key = canonical.path
            val previous = active.putIfAbsent(key, taskId)
            if (previous != null) {
                throw IllegalStateException(
                    "STABLE_KEY_BUSY_IN_PROCESS: 同一断点目录正在被任务 " +
                        "$previous 使用：${canonical.path}",
                )
            }

            // Keep the OS lock outside the task directory: the directory is
            // recursively removed on completion/cancel, while the lease may
            // still be held by the task's finally block on Windows.
            val leaseDir = File(
                canonical.parentFile ?: File(System.getProperty("java.io.tmpdir")),
                ".turbodl-leases",
            ).apply { mkdirs() }
            val lockPath = File(leaseDir, sha256Hex(canonical.path) + ".lock")
            var handle: RandomAccessFile? = null
            var lock: FileLock? = null
            try {
                handle = RandomAccessFile(lockPath, "rw")
                lock = try {
                    handle.channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    throw IllegalStateException(
                        "STABLE_KEY_BUSY_CROSS_PROCESS: 断点目录已被另一个进程占用：" +
                            canonical.path,
                    )
                }
                handle.setLength(0)
                handle.write(
                    "pid=${ProcessHandleCompat.pid()} task=$taskId\n"
                        .toByteArray(Charsets.UTF_8),
                )
                handle.fd.sync()
                val heldHandle = handle
                val heldLock = lock
                return StableKeyLease(heldHandle, heldLock) {
                    active.remove(key, taskId)
                }
            } catch (e: Exception) {
                runCatching { lock?.release() }
                runCatching { handle?.close() }
                active.remove(key, taskId)
                throw e
            }
        }
    }

}

private object ProcessHandleCompat {
    fun pid(): String = runCatching {
        val process = Class.forName("java.lang.ProcessHandle")
        val current = process.getMethod("current").invoke(null)
        process.getMethod("pid").invoke(current).toString()
    }.getOrDefault("unknown")
}

/**
 * Preserve directory names for already-safe keys, but make unsafe keys
 * collision-resistant after sanitization.
 */
internal fun stableKeyDirectoryName(raw: String): String {
    val sanitized = legacyStableKeyDirectoryName(raw)
    if (sanitized == "key_$raw") return sanitized
    val hash = sha256Hex(raw).take(16)
    return "$sanitized-$hash"
}

internal fun legacyStableKeyDirectoryName(raw: String): String =
    "key_" + raw.map {
        if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_'
    }.joinToString("")

internal fun rejectAmbiguousLegacyProgress(chunkDir: File, stableKey: String) {
    val legacyName = legacyStableKeyDirectoryName(stableKey)
    if (legacyName == stableKeyDirectoryName(stableKey)) return
    val legacyDir = File(chunkDir.parentFile, legacyName)
    val legacyEntries = if (legacyDir.isDirectory) legacyDir.list() else emptyArray()
    if (chunkDir.list().isNullOrEmpty() && legacyDir.isDirectory &&
        (legacyEntries == null || legacyEntries.isNotEmpty())) {
        throw IllegalStateException(
            "LEGACY_STABLE_KEY_AMBIGUOUS: existing resume data cannot be assigned safely; " +
                "the destination and old resume data were retained",
        )
    }
}

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

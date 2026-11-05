package dev.turbodl.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 分片存储 —— 把字节**按偏移直接写进目标文件**，而不是每片一个临时文件。
 *
 * ## 为什么要换掉「每片一个文件 + 收尾合并」
 *
 * 旧结构：每个分片写 `seg_{start}_{end}.part`，全部下完后 [PartMerger] 再把它们
 * 顺序拼进目标文件。代价是**每个分片一次文件创建**，而文件创建是操作系统里相当贵的一步。
 *
 * 本机实测（16MB，同一块盘，去掉 fsync 的公平条件下）：
 *
 * | 分片数 | 分片文件 创建+写 | 收尾合并 | 合计 | 直接按偏移写 |
 * |---|---|---|---|---|
 * | 64 | 148ms | 28ms | 176ms | **9ms** |
 * | 128 | 178ms | 43ms | 221ms | **9ms** |
 * | 512 | 627ms | 101ms | 728ms | **11ms** |
 *
 * 512 片时旧结构把 16MB 写成 25 MB/s，而直写是 1454 MB/s —— **57 倍**。
 * 这不是"优化空间"，而是高并发下的**主要瓶颈**：128 连接那档实测总耗时 722ms，
 * 而分片文件 I/O 一项就占 728ms，网络根本没成为限制。
 *
 * 结构上还有两个附带收益：
 *  - 不再有"合并阶段"（UI 上那个卡在 99% 的 MERGING），下载完就是完；
 *  - 不再产生 N 个临时文件，中断清理也简单。
 *
 * 这就是 aria2 / IDM 的做法（按偏移 pwrite 同一个文件）。
 *
 * ## 进度记录（侧车文件）
 *
 * 直写之后「已下载多少」不再能靠文件长度推断（文件长度只反映最远的写入位置，
 * 中间可能有空洞）。因此进度记在一个侧车文件里：每个分片的 `start/end/written`。
 *
 * 写入顺序是 **先写数据、后落进度**。于是崩溃时最多丢掉「最后一次落盘之后」的进度，
 * 重新下载那一小段并覆盖同一区间 —— 内容一致，幂等，永远不会出现"进度说有、文件里没有"。
 * 反过来（先落进度后写数据）就会留下空洞，那是真正会产出损坏文件的方向。
 *
 * ## 网格为什么要存下来
 *
 * 分片边界由**首次创建时**的连接数决定。若第二次用不同连接数续传，重新推导出的网格
 * 会与侧车里的对不上。所以网格一旦确定就写进侧车、以后照抄 —— 续传与连接数解耦。
 */
internal class SegmentStore(
    /** 目标文件（就是调用方要的那个文件，不是临时文件）。 */
    private val dest: File,
    /** 侧车文件（进度记录）。 */
    private val stateFile: File,
    private val total: Long,
) {

    /**
     * 一个分片：目标文件里的绝对区间 [start, end]，以及已写入的字节数。
     *
     * [end] 是 `var` 而不是 `val`：收尾托管会把在飞分片的后半段让给新连接，
     * 需要就地缩短区间（[dev.turbodl.core.SegmentScheduler] 的 tryTailAssist）。
     */
    class Piece(
        val start: Long,
        @Volatile var end: Long,
        @Volatile var written: Long = 0L,
    ) {
        val length: Long get() = end - start + 1
        val complete: Boolean get() = written >= length
    }

    private var raf: RandomAccessFile? = null
    private var channel: FileChannel? = null
    /** 侧车写入的串行化锁：多个下载 worker 会同时触发 persist。 */
    private val persistLock = Any()
    /**
     * File length captured before `ensureLength()` extends it.
     *
     * Resume validation must use this snapshot, not the live length: a newly
     * recreated empty file that was just extended to `total` would otherwise
     * look as if it still contained the old progress.
     */
    private var lengthAtOpen: Long = -1L

    /**
     * 每个写入线程**自己的**通道。
     *
     * ## 为什么必须分开（实测）
     *
     * 一开始所有分片共用同一个 `FileChannel` 做位置写。结果在本机 256MB / 16 连接下：
     * 每连接累计"读"只用 3~13ms，而"写"要 87~318ms —— 写入比读取慢二三十倍，
     * 而且 16 条连接的写入**串行排队**：单连接 106MB/s，聚合仍然只有 102MB/s。
     *
     * 换成每线程独立通道后，写入不再互相等 —— 位置写（pwrite）本身是允许并发的，
     * 是共用同一个通道句柄把并发吃掉了。
     *
     * 通道在 [close] 时统一关闭（Windows 下不关会让目标文件一直被占用）。
     */
    private val writeChannels = java.util.concurrent.ConcurrentLinkedQueue<FileChannel>()

    private val threadChannel = ThreadLocal.withInitial {
        val r = RandomAccessFile(dest, "rw")
        val ch = r.channel
        writeChannels.add(ch)
        ch
    }

    /** 打开目标文件（不存在则创建）。不预分配长度 —— 让它在写入时自然增长。 */
    fun open() {
        dest.parentFile?.mkdirs()
        val r = RandomAccessFile(dest, "rw")
        raf = r
        channel = r.channel
        lengthAtOpen = r.length()
    }

    /**
     * Ensure the destination has its final logical length before concurrent writes.
     *
     * Extending a file is a metadata operation that serializes inside the OS.
     * With 128/256 positional writers, every Range that lands beyond the current
     * EOF can contend on that same lock. Reserving the last byte once turns the
     * transfer phase into pure positional writes and avoids repeated file-growth
     * stalls. A one-byte positional write is used instead of `setLength(total)`
     * because it does not force filesystems to materialize a large zero-filled
     * allocation up front. On filesystems that implement holes the unwritten
     * regions remain sparse; NTFS may materialize the range, so this is a
     * deliberate one-time tradeoff rather than a promise of sparse storage.
     */
    fun ensureLength() {
        ensureLengthChecked()
    }

    /**
     * Variant used by the scheduler to learn whether the destination shrank
     * between `loadOrCreate()` and this call. In that case the restored grid
     * must be reloaded/discarded instead of trusting its completed pieces.
     */
    internal fun ensureLengthChecked(): Boolean {
        // A caller may truncate/replace the destination after open() but before
        // the grid is loaded. Preserve that shorter observation so ensureLength
        // cannot turn a freshly extended hole back into a false resume hit.
        val invalidated = lengthAtOpen >= 0 && dest.length() < lengthAtOpen
        if (invalidated) {
            lengthAtOpen = 0L
        }
        if (total <= 0) return invalidated
        val ch = channel ?: error("SegmentStore 未 open()")
        if (dest.length() > total) {
            ch.truncate(total)
        }
        if (dest.length() >= total) return invalidated
        val marker = ByteBuffer.wrap(byteArrayOf(0))
        var position = total - 1
        while (marker.hasRemaining()) {
            val n = ch.write(marker, position)
            if (n <= 0) throw java.io.IOException("无法预留目标文件长度：$total")
            position += n
        }
        return invalidated
    }

    fun close() {
        // 先关线程通道，再关照句柄。
        while (true) {
            val ch = writeChannels.poll() ?: break
            runCatching { ch.close() }
        }
        runCatching { channel?.close() }
        runCatching { raf?.close() }
        channel = null
        raf = null
        lengthAtOpen = -1L
        // ThreadLocal 已无意义，清掉避免线程池复用时拿到已关闭的通道。
        runCatching { threadChannel.remove() }
    }

    /**
     * 读取已有进度；没有可用记录时按 [blockSize] 生成新网格。
     *
     * 可用性判据：侧车存在、版本/总大小/网格一致，**且目标文件长度不小于总大小**。
     * 最后一条防的是「用户删了目标文件但侧车还在」—— 那样续传会把数据写进一个空洞文件。
     */
    fun loadOrCreate(blockSize: Long): List<Piece> {
        val existing = readState()
        if (existing != null) {
            when (identityStatus(existing)) {
                IdentityStatus.MATCH -> Unit
                IdentityStatus.UNKNOWN -> {
                    if (existing.pieces.any { it.written > 0 } && destCovers(existing.pieces)) {
                        throw ResumeVerificationException()
                    }
                    reset()
                    return buildGrid(blockSize)
                }
                IdentityStatus.MISMATCH -> {
                    // A same-length replacement is invisible to length checks.
                    // Missing/unreadable identity is also unsafe: without proof,
                    // old progress must never be accepted.
                    reset()
                    return buildGrid(blockSize)
                }
            }
        }
        if (existing != null && existing.total == total && existing.pieces.isNotEmpty() &&
            destCovers(existing.pieces)
        ) {
            return existing.pieces
        }
        // 目标文件撑不起记录里的进度（被删过/被截断）：清空重来，绝不在空洞上续传。
        if (existing != null && !destCovers(existing.pieces)) {
            // reset() removes the primary snapshot, backup and temp file, then
            // closes/reopens the destination so Windows does not keep a stale
            // handle that prevents replacement.
            reset()
        }
        return buildGrid(blockSize)
    }

    /**
     * 目标文件是否真的容得下记录声称的进度。
     *
     * ★ 不能用「文件长度 >= 总大小」来判断：本实现**不预分配**目标文件
     *   （预分配 1.3GB 在手机上要等好几秒，且在部分文件系统上会创建稀疏文件）。
     *   未下完时文件长度只等于**最远写入位置**，因此判据是
     *   「文件长度 >= 所有分片里 start+written 的最大值」。
     *
     * 这正好覆盖了要防的场景：用户删掉目标文件 → 长度为 0 → 任何有进度的分片都超界 → 判定无效。
     */
    private fun destCovers(pieces: List<Piece>): Boolean {
        if (!dest.exists()) return false
        // ★ 只统计**已经写过字节**的分片：一片没动过的分片（written=0）不需要文件延伸到它的起点。
        //   把它们的 start 也算进来会让「最后一片还没开始」永远判定为"文件不够长"，
        //   于是每次续传都作废 —— 那正是"暂停后从头下"最隐蔽的一种成因。
        val need = pieces.filter { it.written > 0 }
            .maxOfOrNull { it.start + it.written } ?: return true
        // Use the length observed at open time. `ensureLength()` may run before
        // `peek/loadOrCreate` and must not turn a deleted/recreated file into a
        // false-positive resume candidate.
        val available =
            if (lengthAtOpen >= 0) minOf(lengthAtOpen, dest.length())
            else dest.length()
        return available >= need
    }

    /**
     * 只读现有进度（不创建、不重置）。用于续传前的指纹校验与各类诊断。
     *
     * @return 记录里的分片列表；没有记录、记录损坏或总大小不符时返回 null。
     */
    fun peek(expectedTotal: Long): List<Piece>? {
        val st = readState() ?: return null
        if (identityStatus(st) != IdentityStatus.MATCH) return null
        if (st.total != expectedTotal || st.pieces.isEmpty()) return null
        if (!destCovers(st.pieces)) return null
        return st.pieces
    }

    internal fun hasRecordedBytes(): Boolean? =
        readState()?.pieces?.any { it.written > 0 }

    /** Existing bytes without a usable identity; callers must verify before adopting. */
    internal fun unverifiedLegacyPieces(expectedTotal: Long): List<Piece>? {
        val state = readState() ?: return null
        if (state.embeddedIdentity != null || state.total != expectedTotal ||
            state.pieces.none { it.written > 0 } || !destCovers(state.pieces) ||
            identityStatus(state) != IdentityStatus.UNKNOWN) return null
        return state.pieces
    }

    internal fun identityForMigration(): String? = currentFileIdentity(createIfMissing = true)

    internal fun adoptVerifiedLegacyPieces(pieces: List<Piece>, identity: String) {
        if (currentFileIdentity() != identity) throw ResumeVerificationException()
        persist(pieces)
        if (currentFileIdentity() != identity || peek(total) == null) throw ResumeVerificationException()
    }

    internal fun requireKnownProgressIdentity() {
        val state = readState() ?: return
        if (state.pieces.any { it.written > 0 } && destCovers(state.pieces) &&
            identityStatus(state) == IdentityStatus.UNKNOWN) {
            throw ResumeVerificationException()
        }
    }

    /** 按固定块大小切网格（最后一片可能更短）。 */
    fun buildGrid(blockSize: Long): List<Piece> {
        val step = blockSize.coerceAtLeast(1)
        val out = ArrayList<Piece>()
        var s = 0L
        while (s < total) {
            val e = minOf(s + step - 1, total - 1)
            out.add(Piece(s, e))
            s = e + 1
        }
        return out
    }

    /**
     * 位置写：把 [len] 字节写到目标文件的 [position]。
     *
     * 用 `write(ByteBuffer, position)` 而不是 `seek+write`：位置写**不改变也不依赖**
     * 通道的当前位置，因此多个分片可以并发写同一个通道而不需要任何锁
     * （JVM 对位置写直接下到 `pwrite`，没有用户态互斥）。
     */
    fun writeAt(position: Long, buf: ByteArray, off: Int, len: Int): Int {
        // 用**本线程**的通道：共用通道会让多分片的写入串行化（实测见 writeChannels 的注释）。
        val ch = threadChannel.get()
        val bb = ByteBuffer.wrap(buf, off, len)
        var written = 0
        while (bb.hasRemaining()) {
            val n = ch.write(bb, position + written)
            if (n <= 0) {
                // 【不能静默返回】磁盘满 / 配额不足时 write 可能返回 0。
                // 悄悄少写会让这一片"看起来完成"，最后靠大小校验才发现 ——
                // 那时用户已经白下完整个文件。这里立刻抛，让分片走失败重试，
                // 错误原因也能一路带到界面上。
                throw java.io.IOException("写入失败（偏移 ${position + written}，已写 $written/$len）")
            }
            written += n
        }
        return written
    }

    /**
     * 直接缓冲版本的位置写。
     *
     * FileChannel 对 DirectByteBuffer 可以直接交给操作系统，避免堆数组再次
     * 复制到本地缓冲；调用方负责在每次读取前 clear。
     */
    fun writeBufferAt(position: Long, src: ByteBuffer): Int {
        val ch = threadChannel.get()
        var written = 0
        while (src.hasRemaining()) {
            val n = ch.write(src, position + written)
            if (n <= 0) {
                throw java.io.IOException(
                    "直接缓冲写入失败（偏移 ${position + written}，" +
                        "还剩 ${src.remaining()} 字节）",
                )
            }
            written += n
        }
        return written
    }

    /** 从目标文件的 [position] 读最多 [len] 字节（用于续传前的内容指纹比对）。 */
    fun readAt(position: Long, len: Int): ByteArray {
        val ch = channel ?: error("SegmentStore 未 open()")
        val buf = ByteArray(len)
        val bb = ByteBuffer.wrap(buf)
        var read = 0
        while (bb.hasRemaining()) {
            val n = ch.read(bb, position + read)
            if (n <= 0) break
            read += n
        }
        return if (read == len) buf else buf.copyOf(read)
    }

    /** 落盘进度记录（原子替换：先写临时文件再改名）。 */
    fun persist(pieces: List<Piece>) {
        // 【必须串行】persist 会被多个下载 worker 同时调用（persistProgress 里的
        // lastPersistAt 是普通变量，多个线程可以同时通过节流检查）。两个 writer 会
        // 互相 setLength(0)、交错写头与分片记录，并竞争同一个 .tmp / .bak 的改名 ——
        // 轻则丢断点，重则生成"可解析但混合了两个时刻"的快照。
        synchronized(persistLock) {
        val snapshot = canonicalSnapshot(pieces) ?: return
        val identity = currentFileIdentity(createIfMissing = true) ?: return
        runCatching {
            // A checkpoint must never become durable before the bytes it
            // claims. Flush the destination data first, then fsync the sidecar
            // temp file and atomically publish it. Without this order, sudden
            // power loss can leave a sidecar claiming ranges that are still
            // only page-cache writes.
            channel?.force(false)
            val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
            stateFile.parentFile?.mkdirs()
            RandomAccessFile(tmp, "rw").use { f ->
                f.setLength(0)
                f.write(MAGIC)
                f.writeInt(VERSION)
                val identityBytes = identity.toByteArray(Charsets.UTF_8)
                f.writeInt(identityBytes.size)
                f.write(identityBytes)
                f.writeLong(total)
                f.writeInt(snapshot.size)
                for (p in snapshot) {
                    f.writeLong(p.start)
                    f.writeLong(p.end)
                    f.writeLong(p.written)
                }
                // Make the new sidecar durable before replacing the old one.
                f.fd.sync()
            }
            // Keep the last durable snapshot until the new one is visible.
            // File.renameTo avoids java.nio.file (unavailable on Android 24/25).
            val backup = File(stateFile.parentFile, stateFile.name + ".bak")
            runCatching { backup.delete() }
            if (stateFile.exists()) {
                if (!stateFile.renameTo(backup)) {
                    stateFile.copyTo(backup, overwrite = true)
                    stateFile.delete()
                }
            }
            if (!tmp.renameTo(stateFile)) {
                tmp.copyTo(stateFile, overwrite = true)
                tmp.delete()
            }
            runCatching { backup.delete() }
        }
        }
    }

    /**
     * Copy the live grid into an immutable, ordered snapshot.
     *
     * Dynamic splitting can append a tail after earlier grid entries, and a
     * concurrent persist can observe the brief interval where the old piece
     * has been shortened but its tail has not been appended yet. Rejecting an
     * incomplete snapshot keeps the last durable resume point intact.
     */
    private fun canonicalSnapshot(pieces: List<Piece>): List<Piece>? {
        val snapshot = ArrayList<Piece>(pieces.size)
        for (piece in pieces) {
            val start = piece.start
            val end = piece.end
            val written = piece.written
            if (start < 0 || end < start || written < 0 || written > end - start + 1) {
                return null
            }
            snapshot.add(Piece(start, end, written))
        }
        snapshot.sortBy { it.start }

        if (total == 0L) return snapshot.takeIf { it.isEmpty() }
        if (snapshot.isEmpty()) return null
        var expectedStart = 0L
        for (piece in snapshot) {
            if (piece.start != expectedStart) return null
            expectedStart = piece.end + 1
        }
        return snapshot.takeIf { expectedStart == total }
    }

    /** 清掉进度记录与目标文件（内容已变、无法安全续传时）。 */
    fun reset() {
        // Close destination handles before trying to delete on Windows. Keep
        // sidecars intact until the destination deletion is known to succeed,
        // otherwise a permission/lock failure would destroy the only recovery
        // metadata while leaving the old file in place.
        close()
        if (dest.exists() && !dest.delete()) {
            throw java.io.IOException(
                "无法丢弃失效的目标文件：${dest.absolutePath}",
            )
        }
        synchronized(persistLock) {
            // 【三件一起删】readState 会自动回退到 .bak：只删主文件的话，
            // 明确丢弃旧进度之后仍然可能把旧备份读回来，等于没重置。
            listOf(
                stateFile,
                File(stateFile.parentFile, stateFile.name + ".bak"),
                File(stateFile.parentFile, stateFile.name + ".tmp"),
                identityFile(),
                File(identityFile().parentFile, identityFile().name + ".tmp"),
            ).forEach { file ->
                if (file.exists() && !file.delete()) {
                    throw java.io.IOException(
                        "无法删除失效的进度侧车：${file.absolutePath}",
                    )
                }
            }
        }
        open()
    }

    private enum class IdentityStatus { MATCH, MISMATCH, UNKNOWN }

    private class Loaded(
        val total: Long,
        val pieces: List<Piece>,
        val embeddedIdentity: String?,
    )

    private fun identityFile(): File =
        File(stateFile.parentFile, stateFile.name + ".identity")

    private fun currentFileIdentity(createIfMissing: Boolean = false): String? = runCatching {
        // Android API 24/25 does not expose java.nio.file.Files. Its public
        // android.system.Os.stat API provides the filesystem device and inode.
        val vm = System.getProperty("java.vm.name").orEmpty()
        if (vm.contains("Dalvik", ignoreCase = true) || vm.contains("ART", ignoreCase = true)) {
            val os = Class.forName("android.system.Os")
            val stat = os.getMethod("stat", String::class.java).invoke(null, dest.absolutePath)
            val dev = stat.javaClass.getField("st_dev").getLong(stat)
            val ino = stat.javaClass.getField("st_ino").getLong(stat)
            return@runCatching if (ino != 0L) "android-inode:$dev:$ino" else null
        }
        val attrs = java.nio.file.Files.readAttributes(
            dest.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
        )
        val created = attrs.creationTime().toMillis()
        val key = attrs.fileKey()?.toString()?.takeIf { it.isNotBlank() }
        if (key != null) return@runCatching "$key|$created"
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            return@runCatching null
        }

        // Windows' default FileSystemProvider returns fileKey=null. Use an NTFS
        // alternate data stream as a persistent token for this destination.
        val ads = File(dest.absolutePath + ":turbodl.id")
        val existing = runCatching {
            RandomAccessFile(ads, "r").use { handle ->
                val length = handle.length().coerceAtMost(128).toInt()
                val bytes = ByteArray(length)
                handle.readFully(bytes)
                String(bytes, Charsets.UTF_8).trim()
            }
        }.getOrNull()
        if (!existing.isNullOrEmpty()) return@runCatching "ads:$existing"
        if (!createIfMissing) return@runCatching "ads-absent"

        val token = java.util.UUID.randomUUID().toString()
        RandomAccessFile(ads, "rw").use { handle ->
            handle.setLength(0)
            handle.write(token.toByteArray(Charsets.UTF_8))
            handle.fd.sync()
        }
        "ads:$token"
    }.getOrNull()

    private fun identityStatus(state: Loaded): IdentityStatus {
        val current = currentFileIdentity() ?: return IdentityStatus.UNKNOWN
        val embedded = state.embeddedIdentity
        if (embedded != null) {
            return if (embedded == current) {
                IdentityStatus.MATCH
            } else {
                IdentityStatus.MISMATCH
            }
        }

        // Version 1 snapshots stored identity in a separate file. If that file
        // is missing/unreadable, the state cannot prove which destination it
        // belongs to; refuse to resume instead of silently accepting it.
        val stored = runCatching {
            identityFile().takeIf { it.isFile }?.readText()?.trim()
        }.getOrNull()
        if (stored.isNullOrEmpty() || !stored.startsWith("v1:")) {
            return IdentityStatus.UNKNOWN
        }
        return if (stored == "v1:$current") {
            IdentityStatus.MATCH
        } else {
            IdentityStatus.MISMATCH
        }
    }

    private fun readState(): Loaded? =
        readStateFile(stateFile)
            ?: readStateFile(File(stateFile.parentFile, stateFile.name + ".bak"))

    private fun readStateFile(source: File): Loaded? = runCatching {
        if (!source.isFile) return@runCatching null
        RandomAccessFile(source, "r").use { f ->
            val magic = ByteArray(MAGIC.size)
            if (f.read(magic) != magic.size || !magic.contentEquals(MAGIC)) return@runCatching null
            val version = f.readInt()
            if (version !in 1..VERSION) return@runCatching null
            val embeddedIdentity = if (version >= 2) {
                val identityLength = f.readInt()
                if (identityLength !in 1..MAX_IDENTITY_BYTES) return@runCatching null
                val bytes = ByteArray(identityLength)
                f.readFully(bytes)
                String(bytes, Charsets.UTF_8).takeIf { it.isNotBlank() }
                    ?: return@runCatching null
            } else {
                null
            }
            val totalRead = f.readLong()
            val count = f.readInt()
            // 防御：损坏的计数不该导致巨额分配。
            if (count < 0 || count > MAX_PIECES) return@runCatching null
            val list = ArrayList<Piece>(count)
            repeat(count) {
                val s = f.readLong()
                val e = f.readLong()
                val w = f.readLong()
                if (s < 0 || e < s || w < 0 || w > e - s + 1) {
                    return@runCatching null
                }
                list.add(Piece(s, e, w))
            }
            // 【整网格校验】只检查单条区间不够：一个损坏但可解析的快照可以声明
            // "整文件已完成"，实际留下零/旧数据，而长度校验照样通过。
            // 要求：有序、无重叠、无空洞、完整覆盖 [0, total)。
            if (totalRead > 0 && list.isNotEmpty()) {
                var expectedStart = 0L
                for (p in list) {
                    if (p.start != expectedStart) return@runCatching null
                    expectedStart = p.end + 1
                }
                if (expectedStart != totalRead) return@runCatching null
            }
            Loaded(totalRead, list, embeddedIdentity)
        }
    }.getOrNull()

    private companion object {
        val MAGIC = byteArrayOf('T'.code.toByte(), 'D'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte())
        const val VERSION = 2
        const val MAX_IDENTITY_BYTES = 512

        /** 网格上限：正常文件几千片足够；超过这个数说明记录已损坏。 */
        const val MAX_PIECES = 2_000_000
    }
}

/**
 * Remove the Windows-only persistent identity token when a task reaches a
 * terminal state. The sidecar itself is deleted with the chunk directory; the
 * ADS lives on the destination and must be cleaned separately.
 */
internal fun clearTurbodlFileIdentity(destination: File) {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        return
    }
    runCatching { File(destination.absolutePath + ":turbodl.id").delete() }
}

package dev.turbodl.nativebridge

import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Explicit opt-in to the shared core; loading failures never switch engines mid-task. */
class NativeTransferClient(
    checkpointDirectory: File,
    maxTasks: Int = 3,
    maxConnections: Int = 64,
    bufferBudgetBytes: Long = 32L * 1024 * 1024,
    allowLoopbackFixtures: Boolean = false,
) : Closeable {
    private val handle = AtomicLong(NativeBridge.nCreate(
        checkpointDirectory.absolutePath.encodeToByteArray(), maxTasks, maxConnections,
        bufferBudgetBytes, if (allowLoopbackFixtures) 1 else 0,
    ))

    data class Network(
        val username: String = "",
        val password: String = "",
        val proxy: String = "",
        val caBundle: String = "",
        val sshKnownHosts: String = "",
        val sshPublicKey: String = "",
        val sshPrivateKey: String = "",
        val dohUrl: String = "",
        val httpVersion: Int = 0,
    ) {
        internal fun encoded() = arrayOf(username,password,proxy,caBundle,sshKnownHosts,sshPublicKey,sshPrivateKey,dohUrl)
            .map(String::encodeToByteArray).toTypedArray()
        override fun toString() = "Network(httpVersion=$httpVersion, credentials=<redacted>)"
    }

    data class Policy(
        val connections: Int = 16,
        val requestWindowBytes: Long = 1024 * 1024,
        val retries: Int = 3,
        val maxConnectionsPerHost: Int = 16,
        val cooldownGroup: String = "",
        val connectTimeoutMs: Int = 15_000,
        val readTimeoutMs: Int = 30_000,
    )

    data class Snapshot(
        val taskId: Long, val generation: Long, val state: Int, val errorCode: Int,
        val downloadedBytes: Long, val durableBytes: Long, val wireBytes: Long,
        val totalBytes: Long, val requests: Long, val activeRequests: Int,
        val peakActiveRequests: Int, val httpStatus: Int, val sha256: String, val message: String,
    ) {
        val terminal: Boolean get() = state == COMPLETED || state == FAILED || state == CANCELLED
        internal companion object {
            fun from(value: NativeSnapshot): Snapshot {
                val c=value.counters
                require(c.size>=14) { "Native snapshot ABI mismatch" }
                return Snapshot(c[0],c[1],c[2].toInt(),c[3].toInt(),c[4],c[5],c[6],c[7],c[8],c[9].toInt(),c[10].toInt(),c[11].toInt(),value.sha256,value.message)
            }
        }
    }

    data class Event(val sequence: Long, val kind: Int, val snapshot: Snapshot)

    /** Empty file selection means every file. The task remains on this core. */
    data class TorrentPolicy(
        val peerLimit: Int = 16,
        val seedSeconds: Int = 0,
        val selectedFiles: Set<Int> = emptySet(),
        val downloadBytesPerSecond: Long = 0,
        val uploadBytesPerSecond: Long = 0,
    )

    fun submitTorrent(
        torrentFileOrMagnet: String, destinationDirectory: File, stableKey: String,
        policy: TorrentPolicy = TorrentPolicy(),
    ): Long = NativeBridge.nSubmitTorrent(
        activeHandle(), torrentFileOrMagnet.encodeToByteArray(),
        destinationDirectory.absolutePath.encodeToByteArray(), stableKey.encodeToByteArray(),
        policy.peerLimit, policy.seedSeconds, policy.selectedFiles.sorted().toIntArray(),
        policy.downloadBytesPerSecond, policy.uploadBytesPerSecond,
    )

    fun connectPeer(taskId: Long, address: String, port: Int) {
        require(port in 1..65535)
        NativeBridge.nConnectPeer(activeHandle(), taskId, address.encodeToByteArray(), port)
    }

    data class HttpMetrics(
        val finishedRequests: Long, val newConnections: Long,
        val dnsMicrosecondsSum: Long, val connectMicrosecondsSum: Long,
        val tlsMicrosecondsSum: Long, val requestFirstByteMicrosecondsSum: Long,
        val taskFirstByteMicroseconds: Long, val negotiatedVersionBits: Int,
        val activeStreams: Int, val activeConnections: Int, val peakConnections: Int,
    )

    fun httpMetrics(taskId: Long): HttpMetrics {
        val m=NativeBridge.nHttpMetrics(activeHandle(),taskId)
        check(m.size==11) { "Native HTTP metrics ABI mismatch" }
        return HttpMetrics(m[0],m[1],m[2],m[3],m[4],m[5],m[6],m[7].toInt(),m[8].toInt(),m[9].toInt(),m[10].toInt())
    }

    fun submitMetalink(
        document: String, baseUrl: String, destination: File, stableKey: String,
        fileName: String? = null, policy: Policy = Policy(),
    ): Long = NativeBridge.nSubmitMetalink(
        activeHandle(),document.encodeToByteArray(),baseUrl.encodeToByteArray(),
        fileName?.encodeToByteArray(),destination.absolutePath.encodeToByteArray(),stableKey.encodeToByteArray(),
        policy.connections,policy.requestWindowBytes,policy.connectTimeoutMs,policy.readTimeoutMs,
        policy.retries,policy.maxConnectionsPerHost,policy.cooldownGroup.encodeToByteArray(),
    )

    fun submit(
        url: String, destination: File, stableKey: String,
        expectedSha256: String? = null, headers: Map<String,String> = emptyMap(),
        policy: Policy = Policy(), network: Network = Network(),
    ): Long {
        val entries=headers.entries.toList()
        return NativeBridge.nSubmit(activeHandle(), url.encodeToByteArray(),destination.absolutePath.encodeToByteArray(),
            stableKey.encodeToByteArray(),expectedSha256?.encodeToByteArray(),
            entries.map { it.key.encodeToByteArray() }.toTypedArray(),entries.map { it.value.encodeToByteArray() }.toTypedArray(),
            policy.connections,policy.requestWindowBytes,policy.connectTimeoutMs,policy.readTimeoutMs,
            network.encoded(),network.httpVersion,policy.retries,policy.maxConnectionsPerHost,policy.cooldownGroup.encodeToByteArray())
    }

    class HttpSource(val url: String, val headers: Map<String, String> = emptyMap())

    enum class MediaProtocol(internal val code: Int) { AUTO(0), HLS(1), DASH(2) }

    data class MediaPolicy(
        val protocol: MediaProtocol = MediaProtocol.AUTO,
        val parallelSegments: Int = 4,
        val maximumBandwidth: Long = 0,
        val maximumSegmentBytes: Long = 64L * 1024 * 1024,
        val audioLanguage: String? = null,
        val videoRepresentation: String? = null,
        val audioRepresentation: String? = null,
        val recordingSeconds: Int = 0,
    )

    data class MediaDetails(
        val stage: Int, val failureStage: Int, val tracks: Int, val live: Boolean,
        val units: Long, val verifiedUnits: Long, val recordedMilliseconds: Long,
    )

    fun submitMedia(
        source: HttpSource,
        destination: File,
        stableKey: String,
        expectedSha256: String? = null,
        representationId: String? = null,
        media: MediaPolicy = MediaPolicy(),
        policy: Policy = Policy(),
        network: Network = Network(),
    ): Long {
        val entries = source.headers.entries.toList()
        return NativeBridge.nSubmitMedia(
            activeHandle(), source.url.encodeToByteArray(), destination.absolutePath.encodeToByteArray(),
            stableKey.encodeToByteArray(), expectedSha256?.encodeToByteArray(),
            entries.map { it.key.encodeToByteArray() }.toTypedArray(),
            entries.map { it.value.encodeToByteArray() }.toTypedArray(),
            policy.connections, policy.requestWindowBytes, policy.connectTimeoutMs, policy.readTimeoutMs,
            network.encoded(), network.httpVersion, policy.retries, policy.maxConnectionsPerHost,
            policy.cooldownGroup.encodeToByteArray(), representationId?.encodeToByteArray(),
            media.protocol.code, media.parallelSegments, media.maximumBandwidth, media.maximumSegmentBytes,
            media.audioLanguage?.encodeToByteArray(), media.videoRepresentation?.encodeToByteArray(),
            media.audioRepresentation?.encodeToByteArray(), media.recordingSeconds,
        )
    }

    fun finishRecording(taskId: Long) = NativeBridge.nFinishMedia(activeHandle(), taskId)

    fun mediaDetails(taskId: Long): MediaDetails {
        val fields = NativeBridge.nMediaDetails(activeHandle(), taskId)
        check(fields.size == 7) { "Native media details ABI mismatch" }
        return MediaDetails(fields[0].toInt(), fields[1].toInt(), fields[2].toInt(), fields[3] != 0L,
            fields[4], fields[5], fields[6])
    }

    data class ByteSlice(val offset: Long, val length: Long) {
        init {
            require(offset >= 0 && length > 0 && offset <= Long.MAX_VALUE - length)
        }
    }

    /**
     * The SHA-256 and known size describe the output, including when only a slice is requested.
     * Mixing mirrors requires an expected digest. A representation ID must identify immutable
     * content in the caller's account scope. The checkpoint is owned by the native engine.
     */
    fun submitHttp(
        source: HttpSource,
        destination: File,
        stableKey: String,
        expectedSha256: String? = null,
        knownSize: Long? = null,
        mirrors: List<HttpSource> = emptyList(),
        slice: ByteSlice? = null,
        maximumBytes: Long = 0,
        representationId: String? = null,
        policy: Policy = Policy(),
        network: Network = Network(),
    ): Long {
        require(mirrors.size <= 32)
        require(mirrors.isEmpty() || !expectedSha256.isNullOrBlank())
        require(knownSize == null || knownSize >= 0)
        require(slice == null || knownSize == null || slice.length == knownSize)
        require(maximumBytes >= 0)
        val primaryHeaders = source.headers.entries.toList()
        val mirrorHeaders = mirrors.map { it.headers.entries.toList() }
        return NativeBridge.nSubmitHttp(
            activeHandle(), source.url.encodeToByteArray(), destination.absolutePath.encodeToByteArray(),
            stableKey.encodeToByteArray(), expectedSha256?.encodeToByteArray(),
            primaryHeaders.map { it.key.encodeToByteArray() }.toTypedArray(),
            primaryHeaders.map { it.value.encodeToByteArray() }.toTypedArray(),
            policy.connections, policy.requestWindowBytes, policy.connectTimeoutMs, policy.readTimeoutMs,
            network.encoded(), network.httpVersion, policy.retries, policy.maxConnectionsPerHost,
            policy.cooldownGroup.encodeToByteArray(), knownSize ?: -1, slice?.offset ?: 0,
            slice?.length ?: 0, maximumBytes, representationId?.encodeToByteArray(),
            mirrors.map { it.url.encodeToByteArray() }.toTypedArray(),
            mirrorHeaders.map { fields -> fields.map { it.key.encodeToByteArray() }.toTypedArray() }.toTypedArray(),
            mirrorHeaders.map { fields -> fields.map { it.value.encodeToByteArray() }.toTypedArray() }.toTypedArray(),
        )
    }

    fun query(taskId: Long) = Snapshot.from(NativeBridge.nQuery(activeHandle(),taskId))
    fun poll(capacity: Int = 64) = NativeBridge.nPoll(activeHandle(),capacity).map { Event(it.counters[12],it.counters[13].toInt(),Snapshot.from(it)) }
    fun pause(taskId: Long) = NativeBridge.nControl(activeHandle(),taskId,1)
    fun resume(taskId: Long) = NativeBridge.nControl(activeHandle(),taskId,2)
    fun cancel(taskId: Long) = NativeBridge.nControl(activeHandle(),taskId,3)
    fun setSpeedLimit(bytesPerSecond: Long) = NativeBridge.nSetSpeedLimit(activeHandle(),bytesPerSecond)
    fun forget(taskId: Long) = NativeBridge.nForget(activeHandle(),taskId)
    fun replaceSource(taskId: Long,url: String,headers: Map<String,String> = emptyMap()) {
        val entries=headers.entries.toList()
        NativeBridge.nReplaceSource(activeHandle(),taskId,url.encodeToByteArray(),
            entries.map { it.key.encodeToByteArray() }.toTypedArray(),entries.map { it.value.encodeToByteArray() }.toTypedArray())
    }
    override fun close() { val id=handle.getAndSet(0);if(id!=0L)NativeBridge.nDestroy(id) }
    private fun activeHandle()=handle.get().also { check(it!=0L) { "Native engine is closed" } }

    companion object {
        const val QUEUED=0; const val PROBING=1; const val TRANSFERRING=2; const val PAUSING=3
        const val PAUSED=4; const val VERIFYING=5; const val COMPLETED=6; const val FAILED=7; const val CANCELLED=8
        fun capabilities(): String = NativeBridge.nCapabilities()
    }
}

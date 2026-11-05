package dev.turbodl.plugin.hls

import java.net.URI
import java.security.MessageDigest

/** Parsed, immutable HLS VOD media playlist. */
internal data class HlsMediaPlaylist(
    val sourceUri: URI,
    val mediaSequence: Long,
    val initSegment: HlsInitSegment?,
    val segments: List<HlsSegment>,
    val identity: String,
)

/** One fMP4 media initialization section declared by EXT-X-MAP. */
internal data class HlsInitSegment(
    val uri: URI,
    val byteRange: HlsByteRange?,
    val encryption: HlsEncryption?,
)

/** One media segment in playlist order. */
internal data class HlsSegment(
    val index: Int,
    val uri: URI,
    val byteRange: HlsByteRange?,
    val encryption: HlsEncryption?,
)

/** HTTP byte range associated with one HLS segment. */
internal data class HlsByteRange(val length: Long, val offset: Long)

/** AES-128 segment encryption data resolved for one segment. Null means unencrypted. */
internal data class HlsEncryption(val keyUri: URI, val iv: ByteArray)

/** Active EXT-X-KEY state; an omitted IV is derived separately for each segment sequence. */
private data class HlsKey(val keyUri: URI, val explicitIv: ByteArray?)

/** Parsing failure describing a playlist construct intentionally outside this adapter's scope. */
internal class HlsUnsupportedException(message: String) : IllegalArgumentException(message)

/**
 * Minimal standards-based HLS playlist parser.
 *
 * This adapter intentionally supports the reliable download path: master playlists selecting a
 * variant, static media playlists ending in EXT-X-ENDLIST, EXTINF segments, BYTERANGE, and
 * AES-128 CBC encryption. A single EXT-X-MAP initialization section at the start of an fMP4
 * playlist is prepended to the ordered segment parts. Constructs that require a media pipeline
 * rather than byte assembly fail explicitly instead of producing a deceptively corrupt output.
 */
internal object HlsManifestParser {
    private const val MASTER_TAG = "#EXT-X-STREAM-INF:"
    private const val KEY_TAG = "#EXT-X-KEY:"
    private const val MAP_TAG = "#EXT-X-MAP:"
    private const val RANGE_TAG = "#EXT-X-BYTERANGE:"
    private const val MEDIA_SEQUENCE_TAG = "#EXT-X-MEDIA-SEQUENCE:"

    fun selectVariant(masterText: String, masterUri: URI): URI? {
        val lines = normalizedLines(masterText)
        requireHeader(lines)
        val variants = mutableListOf<Pair<Long, URI>>()
        var pendingBandwidth: Long? = null
        for (line in lines) {
            when {
                line.startsWith(MASTER_TAG) -> {
                    val bandwidth = attributes(line.removePrefix(MASTER_TAG))["BANDWIDTH"]?.toLongOrNull()
                    pendingBandwidth = bandwidth ?: 0L
                }
                pendingBandwidth != null && !line.startsWith("#") -> {
                    variants += pendingBandwidth!! to resolve(masterUri, line)
                    pendingBandwidth = null
                }
            }
        }
        if (pendingBandwidth != null) {
            throw HlsUnsupportedException("HLS master playlist has EXT-X-STREAM-INF without a variant URI")
        }
        return variants.maxByOrNull { it.first }?.second
    }

    fun parseMedia(mediaText: String, sourceUri: URI): HlsMediaPlaylist {
        val lines = normalizedLines(mediaText)
        requireHeader(lines)
        if (lines.any { it.startsWith(MASTER_TAG) }) {
            throw HlsUnsupportedException("HLS nested master playlists are not supported")
        }
        if (lines.any { it.startsWith("#EXT-X-DISCONTINUITY") }) {
            throw HlsUnsupportedException("HLS discontinuity playlists are not supported by the raw segment merger")
        }
        if (lines.none { it == "#EXT-X-ENDLIST" }) {
            throw HlsUnsupportedException("HLS live/event playlists require EXT-X-ENDLIST before they can be downloaded")
        }

        var mediaSequence = 0L
        var currentKey: HlsKey? = null
        var initMap: HlsInitMap? = null
        var pendingRange: PendingRange? = null
        var previousRangeEnd: Long? = null
        var sawExtInf = false
        var sawMediaSegment = false
        val segments = mutableListOf<HlsSegment>()

        for (line in lines) {
            when {
                line.startsWith(MEDIA_SEQUENCE_TAG) -> {
                    mediaSequence = line.removePrefix(MEDIA_SEQUENCE_TAG).trim().toLongOrNull()
                        ?: throw HlsUnsupportedException("Invalid HLS EXT-X-MEDIA-SEQUENCE")
                }
                line.startsWith("#EXT-X-PLAYLIST-TYPE:") -> {
                    val type = line.substringAfter(':').trim()
                    if (!type.equals("VOD", ignoreCase = true)) {
                        throw HlsUnsupportedException("HLS playlist type '$type' is not supported; only VOD is supported")
                    }
                }
                line.startsWith(KEY_TAG) -> {
                    currentKey = parseEncryption(attributes(line.removePrefix(KEY_TAG)), sourceUri)
                }
                line.startsWith(MAP_TAG) -> {
                    if (initMap != null || sawMediaSegment) {
                        throw HlsUnsupportedException(
                            "HLS playlists with multiple or midstream EXT-X-MAP declarations are not supported",
                        )
                    }
                    initMap = parseInitMap(attributes(line.removePrefix(MAP_TAG)), sourceUri, currentKey)
                }
                line.startsWith(RANGE_TAG) -> {
                    pendingRange = parsePendingRange(line.removePrefix(RANGE_TAG))
                }
                line.startsWith("#EXTINF:") -> sawExtInf = true
                !line.startsWith("#") -> {
                    if (!sawExtInf) {
                        throw HlsUnsupportedException("HLS segment URI appeared without a preceding EXTINF")
                    }
                    val uri = resolve(sourceUri, line)
                    val byteRange = pendingRange?.let { range ->
                        val offset = range.offset ?: previousRangeEnd
                            ?: throw HlsUnsupportedException("HLS BYTERANGE without an explicit initial offset")
                        HlsByteRange(range.length, offset).also { previousRangeEnd = it.offset + it.length }
                    }
                    if (pendingRange == null) previousRangeEnd = null
                    val segmentIndex = segments.size
                    val encryption = currentKey?.let { key ->
                        HlsEncryption(key.keyUri, key.explicitIv ?: sequenceIv(mediaSequence + segmentIndex))
                    }
                    segments += HlsSegment(
                        index = segmentIndex,
                        uri = uri,
                        byteRange = byteRange,
                        encryption = encryption,
                    )
                    sawMediaSegment = true
                    pendingRange = null
                    sawExtInf = false
                }
            }
        }
        if (sawExtInf || pendingRange != null) {
            throw HlsUnsupportedException("HLS playlist ended before its pending segment URI")
        }
        if (segments.isEmpty()) throw HlsUnsupportedException("HLS media playlist has no segments")
        val initSegment = initMap?.let { map ->
            HlsInitSegment(
                uri = map.uri,
                byteRange = map.byteRange,
                encryption = map.key?.let { key ->
                    val iv = key.explicitIv ?: throw HlsUnsupportedException(
                        "HLS AES-128 encrypted EXT-X-MAP requires an explicit IV",
                    )
                    HlsEncryption(key.keyUri, iv)
                },
            )
        }
        return HlsMediaPlaylist(
            sourceUri = sourceUri,
            mediaSequence = mediaSequence,
            initSegment = initSegment,
            segments = segments,
            identity = manifestIdentity(sourceUri, lines),
        )
    }

    /**
     * Stable identity for the fully resolved media playlist. Cache entries must
     * never survive a playlist version change merely because their positional
     * index stayed the same.
     */
    private fun manifestIdentity(sourceUri: URI, lines: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sourceUri.toString().toByteArray(Charsets.UTF_8))
        digest.update(0)
        lines.forEach { line ->
            digest.update(line.toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun parseEncryption(attrs: Map<String, String>, playlistUri: URI): HlsKey? {
        val method = attrs["METHOD"]?.uppercase() ?: throw HlsUnsupportedException("HLS EXT-X-KEY is missing METHOD")
        if (method == "NONE") return null
        if (method != "AES-128") {
            throw HlsUnsupportedException("HLS encryption method '$method' is not supported; DRM/SAMPLE-AES requires a media pipeline")
        }
        val keyFormat = attrs["KEYFORMAT"]
        if (keyFormat != null && !keyFormat.equals("identity", ignoreCase = true)) {
            throw HlsUnsupportedException("HLS KEYFORMAT '$keyFormat' is not supported")
        }
        val keyUri = attrs["URI"] ?: throw HlsUnsupportedException("HLS AES-128 key is missing URI")
        return HlsKey(resolve(playlistUri, keyUri), attrs["IV"]?.let(::parseIv))
    }

    private fun parsePendingRange(raw: String): PendingRange {
        val match = Regex("^(\\d+)(?:@(\\d+))?$").matchEntire(raw.trim())
            ?: throw HlsUnsupportedException("Invalid HLS EXT-X-BYTERANGE '$raw'")
        val length = match.groupValues[1].toLong()
        if (length <= 0) throw HlsUnsupportedException("HLS EXT-X-BYTERANGE length must be positive")
        return PendingRange(length, match.groupValues[2].takeIf { it.isNotEmpty() }?.toLong())
    }

    private fun parseInitMap(
        attrs: Map<String, String>,
        playlistUri: URI,
        key: HlsKey?,
    ): HlsInitMap {
        val uriValue = attrs["URI"] ?: throw HlsUnsupportedException("HLS EXT-X-MAP is missing URI")
        if (uriValue.isBlank()) throw HlsUnsupportedException("HLS EXT-X-MAP URI must not be blank")
        val byteRange = attrs["BYTERANGE"]?.let(::parseInitMapRange)
        return HlsInitMap(resolve(playlistUri, uriValue), byteRange, key)
    }

    private fun parseInitMapRange(raw: String): HlsByteRange {
        val match = Regex("^(\\d+)(?:@(\\d+))?$").matchEntire(raw.trim())
            ?: throw HlsUnsupportedException("Invalid HLS EXT-X-MAP BYTERANGE '$raw'")
        val length = match.groupValues[1].toLongOrNull()
            ?: throw HlsUnsupportedException("HLS EXT-X-MAP BYTERANGE length is too large: '$raw'")
        if (length <= 0) throw HlsUnsupportedException("HLS EXT-X-MAP BYTERANGE length must be positive")
        val offsetValue = match.groupValues[2]
        if (offsetValue.isEmpty()) {
            throw HlsUnsupportedException(
                "HLS EXT-X-MAP BYTERANGE requires an explicit offset: '$raw'",
            )
        }
        val offset = offsetValue.toLongOrNull()
            ?: throw HlsUnsupportedException("HLS EXT-X-MAP BYTERANGE offset is too large: '$raw'")
        return HlsByteRange(length, offset)
    }

    private fun parseIv(raw: String): ByteArray {
        val hex = raw.removePrefix("0x").removePrefix("0X")
        if (hex.length !in 1..32 || hex.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) {
            throw HlsUnsupportedException("Invalid HLS AES-128 IV '$raw'")
        }
        val padded = hex.padStart(32, '0')
        return ByteArray(16) { i -> padded.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun sequenceIv(sequence: Long): ByteArray {
        if (sequence < 0) throw HlsUnsupportedException("HLS media sequence must not be negative")
        return ByteArray(16).also { bytes ->
            for (i in 0 until Long.SIZE_BYTES) bytes[15 - i] = (sequence ushr (i * 8)).toByte()
        }
    }

    private fun requireHeader(lines: List<String>) {
        if (lines.firstOrNull() != "#EXTM3U") throw HlsUnsupportedException("Not an HLS M3U8 playlist")
    }

    private fun normalizedLines(text: String): List<String> = text
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()

    private fun attributes(raw: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var cursor = 0
        while (cursor < raw.length) {
            while (cursor < raw.length && (raw[cursor] == ',' || raw[cursor].isWhitespace())) cursor++
            if (cursor >= raw.length) break
            val equals = raw.indexOf('=', cursor)
            if (equals < 0) throw HlsUnsupportedException("Invalid HLS attribute list '$raw'")
            val name = raw.substring(cursor, equals).trim().uppercase()
            cursor = equals + 1
            val value = if (cursor < raw.length && raw[cursor] == '"') {
                val end = raw.indexOf('"', cursor + 1)
                if (end < 0) throw HlsUnsupportedException("Unclosed HLS quoted attribute '$raw'")
                cursor = end + 1
                raw.substring(equals + 2, end)
            } else {
                val end = raw.indexOf(',', cursor).let { if (it < 0) raw.length else it }
                val unquoted = raw.substring(cursor, end).trim()
                cursor = end
                unquoted
            }
            result[name] = value
        }
        return result
    }

    private fun resolve(base: URI, value: String): URI {
        val target = value.trim()
        if (target.isEmpty()) throw HlsUnsupportedException("HLS URI must not be blank")
        val uri = try {
            base.resolve(target)
        } catch (t: IllegalArgumentException) {
            throw HlsUnsupportedException("Invalid HLS URI '$target': ${t.message}")
        }
        if (uri.scheme !in setOf("http", "https")) {
            throw HlsUnsupportedException("HLS URI must use http or https: $uri")
        }
        return uri
    }

    private data class HlsInitMap(val uri: URI, val byteRange: HlsByteRange?, val key: HlsKey?)
    private data class PendingRange(val length: Long, val offset: Long?)
}

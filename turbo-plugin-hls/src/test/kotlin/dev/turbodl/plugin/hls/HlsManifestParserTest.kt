package dev.turbodl.plugin.hls

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlsManifestParserTest {

    private val base = URI("https://cdn.example.com/vod/master.m3u8")

    @Test
    fun selectsHighestBandwidthVariant() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
            high/index.m3u8
        """.trimIndent()
        val variant = HlsManifestParser.selectVariant(master, base)
        assertEquals(URI("https://cdn.example.com/vod/high/index.m3u8"), variant)
    }

    @Test
    fun mediaPlaylistWithoutMasterReturnsNoVariant() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            a.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        assertNull(HlsManifestParser.selectVariant(media, base))
    }

    @Test
    fun parsesVodSegmentsWithRelativeUris() {
        val media = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:6.0,
            seg0.ts
            #EXTINF:6.0,
            seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val playlist = HlsManifestParser.parseMedia(media, URI("https://cdn.example.com/vod/high/index.m3u8"))
        assertEquals(2, playlist.segments.size)
        assertEquals(URI("https://cdn.example.com/vod/high/seg0.ts"), playlist.segments[0].uri)
        assertEquals(URI("https://cdn.example.com/vod/high/seg1.ts"), playlist.segments[1].uri)
        assertTrue(playlist.segments.all { it.encryption == null })
    }

    @Test
    fun parsesByteRangeWithImplicitOffset() {
        val media = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:6.0,
            #EXT-X-BYTERANGE:1000@0
            media.ts
            #EXTINF:6.0,
            #EXT-X-BYTERANGE:1000
            media.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val playlist = HlsManifestParser.parseMedia(media, base)
        assertEquals(HlsByteRange(1000, 0), playlist.segments[0].byteRange)
        assertEquals(HlsByteRange(1000, 1000), playlist.segments[1].byteRange)
    }

    @Test
    fun aes128DerivesSequenceIvWhenAbsent() {
        val media = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-MEDIA-SEQUENCE:5
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:6.0,
            seg0.ts
            #EXTINF:6.0,
            seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val playlist = HlsManifestParser.parseMedia(media, base)
        assertEquals(URI("https://cdn.example.com/vod/key.bin"), playlist.segments[0].encryption!!.keyUri)
        // sequence 5 -> IV ends in 0x05, sequence 6 -> 0x06
        assertEquals(5, playlist.segments[0].encryption!!.iv[15].toInt())
        assertEquals(6, playlist.segments[1].encryption!!.iv[15].toInt())
    }

    @Test
    fun aes128UsesExplicitIvForAllSegments() {
        val media = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x000102030405060708090A0B0C0D0E0F
            #EXTINF:6.0,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val playlist = HlsManifestParser.parseMedia(media, base)
        val iv = playlist.segments[0].encryption!!.iv
        assertEquals(0x0F, iv[15].toInt() and 0xFF)
        assertEquals(0x00, iv[0].toInt() and 0xFF)
    }

    @Test
    fun keyNoneClearsEncryption() {
        val media = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:6.0,
            enc.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:6.0,
            clear.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val playlist = HlsManifestParser.parseMedia(media, base)
        assertTrue(playlist.segments[0].encryption != null)
        assertNull(playlist.segments[1].encryption)
    }

    @Test
    fun livePlaylistWithoutEndlistRejected() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg0.ts
        """.trimIndent()
        assertFailsWith<HlsUnsupportedException> { HlsManifestParser.parseMedia(media, base) }
    }

    @Test
    fun sampleAesRejected() {
        val media = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="key.bin"
            #EXTINF:6.0,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        assertFailsWith<HlsUnsupportedException> { HlsManifestParser.parseMedia(media, base) }
    }

    @Test
    fun fmp4MapRejected() {
        val media = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.0,
            seg0.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        assertFailsWith<HlsUnsupportedException> { HlsManifestParser.parseMedia(media, base) }
    }

    @Test
    fun discontinuityRejected() {
        val media = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:6.0,
            a.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:6.0,
            b.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        assertFailsWith<HlsUnsupportedException> { HlsManifestParser.parseMedia(media, base) }
    }

    @Test
    fun nonHttpSegmentRejected() {
        val media = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:6.0,
            file:///etc/passwd
            #EXT-X-ENDLIST
        """.trimIndent()
        assertFailsWith<HlsUnsupportedException> { HlsManifestParser.parseMedia(media, base) }
    }
}

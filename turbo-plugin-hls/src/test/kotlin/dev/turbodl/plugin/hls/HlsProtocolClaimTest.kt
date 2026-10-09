package dev.turbodl.plugin.hls

import dev.turbodl.core.DownloadRequest
import dev.turbodl.plugin.runtime.PluginHost
import dev.turbodl.plugin.runtime.PluginState
import dev.turbodl.plugin.runtime.ext.ExtensionPoints
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The HLS plugin's protocol declaration, and the honest boundary around it.
 *
 * HLS is a content protocol, not a URL scheme: a `.m3u8` link is an `http(s)` URL. So the plugin
 * declares `hls` (the name the market/diagnostics should know it by) while routing keeps flowing
 * through [HlsBackend.supports] — and the scheme index deliberately does NOT answer for `.m3u8`.
 * These tests pin both halves so nobody later "fixes" the index into pretending otherwise.
 */
class HlsProtocolClaimTest {

    private fun hostWithHls(): PluginHost {
        val host = PluginHost(logger = { _, _ -> })
        host.install(HlsPlugin())
        assertEquals(PluginState.LOADED, host.diagnostics().plugins.single().state)
        return host
    }

    @Test
    fun hlsPluginDeclaresItsProtocolWhenItLoads() {
        val host = hostWithHls()

        val claim = host.protocols.claimsFor("hls").single()
        assertEquals("backend.hls", claim.pluginId)
        assertEquals("hls-vod", claim.label)
        assertEquals(100, claim.priority, "the claim priority mirrors the backend registration")
        assertEquals(listOf("hls"), host.protocols.allClaims().map { it.scheme })
        assertTrue(host.protocols.conflicts().isEmpty(), "HLS is the only claimant of hls")

        // The declaration is additive: the routing registration is untouched and still the only
        // backend this plugin registers.
        assertEquals(1, host.extensions.all(ExtensionPoints.DOWNLOAD_BACKEND).size)
        assertTrue(host.extensions.highest(ExtensionPoints.DOWNLOAD_BACKEND) is HlsBackend)
    }

    @Test
    fun m3u8IsAnHttpUrlSoTheSchemeIndexDoesNotAnswerHls() {
        val host = hostWithHls()

        // Declared name is queryable by name ...
        assertEquals("backend.hls", host.protocols.claimsFor("hls").single().pluginId)
        // ... but an .m3u8 URL is http(s), and nothing declares http/https: no false positive.
        assertNull(host.protocols.resolve("https://x/y.m3u8"), "hls is a content type, not the URL scheme")
        assertNull(host.protocols.resolve("http://x/y.m3u8"))
        assertTrue(host.protocols.claimsFor("https").isEmpty())

        // Routing for that same URL is still decided by the backend predicate, not by the index.
        val request = DownloadRequest("https://x/y.m3u8", File("y"))
        val routed = host.extensions.all(ExtensionPoints.DOWNLOAD_BACKEND).firstOrNull { it.supports(request) }
        assertTrue(routed is HlsBackend, "the .m3u8 request must still route to the HLS backend")
    }

    @Test
    fun uninstallingTheHlsPluginRemovesClaimAndBackend() {
        val host = hostWithHls()
        assertEquals(1, host.protocols.claimsFor("hls").size)

        host.uninstall("backend.hls")

        assertTrue(host.protocols.allClaims().isEmpty(), "the claim must not outlive the plugin")
        assertTrue(host.extensions.all(ExtensionPoints.DOWNLOAD_BACKEND).isEmpty())
    }
}

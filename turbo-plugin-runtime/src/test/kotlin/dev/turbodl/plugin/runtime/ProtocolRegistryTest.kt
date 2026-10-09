package dev.turbodl.plugin.runtime

import dev.turbodl.plugin.runtime.ext.ExtensionPoints
import dev.turbodl.plugin.runtime.ext.ProtocolClaim
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The load-time scheme index: parsing, multi-protocol declarations, conflict reporting and
 * uninstall cleanliness.
 *
 * Claims are exercised through [PluginHost.protocols] (the public query surface), installing real
 * plugins so unloading really goes through the kernel's disposer drain.
 */
class ProtocolRegistryTest {

    /** A plugin declaring one or more schemes, as a "one plugin, many protocols" backend would. */
    private class ClaimingPlugin(
        override val id: String,
        private val schemes: List<String>,
        private val priority: Int,
        private val label: String = "",
    ) : Plugin {
        override fun onLoad(context: PluginContext) {
            schemes.forEach { scheme ->
                context.registerExtension(
                    ExtensionPoints.PROTOCOL_HANDLER,
                    ProtocolClaim(scheme = scheme, pluginId = id, priority = priority, label = label),
                    priority,
                )
            }
        }
    }

    private fun host() = PluginHost(logger = { _, _ -> })

    // ---------- scheme parsing ----------

    @Test
    fun resolvesSchemeFromEveryShapeOfUrl() {
        val host = host()
        // One plugin declares the URL schemes used below, so a successful lookup proves the
        // scheme was parsed (not that some library happened to match a prefix).
        host.install(ClaimingPlugin("net.core", listOf("https", "ftp", "magnet"), priority = 0))

        assertEquals("magnet", host.protocols.resolve("magnet:?xt=urn:btih:0123456789abcdef")?.scheme)
        assertEquals("ftp", host.protocols.resolve("ftp://host/file.iso")?.scheme)
        assertEquals("https", host.protocols.resolve("HTTPS://HOST/x")?.scheme, "scheme must be lowercased")
        assertEquals("net.core", host.protocols.resolve("ftp://host/file.iso")?.pluginId)
    }

    @Test
    fun schemeLessAndEmptyInputsResolveToNull() {
        val host = host()
        host.install(ClaimingPlugin("net.core", listOf("https", "ftp", "magnet"), priority = 0))

        assertNull(host.protocols.resolve("weird"), "a bare word has no scheme")
        assertNull(host.protocols.resolve(""), "empty input has no scheme")
        assertNull(host.protocols.resolve("   "), "blank input has no scheme")
        assertNull(host.protocols.resolve("C:\\Users\\me\\file.bin"), "a local Windows path is not a URL")
        assertNull(host.protocols.resolve("ed2k://|file|x|1|h|/"), "scheme parses but nobody declares it")
    }

    @Test
    fun declaredSchemeIsQueryableDirectlyAndCaseInsensitively() {
        val host = host()
        host.install(ClaimingPlugin("backend.webdav", listOf("webdavs"), priority = 5, label = "webdav"))

        assertEquals(1, host.protocols.claimsFor("webdavs").size)
        assertEquals(1, host.protocols.claimsFor("WEBDAVS").size, "query is normalized like a URL scheme")
        assertEquals("webdav", host.protocols.claimsFor("webdavs").single().label)
        assertTrue(host.protocols.claimsFor("").isEmpty())
    }

    // ---------- one plugin, many protocols ----------

    @Test
    fun onePluginCanDeclareSeveralProtocols() {
        val host = host()
        host.install(ClaimingPlugin("backend.p2p", listOf("magnet", "bt", "ed2k"), priority = 50))

        for (scheme in listOf("magnet", "bt", "ed2k")) {
            assertEquals("backend.p2p", host.protocols.resolve("$scheme:whatever")?.pluginId, "scheme=$scheme")
        }
        assertEquals(3, host.protocols.allClaims().size, "three schemes from a single plugin")
        assertEquals(setOf("bt", "ed2k", "magnet"), host.protocols.allClaims().map { it.scheme }.toSet())
    }

    @Test
    fun claimsSurviveMultipleQueriesAndAreOrderedByPriority() {
        val host = host()
        host.install(ClaimingPlugin("backend.slow", listOf("ftp"), priority = 10))
        host.install(ClaimingPlugin("backend.fast", listOf("ftp"), priority = 90))

        val claims = host.protocols.claimsFor("ftp")
        assertEquals(listOf("backend.fast", "backend.slow"), claims.map { it.pluginId })
        assertEquals("backend.fast", host.protocols.resolve("ftp://h/x")?.pluginId, "highest priority wins")
        // Same answer on a second query: the index is built from the registry, not a stale cache.
        assertEquals(claims, host.protocols.claimsFor("ftp"))
    }

    // ---------- conflicts ----------

    @Test
    fun conflictsReportSchemesClaimedByMoreThanOnePlugin() {
        val host = host()
        host.install(ClaimingPlugin("backend.a", listOf("ftp", "sftp"), priority = 10))
        host.install(ClaimingPlugin("backend.b", listOf("ftp"), priority = 20))

        val conflicts = host.protocols.conflicts()
        assertEquals(1, conflicts.size, "only ftp has two claimants")
        val (scheme, claimants) = conflicts.single()
        assertEquals("ftp", scheme)
        assertEquals(listOf("backend.b", "backend.a"), claimants.map { it.pluginId })
    }

    @Test
    fun aPluginClaimingTheSameSchemeTwiceIsNotAConflict() {
        val host = host()
        host.install(ClaimingPlugin("backend.dup", listOf("hls", "hls"), priority = 0))

        assertEquals(1, host.protocols.claimsFor("hls").size, "duplicate claims are collapsed per plugin")
        assertTrue(host.protocols.conflicts().isEmpty())
    }

    // ---------- uninstall ----------

    @Test
    fun uninstallingAPluginRemovesItsClaimsFromTheIndex() {
        val host = host()
        host.install(ClaimingPlugin("backend.p2p", listOf("magnet", "bt"), priority = 0))

        assertEquals(2, host.protocols.allClaims().size)
        assertEquals("backend.p2p", host.protocols.resolve("magnet:?xt=x")?.pluginId)

        host.uninstall("backend.p2p")

        assertEquals(emptyList(), host.protocols.claimsFor("magnet"))
        assertTrue(host.protocols.allClaims().isEmpty(), "no claims may outlive the plugin")
        assertNull(host.protocols.resolve("magnet:?xt=x"))
    }

    @Test
    fun uninstallingOnePluginLeavesTheOtherClaimants() {
        val host = host()
        host.install(ClaimingPlugin("backend.a", listOf("ftp"), priority = 10))
        host.install(ClaimingPlugin("backend.b", listOf("ftp"), priority = 20))

        host.uninstall("backend.b")

        assertEquals(listOf("backend.a"), host.protocols.claimsFor("ftp").map { it.pluginId })
        assertTrue(host.protocols.conflicts().isEmpty())
    }

    // ---------- malformed declarations ----------

    @Test
    fun malformedClaimsAreIgnoredInsteadOfPoisoningTheIndex() {
        val host = host()
        host.install(ClaimingPlugin("backend.broken", listOf("", "not a scheme!", "HLS"), priority = 0))

        // "HLS" normalizes to "hls" (same rule as URL schemes); the two unusable ones are dropped.
        assertEquals(listOf("hls"), host.protocols.allClaims().map { it.scheme })
        assertNull(host.protocols.resolve("not a scheme!:x"))
    }
}

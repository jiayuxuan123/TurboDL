package dev.turbodl.plugin.runtime.ext

import dev.turbodl.plugin.runtime.ExtensionRegistry

/**
 * ProtocolRegistry — the load-time index of "scheme → who declares it".
 *
 * Claims are registered by plugins at [ExtensionPoints.PROTOCOL_HANDLER] while they load, so the
 * question "which plugin handles this URL?" is answerable **at load time** (market listing,
 * diagnostics, route preview, conflict reporting) instead of being recomputed per download by
 * exercising every backend's `supports()` predicate.
 *
 * This index is declarative and additive:
 *  - it does NOT route downloads — the [ExtensionPoints.DOWNLOAD_BACKEND] predicates still decide
 *    what actually handles a request, so a claim that disagrees with a predicate changes nothing
 *    at download time;
 *  - a claim grants no permission (see the Convention §12 "Protocol declarations").
 *
 * ### Why there is no cached index and no change callback
 *
 * [ExtensionRegistry] has no registration/unregistration callback (its
 * [ExtensionRegistry.register] returns a cancel-only handle), so a cache here could not be
 * invalidated when a plugin unloads or when `onLoad` fails and the kernel rolls its disposer
 * back — it would keep answering with claims from plugins that are gone. This class therefore
 * builds the view **per query** straight from the registry, which is always correct, and pays
 * for it with an O(#claims) scan. Claims are a handful of entries per scheme (one per plugin),
 * so the scan is free next to the network work it informs; correctness after uninstall was
 * chosen over a micro-optimization.
 *
 * All query methods are safe to call from any thread and never throw: an unparsable URL or a
 * malformed claim yields `null` / an empty list instead of an exception.
 */
class ProtocolRegistry(private val extensions: ExtensionRegistry) {

    /**
     * All declarations for [scheme], highest priority first (ties keep registration order).
     *
     * [scheme] is normalized the same way as a URL-derived scheme, so callers may pass "HLS".
     * An empty/blank/unusable scheme yields an empty list.
     */
    fun claimsFor(scheme: String): List<ProtocolClaim> {
        val normalized = normalizeScheme(scheme) ?: return emptyList()
        return snapshot()
            .filter { it.scheme == normalized }
            .sortedByDescending { it.priority }
    }

    /**
     * Every declaration in the index, deduplicated by (scheme, plugin), highest priority first
     * within each scheme and schemes sorted alphabetically for stable output. Intended for
     * diagnostics and market listings; use [claimsFor] for a single lookup.
     */
    fun allClaims(): List<ProtocolClaim> =
        snapshot()
            .groupBy { it.scheme }
            .toSortedMap()
            .flatMap { (_, claims) -> claims.sortedByDescending { it.priority } }

    /**
     * Resolve the scheme of [url] and return the highest-priority claim for it, or null when the
     * URL has no parsable scheme or nobody declares that scheme.
     *
     * Handles `magnet:?xt=...` (no `//` host), `ftp://host/path`, `webdav(s)://...`, and
     * case-insensitively `HTTPS://H/x`; a scheme-less bare string, an empty string and a Windows
     * drive path (`C:\dir\file`) all yield null.
     *
     * NOTE: a scheme is not a content type. `https://host/x.m3u8` resolves to the `https`
     * scheme — HLS is a format carried over HTTP, so its claim is discoverable via
     * `claimsFor("hls")` but never via this method for an .m3u8 URL. Content-type routing stays
     * with the backend predicate.
     */
    fun resolve(url: String): ProtocolClaim? = schemeOf(url)?.let { claimsFor(it).firstOrNull() }

    /**
     * Schemes declared by more than one plugin, as (scheme → claimants, priority first). The
     * market reports these so a user knows two plugins will both claim the same protocol and can
     * see who wins on priority; a plugin claiming the same scheme twice is not a conflict with
     * itself (entries are deduplicated by plugin).
     */
    fun conflicts(): List<Pair<String, List<ProtocolClaim>>> =
        allClaims()
            .groupBy { it.scheme }
            .filterValues { it.map(ProtocolClaim::pluginId).distinct().size > 1 }
            .toSortedMap()
            .map { (scheme, claims) -> scheme to claims }

    // ---------- internals ----------

    /**
     * Effective claims, read fresh from the extension registry.
     *
     * The scheme is normalized and invalid declarations are dropped rather than surfaced, so a
     * malformed claim cannot poison a market listing. The registration's owner fills a blank
     * [ProtocolClaim.pluginId] (the host knows who registered; the plugin may have left it out).
     */
    private fun snapshot(): List<ProtocolClaim> =
        extensions.registrations(ExtensionPoints.PROTOCOL_HANDLER).mapNotNull { reg ->
            val scheme = normalizeScheme(reg.instance.scheme) ?: return@mapNotNull null
            reg.instance.copy(
                scheme = scheme,
                pluginId = reg.instance.pluginId.ifBlank { reg.ownerPluginId },
            )
        }.dedupeByClaimant()

    private fun List<ProtocolClaim>.dedupeByClaimant(): List<ProtocolClaim> {
        val seen = HashSet<String>()
        return filter { seen.add(it.scheme + "\u0000" + it.pluginId) }
    }

    companion object {
        /**
         * Parse the scheme of [url]: case-insensitive, validated (1–32 chars,
         * `[a-z][a-z0-9+.-]*`), null when there is nothing to parse. Never throws.
         */
        private fun schemeOf(url: String): String? {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return null
            // A Windows drive path ("C:\dir\file") looks like a URI with scheme "c" to RFC 3986,
            // but for routing purposes it is a local path, not a download URL: nothing claims it.
            if (DRIVE_PATH.matches(trimmed)) return null
            val match = SCHEME.find(trimmed) ?: return null
            return normalizeScheme(match.groupValues[1])
        }

        /** trim + lowercase + validate; null when the value is not a usable protocol token. */
        private fun normalizeScheme(raw: String): String? {
            val s = raw.trim().lowercase()
            return if (VALID_SCHEME.matches(s)) s else null
        }

        /** `scheme:` per RFC 3986, unanchored at the tail so `magnet:?xt=...` parses. */
        private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*):")

        /** Local Windows path (drive letter + separator), not a URL. */
        private val DRIVE_PATH = Regex("^[a-zA-Z]:[\\\\/]")

        /**
         * Maximum accepted protocol token: 1–32 chars, lowercase, starting with a letter —
         * the same shape the manifest `protocols` field allows, so a claim and a manifest entry
         * can never disagree about what a legal scheme is.
         */
        private val VALID_SCHEME = Regex("^[a-z][a-z0-9+.-]{0,31}$")
    }
}

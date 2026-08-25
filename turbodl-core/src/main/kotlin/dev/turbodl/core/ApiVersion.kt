package dev.turbodl.core

/**
 * TurboDL public API version and compatibility policy.
 *
 * This is the single source of truth for the version that plugins negotiate against. It follows
 * semantic versioning applied to the PUBLIC, plugin-facing API surface (not to internal engine
 * classes):
 *  - MAJOR is bumped on a breaking change to any stable public contract
 *    (DownloadBackend / BackendContext / BackendResult / DownloadRequest / TurboEvent /
 *     BackendResolver / TurboBackends / TurboHttpClients, and the runtime kernel contracts).
 *  - MINOR is bumped on backwards-compatible additions.
 *  - PATCH is bumped on backwards-compatible fixes.
 *
 * A plugin declares the minimum API version it needs. The host loads it only when
 * [ApiVersion.CURRENT] satisfies that minimum AND shares the same MAJOR. A higher host MAJOR is
 * considered incompatible on purpose, so a breaking release fails loudly at load time instead of
 * silently corrupting behavior.
 *
 * NOTE: reserved — a future JS Provider negotiates the same [ApiVersion] before exposing the API
 * surface to scripts; the policy here is language-agnostic.
 */
data class ApiVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ApiVersion> {

    override fun compareTo(other: ApiVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    /**
     * Whether this (the host's current) version can run a plugin that requires [required].
     * Same MAJOR and this >= required. Cross-MAJOR is always incompatible.
     */
    fun satisfies(required: ApiVersion): Boolean =
        major == required.major && this >= required

    companion object {
        /** The current public API version of this TurboDL build. */
        val CURRENT: ApiVersion = ApiVersion(1, 0, 0)

        /** Parse "MAJOR.MINOR.PATCH" (extra pre-release/build metadata after '-' or '+' ignored). */
        fun parse(text: String): ApiVersion {
            val core = text.trim().substringBefore('-').substringBefore('+')
            val parts = core.split('.')
            require(parts.size == 3) { "Invalid API version '$text' (expected MAJOR.MINOR.PATCH)" }
            return ApiVersion(
                parts[0].trim().toInt(),
                parts[1].trim().toInt(),
                parts[2].trim().toInt(),
            )
        }
    }
}

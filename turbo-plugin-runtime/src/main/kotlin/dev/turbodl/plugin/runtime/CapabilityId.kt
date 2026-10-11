package dev.turbodl.plugin.runtime

/**
 * Capability identifiers: `<namespace>.cap.<name>[@<semver>]`.
 *
 * ## Why this exists
 *
 * A capability is a **contract** — it says "this plugin provides (or needs) *this* thing".
 * Two plugins that both claim `cloud.parser` have no way to know whether they mean the same
 * thing, and neither does the host. Namespacing answers "whose contract is this?" and the
 * version answers "which revision of the contract?".
 *
 * Design doc §12.1–12.4 states the rules; this file is their executable form. The rules:
 *
 *  1. **No bare strings.** `download.engine` and `ai.provider` are rejected. Every capability
 *     carries a namespace: `dev.turbodl.cap.*` for the SDK's own capabilities, or the
 *     host's / the plugin author's own reverse-domain namespace.
 *  2. **SemVer is part of the id.** Written `id@version`, e.g. `dev.turbodl.cap.file.handler.video@1.2.0`.
 *     `@1.3.0` and `@2.0.0` are *not* automatically compatible — the host matches versions
 *     explicitly rather than assuming.
 *  3. **Capability ≠ permission.** Declaring `dev.turbodl.cap.storage` grants nothing; the permission
 *     system still decides what the plugin can actually touch. Keeping these apart is what stops
 *     a capability declaration from becoming a privilege escalation.
 *
 * ## Compatibility
 *
 * Existing plugins declare bare names (`http`, `crypto`, …). Those are still read during a
 * migration window, but they are **reported** as deprecated rather than silently accepted —
 * see [parseLegacy]. A silent upgrade would hide the fact that the contract changed, and the
 * whole point of versioning is that the change is visible.
 */
object CapabilityId {

    /** The separator between the id and its version. */
    const val VERSION_SEP = '@'

    /** The infix every capability must contain. */
    const val CAP_INFIX = ".cap."

    /**
     * Namespaces the SDK itself reserves. Third parties must not claim these — otherwise
     * anyone could publish `dev.turbodl.cap.task.executor` and be trusted as if it came
     * from the SDK.
     *
     * **Deliberately does NOT contain a host's namespace.** A host (YunGet, for one) reserves
     * its own (`yung`) in its own code, not here: this class ships inside the SDK, and baking
     * one application's name into it would make the SDK's public surface depend on that
     * application — exactly the coupling a standalone SDK must avoid.
     */
    val RESERVED_NAMESPACES: Set<String> = setOf("dev.turbodl")

    /** A parsed capability id. */
    data class Parsed(
        /** Full namespace, e.g. `yung` or `dev.turbodl`. May be dotted. */
        val namespace: String,
        /** The name after `.cap.`, e.g. `file.handler.video`. */
        val name: String,
        /** The version, e.g. `1.2.0`. */
        val version: SemVer,
    ) {
        /** Canonical form: always lower-case, always with version. */
        val canonical: String get() = "$namespace$CAP_INFIX$name$VERSION_SEP$version"

        /** True when this id sits in a reserved namespace. */
        val isReserved: Boolean get() = namespace in RESERVED_NAMESPACES
    }

    /** A plain SemVer triple. Deliberately minimal — we compare, we do not range-match yet. */
    data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
        override fun compareTo(other: SemVer): Int =
            compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

        override fun toString(): String = "$major.$minor.$patch"

        companion object {
            /** Parses `1.2.3`. Rejects `1.2`, `v1.2.3`, `1.2.3-rc1` — this is a contract id. */
            fun parse(raw: String): SemVer? {
                val parts = raw.split('.')
                if (parts.size != 3) return null
                val nums = parts.map { p ->
                    // 只接受纯数字：前导 +/-、空格、前导零都视为非法，
                    // 否则 "1.2.03" 与 "1.2.3" 会被当成同一个版本。
                    if (p.isEmpty() || p.length > 1 && p[0] == '0') return null
                    p.toIntOrNull() ?: return null
                }
                if (nums.any { it < 0 }) return null
                return SemVer(nums[0], nums[1], nums[2])
            }
        }
    }

    /** Why a capability string was rejected, in terms the plugin author can act on. */
    sealed interface Result {
        data class Ok(val parsed: Parsed) : Result

        /** Valid under the old bare-name scheme; accepted during migration but flagged. */
        data class Legacy(val bare: String) : Result

        data class Invalid(val reason: String) : Result
    }

    /**
     * Parses a capability string.
     *
     * Returns [Result.Legacy] for a bare name that matches one of [legacyNames] — those are the
     * values that were valid before this scheme existed. Anything else malformed is
     * [Result.Invalid]: an unknown bare name is not "legacy", it is a typo or a missing namespace,
     * and treating it as legacy would let the whole scheme be bypassed by writing a new bare name.
     */
    fun parse(raw: String, legacyNames: Set<String> = emptySet()): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result.Invalid("能力标识不能为空")

        val at = text.lastIndexOf(VERSION_SEP)
        if (at < 0) {
            return if (text.lowercase() in legacyNames) Result.Legacy(text)
            else Result.Invalid(
                "缺少命名空间或版本：`$text` 应为 `<namespace>.cap.<name>@<major.minor.patch>`" +
                    "（例：dev.turbodl.cap.storage@1.0.0）"
            )
        }

        val idPart = text.substring(0, at)
        val versionPart = text.substring(at + 1)
        if (idPart.isEmpty()) return Result.Invalid("`$text` 缺少 `@` 前的能力名")

        val version = SemVer.parse(versionPart)
            ?: return Result.Invalid("版本 `$versionPart` 不是合法的 SemVer 三段式（应为 major.minor.patch）")

        if (idPart != idPart.lowercase()) {
            return Result.Invalid("能力名必须全小写：`$idPart`")
        }

        val capAt = idPart.indexOf(CAP_INFIX)
        if (capAt <= 0) {
            return Result.Invalid(
                "缺少 `.cap.` 分隔：`$idPart` 应为 `<namespace>.cap.<name>`（例：dev.turbodl.cap.download.engine）"
            )
        }
        val namespace = idPart.substring(0, capAt)
        val name = idPart.substring(capAt + CAP_INFIX.length)
        if (name.isEmpty()) return Result.Invalid("`.cap.` 之后没有能力名：`$idPart`")
        if (!isValidNamespace(namespace)) return Result.Invalid("命名空间 `$namespace` 不合法")
        if (!isValidName(name)) return Result.Invalid("能力名 `$name` 不合法")

        return Result.Ok(Parsed(namespace, name, version))
    }

    /**
     * Namespace shape: lowercase, dot-separated labels, each `[a-z][a-z0-9-]*`.
     *
     * A single label (`yung`) and a dotted reverse-domain (`dev.turbodl`) are both valid —
     * the rule is about being *attributable*, not about counting dots.
     */
    private fun isValidNamespace(ns: String): Boolean =
        ns.split('.').all { label ->
            label.isNotEmpty() &&
                label[0].isLetter() &&
                label.all { it.isLowerCase() || it.isDigit() || it == '-' }
        }

    /** Name shape: lowercase dot-separated labels — `file.handler.video`, `storage`. */
    private fun isValidName(name: String): Boolean =
        name.split('.').all { label ->
            label.isNotEmpty() &&
                label[0].isLetter() &&
                label.all { it.isLowerCase() || it.isDigit() || it == '-' }
        }

    /**
     * Compares two parsed versions the way the design doc requires: same MAJOR means the
     * contracts are compatible; a different MAJOR means they are not, and must be matched
     * explicitly rather than assumed.
     */
    fun compatible(required: Parsed, provided: Parsed): Boolean =
        required.namespace == provided.namespace &&
            required.name == provided.name &&
            required.version.major == provided.version.major &&
            provided.version >= required.version
}

/**
 * The capabilities TurboDL itself defines. Declaring them as constants means a typo becomes a
 * compile error inside the engine instead of a capability that silently never matches.
 *
 * Third parties must not use these namespaces — see [CapabilityId.RESERVED_NAMESPACES].
 */
object TurboCapabilities {

    /** Download execution backends (an alternative engine implementation). */
    const val DOWNLOAD_ENGINE = "dev.turbodl.cap.download.engine@1.0.0"

    /** Link parsers: turn a share link into downloadable requests. */
    const val LINK_PARSER = "dev.turbodl.cap.link.parser@1.0.0"

    /** Task pre-hooks: adjust a request before it is executed. */
    const val TASK_PRE_HOOK = "dev.turbodl.cap.task.pre.hook@1.0.0"

    /** Task post-hooks: act after a task finishes. */
    const val TASK_POST_HOOK = "dev.turbodl.cap.task.post.hook@1.0.0"

    /**
     * Storage scoped to the plugin's own sandbox.
     *
     * Note this grants **nothing** by itself — the permission system still has to allow storage
     * access. Capability is the contract, permission is the boundary (design doc §12.3).
     */
    const val STORAGE = "dev.turbodl.cap.storage@1.0.0"

    /** Outbound HTTP. Likewise a contract, not a permission. */
    const val HTTP = "dev.turbodl.cap.http@1.0.0"

    /** All capabilities the engine defines. */
    val ALL: Set<String> = setOf(
        DOWNLOAD_ENGINE, LINK_PARSER, TASK_PRE_HOOK, TASK_POST_HOOK, STORAGE, HTTP,
    )
}

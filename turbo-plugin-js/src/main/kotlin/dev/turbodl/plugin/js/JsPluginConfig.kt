package dev.turbodl.plugin.js

import dev.turbodl.plugin.runtime.CapabilityId

import java.io.File

/**
 * JsPluginConfig — the per-loader knobs for the JS provider.
 *
 * Everything here is a **safety or resource bound**, not a feature flag: a JS plugin is arbitrary
 * third-party code running inside the host process, so the loader (the party that grants it a
 * runtime) is the one that sets its ceilings. Defaults are deliberately tight — sized for
 * "a link parser that fetches a page and a manifest", not for "an embedded application server".
 *
 * @param permissions capabilities granted to the produced plugins (blanket grant; a per-plugin
 *   `permissions` attribute on the source narrows this further — the intersection wins).
 * @param evaluationTimeoutMillis hard cap on a single JS invocation's *executing* time. QuickJS
 *   counts only JavaScript time, so a slow `host.http` call is bounded by [httpTimeoutMillis]
 *   instead; a runaway loop is killed here.
 * @param invocationTimeoutMillis outer bound the caller waits for an invocation (JS time + host
 *   time). Must be ≥ [evaluationTimeoutMillis] or the engine timeout can never fire first.
 * @param drainTimeoutMillis how long unload waits for in-flight JS before declaring the instance
 *   stuck and refusing to dispose it (leaked engine beats use-after-free).
 * @param memoryLimitBytes QuickJS heap cap per plugin instance; exceeding it throws inside JS
 *   instead of growing the JVM process.
 * @param maxStackSizeBytes native stack cap per instance — this is what turns unbounded JS
 *   recursion into a catchable `InternalError` rather than a hard VM crash.
 * @param maxResponseBytes cap on a response body read into memory by `host.http.request`.
 * @param maxDownloadBytes cap for `host.http.downloadToFile` (streamed to disk, still bounded).
 * @param maxStorageBytes total on-disk quota for `host.storage` in the plugin's sandbox.
 * @param httpTimeoutMillis per-call OkHttp connect/read ceiling for host HTTP.
 * @param followRedirects whether `host.http` may follow redirects at all. Off by default so a plugin
 *   must ask for the target it actually wants (same posture as the HLS backend, which validates each
 *   hop). When on, following is hop-by-hop with the origin/scheme policy re-applied per hop, and a
 *   single call may still disable it with `followRedirects: false` — a script can narrow this, never
 *   widen it.
 * @param sandboxRoot root directory for per-plugin sandbox dirs (storage/downloads). Null →
 *   `<TurboConfig.workDir>` → `java.io.tmpdir/turbodl-js`.
 * @param allowedEnvVars names `host.env.get` may read. Empty by default: no ambient environment
 *   leaks into a plugin unless the loader explicitly lists it.
 * @param allowExternalDestination whether a JS parser's returned `destination` may be an absolute
 *   path outside the plugin sandbox. **False by default**: a script's request descriptor then only
 *   accepts a bare file name, and the host places it in the plugin's `downloads/` sandbox. Turning
 *   this on is a host decision (an app that trusts the script to write where the user asked), not
 *   a plugin one — which is why it is a loader knob rather than a manifest field.
 */
data class JsPluginConfig(
    val permissions: Set<JsCapability> = JsCapability.defaultGrant(),
    val evaluationTimeoutMillis: Long = 5_000,
    val invocationTimeoutMillis: Long = 20_000,
    val drainTimeoutMillis: Long = 5_000,
    val memoryLimitBytes: Long = 32L * 1024 * 1024,
    val maxStackSizeBytes: Long = 512L * 1024,
    val maxResponseBytes: Long = 8L * 1024 * 1024,
    val maxDownloadBytes: Long = 512L * 1024 * 1024,
    val maxStorageBytes: Long = 4L * 1024 * 1024,
    val httpTimeoutMillis: Long = 30_000,
    val followRedirects: Boolean = false,
    val sandboxRoot: File? = null,
    val allowedEnvVars: Set<String> = emptySet(),
    val allowExternalDestination: Boolean = false,

    /**
     * 凭据后端；宿主注入。不给就是 [JsSecretStore.Unavailable]（写操作会报错，
     * 见那里的注释：静默丢弃比报错糟得多）。
     *
     * 命名空间不在这里 —— 一个 loader 实例服务多个插件，而命名空间是**按插件**的，
     * 所以由 loader 在装载时按 pluginId 构造 [JsSecretScope]（见 JsPluginLoaderPlugin）。
     */
    val secretStore: JsSecretStore = JsSecretStore.Unavailable,

) {
    init {
        require(evaluationTimeoutMillis > 0) { "evaluationTimeoutMillis must be > 0" }
        require(invocationTimeoutMillis >= evaluationTimeoutMillis) {
            "invocationTimeoutMillis ($invocationTimeoutMillis) must be >= evaluationTimeoutMillis " +
                "($evaluationTimeoutMillis), otherwise the outer bound kills work the engine would have stopped cleanly"
        }
        require(drainTimeoutMillis > 0) { "drainTimeoutMillis must be > 0" }
        require(memoryLimitBytes >= 1024 * 1024) { "memoryLimitBytes is too small to be useful (>= 1MiB)" }
        require(maxStackSizeBytes >= 64 * 1024) { "maxStackSizeBytes must be >= 64KiB" }
        require(maxResponseBytes > 0) { "maxResponseBytes must be > 0" }
        require(maxDownloadBytes > 0) { "maxDownloadBytes must be > 0" }
        require(maxStorageBytes > 0) { "maxStorageBytes must be > 0" }
        require(httpTimeoutMillis > 0) { "httpTimeoutMillis must be > 0" }
    }

    /**
     * Cap for one JS invocation's own evaluation timeout, given a caller budget of
     * [budgetMillis] (e.g. a hook that must return before a submit-path deadline). Returns the
     * evaluation timeout to apply for that call: never larger than the budget, never 0 (which
     * would mean "no limit" to QuickJS).
     */
    fun evaluationTimeoutFor(budgetMillis: Long): Long =
        if (budgetMillis <= 0) evaluationTimeoutMillis
        else minOf(evaluationTimeoutMillis, budgetMillis).coerceAtLeast(1)
}
/**
 * Host capabilities, each one a permission boundary.
 *
 * A capability is granted by name at load time; every call into it is re-checked at dispatch time,
 * so "not granted" and "granted but refused" (e.g. a non-http scheme) are two distinct, debuggable
 * errors instead of one undefined behavior.
 */
enum class JsCapability(val jsName: String) {
    /** `host.http` — outbound requests and whole-file downloads. */
    HTTP("http"),

    /** `host.crypto` — digests, HMAC, base64/hex, CSPRNG bytes. */
    CRYPTO("crypto"),

    /** `host.log` — prefixed logging for this plugin id. */
    LOG("log"),

    /** `host.storage` — small persistent KV scoped to this plugin's sandbox. */
    STORAGE("storage"),

    /** `host.time` — clock and date formatting. */
    TIME("time"),

    /** `host.env` — read-only access to a loader-approved allowlist of environment values. */
    ENV("env"),

    /** `host.setTimeout`/`setInterval` — JS timers, always cancellable on unload. */
    TIMER("timer"),

    /**
     * `host.secrets` — 加密存取凭据（cookie / token / 账号口令）。
     *
     * **默认不授予**（见 [defaultGrant]）：凭据是这套系统里最敏感的数据，
     * 多开一条让插件访问它的路径就多一个被滥用的面。必须由用户显式授权，
     * 且授权项要出现在安装确认界面上。
     */
    SECRETS("secrets"),
    ;

    companion object {

        /**
         * The default grant: the three capabilities the JS provider documents as "minimum", plus
         * time (free, no boundary). `storage`, `env` and `timer` are **not** in the default set —
         * persistence and ambient data are opt-ins the loader must decide about.
         */
        fun defaultGrant(): Set<JsCapability> = setOf(HTTP, CRYPTO, LOG, TIME)

        /** Parse a manifest/source permission string ("http,crypto") into the enum set. */
        /**
         * Parses a permission spec into the capability set.
         *
         * Accepts two forms (design doc §12.1–12.2):
         *
         *  - **current**: `dev.turbodl.cap.http@1.0.0` — namespaced and versioned;
         *  - **legacy**: `http`, `crypto` — the bare names this engine shipped with.
         *
         * Legacy names stay readable during the migration window, but only the ones this enum
         * actually defines. An *unknown* bare name is not legacy, it is a typo — accepting it
         * would let the namespacing rule be bypassed by inventing a new bare name.
         *
         * Unrecognised tokens are **dropped**, not fatal: a plugin written against a newer
         * engine may declare capabilities this version has never heard of, and refusing to load
         * it would be worse than running it with fewer permissions. The caller can compare the
         * result size against the input to notice.
         */
        fun parse(spec: String?): Set<JsCapability> {
            if (spec.isNullOrBlank()) return emptySet()
            return spec.split(',', ' ', ';')
                .mapNotNull { token -> resolve(token.trim()) }
                .toSet()
        }

        /** Resolves one token to a capability, or null when it names nothing we know. */
        private fun resolve(token: String): JsCapability? {
            if (token.isEmpty()) return null
            val lower = token.lowercase()
            // 旧式裸名
            entries.firstOrNull { it.jsName == lower }?.let { return it }
            // 新式带命名空间与版本：取 `.cap.` 之后的名字再对一次
            if (!lower.contains(CapabilityId.CAP_INFIX)) return null
            val idPart = lower.substringBefore(CapabilityId.VERSION_SEP)
            val name = idPart.substringAfter(CapabilityId.CAP_INFIX, "")
            if (name.isEmpty()) return null
            // 命名空间必须是保留的那两个之一，否则不允许映射到宿主能力
            val ns = idPart.substringBefore(CapabilityId.CAP_INFIX, "")
            if (ns !in CapabilityId.RESERVED_NAMESPACES) return null
            // 名字里可能还有子路径（dev.turbodl.cap.http）；取最后一段作能力名
            return entries.firstOrNull { it.jsName == name || name.endsWith("." + it.jsName) }
        }
    }
}

/** A capability call that was refused by policy (not granted, or outside its bounds). */
internal class JsPermissionException(message: String) : Exception(message)

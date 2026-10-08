package dev.turbodl.plugin.js

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
    ;

    companion object {

        /**
         * The default grant: the three capabilities the JS provider documents as "minimum", plus
         * time (free, no boundary). `storage`, `env` and `timer` are **not** in the default set —
         * persistence and ambient data are opt-ins the loader must decide about.
         */
        fun defaultGrant(): Set<JsCapability> = setOf(HTTP, CRYPTO, LOG, TIME)

        /** Parse a manifest/source permission string ("http,crypto") into the enum set. */
        fun parse(spec: String?): Set<JsCapability> {
            if (spec.isNullOrBlank()) return emptySet()
            return spec.split(',', ' ', ';')
                .mapNotNull { token ->
                    val name = token.trim().lowercase()
                    if (name.isEmpty()) null else entries.firstOrNull { it.jsName == name }
                }
                .toSet()
        }
    }
}

/** A capability call that was refused by policy (not granted, or outside its bounds). */
internal class JsPermissionException(message: String) : Exception(message)

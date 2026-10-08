package dev.turbodl.plugin.js

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Byte and text ceilings for the JS provider.
 *
 * ## Why every path needs one
 * `docs/plugins/CONVENTION.md` §9 classifies manifest/redirect/link content as untrusted, and the
 * JS provider has one extra hazard: the code reading that content runs **inside the host process**.
 * An unbounded "read the whole response into memory" in a plugin is therefore not a plugin bug, it
 * is a host OOM. So the ceiling is enforced host-side and cannot be raised by the script (a
 * requested `maxBytes` may only lower it).
 *
 * Over-limit is an error, never a silent truncation: a truncated manifest produces a wrong download
 * list that looks like success, which is worse than failing (same reasoning as `HlsIo`).
 */
internal object JsIoLimits {

    /** Request-body size a plugin may send in one `host.http.request`. */
    const val MAX_REQUEST_BODY_BYTES: Long = 2L * 1024 * 1024

    /** Cap on a `Content-Length`-less stream read for [readBounded]. */
    private const val READ_CHUNK = 8 * 1024

    /** Response header block sanity cap (OkHttp already bounds this; we re-check the decoded size). */
    const val MAX_HEADER_COUNT = 64

    /**
     * Read [input] fully, failing as soon as [limit] is exceeded.
     *
     * @throws JsLimitException when the stream is longer than [limit].
     */
    fun readBounded(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) throw JsLimitException("response exceeds the ${limit}B byte limit")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Text of a bounded body, decoded as UTF-8, capped by [JsValueCodec.MAX_STRING_CHARS] chars. */
    fun readBoundedText(input: InputStream, limit: Long): String = asBoundedText(readBounded(input, limit))

    /**
     * Decode bytes that already passed the byte ceiling, applying the *character* ceiling.
     *
     * Split out because a caller that wants to report a byte count must have the byte count, not the
     * decoded length — those differ for any non-ASCII body, and a plugin making a size decision off
     * `bytes` deserves the honest number.
     */
    fun asBoundedText(bytes: ByteArray): String {
        val text = String(bytes, Charsets.UTF_8)
        if (text.length > JsValueCodec.MAX_STRING_CHARS) {
            throw JsLimitException("response text exceeds ${JsValueCodec.MAX_STRING_CHARS} characters")
        }
        return text
    }

    class JsLimitException(message: String) : Exception(message)
}

/**
 * A source uri, shortened for logs.
 *
 * `inline:` and `data:` sources *are* the script, so logging the uri verbatim puts the whole plugin
 * source into every load line — noisy for a 500 KiB script, and a hazard for a host whose logs are
 * shipped or displayed (a script can embed a token in a header literal). The full uri stays
 * available to the host through `PluginSource`; a log line only needs enough to identify it.
 */
internal fun describeSourceUri(uri: String, maxChars: Int = 120): String {
    val trimmed = uri.trim()
    if (trimmed.length <= maxChars) return trimmed
    return trimmed.take(maxChars) + "… (${trimmed.length} chars, ${trimmed.substringBefore(':', trimmed).ifBlank { "file" }})"
}

/**
 * Origin/scheme policy for host HTTP, shared by `host.http` and `host.http.downloadToFile`.
 *
 * This is the JS-facing analogue of `HlsRequestPolicy`, and it exists for the same reason: a plugin
 * hands us a URL and a header map, and the header map may contain the user's cookies or bearer
 * token. Two rules do the protection:
 *
 *  1. **Scheme allowlist** — `http`/`https` only. `file://`/`jar://`/custom schemes from a plugin
 *     would be an SSRF/local-read channel; this is exactly the §9 rule, enforced once here.
 *  2. **Sensitive headers stay on their origin** — `Authorization`/`Cookie`/`Referer`/`Origin`/
 *     `Proxy-Authorization` are stripped for any request whose scheme+host+port differs from the
 *     credential origin. Since a plugin's first request *is* its credential origin unless told
 *     otherwise, an explicit `originUrl` field lets a plugin declare where its credentials live
 *     (e.g. a login host and a CDN host).
 *
 * Redirects are not followed by default (see [JsPluginConfig.followRedirects]); when they are, the
 * policy re-evaluates at the new origin, so a redirect to another host cannot carry credentials.
 */
internal object JsRequestPolicy {

    private val SENSITIVE_HEADERS = setOf(
        "authorization",
        "cookie",
        "origin",
        "proxy-authorization",
        "referer",
    )

    /** Parse and scheme-check a URL the script gave us. */
    fun parseUrl(raw: String): HttpUrl =
        raw.toHttpUrlOrNull()?.takeIf { it.isHttps || it.isHttp }
            ?: throw JsPermissionException("url '$raw' is not a valid http(s) URL")

    private val HttpUrl.isHttp: Boolean get() = scheme == "http"

    /** The credential home: explicit `originUrl` when given and valid, else the request target. */
    fun credentialOrigin(target: HttpUrl, originUrl: String?): HttpUrl =
        if (originUrl.isNullOrBlank()) target else parseUrl(originUrl)

    fun sameOrigin(left: HttpUrl, right: HttpUrl): Boolean =
        left.scheme == right.scheme && left.host == right.host && left.port == right.port

    /** Drop cross-origin sensitive headers; keep everything else (UA etc. must still work). */
    fun headersFor(target: HttpUrl, credentialOrigin: HttpUrl, headers: Map<String, String>): Map<String, String> =
        if (sameOrigin(target, credentialOrigin)) headers
        else headers.filterKeys { it.lowercase() !in SENSITIVE_HEADERS }

    /**
     * Build the OkHttp request for one hop.
     *
     * `User-Agent` is filled with the plugin's identity when the script did not set one, so server
     * logs attribute traffic to the plugin rather than to an anonymous JVM client.
     */
    fun newRequestBuilder(
        targetUrl: HttpUrl,
        credentialOrigin: HttpUrl,
        method: String,
        headers: Map<String, String>,
        body: okhttp3.RequestBody?,
    ): Request.Builder {
        val upper = method.uppercase()
        require(upper in setOf("GET", "POST", "PUT", "HEAD", "DELETE", "PATCH", "OPTIONS")) {
            "unsupported http method '$method'"
        }
        // OkHttp requires a body for POST/PUT/PATCH and forbids one for GET/HEAD.
        if (upper in setOf("POST", "PUT", "PATCH") && body == null) {
            return Request.Builder().url(targetUrl).method(upper, okhttp3.RequestBody.create(null, ByteArray(0))).apply {
                headersFor(targetUrl, credentialOrigin, headers).forEach { (n, v) -> header(n, v) }
                if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) header("User-Agent", DEFAULT_UA)
            }
        }
        if (upper in setOf("GET", "HEAD", "DELETE", "OPTIONS") && body != null) {
            throw JsPermissionException("method $upper must not carry a request body")
        }
        return Request.Builder().url(targetUrl).method(upper, body).apply {
            headersFor(targetUrl, credentialOrigin, headers).forEach { (n, v) -> header(n, v) }
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) header("User-Agent", DEFAULT_UA)
        }
    }

    private const val DEFAULT_UA = "TurboDL-js-plugin/1"

    /** Redirect target for a `Location`, validated the same way as the original URL. */
    fun redirectTarget(location: String?, current: HttpUrl): HttpUrl? {
        if (location.isNullOrBlank()) return null
        return current.resolve(location)?.let { resolved ->
            resolved.takeIf { it.isHttps || it.isHttp }
        }
    }
}

/**
 * The per-plugin file sandbox.
 *
 * `host.storage`, `host.http.downloadToFile` and any temp file a plugin creates must land in a
 * directory owned by that plugin id, and a script-supplied `fileName` must not escape it — the
 * obvious attack is `../../..` or an absolute path. [resolve] is the single choke point: it rejects
 * separators, parent references and absolute paths, then verifies the canonical path is still
 * inside the sandbox root (defense in depth against symlink/suffix tricks).
 */
internal class JsSandbox(
    pluginId: String,
    private val config: JsPluginConfig,
    coreWorkDir: File?,
) {

    internal companion object {
        /**
         * The directory name [JsSandbox] derives for [pluginId]. Sanitization alone is near-identity
         * for the usual `a.b-c` ids but lossy in general — `parser:a`, `parser/a` and `parser_a`
         * would all collapse onto one directory, quietly breaking the per-plugin isolation the
         * sandbox exists for. That is reachable: a host may override a plugin id through the
         * source's `pluginId` attribute with arbitrary characters. The suffix — a short digest of
         * the *original* id — keeps distinct ids on distinct directories while the name stays
         * filesystem-safe and human-recognizable. Exposed as a pure function so tests and
         * diagnostics can name a plugin's directory without creating one.
         */
        internal fun dirNameFor(pluginId: String): String =
            pluginId.map { if (it.isLetterOrDigit() || it == '-' || it == '.') it else '_' }
                .joinToString("")
                .take(64) + "_" + shortDigest(pluginId)

        /** A filesystem-safe fingerprint of the original id; 40 bits makes an accidental collision implausible. */
        private fun shortDigest(value: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .take(5)
                .joinToString("") { "%02x".format(it) }
    }

    val root: File = File(config.sandboxRoot ?: coreWorkDir?.parentFile?.let { File(it, "turbodl-js") } ?: File(System.getProperty("java.io.tmpdir"), "turbodl-js"), dirNameFor(pluginId))

    val storage: File get() = File(root, "storage")
    val downloads: File get() = File(root, "downloads")

    init {
        storage.mkdirs()
        downloads.mkdirs()
    }

    /**
     * Resolve [name] inside [dir], refusing anything that could escape.
     *
     * @param name a *relative, single-segment* file name; nested paths are rejected rather than
     *   created, so a plugin cannot scatter files across the sandbox.
     */
    fun resolve(dir: File, name: String): File {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw JsPermissionException("file name must not be blank")
        if (trimmed.contains('/') || trimmed.contains('\\')) {
            throw JsPermissionException("file name '$name' must not contain path separators")
        }
        if (trimmed == "." || trimmed == ".." || trimmed.startsWith(".")) {
            throw JsPermissionException("file name '$name' is not allowed")
        }
        if (File(trimmed).isAbsolute) throw JsPermissionException("file name '$name' must be relative")
        val candidate = File(dir, trimmed)
        // canonicalize 是一次文件系统调用，会抛 IOException：名字含该文件系统的非法字符
        // （Windows 上尤其多）、路径过长、符号链接成环等。而这个字符串**完全由脚本控制**，
        // 所以任何一种都必须落回本沙箱的"拒绝"语义（JsPermissionException → 该条路由失败），
        // 而不是把原生 IOException 抛穿调用方 —— 那等于让脚本用它崩掉宿主。
        val base = dir.canonicalFileOrRefuse(name)
        val target = candidate.canonicalFileOrRefuse(name)
        if (!target.path.startsWith(base.path + File.separator)) {
            throw JsPermissionException("path '$name' escapes the plugin sandbox")
        }
        return target
    }

    /** [File.canonicalFile]，但把文件系统拒绝（非法名字、过长路径等）翻译成本沙箱的拒绝语义。 */
    private fun File.canonicalFileOrRefuse(name: String): File = try {
        canonicalFile
    } catch (e: java.io.IOException) {
        throw JsPermissionException("file name '$name' cannot be resolved on this filesystem: ${e.message}")
    }

    /** Total bytes currently used under [storage] (for the quota check). */
    fun storageBytes(): Long = storage.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Remove scratch downloads, keeping [storage].
     *
     * A `host.http.downloadToFile` result is only useful to the plugin that made it, so it is
     * teardown garbage — unlike `host.storage`, which is the plugin's own state and may legitimately
     * survive an unload/reinstall cycle (a session token, a resume cursor).
     */
    fun clearDownloads() {
        downloads.listFiles()?.forEach { file -> runCatching { file.deleteRecursively() } }
    }

    /**
     * Turn a script-supplied `destination` into a [File] the engine may write to.
     *
     * This is the highest-risk string in the whole JS surface: a parser returns request
     * descriptors, and a descriptor's destination is a path the *engine* will create and fill. Left
     * unchecked, one script could overwrite any file the host process can write — which is why the
     * default policy is sandbox-only and a bare file name is the normal case:
     *
     *  - no destination (or blank) → [fallbackName] inside `downloads/`
     *  - a bare relative name → inside `downloads/` (via [resolve], so traversal is impossible)
     *  - anything containing a separator, or an absolute path → only accepted when the loader set
     *    [JsPluginConfig.allowExternalDestination], and then used as given (a deliberate host
     *    choice, e.g. "save where the user picked in the Android picker").
     *
     * With the flag on, the plugin's own `downloads/` sandbox is still where relative names land, so
     * a script cannot be *forced* to know about absolute paths.
     */
    fun resolveDestination(raw: String?, fallbackName: String): File {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return resolve(downloads, sanitizeName(fallbackName))
        val looksScoped = text.contains('/') || text.contains('\\') || File(text).isAbsolute
        if (!looksScoped) return resolve(downloads, text)
        if (!config.allowExternalDestination) {
            throw JsPermissionException(
                "destination '$text' is outside the plugin sandbox; a JS parser may only return a " +
                    "file name unless the loader enables allowExternalDestination",
            )
        }
        return File(text)
    }

    /** Make a name safe to be a file name (used for engine-derived fallbacks, not script input). */
    fun sanitizeName(name: String): String {
        val cleaned = name.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .trimStart('.')
        return if (cleaned.isEmpty()) "download.bin" else cleaned.take(180)
    }

    /** Best-effort teardown. Called from the disposer; failures are the caller's to report. */
    fun deleteRecursively() {
        runCatching { root.deleteRecursively() }
    }
}

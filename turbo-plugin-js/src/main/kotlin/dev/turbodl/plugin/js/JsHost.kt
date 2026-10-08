package dev.turbodl.plugin.js

import dev.turbodl.core.TurboConfig
import dev.turbodl.core.TurboHttpClients
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `host.http` — outbound HTTP for JS plugins, coarse-grained by contract.
 *
 * ## Kotlin interface
 * [JsHostHttp.request] / [JsHostHttp.downloadToFile] — both suspend-free, blocking, and returning a
 * **complete** result. There is deliberately no streaming API, no body handle, no per-chunk
 * callback: the point of the boundary is that JS never sits on the data plane. A plugin that needs
 * bytes gets either the whole (bounded) body as text/base64, or a finished file on disk.
 *
 * ## JS API
 * `host.http.request({url, method?, headers?, body?, bodyBase64?, as?, timeoutMillis?, originUrl?, followRedirects?, maxBytes?})`
 * `host.http.downloadToFile({url, headers?, fileName?, timeoutMillis?, maxBytes?, originUrl?, followRedirects?})`
 *
 * ## Input/output types
 * Input is one JSON object; see [JsHostHttp.parseRequest] for the accepted fields and their bounds.
 * Output is a plain object: `{ok, status, statusText, headers, url, redirects,
 * body / bodyBase64 / bytes}` for a request, `{ok, file, bytes, status, contentType,
 * url, redirects}` for a download. Headers are returned as a single-value map (first
 * value wins) because a multi-value map invites a plugin to assume order it cannot get.
 * `redirects` is the number of hops actually followed (0 for a direct hit), so a plugin can tell a
 * rewritten final URL from a chain.
 *
 * ## Redirects
 * Following is a *policy* step here, not a transport detail, so OkHttp's own redirect following is
 * disabled unconditionally and [JsHostHttp.executeWithRedirects] walks the chain instead. On every
 * hop: the `Location` is resolved and scheme-checked through
 * [JsRequestPolicy.redirectTarget], and [JsRequestPolicy.headersFor] re-runs against the declared
 * credential origin — so an `Authorization` headed for the login host is dropped when hop 2 lands on
 * a different origin. A chain longer than [JsHostHttp.MAX_REDIRECT_HOPS] is a `limit` error.
 * When following is off (the default, and always the case for a call the loader did not enable), the
 * 3xx is returned as data with its `Location` header intact, so the plugin decides.
 *
 * ## Error model
 * Thrown as [JsAbi.AbiException] with codes `validation` (bad argument), `permission` (scheme or
 * capability denied), `limit` (byte ceiling exceeded), `network` (transport failure),
 * `timeout` (budget exhausted). Non-2xx is **not** an error: `ok=false, status=...` is returned so
 * a plugin can branch on a 404/403 the way it would in a normal HTTP library.
 *
 * ## Permission boundary
 * Requires [JsCapability.HTTP]. Transport comes from [TurboHttpClients.create] so proxy/DNS/TLS/UA
 * policy is TurboDL's, not the plugin's; the plugin can only *narrow* timeouts, byte ceilings and
 * `followRedirects`, never widen them.
 */
internal class JsHostHttp(
    private val pluginId: String,
    private val config: JsPluginConfig,
    private val engineConfig: TurboConfig,
    private val sandbox: JsSandbox,
) {

    /**
     * Lazily built, per-plugin client. Rebuilt when the engine's transport-relevant config changes
     * so a host that updates its proxy/DNS settings is not forced to reload the plugin — same
     * posture `HttpBackendPlugin` takes.
     *
     * **Redirects are never followed by OkHttp here.** Following is done hop-by-hop by
     * [executeWithRedirects], because a redirect is a policy event, not a transport detail: every
     * hop has to be scheme-checked and has to re-run [JsRequestPolicy.headersFor] against the
     * declared origin, or an `Authorization` set for the login host would ride a redirect to a CDN.
     * OkHttp's own following cannot be audited per hop, so it stays off unconditionally.
     */
    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var clientSignature: String = ""

    private fun httpClient(): OkHttpClient {
        val signature = transportSignature(engineConfig)
        client?.let { existing ->
            if (clientSignature == signature) return existing
        }
        val built = TurboHttpClients.create(engineConfig).newBuilder()
            // Per-call bounds: the plugin asks for less, never more, than the loader granted.
            .callTimeout(config.httpTimeoutMillis, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        client = built
        clientSignature = signature
        return built
    }

    /** What actually invalidates a client: anything HttpClientFactory reads to build one. */
    private fun transportSignature(c: TurboConfig): String =
        listOf(
            c.proxy, c.dns, c.effectiveHttpVersionPolicy, c.connectTimeoutMs, c.readTimeoutMs,
            c.userAgent, c.trustAllCerts, c.maxConnectionsPerTask,
        ).joinToString("|")

    data class HttpRequest(
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val body: ByteArray?,
        val timeoutMillis: Long,
        val maxBytes: Long,
        val responseAs: String,
        val originUrl: String?,
        /** Whether this one call may follow redirects: the loader flag narrowed by the script. */
        val followRedirects: Boolean,
    )

    /**
     * One HTTP exchange, redirects resolved hop-by-hop.
     *
     * [consume] receives the final response and must not throw out of the byte ceiling checks — the
     * response is closed here either way. Returned hops count is the number of redirects actually
     * followed, so a plugin can tell a direct hit from a chain.
     */
    private fun <T> executeWithRedirects(
        req: HttpRequest,
        initial: okhttp3.HttpUrl,
        credentialOrigin: okhttp3.HttpUrl,
        consume: (response: okhttp3.Response, hops: Int) -> T,
    ): T {
        val deadline = System.nanoTime() + req.timeoutMillis * 1_000_000L
        var url = initial
        var method = req.method
        var body = req.body
        var hops = 0
        while (true) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
            val callClient = httpClient().newBuilder()
                .callTimeout(remaining, TimeUnit.MILLISECONDS)
                .readTimeout(remaining, TimeUnit.MILLISECONDS)
                .build()
            val builder = JsRequestPolicy.newRequestBuilder(
                targetUrl = url,
                credentialOrigin = credentialOrigin,
                method = method,
                headers = req.headers,
                body = body?.toRequestBody(req.headers["Content-Type"]?.toMediaTypeOrNull()),
            )
            val response = try {
                callClient.newCall(builder.build()).execute()
            } catch (t: java.io.IOException) {
                throw JsAbi.AbiException(JsAbi.Code.NETWORK, "http request to '$url' failed: ${t.message}", t)
            }
            val next = nextHop(req, response, url, method, body, hops)
            if (next == null) return consume(response, hops)
            hops = next.hops
            url = next.url
            method = next.method
            body = next.body
        }
    }

    /** The hop to take after [response], or null when [response] is the final answer. */
    private fun nextHop(
        req: HttpRequest,
        response: okhttp3.Response,
        url: okhttp3.HttpUrl,
        method: String,
        body: ByteArray?,
        hops: Int,
    ): Hop? {
        val code = response.code
        val redirectish = code == 301 || code == 302 || code == 303 || code == 307 || code == 308
        if (!redirectish) return null
        val location = response.headers["Location"]
        if (location.isNullOrBlank()) return null
        if (!req.followRedirects) {
            // Not following is the documented default: the 3xx comes back as data, with `Location`
            // intact, so the plugin decides what the redirect means instead of the transport deciding.
            return null
        }
        if (hops + 1 > MAX_REDIRECT_HOPS) {
            runCatching { response.close() }
            throw JsAbi.AbiException(
                JsAbi.Code.LIMIT,
                "redirect chain exceeded $MAX_REDIRECT_HOPS hops starting at '${req.url}'",
            )
        }
        // Re-resolved and re-scheme-checked through the same policy entry point as a script-supplied
        // url: a `Location:` pointing at file:// or a non-http scheme is a refusal, not something to
        // hand to the transport.
        val nextUrl = JsRequestPolicy.redirectTarget(location, url) ?: run {
            runCatching { response.close() }
            throw JsPermissionException("redirect target '$location' is not a valid http(s) URL")
        }
        // Fixed 301/302/303 vs 307/308 semantics, applied uniformly (not only "when there is a body"):
        //  - 307/308 are *method-preserving*: the same method and body are re-sent, so a plugin's PUT
        //    lands intact at the new location.
        //  - 301/302/303 are *method-rewriting*: the follow-up is always a bodyless GET, whatever the
        //    original method was. This is RFC 7231's rule for 303 ("retrieve the 3xx's target with GET")
        //    and the browser rule that 301/302 apply to POST; extending it to every method is the safe
        //    choice here — blindly re-issuing a PUT/DELETE/PATCH against a Location that may point at a
        //    different origin (whose credential headers we just stripped) is exactly the mutation we must
        //    not perform. A `303 + PUT` therefore becomes GET, never PUT.
        runCatching { response.close() }
        return if (code == 307 || code == 308) {
            Hop(nextUrl, method, body, hops + 1)
        } else {
            Hop(nextUrl, "GET", null, hops + 1)
        }
    }

    private class Hop(val url: okhttp3.HttpUrl, val method: String, val body: ByteArray?, val hops: Int)

    fun request(payloadJson: String?): Map<String, Any?> {
        val req = parseRequest(payloadJson, forDownload = false)
        val target = JsRequestPolicy.parseUrl(req.url)
        val credentialOrigin = JsRequestPolicy.credentialOrigin(target, req.originUrl)
        return executeWithRedirects(req, target, credentialOrigin) { response, hops ->
            response.use {
                val headers = it.headers.toMultimap().mapValues { e -> e.value.firstOrNull() ?: "" }
                if (headers.size > JsIoLimits.MAX_HEADER_COUNT) {
                    throw JsAbi.AbiException(JsAbi.Code.LIMIT, "response carried more than ${JsIoLimits.MAX_HEADER_COUNT} headers")
                }
                val declared = it.headers["Content-Length"]?.toLongOrNull()
                if (declared != null && declared > req.maxBytes) {
                    throw JsAbi.AbiException(
                        JsAbi.Code.LIMIT,
                        "Content-Length $declared exceeds the ${req.maxBytes}B host.http limit",
                    )
                }
                val bytesRead: Long
                val bodyValue: Map<String, Any?> = when (req.responseAs) {
                    "none" -> {
                        // The ceiling still applies when the body is discarded: a plugin using `as:'none'`
                        // as a cheap size probe must not get a free pass on an unbounded read.
                        val stream = it.body?.byteStream()
                        val bytes = if (stream == null) ByteArray(0) else JsIoLimits.readBounded(stream, req.maxBytes)
                        bytesRead = bytes.size.toLong()
                        // `bytes` is present even when the body was skipped: the documented result shape
                        // is stable across `as`, so a plugin never reads `undefined` for a field.
                        mapOf("body" to null, "bytes" to bytesRead)
                    }
                    "base64" -> {
                        val stream = it.body?.byteStream()
                        val bytes = if (stream == null) ByteArray(0) else JsIoLimits.readBounded(stream, req.maxBytes)
                        bytesRead = bytes.size.toLong()
                        mapOf("bodyBase64" to toBase64(bytes), "bytes" to bytesRead)
                    }
                    else -> {
                        val stream = it.body?.byteStream()
                        val bytes = if (stream == null) ByteArray(0) else JsIoLimits.readBounded(stream, req.maxBytes)
                        // Reported as the *encoded* size: `bytes` has to stay comparable against
                        // Content-Length, which UTF-8 text does not.
                        bytesRead = bytes.size.toLong()
                        mapOf("body" to JsIoLimits.asBoundedText(bytes), "bytes" to bytesRead)
                    }
                }
                buildMap {
                    put("ok", it.isSuccessful)
                    put("status", it.code.toLong())
                    put("statusText", it.message)
                    put("headers", headers)
                    put("url", it.request.url.toString())
                    put("redirects", hops.toLong())
                    putAll(bodyValue)
                }
            }
        }
    }

    /**
     * Download into the plugin sandbox and return the finished file path.
     *
     * Streamed straight to disk (never buffered in memory) but still byte-capped, and written to a
     * `.part` file first so a failed download cannot leave something that looks complete.
     */
    fun downloadToFile(payloadJson: String?): Map<String, Any?> {
        val req = parseRequest(payloadJson, forDownload = true)
        val target = JsRequestPolicy.parseUrl(req.url)
        val credentialOrigin = JsRequestPolicy.credentialOrigin(target, req.originUrl)
        val downloadName = (payloadObject(payloadJson)["fileName"] as? String)
            ?: defaultFileName(target)
        val dest = sandbox.resolve(sandbox.downloads, downloadName)

        return executeWithRedirects(req, target, credentialOrigin) { response, hops ->
            response.use {
                if (!it.isSuccessful) {
                    throw JsAbi.AbiException(
                        JsAbi.Code.NETWORK,
                        "download failed with HTTP ${it.code} for '${req.url}'",
                    )
                }
                val declared = it.headers["Content-Length"]?.toLongOrNull()
                if (declared != null && declared > req.maxBytes) {
                    throw JsAbi.AbiException(JsAbi.Code.LIMIT, "Content-Length $declared exceeds the ${req.maxBytes}B download limit")
                }
                val stream = it.body?.byteStream()
                    ?: throw JsAbi.AbiException(JsAbi.Code.NETWORK, "response had no body")
                val part = File(dest.parentFile, dest.name + ".part")
                var written = 0L
                FileOutputStream(part).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        written += read
                        if (written > req.maxBytes) {
                            runCatching { out.close() }
                            runCatching { part.delete() }
                            throw JsAbi.AbiException(JsAbi.Code.LIMIT, "download exceeded the ${req.maxBytes}B limit")
                        }
                        out.write(buffer, 0, read)
                    }
                }
                if (!part.renameTo(dest)) {
                    runCatching { part.delete() }
                    throw JsAbi.AbiException(JsAbi.Code.INTERNAL, "could not finalize the downloaded file")
                }
                mapOf(
                    "ok" to true,
                    // A path is handed back as *text*, never as a File object: JS gets a string it can
                    // pass to DownloadRequest.destination when it registers a parser result.
                    "file" to dest.absolutePath,
                    "bytes" to written,
                    "status" to it.code.toLong(),
                    "contentType" to it.headers["Content-Type"],
                    "url" to it.request.url.toString(),
                    "redirects" to hops.toLong(),
                )
            }
        }
    }

    fun close() {
        runCatching {
            client?.let { c ->
                c.dispatcher.executorService.shutdownNow()
                c.connectionPool.evictAll()
                runCatching { c.cache?.close() }
            }
        }
        client = null
    }

    // ------------------------------------------------------------------ argument parsing

    private fun parseRequest(payloadJson: String?, forDownload: Boolean): HttpRequest {
        val obj = payloadObject(payloadJson)
        val url = obj["url"] as? String
            ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "host.http requires a 'url' string")
        val method = (obj["method"] as? String ?: "GET").uppercase()
        val headers = JsValueCodec.asStringMap(obj["headers"], "host.http headers")
        val body = when {
            obj["body"] is String -> (obj["body"] as String).toByteArray(Charsets.UTF_8)
            obj["bodyBase64"] is String -> fromBase64(obj["bodyBase64"] as String)
            obj["body"] != null -> JsValueCodec.encodeForJs(obj["body"]).toByteArray(Charsets.UTF_8)
            else -> null
        }
        if (body != null && body.size > JsIoLimits.MAX_REQUEST_BODY_BYTES) {
            throw JsAbi.AbiException(JsAbi.Code.LIMIT, "request body exceeds ${JsIoLimits.MAX_REQUEST_BODY_BYTES}B")
        }
        val requestedTimeout = JsValueCodec.asLongOrNull(obj["timeoutMillis"]) ?: config.httpTimeoutMillis
        if (requestedTimeout <= 0) throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "timeoutMillis must be > 0")
        val ceiling = if (forDownload) config.maxDownloadBytes else config.maxResponseBytes
        val requestedMax = JsValueCodec.asLongOrNull(obj["maxBytes"])
        // A plugin may lower a ceiling; raising it is not possible because `min` has no other arm.
        val maxBytes = if (requestedMax == null || requestedMax <= 0) ceiling else minOf(requestedMax, ceiling)
        val responseAs = (obj["as"] as? String ?: "text").lowercase().also {
            if (it !in setOf("text", "base64", "none")) {
                throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "host.http 'as' must be text / base64 / none")
            }
        }
        // Per-call redirects: narrowing only. `true` cannot enable what the loader disabled, because
        // the ceiling is the loader's decision; `false` can always disable it for one call.
        val perCallFollow = JsValueCodec.asBooleanOrNull(obj["followRedirects"])
        return HttpRequest(
            url = url,
            method = method,
            headers = headers,
            body = body,
            timeoutMillis = minOf(requestedTimeout, config.httpTimeoutMillis),
            maxBytes = maxBytes,
            responseAs = responseAs,
            originUrl = obj["originUrl"] as? String,
            followRedirects = config.followRedirects && perCallFollow != false,
        )
    }

    private fun payloadObject(payloadJson: String?): Map<*, *> {
        if (payloadJson.isNullOrEmpty()) {
            throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "host.http requires an options object")
        }
        val decoded = try {
            JsValueCodec.decodeFromJs(payloadJson, "host.http options")
        } catch (t: JsValueCodec.JsCodecException) {
            throw JsAbi.AbiException(JsAbi.Code.VALIDATION, t.message ?: "invalid options", t)
        }
        return decoded as? Map<*, *>
            ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "host.http options must be an object")
    }

    private fun defaultFileName(url: okhttp3.HttpUrl): String {
        val last = url.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() }
        val cleaned = (last ?: "download").map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")
        return if (cleaned.isBlank() || cleaned == ".") "download" else cleaned.take(120)
    }

    companion object {
        /** Redirect hops one `host.http` call may follow before it is treated as a loop. */
        const val MAX_REDIRECT_HOPS = 5

        private val HEX = "0123456789abcdef".toCharArray()

        fun toBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        fun fromBase64(text: String): ByteArray = try {
            Base64.getDecoder().decode(text)
        } catch (t: IllegalArgumentException) {
            throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "value is not valid base64: ${t.message}", t)
        }

        fun toHex(bytes: ByteArray): String {
            val out = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val v = bytes[i].toInt() and 0xFF
                out[i * 2] = HEX[v ushr 4]
                out[i * 2 + 1] = HEX[v and 0x0F]
            }
            return String(out)
        }

        fun fromHex(text: String): ByteArray {
            if (text.length % 2 != 0) throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "hex string must have even length")
            return try {
                ByteArray(text.length / 2) { i ->
                    text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            } catch (t: NumberFormatException) {
                throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "value is not valid hex", t)
            }
        }
    }
}

/**
 * `host.crypto` — digests, HMAC and encodings.
 *
 * ## Scope (and why it is not "a crypto library")
 * The realistic needs of a downloader plugin are: sign a request (HMAC), verify a checksum
 * (digest), and move bytes through text safely (base64/hex). All of that is JDK `MessageDigest` /
 * `Mac`, which is audited, constant-time where it matters, and adds no dependency. Cipher suites,
 * key agreement and asymmetric crypto are **out of scope**: they need key lifecycle management the
 * JS ABI does not have, and a plugin that truly needs them should ship a backend that does it in
 * Kotlin. `randomBytes` is included because share-link plugins routinely need a nonce, and it is
 * the one thing that must NOT be implemented in JS (`Math.random` is not a CSPRNG).
 *
 * ## JS API
 * `host.crypto.digest(algorithm, data)` → lowercase hex
 * `host.crypto.hmac(algorithm, key, data)` → lowercase hex
 * `host.crypto.randomBytes(length)` → base64
 * `host.crypto.base64Encode/base64Decode/hexEncode/hexDecode`
 *
 * ## Input/output types
 * `data`/`key` are `{kind:'utf8'|'base64', text:string}` as produced by the ABI, so a plugin can
 * hash both text and binary without a byte array ever crossing the boundary. Algorithm names are
 * whitelisted, not passed through to the provider.
 *
 * ## Error model
 * `validation` for an unsupported algorithm or a malformed encoding (never a
 * `NoSuchAlgorithmException` leaking a provider name), `permission` when [JsCapability.CRYPTO] is
 * not granted, `limit` for an oversized input.
 */
internal class JsHostCrypto(private val config: JsPluginConfig) {

    private val digests = setOf("MD5", "SHA-1", "SHA-256", "SHA-512")
    private val macs = mapOf(
        "HmacSHA1" to "HmacSHA1",
        "HmacSHA256" to "HmacSHA256",
        "HmacSHA512" to "HmacSHA512",
    )

    /** Max bytes hashed in one call — hashing a 500MB blob through a JSON envelope is a mistake. */
    private val maxInputBytes: Long = minOf(config.maxResponseBytes, 32L * 1024 * 1024)

    fun digest(payloadJson: String?): Map<String, Any?> {
        val obj = objectOf(payloadJson)
        val algorithm = normalize(obj["algorithm"] as? String, digests, "digest")
        val bytes = bytesOf(obj["data"], "data")
        val md = runCatching { MessageDigest.getInstance(algorithm) }
            .getOrElse { throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "unsupported digest '$algorithm'") }
        return mapOf("algorithm" to algorithm, "hex" to JsHostHttp.toHex(md.digest(bytes)))
    }

    fun hmac(payloadJson: String?): Map<String, Any?> {
        val obj = objectOf(payloadJson)
        val requested = (obj["algorithm"] as? String ?: "").ifBlank { "HmacSHA256" }
        val algorithm = macs[normalize(requested, macs.keys, "hmac")]
            ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "unsupported hmac algorithm '$requested'")
        val key = bytesOf(obj["key"], "key")
        if (key.isEmpty()) throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "hmac key must not be empty")
        val data = bytesOf(obj["data"], "data")
        val mac = runCatching { Mac.getInstance(algorithm).apply { init(SecretKeySpec(key, algorithm)) } }
            .getOrElse { throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "hmac init failed: ${it.message}") }
        // The key itself is never logged or returned — §9 "handle secrets by reference".
        return mapOf("algorithm" to algorithm, "hex" to JsHostHttp.toHex(mac.doFinal(data)))
    }

    fun randomBytes(payloadJson: String?): Map<String, Any?> {
        val obj = objectOf(payloadJson)
        val length = JsValueCodec.asLongOrNull(obj["length"])
            ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "randomBytes needs a numeric 'length'")
        if (length !in 1..512) {
            throw JsAbi.AbiException(JsAbi.Code.LIMIT, "randomBytes length must be 1..512")
        }
        val out = ByteArray(length.toInt())
        java.security.SecureRandom().nextBytes(out)
        return mapOf("base64" to JsHostHttp.toBase64(out), "hex" to JsHostHttp.toHex(out))
    }

    fun base64(payloadJson: String?): Map<String, Any?> {
        val obj = objectOf(payloadJson)
        return when (obj["mode"] as? String) {
            "encode" -> mapOf("text" to JsHostHttp.toBase64(bytesOf(obj["data"], "data")))
            "decode" -> mapOf("base64" to JsHostHttp.toBase64(JsHostHttp.fromBase64(textOf(obj["text"], "text"))))
            else -> throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "base64 mode must be encode|decode")
        }
    }

    fun hex(payloadJson: String?): Map<String, Any?> {
        val obj = objectOf(payloadJson)
        return when (obj["mode"] as? String) {
            "encode" -> mapOf("text" to JsHostHttp.toHex(bytesOf(obj["data"], "data")))
            "decode" -> mapOf("hex" to JsHostHttp.toHex(JsHostHttp.fromHex(textOf(obj["text"], "text"))))
            else -> throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "hex mode must be encode|decode")
        }
    }

    private fun normalize(requested: String?, allowed: Set<String>, label: String): String {
        val raw = requested?.trim().orEmpty()
        if (raw.isEmpty()) throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "$label requires an algorithm")
        // Accept the common spellings; anything else is refused rather than handed to the JCA.
        val candidate = raw.replace("-", "").lowercase()
        return allowed.firstOrNull { it.replace("-", "").lowercase() == candidate }
            ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "unsupported $label algorithm '$raw' (allowed: ${allowed.joinToString("/")})")
    }

    /**
     * Decode an ABI byte descriptor. `{kind:'utf8'}` hashes the UTF-8 bytes; `{kind:'base64'}`
     * decodes first. A bare string is tolerated as utf8 so a simple `digest("sha256","abc")` works.
     */
    private fun bytesOf(value: Any?, field: String): ByteArray {
        val bytes = when (value) {
            is String -> value.toByteArray(Charsets.UTF_8)
            is Map<*, *> -> when (value["kind"] as? String) {
                "utf8" -> textOf(value["text"], field).toByteArray(Charsets.UTF_8)
                "base64" -> JsHostHttp.fromBase64(textOf(value["text"], field))
                else -> throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "$field.kind must be utf8|base64")
            }
            null -> throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "$field is required")
            else -> throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "$field must be a string or {kind,text}")
        }
        if (bytes.size.toLong() > maxInputBytes) {
            throw JsAbi.AbiException(JsAbi.Code.LIMIT, "$field exceeds the ${maxInputBytes}B crypto input limit")
        }
        return bytes
    }

    private fun textOf(value: Any?, field: String): String =
        value as? String ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "$field.text must be a string")

    private fun objectOf(payloadJson: String?): Map<*, *> {
        if (payloadJson.isNullOrEmpty()) throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "crypto call requires an object")
        val decoded = try {
            JsValueCodec.decodeFromJs(payloadJson, "crypto payload")
        } catch (t: JsValueCodec.JsCodecException) {
            throw JsAbi.AbiException(JsAbi.Code.VALIDATION, t.message ?: "invalid payload", t)
        }
        return decoded as? Map<*, *>
            ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "crypto payload must be an object")
    }
}

/**
 * `host.storage` / `host.env` / `host.time` — the three small capabilities.
 *
 * ## Storage
 * A key-value store inside the plugin's own sandbox directory, with a byte quota. Keys are
 * single-segment names (same choke point as file names) so a plugin cannot write outside its quota
 * bucket, and values must be JSON-domain data — storage is for state, not for spilling bytes.
 * Deleting the sandbox on unload is what makes "uninstall leaves nothing behind" true on disk too.
 *
 * ## Env
 * Read-only and allowlist-only. The default allowlist is empty, so the ambient environment is
 * invisible to a plugin unless the loader named a variable explicitly.
 *
 * ## Time
 * The host clock, not `Date.now()`, so a plugin's timestamps are consistent with engine logs and
 * can be compared across plugins. `sleep` exists because a plugin may need a fixed backoff; it is
 * bounded by the invocation budget so it cannot stall a drain indefinitely.
 */
internal class JsHostEnvironment(
    private val config: JsPluginConfig,
    private val sandbox: JsSandbox,
) {

    fun now(): Map<String, Any?> = mapOf("millis" to System.currentTimeMillis())

    fun iso(payloadJson: String?): Map<String, Any?> {
        val millis = if (payloadJson.isNullOrEmpty() || payloadJson == "null") {
            System.currentTimeMillis()
        } else {
            JsValueCodec.asLongOrNull(JsValueCodec.decodeFromJs(payloadJson, "time.iso"))
                ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "time.iso needs a number")
        }
        return mapOf("iso" to java.time.Instant.ofEpochMilli(millis).toString(), "millis" to millis)
    }

    /** Bounded host-side sleep: capped by both the config ceiling and the engine timeout. */
    fun sleep(payloadJson: String?): Map<String, Any?> {
        val millis = JsValueCodec.asLongOrNull(JsValueCodec.decodeFromJs(payloadJson, "time.sleep"))
            ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "time.sleep needs a number")
        if (millis < 0) throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "time.sleep must be >= 0")
        val capped = minOf(millis, MAX_SLEEP_MILLIS)
        Thread.sleep(capped)
        return mapOf("sleptMillis" to capped)
    }

    fun storageGet(payloadJson: String?): Any? {
        val key = storageKey(payloadJson)
        val file = sandbox.resolve(sandbox.storage, key)
        if (!file.isFile) return null
        return JsValueCodec.decodeFromJs(file.readText(Charsets.UTF_8), "storage['$key']")
    }

    fun storageSet(payloadJson: String?): Map<String, Any?> {
        val obj = objectOf(payloadJson)
        val key = storageKey(obj["key"])
        val value = JsValueCodec.requireValid(obj["value"], "storage value")
        val encoded = JsValueCodec.encodeForJs(value)
        if (encoded.length > MAX_STORAGE_VALUE_CHARS) {
            throw JsAbi.AbiException(JsAbi.Code.LIMIT, "storage value exceeds $MAX_STORAGE_VALUE_CHARS characters")
        }
        val file = sandbox.resolve(sandbox.storage, key)
        val previous = if (file.isFile) file.length() else 0L
        val projected = sandbox.storageBytes() - previous + encoded.length
        if (projected > config.maxStorageBytes) {
            throw JsAbi.AbiException(
                JsAbi.Code.LIMIT,
                "storage quota exceeded (${config.maxStorageBytes}B); remove unused keys first",
            )
        }
        file.writeText(encoded, Charsets.UTF_8)
        return mapOf("ok" to true, "bytes" to encoded.length.toLong())
    }

    fun storageRemove(payloadJson: String?): Map<String, Any?> {
        val key = storageKey(payloadJson)
        val file = sandbox.resolve(sandbox.storage, key)
        val removed = file.isFile && file.delete()
        return mapOf("removed" to removed)
    }

    fun storageKeys(): List<String> =
        sandbox.storage.listFiles()?.filter { it.isFile }?.map { decodeKey(it.name) }?.sorted() ?: emptyList()

    fun envGet(payloadJson: String?): Map<String, Any?> {
        val obj = objectOf(payloadJson)
        val name = obj["name"] as? String
            ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "env.get needs a 'name'")
        if (name !in config.allowedEnvVars) {
            throw JsAbi.AbiException(
                JsAbi.Code.PERMISSION,
                "environment variable '$name' is not in this loader's allowlist",
            )
        }
        return mapOf("value" to System.getenv(name))
    }

    fun envHas(payloadJson: String?): Map<String, Any?> {
        val obj = objectOf(payloadJson)
        val name = obj["name"] as? String
            ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "env.has needs a 'name'")
        val allowed = name in config.allowedEnvVars
        return mapOf("allowed" to allowed, "present" to (allowed && System.getenv(name) != null))
    }

    private fun storageKey(payloadJson: String?): String {
        val obj = objectOf(payloadJson)
        return storageKey(obj["key"])
    }

    private fun storageKey(value: Any?): String {
        val raw = value as? String
            ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "storage requires a string 'key'")
        // Keys are *reversibly encoded*, never lossily scrubbed: `user:token` and `user/token` are
        // different logical keys and must stay different files. A character-substitution transform
        // (`:`→`_`, `/`→`_`) would collapse them onto one file, silently overwriting a plugin's
        // own state (e.g. two parser profiles sharing a `cursor`). Percent-encoding every byte
        // outside the safe set is injective, so collisions are impossible by construction.
        if (raw.isEmpty()) throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "storage key must not be empty")
        if (raw.startsWith(".")) {
            throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "storage key '$raw' is not usable as a file name")
        }
        if (raw.length > MAX_KEY_CHARS) {
            throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "storage key exceeds $MAX_KEY_CHARS chars")
        }
        val safe = encodeKey(raw)
        // The encoded name must still fit the filesystem's limit even though the logical key did.
        if (safe.length > MAX_KEY_CHARS * 3) {
            throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "storage key '$raw' encodes to a name that is too long")
        }
        return safe
    }

    /**
     * Percent-encode [raw] into a single filename-safe segment.
     *
     * Safe characters (`A-Za-z0-9._-`) pass through unchanged, so the common `cursor` / `user.token`
     * case stays readable in a directory listing; everything else (including `%` itself, which is
     * always escaped) becomes `%XX`. This is injective, so distinct logical keys never collide.
     */
    private fun encodeKey(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (b in raw.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            val safe = c.isLetterOrDigit() && c.code < 128 || c == '.' || c == '_' || c == '-'
            if (safe) sb.append(c)
            else sb.append('%').append("%02X".format(b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    /** Inverse of [encodeKey] (best-effort: a name not produced by us is returned as-is). */
    private fun decodeKey(name: String): String {
        if ('%' !in name) return name
        val bytes = java.io.ByteArrayOutputStream(name.length)
        var i = 0
        while (i < name.length) {
            val c = name[i]
            if (c == '%' && i + 2 < name.length) {
                val hex = name.substring(i + 1, i + 3)
                val v = hex.toIntOrNull(16)
                if (v != null) {
                    bytes.write(v)
                    i += 3
                    continue
                }
            }
            bytes.write(c.code)
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun objectOf(payloadJson: String?): Map<*, *> {
        if (payloadJson.isNullOrEmpty()) throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "this host call requires an object")
        val decoded = try {
            JsValueCodec.decodeFromJs(payloadJson, "host payload")
        } catch (t: JsValueCodec.JsCodecException) {
            throw JsAbi.AbiException(JsAbi.Code.VALIDATION, t.message ?: "invalid payload", t)
        }
        return decoded as? Map<*, *>
            ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "this host call requires an object payload")
    }

    private companion object {
        const val MAX_SLEEP_MILLIS = 5_000L
        const val MAX_KEY_CHARS = 64
        const val MAX_STORAGE_VALUE_CHARS = 256 * 1024
    }
}

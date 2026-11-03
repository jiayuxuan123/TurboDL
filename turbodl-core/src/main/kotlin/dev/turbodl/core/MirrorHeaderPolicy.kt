package dev.turbodl.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Headers

/**
 * Mirrors are separate origins and must not inherit a primary site's credentials
 * by default. Non-credential headers (for example User-Agent or a custom routing
 * header) remain available so public mirrors keep working.
 */
internal object MirrorHeaderPolicy {
    private val originCredentialHeaders = setOf(
        "authorization",
        "cookie",
        "cookie2",
        "x-api-key",
        "api-key",
        "x-auth-token",
        "x-access-token",
        "x-csrf-token",
        "x-xsrf-token",
    )

    fun headersFor(
        primaryUrl: String,
        mirrorUrl: String,
        headers: Map<String, String>,
    ): Map<String, String> {
        if (sameOrigin(primaryUrl, mirrorUrl)) return headers
        return headers.filterKeys { key ->
            key.trim().lowercase() !in originCredentialHeaders
        }
    }

    /**
     * Remove origin credentials from a redirect request while leaving
     * Proxy-Authorization intact for the proxy layer.
     */
    fun stripOriginCredentials(headers: Headers): Headers {
        val builder = Headers.Builder()
        for (name in headers.names()) {
            if (name.trim().lowercase() in originCredentialHeaders) continue
            for (value in headers.values(name)) {
                builder.add(name, value)
            }
        }
        return builder.build()
    }

    fun sameOrigin(left: String, right: String): Boolean {
        val a = left.toHttpUrlOrNull() ?: return false
        val b = right.toHttpUrlOrNull() ?: return false
        return a.scheme.equals(b.scheme, ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) &&
            a.port == b.port
    }
}

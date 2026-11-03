package dev.turbodl.core

/**
 * Tags an OkHttp request with the source URL the caller initially authorized.
 * Redirect handling uses it to decide whether a request crossed an origin.
 */
internal data class RequestOrigin(val url: String)

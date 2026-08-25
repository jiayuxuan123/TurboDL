package dev.turbodl.core

/**
 * Public factory for built-in backends.
 *
 * Lets an optional plugin (e.g. a bootstrap "HTTP backend plugin") register the same
 * multi-threaded HTTP engine that core uses internally as a routed [DownloadBackend], without
 * exposing core internals ([SegmentDownloader], [HttpClientFactory], ...).
 *
 * When the plugin runtime is NOT used, [TurboClient] still uses its own built-in backend
 * directly; this factory simply makes that capability available to plugin-based wiring too.
 */
object TurboBackends {

    /**
     * Create a standalone built-in HTTP/HTTPS backend.
     *
     * @param clientConfig config used to build the OkHttp client (proxy/DNS/TLS/timeouts).
     *   Note: per-download tuning (connections, limits, dynamic segmentation) is still read from
     *   [BackendContext.config] at download time; this parameter only seeds the HTTP client.
     */
    fun builtinHttp(clientConfig: TurboConfig = TurboConfig()): DownloadBackend {
        val client = HttpClientFactory.build(clientConfig)
        val downloader = SegmentDownloader { client }
        return BuiltinHttpBackend(downloader)
    }
}

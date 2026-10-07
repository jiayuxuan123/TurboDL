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
     *
     * 传输层设置**可热更新**：返回的后端会在每次下载开始时比对 `BackendContext.config` 的
     * 传输层签名（代理/DNS/TLS/超时/连接池），有变化才重建 OkHttpClient。
     * 【为什么必须做】本后端经插件路由接管后，`TurboClient.updateConfig()` 重建的是 core
     * 自己那两个**不被使用**的 client；若不在这里跟随，改了设置就得重启进程才生效。
     */
    fun builtinHttp(clientConfig: TurboConfig = TurboConfig()): DownloadBackend {
        val holder = TransportClientHolder(clientConfig)
        val downloader = SegmentDownloader(
            { holder.client },
            { holder.client },
            { holder.config.ioBufferSize },
        )
        return BuiltinHttpBackend(downloader) { holder.onConfigSeen(it) }
    }
}

/**
 * 传输层客户端持有器：按 [TurboConfig] 的**传输层签名**惰性重建 OkHttpClient。
 *
 * 签名只包含传输层字段（代理/DNS/TLS/超时/连接池/UA），**不含**业务调参项
 * （连接数、限速、分片粒度、慢启动……）——后者每次下载都从 `BackendContext.config` 现读，
 * 改了它们不该触发重建（重建会丢连接池、让下一任务重新握手）。
 */
internal class TransportClientHolder(initial: TurboConfig) {

    @Volatile
    var config: TurboConfig = initial
        private set

    @Volatile
    private var sig: String = transportSignature(initial)

    @Volatile
    private var clientRef: okhttp3.OkHttpClient =
        HttpClientFactory.build(initial, HttpClientFactory.ProtocolPreference.H1_ONLY)

    val client: okhttp3.OkHttpClient get() = clientRef

    /** 每次下载开始调用：签名变了才重建（未变则零开销）。 */
    @Synchronized
    fun onConfigSeen(latest: TurboConfig) {
        val newSig = transportSignature(latest)
        config = latest
        if (newSig == sig) return
        // 旧连接的执行器/连接池需要显式释放，否则每次改设置都泄漏一套线程与连接。
        // 【逐句兜底】`evictAll()` 会真的 close socket（TLS 要写 close_notify），
        // 在主线程上是网络 I/O 且异常会穿透（OkHttp 的 closeQuietly 只吞 IOException）——
        // 一句失败不该让另一句被跳过。
        runCatching { clientRef.dispatcher.executorService.shutdown() }
        runCatching { clientRef.connectionPool.evictAll() }
        clientRef = HttpClientFactory.build(latest, HttpClientFactory.ProtocolPreference.H1_ONLY)
        sig = newSig
    }

    /**
     * 传输层签名。
     *
     * 刻意**排除**以下字段：
     *  - `ioBufferSize` —— 下载写入路径的缓冲区，由 downloader 通过
     *    `{ holder.config.ioBufferSize }` 每次现读，不需要重建 client；
     *  - `maxConnectionsPerTask` —— 它只影响**连接池空闲上限**（`maxOf(maxIdle, 本值)`），
     *    而 `maxIdleConnections` 默认 256 已远大于它，为它重建整个 client（丢弃已建立的
     *    连接池、让下个任务重新握手）是净亏。分片并发数本就每次从 `BackendContext.config` 现读。
     *  - 其余业务调参（限速、分片粒度、慢启动、预热、重试…）同理，全在下载时现读。
     */
    private fun transportSignature(c: TurboConfig): String = listOf(
        c.proxy.toString(),
        c.dns.toString(),
        c.trustAllCerts.toString(),
        c.connectTimeoutMs.toString(),
        c.readTimeoutMs.toString(),
        c.maxIdleConnections.toString(),
        c.keepAliveSeconds.toString(),
        c.userAgent,
        c.effectiveHttpVersionPolicy.toString(),
    ).joinToString("|")
}

/**
 * Public factory for protocol plugins that need the same HTTP transport policy as core.
 *
 * Optional protocol modules (such as HLS) can use this factory to honour [TurboConfig]'s proxy,
 * DNS, TLS, timeout, and connection-pool settings without accessing core's internal downloader
 * classes. Callers own the returned client and must release its dispatcher/pool when finished.
 */
object TurboHttpClients {
    fun create(config: TurboConfig): okhttp3.OkHttpClient = HttpClientFactory.build(config)

    /**
     * 探测各候选 DoH 端点的解析延迟（毫秒）；失败或超时记为 -1。
     *
     * 供宿主把「自动 DNS 选了哪个、各端点各多快」可视化给用户看。
     * 探测是**并发**的，整体耗时约等于最快的那个端点（上限 [timeoutMs]）。
     *
     * @param endpoints 候选端点；默认取 [DnsMode.DEFAULT_DOH_ENDPOINTS]
     * @param hostname 用于探测的域名（默认 `www.baidu.com` —— 国内可达性最好，
     *   避免"探一个被墙的域名导致所有端点都显示失败"）
     * @return 与 [endpoints] 顺序一致的 (端点, 延迟毫秒) 列表；-1 表示不可用
     */
    fun probeDohLatency(
        endpoints: List<String> = DnsMode.DEFAULT_DOH_ENDPOINTS,
        hostname: String = "www.baidu.com",
        timeoutMs: Long = 4_000L,
    ): List<Pair<String, Long>> = HttpClientFactory.probeDohLatency(endpoints, hostname, timeoutMs)
}

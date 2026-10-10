package dev.turbodl.core

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy as JProxy
import java.net.ProxySelector
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 根据 [TurboConfig] 构建下载专用 OkHttp 客户端。
 *
 * 关注点：
 *  - 连接复用：大连接池 + keep-alive（HTTP/2 多路复用优先，回退 HTTP/1.1）；
 *  - 代理：Direct / System / Manual(HTTP,SOCKS,含鉴权) / PAC 脚本；
 *  - DNS：System / StaticHosts / DoH；
 *  - TLS：可选忽略证书校验（抓包调试）。
 */
internal object HttpClientFactory {

    /** 协议偏好：分片并发用 H1_ONLY；探测/单流回退可用 ALLOW_H2。 */
    enum class ProtocolPreference { H1_ONLY, ALLOW_H2 }

    fun build(config: TurboConfig): OkHttpClient =
        build(config, defaultPreferenceFor(config))

    /** 根据策略推导默认偏好：FORCE_HTTP2 → ALLOW_H2；其余（AUTO/FORCE_HTTP1）分片链路默认 H1_ONLY。 */
    private fun defaultPreferenceFor(config: TurboConfig): ProtocolPreference =
        when (config.effectiveHttpVersionPolicy) {
            HttpVersionPolicy.FORCE_HTTP2 -> ProtocolPreference.ALLOW_H2
            else -> ProtocolPreference.H1_ONLY
        }

    /**
     * **分片链路**实际允许的协议列表（按优先级）。
     *
     * 提成公开函数是为了让"策略 → 实际协议"变成可断言的确定性行为：
     * 此前只有 policy 枚举的映射被测试覆盖，协议列表是在 [build] 里内联算的，
     * 于是"策略改对了但协议没跟着变"这类缺陷测不出来（P14 期间整理）。
     *
     * 默认（AUTO）下只有 h1：多分片并发要靠每个请求各自建连、各自拥有独立拥塞窗口
     * （`SlicedConnectionCountTest` 实测 16 请求 = 16 条连接）。
     */
    internal fun segmentProtocolsFor(config: TurboConfig): List<Protocol> =
        if (allowH2(config, ProtocolPreference.H1_ONLY)) {
            listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
        } else {
            listOf(Protocol.HTTP_1_1)
        }

    /**
     * **单流/探测链路**实际允许的协议列表（按优先级）。
     *
     * 单流没有"多连接被抹平"的问题，因此 AUTO 下允许协商 h2 ——
     * 既兼容只支持 h2 的服务器，也不会因此损失并发收益。
     */
    internal fun streamProtocolsFor(config: TurboConfig): List<Protocol> =
        if (allowH2(config, ProtocolPreference.ALLOW_H2)) {
            listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
        } else {
            listOf(Protocol.HTTP_1_1)
        }

    /**
     * 实际协议：由策略 + 链路偏好共同决定。
     *  - FORCE_HTTP1：永远 h1；
     *  - FORCE_HTTP2：允许 h2；
     *  - AUTO：分片链路（H1_ONLY）走 h1，探测/单流链路（ALLOW_H2）允许 h2。
     */
    private fun allowH2(config: TurboConfig, preference: ProtocolPreference): Boolean =
        when (config.effectiveHttpVersionPolicy) {
            HttpVersionPolicy.FORCE_HTTP1 -> false
            HttpVersionPolicy.FORCE_HTTP2 -> true
            HttpVersionPolicy.AUTO -> preference == ProtocolPreference.ALLOW_H2
        }

    fun build(config: TurboConfig, preference: ProtocolPreference): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            // 满并发不被 OkHttp 默认的 per-host=5 锁死
            maxRequests = 1024
            maxRequestsPerHost = 1024
        }
        val protocols = if (allowH2(config, preference)) {
            listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
        } else {
            listOf(Protocol.HTTP_1_1)
        }
        val builder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            // 默认 UA 拦截器：若请求未显式携带 User-Agent，注入配置的通用浏览器 UA（避免部分 CDN 拦截）。
            .addInterceptor { chain ->
                val req = chain.request()
                val out = if (req.header("User-Agent").isNullOrBlank())
                    req.newBuilder().header("User-Agent", config.userAgent).build()
                else req
                chain.proceed(out)
            }
            .connectionPool(
                ConnectionPool(
                    // 空闲连接数至少跟得上单任务并发数，避免分片反复重建 TCP/TLS
                    maxIdleConnections = maxOf(config.maxIdleConnections, config.maxConnectionsPerTask),
                    keepAliveDuration = config.keepAliveSeconds,
                    timeUnit = TimeUnit.SECONDS,
                )
            )
            // 协议由 [allowH2]（策略 × 链路偏好）决定，见 segment/streamProtocolsFor 的说明。
            .protocols(protocols)
            .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)

        applyProxy(builder, config.proxy)
        applyDns(builder, config.dns)
        if (config.trustAllCerts) applyTrustAll(builder)

        return builder.build()
    }

    // ---------- 代理 ----------

    private fun applyProxy(builder: OkHttpClient.Builder, mode: ProxyMode) {
        when (mode) {
            is ProxyMode.Direct -> builder.proxy(JProxy.NO_PROXY)
            is ProxyMode.System -> {
                // 使用 JVM 系统 ProxySelector（读取 http.proxyHost / 环境变量等）
                builder.proxySelector(ProxySelector.getDefault())
            }
            is ProxyMode.Manual -> {
                val jType = when (mode.type) {
                    ProxyType.HTTP -> JProxy.Type.HTTP
                    ProxyType.SOCKS -> JProxy.Type.SOCKS
                }
                builder.proxy(JProxy(jType, InetSocketAddress(mode.host, mode.port)))
                if (mode.username != null) {
                    val user = mode.username
                    val pass = mode.password ?: ""
                    if (mode.type == ProxyType.HTTP) {
                        // HTTP 代理鉴权：Proxy-Authorization
                        val credential = okhttp3.Credentials.basic(user, pass)
                        builder.proxyAuthenticator { _, response ->
                            if (response.request.header("Proxy-Authorization") != null) return@proxyAuthenticator null
                            response.request.newBuilder()
                                .header("Proxy-Authorization", credential)
                                .build()
                        }
                    } else {
                        // SOCKS 鉴权：走 JVM Authenticator
                        java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                            override fun getPasswordAuthentication(): PasswordAuthentication =
                                PasswordAuthentication(user, pass.toCharArray())
                        })
                    }
                }
            }
            is ProxyMode.Pac -> {
                // PAC：交给系统级 PAC ProxySelector（JVM 需 -Djava.net.useSystemProxies 或自定义实现）。
                // 这里用一个基于 java.net.ProxySelector 的简单委托：解析失败回退直连。
                builder.proxySelector(PacProxySelector(mode.pacUrl))
            }
        }
    }

    // ---------- DNS ----------

    private fun applyDns(builder: OkHttpClient.Builder, mode: DnsMode) {
        when (mode) {
            // IPv4 优先：OkHttp 按 DNS 返回顺序逐个地址尝试连接，**每个地址吃满 connectTimeout**
            // （默认 15s）才轮到下一个。若解析同时给出 AAAA/A 而设备 IPv6 路由不通
            // （手机蜂窝网常见），首连接 = AAAA 超时 15s + A 成功 ≈ 15~30s，表现恰好是
            // “任何链接解析都要卡半分钟”。v4 优先让第一个地址就是可用地址；仅调序，不丢地址。
            is DnsMode.System -> builder.dns(FamilyOrderedDns(Dns.SYSTEM))
            is DnsMode.StaticHosts -> builder.dns(StaticHostsDns(mode.hosts))
            is DnsMode.DoH -> builder.dns(FamilyOrderedDns(DohDns(mode.dohUrl)))
            is DnsMode.Auto -> builder.dns(FamilyOrderedDns(DohDns(mode.endpoints)))
        }
    }

    /** IPv4 优先的 DNS 包装（只调序，不丢地址、不改内容）。 */
    private class FamilyOrderedDns(private val delegate: Dns) : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            preferIpv4(delegate.lookup(hostname))
    }

    /** 把 IPv4 排到前面（稳定排序，族内相对顺序不变）。 */
    private fun preferIpv4(addrs: List<InetAddress>): List<InetAddress> =
        if (addrs.size < 2) addrs else addrs.sortedByDescending { it is java.net.Inet4Address }

    /** 静态 hosts 覆盖 DNS：命中返回配置 IP，未命中回退系统解析。 */
    private class StaticHostsDns(private val hosts: Map<String, List<String>>) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            hosts[hostname]?.let { ips ->
                val resolved = ips.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
                if (resolved.isNotEmpty()) return resolved
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    /**
     * DNS over HTTPS（最小实现，RFC 8484 GET application/dns-message）。
     * 失败回退系统 DNS，保证可用性。
     *
     * 支持**单端点**与**自动择优**：
     *  - [single]：固定使用用户指定的 DoH（行为与历史一致）
     *  - [auto]：并发探测多个公共 DoH，固定采用**最快给出有效结果**的那个
     *
     * @param dohUrl 单端点模式下的 DoH 地址（自动模式下忽略）
     * @param autoEndpoints 自动模式的候选端点；非空即启用自动择优
     */
    private class DohDns private constructor(
        dohUrl: String?,
        private val autoEndpoints: List<String>,
    ) : Dns {
        constructor(dohUrl: String) : this(dohUrl, emptyList())
        constructor(endpoints: List<String>) : this(null, endpoints)

        private val base = dohUrl

        /** 自动模式是否启用。 */
        private val auto: Boolean get() = autoEndpoints.isNotEmpty()
        /**
         * DoH 客户端。
         *
         * 【超时压到 3s 而不是 10s】实测（用户反馈：国内网开 Cloudflare DoH）：DoH 在国内**普遍很慢甚至不通**。
         * 而 DoH 只是"更好用的 DNS"，**失败后本来就会回退系统 DNS** —— 所以超时越长，纯粹是白等：
         * 10s 超时意味着「每个新域名的首次解析都白等 10 秒」；3s 让回退早 7 秒发生，
         * 配合下面的缓存，首次连接的开销从"20s 级"降到"3s 级"。
         */
        private val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        /**
         * DoH 查询缓存。
         *
         * 【为什么必须有】一次 DoH 查询是**完整的 HTTPS 往返**（本实现超时 10s 连接 + 10s 读），
         * 而旧实现**每个新连接都要重新查一次**。DoH 服务器慢/不通时，
         * 「每个链接的解析都要先撞一次超时」→ 表现成**任何链接解析都卡 10~30 秒**
         * ——且越新的版本探测请求越少，撞 DoH 的相对占比反而越显眼。
         * 成功缓存 5 分钟；失败（空结果）也缓存 30 秒——失败缓存让后续连接**快速失败**，
         * 而不是每个都再等一轮超时。
         */
        private val cache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()
        private class CacheEntry(val addrs: List<InetAddress>, val expireAt: Long)

        override fun lookup(hostname: String): List<InetAddress> {
            val now = System.currentTimeMillis()
            cache[hostname]?.let { e ->
                if (e.expireAt > now) return e.addrs
                cache.remove(hostname, e)
            }
            val result = runCatching { if (auto) lookupAuto(hostname) else queryDoH(base!!, hostname) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: preferIpv4(Dns.SYSTEM.lookup(hostname))
            cache[hostname] = CacheEntry(result, now + if (result.isEmpty()) 30_000L else 300_000L)
            return result
        }

        /**
         * 自动择优：当前选中的端点。
         *
         * 选定后**固定复用**，直到它解析失败或超出有效期 —— 每次查询都重新测速会让
         * 解析延迟抖动，也会显著增加探测流量（每查一个域名要多打几个 DoH）。
         */
        @Volatile
        private var selectedEndpoint: String? = null

        @Volatile
        private var selectedAt: Long = 0L

        /**
         * 自动模式解析：优先用已选端点；失效则并发探测全部候选，采用最快给出有效结果的那个。
         *
         * 【为什么并发探测】串行时要等前一个超时（3s）才轮到下一个，4 个候选最坏白等 12 秒；
         * 并发把最坏情况压到**一个超时周期**。
         */
        private fun lookupAuto(hostname: String): List<InetAddress> {
            val current = selectedEndpoint
            if (current != null && System.currentTimeMillis() - selectedAt < ENDPOINT_TTL_MS) {
                val addrs = runCatching { queryDoH(current, hostname) }.getOrDefault(emptyList())
                if (addrs.isNotEmpty()) return addrs
                selectedEndpoint = null      // 选定的端点失效：重新择优
            }
            val pool = java.util.concurrent.Executors.newFixedThreadPool(autoEndpoints.size) { r ->
                Thread(r, "turbodl-doh-probe").apply { isDaemon = true }
            }
            return try {
                val futures = autoEndpoints.map { ep ->
                    ep to pool.submit<List<InetAddress>> {
                        runCatching { queryDoH(ep, hostname) }.getOrDefault(emptyList())
                    }
                }
                val deadline = System.currentTimeMillis() + PROBE_DEADLINE_MS
                val pending = futures.toMutableList()
                while (pending.isNotEmpty() && System.currentTimeMillis() < deadline) {
                    val done = pending.firstOrNull { it.second.isDone }
                        ?: run { Thread.sleep(20); null } ?: continue
                    pending.remove(done)
                    val addrs = runCatching { done.second.get() }.getOrDefault(emptyList())
                    if (addrs.isNotEmpty()) {
                        selectedEndpoint = done.first
                        selectedAt = System.currentTimeMillis()
                        return addrs
                    }
                }
                emptyList()
            } finally {
                pool.shutdownNow()
            }
        }

        private fun queryDoH(endpoint: String, hostname: String): List<InetAddress> {
            val query = buildDnsQuery(hostname)
            val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(query)
            val sep = if (endpoint.contains('?')) "&" else "?"
            val url = "$endpoint${sep}dns=$b64"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-message")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val bytes = resp.body?.bytes() ?: return emptyList()
                return parseDnsAnswers(bytes, hostname)
            }
        }

        /** 构造最简 A 记录查询报文。 */
        internal fun buildDnsQuery(hostname: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val dos = java.io.DataOutputStream(out)
            dos.writeShort(0x0000)      // ID
            dos.writeShort(0x0100)      // flags: RD=1
            dos.writeShort(1)           // QDCOUNT
            dos.writeShort(0)           // ANCOUNT
            dos.writeShort(0)           // NSCOUNT
            dos.writeShort(0)           // ARCOUNT
            for (label in hostname.split('.')) {
                val b = label.toByteArray(Charsets.US_ASCII)
                dos.writeByte(b.size)
                dos.write(b)
            }
            dos.writeByte(0)            // end of QNAME
            dos.writeShort(1)           // QTYPE = A
            dos.writeShort(1)           // QCLASS = IN
            return out.toByteArray()
        }

        /** 解析 A 记录（IPv4）。仅提取 answer 中 type=A 的 4 字节地址。 */
        internal fun parseDnsAnswers(msg: ByteArray, hostname: String): List<InetAddress> {
            val din = java.io.DataInputStream(java.io.ByteArrayInputStream(msg))
            din.skipBytes(4)                         // ID + flags
            val qd = din.readUnsignedShort()
            val an = din.readUnsignedShort()
            din.skipBytes(4)                         // NS + AR counts
            repeat(qd) {                             // skip questions
                skipName(din)
                din.skipBytes(4)                     // QTYPE + QCLASS
            }
            val result = mutableListOf<InetAddress>()
            repeat(an) {
                skipName(din)
                val type = din.readUnsignedShort()
                din.skipBytes(2)                     // CLASS
                din.skipBytes(4)                     // TTL
                val rdlen = din.readUnsignedShort()
                if (type == 1 && rdlen == 4) {
                    val ip = ByteArray(4); din.readFully(ip)
                    result.add(InetAddress.getByAddress(hostname, ip))
                } else {
                    din.skipBytes(rdlen)
                }
            }
            return result
        }

        /** 跳过 DNS name（处理压缩指针）。 */
        internal fun skipName(din: java.io.DataInputStream) {
            while (true) {
                val len = din.readUnsignedByte()
                if (len == 0) break
                if (len and 0xC0 == 0xC0) { din.skipBytes(1); break }  // 压缩指针，2 字节
                din.skipBytes(len)
            }
        }

        private companion object {
            /** 选定端点的有效期：过期后重新择优（网络环境可能已变化）。 */
            const val ENDPOINT_TTL_MS = 10 * 60 * 1000L

            /** 并发探测的整体等待上限（略大于单次 3 秒超时）。 */
            const val PROBE_DEADLINE_MS = 4_000L
        }
    }

    // ---------- TLS ----------

    private fun applyTrustAll(builder: OkHttpClient.Builder) {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        builder.sslSocketFactory(ctx.socketFactory, trustAll[0] as X509TrustManager)
        builder.hostnameVerifier { _, _ -> true }
    }

    /**
     * 探测各 DoH 端点的解析延迟（毫秒）；不可用记 -1。
     *
     * 【为什么并发】串行探测 4 个端点、每个超时 3 秒 → 最坏要等 12 秒，
     * 用户点开设置页会明显卡住。并发把整体耗时压到"最快端点的耗时"（上限 timeoutMs）。
     *
     * 【为什么默认探 baidu.com】若探一个在被墙域名，所有端点都会失败，
     * 界面上会显示"全部不可用"——那是探测目标的问题，不是 DoH 的问题。
     * 用国内可达性最好的域名，才能真实反映各端点在本机网络下的可用性与延迟。
     */
    internal fun probeDohLatency(
        endpoints: List<String>,
        hostname: String,
        timeoutMs: Long,
    ): List<Pair<String, Long>> {
        if (endpoints.isEmpty()) return emptyList()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(endpoints.size) { r ->
            Thread(r, "turbodl-doh-latency").apply { isDaemon = true }
        }
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build()
            // 复用 DohDns 的报文编解码，避免重复实现（两处不一致会导致"能查但不能探测"这类怪问题）
            val codec = DohDns(endpoints.first())
            val query = codec.buildDnsQuery(hostname)
            val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(query)
            val futures = endpoints.map { ep ->
                pool.submit<Long> {
                    val started = System.nanoTime()
                    runCatching {
                        val sep = if (ep.contains('?')) "&" else "?"
                        val req = Request.Builder()
                            .url("$ep${sep}dns=$b64")
                            .header("Accept", "application/dns-message")
                            .get()
                            .build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@submit -1L
                            val bytes = resp.body?.bytes() ?: return@submit -1L
                            // 必须真解析出地址才算可用：仅"HTTP 200"可能是一张错误页
                            if (codec.parseDnsAnswers(bytes, hostname).isEmpty()) -1L
                            else (System.nanoTime() - started) / 1_000_000
                        }
                    }.getOrDefault(-1L)
                }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            endpoints.indices.map { i ->
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
                endpoints[i] to runCatching {
                    futures[i].get(remaining, TimeUnit.MILLISECONDS)
                }.getOrDefault(-1L)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // DNS 报文的构造与解析在 DohDns 内部实现，此处复用其逻辑需要可见性调整；
    // 为避免重复实现，把它们提为文件级私有工具（下方）。
}

/**
 * 基于 PAC URL 的 ProxySelector（最小实现）：
 * 依赖 JVM `sun.net.spi.DefaultProxySelector` 无法直接解释 PAC，
 * 因此这里只做「拉取 PAC 内容并对 FindProxyForURL 的常见返回做正则解析」的轻量支持；
 * 无法解析时回退直连，并保证不抛出。
 */
internal class PacProxySelector(private val pacUrl: String) : ProxySelector() {
    @Volatile private var cachedPac: String? = null

    override fun select(uri: java.net.URI?): MutableList<JProxy> {
        val pac = loadPac() ?: return mutableListOf(JProxy.NO_PROXY)
        // 极简：查找形如 PROXY host:port 或 SOCKS host:port 的字面量
        val m = Regex("(PROXY|SOCKS)\\s+([\\w.\\-]+):(\\d+)", RegexOption.IGNORE_CASE).find(pac)
            ?: return mutableListOf(JProxy.NO_PROXY)
        val type = if (m.groupValues[1].equals("SOCKS", true)) JProxy.Type.SOCKS else JProxy.Type.HTTP
        return mutableListOf(JProxy(type, InetSocketAddress(m.groupValues[2], m.groupValues[3].toInt())))
    }

    override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {
        // 忽略；下次 select 回退直连
    }

    private fun loadPac(): String? {
        cachedPac?.let { return it }
        return runCatching {
            OkHttpClient().newCall(Request.Builder().url(pacUrl).get().build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()?.also { cachedPac = it }
            }
        }.getOrNull()
    }
}

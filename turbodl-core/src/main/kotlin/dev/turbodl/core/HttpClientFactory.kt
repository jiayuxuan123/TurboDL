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

    fun build(config: TurboConfig): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            // 满并发不被 OkHttp 默认的 per-host=5 锁死
            maxRequests = 1024
            maxRequestsPerHost = 1024
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
            // 默认强制 HTTP/1.1：HTTP/2 的多路复用会把所有分片挤到一条 TCP 连接上，
            // 共享单个拥塞/流控窗口 → 多线程得不到加速（表现为“64 线程跑出单线程速度”）。
            .protocols(
                if (config.forceHttp1) listOf(Protocol.HTTP_1_1)
                else listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
            )
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
            is DnsMode.System -> { /* OkHttp 默认使用系统 DNS */ }
            is DnsMode.StaticHosts -> builder.dns(StaticHostsDns(mode.hosts))
            is DnsMode.DoH -> builder.dns(DohDns(mode.dohUrl))
        }
    }

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
     */
    private class DohDns(dohUrl: String) : Dns {
        private val base = dohUrl
        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        override fun lookup(hostname: String): List<InetAddress> {
            return runCatching { queryDoH(hostname) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: Dns.SYSTEM.lookup(hostname)
        }

        private fun queryDoH(hostname: String): List<InetAddress> {
            val query = buildDnsQuery(hostname)
            val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(query)
            val sep = if (base.contains('?')) "&" else "?"
            val url = "$base${sep}dns=$b64"
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
        private fun buildDnsQuery(hostname: String): ByteArray {
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
        private fun parseDnsAnswers(msg: ByteArray, hostname: String): List<InetAddress> {
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
        private fun skipName(din: java.io.DataInputStream) {
            while (true) {
                val len = din.readUnsignedByte()
                if (len == 0) break
                if (len and 0xC0 == 0xC0) { din.skipBytes(1); break }  // 压缩指针，2 字节
                din.skipBytes(len)
            }
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

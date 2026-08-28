package dev.turbodl.core

/**
 * TurboDL 全局引擎配置。
 *
 * 设计参考（仅思想，未复制源码）：
 *  - aria2：固定连接数满并发、min-split-size；
 *  - IDM / XDM：动态分段（dynamic segmentation）+ 连接复用；
 *  - ab-download-manager：defaultThreadCount / dynamicPartCreationMode / globalSpeedLimit / minPartSize / maxRetry / 代理策略；
 *  - axel / Persepolis / Motrix：多连接、限速档、代理与队列管理。
 *
 * 全部字段可在运行时读取（部分需在任务启动前设定，见注释）。
 */
data class TurboConfig(
    /** 每个任务的最大连接数（分片并发上限）。范围 1..256。 */
    val maxConnectionsPerTask: Int = 8,

    /** 同时下载的最大任务数（队列并发）。范围 1..64。 */
    val maxConcurrentTasks: Int = 3,

    /** 全局下载速度上限（字节/秒），所有任务合计。0 = 不限速。 */
    val globalSpeedLimitBytesPerSec: Long = 0,

    /** 单个分片下载失败后的最大重试次数（仅重试该分片，不作废整任务）。范围 0..50。 */
    val maxRetries: Int = 5,

    /**
     * 动态分段（IDM/XDM 思想）：
     *  - true：连接空闲时，从「剩余未下载最多」的活动分片中点劈分，让空闲连接接手后半段，消除长尾；
     *  - false：退化为固定块大小 + 工作窃取。
     */
    val dynamicSegmentation: Boolean = true,

    /** 分段最小尺寸（字节）。小于 2*该值的分片不再劈分，避免过度碎片化。 */
    val minSegmentSize: Long = 64L * 1024,

    /**
     * 固定分块大小（字节），仅在无法根据连接数推导时作为上限参考。
     *
     * 注意：调度器**优先按连接数推导块大小**（保证块数 ≥ 连接数 × [segmentsPerConnection]），
     * 以便固定 N 线程能全部跑满；本值只限制单块不要过大。
     */
    val blockSize: Long = 4L * 1024 * 1024,

    /**
     * 每个连接分配的块数（工作窃取粒度）。
     *
     * 块数 = 连接数 × 本值，使快连接能不断领新块、慢连接不拖累整体（等效 IDM/XDM 动态分段的消除长尾）。
     * 调大更均衢但请求次数增加；范围 1..64。
     */
    val segmentsPerConnection: Int = 4,

    /**
     * 自适应并发下调策略（**不照搬 AIMD 抖动判断**）：
     * 仅当收到 429/503 或出现「连续连接失败」达到阈值时才乘性下调并发；
     * 普通网速波动绝不主动减少连接数。设为 0 关闭该保护。
     */
    val backpressureConsecutiveFailures: Int = 4,

    /** 代理配置。 */
    val proxy: ProxyMode = ProxyMode.Direct,

    /** DNS 配置。 */
    val dns: DnsMode = DnsMode.System,

    /** 忽略 TLS/SSL 证书校验（抓包调试用；生产环境勿开）。 */
    val trustAllCerts: Boolean = false,

    /** 连接超时（毫秒）。 */
    val connectTimeoutMs: Long = 15_000,

    /** 读取超时（毫秒）。 */
    val readTimeoutMs: Long = 60_000,

    /** 默认 User-Agent（采用通用浏览器 UA，避免部分 CDN 对非浏览器 UA 直接拦截）。 */
    val userAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",

    /** 连接池最大空闲连接数（连接复用）。应 ≥ maxConnectionsPerTask，否则分片连接会反复重建。 */
    val maxIdleConnections: Int = 256,

    /**
     * 强制使用 HTTP/1.1（默认 true，专为多线程下载优化）。
     *
     * 原因：HTTP/2 会把所有并发请求**多路复用到同一条 TCP 连接**上，
     * 共享单个拥塞窗口与流控窗口——即使开 64/256 个分片，吞吐量仍等同单连接，
     * 这是“开了很多线程却只跑出单线程速度”的典型原因（GitHub / 大多数 CDN 均启用 HTTP/2）。
     * HTTP/1.1 下每个并发请求各自建立 TCP 连接，各自拥有独立拥塞窗口，
     * 才能真正获得多连接加速（aria2 / IDM / XDM 同策略）。
     *
     * 注意：当 [httpVersionPolicy] 为 AUTO 时，本字段仅影响“分片并发”链路（仍走 HTTP/1.1），
     * 而“整文件单流回退”链路允许协商 HTTP/2（单流场景 h2 未必更差且兼容性更好）。
     */
    val forceHttp1: Boolean = true,

    /**
     * HTTP 版本协商策略（默认 AUTO，按下载模式自动选择）：
     *  - **AUTO**：多分片并发走 HTTP/1.1（避免 h2 多路复用抹平多连接收益）；
     *    探测与整文件单流回退允许协商 HTTP/2（兼容仅支持 h2 的服务器，且单流无多连接损失）。
     *  - **FORCE_HTTP1**：全部链路强制 HTTP/1.1。
     *  - **FORCE_HTTP2**：全部链路允许 HTTP/2（仅在确知需要时使用，多线程可能无加速）。
     *
     * 为兼容旧版：未显式设置本字段时，若 [forceHttp1]=false 则等同 FORCE_HTTP2，否则为 AUTO。
     */
    val httpVersionPolicy: HttpVersionPolicy = HttpVersionPolicy.AUTO,

    /**
     * 每个主机（host）的最大并发分片数。<=0 表示不限（用 [maxConnectionsPerTask]）。
     *
     * 部分 CDN（如迅雷）对单 host 的并发 Range 有硬上限，超过后会把 Range 请求降级为 200 整文件，
     * 反而触发整文件回退。设置该上限可避免被降级。实际生效值 = min(maxConnectionsPerTask, 本值)。
     */
    val maxConnectionsPerHost: Int = 0,

    /** 连接保活时长（秒）。 */
    val keepAliveSeconds: Long = 300,

    /**
     * 分片临时目录根。null 时使用系统 java.io.tmpdir/turbodl。
     *
     * Android 上系统 tmpdir 可能被清理或受限，宿主应传入应用专属缓存目录（如 externalCacheDir），
     * 以保证断点分片跨会话、跨进程稳定保留。
     */
    val workDir: java.io.File? = null,

    /**
     * 连接预热 / DNS 预解析（默认开）。
     *
     * 探测拿到最终（重定向后）URL 后，在正式分片下载前先并发建立若干空连接（并预解析 DNS），
     * 填充连接池。这样分片开始时无需串行等待 DNS 解析 + TCP/TLS 握手，启动更快、初期吞吐更高。
     */
    val warmUpConnections: Boolean = true,

    /** 预热时建立的空连接数；<=0 时取 min(maxConnectionsPerTask, 8)。 */
    val warmUpConnectionCount: Int = 0,

    /**
     * 慢启动（默认开）：分片并发从少逐步上调到 [maxConnectionsPerTask]，而非一上来就全开。
     *
     * 好处：避免瞬时几十个连接同时握手冲击服务器/被风控，也避免小文件刚开就过度建连；
     * 与背压正交：背压负责“遇错降”，慢启动负责“健康升”。
     */
    val slowStart: Boolean = true,

    /** 慢启动初始并发；<=0 时取 min(maxConnectionsPerTask, 4)。 */
    val slowStartInitial: Int = 0,
) {
    init {
        require(maxConnectionsPerTask in 1..256) { "maxConnectionsPerTask 必须在 1..256" }
        require(maxConcurrentTasks in 1..64) { "maxConcurrentTasks 必须在 1..64" }
        require(globalSpeedLimitBytesPerSec >= 0) { "globalSpeedLimitBytesPerSec 不能为负" }
        require(maxRetries in 0..50) { "maxRetries 必须在 0..50" }
        require(minSegmentSize >= 4096) { "minSegmentSize 至少 4KB" }
        require(blockSize >= minSegmentSize) { "blockSize 不能小于 minSegmentSize" }
        require(segmentsPerConnection in 1..64) { "segmentsPerConnection 必须在 1..64" }
        require(maxConnectionsPerHost >= 0) { "maxConnectionsPerHost 不能为负" }
        require(warmUpConnectionCount >= 0) { "warmUpConnectionCount 不能为负" }
        require(slowStartInitial >= 0) { "slowStartInitial 不能为负" }
    }

    /**
     * 解析实际生效的 HTTP 版本策略（兼容旧的 [forceHttp1] 字段）。
     * httpVersionPolicy 显式为非 AUTO 时优先；否则由 forceHttp1 推导（true→AUTO, false→FORCE_HTTP2）。
     */
    val effectiveHttpVersionPolicy: HttpVersionPolicy
        get() = when (httpVersionPolicy) {
            HttpVersionPolicy.AUTO -> if (forceHttp1) HttpVersionPolicy.AUTO else HttpVersionPolicy.FORCE_HTTP2
            else -> httpVersionPolicy
        }
}

/** HTTP 版本协商策略。 */
enum class HttpVersionPolicy {
    /** 分片并发走 HTTP/1.1，探测与整文件单流回退允许 HTTP/2。 */
    AUTO,

    /** 全部链路强制 HTTP/1.1。 */
    FORCE_HTTP1,

    /** 全部链路允许 HTTP/2（多线程可能无加速）。 */
    FORCE_HTTP2,
}

/** 代理模式（参考 ab-download-manager 的 ProxyStrategy）。 */
sealed interface ProxyMode {
    /** 直连，不使用代理。 */
    data object Direct : ProxyMode

    /** 使用系统代理（JVM 的 ProxySelector / 环境变量）。 */
    data object System : ProxyMode

    /** 手动指定代理。 */
    data class Manual(
        val type: ProxyType,
        val host: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null,
    ) : ProxyMode

    /** PAC 脚本（自动代理配置）URL。 */
    data class Pac(val pacUrl: String) : ProxyMode
}

enum class ProxyType { HTTP, SOCKS }

/** DNS 模式。 */
sealed interface DnsMode {
    /** 系统默认 DNS。 */
    data object System : DnsMode

    /** 静态 hosts 覆盖（host -> IP 列表）；未命中回退系统 DNS。 */
    data class StaticHosts(val hosts: Map<String, List<String>>) : DnsMode

    /** DNS over HTTPS（例如 https://dns.google/dns-query）。 */
    data class DoH(val dohUrl: String) : DnsMode
}

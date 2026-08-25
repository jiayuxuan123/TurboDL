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
    val minSegmentSize: Long = 1L * 1024 * 1024,

    /** 固定分块大小（字节），dynamicSegmentation=false 或初始规划时使用。 */
    val blockSize: Long = 4L * 1024 * 1024,

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

    /** 默认 User-Agent。 */
    val userAgent: String = "TurboDL/0.1",

    /** 连接池最大空闲连接数（连接复用）。 */
    val maxIdleConnections: Int = 64,

    /** 连接保活时长（秒）。 */
    val keepAliveSeconds: Long = 300,
) {
    init {
        require(maxConnectionsPerTask in 1..256) { "maxConnectionsPerTask 必须在 1..256" }
        require(maxConcurrentTasks in 1..64) { "maxConcurrentTasks 必须在 1..64" }
        require(globalSpeedLimitBytesPerSec >= 0) { "globalSpeedLimitBytesPerSec 不能为负" }
        require(maxRetries in 0..50) { "maxRetries 必须在 0..50" }
        require(minSegmentSize >= 4096) { "minSegmentSize 至少 4KB" }
        require(blockSize >= minSegmentSize) { "blockSize 不能小于 minSegmentSize" }
    }
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

package dev.turbodl.core

/** 下载任务定义（提交给引擎的输入）。 */
data class DownloadRequest(
    /** 下载 URL（HTTP/HTTPS）。 */
    val url: String,

    /** 保存的目标文件（绝对路径）。 */
    val destination: java.io.File,

    /** 附加请求头（Cookie / Referer / UA 覆盖等）。 */
    val headers: Map<String, String> = emptyMap(),

    /** 已知文件大小（字节）；<=0 表示未知，由引擎探测。 */
    val knownSize: Long = -1,

    /** 覆盖本任务的连接数；null 使用全局配置。范围仍受 1..256 限制。 */
    val connectionsOverride: Int? = null,
)

/** 任务状态。 */
enum class TaskState {
    QUEUED,        // 已入队等待并发槽
    PROBING,       // 探测大小 / Range 支持
    DOWNLOADING,   // 分片下载中
    MERGING,       // 合并分片
    COMPLETED,     // 完成
    PAUSED,        // 暂停（保留断点）
    FAILED,        // 失败
    CANCELED,      // 取消
}

/** 实时进度快照。 */
data class TaskProgress(
    val taskId: Long,
    val state: TaskState,
    val downloadedBytes: Long,
    val totalBytes: Long,          // -1 表示未知
    val speedBytesPerSec: Long,
    val activeConnections: Int,
    val etaMillis: Long,           // -1 表示未知
    val error: String? = null,
) {
    val percent: Int
        get() = if (totalBytes > 0)
            ((downloadedBytes * 100 / totalBytes).toInt()).coerceIn(0, 100)
        else -1
}

/**
 * 引擎事件（类型安全）。
 *
 * 说明：本轮只暴露事件流用于观测与解耦，**不实现插件系统/扩展点**。
 * 事件模型足够作为未来可选插件模块的订阅源，但当前不含任何插件相关代码。
 */
sealed interface TurboEvent {
    val taskId: Long

    data class Created(override val taskId: Long, val request: DownloadRequest) : TurboEvent
    data class StateChanged(override val taskId: Long, val state: TaskState) : TurboEvent
    data class Progress(override val taskId: Long, val progress: TaskProgress) : TurboEvent
    data class Completed(override val taskId: Long, val file: java.io.File, val totalBytes: Long) : TurboEvent
    data class Failed(override val taskId: Long, val reason: String) : TurboEvent
}

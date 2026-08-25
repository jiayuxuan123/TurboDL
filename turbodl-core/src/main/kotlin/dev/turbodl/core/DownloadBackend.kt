package dev.turbodl.core

import java.io.File

/**
 * Download backend extension point (defined in core; reusable by the optional runtime).
 *
 * Design (hybrid A+B): core ships a built-in HTTP backend so it works standalone,
 * while this interface lets the optional plugin runtime override the built-in one
 * or add new protocols (HLS, FTP, magnet, ...) via a registry — WITHOUT core ever
 * depending on the runtime module.
 *
 * A backend is responsible for the protocol layer: probing, fetching bytes into the
 * work directory, and reporting progress through [BackendContext]. Merging, task
 * state machine, events, and integrity checks stay in the engine ([TurboClient]).
 *
 * NOTE: reserved — future JS Provider / third-party shim adapter plugins may supply
 * their own DownloadBackend implementations through the runtime's registry.
 */
interface DownloadBackend {
    /** Stable identifier for diagnostics/logging (e.g. "builtin-http"). */
    val name: String

    /** Whether this backend can handle the given request (typically by URL scheme). */
    fun supports(request: DownloadRequest): Boolean

    /**
     * Perform the download. Implementations should:
     *  - honor [BackendContext.isActive] and cooperative coroutine cancellation;
     *  - report total size via [BackendContext.reportTotalSize] once known;
     *  - report progress via [BackendContext.reportProgress];
     *  - optionally rate-limit writes via [BackendContext.throttle];
     *  - write output into [BackendContext.workDir] and return ordered parts to merge.
     *
     * Throwing an exception signals failure (the engine will surface it as a failed task).
     */
    suspend fun download(context: BackendContext): BackendResult
}

/**
 * Result of a backend download: an ordered list of files to be concatenated into the
 * final destination, plus the authoritative total size (-1 if unknown).
 * A single-file result (e.g. whole-file fallback) is simply a one-element list.
 */
class BackendResult(
    val orderedParts: List<File>,
    val totalBytes: Long,
)

/**
 * Services the engine provides to a backend during a download.
 * Kept as a public interface so third-party backends never touch core internals.
 */
interface BackendContext {
    val taskId: Long
    val request: DownloadRequest

    /** Per-task temporary working directory (already created). */
    val workDir: File

    /** Effective engine configuration snapshot. */
    val config: TurboConfig

    /** False once the task is paused/canceled; backends must stop promptly. */
    fun isActive(): Boolean

    /** Consume rate-limit budget for [bytes]; suspends when the global limit is exceeded. */
    suspend fun throttle(bytes: Long)

    /** Report the authoritative total size once known (-1 if unknown/streaming). */
    fun reportTotalSize(total: Long)

    /** Report cumulative downloaded bytes and current active connection count. */
    suspend fun reportProgress(absoluteBytes: Long, activeConnections: Int)
}

/**
 * Resolver that maps a request to the backend that should handle it.
 *
 * core installs no resolver by default and always uses the built-in HTTP backend.
 * The optional runtime registers a resolver (its BackendRegistry) so that plugin
 * backends can override the built-in or add protocols. Returning null means
 * "let the engine fall back to the built-in backend".
 *
 * NOTE: reserved — the runtime's BackendRegistry implements this; core never
 * references the runtime.
 */
fun interface BackendResolver {
    fun resolve(request: DownloadRequest): DownloadBackend?
}

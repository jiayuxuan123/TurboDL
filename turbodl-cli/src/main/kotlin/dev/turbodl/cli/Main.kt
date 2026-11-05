package dev.turbodl.cli

import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.DnsMode
import dev.turbodl.core.HttpVersionPolicy
import dev.turbodl.core.AdaptiveConcurrencyMode
import dev.turbodl.core.ProxyMode
import dev.turbodl.core.ProxyType
import dev.turbodl.core.TurboClient
import dev.turbodl.core.TurboConfig
import dev.turbodl.core.TaskProgress
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

/**
 * TurboDL CLI —— 面向人与 Agent 的多线程下载命令行工具。
 *
 * 设计目标：
 *  - 非交互、退出码规范（0 成功 / 1 下载失败 / 2 用法错误），便于脚本与 Agent 调用；
 *  - `--json` 输出 NDJSON 事件流（逐行 JSON），机器可直接解析；
 *  - 断点续传：同一「URL+输出路径」中断后重跑同一命令自动从断点继续（分片保留在输出旁 .turbodl-parts/）；
 *  - 批量：`--batch urls.txt`，每行 `URL` 或 `URL 输出路径`，# 开头为注释。
 *
 * 示例：
 *   turbodl https://example.com/big.bin -o big.bin -c 64 --json
 *   turbodl <url> --proxy http://127.0.0.1:7890 --doh https://dns.alidns.com/dns-query
 *   turbodl --batch tasks.txt -c 128
 *
 * 版本号来自构建注入的 [BUILD_VERSION]（见 turbodl-cli/build.gradle.kts 的 generateBuildInfo），
 * **不要在这里硬编码** —— 曾经硬编码过，结果从 rc11 起就一直没跟上。
 */
// 版本号由构建注入，见 turbodl-cli/build.gradle.kts 的 generateBuildInfo 任务。

/** 一条待下载任务（批量模式逐条构造）。 */
private class Task(val url: String, val output: File)

/** 当前是否有任务正在下载（供 shutdown hook 判断是否打印续传提示）。 */
private val downloading = AtomicBoolean(false)

/** 解析后的全部命令行选项。 */
private class Options {
    var connections = 16
    var maxTotalConnections = 0
    var expectedSha256: String? = null
    /** Known exact object size. Supplying it lets the engine skip the separate probe RTT. */
    var knownSize: Long? = null
    /** 额外镜像来源（-M/--mirror，可重复）。 */
    val mirrors = mutableListOf<String>()
    var speedLimit: Long = 0L            // 字节/秒，0=不限
    var retry = 5
    /** 批量任务同时运行的下载任务数；全局连接/限速预算由同一个 client 共享。 */
    var jobs = 1
    var output: File? = null             // -o
    var dir: File? = null                // -d
    var batchFile: File? = null          // --batch
    var headers = linkedMapOf<String, String>()
    var userAgent: String? = null
    var proxy: ProxyMode? = null
    var dohUrl: String? = null
    var httpPolicy = HttpVersionPolicy.AUTO
    var warmUp = true
    var slowStart = true
    var adaptive = true
    var adaptiveMode = AdaptiveConcurrencyMode.DOWNWARD
    var insecure = false
    var connectTimeoutMs = 15_000L
    var readTimeoutMs = 60_000L
    var json = false
    var quiet = false
    var progressIntervalMs = 400L
    // ---------- 调优旋钮（基准测试用：无需重新编译即可扫参数）----------
    /** 每连接预分块数。越大越能吸收慢连接，但分片切换开销也越大。 */
    var segmentsPerConnection: Int? = null
    /** 单块硬上限（字节）。 */
    var blockSize: Long? = null
    /** 分片软下限（字节）。 */
    var minSegmentSize: Long? = null
    /** 慢启动起始并发（0 = 自动）。 */
    var slowStartInitial: Int? = null
    /** 关闭收尾托管（用于 A/B 对照）。 */
    var noTailAssist = false
    /** 关闭下载中预先合并（用于 A/B 对照）。 */
    var noOverlap = false
    /** 单连接停滞多久后中断该连接（毫秒，0=关闭）。 */
    var stallTimeoutMs: Long? = null
    /** 输出阶段耗时事件（启动段诊断用）。 */
    var timing = false
}

fun main(args: Array<String>) {
    val processTimingStart = System.currentTimeMillis()
    val opts = Options()
    val tasks = mutableListOf<Task>()

    // ---------- 参数解析 ----------
    var i = 0
    while (i < args.size) {
        val raw = args[i]
        // 支持 --flag=value 形式
        val (flag, inlineVal) = if (raw.startsWith("--") && '=' in raw) {
            raw.substringBefore('=') to raw.substringAfter('=')
        } else raw to null

        fun take(): String {
            if (inlineVal != null) return inlineVal
            val v = args.getOrNull(i + 1) ?: usageError("缺少 $flag 的取值")
            i++
            return v
        }
        when (flag) {
            "-h", "--help" -> { printHelp(); return }
            "-V", "--version" -> { println("turbodl $BUILD_VERSION"); return }
            "-o", "--output" -> opts.output = File(take())
            "-d", "--dir" -> opts.dir = File(take())
            "-c", "--connections", "--threads" ->
                opts.connections = take().toIntOrNull()?.coerceIn(1, 256)
                    ?: usageError("--connections 需为 1..256 的整数")
            "--max-total-connections" ->
                opts.maxTotalConnections = take().toIntOrNull()?.coerceIn(0, 8192)
                    ?: usageError("--max-total-connections 需为 0..8192 的整数")
            "--sha256" -> {
                val value = take()
                if (!value.matches(Regex("^[0-9a-fA-F]{64}$"))) {
                    usageError("--sha256 需为 64 位十六进制 SHA-256")
                }
                opts.expectedSha256 = value.lowercase()
            }
            "--known-size" -> {
                val value = take().toLongOrNull()
                    ?: usageError("--known-size 需为非负整数")
                if (value < 0) usageError("--known-size 需为非负整数")
                opts.knownSize = value
            }
            "-l", "--limit", "--speed-limit" ->
                opts.speedLimit = parseSize(take())
            "-r", "--retry" ->
                opts.retry = take().toIntOrNull()?.coerceIn(0, 50)
                    ?: usageError("--retry 需为 0..50 的整数")
            "--jobs" ->
                opts.jobs = take().toIntOrNull()?.coerceIn(1, 64)
                    ?: usageError("--jobs 需为 1..64 的整数")
            "-b", "--batch" -> opts.batchFile = File(take())
            "-H", "--header" -> {
                val h = take()
                val idx = h.indexOf(':')
                if (idx <= 0) usageError("--header 需为 \"Key: Value\" 形式")
                opts.headers[h.substring(0, idx).trim()] = h.substring(idx + 1).trim()
            }
            "-A", "--user-agent" -> opts.userAgent = take()
            "-M", "--mirror" -> opts.mirrors.add(take())
            "-p", "--proxy" -> opts.proxy = parseProxy(take())
            "--doh" -> opts.dohUrl = take().takeIf { it.isNotBlank() }
            "--http-policy" -> opts.httpPolicy = when (take().lowercase()) {
                "auto" -> HttpVersionPolicy.AUTO
                "http1", "h1", "force-http1" -> HttpVersionPolicy.FORCE_HTTP1
                "http2", "h2", "force-http2" -> HttpVersionPolicy.FORCE_HTTP2
                else -> usageError("--http-policy 仅支持 auto|http1|http2")
            }
            "--force-http1" -> opts.httpPolicy = HttpVersionPolicy.FORCE_HTTP1
            "--no-warmup" -> opts.warmUp = false
            "--slow-start" -> opts.slowStart = true
            "--no-slow-start" -> opts.slowStart = false
            "--adaptive" -> opts.adaptive = true
            "--no-adaptive" -> opts.adaptive = false
            "--adaptive-mode" -> opts.adaptiveMode = when (take().lowercase()) {
                "downward", "down" -> AdaptiveConcurrencyMode.DOWNWARD
                "upward", "up" -> AdaptiveConcurrencyMode.UPWARD
                else -> usageError("--adaptive-mode 仅支持 downward|upward")
            }
            "--spc" -> opts.segmentsPerConnection = take().toIntOrNull()?.coerceIn(1, 64)
                ?: usageError("--spc 需为 1..64 的整数")
            "--block-size" -> opts.blockSize = parseSize(take())
            "--min-segment" -> opts.minSegmentSize = parseSize(take())
            "--slow-start-initial" -> opts.slowStartInitial = take().toIntOrNull()?.coerceAtLeast(1)
                ?: usageError("--slow-start-initial 需为 >=1 的整数")
            "--no-tail-assist" -> opts.noTailAssist = true
            "--no-overlap" -> opts.noOverlap = true
            "--stall-timeout" -> opts.stallTimeoutMs = take().toLongOrNull()?.coerceAtLeast(0)
                ?: usageError("--stall-timeout 需为毫秒数")
            "--insecure" -> opts.insecure = true
            "--connect-timeout" -> opts.connectTimeoutMs = take().toLongOrNull()?.coerceAtLeast(1000)
                ?: usageError("--connect-timeout 需为毫秒数")
            "--read-timeout", "--timeout" -> opts.readTimeoutMs = take().toLongOrNull()?.coerceAtLeast(1000)
                ?: usageError("--timeout 需为毫秒数")
            "--json" -> opts.json = true
            "--timing" -> { opts.json = true; opts.timing = true }
            "-q", "--quiet" -> opts.quiet = true
            "--progress-interval" -> opts.progressIntervalMs = take().toLongOrNull()?.coerceAtLeast(100)
                ?: usageError("--progress-interval 需为毫秒数")
            else -> {
                // 第一个非选项参数 = URL；其余非选项参数视为错误
                if (!raw.startsWith("-") && tasks.isEmpty() && opts.batchFile == null) {
                    tasks.add(Task(raw, File("")))
                } else {
                    usageError("无法识别的参数：$raw")
                }
            }
        }
        i++
    }

    // ---------- 批量文件 ----------
    opts.batchFile?.let { f ->
        if (!f.isFile) usageError("批量文件不存在：$f")
        f.readLines().forEach { line ->
            val t = line.trim()
            if (t.isBlank() || t.startsWith("#")) return@forEach
            val parts = t.split(Regex("\\s+"), limit = 2)
            tasks.add(Task(parts[0], if (parts.size > 1) File(parts[1]) else File("")))
        }
    }
    if (tasks.isEmpty()) { printHelp(); return }

    // ---------- 解析输出路径 ----------
    val resolved = tasks.map { t ->
        val name = defaultFileName(t.url)
        val out = when {
            !t.output.path.isNullOrBlank() -> t.output
            opts.output != null && tasks.size == 1 -> opts.output!!
            else -> File(opts.dir ?: File("."), name)
        }
        Task(t.url, out)
    }

    if (!opts.json && !opts.quiet) {
        println(
            "TurboDL $BUILD_VERSION ｜ ${resolved.size} 个任务 ｜ " +
                "${opts.connections} 连接/任务 ｜ ${opts.jobs} 任务并发"
        )
    }

    // One client owns the connection/speed budgets and all batch tasks. Creating
    // a client per task made maxTotalConnections and the global speed limit
    // effectively per-task, which is not what the public CLI promises.
    val client = TurboClient(buildConfig(opts))

    // Ctrl+C：保留分片，提示可续传（正常完成时 downloading=false，不误报）
    val interrupted = AtomicBoolean(false)
    Runtime.getRuntime().addShutdownHook(Thread {
        if (downloading.get()) {
            interrupted.set(true)
            if (opts.json) println("\n{\"type\":\"interrupted\",\"hint\":\"重跑同一命令可从断点续传\"}")
            else if (!opts.quiet) println("\n已中断：重跑同一命令可从断点续传。")
        }
        // The hook may run while the main coroutine is still unwinding. shutdown()
        // is idempotent and releases sockets/dispatchers for both paths.
        runCatching { client.shutdown() }
    })

    downloading.set(true)
    val results = try {
        runBlocking {
            coroutineScope {
                resolved.mapIndexed { index, task ->
                    async {
                        if (interrupted.get()) {
                            false
                        } else {
                            if (resolved.size > 1 && !opts.json && !opts.quiet) {
                                println("--- [${index + 1}/${resolved.size}] ${task.url}")
                            }
                            runCatching {
                                downloadOne(client, task, opts, resolved.size)
                            }.getOrElse { e ->
                                if (System.getenv("TURBODL_DEBUG_STACK") == "1") e.printStackTrace()
                                if (opts.json) {
                                    println(
                                        """{"type":"failed","url":"${jsonEscForJson(task.url)}",""" +
                                            """"error":"${jsonEscForJson(e.message ?: e.javaClass.simpleName)}"}"""
                                    )
                                } else if (!opts.quiet) {
                                    System.err.println("下载失败：${e.message}")
                                }
                                false
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    } finally {
        downloading.set(false)
        runCatching { client.shutdown() }
        if (opts.timing) {
            println(
                """{"type":"timing","stage":"cli_shutdown",""" +
                    """"t_ms":${System.currentTimeMillis() - processTimingStart}}"""
            )
        }
    }

    val failed = results.count { !it }
    if (failed > 0) {
        if (opts.json) println("""{"type":"summary","total":${resolved.size},"failed":$failed}""")
        exitProcess(1)
    }
}

private fun buildConfig(opts: Options): TurboConfig = TurboConfig(
    maxConnectionsPerTask = opts.connections,
    maxConcurrentTasks = opts.jobs,
    maxTotalConnections = opts.maxTotalConnections,
    globalSpeedLimitBytesPerSec = opts.speedLimit,
    maxRetries = opts.retry,
    httpVersionPolicy = opts.httpPolicy,
    warmUpConnections = opts.warmUp,
    slowStart = opts.slowStart,
    dns = opts.dohUrl?.let { DnsMode.DoH(it) } ?: DnsMode.System,
    proxy = opts.proxy ?: ProxyMode.Direct,
    trustAllCerts = opts.insecure,
    connectTimeoutMs = opts.connectTimeoutMs,
    readTimeoutMs = opts.readTimeoutMs,
    // 调优旋钮：仅在显式给出时覆盖默认值
    segmentsPerConnection = opts.segmentsPerConnection ?: TurboConfig().segmentsPerConnection,
    blockSize = opts.blockSize ?: TurboConfig().blockSize,
    minSegmentSize = opts.minSegmentSize ?: TurboConfig().minSegmentSize,
    slowStartInitial = opts.slowStartInitial ?: TurboConfig().slowStartInitial,
    adaptiveConcurrency = opts.adaptive,
    adaptiveConcurrencyMode = opts.adaptiveMode,
    tailAssist = !opts.noTailAssist,
    overlapMerge = !opts.noOverlap,
    stallTimeoutMs = opts.stallTimeoutMs ?: TurboConfig().stallTimeoutMs,
)

/** 下载单个任务；返回是否成功。 */
private suspend fun downloadOne(
    client: TurboClient,
    task: Task,
    opts: Options,
    taskCount: Int,
): Boolean = coroutineScope {
    val out = task.output.absoluteFile
    out.parentFile?.mkdirs()

    // 断点续传：workDir 放输出旁 .turbodl-parts，stableKey = sha256(url|输出路径) 前 16 位
    val workDir = File(out.parentFile ?: File("."), ".turbodl-parts")
    val stableKey = "cli-" + sha16("${task.url}|${out.path}")

    val headers = opts.headers.toMutableMap()
    opts.userAgent?.let { headers.putIfAbsent("User-Agent", it) }

    // 【启动段诊断】把引擎自报的阶段耗时打到 stdout，供基准脚本归因（`--timing` 开启）。
    // 只读事件、不改行为；与下面的进度轮询并行运行。
    val timingStart = System.currentTimeMillis()
    val taskIdRef = AtomicLong(-1)
    val timingJob = if (opts.timing) {
        // UNDISPATCHED establishes the SharedFlow subscription before submit(),
        // closing the replay=0 startup-event race without another thread.
        launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { ev ->
                if (ev.taskId != taskIdRef.get()) return@collect
                when (ev) {
                    is dev.turbodl.core.TurboEvent.Metadata -> println(
                        """{"type":"timing","taskId":${ev.taskId},"stage":"metadata",""" +
                            """"t_ms":${System.currentTimeMillis() - timingStart},""" +
                            """"probe_ms":${ev.probeMs},"resume":"${jsonEscForJson(ev.resumeNote ?: "")}"}"""
                    )
                    is dev.turbodl.core.TurboEvent.StateChanged -> println(
                        """{"type":"timing","taskId":${ev.taskId},"stage":"state","state":"${ev.state}",""" +
                            """"t_ms":${System.currentTimeMillis() - timingStart}}"""
                    )
                    else -> Unit
                }
            }
        }
    } else null

    val id = client.submit(
        DownloadRequest(
            url = task.url,
            destination = out,
            headers = headers,
            mirrors = opts.mirrors.toList(),
            stableKey = stableKey,
            expectedSha256 = opts.expectedSha256,
            knownSize = opts.knownSize ?: -1L,
        ),
        workDir,
    )
    taskIdRef.set(id)
    if (opts.json) {
        println(
            """{"type":"start","taskId":$id,"url":"${jsonEscForJson(task.url)}",""" +
                """"output":"${jsonEscForJson(out.path)}"}"""
        )
    }

    // 进度输出：轮询 progress StateFlow，天然节流
    var lastPrint = 0L
    val result = try {
        while (true) {
            val p = client.progress.value[id]
            val now = System.currentTimeMillis()
            if (p != null && now - lastPrint >= opts.progressIntervalMs) {
                lastPrint = now
                printProgress(opts, p, id, if (taskCount > 1) "task-$id " else "")
            }
            if (p != null && p.state != dev.turbodl.core.TaskState.QUEUED &&
                p.state != dev.turbodl.core.TaskState.PROBING &&
                p.state != dev.turbodl.core.TaskState.DOWNLOADING &&
                p.state != dev.turbodl.core.TaskState.MERGING
            ) break
            delay(100)
        }
        client.await(id)
    } finally {
        timingJob?.cancel()
    }

    result.fold(
        onSuccess = { f ->
            if (opts.json) {
                val digest = sha256File(f)
                println(
                    """{"type":"completed","taskId":$id,"url":"${jsonEscForJson(task.url)}",""" +
                        """"file":"${jsonEscForJson(f.absolutePath)}","bytes":${f.length()},""" +
                        """"sha256":"$digest","stableKey":"$stableKey"}"""
                )
            } else if (!opts.quiet) {
                println("完成：${f.absolutePath}（${f.length()} 字节）")
            }
            true
        },
        onFailure = { e ->
            if (System.getenv("TURBODL_DEBUG_STACK") == "1") e.printStackTrace()
            if (opts.json) {
                println(
                    """{"type":"failed","taskId":$id,"url":"${jsonEscForJson(task.url)}",""" +
                        """"error":"${jsonEscForJson(e.message ?: e.javaClass.simpleName)}"}"""
                )
            } else if (!opts.quiet) {
                System.err.println("失败：${e.message}（重跑同一命令可从断点续传）")
            }
            false
        },
    )
}

/** 人类可读的单行进度（原地刷新）。 */
private fun printProgress(opts: Options, p: TaskProgress, taskId: Long, label: String = "") {
    if (opts.json) {
        println(
            """{"type":"progress","taskId":$taskId,"state":"${p.state}","downloaded":${p.downloadedBytes},"total":${p.totalBytes},""" +
                """"percent":${if (p.percent >= 0) p.percent else -1},"speed":${p.speedBytesPerSec},"connections":${p.activeConnections},"eta_ms":${p.etaMillis}}"""
        )
        return
    }
    if (opts.quiet) return
    val pct = if (p.percent >= 0) "${p.percent}%" else "--"
    val total = if (p.totalBytes > 0) formatBytes(p.totalBytes) else "?"
    val speed = formatBytes(p.speedBytesPerSec) + "/s"
    val eta = if (p.etaMillis > 0) " eta=${formatDuration(p.etaMillis)}" else ""
    print(
        "\r${if (label.isBlank()) "" else "[$label] "}" +
            "[$pct] ${formatBytes(p.downloadedBytes)}/$total  $speed  " +
            "conns=${p.activeConnections}$eta   "
    )
    if (p.state == dev.turbodl.core.TaskState.MERGING) print(" 合并中…")
}

// ---------- 工具函数 ----------

private fun usageError(msg: String): Nothing {
    System.err.println("错误：$msg\n")
    printHelp(System.err)
    exitProcess(2)
}

private fun printHelp(out: java.io.PrintStream = System.out) {
    out.print(
        """
        TurboDL $BUILD_VERSION —— 多线程下载器（Agent / 脚本友好）

        用法:
          turbodl [选项] <URL>
          turbodl --batch tasks.txt [选项]

        主要选项:
          -o, --output <路径>        输出文件（单任务时可用；默认取 URL 文件名）
          -d, --dir <目录>           输出目录（默认当前目录）
          -b, --batch <文件>         批量下载，每行「URL」或「URL 输出路径」，# 为注释
              --jobs <N>             批量同时下载的任务数（1..64，默认 1）
          -c, --connections <N>      每任务连接数（1..256，默认 16）
              --max-total-connections <N>  所有任务合计连接上限（0=不限）
              --sha256 <hex>             完成后校验 SHA-256（64 位十六进制）
              --known-size <字节>        已知精确文件大小时跳过独立探测 RTT
          -l, --limit <大小>         限速，如 10MB、500KB、1048576（字节）
          -r, --retry <N>            分片失败重试次数（默认 5）
          -H, --header "K: V"        附加请求头，可重复
          -A, --user-agent <UA>      覆盖 User-Agent
          -M, --mirror <URL>         同一文件的镜像来源（可重复）；引擎按实测速度择优并自动故障切换
          -p, --proxy <URL>          代理，如 http://127.0.0.1:7890 或 socks5://host:port
          --doh <URL>                DNS over HTTPS，如 https://dns.alidns.com/dns-query
          --http-policy <模式>       auto|http1|http2（默认 auto：分片走 h1、单流可协商 h2）
          --no-warmup                关闭连接预热
          --no-slow-start            关闭慢启动
          --no-adaptive              关闭吞吐自适应（A/B 对照用）
          --adaptive-mode <模式>     downward|upward（默认 downward：从用户并发上限起跑向下收敛）
          --insecure                 忽略 SSL 证书校验
          --connect-timeout <毫秒>   连接超时（默认 15000）
          --timeout <毫秒>           读取超时（默认 60000）
          --json                     输出 NDJSON 事件流（start/progress/completed/failed）
          -q, --quiet                静默
          --progress-interval <毫秒> 进度刷新间隔（默认 400）
          -V, --version              版本
          -h, --help                 帮助

        断点续传:
          中断后重跑同一命令自动继续（分片保存在输出旁 .turbodl-parts/，完成后自动清理）。

        退出码:
          0 成功 ｜ 1 下载失败 ｜ 2 用法错误
        """.trimIndent() + "\n"
    )
}

/** 从 URL 推导默认文件名（去查询串、URL 解码失败则原样）。 */
private fun defaultFileName(url: String): String =
    url.substringBefore('?').substringAfterLast('/')
        .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        .ifBlank { "download_${System.currentTimeMillis()}.bin" }

/** 解析 10MB / 500KB / 纯字节 为字节数。 */
private fun parseSize(s: String): Long {
    val t = s.trim().uppercase()
    val m = Regex("^([0-9.]+)\\s*(B|KB|MB|GB|K|M|G)?$").find(t)
        ?: usageError("无法解析大小：$s（示例：10MB / 500KB / 1048576）")
    val num = m.groupValues[1].toDouble()
    val mult = when (m.groupValues[2]) {
        "KB", "K" -> 1024L
        "MB", "M" -> 1024L * 1024
        "GB", "G" -> 1024L * 1024 * 1024
        else -> 1L
    }
    return (num * mult).toLong()
}

/** 解析 http://host:port / socks5://host:port / host:port 为 ProxyMode。 */
private fun parseProxy(s: String): ProxyMode {
    val t = s.trim()
    return when {
        t.startsWith("http://") -> {
            val rest = t.removePrefix("http://").trimEnd('/')
            val (h, p) = splitHostPort(rest) ?: usageError("代理格式应为 http://host:port")
            ProxyMode.Manual(ProxyType.HTTP, h, p)
        }
        t.startsWith("socks5://") || t.startsWith("socks://") -> {
            val rest = t.substringAfter("://").trimEnd('/')
            val (h, p) = splitHostPort(rest) ?: usageError("代理格式应为 socks5://host:port")
            ProxyMode.Manual(ProxyType.SOCKS, h, p)
        }
        else -> {
            val (h, p) = splitHostPort(t) ?: usageError("代理格式：http://host:port 或 socks5://host:port")
            ProxyMode.Manual(ProxyType.HTTP, h, p)
        }
    }
}

private fun splitHostPort(s: String): Pair<String, Int>? {
    val idx = s.lastIndexOf(':')
    if (idx <= 0) return null
    val host = s.substring(0, idx)
    val port = s.substring(idx + 1).toIntOrNull() ?: return null
    if (host.isBlank() || port !in 1..65535) return null
    return host to port
}

private fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2fGB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1fMB".format(b / 1048576.0)
    b >= 1L shl 10 -> "%.0fKB".format(b / 1024.0)
    else -> "${b}B"
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return when {
        s >= 3600 -> "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
        s >= 60 -> "%dm%02ds".format(s / 60, s % 60)
        else -> "${s}s"
    }
}

private fun sha16(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(16)

private fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1024 * 1024)
    FileInputStream(file).use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun jsonEscForJson(s: String): String =
    buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20 || c.code > 0x7e) {
                append("\\u%04x".format(c.code))
            } else {
                append(c)
            }
        }
    }

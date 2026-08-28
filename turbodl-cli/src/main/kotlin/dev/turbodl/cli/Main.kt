package dev.turbodl.cli

import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.DnsMode
import dev.turbodl.core.HttpVersionPolicy
import dev.turbodl.core.ProxyMode
import dev.turbodl.core.ProxyType
import dev.turbodl.core.TurboClient
import dev.turbodl.core.TurboConfig
import dev.turbodl.core.TaskProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
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
 */
private const val VERSION = "0.2.0-rc8"

/** 一条待下载任务（批量模式逐条构造）。 */
private class Task(val url: String, val output: File)

/** 当前是否有任务正在下载（供 shutdown hook 判断是否打印续传提示）。 */
private val downloading = AtomicBoolean(false)

/** 解析后的全部命令行选项。 */
private class Options {
    var connections = 16
    var speedLimit: Long = 0L            // 字节/秒，0=不限
    var retry = 5
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
    var insecure = false
    var connectTimeoutMs = 15_000L
    var readTimeoutMs = 60_000L
    var json = false
    var quiet = false
    var progressIntervalMs = 400L
}

fun main(args: Array<String>) {
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
            "-V", "--version" -> { println("turbodl $VERSION"); return }
            "-o", "--output" -> opts.output = File(take())
            "-d", "--dir" -> opts.dir = File(take())
            "-c", "--connections", "--threads" ->
                opts.connections = take().toIntOrNull()?.coerceIn(1, 256)
                    ?: usageError("--connections 需为 1..256 的整数")
            "-l", "--limit", "--speed-limit" ->
                opts.speedLimit = parseSize(take())
            "-r", "--retry" ->
                opts.retry = take().toIntOrNull()?.coerceIn(0, 50)
                    ?: usageError("--retry 需为 0..50 的整数")
            "-b", "--batch" -> opts.batchFile = File(take())
            "-H", "--header" -> {
                val h = take()
                val idx = h.indexOf(':')
                if (idx <= 0) usageError("--header 需为 \"Key: Value\" 形式")
                opts.headers[h.substring(0, idx).trim()] = h.substring(idx + 1).trim()
            }
            "-A", "--user-agent" -> opts.userAgent = take()
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
            "--no-slow-start" -> opts.slowStart = false
            "--insecure" -> opts.insecure = true
            "--connect-timeout" -> opts.connectTimeoutMs = take().toLongOrNull()?.coerceAtLeast(1000)
                ?: usageError("--connect-timeout 需为毫秒数")
            "--read-timeout", "--timeout" -> opts.readTimeoutMs = take().toLongOrNull()?.coerceAtLeast(1000)
                ?: usageError("--timeout 需为毫秒数")
            "--json" -> opts.json = true
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
        println("TurboDL $VERSION ｜ ${resolved.size} 个任务 ｜ ${opts.connections} 连接/任务")
    }

    // Ctrl+C：保留分片，提示可续传（正常完成时 downloading=false，不误报）
    val interrupted = AtomicBoolean(false)
    Runtime.getRuntime().addShutdownHook(Thread {
        if (downloading.get()) {
            interrupted.set(true)
            if (opts.json) println("\n{\"type\":\"interrupted\",\"hint\":\"重跑同一命令可从断点续传\"}")
            else if (!opts.quiet) println("\n已中断：重跑同一命令可从断点续传。")
        }
    })

    var failed = 0
    for ((index, task) in resolved.withIndex()) {
        if (interrupted.get()) break
        if (resolved.size > 1 && !opts.json && !opts.quiet) {
            println("--- [${index + 1}/${resolved.size}] ${task.url}")
        }
        val ok = runCatching { downloadOne(task, opts) }.getOrElse { e ->
            if (opts.json) println("""{"type":"failed","url":"${jsonEsc(task.url)}","error":"${jsonEsc(e.message ?: e.javaClass.simpleName)}"}""")
            else if (!opts.quiet) System.err.println("下载失败：${e.message}")
            false
        }
        if (!ok) failed++
    }

    if (failed > 0) {
        if (opts.json) println("""{"type":"summary","total":${resolved.size},"failed":$failed}""")
        exitProcess(1)
    }
}

/** 下载单个任务；返回是否成功。 */
private fun downloadOne(task: Task, opts: Options): Boolean = runBlocking {
    val out = task.output.absoluteFile
    out.parentFile?.mkdirs()

    // 断点续传：workDir 放输出旁 .turbodl-parts，stableKey = sha256(url|输出路径) 前 16 位
    val workDir = File(out.parentFile ?: File("."), ".turbodl-parts")
    val stableKey = "cli-" + sha16("${task.url}|${out.path}")

    val client = TurboClient(
        TurboConfig(
            maxConnectionsPerTask = opts.connections,
            maxConcurrentTasks = 1,
            globalSpeedLimitBytesPerSec = opts.speedLimit,
            maxRetries = opts.retry,
            httpVersionPolicy = opts.httpPolicy,
            warmUpConnections = opts.warmUp,
            slowStart = opts.slowStart,
            workDir = workDir,
            dns = opts.dohUrl?.let { DnsMode.DoH(it) } ?: DnsMode.System,
            proxy = opts.proxy ?: ProxyMode.Direct,
            trustAllCerts = opts.insecure,
            connectTimeoutMs = opts.connectTimeoutMs,
            readTimeoutMs = opts.readTimeoutMs,
        )
    )
    // 中断时立即释放，分片保留供下次续传
    java.lang.Runtime.getRuntime().addShutdownHook(Thread { runCatching { client.shutdown() } })

    val headers = opts.headers.toMutableMap()
    opts.userAgent?.let { headers.putIfAbsent("User-Agent", it) }

    downloading.set(true)

    val id = client.submit(
        DownloadRequest(
            url = task.url,
            destination = out,
            headers = headers,
            stableKey = stableKey,
        )
    )
    if (opts.json) {
        println("""{"type":"start","url":"${jsonEsc(task.url)}","output":"${jsonEsc(out.path)}"}""")
    }

    // 进度输出：轮询 progress StateFlow，天然节流
    var lastPrint = 0L
    val result = run {
        while (true) {
            val p = client.progress.value[id]
            val now = System.currentTimeMillis()
            if (p != null && now - lastPrint >= opts.progressIntervalMs) {
                lastPrint = now
                printProgress(opts, p)
            }
            if (p != null && p.state != dev.turbodl.core.TaskState.QUEUED &&
                p.state != dev.turbodl.core.TaskState.PROBING &&
                p.state != dev.turbodl.core.TaskState.DOWNLOADING &&
                p.state != dev.turbodl.core.TaskState.MERGING
            ) break
            delay(100)
        }
        client.await(id)
    }

    client.shutdown()
    downloading.set(false)

    result.fold(
        onSuccess = { f ->
            if (opts.json) {
                println("""{"type":"completed","url":"${jsonEsc(task.url)}","file":"${jsonEsc(f.absolutePath)}","bytes":${f.length()},"sha256":"$stableKey"}""")
            } else if (!opts.quiet) {
                println("完成：${f.absolutePath}（${f.length()} 字节）")
            }
            true
        },
        onFailure = { e ->
            if (opts.json) {
                println("""{"type":"failed","url":"${jsonEsc(task.url)}","error":"${jsonEsc(e.message ?: e.javaClass.simpleName)}"}""")
            } else if (!opts.quiet) {
                System.err.println("失败：${e.message}（重跑同一命令可从断点续传）")
            }
            false
        },
    )
}

/** 人类可读的单行进度（原地刷新）。 */
private fun printProgress(opts: Options, p: TaskProgress) {
    if (opts.json) {
        println(
            """{"type":"progress","state":"${p.state}","downloaded":${p.downloadedBytes},"total":${p.totalBytes},""" +
                """"percent":${if (p.percent >= 0) p.percent else -1},"speed":${p.speedBytesPerSec},"connections":${p.activeConnections},"eta_ms":${p.etaMillis}}"""
        )
        return
    }
    if (opts.quiet) return
    val pct = if (p.percent >= 0) "${p.percent}%" else "--"
    val total = if (p.totalBytes > 0) formatBytes(p.totalBytes) else "?"
    val speed = formatBytes(p.speedBytesPerSec) + "/s"
    val eta = if (p.etaMillis > 0) " eta=${formatDuration(p.etaMillis)}" else ""
    print("\r[$pct] ${formatBytes(p.downloadedBytes)}/$total  $speed  conns=${p.activeConnections}$eta   ")
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
        TurboDL $VERSION —— 多线程下载器（Agent / 脚本友好）

        用法:
          turbodl [选项] <URL>
          turbodl --batch tasks.txt [选项]

        主要选项:
          -o, --output <路径>        输出文件（单任务时可用；默认取 URL 文件名）
          -d, --dir <目录>           输出目录（默认当前目录）
          -b, --batch <文件>         批量下载，每行「URL」或「URL 输出路径」，# 为注释
          -c, --connections <N>      每任务连接数（1..256，默认 16）
          -l, --limit <大小>         限速，如 10MB、500KB、1048576（字节）
          -r, --retry <N>            分片失败重试次数（默认 5）
          -H, --header "K: V"        附加请求头，可重复
          -A, --user-agent <UA>      覆盖 User-Agent
          -p, --proxy <URL>          代理，如 http://127.0.0.1:7890 或 socks5://host:port
          --doh <URL>                DNS over HTTPS，如 https://dns.alidns.com/dns-query
          --http-policy <模式>       auto|http1|http2（默认 auto：分片走 h1、单流可协商 h2）
          --no-warmup                关闭连接预热
          --no-slow-start            关闭慢启动
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

private fun jsonEsc(s: String): String =
    buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

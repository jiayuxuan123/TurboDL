package dev.turbodl.cli

import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.TurboClient
import dev.turbodl.core.TurboConfig
import dev.turbodl.core.TurboEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * TurboDL 命令行示例。
 *
 * 用法：
 *   turbodl <url> [输出文件] [--threads N] [--limit BYTES_PER_SEC] [--insecure]
 */
fun main(args: Array<String>) = runBlocking {
    if (args.isEmpty() || args[0] in listOf("-h", "--help")) {
        println(
            """
            TurboDL - 多线程下载器 (SDK 示例 CLI)

            用法:
              turbodl <url> [输出文件] [选项]

            选项:
              --threads N     每任务连接数 (1..256, 默认 8)
              --limit BYTES   全局限速 字节/秒 (0=不限)
              --insecure      忽略 SSL 证书校验
              --no-dynamic    关闭动态分段
            """.trimIndent()
        )
        return@runBlocking
    }

    val url = args[0]
    var out = args.getOrNull(1)?.takeIf { !it.startsWith("--") }
        ?: url.substringAfterLast('/').substringBefore('?').ifBlank { "download.bin" }

    var threads = 8
    var limit = 0L
    var insecure = false
    var dynamic = true
    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--threads" -> { threads = args.getOrNull(++i)?.toIntOrNull() ?: 8 }
            "--limit" -> { limit = args.getOrNull(++i)?.toLongOrNull() ?: 0L }
            "--insecure" -> insecure = true
            "--no-dynamic" -> dynamic = false
        }
        i++
    }

    val client = TurboClient(
        TurboConfig(
            maxConnectionsPerTask = threads,
            globalSpeedLimitBytesPerSec = limit,
            trustAllCerts = insecure,
            dynamicSegmentation = dynamic,
        )
    )

    val eventJob = launch {
        client.events.collect { ev ->
            when (ev) {
                is TurboEvent.Progress -> {
                    val p = ev.progress
                    val pct = if (p.percent >= 0) "${p.percent}%" else "?"
                    val spd = "%.2f MB/s".format(p.speedBytesPerSec / 1048576.0)
                    print("\r[$pct] ${p.downloadedBytes}/${p.totalBytes}  $spd  conns=${p.activeConnections}    ")
                }
                is TurboEvent.Completed -> println("\n完成: ${ev.file} (${ev.totalBytes} 字节)")
                is TurboEvent.Failed -> println("\n失败: ${ev.reason}")
                else -> {}
            }
        }
    }

    val id = client.submit(DownloadRequest(url = url, destination = File(out)))
    val result = client.await(id)
    eventJob.cancel()
    client.shutdown()

    result.fold(
        onSuccess = { println("保存到: ${it.absolutePath}") },
        onFailure = { println("下载失败: ${it.message}"); kotlin.system.exitProcess(1) },
    )
}

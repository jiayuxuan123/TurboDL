package dev.turbodl.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import kotlin.system.exitProcess

private data class AllowedOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
)

private data class ClientKey(
    val connections: Int,
    val pieces: Int,
    val minimum: Long,
    val blockSize: Long,
    val proxy: ProxyMode,
)

private data class BenchDiagnostics(
    val timeToFirstByteMs: Number?,
    val peakActiveRequests: Number?,
    val receivedBytes: Number?,
)

/**
 * Persistent, line-oriented SDK benchmark host for an explicitly allowed origin.
 *
 * Start with `--origin https://host[:port]` or `--local-fixture`; the latter is
 * fixed to `http://127.0.0.1:8845`. Each stdin line is a TAB-separated request,
 * and each result is one flushed JSON line on stdout.
 */
fun main(args: Array<String>) {
    val origin = parseStartupOrigin(args) ?: exitProcess(2)
    runBlocking {
        serve(origin)
    }
}

private suspend fun serve(origin: AllowedOrigin) {
    val clients = LinkedHashMap<ClientKey, TurboClient>()
    val reader = System.`in`.bufferedReader(StandardCharsets.UTF_8)
    try {
        while (true) {
            val frame = reader.readLine() ?: break
            if (frame == "close") break

            val fields = frame.split('\t')
            val seq = fields.getOrNull(0)?.toLongOrNull()
            if (seq == null) {
                emitFailure(null, NumberFormatException())
                continue
            }

            var client: TurboClient? = null
            var taskId: Long? = null
            try {
                require(fields.size in 7..11)
                val url = fields[1]
                require(!url.isEmpty())
                origin.requireContains(url)

                require(fields[2].isNotEmpty())
                val output = File(fields[2])
                val size = fields[3].toLong().also { require(it > 0) }
                val connections = fields[4].toInt().also { require(it in 1..256) }
                val pieces = fields[5].toInt().also { require(it in 1..64) }
                val minimum = fields[6].toLong().also { require(it > 0) }
                val headers = decodeHeaders(fields.getOrNull(7))
                val proxy = parseProxy(fields.getOrNull(8))
                val blockSize = fields.getOrNull(9)?.toLong()?.also { require(it > 0) }
                    ?: (16L * 1024 * 1024)
                val observationMs = fields.getOrNull(10)?.toLong()?.also { require(it in 1_000..60_000) }

                val key = ClientKey(connections, pieces, minimum, blockSize, proxy)
                val selected = clients.getOrPut(key) {
                    TurboClient(
                        TurboConfig(
                            maxConnectionsPerTask = connections,
                            maxConcurrentTasks = 1,
                            slowStart = false,
                            warmUpConnections = false,
                            adaptiveConcurrency = true,
                            proxy = proxy,
                            segmentsPerConnection = pieces,
                            minSegmentSize = minimum,
                            blockSize = blockSize,
                            skipProbeWhenSizeKnown = true,
                        ),
                    )
                }
                client = selected

                val started = System.nanoTime()
                // TurboDL owns redirects and strips credential headers on cross-origin hops.
                val id = selected.submit(
                    DownloadRequest(
                        url = url,
                        destination = output,
                        headers = headers,
                        knownSize = size,
                        connectionsOverride = connections,
                    ),
                    File(output.parentFile ?: File("."), ".turbodl-network-range-parts"),
                )
                taskId = id
                val result = try {
                    withTimeout(observationMs ?: 180000) { selected.await(id) }
                } catch (timeout: TimeoutCancellationException) {
                    if (observationMs == null) throw timeout
                    selected.pause(id)
                    val received = selected.progress.value[id]?.downloadedBytes ?: 0L
                    check(received > 0)
                    emitSuccess(seq, (System.nanoTime() - started) / 1_000_000.0,
                        readDiagnostics(selected, id), partial = true, observedBytes = received)
                    continue
                }
                val downloaded = result.getOrThrow()
                check(downloaded.length() == size && output.length() == size)

                val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
                emitSuccess(seq, elapsedMs, readDiagnostics(selected, id))
            } catch (error: Exception) {
                val selected = client
                val id = taskId
                if (selected != null && id != null) {
                    runCatching { selected.cancel(id, false) }
                }
                emitFailure(seq, error)
            }
        }
    } finally {
        clients.values.forEach { client -> runCatching { client.shutdown() } }
    }
}

private fun parseStartupOrigin(args: Array<String>): AllowedOrigin? {
    if (args.size == 1 && args[0] == "--local-fixture") {
        return AllowedOrigin("http", "127.0.0.1", 8845)
    }
    if (args.size != 2 || args[0] != "--origin") return null
    return runCatching { parseHttpsOrigin(args[1]) }.getOrNull()
}

private fun parseHttpsOrigin(raw: String): AllowedOrigin {
    val url = raw.toHttpUrlOrNull() ?: error("invalid origin")
    require(url.isHttps)
    require(url.username.isEmpty() && url.password.isEmpty())
    require(url.query == null && url.fragment == null)
    require(url.encodedPath == "/")
    return AllowedOrigin("https", url.host, url.port)
}

private fun AllowedOrigin.requireContains(rawUrl: String) {
    val url = rawUrl.toHttpUrlOrNull() ?: error("invalid URL")
    require(url.scheme == scheme)
    require(url.host == host)
    require(url.port == port)
    require(url.username.isEmpty() && url.password.isEmpty())
}

private fun decodeHeaders(encoded: String?): Map<String, String> {
    if (encoded.isNullOrEmpty()) return emptyMap()

    val bytes = Base64.getDecoder().decode(encoded)
    val text = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    if (text.isEmpty()) return emptyMap()

    val parts = text.split('\u0000')
    require(parts.size % 2 == 0)

    val headers = LinkedHashMap<String, String>()
    for (index in parts.indices step 2) {
        val name = parts[index]
        val value = parts[index + 1]
        require(name.isNotEmpty())
        requireNoCrLf(name)
        requireNoCrLf(value)
        // A Host override would escape the origin gate at the HTTP layer.
        require(!name.equals("Host", ignoreCase = true))
        headers[name] = value
    }
    return headers
}

private fun parseProxy(raw: String?): ProxyMode {
    if (raw.isNullOrEmpty()) return ProxyMode.Direct

    val uri = URI(raw)
    require(!uri.isOpaque)
    require(uri.rawQuery == null && uri.rawFragment == null)
    require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")

    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val type: ProxyType
    val defaultPort: Int
    when (scheme) {
        "http" -> {
            type = ProxyType.HTTP
            defaultPort = 80
        }

        "socks", "socks4", "socks5" -> {
            type = ProxyType.SOCKS
            defaultPort = 1080
        }

        else -> error("unsupported proxy scheme")
    }

    val host = uri.host ?: error("missing proxy host")
    val port = if (uri.port >= 0) uri.port else defaultPort
    require(port in 1..65535)

    val userInfo = uri.userInfo
    requireNoCrLf(userInfo.orEmpty())
    val username = userInfo?.substringBefore(':')?.takeIf { it.isNotEmpty() }
    val password = userInfo
        ?.takeIf { it.contains(':') }
        ?.substringAfter(':')
        ?.takeIf { it.isNotEmpty() }
    return ProxyMode.Manual(type, host, port, username, password)
}

private fun requireNoCrLf(value: String) {
    require('\r' !in value && '\n' !in value)
}

private fun readDiagnostics(client: TurboClient, taskId: Long): BenchDiagnostics? {
    val taskDetailsGetter = client.javaClass.methods.firstOrNull { method ->
        method.name == "taskDetails" &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == java.lang.Long.TYPE
    } ?: return null
    val details = runCatching { taskDetailsGetter.invoke(client, taskId) }.getOrNull() ?: return null
    return BenchDiagnostics(
        timeToFirstByteMs = readNumberProperty(details, "getTimeToFirstByteMs"),
        peakActiveRequests = readNumberProperty(details, "getPeakActiveRequests"),
        receivedBytes = readNumberProperty(details, "getReceivedBytes"),
    )
}

private fun readNumberProperty(target: Any, getterName: String): Number? {
    val getter = target.javaClass.methods.firstOrNull { method ->
        method.name == getterName && method.parameterCount == 0
    } ?: return null
    return runCatching {
        getter.invoke(target) as? Number
    }.getOrNull()
}

private fun emitSuccess(seq: Long, engineMs: Double, diagnostics: BenchDiagnostics?,
    partial: Boolean = false, observedBytes: Long? = null) {
    val frame = buildString {
        append("{\"seq\":").append(seq)
        append(",\"ok\":true")
        append(",\"engine_ms\":").append(engineMs)
        append(",\"partial\":").append(partial)
        append(",\"observedBytes\":").append(observedBytes ?: "null")
        append(",\"timeToFirstByteMs\":").append(jsonNumber(diagnostics?.timeToFirstByteMs))
        append(",\"peakActiveRequests\":").append(jsonNumber(diagnostics?.peakActiveRequests))
        append(",\"receivedBytes\":").append(jsonNumber(diagnostics?.receivedBytes))
        append('}')
    }
    emitFrame(frame)
}

private fun emitFailure(seq: Long?, error: Exception) {
    val className = error.javaClass.simpleName.ifEmpty {
        error.javaClass.name.substringAfterLast('.')
    }
    emitFrame(
        "{\"seq\":${seq ?: "null"},\"ok\":false,\"exception\":\"${jsonEscape(className)}\"}",
    )
}

private fun jsonNumber(value: Number?): String = value?.toString() ?: "null"

private fun jsonEscape(value: String): String = buildString {
    for (character in value) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (character < ' ') {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
}

private fun emitFrame(frame: String) {
    System.out.println(frame)
    System.out.flush()
}

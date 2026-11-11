@file:JvmName("SourceRecoveryBench")

package dev.turbodl.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Controlled loopback benchmark for source recovery under real HTTP rejection.
 *
 * This is deliberately not a JUnit test and does not measure internet throughput.
 * The same compiled class works with the 0.5.0.2 API and with a candidate build:
 * legacy client/config/request types are the only static TurboDL dependencies,
 * while grouped backpressure policy construction and submission use reflection.
 */

private const val PAYLOAD_SIZE = 128 * 1024
private const val PAYLOAD_SEED = 20261111L
private const val MAX_ACTIVE_TRANSFERS = 2
private const val ACCEPT_DELAY_MS = 40L
private const val REQUEST_TIMEOUT_MS = 15_000L
private const val BACKPRESSURE_GROUP = "recovery-bench"
private const val ETAG = "\"turbodl-source-recovery-bench-v1\""
private const val TEMP_PREFIX = "turbodl-source-recovery-bench-"
private const val TASK_POLICY_CLASS = "dev.turbodl.core.TaskTransferPolicy"
private val traceRequests = System.getProperty("turbodl.bench.trace") == "1"
private val traceStarted = System.nanoTime()
private fun trace(message: String) {
    if (traceRequests) System.err.println("[source-bench] ${(System.nanoTime() - traceStarted) / 1_000_000}ms $message")
}

fun main(args: Array<String>) {
    val options = try {
        parseOptions(args)
    } catch (error: Throwable) {
        emitFailure(
            round = 0,
            grouped = args.any { it == "--grouped" },
            elapsedMs = 0.0,
            requests = 0,
            rejected429 = 0,
            peakActive = 0,
            error = error.benchmarkMessage(),
        )
        return
    }

    runBlocking {
        for (round in 1..options.rounds) {
            runRound(round, options.grouped)
        }
    }
}

private data class Options(
    val grouped: Boolean,
    val rounds: Int,
)

private fun parseOptions(args: Array<String>): Options {
    var grouped = false
    var rounds = 1
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--grouped" -> {
                grouped = true
                index++
            }

            "--rounds" -> {
                require(index + 1 < args.size) { "--rounds requires an integer" }
                rounds = args[index + 1].toIntOrNull()
                    ?: throw IllegalArgumentException("--rounds must be an integer")
                require(rounds >= 1) { "--rounds must be at least 1" }
                index += 2
            }

            else -> throw IllegalArgumentException("unknown argument: ${args[index]}")
        }
    }
    return Options(grouped = grouped, rounds = rounds)
}

private suspend fun runRound(round: Int, grouped: Boolean) {
    var client: TurboClient? = null
    var server: HttpServer? = null
    var executor: ExecutorService? = null
    var roundDir: Path? = null
    var transferStartedNs: Long? = null
    var hashMatched = false
    var rangeHandler: ControlledRangeHandler? = null

    try {
        val payload = fixedRandomPayload()
        val expectedHashA = payload.sha256Hex()
        val expectedHashB = payload.sha256Hex()

        val localRoundDir = Files.createTempDirectory(TEMP_PREFIX)
        roundDir = localRoundDir
        val taskADir = Files.createDirectories(localRoundDir.resolve("task-a"))
        val taskBDir = Files.createDirectories(localRoundDir.resolve("task-b"))
        val taskAWorkDir = Files.createDirectories(taskADir.resolve("work"))
        val taskBWorkDir = Files.createDirectories(taskBDir.resolve("work"))
        val taskAOutput = taskADir.resolve("payload.bin")
        val taskBOutput = taskBDir.resolve("payload.bin")

        val localHandler = ControlledRangeHandler(payload)
        rangeHandler = localHandler
        val localServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = localServer
        localServer.createContext("/payload.bin", localHandler)

        val localExecutor = Executors.newCachedThreadPool()
        executor = localExecutor
        localServer.executor = localExecutor
        localServer.start()

        val localClient = TurboClient(
            TurboConfig(
                maxConcurrentTasks = 2,
                maxConnectionsPerTask = 4,
                segmentsPerConnection = 1,
                minSegmentSize = 32L * 1024,
                blockSize = 32L * 1024,
                maxSegmentsPerTask = 4,
                slowStart = false,
                warmUpConnections = false,
                skipProbeWhenSizeKnown = true,
                adaptiveConcurrency = true,
                backpressureConsecutiveFailures = 3,
                maxRetries = 6,
            ),
        )
        client = localClient

        val url = "http://127.0.0.1:${localServer.address.port}/payload.bin"
        val requestA = DownloadRequest(
            url = url,
            destination = taskAOutput.toFile(),
            knownSize = payload.size.toLong(),
        )
        val requestB = DownloadRequest(
            url = url,
            destination = taskBOutput.toFile(),
            knownSize = payload.size.toLong(),
        )
        val groupedPolicy = if (grouped) buildGroupedPolicy() else null

        transferStartedNs = System.nanoTime()
        val taskAId = if (grouped) {
            submitWithPolicy(localClient, requestA, checkNotNull(groupedPolicy), taskAWorkDir.toFile())
        } else {
            localClient.submit(requestA, taskAWorkDir.toFile())
        }
        val taskBId = if (grouped) {
            submitWithPolicy(localClient, requestB, checkNotNull(groupedPolicy), taskBWorkDir.toFile())
        } else {
            localClient.submit(requestB, taskBWorkDir.toFile())
        }

        val (resultA, resultB) = withTimeout(REQUEST_TIMEOUT_MS) {
            coroutineScope {
                val awaitA = async { localClient.await(taskAId) }
                val awaitB = async { localClient.await(taskBId) }
                awaitA.await() to awaitB.await()
            }
        }

        val actualHashA = taskAOutput.toFile().sha256HexOrNull()
        val actualHashB = taskBOutput.toFile().sha256HexOrNull()
        hashMatched = actualHashA == expectedHashA &&
            actualHashB == expectedHashB &&
            taskAOutput.toFile().length() == payload.size.toLong() &&
            taskBOutput.toFile().length() == payload.size.toLong()
        val elapsedMs = elapsedMillis(transferStartedNs)

        val failures = mutableListOf<String>()
        resultA.exceptionOrNull()?.let { failures.add("task A: ${it.benchmarkMessage()}") }
        resultB.exceptionOrNull()?.let { failures.add("task B: ${it.benchmarkMessage()}") }
        if (failures.isNotEmpty()) {
            throw IllegalStateException(failures.joinToString("; "))
        }
        if (!hashMatched) {
            throw IllegalStateException(
                "independent SHA-256 check failed: " +
                    "taskA=${actualHashA ?: "missing"}, taskB=${actualHashB ?: "missing"}",
            )
        }

        emitSuccess(
            round = round,
            grouped = grouped,
            elapsedMs = elapsedMs,
            handler = checkNotNull(rangeHandler),
        )
    } catch (error: Throwable) {
        emitFailure(
            round = round,
            grouped = grouped,
            elapsedMs = transferStartedNs?.let(::elapsedMillis) ?: 0.0,
            requests = rangeHandler?.requests?.get() ?: 0L,
            rejected429 = rangeHandler?.rejected429?.get() ?: 0L,
            peakActive = rangeHandler?.peakActive?.get() ?: 0,
            hashMatched = hashMatched,
            error = error.benchmarkMessage(),
        )
    } finally {
        client?.let { runCatching { it.shutdown() } }
        server?.let { runCatching { it.stop(0) } }
        executor?.let {
            it.shutdownNow()
            runCatching { it.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) }
        }
        roundDir?.let(::deleteOwnedTempTree)
    }
}

private fun buildGroupedPolicy(): Any {
    val policyClass = Class.forName(TASK_POLICY_CLASS)
    val builder = policyClass.getMethod("builder").invoke(null)
    val configured = builder.javaClass
        .getMethod("backpressureGroup", String::class.java)
        .invoke(builder, BACKPRESSURE_GROUP)
    return configured.javaClass.getMethod("build").invoke(configured)
}

private fun submitWithPolicy(
    client: TurboClient,
    request: DownloadRequest,
    policy: Any,
    workDir: File,
): Long {
    val method = client.javaClass.methods.firstOrNull { candidate ->
        candidate.name == "submitWithPolicy" &&
            candidate.parameterTypes.size == 3 &&
            candidate.parameterTypes[0] == DownloadRequest::class.java &&
            candidate.parameterTypes[1].isInstance(policy) &&
            candidate.parameterTypes[2] == File::class.java
    } ?: throw UnsupportedOperationException(
        "candidate TurboClient has no submitWithPolicy(DownloadRequest, TaskTransferPolicy, File)",
    )
    return (method.invoke(client, request, policy, workDir) as Number).toLong()
}

private class ControlledRangeHandler(
    private val payload: ByteArray,
) : HttpHandler {
    val requests = AtomicLong(0)
    val rejected429 = AtomicLong(0)
    val peakActive = AtomicInteger(0)
    private val active = AtomicInteger(0)

    override fun handle(exchange: HttpExchange) {
        trace("request ${exchange.requestHeaders.getFirst("Range")}")
        requests.incrementAndGet()
        try {
            when (exchange.requestMethod.uppercase(Locale.ROOT)) {
                "HEAD" -> respondMetadata(exchange)
                "GET" -> respondGet(exchange)
                else -> {
                    exchange.responseHeaders.set("Allow", "GET, HEAD")
                    exchange.sendResponseHeaders(405, -1)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            runCatching { exchange.sendResponseHeaders(500, -1) }
        } finally {
            exchange.close()
        }
    }

    private fun respondMetadata(exchange: HttpExchange) {
        setPayloadHeaders(exchange)
        exchange.sendResponseHeaders(200, payload.size.toLong())
    }

    private fun respondGet(exchange: HttpExchange) {
        val ifRange = exchange.requestHeaders.getFirst("If-Range")
        val rangeHeader = if (ifRange == null || ifRange == ETAG) {
            exchange.requestHeaders.getFirst("Range")
        } else {
            null
        }

        val range = try {
            rangeHeader?.let { parseRange(it, payload.size) }
        } catch (_: IllegalArgumentException) {
            exchange.responseHeaders.set("Content-Range", "bytes */${payload.size}")
            exchange.sendResponseHeaders(416, -1)
            return
        }

        val start = range?.first ?: 0
        val end = range?.last ?: (payload.size - 1)
        val length = end - start + 1

        if (!acquireTransferSlot()) {
            trace("rejected 429 ${exchange.requestHeaders.getFirst("Range")}")
            rejected429.incrementAndGet()
            exchange.responseHeaders.set("Retry-After", "1")
            exchange.sendResponseHeaders(429, -1)
            return
        }

        try {
            trace("accepted $start-$end")
            Thread.sleep(ACCEPT_DELAY_MS)
            setPayloadHeaders(exchange)
            if (range == null) {
                exchange.sendResponseHeaders(200, length.toLong())
            } else {
                exchange.responseHeaders.set(
                    "Content-Range",
                    "bytes $start-$end/${payload.size}",
                )
                exchange.sendResponseHeaders(206, length.toLong())
            }
            exchange.responseBody.use { output ->
                output.write(payload, start, length)
            }
        } finally {
            active.decrementAndGet()
            trace("finished $start-$end")
        }
    }

    private fun acquireTransferSlot(): Boolean {
        while (true) {
            val current = active.get()
            if (current >= MAX_ACTIVE_TRANSFERS) return false
            if (active.compareAndSet(current, current + 1)) {
                peakActive.updateAndGet { peak -> maxOf(peak, current + 1) }
                return true
            }
        }
    }

    private fun setPayloadHeaders(exchange: HttpExchange) {
        exchange.responseHeaders.set("Accept-Ranges", "bytes")
        exchange.responseHeaders.set("ETag", ETAG)
        exchange.responseHeaders.set("Content-Type", "application/octet-stream")
    }
}

private fun parseRange(raw: String, total: Int): IntRange {
    require(raw.startsWith("bytes=", ignoreCase = true)) { "unsupported range unit" }
    val spec = raw.substringAfter('=').trim()
    require(spec.isNotEmpty() && !spec.contains(',')) { "expected one byte range" }
    val dash = spec.indexOf('-')
    require(dash > 0 || (dash == 0 && spec.length > 1)) { "invalid byte range" }

    val firstText = spec.substring(0, dash).trim()
    val lastText = spec.substring(dash + 1).trim()
    if (firstText.isEmpty()) {
        val suffixLength = lastText.toLongOrNull()
            ?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("invalid suffix range")
        val length = minOf(suffixLength, total.toLong()).toInt()
        return (total - length)..(total - 1)
    }

    val first = firstText.toLongOrNull()
        ?.takeIf { it in 0 until total.toLong() }
        ?: throw IllegalArgumentException("range start out of bounds")
    val last = if (lastText.isEmpty()) {
        (total - 1).toLong()
    } else {
        lastText.toLongOrNull()?.coerceAtMost((total - 1).toLong())
            ?: throw IllegalArgumentException("invalid range end")
    }
    require(last >= first) { "range end precedes range start" }
    return first.toInt()..last.toInt()
}

private fun fixedRandomPayload(): ByteArray =
    ByteArray(PAYLOAD_SIZE).also { Random(PAYLOAD_SEED).nextBytes(it) }

private fun ByteArray.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(this)
    return encodeDigestHex(digest.digest())
}

private fun File.sha256HexOrNull(): String? {
    if (!isFile) return null
    return runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        encodeDigestHex(digest.digest())
    }.getOrNull()
}

private fun encodeDigestHex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }
}

private fun elapsedMillis(startedNs: Long?): Double {
    if (startedNs == null) return 0.0
    return (System.nanoTime() - startedNs) / 1_000_000.0
}

private fun emitSuccess(
    round: Int,
    grouped: Boolean,
    elapsedMs: Double,
    handler: ControlledRangeHandler,
) {
    emitJson(
        round = round,
        grouped = grouped,
        elapsedMs = elapsedMs,
        requests = handler.requests.get(),
        rejected429 = handler.rejected429.get(),
        peakActive = handler.peakActive.get(),
        hashMatched = true,
        ok = true,
        error = null,
    )
}

private fun emitFailure(
    round: Int,
    grouped: Boolean,
    elapsedMs: Double,
    requests: Long,
    rejected429: Long,
    peakActive: Int,
    hashMatched: Boolean = false,
    error: String,
) {
    emitJson(
        round = round,
        grouped = grouped,
        elapsedMs = elapsedMs,
        requests = requests,
        rejected429 = rejected429,
        peakActive = peakActive,
        hashMatched = hashMatched,
        ok = false,
        error = error,
    )
}

private fun emitJson(
    round: Int,
    grouped: Boolean,
    elapsedMs: Double,
    requests: Long,
    rejected429: Long,
    peakActive: Int,
    hashMatched: Boolean,
    ok: Boolean,
    error: String?,
) {
    val renderedError = error?.let { ",\"error\":${it.jsonString()}" }.orEmpty()
    println(
        "{\"round\":$round" +
            ",\"grouped\":$grouped" +
            ",\"elapsed_ms\":${String.format(Locale.ROOT, "%.3f", elapsedMs)}" +
            ",\"rejected_429\":$rejected429" +
            ",\"requests\":$requests" +
            ",\"peak_active\":$peakActive" +
            ",\"hash_matched\":$hashMatched" +
            ",\"ok\":$ok" +
            renderedError +
            "}",
    )
}

private fun Throwable.benchmarkMessage(): String {
    val root = if (this is InvocationTargetException) targetException ?: this else this
    val detail = root.message?.takeIf { it.isNotBlank() } ?: "no message"
    return "${root.javaClass.simpleName}: $detail"
}

private fun String.jsonString(): String = buildString(length + 2) {
    append('"')
    for (character in this@jsonString) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (character.toInt() < 0x20) {
                    append("\\u")
                    append(character.toInt().toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
    append('"')
}

private fun deleteOwnedTempTree(root: Path) {
    runCatching {
        val tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val target = root.toAbsolutePath().normalize()
        require(target.startsWith(tempRoot))
        require(target.fileName?.toString()?.startsWith(TEMP_PREFIX) == true)

        val paths = Files.walk(target).use { stream ->
            stream.iterator().asSequence().toList()
        }
        for (path in paths.asReversed()) {
            runCatching { Files.deleteIfExists(path) }
        }
    }
}

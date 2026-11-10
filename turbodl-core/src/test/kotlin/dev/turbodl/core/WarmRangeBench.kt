package dev.turbodl.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/** A persistent SDK host for the local comparison; never counted as a unit test. */
fun main() = runBlocking {
    val clients = mutableMapOf<Triple<Int, Int, Long>, TurboClient>()
    try {
        val reader = System.`in`.bufferedReader()
        while (true) {
            val frame = reader.readLine() ?: break
            if (frame == "close") break
            val fields = frame.split('\t')
            val seq = fields[0].toLong()
            try {
                require(fields.size in 5..7)
                val url = fields[1]
                require(url.startsWith("http://127.0.0.1:8845/"))
                val output = File(fields[2])
                val size = fields[3].toLong().also { require(it > 0) }
                val connections = fields[4].toInt().also { require(it in 1..256) }
                val pieces = fields.getOrNull(5)?.toInt()?.also { require(it in 1..64) } ?: 4
                val minimum = fields.getOrNull(6)?.toLong()?.also { require(it > 0) } ?: (64L * 1024)
                val client = clients.getOrPut(Triple(connections, pieces, minimum)) {
                    TurboClient(TurboConfig(
                        maxConcurrentTasks = 1, maxConnectionsPerTask = connections,
                        slowStart = false, warmUpConnections = false,
                        adaptiveConcurrency = true, proxy = ProxyMode.Direct,
                        segmentsPerConnection = pieces,
                        minSegmentSize = minimum,
                    ))
                }
                val started = System.nanoTime()
                val id = client.submit(DownloadRequest(url, output, knownSize = size), File(output.parentFile, "parts"))
                val result = withTimeout(60_000) { client.await(id) }
                check(result.isSuccess && output.length() == size)
                val elapsed = (System.nanoTime() - started) / 1e6
                println("{\"seq\":$seq,\"engine_ms\":$elapsed,\"ok\":true}")
            } catch (_: Exception) {
                println("{\"seq\":$seq,\"ok\":false}")
            }
        }
    } finally {
        clients.values.forEach { it.shutdown() }
    }
}

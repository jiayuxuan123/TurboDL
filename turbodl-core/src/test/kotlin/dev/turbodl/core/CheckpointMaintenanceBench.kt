package dev.turbodl.core

import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random

/** Explicit measurement entry point; not a passing/no-op JUnit test. */
fun main(args: Array<String>) {
    val rounds = args.firstOrNull()?.toInt() ?: 5
    require(rounds in 1..9)
    for (count in listOf(64, 256, 1024)) {
        val root = Files.createTempDirectory("tdl-checkpoint-bench-").toFile()
        val output = File(root, "payload.bin")
        val state = File(root, "progress.tdlp")
        val payload = ByteArray(count * 4096).also { Random(9047).nextBytes(it) }
        val expected = MessageDigest.getInstance("SHA-256").digest(payload)
        val store = SegmentStore(output, state, payload.size.toLong())
        try {
            store.open()
            store.ensureLength()
            store.writeAt(0, payload, 0, payload.size)
            val pieces = store.buildGrid(4096).onEach { it.written = it.length }
            repeat(2) { store.persist(pieces) }
            val samples = List(rounds) {
                val started = System.nanoTime()
                store.persist(pieces)
                val ms = (System.nanoTime() - started) / 1e6
                // An independent reader checks the published v2 byte format.
                DataInputStream(state.inputStream().buffered()).use { input ->
                    val magic = ByteArray(4).also(input::readFully)
                    check(magic.contentEquals(byteArrayOf(0x54, 0x44, 0x4c, 0x50)))
                    check(input.readInt() == 2)
                    val identity = ByteArray(input.readInt()).also(input::readFully)
                    check(identity.isNotEmpty())
                    check(input.readLong() == payload.size.toLong())
                    check(input.readInt() == count)
                    repeat(count) { index ->
                        check(input.readLong() == index * 4096L)
                        check(input.readLong() == (index + 1) * 4096L - 1)
                        check(input.readLong() == 4096L)
                    }
                    check(input.read() == -1)
                }
                check(MessageDigest.getInstance("SHA-256").digest(output.readBytes()).contentEquals(expected))
                ms
            }
            println("{\"pieces\":$count,\"samples_ms\":${samples.joinToString(prefix = "[", postfix = "]")},\"sha256_matched\":true,\"v2_format_matched\":true}")
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }
}

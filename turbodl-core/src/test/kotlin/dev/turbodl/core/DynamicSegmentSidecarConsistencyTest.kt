package dev.turbodl.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DynamicSegmentSidecarConsistencyTest {

    private fun tempDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "tdlp-$tag-${System.nanoTime()}")
            .apply { mkdirs(); deleteOnExit() }

    private fun store(dir: File, total: Long) = SegmentStore(
        dest = File(dir, "out.bin"),
        stateFile = File(dir, "progress.tdlp"),
        total = total,
    )

    @Test
    fun `tail-appended grid remains readable after persist`() {
        val dir = tempDir("tail-sidecar")
        val writer = store(dir, total = 512)
        writer.open()
        writer.ensureLength()

        val head = SegmentStore.Piece(0, 127)
        val middle = SegmentStore.Piece(256, 511)
        val tail = SegmentStore.Piece(128, 255)

        // This is the order produced when a scheduler appends a tail split to
        // the end of its existing grid: [0..127, 256..511, 128..255].
        writer.persist(listOf(head, middle, tail))
        writer.close()

        val reader = store(dir, total = 512)
        reader.open()
        try {
            val loaded = assertNotNull(
                reader.peek(512),
                "a complete grid must remain resumable regardless of insertion order",
            )
            assertEquals(listOf(0L, 128L, 256L), loaded.map { it.start })
        } finally {
            reader.close()
        }
    }

    @Test
    fun `transient incomplete grid does not replace last durable snapshot`() {
        val dir = tempDir("transient-sidecar")
        val stateFile = File(dir, "progress.tdlp")
        val writer = store(dir, total = 512)
        writer.open()
        writer.ensureLength()

        val first = SegmentStore.Piece(0, 127, written = 128)
        val middle = SegmentStore.Piece(128, 255, written = 128)
        val last = SegmentStore.Piece(256, 511)
        writer.persist(listOf(first, middle, last))
        val durable = stateFile.readBytes()

        // A snapshot taken between shortening a segment and appending its tail
        // can temporarily contain a hole. It must not clobber the last good grid.
        writer.persist(listOf(first, last))
        assertEquals(
            durable.toList(),
            stateFile.readBytes().toList(),
            "an incomplete transient snapshot must be ignored",
        )
        writer.close()

        val reader = store(dir, total = 512)
        reader.open()
        try {
            assertNotNull(reader.peek(512), "the last durable snapshot must remain resumable")
        } finally {
            reader.close()
        }
    }
}

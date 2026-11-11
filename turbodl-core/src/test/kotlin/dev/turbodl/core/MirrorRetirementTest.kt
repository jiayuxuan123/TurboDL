package dev.turbodl.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MirrorRetirementTest {
    @Test fun `late successful response cannot restore a mismatched source`() {
        val source = "https://source.example/file"
        val pool = MirrorPool(listOf(source))
        assertEquals(source, pool.pickWithLimit())
        assertEquals(source, pool.pickWithLimit())
        pool.report(source, ok = false, bytes = 0, elapsedMs = 1, structuralFailure = true)
        pool.report(source, ok = true, bytes = 1024, elapsedMs = 1)
        assertFalse(pool.hasUsableSources())
        assertEquals(null, pool.pickWithLimit())
    }

    @Test fun `retiring a slow unavailable source preserves the healthy mirror`() {
        val slow = "https://slow.example/file"
        val healthy = "https://healthy.example/file"
        val pool = MirrorPool(listOf(slow, healthy))
        pool.retireForTask(slow)
        repeat(8) {
            assertEquals(healthy, pool.pickWithLimit())
            pool.report(healthy, ok = true, bytes = 1024, elapsedMs = 1)
        }
        pool.retireForTask(healthy)
        assertFalse(pool.hasUsableSources())
        assertEquals(null, pool.pickWithLimit())
    }
}

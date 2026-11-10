package dev.turbodl.core

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertEquals

class TailAssistPolicyTest {
    @Test fun `fast tail costs more to restart than to finish`() {
        assertNull(TailAssistPolicy.usefulEtaSeconds(32 * 1024, 64.0 * 1024 * 1024, 200_000_000, 5_000_000))
    }

    @Test fun `slow measured tail can use a free connection`() {
        assertNotNull(TailAssistPolicy.usefulEtaSeconds(32 * 1024, 32.0 * 1024, 200_000_000, 20_000_000))
    }

    @Test fun `handshake latency is not evidence of a slow established transfer`() {
        assertNull(TailAssistPolicy.usefulEtaSeconds(128 * 1024, 256.0 * 1024, 200_000_000, 500_000_000))
    }

    @Test fun `a first sample is not enough to donate a range`() {
        assertNull(TailAssistPolicy.usefulEtaSeconds(1024 * 1024, 32.0 * 1024, 0, 1_000_000))
    }

    @Test fun `unknown empty and invalid measurements cannot trigger splitting`() {
        for (speed in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(TailAssistPolicy.usefulEtaSeconds(1024 * 1024, speed, 1_000_000_000, 0))
        }
        assertNull(TailAssistPolicy.usefulEtaSeconds(0, 1024.0, 1_000_000_000, 0))
    }

    @Test fun `buffered bursts retain the actual byte per time window`() {
        val metrics = TailTransferMetrics().apply { begin(0) }
        metrics.record(8192, 10_000_000)
        repeat(7) { metrics.record(8192, 10_000_000L + it * 100) }
        metrics.record(8192, 60_000_000)
        // 64 KiB over 50 ms, rather than the speed of the seven immediate reads.
        assertNotNull(metrics.usefulEtaSeconds(1024 * 1024, 60_000_000))
    }

    @Test fun `a body that stalls after a fast window can still receive help`() {
        val metrics = TailTransferMetrics().apply { begin(0) }
        metrics.record(8192, 10_000_000)
        metrics.record(512 * 1024, 60_000_000)
        assertNull(metrics.usefulEtaSeconds(128 * 1024, 61_000_000))
        assertNotNull(metrics.usefulEtaSeconds(128 * 1024, 2_000_000_000))
    }

    @Test fun `a retry cannot inherit the previous attempt speed`() {
        val metrics = TailTransferMetrics().apply { begin(0) }
        metrics.record(8192, 10_000_000)
        metrics.record(8192, 100_000_000)
        assertNotNull(metrics.usefulEtaSeconds(1024 * 1024, 100_000_000))
        metrics.begin(200_000_000)
        assertNull(metrics.usefulEtaSeconds(1024 * 1024, 2_000_000_000))
    }

    @Test fun `small slow tails do not wait for a complete rate window`() {
        val metrics = TailTransferMetrics().apply { begin(0) }
        metrics.record(1024, 30_000_000)
        assertNotNull(metrics.usefulEtaSeconds(23 * 1024, 60_000_000))
    }

    @Test fun `slow first byte with an already buffered body does not justify a new request`() {
        val metrics = TailTransferMetrics().apply { begin(0) }
        metrics.record(8192, 150_000_000)
        assertNull(metrics.usefulEtaSeconds(16 * 1024, 155_000_000))
    }

    @Test fun `a partial first read after a delayed response cannot trigger an immediate duplicate`() {
        val metrics = TailTransferMetrics().apply { begin(0) }
        metrics.record(8192, 240_000_000)
        assertNull(metrics.usefulEtaSeconds(128 * 1024, 242_000_000))
    }

    @Test fun `faster helper takes enough bytes for both peers to finish together`() {
        assertEquals(400, TailAssistPolicy.retainedRemaining(1600, 100.0, 300.0, 0, 1))
        assertEquals(900, TailAssistPolicy.retainedRemaining(1600, 100.0, 100.0, 2_000_000_000, 1))
    }

    @Test fun `unknown helper retains the conservative half split`() {
        assertEquals(800, TailAssistPolicy.retainedRemaining(1600, 100.0, 0.0, 0, 1))
    }

    @Test fun `helper startup that outlasts the current transfer is rejected`() {
        assertNull(TailAssistPolicy.retainedRemaining(1600, 100.0, 300.0, 16_000_000_000, 1))
    }

    @Test fun `weighted handoff honors both minimum ranges without overflow`() {
        assertEquals(8192, TailAssistPolicy.retainedRemaining(1_048_576, 100.0, 1e12, 0, 8192))
        assertNull(TailAssistPolicy.retainedRemaining(1_048_576, 1e12, 100.0, 0, 8192))
        assertNull(TailAssistPolicy.retainedRemaining(1_048_576, 100.0, 300.0, 0, Long.MAX_VALUE))
        assertNull(TailAssistPolicy.retainedRemaining(1600, Double.NaN, 300.0, 0, 1))
    }

    @Test fun `a barely useful slower helper cannot add another range`() {
        assertNull(TailAssistPolicy.retainedRemaining(1600, 100.0, 1.0, 0, 1))
    }
}

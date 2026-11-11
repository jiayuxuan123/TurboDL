package dev.turbodl.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskTransferPolicyTest {
    private fun baseConfig(): TurboConfig = TurboConfig(
        maxConnectionsPerTask = 32,
        maxConcurrentTasks = 2,
        minSegmentSize = 128L * 1024,
        blockSize = 8L * 1024 * 1024,
        segmentsPerConnection = 6,
        slowStart = false,
        slowStartInitial = 3,
        adaptiveConcurrencyMode = AdaptiveConcurrencyMode.UPWARD,
        maxConnectionsPerHost = 5,
    )

    @Test
    fun `empty policy inherits every configurable value and leaves the group unset`() {
        val base = baseConfig()
        val snapshot = TaskTransferPolicy.builder().build().snapshot(base)

        assertEquals(base.minSegmentSize, snapshot.minSegmentSize)
        assertEquals(base.blockSize, snapshot.blockSize)
        assertEquals(base.segmentsPerConnection, snapshot.segmentsPerConnection)
        assertEquals(base.slowStart, snapshot.slowStart)
        assertEquals(base.slowStartInitial, snapshot.slowStartInitial)
        assertEquals(base.adaptiveConcurrencyMode, snapshot.adaptiveConcurrencyMode)
        assertEquals(base.maxConnectionsPerHost, snapshot.maxConnectionsPerHost)
        assertNull(snapshot.backpressureGroup)
    }

    @Test
    fun `snapshot stays fixed after the base configuration changes`() {
        val original = baseConfig()
        val snapshot = TaskTransferPolicy.builder().build().snapshot(original)
        val changed = original.copy(
            minSegmentSize = 512L * 1024,
            blockSize = 2L * 1024 * 1024,
            segmentsPerConnection = 12,
            slowStart = true,
            slowStartInitial = 7,
            adaptiveConcurrencyMode = AdaptiveConcurrencyMode.DOWNWARD,
            maxConnectionsPerHost = 11,
        )

        val effective = snapshot.effectiveConfig(changed)

        assertEquals(original.minSegmentSize, effective.minSegmentSize)
        assertEquals(original.blockSize, effective.blockSize)
        assertEquals(original.segmentsPerConnection, effective.segmentsPerConnection)
        assertEquals(original.slowStart, effective.slowStart)
        assertEquals(original.slowStartInitial, effective.slowStartInitial)
        assertEquals(original.adaptiveConcurrencyMode, effective.adaptiveConcurrencyMode)
        assertEquals(original.maxConnectionsPerHost, effective.maxConnectionsPerHost)
    }

    @Test
    fun `different policies remain isolated`() {
        val firstBase = baseConfig()
        val secondBase = baseConfig().copy(
            minSegmentSize = 256L * 1024,
            segmentsPerConnection = 9,
            slowStart = true,
        )
        val first = TaskTransferPolicy.builder()
            .minSegmentSize(16L * 1024)
            .segmentsPerConnection(2)
            .backpressureGroup("first")
            .build()
            .snapshot(firstBase)
        val second = TaskTransferPolicy.builder()
            .minSegmentSize(64L * 1024)
            .segmentsPerConnection(5)
            .backpressureGroup("second")
            .build()
            .snapshot(secondBase)

        assertEquals(16L * 1024, first.minSegmentSize)
        assertEquals(64L * 1024, second.minSegmentSize)
        assertEquals(2, first.segmentsPerConnection)
        assertEquals(5, second.segmentsPerConnection)
        assertEquals("first", first.backpressureGroup)
        assertEquals("second", second.backpressureGroup)
        assertEquals(16L * 1024, first.effectiveConfig(secondBase).minSegmentSize)
        assertEquals(64L * 1024, second.effectiveConfig(firstBase).minSegmentSize)
    }

    @Test
    fun `effective config preserves global budgets and unrelated settings`() {
        val base = baseConfig().copy(
            maxTotalConnections = 37,
            globalSpeedLimitBytesPerSec = 1_234_567,
            ioBufferSize = 64 * 1024,
            ioBufferTotalBudgetBytes = 256 * 1024,
            maxConcurrentTasks = 4,
        )
        val policy = TaskTransferPolicy.builder()
            .minSegmentSize(16L * 1024)
            .blockSize(1024L * 1024)
            .segmentsPerConnection(3)
            .slowStart(true)
            .slowStartInitial(2)
            .adaptiveConcurrencyMode(AdaptiveConcurrencyMode.DOWNWARD)
            .maxConnectionsPerHost(4)
            .build()

        val effective = policy.effectiveConfig(base)

        assertEquals(16L * 1024, effective.minSegmentSize)
        assertEquals(1024L * 1024, effective.blockSize)
        assertEquals(3, effective.segmentsPerConnection)
        assertEquals(true, effective.slowStart)
        assertEquals(2, effective.slowStartInitial)
        assertEquals(AdaptiveConcurrencyMode.DOWNWARD, effective.adaptiveConcurrencyMode)
        assertEquals(4, effective.maxConnectionsPerHost)
        assertEquals(base.maxTotalConnections, effective.maxTotalConnections)
        assertEquals(base.globalSpeedLimitBytesPerSec, effective.globalSpeedLimitBytesPerSec)
        assertEquals(base.ioBufferSize, effective.ioBufferSize)
        assertEquals(base.ioBufferTotalBudgetBytes, effective.ioBufferTotalBudgetBytes)
        assertEquals(base.maxConcurrentTasks, effective.maxConcurrentTasks)
    }

    @Test
    fun `base adaptive concurrency setting is preserved`() {
        val policy = TaskTransferPolicy.builder().build()
        val disabled = policy.effectiveConfig(TurboConfig(adaptiveConcurrency = false))
        val enabled = policy.effectiveConfig(TurboConfig(adaptiveConcurrency = true))

        assertFalse(disabled.adaptiveConcurrency)
        assertTrue(enabled.adaptiveConcurrency)
    }

    @Test
    fun `invalid numeric values and groups are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            TaskTransferPolicy.builder().minSegmentSize(4L * 1024 - 1).build()
        }
        assertFailsWith<IllegalArgumentException> {
            TaskTransferPolicy.builder().segmentsPerConnection(0).build()
        }
        assertFailsWith<IllegalArgumentException> {
            TaskTransferPolicy.builder().segmentsPerConnection(65).build()
        }
        assertFailsWith<IllegalArgumentException> {
            TaskTransferPolicy.builder().slowStartInitial(-1).build()
        }
        assertFailsWith<IllegalArgumentException> {
            TaskTransferPolicy.builder().maxConnectionsPerHost(-1).build()
        }
        assertFailsWith<IllegalArgumentException> {
            TaskTransferPolicy.builder().backpressureGroup("").build()
        }
        assertFailsWith<IllegalArgumentException> {
            TaskTransferPolicy.builder().backpressureGroup("   ").build()
        }
        assertFailsWith<IllegalArgumentException> {
            TaskTransferPolicy.builder().backpressureGroup("x".repeat(129)).build()
        }
        assertFailsWith<IllegalArgumentException> {
            TaskTransferPolicy.builder().backpressureGroup("group\nname").build()
        }
    }

    @Test
    fun `builder mutations do not change an already built policy`() {
        val builder = TaskTransferPolicy.builder()
            .minSegmentSize(16L * 1024)
            .segmentsPerConnection(2)
            .slowStart(false)
            .backpressureGroup("original")
        val built = builder.build()

        builder
            .minSegmentSize(32L * 1024)
            .segmentsPerConnection(8)
            .slowStart(true)
            .backpressureGroup("changed")

        assertEquals(16L * 1024, built.minSegmentSize)
        assertEquals(2, built.segmentsPerConnection)
        assertEquals(false, built.slowStart)
        assertEquals("original", built.backpressureGroup)
    }
}

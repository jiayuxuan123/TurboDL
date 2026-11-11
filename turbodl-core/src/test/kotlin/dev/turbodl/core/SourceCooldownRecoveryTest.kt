package dev.turbodl.core

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SourceCooldownRecoveryTest {
    private val url = "https://source.example/file"

    @Test fun `recovery uses task ceiling rather than combined task fanout`() = runBlocking {
        val gate = SourceCooldowns()
        val initial = List(8) { gate.acquire(url, "account") }
        gate.defer(url, "account", 40, requestedConcurrency = 4)
        initial.take(2).forEach { it.release(true) }
        initial.drop(2).forEach { it.release(false) }
        val first = withTimeout(1_000) { gate.acquire(url, "account") }
        val second = withTimeout(1_000) { gate.acquire(url, "account") }
        val third = async { gate.acquire(url, "account") }
        try {
            delay(25)
            assertFalse(third.isCompleted)
            first.release(true)
            withTimeout(1_000) { third.await() }.release(true)
        } finally {
            third.cancelAndJoin()
            second.release()
            first.release()
        }
    }

    @Test fun `recovery admits one request and canceled waiters do not block successors`() = runBlocking {
        val gate = SourceCooldowns()
        gate.defer(url, "account", 20)
        val first = withTimeout(1_000) { gate.acquire(url, "account") }
        val canceled = async { gate.acquire(url, "account") }
        delay(25)
        assertFalse(canceled.isCompleted)
        canceled.cancelAndJoin()
        val next = async { gate.acquire(url, "account") }
        delay(25)
        assertFalse(next.isCompleted)
        first.release(true)
        first.release(true)
        withTimeout(1_000) { next.await() }.release(true)
    }

    @Test fun `disabled concurrency backpressure preserves parallel admission`() = runBlocking {
        val gate = SourceCooldowns()
        gate.defer(url, "account", 20, recoverConcurrency = false)
        val first = withTimeout(1_000) { gate.acquire(url, "account", recoverConcurrency = false) }
        val second = withTimeout(1_000) { gate.acquire(url, "account", recoverConcurrency = false) }
        second.release()
        first.release()
    }

    @Test fun `long server wait ends automatic retry without shortening the hint`() = runBlocking {
        val gate = SourceCooldowns()
        assertTrue(gate.defer(url, "account", 300_000))
        assertFailsWith<SourceCooldownDeferred> { gate.await(url, "account") }
        assertFailsWith<SourceCooldownDeferred> { gate.acquire(url, "account") }
        withTimeout(500) { gate.acquire(url, "other-account") }.release()
    }
}

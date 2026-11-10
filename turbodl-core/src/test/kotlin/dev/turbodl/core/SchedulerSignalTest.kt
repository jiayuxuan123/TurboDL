package dev.turbodl.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SchedulerSignalTest {
    @Test fun `notification between inspection and suspension cannot be lost`() = runTest {
        val signal = SchedulerSignal()
        val ticket = signal.observe()
        signal.signal()
        signal.await(ticket, 80)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `final drain wakes all idle workers immediately`() = runTest {
        val signal = SchedulerSignal()
        val jobs = List(256) {
            val ticket = signal.observe()
            async(start = CoroutineStart.UNDISPATCHED) { signal.await(ticket, 80) }
        }
        signal.signal()
        jobs.awaitAll()
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `timeouts and cancellation do not cancel a shared notification`() = runTest {
        val signal = SchedulerSignal()
        val ticket = signal.observe()
        val canceled = async(start = CoroutineStart.UNDISPATCHED) { signal.await(ticket, 80) }
        canceled.cancelAndJoin()
        signal.await(ticket, 30)
        assertFalse(ticket.isCompleted)
        signal.signal()
        signal.await(ticket, 80)
        assertEquals(30L, testScheduler.currentTime)
    }

    @Test fun `an old drain does not wake a new generation`() = runTest {
        val signal = SchedulerSignal()
        signal.observe()
        signal.signal()
        signal.await(signal.observe(), 30)
        assertEquals(30L, testScheduler.currentTime)
    }
}

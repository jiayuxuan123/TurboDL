package dev.turbodl.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionBudgetTest {

    @Test
    fun `partial grants stay inside the global limit and wake FIFO waiters`() = runBlocking {
        val limit = AtomicInteger(8)
        val budget = ConnectionBudget { limit.get() }
        val first = budget.acquire(5)
        assertEquals(5, first.permits)
        val second = budget.acquire(5)
        assertEquals(3, second.permits, "剩余预算应给第二个任务 3 条连接")

        val thirdResult = withTimeoutOrNull(100) { budget.acquire(5) }
        assertTrue(thirdResult == null, "预算耗尽时第三个任务必须等待，而不是超发")

        budget.release(first)
        val third = budget.acquire(5)
        assertTrue(third.permits in 1..5)
        budget.release(second)
        budget.release(third)
    }

    @Test
    fun `canceled waiter does not leak permits`() = runBlocking {
        val budget = ConnectionBudget { 4 }
        val held = budget.acquire(4)
        val canceled = launch {
            runCatching { budget.acquire(2) }
        }
        delay(50)
        canceled.cancelAndJoin()
        budget.release(held)

        val after = withTimeoutOrNull(500) { budget.acquire(4) }
        assertTrue(after != null, "取消的等待者不应永久占住预算")
        budget.release(after!!)
    }
}

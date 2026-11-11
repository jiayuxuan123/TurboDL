package dev.turbodl.core

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceCooldownsTest {
    @Test
    fun `same origin and group share a cooldown`() {
        val cooldowns = SourceCooldowns()

        assertTrue(
            cooldowns.defer(
                "https://source.example/first",
                "downloads",
                10_000,
            )
        )
        assertTrue(
            cooldowns.isCooling(
                "https://source.example/second",
                "downloads",
            )
        )
    }

    @Test
    fun `different groups and origins remain isolated`() {
        val cooldowns = SourceCooldowns()

        assertTrue(
            cooldowns.defer(
                "https://source.example/path",
                "first-group",
                10_000,
            )
        )
        assertFalse(
            cooldowns.isCooling(
                "https://source.example/path",
                "second-group",
            )
        )
        assertFalse(
            cooldowns.isCooling(
                "http://source.example/path",
                "first-group",
            )
        )
        assertFalse(
            cooldowns.isCooling(
                "https://source.example:444/path",
                "first-group",
            )
        )
        assertFalse(
            cooldowns.isCooling(
                "https://other.example/path",
                "first-group",
            )
        )
    }

    @Test
    fun `cancelling await returns promptly`() = runBlocking {
        val cooldowns = SourceCooldowns()
        assertTrue(
            cooldowns.defer(
                "https://source.example/path",
                "cancel-group",
                60_000,
            )
        )
        var completed = false
        val waiter = launch {
            cooldowns.await(
                "https://source.example/path",
                "cancel-group",
            )
            completed = true
        }

        delay(25)
        withTimeout(1_000) {
            waiter.cancelAndJoin()
        }
        delay(25)

        assertFalse(completed)
        assertTrue(
            cooldowns.isCooling(
                "https://source.example/path",
                "cancel-group",
            )
        )
    }

    @Test
    fun `cooldown expiry releases await`() = runBlocking {
        val cooldowns = SourceCooldowns()
        assertTrue(
            cooldowns.defer(
                "https://source.example/path",
                "expiry-group",
                100,
            )
        )
        assertTrue(
            cooldowns.isCooling(
                "https://source.example/path",
                "expiry-group",
            )
        )

        withTimeout(2_000) {
            cooldowns.await(
                "https://source.example/path",
                "expiry-group",
            )
        }

        assertFalse(
            cooldowns.isCooling(
                "https://source.example/path",
                "expiry-group",
            )
        )
    }

    @Test
    fun `full capacity rejects a new key without throwing`() {
        val cooldowns = SourceCooldowns(maxEntries = 2)

        assertTrue(
            cooldowns.defer(
                "https://first.example/path",
                "capacity-group",
                10_000,
            )
        )
        assertTrue(
            cooldowns.defer(
                "https://second.example/path",
                "capacity-group",
                10_000,
            )
        )
        assertFalse(
            cooldowns.defer(
                "https://third.example/path",
                "capacity-group",
                10_000,
            )
        )
        assertFalse(
            cooldowns.isCooling(
                "https://third.example/path",
                "capacity-group",
            )
        )
    }

    @Test
    fun `oversized wait is rejected instead of shortened`() {
        val cooldowns = SourceCooldowns()
        val oversizedMillis = 7L * 24 * 60 * 60 * 1_000 + 1

        assertFalse(
            cooldowns.defer(
                "https://source.example/path",
                "oversized-group",
                oversizedMillis,
            )
        )
        assertFalse(
            cooldowns.isCooling(
                "https://source.example/path",
                "oversized-group",
            )
        )

        assertTrue(
            cooldowns.defer(
                "https://source.example/path",
                "existing-group",
                5_000,
            )
        )
        assertFalse(
            cooldowns.defer(
                "https://source.example/path",
                "existing-group",
                oversizedMillis,
            )
        )
        assertTrue(
            cooldowns.isCooling(
                "https://source.example/path",
                "existing-group",
            )
        )
    }
}

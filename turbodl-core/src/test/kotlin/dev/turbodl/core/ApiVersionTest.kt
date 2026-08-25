package dev.turbodl.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiVersionTest {

    @Test
    fun ordering() {
        assertTrue(ApiVersion(1, 0, 0) < ApiVersion(1, 0, 1))
        assertTrue(ApiVersion(1, 2, 0) < ApiVersion(1, 10, 0))
        assertTrue(ApiVersion(2, 0, 0) > ApiVersion(1, 99, 99))
        assertEquals(ApiVersion(1, 2, 3), ApiVersion(1, 2, 3))
    }

    @Test
    fun satisfiesRequiresSameMajorAndAtLeast() {
        val host = ApiVersion(1, 4, 2)
        assertTrue(host.satisfies(ApiVersion(1, 0, 0)))
        assertTrue(host.satisfies(ApiVersion(1, 4, 2)))
        assertFalse(host.satisfies(ApiVersion(1, 5, 0)), "host below required minor")
        assertFalse(host.satisfies(ApiVersion(2, 0, 0)), "cross-major always incompatible")
        // An older plugin built for 0.x is not run on a 1.x host either.
        assertFalse(host.satisfies(ApiVersion(0, 9, 0)))
    }

    @Test
    fun parse() {
        assertEquals(ApiVersion(1, 2, 3), ApiVersion.parse("1.2.3"))
        assertEquals(ApiVersion(1, 2, 3), ApiVersion.parse(" 1.2.3-beta.1 "))
        assertEquals(ApiVersion(1, 2, 3), ApiVersion.parse("1.2.3+build7"))
        assertFailsWith<IllegalArgumentException> { ApiVersion.parse("1.2") }
        assertFailsWith<NumberFormatException> { ApiVersion.parse("x.y.z") }
    }

    @Test
    fun currentIsOneZeroZero() {
        assertEquals(ApiVersion(1, 0, 0), ApiVersion.CURRENT)
    }
}

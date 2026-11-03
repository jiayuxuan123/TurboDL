package dev.turbodl.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MirrorHeaderPolicyTest {
    @Test
    fun `same-origin mirror receives the caller headers`() {
        val headers = mapOf("Cookie" to "session=secret", "X-Route" to "edge")
        assertEquals(
            headers,
            MirrorHeaderPolicy.headersFor(
                "https://files.example.test/a",
                "https://files.example.test/b",
                headers,
            ),
        )
    }

    @Test
    fun `cross-origin mirror loses credentials but keeps safe headers`() {
        val filtered = MirrorHeaderPolicy.headersFor(
            "https://primary.example.test/a",
            "https://mirror.example.test/a",
            mapOf(
                "Cookie" to "session=secret",
                "Authorization" to "Bearer secret",
                "X-Api-Key" to "secret",
                "User-Agent" to "TurboDL-test",
                "X-Route" to "edge",
            ),
        )
        assertEquals(
            mapOf("User-Agent" to "TurboDL-test", "X-Route" to "edge"),
            filtered,
        )
    }

    @Test
    fun `different ports are different origins`() {
        val filtered = MirrorHeaderPolicy.headersFor(
            "http://127.0.0.1:8000/file",
            "http://127.0.0.1:8001/file",
            mapOf("Cookie" to "secret", "User-Agent" to "TurboDL-test"),
        )
        assertEquals(mapOf("User-Agent" to "TurboDL-test"), filtered)
    }
}

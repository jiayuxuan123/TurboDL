package dev.turbodl.core

import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DownloadRequestCompatibilityTest {
    @Test
    fun `legacy eight-argument constructor remains available`() {
        val constructor = DownloadRequest::class.java.constructors.firstOrNull { candidate ->
            val types = candidate.parameterTypes
            types.size == 8 &&
                types[0] == String::class.java &&
                types[1] == File::class.java &&
                types[2] == Map::class.java &&
                types[3] == java.lang.Long.TYPE &&
                types[4] == String::class.java &&
                types[5] == Integer::class.java &&
                types[6] == String::class.java &&
                types[7] == Map::class.java
        }
        assertNotNull(constructor, "0.4 SDK must keep the 0.3.1 DownloadRequest JVM constructor")

        val request = constructor!!.newInstance(
            "https://example.test/file",
            File("file.bin"),
            Collections.emptyMap<String, String>(),
            -1L,
            null,
            null,
            "legacy-key",
            Collections.emptyMap<String, String>(),
        ) as DownloadRequest
        assertEquals("legacy-key", request.stableKey)
        assertEquals(emptyList(), request.mirrors)
    }

    @Test
    fun `legacy component order is not shifted by mirror support`() {
        val request = DownloadRequest(
            url = "https://example.test/file",
            destination = File("file.bin"),
            stableKey = "room-1",
            tags = mapOf("kind" to "test"),
            mirrors = listOf("https://mirror.test/file"),
        )
        assertEquals(
            String::class.java,
            DownloadRequest::class.java.getMethod("component7").returnType,
        )
        assertEquals("room-1", request.component7())
        assertEquals(mapOf("kind" to "test"), request.component8())
        assertEquals(listOf("https://mirror.test/file"), request.component9())
    }

    @Test
    fun `legacy Kotlin constructor and copy ABI remain available`() {
        val names = DownloadRequest::class.java.declaredMethods.map { it.name }
        assertTrue("copy\$default" in names)
        val copy = DownloadRequest::class.java.declaredMethods.single { it.name == "copy\$default" }
        assertEquals(12, copy.parameterCount)
        val synthetic = DownloadRequest::class.java.declaredConstructors.single {
            it.parameterTypes.lastOrNull()?.name == "kotlin.jvm.internal.DefaultConstructorMarker"
        }
        assertEquals(11, synthetic.parameterCount)
    }
}

package dev.turbodl.core

import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the stage-1 backend abstraction:
 *  - a custom [DownloadBackend] installed via [TurboClient.backendResolver] overrides the built-in one;
 *  - when the resolver returns null, the built-in HTTP backend is used (no runtime dependency).
 *
 * This proves the hybrid design: core is usable standalone (built-in backend),
 * yet a plugin backend can override/extend it — without core depending on the runtime.
 */
class BackendOverrideTest {

    private lateinit var tmpDir: File

    @AfterTest
    fun teardown() {
        if (::tmpDir.isInitialized) tmpDir.deleteRecursively()
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun customBackendOverridesBuiltin() = runBlocking {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "turbodl-be-${System.nanoTime()}").apply { mkdirs() }
        val payload = "hello from a plugin backend".repeat(1000).toByteArray()

        // A minimal custom backend that fabricates content locally, proving it fully replaced
        // the built-in HTTP path (the URL is never actually fetched).
        val fakeBackend = object : DownloadBackend {
            override val name = "test-fake"
            override fun supports(request: DownloadRequest) = request.url.startsWith("fake://")
            override suspend fun download(context: BackendContext): BackendResult {
                context.reportTotalSize(payload.size.toLong())
                val part = File(context.workDir, "seg_0_${payload.size - 1}.part")
                part.writeBytes(payload)
                context.reportProgress(payload.size.toLong(), 1)
                return BackendResult(listOf(part), payload.size.toLong())
            }
        }

        val client = TurboClient(TurboConfig())
        // Install a resolver that routes fake:// to our backend, else null (built-in fallback).
        client.backendResolver = BackendResolver { req ->
            if (fakeBackend.supports(req)) fakeBackend else null
        }

        val out = File(tmpDir, "fake.bin")
        val result = client.await(client.submit(DownloadRequest("fake://whatever", out)))
        client.shutdown()

        assertTrue(result.isSuccess, "custom backend should succeed: ${result.exceptionOrNull()?.message}")
        assertEquals(payload.size.toLong(), out.length())
        assertEquals(sha256(payload), sha256(out.readBytes()))
    }
}

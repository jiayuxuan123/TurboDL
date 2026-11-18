package dev.turbodl.core

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectoryBackendResultTest {
    private lateinit var tmpDir: File

    @AfterTest
    fun teardown() {
        if (::tmpDir.isInitialized) tmpDir.deleteRecursively()
    }

    @Test
    fun directoryResultIsPublishedWithoutConcatenation() = runBlocking {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "turbodl-dir-${System.nanoTime()}").apply { mkdirs() }
        val backend = object : DownloadBackend {
            override val name = "test-directory"
            override fun supports(request: DownloadRequest) = request.url.startsWith("dir://")
            override suspend fun download(context: BackendContext): BackendResult {
                val root = File(context.request.destination.parentFile, "torrent")
                File(root, "nested").mkdirs()
                File(root, "a.bin").writeBytes(byteArrayOf(1, 2, 3))
                File(root, "nested/b.bin").writeBytes(byteArrayOf(4, 5))
                context.reportTotalSize(5)
                context.reportProgress(5, 1)
                return BackendResult.directory(root, 5)
            }
        }
        val client = TurboClient(TurboConfig())
        client.backendResolver = BackendResolver { request ->
            backend.takeIf { backend.supports(request) }
        }

        val result = client.await(client.submit(DownloadRequest("dir://fixture", File(tmpDir, "ignored"))))
        client.shutdown()

        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        val directory = result.getOrThrow()
        assertTrue(directory.isDirectory)
        assertEquals(5L, directory.walkTopDown().filter { it.isFile }.sumOf { it.length() })
    }
}

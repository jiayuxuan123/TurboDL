package dev.turbodl.core

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkDirOverrideTest {
    @Test
    fun `request workDir overrides config and reaches backend context`() = runBlocking {
        val defaultRoot = Files.createTempDirectory("turbodl-default-work").toFile()
        val taskRoot = Files.createTempDirectory("turbodl-task-work").toFile()
        val destination = File.createTempFile("turbodl-workdir", ".bin").apply { deleteOnExit() }
        val observed = AtomicReference<File>()
        val payload = "per-task work directory".toByteArray()

        val backend = object : DownloadBackend {
            override val name: String = "test-workdir"
            override fun supports(request: DownloadRequest): Boolean = true

            override suspend fun download(context: BackendContext): BackendResult {
                observed.set(context.workDir)
                val part = File(context.workDir, "part.bin")
                part.writeBytes(payload)
                context.reportTotalSize(payload.size.toLong())
                context.reportProgress(payload.size.toLong(), 1)
                return BackendResult(listOf(part), payload.size.toLong())
            }
        }

        val client = TurboClient(TurboConfig(maxConcurrentTasks = 1, workDir = defaultRoot))
        client.backendResolver = BackendResolver { backend }
        try {
            val id = client.submit(
                DownloadRequest(
                    url = "test://workdir",
                    destination = destination,
                    stableKey = "task/one",
                ),
                taskRoot,
            )
            val result = client.await(id)
            assertTrue(result.isSuccess, "task failed: ${result.exceptionOrNull()?.message}")
            assertEquals(payload.toList(), destination.readBytes().toList())
            assertEquals(
                File(taskRoot, stableKeyDirectoryName("task/one")).canonicalFile,
                observed.get().canonicalFile,
            )
            assertTrue(!observed.get().path.startsWith(defaultRoot.canonicalPath))
        } finally {
            client.shutdown()
            defaultRoot.deleteRecursively()
            taskRoot.deleteRecursively()
            destination.delete()
        }
    }
}

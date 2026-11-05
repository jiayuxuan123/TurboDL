package dev.turbodl.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotEquals

class ClientWorkDirIsolationTest {
    @Test
    fun `clients without stable keys never share an internal task directory`() {
        val dir = java.nio.file.Files.createTempDirectory("tdlp-client-isolation-").toFile()
        val config = TurboConfig(maxConnectionsPerTask = 2, maxConcurrentTasks = 1, workDir = dir)
        val first = TurboClient(config)
        val second = TurboClient(config)
        try {
            val id1 = first.submit(
                DownloadRequest("http://127.0.0.1:1/a.bin", File(dir, "a.bin")),
            )
            val id2 = second.submit(
                DownloadRequest("http://127.0.0.1:1/b.bin", File(dir, "b.bin")),
            )
            assertNotEquals(
                first.chunkDirForTesting(id1),
                second.chunkDirForTesting(id2),
                "不同 client 的 task id 都从 1 开始，未提供 stableKey 时必须使用独立命名空间",
            )
        } finally {
            first.shutdown()
            second.shutdown()
            dir.deleteRecursively()
        }
    }
}

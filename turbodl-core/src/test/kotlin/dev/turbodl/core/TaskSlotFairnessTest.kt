package dev.turbodl.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `maxConcurrentTasks` 是硬上限，不是“大多数时候的上限”。
 *
 * 旧实现先读计数、再在协程里自增，多个任务可以同时穿过 check 和 increment，
 * 实际并发会短暂超过配置值。这里用假 backend 把任务生命周期固定住，
 * 只测 TurboClient 自己的任务槽，不受网络和分片调度干扰。
 */
class TaskSlotFairnessTest {

    @Test
    fun `concurrent submits never exceed maxConcurrentTasks`() = runBlocking {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val backend = object : DownloadBackend {
            override val name: String = "slot-test"
            override fun supports(request: DownloadRequest): Boolean = request.url.startsWith("slot://")
            override suspend fun download(context: BackendContext): BackendResult {
                val now = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                try {
                    delay(120)
                    context.request.destination.parentFile?.mkdirs()
                    context.request.destination.writeBytes(byteArrayOf(1))
                    context.reportTotalSize(1)
                    context.reportProgress(1, 1)
                    return BackendResult(listOf(context.request.destination), 1)
                } finally {
                    active.decrementAndGet()
                }
            }
        }

        val dir = File(System.getProperty("java.io.tmpdir"), "slot-${System.nanoTime()}")
            .apply { mkdirs(); deleteOnExit() }
        val client = TurboClient(TurboConfig(maxConcurrentTasks = 2))
        client.backendResolver = BackendResolver { backend }
        try {
            val ids = (1..8).map { n ->
                client.submit(
                    DownloadRequest(
                        url = "slot://task/$n",
                        destination = File(dir, "out-$n.bin"),
                    )
                )
            }
            ids.forEach { assertTrue(client.await(it).isSuccess, "任务 $it 应完成") }
            assertTrue(peak.get() <= 2, "实际任务峰值为 ${peak.get()}，超过 maxConcurrentTasks=2")
            assertEquals(8, ids.size)
        } finally {
            client.shutdown()
            dir.deleteRecursively()
        }
    }
}

package dev.turbodl.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * 多线程分段下载调度器（引擎核心）。
 *
 * 综合各开源下载器思想（仅思想，无源码复制）：
 *  - **aria2**：`--split` 预切 N 段 + `--min-split-size` 控制粒度；固定连接数满并发。
 *  - **IDM / XDM 的动态分段效果**：本实现用「细粒度预分块 + 工作窃取」达成等效的消除长尾效果——
 *    块数远多于连接数，慢连接拖住某块时其余连接持续领新块，天然负载均衡；
 *    相比运行时就地劈分「在飞段」，预分块无区间重叠风险、断点续传更简单可靠。
 *  - **axel / Persepolis**：顺序分片优先（靠前的先下，便于边下边预览/播放）。
 *  - **Motrix**：全局限速、队列并发。
 *
 * 并发模型（v2，修复「下到后面线程数莫名掉下去、速度暴跌」）：
 *  - 不再用 Semaphore + parked 许可（旧实现 throttleDown 在持有许可时又去 acquire，
 *    会挂起自己等别人释放；且 parked 许可极难完整归还，导致并发单向衰减到 1）。
 *  - 改为**索引自闸门**：启动固定 [workers] 个协程，每个协程按自身 idx 与动态目标并发 [desired] 比较；
 *    idx >= desired 时短暂 park（不占资源、可随时复活），idx < desired 时正常领块下载。
 *    背压只需增减 [desired] 一个整数，天然无死锁、可随时恢复。
 *
 * 关键策略：
 *  1. 服务器不支持/忽略 Range → 返回 [Outcome.NeedWholeFallback]，上层整文件回退。
 *  2. 坏块/超时 → 仅重试该分片（回投优先级队列），不作废整任务。
 *  3. 分片优先级 → 所有块按 start 有序入优先队列，靠前分片先被领取。
 *  4. 背压 → 仅 429/503 或连续失败达阈值时乘性下调并发；持续成功后**主动恢复**到设定值。
 *  5. Range 篡改校验 → 由 [SegmentDownloader] 逐段校验实际字节匹配请求区间。
 */
internal class SegmentScheduler(
    private val downloader: SegmentDownloader,
    private val config: TurboConfig,
    private val speedLimiter: SpeedLimiter,
) {
    private companion object {
        /** 背压恢复阈值：累计成功这么多个分片后，尝试归还一个并发名额。 */
        const val RAMP_UP_SUCCESS_THRESHOLD = 2

        /** 被 park 的协程轮询间隔（毫秒）。 */
        const val PARK_POLL_MS = 80L

        /** 队列暂空但仍有在飞分片时的等待间隔（毫秒）。 */
        const val DRAIN_WAIT_MS = 30L

        /**
         * 单分片遇到「服务器返回整文件（忽略 Range）」的全局容忍次数。
         * 网盘 CDN 偶发 200 不应立刻清空所有已下分片；只有反复出现才判定服务器确实不支持 Range。
         */
        const val RANGE_IGNORED_TOLERANCE = 3
    }

    sealed interface Outcome {
        object Completed : Outcome
        object NeedWholeFallback : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** 固定区间分片（预分块，区间不可变——避免运行时缩短导致的重叠/膨胀）。 */
    private class Segment(
        val start: Long,
        val end: Long,
        var attempts: Int = 0,
    ) {
        val length get() = end - start + 1
        fun file(dir: File) = File(dir, "seg_${start}_${end}.part")
    }

    suspend fun run(
        taskId: Long,
        url: String,
        total: Long,
        chunkDir: File,
        headers: Map<String, String>,
        connections: Int,
        resumeFrom: Long,
        onBytes: suspend (delta: Long, absolute: Long) -> Unit,
        onConnections: (Int) -> Unit,
        isActive: () -> Boolean,
    ): Outcome = coroutineScope {
        chunkDir.mkdirs()
        // per-host 上限：部分 CDN（如迅雷）超过阈值会把 Range 降级为 200 整文件，这里提前限幅。
        val hostCap = config.maxConnectionsPerHost.takeIf { it > 0 } ?: Int.MAX_VALUE
        val workers = connections.coerceIn(1, 256).coerceAtMost(hostCap).coerceAtLeast(1)

        // ---------- 块大小：按「连接数」反推，保证固定 N 线程全部跑满 ----------
        val targetSegments = workers.toLong() *
            config.segmentsPerConnection.coerceIn(1, 64).toLong()
        val effBlock = run {
            val byConnections = if (targetSegments > 0) total / targetSegments else total
            byConnections.coerceIn(1L, config.blockSize)
                .coerceAtLeast(min(config.minSegmentSize, max(1L, total / workers)))
        }

        // ---------- 预分块：整文件切成 [effBlock] 大小的连续区间 ----------
        val allSegments = ArrayList<Segment>()
        run {
            var s = 0L
            while (s < total) {
                val e = min(s + effBlock - 1, total - 1)
                allSegments.add(Segment(s, e))
                s = e + 1
            }
        }

        // ---------- 断点续传：已完整的块跳过，累加已下载量 ----------
        val downloaded = AtomicLong(0)
        val pending = java.util.PriorityQueue<Segment>(compareBy { it.start })  // 分片优先级：靠前优先
        val pendingLock = Any()
        for (seg in allSegments) {
            val f = seg.file(chunkDir)
            if (f.exists() && f.length() >= seg.length) {
                downloaded.addAndGet(seg.length)
            } else {
                synchronized(pendingLock) { pending.offer(seg) }
                if (f.exists()) downloaded.addAndGet(f.length())  // 部分完成的续传起点
            }
        }
        downloaded.set(min(downloaded.get(), total))

        val needWholeFallback = AtomicBoolean(false)
        val failReason = AtomicReference<String?>(null)
        val rangeIgnoredCount = AtomicInteger(0)
        val consecutiveFailures = AtomicInteger(0)
        /** 累计成功分片数（用于背压恢复判断）。 */
        val consecutiveSuccesses = AtomicInteger(0)

        // ---------- 并发闸门：workers 个协程按 idx 与 desired 自闸门 ----------
        /** 动态目标并发（可因背压下调、因持续成功恢复；上限 workers）。 */
        val desired = AtomicInteger(workers)
        /** 当前真实在传输的连接数（供 UI 展示实际并发）。 */
        val activeConns = AtomicInteger(0)
        /** 正在传输中的分片数（判定“队列空但仍可能有重试回投”）。 */
        val inFlight = AtomicInteger(0)

        onConnections(workers)

        fun poll(): Segment? = synchronized(pendingLock) { pending.poll() }
        fun offer(seg: Segment) = synchronized(pendingLock) { pending.offer(seg) }
        fun queueEmpty(): Boolean = synchronized(pendingLock) { pending.isEmpty() }

        /** 背压下调：乘性减半（不低于 1）。仅调整目标整数，被 park 的协程自然让出。 */
        fun throttleDown() {
            if (config.backpressureConsecutiveFailures <= 0) return
            val cur = desired.get()
            val target = max(1, cur / 2)
            if (target < cur) {
                desired.set(target)
                onConnections(min(activeConns.get(), target))
            }
        }

        /**
         * 背压恢复：持续成功后逐步把并发恢复到设定值。
         *
         * 旧实现只降不升，一次瞬时 429/503 就永久把并发减半（可逐步降到 1），
         * 网络恢复后再也跑不满——这正是「下到后面线程数掉下去、速度只剩几 KB」的主因。
         * 现在只要持续成功就一步步把 desired 加回 workers，被 park 的协程随即复活。
         */
        fun rampUpIfHealthy() {
            if (config.backpressureConsecutiveFailures <= 0) return
            if (desired.get() >= workers) return
            if (consecutiveSuccesses.get() < RAMP_UP_SUCCESS_THRESHOLD) return
            consecutiveSuccesses.set(0)
            val next = min(workers, desired.incrementAndGet())
            onConnections(next)
        }

        val ok = try {
            val jobs = List(workers) { idx ->
                async(Dispatchers.IO) {
                    if (idx in 1..8) delay(idx * 20L)  // 错峰建连
                    while (isActive() && !needWholeFallback.get()) {
                        // 索引自闸门：超出当前目标并发的协程 park（不占许可、随时可复活）。
                        if (idx >= desired.get()) {
                            if (queueEmpty() && inFlight.get() == 0) break
                            delay(PARK_POLL_MS)
                            continue
                        }
                        val seg = poll()
                        if (seg == null) {
                            // 队列暂空：若仍有分片在传（可能因失败/限流被回投），等一下再领，不立即退出。
                            if (inFlight.get() > 0) { delay(DRAIN_WAIT_MS); continue }
                            break  // 真正无活可干：退出
                        }
                        if (needWholeFallback.get() || !isActive()) { offer(seg); break }
                        inFlight.incrementAndGet()
                        val live = activeConns.incrementAndGet()
                        onConnections(min(live, desired.get()))
                        try {
                            val res = downloader.downloadSegment(
                                taskId, url, seg.start, seg.end, seg.file(chunkDir), headers
                            ) { bytes ->
                                speedLimiter.awaitAllow(bytes)
                                val abs = min(downloaded.addAndGet(bytes), total)
                                if (!isActive()) return@downloadSegment
                                onBytes(bytes, abs)
                            }
                            when (res) {
                                SegmentResult.OK -> {
                                    consecutiveFailures.set(0)
                                    consecutiveSuccesses.incrementAndGet()
                                    rampUpIfHealthy()
                                }
                                SegmentResult.RANGE_IGNORED -> {
                                    // 单分片被返回整文件：容忍偶发，反复出现才判定服务器不支持 Range。
                                    offer(seg)  // 回投，可能只是该 CDN 节点抖动
                                    if (rangeIgnoredCount.incrementAndGet() >= RANGE_IGNORED_TOLERANCE) {
                                        needWholeFallback.compareAndSet(false, true)
                                    }
                                }
                                SegmentResult.THROTTLED -> {
                                    consecutiveSuccesses.set(0)
                                    val n = consecutiveFailures.incrementAndGet()
                                    offer(Segment(seg.start, seg.end, seg.attempts))
                                    if (config.backpressureConsecutiveFailures in 1..n) {
                                        throttleDown(); consecutiveFailures.set(0)
                                    }
                                }
                                SegmentResult.FAILED -> {
                                    consecutiveSuccesses.set(0)
                                    seg.attempts++
                                    val n = consecutiveFailures.incrementAndGet()
                                    if (seg.attempts > config.maxRetries) {
                                        failReason.compareAndSet(
                                            null,
                                            "分片 ${seg.start}-${seg.end} 重试 ${seg.attempts} 次仍失败"
                                        )
                                    } else {
                                        offer(seg)  // 仅重试该分片
                                    }
                                    if (config.backpressureConsecutiveFailures in 1..n) {
                                        throttleDown(); consecutiveFailures.set(0)
                                    }
                                }
                            }
                        } finally {
                            onConnections(min(activeConns.decrementAndGet(), desired.get()))
                            inFlight.decrementAndGet()
                        }
                    }
                }
            }
            jobs.awaitAll()
            true
        } catch (e: CancellationException) {
            throw e
        }

        if (needWholeFallback.get()) return@coroutineScope Outcome.NeedWholeFallback
        if (!isActive()) return@coroutineScope Outcome.Failed("任务已暂停")
        failReason.get()?.let { return@coroutineScope Outcome.Failed(it) }

        val (covered, missing) = verifyCoverage(chunkDir, total)
        if (!covered) return@coroutineScope Outcome.Failed("缺失区间 ${missing.size} 段")
        Outcome.Completed
    }

    /** 校验 [0,total) 被完整块连续覆盖。 */
    private fun verifyCoverage(dir: File, total: Long): Pair<Boolean, List<LongRange>> {
        val blocks = dir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }
            ?.mapNotNull { f ->
                val name = f.name.removePrefix("seg_").removeSuffix(".part")
                val s = name.substringBefore('_').toLongOrNull() ?: return@mapNotNull null
                val e = name.substringAfter('_').toLongOrNull() ?: return@mapNotNull null
                if (f.length() >= (e - s + 1)) s..e else null
            }?.sortedBy { it.first } ?: emptyList()
        val missing = mutableListOf<LongRange>()
        var pos = 0L
        for (r in blocks) {
            if (r.first > pos) missing.add(pos until r.first)
            if (r.last + 1 > pos) pos = r.last + 1
        }
        if (pos < total) missing.add(pos until total)
        return (missing.isEmpty() && pos >= total) to missing
    }

    /** 合并所有分片（按 start 排序）。 */
    fun finalParts(chunkDir: File): List<File> =
        chunkDir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }
            ?.sortedBy { it.name.removePrefix("seg_").substringBefore('_').toLongOrNull() ?: 0L }
            ?: emptyList()
}

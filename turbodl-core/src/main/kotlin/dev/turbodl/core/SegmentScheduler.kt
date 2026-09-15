package dev.turbodl.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
) {    private companion object {
        /** 背压恢复阈值：累计成功这么多个分片后，尝试归还并发名额。 */
        const val RAMP_UP_SUCCESS_THRESHOLD = 2

        /**
         * 每次上调的比例（相对当前目标并发）。
         *
         * 旧实现每次只 +1，从慢启动初始值 4 爬到 128 需要 124 次上调、约 248 个成功分片——
         * 慢网或中小文件根本爬不到设定值，用户会看到「线程数始终远低于设定」。
         * 改为按比例上调（至少 +1），几十个分片内即可到顶，同时仍是渐进的、不冲击服务器。
         */
        const val RAMP_UP_FACTOR = 0.5

        /** 被 park 的协程轮询间隔（毫秒）。 */
        const val PARK_POLL_MS = 80L

        /** 队列暂空但仍有在飞分片时的等待间隔（毫秒）。 */
        const val DRAIN_WAIT_MS = 30L

        /**
         * 单分片遇到「服务器返回整文件（忽略 Range）」的全局容忍次数。
         * 网盘 CDN 偶发 200 不应立刻清空所有已下分片；只有反复出现才判定服务器确实不支持 Range。
         */
        const val RANGE_IGNORED_TOLERANCE = 3

        /**
         * 限流（429/503）的重试预算，与 `maxRetries` **分开**。
         *
         * 为什么要更大：429/503 表示「并发太高」，正确响应是**降低并发后重试**——
         * 这需要时间（背压逐级减半 + 退避等待）。若与普通失败共用一个小预算，
         * 分片会在并发降到服务器能接受的档位**之前**就被丢掉 → 整个任务失败。
         * 实测（服务器并发上限 16）：N=32 时旧实现整个任务失败
         * （「分片持续被限流（429/503），重试 4 次后放弃」）。
         *
         * 取 `max(8, maxRetries × 2)`：默认 maxRetries=5 → 10 次；配合退避上限 8s，
         * 最坏多等约半分钟 —— 远好于把整个任务判死。
         */
        fun throttleBudget(maxRetries: Int): Int = max(8, maxRetries * 2)

        /**
         * 已稳定在天花板多少个成功分片后，才允许把天花板 +1 重新试探。
         * 取大于 ramp-up 阈值一个量级：试探本身会撞墙（一次 503），必须罕见。
         */
        const val CEILING_PROBE_AFTER = 16

        /**
         * 总切块数的上限（防「连接数 × segmentsPerConnection」无上限膨胀）。
         *
         * 【为什么需要】块数 = `连接数 × spc`（默认 spc=4）是个**没有上限的乘法**：
         * 128 连接 → 512 块（被 minSegmentSize 压到 256 块，每块仅 64KB）。
         * 每个块 = 一次 HTTP 请求 + 一个临时文件 + 最后合并时的一次读写。
         *
         * 【实测（`FanoutCostTest`，16MB 不限速回环，3 次取中位数）】把"连接数"和"分片数"分离后：
         *
         * | 组 | 连接 | 分片 | 吞吐 |
         * |---|---|---|---|
         * | A 基线 | 128 | 256 | **4.4 MB/s** |
         * | B 同连接、分片减半 | 128 | 128 | **10.5** |
         * | C 同分片、连接减半 | 64 | 128 | 11.0 |
         * | D 同分片、连接再减半 | 32 | 128 | 9.7 |
         *
         * **连接数 128→32 几乎无影响，而分片数减半带来 2.4 倍** → 主因是分片数（请求/合并开销），
         * 不是连接数。
         *
         * 【取值 128 的理由】① 对 `连接数 ≤ 32` 的配置**完全不生效**（32×4=128，默认 16 更不受影响），
         * 所以默认体验零变化；② 128 块仍保有工作窃取所需的粒度；
         * ③ 只做"止血"，不去调优「均速要少块 / 偏斜要多块」这个由 `SkewSweepTest` 证明的自适应问题
         * ——那需要真实链路数据，不在本版范围。
         */
        const val MAX_TARGET_SEGMENTS = 128
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
        /**
         * **限流（429/503）单独的计数**，不与 [attempts] 混用。
         *
         * 二者语义不同：429/503 是「并发太高，请慢一点」的**信号**（正确响应是降并发 + 重试），
         * [attempts] 是「这次没拿到」的**失败**（有限次重试后放弃）。
         * 旧实现混用，导致一个在并发降下来之前被拒几次的分片就被丢掉 → 整个任务失败。
         */
        var throttleAttempts: Int = 0,
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
        // per-host 并发上限：默认 0 = 不限（只有个别 CDN 如迅雷才需要限，且应由调用方按 host 选择性启用）。
        // 警告：若对所有下载统一设一个小值（如 16），会把单个下载（全部同一 host）直接限死到该值，
        // 表现为“设 64 线程却只跑 16 / 速度骤降”。这里仅当 >0 时限幅。
        val hostCap = config.maxConnectionsPerHost.takeIf { it > 0 } ?: Int.MAX_VALUE
        val workers = connections.coerceIn(1, 256).coerceAtMost(hostCap).coerceAtLeast(1)

        // ---------- 专用阻塞 IO 调度器（关键吞吐修正）----------
        // Dispatchers.IO 的默认并行度是 max(64, CPU 核数)。分片下载是**阻塞式** socket read，
        // 一个分片占死一个线程；因此设 128 连接时实际最多只有 ~64 个能同时搜数据，
        // 其余在调度器队列里干等——这既压低吞吐，也是“线程数卡在 64”的硬天花板。
        // 另外与其他库共用 Dispatchers.IO 还会互相抢线程。故为本任务开专用调度器，
        // 并行度 = workers + 少量余量（给守护/回调用）。
        val ioDispatcher = Dispatchers.IO.limitedParallelism(workers + 4)


        // ---------- 块大小：按「连接数」反推，保证固定 N 线程全部跑满 ----------
        // 上限 maxSegmentsPerTask（默认 128，**0 = 不限**）：块数不再随连接数无限膨胀
        // （原因、实测与"收益尚未可靠量化"的警告见 TurboConfig.maxSegmentsPerTask）。
        val segCap = if (config.maxSegmentsPerTask <= 0) Long.MAX_VALUE
        else config.maxSegmentsPerTask.toLong()
        val targetSegments = (workers.toLong() *
            config.segmentsPerConnection.coerceIn(1, 64).toLong())
            .coerceAtMost(segCap)
        val effBlock = run {
            // blockSize 是**硬上限**，永远优先；minSegmentSize 是**软下限**，被上限压制。
            // 二者取"上限优先"是为了让 `minSegmentSize > blockSize` 这种组合仍能表达
            // （对齐 aria2/Motrix 的 20MB 分片需要它），而不会让下限压倒上限。
            val hardCap = max(1L, config.blockSize)
            val softFloor = min(config.minSegmentSize, max(1L, total / workers))
                .coerceAtMost(hardCap)
            val byConnections = if (targetSegments > 0) total / targetSegments else total
            byConnections.coerceIn(1L, hardCap).coerceAtLeast(softFloor)
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
        // ---------- 并发闸门：workers 个协程按 idx 与 desired 自闸门 ----------
        // 慢启动：初始目标并发从较小值开始，随持续成功逐步升到 workers，
        // 避免瞬时几十连接同时握手冲击服务器/被风控，也避免小文件过度建连。
        val slowStartInit = when {
            !config.slowStart -> workers
            config.slowStartInitial > 0 -> min(config.slowStartInitial, workers)
            // 默认初始值随设定并发缩放（至少 4）：固定 4 在 128 连接下起点太低，
            // 配合比例上调可快速到顶，同时避免一上来就全开冲击服务器。
            else -> min(workers, max(4, workers / 4))
        }
        /** 动态目标并发（慢启动从 slowStartInit 升、背压时降、健康时升；上限 workers）。 */
        val desired = AtomicInteger(slowStartInit)

        /**
         * 学到的「服务器能接受的并发天花板」。
         *
         * 没有它，背压降下去之后 ramp-up 仍会按比例爬回 [workers] —— 然后再撞墙、再降、再爬，
         * 形成 AIMD 震荡：实测（服务器并发上限 16，N=128）撞了 **323 次 503**。
         * 背压下调时**同步收紧天花板**，恢复期最多爬到这里；长时间稳定后才 +1 缓步试探
         * （试探本身会撞墙，所以步长必须是 +1 而不是按比例，让失败的代价最小）。
         */
        val ceiling = AtomicInteger(workers)

        /** 在天花板处稳定成功的分片计数（用于缓步试探）。 */
        val ceilingStableCount = AtomicInteger(0)

        /** 限流（429/503）重试预算：比 maxRetries 宽（原因见 companion 里 `throttleBudget` 的注释）。 */
        val throttleRetryBudget = throttleBudget(config.maxRetries)
        /** 当前真实在传输的连接数（供 UI 展示实际并发）。 */
        val activeConns = AtomicInteger(0)
        /** 正在传输中的分片数（判定“队列空但仍可能有重试回投”）。 */
        val inFlight = AtomicInteger(0)
        onConnections(slowStartInit)

        fun poll(): Segment? = synchronized(pendingLock) { pending.poll() }
        fun offer(seg: Segment) = synchronized(pendingLock) { pending.offer(seg) }
        fun queueEmpty(): Boolean = synchronized(pendingLock) { pending.isEmpty() }
        fun queueSize(): Int = synchronized(pendingLock) { pending.size }

        /**
         * 上报给 UI 的并发数。
         *
         * 不能直接用“瞬时在飞分片数”：worker 完成一个分片到领取下一个之间有瞬时空档，
         * 速度越快、分片完成越频繁，这种空档占比越大，采样到的数字反而越小
         * ——表现为“速度变快但显示的线程数下降”（用户实测反馈的现象）。
         * 正确语义是“当前有多少连接在干活”：即目标并发，但受剩余工作量限制
         * （收尾阶段剩不到 N 块时，确实就只有那么多连接）。
         */
        fun reportConns() {
            val work = inFlight.get() + queueSize()
            onConnections(min(desired.get(), work).coerceAtLeast(if (work > 0) 1 else 0))
        }

        // ---------- 卡死（stall）守护：思路参考 aria2 --lowest-speed-limit / curl --speed-limit ----------
        // OkHttp 的 readTimeout 只能管“单次 read 阻塞多久”；若 CDN 涓涓吐字节（每几十秒几字节），
        // 永远不超时，表现为“显示下载中但进度几乎不动”。因此额外监控任务级总字节：
        // 超过 stallTimeoutMs 零增长 → cancel 所有在飞请求，分片回到重试链路（重建连接、可能换节点）。
        val stallRecoveries = AtomicInteger(0)
        val stallFailure = AtomicReference<String?>(null)
        val watchdog = if (config.stallTimeoutMs > 0) launch(ioDispatcher) {
            var lastBytes = -1L
            var lastChange = System.currentTimeMillis()
            while (isActive() && !needWholeFallback.get()) {
                delay(2000)
                val cur = downloaded.get()
                val now = System.currentTimeMillis()
                if (cur != lastBytes) {
                    lastBytes = cur
                    lastChange = now
                    continue
                }
                // 队列与在飞均空：任务即将结束，不该当作卡死
                if (queueEmpty() && inFlight.get() == 0) continue
                if (now - lastChange >= config.stallTimeoutMs) {
                    val n = stallRecoveries.incrementAndGet()
                    if (n > config.maxStallRecoveries) {
                        stallFailure.compareAndSet(
                            null,
                            "下载停止响应（${config.stallTimeoutMs / 1000}s 无任何字节），已重试 ${n - 1} 次仍失败"
                        )
                        downloader.cancelCalls(taskId)
                        break
                    }
                    // 主动断开卡死连接：分片会得到 IOException → FAILED → 回投重试
                    downloader.cancelCalls(taskId)
                    lastChange = System.currentTimeMillis()
                }
            }
        } else null


        /**
         * 显式退避时长（对齐 aria2 `retry-wait` 语义）。
         *
         * 指数退避 + 上限：1s, 2s, 4s, 8s, 16s, 32s…（封顶 60s）。
         * 旧实现在 429/503 后**立即回投**同一分片，属于"越挫越勇"式重投，
         * 反而加剧限流乃至封禁；aria2 对此的注释是
         * "Hammering 'busy' server is not a good idea."
         */
        fun backoffMsFor(attempt: Int): Long =
            (1_000L shl attempt.coerceIn(0, 10)).coerceAtMost(60_000L)

        /** 背压下调：乘性减半（不低于 1）。仅调整目标整数，被 park 的协程自然让出。 */
        fun throttleDown() {
            if (config.backpressureConsecutiveFailures <= 0) return
            val cur = desired.get()
            val target = max(1, cur / 2)
            if (target < cur) {
                desired.set(target)
                // 天花板同步收紧：这次限流证明「上一个天花板」也太高了。
                ceiling.updateAndGet { min(it, target) }
                reportConns()
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
            if (desired.get() >= workers) return
            if (consecutiveSuccesses.get() < RAMP_UP_SUCCESS_THRESHOLD) return
            consecutiveSuccesses.set(0)
            val cur = desired.get()
            val cap = ceiling.get()
            if (cur >= cap) {
                // 已稳定在学到的天花板：缓步试探能否再高一点（+1）。
                // 撞墙 → throttleDown 会把天花板重新压回来，代价只有一次 503。
                if (cap < workers && ceilingStableCount.incrementAndGet() >= CEILING_PROBE_AFTER) {
                    ceilingStableCount.set(0)
                    ceiling.incrementAndGet()
                }
                return
            }
            // 按比例上调（至少 +1）：从 4 爬到 128 只需十余次，而非 124 次。
            val step = max(1, (cur * RAMP_UP_FACTOR).toInt())
            val next = min(min(workers, cap), cur + step)
            desired.set(next)
            reportConns()
        }

        val ok = try {
            val jobs = List(workers) { idx ->
                async(ioDispatcher) {
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
                        activeConns.incrementAndGet()
                        reportConns()
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
                                    // 【S4 修复】不再"每次成功都清零"。
                                    // 旧实现 OK 时 consecutiveFailures.set(0)，导致"间歇 429"
                                    // （如每 8 次出 1 次）永远凑不满连续 4 次失败 → 降级永不触发
                                    // → 引擎持续以高并发冲击服务器 → 被封禁。
                                    // 改为**衰减**：每次成功只减 1，让间歇失败能累积到阈值，
                                    // 同时持续健康时又能自然回落到 0。
                                    consecutiveFailures.updateAndGet { if (it > 0) it - 1 else 0 }
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
                                    // 【关键修复】429/503 **不占用** maxRetries 预算，用独立且更宽松的预算。
                                    //
                                    // 旧实现与普通失败共用 `seg.attempts`/`maxRetries`，后果是：
                                    // 一个在 t=0 就被拒的分片，退避 1s→2s→4s 之后即耗尽预算被**丢弃**，
                                    // 而此时背压才刚把并发降下来（甚至还没降）—— 于是整个任务失败。
                                    //
                                    // 实测（`ConnectionSweepTest`，服务器并发上限 16）：N=32 时任务直接失败
                                    // 「分片 131072-262143 持续被限流（429/503），重试 4 次后放弃」，
                                    // 而 N=128 反而"成功"（撞了 299 次 503 后并发终于降到位）。
                                    //
                                    // 语义上二者本来就不同：429/503 =「你慢一点」，是**关于并发**的信号；
                                    // 而 FAILED =「这次没拿到」。把前者当后者处理，就等于拒绝执行服务器的要求。
                                    seg.throttleAttempts++
                                    if (seg.throttleAttempts > throttleRetryBudget) {
                                        failReason.compareAndSet(
                                            null,
                                            "分片 ${seg.start}-${seg.end} 持续被限流（429/503），" +
                                                "并发已降至 ${desired.get()} 仍失败 ${seg.throttleAttempts} 次"
                                        )
                                    } else {
                                        // 【顺序修正】先降并发、再退避重投。
                                        // 旧实现是先 delay 再 throttleDown：等睡醒才降并发，
                                        // 于是重投时并发还没降下来 → 又被拒 → 白烧一次重试预算。
                                        if (config.backpressureConsecutiveFailures in 1..n) {
                                            throttleDown(); consecutiveFailures.set(0)
                                        }
                                        // 【退避长度看宿主意图】宿主关掉背压（backpressureConsecutiveFailures=0）
                                        // 表示"**不要降并发，只重试**"。那就没有"等并发降下来"这回事了 ——
                                        // 长退避只会让 worker 干等，表现为"速度上不去、线程数也上不去"
                                        // （用户实报）。故此时退避压到 1s/2s，快速重投。
                                        val maxShift = if (config.backpressureConsecutiveFailures <= 0) 1 else 3
                                        delay(backoffMsFor((seg.throttleAttempts - 1).coerceAtMost(maxShift)))
                                        offer(seg)
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
                                        // 网络类失败也做轻度退避，避免瞬时故障下的密集重投。
                                        delay(backoffMsFor((seg.attempts - 1).coerceAtMost(3)))
                                        offer(seg)  // 仅重试该分片
                                    }
                                    if (config.backpressureConsecutiveFailures in 1..n) {
                                        throttleDown(); consecutiveFailures.set(0)
                                    }
                                }
                            }
                        } finally {
                            activeConns.decrementAndGet()
                            inFlight.decrementAndGet()
                            reportConns()
                        }
                    }
                }
            }
            jobs.awaitAll()
            true
        } catch (e: CancellationException) {
            throw e
        } finally {
            watchdog?.cancel()
        }

        // 卡死重试耗尽：明确失败（分片已保留，用户重试即续传）
        stallFailure.get()?.let { return@coroutineScope Outcome.Failed(it) }

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

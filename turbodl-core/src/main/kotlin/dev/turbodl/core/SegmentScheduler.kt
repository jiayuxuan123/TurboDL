package dev.turbodl.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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
        /**
         * 每次上调的比例（相对当前目标并发）。
         *
         * 旧实现每次只 +1，从慢启动初始值 4 爬到 128 需要 124 次上调、约 248 个成功分片——
         * 慢网或中小文件根本爬不到设定值，用户会看到「线程数始终远低于设定」。
         *
         * 【2026-09-29 实测调整 0.5 → 1.0】0.5 时从 4 到 16 需要 4 档（4→6→9→13→16），
         * 每档要等一个分片完成。改为 1.0（即翻倍）后只需 2 档（4→8→16），
         * 配合阈值=1，爬满时间从「约 8 个分片」降到「约 2 个分片」。
         *
         * 仍然保留分档（而非一上来全开）：服务器在档与档之间仍有观察窗口，
         * 真限流会被 `throttleDown` 捕获并压回；只是不再让用户白等几秒。
         */
        const val RAMP_UP_FACTOR = 1.0

        /**
         * 爬升检查间隔（毫秒）。
         *
         * 【为什么用时间而不是"每 N 个成功分片"】分片时长 = 块大小 / 单连接速率，
         * 于是**按成功次数爬升会被块大小放大**：8MB 块 + 8MB/s 单连接 = 每档要等 1 秒。
         * 实测（512MB/16连接/每连接 8MB/s）：按次数爬升网络段 6.01s，直接全开 4.21s，
         * 理论下限 4.00s —— 慢启动白吃 1.8s，且**文件越大、连接越慢，白吃越多**，
         * 恰好是最需要并发来提速的场景爬得最慢。
         *
         * 改为每 [RAMP_INTERVAL_MS] 检查一次：窗口内有成功、且没被限流 → 上调一档。
         * 爬满时间从此与分片大小无关，只与窗口数有关（16 连接约 2 档 ≈ 0.5s）。
         *
         * 安全性不变：真限流会走 [throttleDown]（乘性减半）并收紧 [ceiling]，
         * 爬升再快也会被压回来；窗口内出现过限流就不上调。
         */
        /**
         * 爬升检查间隔（毫秒）。
         *
         * 【2026-10-28 实测 250 → 100】慢启动从 4 条连接爬到 16 需要两档。250ms 一档时
         * 前 500ms 只有 4~8 条连接在跑；对 256MB 这种一两秒就下完的文件，这半秒
         * 直接吃掉 15% 的吞吐（实测 256MB/16 连接：默认 1850ms，关掉慢启动 1558ms）。
         *
         * 降到 100ms：两档 200ms 到顶，成本压到原来的 40%，同时保留慢启动的
         * **保护形态**（仍然是一档一档上去，给服务器观察窗口，不是一上来全开）。
         * 网盘/CDN 的风控看的是"瞬时并发"，与档位间隔无关，所以保护没有变弱。
         */
        const val RAMP_INTERVAL_MS = 100L

        /** 被 park 的协程轮询间隔（毫秒）。 */
        const val PARK_POLL_MS = 80L

        /** 吞吐自适应的窗口长度；两个稳定窗口合并后交给候选档控制器。 */
        const val ADAPTIVE_SAMPLE_INTERVAL_MS = 500L

        /**
         * 自适应模式的起始并发。
         *
         * 8 与 AB Download Manager 的默认线程数一致，也是多数 CDN 的安全区间。
         * 30% 以上的提升会触发下一次翻倍；没有提升就停在这里，不再先冲到
         * 128/256 再花时间降回来。
         */
        const val ADAPTIVE_INITIAL_CONNECTIONS = 8

        /**
         * 判定窗口有效时，「在飞连接数相对目标并发」的最低比例。
         *
         * 【为什么不要求 100%】worker 完成一个分片到领取下一个之间有瞬时空档，越高速越频繁；
         * 要求满载会把绝大多数窗口判废，特性就永远学不到东西。
         * 【为什么也要有个下限】若目标 128、实际只有 10 条连接在跑（服务器握手挤住、
         * 或请求大面积退避），此时测到的低吞吐是「并发根本没起来」，不是「128 不如 64」——
         * 拿它当档位比较依据会把结论搞反。
         */
        const val ADAPTIVE_MIN_ACTIVE_RATIO = 0.5

        /** 队列暂空但仍有在飞分片时的等待间隔（毫秒）。 */
        const val DRAIN_WAIT_MS = 30L

        /**
         * 收尾托管的切分对齐粒度（字节）。
         *
         * 对齐到 64KB（而不是任意字节）的理由：避免产生怪异的 Range 边界
         * （某些 CDN 对非对齐的 Range 响应较慢或直接拒绝），
         * 也便于观察/复现。aria2-next 同样用 `64_k` 作为拆分量子下限。
         */
        const val TAIL_ALIGN = 64L * 1024

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
         * 天花板恢复的节流间隔（毫秒）。
         *
         * 背景：背压会把并发与天花板一起压低，之后必须**能自己爬回来**（否则会永久卡在低并发）。
         * 但试探本身可能撞一次 429/503，所以不能太频繁。
         * 5 秒一次、每次最多翻倍：从 1 恢复到 16 约 20 秒，代价最多 4 次被拒。
         */
        /**
         * 两次背压降档之间的最小间隔（毫秒）。
         *
         * 服务器过载时 429/503 是**成批**来的；逐个降档会让并发在百毫秒内从 32 掉到 1。
         * 取 400ms：批量拒绝只算一次，而持续被拒仍会在几百毫秒内逐级降到位。
         */
        const val BACKPRESSURE_MIN_INTERVAL_MS = 400L

        const val FIRST_RECOVER_INTERVAL_MS = 1_000L

        /** 恢复间隔的上限：再慢也不该让一次瞬时限速把并发按死超过 8 秒。 */
        const val MAX_RECOVER_INTERVAL_MS = 8_000L

        /**
         * 进度落盘间隔（毫秒）。
         *
         * 侧车是原子小文件（几百片时约十几 KB），但没必要每个字节块都写一次。
         * 崩溃最多回退这么久的进度，而"重下同一区间"是幂等的 ——
         * 安全方向永远是**少记**而不是多记。
         */
        const val PROGRESS_PERSIST_INTERVAL_MS = 1000L

    }

    sealed interface Outcome {
        object Completed : Outcome
        object NeedWholeFallback : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /**
     * 分片。区间**默认不变**，但收尾托管（[TurboConfig.tailAssist]）可在运行时
     * 缩短 [end] —— 这是「空闲连接接管慢分片剩余部分」的实现基础。
     *
     * 【为什么可以安全缩短】拆分 = [A,B] 缩短为 [A,M] + 新分片 [M+1,B]。
     * 原分片文件始终是 A 起的**连续前缀**，长度 M-A+1；续传时
     * `existing = length()`、`from = start + existing = M+1` → 写入位置正确。
     * 进度以 [Segment.written] 为准（不再依赖文件长度反推覆盖区间）。
     */
    /**
     * 分片 = 目标文件里的一段连续区间 + 已写入字节数。
     *
     * 与旧实现的区别：不再对应任何磁盘文件。字节直接按偏移写进目标文件，
     * 所以这里没有 `file()`、也没有"文件名里记着计划区间"的绕法
     * —— 那些复杂度都是「每片一个临时文件」带来的。
     *
     * 区间末端仍可在运行时被收尾托管缩短（[end] 可写），这是托管机制的基础。
     */
    private class Segment(
        val piece: SegmentStore.Piece,
        var attempts: Int = 0,
        /**
         * **限流（429/503）单独的计数**，不与 [attempts] 混用。
         *
         * 二者语义不同：429/503 是「并发太高，请慢一点」的**信号**（正确响应是降并发 + 重试），
         * [attempts] 是「这次没拿到」的**失败**（有限次重试后放弃）。
         * 旧实现混用，导致一个在并发降下来之前被拒几次的分片就被丢掉 → 整个任务失败。
         */
        var throttleAttempts: Int = 0,
        /** 已被托管的次数（防病态裂变，上限 [TurboConfig.maxSplitsPerSegment]）。 */
        var splits: Int = 0,
    ) {
        /**
         * 本分片**当前这一次**请求的句柄。
         *
         * 【为什么分片要持有它】卡死恢复原先调的是 `downloader.cancelCalls(taskId)` ——
         * 那个方法取消**整个任务**的所有在飞请求。于是一条连接卡住，另外几十条正常的
         * 连接也被一起打断、全部回投重试：本来只损失一个分片，变成了整任务的进度倒退。
         * 有了这个句柄，卡死只取消那一条，其余连接不受影响。
         */
        val callRef = java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>(null)

        /** 上次观测到的已写入字节数（供卡死检测判断这一条是否还在动）。 */
        @Volatile
        var lastObservedWritten: Long = written

        /** 上次"还在动"的时间戳（毫秒）。 */
        @Volatile
        var lastProgressAt: Long = System.currentTimeMillis()

        /** 本轮尝试开始时的已写入字节数（用来判断"这一轮到底进没进数据"）。 */
        @Volatile
        var writtenAtAttemptStart: Long = written

        /**
         * **这一片自己**的最近一次失败原因。
         *
         * 与任务级的 `failureByTask` 分开：后者是单槽，128 条连接同时出错时只会留下最后一条，
         * 于是"重试 N 次仍失败"的报告可能指着别人的原因（实测出现过 Canceled 冒充读超时）。
         */
        @Volatile
        var lastFailure: String? = null

        val start: Long get() = piece.start
        var end: Long
            get() = piece.end
            set(v) { piece.end = v }
        var written: Long
            get() = piece.written
            set(v) { piece.written = v }

        val length: Long get() = piece.length
        val complete: Boolean get() = piece.complete
        val remaining: Long get() = (piece.length - piece.written).coerceAtLeast(0)
    }

    suspend fun run(
        taskId: Long,
        url: String,
        total: Long,
        store: SegmentStore,
        headers: Map<String, String>,
        connections: Int,
        onBytes: suspend (delta: Long, absolute: Long) -> Unit,
        onConnections: (Int) -> Unit,
        isActive: () -> Boolean,
        /**
         * `If-Range` 校验器（可选）：分片是多个请求拼一个文件，
         * 若中途源站换了内容，新旧字节会混在一起且长度校验仍通过。
         * 带上它可让服务器在内容已变时返回整文件（200），由上层重试而非写入错数据。
         */
        ifRange: String? = null,
    ): Outcome = coroutineScope {
        // per-host 并发上限：默认 0 = 不限（只有个别 CDN 如迅雷才需要限，且应由调用方按 host 选择性启用）。
        // 警告：若对所有下载统一设一个小值（如 16），会把单个下载（全部同一 host）直接限死到该值，
        // 表现为“设 64 线程却只跑 16 / 速度骤降”。这里仅当 >0 时限幅。
        val hostCap = config.maxConnectionsPerHost.takeIf { it > 0 } ?: Int.MAX_VALUE
        val workers = connections.coerceIn(1, 256).coerceAtMost(hostCap).coerceAtLeast(1)
        val debugScheduler = System.getenv("TURBODL_DEBUG_SCHED") == "1"

        // ---------- 专用阻塞 IO 调度器（关键吞吐修正）----------
        // Dispatchers.IO 的默认并行度是 max(64, CPU 核数)。分片下载是**阻塞式** socket read，
        // 一个分片占死一个线程；因此设 128 连接时实际最多只有 ~64 个能同时搜数据，
        // 其余在调度器队列里干等——这既压低吞吐，也是“线程数卡在 64”的硬天花板。
        // 另外与其他库共用 Dispatchers.IO 还会互相抢线程。故为本任务开专用调度器，
        // 并行度 = workers + 少量余量（给守护/回调用）。
        val ioDispatcher = Dispatchers.IO.limitedParallelism(workers + 4)

        val adaptiveEnabled =
            config.adaptiveConcurrency && config.globalSpeedLimitBytesPerSec <= 0
        /**
         * Initial grid width.
         *
         * Adaptive mode must not pre-split for the *requested ceiling*. A request
         * for 256 connections that settles at 8 used to produce 1024 tiny files
         * and then process them through 8 workers -- all the request/state cost
         * with none of the parallelism. Start from the conservative concurrency
         * and grow the grid later only if measured throughput justifies it.
         */
        val gridConcurrency = if (adaptiveEnabled) {
            min(
                workers,
                config.slowStartInitial.takeIf { it > 0 }
                    ?: min(workers, ADAPTIVE_INITIAL_CONNECTIONS),
            )
        } else {
            workers
        }

        // ---------- 块大小：按「连接数」反推，保证固定 N 线程全部跑满 ----------
        // 上限 maxSegmentsPerTask（默认 128，**0 = 不限**）：块数不再随连接数无限膨胀
        // （原因、实测与"收益尚未可靠量化"的警告见 TurboConfig.maxSegmentsPerTask）。
        val segCap = if (config.maxSegmentsPerTask <= 0) Long.MAX_VALUE
        else config.maxSegmentsPerTask.toLong()
        val targetSegments = (gridConcurrency.toLong() *
            config.segmentsPerConnection.coerceIn(1, 64).toLong())
            .coerceAtMost(segCap)
        val effBlock = run {
            // blockSize 是**硬上限**，永远优先；minSegmentSize 是**软下限**，被上限压制。
            // 二者取"上限优先"是为了让 `minSegmentSize > blockSize` 这种组合仍能表达
            // （对齐 aria2/Motrix 的 20MB 分片需要它），而不会让下限压倒上限。
            val hardCap = max(1L, config.blockSize)
            val softFloor = min(config.minSegmentSize, max(1L, total / gridConcurrency))
                .coerceAtMost(hardCap)
            val byConnections = if (targetSegments > 0) total / targetSegments else total
            byConnections.coerceIn(1L, hardCap).coerceAtLeast(softFloor)
        }

        // ---------- 网格：优先沿用侧车里的旧网格 ----------
        // 分片边界由**首次**创建时决定并写进侧车；续传时照抄。
        // 否则换了连接数就会得到不同边界，进度记录对不上 → 续传失效。
        val allSegments = store.loadOrCreate(effBlock).map { Segment(it) }
        val segmentList = java.util.Collections.synchronizedList(allSegments)

        // ---------- 续传：已完成的分片跳过，累加已下载量 ----------
        val downloaded = AtomicLong(0)
        val pending = java.util.PriorityQueue<Segment>(compareBy { it.start })  // 分片优先级：靠前优先
        val pendingLock = Any()
        for (seg in allSegments) {
            if (seg.complete) {
                downloaded.addAndGet(seg.length)
            } else {
                synchronized(pendingLock) { pending.offer(seg) }
                downloaded.addAndGet(seg.written)  // 部分完成的续传起点
            }
        }
        downloaded.set(min(downloaded.get(), total))

        // ---------- 读缓冲：**每个 worker 一个、整个任务复用** ----------
        // 旧实现每个分片新建一个 ByteArray（最高 1MB）：512 片就是 512 次大数组分配，
        // 高并发下纯属 GC 负担。这里按并发数一次性分配，总量仍受 effectiveIoBufferSize 约束。
        val readBufferSize = config.effectiveIoBufferSize(workers)
        val buffers = arrayOfNulls<ByteArray>(workers)

        /** 进度落盘节流。 */
        var lastPersistAt = 0L

        val needWholeFallback = AtomicBoolean(false)
        val failReason = AtomicReference<String?>(null)
        val rangeIgnoredCount = AtomicInteger(0)
        val consecutiveFailures = AtomicInteger(0)
        /**
         * 窗口内的成功计数（用于时间驱动的爬升判断）。
         *
         * 注意它不是"连续"计数：由 [rampLoop] 每 250ms 清零一次，
         * 因此语义是「这 250ms 里有没有成功过」——
         * 这正是时间驱动爬升需要的信号（旧的按次计数会被分片时长放大）。
         */
        val consecutiveSuccesses = AtomicInteger(0)

        /** 本窗口内是否出现过限流（429/503）。有则不爬升，把窗口让给背压生效。 */
        val windowThrottled = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * 本窗口内是否出现过任何非成功结果（429/503、网络失败、授权失效、Range 被忽略）。
         *
         * 【为什么按窗口而不是用全局的 [consecutiveFailures]】后者是跨窗口的衰减计数，
         * 在真实网盘 CDN（频繁 502/503）上会长期不为 0，于是**每一个**采样窗口都被判废，
         * 特性就成了"默认开着但从不生效"。而判断"多开的连接有没有换来吞吐"只需要
         * **本窗口**是干净的：窗口内有失败，测到的吞吐就不是这条链路的稳态能力。
         * 背压与降并发仍由 [windowThrottled]/[consecutiveFailures] 那套负责，两者不混。
         */
        val windowHadFailure = java.util.concurrent.atomic.AtomicBoolean(false)

        // ---------- 并发闸门：workers 个协程按 idx 与 desired 自闸门 ----------
        // 慢启动：初始目标并发从较小值开始，随持续成功逐步升到 workers，
        // 避免瞬时几十连接同时握手冲击服务器/被风控，也避免小文件过度建连。
        /**
         * 续传时直接满速起跑。
         *
         * 慢启动的目的是"别一上来就冲击服务器" —— 但它防的是**第一次见面**。
         * 续传时这些连接在上一轮已经证明服务器接受过，从头再爬一遍只是白等：
         * 用户点了"继续"，期望是立刻恢复原来的速度，而不是再看一次起步。
         *
         * 判据用"有任一分片已写入过字节"，因为那只可能来自上一轮的进度。
         */
        val resuming = allSegments.any { it.written > 0 }
        val slowStartInit = when {
            !config.slowStart -> workers
            // 自适应模式从保守档起跑，再由实测吞吐决定是否继续翻倍。
            // 不能先冲到用户上限再往下探：128 路在回环上可能在控制器拿到第一个
            // 有效窗口前就把吞吐打崩，用户看到的就是“线程越多反而越慢”。
            adaptiveEnabled ->
                min(
                    workers,
                    config.slowStartInitial.takeIf { it > 0 }
                        ?: min(workers, ADAPTIVE_INITIAL_CONNECTIONS),
                )
            resuming -> workers                       // 续传：直接到顶
            config.slowStartInitial > 0 -> min(config.slowStartInitial, workers)
            // 默认初始值随设定并发缩放（至少 4）：固定 4 在 128 连接下起点太低，
            // 配合比例上调可快速到顶，同时避免一上来就全开冲击服务器。
            else -> min(workers, max(4, workers / 4))
        }
        /** 动态目标并发（慢启动从 slowStartInit 升、背压时降；自适应时由控制器定档）。 */
        val desired = AtomicInteger(slowStartInit)
        /**
         * Lazily start worker coroutines only when the scheduler actually raises
         * the target concurrency. A request for c=256 used to allocate and run
         * 256 parked workers even when adaptation correctly chose 8; the idle
         * workers then polluted the very benchmark they were meant to improve.
         */
        var ensureWorkersStarted: (Int) -> Unit = {}

        /** 用户级总限速会人为封顶吞吐，因此该任务不启用吞吐学习。 */
        val adaptive = if (adaptiveEnabled) AdaptiveConcurrencyController(
            maximumConcurrency = workers,
            initialConcurrency = slowStartInit,
            probeDownFirst = false,
        ) else null
        val throughputLock = Any()
        var throughputWindowBytes = 0L
        var throughputWindowStartedAt = System.nanoTime()
        var throughputWindowConcurrency = desired.get()
        var throughputWindowEligible = true

        /**
         * 学到的「服务器能接受的并发天花板」。
         *
         * 没有它，背压降下去之后 ramp-up 仍会按比例爬回 [workers] —— 然后再撞墙、再降、再爬，
         * 形成 AIMD 震荡：实测（服务器并发上限 16，N=128）撞了 **323 次 503**。
         * 背压下调时**同步收紧天花板**，恢复期最多爬到这里；长时间稳定后才 +1 缓步试探
         * （试探本身会撞墙，所以步长必须是 +1 而不是按比例，让失败的代价最小）。
         */
        val ceiling = AtomicInteger(workers)

        /** 在天花板处稳定成功的分片计数（保留给诊断；恢复策略改用时间节流）。 */
        val ceilingStableCount = AtomicInteger(0)

        /** 上次抬高天花板的时间（毫秒）—— 见 rampUpIfHealthy 里的恢复说明。 */
        var lastCeilingRaiseAt = 0L

        /** 当前的恢复间隔（毫秒，递增）。 */
        var recoverIntervalMs = FIRST_RECOVER_INTERVAL_MS

        /** 上次背压降档的时间（毫秒）—— 见 throttleDown 的说明。 */
        var lastThrottleDownAt = 0L

        /**
         * 天花板是否被**背压**压低（而不是被自适应控制器主动降档）。
         *
         * 【为什么必须区分】两者都表现为 ceiling 变小，但含义相反：
         *  - 背压压低 = "服务器暂时不让这么快" → 必须能恢复，否则永久卡在低并发；
         *  - 自适应降档 = "多开没用，我选择少开" → 恢复逻辑去抬它就是跟自己的结论打架。
         * 只有前者允许被 rampUpIfHealthy 抬回来。
         */
        val ceilingCutByBackpressure = java.util.concurrent.atomic.AtomicBoolean(false)

        /** 限流（429/503）重试预算：比 maxRetries 宽（原因见 companion 里 `throttleBudget` 的注释）。 */
        val throttleRetryBudget = throttleBudget(config.maxRetries)
        /** 当前真实在传输的连接数（供 UI 展示实际并发）。 */
        val activeConns = AtomicInteger(0)
        /** 正在传输中的分片数（判定“队列空但仍可能有重试回投”）。 */
        val inFlight = AtomicInteger(0)

        /**
         * 当前在飞分片（供收尾托管挑选"剩余最多"的那一个）。
         *
         * 只在收尾阶段被读，写入点也少（领取/归还），用并发集合即可，无需锁。
         */
        val inFlightSegments: MutableSet<Segment> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** 收尾托管已生效次数（用于日志与调参；也在测试里做行为断言）。 */
        val tailAssistCount = AtomicInteger(0)

        /** 本任务实际写入完成的分片数（用于收尾判定：块数少到不够分时才有托管价值）。 */
        onConnections(slowStartInit)

        /**
         * 把当前网格与写入进度落进侧车。
         *
         * **先写数据、后落进度**：崩溃时最多回退一小段并覆盖重下（幂等）。
         * 反过来会留下"进度说有、文件里没有"的空洞 —— 那才会产出损坏文件。
         */
        fun persistProgress(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastPersistAt < PROGRESS_PERSIST_INTERVAL_MS) return
            lastPersistAt = now
            runCatching { store.persist(segmentList.toList().map { it.piece }) }
        }

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

        /** 开始一个干净的吞吐窗口；窗口跨过并发变化时一律丢弃。 */
        fun resetThroughputWindow(nowNanos: Long, concurrency: Int) = synchronized(throughputLock) {
            throughputWindowBytes = 0L
            throughputWindowStartedAt = nowNanos
            throughputWindowConcurrency = concurrency
            throughputWindowEligible = concurrency == desired.get()
        }

        /**
         * 收到已写入字节时更新总吞吐窗口。不是每连接速率；每连接独立限速时，
         * 总吞吐随连接增加而增加，控制器会保留高并发。
         */
        fun recordThroughputBytes(bytes: Long) {
            if (adaptive == null || bytes <= 0) return
            synchronized(throughputLock) {
                if (throughputWindowConcurrency != desired.get()) throughputWindowEligible = false
                throughputWindowBytes += bytes
            }
        }

        /**
         * 检查并结算候选档吞吐。资格不足时只重置窗口，不把限速/失败/收尾误判成平台期。
         *
         * 【资格判据为什么是这几条】
         *  - `measuredAt == desired`：窗口跨越了并发变化 → 测到的是混合吞吐，不能用。
         *  - 不是收尾（队列仍有活可领 或 在飞分片够多）：收尾阶段并发天然塌下去。
         *  - 在飞连接达到目标的一半：[ADAPTIVE_MIN_ACTIVE_RATIO] 的说明。
         *  - 窗口内无 429/503、无连续失败：那是背压的地盘，吞吐不能用来做档位判断。
         */
        fun observeThroughput(nowNanos: Long, queued: Int) {
            if (adaptive == null) return
            synchronized(throughputLock) {
                val elapsed = nowNanos - throughputWindowStartedAt
                if (elapsed < ADAPTIVE_SAMPLE_INTERVAL_MS * 1_000_000L) return
                val measuredAt = throughputWindowConcurrency
                val bytes = throughputWindowBytes
                val inFlightNow = inFlight.get()
                val notTail = queued > 0 || inFlightNow >= measuredAt
                val activeEnough = activeConns.get() >= max(1, (measuredAt * ADAPTIVE_MIN_ACTIVE_RATIO).toInt())
                val eligible = throughputWindowEligible &&
                    measuredAt == desired.get() && notTail && activeEnough &&
                    !windowHadFailure.get() && bytes > 0
                val throughput = if (elapsed > 0) bytes * 1_000_000_000.0 / elapsed else 0.0
                throughputWindowBytes = 0L
                throughputWindowStartedAt = nowNanos
                throughputWindowConcurrency = desired.get()
                throughputWindowEligible = true
                windowHadFailure.set(false)
                if (!eligible) {
                    // 窗口不合格（收尾/限速/失败/并发刚变）：不做任何档位判断。
                    return
                }
                val decision = adaptive.observe(
                    throughput = throughput,
                    // 合格窗口本身就意味着 desired 在本窗口内保持稳定；控制器现在负责
                    // 从起始档向上探测，因此不能再用“到达用户上限”作为学习前提。
                    rampDone = true,
                    concurrencyLimit = min(workers, ceiling.get()),
                )
                if (debugScheduler) {
                    println(
                        "[sched] throughput=${"%.2f".format(throughput / 1_048_576)}MB/s " +
                            "desired=${desired.get()} active=${activeConns.get()} " +
                            "target=${decision.target} ceiling=${min(workers, ceiling.get())}",
                    )
                }
                decision.suggestedCeiling?.let { suggested ->
                    // 这是控制器的"选择"，不是服务器的强制 —— 清掉背压标记，
                    // 免得恢复逻辑把它又抬回去，跟控制器自己的结论打架。
                    ceilingCutByBackpressure.set(false)
                    ceiling.updateAndGet { current -> min(current, suggested) }
                }
                decision.target?.let {
                    val target = it.coerceIn(1, ceiling.get())
                    desired.set(target)
                    ensureWorkersStarted(target)
                }
                if (decision.target != null || decision.suggestedCeiling != null) {
                    reportConns()
                    throughputWindowBytes = 0L
                    throughputWindowStartedAt = nowNanos
                    throughputWindowConcurrency = desired.get()
                    throughputWindowEligible = true
                }
            }
        }

        /**
         * 收尾托管：把一个在飞分片的**后半段**让给新连接，自己保留前半段。
         *
         * 思路来自 aria2-next 的 `CurlSession::rebalanceEndgame`
         * （`src/stream/StreamScheduling.cc`），但触发条件更严格。
         *
         * 【触发条件】调用方必须已确认「队列空 && 有空闲 worker」——
         * 即大块传输阶段**完全不触发**，只在收尾、并发自然塌下去的窗口里生效。
         * 这样新增的请求数只发生在"反正也要等"的时间里，不增加对服务器的冲击面。
         *
         * 【挑谁拆】剩余字节最多的那个；且剩余量必须 >= 2×[TurboConfig.tailAssistMinBytes]
         * （拆完两半都不低于下限，避免"拆出一个更小的尾巴"）。
         *
         * 【拆多少】在剩余区间的**中点**切，并对齐到 64KB（避免怪异的 Range 边界）。
         * aria2-next 用"剩余时间 vs 请求成本"来估算量子，这里简化为固定对齐 ——
         * 我们没有逐连接的实时速率，而且固定对齐已足够避免碎片。
         *
         * @return 是否发生了托管
         */
        fun tryTailAssist(): Boolean {
            if (!config.tailAssist) return false
            if (inFlightSegments.isEmpty()) return false
            // 候选：剩余最多、且足够大、且未超拆分次数
            var best: Segment? = null
            var bestRemaining = 0L
            val minBytes = config.tailAssistMinBytes
            for (s in inFlightSegments) {
                if (s.splits >= config.maxSplitsPerSegment) continue
                // 已下多少 = 该分片的写入进度（由 onProgress 实时推进）
                val remaining = s.remaining
                if (remaining < 2 * minBytes) continue
                if (remaining > bestRemaining) {
                    bestRemaining = remaining
                    best = s
                }
            }
            val seg = best ?: return false

            // 在剩余区间的中点切，对齐 64KB
            val done = seg.written
            val from = seg.start + done
            val keep = ((bestRemaining / 2) / TAIL_ALIGN) * TAIL_ALIGN
            if (keep < minBytes) return false
            val cutAt = from + keep - 1          // 原分片保留 [start, cutAt]
            val tailStart = cutAt + 1            // 新分片接手 [tailStart, end]
            if (tailStart > seg.end) return false

            val oldEnd = seg.end
            // 先原子缩短原分片：下载循环会在下一次检查时停止写入超过 cutAt 的数据
            seg.end = cutAt
            seg.splits++
            // 新分片是一个**新的 Piece**，随网格一起落进侧车，续传时照抄。
            val tail = Segment(SegmentStore.Piece(tailStart, oldEnd))
            segmentList.add(tail)
            offer(tail)
            tailAssistCount.incrementAndGet()
            return true
        }

        // ---------- 卡死（stall）守护：思路参考 aria2 --lowest-speed-limit / curl --speed-limit ----------
        // OkHttp 的 readTimeout 只能管“单次 read 阻塞多久”；若 CDN 涓涓吐字节（每几十秒几字节），
        // 永远不超时，表现为“显示下载中但进度几乎不动”。因此额外监控任务级总字节：
        // 超过 stallTimeoutMs 零增长 → cancel 所有在飞请求，分片回到重试链路（重建连接、可能换节点）。

        /**
         * 卡死守护：**只掐不动的那一条连接**，不碰其它在飞请求。
         *
         * 旧实现一发现"总字节 N 秒没涨"就 `cancelCalls(taskId)` —— 取消整个任务的所有请求。
         * 那一条卡住的连接确实被解决了，但另外几十条正常传输的连接也被打断 → 它们
         * 各自回投、各自退避重试，一次卡顿换来整任务几十秒的倒退。真实 CDN 上
         * 单连接半死不活并不罕见，这个代价远大于收益。
         *
         * 现在逐个分片看：**这一条**自己的写入进度多久没动。只取消它，其余照跑。
         * 只有当"所有在飞分片都停住"且反复发生时，才判定任务整体卡死并失败。
         */
        val watchdog = if (config.stallTimeoutMs > 0) launch(ioDispatcher) {
            while (isActive() && !needWholeFallback.get()) {
                delay(1000)
                val now = System.currentTimeMillis()
                if (queueEmpty() && inFlight.get() == 0) continue
                for (seg in inFlightSegments) {
                    val w = seg.written
                    if (w != seg.lastObservedWritten) {
                        seg.lastObservedWritten = w
                        seg.lastProgressAt = now
                        continue
                    }
                    // 【只看"已经收到过数据之后又停了"】
                    // 刚建立连接、服务器还在排队时，一个字节都没来是**正常**的 ——
                    // 那种情况该由 OkHttp 的读超时（readTimeoutMs）兜底。
                    // 若在这里也判停滞，128 连接压到队列很深的服务器上就会反复
                    // 取消"其实正在排队"的连接：实测表现为「分片重试 6 次仍失败（Canceled）」。
                    val gotDataThisAttempt = seg.lastObservedWritten > seg.writtenAtAttemptStart
                    if (gotDataThisAttempt && now - seg.lastProgressAt >= config.stallTimeoutMs) {
                        // 只掐这一条：它会拿到 IOException → FAILED → 回投重试（可能换节点）。
                        //
                        // 【这里**不**累计"任务级卡死次数"，也不判定任务失败】
                        // 曾经的写法是"卡死事件累计超过 maxStallRecoveries 就判整个任务失败"，
                        // 于是**几个分片各自偶尔断连**就会把任务判死：
                        // 实测（模拟服务器前 3 个请求只发一半就断）→ 引擎报
                        // "下载停止响应（45s 无任何字节），已重试 3 次仍失败"，
                        // 而实际上每个分片重试一次就能成功。
                        // 个别连接停滞由**分片自己的重试预算**（maxRetries）兜底，
                        // 那才是"这一片不行"的正确归属；任务级失败留给"所有分片都耗尽预算"。
                        runCatching { seg.callRef.get()?.cancel() }
                        seg.lastProgressAt = now   // 给重试留出一个完整窗口，避免连打
                    }
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

        /**
         * 背压下调：乘性减半，但不低于 4（用户上限本身小于 4 时取上限）。
         * 仅调整目标整数，被 park 的协程自然让出。
         *
         * 【为什么要按时间限流】
         * 服务器过载时往往**成批**返回 429/503：32 条连接同时开门，可能一次收到 20 多个 503。
         * 若每个都触发一次减半，并发会在 100ms 内从 32 掉到 1 —— 而实际上服务器只是
         * "别一下子来 32 条"，降到 16 就够。这种"一步到位砍到底"之后再靠恢复慢慢爬，
         * 用户看到的就是"刚开始几百 KB，然后一直 1 个线程"。
         *
         * 因此：**每 [BACKPRESSURE_MIN_INTERVAL_MS] 最多降一档**。批量拒绝只算一次，
         * 真的持续被拒时下一档在几百毫秒后自然到来，不会漏掉该降的情况。
         */
        fun throttleDown() {
            if (config.backpressureConsecutiveFailures <= 0) return
            val now = System.currentTimeMillis()
            if (now - lastThrottleDownAt < BACKPRESSURE_MIN_INTERVAL_MS) return
            lastThrottleDownAt = now
            val cur = desired.get()
            val floor = min(workers, 4)
            val target = max(floor, cur / 2)
            if (target < cur) {
                desired.set(target)
                // 429/503 背压高于吞吐学习：清掉旧基线，不能让自适应立即爬回被拒档位。
                ceiling.updateAndGet { min(it, target) }
                // 打上"这是背压压低的"标记 —— 之后 rampUpIfHealthy 才有权把它抬回来。
                // 没有这个标记，一次瞬时 503 就能把并发永久按在 1（实测真实网络上的现象）。
                ceilingCutByBackpressure.set(true)
                adaptive?.resetTo(target)
                synchronized(throughputLock) {
                    throughputWindowBytes = 0L
                    throughputWindowStartedAt = System.nanoTime()
                    throughputWindowConcurrency = target
                    throughputWindowEligible = false
                }
                reportConns()
            }
        }

        /**
         * 背压恢复：持续成功后逐步把并发恢复到设定值。
         *
         * 旧实现只降不升，一次瞬时 429/503 就永久把并发减半（可逐步降到 1），
         * 网络恢复后再也跑不满——这正是「下到后面线程数掉下去、速度只剩几 KB」的主因。
         * 现在只要持续成功就一步步把 desired 加回 workers，被 park 的协程随即复活。
         *
         * 【2026-09-29 改为时间驱动】原实现挂在「每个分片成功」上，于是爬升速度
         * 被**分片时长**绑架：分片越大/单连接越慢，每档等得越久，而大文件正是最需要
         * 尽快跑满并发的场景。现由 [rampLoop] 每 [RAMP_INTERVAL_MS] 调用一次：
         * 窗口内有成功且未限流 → 上调一档。爬满时间与分片大小解耦。
         */
        fun recoverCeilingIfDue(cap: Int) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastCeilingRaiseAt < recoverIntervalMs) return
            lastCeilingRaiseAt = nowMs
            recoverIntervalMs = min(recoverIntervalMs * 2, MAX_RECOVER_INTERVAL_MS)
            val step = if (cap * 2 <= workers) cap else cap + 1
            val next = min(workers, max(cap + 1, step))
            ceiling.set(next)
            if (next >= workers) ceilingCutByBackpressure.set(false)
        }

        fun rampUpIfHealthy() {
            if (windowThrottled.get()) return                 // 窗口内被限流过：先稳住
            val cur = desired.get()
            val cap = ceiling.get()

            // 自适应开启时，desired 只能由控制器按实测吞吐调整。
            // 以前这里仍会按 100ms 一档把 16 直接冲到 128/256，控制器还没来得及测
            // 16 路的基线，链路已经被高并发打崩。这里只保留“背压后的天花板恢复”，
            // 其余爬升全部交给 AdaptiveConcurrencyController。
            if (adaptive != null) {
                if (cap < workers && ceilingCutByBackpressure.get()) {
                    recoverCeilingIfDue(cap)
                }
                return
            }

            if (cur >= workers) return
            if (cur >= cap) {
                if (cap < workers && ceilingCutByBackpressure.get()) {
                    recoverCeilingIfDue(cap)
                }
                return
            }
            // 按比例上调（至少 +1）：log2 档数即可到顶。
            val step = max(1, (cur * RAMP_UP_FACTOR).toInt())
            val next = min(min(workers, cap), cur + step)
            desired.set(next)
            ensureWorkersStarted(next)
            reportConns()
        }

        /**
         * 时间驱动的爬升循环：每 [RAMP_INTERVAL_MS] 尝试上调一档。
         *
         * 与 [rampUpIfHealthy] 的分工：本循环只负责"到点触发"，
         * 是否真的上调由 rampUpIfHealthy 依据窗口内的成功/限流情况决定。
         *
         * 【为什么时间与"分片完成"两个触发都要】二者各覆盖一半场景：
         *  - **分片完成触发**（见 OK 分支）：小块+多块时（8MB 文件切成 512 块、
         *    每块 3ms）完成事件密集，几百毫秒内就能爬满 —— 只靠时间触发反而太慢，
         *    `RampUpReportingTest` 的 128 连接用例就是这么失败的。
         *  - **时间触发**（本循环）：大块+慢连接时（8MB 块 @ 8MB/s = 每块 1 秒）
         *    完成事件稀疏，只靠完成触发要 8 秒才爬满 —— 这正是实测中
         *    网络段被拖到 6.01s（理论 4.00s）的原因。
         *
         * 两者取先到者：小块场景由完成事件驱动，大块场景由时间兜底，
         * 爬升时间从此不再被分片时长绑架。
         */
        val rampLoop = launch(ioDispatcher) {
            var lastBytes = downloaded.get()
            while (isActive() && !needWholeFallback.get()) {
                delay(RAMP_INTERVAL_MS)
                val nowNanos = System.nanoTime()
                val cur = downloaded.get()
                val progressed = cur > lastBytes
                lastBytes = cur
                if (adaptive != null) {
                    // 自适应开启：档位最终由吞吐控制器决定，但**爬升期必须继续爬**。
                    //
                    // 【旧实现的死锁】这里原本无条件 `continue`，把爬升整段跳过；
                    // 控制器则要等 `rampDone = desired >= workers` 才肯离开 WAIT_RAMP。
                    // 两边互等 ⇒ desired 永远停在 slowStartInit（实测 -c 16 全程 4 路、49s；
                    // 关掉自适应恢复爬升后 23s）。
                    observeThroughput(nowNanos, queueSize())
                    if (progressed) windowThrottled.set(false)
                    // 只在控制器尚未开始探测时让爬升工作：一旦开始下探/上探，
                    // desired 必须保持控制器设的档位，否则它测不到真实档位窗口。
                    if (progressed && !adaptive.isProbing()) rampUpIfHealthy()
                    continue
                }
                if (!config.slowStart) continue
                if (!progressed) continue        // 本窗口没进展：不涨，也不清限流标记
                rampUpIfHealthy()
                windowThrottled.set(false)
            }
        }

        val ok = try {
            val jobs = List(workers) { idx ->
                async(ioDispatcher, start = CoroutineStart.LAZY) {
                    val workerBuffer = buffers[idx] ?: ByteArray(readBufferSize).also {
                        buffers[idx] = it
                    }
                    if (debugScheduler) println("[sched] worker idx=$idx entered")
                    if (idx in 1..8) delay(idx * 20L)  // 错峰建连
                    // 【致命失败即停】`failReason` 一旦有值，说明某个分片已经彻底耗尽重试预算 ——
                    // 这个任务注定失败，继续跑其余分片毫无意义。
                    //
                    // 【为什么必须在这里判】旧实现只在 `jobs.awaitAll()` **之后**才看
                    // `failReason`，而 worker 的退出条件是「队列空且没有在飞」。可失败分片
                    // 耗尽后不再入队，其余分片仍会各自走完 `maxRetries` 次指数退避 ——
                    // 于是失败判定被**串行化**：2 MiB / 128 KiB（16 片）在确定性短读的服务器上
                    // 要 ~5 分钟才报错，而这 5 分钟里进度条停在 0%（实测：200 秒仍无终态）。
                    // 用户看到的就是「卡死」。
                    //
                    // 所以：一旦判定失败，立刻让所有 worker 退出循环；在飞请求由下面的
                    // `if (failReason.get() != null) { downloader.cancelCalls(taskId); break }`
                    // 断掉，`jobs.awaitAll()` 随即返回，错误被准确报给宿主。
                    while (isActive() && !needWholeFallback.get() && failReason.get() == null) {
                        // 索引自闸门：超出当前目标并发的协程 park（不占许可、随时可复活）。
                        if (idx >= desired.get()) {
                            if (queueEmpty() && inFlight.get() == 0) break
                            delay(PARK_POLL_MS)
                            continue
                        }
                        val seg = poll()
                        if (seg == null) {
                            // 队列空：先尝试收尾托管（把在飞分片的后半段让给本 worker）。
                            // 只在"没有新块可领"时发生 —— 大块传输阶段队列非空，不受影响。
                            if (inFlight.get() > 0) {
                                if (tryTailAssist()) {
                                    // 托管成功：新分片已入队，立刻回头领取
                                    continue
                                }
                                delay(DRAIN_WAIT_MS); continue
                            }
                            break  // 真正无活可干：退出
                        }
                        if (needWholeFallback.get() || !isActive()) { offer(seg); break }
                        // 【必须在这里重置停滞计时】Segment 对象是在**任务开始**时批量创建的，
                        // 排在队尾的分片可能等了几十秒才被领到。若沿用它创建时的戳，
                        // 这个分片刚开工就会被卡死守护判定为"停滞"并立刻取消 ——
                        // 实测（128 连接 / 12MB）表现为「分片 X 重试 6 次仍失败（IOException: Canceled）」，
                        // 整个任务反复失败。计时必须从**真正开始传数据**算起。
                        seg.lastObservedWritten = seg.written
                        seg.lastProgressAt = System.currentTimeMillis()
                        seg.writtenAtAttemptStart = seg.written
                        inFlight.incrementAndGet()
                        activeConns.incrementAndGet()
                        inFlightSegments.add(seg)
                        reportConns()
                        try {
                            val res = downloader.downloadSegmentInto(
                                taskId = taskId,
                                url = url,
                                pieceStart = seg.start,
                                // 把这次请求的句柄交给分片：卡死守护靠它"只掐这一条"。
                                onCallStarted = { c -> seg.callRef.set(c) },
                                onFailure = { reason -> seg.lastFailure = reason },
                                endProvider = { seg.end },
                                alreadyWritten = seg.written,
                                // 按偏移直写目标文件：没有临时分片文件、没有收尾合并。
                                writeAt = { pos, buf, off, len -> store.writeAt(pos, buf, off, len) },
                                headers = headers,
                                onProgress = { newly, totalWritten ->
                                    // 先推进分片自身进度（托管与续传都读它），再上报总进度。
                                    seg.written = totalWritten
                                    speedLimiter.awaitAllow(newly)
                                    val abs = min(downloaded.addAndGet(newly), total)
                                    recordThroughputBytes(newly)
                                    persistProgress()
                                    if (!isActive()) return@downloadSegmentInto
                                    onBytes(newly, abs)
                                },
                                // 缓冲按 worker 复用：本任务同时跑 workers 个分片，
                                // 每个都按 worker 私有缓冲分配，总量仍受 ioBufferTotalBudgetBytes 约束
                                // （256 连接 × 1MB = 256MB = Android 默认整堆，2026-10-10 真机 OOM 事故）。
                                buffer = workerBuffer,
                                ifRange = ifRange,
                            )
                            when (res) {
                                SegmentResult.OK -> {
                                    // 【S4 修复】不再"每次成功都清零"。
                                    // 旧实现 OK 时 consecutiveFailures.set(0)，导致"间歇 429"
                                    // （如每 8 次出 1 次）永远凑不满连续 4 次失败 → 降级永不触发
                                    // → 引擎持续以高并发冲击服务器 → 被封禁。
                                    // 改为**衰减**：每次成功只减 1，让间歇失败能累积到阈值，
                                    // 同时持续健康时又能自然回落到 0。
                                    consecutiveFailures.updateAndGet { if (it > 0) it - 1 else 0 }
                                    // 【双触发之一：分片完成】小块场景（块数多、每块耗时短）
                                    // 依赖它快速爬升；大块场景由 rampLoop 的时间节拍兜底。
                                    // 详见 rampLoop 的注释。
                                    consecutiveSuccesses.incrementAndGet()
                                    rampUpIfHealthy()
                                    // 分片写完就落一次进度：暂停/崩溃后能从这里接着下。
                                    persistProgress()
                                }
                                SegmentResult.RANGE_IGNORED -> {
                                    windowHadFailure.set(true)
                                    // 单分片被返回整文件：容忍偶发，反复出现才判定服务器不支持 Range。
                                    offer(seg)  // 回投，可能只是该 CDN 节点抖动
                                    if (rangeIgnoredCount.incrementAndGet() >= RANGE_IGNORED_TOLERANCE) {
                                        needWholeFallback.compareAndSet(false, true)
                                    }
                                }
                                SegmentResult.AUTH_EXPIRED -> {
                                    windowHadFailure.set(true)
                                    // 【不降并发】401/403/410 = 授权/时效信号（签名直链过期、
                                    // 反盗链拦截），**不是**"你太快了"。降并发既解决不了它，
                                    // 还会白白拖慢后续重试。
                                    // 正确响应：立即回投重试（下层已遗忘失效终址，
                                    // 下次会重新走原始 URL 解析 → 拿到新签名）。
                                    consecutiveSuccesses.set(0)
                                    seg.attempts++
                                    if (seg.attempts > config.maxRetries) {
                                        failReason.compareAndSet(
                                            null,
                                            "分片 ${seg.start}-${seg.end} 授权/时效失败（401/403/410）" +
                                                "重试 ${seg.attempts} 次仍失败",
                                        )
                                    } else {
                                        // 短暂退避即可：主要成本在"重新解析地址"，
                                        // 不在等待；退避太长反而拖延新签名的获取。
                                        // 与 FAILED 分支同一理由：退避要能被"已判定失败"打断。
                                        val waitMs = backoffMsFor((seg.attempts - 1).coerceAtMost(2))
                                        var waited = 0L
                                        while (waited < waitMs && failReason.get() == null && isActive()) {
                                            delay(200)
                                            waited += 200
                                        }
                                        if (failReason.get() == null && isActive()) offer(seg)
                                    }
                                }
                                SegmentResult.THROTTLED -> {
                                    windowHadFailure.set(true)
                                    // 标记本窗口被限流：rampLoop 会跳过这次爬升，
                                    // 把 250ms 留给背压（throttleDown）生效。
                                    windowThrottled.set(true)
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
                                        // 这一档退避最长可达 60s（maxShift=3 → 1/2/4/8s，但
                                        // backoffMsFor 的通用上限是 60s）。同样必须能被"已判定失败"
                                        // 打断，否则一个已经注定失败的任务要白等满整段退避。
                                        // 【服务器说了算】429/503 常带 Retry-After —— 它比任何指数退避都准确：
                                        // 等太短会继续被拒（白烧重试预算），等太长则白白拖慢整个任务。
                                        val serverHint = downloader.takeRetryAfterMs(taskId)
                                        val waitMs = serverHint
                                            ?: backoffMsFor((seg.throttleAttempts - 1).coerceAtMost(maxShift))
                                        var waited = 0L
                                        while (waited < waitMs && failReason.get() == null && isActive()) {
                                            delay(200)
                                            waited += 200
                                        }
                                        if (failReason.get() == null && isActive()) offer(seg)
                                    }
                                }
                                SegmentResult.FAILED -> {
                                    // 【必须区分"自己的失败"与"连坐"】
                                    // 某个分片耗尽重试后，代码会 cancelCalls 掉所有在飞请求
                                    // （否则其它 worker 还要各自等满读超时）。那些被打断的分片
                                    // 拿到的是 IOException: Canceled —— 那是**别人的问题**。
                                    // 旧实现在这里照样 seg.attempts++，于是几次连坐之后
                                    // 一批无辜分片也"耗尽重试"，最终错误信息还会指着一个
                                    // 根本没出过错的分片。实测（128 连接 / 12MB）：
                                    // 「分片 8978432-9043967 重试 6 次仍失败（IOException: Canceled）」。
                                    // 现在：任务已被判定失败时，此片不计预算、不回投，数据留在盘上等续传。
                                    if (failReason.get() != null) {
                                        // 什么都不做：让任务按已经记录的原因结束。
                                    } else {
                                    windowHadFailure.set(true)
                                    consecutiveSuccesses.set(0)
                                    seg.attempts++
                                    val n = consecutiveFailures.incrementAndGet()
                                    if (seg.attempts > config.maxRetries) {
                                        failReason.compareAndSet(
                                            null,
                                            "分片 ${seg.start}-${seg.end} 重试 ${seg.attempts} 次仍失败" +
                                                (seg.lastFailure?.let { "（$it）" } ?: "")
                                        )
                                        // 立刻断开所有在飞请求：否则其它 worker 手里那些
                                        // 同样卡住的连接还要各等自己的读超时，`awaitAll()` 被拖着。
                                        downloader.cancelCalls(taskId)
                                    } else {
                                        // 网络类失败也做轻度退避，避免瞬时故障下的密集重投。
                                        // 退避期间可能已被判定失败（别的分片耗尽预算），所以要能被
                                        // 打断：`cancelCalls` 只断 HTTP 调用，不会唤醒这个 delay；
                                        // 因此这里用 200ms 切片轮询，一旦 failReason 有值就放弃重投。
                                        val waitMs = backoffMsFor((seg.attempts - 1).coerceAtMost(3))
                                        var waited = 0L
                                        while (waited < waitMs && failReason.get() == null && isActive()) {
                                            delay(200)
                                            waited += 200
                                        }
                                        if (failReason.get() == null && isActive()) offer(seg)
                                    }
                                    if (config.backpressureConsecutiveFailures in 1..n) {
                                        throttleDown(); consecutiveFailures.set(0)
                                    }
                                    }
                                }
                            }
                        } finally {
                            seg.callRef.set(null)
                            activeConns.decrementAndGet()
                            inFlight.decrementAndGet()
                            inFlightSegments.remove(seg)
                            reportConns()
                        }
                    }
                }
            }
            val startedWorkers = java.util.concurrent.atomic.AtomicIntegerArray(workers)
            fun ensureSegmentSupply(targetWorkers: Int) {
                if (targetWorkers <= gridConcurrency) return
                val targetCount = min(
                    segCap,
                    (targetWorkers.toLong() *
                        config.segmentsPerConnection.coerceIn(1, 64).toLong()),
                )
                var added = 0
                synchronized(pendingLock) {
                    val queued = pending.toMutableList()
                    pending.clear()
                    var incomplete = segmentList.count { !it.complete }
                    while (incomplete < targetCount) {
                        var bestIndex = -1
                        var bestRemaining = 0L
                        for (i in queued.indices) {
                            val candidate = queued[i]
                            if (candidate.complete) continue
                            val remaining = candidate.remaining
                            if (remaining > bestRemaining) {
                                bestRemaining = remaining
                                bestIndex = i
                            }
                        }
                        if (bestIndex < 0) break
                        val seg = queued[bestIndex]
                        val from = seg.start + seg.written
                        val remaining = seg.end - from + 1
                        val minPiece = max(config.minSegmentSize, TAIL_ALIGN)
                        if (remaining < 2 * minPiece) break
                        val exactHalf = remaining / 2
                        val firstLen = (exactHalf / TAIL_ALIGN) * TAIL_ALIGN
                        if (firstLen < minPiece || remaining - firstLen < minPiece) break
                        val splitEnd = from + firstLen - 1
                        val oldEnd = seg.end
                        seg.end = splitEnd
                        val suffix = Segment(SegmentStore.Piece(splitEnd + 1, oldEnd))
                        queued += suffix
                        segmentList.add(suffix)
                        incomplete++
                        added++
                    }
                    queued.sortBy { it.start }
                    pending.addAll(queued)
                }
                if (added > 0) persistProgress(force = true)
                if (debugScheduler && (added > 0 || targetWorkers > gridConcurrency)) {
                    println(
                        "[sched] segment supply targetWorkers=$targetWorkers targetCount=$targetCount " +
                            "added=$added incomplete=${segmentList.count { !it.complete }} pending=${queueSize()}",
                    )
                }
            }

            ensureWorkersStarted = { target ->
                ensureSegmentSupply(target)
                val count = min(workers, target.coerceAtLeast(0))
                for (idx in 0 until count) {
                    if (startedWorkers.compareAndSet(idx, 0, 1)) {
                        val begun = jobs[idx].start()
                        if (debugScheduler && idx >= gridConcurrency) {
                            println(
                                "[sched] start idx=$idx begun=$begun active=${jobs[idx].isActive} " +
                                    "completed=${jobs[idx].isCompleted} cancelled=${jobs[idx].isCancelled}",
                            )
                        }
                    }
                }
                if (debugScheduler) {
                    println(
                        "[sched] start workers target=$target requested=$workers " +
                            "desired=${desired.get()} " +
                            "started=${(0 until workers).count { startedWorkers.get(it) == 1 }}",
                    )
                }
            }
            // Ramp loop may already have raised desired before the lazy list was
            // constructed; start to the current target instead of the stale initial.
            ensureWorkersStarted(desired.get())

            // Wait for every worker that actually started. The controller may start
            // more workers while the first ones are draining, so a one-shot snapshot
            // would return too early. Never call await() on an unstarted lazy job:
            // that auto-starts it and recreates the c=256 idle-worker tax.
            while (true) {
                val activeJobs = jobs.filterIndexed { idx, job ->
                    startedWorkers.get(idx) == 1 && !job.isCompleted
                }
                if (activeJobs.isEmpty()) break
                activeJobs.awaitAll()
            }
            jobs.forEachIndexed { idx, job ->
                if (startedWorkers.get(idx) == 0) runCatching { job.cancel() }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } finally {
            watchdog?.cancel()
            // 必须显式取消：rampLoop 是 launch 的子协程，会阻止 coroutineScope 退出，
            // 若不取消则任务完成后仍空转到超时（实测收尾段被拖到 14s）。
            rampLoop?.cancel()
            // 无论成功、失败还是暂停，都把最终进度落盘 —— 续传的准确性全在这一步。
            persistProgress(force = true)
        }

        if (needWholeFallback.get()) return@coroutineScope Outcome.NeedWholeFallback
        if (!isActive()) return@coroutineScope Outcome.Failed("任务已暂停")
        failReason.get()?.let { return@coroutineScope Outcome.Failed(it) }

        // 完成判定：所有分片都标记为写完。不再需要"按文件长度推算覆盖区间"——
        // 进度是显式的（每片 written），比从磁盘长度反推更直接也更难出错。
        val missing = segmentList.filter { !it.complete }
        if (missing.isNotEmpty()) {
            val first = missing.first()
            return@coroutineScope Outcome.Failed(
                "缺失区间 ${missing.size} 段（首段 ${first.start}-${first.end}）"
            )
        }
        Outcome.Completed
    }

}

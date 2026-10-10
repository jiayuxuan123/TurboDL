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
        const val RAMP_INTERVAL_MS = 250L

        /** 被 park 的协程轮询间隔（毫秒）。 */
        const val PARK_POLL_MS = 80L

        /** 吞吐自适应的窗口长度；两个稳定窗口合并后交给候选档控制器。 */
        const val ADAPTIVE_SAMPLE_INTERVAL_MS = 750L

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
         * 已稳定在天花板多少个成功分片后，才允许把天花板 +1 重新试探。
         * 取大于 ramp-up 阈值一个量级：试探本身会撞墙（一次 503），必须罕见。
         */
        const val CEILING_PROBE_AFTER = 16
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
     * 文件名的 end 部分在下载结束后按最终区间重命名，保证 [verifyCoverage] 能正确解析。
     */
    private class Segment(
        val start: Long,
        end: Long,
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
        private val endRef = java.util.concurrent.atomic.AtomicLong(end)
        /** 当前区间末端（可被收尾托管原子缩短）。 */
        var end: Long
            get() = endRef.get()
            set(v) { endRef.set(v) }

        val length get() = end - start + 1

        /**
         * 分片文件。文件名里的 end 是**计划区间**，不随托管缩短而改 ——
         * 因为托管发生时文件可能正被写入，Windows 下无法重命名打开中的文件。
         * 实际覆盖范围以**文件长度**为准（见 [finalParts] / [verifyCoverage]）。
         */
        fun file(dir: File) = File(dir, "seg_${start}_$plannedEnd.part")

        /** 计划末端（构造时确定，永不变）。 */
        val plannedEnd: Long = endRef.get()
    }

    suspend fun run(
        taskId: Long,
        url: String,
        total: Long,
        chunkDir: File,
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
        /**
         * 分片完成回调（可选）：用于**下载中预先合并** —— 把已完成分片
         * 立刻写进目标文件的临时副本，使合并 I/O 与网络等待重叠。
         *
         * 参数为 (分片起始偏移, 分片文件)。实现方需容忍**乱序与重复**调用：
         * 分片可能乱序完成，收尾托管也可能产生区间重叠。
         * 回调抛异常不得影响下载 —— 调用点已包 runCatching。
         */
        onSegmentDone: (suspend (start: Long, file: File) -> Unit)? = null,
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
        val slowStartInit = when {
            !config.slowStart -> workers
            config.slowStartInitial > 0 -> min(config.slowStartInitial, workers)
            // 默认初始值随设定并发缩放（至少 4）：固定 4 在 128 连接下起点太低，
            // 配合比例上调可快速到顶，同时避免一上来就全开冲击服务器。
            else -> min(workers, max(4, workers / 4))
        }
        /** 动态目标并发（慢启动从 slowStartInit 升、背压时降；自适应时由控制器定档）。 */
        val desired = AtomicInteger(slowStartInit)

        /** 用户级总限速会人为封顶吞吐，因此该任务不启用吞吐学习。 */
        val adaptive = if (
            config.adaptiveConcurrency && config.globalSpeedLimitBytesPerSec <= 0
        ) AdaptiveConcurrencyController(
            maximumConcurrency = workers,
            initialConcurrency = workers,
            probeDownFirst = true,
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

        /** 在天花板处稳定成功的分片计数（用于缓步试探）。 */
        val ceilingStableCount = AtomicInteger(0)

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
                    rampDone = desired.get() >= workers,
                    concurrencyLimit = min(workers, ceiling.get()),
                )
                decision.suggestedCeiling?.let { suggested ->
                    ceiling.updateAndGet { current -> min(current, suggested) }
                }
                decision.target?.let { desired.set(it.coerceIn(1, ceiling.get())) }
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
                // 已下多少 = 文件当前长度（下载中持续增长）
                val done = runCatching { s.file(chunkDir).length() }.getOrDefault(0L)
                val remaining = (s.end - s.start + 1) - done
                val from = s.start + done
                if (remaining < 2 * minBytes) continue
                if (remaining > bestRemaining) {
                    bestRemaining = remaining
                    best = s
                }
            }
            val seg = best ?: return false

            // 在剩余区间的中点切，对齐 64KB
            val done = runCatching { seg.file(chunkDir).length() }.getOrDefault(0L)
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
            val tail = Segment(tailStart, oldEnd)
            offer(tail)
            tailAssistCount.incrementAndGet()
            return true
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
                // 429/503 背压高于吞吐学习：清掉旧基线，不能让自适应立即爬回被拒档位。
                ceiling.updateAndGet { min(it, target) }
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
        fun rampUpIfHealthy() {
            if (desired.get() >= workers) return
            if (windowThrottled.get()) return                 // 窗口内被限流过：先稳住
            // 【与吞吐自适应互斥】控制器正在下探/上探时，desired 必须保持它设的档位 ——
            // 否则这里会立刻把并发推回设定值，控制器永远测不到真实的低档窗口（实测表现为特性"不生效"）。
            if (adaptive?.isProbing() == true) return
            val cur = desired.get()
            val cap = ceiling.get()
            if (cur >= cap) {
                // 已稳定在学到的天花板：缓步试探能否再高一点（+1）。
                // 撞墙 → throttleDown 会把天花板重新压回来，代价只有一次 503。
                // 但自适应开启时**不做这个试探**：那个 +1 会一点点侵蚀控制器学到的档位，
                // 最终又爬回设定值（也就是又变慢）。回探由控制器自己负责。
                if (adaptive != null) return
                if (cap < workers && ceilingStableCount.incrementAndGet() >= CEILING_PROBE_AFTER) {
                    ceilingStableCount.set(0)
                    ceiling.incrementAndGet()
                }
                return
            }
            // 按比例上调（至少 +1）：log2 档数即可到顶。
            val step = max(1, (cur * RAMP_UP_FACTOR).toInt())
            val next = min(min(workers, cap), cur + step)
            desired.set(next)
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
                    // 自适应开启：并发档位由吞吐控制器唯一决定，旧的比例爬升退位。
                    observeThroughput(nowNanos, queueSize())
                    if (progressed) windowThrottled.set(false)
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
                        inFlight.incrementAndGet()
                        activeConns.incrementAndGet()
                        inFlightSegments.add(seg)
                        reportConns()
                        try {
                            val res = downloader.downloadSegment(
                                taskId, url, seg.start, { seg.end }, seg.file(chunkDir), headers,
                                { bytes ->
                                    speedLimiter.awaitAllow(bytes)
                            val abs = min(downloaded.addAndGet(bytes), total)
                            recordThroughputBytes(bytes)
                            if (!isActive()) return@downloadSegment
                            onBytes(bytes, abs)
                                },
                                ifRange = ifRange,
                                // 缓冲按**实际并发数**摊薄：本任务会同时跑 workers 个分片，
                                // 每个都独占 ioBufferSize（默认 1MB）的话，256 连接 = 256MB
                                // = Android 默认整堆（2026-10-10 真机 OOM 事故）。
                                // 传 workers（而非配置上限）以免把缓冲白摊小。
                                bufferSize = config.effectiveIoBufferSize(workers),
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
                                    // 下载中预先合并：把刚完成的区间写进目标文件的临时副本。
                                    // 失败不影响下载本身（runCatching 吞掉，收尾仍走常规合并）。
                                    onSegmentDone?.let { cb ->
                                        runCatching { cb(seg.start, seg.file(chunkDir)) }
                                    }
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
                                        delay(backoffMsFor((seg.attempts - 1).coerceAtMost(2)))
                                        offer(seg)
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
                                        delay(backoffMsFor((seg.throttleAttempts - 1).coerceAtMost(maxShift)))
                                        offer(seg)
                                    }
                                }
                                SegmentResult.FAILED -> {
                                    windowHadFailure.set(true)
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
                            inFlightSegments.remove(seg)
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
            // 必须显式取消：rampLoop 是 launch 的子协程，会阻止 coroutineScope 退出，
            // 若不取消则任务完成后仍空转到超时（实测收尾段被拖到 14s）。
            rampLoop?.cancel()
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

    /**
     * 校验 [0,total) 被分片文件**实际覆盖**。
     *
     * 【为什么用文件长度而不是文件名里的区间】收尾托管会把在飞分片缩短
     * （[Segment.end] 运行时可变），但**文件名里的末端是计划值、不会跟着改**
     * （托管发生时文件可能正被写入，Windows 下无法重命名打开中的文件）。
     * 因此判断"这个分片覆盖了哪一段"只能依据它**实际有多少字节**：
     * 起点取自文件名（start 永不变），终点 = start + 实际长度 - 1。
     *
     * 这对未发生托管的常规下载**完全等价**（长度 == 计划长度），
     * 只是把"信任文件名"换成了"信任磁盘上的真实字节"——后者才是事实。
     */
    private fun verifyCoverage(dir: File, total: Long): Pair<Boolean, List<LongRange>> {
        val blocks = dir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }
            ?.mapNotNull { f ->
                val name = f.name.removePrefix("seg_").removeSuffix(".part")
                val s = name.substringBefore('_').toLongOrNull() ?: return@mapNotNull null
                val len = f.length()
                if (len <= 0) return@mapNotNull null
                s..(s + len - 1)
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

    /**
     * 合并用的分片列表（按起点排序）。
     *
     * 【不再去重，改由调用方按"显式偏移"写入】
     * 收尾托管会让在飞分片提前收工，其文件长度可能超过让出点，与接手的分片
     * **重叠**（例如 `seg_A_B.part` 写到 M，而 `seg_M+1_B.part` 也下了 M+1..B）。
     * 按顺序拼接会把重叠算两次 → 长度校验失败。
     *
     * 解决办法不是"猜哪些该丢"，而是**按各自真实起点写入**（见 [partStarts]）：
     * 重叠区间会落到同一位置、写同样的内容 → 幂等，结果天然正确。
     * 起点就来自文件名（start 永不变），无需任何启发式判断。
     */
    fun finalParts(chunkDir: File): List<File> =
        sortedSegFiles(chunkDir).map { it.first }

    /**
     * 各分片在最终文件中的起始偏移（与 [finalParts] 一一对应）。
     *
     * 起点直接取自文件名 —— 这就是它在源文件中的绝对位置，**精确且无需推导**。
     */
    fun partStarts(chunkDir: File): List<Long> =
        sortedSegFiles(chunkDir).map { it.second }

    /** 返回 (文件, 起始偏移)，按起始偏移升序；忽略空文件。 */
    private fun sortedSegFiles(chunkDir: File): List<Pair<File, Long>> =
        chunkDir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }
            ?.mapNotNull { f ->
                val s = f.name.removePrefix("seg_").substringBefore('_').toLongOrNull()
                    ?: return@mapNotNull null
                if (f.length() <= 0) return@mapNotNull null
                f to s
            }
            ?.sortedBy { it.second }
            ?: emptyList()
}

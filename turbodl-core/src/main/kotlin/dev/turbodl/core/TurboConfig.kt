package dev.turbodl.core

/**
 * TurboDL 全局引擎配置。
 *
 * 设计参考（仅思想，未复制源码）：
 *  - aria2：固定连接数满并发、min-split-size；
 *  - IDM / XDM：动态分段（dynamic segmentation）+ 连接复用；
 *  - ab-download-manager：defaultThreadCount / dynamicPartCreationMode / globalSpeedLimit / minPartSize / maxRetry / 代理策略；
 *  - axel / Persepolis / Motrix：多连接、限速档、代理与队列管理。
 *
 * 全部字段可在运行时读取（部分需在任务启动前设定，见注释）。
 */
data class TurboConfig(
    /**
     * 每个任务的最大连接数（分片并发上限）。范围 1..256。
     *
     * ## 【2026-09-14 实测】8 → 16
     *
     * `Phase1ABTest` 场景 1（本地服务器**每连接限速 2MB/s**，24MB 文件）：
     *
     * | 连接数 | 吞吐 |
     * |---|---|
     * | 8（旧默认） | 7.3 MB/s |
     * | 16 | 8.9 MB/s |
     * | **64** | **12.1 MB/s** |
     *
     * **+65%，且单调**。机制很直接：服务器按**每条连接**限速时，
     * 总吞吐 = 单连接上限 × 连接数 —— 此时**连接数就是唯一的倍率**，
     * 任何分片粒度调参都无法替代它。
     *
     * 这与"分片粒度"是两个独立维度：粒度管的是**请求开销**（同速链路下越粗越好），
     * 连接数管的是**带宽倍率**（按连接限速时越多越好）。
     *
     * 取 16 而不是 64，是保守折中：
     *  - aria2 `--max-connection-per-server` **默认 1、硬上限 16**；
     *  - Motrix `balanced` 档用 16，只有 `maximum` 才用 64；
     *  - 对同一 host 开太多连接会被部分服务器**主动限流甚至封禁**，
     *    而 429/503 的降级是**事后**的（靠背压与退避兜底），代价比一开始就克制更高。
     *
     * 需要更激进时，宿主可显式提高（如 64）；配合 [maxConnectionsPerHost] 控制单 host 压力。
     */
    val maxConnectionsPerTask: Int = 16,

    /** 同时下载的最大任务数（队列并发）。范围 1..64。 */
    val maxConcurrentTasks: Int = 3,

    /** 全局下载速度上限（字节/秒），所有任务合计。0 = 不限速。 */
    val globalSpeedLimitBytesPerSec: Long = 0,

    /** 单个分片下载失败后的最大重试次数（仅重试该分片，不作废整任务）。范围 0..50。 */
    val maxRetries: Int = 5,

    /**
     * 动态分段（IDM/XDM 思想）：
     *  - true：连接空闲时，从「剩余未下载最多」的活动分片中点劈分，让空闲连接接手后半段，消除长尾；
     *  - false：退化为固定块大小 + 工作窃取。
     *
     * @see tailAssist 实际生效的收尾托管开关（本字段是它的历史名称）
     */
    val dynamicSegmentation: Boolean = true,

    /**
     * 收尾托管（tail assist）：只在**收尾阶段**把空闲连接派给在飞分片的剩余部分。
     *
     * 思路来自 aria2-next 的 `rebalanceEndgame`（`src/stream/StreamScheduling.cc`），
     * 但触发条件更严格 —— 只在「没有新块可领、却有连接闲着」时启用：
     *
     * ```
     * 队列空 && 仍有在飞分片 && 有空闲 worker
     * ```
     *
     * 【为什么只在收尾】大块传输阶段所有连接都在干活，此时拆分只会增加
     * **同时发往服务器的请求数**（这正是网盘风控的诱因），而收益为零。
     * 收尾阶段则相反：剩余工作不足 N 块，并发自然塌下去（`reportConns` 的注释记录了
     * 这一现象），此时把剩余部分让给空闲连接是纯收益。
     *
     * 【安全性】拆分 = [A,B] 缩短为 [A,M] + 新分片 [M+1,B]：
     * 原文件内容始终是 A 起的**连续前缀**，长度 M-A+1；续传时
     * `existing = length()`、`from = start + existing = M+1` → 写入位置正确。
     *
     * 【aria2-next 的经验】小尾巴不值得拆：拆一次要多付一次 RTT/建连。
     * 故低于 [tailAssistMinBytes] 的剩余量不拆；每个分片最多拆
     * [maxSplitsPerSegment] 次，防病态裂变。
     */
    val tailAssist: Boolean = true,

    /**
     * 收尾托管的最小剩余量（字节）：低于此值不拆分。
     *
     * aria2-next 用的是「剩余时间 > 请求成本 × 2」这种时间判据（更精确，但需要
     * 每条连接的实时速率）；本引擎用字节阈值近似 —— 默认 2MB，配合默认
     * `blockSize=16MB`，即最多拆成 8 份，且只在前几份足够大时才拆。
     *
     * 取值理由：一次请求的往返成本（RTT + 建连）在 WAN 上约 0.1~0.5s，
     * 若拆出来的部分只有几百 KB，新连接还没跑满就被付掉的开销吃回去了。
     */
    val tailAssistMinBytes: Long = 2L * 1024 * 1024,

    /**
     * 单个分片最多被拆几次（防病态裂变）。
     *
     * 只允许 2 次：16MB 的块最多裂成 4 段（4MB 起），足以让收尾阶段的空闲连接
     * 都有活干；再往上拆，新增请求数会超过收益，且更容易触发风控。
     */
    val maxSplitsPerSegment: Int = 2,

    /**
     * 下载中预先合并（overlap merge）：分片一完成就写进目标文件的临时副本，
     * 把合并 I/O 与网络等待重叠，省掉收尾的大段磁盘时间。
     *
     * ## ⚠️ 默认关闭（2026-09-29 实测结论）
     *
     * 慢网络基准（512MB / 64 连接 / 每连接 1MB/s）实测确实更快：
     * **10.39s → 11.08s**（省 0.7s，约 6%）。
     *
     * 但开它会在**收尾托管同时启用**时产生数据不一致：
     * 托管把分片 [A,B] 缩短到 [A,M]，并新建 [M+1,B]；
     * 若原分片在托管前已写入超过 M 的字节（响应快时可能发生），
     * 它的文件会覆盖到 [M+1,B] —— 与接管分片**写入同一区间**，
     * 两者来自不同请求（可能命中不同 CDN 节点），谁后写谁赢 → 内容可能错。
     * 实测表现：长度正确、SHA-256 不符（`DirectWriteContractTest` 复现）。
     *
     * 修复它需要给分片加"已写入区间"的显式记录并做冲突消解 —— 复杂度与风险
     * 都不低，而收益只有 6%。**故默认关闭**，保留实现与开关供后续攻坚。
     *
     * 关闭时行为与改造前完全一致（走常规合并，已验证 89 个测试全绿）。
     */
    val overlapMerge: Boolean = false,

    /**
     * 分片写入是否走缓冲流（P12 评估项，**默认 false**）。
     *
     * ## 为什么不加缓冲（实测结论，不是没做）
     *
     * `SegmentDownloader.writeSlice` 原本（以及默认）用裸 `RandomAccessFile` 直写。
     * "裸直写会有系统调用开销"听起来像优化点，所以做了 A/B 实测
     * （`SegmentWriteBufferTest`，16MB / 128 连接 / 64KB 分片 = 256 片、交错 3 轮取中位数）：
     *
     * | 写法 | 中位数 | 吞吐 | 样本 |
     * |---|---|---|---|
     * | 直写 | 2576ms | 6.2 MB/s | 2391 / 2576 / 2906 |
     * | 缓冲写（64KB + 每块 flush） | 2584ms | 6.2 MB/s | 2419 / 2584 / 2700 |
     *
     * **差异 0.3%，落在噪声范围内** —— 没有可复现的收益。
     *
     * 【为什么这里学不到东西】回环的写路径几乎不阻塞（页缓存），既没有磁盘寻道也没有真实
     * IO 等待，因此"减少 write 调用次数"省不出时间；而真实瓶颈在磁盘时，缓冲的收益又取决于
     * 文件系统与存储介质，不是引擎能一概而论的。
     *
     * 【代价却是确定的】缓冲会把"已写入长度"的含义从"已落盘"变成"已交给 OS 缓冲"。
     * 断点续传靠文件长度判断下了多少（见 `SegmentScheduler.verifyCoverage`），
     * 所以开启后必须"每次回调前 flush" —— 那就等于每块一次 flush，把缓冲的意义抵消掉大半
     * （上表的缓冲写就是这么实现的）。要真正做到"批量落盘 + 精确续传"，
     * 得给分片加"已写入区间"的显式记录，那是 [overlapMerge] 同级的复杂度。
     *
     * 结论：**保持直写**。本开关留作"我知道自己在干什么"时的复测入口 ——
     * 换到更快的链路或真实磁盘压力下，结论可能不同，届时先用 `SegmentWriteBufferTest` 对比再改默认值。
     */
    val bufferedSegmentWrite: Boolean = false,

    /**
     * 分段最小尺寸（字节，默认 64KB）—— 块大小的**下限**。
     *
     * 这个值刻意保持较小：本引擎用「细粒度预分块 + 工作窃取」消除长尾，
     * 依赖**块数远多于连接数**（块数 ≈ 连接数 × [segmentsPerConnection]）才能让
     * 慢连接拖住某块时其余连接继续领新块。
     *
     * 若把它调到 MB 量级（例如对齐 ABDM 的 1MB），16MB 文件 / 32 连接会被压成
     * 恰好 32 块 —— 每连接一块、`segmentsPerConnection` 的余量消失，
     * 收尾阶段并发会自然塌下去，表现为「传输很快但线程数一直掉」。
     *
     * 因此：**限制大文件请求数请调 [blockSize]（上限），不要调大本值。**
     * 只有当你在「少量大分片 + 运行时劈分」的架构下才应显著提高它。
     */
    val minSegmentSize: Long = 64L * 1024,

    /**
     * 固定分块大小上限（字节，默认 16MB）。
     *
     * 调度器**优先按连接数推导块大小**（保证块数 ≥ 连接数 × [segmentsPerConnection]），
     * 以便固定 N 线程能全部跑满；本值只限制单块不要过大。
     *
     * 旧默认 4MB 作为 `coerceIn` 的**硬上限**，会把大文件切成过多请求：
     * `total=10GB, workers=64, segmentsPerConnection=4` → 理想块 40MB，
     * 但被压到 4MB → **2560 个分片** → 2560 次 HTTP 请求 + 2560 次文件开关，
     * 握手开销与对服务器的 QPS 压力在弱网下反而**降低**吞吐。
     * 提高到 16MB 后同样场景为 640 块，请求数降为 1/4。
     */
    val blockSize: Long = 16L * 1024 * 1024,

    /**
     * 每个连接分配的块数（工作窃取粒度）。
     *
     * `块数 = 连接数 × 本值`。它同时服务两个**互相拉扯**的目标：
     *
     * - **调大** → 快连接能在自己那份干完后**抢走慢连接剩余的块**（消除长尾）；
     *   代价是请求数变多，而每块一次请求要付一次首字节往返。
     * - **调小** → 请求数少、往返开销小；代价是长尾无人接手。
     *
     * ## 【2026-09-14 两组实测】结论：保持 4
     *
     * **实验一（连接速度完全相同）** `Phase1ABTest` / `RealNetworkABTest`：
     * 8 连接 32 块 26.8 MB/s → 8 连接 8 块 55.6 MB/s（模拟网络 ×2.1）；
     * 真实网络（GitHub Release）1.75 → 2.11 MB/s。
     * → 看起来「块越少越快」。
     *
     * **实验二（1/8 连接限速 200KB/s）** `WorkStealingValueTest`：
     * | 块数 | 耗时 | 吞吐 |
     * |---|---|---|
     * | 8 块 | 10457 ms | 1.53 MB/s |
     * | 16 块 | 5503 ms | 2.91 MB/s |
     * | **32 块** | **2922 ms** | **5.48 MB/s** |
     * → 慢连接存在时，**32 块比 8 块快 3.6 倍**。
     *
     * **为什么两组结论相反**：实验一里所有连接速度相同，
     * **工作窃取没有任何可窃取的东西** —— 它结构性地把"多块"的收益归零，
     * 只留下"请求数变多"的代价。真实网络的连接速度是不均的，
     * 所以实验二才是有代表性的那个。
     *
     * **为什么仍取 4**：两个方向的损失不对称 ——
     * 选小了（8 块）在有慢连接时损失 **3.6 倍**；
     * 选大了（32 块）在理想均速链路下损失约 **2.1 倍**（且该组数据在多次运行间抖动较大）。
     * 4 是对"未知链路质量"更稳的折中：等价于给每个连接留 4 轮窃取余量。
     */
    val segmentsPerConnection: Int = 4,

    /**
     * 单任务**总切块数上限**（**默认 0 = 不限**；>0 才生效）。
     *
     * 背景：块数 = `连接数 × segmentsPerConnection`，是个**没有上限的乘法**：
     * 128 连接 → 512 块（被 minSegmentSize 压到 256 块，每块仅 64KB）。
     *
     * ⚠️ **为什么默认关闭（曾短暂默认 128，已回退）**：
     * 1. 收益只在**回环**上量到（分片 256→128 约 1.70×）。回环没有 RTT，每请求固定开销被低估，
     *    但真实链路的**最佳块数**从未验证；
     * 2. 更要命的是**它会吃掉工作窃取的余量**：128 线程 + 上限 128 ⇒ 恰好 1 块/线程，
     *    慢连接一旦拖住就没有空闲块可供快连接窃取 —— 而 `SkewSweepTest` 证明
     *    "有 1 条慢连接时多块比少块快 3.17 倍"。真实 CDN 恰恰是速度不均的。
     *    用户实报 rc19 之后"速度与线程上升都变慢"，与此吻合。
     *
     * 结论：**默认不限**，把它留作"我知道自己在干什么"时的手动旋钮。
     * 若要用，请先在真实链路上对比（`RealLinkSweepTest`），并注意块数不得接近连接数。
     */
    val maxSegmentsPerTask: Int = 0,

    /**
     * 自适应并发下调策略（**不照搬 AIMD 抖动判断**）：
     * 仅当收到 429/503 或出现「连续连接失败」达到阈值时才乘性下调并发；
     * 普通网速波动绝不主动减少连接数。设为 0 关闭该保护。
     */
    val backpressureConsecutiveFailures: Int = 4,

    /** 代理配置。 */
    val proxy: ProxyMode = ProxyMode.Direct,

    /** DNS 配置。 */
    val dns: DnsMode = DnsMode.System,

    /** 忽略 TLS/SSL 证书校验（抓包调试用；生产环境勿开）。 */
    val trustAllCerts: Boolean = false,

    /** 连接超时（毫秒）。 */
    val connectTimeoutMs: Long = 15_000,

    /** 读取超时（毫秒）。 */
    val readTimeoutMs: Long = 60_000,

    /** 默认 User-Agent（采用通用浏览器 UA，避免部分 CDN 对非浏览器 UA 直接拦截）。 */
    val userAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",

    /** 连接池最大空闲连接数（连接复用）。应 ≥ maxConnectionsPerTask，否则分片连接会反复重建。 */
    val maxIdleConnections: Int = 256,

    /**
     * 强制使用 HTTP/1.1（默认 true，专为多线程下载优化）。
     *
     * ## 机制前提（已验证）
     *
     * HTTP/2 会把并发请求**多路复用**到少数 TCP 连接上，共享拥塞/流控窗口。
     * 实测（`HttpVersionComparisonTest`，GitHub Release 资产 / 19MB / 16 并发 Range）：
     * **h1 开了 32 条连接，h2 只开 2 条** —— 塌缩确实发生。
     * 而 h1 那一侧每并发请求各占一条连接（`SlicedConnectionCountTest` 实测 16 请求 = 16 条）。
     *
     * ## 但"h2 会更慢"这一条**不成立**（实测订正）
     *
     * 同一环境的吞吐：h2 **6.79 MB/s** vs h1 **2.84 MB/s** —— **h2 更快**，不是更慢。
     * 原因是那次测量里真正的变量不是协议而是**连接数**：单连接基线就有 **6.16 MB/s**，
     * 而 32 条 h1 连接反而只有 2.84 MB/s —— 那个 CDN 在**惩罚高并发**（每连接/聚合限速），
     * 多开连接只是白付握手与限速代价。旧注释把"多连接没用"归因给 h2 的多路复用，是**归因错了**。
     *
     * ## 为什么默认值仍然保持 true
     *
     * 不是因为"h2 更慢"，而是因为**这个结论不能外推**：
     *  - 上述差异来自单个 CDN 在特定网络下的行为，换一个 CDN 结论可能相反；
     *  - h1 的**每连接独立拥塞窗口**在真实丢包链路上仍有价值（这正是 aria2/IDM 用 h1 的理由），
     *    而回环与本地无丢包环境测不出这一项；
     *  - 改变默认值需要**多个 CDN、带丢包的链路**上的对照证据，目前没有。
     *
     * 所以：保持 h1 默认（保守），但**不要再引用"h2 必然更慢"当作理由** —— 那是错的。
     * 真正的自适应手段是 [adaptiveConcurrency]（按实测吞吐收敛并发），它对本问题有效，
     * 且不依赖对协议的猜测。
     *
     * 复测方法：`_audit\_run_h2ab.bat`（会消耗约 38MB 真实流量）。
     *
     * 注意：当 [httpVersionPolicy] 为 AUTO 时，本字段仅影响“分片并发”链路（仍走 HTTP/1.1），
     * 而“整文件单流回退”链路允许协商 HTTP/2（单流场景 h2 未必更差且兼容性更好）。
     */
    val forceHttp1: Boolean = true,

    /**
     * HTTP 版本协商策略（默认 AUTO，按下载模式自动选择）：
     *  - **AUTO**：多分片并发走 HTTP/1.1（避免 h2 多路复用抹平多连接收益）；
     *    探测与整文件单流回退允许协商 HTTP/2（兼容仅支持 h2 的服务器，且单流无多连接损失）。
     *  - **FORCE_HTTP1**：全部链路强制 HTTP/1.1。
     *  - **FORCE_HTTP2**：全部链路允许 HTTP/2（仅在确知需要时使用，多线程可能无加速）。
     *
     * 为兼容旧版：未显式设置本字段时，若 [forceHttp1]=false 则等同 FORCE_HTTP2，否则为 AUTO。
     */
    val httpVersionPolicy: HttpVersionPolicy = HttpVersionPolicy.AUTO,

    /**
     * 每个主机（host）的最大并发分片数。<=0 表示不限（用 [maxConnectionsPerTask]）。
     *
     * 部分 CDN（如迅雷）对单 host 的并发 Range 有硬上限，超过后会把 Range 请求降级为 200 整文件，
     * 反而触发整文件回退。设置该上限可避免被降级。实际生效值 = min(maxConnectionsPerTask, 本值)。
     */
    val maxConnectionsPerHost: Int = 0,

    /** 连接保活时长（秒）。 */
    val keepAliveSeconds: Long = 300,

    /**
     * 分片临时目录根。null 时使用系统 java.io.tmpdir/turbodl。
     *
     * Android 上系统 tmpdir 可能被清理或受限，宿主应传入应用专属缓存目录（如 externalCacheDir），
     * 以保证断点分片跨会话、跨进程稳定保留。
     */
    val workDir: java.io.File? = null,

    /**
     * 连接预热 / DNS 预解析（默认开）。
     *
     * 探测拿到最终（重定向后）URL 后，在正式分片下载前先并发建立若干空连接（并预解析 DNS），
     * 填充连接池。这样分片开始时无需串行等待 DNS 解析 + TCP/TLS 握手，启动更快、初期吞吐更高。
     */
    val warmUpConnections: Boolean = true,

    /** 预热时建立的空连接数；<=0 时取 min(maxConnectionsPerTask, 8)。 */
    val warmUpConnectionCount: Int = 0,

    /**
     * 慢启动（默认开）：分片并发从少逐步上调到 [maxConnectionsPerTask]，而非一上来就全开。
     *
     * 好处：避免瞬时几十个连接同时握手冲击服务器/被风控，也避免小文件刚开就过度建连；
     * 与背压正交：背压负责“遇错降”，慢启动负责“健康升”。
     */
    val slowStart: Boolean = true,

    /** 慢启动初始并发；<=0 时取 min(maxConnectionsPerTask, 4)。 */
    val slowStartInitial: Int = 0,

    /**
     * 基于任务总吞吐的并发收敛（默认开）。
     *
     * 在慢启动档位上先测稳定窗口吞吐，再试探更高并发；只有吞吐有明确提升才接受新档。
     * 若总吞吐进入平台期或下降，则回到最后一个有效档位，并在稳定一段时间后低频回探。
     *
     * 不根据每连接吞吐低就降档：服务端按连接独立限速时，单连接速度本来就低，
     * 增加连接仍可能提高总吞吐。若启用了全局限速，吞吐判定会自动停用，避免把用户限速误判成服务器平台期。
     * 固定并发测量台可显式关闭此项。
     */
    val adaptiveConcurrency: Boolean = true,

    /**
     * 预热并发度上限（默认 4）。
     *
     * 预热本质上是“提前建好 TCP/TLS 连接”，并不需要开很多：
     * 在 2.4GHz Wi-Fi / 弱网下，一口气并发几十个握手会互相争抢信道与带宽，
     * 反而拖慢真正的首字节时间，甚至因堆积超时让任务看起来“卡在下载中”。
     * 故预热并发与下载并发解耦，默认只开少量。
     */
    val warmUpMaxParallel: Int = 4,

    /**
     * 已知文件大小时跳过探测（默认 true）。
     *
     * 调用方（如网盘解析）已经知道准确大小时，再花数秒去探测一次是纯浪费。
     * 此时乐观假设支持 Range 直接开始分片；若服务器实际不支持，
     * 首个分片会收到 200 整文件并触发已有的 RANGE_IGNORED 回退链路，正确性不受影响。
     * 代价仅是得不到重定向后的最终 URL——故仅在调用方未要求强制探测时生效。
     */
    val skipProbeWhenSizeKnown: Boolean = true,

    /**
     * 是否信任「弱校验器」进行续传（默认 false = 正确性优先）。
     *
     * 服务器只回 `Content-Length`、没有 ETag / Last-Modified 时，续传令牌退化为 `len=N`。
     * 此时「服务器换了一个**同样大小**的新文件」无法被检测到 → 旧分片被复用 →
     * 合并出**静默损坏**的文件，而且最终的长度校验恰好通过（大小一致）。
     *
     * - false（默认）：弱校验器且目录里已有旧分片时，丢弃重下。牺牲一点流量换正确性。
     * - true：沿用旧的宽松续传行为，接受上述损坏风险，换取断点续传不重下。
     *
     * 若你的来源（如网盘直链）普遍不提供 ETag/Last-Modified，
     * 又希望续传不重下，请显式设为 true 并自行承担风险。
     */
    val trustWeakValidator: Boolean = false,

    /**
     * 分片读写缓冲区大小的**上限**（字节，默认 1MB）。
     *
     * 缓冲区越小 → 单位时间内 read/write 与进度回调次数越多，高吞吐时开销显著：
     * 256KB 缓冲在 50MB/s 下每秒要跑 200 次完整回调链路。取 1MB 在内存与吞吐间平衡。
     *
     * ★ 这是**单连接的上限**，不是实际值：实际按 [ioBufferTotalBudgetBytes] 与并发数摊薄，
     *   见 [effectiveIoBufferSize]。低并发时就是本值。
     */
    val ioBufferSize: Int = 1024 * 1024,

    /**
     * 全部在飞连接的读缓冲**总预算**（字节，默认 32MB）。
     *
     * ## 为什么必须有这个上限
     *
     * 旧实现是「每个连接固定 [ioBufferSize]（1MB）」—— 看起来很小，乘上连接数就不小了：
     *
     * | 连接数 | 缓冲总量 |
     * |---|---|
     * | 16（默认） | 16MB |
     * | 64 | 64MB |
     * | 128 | 128MB |
     * | **256（引擎上限）** | **256MB** |
     *
     * 2026-10-10 真机事故：Android 默认堆就是 256MB，用户在 256 连接下下载
     * → 256 个 1MB 缓冲**刚好等于整堆** → `OutOfMemoryError`。
     * 缓冲都是活对象（正被下载协程引用），GC 回收不掉，于是堆占满、连 64 字节都分不出来。
     * 崩溃现场落在 Okio 的 Watchdog 线程（它只是想关掉一个超时的 socket），
     * **看起来像网络问题，实际是缓冲把堆吃光了**。
     *
     * 【为什么不是"把线程数降回去"】连接数是吞吐的关键手段（服务端按连接限速时，
     * 总吞吐 ≈ 单连接上限 × 连接数）。砍连接数是拿吞吐换内存，而真正的问题是
     * 内存花得不值：256 个连接每个独占 1MB 缓冲，绝大部分时间都是空着的。
     * 按总量摊薄后，256 连接仍有 128KB/连接 —— 远高于读 socket 的性价比区间，
     * 吞吐不受影响。
     */
    val ioBufferTotalBudgetBytes: Int = 32 * 1024 * 1024,


    /**
     * 进度上报最小间隔（毫秒，默认 200ms）0 = 不节流。
     *
     * 这是**吞吐量的关键**：每次 reportProgress 会重建进度 Map、发一个事件（启动协程），
     * 宿主侧还可能写数据库/刷 UI。若每个缓冲块都上报，64 连接 × 高速下载
     * 会每秒产生上千次 Map 重建与协程调度，把 CPU 耗在上报而不是搜数据上。
     * 节流后仅按固定频率刷新，对 UI 完全够用（人眼看不出 200ms 差异）。
     * 完成/失败/状态切换等关键事件不受节流影响。
     */
    val progressIntervalMs: Long = 200,

    /**
     * 探测（probe）单次超时（毫秒，默认 6s）。
     *
     * 探测只是为了拿到总大小/Range 支持/重定向地址，它**不传输数据**，
     * 因此必须快：健康服务器百毫秒级就会回头，拖到几十秒的只能是异常路径。
     * 超时值过大会直接体现为“点了下载但一直在解析”。
     */
    val probeTimeoutMs: Long = 6_000,

    /**
     * 探测失败/超时的重试次数（默认 1）。
     *
     * 注意：探测失败并不致命（大不了整文件单流），所以不值得为它多等。
     * 总阻塞上限 ≈ probeTimeoutMs × (probeRetries+1) + 退避，请匀控制在数秒内。
     */
    val probeRetries: Int = 1,

    /**
     * 卡死（stall）检测阈值（毫秒，默认 45s）0 = 关闭。
     *
     * 思路参考 aria2 `--lowest-speed-limit` 与 curl `--speed-limit/--speed-time`：
     * OkHttp 的 readTimeout 只能管“单次 read 阻塞多久”，若服务器/CDN 涓涓流式吐字节
     * （每几十秒发几字节），永远不会超时，表现为“显示下载中但进度几乎不动”。
     * 因此额外做**任务级守护**：这么长时间内总下载字节数零增长，则判定卡死，
     * 主动 cancel 当前所有在飞请求，让分片进入重试链路（重新建连、可能命中其他 CDN 节点）。
     */
    val stallTimeoutMs: Long = 45_000,

    /**
     * 连续卡死重置次数上限（默认 3）。达到后任务失败，避免无限重试假活。
     * 分片已保留，用户重试即从断点继续。
     */
    val maxStallRecoveries: Int = 3,

    /**
     * 连接预热单次请求超时（毫秒，默认 8s）。
     * 预热仅为优化，绝不得拖慢正式下载；超时即放弃。
     */
    val warmUpTimeoutMs: Long = 8_000,
) {
    init {
        require(maxConnectionsPerTask in 1..256) { "maxConnectionsPerTask 必须在 1..256" }
        require(maxConcurrentTasks in 1..64) { "maxConcurrentTasks 必须在 1..64" }
        require(globalSpeedLimitBytesPerSec >= 0) { "globalSpeedLimitBytesPerSec 不能为负" }
        require(maxRetries in 0..50) { "maxRetries 必须在 0..50" }
        require(minSegmentSize >= 4096) { "minSegmentSize 至少 4KB" }
        // 【放开】原先要求 `blockSize >= minSegmentSize`，本意是"上限不能小于下限"，
        // 但这条约束让"对齐 aria2/Motrix 的 20MB 分片"这类配置**根本无法表达**。
        // 二者语义其实不同：minSegmentSize 是"别切太碎"的下限，
        // blockSize 是"单块别太大"的上限；当上限小于下限时，上限胜出即可，无需报错。
        // 实际生效值由 SegmentScheduler 的 effBlock 计算决定（见那里的注释）。
        require(segmentsPerConnection in 1..64) { "segmentsPerConnection 必须在 1..64" }
        require(maxSegmentsPerTask >= 0) { "maxSegmentsPerTask 不能为负（0 = 不限）" }
        require(maxConnectionsPerHost >= 0) { "maxConnectionsPerHost 不能为负" }
        require(warmUpConnectionCount >= 0) { "warmUpConnectionCount 不能为负" }
        require(slowStartInitial >= 0) { "slowStartInitial 不能为负" }
        require(probeTimeoutMs >= 1000) { "probeTimeoutMs 至少 1000ms" }
        require(probeRetries >= 0) { "probeRetries 不能为负" }
        require(stallTimeoutMs >= 0) { "stallTimeoutMs 不能为负" }
        require(maxStallRecoveries >= 0) { "maxStallRecoveries 不能为负" }
        require(warmUpTimeoutMs >= 500) { "warmUpTimeoutMs 至少 500ms" }
        require(warmUpMaxParallel >= 1) { "warmUpMaxParallel 至少 1" }
        require(ioBufferSize >= 8 * 1024) { "ioBufferSize 至少 8KB" }
        require(ioBufferTotalBudgetBytes >= ioBufferSize) {
            "ioBufferTotalBudgetBytes 不能小于单连接缓冲上限 ioBufferSize" +
                "（否则低并发时反而比旧行为更省，语义混乱）"
        }
        require(progressIntervalMs >= 0) { "progressIntervalMs 不能为负" }
    }

    /**
     * 实际生效的单连接读缓冲大小：把 [ioBufferTotalBudgetBytes] 按**最坏情况的连接总数**摊开，
     * 且不超过 [ioBufferSize]。
     *
     * ## 分母为什么是 maxConcurrentTasks × connections
     *
     * 预算是**整个进程**的，而连接来自两边相乘：同时跑的任务数 × 每个任务的连接数。
     * 只按单个任务的连接数摊，多任务并行时仍会突破预算 —— 例如 5 个任务各 256 连接
     * （App 里这两项都能设到）就是 1280 条连接，按 128KB/连接算要 160MB。
     *
     * 分母取 [maxConcurrentTasks]（**声明上限**，不是"此刻在跑几个"）是刻意的：
     * 这样不需要跨任务协调、不会阻塞，也让上限是**可静态验证**的。
     * 代价是"设了 5 并但它只跑 1 个"时缓冲比需要的略小，但仍在合理区间（见下）。
     *
     * ## 数值范围
     *
     * | 场景 | 单连接缓冲 | 总占用 |
     * |---|---|---|
     * | 16 连接 × 1 任务（App 默认） | 1MB（触上限） | 16MB |
     * | 256 连接 × 1 任务 | 128KB | 32MB |
     * | 256 连接 × 5 任务 | 26KB | 33MB |
     *
     * [MIN_IO_BUFFER_BYTES]（8KB）是保底：配置极端到摊薄结果低于它时取它，
     * 此时**总占用会略超预算**（如 64 任务 × 256 连接 = 16384 连接 × 8KB = 128MB）。
     * 这是有意的取舍 —— 宁愿在荒谬配置下稍微超一点，也不让缓冲小到只剩 syscall 开销。
     * 而在任何**可达的**配置下（App 上限 5 任务 × 256 连接）总占用都稳定在预算内。
     */
    fun effectiveIoBufferSize(connections: Int): Int =
        budgetedIoBufferSize(
            totalBudgetBytes = ioBufferTotalBudgetBytes,
            perConnectionCapBytes = ioBufferSize,
            connections = connections,
            concurrentTasks = maxConcurrentTasks,
        )

    /**
     * 解析实际生效的 HTTP 版本策略（兼容旧的 [forceHttp1] 字段）。
     * httpVersionPolicy 显式为非 AUTO 时优先；否则由 forceHttp1 推导（true→AUTO, false→FORCE_HTTP2）。
     */
    val effectiveHttpVersionPolicy: HttpVersionPolicy
        get() = when (httpVersionPolicy) {
            HttpVersionPolicy.AUTO -> if (forceHttp1) HttpVersionPolicy.AUTO else HttpVersionPolicy.FORCE_HTTP2
            else -> httpVersionPolicy
        }

    companion object {
        /**
         * 单连接读缓冲的硬下限（8KB）。
         *
         * 与 [ioBufferSize] 的校验下限同源：再往下就只剩系统调用与回调的开销了。
         * [effectiveIoBufferSize] 的摊薄结果触底时取本值。
         */
        const val MIN_IO_BUFFER_BYTES = 8 * 1024

        /**
         * **预算摊薄的唯一实现**：按「最坏情况的连接总数」把总预算摊到单连接，
         * 并夹在 [MIN_IO_BUFFER_BYTES, perConnectionCapBytes] 之间。
         *
         * ## 为什么提成静态函数而不是各自算
         *
         * 这段算法原先在**两个仓库里各写了一份**：引擎的 `TurboConfig.effectiveIoBufferSize`
         * 与 App 兜底引擎的 `ChunkDownloader.ioBufferSizeFor`（0.2.0.8 修 OOM 时留下的）。
         * 两份实现的**上下限参数不同**（引擎 1MB / App 256KB），所以不能简单共用一份配置，
         * 但**摊薄公式必须只有一个** —— 否则改了一处另一处不会跟着改，
         * 而这类不变量（"总占用 ≤ 预算"）出问题时是 OOM，不是可忽略的小偏差。
         *
         * App 侧现在调用本函数并只传自己的上限值，见 `ChunkDownloader.ioBufferSizeFor`。
         *
         * ## 分母为什么含 [concurrentTasks]
         *
         * 预算是**整个进程**的：真实连接数 = 并发任务数 × 每任务连接数。
         * 只按单任务摊，多任务并行时仍会突破（5 任务 × 256 连接 = 1280 条）。
         *
         * @param totalBudgetBytes 全部在飞连接的总预算
         * @param perConnectionCapBytes 单连接上限（低并发时就是这个值）
         * @param connections 单任务的连接数；<=0 视为 1
         * @param concurrentTasks 并发任务数；<=0 视为 1
         */
        fun budgetedIoBufferSize(
            totalBudgetBytes: Int,
            perConnectionCapBytes: Int,
            connections: Int,
            concurrentTasks: Int,
        ): Int {
            val cap = perConnectionCapBytes.coerceAtLeast(MIN_IO_BUFFER_BYTES)
            val lanes = connections.coerceAtLeast(1).toLong() *
                concurrentTasks.coerceAtLeast(1).toLong()
            val shared = (totalBudgetBytes.toLong() / lanes).coerceAtMost(Int.MAX_VALUE.toLong())
            return shared.toInt().coerceIn(MIN_IO_BUFFER_BYTES, cap)
        }
    }
}

/** HTTP 版本协商策略。 */
enum class HttpVersionPolicy {
    /** 分片并发走 HTTP/1.1，探测与整文件单流回退允许 HTTP/2。 */
    AUTO,

    /** 全部链路强制 HTTP/1.1。 */
    FORCE_HTTP1,

    /** 全部链路允许 HTTP/2（多线程可能无加速）。 */
    FORCE_HTTP2,
}

/** 代理模式（参考 ab-download-manager 的 ProxyStrategy）。 */
sealed interface ProxyMode {
    /** 直连，不使用代理。 */
    data object Direct : ProxyMode

    /** 使用系统代理（JVM 的 ProxySelector / 环境变量）。 */
    data object System : ProxyMode

    /** 手动指定代理。 */
    data class Manual(
        val type: ProxyType,
        val host: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null,
    ) : ProxyMode

    /** PAC 脚本（自动代理配置）URL。 */
    data class Pac(val pacUrl: String) : ProxyMode
}

enum class ProxyType { HTTP, SOCKS }

/** DNS 模式。 */
sealed interface DnsMode {
    /** 系统默认 DNS。 */
    data object System : DnsMode

    /** 静态 hosts 覆盖（host -> IP 列表）；未命中回退系统 DNS。 */
    data class StaticHosts(val hosts: Map<String, List<String>>) : DnsMode

    /** DNS over HTTPS（例如 https://dns.google/dns-query）。 */
    data class DoH(val dohUrl: String) : DnsMode

    /**
     * 自动择优：并发探测多个公共 DoH，采用**最快给出有效结果**的那个，
     * 并在后续一段时间内固定使用它；全部失败时回退系统 DNS。
     *
     * 【为什么需要它】用户此前只能在「系统 DNS」与「手动填一个 DoH」之间二选一：
     *  - 系统 DNS：可能被污染/劫持，且运营商解析质量参差
     *  - 手动 DoH：用户很难知道哪个 DoH 在当前网络下可用 ——
     *    实测国内网络下境外 DoH（Cloudflare / Google）普遍很慢甚至不通，
     *    而用户往往先填的就是这几个，结果"开了 DoH 反而更慢"
     *
     * 自动模式把"选哪个"交给实测：一次并发探测（最坏 4 秒）选出当前网络下最快的，
     * 之后固定复用，避免每次解析都重新测速带来的抖动与额外流量。
     *
     * **不影响正常连接速度**：探测只发生在首次解析时（且结果缓存 5 分钟），
     * 选定后与单端点 DoH 的路径完全一致。
     *
     * @param endpoints 候选端点；默认国内优先（阿里/腾讯），其后是境外（Google/Cloudflare）
     */
    data class Auto(
        val endpoints: List<String> = DEFAULT_DOH_ENDPOINTS,
    ) : DnsMode

    companion object {
        /**
         * 默认候选 DoH 端点，**国内优先**。
         *
         * 顺序即探测顺序，但自动模式是**并发**探测取最快者，故顺序只影响极端情况下的兜底。
         * 列入境外端点是为了覆盖"国内 DoH 均不可用"的网络环境。
         */
        val DEFAULT_DOH_ENDPOINTS = listOf(
            "https://dns.alidns.com/dns-query",   // 阿里公共 DNS（国内）
            "https://doh.pub/dns-query",          // 腾讯 DNSPod（国内）
            "https://dns.google/dns-query",       // Google（境外，部分网络不可达）
            "https://cloudflare-dns.com/dns-query", // Cloudflare（境外，部分网络不可达）
        )
    }
}

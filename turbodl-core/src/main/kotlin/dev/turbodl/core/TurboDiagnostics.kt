package dev.turbodl.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 单个档位的测量结果。 */
data class ConnectionTierResult(
    /**
     * 该档的**自变量**取值：
     * 连接数扫描 = 连接数；并发任务扫描 = 同时下载的任务数。
     */
    val connections: Int,
    /** 本窗口内**实际传输**的字节数（已扣掉起跑偏移，见 [startOffset]）。 */
    val bytes: Long,
    val elapsedMs: Long,
    /** 该档位观察到的峰值并发连接数（用来判断"设定的连接数是否真的跑满"）。 */
    val peakConnections: Int,
    val error: String? = null,
    /**
     * 窗口开始时任务已有的下载量。
     * **>0 说明这一档是从断点续传起跑的** —— 速率仍是对的（[bytes] 已减去它），
     * 但"起跑即续传"说明分片目录没做到逐档隔离，报告里必须显式提示。
     */
    val startOffset: Long = 0,
    /** 并发任务扫描时每任务的连接数（0 = 不适用）。 */
    val connectionsPerTask: Int = 0,
    /**
     * 本档是否在窗口结束前**已经下完**。
     *
     * 【为什么必须有这个标志】早期实现永远跑满窗口、并用「字节数 ÷ 窗口时长」算速率 ——
     * 对一个**能在窗口内下完**的文件，字节数就等于文件大小，于是**每个档位都得到同一个速率**
     * （实测：22.9MB 的文件，8/16/64/128 四档全部报 1.51 MB/s，而真实用时差异被完全掩盖）。
     * `completed = true` 的档位**不可用于横向比较**，必须报错而不是给出结论。
     */
    val completed: Boolean = false,
    /** 本档的窗口设定（毫秒）。用于展示"用时 vs 窗口"。 */
    val windowMs: Long = 0,
) {
    val mbPerSec: Double
        get() = if (elapsedMs <= 0) 0.0 else bytes / 1048576.0 / (elapsedMs / 1000.0)

    /** 是否可用于横向比较：没在窗口内提前下完，且无错误、有流量。 */
    val comparable: Boolean
        get() = error == null && bytes > 0 && !completed

    /** 自变量标签：区分"连接数扫描"与"并发任务扫描"。 */
    val axisLabel: String
        get() = if (connectionsPerTask > 0) {
            "任务=${connections}×${connectionsPerTask}连接"
        } else {
            "连接=$connections"
        }

    override fun toString(): String = buildString {
        append(
            "%-20s 吞吐=%6.2f MB/s 用时=%5.1fs/%.0fs 峰值并发=%-4d 传输=%d 字节".format(
                axisLabel, mbPerSec, elapsedMs / 1000.0, windowMs / 1000.0, peakConnections, bytes
            )
        )
        if (completed) append("  ⚠窗口内已下完(数据不可比)")
        if (startOffset > 0) append("  起跑偏移=$startOffset")
        error?.let { append("  错误=$it") }
    }
}

/**
 * 现场诊断工具（**不进下载主链路**，不改变任何下载行为；只在显式调用时运行）。
 *
 * ## 它回答的问题
 *
 * 「多开连接到底有没有用？」——这**完全取决于服务端怎么限速**，而引擎事先不知道对方是哪种：
 *
 * | 服务端模型 | 加连接的后果 |
 * |---|---|
 * | 每连接限速 | 吞吐 **∝ 连接数**，多多益善 |
 * | 按 IP/账户聚合限速 | 吞吐**封顶**，加连接只增加请求开销 |
 * | 对并发有惩罚（429/503） | 加连接**触发拒绝 → 重试风暴** |
 *
 * ## 做法
 *
 * 只改连接数、其他配置完全相同，**每档只跑固定时长窗口后立即取消**（不下完整个文件），
 * 对比吞吐曲线的形状：
 *
 * | 曲线 | 判读 | 该往哪改 |
 * |---|---|---|
 * | 线性上升 | 每连接限速 | 加连接，直到收益饱和 |
 * | 基本持平 | 按 IP 聚合限速 | 加连接无用 → 降到刚好够用，省请求 |
 * | 先升后降 | 对并发有惩罚 | 必须自适应降到拐点（aria2 限 16 的理由） |
 *
 * 为什么必须在**真实链路**上跑：回环没有 RTT、没有真实 CDN 的限流与调度策略，
 * 测出来的曲线与真实网络可能完全不同。
 */
object TurboDiagnostics {

    /**
     * 【前置探活】开跑前先确认链接真的可用。
     *
     * 【为什么必须做】实测事故：任务表里存的是**取链时刻的签名直链**（夸克 `__puus` 约 3 小时过期，
     * 且分享转存任务完成后会删掉云端临时目录），过期后诊断仍然**每档老老实实跑满 15 秒**，
     * 最后给出四档全 `0.00 MB/s` + 「样本不足」——用户白等 1 分钟，还看不出是链接死了。
     *
     * 只发一次 1 字节的 Range 请求。**走引擎自己的 HttpClientFactory**（与测量同一套
     * 代理/DNS/TLS 配置），否则配了代理的用户会探出与实测相反的结论。
     *  - 成功 → 返回 null，继续正常扫描；
     *  - 失败 → 返回可读原因（含 HTTP 状态码），调用方应**直接中止**并展示给用户。
     *
     * 【必须在 IO 线程上调用】内部是阻塞式 `call.execute()`。
     * 曾因宿主在主线程直接调用而抛 `NetworkOnMainThreadException`，
     * 且被 catch 成"链接不可用"的文本 —— 看起来像链接的问题，实则是调用方式的问题。
     * 故此处**自己确保调度器**（见下 [checkReachable] / [checkReachableAsync]），
     * 不依赖调用方传对上下文。
     */
    private fun probeAlive(url: String, headers: Map<String, String>): String? {
        // 出站 host 校验：只允许 http/https，并拒绝环回/私有/保留地址。
        outboundHostRejection(url)?.let { return it }
        val cfg = TurboConfig(maxConnectionsPerTask = 1, maxConcurrentTasks = 1, warmUpConnections = false, slowStart = false)
        val client = HttpClientFactory.build(cfg)
        return try {
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(req).execute().use { resp ->
                val code = resp.code
                when {
                    code in 200..299 -> null
                    code == 301 || code == 302 || code == 303 || code == 307 || code == 308 -> null // 重定向由引擎正常处理
                    code == 401 || code == 403 || code == 404 || code == 410 || code == 412 || code == 416 ->
                        "链接已失效（HTTP $code ${resp.message}）"
                    code == 429 || code == 503 ->
                        "服务器限流/不可用（HTTP $code ${resp.message}）"
                    else -> "链接不可用（HTTP $code ${resp.message}）"
                }
            }
        } catch (e: Exception) {
            "${e::class.simpleName}: ${e.message ?: "无法连接"}"
        } finally {
            runCatching { client.dispatcher.executorService.shutdown() }
            runCatching { client.connectionPool.evictAll() }
        }
    }

    /**
     * 出站地址预检：拒绝非 http(s) 与环回/私有/保留地址；通过则返回 null。
     *
     * 探测会真的建立连接，因此与其它出站请求适用同一条约束：
     * 只允许公网 http/https，不允许把诊断当成访问本机/内网服务的工具。
     */
    private fun outboundHostRejection(url: String): String? {
        val uri = runCatching { java.net.URI(url) }.getOrNull()
            ?: return "地址格式不正确"
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "仅支持 http/https 链接（当前为 ${uri.scheme ?: "未知"}）"
        }
        val host = uri.host ?: return "地址缺少主机名"
        val addrs = runCatching { java.net.InetAddress.getAllByName(host) }.getOrNull()
            ?: return null   // 解析不了就交给真正的请求去暴露原因（可能是 DNS 问题）
        for (a in addrs) {
            if (a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress ||
                a.isSiteLocalAddress || a.isMulticastAddress
            ) {
                return "拒绝访问本机/内网/保留地址（$host）"
            }
        }
        return null
    }

    /**
     * [probeAlive] 的公开同步入口（宿主自行保证不在主线程调用）。
     *
     * 仍保留同步形式：已有调用方在挂起函数内使用它，改签名会波及它们。
     * 若你在 UI 层调用，请改用 [checkReachableAsync]。
     */
    fun checkReachable(url: String, headers: Map<String, String> = emptyMap()): String? =
        probeAlive(url, headers)

    /**
     * [probeAlive] 的**挂起版**：内部切到 IO 调度器，可在任意协程上下文安全调用。
     *
     * 新增此入口是为了从根上消除 `NetworkOnMainThreadException` ——
     * 让"在哪条线程调"不再是调用方需要操心的事。
     */
    suspend fun checkReachableAsync(url: String, headers: Map<String, String> = emptyMap()): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            probeAlive(url, headers)
        }

    /**
     * 扫描一组连接数，返回每档的吞吐。
     *
     * @param tiers 连接数档位；默认 `8/16/64/128`（含 rc14 的默认 16 与用户常用的 128）。
     * @param windowMs 每档测量窗口；到点即取消，**不会**下完整个文件。
     * @param onTier 每档结束后的回调（可用来实时刷新界面/日志）。
     */
    suspend fun sweepConnections(
        url: String,
        headers: Map<String, String> = emptyMap(),
        knownSize: Long = -1L,
        tiers: List<Int> = listOf(8, 16, 64, 128),
        windowMs: Long = 15_000,
        gapMs: Long = 1_000,
        workDir: File? = null,
        onTier: (suspend (ConnectionTierResult) -> Unit)? = null,
    ): List<ConnectionTierResult> = coroutineScope {
        // 前置探活：链接死了就立刻报，别让用户白等 4 个 15 秒窗口。
        // 前置探活是阻塞调用，必须切到 IO —— 否则宿主在主线程调用时这里会抛
        // NetworkOnMainThreadException，并被上层误显示成"链接不可用"。
        withContext(Dispatchers.IO) { probeAlive(url, headers) }?.let { reason ->
            return@coroutineScope tiers.map { n ->
                ConnectionTierResult(n, 0, 0, 0, "未开始：$reason").also { onTier?.invoke(it) }
            }
        }
        val out = mutableListOf<ConnectionTierResult>()
        // 【整段切 IO，而不只是探活】下面每一档都会：建/删临时目录与文件（磁盘 I/O）、
        // 构造 TurboClient（建 OkHttpClient）、到点 `client.cancel(id)`（关闭在飞 socket）、
        // 收尾 `client.shutdown()`（清连接池 = 关 socket，TLS 要写 close_notify）。
        // 宿主是在主线程调本函数的（设置页 `rememberCoroutineScope` + `scope.launch`），
        // 只把探活切走的话，这里仍会**在主线程上跑满一分钟**并做上述 socket 操作。
        withContext(Dispatchers.IO) {
            for (n in tiers) {
                val r = runCatching { measureTier(url, headers, knownSize, n, windowMs, workDir) }
                    .getOrElse { e ->
                        if (e is CancellationException) throw e
                        ConnectionTierResult(n, 0, 0, 0, e.message ?: e.toString())
                    }
                out += r
                onTier?.invoke(r)
                if (gapMs > 0) delay(gapMs)
            }
        }
        out
    }

    /**
     * 根据曲线形状给出判读（宿主可直接展示给用户）。
     *
     * 【必须先判"平"再判"形状"】四档几乎相等时，`maxByOrNull` 选出的"最优档"是**任意的**
     * （实测：夸克链接 8/16/64/128 全为 1.51 MB/s，旧逻辑却报出"先升后降：对并发有惩罚"，
     * 与数据相反）。所以先用**极差**判断是否平坦，再谈形状。
     */
    fun interpret(results: List<ConnectionTierResult>): String {
        // 【先做有效性检查，再谈形状】窗口内提前下完 → 每档的"速率"都等于 文件大小÷窗口，
        // 会伪装成"完全平坦"。早期版本正是这么把 22.9MB 的文件误判成"服务端聚合限速"的。
        val done = results.filter { it.completed }
        if (done.isNotEmpty()) {
            return buildString {
                append("⚠ **数据无效，不下结论**：${done.size} 个档位在窗口内已下完整个文件")
                append("（${done.joinToString("、") { "连接=${it.connections} 用 ${it.elapsedMs / 1000.0}s" }}）。\n")
                append("   对能下完的文件，「速率」会被算成 文件大小÷窗口时长 —— 各档看起来一模一样，")
                append("   真实差异被掩盖。**请改用更大的文件（建议 ≥200MB）复测。**")
            }
        }
        val ok = results.filter { it.comparable }
        if (ok.size < 2) {
            // 【区分"链接死了"与"没测到"】任务表存的是取链时刻的签名直链，
            // 网盘直链会过期（夸克 __puus ≈3 小时）；分享转存任务完成后云端临时目录还会被删。
            // 此时四档全 0、峰值并发也是 0 —— 必须直说，否则用户只会看到"样本不足"。
            val failReasons = results.mapNotNull { it.error }.distinct()
            if (failReasons.isNotEmpty() && results.all { it.peakConnections == 0 }) {
                return buildString {
                    append("❌ **链接不可用，诊断未开始**：${failReasons.first()}\n")
                    append("   四档均未建立任何连接（峰值并发=0）。任务的直链是**取链时签发的**，")
                    append("网盘直链会过期（夸克约 3 小时），分享转存类任务完成后云端临时文件也会被删。\n")
                    append("   **请重新解析该链接、新建一个下载任务，再对那个任务跑诊断。**")
                }
            }
            return "样本不足，无法判读（检查链接/请求头/网络可达性）"
        }
        val rates = ok.map { it.mbPerSec }
        val lo = rates.min()
        val hi = rates.max()
        val spread = if (lo > 0) hi / lo else Double.MAX_VALUE
        val best = ok.maxByOrNull { it.mbPerSec }!!
        val lowest = ok.minByOrNull { it.mbPerSec }!!
        val peakOk = ok.all { it.peakConnections >= it.connections }

        return buildString {
            append("吞吐 %.2f → %.2f MB/s（极差 %.2f 倍".format(lo, hi, spread))
            append(if (peakOk) "；各档峰值并发均达到设定值）\n" else "；⚠ 有的档位并发没跑满，该档数据不可比）\n")
            append(
                when {
                    // 平坦：与连接数无关 —— 服务端按 IP/账户/文件聚合限速
                    spread <= 1.15 ->
                        "→ **加连接无收益（曲线平坦）**：$lo → $hi MB/s 基本不变，" +
                            "而峰值并发逐档上升都没有换到速度 ⇒ 服务端限的是**聚合速率**（按 IP/账户/文件），" +
                            "不是每条连接。**提高线程数无法突破这个上限**；" +
                            "该做的是：换来源/换时段/多任务并行（若限的是单文件而非整 IP）。"
                    // 最高档最优：每连接限速
                    best.connections == ok.maxOf { it.connections } ->
                        "→ **加连接有收益（单调上升）**：典型「每连接限速」，$hi MB/s @ ${best.connections} 连接。" +
                            "可继续加，直到收益饱和；同时看峰值并发确认真的跑满。"
                    // 中间最优且最高档明显更差：并发惩罚
                    best.connections != lowest.connections && spread > 1.15 ->
                        "→ **先升后降**：最优在 ${best.connections} 连接，而 ${lowest.connections} 连接掉到 " +
                            "%.2f MB/s ⇒ 服务端对**并发**有惩罚，应自适应降到拐点".format(lowest.mbPerSec) +
                            "（这正是 aria2 --max-connection-per-server=16 的理由）。"
                    else ->
                        "→ 曲线无单调规律（极差 %.2f 倍），样本噪声可能主导；建议加大单档时长后复测。".format(spread)
                }
            )
        }
    }

    /**
     * 判读[并发任务扫描][sweepConcurrentTasks]。
     *
     * 【必须扣掉"连接数"这个混淆变量】本扫描固定**每任务**连接数，于是
     * `总连接数 = 任务数 × connectionsPerTask` 也随任务数一起增长。
     * 在这个设计下，「按文件限速」与「每连接限速」**预测完全相同**（都是增益 = 任务数比），
     * 因此**单靠本扫描无法区分这两者** —— 必须把增益与**总连接数之比**对照着看。
     *
     * 早期版本只看增益就断言「限速按文件计 ⇒ 可拆成多个任务并行」，实测被证伪：
     * 真实原因是**连接数**，而连接数在**单任务内**就能加满（不必拆文件）。
     */
    fun interpretConcurrent(results: List<ConnectionTierResult>): String {
        val done = results.filter { it.completed }
        if (done.isNotEmpty()) {
            return buildString {
                append("⚠ **数据无效，不下结论**：${done.size} 个档位在窗口内已下完。\n")
                append("   文件够小时，N 个任务会各自下完**一整份拷贝**，于是「总字节 = N × 文件大小」")
                append("   看起来像「吞吐随任务数线性叠加」，其实只是下了 N 份同样的文件。")
                append("   **请改用更大的文件（建议 ≥200MB）复测。**")
            }
        }
        val ok = results.filter { it.comparable }.sortedBy { it.connections }
        if (ok.size < 2) {
            val failReasons = results.mapNotNull { it.error }.distinct()
            if (failReasons.isNotEmpty() && results.all { it.peakConnections == 0 }) {
                return buildString {
                    append("❌ **链接不可用，诊断未开始**：${failReasons.first()}\n")
                    append("   各档均未建立任何连接（峰值并发=0）。任务的直链是**取链时签发的**，")
                    append("网盘直链会过期（夸克约 3 小时），分享转存类任务完成后云端临时文件也会被删。\n")
                    append("   **请重新解析该链接、新建一个下载任务，再对那个任务跑诊断。**")
                }
            }
            return "样本不足，无法判读（检查链接/请求头/网络可达性）"
        }
        val one = ok.first()
        val top = ok.last()
        val taskGain = top.connections.toDouble() / one.connections
        val gain = if (one.mbPerSec > 0) top.mbPerSec / one.mbPerSec else 0.0

        // 总连接数之比：本扫描里它与任务数之比相同（每任务连接数固定）。
        val perTaskConn = top.connectionsPerTask
        val connGain = if (perTaskConn > 0) {
            (top.connections * perTaskConn).toDouble() / (one.connections * perTaskConn)
        } else taskGain
        val a = one.connections * (if (perTaskConn > 0) perTaskConn else 1)
        val b = top.connections * (if (perTaskConn > 0) perTaskConn else 1)

        return buildString {
            append(
                "单任务 %.2f MB/s → %d 任务 %.2f MB/s（实测增益 %.2f 倍）\n"
                    .format(one.mbPerSec, top.connections, top.mbPerSec, gain)
            )
            append("   同时：总连接数 %d → %d（%.1f 倍）—— 本扫描里这两个量**同时变化**\n".format(a, b, connGain))
            append(
                when {
                    // 增益跟着连接数走：涨的是连接，不是"多任务"本身
                    gain >= connGain * 0.7 ->
                        "→ 增益**与连接数增长同步**：提速来自**更多连接**，而非「多开任务」本身。\n" +
                            "   实现含义：**加线程就够了**（单任务内加到 %d 连接即可），\n".format(b) +
                            "   不需要拆文件／拆任务。反过来，若把连接数固定住再比任务数，总吞吐应当不变。"
                    gain <= 1.2 ->
                        "→ 总吞吐**基本不变**，但连接数已经翻了 %.1f 倍：限速按 **IP/账户总量**计\n".format(connGain) +
                            "   ⇒ 无论加连接还是多开任务都无法叠加；只能换来源 / 换时段 / 等限速解除。"
                    else ->
                        "→ **增益明显低于连接数增长**（%.2f 倍 vs %.1f 倍）：可能同时存在\n".format(gain, connGain) +
                            "   「连接数收益递减」与「聚合总量上限」，或另有瓶颈（磁盘/CPU）。建议再测一轮确认。"
                }
            )
        }
    }

    /**
     * 【并发任务扫描】固定连接数、只改**同时下载的任务数**，看总吞吐怎么变。
     *
     * 回答的是上一个扫描无法回答的问题：服务端的聚合限速到底是
     * **按文件**（多任务能叠加 → 开几个就快几倍）还是**按 IP/账户**（多任务总量不变）。
     *
     * 实测意义：若按文件限速，则"单个大文件 1.5MB/s"是可以靠并行多任务绕开的；
     * 若按 IP 限速，则任何客户端手段都无效，只能换来源。
     */
    suspend fun sweepConcurrentTasks(
        url: String,
        headers: Map<String, String> = emptyMap(),
        knownSize: Long = -1L,
        taskCounts: List<Int> = listOf(1, 2, 3),
        connectionsPerTask: Int = 16,
        windowMs: Long = 15_000,
        gapMs: Long = 1_000,
        workDir: File? = null,
        onTier: (suspend (ConnectionTierResult) -> Unit)? = null,
    ): List<ConnectionTierResult> = coroutineScope {
        // 前置探活：链接死了就立刻报，别让用户白等 N 个 15 秒窗口（与连接数扫描同理）。
        // 前置探活是阻塞调用，必须切到 IO —— 否则宿主在主线程调用时这里会抛
        // NetworkOnMainThreadException，并被上层误显示成"链接不可用"。
        withContext(Dispatchers.IO) { probeAlive(url, headers) }?.let { reason ->
            return@coroutineScope taskCounts.map { k ->
                ConnectionTierResult(k, 0, 0, 0, "未开始：$reason").also { onTier?.invoke(it) }
            }
        }
        val out = mutableListOf<ConnectionTierResult>()
        // 【整段切 IO】理由同 [sweepConnections]：测量循环本身含磁盘 I/O、socket 关闭
        // 与 client 生命周期操作，宿主在主线程调用时不能让它跑在调用者调度器上。
        withContext(Dispatchers.IO) {
            for (k in taskCounts) {
                val r = runCatching {
                    measureConcurrent(url, headers, knownSize, k, connectionsPerTask, windowMs, workDir)
                }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    ConnectionTierResult(k, 0, 0, 0, e.message ?: e.toString())
                }
                out += r
                onTier?.invoke(r)
                if (gapMs > 0) delay(gapMs)
            }
        }
        out
    }

    /** 每档独立的临时工作目录：避免上一档留下的分片被下一档"续传"，污染测量。 */
    private fun freshTierDir(workDir: File?, label: String): File {
        val base = workDir ?: File(System.getProperty("java.io.tmpdir"), "turbodl-diag")
        val d = File(base, "$label-${System.nanoTime()}")
        d.mkdirs()
        return d
    }

    private suspend fun measureTier(
        url: String,
        headers: Map<String, String>,
        knownSize: Long,
        connections: Int,
        windowMs: Long,
        workDir: File?,
    ): ConnectionTierResult = coroutineScope {
        val tierDir = freshTierDir(workDir, "conn$connections")
        val out = File.createTempFile("turbodl-diag", ".bin", tierDir).apply { deleteOnExit() }
        // 固定并发：关掉慢启动与预热，才能干净地只对比"连接数"这一个变量。
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = connections,
                maxConcurrentTasks = 1,
                warmUpConnections = false,
                slowStart = false,
                workDir = tierDir,
            )
        )
        val lastBytes = AtomicLong(0)
        val firstBytes = AtomicLong(-1)
        val peak = AtomicInteger(0)
        val finishedAt = AtomicLong(0)
        var collector: kotlinx.coroutines.Job? = null
        var waiter: kotlinx.coroutines.Job? = null
        val t0 = System.currentTimeMillis()
        try {
            val id = client.submit(
                DownloadRequest(url = url, destination = out, headers = headers, knownSize = knownSize)
            )
            collector = launch {
                client.events.collect { ev ->
                    if (ev is TurboEvent.Progress) {
                        val p = ev.progress
                        if (firstBytes.get() < 0) firstBytes.set(p.downloadedBytes)
                        if (p.downloadedBytes > lastBytes.get()) lastBytes.set(p.downloadedBytes)
                        if (p.activeConnections > peak.get()) peak.set(p.activeConnections)
                    }
                }
            }
            // 【关键】监视"是否已下完"：小文件会在窗口内完成，此时速率必须按**实际用时**算，
            // 否则「文件大小 ÷ 固定窗口」会让每一档都得到同样的假速率。
            waiter = launch {
                runCatching { client.await(id) }
                finishedAt.set(System.currentTimeMillis())
            }
            while (System.currentTimeMillis() - t0 < windowMs) {
                if (finishedAt.get() > 0) break
                delay(200)
            }
            val done = finishedAt.get() > 0
            val elapsed = if (done) (finishedAt.get() - t0).coerceAtLeast(1) else System.currentTimeMillis() - t0
            client.cancel(id)
            // 只统计**窗口内**传输量：扣掉起跑偏移，即使是从断点续传起跑也不会虚高。
            val start = firstBytes.get().coerceAtLeast(0)
            val transferred = (lastBytes.get() - start).coerceAtLeast(0)
            ConnectionTierResult(
                connections = connections,
                bytes = transferred,
                elapsedMs = elapsed,
                peakConnections = peak.get(),
                startOffset = start,
                completed = done,
                windowMs = windowMs,
            )
        } finally {
            waiter?.cancel()
            collector?.cancel()
            runCatching { client.shutdown() }
            runCatching { out.delete() }
            runCatching { tierDir.deleteRecursively() }
        }
    }

    /**
     * 固定每任务连接数，只改**同时下载的任务数**，测总吞吐。
     *
     * 判读：总吞吐随任务数**成倍上升** ⇒ 限速按**文件**（多任务能叠加）；
     * 总吞吐**基本不变** ⇒ 限速按 **IP/账户**（多任务无用，只能换来源）。
     */
    private suspend fun measureConcurrent(
        url: String,
        headers: Map<String, String>,
        knownSize: Long,
        taskCount: Int,
        connectionsPerTask: Int,
        windowMs: Long,
        workDir: File?,
    ): ConnectionTierResult = coroutineScope {
        val tierDir = freshTierDir(workDir, "task$taskCount")
        val outs = (1..taskCount).map {
            File.createTempFile("turbodl-diag-t$it", ".bin", tierDir).apply { deleteOnExit() }
        }
        val client = TurboClient(
            TurboConfig(
                maxConnectionsPerTask = connectionsPerTask,
                maxConcurrentTasks = taskCount,
                warmUpConnections = false,
                slowStart = false,
                workDir = tierDir,
            )
        )
        // 每个任务分别累计，最后求和；扣掉各自起跑偏移。
        val last = HashMap<Long, Long>()
        val first = HashMap<Long, Long>()
        val peak = AtomicInteger(0)
        val finishedCount = AtomicInteger(0)
        var collector: kotlinx.coroutines.Job? = null
        val waiters = mutableListOf<kotlinx.coroutines.Job>()
        val t0 = System.currentTimeMillis()
        try {
            val ids = outs.map { f ->
                client.submit(
                    DownloadRequest(url = url, destination = f, headers = headers, knownSize = knownSize)
                )
            }
            collector = launch {
                client.events.collect { ev ->
                    if (ev is TurboEvent.Progress) {
                        val p = ev.progress
                        first.putIfAbsent(ev.taskId, p.downloadedBytes)
                        if (p.downloadedBytes > (last[ev.taskId] ?: 0L)) last[ev.taskId] = p.downloadedBytes
                        if (p.activeConnections > peak.get()) peak.set(p.activeConnections)
                    }
                }
            }
            // 同样要监视"是否全部下完"：文件够小的话，N 个任务会各自下完**一整份拷贝**，
            // 此时"总字节 = N × 文件大小"会被误读成"吞吐随任务数线性叠加"。
            ids.forEach { taskId ->
                waiters += launch {
                    runCatching { client.await(taskId) }
                    finishedCount.incrementAndGet()
                }
            }
            while (System.currentTimeMillis() - t0 < windowMs) {
                if (finishedCount.get() >= ids.size) break
                delay(200)
            }
            val allDone = finishedCount.get() >= ids.size
            val elapsed = System.currentTimeMillis() - t0
            ids.forEach { runCatching { client.cancel(it) } }
            var transferred = 0L
            var startSum = 0L
            for (id in ids) {
                val s = (first[id] ?: 0L).coerceAtLeast(0)
                startSum += s
                transferred += ((last[id] ?: 0L) - s).coerceAtLeast(0)
            }
            ConnectionTierResult(
                connections = taskCount,
                bytes = transferred,
                elapsedMs = elapsed,
                peakConnections = peak.get(),
                startOffset = startSum,
                connectionsPerTask = connectionsPerTask,
                completed = allDone,
                windowMs = windowMs,
            )
        } finally {
            waiters.forEach { it.cancel() }
            collector?.cancel()
            runCatching { client.shutdown() }
            outs.forEach { o -> runCatching { o.delete() } }
            runCatching { tierDir.deleteRecursively() }
        }
    }
}

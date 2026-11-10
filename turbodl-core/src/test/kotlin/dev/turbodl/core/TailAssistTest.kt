package dev.turbodl.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 收尾托管（tail assist）的行为与安全测试。
 *
 * ## 为什么要单独测
 *
 * 收尾托管是**唯一会在运行时改变分片区间**的机制，它触碰的是
 * 出过三次数据事故的续传/合并路径。默认配置下它只在
 * 「队列空 && 有在飞分片 && 有空闲 worker」时触发——
 * 常规小文件测试根本碰不到它，容易"测试全绿但功能从未生效"。
 *
 * 本测试用**慢服务器 + 大分片**构造出必然的收尾窗口：
 * 分片数少于连接数时，早期就会有 worker 空转 → 托管必然发生。
 *
 * 验证三件事：
 *  1. **真的触发了**（否则测试是假绿）
 *  2. **字节精确**（SHA-256 与源一致 —— 重叠/错位都会在这里暴露）
 *  3. **可关掉**（`tailAssist=false` 时行为回退到纯预分块）
 */
class TailAssistTest {

    private lateinit var tmpDir: File
    private lateinit var server: HttpServer
    private var port = 0
    private lateinit var payload: ByteArray

    private val rangeStarts = java.util.Collections.synchronizedList(mutableListOf<Long>())

    private fun subDir(name: String): File {
        require(!name.contains('/') && !name.contains('\\') && name != "..") { "非法子目录名：$name" }
        return File(tmpDir, name).apply { mkdirs() }
    }

    @BeforeTest
    fun setup() {
        payload = ByteArray(8 * 1024 * 1024) { ((it * 40503) xor (it ushr 3)).toByte() }
        tmpDir = java.nio.file.Files.createTempDirectory("turbodl-tail-").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // 慢服务器：每 64KB 睡 6ms → 8MB 约需 0.8s/连接。
        // 足够慢，能让先完成的 worker 进入"队列空但有在飞分片"的收尾窗口。
        server.createContext("/f.bin") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            var s = 0
            var e = payload.size - 1
            if (range != null) {
                val m = Regex("bytes=(\\d+)-(\\d*)").find(range)
                if (m != null) {
                    s = m.groupValues[1].toInt()
                    e = m.groupValues[2].toIntOrNull() ?: (payload.size - 1)
                    rangeStarts.add(s.toLong())
                }
            }
            val len = e - s + 1
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            ex.responseHeaders.add("ETag", "\"tail-etag\"")
            if (range != null) {
                ex.responseHeaders.add("Content-Range", "bytes $s-$e/${payload.size}")
                ex.sendResponseHeaders(206, len.toLong())
            } else {
                ex.sendResponseHeaders(200, len.toLong())
            }
            ex.responseBody.use { out ->
                var off = s
                val chunk = 64 * 1024
                while (off <= e) {
                    val n = minOf(chunk, e - off + 1)
                    out.write(payload, off, n)
                    out.flush()
                    off += n
                    Thread.sleep(6)
                }
            }
        }
        server.executor = Executors.newFixedThreadPool(64)
        server.start()
        port = server.address.port
    }

    @AfterTest
    fun teardown() {
        server.stop(0)
        tmpDir.deleteRecursively()
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered().use { ins ->
            val b = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(b)
                if (n <= 0) break
                md.update(b, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * 构造必然触发收尾托管的配置：
     *  - 连接数 32，但强制只有 4 个分片（`blockSize` 大 + `segmentsPerConnection=1`）
     *    → 早期就有 28 个 worker 空转 → 托管窗口必然出现
     *  - `tailAssistMinBytes` 调小，让 2MB 的分片也够格被拆
     */
    private fun tailConfig(work: File, assist: Boolean) = TurboConfig(
        maxConnectionsPerTask = 32,
        maxConcurrentTasks = 1,
        warmUpConnections = false,
        slowStart = false,
        workDir = work,
        segmentsPerConnection = 1,
        maxSegmentsPerTask = 4,
        blockSize = 2L * 1024 * 1024,
        minSegmentSize = 2L * 1024 * 1024,
        tailAssist = assist,
        tailAssistMinBytes = 256 * 1024,
        maxSplitsPerSegment = 2,
    )

    @Test
    fun `tail assist produces byte-exact output`() = runBlocking {
        val work = subDir("ta1")
        val out = File(tmpDir, "out_ta1.bin")
        val client = TurboClient(tailConfig(work, assist = true))
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:$port/f.bin", out))
            val r = client.await(id)
            assertTrue(r.isSuccess, "下载应成功，实际 $r")
            assertEquals(payload.size.toLong(), out.length(), "长度应精确")
            assertEquals(
                sha256(payload), sha256(out),
                "内容必须与源一致 —— 托管造成的重叠/错位会在这里暴露",
            )
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun `tail assist actually triggers under contention`() = runBlocking {
        val work = subDir("ta2")
        val out = File(tmpDir, "out_ta2.bin")
        // 通过事件流观察：无直接 API 暴露托管次数，这里用"请求数 > 初始分片数"作为证据
        val client = TurboClient(tailConfig(work, assist = true))
        try {
            rangeStarts.clear()
            val id = client.submit(DownloadRequest("http://127.0.0.1:$port/f.bin", out))
            assertTrue(client.await(id).isSuccess)
            assertEquals(sha256(payload), sha256(out))
            // 初始只有 4 个分片（8MB / 2MB）；若托管生效，总请求数必然 > 4
            val totalRequests = rangeStarts.size
            assertTrue(
                totalRequests > 4,
                "收尾托管应增加请求数（初始 4 个分片），实际只有 $totalRequests 个 —— 说明托管未触发，测试是假绿",
            )
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun `disabling tail assist still completes correctly`() = runBlocking {
        val work = subDir("ta3")
        val out = File(tmpDir, "out_ta3.bin")
        val client = TurboClient(tailConfig(work, assist = false))
        try {
            val id = client.submit(DownloadRequest("http://127.0.0.1:$port/f.bin", out))
            assertTrue(client.await(id).isSuccess)
            assertEquals(payload.size.toLong(), out.length())
            assertEquals(
                sha256(payload), sha256(out),
                "关闭托管时行为应与改造前完全一致（纯预分块）",
            )
        } finally {
            client.shutdown()
        }
    }

    /** 分片目录快照，用于断言失败时给出可诊断的信息（而不是只说"缺区间"）。 */
    private fun dumpParts(dir: File): String {
        val files = dir.listFiles { f -> f.name.startsWith("seg_") }?.sortedBy { it.name } ?: return "(空)"
        if (files.isEmpty()) return "(无分片文件)"
        return files.joinToString("\n") { f ->
            val s = f.name.removePrefix("seg_").substringBefore('_').toLongOrNull() ?: -1
            val e = f.name.removePrefix("seg_").removeSuffix(".part").substringAfter('_').toLongOrNull() ?: -1
            val len = f.length()
            "  ${f.name}: 计划=[$s,$e] 实际长度=$len 覆盖=[$s,${s + len - 1}]" +
                (if (len >= e - s + 1) " 完整" else " **不完整**")
        }
    }

    @Test
    fun `tail assist survives interruption and resume`() = runBlocking {
        // 最危险的组合：托管产生重叠分片 + 中途中断 + 续传
        val work = subDir("ta4")
        val out = File(tmpDir, "out_ta4.bin")
        // 【必须用 stableKey】分片目录按 stableKey（缺省则按内部自增 id）命名，
        // 两次 submit 的 id 不同 → 不复用目录 → 续传永远拿不到旧分片。
        // 真实宿主（YunGet）传的是 "room-<id>"，这里用固定键模拟同一任务。
        val key = "ta4-fixed"

        val c1 = TurboClient(tailConfig(work, assist = true))
        val id1 = c1.submit(DownloadRequest("http://127.0.0.1:$port/f.bin", out, stableKey = key))
        kotlinx.coroutines.delay(500)   // 让它下到一半（此时多半已有托管发生的重叠分片）
        c1.shutdown()
        // 【必须等 c1 的 worker 真正退出】shutdown 只是**发起**取消：在飞的阻塞
        // 网络调用要等返回后才检查取消标记，worker 会多活一小段时间。不等就启动
        // 下一个实例，两个引擎会同时写同一个 stableKey 的分片目录 ——
        // 全量测试跑（CPU 紧张）时这个残留窗口被拉长，相互踩踏后续传报
        // 「缺失区间」（实测复现两次）。真实宿主对同一任务只会有一个引擎实例
        // 在写，不存在这种双写；这里是测试自己的时序缺陷，与产品代码无关。
        // await 挂起到任务终结（此处为取消）；带超时防极端情况下永远挂死。
        runCatching { kotlinx.coroutines.withTimeout(15_000) { c1.await(id1) } }

        val afterFirst = dumpParts(work)

        val c2 = TurboClient(tailConfig(work, assist = true))
        try {
            val id = c2.submit(DownloadRequest("http://127.0.0.1:$port/f.bin", out, stableKey = key))
            val r = c2.await(id)
            assertTrue(
                r.isSuccess,
                "托管后中断再续传应成功，实际 $r\n第一次中断后的分片：\n$afterFirst\n" +
                    "续传后的分片：\n${dumpParts(work)}",
            )
            assertEquals(payload.size.toLong(), out.length(), "续传后长度应精确")
            assertEquals(
                sha256(payload), sha256(out),
                "续传后内容必须与源一致 —— 重叠分片按偏移写入应保持幂等",
            )
        } finally {
            c2.shutdown()
        }
    }
}

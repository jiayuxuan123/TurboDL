package dev.turbodl.cli

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CliContractTest {
    @Test
    fun `NDJSON escaping keeps bytes ASCII even on non-UTF8 consoles`() {
        val escaped = jsonEscForJson("中文\n\"\\\u0001😀")
        assertEquals(
            "\\u4e2d\\u6587\\n\\\"\\\\\\u0001\\ud83d\\ude00",
            escaped,
        )
        assertFalse(escaped.any { it.code > 0x7e }, "JSON 行不得依赖终端编码")
    }

    private data class Marker(val token: String, val value: String? = null)

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    private val markers = listOf(
        Marker("-h"),
        Marker("--help"),
        Marker("-V"),
        Marker("--version"),
        Marker("-o", "out.bin"),
        Marker("--output", "out.bin"),
        Marker("-d", "out-dir"),
        Marker("--dir", "out-dir"),
        Marker("-c", "17"),
        Marker("--connections", "17"),
        Marker("--threads", "17"),
        Marker("--max-total-connections", "32"),
        Marker("--sha256", "a".repeat(64)),
        Marker("--known-size", "32241380"),
        Marker("-l", "10MB"),
        Marker("--limit", "10MB"),
        Marker("--speed-limit", "10MB"),
        Marker("-r", "3"),
        Marker("--retry", "3"),
        Marker("--jobs", "2"),
        Marker("-b", "tasks.txt"),
        Marker("--batch", "tasks.txt"),
        Marker("-H", "X-Test: value"),
        Marker("--header", "X-Test: value"),
        Marker("-A", "TurboDL-Test"),
        Marker("--user-agent", "TurboDL-Test"),
        Marker("-M", "http://mirror.invalid/file.bin"),
        Marker("--mirror", "http://mirror.invalid/file.bin"),
        Marker("-p", "http://127.0.0.1:7890"),
        Marker("--proxy", "http://127.0.0.1:7890"),
        Marker("--doh", "https://dns.example.invalid/dns-query"),
        Marker("--http-policy", "auto"),
        Marker("--force-http1"),
        Marker("--no-warmup"),
        Marker("--slow-start"),
        Marker("--no-slow-start"),
        Marker("--adaptive"),
        Marker("--no-adaptive"),
        Marker("--adaptive-mode", "down"),
        Marker("--spc", "4"),
        Marker("--block-size", "1MB"),
        Marker("--min-segment", "64KB"),
        Marker("--slow-start-initial", "4"),
        Marker("--no-tail-assist"),
        Marker("--no-overlap"),
        Marker("--stall-timeout", "1000"),
        Marker("--insecure"),
        Marker("--connect-timeout", "1000"),
        Marker("--read-timeout", "1000"),
        Marker("--timeout", "1000"),
        Marker("--json"),
        Marker("--timing"),
        Marker("-q"),
        Marker("--quiet"),
        Marker("--progress-interval", "100"),
    )

    private val valueMarkers = markers.filter { it.value != null }

    @Test
    fun `help version and no arguments exit zero`() {
        val help = runCli("--help")
        assertEquals(0, help.exitCode, help.stderr)
        assertTrue(help.stdout.contains("用法:"))
        assertTrue(help.stdout.contains("退出码:"))

        val noArgs = runCli()
        assertEquals(0, noArgs.exitCode, noArgs.stderr)
        assertTrue(noArgs.stdout.contains("TurboDL"))

        val version = runCli("--version")
        assertEquals(0, version.exitCode, version.stderr)
        assertTrue(Regex("^turbodl \\d+\\.\\d+\\.\\d+\\.\\d+\\s*$").matches(version.stdout))
    }

    @Test
    fun `all 55 parser markers are recognized`() {
        assertEquals(55, markers.size, "P02a/Q00 的解析标记盘点必须保持为 55")
        markers.forEach { marker ->
            val args = mutableListOf(marker.token)
            marker.value?.let(args::add)
            args += "--help"
            val result = runCli(*args.toTypedArray())
            assertEquals(0, result.exitCode, "${marker.token} 未被识别: ${result.stderr}")
        }
    }

    @Test
    fun `unknown and surplus positional arguments are usage errors`() {
        val unknown = runCli("--definitely-unknown", "--help")
        assertEquals(2, unknown.exitCode)
        assertTrue(unknown.stderr.contains("无法识别的参数：--definitely-unknown"), unknown.stderr)
        assertTrue(unknown.stderr.contains("用法:"), unknown.stderr)

        val extraPositional = runCli("http://example.invalid/a", "http://example.invalid/b")
        assertEquals(2, extraPositional.exitCode)
        assertTrue(extraPositional.stderr.contains("无法识别的参数：http://example.invalid/b"))
    }

    @Test
    fun `every value marker reports missing value`() {
        valueMarkers.forEach { marker ->
            val result = runCli(marker.token)
            assertEquals(2, result.exitCode, "${marker.token} 缺值时退出码错误")
            assertTrue(
                result.stderr.contains("缺少 ${marker.token} 的取值"),
                "${marker.token} 缺值提示不符: ${result.stderr}",
            )
        }
    }

    @Test
    fun `invalid numbers and malformed values are usage errors`() {
        val invalidCases = listOf(
            listOf("--connections", "abc"),
            listOf("--max-total-connections", "abc"),
            listOf("--retry", "abc"),
            listOf("--spc", "abc"),
            listOf("--slow-start-initial", "abc"),
            listOf("--stall-timeout", "abc"),
            listOf("--connect-timeout", "abc"),
            listOf("--timeout", "abc"),
            listOf("--progress-interval", "abc"),
            listOf("--limit", "abc"),
            listOf("--block-size", "abc"),
            listOf("--min-segment", "abc"),
            listOf("--sha256", "not-a-sha256"),
            listOf("--known-size", "not-a-size"),
            listOf("--header", "missing-colon"),
            listOf("--proxy", "http://host-no-port"),
            listOf("--http-policy", "http3"),
            listOf("--adaptive-mode", "sideways"),
        )

        invalidCases.forEach { args ->
            val result = runCli(*args.toTypedArray())
            assertEquals(2, result.exitCode, "${args.joinToString(" ")} 应以用法错误退出")
            assertTrue(result.stderr.contains("错误："), result.stderr)
            assertTrue(result.stderr.contains("用法:"), result.stderr)
        }
    }

    @Test
    fun `numeric bounds are silently clamped at TurboConfig seam`() {
        withTempDir { dir ->
            val highOut = File(dir, "high.bin")
            val highCapture = File(dir, "high.properties")
            val high = runCli(
                "--connections", "999",
                "--max-total-connections", "99999",
                "--retry", "999",
                "--json",
                "-o", highOut.absolutePath,
                "http://example.invalid/high.bin",
                capture = highCapture,
            )
            assertEquals(0, high.exitCode, high.stderr)
            val highConfig = loadProperties(highCapture)
            assertEquals("256", highConfig.getProperty("config.maxConnectionsPerTask"))
            assertEquals("8192", highConfig.getProperty("config.maxTotalConnections"))
            assertEquals("50", highConfig.getProperty("config.maxRetries"))

            val lowOut = File(dir, "low.bin")
            val lowCapture = File(dir, "low.properties")
            val low = runCli(
                "--connections", "0",
                "--max-total-connections", "-1",
                "--retry", "-1",
                "--json",
                "-o", lowOut.absolutePath,
                "http://example.invalid/low.bin",
                capture = lowCapture,
            )
            assertEquals(0, low.exitCode, low.stderr)
            val lowConfig = loadProperties(lowCapture)
            assertEquals("1", lowConfig.getProperty("config.maxConnectionsPerTask"))
            assertEquals("0", lowConfig.getProperty("config.maxTotalConnections"))
            assertEquals("0", lowConfig.getProperty("config.maxRetries"))
        }
    }

    @Test
    fun `progress interval accepts out of range values silently`() {
        withTempDir { dir ->
            listOf("0", "-1", "999999").forEach { value ->
                val result = runCli(
                    "--progress-interval", value,
                    "--json",
                    "-o", File(dir, "progress-$value.bin").absolutePath,
                    "http://example.invalid/progress.bin",
                )
                assertEquals(0, result.exitCode, "--progress-interval $value: ${result.stderr}")
                assertFalse(result.stderr.contains("错误："), result.stderr)
            }
        }
    }

    @Test
    fun `no overlap is recorded as deprecated config assignment only`() {
        withTempDir { dir ->
            val capture = File(dir, "capture.properties")
            val result = runCli(
                "--no-overlap",
                "--json",
                "-o", File(dir, "out.bin").absolutePath,
                "http://example.invalid/out.bin",
                capture = capture,
            )
            assertEquals(0, result.exitCode, result.stderr)
            assertEquals("false", loadProperties(capture).getProperty("config.overlapMerge"))
            // TurboConfig.overlapMerge 已弃用且引擎不再读取；本断言只记录 CLI 赋值契约，
            // 不能证明 --no-overlap 改变了实际下载行为。
        }
    }

    @Test
    fun `known size reaches the SDK request and skips the extra probe`() {
        withTempDir { dir ->
            val capture = File(dir, "capture.properties")
            val result = runCli(
                "--known-size", "32241380",
                "--json",
                "-o", File(dir, "out.bin").absolutePath,
                "http://example.invalid/out.bin",
                capture = capture,
            )
            assertEquals(0, result.exitCode, result.stderr)
            assertEquals("32241380", loadProperties(capture).getProperty("request.knownSize"))
        }
    }

    @Test
    fun `json success is valid NDJSON with stable schema and no blank lines`() {
        withTempDir { dir ->
            val result = runCli(
                "--json",
                "-o", File(dir, "ok.bin").absolutePath,
                "http://example.invalid/ok.bin",
            )
            assertEquals(0, result.exitCode, result.stderr)
            val events = jsonEvents(result.stdout)
            assertNoBlankLines(result.stdout)
            assertEquals(listOf("start", "progress", "completed"), events.map { it.string("type") })

            events[0].requireStrings("type", "url", "output")
            events[0].requireNumbers("taskId")
            assertEquals(events[0].get("taskId").asLong, events[1].get("taskId").asLong)
            events[1].requireNumbers(
                "taskId", "downloaded", "total", "percent", "speed", "connections", "eta_ms",
            )
            assertEquals("COMPLETED", events[1].string("state"))
            events[2].requireStrings("type", "url", "file", "sha256", "stableKey")
            events[2].requireNumbers("taskId", "bytes")
            assertEquals(events[0].get("taskId").asLong, events[2].get("taskId").asLong)
            assertTrue(Regex("^[0-9a-f]{64}$").matches(events[2].string("sha256")))
        }
    }

    @Test
    fun `json failure schema and exit code are observable`() {
        withTempDir { dir ->
            val result = runCli(
                "--json",
                "--retry", "0",
                "-o", File(dir, "failed.bin").absolutePath,
                "http://example.invalid/failed.bin",
                progressMode = "failure",
            )
            assertEquals(1, result.exitCode, result.stderr)
            val events = jsonEvents(result.stdout)
            assertNoBlankLines(result.stdout)
            assertEquals(listOf("start", "progress", "failed", "summary"), events.map { it.string("type") })
            events[2].requireStrings("type", "url", "error")
            events[2].requireNumbers("taskId")
            events[3].requireNumbers("total", "failed")
            assertEquals(1, events[3].get("total").asInt)
            assertEquals(1, events[3].get("failed").asInt)
        }
    }

    @Test
    fun `quiet suppresses success stdout`() {
        withTempDir { dir ->
            val result = runCli(
                "--quiet",
                "-o", File(dir, "quiet.bin").absolutePath,
                "http://example.invalid/quiet.bin",
            )
            assertEquals(0, result.exitCode, result.stderr)
            assertEquals("", result.stdout)
        }
    }

    @Test
    fun `batch uses one client and shares the jobs budget`() {
        withTempDir { dir ->
            val batch = File(dir, "tasks.txt").apply {
                writeText(
                    "http://example.invalid/a.bin\n" +
                        "http://example.invalid/b.bin\n",
                )
            }
            val capture = File(dir, "capture.properties")
            val result = runCli(
                "--batch", batch.absolutePath,
                "--jobs", "2",
                "--json",
                "--dir", dir.absolutePath,
                capture = capture,
            )

            assertEquals(0, result.exitCode, result.stderr)
            val config = loadProperties(capture)
            assertEquals("2", config.getProperty("config.maxConcurrentTasks"))
            assertEquals("1", config.getProperty("client.instances"))
            assertEquals(
                File(dir, ".turbodl-parts").absolutePath,
                config.getProperty("request.workDir"),
            )
            assertTrue(File(dir, "a.bin").isFile)
            assertTrue(File(dir, "b.bin").isFile)
        }
    }

    private fun runCli(
        vararg args: String,
        capture: File? = null,
        progressMode: String = "completed",
    ): CliResult {
        val testClasses = File(
            CliContractTest::class.java.protectionDomain.codeSource.location.toURI()
        ).absoluteFile
        val classpath = testClasses.absolutePath + File.pathSeparator + System.getProperty("java.class.path")
        val javaExecutable = File(System.getProperty("java.home"), "bin/java.exe")
            .takeIf { it.isFile }
            ?: File(System.getProperty("java.home"), "bin/java")
        val command = mutableListOf(
            javaExecutable.absolutePath,
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            "-Dsun.stdout.encoding=UTF-8",
            "-Dsun.stderr.encoding=UTF-8",
            "-cp",
            classpath,
            "dev.turbodl.cli.MainKt",
        )
        command += args

        val process = ProcessBuilder(command)
            .also { builder ->
                capture?.let { builder.environment()["TURBODL_TEST_CAPTURE"] = it.absolutePath }
                builder.environment()["TURBODL_TEST_PROGRESS_MODE"] = progressMode
            }
            .start()
        process.outputStream.close()

        var stdout = ""
        var stderr = ""
        val stdoutReader = thread(name = "cli-test-stdout") {
            stdout = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val stderrReader = thread(name = "cli-test-stderr") {
            stderr = process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            error("CLI 子进程超时: ${args.joinToString(" ")}")
        }
        stdoutReader.join()
        stderrReader.join()
        return CliResult(process.exitValue(), stdout, stderr)
    }

    private fun jsonEvents(stdout: String): List<JsonObject> =
        stdout.trim().lines().filter { it.isNotBlank() }.map { line ->
            runCatching { Gson().fromJson(line, JsonObject::class.java) }
                .getOrElse { error("无效 NDJSON 行: $line\n$it") }
        }

    private fun assertNoBlankLines(stdout: String) {
        val body = stdout.trimEnd('\r', '\n')
        assertFalse(body.lines().any { it.isBlank() }, "NDJSON 不应包含空白行:\n$stdout")
    }

    private fun JsonObject.string(name: String): String {
        val value = get(name)
        assertNotNull(value, "缺少字段 $name: $this")
        assertTrue(value.isJsonPrimitive && value.asJsonPrimitive.isString, "字段 $name 应为字符串: $this")
        return value.asString
    }

    private fun JsonObject.requireStrings(vararg names: String) {
        names.forEach { string(it) }
    }

    private fun JsonObject.requireNumbers(vararg names: String) {
        names.forEach { name ->
            val value = get(name)
            assertNotNull(value, "缺少字段 $name: $this")
            assertTrue(value.isJsonPrimitive && value.asJsonPrimitive.isNumber, "字段 $name 应为数字: $this")
        }
    }

    private fun loadProperties(file: File): Properties {
        assertTrue(file.isFile, "未生成捕获文件: $file")
        return Properties().apply { file.inputStream().use(::load) }
    }

    private inline fun withTempDir(block: (File) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("turbodl-cli-test-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}

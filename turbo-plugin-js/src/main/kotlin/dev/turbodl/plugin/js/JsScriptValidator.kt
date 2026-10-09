package dev.turbodl.plugin.js

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Static checks for a JavaScript plugin **without running it**.
 *
 * Why this exists separately from the loader: a host that lets users write or paste a script (an
 * editor in an app, an import dialog, a marketplace upload form) needs to answer "is this even
 * parseable, and does it look like a TurboDL plugin?" *before* installing it. Loading it to find out
 * means side effects — the script's top level runs, it may register things, it may burn its whole
 * timeout — and a failed load is a much worse error channel than a line number.
 *
 * What it does:
 *  1. **Parses** the source. The source is wrapped in a function expression and evaluated with a
 *     tiny timeout, so a syntax error surfaces as a parse error while nothing user-visible executes.
 *  2. **Looks for the ABI surface** the script is supposed to use (`plugin.register*` / `host.*`),
 *     which catches the common "I wrote a plain script instead of a plugin" mistake.
 *  3. **Reads the declared permissions** so a host can show "this plugin asks for storage + http"
 *     before the user agrees to install it.
 *
 * What it deliberately does **not** do: sandbox policy resolution, permission grant checks, or any
 * judgement about whether the script is *safe*. Those belong to the loader at load time — a
 * validator that pretended to decide them would be a second, weaker policy engine.
 *
 * A parse check is not a proof of correctness: a script can parse fine and still throw on its first
 * line at load time. That is fine — the loader reports those, and this is a pre-filter, not a gate.
 */
object JsScriptValidator {

    /** Outcome of [validate]. [problems] is empty exactly when [ok] is true. */
    data class Report(
        val ok: Boolean,
        /** Parse error text (with the engine's line info when available), or null. */
        val syntaxError: String?,
        /** Non-fatal observations: missing ABI usage, empty registration, suspicious size, … */
        val warnings: List<String>,
        /** Permissions the script declares through `plugin.requires`, in the ABI's names. */
        val declaredPermissions: List<String>,
        /** Registration kinds the script appears to call (parser / taskPreHook / …). */
        val registrationKinds: List<String>,
        /** Lines of the script, useful for an editor's gutter. */
        val lineCount: Int,
    ) {
        val problems: List<String> get() = listOfNotNull(syntaxError)
    }

    /** Upper bound on a script we are willing to *parse*. Mirrors the loader's own source limit. */
    private const val MAX_SCRIPT_BYTES = 512 * 1024

    /** Parse budget. Parsing is fast; this only exists so a pathological input cannot hang a caller. */
    private const val PARSE_TIMEOUT_MS = 2_000L

    /**
     * 脚本能注册的东西 —— **以 `abi.js` 里真实存在的函数为准**。
     *
     * 这里刻意不做"猜名字"：校验器报出来的注册项要与加载器实际会注册的东西一致，
     * 否则用户会看到"校验说注册了解析器、装上去却什么都没发生"。所以这份表只列
     * `abi.js` 里 `plugin.xxx = function` 定义过的注册入口（`defineMeta` / `requires`
     * 不是注册入口，它们是声明，不在这里）。
     */
    private val REGISTRATION_MARKERS = listOf(
        "registerParser" to "parser",
        "registerTaskPreHook" to "preHook",
        "registerTaskPostHook" to "postHook",
        "onEvent" to "event",
        "onInit" to "init",
        "onDestroy" to "destroy",
    )

    /**
     * Validate [source].
     *
     * Runs on [Dispatchers.IO] because it creates a native QuickJS runtime and disposes it again.
     * Never throws for a bad script — a bad script is the *result*.
     */
    suspend fun validate(source: String): Report = withContext(Dispatchers.IO) {
        val lineCount = source.count { it == '\n' } + 1
        val warnings = mutableListOf<String>()

        if (source.isBlank()) {
            return@withContext Report(
                ok = false,
                syntaxError = "脚本内容为空",
                warnings = emptyList(),
                declaredPermissions = emptyList(),
                registrationKinds = emptyList(),
                lineCount = lineCount,
            )
        }
        if (source.toByteArray(Charsets.UTF_8).size > MAX_SCRIPT_BYTES) {
            return@withContext Report(
                ok = false,
                syntaxError = "脚本超过 ${MAX_SCRIPT_BYTES / 1024} KiB 上限（加载器也会拒绝）",
                warnings = emptyList(),
                declaredPermissions = emptyList(),
                registrationKinds = emptyList(),
                lineCount = lineCount,
            )
        }

        // ---- 1) 解析（只解析，不执行用户代码） ----
        val syntaxError = try {
            parseOnly(source)
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message.orEmpty()}".trim()
        }
        if (syntaxError != null) {
            return@withContext Report(
                ok = false,
                syntaxError = syntaxError,
                warnings = emptyList(),
                declaredPermissions = emptyList(),
                registrationKinds = emptyList(),
                lineCount = lineCount,
            )
        }

        // ---- 2) ABI 使用情况 ----
        val kinds = REGISTRATION_MARKERS
            .filter { (name, _) -> Regex("""\bplugin\s*\.\s*$name\s*\(""").containsMatchIn(source) }
            .map { it.second }
        if (kinds.isEmpty()) {
            warnings += "没有看到任何 plugin.register… / onEvent 调用 —— 这个脚本加载后不会做任何事"
        }
        if (!Regex("""\bplugin\s*\.\s*requires\s*\(""").containsMatchIn(source)) {
            warnings += "没有声明 plugin.requires({permissions}) —— 未声明的能力在运行时不可用"
        }

        // ---- 3) 声明的权限（只读文本，不执行） ----
        val permissions = Regex("""permissions\s*:\s*\[([^\]]*)]""")
            .find(source)
            ?.groupValues?.get(1)
            ?.let { body ->
                Regex("""['"]([a-z][a-z0-9_]*)['"]""").findAll(body).map { it.groupValues[1] }.toList()
            }
            ?: emptyList()

        Report(
            ok = true,
            syntaxError = null,
            warnings = warnings,
            declaredPermissions = permissions.distinct().sorted(),
            registrationKinds = kinds,
            lineCount = lineCount,
        )
    }

    /**
     * Parse [source] with the real engine and return an error string, or null when it parses.
     *
     * The source is wrapped in a **function expression** (`(function(){ … })`), so the body is
     * parsed without being executed: evaluating a function literal only compiles it. The wrapper
     * also means a top-level `return` — which is legal in a script body the loader evaluates — still
     * parses here.
     */
    private fun parseOnly(source: String): String? {
        val engine = try {
            QuickJs.create(jobDispatcher = Dispatchers.IO)
        } catch (t: Throwable) {
            // 引擎起不来是**环境**问题，不是脚本问题。如实说明，别让调用方以为脚本有错。
            return "无法创建 JS 引擎（本机不可用）：${t.javaClass.simpleName} ${t.message.orEmpty()}".trim()
        }
        return try {
            engine.evaluationTimeoutMillis = PARSE_TIMEOUT_MS
            engine.memoryLimit = 16L * 1024 * 1024
            engine.maxStackSize = 256L * 1024
            // 只编译，不调用：函数字面量的求值过程就是一次解析
            runBlockingEvaluate(engine, "(function(){\n$source\n})", "validate.js")
            null
        } catch (t: Throwable) {
            val message = t.message.orEmpty()
            // 超时说明连解析都没在预算内完成 —— 通常是脚本体积/嵌套异常极端
            if (message.contains("timeout", ignoreCase = true) || message.contains("interrupt", ignoreCase = true)) {
                "解析超时（${PARSE_TIMEOUT_MS}ms）—— 脚本可能异常庞大或嵌套过深"
            } else {
                message.ifBlank { t.javaClass.simpleName }
            }
        } finally {
            runCatching { engine.close() }
        }
    }

    /**
     * `QuickJs.evaluate` is a suspend function; this object's public API is suspend too, so there is
     * no reason to block a thread here — but `parseOnly` is called from a non-suspend helper, so the
     * call is routed through [kotlinx.coroutines.runBlocking] on the IO dispatcher it was given.
     */
    private fun runBlockingEvaluate(engine: QuickJs, code: String, filename: String) {
        kotlinx.coroutines.runBlocking { engine.evaluate<Any?>(code, filename) }
    }
}

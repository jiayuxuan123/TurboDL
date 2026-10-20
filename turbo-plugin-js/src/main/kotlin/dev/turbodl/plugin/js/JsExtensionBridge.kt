package dev.turbodl.plugin.js

import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.TurboEvent
import dev.turbodl.plugin.runtime.PluginContext
import dev.turbodl.plugin.runtime.ext.ExtensionPoints
import dev.turbodl.plugin.runtime.ext.FileHandler
import dev.turbodl.plugin.runtime.ext.HandledFile
import dev.turbodl.plugin.runtime.ext.LinkParser
import dev.turbodl.plugin.runtime.ext.ProtocolClaim
import dev.turbodl.plugin.runtime.ext.TaskPostHook
import dev.turbodl.plugin.runtime.ext.TaskPreHook
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The JS→TurboDL direction: turn one `plugin.register…(...)` call into a real extension-point
 * implementation registered in the host's registry (parser, hooks, file handler, protocol claim).
 *
 * ## Shape of one invocation
 * ```
 * consumer ──▶ JsLinkParser.parse(raw)                       // Kotlin contract, caller's thread
 *              link.invokeCallback(cbId, payload)             // refused once the instance is STOPPING
 *              QuickJS `__turboDispatchCallback(id, json)`    // on the plugin's own JS thread
 *              script returns a JS value → JSON envelope → JsValueCodec → DownloadRequest
 * ```
 * Data crosses as JSON text in both directions and the JS function never does. Consumers see an
 * ordinary `LinkParser`, never "a JS thing" — which is what lets Kotlin and JS plugins coexist in
 * the same extension point with the same priority rules.
 *
 * ## Failure semantics are per contract, not global
 * A throwing script must never be able to fail a download, so "JS failed" is mapped onto the meaning
 * the extension point already has:
 *  - [LinkParser] → `null` — §6: "MUST return null (not throw) for input it does not handle, so the
 *    router can try the next parser". A broken parser behaves exactly like a non-matching one.
 *  - [TaskPreHook] → the input request unchanged — §6: "return the input unchanged to no-op".
 *  - [TaskPostHook] and the event listener → logged and swallowed — §8: listeners must not throw.
 *  - [FileHandler] → `matches` 返回 `false`（"这个文件我不接"），`handle` 返回一句说明失败的话。
 *    该挂载点自己的约定是"处理器抛异常只记日志，已完成的下载不受影响"，所以这两个方法都不许抛，
 *    也都不许把任务变成失败。
 *  - [ProtocolClaim] → 没有可失败的调用点：claim 是纯数据、没有回调。它唯一的失败面是注册时的
 *    入参校验（见 [protocolClaimFor]）。
 *
 * Every failure is logged with the plugin id and the ABI error code, so "my parser stopped matching"
 * is debuggable rather than silent.
 *
 * ## Concurrency
 * §6: implementations "MUST tolerate being called concurrently". [JsRuntime] serializes every
 * invocation onto one JS thread, so concurrent consumers queue instead of corrupting the context;
 * nothing here holds a lock across a JS call.
 *
 * ## Unloading one registration
 * `PluginContext` deliberately exposes no per-registration cancel (registrations are removed as a set
 * when the plugin unloads, through its disposer). So [deactivate] neutralizes a registration: its
 * callback is released and the adapter short-circuits to its own no-op, making the entry inert and
 * cheap. The registry entry itself disappears when the plugin is unloaded — recorded as a known
 * limitation in the README rather than worked around by reaching into `ExtensionRegistry`, which
 * would mean handing a Kotlin internals object to JS-owned code.
 */
internal class JsExtensionBridge(
    private val pluginId: String,
    private val context: PluginContext,
    private val sandbox: JsSandbox,
    private val linkProvider: () -> JsInstanceLink?,
    /**
     * Priority applied when a script does not set `impl.priority`. The loader can pass a value so a
     * host demotes *all* JS plugins at once (e.g. below a trusted Kotlin adapter) without editing
     * scripts; null keeps the [DEFAULT_PRIORITY] convention value.
     */
    private val loaderDefaultPriority: Int? = null,
) {

    /** One registration the script made, plus the flag that makes teardown per-registration. */
    private class Registration(
        val handle: Long,
        val kind: String,
        val jsCallbackId: Long,
        val priority: Int,
    ) {
        val active = AtomicBoolean(true)
    }

    private val byHandle = ConcurrentHashMap<Long, Registration>()
    private val nextHandle = AtomicLong(1)

    /**
     * Serve one `__turbodlRegister(kind, cbId, opts)`.
     *
     * @return data for the script — `{handle, kind, priority}`, all plain values. The
     *   [Registration] object never leaves this class.
     */
    fun install(kind: String, jsCallbackId: Long, optionsJson: String?): Map<String, Any?> {
        val options = parseOptions(optionsJson)
        // Precedence: the script's own `impl.priority`, then the loader-wide default, then the
        // convention value. A host demoting all JS plugins must not be able to override a script
        // that stated a priority deliberately, and a stated one must never be silently dropped.
        // Strict on range: a stated-but-out-of-range priority is a bug in the script, and falling
        // back to the default here would make the plugin route at a number its author never chose.
        val scriptPriority = JsValueCodec.asIntInRange(options["priority"], PRIORITY_RANGE, "registration priority")
        val priority = scriptPriority ?: loaderDefaultPriority ?: DEFAULT_PRIORITY
        val reg = Registration(nextHandle.getAndIncrement(), kind, jsCallbackId, priority)
        when (kind) {
            "parser" -> context.registerExtension(ExtensionPoints.LINK_PARSER, parserFor(reg), priority)
            "preHook" -> context.registerExtension(ExtensionPoints.TASK_PRE_HOOK, preHookFor(reg), priority)
            "postHook" -> context.registerExtension(ExtensionPoints.TASK_POST_HOOK, postHookFor(reg), priority)
            "event" -> context.onEvent(eventListenerFor(reg))
            // 一个 claim 是纯声明：脚本这边没有回调可绑，所以 cbId 是占位的 0（ABI 侧同样不发绑定，
            // 见 abi.js 的 registerProtocol）。释放一个不存在的回调 id 是 no-op，因此 unload 路径无需特例。
            "protocol" -> context.registerExtension(ExtensionPoints.PROTOCOL_HANDLER, protocolClaimFor(reg, options), priority)
            "fileHandler" -> context.registerExtension(ExtensionPoints.FILE_HANDLER, fileHandlerFor(reg, options), priority)
            "backend" -> throw JsAbi.AbiException(
                JsAbi.Code.UNSUPPORTED,
                "JS plugins may not register a DownloadBackend: the byte/chunk plane stays in Kotlin " +
                    "(host.http.downloadToFile gives a script the finished file instead)",
            )
            "service" -> throw JsAbi.AbiException(
                JsAbi.Code.UNSUPPORTED,
                "JS plugins may not publish host services; a service is a Kotlin object graph",
            )
            else -> throw JsAbi.AbiException(
                JsAbi.Code.VALIDATION,
                "unknown registration kind '$kind' (parser | preHook | postHook | fileHandler | protocol | event)",
            )
        }
        byHandle[reg.handle] = reg
        return mapOf("handle" to reg.handle, "kind" to kind, "priority" to priority.toLong())
    }

    /** Live registrations (diagnostics / unload reporting). */
    fun activeCount(): Int = byHandle.values.count { it.active.get() }

    fun describe(): List<String> = byHandle.values.map { "${it.kind}#${it.handle}@${it.priority}" }.sorted()

    /**
     * Neutralize every registration and return the JS callback ids to release.
     *
     * Runs *before* the drain so a consumer that races the unload gets a clean no-op (`null` from a
     * parser, the input back from a hook) rather than blocking on an instance that is going away.
     */
    fun deactivateAll(): List<Long> {
        val ids = byHandle.values.filter { it.active.getAndSet(false) }.map { it.jsCallbackId }
        byHandle.clear()
        return ids
    }

    // ------------------------------------------------------------------ adapters

    private fun parserFor(reg: Registration): LinkParser = LinkParser { raw ->
        if (!reg.active.get()) return@LinkParser null
        // The *result* is plugin-authored data, so validating it is as much a plugin fault as
        // throwing is: `LinkParser.parse` must return null, never propagate (CONVENTION.md §6).
        val result = try {
            when (val call = call("parse", reg, mapOf("input" to raw))) {
                is CallFailure -> {
                    context.log("parser #${reg.handle} failed (${call.code}): ${call.message} — falling through to the next parser")
                    return@LinkParser null
                }
                is CallSuccess -> call.value
            }
        } catch (t: Throwable) {
            context.log("parser #${reg.handle} returned unusable data (${t.message}) — falling through to the next parser")
            return@LinkParser null
        }
        try {
            toRequests(result, reg)
        } catch (t: JsAbi.AbiException) {
            context.log("parser #${reg.handle} returned an invalid request (${t.code}): ${t.message} — falling through to the next parser")
            null
        } catch (t: JsPermissionException) {
            context.log("parser #${reg.handle} returned a refused request (${t.message}) — falling through to the next parser")
            null
        } catch (t: JsValueCodec.JsCodecException) {
            context.log("parser #${reg.handle} returned unbridgeable data (${t.message}) — falling through to the next parser")
            null
        }
    }

    private fun preHookFor(reg: Registration): TaskPreHook = TaskPreHook { request ->
        if (!reg.active.get()) return@TaskPreHook request
        val result = call("beforeSubmit", reg, mapOf("request" to JsRequests.toMap(request, sandbox)))
        when {
            result is CallFailure -> {
                context.log("pre-hook #${reg.handle} failed (${result.code}): ${result.message} — request left unchanged")
                request
            }
            // `undefined`/null from the script means "no change"; anything else is a new descriptor.
            result is CallSuccess && result.value == null -> request
            result is CallSuccess -> try {
                // `request` is passed as the round-trip reference: a hook legitimately echoes the
                // destination it was given while changing headers, and that path was the host's
                // choice, not the script's. See JsRequests.fromMap.
                JsRequests.fromMap(result.value, sandbox, "request returned by pre-hook #${reg.handle}", request)
            } catch (t: JsAbi.AbiException) {
                context.log("pre-hook #${reg.handle} returned an invalid request (${t.code}): ${t.message} — request left unchanged")
                request
            } catch (t: JsValueCodec.JsCodecException) {
                // `headers`/`tags` are validated by the codec, which speaks its own exception. An
                // uncaught one here would land in TurboClient's submit path — the exact thing §6
                // forbids for a plugin.
                context.log("pre-hook #${reg.handle} returned unbridgeable data (${t.message}) — request left unchanged")
                request
            } catch (t: JsPermissionException) {
                context.log("pre-hook #${reg.handle} returned a refused request (${t.message}) — request left unchanged")
                request
            }
            else -> request
        }
    }

    private fun postHookFor(reg: Registration): TaskPostHook = TaskPostHook { request, success, detail ->
        if (!reg.active.get()) return@TaskPostHook
        val payload = mapOf(
            "request" to JsRequests.toMap(request, sandbox),
            "success" to success,
            "detail" to detail,
        )
        val result = call("afterFinish", reg, payload)
        if (result is CallFailure) {
            context.log("post-hook #${reg.handle} failed (${result.code}): ${result.message} — ignored")
        }
    }

    private fun eventListenerFor(reg: Registration): (TurboEvent) -> Unit = { event ->
        // Observation only: a script cannot influence the engine from here, and a throwing listener
        // can never break the event pump — every failure is converted below, so no `throw` escapes.
        // The invocation is deliberately synchronous and bounded: a slow listener holds the pump for
        // at most one invocationTimeoutMillis budget. That is the documented contract (bounded
        // observer); full async delivery would trade that bounded pause for unbounded event
        // reordering plus a second lifecycle to audit.
        if (reg.active.get()) {
            val result = call("onEvent", reg, eventToMap(event))
            if (result is CallFailure) {
                context.log("event listener #${reg.handle} failed (${result.code}): ${result.message} — ignored")
            }
        }
    }

    // ------------------------------------------------------------ file handler (P16)

    /**
     * JS `FileHandler`：`matches` / `handle` 是**同一个注册的两个方法**，因此走这个注册自己那一个
     * JS 回调 id（ABI 侧用 `payload.op` 分流），而不是再偷偷多要一个 id —— 后者会破坏"unload 释放
     * 的恰好是本注册拥有的回调"这条不变量，而 `deactivateAll()` / `releaseCallbacks()` 都建立在它上面。
     *
     * `id` / `name` 只用于诊断与界面展示。脚本自报的 `id` 会被**加上宿主给的 pluginId 前缀**
     * （`<pluginId>:<declared>`），理由与 [ProtocolClaim.pluginId] 由宿主填写相同：脚本不该能在宿主的
     * 处理器列表里冒充另一个插件。
     */
    private fun fileHandlerFor(reg: Registration, options: Map<*, *>): FileHandler {
        val declaredId = optionText(options, "id")
        val handlerId = if (declaredId == null) pluginId else "$pluginId:$declaredId"
        val handlerName = optionText(options, "name") ?: pluginId
        return object : FileHandler {
            override val id: String get() = handlerId
            override val name: String get() = handlerName

            override fun matches(file: HandledFile): Boolean {
                // 实例正在离场时是"惰性"的：false 就是本契约里的"这个文件我不接"，宿主会直接跳过它。
                // 这里返回 true 反而更糟 —— 那会把文件交给一个已经跑不起来的 handle。
                if (!reg.active.get()) return false
                return when (val result = call("matches", reg, fileHandlerPayload("matches", file))) {
                    is CallFailure -> {
                        context.log(
                            "file handler #${reg.handle} ($handlerId) failed in matches() (${result.code}): " +
                                "${result.message} — the file is left to the next handler",
                        )
                        false
                    }
                    is CallSuccess -> jsTruthy(result.value)
                }
            }

            override fun handle(file: HandledFile): String {
                // 与 matches() 同一姿态：绝不抛。挂载点的约定是"处理器出错只记日志、已完成的下载
                // 不受影响"，而 handle 的返回值恰好就是一句话 —— 所以失败在这里被**报告**成一句话，
                // 而不是抛进一个已经成功的下载里。
                if (!reg.active.get()) return "JS file handler $handlerId is unloaded; ${file.fileName} was not handled"
                return when (val result = call("handle", reg, fileHandlerPayload("handle", file))) {
                    is CallFailure -> {
                        context.log(
                            "file handler #${reg.handle} ($handlerId) failed in handle() (${result.code}): " +
                                "${result.message} — the finished download is unaffected",
                        )
                        "handling ${file.fileName} failed ($handlerId): ${result.code} — ${result.message}"
                    }
                    is CallSuccess -> describeHandleResult(result.value, handlerId, file)
                }
            }
        }
    }

    /** 两个方法共用一个回调，用 `op` 分流；`file` 是宿主侧只读描述的 JSON 快照。 */
    private fun fileHandlerPayload(op: String, file: HandledFile): Map<String, Any?> =
        mapOf("op" to op, "file" to handledFileToMap(file))

    /**
     * `handle` 要的是"给用户看的一句话"，所以这里对 codec 允许的每一种形状都给出**可打印**的东西：
     * 非字符串标量直接转文本（脚本返回一个计数不该让这行日志失败），`null`/`undefined` 与结构化数据
     * 则如实说"没有可用说明"，而不是替脚本编一句话。
     *
     * 说明文本用英文：它与同一条路径上的其他日志行（宿主与引擎的日志）是同一批输出，混排两种语言
     * 只会让用户在同一条时间线上看到两种口吻。
     */
    private fun describeHandleResult(value: Any?, handlerId: String, file: HandledFile): String = when {
        value == null -> "JS file handler $handlerId returned no message for ${file.fileName}"
        value is String -> value.trim().take(MAX_HANDLE_MESSAGE_CHARS).ifEmpty {
            "JS file handler $handlerId returned a blank message for ${file.fileName}"
        }
        value is Number || value is Boolean -> value.toString()
        else -> "JS file handler $handlerId returned data that cannot be shown as a message (${value.javaClass.simpleName})"
    }

    /**
     * JS 真值语义，在 Kotlin 侧再判一次。
     *
     * ABI 已经用 `!!` 收口，正常脚本到这里一定是 Boolean；其余形状是 `__turbodlRegister` 原语仍能
     * 送进来的。`matches` 只是一个"接不接这个文件"的闸门，唯一后果是宿主会不会调 `handle`，所以按
     * JS 的 truthy 语义映射是对的 —— 若改成严格 `is Boolean`，一个 `return file.sizeBytes > 0 && …`
     * 写成数字/字符串的脚本会**静默地永不命中**，那正是本模块其余地方用严格校验去避免的失败形态。
     */
    private fun jsTruthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Double -> value != 0.0 && !value.isNaN()
        is Number -> value.toLong() != 0L
        is String -> value.isNotEmpty()
        else -> true
    }

    // ------------------------------------------------------------ protocol claim

    /**
     * JS `ProtocolClaim` —— 脚本唯一"声明"而非"实现"的扩展点。
     *
     * 没有回调可包、调用期也没有可失败的点，所以整个 adapter 就是入参校验。这份校验与
     * [ProtocolRegistry] 的不重复：registry 会把 claim 归一化，并且**静默丢弃**归一化不出合法
     * 协议名的值，因此一个不校验的 claim 会产出"加载成功、却在任何 scheme 查询里都不存在"的插件。
     * 在注册期失败就把这份沉默换成一个点名字段的加载错误（与越界 `priority` 同一姿态）。
     *
     * `pluginId` 取宿主给本实例的 id，绝不取脚本自报的 id：`ProtocolClaim.pluginId` 是市场与诊断
     * 展示的"声明者"，不能因为脚本改写自己的 `plugin.id` 就被冒认。
     */
    private fun protocolClaimFor(reg: Registration, options: Map<*, *>): ProtocolClaim {
        val raw = options["scheme"]
        val scheme = (raw as? String)?.trim()?.lowercase()?.takeIf { PROTOCOL_SCHEME.matches(it) }
            ?: throw JsAbi.AbiException(
                JsAbi.Code.VALIDATION,
                "a 'protocol' registration requires a scheme matching [a-z][a-z0-9+.-]{0,31}; got " +
                    (raw?.toString()?.take(40)?.let { "'$it'" } ?: "nothing"),
            )
        return ProtocolClaim(
            scheme = scheme,
            pluginId = pluginId,
            // 与注册本身的 priority 同值 —— ProtocolClaim 的 KDoc 要求通用注册表与 scheme 索引
            // 对顺序的理解一致。
            priority = reg.priority,
            label = optionText(options, "label").orEmpty(),
        )
    }

    /** 一个去空白、限长的字符串选项；缺失/空白/非字符串一律 null。 */
    private fun optionText(options: Map<*, *>, key: String): String? =
        (options[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_OPTION_CHARS)

    /**
     * `HandledFile` → JS 形状。字段与宿主契约一一对应，没有额外推导（宿主已经算过 MIME/大小，
     * 处理器不该自己再猜一遍）。
     *
     * ## `absolutePath` 原样传出（判断与理由）
     *
     * 这是本文件里唯一一处把**真实文件系统路径**交给沙箱里的脚本，所以需要明确的理由：
     *
     *  1. **这个挂载点的用途就是"把文件交给别的东西"**（起播放器、导入相册、丢进某目录）。那些动作
     *     的目标是插件自己沙箱外的对象，路径是**必需的输入**，不是可以省略的实现细节。抹掉它等于把
     *     这个扩展点变成"只知道有个文件、什么也做不了"——那还不如不开放。
     *  2. **沙箱管的是"脚本让宿主写哪里"，不是"脚本读到什么"。** 宿主已有的信息面（`onEvent` 的
     *     `Completed.file`、`postHook` 的 `detail`、`host.http.downloadToFile` 的返回路径）本来就
     *     带着绝对路径，而且那是宿主**主动交出去**的。这里多传一条同类事实不新增暴露面。
     *  3. **它不授予任何写权限。** 路径是字符串；脚本能拿它做的仍然只有经宿主的能力（`host.http`、
     *     `host.storage`），而那些入口都各自过沙箱/权限校验。`FileHandler` 契约本身也只读（见
     *     `ExtensionPoints.HandledFile` 的 KDoc："只有只读信息，不给写权限"）。
     *
     * 反过来，**裁剪或改写路径才是更糟的选择**：处理器拿不到真路径就无法把文件交给别的 App，而一个
     * "看起来像路径、实际不是"的字符串会让插件在别处悄悄失败——那正是本模块用严格校验去避免的形态。
     */
    private fun handledFileToMap(file: HandledFile): Map<String, Any?> = mapOf(
        "taskId" to file.taskId,
        "absolutePath" to file.absolutePath,
        "fileName" to file.fileName,
        "sizeBytes" to file.sizeBytes,
        // MIME 为空串时如实传空串：契约明确说"不要猜"，替宿主填一个 application/octet-stream
        // 会让处理器按错误的类型决策，而它无从分辨那是宿主的判断还是宿主的缺省。
        "mimeType" to file.mimeType,
        "extension" to file.extension,
        // sourceUrl 可能含 token（契约里写明了）：这里只传给插件自己，不进日志 —— 本 adapter 的所有
        // 日志行都只打印文件名/handler id，不打印 URL。
        "sourceUrl" to file.sourceUrl,
    )

    // ------------------------------------------------------------------ shared plumbing

    private sealed interface CallResult
    private class CallSuccess(val value: Any?) : CallResult
    private class CallFailure(val code: String, val message: String) : CallResult

    /**
     * Invoke the script's callback, converting *every* failure into a tagged result.
     *
     * A `GONE` here is the expected outcome while an instance is stopping, which is why it is logged
     * at the adapter level and never re-thrown: the callers of these extension points (TurboClient's
     * submit path, the event pump) must not see an exception from a plugin that is simply leaving.
     */
    private fun call(label: String, reg: Registration, payload: Any?): CallResult = try {
        val link = linkProvider()
            ?: return CallFailure(JsAbi.Code.GONE, "the JS runtime is gone")
        CallSuccess(link.invokeCallback(reg.jsCallbackId, payload))
    } catch (t: JsAbi.AbiException) {
        CallFailure(t.code, t.message ?: label)
    } catch (t: JsValueCodec.JsCodecException) {
        CallFailure(JsAbi.Code.VALIDATION, t.message ?: "codec error")
    } catch (t: Throwable) {
        CallFailure(JsAbi.Code.INTERNAL, t.message ?: t.javaClass.simpleName)
    }

    /** A parser result is `null` (no match), a single descriptor, or a list of them. */
    private fun toRequests(value: Any?, reg: Registration): List<DownloadRequest>? {
        if (value == null) return null
        val items = (value as? List<*>) ?: return listOf(single(value, reg))
        if (items.isEmpty()) return null
        JsRequests.checkCount(items.size)
        return items.map { single(it, reg) }
    }

    private fun single(value: Any?, reg: Registration): DownloadRequest =
        JsRequests.fromMap(value, sandbox, "request #${reg.handle} returned by the parser")

    private fun parseOptions(optionsJson: String?): Map<*, *> {
        if (optionsJson.isNullOrEmpty() || optionsJson == "null") return emptyMap<String, Any?>()
        val decoded = try {
            JsValueCodec.decodeFromJs(optionsJson, "registration options")
        } catch (t: JsValueCodec.JsCodecException) {
            throw JsAbi.AbiException(JsAbi.Code.VALIDATION, t.message ?: "invalid registration options", t)
        }
        return decoded as? Map<*, *>
            ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "registration options must be an object")
    }

    override fun toString(): String = "JsBridge($pluginId, active=${activeCount()})"

    private companion object {
        /**
         * Default priority for JS registrations. `CONVENTION.md` §6: base plugins `0`, HLS `100`,
         * adapters commonly `200`. A JS plugin is by construction an adapter (it recognizes links of
         * its own), so `200` matches intent without letting JS outrank a Kotlin plugin that asked for
         * more. A script may override it per registration via `impl.priority`.
         */
        const val DEFAULT_PRIORITY = 200
        val PRIORITY_RANGE: IntRange = -10_000..10_000

        /**
         * 合法协议名：与 [ProtocolRegistry] 的 `VALID_SCHEME` 同一形状（RFC 3986 的 scheme，1–32 字符）。
         *
         * 刻意复制而不是引用（那里的正则是 private）：注册期这一份是**诊断用**的，registry 那一份是
         * **归一化用**的，两者允许各自演进 —— 但今天必须一致，否则会出现"这里通过、那里被丢弃"的
         * 插件。测试用同一批取值把两侧钉在一起。
         */
        val PROTOCOL_SCHEME = Regex("^[a-z][a-z0-9+.-]{0,31}$")

        /** 注册选项里 `id`/`name`/`label` 的长度上限：它们是诊断文本，不是数据通道。 */
        const val MAX_OPTION_CHARS = 200

        /**
         * `FileHandler.handle` 返回值会进日志（可能进通知），所以在这里截断而不是信任脚本的长度：
         * 一次调用能写多长日志是宿主的能力，不该由插件决定。
         */
        const val MAX_HANDLE_MESSAGE_CHARS = 500
    }
}

/**
 * `DownloadRequest` ⇄ the JS descriptor shape, in one place.
 *
 * ## Why a hand-rolled mapping
 * [DownloadRequest] holds a `java.io.File`, an `Int?` and two string maps. Handing that graph to JS
 * would break the module's first rule (no Kotlin internals in the script world) and would pin the
 * ABI to core's field names. So the JS contract is a *documented JSON shape* and this object is the
 * only code that knows both sides:
 *
 * ```js
 * { url, destination?, fileName?, headers?, knownSize?, connections?, stableKey?, tags? }
 * ```
 *
 * On the way **in** `destination` is an absolute path *string* (never a `File`); on the way **back**
 * it is a bare file name, or a path only if the loader enabled
 * [JsPluginConfig.allowExternalDestination]. Everything else is a string, number or string map —
 * inside the [JsValueCodec] domain by construction.
 *
 * ## Bounds
 * Descriptors are plugin-authored data, so they are validated like any other untrusted input: a
 * missing `url`, a `connections` outside 1..256, or a non-http scheme fails the whole result rather
 * than applying it partially. A parser that returns one bad entry is a broken parser; silently
 * dropping that entry would change the download list, which is the one thing a parser must not do.
 */
internal object JsRequests {

    private const val MAX_URL_CHARS = 8 * 1024
    private const val MAX_REQUESTS = 256

    /** Same ceiling as `TurboConfig.maxConnectionsPerTask`; a script may not exceed the engine. */
    private val CONNECTIONS_RANGE: IntRange = 1..256

    /** Kotlin → JS. */
    fun toMap(request: DownloadRequest, sandbox: JsSandbox): Map<String, Any?> = mapOf(
        "url" to request.url,
        // A path string, and a hook that round-trips it unchanged still goes through the containment
        // check on the way back — so a hook cannot launder an outside path into the sandbox.
        "destination" to request.destination.absolutePath,
        "fileName" to request.destination.name,
        "headers" to request.headers,
        "knownSize" to request.knownSize,
        "connections" to request.connectionsOverride,
        "stableKey" to request.stableKey,
        "tags" to request.tags,
        // The plugin's own directory, so a script can explain where its relative names land.
        "sandbox" to sandbox.root.absolutePath,
    )

    /**
     * JS → Kotlin for one descriptor.
     *
     * @param roundTrip the engine's own request when this descriptor came *from* the host (a pre-hook
     *   result). Its `destination` is accepted verbatim, because the host chose it: without that edge,
     *   a hook that only edits headers would be rejected for echoing the absolute path it was handed —
     *   a silent no-op that looks like a working plugin. A parser passes no reference, so its script
     *   cannot name any path outside its sandbox.
     * @throws JsAbi.AbiException on a shape or bounds violation.
     */
    fun fromMap(value: Any?, sandbox: JsSandbox, label: String, roundTrip: DownloadRequest? = null): DownloadRequest {
        val obj = value as? Map<*, *>
            ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "$label must be an object")
        val url = (obj["url"] as? String)?.trim().orEmpty()
        if (url.isEmpty() || url.length > MAX_URL_CHARS) {
            throw JsAbi.AbiException(
                JsAbi.Code.VALIDATION,
                "$label.url must be a non-empty string of at most $MAX_URL_CHARS characters",
            )
        }
        // Scheme checked here, not left to the transport: a parser returning `file:///etc/passwd`
        // should fail as a plugin error instead of starting an engine task for it.
        JsRequestPolicy.parseUrl(url)
        val headers = JsValueCodec.asStringMap(obj["headers"], "$label.headers")
        val tags = JsValueCodec.asStringMap(obj["tags"], "$label.tags")
        val knownSize = JsValueCodec.asLongOrNull(obj["knownSize"]) ?: -1L
        // Strict: `connections: 99999` must be a `validation` failure, not a quiet fall back to the
        // engine default — a script that asked for 99999 threads and got 8 has no way to notice.
        val connections = JsValueCodec.asIntInRange(obj["connections"], CONNECTIONS_RANGE, "$label.connections")
        val stableKey = (obj["stableKey"] as? String)?.takeIf { it.isNotBlank() }?.take(200)
        val suggested = (obj["fileName"] as? String) ?: url.substringAfterLast('/').ifBlank { "download.bin" }
        val echoed = JsValueCodec.asStringOrNull(obj["destination"])
        // Verbatim echo of the destination the host handed out keeps the host's own file; anything
        // else goes back through the sandbox policy, so only the *unchanged* path is trusted.
        val destination = if (roundTrip != null && echoed != null && echoed == roundTrip.destination.absolutePath) {
            roundTrip.destination
        } else {
            // The sandbox policy speaks JsPermissionException; the extension adapters only catch ABI
            // failures, so translate here rather than letting a policy refusal escape into the
            // engine's submit path.
            try {
                sandbox.resolveDestination(echoed, suggested)
            } catch (e: JsPermissionException) {
                throw JsAbi.AbiException(JsAbi.Code.PERMISSION, e.message ?: "destination refused", e)
            }
        }
        return DownloadRequest(
            url = url,
            destination = destination,
            headers = headers,
            knownSize = knownSize,
            connectionsOverride = connections,
            stableKey = stableKey,
            tags = tags,
        )
    }

    /** Ceiling on how many requests one parse may return. */
    fun checkCount(size: Int) {
        if (size > MAX_REQUESTS) {
            throw JsAbi.AbiException(JsAbi.Code.LIMIT, "a parser returned $size requests; the limit is $MAX_REQUESTS")
        }
    }
}

/** `TurboEvent` → a flat, JSON-domain snapshot. */
internal fun eventToMap(event: TurboEvent): Map<String, Any?> {
    val base = mapOf("type" to event.javaClass.simpleName, "taskId" to event.taskId)
    return when (event) {
        is TurboEvent.Created -> base + mapOf("url" to event.request.url)
        is TurboEvent.StateChanged -> base + mapOf("state" to event.state.name)
        is TurboEvent.Progress -> base + mapOf(
            "state" to event.progress.state.name,
            "downloadedBytes" to event.progress.downloadedBytes,
            "totalBytes" to event.progress.totalBytes,
            "speedBytesPerSec" to event.progress.speedBytesPerSec,
            "activeConnections" to event.progress.activeConnections,
            "etaMillis" to event.progress.etaMillis,
            "percent" to event.progress.percent.toLong(),
            "error" to event.progress.error,
        )
        is TurboEvent.Completed -> base + mapOf("file" to event.file.absolutePath, "totalBytes" to event.totalBytes)
        is TurboEvent.Failed -> base + mapOf("reason" to event.reason)
        is TurboEvent.Metadata -> base + mapOf(
            "suggestedFileName" to event.suggestedFileName,
            "contentType" to event.contentType,
            "etag" to event.etag,
            "lastModified" to event.lastModified,
            "totalBytes" to event.totalBytes,
            "supportsRange" to event.supportsRange,
            "resolvedUrl" to event.resolvedUrl,
            "probeMs" to event.probeMs,
        )
    }
}

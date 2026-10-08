package dev.turbodl.plugin.js

import dev.turbodl.core.TurboConfig
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The single JS→host boundary for one plugin instance: implements [JsHostApi.dispatch] by routing a
 * capability path to the owning capability object, **after** checking the grant.
 *
 * ## Why one funnel
 * `abi.js` binds exactly three primitives, so every script-side call lands here. One entry point
 * means one place where permissions are enforced, one place to audit, and no capability can be
 * reached by a path the loader did not consider. It also means the check cannot be forgotten by a
 * new capability author: an unrouted path is `unsupported`, not "works because someone wired it".
 *
 * ## Permission model
 * Grant is per loader ([JsPluginConfig.permissions]) and re-checked per call — including `host.log`,
 * which is a declared capability ([JsCapability.LOG]) and therefore gated like every other one. It is
 * in the default grant, so the common case is unchanged; a loader that removes `log` gets a plugin
 * that cannot write plugin log lines. The check lives on the **JS-facing** door only: the host's own
 * diagnostics for that instance (timer failures, refusal notices) still reach the sink, because a
 * plugin must not be able to silence the host by lacking a grant.
 *
 * Every capability path maps onto a [JsCapability]:
 *
 * | path                     | capability |
 * |--------------------------|------------|
 * | `http.request`           | HTTP       |
 * | `http.downloadToFile`    | HTTP       |
 * | `crypto.*`               | CRYPTO     |
 * | `time.*`                 | TIME       |
 * | `storage.*`              | STORAGE    |
 * | `env.*`                  | ENV        |
 * | `timer.*`                | TIMER      |
 *
 * (`host.log` has no dispatch path: it travels on the `__turbodlLog` fast path, which is checked the
 * same way — see [log].)
 *
 * "Not granted" and "granted but refused" stay distinct: the first throws
 * [JsPermissionException] (code `permission`, message names the capability to request), the second
 * throws [JsAbi.AbiException] with its own code. A plugin author sees a different error for a policy
 * gap than for a bad argument, which is the difference between "add the grant" and "fix the call".
 * `host.log` is the one deliberate exception to that reporting rule: it must never throw (see [log]),
 * so an ungranted log line is dropped and reported once by the host instead of surfacing to the script.
 *
 * ## Lifecycle
 * [shutdown] releases the OkHttp client and cancels every pending timer. It runs from the disposer,
 * after JS has drained and before the QuickJS instance is closed, so a timer can never fire into a
 * dead engine.
 */
internal class JsHost(
    val pluginId: String,
    val config: JsPluginConfig,
    engineConfig: TurboConfig,
    private val sandbox: JsSandbox,
    /** Log sink; the caller renders the plugin-id prefix (see [emit]). */
    private val sink: (level: String, text: String) -> Unit,
    /** How a host-owned resource (timer) reaches JS; null until the runtime exists. */
    private val linkProvider: () -> JsInstanceLink?,
    /** Shared scheduler for timers; the manager owns its lifecycle. */
    private val scheduler: ScheduledExecutorService,
) : JsHostApi {

    /** Ensures an ungranted `host.log` is announced once per instance, not once per line. */
    private val logRefusalReported = java.util.concurrent.atomic.AtomicBoolean(false)

    private val http = JsHostHttp(pluginId, config, engineConfig, sandbox)
    private val crypto = JsHostCrypto(config)
    private val environment = JsHostEnvironment(config, sandbox)
    /** JS-visible timer id → pending task. Only this map is touched by timer.* paths. */
    private val timers = java.util.concurrent.ConcurrentHashMap<Long, ScheduledFuture<*>>()
    private val nextTimerId = AtomicLong(1)

    override fun dispatch(path: String, payloadJson: String?): Any? = when (path) {
        "http.request" -> {
            requireCapability(JsCapability.HTTP, "host.http.request")
            http.request(payloadJson)
        }
        "http.downloadToFile" -> {
            requireCapability(JsCapability.HTTP, "host.http.downloadToFile")
            http.downloadToFile(payloadJson)
        }
        "crypto.digest" -> {
            requireCapability(JsCapability.CRYPTO, "host.crypto")
            crypto.digest(payloadJson)
        }
        "crypto.hmac" -> {
            requireCapability(JsCapability.CRYPTO, "host.crypto")
            crypto.hmac(payloadJson)
        }
        "crypto.randomBytes" -> {
            requireCapability(JsCapability.CRYPTO, "host.crypto")
            crypto.randomBytes(payloadJson)
        }
        "crypto.base64" -> {
            requireCapability(JsCapability.CRYPTO, "host.crypto")
            crypto.base64(payloadJson)
        }
        "crypto.hex" -> {
            requireCapability(JsCapability.CRYPTO, "host.crypto")
            crypto.hex(payloadJson)
        }
        "time.now" -> {
            requireCapability(JsCapability.TIME, "host.time")
            environment.now()
        }
        "time.iso" -> {
            requireCapability(JsCapability.TIME, "host.time")
            environment.iso(payloadJson)
        }
        "time.sleep" -> {
            requireCapability(JsCapability.TIME, "host.time")
            environment.sleep(payloadJson)
        }
        "storage.get" -> {
            requireCapability(JsCapability.STORAGE, "host.storage")
            environment.storageGet(payloadJson)
        }
        "storage.set" -> {
            requireCapability(JsCapability.STORAGE, "host.storage")
            environment.storageSet(payloadJson)
        }
        "storage.remove" -> {
            requireCapability(JsCapability.STORAGE, "host.storage")
            environment.storageRemove(payloadJson)
        }
        "storage.keys" -> {
            requireCapability(JsCapability.STORAGE, "host.storage")
            environment.storageKeys()
        }
        "env.get" -> {
            requireCapability(JsCapability.ENV, "host.env")
            environment.envGet(payloadJson)
        }
        "env.has" -> {
            requireCapability(JsCapability.ENV, "host.env")
            environment.envHas(payloadJson)
        }
        "timer.setTimeout" -> {
            requireCapability(JsCapability.TIMER, "host.setTimeout")
            schedule(payloadJson, repeat = false)
        }
        "timer.setInterval" -> {
            requireCapability(JsCapability.TIMER, "host.setInterval")
            schedule(payloadJson, repeat = true)
        }
        "timer.clear" -> {
            // Checked like every other path: an ungranted instance can reach `timer.clear` only by
            // hand, and letting it probe/cancel ids it never scheduled would leak the timer table.
            // Internal cancels (fire/shutdown) call cancelTimer directly, not this path.
            requireCapability(JsCapability.TIMER, "host.clearTimeout")
            clearTimer(payloadJson)
        }
        else -> throw JsAbi.AbiException(
            JsAbi.Code.UNSUPPORTED,
            "unknown host path '$path' — the JS ABI exposes host.http/crypto/log/time/storage/env and host.setTimeout/setInterval/clearTimeout",
        )
    }

    /**
     * `host.log`, from JS. The LOG capability is enforced here because this is the JS-facing door.
     *
     * A missing grant drops the line rather than throwing: a plugin must not be able to lose its
     * download over a log call, and the ABI contract for this path is "never raises". The drop is
     * reported once per instance at host level, so a loader that forgot the grant can see why a
     * plugin is silent.
     */
    override fun logFromJs(level: String, text: String) {
        if (JsCapability.LOG !in config.permissions) {
            if (logRefusalReported.compareAndSet(false, true)) {
                emit("warn", "host.log is not granted to this plugin (grants: " +
                    config.permissions.joinToString(",") { it.jsName }.ifEmpty { "none" } +
                    "); the script's log lines are being dropped")
            }
            return
        }
        emit(level, text)
    }

    /**
     * The host's own diagnostics for this instance. Never gated, because the alternative is absurd: a
     * plugin without LOG would be able to silence the host's report of *its* failures.
     */
    override fun log(level: String, text: String) = emit(level, text)

    /** Shared rendering: validated level, capped text, and a sink that may not throw. */
    private fun emit(level: String, text: String) {
        val safe = when (level.lowercase()) {
            "debug", "info", "warn", "error" -> level.lowercase()
            else -> "info"
        }
        runCatching { sink(safe, text.take(MAX_LOG_CHARS)) }
    }

    override fun shutdown() {
        // Timers first: a task already dequeued must not hand work to JS after we stop caring.
        timers.keys.toList().forEach { cancelTimer(it) }
        timers.clear()
        runCatching { http.close() }
    }

    // ------------------------------------------------------------------ timers

    /**
     * host-owned timer. Ids are allocated here (not in JS) so `clearTimeout` is unambiguous, and the
     * handler travels as a JS callback id — the same registry rule as every other callback.
     *
     * A timer firing after STOPPING is silently dropped: the plugin is going away, and delivering an
     * error to a script that no longer has a caller would only pollute the log.
     */
    private fun schedule(payloadJson: String?, repeat: Boolean): Map<String, Any?> {
        val decoded = decode(payloadJson, "timer")
        val cbId = JsValueCodec.asLongOrNull(decoded["cb"])
            ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "timer requires a callback id")
        val delay = JsValueCodec.asLongOrNull(decoded["delayMillis"]) ?: 0L
        if (delay < 0) throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "timer delay must be >= 0")
        if (delay > MAX_TIMER_DELAY_MILLIS) {
            throw JsAbi.AbiException(JsAbi.Code.LIMIT, "timer delay must be <= ${MAX_TIMER_DELAY_MILLIS}ms")
        }
        val id = nextTimerId.getAndIncrement()
        val task: Runnable = Runnable { fire(id, cbId, repeat) }
        val future = try {
            if (repeat) {
                scheduler.scheduleWithFixedDelay(task, delay, delay.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            } else {
                scheduler.schedule(task, delay, TimeUnit.MILLISECONDS)
            }
        } catch (t: IllegalStateException) {
            // scheduler already shut down (host closing) — not a plugin error.
            throw JsAbi.AbiException(JsAbi.Code.GONE, "the host scheduler is no longer running", t)
        }
        timers[id] = future
        return mapOf("id" to id)
    }

    private fun fire(timerId: Long, cbId: Long, repeat: Boolean) {
        val link = linkProvider()
        if (link == null || !link.acceptsInvocations()) {
            cancelTimer(timerId)
            return
        }
        try {
            link.invokeCallback(cbId, mapOf("id" to timerId))
        } catch (t: JsAbi.AbiException) {
            when (t.code) {
                // Expected while draining/unloading, or the callback was already released.
                JsAbi.Code.GONE, JsAbi.Code.PLUGIN, JsAbi.Code.INTERRUPTED -> cancelTimer(timerId)
                JsAbi.Code.TIMEOUT -> log("warn", "timer $timerId callback exceeded its budget; the plugin stayed loaded")
                else -> log("warn", "timer $timerId failed: ${t.message}")
            }
            if (!repeat) cancelTimer(timerId)
            return
        } catch (t: Throwable) {
            log("warn", "timer $timerId failed: ${t.message}")
        }
        if (!repeat) cancelTimer(timerId)
    }

    private fun clearTimer(payloadJson: String?): Map<String, Any?> {
        val decoded = decode(payloadJson, "timer")
        val id = JsValueCodec.asLongOrNull(decoded["id"])
            ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "timer.clear requires an 'id'")
        return mapOf("cleared" to cancelTimer(id))
    }

    private fun cancelTimer(id: Long): Boolean {
        val future = timers.remove(id) ?: return false
        future.cancel(false)
        return true
    }

    /** Live timer count, for diagnostics and unload reporting. */
    fun pendingTimers(): Int = timers.size

    // ------------------------------------------------------------------ helpers

    private fun requireCapability(capability: JsCapability, jsApi: String) {
        if (capability !in config.permissions) {
            throw JsPermissionException(
                "$jsApi needs the '${capability.jsName}' capability; the JS loader grants " +
                    config.permissions.joinToString(",") { it.jsName },
            )
        }
    }

    private fun decode(payloadJson: String?, label: String): Map<*, *> {
        if (payloadJson.isNullOrEmpty()) {
            throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "$label call requires an options object")
        }
        val value = try {
            JsValueCodec.decodeFromJs(payloadJson, "$label payload")
        } catch (t: JsValueCodec.JsCodecException) {
            throw JsAbi.AbiException(JsAbi.Code.VALIDATION, t.message ?: "invalid payload", t)
        }
        return value as? Map<*, *>
            ?: throw JsAbi.AbiException(JsAbi.Code.VALIDATION, "$label payload must be an object")
    }

    /** Sandbox root, exposed so the script plugin can delete it on dispose. */
    val sandboxRoot: java.io.File get() = sandbox.root

    private companion object {
        const val MAX_LOG_CHARS = 8 * 1024
        const val MAX_TIMER_DELAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

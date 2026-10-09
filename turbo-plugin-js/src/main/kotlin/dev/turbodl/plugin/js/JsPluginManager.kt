package dev.turbodl.plugin.js

import dev.turbodl.core.TurboConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger

/**
 * The registry of live JS plugin instances, and the owner of the two shared thread pools.
 *
 * ## Why a manager at all
 * The per-instance machinery ([JsRuntime], [JsHost], [JsExtensionBridge], [JsSandbox]) is created by
 * a plugin's `onLoad` and destroyed by its own teardown. What must NOT be per-instance are the pools
 * that watch over them: a thread that only fires on a timeout is a thread spent doing nothing until
 * it does. So the manager owns them and shuts them down exactly once — when the JS loader plugin is
 * unloaded.
 *
 * ## Why TWO pools, and not one
 * They have opposite blocking profiles, and sharing one lets the wrong one starve the right one:
 *
 *  - [timerScheduler] runs `host.setTimeout`/`setInterval` callbacks, and a callback **is** a JS
 *    invocation: it can legitimately occupy its thread for the whole invocation budget (`host.time.sleep`,
 *    a slow `host.http`). Two such callbacks are enough to fill a small pool.
 *  - [watchdogScheduler] has exactly one job: call `QuickJs.interruptEvaluation()` for an invocation
 *    that overran. That is the one task that must *never* queue behind the work it exists to kill —
 *    if it does, the caller gets its `timeout` anyway but the runaway JS keeps owning the plugin's
 *    engine thread, which is precisely the resource a drain is waiting for.
 *
 * So the watchdog gets its own single-threaded pool: it cannot be starved by plugin work, and it does
 * not need more than one thread because interrupting is not blocking.
 *
 * Note what the manager does **not** own: the policy. Each instance carries its own
 * [JsPluginConfig], narrowed per source by the loader, so a sandbox for a stricter script cannot be
 * created with the loader's looser default.
 *
 * ## Ordering this makes safe
 * [shutdown] tears down every live instance *first*, then the timer pool, then the watchdog — so a
 * timer can never be delivered to a runtime that has already been disposed.
 */
internal class JsPluginManager(
    private val loaderId: String,
    /** Engine config the host HTTP transport inherits (proxy / DNS / TLS / UA come from here). */
    val engineConfig: TurboConfig,
    /** Remove each plugin's sandbox directory (including `host.storage`) when it unloads. */
    val purgeSandboxOnUnload: Boolean = false,
) {

    /**
     * Timer pool: `host.setTimeout`/`setInterval` callbacks. Two threads so one plugin's slow timer
     * does not hold every other plugin's timers, and named so a thread dump says what it is.
     * A blocked timer thread delays timers only — never the watchdog, which is what has to be able to
     * interrupt a runaway evaluation.
     */
    private val timerScheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(TIMER_THREADS) { runnable ->
        Thread(runnable, "turbo-js-$loaderId-timers").apply { isDaemon = true }
    }

    /**
     * Watchdog pool: one thread whose only task is `QuickJs.interruptEvaluation()`. It is never given
     * plugin work, so an interrupt can never queue behind the JavaScript it exists to stop.
     */
    private val watchdogScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "turbo-js-$loaderId-watchdog").apply { isDaemon = true }
        }

    private val instances = ConcurrentHashMap<String, JsScriptPlugin>()

    /** Instances whose drain timed out, so disposal was refused (a deliberate, reported leak). */
    private val leakedRuntimes = AtomicInteger(0)

    fun newSandbox(pluginId: String, config: JsPluginConfig): JsSandbox =
        JsSandbox(pluginId, config, engineConfig.workDir)

    /** Where a plugin's host timers are scheduled. */
    fun timerScheduler(): ScheduledExecutorService = timerScheduler

    /** Where overruns are interrupted from. Never carries plugin work. */
    fun watchdogScheduler(): ScheduledExecutorService = watchdogScheduler

    fun track(plugin: JsScriptPlugin) {
        instances[plugin.id] = plugin
    }

    fun untrack(pluginId: String) {
        instances.remove(pluginId)
    }

    fun liveIds(): List<String> = instances.keys.toList().sorted()

    /** The live instances themselves — the loader's public listing is built from this. */
    fun livePlugins(): List<JsScriptPlugin> = instances.values.sortedBy { it.id }

    fun liveCount(): Int = instances.size

    /** How many QuickJS runtimes were left un-closed because JS was still executing at unload. */
    fun leakedCount(): Int = leakedRuntimes.get()

    internal fun recordLeak() {
        leakedRuntimes.incrementAndGet()
    }

    /** Diagnostics snapshot of every live instance, as plain data. */
    fun snapshot(): List<Map<String, Any?>> = instances.values.map { it.diagnostics() }

    /**
     * Tear down everything still live, then both pools. Called from `loader.js`'s disposer.
     *
     * In the normal case the instances are already gone: [dev.turbodl.plugin.runtime.PluginHost.uninstall]
     * cascades to plugins recorded by `loadSource` *before* the loader's disposer runs, so each
     * instance has already completed its own drain. This is therefore the safety net for instances
     * that reached the manager without that ownership edge — a script plugin installed directly via
     * `host.install(...)` — plus the single place the shared pools are stopped.
     *
     * Order matters: instances, then timers (a cancelled timer can no longer deliver into a runtime),
     * then the watchdog — a live instance being disposed may still have an armed interrupt.
     *
     * Safe to call during teardown: [JsScriptPlugin.dispose] never re-enters the host, so no disposer
     * can see a nested `uninstall`.
     */
    fun shutdown(report: (String, Throwable?) -> Unit) {
        instances.values.toList().forEach { plugin ->
            runCatching { plugin.dispose(report) }
                .onFailure { report("JS instance '${plugin.id}' failed to dispose", it) }
        }
        instances.clear()
        timerScheduler.shutdownNow()
        watchdogScheduler.shutdownNow()
    }

    override fun toString(): String = "JsPluginManager($loaderId, live=${instances.size}, leaked=${leakedRuntimes.get()})"

    internal companion object {
        /** Timer threads: enough that one plugin's slow timer does not hold up every other plugin. */
        const val TIMER_THREADS = 2
    }
}

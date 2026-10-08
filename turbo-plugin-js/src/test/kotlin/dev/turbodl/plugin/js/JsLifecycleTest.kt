package dev.turbodl.plugin.js

import dev.turbodl.core.TurboConfig
import dev.turbodl.plugin.runtime.PluginHost
import dev.turbodl.plugin.runtime.PluginSource
import dev.turbodl.plugin.runtime.PluginState
import dev.turbodl.plugin.runtime.ext.ExtensionPoints
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The unload contract: `ACTIVE → STOPPING → DRAINING → DISPOSED`, and the two properties the spec
 * calls out as non-negotiable — a throwing script never damages the host, and an unload racing a
 * live invocation never frees memory JavaScript is still reading.
 */
class JsLifecycleTest {

    private fun host(logs: MutableList<String>): PluginHost =
        PluginHost(logger = { msg, e -> logs.add(msg + (e?.let { " — ${it.message}" } ?: "")) })

    private fun loader(js: JsPluginConfig = JsPluginConfig(), engine: TurboConfig = TurboConfig()) =
        JsPluginLoaderPlugin(engine, js)

    /** `spin(ms)` burns JS time without touching the host, so it is controllable from a script. */
    private val spinScript = """
        function spin(ms) { var t = Date.now(); while (Date.now() - t < ms) {} return 'spun'; }
        plugin.registerParser({
          parse: function (input) { spin(120); return [{ url: 'https://example.com/' + input.input, fileName: 'x.bin' }]; }
        });
    """.trimIndent()

    // ------------------------------------------------------------------ state machine

    @Test
    fun `the guard refuses new work the moment stopping begins`() {
        val guard = JsInvocationGuard()
        assertEquals(JsLifecycleState.ACTIVE, guard.state)
        assertTrue(guard.begin())
        assertEquals(1, guard.inFlightCount)
        guard.end()
        assertEquals(0, guard.inFlightCount)

        guard.markStopping()
        assertEquals(JsLifecycleState.STOPPING, guard.state)
        assertFalse(guard.begin(), "STOPPING must refuse ordinary invocations")
        assertFalse(guard.state.acceptsInvocations)
        // onDestroy is the one admitted caller, and only until DISPOSED.
        assertTrue(guard.begin(allowStopping = true), "onDestroy must still run while stopping")
        guard.end()
        assertTrue(guard.drain(0), "nothing in flight, so drain is immediate")
        guard.markDisposed()
        assertFalse(guard.begin(allowStopping = true), "DISPOSED admits nothing, not even onDestroy")
    }

    @Test
    fun `drain reports unfinished work instead of pretending it is safe`() {
        val guard = JsInvocationGuard()
        assertTrue(guard.begin())
        assertFalse(guard.drain(30), "an in-flight invocation must block the drain")
        guard.end()
        assertTrue(guard.drain(0), "and clears as soon as the invocation ends")
    }

    /**
     * The other half of the same property, and the one a caller cannot see.
     *
     * A timed-out invocation is abandoned by its caller while the worker is still inside `evaluate`,
     * so `inFlight` alone reads zero with JavaScript still executing. Since the very next action after
     * a successful drain is `QuickJs.close()`, a guard that counted only callers would turn a timeout
     * into a use-after-free.
     */
    @Test
    fun `the guard keeps an abandoned engine worker as busy work`() {
        val guard = JsInvocationGuard()
        assertTrue(guard.begin())
        guard.end()
        assertEquals(0, guard.inFlightCount, "the caller is done waiting")

        guard.beginEngineTask()
        assertEquals(1, guard.engineBusyCount, "but the JS thread has not returned")
        assertEquals(1, guard.busyCount)
        assertFalse(guard.drain(30), "a worker its caller abandoned must still block the drain")
        assertFalse(guard.drain(0), "and a zero-budget drain must not slip past it either")

        guard.endEngineTask()
        assertEquals(0, guard.busyCount)
        assertTrue(guard.drain(0), "the drain clears once the engine really is idle")
    }

    /**
     * The same window, reproduced end-to-end on a real engine: a host call that outlives the caller's
     * budget leaves the worker running, and close() must refuse while it does.
     */
    @Test
    fun `a timed-out invocation cannot be drained away and cannot be closed over`() {
        JsEngineProbe.requireEngine()
        // A host call that blocks far longer than the caller is willing to wait. QuickJS counts JS
        // time only, so neither the engine timeout nor the watchdog can cut into a binding call —
        // which is exactly why the worker, not the caller, has to be what unload waits for.
        val host = JsEngineProbe.fakeHost().apply { sleepMillis = 2_500 }
        val runtime = JsRuntime(
            pluginId = "parser.abandoned",
            jsConfig = JsPluginConfig(evaluationTimeoutMillis = 200, invocationTimeoutMillis = 300),
            hostProvider = { host },
            registrar = JsRuntime.JsRegistrar { _, _, _ -> null },
        )
        runtime.watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "turbo-js-test-watchdog").apply { isDaemon = true }
        }
        runtime.start("1;", "abandoned.js", JsScriptIdentity("parser.abandoned", "inline:test"))
        try {
            val outcome = runCatching { runtime.evaluateAny("host.time.sleep(2500)", "sleep.js") }
            val failure = outcome.exceptionOrNull()
            assertTrue(failure is JsAbi.AbiException && failure.code == JsAbi.Code.TIMEOUT, "actual: $outcome")

            // The caller gave up; the engine did not.
            assertEquals(0, runtime.inFlight, "no caller is waiting any more")
            assertEquals(1, runtime.engineBusy, "the worker still owns the JS thread")
            assertFalse(runtime.drainInFlight(150), "so a drain must report the instance as still busy")
            val refusal = assertFailsWith<IllegalStateException> { runtime.close() }
            assertTrue("in flight" in refusal.message!!, "the refusal must say why: ${refusal.message}")
            assertTrue("engine worker task" in refusal.message!!, "and name the abandoned worker: ${refusal.message}")
            assertTrue(runtime.isAlive, "a refused close must leave the engine intact")

            // Once the host call really returns, the same unload completes normally.
            val deadline = System.currentTimeMillis() + 8_000
            while (runtime.engineBusy > 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(0, runtime.engineBusy, "the worker must finish on its own")
            runtime.markStopping()
            assertTrue(runtime.drainInFlight(1_000), "and the drain then succeeds")
        } finally {
            runtime.close()
        }
        assertFalse(runtime.isAlive)
    }

    @Test
    fun `close is refused while js is executing`() {
        JsEngineProbe.requireEngine()
        val runtime = JsRuntime(
            pluginId = "parser.spin",
            jsConfig = JsPluginConfig(),
            hostProvider = { JsEngineProbe.fakeHost() },
            registrar = JsRuntime.JsRegistrar { _, _, _ -> null },
        )
        runtime.watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "turbo-js-test-watchdog").apply { isDaemon = true }
        }
        runtime.start("1;", "spin.js", JsScriptIdentity("parser.spin", "inline:test"))

        // Hold an invocation open on the engine thread — busy JS, no host call, so nothing can
        // shortcut it. This is the exact window in which close() must refuse.
        val finished = java.util.concurrent.CountDownLatch(1)
        val holder = Thread {
            runCatching { runtime.evaluateAny("(function(){var t=Date.now();while(Date.now()-t<1500){}return 1;})()") }
            finished.countDown()
        }.apply { isDaemon = true; start() }
        val deadline = System.currentTimeMillis() + 2_000
        while (runtime.inFlight < 1 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        try {
            assertTrue(runtime.inFlight >= 1, "the holder invocation should be in flight by now")
            val failure = assertFailsWith<IllegalStateException> { runtime.close() }
            assertTrue(failure.message!!.contains("in flight"), "actual: ${failure.message}")
            assertTrue(runtime.isAlive, "a refused close must leave the engine intact")
        } finally {
            finished.await(5, TimeUnit.SECONDS)
            runtime.close()
            holder.join(5_000)
        }
        assertFalse(runtime.isAlive)
    }

    /**
     * The two shared pools must be two pools.
     *
     * A timer callback *is* a JS invocation, so it can legitimately hold its thread for the whole
     * budget; an interrupt is a five-nanosecond call whose job is to stop runaway JS. When both lived
     * on one small scheduler, enough blocked timers could push an interrupt to the back of the queue —
     * behind the very JavaScript it exists to kill.
     */
    @Test
    fun `blocked timer callbacks cannot starve the watchdog pool`() {
        val manager = JsPluginManager("loader.js", TurboConfig())
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            val timers = manager.timerScheduler()
            val watchdog = manager.watchdogScheduler()
            assertFalse(timers === watchdog, "timers and interrupts must not share a pool")

            // Saturate the timer pool well past its capacity with callbacks that never return.
            val blocked = java.util.concurrent.atomic.AtomicInteger(0)
            repeat(8) {
                timers.schedule(Runnable { blocked.incrementAndGet(); release.await(10, TimeUnit.SECONDS) }, 0, TimeUnit.MILLISECONDS)
            }
            val deadline = System.currentTimeMillis() + 2_000
            while (blocked.get() < JsPluginManager.TIMER_THREADS && System.currentTimeMillis() < deadline) Thread.sleep(5)
            assertEquals(
                JsPluginManager.TIMER_THREADS,
                blocked.get(),
                "every timer thread must be occupied before the interrupt is asked for",
            )

            val ran = java.util.concurrent.CountDownLatch(1)
            val started = System.currentTimeMillis()
            watchdog.schedule(Runnable { ran.countDown() }, 5, TimeUnit.MILLISECONDS)
            val delivered = ran.await(1, TimeUnit.SECONDS)
            val elapsed = System.currentTimeMillis() - started
            assertTrue(delivered, "an interrupt must be delivered while $blocked timer callbacks are blocked")
            assertTrue(elapsed < 500, "and it must not queue behind plugin work, took ${elapsed}ms")
        } finally {
            release.countDown()
            manager.shutdown { _, _ -> }
        }
    }

    // ------------------------------------------------------------------ timeout

    @Test
    fun `a runaway loop costs one call, not the plugin`() {
        JsEngineProbe.requireEngine()
        val tight = JsPluginConfig(evaluationTimeoutMillis = 400, invocationTimeoutMillis = 1_500)
        val logs = mutableListOf<String>()
        val h = host(logs)
        h.install(loader(js = tight))
        h.loadSource(PluginSource("js", "inline:plugin.registerParser({parse: function(){ var t = Date.now(); while (Date.now() - t < 30000) {} return null; }});", mapOf("pluginId" to "parser.runaway")))

        val parsers = h.extensions.all(ExtensionPoints.LINK_PARSER)
        assertEquals(1, parsers.size)
        val started = System.currentTimeMillis()
        // The busy loop is killed at the budget and the contract degrades to "no match" — never a
        // thrown exception into TurboDL's submit path, and never a hung caller.
        assertEquals(null, parsers.first().parse("x"))
        val elapsed = System.currentTimeMillis() - started
        assertTrue(elapsed < 8_000, "the watchdog must cut the loop, took ${elapsed}ms")
        assertTrue(logs.any { "timeout" in it.lowercase() }, "the overrun must be logged, got: $logs")
        // Still alive afterwards: the same instance keeps serving later calls.
        assertTrue(h.diagnostics().plugins.any { it.id == "parser.runaway" && it.state == PluginState.LOADED })
        h.shutdown()
    }

    // ------------------------------------------------------------------ queued-invocation watchdog ownership

    /**
     * The watchdog race, pinned: invocation A holds the engine (inside a host call, so nothing may
     * legitimately cut into it), invocation B sits in the queue and its budget expires while it
     * waits. B's expiry must fail B alone — arming a watchdog at submit time, or interrupting on
     * the caller's timeout, would reach into the engine and kill A instead.
     */
    @Test
    fun `a queued invocation's expiry must not interrupt the invocation holding the engine`() {
        JsEngineProbe.requireEngine()
        val host = JsEngineProbe.fakeHost().apply { sleepMillis = 2_000 }
        val runtime = JsRuntime(
            pluginId = "parser.queued-race",
            jsConfig = JsPluginConfig(evaluationTimeoutMillis = 10_000, invocationTimeoutMillis = 20_000),
            hostProvider = { host },
            registrar = JsRuntime.JsRegistrar { _, _, _ -> null },
        )
        runtime.watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "turbo-js-test-watchdog").apply { isDaemon = true }
        }
        runtime.start("1;", "queued-race.js", JsScriptIdentity("parser.queued-race", "inline:test"))
        try {
            // A takes the engine and stays inside its host call far longer than B's whole budget.
            val a = java.util.concurrent.CompletableFuture.supplyAsync {
                runCatching { runtime.evaluateAny("host.time.sleep(2000); 'a-done';") }
            }
            // Submit B only once A's task actually owns the engine thread — before that, B might
            // legitimately start first and this test would prove nothing.
            val deadline = System.currentTimeMillis() + 2_000
            while (runtime.engineBusy < 1 && System.currentTimeMillis() < deadline) Thread.sleep(5)
            assertEquals(1, runtime.engineBusy, "A must hold the engine before B is submitted")

            // B queues behind A, and its 400ms budget expires while it waits.
            val b = runCatching { runtime.evaluateAny("1 + 1", "b.js", 400) }
            val bFailure = b.exceptionOrNull()
            assertTrue(bFailure is JsAbi.AbiException && bFailure.code == JsAbi.Code.TIMEOUT, "B must fail as a timeout, got: $bFailure")

            // The defect this pins showed up exactly here: A used to die with an interrupt it never earned.
            assertEquals("a-done", a.get(10, TimeUnit.SECONDS).getOrNull(), "A must complete untouched")
            assertEquals(2L, runtime.evaluateAny("1 + 1"), "and the instance must be fully usable afterwards")
        } finally {
            runtime.close()
        }
    }

    /**
     * The inverse half of the queueing contract: once a queued task really starts within its
     * budget, it owns the interrupt right — a runaway loop it runs must be cut at its own deadline
     * even though it started late. Guards against "fixing" the race by never arming a queued task.
     */
    @Test
    fun `a queued invocation that starts within budget still gets its own watchdog`() {
        JsEngineProbe.requireEngine()
        val host = JsEngineProbe.fakeHost().apply { sleepMillis = 800 }
        val runtime = JsRuntime(
            pluginId = "parser.queued-watchdog",
            jsConfig = JsPluginConfig(evaluationTimeoutMillis = 10_000, invocationTimeoutMillis = 20_000),
            hostProvider = { host },
            registrar = JsRuntime.JsRegistrar { _, _, _ -> null },
        )
        runtime.watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "turbo-js-test-watchdog").apply { isDaemon = true }
        }
        runtime.start("1;", "queued-watchdog.js", JsScriptIdentity("parser.queued-watchdog", "inline:test"))
        try {
            val a = java.util.concurrent.CompletableFuture.supplyAsync {
                runCatching { runtime.evaluateAny("host.time.sleep(800); 'a-done';") }
            }
            val deadline = System.currentTimeMillis() + 2_000
            while (runtime.engineBusy < 1 && System.currentTimeMillis() < deadline) Thread.sleep(5)
            assertEquals(1, runtime.engineBusy, "A must hold the engine before B is submitted")

            // B queues ~800ms behind A with a 2500ms budget, then runs a runaway loop. Its watchdog
            // — armed by the task itself at start, for the remaining ~1700ms — must cut the loop.
            val started = System.currentTimeMillis()
            val b = runCatching {
                runtime.evaluateAny("(function(){var t=Date.now();while(Date.now()-t<30000){}return 'b-done';})()", "b-loop.js", 2_500)
            }
            val elapsed = System.currentTimeMillis() - started
            val bFailure = b.exceptionOrNull()
            assertTrue(bFailure is JsAbi.AbiException && bFailure.code == JsAbi.Code.TIMEOUT, "the loop must die at B's deadline, got: $bFailure")
            assertTrue(elapsed < 6_000, "the task-owned watchdog must cut the loop, took ${elapsed}ms")
            // The caller giving up must not be what stopped the work: the engine itself must be idle
            // again shortly after B's deadline, which only the task's own watchdog can achieve here.
            val idleDeadline = System.currentTimeMillis() + 4_000
            while (runtime.engineBusy > 0 && System.currentTimeMillis() < idleDeadline) Thread.sleep(10)
            assertEquals(0, runtime.engineBusy, "the runaway task must be stopped by its own watchdog, not merely abandoned")
            assertEquals("a-done", a.get(10, TimeUnit.SECONDS).getOrNull(), "A completes first, untouched")
            assertEquals(2L, runtime.evaluateAny("1 + 1"), "the instance survives both invocations")
        } finally {
            runtime.close()
        }
    }

    // ------------------------------------------------------------------ unload independence

    @Test
    fun `unloading one js plugin leaves the other and its own resources working`() {
        JsEngineProbe.requireEngine()
        val logs = mutableListOf<String>()
        val h = host(logs)
        h.install(loader())
        val keep = "plugin.registerParser({parse: function (i) { return [{ url: 'https://keep.example/' + i.input, fileName: 'k.bin' }]; }});"
        h.loadSource(PluginSource("js", "inline:$keep", mapOf("pluginId" to "parser.keep")))
        h.loadSource(PluginSource("js", "inline:$keep", mapOf("pluginId" to "parser.drop")))

        h.uninstall("parser.drop")
        assertEquals(1, h.extensions.all(ExtensionPoints.LINK_PARSER).size, "only the unloaded plugin's parser may leave")
        val survivor = h.extensions.all(ExtensionPoints.LINK_PARSER).first()
        assertEquals("https://keep.example/movie", survivor.parse("movie")!![0].url)

        // The unloaded instance disposed cleanly: no leaked runtime, and its own id is gone.
        assertTrue(logs.any { "parser.drop' unloaded" in it }, "expected an unload log, got: $logs")
        assertFalse(h.diagnostics().plugins.any { it.id == "parser.drop" })
        h.shutdown()
    }

    @Test
    fun `a concurrent unload never surfaces an exception to the caller`() {
        JsEngineProbe.requireEngine()
        val logs = mutableListOf<String>()
        val h = host(logs)
        h.install(loader())
        h.loadSource(PluginSource("js", "inline:$spinScript", mapOf("pluginId" to "parser.race")))
        val parser = h.extensions.all(ExtensionPoints.LINK_PARSER).first()

        val stop = java.util.concurrent.atomic.AtomicBoolean(true)
        val outcomes = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val callers = (1..6).map { index ->
            Thread {
                while (stop.get()) {
                    val outcome = runCatching { parser.parse("clip$index") }
                    outcomes.merge(
                        if (outcome.isFailure) "THREW:${outcome.exceptionOrNull()!!::class.simpleName}"
                        else if (outcome.getOrNull() == null) "null" else "requests",
                        1,
                    ) { a, b -> a + b }
                }
            }.apply { isDaemon = true; start() }
        }
        Thread.sleep(80)
        h.uninstall("parser.race")
        Thread.sleep(80)
        stop.set(false)
        callers.forEach { it.join(5_000) }

        val threw = outcomes.keys.filter { it.startsWith("THREW") }
        assertTrue(threw.isEmpty(), "a racing consumer must get a clean no-op, never an exception: $outcomes")
        assertTrue(outcomes.getOrDefault("null", 0) > 0, "some calls must have hit the stopping instance, got $outcomes")
        assertEquals(0, h.extensions.all(ExtensionPoints.LINK_PARSER).size)
        assertFalse(logs.any { "RUNTIME LEAKED" in it }, "the drain must have finished; logs: $logs")
        h.shutdown()
    }

    // ------------------------------------------------------------------ onDestroy / listeners

    @Test
    fun `onDestroy runs during unload and a throwing onDestroy still disposes`() {
        JsEngineProbe.requireEngine()
        val logs = mutableListOf<String>()
        val h = host(logs)
        h.install(loader())
        h.loadSource(
            PluginSource(
                "js",
                "inline:plugin.onDestroy(function(){ host.log.info('goodbye from js'); }); plugin.registerParser({parse:function(){return null;}});",
                mapOf("pluginId" to "parser.bye"),
            ),
        )
        h.uninstall("parser.bye")
        assertTrue(logs.any { "goodbye from js" in it }, "the script's onDestroy must run, got: $logs")

        h.loadSource(
            PluginSource(
                "js",
                "inline:plugin.onDestroy(function(){ throw new Error('destroy exploded'); });",
                mapOf("pluginId" to "parser.badDestroy"),
            ),
        )
        h.uninstall("parser.badDestroy")
        assertTrue(logs.any { "onDestroy of 'parser.badDestroy' failed" in it }, "reported, got: $logs")
        assertTrue(logs.any { "'parser.badDestroy' unloaded" in it }, "and disposal still completed, got: $logs")
        h.shutdown()
    }

    @Test
    fun `a throwing event listener cannot break the event pump`() {
        JsEngineProbe.requireEngine()
        val logs = mutableListOf<String>()
        val h = host(logs)
        h.install(loader())
        h.loadSource(
            PluginSource(
                "js",
                "inline:plugin.onEvent(function (e) { if (e.type === 'Progress') throw new Error('listener bug'); });",
                mapOf("pluginId" to "hook.events"),
            ),
        )
        assertEquals(1, h.diagnostics().eventListeners)
        // Must not throw out of publish, per CONVENTION.md §8.
        runCatching {
            h.publishEvent(
                dev.turbodl.core.TurboEvent.Progress(
                    1L,
                    dev.turbodl.core.TaskProgress(
                        taskId = 1L,
                        state = dev.turbodl.core.TaskState.DOWNLOADING,
                        downloadedBytes = 5L,
                        totalBytes = 10L,
                        speedBytesPerSec = 1024L,
                        activeConnections = 2,
                        etaMillis = 100L,
                    ),
                ),
            )
        }.onFailure { error("publishEvent must isolate a plugin listener fault, got: $it") }
        assertTrue(logs.any { "event listener" in it && "ignored" in it }, "the fault is logged and swallowed, got: $logs")
        h.shutdown()
        assertEquals(0, h.diagnostics().eventListeners, "unloading must drop the JS subscription")
    }

    @Test
    fun `a pre-hook failure leaves the request unchanged instead of failing the submit`() {
        JsEngineProbe.requireEngine()
        val logs = mutableListOf<String>()
        val h = host(logs)
        h.install(loader())
        h.loadSource(
            PluginSource(
                "js",
                "inline:plugin.registerTaskPreHook({beforeSubmit: function () { throw new Error('nope'); }});",
                mapOf("pluginId" to "hook.broken"),
            ),
        )
        val hooks = h.extensions.all(ExtensionPoints.TASK_PRE_HOOK)
        assertEquals(1, hooks.size)
        val original = dev.turbodl.core.DownloadRequest("https://example.com/a.bin", java.io.File("a.bin"))
        val out = hooks.first().beforeSubmit(original)
        assertEquals(original, out, "a broken hook must be a no-op, not a submit failure")
        assertNotNull(logs.firstOrNull { "pre-hook" in it && "left unchanged" in it })

        // A working hook rewrites the request through the documented descriptor shape.
        h.loadSource(
            PluginSource(
                "js",
                "inline:plugin.registerTaskPreHook({beforeSubmit: function (p) { var r = p.request; r.headers['X-JS'] = '1'; return r; }});",
                mapOf("pluginId" to "hook.editor"),
            ),
        )
        val editor = h.extensions.all(ExtensionPoints.TASK_PRE_HOOK).last()
        val edited = editor.beforeSubmit(original)
        assertEquals("1", edited.headers["X-JS"], "a returned descriptor must be honoured")
        assertEquals(original.destination, edited.destination, "an echoed destination must stay exactly where the host put it")
        h.shutdown()
    }

    @Test
    fun `a pre-hook may not launder an outside destination into the request`() {
        JsEngineProbe.requireEngine()
        val logs = mutableListOf<String>()
        val h = host(logs)
        h.install(loader())
        h.loadSource(
            PluginSource(
                "js",
                "inline:plugin.registerTaskPreHook({beforeSubmit: function (p) { var r = p.request; r.destination = '/tmp/escaped.bin'; return r; }});",
                mapOf("pluginId" to "hook.escape"),
            ),
        )
        val original = dev.turbodl.core.DownloadRequest("https://example.com/a.bin", java.io.File("a.bin"))
        val out = h.extensions.all(ExtensionPoints.TASK_PRE_HOOK).first().beforeSubmit(original)
        assertEquals(original, out, "a changed destination outside the sandbox must reject the whole edit")
        assertTrue(logs.any { "invalid request" in it && "left unchanged" in it }, "and say why, got: $logs")
        h.shutdown()
    }
}

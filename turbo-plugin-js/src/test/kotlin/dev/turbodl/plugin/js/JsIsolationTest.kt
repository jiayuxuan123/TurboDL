package dev.turbodl.plugin.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Per-plugin isolation and the unload state machine, tested on [JsRuntime] directly rather than
 * through the loader — because these two properties are exactly the ones the spec forbids sharing,
 * and they must hold even for a runtime the host never registered anywhere.
 */
class JsIsolationTest {

    private fun config(extra: JsPluginConfig = JsPluginConfig()) = extra

    /** Start a runtime with [script] and a no-op registrar; caller closes it. */
    private fun started(
        pluginId: String,
        script: String,
        jsConfig: JsPluginConfig = config(),
        registrar: JsRuntime.JsRegistrar = JsRuntime.JsRegistrar { _, _, _ -> mapOf("handle" to 1L) },
        host: JsHostApi = JsEngineProbe.fakeHost(),
    ): JsRuntime {
        val runtime = JsRuntime(
            pluginId = pluginId,
            jsConfig = jsConfig,
            hostProvider = { host },
            registrar = registrar,
        )
        // The manager normally supplies this; a standalone runtime needs its own so the outer
        // budget can interrupt a call the engine's own (JS-time-only) timeout cannot see.
        runtime.watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "turbo-js-test-watchdog").apply { isDaemon = true }
        }
        runtime.start(script, "test-$pluginId.js", JsScriptIdentity(pluginId, "inline:test"))
        return runtime
    }

    @Test
    fun `globals do not cross between plugin instances`() {
        JsEngineProbe.requireEngine()
        val a = started("parser.a", "globalThis.secret = 'from-a';")
        val b = started("parser.b", "globalThis.other = 42;")
        try {
            assertEquals("from-a", a.evaluateAny("globalThis.secret"))
            // b never saw a's global — this is the whole point of one runtime+context per plugin.
            assertEquals("undefined", b.evaluateAny("typeof globalThis.secret"))
            assertEquals("undefined", a.evaluateAny("typeof globalThis.other"))

            // Mutating one instance's copy cannot change the other's.
            a.evaluateAny("globalThis.secret = 'mutated'")
            assertEquals("mutated", a.evaluateAny("globalThis.secret"))
            assertEquals("undefined", b.evaluateAny("typeof globalThis.secret"))
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun `the shared shim text is evaluated per context, so registry state stays private`() {
        JsEngineProbe.requireEngine()
        // Two scripts register parsers; callback ids restart at 1 in each instance because the
        // ABI's registry lives in the context, not in the (shared) shim string.
        val script = "plugin.registerParser({parse: function () { return null; }});"
        val a = started("parser.ids-a", script)
        val b = started("parser.ids-b", script)
        try {
            val sealA = a.seal()
            val sealB = b.seal()
            assertEquals(1, sealA.registrations.size)
            assertEquals(1, sealB.registrations.size)
            assertTrue(sealA.abiCompatible && sealB.abiCompatible, "both must report the loader's ABI major")
            // Neither instance's callback table knows the other's id.
            assertEquals(false, a.evaluateAny("__turboHasCallback(99)"))
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun `heap accounting is per instance`() {
        JsEngineProbe.requireEngine()
        val idle = started("parser.heap-idle", "1;")
        val heavy = started(
            "parser.heap-heavy",
            "globalThis.hold = []; for (var i = 0; i < 20000; i++) globalThis.hold.push({i: i, s: 'x'.repeat(16)});",
        )
        try {
            val idleBytes = idle.memoryUsed()
            val heavyBytes = heavy.memoryUsed()
            assertTrue(idleBytes >= 0, "heap usage must be readable, got $idleBytes")
            assertTrue(heavyBytes > idleBytes, "a plugin holding data must account more heap: $heavyBytes vs $idleBytes")
        } finally {
            idle.close(); heavy.close()
        }
    }

    @Test
    fun `the memory ceiling is reported as a heap breach, not a blank error`() {
        JsEngineProbe.requireEngine()
        // Tightest ceiling the config allows, so a few MB of allocation is already over budget.
        val runtime = started("parser.tiny-heap", "1;", JsPluginConfig(memoryLimitBytes = 2L * 1024 * 1024))
        try {
            val failure = runCatching {
                runtime.evaluateAny("globalThis.x = []; for (var i = 0; i < 400000; i++) globalThis.x.push({pad: 'y'.repeat(80)});")
            }.exceptionOrNull()
            assertNotNull(failure, "an allocation past memoryLimitBytes must throw, not be tolerated")
            // Verified engine behaviour: quickjs-kt raises a QuickJsException whose message, stack and
            // file name are ALL null here, so the host must identify the fault from its own accounting.
            val abi = failure as JsAbi.AbiException
            assertEquals(JsAbi.Code.LIMIT, abi.code, "actual: ${abi.code} / ${abi.message}")
            assertTrue(abi.message!!.contains("memoryLimitBytes"), "the message must name the knob to raise: ${abi.message}")
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `a script that catches its own heap breach keeps working`() {
        JsEngineProbe.requireEngine()
        val runtime = started("parser.oom-caught", "1;", JsPluginConfig(memoryLimitBytes = 2L * 1024 * 1024))
        try {
            // The breach still fails the statement, but because JS handled it the context stays sane —
            // which is why plugin authors are told to wrap bulk allocation (README: known limitation).
            val outcome = runCatching {
                runtime.evaluateAny("(function(){try{var z=[];for(var i=0;i<400000;i++)z.push({p:'y'.repeat(80)});return 'no oom';}catch(e){return 'caught';}})()")
            }
            assertTrue(outcome.isFailure || outcome.getOrNull() == "caught", "actual: $outcome")
            assertEquals(2L, runtime.evaluateAny("1 + 1"), "the instance must still evaluate afterwards")
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `an escaped heap breach never blocks unload and never leaks the runtime`() {
        JsEngineProbe.requireEngine()
        val runtime = started("parser.oom-escaped", "1;", JsPluginConfig(memoryLimitBytes = 2L * 1024 * 1024))
        val breach = runCatching {
            runtime.evaluateAny("globalThis.x = []; for (var i = 0; i < 400000; i++) globalThis.x.push({pad: 'y'.repeat(80)});")
        }
        assertTrue(breach.isFailure, "an uncaught allocation past the ceiling must fail the call")
        // Measured across repeated runs, a context whose OOM escaped does one of two things on the
        // next evaluation: it fails with a message-less InternalError, or QuickJS has already
        // reclaimed enough that it works again. Both are acceptable; silently returning *wrong* data
        // is not, and neither may block disposal — which is the host's real guarantee.
        val after = runCatching { runtime.evaluateAny("1 + 1") }
        if (after.isSuccess) {
            assertEquals(2L, after.getOrNull(), "a recovered context must still be correct, not garbage")
        } else {
            val wedged = after.exceptionOrNull()
            assertTrue(
                wedged is JsAbi.AbiException,
                "a wedged context must report a structured failure, got ${wedged?.javaClass?.name}: ${wedged?.message}",
            )
        }
        runtime.close()
        assertFalse(runtime.isAlive, "close must still succeed, or an over-budget plugin would leak a runtime")
    }

    @Test
    fun `a wedged or escaped instance still reports its state honestly`() {
        JsEngineProbe.requireEngine()
        val runtime = started("parser.oom-state", "1;", JsPluginConfig(memoryLimitBytes = 2L * 1024 * 1024))
        try {
            runCatching {
                runtime.evaluateAny("globalThis.x = []; for (var i = 0; i < 400000; i++) globalThis.x.push({pad: 'y'.repeat(80)});")
            }
            // Diagnostics must not throw while the context is in this state — a host that cannot ask
            // "how much is this plugin holding" after a breach has no way to decide to unload it.
            assertTrue(runtime.memoryUsed() >= 0L, "heap accounting must stay readable")
            assertEquals(JsLifecycleState.ACTIVE.name, runtime.lifecycleState)
        } finally {
            runtime.close()
        }
        assertEquals(JsLifecycleState.DISPOSED.name, runtime.lifecycleState)
        assertEquals(-1L, runtime.memoryUsed(), "a disposed instance reports no heap rather than a stale number")
    }

    @Test
    fun `unbounded recursion is caught rather than crashing the vm`() {
        JsEngineProbe.requireEngine()
        val runtime = started("parser.recursive", "function f(n) { return f(n + 1); }")
        try {
            val failure = runCatching { runtime.evaluateAny("f(0)") }.exceptionOrNull()
            assertNotNull(failure, "stack exhaustion must surface as an error")
            // Still alive afterwards — a hard VM crash would not be.
            assertEquals("ok", runtime.evaluateAny("'ok'"))
        } finally {
            runtime.close()
        }
    }
}

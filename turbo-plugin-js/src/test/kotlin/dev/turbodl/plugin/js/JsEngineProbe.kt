package dev.turbodl.plugin.js

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Shared test plumbing.
 *
 * QuickJS reaches the host through a bundled JNI library, which is present for the platforms this
 * repo ships for but cannot be assumed on an arbitrary CI runner. Rather than let a missing native
 * library read as a plugin bug **or** as a passing test, every engine-touching test calls
 * [requireEngine] first: that is a JUnit *assumption*, so an absent engine reports the test as
 * SKIPPED with a reason. A guard that simply `return`s would report a pass for work that never ran,
 * which is exactly the kind of green a reviewer cannot audit.
 */
internal object JsEngineProbe {

    private val available: Boolean by lazy {
        runCatching {
            runBlocking {
                val q = com.dokar.quickjs.QuickJs.create(Dispatchers.IO)
                try {
                    q.evaluate<Int>("1 + 1") == 2
                } finally {
                    q.close()
                }
            }
        }.getOrDefault(false)
    }

    /** True when QuickJS can actually run in this JVM. */
    fun engineAvailable(): Boolean = available

    /**
     * Skip the calling test (not pass it) when the engine cannot run here.
     *
     * The probe is a *real* one — create, evaluate, close — so a broken native load fails loudly
     * inside the probe rather than mid-assertion. Deliberately an assumption rather than an early
     * `return`: a skipped test is visible in the report as work that did not happen, where a guarded
     * return would be counted as a pass.
     */
    fun requireEngine() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            engineAvailable(),
            "QuickJS native library is unavailable on this platform; engine-dependent tests are skipped",
        )
    }

    /** A [JsHostApi] that serves only what these tests need, so no network or disk is touched. */
    fun fakeHost(sink: (String, String) -> Unit = { _, _ -> }): FakeHostApi = FakeHostApi(sink)

    /** Records what the script logged, and can block inside a call to make an invocation long. */
    class FakeHostApi(private val sink: (String, String) -> Unit) : JsHostApi {

        /** Set when [dispatch] should stall, so a test can hold an invocation in flight. */
        @Volatile
        var sleepMillis: Long = 0

        val calls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        override fun dispatch(path: String, payloadJson: String?): Any? {
            calls.add(path)
            return when (path) {
                "time.sleep" -> {
                    val millis = JsValueCodec.asLongOrNull(JsValueCodec.decodeFromJs(payloadJson, "sleep")) ?: 0
                    val budget = if (sleepMillis > 0) sleepMillis else millis
                    sleepUninterruptibly(budget.coerceAtMost(30_000))
                    mapOf("sleptMillis" to budget)
                }
                "time.now" -> mapOf("millis" to System.currentTimeMillis())
                "time.iso" -> mapOf("iso" to "1970-01-01T00:00:00Z", "millis" to 0L)
                "storage.get" -> null
                "storage.set" -> mapOf("ok" to true, "bytes" to 0L)
                "storage.keys" -> emptyList<String>()
                else -> throw JsAbi.AbiException(JsAbi.Code.UNSUPPORTED, "fake host does not serve '$path'")
            }
        }

        /**
         * Block for [millis], ignoring interrupts.
         *
         * A real host call — a socket read, a disk write — does not stop because its caller gave up on
         * a timeout, and that is precisely the property the abandoned-worker lifecycle tests need. A
         * plain [Thread.sleep] here would be cut short by the caller's `future.cancel(true)` and the
         * test would pass for the wrong reason.
         */
        private fun sleepUninterruptibly(millis: Long) {
            var remaining = millis
            val deadline = System.currentTimeMillis() + remaining
            while (remaining > 0) {
                try {
                    Thread.sleep(remaining)
                } catch (e: InterruptedException) {
                    Thread.interrupted() // clear the flag, then keep waiting like an I/O call would
                }
                remaining = deadline - System.currentTimeMillis()
            }
        }

        override fun logFromJs(level: String, text: String) = sink(level, text)

        override fun log(level: String, text: String) = sink(level, text)

        override fun shutdown() = Unit
    }
}

/** Convenience for tests that need a scratch directory under the build dir, not in the repo. */
internal fun jsTempDir(prefix: String = "turbo-js-test"): java.io.File =
    java.nio.file.Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

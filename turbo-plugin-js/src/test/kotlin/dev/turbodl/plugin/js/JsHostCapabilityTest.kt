package dev.turbodl.plugin.js

import com.sun.net.httpserver.HttpServer
import dev.turbodl.core.TurboConfig
import dev.turbodl.plugin.runtime.PluginHost
import dev.turbodl.plugin.runtime.PluginSource
import dev.turbodl.plugin.runtime.ext.ExtensionPoints
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Host capabilities, exercised the only way that matters: from inside a script, through the ABI.
 * Each case checks one of the four things a capability must document — its JS surface, its output
 * shape, its error model, its permission boundary — and results travel as `host.log` text so a
 * passing test proves a plugin author can observe the same thing.
 *
 * HTTP runs against a local [HttpServer] so TurboDL's own transport (`TurboHttpClients`) is the path
 * under test, with no external dependency in CI.
 */
class JsHostCapabilityTest {

    private val served = AtomicInteger(0)

    /** Every handler counts itself, so `served` proves a call reached the transport at all. */
    private fun counted(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): com.sun.net.httpserver.HttpHandler =
        com.sun.net.httpserver.HttpHandler { exchange ->
            served.incrementAndGet()
            handler(exchange)
        }

    private fun startServer(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
            "/hello",
            counted { exchange ->
                val body = "hello world".toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/plain")
                exchange.responseHeaders.add("X-Turbo-Test", "yes")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            },
        )
        server.createContext(
            "/missing",
            counted { exchange ->
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            },
        )
        server.createContext(
            "/large",
            counted { exchange ->
                val body = ByteArray(256 * 1024) { 'a'.code.toByte() }
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            },
        )
        // Echoes the request headers it received, which is how the credential-stripping rule is
        // observable without instrumenting production code.
        server.createContext(
            "/echo",
            counted { exchange ->
                val text = exchange.requestHeaders.keys.joinToString(";") { name ->
                    "$name=${exchange.requestHeaders.getFirst(name)}"
                }
                val body = text.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            },
        )
        // Reports the method and body a hop *actually* delivered, so the 301/302/303-vs-307/308
        // method-rewrite rule is observed at the far origin rather than inferred from the code.
        server.createContext(
            "/method",
            counted { exchange ->
                val received = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val text = "${exchange.requestMethod}|$received"
                val body = text.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            },
        )
        server.start()
        return server
    }

    /**
     * A server whose only job is to redirect.
     *
     * [echoPort] is the *other* server's port, so a hop lands on a different origin (the port differs)
     * — which is the only way to observe the per-hop credential policy rather than assume it.
     */
    private fun startRedirectServer(echoPort: Int): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
            "/to-echo",
            counted { exchange ->
                exchange.responseHeaders.add("Location", "http://127.0.0.1:$echoPort/echo")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            },
        )
        // A chain that never terminates, for the hop cap.
        server.createContext(
            "/loop",
            counted { exchange ->
                exchange.responseHeaders.add("Location", "/loop")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            },
        )
        // The scheme escape a redirect is famous for: a Location that leaves http(s) entirely.
        server.createContext(
            "/to-file",
            counted { exchange ->
                exchange.responseHeaders.add("Location", "file:///etc/passwd")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            },
        )
        // A 303 (See Other) and a 307 (Temporary Redirect) to the *echo* server, so the far side can
        // report which method actually arrived — the only way to observe the method-rewrite rule.
        server.createContext(
            "/see-other",
            counted { exchange ->
                exchange.responseHeaders.add("Location", "http://127.0.0.1:$echoPort/method")
                exchange.sendResponseHeaders(303, -1)
                exchange.close()
            },
        )
        server.createContext(
            "/temporary-preserve",
            counted { exchange ->
                exchange.responseHeaders.add("Location", "http://127.0.0.1:$echoPort/method")
                exchange.sendResponseHeaders(307, -1)
                exchange.close()
            },
        )
        server.start()
        return server
    }

    private class Harness(val host: PluginHost, val logs: MutableList<String>, val pluginId: String) {

        /** The rendered prefix of a line this plugin wrote at [level]; nothing else is attributable. */
        private fun prefix(level: String) = "[$pluginId] [$level] "
        val text: String get() = logs.joinToString("\n")

        /** Only the plugin's own `info` lines — a loader or lifecycle message must never satisfy an assertion. */
        fun lines(level: String = "info"): List<String> = logs.filter { it.startsWith(prefix(level)) }
        fun close() = host.shutdown()
    }

    /** Load [script] as [id] in a fresh host, then invoke its parser once. */
    private fun run(
        script: String,
        id: String = "parser.cap",
        js: JsPluginConfig = JsPluginConfig(),
        engine: TurboConfig = TurboConfig(),
    ): Harness {
        val logs = mutableListOf<String>()
        val h = PluginHost(logger = { msg, e -> logs.add(msg + (e?.let { " — ${it.message}" } ?: "")) })
        h.install(JsPluginLoaderPlugin(engine, js))
        h.loadSource(PluginSource("js", "inline:$script", mapOf("pluginId" to id)))
        h.extensions.all(ExtensionPoints.LINK_PARSER).firstOrNull()?.parse("go")
        return Harness(h, logs, id)
    }

    /**
     * The value a plugin logged after [marker], taken only from *this plugin's* info lines.
     *
     * Deliberately narrow: a lifecycle line that happens to contain the marker must not make a
     * capability test pass, so an unmatched marker is a hard failure with both the marker and the
     * plugin's own output in the message.
     */
    private fun Harness.value(marker: String): String {
        val line = lines().firstOrNull { marker in it }
            ?: error("no plugin log contains '$marker'; the plugin wrote ${lines()} and the host wrote ${logs.filterNot { it in lines() }}")
        return line.substringAfter(marker).trim().trimEnd('\'')
    }

    private fun script(body: String) =
        "plugin.registerParser({parse: function(){ $body return null; }});"

    private fun sandboxDir(root: File, pluginId: String): File = File(root, JsSandbox.dirNameFor(pluginId))

    // ------------------------------------------------------------------ host.log

    @Test
    fun `every js log line is prefixed with the plugin id and a validated level`() {
        JsEngineProbe.requireEngine()
        val harness = run(
            script(
                """
                host.log.info('hi'); host.log.warn('careful'); host.log.error('broken'); host.log.debug('detail');
                host.log.info({taskId: 7, ok: true});
                """.trimIndent()
            ),
        )
        assertTrue("[parser.cap] [info] hi" in harness.text, "actual: ${harness.logs}")
        assertTrue("[parser.cap] [warn] careful" in harness.text)
        assertTrue("[parser.cap] [error] broken" in harness.text)
        assertTrue("[parser.cap] [debug] detail" in harness.text)
        // Structures are rendered by the ABI, so a plugin logs data without a Kotlin type.
        assertTrue("\"taskId\":7" in harness.text, "a structured log must not degrade: ${harness.logs}")
        harness.close()
    }

    @Test
    fun `an unknown log level is normalized and an oversized line is capped`() {
        JsEngineProbe.requireEngine()
        // The `__turbodlLog` primitive is what the ABI calls; the host must not trust its arguments.
        val harness = run(
            script(
                """
                __turbodlLog('SHOUTING', 'unvalidated level');
                __turbodlLog(null, 'missing level');
                __turbodlLog('info', 'x'.repeat(40000));
                """.trimIndent()
            ),
        )
        assertTrue("[parser.cap] [info] unvalidated level" in harness.text, "actual: ${harness.logs}")
        assertTrue("[parser.cap] [info] missing level" in harness.text)
        val long = harness.logs.first { it.contains("xxxx") }
        val payload = long.substringAfter("[info] ")
        assertTrue(payload.length <= 8 * 1024, "a plugin must not be able to write an unbounded log line (${payload.length})")
        harness.close()
    }

    // ------------------------------------------------------------------ host.crypto

    @Test
    fun `crypto digests and hmac match the published test vectors`() {
        JsEngineProbe.requireEngine()
        val harness = run(
            script(
                """
                host.log.info('SHA256:' + host.crypto.digest('SHA-256', 'abc').hex);
                host.log.info('MD5:' + host.crypto.digest('md5', 'abc').hex);
                host.log.info('HMAC:' + host.crypto.hmac('HmacSHA256', 'key', 'The quick brown fox jumps over the lazy dog').hex);
                host.log.info('B64:' + host.crypto.base64Encode('abc').text);
                host.log.info('B64BACK:' + host.crypto.base64Decode('YWJj').base64);
                host.log.info('HEX:' + host.crypto.hexEncode('abc').text);
                host.log.info('ALG:' + host.crypto.digest('SHA-1', 'abc').algorithm);
                """.trimIndent()
            ),
        )
        // Known-answer tests: a wrong implementation would otherwise still "work".
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", harness.value("SHA256:"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", harness.value("MD5:"))
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8", harness.value("HMAC:"))
        assertEquals("YWJj", harness.value("B64:"))
        assertEquals("YWJj", harness.value("B64BACK:"))
        assertEquals("616263", harness.value("HEX:"))
        // The response names the *normalized* algorithm, so a script can log what it actually got.
        assertEquals("SHA-1", harness.value("ALG:"))
        harness.close()
    }

    @Test
    fun `an unsupported algorithm is a validation error that names no jca internals`() {
        JsEngineProbe.requireEngine()
        val harness = run(
            script(
                """
                try { host.crypto.digest('SHA-999', 'x'); host.log.info('CODE:through'); }
                catch (e) { host.log.info('CODE:' + e.code); host.log.info('MSG:' + e.message); }
                try { host.crypto.hexDecode('abc'); host.log.info('HEX:through'); }
                catch (e) { host.log.info('HEX:' + e.code); }
                """.trimIndent()
            ),
        )
        assertEquals("validation", harness.value("CODE:"))
        assertTrue("SHA-999" in harness.value("MSG:"), "the message must say what was rejected: ${harness.logs}")
        assertFalse("NoSuchAlgorithmException" in harness.text, "a provider exception must not leak: ${harness.logs}")
        assertEquals("validation", harness.value("HEX:"))
        harness.close()
    }

    @Test
    fun `randomBytes comes from a csprng and is length-capped`() {
        JsEngineProbe.requireEngine()
        val harness = run(
            script(
                """
                var a = host.crypto.randomBytes(16), b = host.crypto.randomBytes(16);
                host.log.info('LEN:' + a.hex.length);
                host.log.info('SAME:' + (a.hex === b.hex));
                host.log.info('HEXONLY:' + /^[0-9a-f]+$/.test(a.hex));
                try { host.crypto.randomBytes(100000); host.log.info('OVER:through'); }
                catch (e) { host.log.info('OVER:' + e.code); }
                """.trimIndent()
            ),
        )
        assertEquals("32", harness.value("LEN:"), "16 bytes must render as 32 hex chars")
        assertEquals("false", harness.value("SAME:"), "two draws must not repeat")
        assertEquals("true", harness.value("HEXONLY:"))
        assertEquals("limit", harness.value("OVER:"))
        harness.close()
    }

    // ------------------------------------------------------------------ host.http

    @Test
    fun `http request returns a complete response and keeps non-2xx as data`() {
        JsEngineProbe.requireEngine()
        val server = startServer()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val harness = run(
                script(
                    """
                    var r = host.http.request({url: '$base/hello'});
                    host.log.info('OK:' + r.status + '|' + r.ok + '|' + r.body + '|' + r.bytes + '|' + r.url);
                    host.log.info('HDR:' + JSON.stringify(r.headers));
                    var bad = host.http.request({url: '$base/missing'});
                    host.log.info('BAD:' + bad.ok + '|' + bad.status);
                    var bin = host.http.request({url: '$base/hello', as: 'base64'});
                    host.log.info('BIN:' + bin.bodyBase64 + '|' + bin.bytes);
                    var none = host.http.request({url: '$base/hello', as: 'none'});
                    host.log.info('NONE:' + none.body + '|' + none.bytes);
                    """.trimIndent()
                ),
            )
            assertEquals(
                "200|true|hello world|11|$base/hello",
                harness.value("OK:"),
                "a response must come back complete: status, ok, body, byte count, final url",
            )
            // Header names keep the server's spelling, so the check is deliberately case-insensitive.
            val headers = harness.value("HDR:")
            assertTrue("text/plain" in headers, "content-type must be readable: $headers")
            assertTrue(Regex("(?i)x-turbo-test").containsMatchIn(headers), "custom headers must survive: $headers")
            // A 404 is data, never an exception — a plugin must be able to branch on it.
            assertEquals("false|404", harness.value("BAD:"))
            assertEquals("aGVsbG8gd29ybGQ=|11", harness.value("BIN:"))
            // `as:'none'` returns no body but still counts it, so a plugin can size-check a resource.
            assertEquals("null|11", harness.value("NONE:"))
            assertEquals(4, served.get(), "every call must have gone through the transport, got ${served.get()}")
            harness.close()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `http inherits the engine transport and respects a script-set user-agent`() {
        JsEngineProbe.requireEngine()
        val server = startServer()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val harness = run(
                script(
                    """
                    host.log.info('AUTO:' + host.http.request({url: '$base/echo'}).body);
                    host.log.info('OWN:' + host.http.request({url: '$base/echo', headers: {'User-Agent': 'MyApp/2.0'}}).body);
                    """.trimIndent()
                ),
                engine = TurboConfig(userAgent = "TurboDL/0.2.0"),
            )
            // With no UA from the script, traffic is attributed to the plugin, not to an anonymous JVM.
            // The echo server re-canonicalizes header names (`User-agent`), so the match ignores case.
            assertTrue(Regex("(?i)user-agent=TurboDL-js-plugin/1").containsMatchIn(harness.value("AUTO:")), "actual: ${harness.logs}")
            assertTrue(Regex("(?i)user-agent=MyApp/2\\.0").containsMatchIn(harness.value("OWN:")), "a script-set UA must win: ${harness.logs}")
            harness.close()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `credentials are stripped when the target leaves the declared origin`() {
        JsEngineProbe.requireEngine()
        val server = startServer()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val harness = run(
                script(
                    """
                    var away = host.http.request({url: '$base/echo', originUrl: 'https://login.example/', headers: {'Authorization': 'Bearer secret', 'Cookie': 'sid=1', 'Accept': 'application/json'}});
                    host.log.info('AWAY:' + away.body);
                    var home = host.http.request({url: '$base/echo', headers: {'Authorization': 'Bearer secret'}});
                    host.log.info('HOME:' + home.body);
                    """.trimIndent()
                ),
            )
            val away = harness.value("AWAY:")
            assertFalse("Authorization" in away, "a bearer token must not follow off-origin: $away")
            assertFalse("Cookie" in away, "cookies must not follow off-origin: $away")
            assertTrue("Accept" in away, "non-sensitive headers must survive: $away")
            assertTrue("Authorization" in harness.value("HOME:"), "a same-origin request keeps its credentials")
            harness.close()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a non-http scheme is refused before any socket opens, distinctly from a network failure`() {
        JsEngineProbe.requireEngine()
        val harness = run(
            """
            var bad = ['file:///etc/passwd', 'gopher://x/y', 'not a url', 'http://turbo-dl-invalid-host.invalid/a'];
            plugin.registerParser({parse: function(){
              for (var i = 0; i < bad.length; i++) {
                try { host.http.request({url: bad[i]}); host.log.info('LEAK:' + bad[i]); }
                catch (e) { host.log.info('DENY:' + e.code); }
              }
              return null;
            }});
            """.trimIndent(),
        )
        assertFalse("LEAK:" in harness.text, "a refused url must never be fetched: ${harness.logs}")
        val denies = Regex("DENY:(\\w+)").findAll(harness.text).map { it.groupValues[1] }.toList()
        assertEquals(3, denies.count { it == "permission" }, "scheme/format refusals expected, got $denies")
        assertTrue("network" in denies, "a valid-but-unreachable url must report `network`, got $denies")
        harness.close()
    }

    @Test
    fun `an over-cap response errors instead of truncating`() {
        JsEngineProbe.requireEngine()
        val server = startServer()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val harness = run(
                script("try { var r = host.http.request({url: '$base/large'}); host.log.info('GOT:' + r.bytes); } catch (e) { host.log.info('CAP:' + e.code); }"),
                js = JsPluginConfig(maxResponseBytes = 16L * 1024),
            )
            assertEquals("limit", harness.value("CAP:"), "a truncated manifest would look like success; ${harness.logs}")

            // The same ceiling must hold for a zero-length probe of the same resource: the point is
            // that no path reads the body unchecked, and `as:'none'` is the path most likely to skip it.
            val skipped = run(
                script("try { var r = host.http.request({url: '$base/large', as: 'none'}); host.log.info('SKIPPED:' + r.bytes); } catch (e) { host.log.info('SKIPPED:' + e.code); }"),
                js = JsPluginConfig(maxResponseBytes = 16L * 1024),
            )
            assertEquals("limit", skipped.value("SKIPPED:"), "even a skipped body must respect the ceiling: ${skipped.logs}")

            // A script may lower the ceiling for one call, never raise it.
            val lowered = run(
                script("try { host.http.request({url: '$base/hello', maxBytes: 4}); host.log.info('R:through'); } catch (e) { host.log.info('R:' + e.code); }"),
                js = JsPluginConfig(maxResponseBytes = 16L * 1024),
            )
            assertEquals("limit", lowered.value("R:"), "a per-call maxBytes must be honoured")

            val raised = run(
                script("try { host.http.request({url: '$base/large', maxBytes: 100000000}); host.log.info('RAISED:through'); } catch (e) { host.log.info('RAISED:' + e.code); }"),
                js = JsPluginConfig(maxResponseBytes = 16L * 1024),
            )
            assertEquals("limit", raised.value("RAISED:"), "a script must not be able to raise the ceiling: ${raised.logs}")
            harness.close(); skipped.close(); lowered.close(); raised.close()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `downloadtofile writes the whole file into the sandbox and cleans it on unload`() {
        JsEngineProbe.requireEngine()
        val server = startServer()
        val root = jsTempDir("turbo-js-dl")
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val harness = run(
                script("var r = host.http.downloadToFile({url: '$base/large', fileName: 'blob.bin'}); host.log.info('FILE:' + r.file + '|' + r.bytes + '|' + r.ok); host.log.info('ESCAPE:' + (function(){ try { host.http.downloadToFile({url: '$base/large', fileName: '../evil.bin'}); return 'through'; } catch (e) { return e.code; } })());"),
                js = JsPluginConfig(sandboxRoot = root),
            )
            val line = harness.value("FILE:")
            val parts = line.split("|")
            val file = File(parts[0])
            assertEquals("262144", parts[1], "the byte count must be the whole body: $line")
            assertEquals("true", parts[2])
            assertTrue(file.isFile && file.length() == 262_144L, "the file must exist where it was reported: ${parts[0]}")
            assertTrue("downloads" in file.absolutePath, "and inside the plugin's own downloads dir: ${file.absolutePath}")
            assertFalse(File(file.parentFile, file.name + ".part").exists(), "the scratch .part must be renamed away")
            // A traversal is a sandbox-policy refusal, so it surfaces as `permission` (the same code a
            // scheme refusal uses) rather than `validation` — the point is that it is refused at all.
            assertEquals("permission", harness.value("ESCAPE:"), "a traversal fileName must be refused, not clamped")
            harness.close()
            assertFalse(file.exists(), "scratch downloads must not outlive the plugin: ${file.absolutePath}")
            assertTrue(sandboxDir(root, "parser.cap").resolve("storage").isDirectory, "but the plugin's storage dir is policy, not garbage")
        } finally {
            server.stop(0)
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ redirects

    /**
     * The documented promise, made observable: a redirect is re-checked per hop.
     *
     * Two listeners on two ports so the hop really does change origin, and the echo on the far side
     * reports which headers arrived. If following were delegated to OkHttp, `Authorization` would ride
     * the 302 and this test would fail — that is the point of the test, not an incidental detail.
     */
    @Test
    fun `a followed redirect re-applies the origin policy on the new hop`() {
        JsEngineProbe.requireEngine()
        val echo = startServer()
        val redirect = startRedirectServer(echo.address.port)
        try {
            val from = "http://127.0.0.1:${redirect.address.port}"
            val to = "http://127.0.0.1:${echo.address.port}"
            val harness = run(
                script(
                    """
                    var r = host.http.request({url: '$from/to-echo', followRedirects: true, headers: {'Authorization': 'Bearer secret', 'Accept': 'application/json'}});
                    host.log.info('HOP:' + r.status + '|' + r.redirects + '|' + r.url);
                    host.log.info('SENT:' + r.body);
                    """.trimIndent()
                ),
                js = JsPluginConfig(followRedirects = true),
            )
            val line = harness.value("HOP:")
            assertEquals("200|1|$to/echo", line, "the chain must be followed once and reported: $line")
            val arrived = harness.value("SENT:")
            assertFalse("Authorization" in arrived, "a bearer token must not follow the redirect to another origin: $arrived")
            assertTrue("Accept" in arrived, "non-sensitive headers must still survive the hop: $arrived")
            harness.close()
        } finally {
            redirect.stop(0)
            echo.stop(0)
        }
    }

    @Test
    fun `a disabled loader leaves the 3xx as data with its Location intact`() {
        JsEngineProbe.requireEngine()
        val echo = startServer()
        val redirect = startRedirectServer(echo.address.port)
        try {
            val from = "http://127.0.0.1:${redirect.address.port}"
            // Default config: followRedirects = false.
            val harness = run(
                script(
                    """
                    var r = host.http.request({url: '$from/to-echo'});
                    var loc = r.headers['Location'] || r.headers['location'];
                    host.log.info('NOFOLLOW:' + r.ok + '|' + r.status + '|' + r.redirects + '|' + loc);
                    """.trimIndent()
                ),
            )
            assertTrue(
                harness.value("NOFOLLOW:").startsWith("false|302|0|http://"),
                "a 302 is data, not an error, and must not have been followed: ${harness.value("NOFOLLOW:")}",
            )
            assertEquals(1, served.get(), "only the first hop may have reached the transport, got ${served.get()}")
            harness.close()
        } finally {
            redirect.stop(0)
            echo.stop(0)
        }
    }

    @Test
    fun `a per-call followRedirects can narrow the loader grant but never widen it`() {
        JsEngineProbe.requireEngine()
        val echo = startServer()
        val redirect = startRedirectServer(echo.address.port)
        try {
            val from = "http://127.0.0.1:${redirect.address.port}"
            val to = "http://127.0.0.1:${echo.address.port}"
            // Loader enabled; one call opts out.
            val narrowed = run(
                script(
                    """
                    var r = host.http.request({url: '$from/to-echo', followRedirects: false});
                    host.log.info('N:' + r.status + '|' + r.redirects + '|' + r.url);
                    """.trimIndent()
                ),
                js = JsPluginConfig(followRedirects = true),
            )
            assertEquals("302|0|$from/to-echo", narrowed.value("N:"), "a script must be able to disable following for one call")
            narrowed.close()

            // Loader disabled; a script asking for following must not get it.
            val widened = run(
                script(
                    """
                    var r = host.http.request({url: '$from/to-echo', followRedirects: true});
                    host.log.info('W:' + r.status + '|' + r.redirects);
                    """.trimIndent()
                ),
            )
            assertEquals("302|0", widened.value("W:"), "a script must not be able to enable what the loader disabled")
            widened.close()

            // And when both agree, the final URL is the far origin.
            val both = run(
                script("var r = host.http.request({url: '$from/to-echo', followRedirects: true}); host.log.info('B:' + r.url);"),
                js = JsPluginConfig(followRedirects = true),
            )
            assertEquals("$to/echo", both.value("B:"))
            both.close()
        } finally {
            redirect.stop(0)
            echo.stop(0)
        }
    }

    @Test
    fun `a redirect loop is a limit error and a non-http Location is a policy refusal`() {
        JsEngineProbe.requireEngine()
        val echo = startServer()
        val redirect = startRedirectServer(echo.address.port)
        try {
            val from = "http://127.0.0.1:${redirect.address.port}"
            val harness = run(
                script(
                    """
                    try { host.http.request({url: '$from/loop', followRedirects: true}); host.log.info('LOOP:through'); }
                    catch (e) { host.log.info('LOOP:' + e.code + '|' + e.message); }
                    try { host.http.request({url: '$from/to-file', followRedirects: true}); host.log.info('SCHEME:through'); }
                    catch (e) { host.log.info('SCHEME:' + e.code); }
                    """.trimIndent()
                ),
                js = JsPluginConfig(followRedirects = true, httpTimeoutMillis = 10_000),
            )
            val loop = harness.value("LOOP:")
            assertTrue(loop.startsWith("limit|"), "an endless chain must be cut as a limit failure, got $loop")
            assertTrue("hops" in loop, "and say it was the hop cap: $loop")
            assertEquals("permission", harness.value("SCHEME:"), "a Location that leaves http(s) is refused, not fetched")
            harness.close()
        } finally {
            redirect.stop(0)
            echo.stop(0)
        }
    }

    /**
     * The method-rewrite rule, pinned for the cases a "did it have a body?" shortcut gets wrong.
     *
     * 303 (See Other) means "fetch the target with GET" *regardless* of the original method, and
     * 301/302 applied to a bodyless DELETE/PUT must not silently re-issue that method against a
     * Location that may be a different origin. 307/308 are the opposite: the method and body must be
     * preserved byte-for-byte. This is checked at the far server, which reports what it actually got.
     */
    @Test
    fun `a 303 rewrites any method to a bodyless GET while a 307 preserves method and body`() {
        JsEngineProbe.requireEngine()
        val echo = startServer()
        val redirect = startRedirectServer(echo.address.port)
        try {
            val from = "http://127.0.0.1:${redirect.address.port}"
            val harness = run(
                script(
                    """
                    var a = host.http.request({url: '$from/see-other', method: 'PUT', body: 'payload', followRedirects: true});
                    host.log.info('SEE303:' + a.body);
                    var b = host.http.request({url: '$from/temporary-preserve', method: 'PUT', body: 'payload', followRedirects: true});
                    host.log.info('PRESERVE307:' + b.body);
                    var c = host.http.request({url: '$from/see-other', method: 'DELETE', followRedirects: true});
                    host.log.info('DELETE303:' + c.body);
                    """.trimIndent()
                ),
                js = JsPluginConfig(followRedirects = true),
            )
            assertEquals(
                "GET|",
                harness.value("SEE303:"),
                "303 must turn PUT into a bodyless GET, whatever the original method",
            )
            assertEquals(
                "PUT|payload",
                harness.value("PRESERVE307:"),
                "307 must re-send the same method and body",
            )
            assertEquals(
                "GET|",
                harness.value("DELETE303:"),
                "303 must turn a bodyless DELETE into GET too, not preserve it",
            )
            harness.close()
        } finally {
            redirect.stop(0)
            echo.stop(0)
        }
    }

    // ------------------------------------------------------------------ permission boundary

    @Test
    fun `an ungranted capability fails at dispatch and names what to request`() {
        JsEngineProbe.requireEngine()
        val harness = run(
            script("try { host.storage.set('k', 1); host.log.info('GOT:through'); } catch (e) { host.log.info('GOT:' + e.code + '|' + e.message); }"),
            js = JsPluginConfig(permissions = setOf(JsCapability.HTTP, JsCapability.CRYPTO, JsCapability.LOG)),
        )
        val refusal = harness.value("GOT:")
        assertTrue(refusal.startsWith("permission|"), "actual: $refusal")
        assertTrue("storage" in refusal, "the message must name the capability: $refusal")
        harness.close()
    }

    @Test
    fun `an unknown path is unsupported rather than silently ignored`() {
        JsEngineProbe.requireEngine()
        // The ABI's funnel is the only door; a call that bypasses it still lands on the host's `when`.
        // The raw primitive answers with an envelope instead of throwing — that is its contract, and the
        // shim is what turns `ok:false` into a catchable error — so the envelope is read directly here.
        val harness = run(
            script("var env = JSON.parse(__turbodlCall('runtime.exec', '{}')); host.log.info('P:' + env.ok + '|' + env.error.code); try { host.runtime.classForName('java.lang.Runtime'); host.log.info('J:through'); } catch (e) { host.log.info('J:' + (typeof host.runtime)); }"),
        )
        assertEquals("false|unsupported", harness.value("P:"), "an unrouted capability path must be refused: ${harness.logs}")
        // Not a JVM door, not a stubbed namespace — `host.runtime` simply does not exist.
        assertEquals("undefined", harness.value("J:"), "and there must be no JS surface that reaches JVM classes")
        harness.close()
    }

    @Test
    fun `storage round-trips json values inside its own quota`() {
        JsEngineProbe.requireEngine()
        val root = jsTempDir("turbo-js-storage")
        val granted = JsPluginConfig(
            permissions = JsCapability.defaultGrant() + JsCapability.STORAGE,
            sandboxRoot = root,
            maxStorageBytes = 4096,
        )
        try {
            val first = run(
                script("host.storage.set('cursor', {offset: 12, ok: true}); host.log.info('BACK:' + JSON.stringify(host.storage.get('cursor')) + '|' + host.storage.keys().join(',')); host.log.info('GONE:' + host.storage.get('other'));"),
                js = granted,
            )
            assertEquals("""{"offset":12,"ok":true}|cursor""", first.value("BACK:"))
            assertEquals("null", first.value("GONE:"), "a missing key reads as null, not an error")
            val stored = sandboxDir(root, "parser.cap").resolve("storage/cursor")
            assertTrue(stored.isFile, "state must live under the plugin's own sandbox: ${stored.absolutePath}")
            first.close()

            val again = run(script("var v = host.storage.get('cursor'); host.log.info('RE:' + (v && v.offset));"), js = granted)
            assertEquals("12", again.value("RE:"), "storage must survive an unload/reload cycle")

            val over = run(script("try { host.storage.set('big', 'x'.repeat(6000)); host.log.info('Q:through'); } catch (e) { host.log.info('Q:' + e.code); }"), js = granted)
            assertEquals("limit", over.value("Q:"), "the quota is per plugin and enforced on write")

            val removed = run(script("host.storage.remove('cursor'); host.log.info('RM:' + host.storage.keys().length);"), js = granted)
            assertEquals("0", removed.value("RM:"))
            again.close(); over.close(); removed.close()
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * Keys that differ only in a "filename-unsafe" character must stay distinct.
     *
     * A lossy scrub (`:`→`_`, `/`→`_`) would fold `a:b`, `a/b`, `a b` onto one file, so one key's
     * write would silently clobber another's state. The transform is percent-encoding, an injective
     * map, so this cannot happen — and the documented `user:token` shape still round-trips.
     */
    @Test
    fun `storage keys that differ only by unsafe characters do not collide`() {
        JsEngineProbe.requireEngine()
        val root = jsTempDir("turbo-js-storagekeys")
        val granted = JsPluginConfig(
            permissions = JsCapability.defaultGrant() + JsCapability.STORAGE,
            sandboxRoot = root,
        )
        try {
            val harness = run(
                script(
                    """
                    host.storage.set('user:token', 'colon');
                    host.storage.set('user/token', 'slash');
                    host.storage.set('user token', 'space');
                    host.log.info('COLON:' + host.storage.get('user:token'));
                    host.log.info('SLASH:' + host.storage.get('user/token'));
                    host.log.info('SPACE:' + host.storage.get('user token'));
                    host.log.info('COUNT:' + host.storage.keys().length);
                    """.trimIndent()
                ),
                js = granted,
            )
            assertEquals("colon", harness.value("COLON:"), "the documented `user:token` shape must round-trip")
            assertEquals("slash", harness.value("SLASH:"), "a slash key must not read back the colon key's value")
            assertEquals("space", harness.value("SPACE:"), "a space key must not read back another key's value")
            assertEquals("3", harness.value("COUNT:"), "three distinct logical keys must be three files")
            harness.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `env is invisible unless the loader names the variable`() {
        JsEngineProbe.requireEngine()
        val closed = run(
            script("try { host.env.get('PATH'); host.log.info('E:open'); } catch (e) { host.log.info('E:' + e.code); } host.log.info('HAS:' + JSON.stringify(host.env.has('PATH')));"),
            js = JsPluginConfig(permissions = JsCapability.defaultGrant() + JsCapability.ENV),
        )
        assertEquals("permission", closed.value("E:"), "the ambient environment is not readable by default")
        assertTrue("\"allowed\":false" in closed.value("HAS:"), "has() must report policy rather than throw: ${closed.logs}")
        closed.close()

        val open = run(
            script("host.log.info('V:' + JSON.stringify(host.env.has('JAVA_HOME')) + '|' + (typeof host.env.get('JAVA_HOME')));"),
            js = JsPluginConfig(permissions = JsCapability.defaultGrant() + JsCapability.ENV, allowedEnvVars = setOf("JAVA_HOME")),
        )
        assertTrue("V:" in open.text, "an allowlisted variable must be readable: ${open.logs}")
        assertFalse("PATH" in open.value("V:"), "allowlisting one name must not expose the set")
        open.close()
    }

    // ------------------------------------------------------------------ host.time

    @Test
    fun `time comes from the host clock and sleep is bounded`() {
        JsEngineProbe.requireEngine()
        val harness = run(
            script(
                """
                var t = host.time.now().millis;
                host.log.info('T:' + (Math.abs(t - Date.now()) < 5000) + '|' + host.time.iso(0).iso);
                var s = host.time.sleep(40);
                host.log.info('S:' + s.sleptMillis);
                host.log.info('CAP:' + host.time.sleep(999999).sleptMillis);
                """.trimIndent()
            ),
        )
        val line = harness.value("T:")
        assertTrue(line.startsWith("true"), "the host clock is the plugin's clock: $line")
        assertEquals("1970-01-01T00:00:00Z", line.substringAfter("|"), "iso(0)")
        assertEquals("40", harness.value("S:"))
        // A plugin cannot hold an invocation open longer than the host's own sleep ceiling.
        assertTrue(harness.value("CAP:").toLong() <= 5_000, "sleep must be capped: ${harness.logs}")
        harness.close()
    }

    @Test
    fun `clearTimeout is refused without the grant, exactly like setTimeout`() {
        JsEngineProbe.requireEngine()
        // Every capability path is checked at dispatch, including the one that only *cancels*: an
        // ungranted instance hand-cancelling ids it never scheduled would probe the timer table.
        // The raw primitive is used here because the ABI's own clearTimeout cannot be reached without
        // TIMER — which is the point.
        val harness = run(
            script(
                """
                var env = JSON.parse(__turbodlCall('timer.clear', JSON.stringify({id: 1})));
                host.log.info('CLEAR:' + env.ok + '|' + (env.error ? env.error.code : 'none'));
                """.trimIndent()
            ),
            js = JsPluginConfig(permissions = setOf(JsCapability.HTTP, JsCapability.CRYPTO, JsCapability.LOG, JsCapability.TIME)),
        )
        assertEquals("false|permission", harness.value("CLEAR:"), "an ungranted timer.clear must be refused, not answered: ${harness.logs}")
        harness.close()
    }

    @Test
    fun `host log is a grantable capability and an ungranted one is reported once at host level`() {
        JsEngineProbe.requireEngine()
        // LOG is in the default grant, so the ordinary case is a plugin that logs (see the first test
        // in this file). Here the loader has removed it: the script's lines must be dropped — and
        // dropped without throwing, because a log call may never break a plugin — while the host's own
        // diagnostic about the drop still reaches the sink exactly once.
        val noLog = JsPluginConfig(permissions = setOf(JsCapability.HTTP, JsCapability.CRYPTO, JsCapability.TIME))
        val harness = run(
            script(
                """
                host.log.info('SILENT:line-one');
                host.log.warn('SILENT:line-two');
                host.log.info('SILENT:line-three');
                """.trimIndent()
            ),
            js = noLog,
        )
        // `SILENT:` appears in the loader's own "accepting '<source>'" line, so the assertion must be
        // about *rendered plugin lines* (prefix-filtered) rather than the whole log — which is exactly
        // what Harness.lines() is for.
        assertFalse(harness.lines("info").any { "SILENT:" in it }, "a plugin without LOG must not reach the sink at info: ${harness.logs}")
        assertFalse(harness.lines("warn").any { "SILENT:" in it }, "nor at any other level: ${harness.logs}")
        val notices = harness.logs.count { "host.log is not granted" in it }
        assertEquals(1, notices, "the drop must be reported once, not per line: ${harness.logs}")
        // That single host-level notice is also the proof the script really ran: it comes from the
        // host answering the script's own log call. And the plugin survived it — the refusal never
        // surfaced as a script error, so a missing LOG grant costs lines, not the download.
        assertFalse(harness.logs.any { "onLoad failed" in it }, "a missing LOG grant must not fail the load: ${harness.logs}")
        harness.close()
    }

    // ------------------------------------------------------------------ host timers

    @Test
    fun `timers are not part of the default grant`() {
        JsEngineProbe.requireEngine()
        // A timer is a callback the host invokes *later*, from another thread — which makes it the one
        // capability that can outlive the call that created it, so it stays opt-in.
        val harness = run(
            script("try { host.setTimeout(function(){}, 5); host.log.info('GOT:through'); } catch (e) { host.log.info('GOT:' + (e.code || 'error')); }"),
        )
        assertTrue(harness.value("GOT:") in setOf("permission", "error"), "an ungranted timer must be refused: ${harness.logs}")
        assertFalse(harness.lines().any { "through" in it }, "and it must not have been scheduled: ${harness.logs}")
        harness.close()
    }

    @Test
    fun `a granted timer fires back into js and can be cleared`() {
        JsEngineProbe.requireEngine()
        val harness = run(
            script(
                """
                host.setTimeout(function(){ host.log.info('FIRED:once'); }, 20);
                var n = 0;
                var iv = host.setInterval(function(){ n++; host.log.info('TICK:' + n); if (n === 3) host.clearInterval(iv); }, 20);
                // The handle must be usable as-is: a plugin must never be able to clear its own timer
                // only by digging into an envelope, and a second clear must report that there was nothing
                // to clear rather than pretending. A separate handle, so this does not stop `iv`.
                var probe = host.setInterval(function(){ host.log.info('DEAD:cleared-late'); }, 1000);
                host.log.info('ID:' + (typeof probe) + '|' + host.clearTimeout(probe) + '|' + host.clearTimeout(probe));
                try { host.setTimeout('not a function', 5); host.log.info('BAD:through'); } catch (e) { host.log.info('BAD:refused'); }
                try { host.setTimeout(function(){}, -1); host.log.info('NEG:through'); } catch (e) { host.log.info('NEG:' + (e.code || 'refused')); }
                """.trimIndent()
            ),
            js = JsPluginConfig(permissions = JsCapability.defaultGrant() + JsCapability.TIMER),
        )
        // Timer delivery is asynchronous, so the harness waits on observable proof rather than a sleep.
        awaitLog(harness.logs, "FIRED")
        assertEquals("number|true|false", harness.value("ID:"), "a timer handle is a number and clearing is honest about a repeat: ${harness.logs}")
        assertTrue("FIRED:once" in harness.text, "a granted timer must reach JS again: ${harness.logs}")
        assertEquals("refused", harness.value("BAD:"), "a non-function handler is refused before scheduling")
        assertTrue(harness.value("NEG:") in setOf("validation", "refused"), "a negative delay must be refused: ${harness.logs}")
        // The interval ran until the plugin cleared it — and stopped, which is the observable half.
        awaitLog(harness.logs, "TICK:3")
        val ticks = Regex("TICK:(\\d+)").findAll(harness.text).map { it.groupValues[1] }.toList()
        assertEquals(listOf("1", "2", "3"), ticks, "an interval repeats until cleared, then stops: $ticks")
        harness.close()
        assertFalse("DEAD" in harness.text, "a cleared timer must not fire again during or after unload: ${harness.logs}")
    }

    @Test
    fun `a throwing timer callback cannot take the plugin or its unload down`() {
        JsEngineProbe.requireEngine()
        val harness = run(
            script(
                """
                host.setInterval(function(){ throw new Error('boom'); }, 20);
                host.setTimeout(function(){ host.log.info('ALIVE:still-here'); }, 80);
                """.trimIndent()
            ),
            js = JsPluginConfig(permissions = JsCapability.defaultGrant() + JsCapability.TIMER),
        )
        awaitLog(harness.logs, "ALIVE")
        assertTrue("still-here" in harness.text, "a faulting timer must not stop later JS work: ${harness.logs}")
        // The host reports the failure as its own warn line, never as plugin info.
        assertFalse(harness.lines().any { "boom" in it }, "a timer fault must not look like plugin output: ${harness.logs}")
        assertTrue(harness.logs.any { "timer" in it && ("boom" in it || "failed" in it) }, "and it must still be diagnosable: ${harness.logs}")
        harness.close()
        // Teardown must have cancelled the interval; nothing may fire into a disposed runtime.
        assertTrue("DISPOSED" in harness.text, "unload must complete even with a live interval: ${harness.logs}")
        assertFalse("RUNTIME LEAKED" in harness.text, "unload must not leak: ${harness.logs}")
    }

    /** Poll [logs] until [marker] appears, or give up. Timers are asynchronous by nature. */
    private fun awaitLog(logs: List<String>, marker: String, millis: Long = 4_000) {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            if (logs.any { marker in it }) return
            Thread.sleep(20)
        }
    }
}

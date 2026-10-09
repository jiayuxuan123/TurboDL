package dev.turbodl.plugin.js

import dev.turbodl.core.TurboConfig
import dev.turbodl.plugin.runtime.Plugin
import dev.turbodl.plugin.runtime.PluginContext
import dev.turbodl.plugin.runtime.PluginLoaderProvider
import dev.turbodl.plugin.runtime.PluginSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

/**
 * `loader.js` — the JS plugin loader, a first-class **system plugin** and the peer of
 * `turbo-plugin-hls` in the `turbodl-loader` category.
 *
 * ## What it is
 * A [PluginLoaderProvider] implementation registered at the kernel's one and only known extension
 * point ([PluginLoaderProvider.KEY]), plus a [Plugin] that owns the JS instances it produces. It is
 * *not* wired into `turbo-plugin-bootstrap`'s defaults: the Kotlin-only distribution stays
 * byte-identical, and a host opts in with
 * ```kotlin
 * TurboBootstrap.create(extraPlugins = listOf(JsPluginLoaderPlugin(engineConfig = config)))
 * ```
 * That choice is deliberate — QuickJS is a native (JNI) dependency, and a library that adds a `.so`
 * to every downstream build (including YunGet on Android, where the ABI would need its own
 * verification) is a decision the host makes, not the loader.
 *
 * ## What it does NOT do
 * It implements no concrete cloud/HLS/parsing plugin and knows nothing about any of them. It does
 * not expose `PluginContext`, `ExtensionPointKey`, `ServiceRegistry` or `DownloadBackend` to scripts
 * (see [JsExtensionBridge] for the deliberate translation at the boundary), and it never registers a
 * `DownloadBackend` on a script's behalf.
 *
 * ## Sources it accepts
 * `source.kind == "js"`, with `source.uri` naming the script. Recognised shapes:
 *  - `/abs/path/plugin.js` or `file:/abs/path/plugin.js` → read as text
 *  - `inline:<javascript source>` → used directly (tests, host-embedded snippets)
 *  - `data:<percent-encoded text>` → percent-decoded as UTF-8
 *
 * `jar:` / classpath / URL sources are deliberately NOT supported yet (README TODO) — a loader that
 * cannot read a source returns an empty list with a clear log line rather than guessing.
 *
 * Attributes (all optional, all host-supplied — never script-supplied):
 *  - `pluginId` / `pluginName`: override the derived id/name (a script never chooses its own id, so
 *    `loader.`/`core.` cannot be spoofed by a manifest)
 *  - `permissions`: capability list the *source* narrows; intersected with the loader's grant
 *  - `entry`: script file name used for diagnostics and as the id slug
 *  - `category`: id category slug when the uri does not make it obvious
 *  - `encoding`: charset of the script file (default UTF-8)
 *
 * ## Ownership and cascading
 * Plugins this loader produces are installed through
 * [dev.turbodl.plugin.runtime.PluginHost.loadSource], which records them against *this* plugin's id.
 * Unloading `loader.js` therefore unloads every JS plugin first (reverse install order), each running
 * its own [JsScriptPlugin.dispose] drain sequence, and this plugin's disposer stops the shared
 * scheduler last. That ordering is what makes "unload the JS provider" a safe operation rather than a
 * memory-corruption gamble.
 */
class JsPluginLoaderPlugin(
    /** Engine config the host HTTP transport inherits; the loader does not modify it. */
    private val engineConfig: TurboConfig = TurboConfig(),
    /** Loader-wide policy. Defaults are the tight ones documented on [JsPluginConfig]. */
    private val jsConfig: JsPluginConfig = JsPluginConfig(),
    /**
     * Routing priority of the produced JS extensions when a script does not state its own.
     * Kept on the loader so a host can demote all JS plugins at once (e.g. below a Kotlin adapter)
     * without editing scripts; the per-registration `impl.priority` still wins.
     */
    private val defaultExtensionPriority: Int? = null,
    /**
     * Delete each plugin's whole sandbox (`host.storage` included) when it unloads. Off by default:
     * a plugin's stored session token is its own state, and a host that purges it cannot expect a
     * reinstall to behave. Turn it on for kiosk-style hosts where a plugin must leave nothing behind.
     */
    private val purgeSandboxOnUnload: Boolean = false,
) : Plugin {

    override val id: String = "loader.js"
    override val name: String = "JavaScript Plugin Loader"

    /**
     * The kernel's API surface used here is `Plugin`/`PluginContext`/`PluginLoaderProvider`/
     * `ExtensionPointKey` — all present since 1.0.0 — plus [PluginHost.loadSource], which is the
     * generic source dispatch added for loaders of any language. Nothing JS-specific is required of
     * the host, so 1.x is the honest floor.
     */
    override val requiredApiVersion: dev.turbodl.core.ApiVersion = dev.turbodl.core.ApiVersion(1, 0, 0)

    /**
     * The manager this loader created in [onLoad]. Null before load and after unload.
     *
     * Held as a field (it used to be a local) so a host that manages plugins can ask "what is
     * installed right now" without going through the kernel's plugin list and casting. The manager
     * itself stays `internal`: hosts see [livePlugins] and [leakedRuntimeCount], not its internals.
     */
    private var managerRef: JsPluginManager? = null

    /**
     * Every JavaScript plugin instance alive right now, sorted by id.
     *
     * Empty before [onLoad] and once the loader is unloaded — both are honest answers, not errors.
     */
    fun livePlugins(): List<JsScriptPlugin.Info> =
        managerRef?.livePlugins()?.map { it.info() } ?: emptyList()

    /** Ids only; cheaper when a host just needs to know what is there. */
    fun livePluginIds(): List<String> = managerRef?.liveIds() ?: emptyList()

    /**
     * How many QuickJS runtimes this loader refused to close because JavaScript was still executing
     * in them. Non-zero means a script kept running past its drain budget — worth surfacing in a
     * diagnostics screen, because the leak is otherwise invisible.
     */
    fun leakedRuntimeCount(): Int = managerRef?.leakedCount() ?: 0

    override fun onLoad(context: PluginContext) {
        val manager = JsPluginManager(
            loaderId = "js",
            engineConfig = engineConfig,
            purgeSandboxOnUnload = purgeSandboxOnUnload,
        )
        managerRef = manager
        // The disposer stops instances first, then the scheduler — see JsPluginManager.shutdown.
        context.disposer.register {
            // Clear the field first: a host querying livePlugins() while teardown runs must see
            // "nothing is loaded" rather than a half-torn-down manager.
            managerRef = null
            manager.shutdown { message, error -> context.log(message, error) }
        }

        val provider = object : PluginLoaderProvider {
            override val loaderId: String = "js"

            override fun canLoad(source: PluginSource): Boolean = source.kind == KIND

            override fun load(source: PluginSource): List<Plugin> {
                val script = readScript(source, context) ?: return emptyList()
                val pluginId = source.attributes[ATTR_PLUGIN_ID] ?: deriveId(source)
                val identity = JsScriptIdentity(pluginId, source.uri, source.attributes)
                val plugin = JsScriptPlugin(
                    id = pluginId,
                    name = source.attributes[ATTR_PLUGIN_NAME] ?: "JS plugin ${script.displayName}",
                    sourceUri = source.uri,
                    scriptSource = script.source,
                    scriptName = script.displayName,
                    manager = manager,
                    attributes = identity.attributes,
                    logSink = { message, error -> context.log(message, error) },
                    config = narrow(source),
                    defaultExtensionPriority = defaultExtensionPriority,
                )
                context.log("accepting '${describeSourceUri(source.uri)}' as plugin '$pluginId'")
                return listOf(plugin)
            }
        }

        context.registerExtension(PluginLoaderProvider.KEY, provider)
        // Service id == plugin id, per CONVENTION.md §4, so another plugin can gate on the JS loader.
        context.registerService(id, provider)
        context.log(
            "JS loader ready (grants: ${jsConfig.permissions.joinToString(",") { it.jsName }}, " +
                "evaluation budget: ${jsConfig.evaluationTimeoutMillis}ms, " +
                "heap cap: ${jsConfig.memoryLimitBytes}B, " +
                "default extension priority: ${defaultExtensionPriority ?: "per-script"})",
        )
    }

    /**
     * Narrow the loader policy for one source.
     *
     * `permissions` on the source is an *intersection*, never a union: a manifest that asks for
     * `storage` from a loader that does not grant it gets a load-time handshake failure (see
     * [JsScriptPlugin.handshake]) rather than a capability it was never given. Resource ceilings come
     * from the loader only — a script has no way to raise a byte or time bound, by construction.
     */
    private fun narrow(source: PluginSource): JsPluginConfig {
        val requested = JsCapability.parse(source.attributes[ATTR_PERMISSIONS])
        if (requested.isEmpty()) return jsConfig
        val effective = requested intersect jsConfig.permissions
        return jsConfig.copy(permissions = effective)
    }

    /**
     * A loader is allowed to return an empty list, and "I cannot read this" must not be an exception:
     * [dev.turbodl.plugin.runtime.PluginHost.loadSource] logs a loader failure and keeps the host
     * running, so a bad path is a diagnostic, not a crash.
     */
    private fun readScript(source: PluginSource, context: PluginContext): LoadedScript? {
        val uri = source.uri.trim()
        if (uri.isEmpty()) {
            context.log("js source has an empty uri")
            return null
        }
        val encoding = source.attributes[ATTR_ENCODING]
            ?.let { name -> runCatching { Charset.forName(name) }.getOrElse { context.log("unknown encoding '$name', using UTF-8"); Charsets.UTF_8 } }
            ?: Charsets.UTF_8
        return when {
            uri.startsWith("inline:") -> LoadedScript(uri.removePrefix("inline:"), source.attributes[ATTR_ENTRY] ?: "inline.js")
            uri.startsWith("data:") -> runCatching {
                LoadedScript(percentDecode(uri.removePrefix("data:")), source.attributes[ATTR_ENTRY] ?: "data.js")
            }.getOrElse {
                context.log("could not decode data: source '${uri.take(64)}'", it)
                null
            }
            else -> readFile(uri, encoding, source.attributes[ATTR_ENTRY], context)
        }
    }

    private fun readFile(uri: String, encoding: Charset, entry: String?, context: PluginContext): LoadedScript? {
        val path = uri.removePrefix("file:")
        val file = File(path)
        if (!file.isFile) {
            // A jar uri (`jar:file:...!/x.js`) is a documented follow-up; see README TODO. Failing
            // with a clear message is better than half-supporting classpath scanning here.
            context.log("js script '$uri' is not a readable file (jar: sources are not supported yet)")
            return null
        }
        if (file.length() > MAX_SCRIPT_BYTES) {
            context.log("js script '$uri' is ${file.length()}B, over the ${MAX_SCRIPT_BYTES}B loader limit")
            return null
        }
        return runCatching { LoadedScript(file.readText(encoding), file.name) }
            .getOrElse {
                context.log("could not read js script '$uri'", it)
                null
            }
    }

    /**
     * Derive a stable, convention-shaped id from the source. `CONVENTION.md` §4 reserves
     * `backend.`/`loader.`/`core.` for the official project and wants `<category>.<name>`, so a
     * script `acme-parser.js` becomes `parser.acme-parser` — a category the manifest enum already
     * allows — rather than an invented prefix.
     */
    private fun deriveId(source: PluginSource): String {
        val base = source.attributes[ATTR_ENTRY]
            ?: source.uri.substringAfterLast('/').substringBeforeLast("!", "")
            .ifBlank { "plugin" }
        val slug = base.lowercase().map { if (it.isLetterOrDigit() || it == '-' || it == '.') it else '-' }
            .joinToString("")
            .trim('-', '.')
            .ifBlank { "plugin" }
            .take(80)
        val category = source.attributes[ATTR_CATEGORY]
            ?.takeIf { it in CATEGORIES }
            ?: guessCategory(source)
        return "$category.$slug"
    }

    private fun guessCategory(source: PluginSource): String {
        val text = (source.attributes[ATTR_ENTRY] ?: source.uri).lowercase()
        return when {
            "hook" in text -> "hook"
            "parser" in text || "link" in text -> "parser"
            "adapter" in text || "cloud" in text -> "adapter"
            else -> "plugin"
        }
    }

    /** Percent-decode a `data:` source as UTF-8 bytes. */
    private fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val bytes = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                require(i + 2 < text.length) { "truncated percent-escape at index $i" }
                val value = text.substring(i + 1, i + 3).toIntOrNull(16)
                    ?: throw IllegalArgumentException("invalid percent-escape '%${text.substring(i + 1, i + 3)}' at index $i")
                bytes.write(value)
                i += 3
            } else {
                // Only ASCII is written literally; any other char is a bug in the encoder, and
                // silently dropping it would corrupt the script.
                require(c.code < 128) { "data: source must be percent-encoded; raw character '$c' at index $i" }
                bytes.write(c.code)
                i++
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private class LoadedScript(val source: String, val displayName: String)

    private companion object {
        const val KIND = "js"
        const val ATTR_PLUGIN_ID = "pluginId"
        const val ATTR_PLUGIN_NAME = "pluginName"
        const val ATTR_ENTRY = "entry"
        const val ATTR_ENCODING = "encoding"
        const val ATTR_CATEGORY = "category"
        const val ATTR_PERMISSIONS = "permissions"

        /** Manifest `category` values with the `turbodl-` prefix removed. */
        val CATEGORIES = setOf("backend", "adapter", "parser", "hook", "loader", "plugin")

        /** A 1 MB script is already a mistake; anything larger is not a plugin, it is an app. */
        const val MAX_SCRIPT_BYTES = 512L * 1024
    }
}

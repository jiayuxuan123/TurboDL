# TurboDL Plugins

TurboDL is a standalone download engine **and** an optional plugin platform. This directory is the
home for everything a plugin author needs:

- **[Plugin authoring guide](#authoring-a-plugin)** — this file: concepts + a step-by-step guide.
- **[Development Convention](CONVENTION.md)** — the official compatibility rulebook (stable API,
  versioning, naming, safety). Read this before publishing.
- **[Plugin Market](MARKET.md)** — how to publish/discover plugins via GitHub topics + a
  `turbodl-plugin.json` manifest ([schema](turbodl-plugin.schema.json)).

Runnable examples live in the [`demo`](../../demo) module:
`./gradlew :demo:run --args="1"` (Kotlin plugin), `"2"` (bootstrap), `"3"` (shim adapter).

---

## Philosophy

Three ideas drive the design:

1. **Core works alone.** `turbodl-core` is a complete, standalone multi-threaded download engine
   with zero plugin dependency. Plugins are strictly additive; if you never load one, nothing
   changes.

2. **Everything is a plugin; the kernel is only mechanism.** The runtime kernel
   (`turbo-plugin-runtime`) knows nothing about HTTP, HLS, or any protocol. It provides only
   mechanism: lifecycle, cleanup (disposer), a service registry with dependency resolution, a
   type-safe event bus, an extension-point registry, a version handshake, and diagnostics. The
   Kotlin loader, the HTTP backend, the HLS backend — all of them are ordinary plugins. Even a
   future JS runtime would be just a plugin implementing `PluginLoaderProvider`.

3. **Hybrid A+B.** (A) Core ships a built-in HTTP backend so it is useful out of the box.
   (B) A plugin backend can override the built-in one or add new protocols through the extension
   registry — without core ever depending on the runtime. Direction of dependency is strict:
   `runtime → core`, never the reverse.

The result: a small, stable contract surface that third parties can build against, with a
compatibility policy (the [Convention](CONVENTION.md)) so a future breaking core release fails
loudly instead of silently corrupting plugins.

---

## The building blocks

| Concept | Type | What it's for |
|---|---|---|
| Plugin | `Plugin` | The unit you implement. Has an `id`, a `requiredApiVersion`, optional `dependencies`. |
| Context | `PluginContext` | Handed to `onLoad`; how you register services/events/extensions. All registrations auto-clean on unload. |
| Cleanup | `Disposer` | LIFO cleanup chain; drained on unload with per-callback isolation. |
| Services | `ServiceRegistry` | Lightweight id→instance registry + dependency gating. |
| Events | `EventBus` | Observe `TurboEvent`s and intercept `DownloadRequest`s at submit time. |
| Extension points | `ExtensionPointKey<T>` | Typed contracts plugins implement; consumers query by key/priority. |
| Version | `ApiVersion` | The handshake: host must satisfy a plugin's `requiredApiVersion`. |

**Built-in extension points** (`dev.turbodl.plugin.runtime.ext.ExtensionPoints`):
- `DOWNLOAD_BACKEND` — add/override a protocol (`DownloadBackend`)
- `LINK_PARSER` — turn a raw link into `DownloadRequest`s (`LinkParser`)
- `TASK_PRE_HOOK` — rewrite a request before submit (`TaskPreHook`)
- `TASK_POST_HOOK` — react after a task finishes (`TaskPostHook`)

The only extension point the **kernel** knows by name is `PluginLoaderProvider` (loaders).

---

## Authoring a plugin

### 1. Depend on the contracts

```kotlin
dependencies {
    implementation("dev.turbodl:turbodl-core:<version>")
    implementation("dev.turbodl:turbo-plugin-runtime:<version>")
}
```

### 2. Implement `Plugin`

```kotlin
import dev.turbodl.core.ApiVersion
import dev.turbodl.plugin.runtime.Plugin
import dev.turbodl.plugin.runtime.PluginContext

class MyPlugin : Plugin {
    override val id = "adapter.acme"                 // unique, dot-separated, vendor-qualified
    override val name = "ACME Adapter"
    override val requiredApiVersion = ApiVersion(1, 0, 0)   // lowest API you actually use

    override fun onLoad(context: PluginContext) {
        // register services / events / extensions here — all auto-cleaned on unload
    }
}
```

### 3. Register capabilities in `onLoad`

```kotlin
// A service other plugins may depend on:
context.registerService(id, myService)

// Observe engine events (auto-unsubscribed on unload):
context.onEvent { event -> /* ... */ }

// Rewrite requests before they run:
context.interceptRequest { req -> req.copy(headers = req.headers + ("X-Trace" to "1")) }

// Provide an extension-point implementation (higher priority wins for "the" impl):
context.registerExtension(ExtensionPoints.DOWNLOAD_BACKEND, myBackend, priority = 100)

// Anything else that needs teardown:
context.disposer.register { myThreadPool.shutdown() }
```

Everything registered through `context` is torn down automatically when the plugin is
uninstalled. If `onLoad` throws, partial registrations are rolled back for you.

### 4. Writing a `DownloadBackend`

A backend owns the protocol; the engine owns state, events, merging and integrity. Rules
(full list in [Convention §7](CONVENTION.md)):

- `supports(request)` — cheap, side-effect-free, conservative.
- Honor `context.isActive()` and coroutine cancellation.
- `context.reportTotalSize(total)` once known (`-1` if unknown), `context.reportProgress(...)` as
  you go, `context.throttle(bytes)` to respect the global speed limit.
- Write parts into `context.workDir`, return them **in order** as `BackendResult.orderedParts`.
- On unrecoverable/out-of-scope input, **throw** — never emit a corrupt file.
- Need core's HTTP transport policy (proxy/DNS/TLS)? Use `TurboHttpClients.create(config)`; never
  touch core internals.

See `turbo-plugin-hls` for a complete, non-trivial backend (playlist parsing, AES-128,
byte-range, explicit rejection of unsupported constructs).

### 5. Install it

```kotlin
val host = PluginHost()
host.install(MyPlugin())

// Route downloads through plugin backends (falls back to built-in HTTP when none match):
val client = TurboClient(config)
client.backendResolver = BackendRegistry(host.extensions)
```

Or use the bootstrap convenience:

```kotlin
val boot = TurboBootstrap.create(extraPlugins = listOf(MyPlugin()))
val id = boot.client.submit(DownloadRequest(url, dest))
```

### 6. Verify with diagnostics

```kotlin
println(host.diagnostics().render())
// Lists plugins + states (LOADED / WAITING / FAILED / INCOMPATIBLE / UNLOADED),
// extension points, services, listener counts.
```

If your plugin shows `INCOMPATIBLE`, the running TurboDL API does not satisfy your
`requiredApiVersion` — check the handshake ([Convention §2](CONVENTION.md)).

### 7. Publish

Follow the [Plugin Market](MARKET.md) steps: add `turbodl-plugin.json`, tag the repo with
`turbodl-plugin` + a category + capabilities, publish an artifact, cut a release.

---

## Shim adapters

A "shim" wraps an external downloader/SDK and exposes it to TurboDL through `LinkParser` +
`DownloadBackend`, without TurboDL knowing anything about that system. Start from the template in
`demo/.../Example3ShimAdapter.kt` — replace the placeholder `ExternalDownloader` with a real SDK.

---

## A note on JS

A JavaScript runtime is a **reserved, future** capability. It would ship as a separate plugin
implementing `PluginLoaderProvider`; the core and the Kotlin loader stay unaware of JS, and no JS
engine is pulled into the kernel. The manifest reserves `entry.language: "js"` for that day.

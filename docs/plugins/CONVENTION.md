# TurboDL Plugin Ecosystem Development Convention

**Status:** Official · **Version:** 1.0 · **Applies to TurboDL API:** `1.x`

This is the official contract between the TurboDL project and plugin authors. It exists so the
ecosystem can grow without a breaking core update silently breaking every plugin. It is
maintained by the TurboDL project itself (not delegated to third parties) so that compatibility
rules stay consistent across the ecosystem.

If you only want to *write* a plugin, start with [docs/plugins/README.md](README.md); this
document is the *rulebook* that guide relies on.

---

## 1. Scope and terms

- **Core** — the `turbodl-core` module: the standalone download engine and the public data
  models/contracts plugins are allowed to use.
- **Runtime kernel** — the `turbo-plugin-runtime` module: lifecycle, disposer, service registry,
  event bus, extension-point registry, version handshake. Mechanism only, no business logic.
- **Plugin** — anything that implements `dev.turbodl.plugin.runtime.Plugin`. Loaders, backends,
  parsers, hooks and adapters are all plugins. Only the kernel is not a plugin.
- **Stable API** — the symbols listed in §3. Everything else is internal and may change without
  notice.
- **MUST / SHOULD / MAY** follow RFC 2119.

---

## 2. Versioning and compatibility policy

TurboDL versions its **public, plugin-facing API** with semantic versioning, exposed as
`dev.turbodl.core.ApiVersion.CURRENT`.

- **MAJOR** — incremented on any breaking change to a Stable API symbol (§3). Cross-MAJOR is
  always considered incompatible.
- **MINOR** — incremented on backwards-compatible additions (new methods with defaults, new
  extension points, new optional config).
- **PATCH** — incremented on backwards-compatible fixes.

Handshake rule enforced by the kernel:

```
host.satisfies(required)  ==  (host.major == required.major && host >= required)
```

- Every plugin declares `Plugin.requiredApiVersion` (default `1.0.0`).
- The host loads a plugin only when `ApiVersion.CURRENT.satisfies(plugin.requiredApiVersion)`.
- On mismatch the plugin is marked `PluginState.INCOMPATIBLE`, `onLoad` is **never** called, and
  a diagnostic is logged. This is by design: a breaking release fails loudly at load time
  instead of corrupting behavior.

Plugin authors:
- MUST set `requiredApiVersion` to the lowest version whose API they actually use.
- SHOULD publish a new plugin release for each core MAJOR they support.
- MUST NOT rely on internal (non-§3) classes to dodge the handshake.

### TurboDL's promises to plugin authors

Within a single MAJOR line, the project:
- MUST NOT remove or change the signature of any Stable API symbol.
- MUST NOT change the documented semantics of an extension point in a way that breaks existing
  implementations.
- MAY add new Stable API (MINOR) — additions must be source- and binary-compatible (interface
  additions ship with default implementations).
- MUST document every change in `CHANGELOG` and, for a MAJOR bump, provide a migration note.

---

## 3. The Stable API surface (`1.x`)

Only these symbols are covered by the compatibility policy. Package prefixes:
`dev.turbodl.core.*` (core) and `dev.turbodl.plugin.runtime.*` (kernel).

**Core contracts**
- `ApiVersion` (+ `CURRENT`, `satisfies`, `parse`)
- `DownloadRequest`, `TaskState`, `TaskProgress`, `TurboEvent` (sealed hierarchy)
- `DownloadBackend`, `BackendContext`, `BackendResult`, `BackendResolver`
- `TurboConfig`, `ProxyMode`, `ProxyType`, `DnsMode`
- `TurboClient` public methods: `submit`, `await`, `pause`, `resume`, `cancel`,
  `updateConfig`, `shutdown`, `events`, `progress`, `backendResolver`
- `TurboBackends.builtinHttp`, `TurboHttpClients.create`

**Runtime kernel contracts**
- `Plugin`, `PluginContext` (+ `service` reified helper)
- `PluginHost` public methods: `install`, `installAll`, `uninstall`, `shutdown`,
  `publishEvent`, `applyRequestInterceptors`, `diagnostics`, and the `services`/`extensions`/
  `eventBus` accessors
- `Disposer`, `PluginState`, `PluginInfo`, `DiagnosticsSnapshot`
- `ExtensionPointKey`, `ExtensionRegistration`, `ExtensionRegistry`, `ServiceRegistry`, `EventBus`
- `PluginLoaderProvider` (+ `KEY`), `PluginSource`
- `dev.turbodl.plugin.runtime.ext.*`: `ExtensionPoints`, `LinkParser`, `TaskPreHook`,
  `TaskPostHook`, `BackendRegistry`

**Explicitly NOT stable** (internal; do not depend on): `SegmentDownloader`, `SegmentScheduler`,
`BuiltinHttpBackend`, `HttpClientFactory`, `PartMerger`, `SpeedLimiter`, and anything not listed
above.

---

## 4. Plugin identity and naming

- `Plugin.id` MUST be globally unique, stable across releases, lowercase, dot-separated:
  `<category>.<name>`, e.g. `backend.http`, `backend.hls`, `loader.kotlin`, `adapter.cordis`.
- Reserved category prefixes owned by the official project: `backend.`, `loader.`, `core.`.
  Third-party plugins SHOULD use a vendor-qualified name, e.g. `adapter.acme-cloud`,
  `backend.acme-ftp` (a new protocol backend is allowed to use `backend.` but SHOULD vendor-qualify).
- Service ids registered via `registerService` follow the same rules and SHOULD match the plugin
  id for a plugin's primary service.
- Changing a published `Plugin.id` is a breaking change for anyone who depends on it; treat it as
  a MAJOR event for your plugin.

---

## 5. Lifecycle contract

- `onLoad` runs exactly once, only after (a) the version handshake passed and (b) all declared
  `dependencies` (service ids) are present.
- A plugin MUST register a matching cleanup for every side effect. In practice, prefer the
  `PluginContext` methods — service/event/extension registrations are auto-wired into the
  disposer — and use `context.disposer.register { ... }` for anything else (threads, sockets,
  temp files, external SDK handles).
- If `onLoad` throws, the host rolls back the plugin's disposer chain and marks it `FAILED`.
  Partial side effects MUST be safe to roll back.
- `onUnload` MAY do extra work but MUST NOT assume the disposer has run yet (it runs after).
- Plugins MUST be safe to `uninstall` at any time: after unload, none of the plugin's services,
  listeners or extension implementations may remain reachable.
- `onLoad`/`onUnload` MUST return promptly. Long or blocking work belongs on the plugin's own
  coroutine/thread, torn down via a disposer.

---

## 6. Extension points

- Register implementations via `PluginContext.registerExtension(key, impl, priority)`.
- Higher `priority` wins where a consumer picks "the" implementation (e.g. backend routing).
  Official base plugins use priority `0`; a plugin that intends to override a base capability
  uses a higher value (HLS uses `100`; adapters commonly `200`). Choose the lowest priority that
  achieves your intent.
- `DownloadBackend.supports` MUST be cheap, side-effect-free, and conservative — return `true`
  only for requests you can actually handle, so routing stays predictable and non-matching
  requests fall through to the built-in HTTP backend.
- `LinkParser.parse` MUST return `null` (not throw) for input it does not handle, so the router
  can try the next parser.
- Hook/parser/backend implementations MUST tolerate being called concurrently.

---

## 7. Backend authoring rules

A `DownloadBackend` owns the protocol layer only; the engine owns state, events, merging and
integrity. A backend:

- MUST honor cooperative cancellation: check `BackendContext.isActive()` and respect coroutine
  cancellation; stop promptly when paused/canceled.
- MUST report size via `reportTotalSize` once known (use `-1` for unknown/streaming) and progress
  via `reportProgress`.
- SHOULD rate-limit byte writes through `BackendContext.throttle(bytes)` so the global speed
  limit is honored across tasks.
- MUST write outputs into `BackendContext.workDir` and return them as an ordered
  `BackendResult.orderedParts`; the engine concatenates them in that exact order.
- MUST fail with an exception on unrecoverable errors rather than producing a truncated or
  corrupt output. When a format is out of scope, fail explicitly (see how the HLS backend rejects
  live/DRM/fMP4 rather than emitting a broken file).
- MUST NOT reach into core internals; use only §3 symbols. If you need core's HTTP transport
  policy (proxy/DNS/TLS), obtain a client via `TurboHttpClients.create(config)`.

---

## 8. Events and services

- Event listeners and request interceptors MUST NOT throw; the bus isolates and logs failures,
  but a well-behaved plugin handles its own errors.
- Interceptors MUST be pure-ish and fast; return the input unchanged to no-op. They run on the
  submit path.
- Services are a lightweight id→instance registry, not an IoC container. Depend on a service by
  listing its id in `dependencies`; look one up with `context.service<T>(id)`.
- Do not block the event bus; offload heavy work.

---

## 9. Security and safety

- Treat all playlist/manifest/redirect/link content as untrusted input. Validate schemes; reject
  non-`http(s)` URIs unless your protocol explicitly requires otherwise (the HLS backend rejects
  `file://` to prevent SSRF/local file reads).
- Do not exfiltrate user data or credentials. A plugin MUST NOT transmit request URLs, headers,
  cookies, or downloaded content to third-party endpoints unless that is the plugin's explicit,
  documented purpose.
- Pin dependency versions; avoid pulling large or unvetted transitive dependencies into the
  runtime.
- A plugin MUST NOT weaken TLS (`trustAllCerts`) except when the user explicitly opts in through
  config; never hardcode it on.
- Handle secrets (keys, tokens) by reference; never log their values.

---

## 10. Packaging and distribution

- One plugin repository SHOULD ship one primary capability. Provide a `turbodl-plugin.json`
  manifest (see the plugin market doc) and tag the repo with the appropriate GitHub topics.
- Declare which TurboDL MAJOR line the release targets in both the manifest and the release notes.
- Provide a runnable example or test proving the plugin loads and performs its capability.
- License your plugin however you wish. Under TurboDL's supplemental terms, interacting through
  the public API/extension points does not make your plugin a derivative work of TurboDL.

---

## 11. Breaking-change discipline (for plugin authors)

Apply the same discipline to your own plugin that TurboDL applies to the core:
- Bump your plugin's MAJOR when you change its `Plugin.id`, remove a service it published, or
  change an extension's observable behavior.
- Keep `requiredApiVersion` accurate.
- Document migration steps in your plugin's changelog.

---

## 12. Changing this convention

This document is versioned. Backwards-compatible clarifications bump its MINOR; a change that
invalidates previously-conformant plugins bumps its MAJOR and MUST ship with the corresponding
core MAJOR and a migration note. Proposals go through the TurboDL repository.

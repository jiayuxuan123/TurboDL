# TurboDL Plugin Market (GitHub-Topic Based)

TurboDL does not run a centralized package server. The plugin "market" is simply **GitHub topic
tags plus a machine-readable manifest**. Anyone can publish a plugin by pushing a repository,
adding the right topics, and dropping a `turbodl-plugin.json` at the repo root. Anyone can
discover plugins with a GitHub topic search. This keeps the ecosystem open, decentralized, and
zero-infrastructure.

This document defines the tags, the manifest, and the publish/discover flow. It pairs with the
[Plugin Ecosystem Development Convention](CONVENTION.md) (the compatibility rulebook) and the
[plugin authoring guide](README.md).

---

## 1. Discover plugins

All TurboDL plugins carry the root topic **`turbodl-plugin`**. Browse them at:

```
https://github.com/topics/turbodl-plugin
```

Narrow by capability by combining topics in GitHub search:

```
topic:turbodl-plugin topic:turbodl-backend      # protocol backends
topic:turbodl-plugin topic:turbodl-adapter       # shim/service adapters
topic:turbodl-plugin topic:turbodl-hls           # HLS-related
```

The GitHub API works too:

```
GET https://api.github.com/search/repositories?q=topic:turbodl-plugin+topic:turbodl-backend
```

---

## 2. Topic tags (the "shelves")

Every plugin repo MUST have `turbodl-plugin`, plus exactly one **category** topic, plus any
number of **capability** topics.

**Root (required)**
- `turbodl-plugin`

**Category (choose one, required)**
- `turbodl-backend` — adds/overrides a download protocol (`DownloadBackend`)
- `turbodl-adapter` — bridges an external system/service (shim; usually `LinkParser` + backend)
- `turbodl-parser` — link/manifest parser only (`LinkParser`)
- `turbodl-hook` — task pre/post processing (`TaskPreHook` / `TaskPostHook`)
- `turbodl-loader` — a plugin loader (`PluginLoaderProvider`, e.g. a JS provider)

**Capability (optional, any number)**
- Protocol/format: `turbodl-hls`, `turbodl-dash`, `turbodl-ftp`, `turbodl-magnet`, `turbodl-m3u8`
- Behavior: `turbodl-remux`, `turbodl-checksum`, `turbodl-notify`, `turbodl-unpack`
- Integration: `turbodl-cloud`, `turbodl-drm-free`

Category and capability tags are what make market plugins easy to build and find: you pick your
shelf, users filter to it.

---

## 3. The `turbodl-plugin.json` manifest

Place this file at the repository root. It is the single machine-readable descriptor a tool (or a
future official indexer) reads to understand your plugin.

```json
{
  "manifestVersion": "1.0",
  "id": "backend.hls",
  "name": "HLS VOD Backend",
  "description": "Downloads HLS VOD (.m3u8) streams: variant selection, AES-128, byte-range.",
  "version": "1.0.0",
  "author": "your-name-or-org",
  "homepage": "https://github.com/you/turbodl-plugin-hls",
  "license": "MIT",

  "category": "turbodl-backend",
  "capabilities": ["turbodl-hls", "turbodl-m3u8"],

  "turbodl": {
    "apiMajor": 1,
    "requiredApiVersion": "1.0.0"
  },

  "entry": {
    "language": "kotlin",
    "pluginClass": "dev.turbodl.plugin.hls.HlsPlugin"
  },

  "artifact": {
    "type": "maven",
    "coordinates": "dev.turbodl:turbo-plugin-hls:1.0.0"
  },

  "extensionPoints": ["turbo.downloadBackend"],
  "services": ["backend.hls"]
}
```

Field notes:
- `id` MUST equal the plugin's `Plugin.id` and follow the naming rules in the Convention (§4).
- `turbodl.apiMajor` and `requiredApiVersion` MUST match what the plugin declares in code
  (`Plugin.requiredApiVersion`). This is how a market/tool filters out plugins that cannot run on
  a given TurboDL version **before** downloading them.
- `category` MUST be one of the category topics; `capabilities` SHOULD mirror the repo's
  capability topics.
- `entry.language` is `kotlin` today. `js` is **reserved** for a future JS provider; the core and
  the Kotlin loader remain unaware of JS.
- `artifact.type` is `maven` (published JAR) or `jar` (direct release asset URL in
  `artifact.url`). Choose what your distribution uses.

A JSON Schema for validation lives at [`turbodl-plugin.schema.json`](turbodl-plugin.schema.json).

---

## 4. Recommended repository layout

```
turbodl-plugin-<name>/
├─ turbodl-plugin.json          # manifest (root)
├─ README.md                    # what it does, install snippet, supported TurboDL MAJOR
├─ LICENSE
├─ src/main/kotlin/...          # the Plugin implementation
└─ src/test/kotlin/...          # a test proving it loads + performs its capability
```

Repository description and README SHOULD state the supported TurboDL MAJOR line explicitly
(e.g. "TurboDL 1.x").

---

## 5. Publish checklist

1. Implement a `Plugin` per the [authoring guide](README.md) and the [Convention](CONVENTION.md).
2. Set `Plugin.requiredApiVersion` to the lowest API you actually use.
3. Add `turbodl-plugin.json` at the repo root; validate it against the schema.
4. Add GitHub topics: `turbodl-plugin` + one category + capabilities.
5. Fill README with an install snippet and the supported TurboDL MAJOR.
6. Publish an artifact (Maven coordinates or a release JAR) matching `artifact`.
7. Tag a release whose version equals the manifest `version`.

That's the whole "market": push, tag, done. No gatekeeper, no server.

---

## 6. Install a plugin (consumer side)

1. Add the plugin artifact to your build (Maven coordinates from the manifest), alongside
   `turbodl-core` and `turbo-plugin-runtime`.
2. Install it into your host:

```kotlin
val host = PluginHost()
host.install(HlsPlugin())                 // or the plugin's documented entry class
// If you use the bootstrap convenience:
val boot = TurboBootstrap.create(extraPlugins = listOf(HlsPlugin()))
```

3. The version handshake runs automatically. If the plugin needs a newer API than your TurboDL,
   it is marked `INCOMPATIBLE` and never loaded — check `host.diagnostics().render()`.

---

## 7. Trust and safety

There is no central review, so treat third-party plugins like any dependency:
- Read the source; prefer plugins with tests and a clear license.
- Check the manifest `apiMajor` matches your TurboDL before installing.
- The [Convention §9](CONVENTION.md) lists the security rules plugins are expected to follow
  (untrusted-input validation, no data exfiltration, no silent TLS weakening). Plugins violating
  these should be reported on their repository and MAY be delisted from any official index.

---

## 8. Future: optional official index

The topic-based market needs no server. If demand grows, the project MAY publish a static,
generated index that periodically crawls `topic:turbodl-plugin`, validates each
`turbodl-plugin.json`, and renders a searchable list filtered by category, capability and
supported API MAJOR. This would remain a convenience layer on top of GitHub topics, never a
gatekeeper.

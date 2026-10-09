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
topic:turbodl-plugin topic:turbodl-protocol     # plugins that declare protocols/schemes
topic:turbodl-plugin topic:turbodl-adapter      # shim/service adapters
topic:turbodl-plugin topic:turbodl-hls          # HLS-related
```

Then narrow further by **protocol**: a plugin's manifest `protocols` array is the
machine-readable "what does it handle" list — one plugin may declare several — so a market
indexer built on these manifests can answer "which plugins handle `magnet`?" without a central
server (see §2 and §3).

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
- `turbodl-protocol` — declares the protocols/schemes it handles (`protocols`); typically an
  adapter/backend for one protocol family, e.g. HLS, magnet/BT, FTP family
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

### Filtering by protocol

Topics are the coarse shelf; the manifest `protocols` array is the precise answer to "does this
plugin handle my link?". Conventions:

- A plugin that handles a protocol declares it in `protocols` (one plugin MAY declare several —
  e.g. `["magnet","bt","ed2k"]`); a plugin that handles none (a loader, a hook, a checksum tool)
  declares `[]`.
- A market/indexer SHOULD offer "filter by protocol" alongside "filter by category", reading
  `protocols` from validated manifests, and SHOULD surface the protocols on the plugin card.
- When two listed plugins declare the same protocol, the market SHOULD show both and say that the
  host picks the higher-`priority` claimant at load time (the host's index reports this overlap as
  a conflict) rather than hiding one.
- Protocol names are lowercase tokens (`^[a-z][a-z0-9+.-]{0,31}$`). A name is not a URL scheme
  guarantee: HLS declares `hls` although an `.m3u8` link is an `http(s)` URL; routing is decided
  by the plugin's backend predicate, the declaration is what the market filters on. Plugin cards
  SHOULD carry that caveat when it applies.

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

  "category": "turbodl-protocol",
  "capabilities": ["dev.turbodl.cap.hls", "dev.turbodl.cap.m3u8"],
  "protocols": ["hls"],

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

  "extensionPoints": ["turbo.downloadBackend", "turbo.protocolHandler"],
  "services": ["backend.hls"]
}
```

Field notes:
- `id` MUST equal the plugin's `Plugin.id` and follow the naming rules in the Convention (§4).
- `turbodl.apiMajor` and `requiredApiVersion` MUST match what the plugin declares in code
  (`Plugin.requiredApiVersion`). This is how a market/tool filters out plugins that cannot run on
  a given TurboDL version **before** downloading them.
- `category` MUST be one of the category topics.
- `protocols` lists the protocols/schemes the plugin declares it handles — the field the market
  filters by. It is optional (omit or `[]` when the plugin handles no protocol) and a plugin MAY
  declare several: `["magnet","bt","ed2k"]` is one plugin, three protocols. The names MUST match
  the `ProtocolClaim`s the plugin registers (Convention §12), and a declaration grants no
  permission and does not by itself route a download.
- `capabilities` SHOULD list one namespaced id per capability topic the repo carries, in the form
  `<namespace>.cap.<name>` — TurboDL's own namespace is `dev.turbodl.cap.*`
  (`dev.turbodl.cap.hls` ↔ topic `turbodl-hls`). The namespace is what lets two vendors publish a
  same-named capability without colliding; a bare legacy id such as `turbodl-hls` is rejected by
  the schema. Like a protocol declaration, a capability is a label: it grants no permission.
- `entry.language` is `kotlin` for a JVM plugin class, or `js` for a script loaded by the
  `turbo-plugin-js` loader. Requiring `js` means the host must have that loader installed;
  the core and the Kotlin loader itself stay unaware of JS.
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
4. Handle protocols? List them in the manifest `protocols` and register the matching
   `ProtocolClaim`s (Convention §12); if you handle none, `protocols: []`.
5. Add GitHub topics: `turbodl-plugin` + one category + capabilities.
6. Fill README with an install snippet and the supported TurboDL MAJOR.
7. Publish an artifact (Maven coordinates or a release JAR) matching `artifact`.
8. Tag a release whose version equals the manifest `version`.

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

# TurboDL

当前维护版本：**0.5.0.3**（Kotlin/JVM 依赖包 `turbodl-core`）。本版增加任务级传输策略与执行诊断，详见[0.5.0.3 更新日志](engineering/maintenance/RELEASE-0.5.0.3.md)；上一版 [0.5.0.2 历史记录](engineering/maintenance/RELEASE-0.5.0.2.md)保留。

> High-performance multi-threaded download engine SDK — the core remains pure Kotlin/JVM with no Android dependency and is embeddable in any JVM application (including Android). An optional native CLI fast path adds instant startup and a Zig HTTP data plane.

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

**Website:** [jiayuxuan123.github.io/TurboDL](https://jiayuxuan123.github.io/TurboDL/) — documentation, plugin authoring guide, download page

**Engineering plan:** [Industrial execution roadmap](engineering/industrial/ROADMAP.md) · [Execution board and 8 GiB network budget](engineering/industrial/EXECUTION_BOARD.md) · [Risk register](engineering/industrial/RISK_REGISTER.md) · [Network and competitor acceptance](engineering/industrial/NETWORK_ACCEPTANCE.md) · [First engine evidence pass](engineering/industrial/evidence/2026-11-04-engine-pass1.md) · [Model handoff](engineering/industrial/HANDOFF.md). These are planned acceptance requirements, not claims that all targets have already passed.

**Languages:** English · [简体中文](docs/i18n/README_zh-CN.md) · [繁體中文](docs/i18n/README_zh-TW.md) · [日本語](docs/i18n/README_ja.md) · [한국어](docs/i18n/README_ko.md) · [Deutsch](docs/i18n/README_de.md)

TurboDL is a download core written from scratch. It **only draws on the architectural and algorithmic ideas** of mature download managers (aria2, IDM/XDM, axel, Persepolis, Motrix, ab-download-manager) **without copying any of their source code**, and is therefore released under the permissive **MIT license (with supplemental plugin-ecosystem terms)**, free to use in open-source or commercial projects.

## Features

- **Multi-threaded segmented download**: HTTP Range parallel segments written **directly into the destination file at their offsets** (positional writes), connection reuse (HTTP/2 multiplexing + keep-alive).
- **No merge phase**: segments are not separate temp files that get concatenated afterwards. Measured on one disk, 16 MB: creating 512 segment files costs 627 ms while writing the same bytes positionally into one file costs 11 ms — 57x. On loopback this took 128-connection throughput from 20 MB/s to 88 MB/s, and it no longer degrades as connection count rises. There is also no "stuck at 99 %" step, because nothing is left to assemble.
- **Resumable progress**: a sidecar records segment offsets and is persisted after the engine flushes the destination. Process-kill recovery is tested; actual power-loss durability also depends on the operating system, filesystem, and storage hardware.
- **Failure attribution without collateral damage**: every segment keeps its own last error, and a request cancelled because *another* segment exhausted its retries does not consume the cancelled segment's retry budget. A single flaky connection therefore neither fails the task nor produces a misleading "retry N times failed (Canceled)" message.
- **Dynamic segmentation**: fine-grained pre-splitting + work stealing, so slow connections don't drag down the whole transfer and the "last segment finishing on a single thread" long tail is eliminated (IDM/XDM idea).
- **Host-controlled adaptive concurrency**: the configured connection count is a ceiling. `DOWNWARD` starts at the caller's requested count and reduces only when repeated throughput measurements prove the connections are wasted; `UPWARD` starts conservatively and doubles only after aggregate throughput improves by at least 30%. The selected policy is a host decision, not a provider-specific rule baked into the engine.
- **Endgame by ETA, not by segment size**: tail assist now tracks each in-flight segment's EWMA throughput and takes the remaining tail from the connection with the worst estimated completion time. A shrinking segment's old writer also stops at the handoff boundary instead of writing into the portion assigned to the new connection.
- **Global resource budgets**: task slots are FIFO and atomic, and `maxTotalConnections` caps the aggregate connection count across every built-in HTTP task. Three 64-connection tasks can no longer silently create 192 sockets when the host or router should only see a smaller budget.
- **Desktop I/O path**: JVM hosts use direct ByteBuffers; Android/Dalvik falls back to heap buffers and `RandomAccessFile`. Sidecar replacement keeps a durable backup instead of deleting the old progress before the new one is visible.
- **Caller-supplied SHA-256**: `DownloadRequest.expectedSha256` streams the finished file through SHA-256 before completion; a mismatch fails the task and drops the resume snapshot so bad bytes are never reused.
- **Multi-source mirrors** (`0.4.0.0`): `DownloadRequest.mirrors` adds alternate URLs for the same file. The engine samples sources, routes segments toward faster ones, and rejects a source whose reported size disagrees with the task size. A same-size different file is not detected by size alone; provide `expectedSha256` to check the completed output. CLI: `--mirror <URL>` (repeatable).
- **Sequential segment priority**: earlier segments are downloaded first, enabling progressive preview/playback (axel/Persepolis idea).
- **Robust fallback & retry**:
  - Server does not support / ignores Range → automatic fallback to whole-file single-stream download;
  - A bad/timed-out segment → **only that segment is retried**, the whole task is not discarded;
  - Verifies that the actually returned bytes match the requested Range (guards against servers tampering with Range and returning the whole file).
- **Restrained adaptivity**: a task starts at the caller's connection ceiling. A lower tier is accepted only when a clean, post-switch window still retains at least 90% of aggregate throughput; stale requests from the previous tier are excluded. 429/503 backpressure always takes priority.
- **Resumable downloads**: segments are persisted; pause/resume continues from the real on-disk progress.
- **Final verification**: the output length is checked. When `expectedSha256` is provided, its SHA-256 is also checked before the task is marked complete; length alone cannot detect same-size corruption.

## 0.5.0.1 — native fast path and stability

- Add an optional Zig fast path: `turbodl native <url>` performs simple HTTP
  Range downloads without starting the JVM: positional writes, retries,
  resumable sidecars and final SHA-256 are covered.
- Add a Zig launcher: native `--version` returns immediately; all other commands
  hand off to the unchanged Kotlin/JVM engine.
- A/B tests (32 MiB / 256 MiB / 2 GiB, interleaved, independent SHA-256) showed
  the native data plane at 2.44x / 1.64x / 1.25x the JVM path p50.
- A native C SHA-256 accelerator was evaluated and rejected: it was slower than
  the JVM SHA-NI path, so it is not shipped.
- Bound DoH response reads to 64 KiB before DNS parsing.
- Make the JS timer capability test harness thread-safe; production behavior is unchanged.
- Windows x86_64 is built and run; Windows arm64, Linux and macOS are
  cross-compiled and artifact-checked. Android continues to use Kotlin/ART.

## 0.5.0.0 — resume and HTTP reliability

- Store file identity with each progress snapshot to detect a replaced destination. Legacy snapshots without identity are sampled before being adopted; if sampling fails, the old data is retained. Sampling is not full-file proof.
- Add an opt-in policy that stops and retains saved progress when resume verification is unavailable. Sampling cannot establish full-file identity; provide a trusted SHA-256 when possible.
- Isolate structurally invalid mirrors and plugin capability failures; stop in-flight requests when a download reaches a terminal failure.
- Improve connection reuse, short-file startup and tail assistance. Batch CLI jobs share one client and its built-in HTTP connection and speed budgets.
- Support one initial `EXT-X-MAP` for HLS fMP4 byte concatenation, with bounded playlist reads and workers. Live streams, changing maps, discontinuities and DRM remain unsupported.

## 0.4.1.0 — industrial reliability pass

This patch builds on the `0.4.0.0` correctness overhaul:

- HTTP `416` no longer marks an incomplete segment as successful. A transient rejection is retried; a persistent one carries an actionable failure and a changed source size invalidates the learned redirect.
- Dynamic tail segments are persisted as an ordered, validated immutable snapshot. An incomplete transient grid cannot replace the last durable resume point.
- Mirrors no longer overwrite each other's learned redirect target, and cross-origin mirrors/redirects do not inherit Cookie, Authorization or API-key headers. Proxy credentials remain separate.
- Task events are forwarded by one ordered consumer. `MERGING`, `COMPLETED` and `FAILED` now have explicit state-change events before their terminal events, so a host cannot receive completion before the lifecycle transition.
- Pause cleanup can no longer overwrite a completed/canceled/failed terminal state.
- `DownloadRequest.mirrors` moved to the end of the constructor and `JvmOverloads` keeps the legacy JVM constructor available; `component7/component8` retain the old field meanings.
- Concurrent tail-assist snapshots no longer race Kotlin's `size()`/iterator fast path at high concurrency; a c=256 stress run exposed and reproduced the `NoSuchElementException`.
- Adaptive down-probes now wait until in-flight work is actually at the new target. Previously, requests left over from the old tier inflated the probe window and could halve real throughput.
- The CLI's JSON completion event now reports the real file SHA-256 plus the resume `stableKey`; it previously put the 16-character stable key in the `sha256` field.
- The benchmark runner now computes a local SHA-256 and supports `--sha256` instead of trusting file size alone. Its adaptive scenario uses the production fast-start policy unless slow start is explicitly requested.

## 0.4.0.0 — correctness overhaul

This release is mostly **bug fixes**: thirteen defects surfaced by an internal audit, three of which could silently corrupt the output file.

| # | Defect | Consequence before the fix |
|---|---|---|
| 1 | Resume reused old segments when the content fingerprint could not be fetched **and** the strong validator had changed | a file swapped server-side could produce a length-valid but mixed old/new file |
| 2 | Whole-file fallback ignored `delete()` failures and never truncated | a shorter re-download kept the previous file's tail |
| 3 | Multiple workers wrote the progress sidecar concurrently through one `.tmp` | interleaved snapshots, lost resume points |
| 4 | Sidecar restore validated single ranges only; `reset()` left the `.bak` behind | a corrupt snapshot could claim "already complete"; discarded progress came back |
| 5 | The "global" speed limit was one token bucket **per task** | 3 tasks × 1 MB/s = 3 MB/s |
| 6 | `resume()` never completed its `Deferred` on pause and never set `FAILED` on error | `await()` hung forever; progress stuck at DOWNLOADING |
| 7 | Back-pressure could not go below 4 connections | servers allowing 1–3 connections always failed |
| 8 | An adaptive down-shift permanently lowered the ceiling | after the network recovered, the engine never retried 16/32 |
| 9 | `connectTimeout`/`readTimeout` do not cover DNS | a hung resolver stalled the task indefinitely |
| 10 | A `206` without a parseable `Content-Range` was accepted | bytes from the wrong offset could be written |
| 11 | Warm-up ran before the global connection budget was acquired | warm-up bypassed `maxTotalConnections` |
| 12 | `SpeedLimiter` read the limit once outside its wait loop | changing the limit mid-download had no effect for seconds |
| 13 | Every `5xx` counted as throttling | spurious concurrency cuts and misleading "持续被限流" errors |

Each fix is covered by a regression test (`RegressionFixesTest.kt`) plus two end-to-end mirror tests (`MirrorPoolTest.kt`).

## Nine engine capabilities

| Capability | Config field |
|---|---|
| Global speed limit | `globalSpeedLimitBytesPerSec` (token bucket, 0 = unlimited) |
| Thread count (up to 256) | `maxConnectionsPerTask` (1..256) |
| Concurrent tasks | `maxConcurrentTasks` (1..64) |
| Global connection budget | `maxTotalConnections` (0 = unlimited) |
| Final SHA-256 | `DownloadRequest.expectedSha256` (optional 64-hex digest) |
| Max download retries | `maxRetries` (0..50) |
| Dynamic segmentation | `dynamicSegmentation` (true/false) |
| Manual/auto proxy | `proxy = Direct / System / Manual(HTTP,SOCKS,auth) / Pac(url)` |
| DNS configuration | `dns = System / StaticHosts / DoH(url)` |
| Ignore SSL | `trustAllCerts` |
| Mirror sources (same file) | `DownloadRequest.mirrors` (speed-weighted selection + failover) |
| Theming | handled by the upper-layer app (the SDK does not deal with UI rendering) |

## Quick start

```kotlin
import dev.turbodl.core.*
import java.io.File

val client = TurboClient(
    TurboConfig(
        maxConnectionsPerTask = 16,
        globalSpeedLimitBytesPerSec = 0,        // unlimited
        dynamicSegmentation = true,
        proxy = ProxyMode.Direct,
        dns = DnsMode.System,
    )
)

// Submit a task
val id = client.submit(
    DownloadRequest(
        url = "https://example.com/big.zip",
        destination = File("big.zip"),
    )
)

// Observe progress events
scope.launch {
    client.events.collect { event ->
        when (event) {
            is TurboEvent.Progress -> println("${event.progress.percent}%  ${event.progress.speedBytesPerSec} B/s")
            is TurboEvent.Completed -> println("done: ${event.file}")
            is TurboEvent.Failed -> println("failed: ${event.reason}")
            else -> {}
        }
    }
}

// Suspend until finished
val result = client.await(id)   // Result<File>

// Controls
client.pause(id)
client.resume(id)                 // legacy synchronous compatibility
// client.resumeAsync(id)         // preferred from Android/UI coroutines
client.cancel(id, deleteOutput = true)

client.shutdown()
```

For the built-in HTTP backend, hosts can call
`client.submit(request, UnverifiableResumePolicy.FAIL)` to fail and retain
saved progress when resume verification cannot be performed. Unsupported
custom backends reject this policy. A matching sampled fingerprint does not
guarantee complete file integrity; supply `expectedSha256` when available.

## Task-level policy and details (0.5.0.3)

The existing `submit(...)` overloads keep their original behavior. For one task,
build an immutable `TaskTransferPolicy` with `TaskTransferPolicy.builder()` and
call `submitWithPolicy(...)`. The client snapshots task-level segment size,
block size, segments per connection, slow-start settings, adaptive-concurrency
mode and per-host connection cap at submission; later changes to `TurboConfig`
do not rewrite that task. Global `maxTotalConnections` and
`globalSpeedLimitBytesPerSec` remain client-wide limits.

`backpressureGroup` is optional. Shared source/group cooldown currently applies
only to the built-in HTTP backend; different sources or groups do not share it.
`backpressureConsecutiveFailures = 0` still turns backpressure off, and setting
a group does not by itself reduce concurrency.

```kotlin
val policy = TaskTransferPolicy.builder()
    .minSegmentSize(4L * 1024 * 1024)
    .blockSize(1L * 1024 * 1024)
    .segmentsPerConnection(2)
    .slowStart(true)
    .slowStartInitial(2)
    .adaptiveConcurrencyMode(AdaptiveConcurrencyMode.DOWNWARD)
    .maxConnectionsPerHost(8)
    .backpressureGroup("origin-a") // optional
    .build()

val id = client.submitWithPolicy(
    DownloadRequest(
        url = "https://example.com/big.zip",
        destination = File("big.zip"),
    ),
    policy,
)

// Existing entry point and behavior are unchanged.
val legacyId = client.submit(
    DownloadRequest(
        url = "https://example.com/legacy.zip",
        destination = File("legacy.zip"),
    ),
)

val details = client.taskDetails(id)
if (details != null) {
    println("phase=${details.phase}")
    println("active HTTP requests=${details.activeRequests}, peak=${details.peakActiveRequests}")
    println("request count=${details.requestCount}, received=${details.receivedBytes}")
}
```

When request metrics are available, `activeRequests` and `peakActiveRequests`
count in-flight HTTP calls, not physical TCP sockets. If
`requestMetricsAvailable` is `false`, do not treat `activeRequests`,
`peakActiveRequests` or `requestCount` as valid. These counters currently come
from the built-in HTTP backend; HLS, JS-plugin, native and other paths do not
all share them.

## Command line

`turbodl-cli` is a full-featured, Agent/script-friendly downloader (multi-threaded, resumable, JSON output).

Build the standalone fat JAR:

```
./gradlew :turbodl-cli:fatJar
# => turbodl-cli/build/libs/turbodl-cli-all.jar
java -jar turbodl-cli-all.jar --help
```

Common usage:

```
# Basic (default file name from URL)
turbodl <url> -o out.bin -c 64

# Through a proxy, with encrypted DNS, speed limited to 10MB/s
turbodl <url> -p http://127.0.0.1:7890 --doh https://dns.alidns.com/dns-query -l 10MB

# Machine-readable NDJSON events (start/progress/completed/failed) for scripts & agents
turbodl <url> --json

# Batch download (each line: <URL> [output]); one client shares global limits
turbodl --batch tasks.txt -c 128 --jobs 4

# Custom headers / UA / ignore TLS
turbodl <url> -H "Cookie: k=v" -A "MyAgent/1.0" --insecure
```

Notes:
- Interrupted downloads resume automatically when re-running the same command (segments kept in `.turbodl-parts/` next to the output, cleaned up after merge). Batch tasks share one client, so `--max-total-connections` and `-l/--limit` apply to the whole batch; `--jobs` controls task concurrency.
- Exit codes: `0` success, `1` download failed, `2` usage error.
- `--json` emits one JSON object per line; the final `completed` line contains the absolute file path and size.

## NPM wrapper (`@turbodl/cli`)

A thin Node.js shell around the JVM CLI (no download logic in JS — it auto-downloads and caches the JAR on first run, then passes all args through):

```bash
# After publishing:
npm install -g @turbodl/cli      # then: turbodl <url> -o out -c 64
npx @turbodl/cli <url> --json    # zero-install, agent-friendly NDJSON output

# Requires Java 17+; jar cached in ~/.turbodl/; TURBODL_JAR overrides the jar path.
```

Source: [`npm/cli`](npm/cli). Published as **@turbodl/cli** on npm.

## Build

```
./gradlew build        # compile + run unit tests
```

Unit tests use an embedded HTTP(Range) server and cover: multi-threaded byte-level correctness, dynamic segmentation, fallback when Range is unsupported, fallback when Range is tampered, transient 503 retrying only the affected segment, and global speed limiting.

## Modules

- `turbodl-core`: the download engine SDK (the published library, usable standalone, no plugin-framework dependency).
- `turbodl-cli`: command-line example demonstrating SDK usage.
- `turbo-plugin-runtime`: **optional** plugin runtime kernel (lifecycle / disposer / event bus / service registry / extension points / version handshake / diagnostics). core does not depend on it; when not included, core works as usual.
- `turbo-plugin-bootstrap`: **optional** bootstrap module for one-click wiring of base plugins (Kotlin loader + HTTP backend); not a mandatory dependency.
- `turbo-plugin-hls`: **optional** HLS VOD protocol adapter plugin — resolves master/media M3U8 playlists, downloads segments concurrently (with per-segment retry), decrypts AES-128, honors EXT-X-BYTERANGE, and returns ordered parts for the engine to merge. Registers itself as a routed `DownloadBackend`; unsupported constructs (live streams, DRM/SAMPLE-AES, fMP4 EXT-X-MAP, discontinuities) fail explicitly instead of producing corrupt output. It also declares the `hls` protocol (manifest `protocols` + a load-time `ProtocolClaim`; the declaration grants no permission and routes nothing — `.m3u8` stays the `DownloadBackend` predicate's job).
- `turbo-plugin-js`: **optional** JavaScript plugin loader — loads TurboDL plugins written in JavaScript. Embeds QuickJS (one runtime and one context per script) and exposes a stable `host`/`plugin` ABI with coarse-grained capabilities (http, crypto, log, storage, env, time, timers). Bridges JS link parsers and task hooks into the ordinary extension points, and unloads through an `ACTIVE → STOPPING → DRAINING → DISPOSED` sequence that never closes a runtime while its script is still executing. Only this module pulls in a JS engine; leave it out and nothing about QuickJS reaches your classpath.
- `turbo-plugin-parser`: **optional** link-parser plugin — turns a share link from a video/social platform into concrete download requests. Ships parsers for Weibo (MP4 + image sets), Xiaohongshu (watermark-free video + original images) and Pixiv (every page's `urls.original`). Registers one `LinkParser` at `LINK_PARSER` plus one `ProtocolClaim` per platform at `PROTOCOL_HANDLER`; core and runtime are untouched. Holds no credentials — where a platform needs a login it returns `null` and the host's own session handling takes over. Its public result type (`ParsedPost`) is deliberately path-free, because a `DownloadRequest` needs a `destination: File` while a parser runs *before* the host has chosen a directory; the temp-dir destination is filled in only at the boundary.
- `demo`: three runnable examples — Kotlin-native plugin, bootstrap usage, and a shim-adapter template. Run with `./gradlew :demo:run --args="1"` (or `2`, `3`, `all`).

## Plugin framework (optional)

TurboDL is a standalone engine **and** an optional plugin platform. Three ideas drive the design:

1. **Core works alone.** `turbodl-core` is a complete multi-threaded engine with zero plugin dependency. Plugins are strictly additive.
2. **Everything is a plugin; the kernel is only mechanism.** The runtime kernel knows nothing about any protocol — it provides only lifecycle, cleanup (disposer), a service registry, a type-safe event bus, an extension-point registry, a version handshake, and diagnostics. The Kotlin loader, the HTTP backend and the HLS backend are all ordinary plugins.
3. **Hybrid A+B.** Core ships a built-in HTTP backend (A); a plugin backend can override it or add protocols through the registry (B) — without core ever depending on the runtime. Dependency direction is strict: `runtime → core`, never the reverse.

A versioned public API (`ApiVersion`, currently `1.0.0`) plus a load-time handshake means a future breaking core release fails loudly (a plugin is marked `INCOMPATIBLE` and never loaded) instead of silently corrupting behavior.

Plugin documentation:
- [Plugin authoring guide](docs/plugins/README.md) — how to build and integrate a plugin.
- [Development Convention](docs/plugins/CONVENTION.md) — the official compatibility rulebook (stable API, versioning, naming, safety).
- [Plugin Market](docs/plugins/MARKET.md) — publish/discover plugins via GitHub topics + a `turbodl-plugin.json` manifest.
- [Manifest schema](docs/plugins/turbodl-plugin.schema.json) — JSON Schema for `turbodl-plugin.json`.

### Plugins written in JavaScript

Adding `turbo-plugin-js` lets a plugin be a script instead of a JVM class:

```kotlin
val boot = TurboBootstrap.create(
    config = TurboConfig(maxConnectionsPerTask = 16),
    extraPlugins = listOf(HlsPlugin(), JsPluginLoaderPlugin()),
)

boot.host.loadSource(PluginSource(kind = "js", uri = "/opt/turbodl-plugins/acme-parser.js"))
```

A script gets a `host`/`plugin` ABI and nothing more: it computes URLs, headers and signatures,
reads and writes its own sandbox directory, and logs. It never touches the download data stream and
cannot register a `DownloadBackend` or publish a host service — that boundary is what keeps a script
from becoming a bandwidth bottleneck. Scripts are local files (or `inline:` / `data:` sources);
`require`, npm and remote `fetch` are not part of the ABI.

The loader owns one QuickJS runtime and one context per script, and unloads in four stages so a
runtime is never closed while its JavaScript is still running. See
[`turbo-plugin-js/README.md`](turbo-plugin-js/README.md) for the ABI reference and
[`turbo-plugin-js/ARCHITECTURE.md`](turbo-plugin-js/ARCHITECTURE.md) for why it is shaped that way.

## Design notes & acknowledgements

TurboDL's design draws on the ideas of the following open-source projects (ideas only, **no source copied**), with thanks:
[aria2](https://github.com/aria2/aria2), [Xtreme Download Manager](https://github.com/subhra74/xdm), [axel](https://github.com/axel-download-accelerator/axel), [Persepolis](https://github.com/persepolisdm/persepolis), [Motrix](https://github.com/agalwood/Motrix), [ab-download-manager](https://github.com/amir1376/ab-download-manager).

## License

[MIT License](LICENSE), with "plugin-ecosystem supplemental terms" (clarifying that plugins are independent works, may be licensed freely, and are not considered derivative works merely by interacting through public APIs / extension points).

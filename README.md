# TurboDL

> High-performance multi-threaded download engine SDK — pure Kotlin/JVM, no Android dependency, embeddable in any JVM application (including Android).

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

**Languages:** English · [简体中文](docs/i18n/README_zh-CN.md) · [繁體中文](docs/i18n/README_zh-TW.md) · [日本語](docs/i18n/README_ja.md) · [한국어](docs/i18n/README_ko.md) · [Deutsch](docs/i18n/README_de.md)

TurboDL is a download core written from scratch. It **only draws on the architectural and algorithmic ideas** of mature download managers (aria2, IDM/XDM, axel, Persepolis, Motrix, ab-download-manager) **without copying any of their source code**, and is therefore released under the permissive **MIT license (with supplemental plugin-ecosystem terms)**, free to use in open-source or commercial projects.

## Features

- **Multi-threaded segmented download**: HTTP Range parallel segments, connection reuse (HTTP/2 multiplexing + keep-alive).
- **Dynamic segmentation**: fine-grained pre-splitting + work stealing, so slow connections don't drag down the whole transfer and the "last segment finishing on a single thread" long tail is eliminated (IDM/XDM idea).
- **Sequential segment priority**: earlier segments are downloaded first, enabling progressive preview/playback (axel/Persepolis idea).
- **Robust fallback & retry**:
  - Server does not support / ignores Range → automatic fallback to whole-file single-stream download;
  - A bad/timed-out segment → **only that segment is retried**, the whole task is not discarded;
  - Verifies that the actually returned bytes match the requested Range (guards against servers tampering with Range and returning the whole file).
- **Restrained adaptivity**: concurrency is throttled down **only** on 429/503 or when consecutive failures hit a threshold; normal network jitter **never** reduces the thread count (no AIMD jitter heuristics).
- **Resumable downloads**: segments are persisted; pause/resume continues from the real on-disk progress.
- **Byte-level integrity check**: total size is verified after merge, rejecting corrupted files.

## Nine engine capabilities

| Capability | Config field |
|---|---|
| Global speed limit | `globalSpeedLimitBytesPerSec` (token bucket, 0 = unlimited) |
| Thread count (up to 256) | `maxConnectionsPerTask` (1..256) |
| Concurrent tasks | `maxConcurrentTasks` (1..64) |
| Max download retries | `maxRetries` (0..50) |
| Dynamic segmentation | `dynamicSegmentation` (true/false) |
| Manual/auto proxy | `proxy = Direct / System / Manual(HTTP,SOCKS,auth) / Pac(url)` |
| DNS configuration | `dns = System / StaticHosts / DoH(url)` |
| Ignore SSL | `trustAllCerts` |
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
client.resume(id)
client.cancel(id, deleteOutput = true)

client.shutdown()
```

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

# Batch download (each line: <URL> [output])
turbodl --batch tasks.txt -c 128

# Custom headers / UA / ignore TLS
turbodl <url> -H "Cookie: k=v" -A "MyAgent/1.0" --insecure
```

Notes:
- Interrupted downloads resume automatically when re-running the same command (segments kept in `.turbodl-parts/` next to the output, cleaned up after merge).
- Exit codes: `0` success, `1` download failed, `2` usage error.
- `--json` emits one JSON object per line; the final `completed` line contains the absolute file path and size.

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
- `turbo-plugin-hls`: **optional** HLS VOD protocol adapter plugin — resolves master/media M3U8 playlists, downloads segments concurrently (with per-segment retry), decrypts AES-128, honors EXT-X-BYTERANGE, and returns ordered parts for the engine to merge. Registers itself as a routed `DownloadBackend`; unsupported constructs (live streams, DRM/SAMPLE-AES, fMP4 EXT-X-MAP, discontinuities) fail explicitly instead of producing corrupt output.
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

## Design notes & acknowledgements

TurboDL's design draws on the ideas of the following open-source projects (ideas only, **no source copied**), with thanks:
[aria2](https://github.com/aria2/aria2), [Xtreme Download Manager](https://github.com/subhra74/xdm), [axel](https://github.com/axel-download-accelerator/axel), [Persepolis](https://github.com/persepolisdm/persepolis), [Motrix](https://github.com/agalwood/Motrix), [ab-download-manager](https://github.com/amir1376/ab-download-manager).

## License

[MIT License](LICENSE), with "plugin-ecosystem supplemental terms" (clarifying that plugins are independent works, may be licensed freely, and are not considered derivative works merely by interacting through public APIs / extension points).

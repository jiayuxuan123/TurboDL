# TurboDL

> 🌐 本文件為英文原版內容的佔位複製，翻譯將於後續迭代完善。

**語言：** [English](../../README.md) · [简体中文](README_zh-CN.md) · 繁體中文 · [日本語](README_ja.md) · [한국어](README_ko.md) · [Deutsch](README_de.md)

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

```
./gradlew :turbodl-cli:installDist
./turbodl-cli/build/install/turbodl-cli/bin/turbodl <url> [output] --threads 16 --limit 0 [--insecure] [--no-dynamic]
```

## Build

```
./gradlew build        # compile + run unit tests
```

Unit tests use an embedded HTTP(Range) server and cover: multi-threaded byte-level correctness, dynamic segmentation, fallback when Range is unsupported, fallback when Range is tampered, transient 503 retrying only the affected segment, and global speed limiting.

## Modules

- `turbodl-core`: the download engine SDK (the published library, usable standalone, no plugin-framework dependency).
- `turbodl-cli`: command-line example demonstrating SDK usage.
- `turbo-plugin-runtime`: **optional** plugin runtime kernel (lifecycle / disposer / event bus / service registry / extension points / diagnostics). core does not depend on it; when not included, core works as usual. *(under construction, currently a scaffold)*
- `turbo-plugin-bootstrap`: **optional** bootstrap module for one-click loading of base plugins; not a mandatory dependency. *(under construction, currently a scaffold)*
- `demo`: framework usage examples, not intrusive to the core source. *(under construction, currently a scaffold)*

## Design notes & acknowledgements

TurboDL's design draws on the ideas of the following open-source projects (ideas only, **no source copied**), with thanks:
[aria2](https://github.com/aria2/aria2), [Xtreme Download Manager](https://github.com/subhra74/xdm), [axel](https://github.com/axel-download-accelerator/axel), [Persepolis](https://github.com/persepolisdm/persepolis), [Motrix](https://github.com/agalwood/Motrix), [ab-download-manager](https://github.com/amir1376/ab-download-manager).

> A plugin / extension framework is planned for a later iteration; the current version of this repository does not include a plugin system.

## License

[MIT License](../../LICENSE), with "plugin-ecosystem supplemental terms" (clarifying that plugins are independent works, may be licensed freely, and are not considered derivative works merely by interacting through public APIs / extension points).

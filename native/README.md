# TurboDL native fast path

The SDK core remains Kotlin/JVM. These native components are optional and are
shipped only where A/B tests prove a benefit.

## Components

- `launcher/` — Zig starter. Instant process start, native `--version`, and
  hand-off to either the Kotlin/JVM engine or the native data plane.
- `dataplane/` — Zig HTTP Range data plane. Range requests, retries, positional
  writes, resumable sidecar, and final SHA-256.
- `ab_native_vs_jvm.py` — interleaved A/B harness used to decide whether a
  native path is allowed into a release.

The candidate C SHA-256 accelerator was measured against the JVM's SHA-NI path
and rejected: it was slower on the same file. It is not part of this module or
any release.

## Build

Requires Zig 0.14.x:

```powershell
pwsh native/build-all.ps1 -Zig C:\path\to\zig.exe
```

Individual components:

```powershell
zig build -Doptimize=ReleaseFast              # run in native/launcher
zig build -Doptimize=ReleaseFast              # run in native/dataplane
zig build -Dtarget=x86_64-linux-gnu -Doptimize=ReleaseFast
```

## Usage

```text
turbodl <url> ...          # full Kotlin/JVM engine (default)
turbodl native <url> ...   # native simple-HTTP fast path
turbodl --version          # native instant response
```

The native fast path accepts positional URL, `-o/--output`, `-c/--connections`,
`-r/--retry`, `--sha256`, `--known-size`, `--json`, `--quiet`, and `--workdir`.
Everything else (batch, mirrors, proxy, HLS, plugins) uses the JVM engine.

## Platform status

- Windows x86_64: built, run, A/B verified.
- Windows arm64, Linux x86_64/arm64 (glibc/musl), macOS x86_64/arm64/universal2:
  cross-compiled and artifact-checked; target-platform runtime is not yet claimed.
- Android: the Kotlin/ART engine is the supported path. A bare sidecar is not an
  Android process model.

## A/B results (0.5.0.1 baseline)

| Fixture | Native p50 | JVM p50 | Result |
|---|---:|---:|---:|
| 32 MiB | 577.8 ms | 1412.0 ms | 2.44x |
| 256 MiB | 2534.7 ms | 4158.4 ms | 1.64x |
| 2 GiB | 27250.8 ms | 34080.7 ms | 1.25x |

Every run independently verified the output SHA-256. These are the historical
0.5.0.1 measurements, not a new speed claim for later versions.

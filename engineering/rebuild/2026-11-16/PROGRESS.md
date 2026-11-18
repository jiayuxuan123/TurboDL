# Shared-core rebuild execution record

Updated: 2026-11-17 (Asia/Shanghai). Overall: **NOT COMPLETE**.
Completion requires the entire accepted rebuild, YunGet integration, fair
comparisons and both formal releases. Local fixture results are not parity.

## 2026-11-17 scheduler and measurement iteration

Latest candidate: Windows x64 Release DLL SHA-256
`baa5fd552fdedf06bf09e74c252f96e1e9dc56188a2c6916f2ab4bc69e60a5a6`.
`local-serial-20261117-v2/gate.json`: **90 tests passed, zero skips/failures,
90.362 seconds**. The serial comparison phase is now active in that directory;
its manifest records driver hashes and matched input/verification configuration.
Do not rebuild the DLL or run other load tests during comparison.

Two new portability findings are included: Windows extended paths are resolved
with the native file APIs, including existing-parent canonicalization; logical
checkpoint paths keep their prior representation. The old filesystem helper
could also turn an extended `C:\\...` path into a drive-relative `C:...` path.
An independent long-path transfer, unowned-file preservation and media tests
cover the correction. Media pause fixtures now hold a partial response with
heartbeats until a sibling is verified, instead of racing against completion.
Failed earlier full runs remain archived and are not counted as passes.

User tightened the release gate: each applicable old-SDK workload must be
outperformed before release; see TARGETS-2026-11-17.md and acceptance.json.
The previous production versions remain unchanged.

Implemented media orchestration now passes 13 local transfer cases: HLS plain,
rotating AES keys, initialization/byte ranges, audio/video selection and muxing,
DASH templates, redirects, live end-list/explicit finish, in-process resume,
engine recreation and retrying only target publication. Output packets are
independently inspected/decoded. Media/platform acceptance remains open.

The first comparison exposed substantial native regressions. Diagnostic results
and all unsuccessful runs are kept externally, including incorrect reference
adapter attempts. The original SDK/native size and hash inputs differed; the
new matched track passes unknown size and expected SHA-256 to both. Native
measurement now uses a standalone public-ABI host, so Python CPU/RSS no longer
contaminates native resource figures. The old SDK JAR remains frozen; only its
external measurement driver changed. Other reference completion semantics are
recorded separately from the matched SDK track.

New native changes:

- Dispatch immediately after verified response headers; multi-connection
  discovery uses a 1-byte range. A header/body gate reproduced the old delay.
- Stream through alternating disk buffers; size them to the request and charge
  actual payload leases. A small process budget still restricts real requests.
- Checkpoint after 16 MiB or one second, plus forced lifecycle checkpoints.
  Preserve file synchronization before SQLite durability and block verification.
- Redistribute a measured slow single-source tail using observed source speed;
  the original response can commit only before its assigned cut. Verified
  pause/recreation without a supplied full digest after reassignment.
- Retry allowances belong to missing ranges. Several independent connection
  failures no longer exhaust one shared task counter. A reused dead connection
  gets one immediate fresh-connection retry; repeated failures retain limits.

Matched SDK round 4 retained all full-file hashes. Windows x64 SSD, 5 pairs per
case with noisy cells extended to 9: single 474/552 ms, unlimited 573/455 ms,
paced 1482/1533 ms, latency 532/606 ms, tail 70/467 ms, straggler 436/507 ms
(native/old SDK medians). Only single, latency and tail had a paired CI upper
bound below 1.00. Native RSS medians were 12-28 MiB versus 180-202 MiB for the
persistent JVM host. That round preceded the dead-connection recovery change;
it is development evidence and does not authorize release.

Gopeed 1.9.3 REST adapter now uses the pinned upstream `opts` contract, verifies
the runtime version, binds localhost, uses synthetic API authentication and an
owned output allowlist. Single/16-connection smoke downloads pass exact hashes.
It removes only task records before the controller cleans verified output.

Latest full gate is running through run_local_gate.py. An earlier tool-owned
run stopped without a final unittest summary and is not counted as passed.
The runner writes an explicit result JSON and keeps all generated files in an
owned D-drive temporary subtree. Do not start performance work concurrently.

## Workspace

- TurboDL: `codex/native-engine-rebuild`, based on `3d10e67`.
- Production baselines: TurboDL 0.5.0.6 and YunGet 2.7.39.
- Core: `native/engine`; JVM adapter: `turbodl-native`.
- Sources, binaries and logs: `D:/CodexResearch/turbodl-rebuild-20261116`.
- No new version bump, release, push or production deployment for this rebuild.
- DeepSeek-V4.1 Flash is unavailable in the current model list; no substitute
  model was dispatched under its name.
- User confirmed no available Android/Apple devices. Device gates remain blocked.

## Implemented and exercised locally

- Frozen source archives and hashes of 30 published release artifacts.
- Versioned/size-checked C ABI, copied UTF-8 inputs, explicit lifetime and batched
  JNI snapshots/events. No file payload crosses JNI.
- libcurl multi reactor, disk queue, bounded payload admission, independent
  request windows and persistent disjoint extents.
- SQLite FULL/WAL checkpoint follows file synchronization; saved block hashes
  reject modified files without requiring a caller-supplied full hash.
- Pause/resume, process recreation, exact test-child kill, invalid Range,
  short reads, whole-file fallback, source refresh and task retirement.
- Process-wide task/request/buffer admission and shared download rate cap.
- Mirrors with independent headers, redirect state and origin cooldown. Mixing
  requires a supplied SHA-256. One failed/cooling origin does not block another.
- Real TLS custom-CA and hostname checks; FTP login; SFTP known-host rejection.
- Real local HTTP/2 TLS/ALPN and HTTP/3 QUIC transfers, complete independent hashes
  and negotiated-version assertions. Compiler feature strings are not evidence.
- HTTP diagnostics expose negotiated protocol, streams, connection IDs/new
  connections, request timing sums and task time to first body byte. These are
  not packet-capture, CPU or RSS measurements.
- Torrent/magnet metadata and transfer, file selection, task/global rate caps,
  mixed HTTP/BT admission, bounded seeding cancellation and resume.
- BT changed-piece regression found and fixed false completion: force_recheck
  is ignored during initial resume checking. Now it is requested after the
  initial torrent_checked alert, and completion awaits the subsequent check.
- BT final checkpoint waits for pause acknowledgement and a fresh flushed
  resume record; a previous periodic snapshot cannot acknowledge pause/cancel.
- Metalink v3/v4, explicit file selection, priority, relative URLs, SHA-256,
  expected length and mirrors use the same HTTP lifecycle. XML names never
  select local output paths. DTD/entities, excess nesting and unhashed mixing
  are rejected. Protected Metalink source policy is still incomplete.
- CLI regular/Metalink downloads and torrent/magnet submission. Windows CLI
  and JNI Unicode paths are tested. JVM exposes BT, Metalink and HTTP metrics.

## Latest evidence

`native-checkpoint-regression.log`: **70 tests passed in 76.412 seconds**.
This is the current native development suite, not the legacy SDK release gate.
The earlier protocol/JNI results below overlap that run; do not add counts again:

- `http2-http3-regression.log`: 2 real negotiation checks.
- `metalink-regression.log`: 8 parser and complete-download checks.
- `bt-recheck-green2.log`: modified-piece and normal resume checks.
- `jvm-expanded-api-build.log`: Kotlin/Java compilation successful in 46 seconds.

JNI HTTP, magnet and Metalink transfers pass. `jni-metalink-regression.log`
checks that host concurrency policy survives JNI as well as complete output SHA.
`jvm-metalink-api-build.log` confirms the latest Kotlin/Java signatures compile.

FFmpeg 9.0.2 libraries now build/install with the pinned compiler, LGPL options
and networking disabled. The adapter fixes GNU PE linker/dlltool and MSYS/native
make path mismatches. This is dependency-build evidence, not HLS/DASH playback.

## Latest candidate after that complete run

- FIFO task tickets prevent another client overtaking a waiting client, and
  canceled waiters are removed. This is included in the 70-case run.
- HLS manifest component: `hls-manifest-regression.log`, 9 case groups pass.
  Covers master/audio groups, maps, byte ranges, rotating AES IVs, live/gaps
  and malformed/unsupported inputs. No HLS transfer/playback claim yet.
- Reactor candidate now wakes on commands, disk completion, resources and BT
  alerts instead of unconditional 5 ms polling; it fills available HTTP windows
  in one dispatch. Timers remain for retry, throttling and BT status.
- `reactor-wakeup-regression.log`: 29 HTTP/lifecycle/admission checks pass in
  27.443 seconds, including idle submit/destroy wakeup.
- The first event-driven protocol gate exposed a magnet-selection race (one
  failure). The fix waits for file_prio_alert and checks the actual selected
  priorities plus full wanted-byte progress before finishing. Ten immediate
  repetitions pass; the complete 70-case run includes this regression.
- Source refresh reproduced old Basic credentials leaking to a new origin.
  Credentials/SSH keys are now pinned to the initial primary origin. Cross-
  protocol refresh cannot bypass SFTP trust or downgrade HTTPS. Same-origin
  authentication, changed-origin isolation and SFTP bypass rejection pass.
- Empty Range representations and chunked empty files now complete with the
  correct hash/size; a previously partial file becoming empty is rejected while
  keeping its durable bytes. Completed unknown-length transfers report size.
- Source cooldown deadlines now wake the reactor rather than adding an idle
  polling second. Fixture caller scopes are isolated when reusing local ports.
- These changes pass the complete 70-case gate; HLS parser remains 9 separate
  component case groups, not extra protocol transfers.
- Energy/throughput benefit has NOT been measured for this candidate. Fair A/B
  and held-out reference acceptance remain open.

## Required work still open

1. Remaining HLS/DASH edge cases and target-platform validation beyond the
   locally exercised orchestration, AES, live, recovery and playable output.
2. ED2K implementation/interop; complete Metalink protected-source handling.
3. Adaptive/tail scheduling, adverse-link mirrors, identity, proxy/DNS/IPv6/system
   trust, BT DHT/tracker interoperability and discovery/socket overhead budgets.
4. Actual Android/Apple/Linux artifacts; old API/plugin bridge and YunGet targets,
   source refresh and diagnostics. Analytics API/storage migration is deferred
   to this app phase; see APP-INTEGRATION.md. Do not add another D1 event table.
5. ABI compatibility, sanitizers, parser fuzzing, independent default/common-cap
   comparisons, real networks, devices and 24-hour platform soaks.
6. Only after all gates pass: default switch and both formal releases, complete
   assets, websites and announcements.

## Reproduction and budget

CMake/Ninja: `D:/Android/sdk/cmake/3.31.6/bin/`.
JDK: `D:/env/jdk-21.0.12+8`; Java classes: `.../java-classes`.
Set TD_ENGINE_LIBRARY, TD_TORRENT_FIXTURE, TD_JAVA_HOME and TD_JAVA_CLASSES.
PYTHONPATH includes `.../deps/test-protocols` and its `pyftpdlib-2.2.0` folder.
Run: `python -m unittest discover -s native/engine/tests -p test_*.py -v`.
Test dependencies are hash-verified on D:, not installed globally.

Use only the existing 12 GiB append-only ledger. No new allowance was granted.
H2/H3 dependency setup/recovery is reconciled under four separate run IDs.
Official reference metadata and three isolated Windows archives are separately
reconciled. Official tags are pinned to commits; archive/executable hashes are
frozen in REFERENCES.json and ASSETS.json under the external cache. Gopeed and
Wenxi archives match publisher digests; aria2 has no publisher digest in that
release metadata, so only its local digest/ZIP CRC are claimed. Aria2/curl version-feature output and static Go module/build information are
saved in REFERENCE-CAPABILITIES.json. Protocol workloads and comparisons have
not yet run. Curl is separately pinned to its official tag and local binary.
Last balance: **1,281,711,436 bytes**, no open reservations. Read the live ledger
before another outbound experiment. Real-network/device gates remain mandatory.

## 2026-11-18 local validation

The November 17 v5 report records 90 successful tests for its recorded DLL.
The shared build directory contained a different DLL at the end of the run.
The serial runner now copies the executable and dependency set into the
evidence directory, checks source and artifact hashes around the gate, and
uses that same native build for the comparison. The local gate and comparison
use a process-owned OS lock.

`D:/CodexResearch/turbodl-rebuild-20261116/serial-durable-jni-20261118/gate.json`
records 91 integration cases and two component executables passing against
DLL `a6b4e0a4db4dc4cf80dbf63b635193631b22b9493f60cd21433792b8667227fe`.
The runner compiled JNI test classes from source and cleaned its scratch
directory. Later source changes require their own validation.

Recent core changes included in the candidate:

- a checkpoint for a finished transfer again synchronizes the file before
  committing SQLite. The checkpoint writer reuses its prepared statement,
  and final verification skips a redundant truncate when the size matches.
  Empty responses save their final length and reopen without another request;
- parallel HTTP discovery uses the configured initial window up to 1 MiB,
  while a single connection uses an open-ended range;
- slow-tail assistance starts after 50 ms of measured evidence, with the
  existing speed/startup cost guard;
- process-wide resource accounting resets safely only when the last engine
  owner leaves, preventing one crashed test from poisoning the next;
- 401/403 HTTP responses are reported as authentication failures instead of
  generic HTTP errors;
- bounded cooldown replacement no longer silently fails open at 1024 entries.
- the additive JVM HTTP entry point passes expected size, a byte slice,
  response size limits, a representation ID and per-source mirror headers.
  The original JNI submission entry point is still covered. Six JNI and
  credential checks and the Kotlin compilation passed.

ED2K P0 is now an independently tested component:
`native/engine/src/ed2k/` plus `native/engine/tests/ed2k_p0_test.cpp` cover
strict file-link parsing, percent-decoded safe names, MD4/SHA-1, ED2K part and
block boundaries, file hashes and AICH primitives. It is still **not** a
working ED2K network downloader; the capability table correctly keeps network
ED2K unimplemented until server/peer interoperability and recovery pass.

The November 17 comparisons remain development observations. Some compared
different actual request peaks, and the final iteration charged the reference
drivers for task retirement while skipping the candidate's last file sync.
The new run records retirement separately, measures through independent
verification, and assigns slow behavior to the first data connection even
after a one-byte probe. Raw observations and incomparable cells are retained.

The November 18 D-drive comparison completed 304 measured transfers with
matching size and SHA-256. Single stream was 713 ms native vs 404 ms SDK
(median through independent verification). Slow tail was 1,974 ms vs 1,020 ms,
with different observed request peaks. Several workloads exceeded the 15%
variation limit. The result does not pass the performance release gate.
The outer runner falsely reported an inspection shell as a leftover process.
The gate and comparison both verified their actual child and scratch cleanup.

The slow-tail follow-up lets one idle connection split a slow discovery
response covering the entire object, based on the measured origin startup
and remaining work. It allows a second handoff when a completed peer confirms
a higher rate. Both versions passed the exact 48 KiB regression, and pause
after a handoff retained the correct extents.

`ssd-repeat-tail-20261118` records 20 measured, independently verified
transfers. The slow-tail fixture took 341 ms native vs 479 ms SDK in five
matched pairs. The upper 95% ratio bound was 0.782 and both CVs were below
10%. The large slow-connection fixture had unequal request peaks. The
earlier SSD single-stream check had a lower native median (363 vs 469 ms)
but native variation exceeded 15% and results against mature references
were inconclusive or slower.

The JVM adapter also exposes media start, live finish and stage details.
The local JNI live fixture records all four segments, stops through that
interface, and independently decodes the expected output packets.

Android has a host-side build entry point for API 24, four ABIs and ELF 16 KiB
load-segment verification. Its dependency preflight reports missing
ABI-specific curl, TLS, and FFmpeg builds. No Android artifact was produced.

The Windows/JVM follow-up gate ran against the frozen DLL
`372ca53335d3f315b67ee86562c4c2dbd4c11bd16cc84e0c06f7ca0ca841f038`.
`D:/CodexResearch/turbodl-rebuild-20261116/serial-media-sdk-20261118/gate.json`
records 93 integration cases, zero skipped cases, and two passing component
executables. The source and binary hashes stayed unchanged during the gate.

The same DLL ran 224 SSD comparison samples. Every file matched the expected
size and SHA-256, and the runner verified shutdown and scratch cleanup.
Times in the table include independent output verification.

| Local case | Native median | SDK 0.5.0.6 median | SDK gate |
| --- | ---: | ---: | --- |
| Single stream | 344 ms | 399 ms | Excess variance after nine pairs |
| Uncapped, 16 configured | 345 ms | 468 ms | Different observed peaks and excess variance |
| Paced, 64 | 1,471 ms | 1,497 ms | Local pass, ratio upper bound 0.995 |
| Paced, 16 | 5,275 ms | 5,357 ms | Local pass, ratio upper bound 0.996 |
| High latency | 292 ms | 563 ms | Different observed peaks |
| Small slow tail | 321 ms | 407 ms | Local pass, ratio upper bound 0.803 |
| One slow connection | 406 ms | 459 ms | Different observed peaks |

On single stream, Wenxi measured 253 ms and Gopeed 272 ms. Both outperformed
the candidate. The curl cold-process baseline measured 217 ms and is recorded
separately. The matched 16-connection paced result is within the reference
elapsed-time threshold for all applicable persistent comparators. These are
development workloads. Default-settings, held-out, cross-platform, resource,
and real-network acceptance remain open.

The previous 2.7.39 YunGet stable and 0.5.0.6 TurboDL production artifacts
remain untouched. Android/Apple/Linux runtime artifacts, the remaining JNI
surface, old task/plugin migration and final YunGet integration are still open.

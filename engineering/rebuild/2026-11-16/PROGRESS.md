# Shared-core rebuild execution record

Updated: 2026-11-17 (Asia/Shanghai). Overall: **NOT COMPLETE**.
Completion requires the entire accepted rebuild, YunGet integration, fair
comparisons and both formal releases. Local fixture results are not parity.

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

1. HLS/DASH orchestration, AES/ByteRange, live/track selection, durable media state
   and valid, playable output through controlled transfers.
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

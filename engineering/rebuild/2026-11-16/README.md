# TurboDL shared-core rebuild

Status: implementation in progress, not a parity or release claim.

The accepted scope is HTTP(S), HLS/DASH, FTP/SFTP, BitTorrent/magnet,
Metalink and ED2K on Android, Windows, Linux, macOS and iOS. The production
baseline is TurboDL 0.5.0.6 and YunGet 2.7.39. The old JVM engine remains
the production default until every release gate is satisfied.

## Decisions

- C++20 owns task state, transfer scheduling, resource admission, durable
  checkpoints and diagnostics. libcurl multi provides network transfers.
- Kotlin/JVM and Swift are adapters. No per-buffer JNI calls. The C ABI
  uses versioned structs, opaque handles and explicit ownership.
- Existing tasks stay pinned to their original engine and checkpoint format.
  No active task is shared between the new core and the old engine.
- Native dependencies are externally cached, pinned and hash verified. No
  automatic dependency downloads during CMake configuration.
- All public-network experiments use the existing 12 GiB ledger. No new
  allowance was granted. Missing real-network and device evidence blocks
  parity claims, regardless of local test results.

## First implementation seam

Tests exercise only the public C ABI and an external HTTP fixture: submit,
query, events, pause, resume, cancel, process recreation and shutdown. The
fixture and Python independently compute content and SHA-256. Tests do not
read the checkpoint database or call private scheduler functions.

Development tests are not competitive performance evidence. The acceptance
manifest is frozen before competitive measurements, and includes failed,
unsupported and blocked cells. Platform build success is distinct from
platform runtime success.

## Release gates

See `acceptance.json` and `CAPABILITIES.csv`. Release requires all mandatory
rows to pass, five paired runs per performance cell (at most nine for noisy
cells), no integrity failures, 24-hour platform soak tests and actual Android
and iOS device evidence. No timeout, skipped test or zero-byte benchmark is
counted as success.

Large fixtures and archives belong under D:/CodexResearch. Short-lived local
test files are removed by the test harness. Credentials and signed URLs must
never appear in evidence.

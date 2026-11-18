# Native Engine Mechanism Review

Read-only review of mechanisms used by mature downloaders and gaps in the
native core. This is not a parity claim and does not replace runtime evidence.

## Sources inspected

- IDM segmentation documentation:
  https://www.internetdownloadmanager.com/support/segmentation.html
- Gopeed fixed source:
  `_research/wenxi-reference/28e7b457/source/native/gopeed`
  - `internal/protocol/http/fetcher.go`
  - `internal/protocol/http/asterlink_baidu_public.go`
  - `internal/protocol/http/asterlink_readable.go`
- Frozen reference binaries and hashes:
  `D:\CodexResearch\turbodl-rebuild-20261116\references`
- Current native core:
  `native/engine/src/{reactor,resources,storage,media,torrent}.{cpp,hpp}`

## Mechanisms worth adopting

### Dynamic segmentation and worker reuse

IDM describes splitting the largest remaining segment as connections become
available and reusing established connections instead of reconnecting for every
range. Gopeed uses the same idea for slow tails: a worker that has finished can
split another worker's unread tail, with a minimum split size. Its fixed source
shows `helpOtherConnection`, a 1 MiB help threshold, and a smaller tail
threshold for constrained profiles.

Current native core:

- `assist_tail` measures source speed and startup cost, and splits a slow
  request after a real byte/time window.
- It starts a new `Request` with a new curl easy handle rather than lending an
  existing worker handle.
- The initial parallel discovery request is now one byte for unknown size, so
  small slow-first files no longer download their whole prefix on the slow path.

Next priority:

1. Keep a bounded per-task pool of curl easy handles and reset/reuse a handle
   after a completed range. Preserve the multi connection cache and avoid
   reconnecting when the same origin and credentials remain valid.
2. Add a real worker-stealing operation that splits the largest unread extent
   and keeps the helper's handle alive.
3. Test connection reuse with a local server that records TCP connection IDs,
   plus byte-for-byte SHA checks and pause/recreation.

### Slow start and response-driven concurrency

Gopeed's split/readable logic separates a discovery/metadata phase from the
worker phase. Its connection count is effective, not simply the requested
number. The current native core starts all configured connections immediately
after the first verified response header.

Next priority:

- Add a ramp policy for real origins: 1, 2, 4, 8, then the configured ceiling,
  advancing only after a verified response and useful byte progress.
- Treat `429`, `503`, `Retry-After`, `403`, and repeated connection resets as
  source-scoped capacity signals. Keep independent mirrors eligible.
- Keep the configured ceiling as a hard limit; do not silently reinterpret a
  user's requested connection count.

### Readable verified ranges

Gopeed exposes `ReadableRanges` from persisted chunk state. The current C ABI
exposes progress and durable byte counts but not the verified extents. Hosts
that want progressive playback or direct target access cannot consume a
continuous readable prefix without guessing.

Next priority:

- Add a bounded, versioned readable-range query to the C ABI.
- Return only ranges backed by verified block digests and a completed target
  flush; never expose sparse logical size as readable data.

### Disk path and checkpointing

The native core already has:

- SQLite WAL checkpoints with `synchronous=FULL`;
- block digests and restart verification;
- a bounded byte/time checkpoint interval;
- a shared payload buffer budget and curl pause backpressure;
- a single `IoQueue` worker per engine.

Current risks:

- One disk worker serializes all file writes and checkpoint jobs for an engine.
  That is predictable, but can become the bottleneck when several tasks or
  several HLS/DASH children write unrelated files.
- Multi-range completion still rereads the whole file for final SHA-256. The
  single-range path now hashes while writing; extend ordered hash completion to
  multi-range without holding unbounded memory.
- The final checkpoint and verification path should be measured separately
  from network completion. The benchmark already records written, durable and
  verifying phases.

Next priority:

- Add per-task ordered hash assembly with a bounded reorder window.
- Evaluate a small disk-write queue or direct `WriteAt`/`pwrite` path without
  changing checkpoint semantics.
- Keep all benchmark scratch files on C for SSD-sensitive runs and delete them
  after each measurement.

### Retry and source identity

The native core already has independent mirror headers, same-origin credential
scoping, source cooldown, stale-connection retries, resume validators, and
per-range retry accounting. The remaining gap is scheduling policy: a retry
should be charged to the source and segment that caused it, and an independent
mirror must be able to proceed during a cooldown.

## Current evidence boundary

- Current source full gate: 90 tests, 0 failures, 0 skipped.
- Current source DLL: `8bc7a4744cc15d6e2b3a07ff05f162b3df10e137ba675429427ab15460ddad23`
  (the next build must be re-hashed and re-gated).
- Local C-drive native-vs-SDK comparison after the kernel changes:
  - single: native ~267 ms engine / SDK ~462 ms;
  - unlimited 16: native ~324 ms / SDK ~405 ms;
  - independent SHA checks passed.
- These are localhost measurements, not public-network or five-platform
  acceptance.

## Do not claim yet

- Do not claim full aria2/Gopeed/IDM parity.
- Do not claim ED2K completeness; only the parser/P0 layer exists.
- Do not claim Android/iOS/macOS/Linux runtime acceptance; no real devices are
  connected.
- Do not claim HTTP/3, DHT, tracker or proxy behavior from local fixtures alone
  beyond the specific tests that actually ran.

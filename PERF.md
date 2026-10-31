# TurboDL performance ledger

This file records experiments that changed engine behavior. Measurements are
kept with the implementation so a rejected idea is not accidentally reintroduced.

## 2026-10-31: full engine pass

| Idea | Baseline | Result | Decision |
|---|---|---|---|
| Throughput-seeking concurrency (8 → 16 → … only after ≥30% gain) | 8 fixed workers | 0.80 MB/s at c8; 1.44 MB/s at c16; 2.66 MB/s at c32 on a 100 KB/s-per-connection local server | keep |
| Aggregate-cap convergence | request c256 | Node server plateaued at ~3.0 MB/s; controller stopped at the plateau instead of keeping 128–256 sockets | keep |
| Lazy worker + segment grid | pre-create requested ceiling | no idle 256-worker / 1024-segment allocation when adaptation remains at 8 | keep |
| Logical-length reservation after resume validation | extend before validation | prevents a deleted/recreated file from reviving stale sidecar progress | keep |
| Direct ByteBuffer path on JVM | heap `ByteArray` | byte-exact tests pass; avoids a second copy into `FileChannel` | keep, Android/Dalvik falls back to heap |
| ETA-based tail assist | largest remaining segment | chooses the worst estimated completion time instead of the largest byte count | keep, regression covered |
| Backoff jitter | exact 1/2/4/8 s retry pulses | ±25% jitter spreads synchronized retries | keep |
| Buffered segment write | direct write | 1538 ms vs 1526 ms median at 16 MB / 128 connections; within noise | reverted as a default; optional switch remains |

## Reproducible commands

```powershell
# Unit/integration suite
.\gradlew.bat :turbodl-core:test --no-daemon

# Opt-in microbenchmarks
$env:TURBODL_BENCH='1'
.\gradlew.bat :turbodl-core:test `
  --tests 'dev.turbodl.core.BaselineMetricsTest' `
  --tests 'dev.turbodl.core.EngineOverheadTest' `
  --tests 'dev.turbodl.core.SegmentWriteBufferTest' --no-daemon

# Local per-connection-limit matrix
node _bench/bench_server_node.js --port=8896 --size=64 --rate=100
python _bench/run_concurrency_matrix.py `
  --url http://127.0.0.1:8896/f.bin --size 67108864 `
  --connections 8,16,32,64,128,256 --mode adaptive --repeats 2
```

Loopback and Java `HttpServer` results are useful for detecting scheduler
regressions, but cannot be treated as a real-CDN throughput promise. A changing
local server can dominate the connection-count curve; real tests must record
the URL, proxy, time window, and server behavior.

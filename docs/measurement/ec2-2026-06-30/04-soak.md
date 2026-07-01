# Soak — 6 h leak/OOM burn-in — **PASS**

The prior 24 h soak attempt OOM'd at **3.45 h**. This run reached the **full 6 h (21,601 s, 691 samples)**
with a flat resource profile — **no OOM, no leak of any kind.** The FD-leak fixes (release event-loop
groups on failed bind) are confirmed on a real multi-hour burn.

## Configuration
`perf/soak.sh` on the box, data+WAL on `/mnt/nvme`, 3 co-located ZGC nodes, 2 g heaps, `-XX:Native
MemoryTracking=summary` + `-XX:+HeapDumpOnOutOfMemoryError`, shared signing key (D-1-safe), steady
**300 writes/s** (below the ~800/s churn knee → churn-free), 30 s sampling. Start 2026-06-30T20:57:30Z,
DONE 2026-07-01T02:57Z.

## Result — flat over the whole run (post-warmup t+312s → t+21,601s)

| signal | start | end | over 6 h | verdict |
|---|---:|---:|---|---|
| **FD (total)** | 350 | 350 | **min=350, max=350 — perfectly flat** | ✅ no FD leak (the prior-suspect vector) |
| **RSS (total)** | 2,436 MB | 2,498 MB | **2.6 % spread** (ZGC commit plateau, reached early) | ✅ no native/direct-buffer leak |
| **Heap-used floor** | ~403 MB | ~398 MB | stable ~390–404 MB floor (sawtooth peaks ~545 MB, always returns) | ✅ no heap leak |
| **Threads** | 172 | 178 | 172–180 (pool jitter) | ✅ flat |
| **GC** | 1.96 s cum | 198.4 s cum | 0.92 % overhead, **linear** accumulation | ✅ no GC degradation |
| **commit p50 / p99** | 2.2 / 2.9 ms | 2.2 / 3.1 ms | p50 ~2.2–2.5 ms, p99 ~3–6 ms | ✅ no latency drift |
| **committed / rejected** | 9000 / 0 | — / 0 | 300/s sustained, **0 rejected all run** | ✅ stable |

Leak-verdict method (charter §4): the definitive Java-heap signal (jstat heap-used) has a **stable floor
across the whole post-warmup window** (no rising trend under the sawtooth); RSS/FD/thread/GC — which do not
sawtooth — are flat. Evidence: `captures/soak/soak-trend-full.log` (all 691 samples) +
`captures/soak/soak-heap-watch-5min.log` (5-min heap/RSS/FD samples over 6 h).

## Verdict: **v1 go/no-go GATE PASSED**
A clean soak (flat heap/RSS/GC/FD past the prior 3.45 h failure point, sustained to the full 6 h lean
target) is a v1 go/no-go gate — **PASSED**. The recurring OOM does not reproduce on clean code (`ce7d719`).

## Harness note (not a system defect)
The `launch-soak.sh` wrapper set `SOAK_BASE` == `--out` (same dir), and `soak.sh`'s cleanup does
`rm -rf "$BASE"` on exit — so it deleted its own `result.txt`/`trend.csv`/`gclogs` at completion. The full
trend survived in the wrapper's stdout log (`soak-run.log`, kept outside that dir) + the 5-min watch
samples, so no data was lost. Follow-up hardening: guard `soak.sh` cleanup to refuse `rm -rf "$BASE"` when
`$BASE` == `$OUT_DIR` (or point them at distinct dirs). The system under test is unaffected.

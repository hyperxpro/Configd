# Soak — 6 h leak/OOM burn-in (IN PROGRESS)

The prior 24 h soak attempt OOM'd at **3.45 h**; the lean target is a 6–8 h burn-in past that point with a
flat heap/RSS/GC/FD profile. The FD-leak fixes (release event-loop groups on failed bind) just landed —
this run confirms FDs stay flat.

## Configuration
- `perf/soak.sh` on the box, data+WAL on `/mnt/nvme`, 3 co-located ZGC nodes, **2 g heaps** (`-Xmx2g
  -Xms2g` — surfaces a heap leak faster than 4 g while staying realistic), **`-XX:NativeMemoryTracking=
  summary` + `-XX:+HeapDumpOnOutOfMemoryError`** (off-heap / OOM capture), shared signing key (D-1-safe).
- Steady **300 writes/s** (well below the ~450/s single-group churn knee → a clean, churn-free leak signal,
  not a throughput test), trend sample every 30 s. Duration target **6 h** (21,600 s).
- Harness note: `soak.sh` had to be fixed to boot at all — it predated the D-1 fail-closed guard and
  launched nodes with a co-located per-node signing key, which is now refused. Fixed to mount a shared
  `--signing-key-file` outside the data dirs (committed).

## Watch
A health-check (`soak-check.sh`) trips on DONE / OOM (heap dump) / node-death / leak-suspect (post-warmup
heap > 1.5× or RSS > 1.4× or FD > 1.3× vs the first post-warmup sample).

## Results

[PENDING — filled on completion: heap/RSS/GC/FD trend over the run, pass vs the 3.45 h prior failure point,
and the leak/no-leak verdict. A clean flat profile past 3.45 h with margin = v1 go/no-go PASS; a recurring
OOM/leak = v1 BLOCKER (heap/alloc profile captured + surfaced).]

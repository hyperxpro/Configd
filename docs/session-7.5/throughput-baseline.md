# Session 7.5 — Throughput headline, PART 1: the per-op-fsync BASELINE (the "before")

> Charter §7.0/§7.1, Prime Directive §4 (before/after on THIS box). Box: m6id.4xlarge, 16 vCPU,
> data+WAL on `/mnt/nvme` (`/dev/nvme1n1`, runtime-asserted). Harness: `perf/s75-throughput.sh`
> (CO-corrected `OpenLoopWriteDriver`, 3-node real cluster, shared pre-generated signing key).
> Captures: `captures/throughput/`. This is the as-built (per-entry fsync, NO group commit) result;
> PART 2 (group commit) follows.

## The numbers (as-built, per-entry `force(true)`, no group commit)

| Run | Offered | Achieved commit/s | 200 | 503 (NotLeader/Lost) | 429 (Overloaded) | 504 | backpressure-shed |
|---|---|---|---|---|---|---|---|
| Calibrate (closed-loop, c=64) | max | **380/s** | 6465 | 627 | 0 | 53 | — |
| Phase 3 (sustained, c=256, 512B) | 10,000/s | **391/s** | 27,652 | 121,019 | 0 | 1,439 | 549,835 |
| Phase 4 (burst, c=512, 512B) | 100,000/s | **347/s** | 11,892 | 72,978 | **7,760** | 2,029 | 2,905,298 |

The as-built cluster sustains **~380 commits/s** and **does NOT meet the §0.1 10k/s target**. At 10k
offered, 96% is shed/failed; the dominant failure is **503 NotLeader/Lost (121k)** = leadership churn,
not clean queue-backpressure. (Burst did fire **7,760× 429 Overloaded** — the queue-1024 path — but
leadership collapse dominates.)

## §7.0 attribution — NOT disk-bound, NOT CPU-bound → IMPLEMENTATION-bound (case a)

**Commit-fsync model (code):** per-entry `force(true)` (fsync data+metadata) executed **synchronously
on the single tick/consensus executor** (`RaftNode.propose`→`log.append`→`FileStorage.force(true)`;
proposals marshalled onto the single tick thread, `ConfigdServer.java:510`). No group commit.

**Evidence the disk is NOT the ceiling** (`captures/throughput/phase3.iostat.txt`, nvme1n1):
- `%util` median **~14%** (q75 ~27%, peaks ~60%, one transient 80%) — the device is idle most of the
  time (review-architect re-derived from the per-second samples).
- `f/s ≈ 0`, `w/s` 1.8k–18k at ~35% merge, `w_await ≈ 0.03 ms` — the NVMe has massive headroom vs its
  ~8.3k–14.3k fdatasync/s ceiling (`run-log.md §6.1`).

**Evidence the CPU is NOT the ceiling** (`phase3.mpstat.txt` / `phase3.pidstat.txt`):
- All-core **%idle 72–88%** throughout (≈3–4 of 16 vCPU busy).
- Each of the 3 node JVMs uses ~40–140% of one core (of 1600% available) — no JVM is core-bound.

**Therefore the ceiling is the single-threaded consensus path (R-01) carrying a synchronous per-entry
fsync.** Under concurrent load the tick thread serializes (heartbeat + per-op fsync + replication +
apply); the per-entry fsyncs delay heartbeats past the election timeout → followers force elections →
**leadership churn (the 121k× 503)** → commits stall/retry → throughput collapses to ~380/s **while disk
and CPU sit idle**. This is **case (a): an implementation finding (P1)** — per-op fsync on the critical
consensus thread, NOT the true architectural ceiling — and it is **fixable on THIS box** via group
commit (batch N proposals → one fsync), exactly per §7.0(a)/§7.1. PART 2 implements it and re-measures.

This supersedes the S5 a-priori hypothesis ("mechanism does 815k/s in-memory; only host-capacity
limits 10k/s"): on real hardware with real fsync, the **as-built** tops at ~380/s for an
**implementation** reason (per-op fsync on the consensus thread), not a host-capacity or disk reason.

## Unloaded latency (for reference; the clean local-commit component)
Phase 2 (200/s, no overload): **p99 = 5.51 ms**, p50 = 2.58 ms (CO-corrected). At low load the tick
thread keeps up; the collapse is a *loaded* phenomenon.

## Method rails
CO-corrected (intended-time); HdrHistogram; box spec + fsync baseline recorded once; disk+CPU
evidence captured per phase; before/after on this box. The phase-3/4 HISTOGRAMS are overload
artifacts (dominated by backpressure rejections) and are NOT used as latency numbers — the latency
SLO uses phase-2 (unloaded) + the post-group-commit at-rate run.

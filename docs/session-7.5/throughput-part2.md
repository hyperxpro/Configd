# Session 7.5 — Throughput headline, PART 2: group commit + the HONEST re-attribution

> Charter §7.0/§7.1, Prime Directive §4 (before/after on THIS box), §4.2 honest attribution.
> Box: m6id.4xlarge, 16 vCPU, 3 co-located nodes, data+WAL on `/mnt/nvme` (`/dev/nvme1n1`,
> runtime-asserted). Harness: `perf/s75-throughput.sh`. Captures: `captures/throughput/part2/`.
> This part supersedes PART 1's attribution with a direct measurement.

## TL;DR (the honest headline)

1. **Group commit was implemented, verified correct (redteam SAFE, 334 consensus + 20,218
   testkit/sim/fault tests green), and measured — and it does NOT lift the throughput ceiling on
   this box.** Reason, proven directly: **fsync is nearly free on this AWS instance-store NVMe**
   (`f/s` = 0, `w_await` = 0.03 ms — no volatile write cache to flush). PART 1's "fsync-per-op-bound
   (case a)" attribution is therefore **WRONG and is hereby corrected**: batching the fsync changes
   nothing because the fsync was never the cost.
2. **The real as-built ceiling is leadership churn under load, NOT fsync.** A clean rate ladder shows
   the 3-node cluster is **stable to ~800 writes/s** (1 election, 0 failures) and **collapses into
   leadership churn at ~1000–1200/s** (15–34 elections per 15 s, throughput falling to ~400–600/s
   while the rest is rejected `503 NotLeader`). CPU is ~86 % idle and the disk is ~free throughout —
   so this is **case (c): the single-threaded consensus path (R-01) is the architectural ceiling**,
   amplified by co-location. **10k/s is NOT met by the as-built system; it is heartbeat-starvation-
   bound at ~800–1000/s, not host-capacity- or disk-bound.**
3. Group commit is **retained** (not reverted): it is correct, standard durability engineering that
   IS load-bearing on media where fsync has a real cost (EBS, network block, disks with a volatile
   write cache that honor flush). It is simply a no-op win on this particular instance-store NVMe.

## A. fsync is free here — the measurement that corrects PART 1

`iostat -x nvme1n1` during the **per-op-fsync baseline** (`-Dconfigd.groupCommit.enabled=false`, i.e.
the PART 1 path on the SAME binary):

| metric | value | meaning |
|---|---|---|
| `f/s` (device flush/s) | **0.00** | `FileChannel.force(true)` issues no device flush — the NVMe reports no volatile write cache |
| `w_await` | **0.03 ms** | writes complete in 30 µs; there is no flush penalty to amortize |
| `w/s` | 1.5k–2.3k | the WAL writes happen and complete fast |
| `%util` | 4–7 % | device almost idle |

The operator fio baseline (~8,300–14,300 fdatasync IOPS) reflects the same reality: fdatasync is fast
here because nothing real is flushed. **Group commit amortizes a cost that is ~zero on this device, so
it cannot raise throughput here.** (On EBS/SAN/HDD with an honored write cache, the per-op `force` is
the classic bottleneck and group commit is the standard fix — hence we keep it.)

## B. The group-commit sizing curve is FLAT — empirical proof fsync isn't the bottleneck

Closed-loop `calibrate` (max sustainable commit/s), sweeping linger × maxBatch, group commit ON vs the
per-op baseline (`captures/throughput/part2/sizing-curve.txt`):

| config | commit/s |
|---|---|
| off (per-op fsync = PART 1 baseline) | 342 |
| linger 0 µs, maxBatch 4096 | 317 |
| linger 250 µs | 329 |
| linger 500 µs | 333 |
| linger 1000 µs | 370 |
| linger 2000 µs | 357 |
| linger 500 µs, maxBatch 16 / 64 / 256 / 1024 | 347 / 387 / 322 / 312 |

Every point is within run-to-run noise of the per-op baseline. **No linger or batch size helps** —
because the thing being batched (fsync) is free. The calibrate regime is itself churn-dominated
(503s ~600–770/run), which is why even the "off" number (~340) is well under the stable rate in §C
(calibrate's concurrency=64 closed loop pushes the cluster into churn).

## C. The real ceiling — rate ladder (open-loop, group commit ON, `captures/throughput/part2/ladder/`)

Fresh 3-node cluster per rate, 15 s, concurrency 256, 512 B values; `elections` =
`configd_raft_elections_total` (max across nodes) — the direct heartbeat-starvation signal:

| offered/s | achieved/s | elections | 200 / 503 / 504 | state |
|---|---|---|---|---|
| 200  | 200 | **1** | 3000 / 0 / 0      | **stable** |
| 400  | 400 | **1** | 6000 / 0 / 0      | **stable** |
| 600  | 600 | **1** | 9000 / 0 / 0      | **stable** |
| 800  | 799 | **1** | 12000 / 0 / 0     | **stable** |
| 1000 | 607 | 15 | 9155 / 5842 / 0      | collapsing |
| 1200 | 560 | 20 | 8462 / 8877 / 130    | collapsed |
| 2000 | 490 | 28 | 8424 / 13765 / 189   | collapsed |
| 4000 | 405 | 34 | 6534 / 33333 / 0     | collapsed |
| 8000 | 409 | 32 | 7071 / 51840 / 0     | collapsed |

**The knee is sharp and between 800 and 1000/s.** Below it: 1 election, 0 failures, full
achievement — a perfectly healthy cluster. Above it: elections multiply, achieved throughput
*inverts* (more offered → less committed), and the dominant outcome is `503 NotLeader` (no stable
leader). Elections scale monotonically with offered load — the signature of heartbeat starvation.

## D. §7.0 attribution (corrected) — case (c), single-threaded consensus path

- **NOT (a) fsync-IOPS-bound:** §A (`f/s`=0, `w_await`=0.03 ms) and §B (flat sizing curve) prove it.
  PART 1's case-(a) call is corrected.
- **NOT raw-CPU-bound:** aggregate CPU ~14 % busy (86 % idle); busiest single core peaks ~73 %, never
  pegged (`captures/throughput/part2/*/calibrate.mpstat.txt`). The disk is ~free.
- **(c) the single-threaded consensus path (R-01) is the ceiling.** Every RaftNode mutation —
  `propose` (append + `broadcastAppendEntries` to 2 peers, encode each), inbound message handling,
  `applyCommitted`, AND the periodic heartbeat tick — runs on ONE executor thread
  (`ConfigdServer` `tickExecutor`). Above ~800–1000 offered/s the per-proposal work (notably a
  broadcast-per-propose) on that one thread delays the scheduled heartbeat past the election timeout
  (heartbeat 50 ms, election 150–300 ms) → a follower elects → the in-flight requests get
  `503 NotLeader` → clients retry → thundering herd on the new leader → more delay → **self-reinforcing
  churn**, throughput collapses while CPU and disk sit idle. The transport `send` is non-blocking
  (RR-002, encode + bounded enqueue), so this is queue/scheduling latency on the single thread, not a
  blocking I/O stall.

**Co-location confound (honestly flagged):** 3 full nodes + a 256-thread load driver share 16 vCPUs,
so OS-scheduler latency for the lone tick thread is plausibly amplified vs a dedicated host. The
QUALITATIVE finding (single-thread heartbeat-starvation churn; NOT fsync) is established here; the
PRECISE stable ceiling (is it ~800/s or higher on a dedicated host?) requires one-node-per-host and is
a **signed multi-box deferral** (see shrunken manifest), NOT manifest-dumping — the cause is proven,
only the exact number is co-location-confounded.

## E. What this means for the M-items and the fix

- **The 10k/s headline (M-?) is NOT met by the as-built system, and the reason is now correctly
  attributed:** single-threaded consensus-path heartbeat starvation, not fsync, not host capacity.
  This is the honest answer the box was provisioned to find.
- **Recommended fix (next):** make the leader hold leadership under overload so it degrades gracefully
  instead of collapsing. Two complementary levers, both implementable on this box:
  1. **Admission control / bounded in-flight proposals** → shed excess as `429 + Retry-After` (the
     §11 documented shed path) BEFORE the tick executor's backlog starves heartbeats. Converts
     `503`-churn-collapse into clean `429` shedding at the stable rate.
  2. **Coalesce replication** (broadcast per *tick*, not per *propose* — analogous to group commit)
     to cut per-proposal tick-thread work and push the knee higher.
  Each must be measured before/after on this box and must not regress RR-002 / the durability gates.

## F. Election-timeout experiment — longer timeout does NOT help (negative result)

Hypothesis: if the churn is just-too-aggressive failover, a longer election timeout (etcd default is
1000 ms vs Configd's 150 ms) should reduce elections and raise throughput. Tested on the same binary
(timeouts now operator-tunable via `-Dconfigd.raft.electionTimeoutMin/MaxMs`),
`captures/throughput/part2/ladder-et/`:

| config | offered/s | achieved/s | elections | dominant |
|---|---|---|---|---|
| control (election 150–300 ms) | 1200 | **749** | 14 | churn |
| election 1000–1500 ms | 1200 | **396** | 8 | longer stalls |
| election 1000–1500 ms | 2000 | 160 | 11 | |
| election 1000–1500 ms | 4000 | 218 | 11 | |
| election 1000–1500 ms | 8000 | 182 | 11 | |

The longer timeout **reduced elections but HALVED throughput**. This confirms the root cause is genuine
**leader saturation**, not aggressive failover: when the starved leader stalls, a longer timeout just
makes followers wait longer before a new leader takes over, so nothing commits during the gap. The
aggressive 150 ms timeout is actually *better* under this churn because it recovers quickly. **Timeout
tuning is therefore NOT the fix** — the leader must either shed load (admission control → graceful
`429`) or do less per-proposal work (coalesce replication) so it can keep up AND heartbeat. This
sharpens the §E recommendation: pursue admission control / replication coalescing, not timeout tuning.

## Method rails
CO-corrected (intended-time) HdrHistogram on the driver; box spec + fsync baseline recorded once
(`run-log.md`); per-phase iostat/mpstat/pidstat captured; before/after on THIS box via
`-Dconfigd.groupCommit.enabled` on one binary; `elections_total` read from `/metrics` per rate.
Group-commit correctness independently reviewed SAFE (redteam, run-log DL-7.5-01) and gate-verified.

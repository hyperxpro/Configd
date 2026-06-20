# Session 7.5 — Measurement Methodology Note (review-architect sign-off gate)

> Per charter §15.2, the methodology (coordinated-omission handling at 16 vCPU + the cited RTT
> matrix) is signed by `review-architect` BEFORE the bulk latency numbers are accepted. This note
> states the method; the numbers live in `run-log.md` and the workstream docs. Box spec +
> fsync baseline: see `run-log.md §6.1`.

## 1. Coordinated-omission (CO) correction — how each latency harness avoids hiding stalls

More cores make a stall *easier* to hide (a backed-up generator looks fast), so CO discipline is
*more* important at 16 vCPU, not less (charter §4.3). The write-path harness is
`OpenLoopWriteDriver` (`configd-testkit`, S5, unmodified):

- **Open-loop schedule.** Request *i* is scheduled at a fixed wall-clock `start + i·(1e9/rate)` ns
  and issued at that instant regardless of whether prior requests have completed
  (`OpenLoopWriteDriver.java:206-212`). The schedule never waits on completions, so a slow server
  cannot slow the offered rate (the classic CO trap).
- **Latency measured against SCHEDULED time, not send time.** Every sample is
  `now − scheduledTime` (`:221`, `:228`, `:235`), so a request that *should* have been sent at T but
  was delayed still accrues latency from T. This is the Tene CO correction.
- **Backpressure is recorded, not dropped.** A bounded worker pool + bounded queue; on
  `RejectedExecutionException` the intended-time latency is STILL recorded and a `rejected_backpressure`
  counter increments (`:231-236`). A full queue therefore shows up as tail latency + a shed count, not
  as a silently-faster run.
- **HdrHistogram**, µs resolution, range [1 µs, 120 s], 3 sig-figs (`:194`); output line
  `ATRATE-HISTOGRAM ... co_corrected=intended-time` carries p50/p90/p99/p999/p9999/max/mean.

The read-path (edge) latency uses the S5 `LocalConfigStoreReadBenchmark` (JMH, in-process,
`@Benchmark` sampling) — JMH's own sampling avoids CO for the in-process read; the propagation/
staleness probe uses a **fixed wall-clock edge sampling cadence** (decoupled from the write side)
so staleness is sampled on a clock, not on completions.

## 2. fsync-ceiling attribution method (§7.0) — applied BEFORE any throughput claim

The disk's durable-append ceiling is the dominant interpretive number. Method:

1. **Model determined in code** (done — `run-log.md §7.0`): Configd fsyncs **per entry**
   (`force(true)`, data+metadata) serialized on one tick thread, **no group commit**.
2. **fsyncs/sec measured** two ways:
   - *Primary (derivation):* per-entry fsync ⇒ leader fsync/s = achieved commit/s (driver's
     `achieved_commit_rate`). The 3 voters are **co-located on ONE NVMe** (`/mnt/nvme`), so the
     device sees ≈ **3 × commit/s** fsyncs (leader append + 2 follower AppendEntries appends, each
     per-entry). The relevant device ceiling for a 3-on-1-disk cluster is therefore ≈ ceiling/3 per
     node.
   - *Cross-check:* `strace -f -e trace=fdatasync,fsync` on one node at a LOW rate (phase 2) to
     confirm the 1-fsync-per-commit model empirically without ptrace-perturbing the headline; plus
     `iostat -x` device flush/util and `pidstat` per-process CPU sampled across each phase.
   (`perf` is unavailable on this box — `perf_event_paranoid` + no tracefs access.)
3. **Attribution to exactly one cause, with evidence:**
   - **(a) fsync-IOPS-bound** — commit/s ≈ ceiling/3 and device fsync/s ≈ device ceiling and CPU has
     spare headroom ⇒ batching off/undersized. **Implementation finding (P1/P2)**, fixable here.
   - **(b) CPU-bound** — 16 vCPU saturated (3 JVMs + driver contending), device fsync/s well below
     ceiling ⇒ contention ceiling (shared box; production dedicates hosts).
   - **(c) genuine system-bound** — group commit on + well-sized, CPU headroom, fsync/s below the
     device ceiling, throughput still plateaus ⇒ the real architectural ceiling.
4. Only then is the rate stated, naming (a)/(b)/(c) with the fsync/s + CPU evidence.

**Reference ceiling band** (`run-log.md §6.1`, re-measured by fio on `/mnt/nvme`): single-thread
durable fdatasync ≈ **6.5k (O_DIRECT) – 14.3k (buffered) /s**; operator baseline ~8,300/s anchors the
conservative end. Configd's `force(true)` (metadata) + per-entry open/close is *heavier* than the bare
fdatasync fio measured.

## 3. The WAN split (cross-region targets — three numbers, never one) — §13.1

No cross-region target is VERIFIED on one box (loopback is sub-ms; real inter-region is tens of ms —
charter §4.2). Each is reported as THREE numbers using the canonical RTT matrix
(`docs/session-5/methodology.md §2`, a *declared model input*, not measured here):

- **local component** = VERIFIED-at-scale here (HdrHistogram, this box).
- **modeled total** = `local_commit_component + RTT(quorum)`, with **RTT(2nd-fastest follower) = 68 ms**
  for the 5-region layout (the round trip that completes the majority; `methodology.md §2`).
- **combined target** = flagged **PENDING multi-box confirmation** (M-1/M-2 in the shrunken manifest).

For write-commit: `modeled_commit_p99 = local_p99 + 68 ms`; compare to the 150 ms target. For edge
propagation: `local_fanout_component + WAN Plumtree leg (1–3 hops × matrix RTT)`; compare to 500 ms.
The model keeps non-overlapping leader-side serial terms (batch window, proposal serialization, fsync)
in `local_commit_component` so the WAN term cannot make the model falsely optimistic
(`methodology.md §2`).

## 4. Honesty rails (every at-scale number)
Box spec + harness invocation + HdrHistogram + CO method + fsync attribution recorded together;
extrapolations LABELED (never claimed measured); before/after on THIS box for any optimization.

---
**review-architect sign-off:** ☐ PENDING (to be signed before §9 latency numbers are accepted).

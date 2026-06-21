# Session 7.5 → Session 8 handoff (real-hardware campaign, checkpoint state)

> First real-hardware measurement of Configd, on a hand-provisioned AWS EC2 **m6id.4xlarge SPOT**
> (16 vCPU / 64 GB / local NVMe instance store) in ap-south-1, repo on `/mnt/nvme`. All results below
> are committed + PUSHED to `main`. This is a **CHECKPOINT** handoff at a clean pushed seam — the two
> highest-value, box-only items (the throughput headline and the D-1 security P1) are DONE; the long
> tail is honestly PENDING (see §Pending). Box spec + fsync baseline: `run-log.md §6.1`.

## Box spec used (every number is relative to this)
m6id.4xlarge, **16 vCPU** (Xeon 8375C, single-socket, no NUMA), **61 GiB**, Ubuntu 26.04 /
kernel 7.0, OpenJDK 25, ZGC. Data+WAL on `/dev/nvme1n1` (local instance store), runtime-asserted on
`/mnt/nvme`. Operator fsync baseline ~8,300 fdatasync IOPS; **re-measured: fsync is effectively FREE
on this device** (`iostat f/s`=0, `w_await`=0.03 ms — no volatile write cache to flush). That single
fact reshaped the headline (below).

## The headline finding (the result the box was provisioned to find) — see `throughput-part2.md`

**The as-built 3-node cluster sustains ~800 writes/s STABLY and collapses into leadership churn
above ~1000/s. This is NOT fsync-bound; it is single-threaded-consensus-path heartbeat starvation.**

Chain of evidence (all on this box, all pushed):
1. **PART 1** (pre-existing, `throughput-baseline.md`): as-built per-op-fsync tops at ~380 commits/s,
   attributed to fsync-per-op (case a). **PART 2 CORRECTS this.**
2. **fsync is free here** → group commit (correctly implemented + independently reviewed SAFE +
   334 consensus + 20,218 testkit/sim/fault tests + a dedicated durability-gate regression test)
   **does not move throughput**. The group-commit sizing curve is FLAT (310–390 commit/s across every
   linger×maxBatch). Group commit is RETAINED (correct, standard; load-bearing on EBS/SAN/HDD where
   fsync has a real cost; a no-op win on this instance-store NVMe only).
3. **Rate ladder** (`captures/throughput/part2/ladder/`): stable + full achievement + 1 election at
   200/400/600/800 s; collapse at ~1000–1200/s (15–34 elections/15 s, throughput *inverts*, dominant
   `503 NotLeader`). CPU ~86 % idle, disk ~free. The single tick executor (R-01) carrying
   propose+broadcast-per-propose+apply+inbound starves the periodic heartbeat under load.
4. **Election-timeout experiment** (negative): a longer election timeout (1000–1500 ms) REDUCED
   elections but HALVED throughput (genuine leader saturation → longer stalls, not aggressive
   failover). **Timeout tuning is ruled out.**
5. **§7.0 attribution = case (c): the single-threaded consensus path is the ceiling**, co-location-
   confounded (3 nodes + 256-thread driver on 16 vCPU). 10k/s is NOT met by the as-built system.

**Recommended fix (the top next-step, both implementable on this box, measure before/after):**
1. **Admission control** — bound in-flight proposals; shed excess as `429 + Retry-After` (the §11
   documented shed path) BEFORE the tick-executor backlog starves heartbeats. Converts the
   `503`-churn-collapse into clean graceful shedding at the stable rate (makes §11 actually pass).
2. **Coalesce replication** — broadcast per *tick*, not per *propose* (analogous to group commit) to
   cut per-proposal tick-thread work and push the knee higher.

## ❌ THE 10k/s WRITE-THROUGHPUT SLO IS NOT MET — P0 (RR-113)
The single-Raft-group sustained write rate measured on real hardware is **~800–1,600/s, far below the
advertised 10,000/s target** (M-9 / §0.1). Admission control (S7.5) fixes the FAILURE MODE only —
graceful `429` shed instead of `503` churn-collapse, ~2× the under-overload rate — it does **NOT meet
the target**. The S7.5 throughput **WORK** (group commit, corrected attribution, admission-control
mitigation) is done; the **TARGET** is not, and the gap is tracked as **P0 RR-113** in the readiness
register. **This must not be reported as "done."** The path to 10k/s is write-sharding across multiple
Raft groups (and/or decoupling heartbeat/replication from the single consensus thread) — see RR-113.

## DONE + PUSHED this session (WORK done — for the throughput SLO see the P0 above)
- **Bring-up** (`bring-up-gates.md`, `run-log.md`): box spec, fsync baseline, data-on-NVMe asserted,
  gates 1–7 green on box (pre-campaign).
- **Throughput investigation + mitigation (work done; 10k/s SLO NOT met — P0 RR-113):** group commit
  (durable-index-gated, reviewed SAFE DL-7.5-01, `GroupCommitDurabilityTest` 2/2) + the corrected §7.0
  attribution (ceiling is single-thread-consensus churn ~800/s, NOT fsync — `iostat f/s`=0) +
  admission-control mitigation (`-Dconfigd.write.maxInflightProposals`: graceful `429` shed, ~2×
  under-overload, leader stable). All tunable via `-D`. **The 10,000/s target remains unmet (RR-113).**
- **D-1 (security P1) RESOLVED** — fail-closed signing-key co-location guard
  (`ConfigdServer.enforceSigningKeyNotColocated` refuses to start when the signing key is inside the
  data dir; dev/test opt-out via property/env). `D1FailClosedTest` 6/6. Readiness register row added
  (P1, RESOLVED). Realistic deploy layout validated (compose mounts key on separate `/secrets:ro`).
  ADR-0043 documents the prod KMS/HSM/mounted-secret expectation.
- **Operator knobs added & retained:** Raft timing now tunable via `-Dconfigd.raft.*` (defaults
  unchanged). Harness robustness: staggered shared-key launch, `S75_JVM_EXTRA`, uutils `tail` fix.

## PENDING (honest — NOT done this session; the long tail)
1. **Admission control — IMPLEMENTED + VALIDATED** (`throughput-part2.md §G`,
   `-Dconfigd.write.maxInflightProposals=N`). At the knee (=16) under a 2000/s flood: leader STABLE
   (1 election vs 29), throughput DOUBLES (864 vs 432), failure flips from 503 churn to clean 429+
   Retry-After (zero 503). Kept opt-in (default off) — the optimal bound is co-location-confounded;
   REMAINING: enable-by-default with a dedicated-host-tuned/adaptive bound, and the replication-
   coalescing lever (broadcast per tick) to push the knee higher.
2. **Burst 100k/s + §11 ladder — DONE (M-10 VERIFIED)** with admission control (`throughput-part2.md §H`):
   100k/s offered → 565/s committed, 680k× `429 Overloaded`+Retry-After graceful shed, only 1,297× 503,
   5 elections (no collapse). The documented shed order/signals/queue-bounds fire at real burst load.
   REMAINING: a dedicated post-burst recovery-to-CURRENT confirmation (quick).
3. **Latency §9:** write-commit three-number split DONE (`latency-wan-split.md`: local 5.51 ms +
   WAN 68 ms = **modeled ≈ 73.5 ms < 150 ms target, PENDING multi-box**). REMAINING: the propagation/
   staleness M-2 split needs the S5 edge-probe re-run at scale (single-host-provable, not yet done).
4. **Scale §8 — read-path + GC DONE** (`scale-read-gc.md`): read stays sub-µs at 100M keys (getHit
   483 ns, getMiss 12 ns), ~0–32 B/op constant in size, ZGC a non-event for reads (6 alloc-stalls
   were a 100M bulk-LOAD artifact, not steady-state). Memory ~170 B/key → 100M ≈ 17 GB; **10⁹ ≈ 170 GB
   exceeds this box → M-5 (large-memory host)**; read-latency-at-10⁹ extrapolated sub-µs (labeled).
   REMAINING: the soak (§8, last).
5. **S7 residuals / §10 — mostly DONE:** slowloris (F-S7-FUZZ-1, HIGH) ✓, per-principal rate limiting
   (Med) ✓, edge /metrics scrape-token auth (F-S7-TLS-2, Low) ✓ — all committed with green negative
   tests. REMAINING (recon-blueprinted in `deploy-security-recon.md`, both security-sensitive — do
   carefully): **leaf-anchor cert-expiry** (Item 2, RFC 5280 §6.1 — wrap the trust manager to validate
   `chain[0]` expiry, or move to a real CA topology) and **active-replay rejection** (Item 4 — the
   ReplayGuard stops passive replay but a token-holder can mint fresh requests; needs per-request
   signing). Plus the **synthetic N↔N+1 upgrade** (build a v1-with-a-deliberate-wire-change variant,
   run a mixed-version cluster on one host; HEAD = v1). **PENDING — NOT box-blocked: none of these
   need special hardware** — they are pure logic / one local cluster, doable on any box (laptop/CI);
   they were deferred only by session length + the soak occupying this box.
6. **Threshold promotion §11** — drive each S6 PROPOSED threshold's condition, confirm it fires (true
   positive) and stays quiet under normal load (no false positive), promote VALIDATED or correct-with-
   delta. Needs a running cluster + driven load but on **ANY box** (these are behavioral thresholds,
   not raw-perf) — **PENDING, NOT real-hardware-bound**; deferred here only because the soak occupied
   this box. (Inherently-multi-region thresholds stay PROPOSED → multi-box.)
7. **Soak §8 — CALLED at ~13 h CLEAN (this is a 13-HOUR soak, NOT 24 h; 24 h RECOMMENDED to fully close
   §8)** (`perf/results/soak-s75/`, 400/s stable, 2g heaps, 64 GB box). Leak-negative over the full
   ~13 h: FD 107 / threads 168 pinned at the t+31s baseline (zero drift), RSS plateaued ~2.47 GB
   (settled slope ~0–0.3 MB/h = ZGC/native noise, not a leak), p50~2 ms, p99~3 ms, 0 rejected
   throughout. Passed t+12400 (3.45 h) — where the S5 soak OOM'd on the old 7.7 GB box (RR-112) —
   CLEAN, and past 12 h: that OOM was box heap-sizing (3×1g → 3.27 GB hit the 7.7 GB ceiling), NOT a
   Configd leak; **RR-112 superseded**. The soak was stopped here only because the operator is tearing
   down the box; **the achieved duration is honestly ~13 h, never claimed as 24 h.** To fully close §8,
   re-run `perf/soak.sh --duration=86400` for a real 24 h on a right-sized box (**no special hardware
   needed**). Trend at `perf/results/soak-s75/trend.csv`.
8. **gate-7.5** (§12) — NOT wired/run yet (the new S7.5-specific cumulative gate is still to be
   authored). BUT **S4 (gate-4) + S7 (gate-7) ARE re-greened**: the first cumulative gate-7 re-run
   found a D-1 regression (the `LivePropagationProbeMain` dev probe boots a co-located-key server and
   the fail-closed guard correctly refused it) — FIXED (probe opts out), and the **clean cumulative
   gate-7 re-run is GREEN (gates 1–7, GATE7B_EXIT=0; `S4 cells still green`)** — §2 / §13.6 satisfied
   (run-log §13.6). REMAINING: author + wire `gate-7.5` (D-1 fail-closed test, slowloris negative,
   S7-residual negatives, synthetic upgrade, promoted-threshold tests) once those features land.
9. **`SigningKeyStore.loadOrCreate` concurrent first-boot race** (found during bring-up; race-tolerant
   load + atomic write) — follow-up to D-1.

## Decision log (for retroactive veto) — `run-log.md`
- **DL-7.5-01:** durable-index-gated coalescing group commit; redteam review SAFE.
- **DL-7.5-02:** corrected PART 1's fsync attribution to single-thread-consensus churn, on direct
  measurement (fsync-free, flat sizing curve, rate-ladder elections-scale-with-load); co-location
  confound flagged for multi-box.

## Operator teardown reminder (§16)
All results are pushed (latest commit recorded in `run-log.md` / the closeout). When done, **TERMINATE**
the spot instance from the console (not stop — terminate; verify it shows terminated). The NVMe and its
data are discarded on terminate, which is fine because everything is pushed.

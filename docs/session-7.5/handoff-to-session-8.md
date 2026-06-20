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

## DONE + PUSHED this session
- **Bring-up** (`bring-up-gates.md`, `run-log.md`): box spec, fsync baseline, data-on-NVMe asserted,
  gates 1–7 green on box (pre-campaign).
- **Headline PART 2** (group commit + the honest re-attribution above): implemented, reviewed SAFE
  (DL-7.5-01), measured, documented, pushed. `GroupCommitDurabilityTest` 2/2 locks the durable-index
  gate. Group commit is tunable (`-Dconfigd.groupCommit.{enabled,maxBatch,lingerMicros}`).
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
2. **Burst 100k/s characterization + the full §11 overload ladder** at real load (shed order, 429 +
   Retry-After, queue bounds, recovery) — PART 1 saw the 429 path fire (7,760×) but churn dominated;
   re-do after the admission-control fix.
3. **Latency §9:** write-commit three-number split DONE (`latency-wan-split.md`: local 5.51 ms +
   WAN 68 ms = **modeled ≈ 73.5 ms < 150 ms target, PENDING multi-box**). REMAINING: the propagation/
   staleness M-2 split needs the S5 edge-probe re-run at scale (single-host-provable, not yet done).
4. **Scale §8:** large key-count read p99 + memory; 10⁹ extrapolation; ZGC at large live set; soak.
5. **Slowloris (F-S7-FUZZ-1) + S7 residuals** (leaf-anchor expiry, edge /metrics, active replay,
   per-principal rate limiting) + **synthetic N↔N+1 upgrade** — all logic, NOT started.
6. **Threshold promotion §11** — needs the real-load conditions (post-fix).
7. **Soak §8** — NOT started (run at a STABLE rate ~500–600/s given the churn ceiling; label by
   achieved duration).
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

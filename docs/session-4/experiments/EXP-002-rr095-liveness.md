# EXP-002 — RR-095 re-run + first-class bounded-progress liveness checking

- **Workstream:** A2 (the liveness debt)
- **Register rows:** RR-095 (P3, Verification / liveness), RR-103 (link)
- **Status:** RR-095 → ACCEPTED-RISK (per-seed diagnosed); liveness checking delivered

## 1. Hypothesis

Cited: the RR-095 row characterizes the 7 stall seeds (452, 869, 4740, 5100, 5159, 5500,
8319) as *expected never-healed-schedule artifacts* — a sustained drop window and/or
partitions never healed before end-of-run, so the cluster correctly makes no progress
(safety preserved). The RR-103 row names the per-peer inflight-window leak as a *candidate
root-cause component* of this family. **Predicted:** after the RR-103 fix, (a) if RR-103
was causal, some of the 7 seeds now elect a leader; (b) if not, all 7 still stall and each
is explained by a network that is *still faulted at end-of-run* (never-healed), not by a
healed-but-stuck state.

Architecture basis: §6 (CheckQuorum/PreVote) — a cluster without a quorum-capable
component correctly elects no leader; this is correct behavior, not a defect. A genuine
liveness defect is a cluster that **heals** and still fails to make progress in bounded
time (the standard Raft liveness guarantee under eventual delivery — ConsensusSpec
`WF_vars(Next)` / `EdgePropagationLiveness`).

## 2. Injection / method

1. **Per-seed re-run** (`Rr095StallSeedDiagnosisTest`, testkit): replay each of the 7
   seeds against the RR-103-fixed kernel (5 nodes × 1500 ticks, the registered config),
   report `leaderElected`, and read the **authoritative end-of-run network state** (new
   diagnosis seams `AdversarialNetwork.dropRateForTest()` / `activePartitionsForTest()`).
2. **10k re-sweep** (`AdversarialSimTest#nightlyAdversarialSweep`, post-fix): the
   aggregate stall count vs the S2 baseline of 7.
3. **First-class bounded-progress liveness checking** (`LivenessBoundedProgressSweepTest`,
   testkit): a scripted shatter/heal sweep that applies a recovery DEADLINE only *after*
   the fault clears — the only honest way to tell a never-healed stall (benign) from a
   healed-but-stuck defect.

Repro:
```
./mvnw -pl configd-testkit test -Dtest=Rr095StallSeedDiagnosisTest
./mvnw -pl configd-testkit test -Dtest=AdversarialSimTest#nightlyAdversarialSweep -Dconfigd.adversarial.nightly=true
./mvnw -pl configd-testkit test -Dtest=LivenessBoundedProgressSweepTest   # add -Dconfigd.liveness.sweepCount=N
```

## 3. Observation

- **Per-seed (capture `rr095-perseed-diagnosis.txt`):** all 7 seeds `leaderElected=false`
  (unchanged) and **every one ends with a sustained drop rate of 0.384–0.498 and/or
  active partitions** — the network is still adversarially faulted at end-of-run. Each is
  classified NEVER-HEALED ARTIFACT (benign); none is HEALED-BUT-STUCK. Per-seed line, e.g.
  `seed=452 leaderElected=false endDropRate=0.445 endPartitions=5 … NEVER-HEALED`.
- **10k re-sweep (capture `rr103-10k-resweep.txt`):** `elected=9993 livenessStalls=7
  safetyViolations=0` — stall count **unchanged from the S2 baseline of 7**.
- **Bounded-progress liveness sweep (200 seeds, fixed kernel):** 0 liveness violations;
  measured worst post-heal convergence **48 ticks** (mean 21), majority re-election worst
  995 ticks, bootstrap election worst 398 ticks (`liveness-sweep-fixed.txt`).
- **Live-net proof (capture `liveness-sweep-rr103-livenet-proof.txt`):** with the RR-103
  decay reverted, the SAME sweep fails at seed 0 — "after heal the cluster did not return
  to full service … the rejoined minority stayed behind" — so the sweep genuinely detects
  the leak class, not just the targeted unit test.

## 4. Verdict

- **RR-103 is NOT a root-cause component of the RR-095 family.** The stall count is
  unchanged (7→7) and each of the 7 is independently diagnosed as a never-healed
  adversarial schedule. The RR-103-row hypothesis ("candidate root-cause") is **refuted**
  for this family (recorded honestly, not absorbed).
- **RR-095 → ACCEPTED-RISK** (charter §0): expected never-healed-schedule artifacts, 0
  safety impact, each of the 7 individually diagnosed (no blob). Re-reviewed in S7.
- **Liveness checking is now first-class** (CODE deliverable, not a finding): bounded
  post-heal election + propagation assertions, swept across seeds, proven to be a live net
  for the recoverable-but-stuck class. This closes the asymmetry the charter named
  (safety had 10k-seed coverage; liveness had anecdotes).

## 5. Recovery bounds

Recorded in `recovery-bounds.md`: post-heal minority→whole-cluster convergence (worst 48
ticks), majority re-election (worst 995), bootstrap election (worst 398). Never-healed
seeds have **no** recovery bound (no recovery was ever possible) — a liveness non-event,
not a measurement.

## 6. Gate

`LivenessBoundedProgressSweepTest` (fast: 200 seeds ≈ 1.5 s) belongs in the gate-4 seed
set as the bounded-progress liveness verdict (charter DoD).

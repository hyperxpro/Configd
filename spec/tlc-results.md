# TLC Model Checking Results

> **Regenerated 2026-06-06 (Session A2) from the live `.cfg` files.** Supersedes the prior
> single-spec, 2026-04-10 version (which listed the since-removed `NoStaleOverwrite` and omitted
> `LeaderCompleteness`/`VersionMonotonicity`). All three specs now run in CI
> (`.github/workflows/ci.yml`). Results below are from a live re-run on this machine
> (Corretto 25, `tla2tools.jar` v1.8.0, `-workers auto`).

## Summary

**Session-2 full re-run (2026-06-11, this box, `-workers 2`, with the new
invariants/actions/variables):**

| Spec | Constants | States | Distinct | Result | Wall |
|---|---|---|---|---|---|
| ConsensusSpec | Nodes=3, MaxTerm=**2**, MaxLogLen=3, Values={v1,v2}, ACK_ON_APPEND=FALSE | 9,735,403 | 2,285,001 | **No error** | 28m29s |
| ReadIndexSpec | Nodes=3, MaxTerm=2, MaxIndex=2 | 10,743,169 | 2,036,131 | **No error** | 4m25s |
| SnapshotInstallSpec | Nodes=3, MaxTerm=2, MaxIndex=**2**, PERSIST_BEFORE_TRUNCATE=TRUE | 18,112,899 | 1,797,096 | **No error** | 13m26s |

> **Bound reductions (B7 — authorized by the task: "document and reduce bounds with
> justification" when the new actions blow up runtime >3x historical):**
> - **ConsensusSpec MaxTerm 3→2.** The `acked` variable (capped to one ack position) +
>   `ClientAck` + `AckImpliesCommitted` (RR-004) multiply the reachable space against the
>   term/reconfig product. At historical MaxTerm=3/MaxLogLen=3 the full run did NOT exhaust
>   in 40 min (16.6M states / 4.19M distinct / depth 19, 1.2M queued, **0 violations** in the
>   explored prefix). At MaxTerm=2 it EXHAUSTS (2.285M distinct, No error, 28m29s). Election +
>   re-election, joint reconfiguration, client writes, edge propagation, and the
>   commit-confirmed ack cycle all stay reachable. The ack≠commit defect is proven at full
>   term dynamics by `ConsensusSpec-ackonappend.cfg`.
> - **SnapshotInstallSpec MaxIndex 4→2.** The RR-003 durability model (4 new per-node
>   variables + always-enabled `CrashRestart`) is a fundamental state-space change; see the
>   per-spec note below.
> - **ReadIndexSpec** kept its historical bounds — the `~HasServeableRead` liveness gate
>   slightly REDUCES the safety space (2.036M vs the prior 2.276M distinct), no change needed.
>
> **Historical (pre-Session-2):** ConsensusSpec 3.30M distinct @ MaxTerm=3; ReadIndexSpec
> 2.276M distinct; SnapshotInstallSpec 847K distinct @ MaxIndex=4.

---

> **Session 2 (RR-004/RR-003/RR-026) update.** ConsensusSpec gained the `acked` variable +
> `ClientAck` action + `AckImpliesCommitted` invariant (ADR-0033); SnapshotInstallSpec gained a
> durability model (`durIndex`/`walBase`/`walTip`, persist/truncate/crash-restart actions) +
> `DurablePrefix`/`RecoveredCoversCommitted` invariants (RR-003); ReadIndexSpec gained a fair
> `LiveSpec` + the `ReadEventuallyServed` liveness property (RR-026). Two **spec-level
> test-the-tester** counterexample cfgs prove the new invariants are non-vacuous (below).

## ConsensusSpec
- **Config:** `ConsensusSpec.cfg` · Nodes={n1,n2,n3}, MaxTerm=2 (reduced from 3 — see Summary),
  MaxLogLen=3, Values={v1,v2}, `ACK_ON_APPEND=FALSE`
- **Full result (Session-2):** 9,735,403 states / 2,285,001 distinct / No error / 28m29s.
- **Invariants (live):** TypeOK, ElectionSafety, StateMachineSafety, LeaderCompleteness, LogMatching,
  VersionMonotonicity, ReconfigSafety, SingleServerInvariant, NoOpBeforeReconfig, **AckImpliesCommitted**
  (new, RR-004) — **all PASS**.
- **Smoke (`gates/spec-smoke/`, MaxLogLen=2):** 122,184 distinct / No error / 43s (the `acked` variable
  enlarges the space; MaxLogLen cut 3→2 for the smoke bound — see the cfg header).
- **AckImpliesCommitted non-vacuity (test-the-tester):** `spec/ConsensusSpec-ackonappend.cfg`
  (`ACK_ON_APPEND=TRUE`) makes TLC report **`AckImpliesCommitted is violated`** in ~1s — a write acked on
  local append (pre-commit) is truncated on a leader change and lost (the RR-004 defect). Capture:
  `docs/session-2/captures/spec-ack-on-append-counterexample.txt`.

## ReadIndexSpec
- **Config:** `ReadIndexSpec.cfg` · Nodes={n1,n2,n3}, MaxTerm=2, MaxIndex=2
- **Invariants (live):** TypeOK, ElectionSafety, ReadIndexBoundedByMaxIndex, ReadFreshness,
  NoStaleLeaderServe — **all PASS**.
- **Full result (Session-2):** 10,743,169 states / 2,036,131 distinct / No error / 4m25s.
- **Liveness (RR-026, NEW):** `LiveSpec == Init /\ [][Next]_vars /\ FairNext` (weak fairness on
  CompleteReadIndex / ApplyEntry / ReadHeartbeatAck) checks **`ReadEventuallyServed`** (a serveable read
  never starves) **GREEN** at smoke bounds (`gates/spec-smoke/ReadIndexSpec-liveness.cfg`, 24s).
  **Wrong-fairness vacuity proven:** the same property under the UNFAIR base `Spec`
  (`ReadIndexSpec-livenessvacuity.cfg`) is **VIOLATED** — the green result is earned by the fairness, not
  vacuous. Capture: `docs/session-2/captures/spec-readindex-liveness-vacuity.txt`. This is the first
  liveness property ever model-checked in this repo (closes RR-026 on this spec).
- **R-05c (Session A2):** `ReadFreshness` and `NoStaleLeaderServe` were **de-vacuumed** — they
  previously had a literal `TRUE` consequent (could never fail). They now constrain real state:
  `ReadFreshness` asserts every served read's `readIdx <= appliedIndex[server]` (a read never
  reports ahead of the applied state — the F-0009 property); `NoStaleLeaderServe` asserts every
  served read's `term <= currentTerm[server]`. Both are non-vacuous (proven by a seeded buggy-action
  counterexample) and still model-check green.

## SnapshotInstallSpec
- **Config:** `SnapshotInstallSpec.cfg` · Nodes={n1,n2,n3}, MaxTerm=2, MaxIndex=2 (was MaxTerm=3/MaxIndex=4;
  reduced for the durability model — see the cfg header rationale), `PERSIST_BEFORE_TRUNCATE=TRUE`
- **Invariants (live):** TypeOK, SnapshotBoundedByCommitted, SnapshotMatching, NoCommitRevert,
  InflightTermMonotonic, **DurablePrefix** (new, RR-003), **RecoveredCoversCommitted** (new, RR-003) —
  **all PASS**.
- **Smoke (`gates/spec-smoke/`, MaxIndex=1):** 6,915 distinct / No error / 4s (durability model + always-
  enabled crash-restart enlarge the space; MaxIndex cut to 1 for the smoke bound — the full
  commit→WAL→snapshot→persist→truncate→crash cycle is still exercised).
- **Full result (Session-2, MaxIndex=2):** 18,112,899 states / 1,797,096 distinct / No error / 13m26s.
  (At the historical MaxIndex=4 the durability model is intractable; MaxIndex=2 exhausts and still
  exercises snapshot-of-snapshot + the full commit→WAL→snapshot→persist→truncate→crash cycle + install.)
- **DurablePrefix non-vacuity (test-the-tester):** `spec/SnapshotInstallSpec-truncatebeforepersist.cfg`
  (`PERSIST_BEFORE_TRUNCATE=FALSE`) makes TLC report **`DurablePrefix is violated`** in ~1s — the WAL
  prefix is truncated while the snapshot is RAM-only, so a crash/restart leaves a committed index in
  neither the durable snapshot nor the durable WAL (the RR-003 silent-data-loss defect). Capture:
  `docs/session-2/captures/spec-truncate-before-persist-counterexample.txt`.
- **Full result:** _see Summary table (Session-2 re-run)._
- **R-05c (Session A2):** `NoCommitRevert` was **de-vacuumed** — it was `P ∨ ¬P` (a tautology). It
  now asserts that an in-flight InstallSnapshot which would install (`lastIncludedIndex >
  snapshot[to].index`) must carry `lastIncludedTerm >= snapshot[to].term` (a higher-index snapshot
  never reverts the term). Non-vacuous (seeded-bug counterexample) and green.

---

## Bugs Found and Fixed During Model Checking (ConsensusSpec — historical)

TLC uncovered several spec bugs that were fixed before the final successful run.

> Note: `NoStaleOverwrite` (Bug 6 below) was subsequently **removed** from `ConsensusSpec` as
> redundant with `StateMachineSafety`; it is no longer in `ConsensusSpec.cfg`.

### Bug 1: Out-of-bounds tuple access in AppendEntry
- **Symptom:** `RuntimeException: Attempted to access index 0 of tuple <<>>`
- **Cause:** TLC evaluates both branches of `\/` even when the first is TRUE, causing `log[m][prevIdx]` to be evaluated when `prevIdx = 0`.
- **Fix:** Changed `(prevIdx = 0 \/ ...)` to `IF prevIdx = 0 THEN TRUE ELSE ...`.

### Bug 2: Missing log truncation in AppendEntry
- **Symptom:** LogMatching invariant violation.
- **Cause:** Overwriting a single entry via `EXCEPT` left stale suffix entries with inconsistent terms.
- **Fix:** `Append(SubSeq(log[m], 1, idx - 1), entry)` to truncate from the overwrite point.

### Bug 3: Missing follower step-down on AppendEntries
- **Symptom:** ElectionSafety violation (two leaders in same term after reconfig).
- **Cause:** Follower stepped down only when `currentTerm[n] > currentTerm[m]`, not on equal terms.
- **Fix:** Unconditionally set the receiver to "follower" upon accepting AppendEntries.

### Bug 4: Configuration not recomputed on log truncation
- **Symptom:** ReconfigSafety violation.
- **Cause:** Stale config persisted when a config entry was overwritten by a non-config entry.
- **Fix:** Added `EffectiveConfig(logSeq)` and recompute the follower's config after truncation.

### Bug 5: SingleServerInvariant too strict for inherited entries
- **Symptom:** SingleServerInvariant violation.
- **Cause:** Counted ALL uncommitted config entries, including those inherited from prior terms.
- **Fix:** Restricted the count to config entries in the leader's own current term.

### Bug 6: NoStaleOverwrite too strict
- **Symptom:** NoStaleOverwrite violation.
- **Cause:** Required committed entries identical across ALL nodes, even un-replicated ones.
- **Fix:** Compared only indices both nodes committed — i.e. it collapsed into `StateMachineSafety`,
  so the invariant was later removed as redundant.

### Bug 7: Leader not counting itself in quorum
- **Improvement:** AdvanceCommitIndex now includes the leader in the quorum agreement set.

### Non-bug: Deadlock at model bounds
- At MaxTerm/MaxLogLen bounds no actions are enabled (expected). `CHECK_DEADLOCK FALSE` set.

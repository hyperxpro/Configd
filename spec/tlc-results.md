# TLC Model Checking Results

> **Regenerated 2026-06-06 (Session A2) from the live `.cfg` files.** Supersedes the prior
> single-spec, 2026-04-10 version (which listed the since-removed `NoStaleOverwrite` and omitted
> `LeaderCompleteness`/`VersionMonotonicity`). All three specs now run in CI
> (`.github/workflows/ci.yml`). Results below are from a live re-run on this machine
> (Corretto 25, `tla2tools.jar` v1.8.0, `-workers auto`).

## Summary

| Spec | Constants | States | Distinct | Depth | Result |
|---|---|---|---|---|---|
| ConsensusSpec | Nodes=3, MaxTerm=3, MaxLogLen=3, Values={v1,v2} | 13,775,323 | 3,299,086 | 25 | No error |
| ReadIndexSpec | Nodes=3, MaxTerm=2, MaxIndex=2 | 12,403,444 | 2,276,125 | 38 | No error |
| SnapshotInstallSpec | Nodes=3, MaxTerm=3, MaxIndex=4 | 5,995,717 | 847,124 | 14 | No error |

---

## ConsensusSpec
- **Config:** `ConsensusSpec.cfg` · Nodes={n1,n2,n3}, MaxTerm=3, MaxLogLen=3, Values={v1,v2}
- **Invariants (live):** TypeOK, ElectionSafety, StateMachineSafety, LeaderCompleteness, LogMatching,
  VersionMonotonicity, ReconfigSafety, SingleServerInvariant, NoOpBeforeReconfig — **all PASS**.
- **Result:** 13,775,323 states / 3,299,086 distinct / depth 25 / No error.

## ReadIndexSpec
- **Config:** `ReadIndexSpec.cfg` · Nodes={n1,n2,n3}, MaxTerm=2, MaxIndex=2
- **Invariants (live):** TypeOK, ElectionSafety, ReadIndexBoundedByMaxIndex, ReadFreshness,
  NoStaleLeaderServe — **all PASS**.
- **Result:** 12,403,444 states / 2,276,125 distinct / depth 38 / No error.
- **R-05c (Session A2):** `ReadFreshness` and `NoStaleLeaderServe` were **de-vacuumed** — they
  previously had a literal `TRUE` consequent (could never fail). They now constrain real state:
  `ReadFreshness` asserts every served read's `readIdx <= appliedIndex[server]` (a read never
  reports ahead of the applied state — the F-0009 property); `NoStaleLeaderServe` asserts every
  served read's `term <= currentTerm[server]`. Both are non-vacuous (proven by a seeded buggy-action
  counterexample) and still model-check green.

## SnapshotInstallSpec
- **Config:** `SnapshotInstallSpec.cfg` · Nodes={n1,n2,n3}, MaxTerm=3, MaxIndex=4
- **Invariants (live):** TypeOK, SnapshotBoundedByCommitted, SnapshotMatching, NoCommitRevert,
  InflightTermMonotonic — **all PASS**.
- **Result:** 5,995,717 states / 847,124 distinct / depth 14 / No error.
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

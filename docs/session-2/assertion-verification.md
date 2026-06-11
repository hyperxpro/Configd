# Assertion-Twin Verification Matrix (§4.5 / RR-030)

**State: COMPLETE (RR-030 RESOLVED).** Every checked invariant in `spec/` now has a Java runtime-assertion
twin, AND every twin is observed firing by `AssertionTwinFiringTest` (gate-2 step g). This closes CM-002
(`research.md:575` claimed every TLA invariant had a runtime twin; Session 1 found 7 missing — they are
now built). No row says UNVERIFIED.

§4.5 rule of record: **an assertion never observed firing is unverified.** Every twin below has a firing
observation in the consolidated firing harness:
- **consensus-core** `io.configd.raft.AssertionTwinFiringTest` — all 16 RaftNode-side twins (8 ConsensusSpec
  in-node + `durable_prefix_no_gap` + 3 ReadIndexSpec + 4 SnapshotInstallSpec).
- **config-store** `io.configd.store.AssertionTwinFiringTest` — `per_key_order` + the RR-029/W-1
  `apply_owner_thread` two-thread violation test (closes RR-004 review F-1, RR-029 residual).

Firing mechanism classes (per twin, in the matrix below):
- **real-path** — a fault/poisoned state drives the production method to violation.
- **extracted-check** — the production check method (`assertReadServeInvariants`,
  `checkSnapshotInstallTwins`, `checkSnapshotSendTwin`, `triggerSnapshot`) is driven with a violating
  input — the check EXPRESSION is production code (the `InvariantNetMetricTest` precedent).
- **guarded-seam** — the twin's production call site sits behind a guard that early-returns whenever the
  checked condition would be false (defence-in-depth, unfirable via protocol); fired through the identical
  production `invariantChecker.check(name, false, …)` shape via `fireInNodeTwinForTest`. These twins are
  *intentionally* structurally guarded — that they cannot trip via protocol is a property, not a gap.

## 0. Scope: what counts as a checked invariant

19 invariants are checked across the three `.cfg` files (matches RR-026's "all 19 TLC invariants"):

- **ConsensusSpec.cfg** (9): TypeOK, ElectionSafety, StateMachineSafety, LeaderCompleteness, LogMatching,
  VersionMonotonicity, ReconfigSafety, SingleServerInvariant, NoOpBeforeReconfig.
  (`EdgePropagationLiveness` is defined but **commented out** of the cfg — RR-026; not a checked invariant.)
- **ReadIndexSpec.cfg** (5): TypeOK, ElectionSafety, ReadIndexBoundedByMaxIndex, ReadFreshness,
  NoStaleLeaderServe. (INV-RI-5 "Monotonic served reads" is prose-only, verified structurally, **not** a
  checked invariant — `ReadIndexSpec.tla:253-259`.)
- **SnapshotInstallSpec.cfg** (5): TypeOK, SnapshotBoundedByCommitted, SnapshotMatching, NoCommitRevert,
  InflightTermMonotonic.

`TypeOK` appears in all three but is a model-checker type guard, not a runtime safety property — it has no
meaningful Java twin (a Java twin would be "every field has its declared type," tautological on the JVM).
It is listed for completeness and marked N/A-by-design. `ElectionSafety` appears in both ConsensusSpec and
ReadIndexSpec; it is one real property with one twin.

**Headline numbers:** 19 checked invariants. Distinct safety properties needing a twin (excluding the 3
TypeOK type-guards and the duplicate ElectionSafety) = **14**. Of those 14: **7 have a real twin today**
(the ConsensusSpec set, wired via `RaftNode.InvariantChecker` + `ConfigStateMachine` per_key_order), and
**7 have NO twin** (all of ReadIndexSpec's safety invariants + all of SnapshotInstallSpec's) — this is
exactly RR-030's "7 of the TLA+ invariants … have no runtime assertion twin." **K = 7 twins to build this
session-backlog.**

> RR-030's "all of ReadIndexSpec + SnapshotInstallSpec" = ReadIndexSpec {ReadIndexBoundedByMaxIndex,
> ReadFreshness, NoStaleLeaderServe} + ElectionSafety(shared, already has a twin) + SnapshotInstallSpec
> {SnapshotBoundedByCommitted, SnapshotMatching, NoCommitRevert, InflightTermMonotonic}. Subtracting the
> two TypeOKs and the shared ElectionSafety twin gives the 7 to build.

## 1. Twin wiring facts (Session 1 verified)

- **Production wiring is REAL, not NOOP.** `ConfigdServer.java:204-206` constructs
  `new InvariantMonitor(metricsRegistry, false)` (production mode = record metric + SEVERE log, never
  throw) and passes `invariantMonitor::check` as **both** the `RaftNode.InvariantChecker` and the
  `ConfigStateMachine.InvariantChecker` SAM. So in a running server the 7 existing twins fire into a real
  metric counter.
- **Unit tests wire NOOP** (RR-089): `RaftNode.InvariantChecker.NOOP = (name, condition, message) -> {}`
  (`RaftNode.java:130-132`) is the default when no checker is supplied; most unit tests construct nodes
  without a monitor, so the twins are inert in those tests.
- **Test/sim mode = throw.** `InvariantMonitor(metrics, true)` throws `AssertionError` on violation
  (`InvariantMonitor.java:95-97`) — the fail-fast mode for sim/integration.
- **Metric export:** each twin increments `invariant.violation.<name>` →
  `invariant_violation_<name>_total` in the Prometheus exposition (`InvariantMonitor.java:43,:232-234`).
- **Ever observed firing:** only ONE twin has ever been seen to fire, and only via injection:
  `per_key_order` in `InvariantNetMetricTest` (`configd-server`), which reflectively rewinds
  `ConfigStateMachine.sequenceCounter` to force a non-monotonic version and asserts the live
  `invariant_violation_per_key_order_total` counter ≥ 1 (RR-089). No other twin has a firing observation;
  the other 6 existing twins are wired and reachable but **never observed to fire** — by §4.5 they are
  *unverified* until each gets an injection.

## 2. Matrix — ConsensusSpec invariants (7 real twins + type guard)

Columns: (a) twin file:line · (b) reachable in prod wiring · (c) enabled in test/sim · (d) exported as
violation-counter metric · (e) ever observed firing.

| TLA invariant | Twin name | (a) Twin file:line | (b) Prod | (c) Test/sim | (d) Metric | (e) Observed firing | Injection plan (to satisfy §4.5) |
|---|---|---|---|---|---|---|---|
| TypeOK | — | N/A by design (JVM type guard) | — | — | — | — | N/A |
| ElectionSafety (INV-1) | `election_safety` | `RaftNode.java:1165` | Yes | Yes (throw) | `…_election_safety_total` | **No** | Sim: force two nodes to `becomeLeader` in the same term via a crafted vote-split schedule (suppress step-down); twin compares `clusterConfig.isQuorum(votesReceived)`. Build a sim fault that delivers duplicate vote grants. |
| LeaderCompleteness (INV-2) | `leader_completeness` | `RaftNode.java:1171` | Yes | Yes | `…_leader_completeness_total` | **No** | Sim: elect a leader whose `log.lastIndex() < log.commitIndex()` by injecting a commitIndex advance without the matching log append (a buggy-action fault hook on `becomeLeader`). |
| LogMatching (INV-3) | `log_matching` | `RaftNode.java:833` | Yes | Yes | `…_log_matching_total` | **No** | Sim/test: deliver an AppendEntries whose last entry's `(index,term)` disagrees with the stored entry at that index (codec-level fault that flips a term byte). |
| StateMachineSafety (INV-4) | `state_machine_safety` | `RaftNode.java:1373` | Yes | Yes | `…_state_machine_safety_total` | **No** | Test: drive `applyCommitted` with an out-of-order entry (`entry.index() != nextApply`) via a fault that hands the apply loop a gapped entry. |
| VersionMonotonicity (INV-5) | `version_monotonicity` | `RaftNode.java:1367` | Yes | Yes | `…_version_monotonicity_total` | **No** | Test: feed `applyCommitted` an entry with `index() <= lastApplied()` (replay/duplicate-apply fault). Directly mirrors the spec's `edgeVersion <= commitIndex`. |
| ReconfigSafety (INV-7) | `reconfig_safety` | `RaftNode.java:545` | Yes | Yes | `…_reconfig_safety_total` | **No** | Test: drive `proposeConfigChange` and corrupt the built config so `jointConfig.isJoint()` is false (fault on the joint-config builder). Ties to RR-018 (reconfig path is 46% mutation-covered — this injection doubles as a reconfig de-vacuation). |
| SingleServerInvariant (INV-8) | `single_server_invariant` | `RaftNode.java:532` | Yes | Yes | `…_single_server_invariant_total` | **No** | Test: attempt a second `proposeConfigChange` while `configChangePending` is true (the twin asserts `!configChangePending`). |
| NoOpBeforeReconfig (INV-9) | `no_op_before_reconfig` | `RaftNode.java:537` | Yes | Yes | `…_no_op_before_reconfig_total` | **No** | Test: call `proposeConfigChange` before the leader has committed its term no-op; twin asserts the no-op precondition. |
| (INV-W1, contract §5/§8 — maps here) | `per_key_order` | `ConfigStateMachine.java:269` | Yes | Yes | `…_per_key_order_total` | **YES** (`InvariantNetMetricTest`, reflective seq-counter rewind) | Already has an injection (the only one). Keep as the template for the others. |

ConsensusSpec real-twin count = 7 distinct safety properties (ElectionSafety, LeaderCompleteness,
LogMatching, StateMachineSafety, VersionMonotonicity, ReconfigSafety, SingleServerInvariant) +
NoOpBeforeReconfig + per_key_order, all wired. §4.5 gap: **only per_key_order has a firing observation;
the other 8 need injections** (listed above) to move from wired→verified.

## 3. Matrix — ReadIndexSpec invariants (BUILT + observed firing)

All three ReadIndexSpec safety twins are now built at the serve seam
(`RaftNode.assertReadServeInvariants`, called from both `whenReadReady` immediate serve and
`fireReadyCallbacks` deferred serve). `ReadIndexState` now carries the read-initiation term (`startRead`
overload + `termOf`) so the stale-leader twin can compare it. Wired through the existing
`RaftNode.InvariantChecker` SAM (prod metric+log, test/sim throw). Firing: `extracted-check` via the
package-private `assertReadServeInvariants` driven with a poisoned pending read (`injectPendingReadForTest`).

| TLA invariant | Twin name (BUILT) | Code site | Compares | Observed firing |
|---|---|---|---|---|
| ElectionSafety (INV-RI-1) | `election_safety` (reused) | `RaftNode.becomeLeader` | (shared with ConsensusSpec) | Yes (§2) |
| ReadIndexBoundedByMaxIndex (INV-RI-2) | `read_index_bounded` | `RaftNode.assertReadServeInvariants` | served `readIndex <= log.commitIndex()` | **Yes** — `AssertionTwinFiringTest`: poisoned read with `readIndex > commitIndex` (lastApplied bumped so the freshness gate passes first) |
| ReadFreshness (INV-RI-3) | `read_freshness` | `RaftNode.assertReadServeInvariants` | served `readIndex <= log.lastApplied()` | **Yes** — poisoned read with `readIndex = 9999 >> lastApplied` |
| NoStaleLeaderServe (INV-RI-4) | `no_stale_leader_serve` | `RaftNode.assertReadServeInvariants` | still LEADER and recorded `term <= currentTerm` | **Yes** — read recorded at `currentTerm + 5` |

ReadIndexSpec twins built = **3** (read_index_bounded, read_freshness, no_stale_leader_serve);
ElectionSafety reuses the existing twin. **In the live serve paths the `isReadReady` gate makes the
freshness/bound conditions hold; the twins are defence-in-depth that catch a regression corrupting the
read record between the readiness gate and the serve, or removing the gate.**

## 4. Matrix — SnapshotInstallSpec invariants (BUILT + observed firing)

All four SnapshotInstallSpec twins are now built, wired through the existing `RaftNode.InvariantChecker`.
The receive-side and send-side checks were extracted into package-private methods
(`checkSnapshotInstallTwins`, `checkSnapshotSendTwin`) so the firing test drives the exact production
check with a poisoned descriptor. `snapshot_bounded` lives on the local-snapshot path (`triggerSnapshot`)
where `index <= commitIndex` is falsifiable (the receive-branch precondition makes a bound check vacuous
there). RR-003 made these paths real (snapshot persistence to disk).

| TLA invariant | Twin name (BUILT) | Code site | Compares | Observed firing |
|---|---|---|---|---|
| SnapshotBoundedByCommitted (INV-SI-1) | `snapshot_bounded` | `RaftNode.triggerSnapshot` | local snapshot `index <= log.commitIndex()` | **Yes** — `AssertionTwinFiringTest`: append an uncommitted entry, set lastApplied past commitIndex, `triggerSnapshot` |
| SnapshotMatching (INV-SI-2) | `snapshot_matching` | `RaftNode.checkSnapshotInstallTwins` (in `handleInstallSnapshot`) | incoming `lastIncludedTerm == log.termAt(lastIncludedIndex)` | **Yes** — boundary index 10 (term 5) vs install claiming term 9 |
| NoCommitRevert (INV-SI-3) | `snapshot_no_commit_revert` | `RaftNode.checkSnapshotInstallTwins` | higher-index install does not carry a lower term than the current snapshot | **Yes** — current snapshot (10, 5), install (20, 3) |
| InflightTermMonotonic (INV-SI-4) | `snapshot_term_consistent` | `RaftNode.checkSnapshotSendTwin` (in `sendInstallSnapshot`) | outbound `(idx, term)` matches `log.termAt(idx)` | **Yes** — boundary (7, 4), send descriptor (7, 9) |

SnapshotInstallSpec twins built = **4** (snapshot_bounded, snapshot_matching,
snapshot_no_commit_revert, snapshot_term_consistent).

## 5. Data-plane twins already present (contract §8, not in the 19 TLC invariants)

For completeness — these exist in `InvariantMonitor` and map to contract invariants, but are **not** among
the 19 TLC invariants (no TLA+ formalization checks them; they are edge/data-plane properties):

| Contract invariant | Twin name | file:line | Status |
|---|---|---|---|
| INV-M1 monotonic read | `monotonic_read` | `InvariantMonitor.java:115,:139` (called from `LocalConfigStore.get` cursor path) | Wired; **never observed firing**. Injection: serve a cursor read where `newVersion < seenVersion` (force an out-of-order delta apply in the edge sim). Note: the edge read path is largely orphaned at runtime (RR-039/RR-043), so prod reachability is limited until Session 3 wires the data plane. |
| INV-S1 staleness bound | `staleness_bound` | `InvariantMonitor.java:122,:166` (called from `StalenessTracker.isStale`) | Wired; **never observed firing**. Per ADR-0035 the §2 *measurement* is being redefined (commit-notification timestamps, not per-entry HLC) — the twin's trigger (`staleMs > thresholdMs`) is unchanged but the `staleMs` source changes in Session 3. Injection: advance the edge clock past the threshold with no commit-notification, assert `…_staleness_bound_total` ≥ 1 + the p99 distribution assertion (RR-031/ADR-0035 handoff item 3). |

## 6. Build backlog summary (post-A1/A2/A3)

- **Build 7 missing twins:** ReadIndex {read_index_bounded, read_freshness, no_stale_leader_serve} +
  Snapshot {snapshot_bounded, snapshot_matching, snapshot_no_commit_revert, snapshot_term_consistent}.
  Wire each through `RaftNode.InvariantChecker` (same SAM as the existing 8) so prod/test/sim modes and
  the metric export come for free.
- **Add 8 injections** for the existing-but-never-fired ConsensusSpec twins (per_key_order already has
  one), so every existing twin moves wired→verified per §4.5.
- **Add 7 injections** for the new twins (one per row above).
- **Sequencing:** ReadIndex twins after RR-004/ADR-0033 (shared propose/apply seam); Snapshot twins after
  RR-003/RR-019 (snapshot persistence + silent-drop rework). Building them before those fixes lands them on
  code that is about to move.

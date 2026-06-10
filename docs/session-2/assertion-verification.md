# Assertion-Twin Verification Matrix (§4.5 / RR-030)

**State: DRAFT.** This inventories every checked invariant in `spec/` against its Java runtime-assertion
twin, per the documented invariant→assertion methodology (`research.md:575` claims every TLA invariant has
a runtime twin; CM-002 found that false). The "twin to BUILD" and "injection plan" columns are the
build backlog; **the build work happens after the A1/A2/A3 fixes land** (the apply/propose seams these
twins hang on are being changed by RR-004/ADR-0033 and RR-003). Nothing here is wired this session.

§4.5 rule of record: **an assertion never observed firing is unverified.** Every twin (existing or to-build)
needs an injection path that makes it fire at least once in test/sim, or it is a NOOP that happens to
compile.

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

## 3. Matrix — ReadIndexSpec invariants (twins to BUILD)

No runtime twin exists for any of these (RR-030). The ReadIndex path in code is `RaftNode.whenReadReady` /
the ReadIndex dispatch at `ConfigdServer.java:483-510`; ADR-0033 is changing the adjacent propose/apply
seam, so these twins land **after** A1/RR-004.

| TLA invariant | Maps to | (a) Twin to BUILD — where the check belongs / what it compares | Injection plan |
|---|---|---|---|
| ElectionSafety (INV-RI-1) | same property as ConsensusSpec `election_safety` | **No new twin** — the existing `election_safety` twin (`RaftNode.java:1165`) covers it; the ReadIndex spec re-checks it only as a sanity guard. | (covered by §2 election_safety injection) |
| ReadIndexBoundedByMaxIndex (INV-RI-2) | bound check (model artifact) | Marginal as a runtime twin (`readIdx <= MaxIndex` is a model-bound check). **Build as** `read_index_bounded`: in the ReadIndex completion path, assert the served `readIdx <= log.commitIndex()` at serve time (the real-system analogue: a served read's index never exceeds what was committed). Belongs in the ReadIndex serve seam (`whenReadReady` completion / `completeRead`). | Fault: force the serve path to record a `readIdx > commitIndex` (inject a stale commitIndex snapshot into the read record), assert the counter fires. |
| ReadFreshness (INV-RI-3) | linearizable read freshness (F-0009) | **Build as** `read_freshness`: at ReadIndex serve time assert `servedReadIdx <= log.lastApplied()` — a read is never served ahead of the applied state machine. Belongs exactly where the read result is dispatched (the `whenReadReady`/`completeRead` callback at the ReadIndex completion). Compares the read's recorded `readIdx` against `lastApplied` at serve. | Fault: serve a ReadIndex before `lastApplied` reaches `readIdx` (suppress the "wait for apply" gate via a test hook), assert `…_read_freshness_total` ≥ 1. This is the direct runtime counterpart of the de-vacuumed spec invariant. |
| NoStaleLeaderServe (INV-RI-4) | stale/stepped-down leader cannot serve | **Build as** `no_stale_leader_serve`: at serve time assert `readRecord.term == currentTerm` (the leader has not stepped down / term-bumped since recording the read). Belongs in the same serve callback. Compares the term captured at ReadIndex initiation against the node's current term at serve. | Fault: bump the node's term (deliver a higher-term AppendEntries) between read initiation and serve, then let the serve fire; assert the counter increments. |

ReadIndexSpec twins to build = **3** (read_index_bounded, read_freshness, no_stale_leader_serve);
ElectionSafety reuses the existing twin.

## 4. Matrix — SnapshotInstallSpec invariants (twins to BUILD)

No runtime twin exists for any of these (RR-030). The InstallSnapshot path is
`RaftNode.sendInstallSnapshot` / the receive handler (`RaftNode.java:1277-1305`), which RR-003 (snapshot
persistence) and RR-019 (4 MiB silent-drop) are reworking — so these twins land **after** A3/RR-003. The
spec also models the `LocalSnapshot` path, which RR-003 makes real (snapshot persistence to disk).

| TLA invariant | (a) Twin to BUILD — where the check belongs / what it compares | Injection plan |
|---|---|---|
| SnapshotBoundedByCommitted (INV-SI-1) | **Build as** `snapshot_bounded`: on installing (or taking) a snapshot, assert `snapshot.lastIncludedIndex <= log.commitIndex()` — a node never holds a snapshot ahead of its committed state. Belongs in `triggerSnapshot` (local path, `RaftNode.java:329-349`) and the InstallSnapshot receive handler. | Fault: hand the receive handler an InstallSnapshot with `lastIncludedIndex > commitIndex` (codec fault inflating the index), assert `…_snapshot_bounded_total` ≥ 1. |
| SnapshotMatching (INV-SI-2) | **Build as** `snapshot_matching`: when installing a snapshot whose `lastIncludedIndex` equals an index this node still has in its log/prior snapshot, assert the terms agree. Compares incoming `lastIncludedTerm` against the term this node has recorded at that index. Belongs in the receive handler before truncating the log. | Fault: deliver a snapshot at a known index with a flipped `lastIncludedTerm`, assert the counter fires. (Doubles as a guard for RR-019's silent-drop path.) |
| NoCommitRevert (INV-SI-3) | **Build as** `snapshot_no_commit_revert`: in the receive handler, when `incoming.lastIncludedIndex > current snapshot.index`, assert `incoming.lastIncludedTerm >= current snapshot.term` — a higher-index install never reverts the term. Belongs at the install decision point (the spec's `ReceiveInstallSnapshot` "newer — install" branch, `SnapshotInstallSpec.tla:129-135`). | Fault: deliver a higher-index, lower-term snapshot (the spec's exact regression), assert `…_snapshot_no_commit_revert_total` ≥ 1. This is the runtime twin of the de-vacuumed INV-SI-3. |
| InflightTermMonotonic (INV-SI-4) | **Build as** `snapshot_term_consistent`: before sending an InstallSnapshot, assert the leader actually has the entry/snapshot it claims — `lastIncludedTerm == term recorded at lastIncludedIndex` in the leader's own state. Belongs in `sendInstallSnapshot` (`RaftNode.java:1277`). Compares the outgoing `(lastIncludedIndex, lastIncludedTerm)` against the leader's local snapshot/log term. | Fault: corrupt the outgoing snapshot descriptor's term before send (sender-side fault), assert the counter fires on the sender. |

SnapshotInstallSpec twins to build = **4** (snapshot_bounded, snapshot_matching,
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

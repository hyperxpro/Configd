# TLA+ to Runtime Invariant Mapping

> **Production Readiness Review -- Correctness Audit**
> Generated: 2026-04-11
> Auditor: correctness-auditor (PRR gate)

---

## Summary

| Category | Count |
|---|---|
| TLA+ Safety Invariants (INV-1 through INV-9) | 9 |
| TLA+ Liveness Properties (LIVE-1) | 1 |
| Consistency Contract Invariants (INV-L1, INV-S1/S2, INV-M1/M2, INV-V1/V2, INV-W1/W2, INV-RYW1) | 10 |
| **Total Invariants** | **20** |
| Runtime assertion present | 18 |
| Test coverage present | 19 |
| **BLOCKER findings** | **1** |
| **MAJOR findings** | **0** |

---

## TLA+ Safety Invariants

### INV-1: ElectionSafety -- At most one leader per term

| Field | Detail |
|---|---|
| **Spec Location** | `spec/ConsensusSpec.tla:149-151` |
| **Runtime Assertion Location** | `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:869-872` |
| **Runtime Assertion Name** | `election_safety` |
| **Runtime Assertion Logic** | On `becomeLeader()`: asserts `votesGranted >= clusterConfig.quorumSize()` or single-node cluster |
| **Test Coverage** | `configd-testkit/src/test/java/io/configd/testkit/ConsistencyPropertyTests.java:1617-1717` (`ElectionSafetyTest`) -- 3 tests: `atMostOneLeaderPerTerm`, `electionSafetyUnderPartitions`, `electionSafetyWithRandomSeeds` (RepeatedTest x3) |
| **Assertion Mode** | metric (production) / exception (test), controlled by `InvariantChecker` interface |
| **Status** | **PASS** |

**Notes:** The runtime assertion checks that the quorum requirement is met before transitioning to LEADER. The test exercises a 5-node cluster over 2000-3000 ticks, tracking all leaders per term and asserting at most one. Partition injection and random seeds provide adversarial coverage.

---

### INV-2: LeaderCompleteness -- Committed entries present in all future leaders

| Field | Detail |
|---|---|
| **Spec Location** | `spec/ConsensusSpec.tla:155-160` |
| **Runtime Assertion Location** | `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:875-878` |
| **Runtime Assertion Name** | `leader_completeness` |
| **Runtime Assertion Logic** | On `becomeLeader()`: asserts `log.lastIndex() >= log.commitIndex()` -- new leader's log must contain all committed entries |
| **Test Coverage** | `configd-testkit/src/test/java/io/configd/testkit/ConsistencyPropertyTests.java:348-384` (`linearizabilitySurvivesLeaderFailover`) -- commits a value under old leader, partitions it, verifies new leader sees the committed value via ReadIndex |
| **Additional Test Coverage** | `configd-consensus-core/src/test/java/io/configd/raft/RaftNodeTest.java:313-355` (`commitRuleOnlyCurrentTermEntries`) -- verifies Raft Section 5.4.2 safety rule |
| **Assertion Mode** | metric (production) / exception (test) |
| **Status** | **PASS** |

**Notes:** Structurally guaranteed by the `LogUpToDate` restriction in `handleRequestVote`. The runtime assertion is a defense-in-depth check that fires if the structural guarantee is somehow violated.

---

### INV-3: LogMatching -- Same index+term implies same entry and all preceding

| Field | Detail |
|---|---|
| **Spec Location** | `spec/ConsensusSpec.tla:164-167` |
| **Runtime Assertion Location** | `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:585-590` |
| **Runtime Assertion Name** | `log_matching` |
| **Runtime Assertion Logic** | In `handleAppendEntries()`: after appending entries, verifies that the stored entry at the last appended index has the expected term |
| **Test Coverage** | `configd-consensus-core/src/test/java/io/configd/raft/RaftNodeTest.java:629-663` (`followerTruncatesDivergentEntries`) -- creates a divergent log and verifies truncation+replacement. Also `RaftNodeTest.java:665+` (`followerRejectsIfPrevLogDoesNotMatch`) |
| **Assertion Mode** | metric (production) / exception (test) |
| **Status** | **PASS** |

**Notes:** The LogMatching invariant is structurally enforced by the AppendEntries consistency check (prevLogIndex/prevLogTerm must match). The runtime assertion verifies the post-condition after entry application.

---

### INV-4: StateMachineSafety -- No two nodes apply different values at same index

| Field | Detail |
|---|---|
| **Spec Location** | `spec/ConsensusSpec.tla:170-173` |
| **Runtime Assertion Location** | `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:1044-1048` |
| **Runtime Assertion Name** | `state_machine_safety` |
| **Runtime Assertion Logic** | In `applyCommitted()`: asserts `entry.index() == nextApply` -- the entry at the expected apply index matches |
| **Test Coverage** | `configd-testkit/src/test/java/io/configd/testkit/ConsistencyPropertyTests.java:1247-1291` (`allReplicasConvergeToSameOrder` in IntraGroupOrderTest) -- verifies all replicas apply the same entries in the same order. Also covered by `allReplicasSeeIdenticalOrderForSameKey` at line 1056 |
| **Assertion Mode** | metric (production) / exception (test) |
| **Status** | **PASS** |

**Notes:** This is the consequence of LogMatching + commit rules. The runtime assertion verifies the local consistency of the apply sequence. Cross-node verification is done in integration tests by comparing state machine outputs across replicas.

---

### INV-5: VersionMonotonicity -- No version ever decreases at any observer

| Field | Detail |
|---|---|
| **Spec Location** | `spec/ConsensusSpec.tla:176-177` |
| **Runtime Assertion Location** | `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:1037-1040` |
| **Runtime Assertion Name** | `version_monotonicity` |
| **Runtime Assertion Logic** | In `applyCommitted()`: asserts `nextApply > log.lastApplied()` -- applied index must advance monotonically |
| **Additional Runtime Enforcement** | `configd-edge-cache/src/main/java/io/configd/edge/LocalConfigStore.java:165-174` -- `applyDelta()` rejects deltas where `delta.fromVersion() != currentVersion`, preventing version regression. `configd-distribution-service/src/main/java/io/configd/distribution/WatchCoalescer.java:90-93` -- throws on non-monotonic version |
| **Test Coverage** | `configd-testkit/src/test/java/io/configd/testkit/ConsistencyPropertyTests.java:1454-1517` (`VersionMonotonicityEdgeTest`) -- 2 tests. Also `configd-testkit/src/test/java/io/configd/testkit/EndToEndTest.java:253-276` (`edgeVersionIncreasesWithEachDelta`) |
| **Assertion Mode** | metric (production) / exception (test) at Raft layer; exception (always) at edge layer via `IllegalArgumentException` |
| **Status** | **PASS** |

---

### INV-6: NoStaleOverwrite -- Committed entries agree across nodes

| Field | Detail |
|---|---|
| **Spec Location** | `spec/ConsensusSpec.tla:180-185` |
| **Runtime Assertion Location** | Structurally enforced by Raft log replication protocol. No dedicated named assertion; the `state_machine_safety` and `log_matching` assertions jointly enforce this. The TLA+ spec notes INV-6 is equivalent to INV-4 (StateMachineSafety). |
| **Test Coverage** | `configd-testkit/src/test/java/io/configd/testkit/ConsistencyPropertyTests.java:1524-1610` (`NoStaleOverwriteTest`) -- 2 tests: `committedEntrySurvivesLeaderFailure` (verifies committed log terms match after leader change) and `committedValuePersistsAcrossLeaderChange` (verifies committed store values survive failover) |
| **Assertion Mode** | Structural + metric (via INV-3 and INV-4 assertions) |
| **Status** | **PASS** |

**Notes:** The TLA+ spec explicitly states INV-6 is a consequence of INV-4. Both tests verify the property end-to-end with 5-node clusters, partitions, and leader failover.

---

### INV-7: ReconfigSafety -- Joint config entry exists when in joint phase

| Field | Detail |
|---|---|
| **Spec Location** | `spec/ConsensusSpec.tla:206-213` |
| **Runtime Assertion Location** | `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:397-400` |
| **Runtime Assertion Name** | `reconfig_safety` |
| **Runtime Assertion Logic** | In `proposeConfigChange()`: asserts `jointConfig.isJoint()` -- the config change must use joint consensus |
| **Additional Structural Enforcement** | `RaftNode.java:1020` -- `maybeAdvanceCommitIndex()` uses `clusterConfig.isQuorum(replicated)` which, during joint phase, requires majorities of BOTH old and new voter sets via `ClusterConfig.isQuorum()` |
| **Test Coverage** | `configd-consensus-core/src/test/java/io/configd/raft/ReconfigurationTest.java:216-248` (`JointConsensusTransition`) -- verifies proposing config change enters joint state with correct old/new voter sets |
| **Assertion Mode** | metric (production) / exception (test) |
| **Status** | **PASS** |

---

### INV-8: SingleServerInvariant -- At most one config change in-flight per leader term

| Field | Detail |
|---|---|
| **Spec Location** | `spec/ConsensusSpec.tla:219-228` |
| **Runtime Assertion Location** | `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:384-387` |
| **Runtime Assertion Name** | `single_server_invariant` |
| **Runtime Assertion Logic** | In `proposeConfigChange()`: asserts `!configChangePending` -- rejects a second config change while one is in-flight |
| **Additional Structural Enforcement** | `RaftNode.java:374` -- `if (configChangePending) return false;` guard prevents second config change at API level before the assertion fires |
| **Test Coverage** | `configd-consensus-core/src/test/java/io/configd/raft/ReconfigurationTest.java:180-194` (`rejectsSecondConfigChangeWhilePending`) -- proposes two config changes; verifies the second is rejected |
| **Assertion Mode** | metric (production) / exception (test) |
| **Status** | **PASS** |

---

### INV-9: NoOpBeforeReconfig -- Leader commits no-op before config change

| Field | Detail |
|---|---|
| **Spec Location** | `spec/ConsensusSpec.tla:235-241` |
| **Runtime Assertion Location** | `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:389-392` |
| **Runtime Assertion Name** | `no_op_before_reconfig` |
| **Runtime Assertion Logic** | In `proposeConfigChange()`: asserts `noopCommittedInCurrentTerm` -- no-op must be committed before any config change |
| **Additional Structural Enforcement** | `RaftNode.java:377` -- `if (!noopCommittedInCurrentTerm) return false;` guard; `RaftNode.java:898-901` -- no-op is appended immediately on `becomeLeader()`; `RaftNode.java:1050-1052` -- `noopCommittedInCurrentTerm` is set to true when no-op is applied |
| **Test Coverage** | `configd-consensus-core/src/test/java/io/configd/raft/ReconfigurationTest.java:165-178` (`rejectsConfigChangeBeforeNoopCommitted`) -- tests that config change is only accepted after no-op commits |
| **Assertion Mode** | metric (production) / exception (test) |
| **Status** | **PASS** |

---

## TLA+ Liveness Properties

### LIVE-1: EdgePropagationLiveness -- Every committed write reaches every live edge

| Field | Detail |
|---|---|
| **Spec Location** | `spec/ConsensusSpec.tla:246-251` |
| **Runtime Assertion Location** | **MISSING** -- No dedicated runtime assertion or metric for edge propagation liveness. The `StalenessTracker` transitions (`CURRENT -> STALE -> DEGRADED -> DISCONNECTED`) provide indirect detection, but there is no assertion that verifies "every committed write eventually reaches every live edge." |
| **Structural Mechanisms** | `configd-edge-cache/src/main/java/io/configd/edge/DeltaApplier.java` -- gap detection triggers full snapshot sync. `configd-edge-cache/src/main/java/io/configd/edge/StalenessTracker.java` -- detects disconnection. `configd-distribution-service/src/main/java/io/configd/distribution/CatchUpService.java` -- provides snapshot catch-up for lagging edges. |
| **Test Coverage** | `configd-testkit/src/test/java/io/configd/testkit/ConsistencyPropertyTests.java:451-510` (`edgeStalenessStaysWithinBoundsUnderNormalConditions`) -- verifies staleness bounds but does NOT verify that every committed write reaches every edge. No test directly exercises the LIVE-1 temporal property. |
| **Assertion Mode** | **MISSING** |
| **Status** | **BLOCKER** |

**BLOCKER Rationale:** LIVE-1 is a liveness property (temporal, not just safety). The TLA+ spec defines it as a `<>` (eventually) property. There is no runtime mechanism that tracks whether all committed writes have been delivered to all subscribed edges, and no test that exercises the property end-to-end (e.g., committing N writes and verifying all N arrive at every live edge). The `StalenessTracker` provides a time-based proxy but cannot distinguish between "edge is current but missed writes" and "edge received all writes." The `DeltaApplier` gap detection catches version gaps but there is no liveness assertion that all committed entries are eventually propagated.

**Recommended Remediation:**
1. Add a runtime metric `configd.edge.propagation_lag_entries` tracking `leader_commit_index - edge_last_applied_seq` per edge.
2. Add an integration test in `ConsistencyPropertyTests` that commits N writes to a Raft cluster, syncs to M edge stores, and asserts all N writes are visible on all M edges within a bounded number of ticks.
3. Register a liveness invariant in `InvariantMonitor` that alerts when `propagation_lag > threshold` for configurable duration.

---

## Consistency Contract Invariants

### INV-L1: Linearizability of writes per Raft group

| Field | Detail |
|---|---|
| **Spec Location** | `docs/consistency-contract.md:29-31` |
| **Runtime Assertion Location** | Structurally enforced by Raft consensus (single leader serializes all writes). The `leader_completeness` assertion at `RaftNode.java:875` provides a component check. ReadIndex protocol at `RaftNode.java:311-321` ensures linearizable reads. |
| **Test Coverage** | `configd-testkit/src/test/java/io/configd/testkit/ConsistencyPropertyTests.java:234-384` (`LinearizabilityTest`) -- 4 tests: `writeCompletedBeforeReadIsAlwaysVisible`, `sequentialWritesThenReadsAreLinearizable`, `readIndexReflectsStateAtIssueTime`, `linearizabilitySurvivesLeaderFailover` (RepeatedTest x3) |
| **Assertion Mode** | Structural (Raft protocol) + metric (leader_completeness) |
| **Status** | **PASS** |

---

### INV-S1: Edge Staleness Measurement

| Field | Detail |
|---|---|
| **Spec Location** | `docs/consistency-contract.md:61-62` |
| **Runtime Assertion Location** | `configd-edge-cache/src/main/java/io/configd/edge/StalenessTracker.java:87-98` -- `currentState()` computes staleness from `clock.nanoTime() - lastUpdateNanos` and returns appropriate state |
| **Test Coverage** | `configd-testkit/src/test/java/io/configd/testkit/ConsistencyPropertyTests.java:405-437` (`stalenessStateTransitionsAreCorrect`), lines 518-537 (`stalenessTrackerDetectsAndRecoversFromPartition`), lines 544-560 (`stalenessMsIsMonotonicallyIncreasingBetweenUpdates`) |
| **Assertion Mode** | State transition (runtime behavior, not metric-gated assertion) |
| **Status** | **PASS** |

---

### INV-S2: Edge Staleness Bounds (p99 < 500ms, p9999 < 2s)

| Field | Detail |
|---|---|
| **Spec Location** | `docs/consistency-contract.md:64-67` |
| **Runtime Assertion Location** | `configd-edge-cache/src/main/java/io/configd/edge/StalenessTracker.java:23-25` -- threshold constants define state transitions. `EdgeMetrics` (line 10) exposes `currentVersion` and staleness for metric scraping. |
| **Test Coverage** | `configd-testkit/src/test/java/io/configd/testkit/ConsistencyPropertyTests.java:451-510` (`edgeStalenessStaysWithinBoundsUnderNormalConditions`) -- simulates 2000 ticks with periodic writes and edge sync, asserts stale ratio < 0.01 and severe ratio < 0.0001 |
| **Assertion Mode** | metric (StalenessTracker state transitions drive alerting) |
| **Status** | **PASS** |

---

### INV-M1: Monotonic Reads per client session

| Field | Detail |
|---|---|
| **Spec Location** | `docs/consistency-contract.md:93-94` |
| **Runtime Assertion Location** | `configd-edge-cache/src/main/java/io/configd/edge/LocalConfigStore.java:121-128` -- `get(key, cursor)` returns `NOT_FOUND` if `snap.version() < cursor.version()`, preventing monotonic read violation. `configd-config-store/src/main/java/io/configd/store/VersionedConfigStore.java:203-208` -- `get(key, minVersion)` returns `NOT_FOUND` if store version < minVersion. |
| **Test Coverage** | `configd-testkit/src/test/java/io/configd/testkit/ConsistencyPropertyTests.java:786-873` (`MonotonicReadTest`) -- 3 tests: `versionCursorMonotonicallyIncreasesDuringWrites`, `edgeStoreCursorEnforcesMonotonicReads`, `controlPlaneMinVersionEnforcesMonotonicRead` |
| **Assertion Mode** | exception (structural: returns NOT_FOUND instead of stale data) |
| **Status** | **PASS** |

---

### INV-M2: Monotonic Reads Survive Edge Failover

| Field | Detail |
|---|---|
| **Spec Location** | `docs/consistency-contract.md:96-98` |
| **Runtime Assertion Location** | Same as INV-M1 -- `LocalConfigStore.get(key, cursor)` enforces cursor check regardless of which edge instance is serving the read |
| **Test Coverage** | `configd-testkit/src/test/java/io/configd/testkit/ConsistencyPropertyTests.java:886-1043` (`MonotonicReadFailoverTest`) -- 4 tests: `cursorFromEdgeAEnforcedOnEdgeB`, `staleEdgeRejectsReadWithFutureCursor`, `failoverEdgeServesReadAfterCatchUp`, `endToEndFailoverWithRaftClusterAndTwoEdges` |
| **Assertion Mode** | exception (structural) |
| **Status** | **PASS** |

---

### INV-V1: Sequence Monotonicity

| Field | Detail |
|---|---|
| **Spec Location** | `docs/consistency-contract.md:128-129` |
| **Runtime Assertion Location** | `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:146-147` (Put), `164-165` (Delete), `174-175` (Batch) |
| **Runtime Assertion Name** | `sequence_monotonic` |
| **Runtime Assertion Logic** | On every apply: asserts `seq > prevSeq` -- new sequence must exceed previous |
| **Test Coverage** | `configd-testkit/src/test/java/io/configd/testkit/ConsistencyPropertyTests.java:571-664` (`SequenceMonotonicityTest`) -- 3 tests: `committedEntriesHaveStrictlyIncreasingSequence`, `monotonicitySurvivesLeaderFailure` (RepeatedTest x3), `sequenceMonotonicOnFollowerReplicas` |
| **Assertion Mode** | metric (production) / exception (test) |
| **Status** | **PASS** |

---

### INV-V2: Sequence Gap-Free

| Field | Detail |
|---|---|
| **Spec Location** | `docs/consistency-contract.md:131-132` |
| **Runtime Assertion Location** | `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:149-150` (Put), `166-167` (Delete), `176-177` (Batch) |
| **Runtime Assertion Name** | `sequence_gap_free` |
| **Runtime Assertion Logic** | On every apply: asserts `seq == prevSeq + 1` -- no gaps in sequence |
| **Test Coverage** | `configd-testkit/src/test/java/io/configd/testkit/ConsistencyPropertyTests.java:674-775` (`SequenceGapFreeTest`) -- 3 tests: `consecutiveCommittedEntriesAreGapFree`, `gapFreeWithMultipleKeysInSameGroup`, `gapFreeAcrossLeaderFailover` |
| **Assertion Mode** | metric (production) / exception (test) |
| **Status** | **PASS** |

---

### INV-W1: Per-Key Total Order

| Field | Detail |
|---|---|
| **Spec Location** | `docs/consistency-contract.md:152-153` |
| **Runtime Assertion Location** | `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:153-156` |
| **Runtime Assertion Name** | `per_key_order` |
| **Runtime Assertion Logic** | On Put apply: if key already exists, asserts `seq > existing.version()` -- new version must exceed existing version for same key |
| **Test Coverage** | `configd-testkit/src/test/java/io/configd/testkit/ConsistencyPropertyTests.java:1052-1163` (`PerKeyTotalOrderTest`) -- 3 tests: `allReplicasSeeIdenticalOrderForSameKey`, `sequenceNumbersOfSameKeyWritesAreStrictlyIncreasing`, `perKeyOrderSurvivesLeaderChange` |
| **Assertion Mode** | metric (production) / exception (test) |
| **Status** | **PASS** |

---

### INV-W2: Intra-Group Total Order

| Field | Detail |
|---|---|
| **Spec Location** | `docs/consistency-contract.md:155-156` |
| **Runtime Assertion Location** | Enforced by the combination of `sequence_monotonic` (INV-V1) and `sequence_gap_free` (INV-V2) assertions in `ConfigStateMachine.java`. No separate named assertion for INV-W2 because it is a consequence of V1+V2 within a single Raft group. Additionally, `WatchCoalescer.java:90-93` throws `IllegalArgumentException` on non-monotonic version. |
| **Test Coverage** | `configd-testkit/src/test/java/io/configd/testkit/ConsistencyPropertyTests.java:1173-1291` (`IntraGroupOrderTest`) -- 3 tests: `allWritesInGroupShareTotalOrder`, `deleteAndPutInterleavedMaintainOrder`, `allReplicasConvergeToSameOrder` |
| **Assertion Mode** | metric (production) / exception (test) via V1+V2 assertions |
| **Status** | **PASS** |

---

### INV-RYW1: Read-Your-Writes within region

| Field | Detail |
|---|---|
| **Spec Location** | `docs/consistency-contract.md:180-183` |
| **Runtime Assertion Location** | Enforced structurally by `LocalConfigStore.get(key, cursor)` at `LocalConfigStore.java:121-128` -- cursor-gated reads ensure that a client's cursor (set to the commit sequence of its last write) is honored. If the edge is behind, returns NOT_FOUND. |
| **Test Coverage** | `configd-testkit/src/test/java/io/configd/testkit/ConsistencyPropertyTests.java:1303-1448` (`ReadYourWritesTest`) -- 4 tests: `readAfterWriteReturnsCommittedValue`, `readYourWritesWithVersionCursorOnEdge`, `readYourDeleteOnEdge`, `readYourWritesAcrossMultipleKeys` |
| **Assertion Mode** | exception (structural: cursor-gated reads) |
| **Status** | **PASS** |

---

## Complete Mapping Table

| Invariant | Spec Location | Runtime Assertion Location (file:line) | Test Coverage (file:line) | Assertion Mode | Status |
|---|---|---|---|---|---|
| **INV-1** ElectionSafety | `ConsensusSpec.tla:149` | `RaftNode.java:869` (`election_safety`) | `ConsistencyPropertyTests.java:1617` (3 tests) | metric/exception | **PASS** |
| **INV-2** LeaderCompleteness | `ConsensusSpec.tla:155` | `RaftNode.java:875` (`leader_completeness`) | `ConsistencyPropertyTests.java:348` + `RaftNodeTest.java:313` | metric/exception | **PASS** |
| **INV-3** LogMatching | `ConsensusSpec.tla:164` | `RaftNode.java:588` (`log_matching`) | `RaftNodeTest.java:629` (2 tests) | metric/exception | **PASS** |
| **INV-4** StateMachineSafety | `ConsensusSpec.tla:170` | `RaftNode.java:1046` (`state_machine_safety`) | `ConsistencyPropertyTests.java:1247` | metric/exception | **PASS** |
| **INV-5** VersionMonotonicity | `ConsensusSpec.tla:176` | `RaftNode.java:1038` (`version_monotonicity`) + `LocalConfigStore.java:169` + `WatchCoalescer.java:90` | `ConsistencyPropertyTests.java:1454` + `EndToEndTest.java:253` | metric/exception | **PASS** |
| **INV-6** NoStaleOverwrite | `ConsensusSpec.tla:180` | Structural (via INV-3 + INV-4 assertions) | `ConsistencyPropertyTests.java:1524` (2 tests) | metric/exception | **PASS** |
| **INV-7** ReconfigSafety | `ConsensusSpec.tla:206` | `RaftNode.java:398` (`reconfig_safety`) | `ReconfigurationTest.java:216` | metric/exception | **PASS** |
| **INV-8** SingleServerInvariant | `ConsensusSpec.tla:219` | `RaftNode.java:385` (`single_server_invariant`) | `ReconfigurationTest.java:180` | metric/exception | **PASS** |
| **INV-9** NoOpBeforeReconfig | `ConsensusSpec.tla:235` | `RaftNode.java:390` (`no_op_before_reconfig`) | `ReconfigurationTest.java:165` | metric/exception | **PASS** |
| **LIVE-1** EdgePropagationLiveness | `ConsensusSpec.tla:246` | **MISSING** | **Partial** (`ConsistencyPropertyTests.java:451` -- staleness only) | **MISSING** | **BLOCKER** |
| **INV-L1** Linearizability | `consistency-contract.md:29` | Structural (Raft) + `RaftNode.java:875` | `ConsistencyPropertyTests.java:234` (4 tests) | metric/exception | **PASS** |
| **INV-S1** Staleness Measurement | `consistency-contract.md:61` | `StalenessTracker.java:87` | `ConsistencyPropertyTests.java:405` (3 tests) | state transition | **PASS** |
| **INV-S2** Staleness Bounds | `consistency-contract.md:64` | `StalenessTracker.java:23-25` (thresholds) | `ConsistencyPropertyTests.java:451` | metric | **PASS** |
| **INV-M1** Monotonic Reads | `consistency-contract.md:93` | `LocalConfigStore.java:125` + `VersionedConfigStore.java:206` | `ConsistencyPropertyTests.java:786` (3 tests) | exception (structural) | **PASS** |
| **INV-M2** Monotonic Read Failover | `consistency-contract.md:96` | `LocalConfigStore.java:125` | `ConsistencyPropertyTests.java:886` (4 tests) | exception (structural) | **PASS** |
| **INV-V1** Sequence Monotonicity | `consistency-contract.md:128` | `ConfigStateMachine.java:146` (`sequence_monotonic`) | `ConsistencyPropertyTests.java:571` (3 tests) | metric/exception | **PASS** |
| **INV-V2** Sequence Gap-Free | `consistency-contract.md:131` | `ConfigStateMachine.java:149` (`sequence_gap_free`) | `ConsistencyPropertyTests.java:674` (3 tests) | metric/exception | **PASS** |
| **INV-W1** Per-Key Total Order | `consistency-contract.md:152` | `ConfigStateMachine.java:154` (`per_key_order`) | `ConsistencyPropertyTests.java:1052` (3 tests) | metric/exception | **PASS** |
| **INV-W2** Intra-Group Order | `consistency-contract.md:155` | Structural (via V1 + V2) + `WatchCoalescer.java:90` | `ConsistencyPropertyTests.java:1173` (3 tests) | metric/exception | **PASS** |
| **INV-RYW1** Read-Your-Writes | `consistency-contract.md:180` | `LocalConfigStore.java:125` (cursor gate) | `ConsistencyPropertyTests.java:1303` (4 tests) | exception (structural) | **PASS** |

---

## Findings Summary

### BLOCKER: LIVE-1 (EdgePropagationLiveness) has no runtime assertion and no direct test

**Severity:** BLOCKER -- This invariant exists ONLY in TLA+ with no runtime counterpart.

**Evidence:**
- The TLA+ spec defines `EdgePropagationLiveness` as a temporal property (`<>`) at `ConsensusSpec.tla:246-251`.
- The `.cfg` file comments out the liveness check: `\* PROPERTIES \*     EdgePropagationLiveness`.
- No Java code contains an assertion named `edge_propagation` or similar.
- No test in the entire codebase verifies that committed writes eventually reach all subscribed edge nodes.
- The `StalenessTracker` provides a time-based proxy but tracks wall-clock staleness, not entry-level propagation completeness.
- The `DeltaApplier` detects version gaps but only reactively (when a stale delta arrives); it does not proactively verify liveness.

**Impact:** In production, a subtle bug in the Plumtree fan-out or delta computation could cause an edge node to silently miss committed writes without any alarm firing, as long as the `StalenessTracker` continues to receive _some_ updates (which would keep it in CURRENT state even if specific entries were dropped).

**Remediation (must be completed before PRR sign-off):**
1. Add a `propagation_lag` metric: `leader_commit_index - edge_last_applied_seq` per subscribed Raft group per edge.
2. Register an `InvariantMonitor` check: if `propagation_lag > 0` for more than `propagation_liveness_timeout` (e.g., 5s), fire `invariant.violation.edge_propagation_liveness`.
3. Add an integration test in `ConsistencyPropertyTests` that commits N entries, propagates to M edge stores via delta application, and asserts all N entries are applied to all M edges within a bounded tick count.

---

## Invariant Enforcement Chain Summary

```
TLA+ Spec (ConsensusSpec.tla)
    |
    v
Runtime Assertions (RaftNode.java, ConfigStateMachine.java)
    |-- election_safety          -> InvariantChecker.check()
    |-- leader_completeness      -> InvariantChecker.check()
    |-- log_matching             -> InvariantChecker.check()
    |-- state_machine_safety     -> InvariantChecker.check()
    |-- version_monotonicity     -> InvariantChecker.check()
    |-- single_server_invariant  -> InvariantChecker.check()
    |-- no_op_before_reconfig    -> InvariantChecker.check()
    |-- reconfig_safety          -> InvariantChecker.check()
    |-- sequence_monotonic       -> InvariantChecker.check()
    |-- sequence_gap_free        -> InvariantChecker.check()
    |-- per_key_order            -> InvariantChecker.check()
    |
    v
InvariantMonitor (observability module)
    |-- testMode=true  -> throws AssertionError
    |-- testMode=false -> metrics.counter("invariant.violation.<name>").increment()
    |
    v
Structural Enforcement (edge cache, config store)
    |-- LocalConfigStore.get(key, cursor) -> NOT_FOUND if version < cursor
    |-- VersionedConfigStore.get(key, minVersion) -> NOT_FOUND if behind
    |-- WatchCoalescer.add() -> IllegalArgumentException on non-monotonic
    |-- DeltaApplier.offer() -> GAP_DETECTED on version mismatch
    |-- StalenessTracker -> CURRENT/STALE/DEGRADED/DISCONNECTED state machine
```

---

## PRR Gate Decision

**Result: CONDITIONAL PASS -- 1 BLOCKER must be resolved.**

All 9 TLA+ safety invariants have runtime assertions and test coverage. All 10 consistency contract invariants have runtime enforcement (structural or assertion-based) and thorough test coverage.

The single BLOCKER is **LIVE-1 (EdgePropagationLiveness)**: the only invariant in the TLA+ spec with zero runtime counterpart. This must be addressed with a runtime metric and integration test before the PRR can be signed off.

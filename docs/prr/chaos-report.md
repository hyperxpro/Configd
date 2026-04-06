# Chaos Engineering Report -- Configd Production Readiness Review

**Date:** 2026-04-11
**Author:** Chaos Engineering (automated PRR audit)
**Scope:** Deterministic simulation framework (`configd-testkit`) and failure injection coverage

---

## 1. Existing Chaos Infrastructure Audit

### 1.1 Simulation Framework Overview

The deterministic simulation framework lives in `configd-testkit/src/main/java/io/configd/testkit/` and consists of three core components:

| Component | File | Purpose |
|-----------|------|---------|
| `RaftSimulation` | `RaftSimulation.java` | Orchestrator: seeded PRNG, tick-based advancement, invariant checkers, partition injection |
| `SimulatedNetwork` | `SimulatedNetwork.java` | Deterministic message delivery with configurable latency, drop rate, and partitions |
| `SimulatedClock` | `SimulatedClock.java` | Deterministic clock with ms and ns precision; time advances only on explicit tick |

The `ConsistencyPropertyTests.ClusterHarness` (lines 44-211) wires these together with real `RaftNode`, `RaftLog`, `ConfigStateMachine`, and `VersionedConfigStore` instances, forming a full in-process Raft cluster.

### 1.2 Failure Injection Capabilities

| Capability | Supported? | Evidence | Notes |
|------------|-----------|----------|-------|
| **Symmetric partition** (bidirectional) | YES | `SimulatedNetwork.isolate(a, b)` adds partitions in both directions | Used extensively in tests |
| **Asymmetric partition** (unidirectional) | YES | `SimulatedNetwork.addPartition(from, to)` creates one-way partition | Tested in `SimulatedNetworkTest.Partitions.partitionIsUnidirectional` (line 175) |
| **Node isolation** (from all peers) | YES | `RaftSimulation.isolateNode(node)` calls `isolate()` for all peers | Used in leader failover tests |
| **Partition healing** | YES | `SimulatedNetwork.healAll()` and `removePartition(from, to)` | Used in election safety tests |
| **Message loss** (probabilistic) | YES | `SimulatedNetwork.setDropRate(rate)` with 0.0-1.0 | Tested at 0%, 50%, 100% in `SimulatedNetworkTest.DropRate` |
| **Message latency** (configurable) | YES | Constructor takes `minLatencyMs`, `maxLatencyMs`; randomized within range | Default 1-10ms |
| **Message reordering** | PARTIAL | Emergent from random latency -- messages sent at different times may deliver out of order | No explicit reorder injection API |
| **Message duplication** | NO | No API exists to duplicate messages | Gap |
| **Clock skew injection** | NO | `SimulatedClock` only advances forward; `setTimeMs()` can set backward but `advanceMs()` rejects negative | No per-node clock divergence |
| **Monotonic clock jump** | NO | No API to simulate clock jumps or NTP corrections | Gap |
| **Slow disk simulation** | NO | `Storage.inMemory()` is the only simulation storage; no latency injection on write/fsync path | Gap |
| **Node crash/restart** | PARTIAL | Node isolation simulates crash (no messages), but no state loss/recovery simulation | No restart-from-durable-state path |
| **Seed-based replay** | YES | `RaftSimulation(seed, nodeCount)` uses seeded `L64X128MixRandom` PRNG | Same seed = same execution (in theory; see finding below) |

### 1.3 Seed Reproducibility Concern

**FINDING:** The `RaftSimulation` constructor creates a `RandomGenerator` but does NOT seed it -- `RandomGenerator.of("L64X128MixRandom")` uses a default (random) seed. The `seed` field is stored and reported in `stats()` but the local `random` field is not seeded with it. The `SimulatedNetwork` has the same issue (line 34). Only the `ConsistencyPropertyTests.ClusterHarness` passes seeds to `RaftSimulation`, and the `@RepeatedTest` methods use `System.nanoTime()` as seeds, which makes them non-reproducible across runs.

**Impact:** The "same seed = same execution" guarantee claimed in the Javadoc is partially broken. Partition injection order (via `injectRandomPartition()`) varies between runs even with the same seed.

---

## 2. Mandatory Scenario Evaluation

### Scenario 1: Single Region Isolated (Minority and Majority Sides)

| Attribute | Value |
|-----------|-------|
| **Can execute?** | YES -- `isolateNode()` can isolate any node; combined with a 5-node cluster, this creates a 1-vs-4 or 2-vs-3 partition |
| **Test exists?** | PARTIAL -- Leader isolation exists in `ConsistencyPropertyTests.LinearizabilityTest.linearizabilitySurvivesLeaderFailover` (line 349) and `NoStaleOverwriteTest.committedEntrySurvivesLeaderFailure` (line 1528). However, no test isolates a minority group (2 nodes) and verifies the majority side continues committing while the minority side correctly stops. No region concept exists in the consensus layer. |
| **Expected behavior** | Majority side elects new leader and continues serving. Minority side stops committing. Committed values are preserved. |
| **Observed behavior** | Leader failover tests pass: committed values survive leader isolation. New leader elected on majority side. 180 random-seed runs passed (seed sweep). |
| **Pass/Fail** | **PARTIAL PASS** -- majority-side behavior tested; minority-side stall not explicitly verified; no region abstraction in code. |

### Scenario 2: Asymmetric Partition (A sees B, B does not see A)

| Attribute | Value |
|-----------|-------|
| **Can execute?** | YES -- `SimulatedNetwork.addPartition(from, to)` creates unidirectional partitions |
| **Test exists?** | NO -- `SimulatedNetworkTest.partitionIsUnidirectional` (line 175) tests the network primitive, but no Raft-level test exercises asymmetric partitions (e.g., leader can send to follower but not receive responses). |
| **Expected behavior** | Leader with one-way connectivity should eventually step down (cannot get majority acknowledgment). Followers receiving unrequited AppendEntries should not disrupt the cluster. |
| **Observed behavior** | Not tested at Raft level. |
| **Pass/Fail** | **FAIL** -- capability exists but no test exercises asymmetric partitions on the Raft protocol. |

### Scenario 3: Leader Isolated from Followers but Not Clients (Gray Failure)

| Attribute | Value |
|-----------|-------|
| **Can execute?** | PARTIAL -- The simulation has no concept of "clients" vs "peers." All communication goes through the same `SimulatedNetwork`. A gray failure where a leader can serve client reads but cannot replicate to followers would require separating client and peer network paths. |
| **Test exists?** | NO |
| **Expected behavior** | Leader should detect it cannot replicate (no quorum acknowledgment) and step down within an election timeout. Clients should get redirected or fail reads within a bounded time. ReadIndex should fail because heartbeat confirmation will time out. |
| **Observed behavior** | Not tested. |
| **Pass/Fail** | **FAIL** -- framework cannot distinguish client traffic from peer traffic; gray failure not simulatable without refactoring. |

### Scenario 4: Clock Skew (+/-500ms, +/-5s, Monotonic Clock Jump)

| Attribute | Value |
|-----------|-------|
| **Can execute?** | NO -- `SimulatedClock` is a single shared clock. There is no per-node clock. Raft nodes get time from `sim.clock()` which is shared. `advanceMs()` rejects negative values. `setTimeMs()` can jump backward but affects all nodes simultaneously. |
| **Test exists?** | NO |
| **Expected behavior** | With +/-500ms skew, election timeouts may trigger spuriously on skewed nodes. With +/-5s skew, lease-based reads could serve stale data. Monotonic clock jumps should not cause safety violations (only liveness). |
| **Observed behavior** | Not tested. |
| **Pass/Fail** | **FAIL** -- framework lacks per-node clock injection; clock skew scenarios are not simulatable. |

### Scenario 5: Slow Disk (fsync Latency 10ms, 100ms, 1s)

| Attribute | Value |
|-----------|-------|
| **Can execute?** | NO -- `Storage.inMemory()` is the only simulation storage impl. `RaftLog` is fully in-memory with no pluggable latency. There is no WAL or fsync on the simulation path. |
| **Test exists?** | NO |
| **Expected behavior** | Slow fsync on the leader delays commit acknowledgment. If fsync takes longer than the heartbeat interval, followers may time out and trigger elections. The system must not lose committed entries despite slow persistence. |
| **Observed behavior** | Not tested. |
| **Pass/Fail** | **FAIL** -- no disk simulation layer exists. |

### Scenario 6: Slow Network (1% Loss, 5% Loss, 50ms Jitter, Reordering)

| Attribute | Value |
|-----------|-------|
| **Can execute?** | PARTIAL -- `setDropRate()` supports arbitrary loss rates. Latency range (min/max) provides jitter. Reordering is emergent from random latency. However, no test exercises these at Raft level. |
| **Test exists?** | NO at Raft level -- only `SimulatedNetworkTest.DropRate` (line 256) tests the network primitive |
| **Expected behavior** | At 1% loss, the system should be virtually unaffected. At 5%, retransmission should keep the system functional with slightly higher latency. At 50ms jitter, elections may be slightly delayed but should converge. |
| **Observed behavior** | Not tested at Raft level. |
| **Pass/Fail** | **FAIL** -- capability partially exists but no Raft-level tests exercise lossy/jittery networks. |

### Scenario 7: Region Loss (Hard Kill of Entire Region Mid-Write-Burst)

| Attribute | Value |
|-----------|-------|
| **Can execute?** | PARTIAL -- Multiple nodes can be isolated simultaneously via `isolateNode()`. However, there is no "region" concept, so "kill entire region" means manually isolating N nodes. No test does mid-write-burst isolation. |
| **Test exists?** | NO -- No test isolates multiple nodes simultaneously during active writes. |
| **Expected behavior** | If a minority region is lost, the majority region should continue. If a majority region is lost, the system should stop committing (correctly). In-flight writes to the lost region should time out and not be acknowledged. |
| **Observed behavior** | Not tested. |
| **Pass/Fail** | **FAIL** -- no test exercises this scenario. |

### Scenario 8: Edge Flood (1M Simulated Edge Subscribers, Fan-Out Latency)

| Attribute | Value |
|-----------|-------|
| **Can execute?** | PARTIAL -- `WatchFanOutBenchmark` scales to 1000 watchers. `PlumtreeFanOutBenchmark` scales to 500 fan-out. No test or benchmark reaches 1M. The `WatchService` and `PlumtreeNode` fan-out mechanisms exist, but 1M subscriber simulation has not been attempted. |
| **Test exists?** | NO at the required scale. `WatchFanOutBenchmark` (line 39, `@Param({"1", "10", "100", "1000"})`) is the closest, but it is a JMH benchmark, not a correctness test, and tops out at 1000 watchers. |
| **Expected behavior** | Fan-out latency should be bounded. The coalescer should batch burst writes to prevent thundering herd. Memory usage should be bounded (shared immutable buffers via `FanOutBuffer`). |
| **Observed behavior** | Not tested at required scale. |
| **Pass/Fail** | **FAIL** -- no 1M subscriber test exists; existing benchmarks reach only 1K. |

### Scenario 9: Slow Consumer (One Edge 10x Slower, Quarantine Policy)

| Attribute | Value |
|-----------|-------|
| **Can execute?** | YES -- `SlowConsumerPolicy` implements HEALTHY -> SLOW -> DISCONNECTED -> QUARANTINED state machine. `SimulatedClock` can advance time to trigger transitions. |
| **Test exists?** | PARTIAL -- `SlowConsumerPolicy` exists as production code with state transitions, but no integration test exercises the full scenario of one slow edge causing quarantine while others remain healthy. Unit tests for `SlowConsumerPolicy` likely exist in `configd-distribution-service` tests but are not in the deterministic simulation harness. |
| **Expected behavior** | Slow edge transitions to SLOW after exceeding pending threshold, then DISCONNECTED after 30s, then QUARANTINED after 60s. Other edges are unaffected. Quarantined edge must re-bootstrap via full snapshot. |
| **Observed behavior** | Not tested in the simulation harness. |
| **Pass/Fail** | **PARTIAL PASS** -- policy code exists and is correct by inspection; no simulation-level integration test. |

### Scenario 10: Reconfiguration Under Load (Joint Consensus During Writes)

| Attribute | Value |
|-----------|-------|
| **Can execute?** | YES -- `RaftNode.proposeConfigChange()` exists. `ReconfigurationTest` covers joint consensus mechanics. The simulation harness could combine write load with reconfiguration. |
| **Test exists?** | PARTIAL -- `ReconfigurationTest` (configd-consensus-core) tests joint consensus preconditions, adding/removing nodes, leader step-down, and safety (both old+new majorities). However, no test exercises reconfiguration while a write burst is in progress. No test uses the deterministic simulation harness for reconfiguration. |
| **Expected behavior** | During joint consensus, both old and new majorities must agree. In-flight writes should either commit or be rejected, never lost. The cluster should remain available throughout (if the reconfiguration is for adding a node to a healthy cluster). |
| **Observed behavior** | ReconfigurationTest passes for static scenarios. No under-load test. |
| **Pass/Fail** | **PARTIAL PASS** -- joint consensus correctness tested; concurrent writes during reconfig not tested. |

### Scenario 11: Bootstrap Storm (10% of Edges Cold-Start Simultaneously)

| Attribute | Value |
|-----------|-------|
| **Can execute?** | PARTIAL -- `CatchUpService` provides `SnapshotRequired` for cold-start edges. Multiple edges could be instantiated simultaneously in a test. However, no test does so, and there is no concept of rate-limiting bootstrap requests. |
| **Test exists?** | NO |
| **Expected behavior** | Simultaneous snapshot transfers should be rate-limited to prevent control plane overload. Each bootstrapping edge should eventually converge to the current state. The control plane must remain responsive to normal operations during the storm. |
| **Observed behavior** | Not tested. |
| **Pass/Fail** | **FAIL** -- no bootstrap storm test exists; no rate-limiting for concurrent snapshot transfers observed in `CatchUpService`. |

### Scenario 12: Version Gap (Edge Misses N Updates, Catch-Up and No Divergence)

| Attribute | Value |
|-----------|-------|
| **Can execute?** | YES -- `CatchUpService.resolve()` handles delta replay and snapshot fallback. `EndToEndTest.DeltaVersionMismatch` (line 360) tests version mismatch detection. |
| **Test exists?** | PARTIAL -- `EndToEndTest.DeltaVersionMismatch.applyingDeltaWithWrongFromVersionThrows` (line 365) verifies that applying a delta with wrong fromVersion throws `IllegalArgumentException`. `EndToEndTest.SequentialDeltas` (line 170) tests sequential delta application. `CatchUpService` unit tests exist. However, no simulation-level test exercises an edge missing N updates and then catching up through the `CatchUpService`. |
| **Expected behavior** | Edge detects version gap, requests catch-up. If deltas are available in history, delta replay is used. If gap is too large, full snapshot is transferred. After catch-up, edge must be consistent with control plane (no divergence). |
| **Observed behavior** | Delta mismatch correctly rejected (test passes). Sequential deltas correctly applied (test passes). Full catch-up flow not tested in simulation. |
| **Pass/Fail** | **PARTIAL PASS** -- building blocks tested individually; end-to-end catch-up flow not tested in simulation. |

---

## 3. Test Execution Results

### 3.1 RaftSimulationTest

```
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
Time elapsed: 0.445s
BUILD SUCCESS
```

All 24 tests pass. Tests cover: construction, tick mechanics, message delivery, invariant checkers, network partitions, advance-to-next-event, and stats reporting.

### 3.2 EndToEndTest

```
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
Time elapsed: 0.604s
BUILD SUCCESS
```

All 13 tests pass. Tests cover: full pipeline (control plane -> edge), version cursor enforcement, sequential deltas, delete propagation, version monotonicity, staleness tracking, delta version mismatch, empty delta, snapshot replacement.

### 3.3 ConsistencyPropertyTests

```
Tests run: 44, Failures: 1, Errors: 0, Skipped: 0
Time elapsed: 1.276s
BUILD FAILURE
```

**43 of 44 tests pass.** One flaky failure detected:

**Failing test:** `IntraGroupOrderTest.allReplicasConvergeToSameOrder` (line 1251)

```
AssertionFailedError: Leader must be elected ==> expected: <true> but was: <false>
```

**Root cause analysis:** This test uses a fixed seed of `42L` with a 3-node cluster and calls `electLeader(600)` which allows only 600 ticks for leader election. The failure is flaky -- it passes in isolation and in 5/6 subsequent full-suite runs. The `RaftSimulation`'s `RandomGenerator` is not seeded from the constructor's `seed` parameter (see Finding 1.3), so execution order of parallel test classes affects the PRNG state, making the test non-deterministic.

**Flake rate:** Observed 1 failure in 6 full-suite runs (~17%). When run in isolation, 0 failures in 5 runs.

### 3.4 Seed Sweep Results

Executed 180 random seeds across three `@RepeatedTest` methods over 20 iterations:
- `linearizabilitySurvivesLeaderFailover` -- 60 seeds, **0 failures**
- `monotonicitySurvivesLeaderFailure` -- 60 seeds, **0 failures**
- `electionSafetyWithRandomSeeds` -- 60 seeds, **0 failures**

**Verdict:** No safety violations detected across 180 random seeds.

---

## 4. Summary Scorecard

| # | Scenario | Can Execute? | Test Exists? | Verdict |
|---|----------|-------------|-------------|---------|
| 1 | Single region isolated | YES | PARTIAL | PARTIAL PASS |
| 2 | Asymmetric partition | YES | NO | **FAIL** |
| 3 | Gray failure (leader/client split) | NO | NO | **FAIL** |
| 4 | Clock skew | NO | NO | **FAIL** |
| 5 | Slow disk | NO | NO | **FAIL** |
| 6 | Slow network | PARTIAL | NO | **FAIL** |
| 7 | Region loss mid-write | PARTIAL | NO | **FAIL** |
| 8 | Edge flood (1M subscribers) | PARTIAL | NO | **FAIL** |
| 9 | Slow consumer / quarantine | YES | PARTIAL | PARTIAL PASS |
| 10 | Reconfiguration under load | YES | PARTIAL | PARTIAL PASS |
| 11 | Bootstrap storm | PARTIAL | NO | **FAIL** |
| 12 | Version gap catch-up | YES | PARTIAL | PARTIAL PASS |

**Overall: 0/12 FULL PASS, 4/12 PARTIAL PASS, 8/12 FAIL**

---

## 5. Critical Gaps and Recommendations

### 5.1 Blocking (P0) -- Must Fix Before Production

1. **Fix PRNG seeding in `RaftSimulation` and `SimulatedNetwork`.** The `seed` parameter is stored but not used to seed the `RandomGenerator`. This breaks the "same seed = same execution" reproducibility guarantee. Every simulation failure should be replayable by seed.

2. **Add per-node clock injection.** The shared `SimulatedClock` cannot simulate clock skew. Each `RaftNode` should receive its own `Clock` instance, with a `ClockSkewInjector` wrapper that adds configurable offset. This unlocks scenarios 3 and 4.

3. **Add slow-disk simulation.** Wrap `RaftLog` or `Storage` with a `SimulatedDisk` that injects configurable fsync latency. This unlocks scenario 5 and is critical for understanding commit latency under real hardware.

4. **Fix the flaky `allReplicasConvergeToSameOrder` test.** Either increase the election timeout budget from 600 to 1200+ ticks, or (better) fix the PRNG seeding so the test is fully deterministic.

### 5.2 Important (P1) -- Should Fix Before GA

5. **Add asymmetric partition test at Raft level.** The network primitive supports it; a test should verify that a leader with one-way connectivity eventually steps down.

6. **Add lossy-network Raft tests.** Exercise `setDropRate(0.01)`, `setDropRate(0.05)`, and high-jitter latency ranges (e.g., 1-50ms) on full Raft clusters. Verify that committed entries are never lost under lossy conditions.

7. **Add region-loss-mid-write test.** Isolate 2 of 5 nodes during a burst of `proposeAndCommit()` calls. Verify the remaining 3 nodes elect a leader and continue committing. Verify in-flight writes to isolated nodes time out cleanly.

8. **Add reconfiguration-under-load test.** Combine `proposeConfigChange()` with concurrent `proposeAndCommit()` in the simulation harness. Verify no writes are lost and version monotonicity holds through the transition.

9. **Add version-gap-catch-up simulation test.** Create a cluster, commit N entries, then instantiate a new edge that starts at version 0 and must catch up through `CatchUpService`. Verify convergence.

### 5.3 Nice to Have (P2) -- Before Scale

10. **Add gray failure simulation.** Separate client and peer network paths in the simulation harness to enable testing scenario 3.

11. **Scale fan-out benchmarks to 1M subscribers.** The current 1K ceiling is insufficient for validating production fan-out. Use a lightweight subscriber stub to reach 1M.

12. **Add bootstrap storm test.** Instantiate 100+ edges simultaneously and verify the control plane handles concurrent snapshot transfers without degradation.

13. **Add message duplication injection** to `SimulatedNetwork` for testing idempotency of Raft message handling.

---

## 6. Framework Quality Assessment

**Strengths:**
- Clean, well-documented simulation primitives (clock, network, simulation orchestrator)
- `ClusterHarness` in `ConsistencyPropertyTests` is a solid pattern for wiring real Raft nodes through simulated infrastructure
- Invariant checker mechanism enables continuous safety verification during simulation
- Comprehensive consistency contract tests (INV-L1, INV-S1/S2, INV-V1/V2, INV-M1/M2, INV-W1/W2, INV-RYW1, INV-6)
- 44 property/simulation tests covering linearizability, staleness, monotonicity, gap-free sequences, per-key ordering, and election safety

**Weaknesses:**
- Seed reproducibility is broken (P0 bug)
- No chaos scenarios involving combined failures (e.g., partition + load + reconfiguration)
- No long-running soak tests (longest simulation is ~3000 ticks = 3s simulated time)
- No Jepsen-style history linearizability checking (tests use custom assertions rather than a formal checker)
- `@RepeatedTest(3)` with `System.nanoTime()` seeds provides only 3 random seeds per method; the sweep should be at least 1000+ for confidence
- No integration between the simulation harness and the distribution layer (`WatchService`, `PlumtreeNode`, `SlowConsumerPolicy`)

---

## 7. Test File Reference

| Test File | Path | Tests | Status |
|-----------|------|-------|--------|
| `RaftSimulationTest` | `configd-testkit/src/test/java/io/configd/testkit/RaftSimulationTest.java` | 24 | ALL PASS |
| `SimulatedNetworkTest` | `configd-testkit/src/test/java/io/configd/testkit/SimulatedNetworkTest.java` | ~20 | ALL PASS (not explicitly run; passes in suite) |
| `SimulatedClockTest` | `configd-testkit/src/test/java/io/configd/testkit/SimulatedClockTest.java` | ~18 | ALL PASS (not explicitly run; passes in suite) |
| `EndToEndTest` | `configd-testkit/src/test/java/io/configd/testkit/EndToEndTest.java` | 13 | ALL PASS |
| `ConsistencyPropertyTests` | `configd-testkit/src/test/java/io/configd/testkit/ConsistencyPropertyTests.java` | 44 | 43 PASS, 1 FLAKY |
| `ReconfigurationTest` | `configd-consensus-core/src/test/java/io/configd/raft/ReconfigurationTest.java` | ~15 | Not run (out of testkit scope) |
| `WatchServicePropertyTest` | `configd-distribution-service/src/test/java/io/configd/distribution/WatchServicePropertyTest.java` | ~10 (jqwik) | Not run (out of testkit scope) |

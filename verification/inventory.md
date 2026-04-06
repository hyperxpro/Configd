# Verification Inventory

> Produced by: Blue Team (Phase 0)
> Date: 2026-04-13
> Status: Complete

---

## 1. Module Tree with LOC

| Module | Production LOC | Test LOC | Prod Files | Test Files |
|--------|---------------|----------|------------|------------|
| configd-common | 614 | 776 | 12 | 6 |
| configd-config-store | 2,522 | 2,826 | 14 | 10 |
| configd-consensus-core | 3,047 | 4,044 | 22 | 8 |
| configd-control-plane-api | 808 | 555 | 7 | 5 |
| configd-distribution-service | 2,067 | 2,220 | 10 | 9 |
| configd-edge-cache | 1,163 | 2,024 | 9 | 9 |
| configd-observability | 1,020 | 1,251 | 7 | 7 |
| configd-replication-engine | 1,028 | 1,570 | 5 | 5 |
| configd-server | 839 | 527 | 3 | 2 |
| configd-testkit | 306 | 3,935 | 3 | 13 |
| configd-transport | 1,224 | 1,094 | 10 | 7 |
| **TOTAL** | **14,638** | **20,822** | **98** | **81** |

Additional:
- TLA+ spec: 512 LOC (1 file: ConsensusSpec.tla)
- CI: 1 file (.github/workflows/ci.yml)
- Docker: 0 files (NOT PRESENT — finding)
- Deploy: 1 directory (deploy/)

## 2. Public API Surface

### Control Plane API (configd-control-plane-api)
| Class | Methods | Type |
|-------|---------|------|
| ConfigWriteService | put, delete, batch | Write endpoint |
| ConfigReadService | get, list, watch | Read endpoint |
| AdminService | status, reconfigure, transfer | Admin endpoint |
| AclService | grant, revoke, check | Authorization |
| AuthInterceptor | intercept | Authentication |
| HealthService | health, ready, live | Health checks |
| RateLimiter | tryAcquire | Rate limiting |

### Consensus (configd-consensus-core)
| Class | Methods | Type |
|-------|---------|------|
| RaftNode | tick, handleMessage, propose, readIndex, isReadReady, completeRead, transferLeadership, triggerSnapshot, proposeConfigChange, clusterConfig, metrics | Core Raft |
| RaftLog | append, appendEntries, entryAt, termAt, commitIndex, lastApplied, compact, entriesBatch | Log management |
| StateMachine | apply, snapshot, restoreSnapshot | State machine |

### Edge Cache (configd-edge-cache)
| Class | Methods | Type |
|-------|---------|------|
| LocalConfigStore | get, getWithCursor, snapshot | Read path |
| EdgeConfigClient | connect, subscribe, unsubscribe | Client |
| DeltaApplier | applyDelta | Write path |
| StalenessTracker | recordUpdate, currentStaleness, state | Monitoring |
| VersionCursor | version, timestamp, isNewerThan | Cursor |

### Distribution (configd-distribution-service)
| Class | Methods | Type |
|-------|---------|------|
| PlumtreeNode | broadcast, receive, tick | Gossip |
| HyParViewOverlay | join, shuffle, tick | Overlay |
| WatchService | watch, unwatch, notify | Watch |
| SubscriptionManager | subscribe, unsubscribe | Subscriptions |
| CatchUpService | requestCatchUp, streamSnapshot | Catch-up |
| SlowConsumerPolicy | check, quarantine | Backpressure |
| FanOutBuffer | offer, poll, isFull | Buffering |
| RolloutController | canRollout, advance | Progressive rollout |

## 3. Claimed Invariants (Extracted from All Sources)

### From consistency-contract.md
| ID | Invariant | Source |
|----|-----------|--------|
| INV-L1 | Write linearizability per Raft group | §1 |
| INV-S1 | Edge staleness defined as wall_time - timestamp(last_applied) | §2 |
| INV-S2 | P(staleness > 500ms) < 0.01 under normal conditions | §2 |
| INV-M1 | Monotonic reads: version(r2) >= version(r1) for sequential reads | §3 |
| INV-M2 | Per-key monotonic reads across failover | §3 |
| INV-V1 | Sequence numbers strictly increasing per Raft group | §4 |
| INV-V2 | Gap-free committed sequences: seq(e_{i+1}) = seq(e_i) + 1 | §4 |
| INV-W1 | Per-key total order via Raft serialization | §5 |
| INV-W2 | Intra-group total order with HLC | §5 |
| INV-RYW1 | Read-your-writes within same region within timeout | §6 |

### From TLA+ spec (ConsensusSpec.tla)
| ID | Invariant | Lines |
|----|-----------|-------|
| INV-1 | ElectionSafety: at most one leader per term | 149-151 |
| INV-2 | LeaderCompleteness: committed entries in future leaders (approximation) | 161-167 |
| INV-3 | LogMatching: same index+term ⇒ same entry and all preceding | 170-174 |
| INV-4 | StateMachineSafety: no two nodes apply different values at same index | 177-180 |
| INV-5 | VersionMonotonicity: edgeVersion >= 0 (TRIVIAL — NOT A REAL CHECK) | 184 |
| INV-6 | NoStaleOverwrite: committed entries agree | 189-192 |
| INV-7 | ReconfigSafety: joint config entry exists when in joint phase | 213-220 |
| INV-8 | SingleServerInvariant: at most one in-flight config change per leader term | 228-235 |
| INV-9 | NoOpBeforeReconfig: no-op committed before any config change | 243-248 |
| LIVE-1 | EdgePropagationLiveness: committed writes reach edges (temporal) | 253-258 |

### From RaftNode.java Runtime Assertions
| Check Name | Location | Condition |
|------------|----------|-----------|
| election_safety | RaftNode:1023 | isQuorum(votesReceived) |
| leader_completeness | RaftNode:1029 | lastIndex >= commitIndex |
| log_matching | RaftNode:706 | stored.term == appended.term |
| state_machine_safety | RaftNode:1204 | entry.index == nextApply |
| version_monotonicity | RaftNode:1196 | nextApply > lastApplied |
| single_server_invariant | RaftNode:405 | !configChangePending |
| no_op_before_reconfig | RaftNode:410 | noopCommittedInCurrentTerm |
| reconfig_safety | RaftNode:418 | jointConfig.isJoint() |

## 4. Benchmark Files

| File | Measures | Claims |
|------|----------|--------|
| HamtReadBenchmark | HAMT get/getMiss across sizes | ~41-147 ns/op |
| HamtWriteBenchmark | HAMT put new/overwrite | ~76-190 ns/op |
| VersionedStoreReadBenchmark | Volatile load + HAMT traversal | ~18-92 ns/op |
| RaftCommitBenchmark | Raft commit throughput (simulated) | 815K ops/s (3-node) |
| PlumtreeFanOutBenchmark | Fan-out broadcast+drain | 0.25-7.25 μs/op |
| HybridClockBenchmark | HLC now/receive | ~34 ns/op |
| WatchFanOutBenchmark | Watch dispatch to subscribers | Not documented in perf.md |

## 5. Test Classification

### Unit Tests
| File | Module | Focus |
|------|--------|-------|
| NodeIdTest | common | NodeId value semantics |
| ConfigScopeTest | common | Config scope enum |
| HybridTimestampTest | common | HLC timestamp operations |
| HybridClockTest | common | Clock monotonicity |
| FileStorageTest | common | Durable storage |
| BuggifyRuntimeTest | common | Fault injection |
| HamtMapTest | config-store | HAMT operations |
| CommandCodecTest | config-store | Serialization |
| CompactorTest | config-store | Log compaction |
| ConfigSignerTest | config-store | Signature verification |
| ConfigStateMachineTest | config-store | State machine apply |
| ConfigValidatorTest | config-store | Input validation |
| DeltaComputerTest | config-store | Delta computation |
| VersionedConfigStoreTest | config-store | Versioned store |
| RaftNodeTest | consensus-core | Core Raft operations |
| ClusterConfigTest | consensus-core | Cluster configuration |
| DurableRaftStateTest | consensus-core | Persistent state |
| InstallSnapshotTest | consensus-core | Snapshot handling |
| RaftLogWalTest | consensus-core | WAL operations |
| ReadIndexStateTest | consensus-core | ReadIndex protocol |
| ReconfigurationTest | consensus-core | Joint consensus |
| All transport tests | transport | Frame codec, TLS, connections |
| All distribution tests | distribution | Plumtree, HyParView, watches |
| All edge-cache tests | edge-cache | Local store, delta, staleness |
| All observability tests | observability | Metrics, SLOs, invariants |
| All API tests | control-plane-api | Auth, ACL, rate limiting |
| All server tests | server | Bootstrap, config |

### Property Tests (jqwik)
| File | Module | Focus |
|------|--------|-------|
| HamtMapPropertyTest | config-store | HAMT correctness properties |
| VersionedConfigStorePropertyTest | config-store | Version monotonicity |
| WatchServicePropertyTest | distribution | Watch ordering, cancellation |
| BloomFilterPropertyTest | edge-cache | False positive rate, membership |
| ConsistencyPropertyTests | testkit | Linearizability, monotonicity, gaps, staleness |

### Deterministic Simulation
| File | Module | Focus |
|------|--------|-------|
| RaftSimulationTest | testkit | Raft invariants under partitions |
| SeedSweepTest | testkit | Multi-seed simulation sweep |

### End-to-End / Integration
| File | Module | Focus |
|------|--------|-------|
| EndToEndTest | testkit | Full stack integration |

### Certification / Conformance
| File | Module | Focus |
|------|--------|-------|
| CertificationTest | consensus-core | Raft conformance certification |

### Benchmarks (JMH)
7 benchmark files in configd-testkit (listed in §4)

### MISSING test types
- **Chaos suite**: No dedicated chaos test files found — FINDING
- **Jepsen-style linearizability checker**: ConsistencyPropertyTests has LinearizabilityTest but it's a property test, not a Jepsen runner — NEEDS VERIFICATION
- **Nightly seed rotation**: No CI evidence of rotating seeds
- **Load/soak tests**: No sustained throughput tests (1-hour runs)

## 6. External Dependencies

| GroupId | ArtifactId | Version | License | Purpose |
|---------|-----------|---------|---------|---------|
| org.agrona | agrona | 1.23.1 | Apache 2.0 | Off-heap buffers, concurrent data structures |
| org.jctools | jctools-core | 4.0.5 | Apache 2.0 | Lock-free concurrent queues |
| org.junit.jupiter | junit-jupiter | 5.11.4 | EPL 2.0 | Testing (test scope) |
| org.openjdk.jmh | jmh-core | 1.37 | GPL 2.0+CE | Benchmarking (test scope) |
| org.openjdk.jmh | jmh-generator-annprocess | 1.37 | GPL 2.0+CE | JMH annotation processing |
| org.hdrhistogram | HdrHistogram | 2.2.2 | CC0 1.0 | Latency histograms |
| io.micrometer | micrometer-core | 1.14.4 | Apache 2.0 | Metrics facade |
| net.jqwik | jqwik | 1.9.2 | EPL 2.0 | Property-based testing (test scope) |

**Runtime**: Java 25 (Amazon Corretto 25.0.2) with --enable-preview
**Build**: Maven 3.x (wrapper included)

### MISSING from DoD
- **SBOM**: Not generated — FINDING
- **CVE scan**: Not run — FINDING
- **Dependency pinning**: Versions pinned in parent POM ✓
- **License compliance**: No FOSSA/license-checker configured — FINDING

## 7. Original §6 DoD Checklist Gaps

| DoD Item | Status | Finding |
|----------|--------|---------|
| TLA+ specs model-checked | ✅ Present | Small bounds (3 nodes, MaxTerm=3) |
| Deterministic simulation | ✅ Present | RaftSimulation, SeedSweep |
| JMH benchmarks | ✅ Present | 7 benchmarks |
| Property tests | ✅ Present | 5 property test classes |
| Chaos suite | ❌ MISSING | No dedicated chaos tests |
| SBOM | ❌ MISSING | Not generated |
| CVE scan | ❌ MISSING | Not run |
| Linearizability checker | ⚠️ PARTIAL | Property test only, not Jepsen/Porcupine |
| Docker/containerization | ❌ MISSING | No Dockerfiles found |
| CI pipeline | ⚠️ PARTIAL | ci.yml exists but not verified |
| Load/soak tests | ❌ MISSING | No sustained throughput tests |
| Runbooks | ✅ Present | 8 runbooks |
| Security audit | ⚠️ PARTIAL | docs/prr/security-report.md exists |

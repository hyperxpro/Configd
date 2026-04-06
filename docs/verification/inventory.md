# Phase V1 — Inventory & Baseline

**Date:** 2026-04-14
**Verifier:** Adversarial verification (Jepsen-class)
**Build:** `mvn test` — 21,222 tests, 0 failures, BUILD SUCCESS
**Runtime:** Amazon Corretto 25.0.2+10-LTS (OpenJDK 25), `--enable-preview`
**Compiler target:** Java 25 (`maven.compiler.release=25`)

---

## 1. Module Inventory

| Module | Source Files | Source LoC | Test Files | Test LoC | Tests | Role |
|--------|-------------|-----------|-----------|---------|-------|------|
| configd-common | 11 | 640 | 6 | 910 | 80 | Clock, Storage, FileStorage, NodeId, codec utils |
| configd-config-store | 13 | 2,551 | 10 | 2,952 | 182 | HAMT, MVCC VersionedConfigStore, StateMachine, CommandCodec |
| configd-consensus-core | 21 | 3,100 | 8 | 4,133 | 148 | RaftNode, RaftLog, ReadIndexState, ClusterConfig, joint consensus |
| configd-control-plane-api | 7 | 874 | 5 | 639 | 50 | ConfigReadService, ConfigWriteService, AdminService |
| configd-distribution-service | 10 | 2,067 | 9 | 2,220 | 139 | Plumtree, HyParView, FanOutBuffer, WatchService, DeltaApplier |
| configd-edge-cache | 9 | 1,158 | 9 | 2,044 | 101 | LocalConfigStore, BloomFilter, StalenessTracker, VersionCursor |
| configd-observability | 7 | 1,020 | 7 | 1,251 | 119 | Metrics, InvariantMonitor, HealthCheck |
| configd-replication-engine | 5 | 1,028 | 5 | 1,570 | 146 | Hierarchical Raft replication engine |
| configd-server | 5 | 1,514 | 3 | 976 | 51 | ConfigdServer (assembly), ServerConfig, RaftMessageCodec |
| configd-testkit | 3 | 306 | 13 | 3,935 | 20,132 | RaftSimulation, deterministic harness, 7 JMH benchmarks |
| configd-transport | 9 | 1,226 | 7 | 1,163 | 74 | TcpRaftTransport, TLS, FrameCodec, MessageType |
| **TOTAL** | **100** | **15,484** | **82** | **21,793** | **21,222** | |

Test:source ratio = 1.41:1 (LoC). 20,132 of 21,222 tests are in configd-testkit (deterministic simulation with seeded PRNG).

---

## 2. Formal Specification Inventory

### TLA+ Model: `spec/ConsensusSpec.tla`

| Invariant | Type | TLC Result | Notes |
|-----------|------|------------|-------|
| TypeOK | Safety | PASS | Type constraint on all state variables |
| ElectionSafety | Safety | PASS | At most one leader per term |
| StateMachineSafety | Safety | PASS | Committed entries agree across nodes |
| NoStaleOverwrite | Safety | PASS | **Redundant** — identical formula to StateMachineSafety |
| LogMatching | Safety | PASS | Same-term entries at same index → identical prefix |
| ReconfigSafety | Safety | PASS | Joint config entries match config state |
| SingleServerInvariant | Safety | PASS | At most one pending reconfig entry per leader term |
| NoOpBeforeReconfig | Safety | PASS | Noop committed before reconfig in same term |
| EdgePropagationLiveness | Liveness | **NOT CHECKED** | Commented out in ConsensusSpec.cfg |

**TLC stats:** 13,775,323 states explored, 3,299,086 distinct, depth 25, 4m10s.
**Model params:** Nodes={n1,n2,n3}, MaxTerm=3, MaxLogLen=3, Values={v1,v2}.

### TLA+ Gaps Identified

| Gap | Severity | Description |
|-----|----------|-------------|
| SPEC-GAP-1 | High | `NoStaleOverwrite` is byte-for-byte identical to `StateMachineSafety` — provides zero additional coverage |
| SPEC-GAP-2 | High | `EdgePropagationLiveness` is commented out — liveness is never checked |
| SPEC-GAP-3 | Medium | `VersionMonotonicity` — not modeled in TLA+; only checked at runtime via InvariantChecker |
| SPEC-GAP-4 | Medium | ReadIndex protocol not modeled — linearizable read correctness not formally verified |
| SPEC-GAP-5 | Low | Small state space (3 nodes, 3 terms, 3 log entries) — may miss bugs requiring deeper state |

---

## 3. JMH Benchmark Inventory

| Benchmark | Methods | What It Measures | Target Claims |
|-----------|---------|------------------|---------------|
| HamtReadBenchmark | get, getMiss | HAMT read latency at 1K–1M keys | Edge read p50 < 50ns, p99 < 200ns (10K) |
| HamtWriteBenchmark | putNew, putOverwrite | HAMT structural-sharing write cost | Put p50 < 150ns, p99 < 500ns (10K) |
| HybridClockBenchmark | now, receive | HLC timestamp generation/merge | HLC now() p50 < 40ns, p99 < 100ns |
| PlumtreeFanOutBenchmark | broadcast+drain, broadcastOnly, receiveAndForward | Gossip fan-out latency at 10–500 peers | Broadcast p50 < 10μs, p99 < 50μs (500) |
| RaftCommitBenchmark | proposeAndCommit | Raft commit in deterministic sim (3/5 node) | Simulation only — no network latency |
| VersionedStoreReadBenchmark | getHit, getMiss, getWithMinVersion, snapshotGet | Full read path incl. volatile snapshot load | End-to-end edge read p99 < 1ms |
| WatchFanOutBenchmark | dispatch, prefixFiltered, coalescedBurst | Watch notification fan-out at 1–1000 watchers | Watch dispatch latency |

### Performance Claims NOT Covered by Benchmarks

| Claim | Source | Gap |
|-------|--------|-----|
| Write commit p99 < 150ms (cross-region) | performance.md, architecture.md | Network-bound; no end-to-end benchmark |
| Propagation p99 < 500ms global | performance.md | Requires multi-hop network sim |
| 10K/s sustained writes | performance.md | No sustained-throughput stress benchmark |
| Allocation rate < 50 MB/s steady state | performance.md | Per-op alloc measured, not sustained rate |
| GC pause < 1ms p99 (ZGC) | performance.md | Operational; not benchmarkable in JMH |
| 99.999% control plane availability | architecture.md | Operational SLO; not benchmarkable |
| 99.9999% edge read availability | architecture.md | Operational SLO; not benchmarkable |

---

## 4. Property-Based Test Inventory (jqwik)

| File | Module | Properties Tested |
|------|--------|-------------------|
| HamtMapPropertyTest.java | config-store | HAMT put/get/delete/forEach consistency, collision handling |
| VersionedConfigStorePropertyTest.java | config-store | MVCC version ordering, snapshot isolation |
| WatchServicePropertyTest.java | distribution-service | Watch notification delivery, prefix matching |
| BloomFilterPropertyTest.java | edge-cache | False positive rate, membership correctness |

---

## 5. Deterministic Simulation Test Inventory

| File | Tests | Seeds | Description |
|------|-------|-------|-------------|
| RaftSimulationTest.java | 24 direct + 20,108 seeded | 10K+ | Leader election, log replication, partition healing, commit safety |

The simulation harness (`configd-testkit`) uses seeded PRNG to deterministically replay network partitions, message drops, and timing variations across 10,000+ seeds per scenario.

---

## 6. Doc-vs-Code Drift Register

| ID | Severity | Document | Claim | Reality | Status |
|----|----------|----------|-------|---------|--------|
| DRIFT-1 | **Critical** | ADR-0010, architecture.md | "Netty gRPC transport" | Plain Java TCP sockets + virtual threads; zero Netty/gRPC deps | **Open** — ADR needs update |
| DRIFT-2 | **Critical** | ADR-0016 | "SWIM/Lifeguard membership protocol" | Not implemented; uses HyParView + Raft membership | **Open** — ADR needs update or removal |
| DRIFT-3 | Low | ADR-0009 vs ADR-0022 | "Java 21 LTS" vs "Java 25" | Java 25 (Corretto 25.0.2); ADR-0022 intentionally supersedes | Mitigated — ADR-0022 documents rationale |
| DRIFT-4 | Low | README.md | No module list | 11 modules exist in pom.xml | Minor |
| DRIFT-5 | None | production-deployment.md | Server flags | All flags match ServerConfig.java | Match |
| DRIFT-6 | None | consistency-contract.md | Bounded staleness < 500ms, monotonic reads | StalenessTracker.STALE_THRESHOLD_MS=500, VersionCursor implemented | Match |

---

## 7. Findings Register (All Phases)

### Current Verification (F-series, this audit)

| ID | Severity | Title | Status |
|----|----------|-------|--------|
| F-0009 | **P0** | Linearizable read NOT linearizable — ReadIndex never awaited | **Fixed** |
| F-0010 | **P0** | Data race on ReadIndexState from HTTP handler threads | **Fixed** |
| F-0011 | **P1** | WAL recovery crashes on truncated trailing entry | **Fixed** |
| F-0012 | **P1** | Missing directory fsync after WAL rewrite in truncateFrom() | **Fixed** |
| F-0013 | **P2** | Snapshot key length overflow for keys > 65535 bytes | **Fixed** |
| F-0014 | **P2** | FanOutBuffer.deltasSince() can silently miss deltas | **Documented** — defense-in-depth in DeltaApplier |

### Prior Verification Rounds (FIND-series, for reference)

| ID | Severity | Title | Status |
|----|----------|-------|--------|
| FIND-0001 | Critical | triggerSnapshot() captures uncommitted config state | Fixed |
| FIND-0002 | Critical | ReadIndex not linearizable under leadership change race | Fixed |
| FIND-0003 | Critical | ReadResult "zero allocation" claim is false | Fixed |
| FIND-0004 | Critical | Signature verification encoding path mismatch | Fixed |
| FIND-0005 | Critical | Raft tick loop silently dies on uncaught exception | Fixed |
| FIND-0006 | Critical | FileStorage.put() not crash-safe (no atomic rename) | Fixed |
| FIND-0007 | Critical | Auth disabled by default — unauthenticated production | Mitigated |
| FIND-0008 | Critical | FanOutBuffer ring buffer TOCTOU race and gap bug | Mitigated |

Prior audit rounds (docs/audit, docs/prr) report 36 additional findings — all fixed.

---

## 8. Build & Environment Baseline

| Property | Value |
|----------|-------|
| Java | Amazon Corretto 25.0.2+10-LTS |
| Build | Maven 3.x, `maven.compiler.release=25`, `--enable-preview` |
| GC | ZGC (configured in production-deployment.md) |
| Dependencies | Agrona 1.23.1, JCTools 4.0.5, JUnit 5.11.4, JMH 1.37 |
| External deps | Zero (no Netty, no gRPC, no Spring, no Guava) |
| TLA+ | TLC model checker, ConsensusSpec.tla |
| Test framework | JUnit 5, jqwik (PBT), JMH (benchmarks), custom deterministic sim |

---

## Phase V1 Exit Criteria

- [x] Module inventory complete (11 modules, 100 source files, 15,484 LoC)
- [x] Test inventory complete (21,222 tests, all passing)
- [x] TLA+ spec inventory complete (8 invariants + 1 unchecked liveness)
- [x] Benchmark inventory complete (7 benchmark classes, 17 methods)
- [x] Property-based test inventory (4 jqwik test classes)
- [x] Doc-vs-code drift scan complete (2 critical drifts, 1 low, 1 minor)
- [x] Findings register current (6 F-series + 8 FIND-series + 36 prior)
- [x] Build and environment pinned

**Phase V1 status: COMPLETE.** Proceed to Phase V2 (Formal Re-Verification).

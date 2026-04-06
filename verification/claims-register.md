# Claims Register

> Every numerical or behavioral claim in the repo, with source and verification status.
> Produced by: Blue Team (Phase 0)
> Date: 2026-04-13

---

## Status Key
- **Verified**: Independently reproduced or proven
- **Unverified**: Not yet checked
- **Refuted**: Claim does not hold
- **Untestable**: Cannot be verified in current environment

---

## Performance Claims

| # | Claim | Source | Status | Notes |
|---|-------|--------|--------|-------|
| P-01 | Edge read p99 < 1ms (in-process) | architecture.md §1, performance.md §3 | Unverified | JMH shows avg 41-147ns but reports AVG not p99. Error bars enormous (±570ns at 10K). |
| P-02 | Edge read p999 < 5ms | architecture.md §1 | Unverified | No p999 data in JMH output. Only avg reported. |
| P-03 | Write commit p99 < 150ms cross-region | architecture.md §1 | Untestable | Network-bound. Claims "< 100ms" based on 68ms RTT + 32ms overhead. |
| P-04 | Propagation p99 < 500ms global | architecture.md §1 | Untestable | Claims "300-400ms" from Plumtree modeling, not measurement. |
| P-05 | Sustained write QPS 10K/s | architecture.md §2 | Unverified | JMH shows 815K ops/s in simulation (no network). No sustained test exists. |
| P-06 | Burst write QPS 100K/s | architecture.md §2 | Unverified | No burst test exists. |
| P-07 | Zero allocation on read path (cache miss) | performance.md §1 | Unverified | Claims "0 B/op" — need -prof gc verification |
| P-08 | ~48 B/op on read path (cache hit) | performance.md §1 | Unverified | ReadResult record allocation |
| P-09 | HAMT read 41 ns at 10K keys | performance.md §3 | Unverified | JMH Cnt=3, huge error ±570ns. Statistically meaningless. |
| P-10 | HAMT read 147 ns at 1M keys | performance.md §3 | Unverified | JMH Cnt=3, error ±88ns |
| P-11 | HLC now() 34 ns | performance.md §3 | Unverified | JMH Cnt=3, error ±17ns |
| P-12 | Raft commit 815K ops/s (3-node) | performance.md §3 | Unverified | JMH Cnt=3, error ±851. Confidence interval crosses zero. SUSPECT. |
| P-13 | Plumtree broadcast 0.25 μs (10 peers) | performance.md §3 | Unverified | |
| P-14 | Zero locks on read path | performance.md §2 | Unverified | Claim is "verified by design" — needs async-profiler |
| P-15 | Batch window 200μs bounded | architecture.md §2 | Unverified | Not measured |
| P-16 | Leader failure recovery < 5s | performance.md §9 | Unverified | Measured in simulation only |

### CRITICAL issue with JMH results
The documented JMH results show `Cnt=3` (3 measurement iterations). The docs claim `-wi 5 -i 10 -f 3` (5 warmup, 10 measurements, 3 forks = 30 data points). The actual output shows only 3 data points. Either:
1. The benchmarks were run with different parameters than documented (integrity issue)
2. The output was truncated or misreported

Error bars on P-09 (HamtReadBenchmark.get at 10K): `52.939 ± 569.997 ns/op` — the error is **10x the measurement**. This result is statistically meaningless.

Error bars on P-12 (RaftCommitBenchmark): `0.815 ± 0.851 ops/μs` — the confidence interval includes zero. This is not a valid measurement.

---

## Consistency Claims

| # | Claim | Source | Status | Notes |
|---|-------|--------|--------|-------|
| C-01 | Writes are linearizable per Raft group | consistency-contract.md §1 | Unverified | Property test exists (LinearizabilityTest) but uses custom checker, not Porcupine/Jepsen |
| C-02 | Edge staleness p99 < 500ms | consistency-contract.md §2 | Unverified | StalenessUpperBoundTest exists but runs in simulation only |
| C-03 | Monotonic reads per client session | consistency-contract.md §3 | Unverified | MonotonicReadTest referenced but need to verify it actually exercises the path |
| C-04 | Read-your-writes in same region | consistency-contract.md §6 | Unverified | ReadYourWritesTest referenced but need to verify |
| C-05 | Version sequence gap-free | consistency-contract.md §4 | Unverified | SequenceGapFreeTest exists |
| C-06 | Version sequence strictly monotonic | consistency-contract.md §4 | Unverified | SequenceMonotonicityTest exists |
| C-07 | Monotonic reads survive edge failover | consistency-contract.md §3 | Unverified | MonotonicReadFailoverTest referenced — need to verify it exists |
| C-08 | ReadIndex is linearizable | consistency-contract.md §1 | Unverified | ReadIndex implemented in RaftNode but no linearizability proof |

---

## Architecture Claims

| # | Claim | Source | Status | Notes |
|---|-------|--------|--------|-------|
| A-01 | Lock-free read path | architecture.md §3, ADR-0005 | Unverified | Needs code trace + async-profiler |
| A-02 | Single-writer / multi-reader model | architecture.md §3 | Unverified | Needs code trace for threading model |
| A-03 | HAMT O(log32 N) lookup | architecture.md §3 | Unverified | Need to verify branching factor in code |
| A-04 | Plumtree O(N) message complexity | architecture.md §7 | Unverified | Need to verify algorithm correctness |
| A-05 | HyParView maintains connectivity | architecture.md §7 | Unverified | Need to verify overlay correctness |
| A-06 | Credit-based flow control | architecture.md §7 | Unverified | Need to verify implementation exists |
| A-07 | Catch-up via delta replay or snapshot | architecture.md §7 | Unverified | Need to verify CatchUpService impl |
| A-08 | Joint consensus for reconfig | architecture.md §6 | Unverified | Code review in progress |
| A-09 | PreVote prevents term inflation | architecture.md §6 | Unverified | Code review in progress |
| A-10 | CheckQuorum forces leader step-down | architecture.md §6 | Unverified | Code review in progress |

---

## Security Claims

| # | Claim | Source | Status | Notes |
|---|-------|--------|--------|-------|
| S-01 | mTLS on all control plane connections | architecture.md (implied) | Unverified | TlsManager/TlsConfig exist but need verification |
| S-02 | Config signatures checked at every hop | architecture.md §8 | Unverified | ConfigSigner exists but need to verify check at every hop |
| S-03 | Replay protection via version check | consistency-contract.md (implied) | Unverified | |
| S-04 | ACL check on every endpoint | control-plane-api | Unverified | AuthInterceptor exists |
| S-05 | Audit log on every mutation | architecture.md (implied) | Unverified | No AuditLog class found — SUSPECT |
| S-06 | No secrets in logs | general | Unverified | |
| S-07 | No unsafe deserialization | general | Unverified | ByteBuffer-based, no ObjectInputStream expected |

---

## TLA+ Spec Claims

| # | Claim | Source | Status | Notes |
|---|-------|--------|--------|-------|
| T-01 | All 8 safety invariants pass | tlc-results.md | Unverified | TLC re-run in progress |
| T-02 | 13.7M states explored | tlc-results.md | Unverified | Re-running |
| T-03 | Model covers joint consensus | ConsensusSpec.tla | Unverified | Spec includes ProposeConfigChange, CommitJointConfig |
| T-04 | Liveness property checked | ConsensusSpec.tla | SUSPECT | EdgePropagationLiveness is temporal — TLC may not check it without fairness constraints |
| T-05 | Spec covers all code paths | claimed | Refuted | PreVote, CheckQuorum, ReadIndex, leadership transfer NOT in spec |

---

## Surpass-Quicksilver Claims

| # | Claim | Source | Status | Notes |
|---|-------|--------|--------|-------|
| Q-01 | Write commit < 150ms vs Quicksilver ~500ms | performance.md §11 | Unverified | Based on RTT modeling, not measurement |
| Q-02 | Edge staleness < 500ms vs Quicksilver ~2.3s | performance.md §11 | Unverified | Based on Plumtree modeling |
| Q-03 | Write throughput 10K+/s vs Quicksilver ~350/s | performance.md §11 | Unverified | Based on simulated Raft throughput |
| Q-04 | Operational complexity: single artifact | performance.md §11 | Unverified | No Docker/deployment artifacts found |

---

## Summary

| Category | Total Claims | Verified | Unverified | Refuted | Untestable |
|----------|-------------|----------|------------|---------|------------|
| Performance | 16 | 0 | 12 | 0 | 4 |
| Consistency | 8 | 0 | 8 | 0 | 0 |
| Architecture | 10 | 0 | 10 | 0 | 0 |
| Security | 7 | 0 | 7 | 0 | 0 |
| TLA+ | 5 | 0 | 3 | 1 | 0 |
| Surpass-Quicksilver | 4 | 0 | 4 | 0 | 0 |
| **TOTAL** | **50** | **0** | **44** | **1** | **4** |

**CRITICAL: Zero claims independently verified at this stage.**
**One claim already refuted: T-05 (spec covers all code paths) — PreVote, CheckQuorum, ReadIndex, and leadership transfer are NOT modeled in the TLA+ spec.**

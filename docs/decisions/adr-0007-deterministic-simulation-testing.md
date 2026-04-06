# ADR-0007: Deterministic Simulation Testing + TLA+ Formal Verification

## Status
Accepted

## Context
Distributed systems have failure modes that are invisible to unit tests, integration tests, and even stress tests. The etcd v3.5 data corruption bug (10 months in production), ZooKeeper's 6 deep bugs found only by multi-grained TLA+ (Huang et al., 2023), and EPaxos' ballot management bug (Sutra, 2019) demonstrate that correctness requires both formal verification and simulation testing.

## Decision
We adopt a two-pronged verification strategy:

### 1. TLA+ Formal Specification
- **Scope:** Consensus protocol, reconfiguration, and edge propagation.
- **Tool:** Apalache (symbolic model checker) for fast bug finding + TLC (explicit-state) for higher confidence on small models.
- **Multi-grained approach** (Huang et al., 2023): Protocol-level spec for design bugs + implementation-level spec for code bugs.
- **Mandatory invariants in TLA+:**
  - Leader election safety and liveness
  - Log matching and state machine safety
  - Reconfiguration safety (single-server with no-op fix)
  - Version monotonicity: no version ever decreases at any observer
  - No stale overwrite: write at version V cannot be overwritten by write < V
  - Edge propagation correctness: every committed write eventually reaches every live edge, bounded by staleness contract

### 2. Deterministic Simulation Testing (FoundationDB-style)
- **Runtime:** Project Loom virtual threads with custom single-threaded executor.
- **Determinism:** Seeded PRNG (`deterministicRandom()`) for all randomness.
- **Simulated infrastructure:** Interface-based `Network`, `Clock`, `Storage` with real and simulated implementations.
- **Fault injection:** `@Buggify` annotation on production code paths. Only fires in simulation mode. ~1000 injection points targeting: extra latency, wrong error codes, partial writes, slow disk, dropped messages.
- **Fault scenarios:** Machine failure, rack failure, network partition (symmetric + asymmetric), disk failure, clock skew, message reorder/drop/duplication.
- **Accelerated time:** Failures that happen yearly in production simulated every minute.
- **Test oracles:** Property-based assertions verifying invariants after each simulated step.
- **Scale:** Thousands of simulation runs per CI build. Seeded for reproducibility — failing seed = reproducible bug.

### 3. Runtime Assertions
Every TLA+ invariant has a corresponding runtime assertion in Java:
- **Test mode:** Assertion violation = test failure + stack trace.
- **Production mode:** Assertion violation = metric increment (`configd.invariant.violation`) + structured log + alert. Never crash.

## Influenced by
- **FoundationDB:** "Only 1-2 customer-reported bugs across entire company history." Deterministic simulation with BUGGIFY found bugs no other method catches. ~1 trillion CPU-hours of simulation.
- **TigerBeetle:** Deterministic simulation in Zig, inspired by FoundationDB.
- **James Baker (Project Loom approach):** 40,000 simulated Raft rounds/second on single core with virtual threads.
- **OpenDST (Ping Identity):** Java Agent bytecode instrumentation for deterministic testing.
- **Huang et al., 2023:** Multi-grained TLA+ found 6 deep bugs in ZooKeeper (v3.4.10-v3.9.1).

## Reasoning
### Why both TLA+ and simulation?
- TLA+ catches design bugs (protocol-level incorrectness) but has state space explosion for large models.
- Simulation catches implementation bugs (race conditions, resource leaks, edge cases) but only explores paths the random seed happens to trigger.
- Together, they cover both design and implementation correctness.

### Why not Jepsen alone?
- Jepsen tests real deployments with real network faults. Valuable but slow (hours per run), non-deterministic (failing tests may not reproduce), and limited to scenarios the test framework can inject.
- Simulation runs thousands of scenarios per second, deterministically. Jepsen validates that simulation results hold in real deployments.
- We use both: simulation for development-time bug finding, Jepsen-style tests for release qualification.

## Consequences
- **Positive:** Highest achievable confidence in correctness. Reproducible bug reports (seed-based). Catches bugs before production. Runtime assertions provide continuous production verification.
- **Negative:** Significant engineering investment (simulation framework, @Buggify infrastructure, TLA+ specs). Simulation adds ~30 minutes to CI pipeline.
- **Risks and mitigations:** Simulation-production divergence (simulation doesn't model JIT, GC pressure, real network behavior) mitigated by integration tests and Jepsen-style qualification. TLA+ spec drift from implementation mitigated by CI check that extracts key invariants from both spec and code.

## Reviewers
- principal-distributed-systems-architect: ✅
- formal-methods-engineer: ✅
- qa-test-engineer: ✅

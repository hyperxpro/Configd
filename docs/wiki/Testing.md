# Testing

Configd uses a layered testing strategy: unit tests, end-to-end pipeline tests, deterministic
simulation, linearizability checking, and Java Memory Model concurrency tests.

## Running tests

```bash
# Everything (full reactor)
./mvnw test

# One module
./mvnw -pl configd-consensus-core test

# One test class
./mvnw -pl configd-consensus-core test -Dtest=RaftNodeTest

# In a hermetic container
docker build -f docker/Dockerfile.build -t configd-build .
docker run --rm configd-build
```

The `--enable-preview` flag is configured in the build (compiler and the Surefire test JVM), so you
never pass it by hand. Note that some harness modules (`configd-testkit`) are not on the `-am`
dependency path of the runtime modules, so a `mvn clean test-compile` at the reactor root is the way
to compile the whole tree at once.

## Test categories

### Unit tests

Each module has focused unit tests over its public API and edge cases.

| Module | Example test classes | Covers |
|---|---|---|
| configd-consensus-core | `RaftNodeTest` | election, replication, PreVote, CheckQuorum, leadership transfer |
| configd-config-store | `VersionedConfigStoreTest`, `HamtMapTest` | MVCC put/delete/batch, structural sharing, version monotonicity |
| configd-edge-cache | `LocalConfigStoreTest`, `StalenessTrackerTest` | lock-free reads, delta application, cursor enforcement, state transitions |

### End-to-end pipeline tests

`EndToEndTest` (present in several modules) validates the full write pipeline: write to the
`VersionedConfigStore`, compute a delta with `DeltaComputer`, apply it to a `LocalConfigStore`, and
verify read consistency.

### Deterministic simulation (configd-testkit)

Inspired by FoundationDB (ADR-0007), the simulation runs a multi-node cluster in a single thread with
controlled time, so a run is fully reproducible from its seed.

- `RaftSimulation` / `SimulatedNetwork` -- a seeded Raft cluster with injectable network faults
  (partitions, delays, drops, reordering).
- `AdversarialSim` -- adversarial fault schedules over the same engine.
- `MultiShardSim` -- multiple Raft groups under one driver, for the sharding invariants.
- `EdgeFanOutSim` -- the edge fan-out and staleness-distribution simulations.
- `InMemoryRaftCluster` -- an in-memory multi-node cluster used by higher-level tests and benchmarks.

Properties the simulation gives you:

- **Deterministic** -- the same seed produces the same execution.
- **Reproducible** -- a failing seed replays exactly.
- **Single-threaded** -- no concurrency bugs in the harness itself.
- **Fast** -- no real I/O or sleeps; thousands of simulated seconds run in milliseconds.

Seed sweeps (many thousands of seeds) and adversarial schedules run as part of the nightly gates.

### Linearizability (configd-linz)

The `configd-linz` module records operation histories from a live cluster (`Cluster`, `ClusterNode`,
`ConfigClient`, `FaultInjector`, `HistoryRecorder`) and checks them with a Porcupine-style
linearizability checker (`PorcupineChecker`, `Verdict`). It covers the strong-consistency claims --
including targeted scenarios such as lost-write and stale-read (`LostWriteScenario`,
`StaleReadScenario`) -- and can check both live histories and recorded simulation histories.

### Java Memory Model concurrency (configd-jcstress)

The `configd-jcstress` module runs jcstress tests that stress the publication and ownership invariants
under the real memory model: `RaftOwnerThreadGuardTest` (the owner-thread guard), 
`RaftMonitorViewPublicationTest` (the `monitorView()` snapshot never tears),
`RehomingDoubleOwnershipTest`, `HamtMapStructuralSharingTest`, and the edge/store read tests. These
back the guarantees in
[`../architecture/raft-threading-contract.md`](../architecture/raft-threading-contract.md).

## Test conventions

- Tests use JUnit 5. `--enable-preview` is supplied by the build.
- Randomness uses `java.util.random.RandomGenerator` with explicit seeds for reproducibility.
- No external dependencies -- everything runs in-process; no databases or network services.
- `configd-testkit`, `configd-linz`, and `configd-jcstress` are test/verification harnesses, not
  published runtime artifacts.

## Coverage philosophy

There is no enforced line-coverage threshold. The emphasis is:

1. **Correctness of invariants** over line coverage.
2. **Simulation breadth** (many seeds, many fault scenarios) over targeted unit tests.
3. **Deterministic reproducibility** -- every failure is replayable from its seed.

Mutation testing is used as the quality signal for the safety-critical modules, rather than a raw
coverage percentage.

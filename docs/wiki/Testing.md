# Testing

Configd uses a multi-layered testing strategy: unit tests, integration tests, and deterministic simulation.

## Running Tests

```bash
# All tests
./gradlew test

# Single module
./gradlew :configd-consensus-core:test

# Single test class
./gradlew :configd-consensus-core:test --tests "io.configd.raft.RaftNodeTest"

# With Docker (hermetic)
docker build -f docker/Dockerfile.build -t configd-build .
docker run --rm configd-build
```

## Test Categories

### Unit Tests

Each module has focused unit tests covering its public API and edge cases.

| Module | Key Test Classes | What's Covered |
|--------|-----------------|----------------|
| configd-consensus-core | `RaftNodeTest` | Leader election, log replication, PreVote, CheckQuorum, leadership transfer |
| configd-config-store | `VersionedConfigStoreTest`, `HamtMapTest` | MVCC put/delete/batch, structural sharing, version monotonicity |
| configd-edge-cache | `LocalConfigStoreTest`, `StalenessTrackerTest` | Lock-free reads, delta application, cursor enforcement, state transitions |

### End-to-End Tests

`EndToEndTest` in `configd-testkit` validates the full write pipeline:

1. Write to `VersionedConfigStore`
2. Compute delta via `DeltaComputer`
3. Apply delta to `LocalConfigStore`
4. Verify read consistency

### Deterministic Simulation (`configd-testkit`)

Inspired by FoundationDB's simulation testing (ADR-0007). The simulation framework provides:

- **`RaftSimulation`** — runs a multi-node Raft cluster in a single thread with controlled time advancement
- **`SimulatedNetwork`** — injects network faults: partitions, message delays, message drops, reordering
- **`SimulatedClock`** — logical clock that advances only when explicitly ticked

Key properties:

- **Deterministic**: Same seed produces the same execution every time
- **Reproducible**: A failing seed can be replayed exactly
- **Single-threaded**: No concurrency bugs in the test harness itself
- **Fast**: No real I/O or sleeps — thousands of simulated seconds run in milliseconds

#### Writing a Simulation Test

```java
import io.configd.testkit.RaftSimulation;

@Test
void clusterElectsLeaderAfterPartitionHeals() {
    RaftSimulation sim = new RaftSimulation(5, /* seed= */ 42L);

    // Advance time until a leader is elected
    sim.tickUntilLeaderElected(10_000);
    assertNotNull(sim.currentLeader());

    // Partition the leader from the cluster
    sim.partitionNode(sim.currentLeader());
    sim.tickAll(5_000);

    // A new leader should be elected among the remaining nodes
    assertNotNull(sim.currentLeader());

    // Heal the partition
    sim.healAllPartitions();
    sim.tickAll(5_000);

    // The old leader should step down and follow the new leader
    assertEquals(1, sim.leaderCount());
}
```

## Test Conventions

- All tests run with `--enable-preview` (configured in root `build.gradle.kts`)
- Tests use `java.util.random.RandomGenerator` with explicit seeds for reproducibility
- No external dependencies (databases, network services) — everything is in-process
- `configd-testkit` is a test-only module — it is not published as a library artifact

## Coverage

There is no enforced coverage threshold. The testing philosophy prioritizes:

1. **Correctness of invariants** over line coverage
2. **Simulation breadth** (many seeds, many fault scenarios) over targeted unit tests
3. **Deterministic reproducibility** — every failure is replayable

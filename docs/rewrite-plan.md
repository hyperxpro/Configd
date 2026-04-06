# Rewrite Plan — Module Structure, Dependencies, and Migration Order

> **Phase 4 deliverable.**
> This is a greenfield build, not a migration. The "rewrite" replaces the conceptual Quicksilver-like scaffold with a purpose-built architecture.

---

## 1. Build System

**Gradle multi-module** with Kotlin DSL. Chosen over Maven for:
- Parallel task execution (faster builds)
- Incremental compilation
- Build cache
- Kotlin DSL provides type-safe configuration

### Root `settings.gradle.kts`
```kotlin
rootProject.name = "configd"

include(
    "configd-consensus-core",
    "configd-replication-engine",
    "configd-config-store",
    "configd-edge-cache",
    "configd-distribution-service",
    "configd-control-plane-api",
    "configd-transport",
    "configd-observability",
    "configd-testkit",
    "configd-common"
)
```

---

## 2. Module Inventory

### 2.1 `configd-common`
**Purpose:** Shared types, interfaces, and utilities used across all modules.

**Contents:**
- `Clock` interface (real + simulated implementations)
- `Network` interface (real + simulated)
- `Storage` interface (real + simulated)
- HLC (Hybrid Logical Clock) implementation
- Common protobuf message definitions
- `@Buggify` annotation and runtime support
- Deterministic random (`DeterministicRandom` seeded PRNG)
- Configuration key types (`ConfigKey`, `ConfigValue`, `ConfigScope`)

**Dependencies:** None (leaf module).

**Tech:** Java 21+, Agrona, protobuf.

### 2.2 `configd-consensus-core`
**Purpose:** Raft consensus engine — leader election, log replication, state machine interface, snapshots, reconfiguration.

**Contents:**
- `RaftNode` — core Raft state machine (follower/candidate/leader)
- `RaftLog` — persistent log with WAL and compaction
- `StateMachine` interface — applied by config-store
- `ElectionManager` — PreVote + CheckQuorum + leadership transfer
- `LogReplicator` — batched, pipelined AppendEntries with flow control
- `SnapshotManager` — chunked InstallSnapshot, copy-on-write
- `ReconfigManager` — single-server changes with mandatory no-op commit
- `ReadIndex` — linearizable read protocol
- `RaftMetrics` — term, commit index, apply index, replication lag

**Dependencies:** `configd-common`, `configd-transport`

**Tech:** Java 21+, Agrona (off-heap log buffers), JCTools (SPSC queue for apply thread).

**Key design constraints:**
- Single Raft I/O thread per group (no synchronization needed within group)
- All state transitions driven by message receipt or timer expiry
- Timer management via `Clock` interface (simulatable)
- No static mutable state — all state scoped to `RaftNode` instance

### 2.3 `configd-replication-engine`
**Purpose:** Log shipping, batching, pipelining, flow control between Raft nodes. Manages multiple Raft groups on a single node.

**Contents:**
- `MultiRaftDriver` — drives multiple `RaftNode` instances, coalesces heartbeats
- `ReplicationPipeline` — batched AppendEntries with configurable max batch size/delay
- `FlowController` — credit-based flow control per follower
- `SnapshotTransfer` — chunked snapshot send/receive with progress tracking
- `HeartbeatCoalescer` — one heartbeat per node pair per tick (CockroachDB pattern)

**Dependencies:** `configd-common`, `configd-consensus-core`, `configd-transport`

**Tech:** Java 21+, Netty (for async I/O), Agrona (ring buffers for batching).

### 2.4 `configd-config-store`
**Purpose:** Versioned configuration storage with MVCC, immutable snapshots, and state machine implementation.

**Contents:**
- `ConfigStateMachine` implements `StateMachine` — applies committed Raft entries
- `VersionedStore` — MVCC key-value store with configurable retention window
- `HamtMap` — Hash Array Mapped Trie implementation with structural sharing
- `Snapshot` — immutable point-in-time view of the config store
- `DeltaComputer` — computes minimal diff between two snapshots
- `Compactor` — background compaction of old versions (non-blocking)
- `ConfigValidator` — pluggable validation callbacks per key prefix

**Dependencies:** `configd-common`, `configd-consensus-core`

**Tech:** Java 21+, custom HAMT implementation, Agrona (off-heap value storage for large values).

**Key design constraints:**
- Single writer thread (apply thread from Raft)
- Immutable snapshots published via volatile reference swap
- Readers never allocate, never lock, never block writer
- Compaction runs on separate thread, never blocks reads or writes

### 2.5 `configd-edge-cache`
**Purpose:** Client-side library for edge nodes. Lock-free read path, version-aware, delta application.

**Contents:**
- `EdgeConfigClient` — main entry point for edge applications
- `LocalStore` — in-process HAMT behind volatile pointer (ADR-0005)
- `DeltaApplier` — receives deltas from distribution stream, applies to HAMT, swaps pointer
- `VersionCursor` — tracks last-read version for monotonic-read enforcement
- `StalenessTracker` — monitors time since last applied update, emits metrics
- `PrefixSubscription` — manages subscription to key prefixes
- `NegativeCache` — Bloom filter for fast rejection of non-existent keys

**Dependencies:** `configd-common`

**Tech:** Java 21+, gRPC client (for connecting to distribution service), Micrometer (metrics).

**Key design constraints:**
- Read path: volatile load → HAMT traverse → return. NOTHING else.
- Write path (delta application): single thread, never blocks readers
- Library must be embeddable in any Java application with minimal footprint
- No Spring dependency — pure Java library

### 2.6 `configd-distribution-service`
**Purpose:** Fan-out from control plane to edge nodes. Plumtree + HyParView overlay. Subscription management. Catch-up protocol.

**Contents:**
- `PlumtreeNode` — epidemic broadcast tree node (eager/lazy peer management, PRUNE/GRAFT)
- `HyParViewOverlay` — active/passive view management, ForwardJoin, Shuffle
- `SubscriptionManager` — prefix-based subscription tracking
- `CatchUpService` — delta replay from WAL or snapshot transfer for lagging nodes
- `SlowConsumerPolicy` — 30s threshold → disconnect → quarantine → re-bootstrap
- `FanOutBuffer` — shared immutable event buffer for efficient multi-subscriber serialization
- `ProgressNotifier` — periodic liveness signals on idle streams
- `RolloutController` — progressive rollout stage management (ADR-0008)

**Dependencies:** `configd-common`, `configd-config-store`, `configd-transport`

**Tech:** Java 21+, gRPC server (for edge node connections), Netty (for Plumtree protocol), virtual threads (for per-stream handling).

### 2.7 `configd-control-plane-api`
**Purpose:** Admin and write APIs. Authentication, authorization, audit logging.

**Contents:**
- `ConfigWriteService` — handles write requests, routes to appropriate Raft group
- `ConfigReadService` — linearizable reads via ReadIndex, stale reads from local store
- `AdminService` — cluster management (add/remove nodes, reconfiguration)
- `AclService` — per-key-prefix ACL enforcement
- `AuditLogger` — structured audit log for all write operations
- `HealthService` — liveness, readiness, and detailed health checks
- `RateLimiter` — per-producer write quotas

**Dependencies:** `configd-common`, `configd-consensus-core`, `configd-replication-engine`, `configd-config-store`, `configd-observability`

**Tech:** Spring Boot 3.x (control plane only, NEVER on hot read path), Spring Security, gRPC-Spring integration.

### 2.8 `configd-transport`
**Purpose:** Network transport layer. Custom framed protocol for data plane, gRPC for control plane. mTLS.

**Contents:**
- `NettyTransport` — Netty-based transport for Raft and Plumtree messages
- `FrameCodec` — encoder/decoder for custom wire format (ADR-0010)
- `ConnectionManager` — connection pooling, reconnection with backoff
- `TlsManager` — mTLS certificate management, rotation
- `MessageRouter` — routes incoming messages to correct Raft group or Plumtree node
- `BatchEncoder` — manual batching with bounded delay (200μs Raft, 100μs Plumtree)

**Dependencies:** `configd-common`

**Tech:** Netty 4.x, Netty-tcnative (for OpenSSL-based TLS), gRPC-java, protobuf.

### 2.9 `configd-observability`
**Purpose:** Metrics, tracing, structured logging, continuous profiling hooks.

**Contents:**
- `MetricsRegistry` — Micrometer-based metrics (counters, gauges, histograms, timers)
- `TracingInterceptor` — OpenTelemetry span creation for cross-node requests
- `StructuredLogger` — JSON-structured logging with correlation IDs
- `InvariantMonitor` — runtime assertion checking with metric emission
- `ProfilingHooks` — async-profiler integration points for continuous profiling
- `SloTracker` — SLO/SLI computation and alerting

**Predefined metrics:**
- `configd.raft.commit_latency` (histogram, per group)
- `configd.raft.term` (gauge, per group)
- `configd.raft.apply_lag` (gauge, per group)
- `configd.edge.staleness_ms` (histogram)
- `configd.edge.read_latency_ns` (histogram)
- `configd.distribution.propagation_ms` (histogram)
- `configd.distribution.fan_out_queue_depth` (gauge)
- `configd.invariant.violation` (counter, per invariant name)

**Dependencies:** `configd-common`

**Tech:** Micrometer, OpenTelemetry, SLF4J + Logback, HdrHistogram.

### 2.10 `configd-testkit`
**Purpose:** Deterministic simulation, property tests, Jepsen harness, conformance suite.

**Contents:**
- `SimulationRuntime` — single-threaded executor with simulated time (Project Loom virtual threads)
- `SimulatedNetwork` — message delivery with configurable latency, drops, reordering, partitions
- `SimulatedClock` — deterministic time advancement
- `SimulatedStorage` — in-memory storage with fault injection (slow disk, corruption)
- `BuggifyRuntime` — manages @Buggify point activation per simulation run
- `PartitionMatrix` — defines network partition scenarios (symmetric, asymmetric, gray failure)
- `PropertyTest` — base class for invariant-checking property tests
- `ConformanceSuite` — black-box client tests validating consistency contract
- `JepsenHarness` — integration with Jepsen for real-deployment testing
- `LoadGenerator` — configurable write/read workload generator

**Dependencies:** `configd-common`, `configd-consensus-core`, `configd-replication-engine`, `configd-config-store`, `configd-edge-cache`, `configd-distribution-service`

**Tech:** JUnit 5, JMH, jqwik (property-based testing), Testcontainers (for Jepsen).

---

## 3. Dependency Graph

```
configd-common (leaf — no dependencies)
    ↑
    ├── configd-transport
    │       ↑
    │       ├── configd-consensus-core
    │       │       ↑
    │       │       ├── configd-replication-engine
    │       │       └── configd-config-store
    │       │               ↑
    │       │               └── configd-distribution-service
    │       └── configd-distribution-service
    │
    ├── configd-edge-cache (standalone library)
    ├── configd-observability
    │       ↑
    │       └── configd-control-plane-api
    │               (depends on: consensus-core, replication-engine, config-store, observability)
    │
    └── configd-testkit (depends on everything — test scope only)
```

---

## 4. Implementation Order

### Phase 5a: Foundation (Week 1-2)
1. `configd-common` — interfaces, HLC, protobuf messages, @Buggify
2. `configd-transport` — Netty transport, frame codec, connection management
3. `configd-testkit` — simulation runtime (needed for testing everything else)

### Phase 5b: Consensus (Week 3-4)
4. `configd-consensus-core` — Raft state machine, election, log replication
5. `configd-replication-engine` — multi-Raft driver, heartbeat coalescing

### Phase 5c: Storage & Distribution (Week 5-6)
6. `configd-config-store` — HAMT, versioned store, state machine, compactor
7. `configd-edge-cache` — local store, delta applier, version cursor
8. `configd-distribution-service` — Plumtree, HyParView, subscriptions, catch-up

### Phase 5d: API & Observability (Week 7)
9. `configd-observability` — metrics, tracing, invariant monitor
10. `configd-control-plane-api` — Spring Boot API, ACL, audit

### Phase 5e: Integration (Week 8)
11. End-to-end integration: write → consensus → distribute → edge read
12. Conformance suite against consistency contract
13. Performance baseline measurements

---

## 5. Tech Stack Summary

| Layer | Technology | Justification |
|---|---|---|
| Language | Java 21+ LTS | Virtual threads, modern ZGC, pattern matching |
| GC | ZGC | Sub-ms pauses (ADR-0009) |
| Build | Gradle (Kotlin DSL) | Parallel builds, incremental compilation |
| Consensus I/O | Netty 4.x | Non-blocking, zero-copy, mTLS |
| Control API | Spring Boot 3.x | Control plane only. REST + gRPC |
| Client Streams | gRPC-java | Streaming subscriptions, deadline propagation |
| Off-heap | Agrona | Ring buffers, direct byte buffers, off-heap maps |
| Lock-free queues | JCTools | MPSC/SPSC for inter-thread hand-off |
| Serialization | Protocol Buffers | Schema evolution, language-neutral |
| Metrics | Micrometer + HdrHistogram | Percentile-accurate histograms |
| Tracing | OpenTelemetry | Distributed tracing across nodes |
| Logging | SLF4J + Logback | Structured JSON logging |
| Testing | JUnit 5 + jqwik + JMH | Unit + property-based + benchmark |
| Code generation | Lombok | Boilerplate only (builders, equals/hashCode) |

### Explicitly Forbidden on Hot Path
- `synchronized`, `ReentrantLock` on reads
- Allocation in steady state (verified by JMH `-prof gc`)
- Reflection, dynamic proxies
- Logging at INFO+ per-request
- Autoboxing
- `String.format()` or string concatenation
- `HashMap` (use primitive-specialized maps)
- Virtual threads for CPU-bound work

---

## 6. Key Interfaces (Contract Between Modules)

```java
// configd-common
public interface Clock {
    long currentTimeMillis();
    long nanoTime();
    HybridTimestamp now();  // HLC
}

public interface Network {
    void send(NodeId target, Message message);
    void registerHandler(MessageType type, MessageHandler handler);
    CompletableFuture<Void> connect(NodeId target);
}

public interface Storage {
    void append(long index, byte[] entry);
    byte[] read(long index);
    void truncateAfter(long index);
    long lastIndex();
    Snapshot loadSnapshot();
    void saveSnapshot(Snapshot snapshot);
}

// configd-consensus-core
public interface StateMachine {
    void apply(long index, long term, byte[] command);
    Snapshot snapshot();
    void restore(Snapshot snapshot);
}

// configd-config-store
public interface ConfigReader {
    ConfigValue get(ConfigKey key);
    ConfigValue get(ConfigKey key, VersionCursor cursor);
    Map<ConfigKey, ConfigValue> getPrefix(String prefix);
    long currentVersion();
}
```

These interfaces enable simulation swapping (real ↔ simulated implementations) and clean module boundaries.

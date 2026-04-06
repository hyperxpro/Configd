# Module Reference

## configd-common

Shared types used across all modules.

**Key types:**
- `NodeId` — unique node identifier (int-based value type)
- `Clock` / `SystemClock` — time abstraction for testability
- `HybridTimestamp` / `HybridClock` — Hybrid Logical Clock for cross-node ordering
- `ConfigScope` — GLOBAL vs REGIONAL scope marker
- `Buggify` / `BuggifyRuntime` — fault injection hooks for simulation testing

**Dependencies:** Agrona 1.23.1

---

## configd-consensus-core

Full Raft consensus implementation. Single-threaded, tick-driven.

**Key types:**
- `RaftNode` — core state machine driven by `tick()` and `handleMessage()`
- `RaftConfig` — immutable cluster configuration (node ID, peers, timeouts, batch limits)
- `RaftLog` — append-only log with snapshot support
- `RaftRole` — FOLLOWER, CANDIDATE, LEADER
- `StateMachine` — interface for applying committed entries
- `RaftTransport` — interface for sending messages to peers

**Protocol messages:** `AppendEntriesRequest/Response`, `RequestVoteRequest/Response`, `TimeoutNowRequest`

**Implemented Raft features:**
- Leader election with PreVote (section 9.6)
- Log replication with batching
- CheckQuorum (leader steps down without majority contact)
- Leadership transfer (section 3.10)
- No-op commit on leader election (section 5.4.2)

**Dependencies:** configd-common, configd-transport, Agrona, JCTools

---

## configd-config-store

MVCC versioned configuration store. Single-writer, multi-reader.

**Key types:**
- `VersionedConfigStore` — the control plane store; `put()`, `delete()`, `applyBatch()`, `get()`
- `ConfigSnapshot` — immutable point-in-time snapshot (HAMT + version + timestamp)
- `HamtMap<K,V>` — persistent Hash Array Mapped Trie with structural sharing
- `ConfigDelta` — minimal diff between two snapshots
- `DeltaComputer` — computes deltas between snapshots
- `ConfigMutation` — sealed interface: `Put` or `Delete`
- `VersionedValue` — value bytes + version + timestamp
- `ReadResult` — read response with `NOT_FOUND` singleton for zero-allocation misses

**Dependencies:** configd-common, Agrona

---

## configd-edge-cache

Lock-free edge-local configuration store. The hot read path.

**Key types:**
- `LocalConfigStore` — volatile HAMT pointer; `get()`, `applyDelta()`, `loadSnapshot()`
- `VersionCursor` — opaque cursor for monotonic read enforcement
- `StalenessTracker` — monitors time since last update (CURRENT / STALE / DEGRADED / DISCONNECTED)

**Dependencies:** configd-common, configd-config-store

---

## configd-transport

Transport abstraction layer for Raft message delivery.

**Key types:**
- `RaftTransport` — interface for sending messages between nodes
- `MessageType` — enum of protocol message types

**Dependencies:** configd-common, Agrona

---

## configd-testkit

Deterministic simulation framework for testing multi-node clusters.

**Key types:**
- `RaftSimulation` — orchestrates a simulated Raft cluster (single-threaded, seeded)
- `SimulatedNetwork` — network fault injection (partitions, delays, drops)
- `SimulatedClock` — logical clock with explicit time advancement

**Dependencies:** configd-common, configd-consensus-core, configd-config-store, configd-edge-cache, configd-transport, JUnit 5

**Note:** This is a test-only module. It is not intended for production use.

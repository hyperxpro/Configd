# Audit — Existing Scaffold Assessment

> **Phase 3 deliverable.**
> Auditor: senior-java-systems-engineer
> Date: 2026-04-09

## Status: Code Review (10 modules, ~70 source files, ~44 test files)

The scaffold contains a complete multi-module implementation across 10 Gradle subprojects with working Raft consensus, MVCC config store, Plumtree distribution, edge caching, replication engine, transport, observability, and deterministic simulation testing. All tests pass. No external dependencies (only JUnit 5 in test scope).

---

## 1. Architectural Violations

### AV-1: Read Path Allocation (SEVERITY: HIGH)
**File:** `configd-config-store/src/main/java/io/configd/store/ReadResult.java:29`
```java
public static ReadResult found(byte[] value, long version) {
    return new ReadResult(value, version, true);
}
```
Every successful read allocates a new `ReadResult` record. While `NOT_FOUND` is pre-allocated, a cache hit — the common case — creates garbage. At 3+ billion reads/second (Quicksilver scale), this produces ~72 GB/s of short-lived objects. **Violates:** §5 Rule 6 ("No locks on the read path") is satisfied, but the "zero allocation in steady state" requirement from §3 Output 5 Step 3.5 is not.

**Also in:** `configd-edge-cache/src/main/java/io/configd/edge/LocalConfigStore.java:82`

**Fix direction:** Pool or flyweight `ReadResult` objects. Alternatively, return value + version through a reusable `ThreadLocal<ReadResult>` cursor or split into separate `byte[] getValue()` / `long getVersion()` calls.

### AV-2: Hard-coded System.currentTimeMillis() Bypasses Clock Abstraction (SEVERITY: HIGH)
**File:** `configd-config-store/src/main/java/io/configd/store/VersionedConfigStore.java:75`
```java
long timestamp = System.currentTimeMillis();
```
Also at: `:93`, `:118`

**File:** `configd-edge-cache/src/main/java/io/configd/edge/LocalConfigStore.java:153`
```java
long timestamp = System.currentTimeMillis();
```
**File:** `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:170`
```java
long timestamp = System.currentTimeMillis();
```

Direct `System.currentTimeMillis()` calls bypass the `Clock` interface defined in `configd-common`. This breaks deterministic simulation (ADR-0007) — tests cannot control time progression in the store or edge cache. **Violates:** Anti-Pattern 8 (hidden global state).

**Fix direction:** Inject `Clock` into `VersionedConfigStore`, `LocalConfigStore`, and `ConfigStateMachine` constructors. All timestamp acquisition through `clock.currentTimeMillis()`.

### AV-3: VarHandle Reflection for Snapshot Restore (SEVERITY: MEDIUM)
**File:** `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:294-303`
```java
private void restoreStoreState(ConfigSnapshot newSnapshot) {
    var handle = java.lang.invoke.MethodHandles.privateLookupIn(
            VersionedConfigStore.class,
            java.lang.invoke.MethodHandles.lookup()
    ).findVarHandle(VersionedConfigStore.class, "currentSnapshot", ConfigSnapshot.class);
    handle.setVolatile(store, newSnapshot);
}
```
Uses VarHandle with `privateLookupIn` to directly write a private field. Fragile — breaks if the field is renamed, retyped, or access rules change. The long comment block (lines 200-228) documenting the workaround indicates a missing API. **Violates:** §5 Rule 6 (reflection on hot-adjacent path).

**Fix direction:** Add `void restoreSnapshot(ConfigSnapshot snapshot)` method to `VersionedConfigStore`. The method is package-private or public since both classes are in the same module.

### AV-4: handleMessage(Object) Megamorphic Dispatch (SEVERITY: LOW)
**File:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:130`
```java
public void handleMessage(Object message) {
    switch (message) {
        case AppendEntriesRequest req -> ...
```
Accepts `Object` and uses pattern matching. While the JIT can devirtualize sealed hierarchies, using `Object` prevents the compiler from proving a closed type set. Should use a sealed interface.

**Fix direction:** Define `sealed interface RaftMessage permits AppendEntriesRequest, AppendEntriesResponse, RequestVoteRequest, RequestVoteResponse, TimeoutNowRequest, InstallSnapshotRequest, InstallSnapshotResponse {}` and change parameter type.

---

## 2. Scalability Bottlenecks

### SB-1: Unbounded receivedMessages Set in PlumtreeNode (SEVERITY: HIGH)
**File:** `configd-distribution-service/src/main/java/io/configd/distribution/PlumtreeNode.java:78`
```java
this.receivedMessages = new HashSet<>();
```
The `receivedMessages` set grows unbounded as messages are received. While `maxReceivedHistory` is defined (line 60), no eviction mechanism is visible in the constructor. At 10K messages/second, this will OOM within hours.

**Fix direction:** Use a bounded LRU set (e.g., `LinkedHashSet` with `removeEldestEntry` pattern, or a Bloom filter for probabilistic dedup).

### SB-2: ArrayList Without Pre-sizing in RaftLog (SEVERITY: LOW)
**File:** `configd-consensus-core/src/main/java/io/configd/raft/RaftLog.java:49`
```java
this.entries = new ArrayList<>();
```
Default capacity (10 elements). For a log expected to grow to thousands of entries before compaction, this causes repeated array resizing and copying.

**Fix direction:** `new ArrayList<>(1024)` or similar estimate based on expected compaction window.

### SB-3: List.copyOf() Allocation on Replication Path (SEVERITY: MEDIUM)
**File:** `configd-consensus-core/src/main/java/io/configd/raft/RaftLog.java:119`
```java
return List.copyOf(entries.subList(fromOffset, toOffset));
```
Every `entriesFrom()` call copies the sublist. On the replication path (leader → followers), this is called per heartbeat interval per follower. With 100 Raft groups × 4 followers × 10 batches/sec = 4000 copies/sec.

**Fix direction:** Return an unmodifiable view (`Collections.unmodifiableList(entries.subList(...))`) or the subList directly since the Raft I/O thread is single-threaded and callers don't hold references.

### SB-4: O(N) Prefix Scan in VersionedConfigStore.getPrefix() (SEVERITY: MEDIUM)
**File:** `configd-config-store/src/main/java/io/configd/store/VersionedConfigStore.java:183`
```java
snap.data().forEach((key, vv) -> {
    if (key.startsWith(prefix)) {
        results.put(key, ReadResult.found(vv.valueUnsafe(), vv.version()));
    }
});
```
Full HAMT traversal for prefix queries. At 10^6 keys, this scans all entries. Comment at line 180 acknowledges this.

**Fix direction:** Secondary trie index for prefix queries, or replace HAMT with a sorted structure (radix trie, B+ tree) that supports ordered iteration.

---

## 3. Incorrect Assumptions

### IA-1: No Persistence Layer (SEVERITY: CRITICAL)
The Raft log (`RaftLog.java`) is entirely in-memory with no WAL (Write-Ahead Log). A process crash loses all committed state. This fundamentally violates Raft's durability requirement — committed entries must survive node restarts.

**Affected files:** `configd-consensus-core/src/main/java/io/configd/raft/RaftLog.java` (entire file)

**Fix direction:** Implement `Storage` interface (defined in `docs/rewrite-plan.md`) backed by memory-mapped file or RocksDB. WAL with fsync for committed entries.

### IA-2: No Persistent State for currentTerm and votedFor (SEVERITY: CRITICAL)
**File:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:95-96`
```java
this.currentTerm = 0;
this.votedFor = null;
```
Raft requires `currentTerm` and `votedFor` to persist across restarts (§5.2). A node restarting from zero can violate the election safety invariant by voting twice in the same term.

**Fix direction:** Persist `currentTerm` and `votedFor` to durable storage on every update. Load from storage on construction.

### IA-3: Snapshot Key Length Limited to 16-bit (SEVERITY: LOW)
**File:** `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:139`
```java
buf.putShort((short) keyBytes.length);
```
Key length encoded as `short` (max 32,767 bytes for signed, 65,535 for unsigned). While config keys shouldn't be this long, the unsigned interpretation via `Short.toUnsignedInt` at line 173 limits to 65,535 bytes which is adequate but should be documented.

---

## 4. Concurrency Concerns

### CC-1: ReadResult.found() Allocates on Read Path (SEVERITY: HIGH)
See AV-1. The read path (`LocalConfigStore.get()`, `VersionedConfigStore.get()`) allocates a new `ReadResult` record per successful lookup. Under high concurrency (millions of reads/sec), this creates significant GC pressure.

### CC-2: HashMap in RaftNode for Leader State (SEVERITY: LOW)
**File:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:101-103`
```java
this.peerActivity = new HashMap<>();
this.nextIndex = new HashMap<>();
this.matchIndex = new HashMap<>();
```
`HashMap` is not forbidden here (single-threaded I/O context, not on read path), but with small peer sets (3-7 nodes), a flat array indexed by peer ordinal would be more cache-friendly and avoid autoboxing of `Long` values.

**Fix direction:** Use `NodeId[] peers` + `long[] nextIndex` + `long[] matchIndex` arrays indexed by peer ordinal.

### CC-3: CopyOnWriteArrayList for Listeners (SEVERITY: LOW)
**File:** `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:43`
```java
private final List<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();
```
CopyOnWriteArrayList is correct for reads >> writes, but listener notification at line 276 iterates on the apply thread. If a listener blocks or throws, it blocks apply. Should add timeout/catch.

---

## 5. Missing Components (per PROMPT.md §3 Output 5)

| Component | Status | Notes |
|---|---|---|
| WAL (Write-Ahead Log) | **MISSING** | Critical for durability |
| Reconfiguration (Joint Consensus) | **MISSING** | No membership change protocol |
| JMH Benchmarks | **MISSING** | No performance verification |
| Property Tests | **PARTIAL** | Simulation tests exist, no formal invariant tests |
| mTLS / Auth | **MISSING** | Transport layer has no security |
| Structured Logging | **MISSING** | No logging framework integrated |
| Audit Logging | **MISSING** | No write operation audit trail |

---

## 6. Findings Summary

| ID | Severity | Category | File | Line | Description |
|---|---|---|---|---|---|
| AV-1 | HIGH | Architecture | ReadResult.java | 29 | Allocation on read hot path |
| AV-2 | HIGH | Architecture | VersionedConfigStore.java | 75,93,118 | System.currentTimeMillis() bypasses Clock |
| AV-2 | HIGH | Architecture | LocalConfigStore.java | 153 | System.currentTimeMillis() bypasses Clock |
| AV-2 | HIGH | Architecture | ConfigStateMachine.java | 170 | System.currentTimeMillis() bypasses Clock |
| AV-3 | MEDIUM | Architecture | ConfigStateMachine.java | 294-303 | VarHandle reflection for snapshot restore |
| AV-4 | LOW | Architecture | RaftNode.java | 130 | Object parameter, megamorphic dispatch |
| SB-1 | HIGH | Scalability | PlumtreeNode.java | 78 | Unbounded receivedMessages set |
| SB-2 | LOW | Scalability | RaftLog.java | 49 | ArrayList without pre-sizing |
| SB-3 | MEDIUM | Scalability | RaftLog.java | 119 | List.copyOf() on replication path |
| SB-4 | MEDIUM | Scalability | VersionedConfigStore.java | 183 | O(N) prefix scan |
| IA-1 | CRITICAL | Correctness | RaftLog.java | entire | No WAL — data loss on crash |
| IA-2 | CRITICAL | Correctness | RaftNode.java | 95-96 | currentTerm/votedFor not persisted |
| IA-3 | LOW | Correctness | ConfigStateMachine.java | 139 | 16-bit key length in snapshot format |
| CC-1 | HIGH | Concurrency | ReadResult.java | 29 | See AV-1 |
| CC-2 | LOW | Concurrency | RaftNode.java | 101-103 | HashMap for small peer sets |
| CC-3 | LOW | Concurrency | ConfigStateMachine.java | 43 | Listener blocking on apply thread |

**CRITICAL findings (2):** Both relate to missing persistence. The Raft protocol requires durable storage of committed log entries, currentTerm, and votedFor. Without these, the system cannot survive process restarts and violates fundamental Raft safety properties.

**HIGH findings (4):** Read path allocation (AV-1/CC-1), Clock abstraction bypass (AV-2), unbounded dedup set (SB-1). All are fixable without architectural changes.

**MEDIUM findings (3):** VarHandle hack (AV-3), List.copyOf on replication path (SB-3), O(N) prefix scan (SB-4). Design improvements, not correctness issues.

**LOW findings (4):** Minor efficiency improvements and API cleanliness.

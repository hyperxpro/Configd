# Java Systems Audit: Hot-Path Performance Analysis

**Auditor:** java-systems-auditor
**Date:** 2026-04-11
**Scope:** Locks, allocation, reflection, megamorphic calls, GC pressure on read/write/fan-out paths
**Verdict:** 2 Blockers, 7 Major, 12 Minor findings

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Read Path Audit](#read-path-audit)
3. [Write Path Audit](#write-path-audit)
4. [Fan-out Path Audit](#fan-out-path-audit)
5. [Cross-cutting Concerns](#cross-cutting-concerns)
6. [Findings Summary Table](#findings-summary-table)

---

## Executive Summary

The Configd read path is *well-designed* for performance: the core
`LocalConfigStore.get()` -> `HamtMap.get()` chain uses a volatile pointer
load + pointer-chasing through a sealed HAMT hierarchy with zero locks
and zero allocation on miss. However, the `ThreadLocal.get()` call in
`ReadResult.foundReusable()` introduces a hidden allocation concern on
virtual threads and defeats the stated "zero allocation" goal on the
common cache-hit path.

The write path correctly uses single-writer semantics throughout
(RaftNode, RaftLog, VersionedConfigStore). The main concerns are
per-proposal `HashMap` autoboxing and `HashSet` allocation in
`maybeAdvanceCommitIndex()`.

The fan-out path has two blockers: the `FanOutBuffer` is not
thread-safe despite claiming single-writer/multi-reader semantics,
and `PlumtreeNode.drainOutbox()` copies the entire outbox into a
new `LinkedList` on every drain.

---

## Read Path Audit

### File 1: LocalConfigStore.java (edge read entry point)

**Path:** `configd-edge-cache/src/main/java/io/configd/edge/LocalConfigStore.java`

| Check | Result | Details |
|-------|--------|---------|
| `synchronized` / Lock | NONE | Volatile-only RCU pattern. Correct. |
| Object allocation | 1 per hit | `ReadResult.foundReusable()` at line 106 calls `ThreadLocal.get()` (see RP-1) |
| Reflection / dynamic proxy | NONE | |
| Autoboxing | NONE | |
| String.format / concat | NONE on read path | Error path at line 171 uses `+` concat but only fires on version mismatch (write path) |
| HashMap usage | NONE | |
| Logging at INFO+ | NONE | |
| Method size (get) | ~15 bytecodes | Will inline. Well under 325 bytes. |
| Type hierarchy | `final class` | Devirtualized at all call sites. |

**RP-1 [MAJOR] ThreadLocal.get() on hot read path (line 106, 133)**
`ReadResult.foundReusable()` calls `REUSABLE.get()` which:
- On platform threads: ~15ns overhead, one `ThreadLocalMap` lookup, typically zero-alloc after warmup.
- On virtual threads (Project Loom): `ThreadLocal.get()` pins to the carrier thread's map, BUT if virtual threads are used for read serving (common in modern Java 21+ servers), ThreadLocals are inherited/copied on each virtual thread creation, causing **one `ReadResult` allocation per virtual thread**. This defeats the zero-allocation claim.
- Even on platform threads, the first call from each thread allocates a `ReadResult` via the `withInitial` supplier.

**Recommendation:** Replace with a method that returns a freshly-stack-allocated value object (if using Java value types preview) or simply accept the allocation since `ReadResult` is 24 bytes (tiny, nursery-collected). Alternatively, return the raw `VersionedValue` and let callers destructure.

**RP-2 [MINOR] Objects.requireNonNull per read (lines 100, 122-123)**
Two null checks per `get()` call. On modern JVMs these compile to a single
`test` + conditional trap. Negligible, but noted for completeness. The JIT
will likely intrinsify these away.

---

### File 2: EdgeConfigClient.java

**Path:** `configd-edge-cache/src/main/java/io/configd/edge/EdgeConfigClient.java`

| Check | Result |
|-------|--------|
| `synchronized` / Lock | NONE |
| Object allocation | NONE (delegates to store) |
| Reflection | NONE |
| Autoboxing | NONE |
| Method size | Trivial delegation, will inline |
| Type hierarchy | `final class` |

Clean pass-through. `get()` at line 67 is a single-hop delegation. The JIT
should inline this to zero overhead.

**RP-3 [MINOR] `metrics()` allocates per call (line 194-202)**
`new EdgeMetrics(...)` is allocated on every call. Not on the read hot path
per se, but if called from a per-request metrics interceptor, this adds
GC pressure. Consider a flyweight or rate-limiting metrics collection.

---

### File 3: VersionedConfigStore.java (control plane reads)

**Path:** `configd-config-store/src/main/java/io/configd/store/VersionedConfigStore.java`

| Check | Result |
|-------|--------|
| `synchronized` / Lock | NONE |
| Object allocation (get) | Same as LocalConfigStore (ThreadLocal `ReadResult`) |
| Reflection | NONE |
| HashMap usage | `LinkedHashMap` at line 226 in `getPrefix()` -- but `getPrefix()` is not hot path |
| Logging | NONE |

**RP-4 [MAJOR] `getPrefix()` allocates O(N) (lines 223-233)**
`getPrefix()` creates a `LinkedHashMap` and allocates a new `ReadResult.found()`
(not the reusable one) for *every* matching key. This is O(N) allocation where
N is the key count. If ever used on a hot path (e.g., prefix-based config
loading at startup per-request), this will cause major GC pressure.

Furthermore, `ReadResult.found()` at line 229 allocates a new `ReadResult`
for each entry, unlike the hot-path `foundReusable()`. This is correct
(results stored in a map), but callers must be aware of the cost.

**Recommendation:** Document that `getPrefix()` is O(N) scan + O(matches)
allocation. Consider adding a `forEachPrefix(String, BiConsumer)` that
avoids intermediate allocation.

---

### File 4: HamtMap.java (core data structure)

**Path:** `configd-config-store/src/main/java/io/configd/store/HamtMap.java`

| Check | Result |
|-------|--------|
| `synchronized` / Lock | NONE |
| Allocation in `get()` | ZERO | Uses array indexing only, no iterator, no autoboxing |
| Reflection | NONE |
| Autoboxing | NONE on read path. Write path uses `SizeChange` carrier (mutable, no boxing) |
| Type hierarchy | `sealed interface Node permits BitmapIndexedNode, ArrayNode, CollisionNode` (line 161-162). **All three are `final`**. JIT can devirtualize the `get()` dispatch. |

This is the crown jewel. Zero-allocation read path confirmed:
- `HamtMap.get()` (line 87-93): null check, one `spread()` call (pure arithmetic), delegate to `root.get()`.
- `BitmapIndexedNode.get()` (lines 209-222): bit operations + array indexing. No allocation.
- `ArrayNode.get()` (lines 421-424): direct array index. No allocation.
- `CollisionNode.get()` (lines 534-536): linear scan of flat array. No allocation.

**RP-5 [MINOR] `SizeChange` carrier allocation on write (line 109, 128)**
`var sc = new SizeChange()` is allocated per `put()`/`remove()`. This is 16 bytes
(object header + int field), nursery-allocated, and only on the write path.
Consider a `static final ThreadLocal<SizeChange>` to eliminate this, but it
is minor since writes are infrequent relative to reads.

---

### File 5: ReadResult.java

**Path:** `configd-config-store/src/main/java/io/configd/store/ReadResult.java`

| Check | Result |
|-------|--------|
| `synchronized` / Lock | NONE |
| Allocation | `NOT_FOUND` is a singleton (line 27). `foundReusable()` reuses a ThreadLocal (line 69-75). `found()` allocates (line 53). |
| Reflection | NONE |
| Type hierarchy | `final class` |

**RP-6 [MAJOR] Mutable ThreadLocal flyweight is a correctness hazard (lines 34-75)**
`ReadResult` fields are *mutable* (`private byte[] value; private long version; private boolean found;`).
The `foundReusable()` method mutates a ThreadLocal instance in place. This creates
a subtle correctness hazard:

```java
ReadResult r1 = store.get("key1");  // r1 = TL instance, found=true
ReadResult r2 = store.get("key2");  // r2 = SAME TL instance, now key2's data
// r1.value() now returns key2's value!
```

If any caller stores the reference (e.g., in a local variable across a second
`get()` call), they silently read stale/wrong data. The Javadoc warns about this,
but it is an extremely fragile API. Any future refactoring that introduces an
intermediate `get()` call will silently corrupt data.

**Recommendation:** Either:
1. Accept the 24-byte allocation per hit (tiny, nursery-collected, <5ns on G1/ZGC).
2. Use a Java value type (Valhalla preview) to get stack allocation.
3. Return the `VersionedValue` directly and let callers extract fields.

---

### File 6: ConfigSnapshot.java

**Path:** `configd-config-store/src/main/java/io/configd/store/ConfigSnapshot.java`

| Check | Result |
|-------|--------|
| `synchronized` / Lock | NONE |
| Allocation | `record` -- immutable, no allocation on access |
| Reflection | NONE |
| Type hierarchy | `record` (implicitly `final`) |

Clean. The `get()` method at line 41 delegates directly to `HamtMap.get()` and
returns the internal byte array without copying (`valueUnsafe()`).

---

### File 7: VersionCursor.java

**Path:** `configd-edge-cache/src/main/java/io/configd/edge/VersionCursor.java`

| Check | Result |
|-------|--------|
| `synchronized` / Lock | NONE |
| Allocation | `record` with two `long` fields -- 16 bytes on heap. `INITIAL` is a singleton. |
| Reflection | NONE |
| Type hierarchy | `record` (implicitly `final`) |

Clean. The `VersionCursor` passed to `get(key, cursor)` is caller-owned and
reusable. No per-request allocation if callers cache their cursor.

---

## Write Path Audit

### File 1: RaftNode.java

**Path:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java`

| Check | Result |
|-------|--------|
| `synchronized` / Lock | NONE (single-threaded by design, ADR-0009) |
| Reflection | NONE |
| Megamorphic calls | `handleMessage()` at line 211 uses pattern matching on sealed `RaftMessage`. JIT devirtualizes. |

**WP-1 [MAJOR] `HashMap<NodeId, Long>` autoboxing on every AppendEntries response (lines 624-641)**
`matchIndex.put(resp.from(), newMatchIndex)` boxes `long -> Long` on every
successful AppendEntries response. With 3-5 peers and ~1000 commits/sec,
this is ~5000 `Long` allocations/sec. Similarly, `nextIndex.put()` boxes
another `Long`.

`inflightCount.merge(resp.from(), -1, Integer::sum)` at line 624 boxes
both the `-1` literal and the merge result on every response.

**Recommendation:** Use a primitive `long[]` indexed by peer ordinal, or
use Eclipse Collections `ObjectLongHashMap`.

**WP-2 [MAJOR] `HashSet` allocation in `maybeAdvanceCommitIndex()` (line 1012)**
`new java.util.HashSet<NodeId>()` is allocated on *every* call to
`maybeAdvanceCommitIndex()`, which runs after every successful
`AppendEntriesResponse`. For a 5-node cluster, this allocates a `HashSet`
with initial capacity 16 + `NodeId` entries, multiple times per commit.

**Recommendation:** Pre-allocate a reusable set at leader initialization
and clear it per call, or count replicas with a simple `int` counter
instead of building a set.

**WP-3 [MINOR] `LogEntry` allocation per proposal (line 241)**
`new LogEntry(newIndex, currentTerm, command)` at line 241 is unavoidable --
each proposal needs a log entry. The `LogEntry` record's compact constructor
creates a defensive copy of `command` if null (line 24 of LogEntry.java),
but non-null commands are stored by reference. Acceptable.

**WP-4 [MINOR] `serializeConfigChange()` allocates 1KB ByteBuffer (line 440)**
`ByteBuffer.allocate(1024)` is a fixed 1KB allocation regardless of actual
config size. This only fires on config changes (rare), so it is minor.

**WP-5 [MINOR] String concatenation in invariant checker messages (throughout)**
Lines like `"Sequence " + seq + " not > previous " + prevSeq` at line 147
of ConfigStateMachine cause string allocation even when the invariant holds
(the message is eagerly constructed). However, since the checker interface
takes a `String` parameter, this cannot be lazily evaluated without changing
the API to accept a `Supplier<String>`.

**Recommendation:** Change `InvariantChecker.check()` signature to accept
`Supplier<String>` for the message parameter, or use a two-arg overload
that only constructs the message on violation.

---

### File 2: RaftLog.java

**Path:** `configd-consensus-core/src/main/java/io/configd/raft/RaftLog.java`

| Check | Result |
|-------|--------|
| `synchronized` / Lock | NONE (single-threaded) |
| Allocation | `ArrayList` with initial capacity 1024 (line 49). Amortized append. |

**WP-6 [MINOR] `entriesBatch()` allocates `List.copyOf()` per batch (line 149)**
`List.copyOf(entries.subList(fromOffset, fromOffset + count))` allocates a
new array + list wrapper on every batch send. With pipelining, this fires
multiple times per heartbeat interval. The copy is necessary for safety
(the subList is a view into the mutable ArrayList), but consider caching
batch views.

**WP-7 [MINOR] `entriesFrom()` returns `Collections.unmodifiableList(entries.subList(...))` (line 119)**
`unmodifiableList` wraps without copying -- the view is backed by the live
ArrayList. If the log is truncated while a caller holds this view, the
behavior is undefined. Since access is single-threaded, this is safe in
practice, but fragile.

---

### File 3: ConfigStateMachine.java

**Path:** `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java`

| Check | Result |
|-------|--------|
| `synchronized` / Lock | NONE |
| Allocation | Per-apply: `CommandCodec.decode()` allocates a `DecodedCommand` record. |
| CopyOnWriteArrayList | `listeners` at line 45 -- COW iteration for notification. |

**WP-8 [MINOR] `List.of(new ConfigMutation.Put(...))` per apply (lines 159, 169)**
Each non-batch apply creates a singleton list via `List.of()`. This is a
tiny allocation (1-element unmodifiable list), but fires on every committed
entry.

**WP-9 [MINOR] String allocation in `CommandCodec.decode()` (line 227 of CommandCodec.java)**
`new String(keyBytes, StandardCharsets.UTF_8)` allocates a new `String` on
every decoded command. Unavoidable with the current binary format. Consider
interning frequently-used keys if key cardinality is bounded.

---

### File 4: MultiRaftDriver.java

**Path:** `configd-replication-engine/src/main/java/io/configd/replication/MultiRaftDriver.java`

| Check | Result |
|-------|--------|
| `synchronized` / Lock | NONE |
| HashMap | `HashMap<Integer, RaftNode>` at line 40. `Integer` key autoboxes on every `get()/put()`. |

**WP-10 [MINOR] `Integer` autoboxing on `routeMessage()` and `propose()` (lines 117, 133)**
`groups.get(groupId)` autoboxes the `int groupId` to `Integer` on every
message route and proposal. With multi-raft, every incoming message triggers
this. Use an `IntObjectHashMap` or array-based lookup if group IDs are dense.

---

### File 5: ReplicationPipeline.java

**Path:** `configd-replication-engine/src/main/java/io/configd/replication/ReplicationPipeline.java`

| Check | Result |
|-------|--------|
| `synchronized` / Lock | NONE |
| Allocation | `List.copyOf(pending)` in `flush()` (line 138) |

**WP-11 [MINOR] `List.copyOf()` in `flush()` (line 138)**
Each flush copies the pending list. This is O(batch_size) allocation.
Acceptable since batches are bounded by `maxBatchSize` and flush frequency
is controlled by the 200us batching window.

---

### File 6: FlowController.java

**Path:** `configd-replication-engine/src/main/java/io/configd/replication/FlowController.java`

| Check | Result |
|-------|--------|
| `synchronized` / Lock | NONE |
| HashMap<NodeId, Integer> | Autoboxing on every `acquireCredits()`/`releaseCredits()` |

**WP-12 [MINOR] `Integer` autoboxing in `acquireCredits()` / `releaseCredits()` (lines 70-76, 95-99)**
`credits.get(follower)` returns boxed `Integer`, then `credits.put(follower, available - granted)`
re-boxes. This happens on every entry sent to a follower. For 3-5 followers at
1000 entries/sec, this is ~5000 box/unbox cycles per second. Use an
`ObjectIntHashMap` from Eclipse Collections.

---

## Fan-out Path Audit

### File 1: PlumtreeNode.java

**Path:** `configd-distribution-service/src/main/java/io/configd/distribution/PlumtreeNode.java`

**FO-1 [MAJOR] `drainOutbox()` copies entire queue into new LinkedList (line 232-235)**
```java
public Queue<OutboundMessage> drainOutbox() {
    Queue<OutboundMessage> result = new LinkedList<>(outbox);
    outbox.clear();
    return result;
}
```
Every drain allocates a new `LinkedList` plus a `Node` object per message
in the outbox. For N eager peers and M messages per tick, this is O(N*M)
`LinkedList.Node` allocations per tick. `LinkedList` is also cache-hostile
due to pointer chasing.

**Recommendation:** Swap the internal `outbox` reference with a fresh empty
queue and return the old one. Or better, use an `ArrayDeque` and swap:
```java
public Queue<OutboundMessage> drainOutbox() {
    Queue<OutboundMessage> drained = outbox;
    outbox = new ArrayDeque<>();
    return drained;
}
```
This reduces allocation to one `ArrayDeque` per drain instead of N nodes.

**FO-2 [MINOR] `OutboundMessage` record allocation per broadcast (lines 124-128)**
Each `broadcast()` creates one `EagerPush` record per eager peer and one
`IHave` record per lazy peer. These are small records (3-4 fields) and
unavoidable given the outbox design. But with 10 eager peers and 100
messages/sec, this is ~1000 record allocations/sec.

**FO-3 [MINOR] `tick()` allocates `HashSet<MessageId>` per tick (line 209)**
`var expired = new HashSet<MessageId>()` is allocated on every tick, even
when no notifications have expired. Pre-allocate and reuse, or check
`lazyNotifications.isEmpty()` first.

**FO-4 [MINOR] `eagerPeers()` and `lazyPeers()` allocate `Set.copyOf()` (lines 240, 244)**
Every call to these accessors copies the set. Only a concern if called from
hot monitoring paths.

---

### File 2: FanOutBuffer.java

**Path:** `configd-distribution-service/src/main/java/io/configd/distribution/FanOutBuffer.java`

**FO-5 [BLOCKER] Data race between writer and readers (lines 25-59)**
The class claims "single-writer and multiple readers" but the implementation
is NOT thread-safe:

1. `entries` is a plain `ArrayList` (not synchronized, not concurrent).
2. The writer calls `entries.removeFirst()` (line 57) and `entries.add()` (line 58)
   which mutate the internal array.
3. Readers call `entries.get(i)` (line 74) based on the volatile `size` read.
4. Between `removeFirst()` and `add()`, a concurrent reader can observe
   a shifted array where `entries.get(i)` returns the *wrong* delta or
   throws `IndexOutOfBoundsException`.

Even without `removeFirst()`, a plain `ArrayList.add()` may trigger internal
array resize (`grow()`), which copies to a new array. A concurrent reader
on the old internal array would read stale data (though not crash, since
the old array remains valid).

The volatile `size` field provides *visibility* of the new size but does NOT
provide safe publication of the array mutations.

**Recommendation:** Replace with a bounded lock-free ring buffer using an
`AtomicReferenceArray`, or use `CopyOnWriteArrayList` (acceptable since
writes are infrequent relative to reads), or use a proper SPSC queue.

**FO-6 [MINOR] `deltasSince()` allocates `ArrayList` per call (line 71)**
Each reader call allocates a new `ArrayList` and fills it with matching
deltas. For many subscribers calling simultaneously, this creates
allocation pressure. Consider returning a read-only view/slice.

---

### File 3: WatchCoalescer.java

**Path:** `configd-distribution-service/src/main/java/io/configd/distribution/WatchCoalescer.java`

| Check | Result |
|-------|--------|
| `synchronized` / Lock | NONE (single-threaded) |
| Allocation | `List.copyOf(pending)` in `flush()` (line 130), `ArrayList` at construction |

**FO-7 [MINOR] `List.copyOf(pending)` in `flush()` (line 130)**
Each flush copies the pending list. Bounded by `maxBatch` (default 64).
Acceptable.

---

### File 4: WatchService.java

**Path:** `configd-distribution-service/src/main/java/io/configd/distribution/WatchService.java`

**FO-8 [BLOCKER] Per-dispatch `ArrayList` copy of all watches (line 266)**
```java
List<Map.Entry<Long, Watch>> entries = new ArrayList<>(watches.entrySet());
```
On *every* dispatch event, the entire watch map's entry set is copied
into a new `ArrayList`. With 1000 watchers and events at 100/sec, this
is 100,000 `Map.Entry` copies per second, plus 100 `ArrayList` allocations.

The copy exists to avoid `ConcurrentModificationException` when
`watches.put()` is called at line 280 (cursor advancement), but since
the class is documented as single-threaded, the map could be iterated
directly if the cursor advancement were deferred.

**Recommendation:** Use a two-pass approach:
1. First pass: iterate `watches.values()` directly, collect cursor updates.
2. Second pass: apply cursor updates.
Or use a `watches` array that is rebuilt only when watches are added/removed.

**FO-9 [MAJOR] Watch record allocation per cursor advance (line 280)**
```java
watches.put(entry.getKey(), watch.advanceCursor(event.version()));
```
`advanceCursor()` creates a new `Watch` record (4 fields including a
`WatchListener`) for *every* watcher on *every* event. With 1000 watchers,
this is 1000 record allocations per event. Records are ~40 bytes each.

**Recommendation:** Make `Watch` a mutable class with a `long cursor`
field instead of an immutable record, eliminating per-dispatch allocation.

**FO-10 [MINOR] `filterByPrefix()` allocates `ArrayList` per watcher (lines 325-336)**
For each watcher that has a non-empty prefix, a new `ArrayList` is
allocated for the filtered mutations. With 1000 watchers and 10 mutations
per event, this is potentially 1000 `ArrayList` allocations per dispatch.

---

### File 5: SlowConsumerPolicy.java

**Path:** `configd-distribution-service/src/main/java/io/configd/distribution/SlowConsumerPolicy.java`

| Check | Result |
|-------|--------|
| `synchronized` / Lock | NONE |
| Allocation | `ConsumerTracker` allocated per `register()` (infrequent) |

**FO-11 [MINOR] `consumersInState()` allocates `HashSet` (line 177)**
Called for monitoring, not on the hot path. Acceptable.

---

## Cross-cutting Concerns

### HybridClock.java

**Path:** `configd-common/src/main/java/io/configd/common/HybridClock.java`

**CC-1 [MAJOR] `synchronized` on `now()`, `receive()`, `current()` (lines 26, 41, 58)**
All three methods are `synchronized`. While the comment says "HLC operations
are infrequent (on writes, not reads)", the `now()` method is called from
the write path (`clock.currentTimeMillis()` in `VersionedConfigStore.put()`).
If HybridClock is used as the Clock implementation (rather than `SystemClock`),
the `synchronized` block on `now()` introduces a lock on the Raft apply thread.

More critically, `now()` allocates a new `HybridTimestamp` on every call
(line 34: `new HybridTimestamp(lastWallTime, lastLogical)`). Each timestamp
is 24 bytes (object header + long + int + padding).

However, examining the code, the `Clock` interface used throughout the
codebase (`Clock.system()`) returns a `SystemClock` singleton, NOT a
`HybridClock`. So `HybridClock.now()` is only called when explicitly
used. The standard read/write paths use `Clock.currentTimeMillis()` which
goes through `SystemClock` (no lock, no allocation).

**Net assessment:** The `synchronized` in HybridClock is NOT on the
standard hot path. But if anyone wires `HybridClock` as the `Clock`
implementation, the lock would be on the write hot path. Document this.

---

### HybridTimestamp.java

**Path:** `configd-common/src/main/java/io/configd/common/HybridTimestamp.java`

| Check | Result |
|-------|--------|
| Value type candidate | YES -- 12 bytes payload (long + int), immutable, no identity. |
| Allocation | `new HybridTimestamp(...)` on every clock tick. |

**CC-2 [MINOR] Not a Java value type (line 10)**
`HybridTimestamp` is a `final class`, not a `value class`. When Java value
types (Valhalla) ship, this should be migrated to avoid heap allocation.
Currently, since it is only used on the write path (not the read hot path),
the allocation is acceptable.

The `packed()` method (line 28) provides a zero-allocation alternative for
serialization contexts -- good design.

---

### MetricsRegistry.java

**Path:** `configd-observability/src/main/java/io/configd/observability/MetricsRegistry.java`

| Check | Result |
|-------|--------|
| Lock-free counters | YES -- `LongAdder` (line 210). |
| Lock-free histograms | MOSTLY -- `AtomicLong` cursor + volatile min/max. |
| ConcurrentHashMap | YES -- for registry lookups (lines 41-43). |

**CC-3 [MINOR] Histogram `compareAndSetMin/Max` uses `synchronized` (lines 309-323)**
The min/max update uses a `synchronized` CAS pattern instead of a
`VarHandle` CAS. This acquires a monitor lock on new-min/new-max
discovery. After warmup (first ~100 values), new min/max are rare,
so contention is minimal. But during the initial warmup burst, every
value is a new min AND a new max, causing 2 lock acquisitions per
`record()` call.

**Recommendation:** Use `VarHandle.compareAndSet()` for truly lock-free
min/max updates:
```java
private static final VarHandle MIN_HANDLE = ...;
// In updateMin:
while (!MIN_HANDLE.compareAndSet(this, current, value)) { ... }
```

**CC-4 [MINOR] `snapshot()` iterates all metrics under ConcurrentHashMap (line 188-200)**
`MetricsRegistry.snapshot()` iterates all counters, gauges, and histograms,
allocating a `MetricValue` record per metric and a `LinkedHashMap`. This is
O(metrics) and fires on monitoring endpoints, not on the data hot path.
Acceptable.

---

## Findings Summary Table

| ID | Severity | Component | File:Line | Description |
|----|----------|-----------|-----------|-------------|
| FO-5 | **BLOCKER** | FanOutBuffer | FanOutBuffer.java:50-59 | Data race: ArrayList mutations visible to concurrent readers without safe publication. `removeFirst()` + `add()` can cause readers to see shifted/corrupt data. |
| FO-8 | **BLOCKER** | WatchService | WatchService.java:266 | Per-dispatch `ArrayList` copy of entire watches map entry set. With 1000 watchers at 100 events/sec = 100K entry copies/sec. Also modifies map during conceptual iteration via cursor advance. |
| RP-1 | **MAJOR** | ReadResult | ReadResult.java:69-75 | `ThreadLocal.get()` on hot read path. ~15ns overhead per read; defeats zero-allocation on virtual threads; mutable flyweight is a correctness hazard. |
| RP-4 | **MAJOR** | VersionedConfigStore | VersionedConfigStore.java:223-233 | `getPrefix()` O(N) scan + O(matches) allocation. LinkedHashMap + ReadResult per match. |
| RP-6 | **MAJOR** | ReadResult | ReadResult.java:34-75 | Mutable ThreadLocal flyweight aliasing hazard. Two consecutive `get()` calls silently overwrite first result. |
| WP-1 | **MAJOR** | RaftNode | RaftNode.java:624-641 | `HashMap<NodeId, Long>` autoboxing: `Long` + `Integer` boxing on every AppendEntries response (~5000 boxes/sec at 1000 commits/sec). |
| WP-2 | **MAJOR** | RaftNode | RaftNode.java:1012 | `HashSet<NodeId>` allocation in `maybeAdvanceCommitIndex()` per commit. |
| FO-1 | **MAJOR** | PlumtreeNode | PlumtreeNode.java:232-235 | `drainOutbox()` copies entire queue into new `LinkedList`. O(N) `LinkedList.Node` allocations per drain. Cache-hostile. |
| FO-9 | **MAJOR** | WatchService | WatchService.java:280 | `Watch` record re-created for cursor advance on every watcher per event. 1000 watchers = 1000 records/event. |
| CC-1 | **MAJOR** | HybridClock | HybridClock.java:26 | `synchronized` on `now()`. Not currently on hot path, but a wiring mistake would put a monitor lock on the Raft apply thread. |
| RP-2 | MINOR | LocalConfigStore | LocalConfigStore.java:100 | `Objects.requireNonNull` per read. Negligible after JIT intrinsification. |
| RP-3 | MINOR | EdgeConfigClient | EdgeConfigClient.java:194-202 | `EdgeMetrics` allocation per `metrics()` call. |
| RP-5 | MINOR | HamtMap | HamtMap.java:109 | `SizeChange` carrier allocation per `put()`/`remove()`. 16 bytes, write path only. |
| WP-3 | MINOR | RaftNode | RaftNode.java:241 | `LogEntry` allocation per proposal. Unavoidable. |
| WP-4 | MINOR | RaftNode | RaftNode.java:440 | 1KB `ByteBuffer.allocate` for config change serialization. Rare path. |
| WP-5 | MINOR | ConfigStateMachine | ConfigStateMachine.java:147 | String concatenation in invariant messages even when invariant holds. |
| WP-6 | MINOR | RaftLog | RaftLog.java:149 | `List.copyOf()` per batch in `entriesBatch()`. |
| WP-7 | MINOR | RaftLog | RaftLog.java:119 | `unmodifiableList(subList(...))` view fragile under truncation. |
| WP-8 | MINOR | ConfigStateMachine | ConfigStateMachine.java:159 | `List.of()` singleton list per non-batch apply. |
| WP-9 | MINOR | CommandCodec | CommandCodec.java:227 | `new String(keyBytes, UTF_8)` per decoded command. Unavoidable. |
| WP-10 | MINOR | MultiRaftDriver | MultiRaftDriver.java:117 | `Integer` autoboxing on `groups.get(groupId)` per message route. |
| WP-11 | MINOR | ReplicationPipeline | ReplicationPipeline.java:138 | `List.copyOf()` per flush. Bounded by batch size. |
| WP-12 | MINOR | FlowController | FlowController.java:70-76 | `Integer` autoboxing in `credits.get()/put()` per entry sent. |
| FO-2 | MINOR | PlumtreeNode | PlumtreeNode.java:124-128 | `OutboundMessage` record allocation per peer per broadcast. |
| FO-3 | MINOR | PlumtreeNode | PlumtreeNode.java:209 | `HashSet` allocation per tick even when empty. |
| FO-4 | MINOR | PlumtreeNode | PlumtreeNode.java:240 | `Set.copyOf()` in accessor methods. |
| FO-6 | MINOR | FanOutBuffer | FanOutBuffer.java:71 | `ArrayList` allocation per `deltasSince()` call. |
| FO-7 | MINOR | WatchCoalescer | WatchCoalescer.java:130 | `List.copyOf()` per flush. Bounded by maxBatch. |
| FO-10 | MINOR | WatchService | WatchService.java:325-336 | `ArrayList` allocation per watcher in `filterByPrefix()`. |
| FO-11 | MINOR | SlowConsumerPolicy | SlowConsumerPolicy.java:177 | `HashSet` allocation in `consumersInState()`. Monitoring only. |
| CC-2 | MINOR | HybridTimestamp | HybridTimestamp.java:10 | Not a value type. Future Valhalla migration candidate. |
| CC-3 | MINOR | MetricsRegistry | MetricsRegistry.java:309-323 | `synchronized` CAS for histogram min/max. Lock-free alternative via VarHandle recommended. |
| CC-4 | MINOR | MetricsRegistry | MetricsRegistry.java:188-200 | O(metrics) allocation in `snapshot()`. Monitoring path only. |

---

## Priority Actions

### Must-fix before production (Blockers)

1. **FO-5: FanOutBuffer data race** -- Replace `ArrayList` with a thread-safe
   structure (CopyOnWriteArrayList, lock-free ring buffer, or add explicit
   synchronization). Current code will cause silent data corruption under
   concurrent read/write access.

2. **FO-8: WatchService per-dispatch copy** -- Restructure dispatch loop to
   avoid copying the entire watches map. Use a direct iteration + deferred
   cursor update, or maintain watches as a pre-sorted array.

### Should-fix before production (Major)

3. **RP-1 + RP-6: ReadResult ThreadLocal flyweight** -- The correctness hazard
   outweighs the allocation savings. Remove the mutable flyweight and accept
   the 24-byte-per-hit allocation, or return `VersionedValue` directly.

4. **WP-1 + WP-2: RaftNode HashMap boxing** -- Replace `HashMap<NodeId, Long>`
   with primitive maps or arrays. Replace `HashSet` in `maybeAdvanceCommitIndex()`
   with an int counter.

5. **FO-1: PlumtreeNode drainOutbox** -- Swap `LinkedList` for `ArrayDeque`
   and use reference swap instead of copy.

6. **FO-9: Watch record per cursor advance** -- Make `Watch` mutable or
   track cursors in a separate `long[]`.

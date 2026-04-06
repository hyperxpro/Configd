# Audit Report: Concurrency Correctness (Phase 3)

**System:** Configd Distributed Configuration System  
**Date:** 2026-04-13  
**Auditor:** Production Readiness Review  
**Status:** PASS (all checks, 1 fixed finding)

---

## 1. Volatile Snapshot References

### VersionedConfigStore.currentSnapshot

| Check | Result |
|-------|--------|
| Field declared volatile | **PASS** |

**Evidence:** `VersionedConfigStore.java:42` -- `private volatile ConfigSnapshot currentSnapshot`

**Correctness rationale:** `ConfigSnapshot` (`ConfigSnapshot.java:15`) is an immutable `record` containing a `HamtMap` (persistent/immutable data structure) and two primitive fields (`version`, `timestamp`). The volatile write by the single writer thread (Raft apply thread) establishes a happens-before relationship with volatile reads by concurrent reader threads. Since the published object is deeply immutable, no further synchronization is needed. Readers see a consistent snapshot on every volatile read (`VersionedConfigStore.java:191`).

---

### LocalConfigStore.currentSnapshot

| Check | Result |
|-------|--------|
| Field declared volatile | **PASS** |

**Evidence:** `LocalConfigStore.java:47` -- `private volatile ConfigSnapshot currentSnapshot`

**Correctness rationale:** Identical pattern to `VersionedConfigStore`. The edge-local store follows the Read-Copy-Update (RCU) pattern (ADR-0005). The single DeltaApplier thread writes new snapshots; concurrent reader threads load via volatile read at `LocalConfigStore.java:101`. `ConfigSnapshot` and `HamtMap` are immutable, so the volatile store-load is sufficient for safe publication.

---

## 2. FanOutBuffer head/tail Volatiles

| Check | Result |
|-------|--------|
| head and tail declared volatile | **PASS** |
| Ring buffer uses AtomicReferenceArray | **PASS** |

**Evidence:**
- `FanOutBuffer.java:22-23` -- `private volatile long head;` and `private volatile long tail;`
- `FanOutBuffer.java:20` -- `private final AtomicReferenceArray<ConfigDelta> ring;`

**Correctness rationale:** The single-writer thread appends via `AtomicReferenceArray.set()` (volatile write semantics) at `FanOutBuffer.java:36`, then publishes the new head via volatile store at line 37. Readers load `tail` then `head` (volatile reads, lines 44-45), then read ring slots via `AtomicReferenceArray.get()` (volatile read semantics, line 48). The volatile head/tail writes fence the ring buffer contents, ensuring readers see fully constructed `ConfigDelta` objects.

---

## 3. RateLimiter -- FIXED (HIGH-4)

| Check | Result |
|-------|--------|
| Lock-free implementation | **PASS** (after fix) |

**Finding:** The original `RateLimiter` used `synchronized` methods, creating a serialization bottleneck and causing virtual thread carrier pinning on JDK 21+.

**Fix applied:** Replaced with a CAS-based lock-free token bucket implementation.

**Evidence:** `RateLimiter.java:21` -- `public final class RateLimiter`
- `RateLimiter.java:30-31` -- State encoded in `AtomicLong storedPermitsScaled` and `AtomicLong lastRefillNanos`
- `RateLimiter.java:89-121` -- `tryAcquire()` uses a CAS loop: reads current state, computes refill, attempts `storedPermitsScaled.compareAndSet()`. On CAS failure, retries (line 120).
- No `synchronized` blocks remain in the class.

**Severity:** HIGH-4 (virtual thread pinning, throughput bottleneck on write path)  
**Status:** FIXED

---

## 4. No Lock on Edge Read Path

| Check | Result |
|-------|--------|
| Edge read path is lock-free | **PASS** |

**Evidence:** `LocalConfigStore.java:99-107` -- `get(String key)` performs:
1. Single volatile read of `currentSnapshot` (line 101)
2. HAMT traversal via `snap.data().get(key)` -- zero allocation, no locks (line 102)
3. Returns `ReadResult.NOT_FOUND` singleton on miss or allocates a new `ReadResult` on hit

No locks, no CAS operations, no synchronization of any kind on the read path. This satisfies Hard Rule #6 (no lock on edge read path).

---

## 5. ConfigStateMachine.apply() Single-Threaded

| Check | Result |
|-------|--------|
| apply() called from single thread only | **PASS** |

**Evidence:** `ConfigStateMachine.java:24-26` -- Javadoc states: "This class is designed to be called from the Raft apply thread only (single-threaded). No internal synchronization is provided for the apply method."

The call chain is: `RaftNode.tick()` -> `applyCommitted()` (`RaftNode.java:1236-1267`) -> `stateMachine.apply()` (`RaftNode.java:1262`). The `RaftNode` class is documented as single-threaded (driven by a single `ScheduledExecutorService` tick thread, `ConfigdServer.java:261-283`). The `sequenceCounter` field in `ConfigStateMachine` (line 77) is a plain `long`, confirming the single-writer assumption.

---

## 6. HamtMap Deep Immutability

| Check | Result |
|-------|--------|
| All node types use final fields | **PASS** |
| Arrays defensively copied on construction | **PASS** |

**Evidence:**
- `HamtMap.java:57-58` -- `private final Node<K, V> root;` and `private final int size;`
- `BitmapIndexedNode` (`HamtMap.java:187-192`) -- `final int bitmap;` and `final Object[] array;`
- `ArrayNode` (`HamtMap.java:412-413`) -- `private final int count;` and `private final Node<K, V>[] children;`
- `CollisionNode` (`HamtMap.java:520-521`) -- `final int hash;` and `final Object[] pairs;`

All mutation operations (`put`, `remove`) return new node instances via `array.clone()` (`HamtMap.java:339`, `HamtMap.java:474`), preserving the original nodes. The `HamtMap` is a persistent (immutable) data structure with structural sharing -- concurrent readers are guaranteed to see a consistent tree at all times without synchronization.

---

## 7. ReadResult.NOT_FOUND Singleton Safe Publication

| Check | Result |
|-------|--------|
| Singleton safely published via static final | **PASS** |

**Evidence:** `ReadResult.java:24` -- `public static final ReadResult NOT_FOUND = new ReadResult(EMPTY, 0, false);`

The `NOT_FOUND` singleton is published via a `static final` field, which is guaranteed by the JLS (§17.5) to be safely published to all threads after class initialization completes. The `ReadResult` class itself is immutable (all fields are `final`: `value`, `version`, `found` at lines 26-28), so no further synchronization is needed after publication.

---

## Summary

| Item | Status |
|------|--------|
| VersionedConfigStore.currentSnapshot volatile | PASS |
| LocalConfigStore.currentSnapshot volatile | PASS |
| FanOutBuffer head/tail volatiles with AtomicReferenceArray | PASS |
| RateLimiter lock-free CAS-based (was synchronized) | PASS (FIXED, was HIGH-4) |
| No lock on edge read path (Hard Rule #6) | PASS |
| ConfigStateMachine.apply() single-threaded from tick loop | PASS |
| HamtMap nodes deeply immutable (final fields, arrays copied) | PASS |
| ReadResult.NOT_FOUND singleton safely published (static final) | PASS |

All concurrency properties verified. One finding (HIGH-4: RateLimiter synchronized) was fixed by replacing with a lock-free CAS-based token bucket. No remaining concurrency issues detected.

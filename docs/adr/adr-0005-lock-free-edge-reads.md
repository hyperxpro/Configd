# ADR-0005: Lock-Free Edge Read Path (HAMT + Volatile Atomic Swap)

## Status
Accepted

## Context
Edge read latency must be < 1ms p99 (in-process) and < 5ms p999. The read path must support concurrent readers and a single writer with zero contention, zero allocation in steady state, and no locks or CAS loops.

## Decision
The edge config store uses a **Hash Array Mapped Trie (HAMT)** as an immutable persistent data structure behind a **volatile reference pointer**. The pattern:

```
┌─────────────┐       ┌──────────────────┐
│ volatile ref │──────►│ Immutable HAMT v42│
│ (8 bytes)    │       │ (structural      │
└─────────────┘       │  sharing with v41)│
                       └──────────────────┘
```

**Reader (hot path, any thread):**
1. Load volatile reference → acquires current snapshot (single CPU instruction)
2. Traverse HAMT to find key → O(log32 N) ≈ O(1) for practical N
3. Return value + version cursor
4. **Zero allocation, zero locks, zero CAS, zero syscalls**

**Writer (single thread):**
1. Receive delta from Plumtree stream
2. Apply delta to current HAMT → produces new HAMT with structural sharing (only modified path cloned)
3. Store new HAMT reference to volatile field (single CPU instruction, StoreStore barrier)
4. Old HAMT becomes eligible for GC once no readers hold references

This is the **Read-Copy-Update (RCU)** pattern, proven in the Linux kernel for decades.

## Influenced by
- **Linux kernel RCU:** Readers proceed with zero synchronization; writers publish new version atomically.
- **SurrealDB VART:** Versioned Adaptive Radix Trie with snapshot isolation via copy-on-write.
- **Rust ArcSwap:** Lock-free, wait-free atomic pointer swap. Reads in 10-100ns range.
- **CockroachDB closed timestamps:** Follower reads from immutable snapshot at known-safe timestamp.

## Reasoning
### Why HAMT?
- O(log32 N) lookup — effectively O(1) for 10^6 keys (4 levels) or 10^9 keys (6 levels).
- Structural sharing: updating one key clones only ~6 nodes (path from leaf to root), sharing the rest. For 10^6 keys with 1KB values: ~32 KB copied per update, not 1 GB.
- Immutable after creation — no concurrent modification hazards.

### Why volatile pointer (not AtomicReference)?
- `volatile` provides the same visibility guarantee as `AtomicReference.get()` for reads (LoadLoad + LoadStore barrier).
- Writer uses plain store (StoreStore barrier via volatile semantics). No CAS needed because writer is single-threaded.
- Avoids `AtomicReference` object header overhead and indirection.

### Why not ConcurrentHashMap?
- ConcurrentHashMap uses striped locks for writes and `volatile` reads. Reads are lock-free but allocate `Map.Entry` objects during iteration.
- Does not support immutable snapshots — a reader can see partial updates if a batch of changes is being applied.
- No structural sharing — full copy needed for snapshot.

### Performance projection
- Volatile read: ~1-2ns (L1 cache hit for pointer)
- HAMT traversal (4 levels): ~20-40ns (4 array lookups + 4 hash computations)
- Total read: **< 50ns** (50,000× under the 1ms target)
- At p999 with GC jitter (ZGC): ~1-5μs (1000× under 5ms target)

## Rejected Alternatives
- **ConcurrentHashMap:** Allocates during reads (iterator). No immutable snapshots. No structural sharing.
- **Copy-on-write HashMap (Collections.unmodifiableMap):** Full copy on every update. At 10^6 keys × 1KB = 1 GB copied per update — catastrophic.
- **ReadWriteLock:** Reader-writer contention under high read concurrency. Lock acquisition cost (~50-100ns) is comparable to the entire HAMT traversal.
- **StampedLock optimistic reads:** Requires retry loop on write contention. Adds complexity and unpredictable latency.

## Consequences
- **Positive:** Sub-100ns reads. Zero allocation in steady state. Zero reader-writer contention. Readers never block writer; writer never blocks readers. Natural versioning via HAMT identity.
- **Negative:** Writer must be single-threaded (or serialized). Old HAMT versions consume memory until GC collects them. Structural sharing means keys reference shared internal nodes — cannot free individual keys without GC.
- **Risks and mitigations:** Memory pressure from retained old versions mitigated by ZGC (sub-ms pause times) and bounded version retention (writer drops references to versions older than N updates). Write throughput limited by single writer thread mitigated by batching deltas (multiple key updates per HAMT swap).

## Reviewers
- principal-distributed-systems-architect: ✅
- performance-engineer: ✅
- senior-java-systems-engineer: ✅

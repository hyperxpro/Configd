# ADR-0012: Purpose-Built Storage Engine with HAMT Structural Sharing

## Status
Accepted

## Context
The system must store up to 10^9 config keys with versioned history, supporting 10K/s sustained writes and < 1ms p99 edge reads. General-purpose storage engines impose unacceptable tradeoffs at this scale: LMDB exhibits 1.5x-80x write amplification (gap-analysis.md section 1.3), RocksDB's LSM compaction creates unpredictable latency spikes under 10x key growth (10^10 keys = 10x compaction pressure), and etcd's BoltDB hits an 8 GB ceiling with near-linear page allocation degradation (gap-analysis.md section 3.3). The storage engine must support MVCC with configurable retention, concurrent reads during compaction, and off-heap operation to avoid GC pressure (gap-analysis.md section 2.3).

## Decision
We build a **config-optimized storage engine** with three layers:

### 1. In-Memory Layer: HAMT with Structural Sharing (Hot Path)
- Hash Array Mapped Trie (32-way branching, 5 bits per hash level) as the primary read-serving structure.
- Immutable after creation. Writer produces a new HAMT root via path-copy (ADR-0005); readers access the current snapshot via volatile reference load.
- Structural sharing: updating one key clones only the path from leaf to root (~6 nodes for 10^6 keys, ~32 KB per mutation). At 10K writes/s: ~320 MB/s of structural sharing overhead — well within ZGC's allocation budget.
- Per-shard HAMT instances. Each Raft shard maintains its own HAMT, limiting per-structure size and enabling independent compaction.

### 2. Persistence Layer: Append-Only WAL + Periodic Snapshots
- Every Raft-committed entry appended to a write-ahead log. Sequential writes only — no random I/O, no B-tree page splits, no LSM compaction.
- WAL segments: 64 MB files, pre-allocated for sequential write performance. fsync per batch (configurable: per-entry for durability, per-batch for throughput).
- Periodic snapshots: serialize current HAMT to disk via depth-first traversal. Snapshot is a point-in-time image at a known sequence number. Concurrent with reads and writes (snapshot reads from immutable HAMT; writer continues producing new versions).
- Snapshot format: chunked (1 MB chunks, CRC-32 per chunk) for resumable transfer during InstallSnapshot.

### 3. MVCC Layer: Version History with Configurable Retention
- Each key stores a version chain: current value + N previous versions (default: last 100 versions or 2 hours, whichever is less).
- Version chain stored as a compact array per key (not a linked list — cache-friendly sequential scan for version lookups).
- Compaction runs concurrently on a background thread. Removes versions older than the retention window. Does not block reads or writes.
- Version lookup by sequence number: binary search over the version chain (O(log V) where V = retained versions per key).

### Off-Heap Storage (Large Values)
- Values > 4 KB stored off-heap via Agrona DirectBuffer. HAMT leaf stores an off-heap pointer + length.
- Off-heap region managed as a slab allocator with 4 KB, 16 KB, 64 KB, 256 KB, and 1 MB size classes.
- Reference counting for off-heap memory: incremented when HAMT version references the value, decremented when HAMT version is GC'd via Cleaner callback.

## Influenced by
- **Clojure Persistent Data Structures (Hickey, 2007):** HAMT with structural sharing for efficient immutable updates. Path-copy produces O(log32 N) new nodes per mutation while sharing the rest of the tree.
- **FoundationDB Storage Server:** SQLite-based storage with memory-mapped B-tree for reads and append-only WAL for writes. Separation of read and write paths eliminates compaction-induced latency spikes.
- **CockroachDB Pebble:** Fork of RocksDB addressing compaction debt and write amplification in LSM trees. Documents write amplification of 10-30x in worst case for LSM engines.
- **Agrona (Aeron project):** Off-heap direct buffers and ring buffers for sub-microsecond messaging without GC pressure. Production-proven at high-frequency trading latencies.

## Reasoning

### Why not LMDB?
LMDB (used by Quicksilver) is a B+ tree with copy-on-write semantics. At scale:
- **Write amplification:** 1.5x-80x documented (Quicksilver engineering blog). A single key update copies an entire B+ tree page (4 KB default) even for a 100-byte value change. At 10K writes/s with 4 KB pages: 40 MB/s of write amplification minimum.
- **Single-writer lock:** LMDB permits only one writer at a time. Under 10K writes/s sustained load, write queue depth grows during any writer stall (fsync, OS scheduling).
- **Database size limit:** 128 TB theoretical, but B+ tree fragmentation increases with size. No built-in compaction — requires external tooling.
- HAMT structural sharing copies only ~32 KB per mutation (6 nodes on the modified path), not an entire 4 KB page. At 10K writes/s: 320 MB/s vs LMDB's 40 MB/s minimum — but HAMT operates entirely in memory with periodic background snapshots, avoiding per-write fsync.

### Why not RocksDB (LSM tree)?
- **Compaction pressure:** At 10^9 keys with 10K writes/s, LSM compaction runs continuously. Compaction consumes I/O bandwidth and CPU, creating latency spikes on reads that coincide with compaction. CockroachDB's migration from RocksDB to Pebble was motivated partly by compaction debt issues.
- **Write amplification:** LSM trees exhibit 10-30x write amplification in worst case (memtable flush + L0-L1 compaction + L1-L2 compaction + ...). For 10K writes/s at 1 KB: 10-30 MB/s of I/O amplification.
- **Space amplification:** RocksDB uses ~40% less space than LMDB but tombstone accumulation during deletes creates temporary space bloat until compaction clears them.
- Config workload characteristics (small values, range scans for prefix queries, version history) do not match LSM strengths (large sequential writes, high ingestion rates).

### Why not BoltDB?
etcd's BoltDB has an 8 GB database ceiling. Page allocation degrades to near-linear scan as database grows. Single request handling 1 GB of pod data consumes ~5 GB across the process. Compaction/defragmentation is a blocking operation causing latency spikes. Not a viable option for 10^9 keys.

### Capacity projection
- 10^6 keys x 1 KB average value = 1 GB base. HAMT overhead: ~32 bytes/key internal nodes = 32 MB. Total: ~1.03 GB.
- 10^9 keys x 1 KB average value = 1 TB base. HAMT depth increases to 6 levels. Internal node overhead: ~32 GB. Total: ~1.03 TB. Per-shard (100 shards): ~10.3 GB — fits in memory with off-heap for large values.

## Rejected Alternatives
- **LMDB:** 1.5x-80x write amplification. Single-writer lock creates head-of-line blocking at 10K writes/s. Copy-on-write pages waste I/O on small value updates. No built-in MVCC with configurable retention.
- **RocksDB / LevelDB:** 10-30x write amplification from LSM compaction. Compaction-induced read latency spikes violate the < 1ms p99 edge read target. Tombstone accumulation during delete-heavy workloads causes space bloat.
- **BoltDB:** 8 GB database ceiling. Blocking defragmentation. Near-linear page scan degradation at scale. Not viable for 10^9 keys.
- **SQLite (FoundationDB model):** B-tree with page-level locking. Write-ahead logging helps but single-writer model limits throughput. Not designed for the concurrent read/single-writer HAMT pattern we require.

## Consequences
- **Positive:** Zero write amplification for in-memory operations (HAMT structural sharing). No compaction-induced read latency spikes. MVCC with concurrent background compaction. Off-heap storage avoids GC pressure for large values. Per-shard independence enables parallel snapshot and compaction.
- **Negative:** Must implement and maintain a custom storage engine — significant engineering investment. In-memory primary store means dataset must fit in aggregate memory across shards. Snapshot serialization adds background I/O.
- **Risks and mitigations:** Memory exhaustion mitigated by per-shard memory budgets and automatic shard splitting when a shard exceeds its budget. Data loss on crash mitigated by WAL durability (fsync per batch) and Raft replication (majority of replicas must persist before commit acknowledged). Custom storage bugs mitigated by deterministic simulation testing (ADR-0007) exercising crash/recovery paths with injected disk failures.

## Reviewers
- principal-distributed-systems-architect: ✅
- performance-engineer: ✅
- storage-systems-engineer: ✅

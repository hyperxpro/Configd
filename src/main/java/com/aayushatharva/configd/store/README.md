# Storage Engine

The storage layer is the heart of Configd. It provides a versioned key-value store backed by RocksDB with multi-version concurrency control (MVCC) and Bloom-filter-accelerated lookups.

## Architecture

```
                    +---------------------------+
                    |         KVStore            |  Interface
                    +-------------+-------------+
                                  |
                    +-------------v-------------+
                    |       RocksDBStore         |  Implementation
                    |                           |
                    |  +---------+ +---------+  |
                    |  | default | |  mvcc   |  |  Column Families
                    |  |   CF    | |   CF    |  |
                    |  +---------+ +---------+  |
                    |  +---------+              |
                    |  |  meta   |              |
                    |  |   CF    |              |
                    |  +---------+              |
                    +---------------------------+

                    +---------------------------+
                    |        KeyIndex           |  Bloom filter index
                    | (proxy nodes only)        |  for negative lookups
                    +---------------------------+
```

## Components

### KVStore (Interface)

Defines the contract for all storage operations:

- `get(key)` -- read the current value
- `get(key, version)` -- MVCC read at a specific replication version
- `put(key, value, version)` -- write with version tag
- `delete(key, version)` -- tombstone with version tag
- `scan(startKey, endKey, limit)` -- ordered range scan
- `containsKey(key)` -- Bloom-filter-accelerated existence check
- `getCurrentVersion()` / `setCurrentVersion(version)` -- replication version tracking

### RocksDBStore

The primary implementation using RocksDB with three column families:

**Default CF** -- Current values. The latest version of each key. Configured with:
- 10-bit Bloom filters for fast negative lookups
- 64 MB write buffers with 3 buffer limit
- Level-style compaction
- 64 MB LRU block cache

**MVCC CF** -- Historical versions. Keys are encoded as `[original-key][0xFF][8-byte version big-endian]`. This encoding ensures:
- Natural byte ordering (higher versions sort after lower ones)
- Prefix scanning to find all versions of a key
- `seekForPrev` to efficiently find the latest version <= a requested version

**Meta CF** -- Internal metadata. Stores the current replication version counter, persisted across restarts.

#### MVCC Read Path

When a proxy node lags behind its upstream replica, it needs to read values at a specific historical version. The MVCC read path:

1. **Fast path**: Check the default CF. If the stored version <= requested version, return immediately.
2. **Slow path**: Scan the MVCC CF using `seekForPrev` to find the latest version of the key that is <= the requested version. Check for tombstones (empty values indicate deletion).

This is critical for Configd's sequential consistency guarantee: if key A was written before key B, no node should ever see B without also seeing A.

#### Write Path

Every write atomically updates both column families using a `WriteBatch`:
1. Write the current value to the default CF
2. Write the versioned value to the MVCC CF

The version counter advances monotonically and is periodically persisted to the meta CF.

### KeyIndex

Used exclusively by proxy nodes. Configd discovered that proxies receive ~10x more negative lookups (keys that don't exist) than positive ones. Caching negative results is impractical because the key space is ~1000x larger than the actual data set.

The solution: store all keys on proxies but only persist values for cached keys. The `KeyIndex` maintains a Guava Bloom filter over the full key set:

- `mightContain(key)` -- returns `false` if the key definitely doesn't exist (no disk I/O needed)
- `addKey(key)` -- adds a key to the filter
- `rebuild(keys)` -- reconstructs the filter from a fresh key set (called periodically)

Configuration: 10 million expected keys, 1% false positive probability. This uses approximately 6 GB of memory at scale (vs. 18 GB for a Cuckoo filter with the same guarantees).

## Data Format

### MVCC Key Encoding

```
+-------------------+------+-------------------+
| original key      | 0xFF | version (8 bytes) |
| (variable length) |      | (big-endian long) |
+-------------------+------+-------------------+
```

The `0xFF` delimiter separates the original key from the version suffix. This works because:
- RocksDB sorts keys lexicographically
- All versions of the same key are contiguous
- `seekForPrev` finds the correct historical version efficiently

### Version Tracking

The replication version is a monotonically increasing counter. It corresponds to the transaction log sequence number. Every write operation carries a version, and the store's current version only advances (never regresses). This is the foundation for both MVCC reads and replication consistency.

## Configuration

Key RocksDB tuning parameters (set in `RocksDBStore`):

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Bloom filter bits | 10 | Good balance of memory vs. false positive rate |
| Write buffer size | 64 MB | Batches writes before flushing to SST files |
| Max write buffers | 3 | Allows background flushes without stalling |
| Block size | 16 KB | Balanced for point lookups and scans |
| Block cache | 64 MB LRU | Hot blocks stay in memory |
| Compaction | Level | Best for read-heavy workloads |

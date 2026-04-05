# Cache System

The three-tier caching system is Configd v2's most impactful innovation. It replaced the v1 model (where every server stored the entire dataset) with a hierarchical cache that achieves 99.99%+ hit rates while using a fraction of the storage.

## Three-Tier Architecture

```
  +-----------------------------------------------+
  |                   Proxy Node                   |
  |                                                |
  |  +------------------------------------------+  |
  |  | L1: LocalCache (in-memory LRU)           |  |
  |  | Per-server, ~256 MB, microsecond access   |  |
  |  +------------------+-----------------------+  |
  |                     | miss                      |
  |  +------------------v-----------------------+  |
  |  | L1.5: PersistentLRUCache (RocksDB)       |  |
  |  | Disk-backed, survives restarts            |  |
  |  +------------------+-----------------------+  |
  |                     | miss                      |
  +---------------------+--------------------------+
                        | network
  +---------------------v--------------------------+
  |  L2: ShardedCacheClient                        |
  |  Data-center-wide, 1024 shards, distributed    |
  |  across peer proxy nodes in the same DC         |
  +---------------------+--------------------------+
                        | miss (via relay)
  +---------------------v--------------------------+
  |  L3: Full Replicas                              |
  |  Complete dataset on dedicated storage nodes    |
  +------------------------------------------------+
```

## Components

### CacheManager

The orchestrator that ties all tiers together. On a `get(key)` call:

1. **Replica nodes**: Skip the cache entirely, read directly from the RocksDB store.
2. **Proxy nodes**:
   - Check L1 (in-memory). If hit, return immediately.
   - Check L1.5 (persistent disk cache). If hit, promote to L1 and return.
   - Check L2 (sharded cache across the data center). If hit, promote to L1 and return.
   - Fall through to L3 (full replica via relay). On hit, populate all cache tiers.

The cache manager also supports MVCC-aware reads: `get(key, version)` checks the cached version against the requested version and falls back to the store's MVCC lookup if the cached version is too new.

**Metrics tracked**: L1 hits, L2 hits, L3 hits, total misses, local cache utilization. Exposed via the `/v1/status` API endpoint.

### LocalCache (L1)

An in-memory LRU cache using an access-ordered `LinkedHashMap`. This is the fastest tier with microsecond access times.

**Eviction policy**:
- **Soft limit** (default 80% of max bytes): Triggers background LRU eviction. Evicts entries until at 90% of the soft limit.
- **Hard limit** (default 95% of max bytes): Rejects new cache entries entirely until space is freed. This prevents the cache from consuming all available memory.

**Version awareness**: Each cache entry stores the replication version at which it was written. The `get(key, maxVersion)` method returns `null` if the cached entry's version exceeds `maxVersion`, forcing a fallback to the MVCC store. This handles the case where a proxy's cache is slightly ahead of its replication state.

**Thread safety**: Read operations use a read lock; writes use a write lock. The LRU ordering is maintained by `LinkedHashMap`'s access-order mode.

### PersistentLRUCache (L1.5)

A RocksDB-backed on-disk cache. Why not just use in-memory caching?

- With billions of keys, an in-memory cache would require hundreds of GB of RAM
- Cold starts after a restart would cause a flood of L3 requests
- Persistent caching keeps the working set on disk with a low memory footprint

Each entry is stored as `[8-byte timestamp][value bytes]`. On read, the timestamp is updated (LRU touch). During compaction, entries older than the retention period would be dropped (production systems use custom compaction filters; this implementation tracks size and uses soft/hard limits).

**Eviction**: Same soft/hard limit pattern as L1. When the hard limit is reached, the cache stops accepting new entries until compaction or manual cleanup brings it below the soft limit.

### ShardedCacheClient (L2)

The data-center-wide distributed cache. When a key misses in L1 and L1.5, it might exist in another proxy node's cache. The sharded cache distributes the key space across all proxy nodes using the `ShardManager`'s hash partitioning.

**Lookup flow**:
1. Hash the key to determine which physical shard (and therefore which node) owns it.
2. If the shard is local, it's handled by the local store.
3. Otherwise, send a `CACHE_LOOKUP_REQUEST` over TCP to the owning node.
4. The remote node checks its local store and responds.
5. On hit, the value is promoted to L1 on the requesting node.

**Store flow**: After an L3 resolution, the value is stored in L2 so other proxies can benefit. This is a fire-and-forget `CACHE_STORE_REQUEST`.

### ReactivePrefetcher

The final piece of the cache puzzle. In Configd v2, all cache misses resolved by relays are **broadcast as streams** to all proxies in the data center. Proxies subscribe and proactively populate their local caches.

This means: if proxy A resolves a cache miss for key X, proxies B, C, and D will also receive key X's value before any of their users ask for it.

The prefetcher integrates with the replication system:
- It implements `ReplicationPuller.ReplicationListener`
- When new entries arrive via replication, they're immediately inserted into L1
- When a `PREFETCH_NOTIFY` message arrives from a relay, the key-value pair is cached locally

**Impact**: This pushes cache hit rates from ~99% to >99.999% for most instances.

## Why Three Tiers?

Cloudflare's working set analysis revealed:
- **Large data centers**: ~20% of the key space is actively accessed
- **Small data centers**: ~1% of the key space is accessed

A single-tier cache wastes enormous storage by caching the full dataset everywhere. The three-tier approach means:
- Hot keys live in L1 (fast, small)
- Warm keys live in L2 (shared across the DC, medium)
- Cold keys are fetched from L3 only when actually needed (rare)

At Cloudflare's scale (5 billion keys, 1.6 TB total), this saves hundreds of terabytes of storage globally compared to full replication.

## Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| localCacheMaxBytes | 256 MB | L1 in-memory cache size |
| shardedCacheMaxBytes | 1 GB | Persistent cache size per node |
| evictionSoftLimitRatio | 0.80 | Start background eviction |
| evictionHardLimitRatio | 0.95 | Stop accepting new entries |
| mvccRetentionMs | 2 hours | How long to keep historical versions |

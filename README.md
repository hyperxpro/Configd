# Configd

A globally distributed key-value store inspired by [Cloudflare's Quicksilver](https://blog.cloudflare.com/quicksilver-v2-evolution-of-a-globally-distributed-key-value-store-part-1/). Built in Java 17 with RocksDB, Netty, and a pull-based hierarchical replication protocol. Implements all three generations of Quicksilver's architecture (v1, v1.5, v2).

## What Is This?

Cloudflare's Quicksilver is the internal distributed KV store that powers configuration distribution across their global network -- serving over **3 billion keys per second** with sub-millisecond latency at 330+ data centers worldwide. It propagates DNS records, firewall rules, CDN configuration, and more.

Configd is a faithful Java implementation of Quicksilver's architecture as described across Cloudflare's engineering blog series:

- **v1**: Full-dataset replication to every server. LMDB storage, fan-out replication tree, monotonic transaction logs.
- **v1.5**: Introduced proxy nodes with persistent caching and Bloom-filter-accelerated negative lookups. Reduced disk usage ~50%.
- **v2**: Three-tier caching (local, data-center sharded, full replicas), 1024 logical shards, reactive prefetching, and relay nodes. Achieved 99.99%+ cache hit rates.

## Architecture

```
                         +--------------+
                         |    ROOT      |     Writes enter here
                         |  (Replica)   |
                         +------+-------+
                                |
               Replication (pull-based, sequential consistency)
                                |
              +-----------------+-----------------+
              |                 |                 |
        +-----+------+   +-----+------+   +------+-----+
        |INTERMEDIATE |   |INTERMEDIATE |   |INTERMEDIATE |
        | (Replica)   |   | (Replica)   |   | (Replica)   |
        +-----+-------+  +------+------+   +------+------+
              |                  |                  |
     +--------+------+          |          +-------+-------+
     |        |      |          |          |       |       |
   +--+-+  +--+-+ +--+-+     +--+-+     +--+-+ +--+-+  +--+-+
   |Rly |  |Prx | |Prx |     |Rly |     |Rly | |Prx |  |Prx |
   +----+  +----+ +----+     +----+     +----+ +----+  +----+

     Data Center A        Data Center B       Data Center C
```

### Node Roles (Position in Replication Tree)

| Role | Description |
|------|-------------|
| **ROOT** | Write ingestion point. Hyperscale core data centers with terabytes of storage. Aggregates all writes to ensure monotonic sequence numbering. |
| **INTERMEDIATE** | Replicates from root nodes and fans out to leaf nodes. Bridges regions. |
| **LEAF** | End-user-facing servers. Receives updates from intermediate nodes. |

### Node Modes (Behavior Within a Data Center)

| Mode | Description |
|------|-------------|
| **REPLICA** | Stores the complete dataset. Serves as L3 (last resort) for cache misses. |
| **RELAY** | Maintains connections to replicas. Multiplexes proxy requests to avoid connection overload on replicas. Broadcasts cache-miss resolutions to proxies (reactive prefetching). |
| **PROXY** | Persistent cache. Stores all keys but only caches values for accessed keys. Uses Bloom filters for fast negative lookups. Lightest storage footprint. |

### Three-Tier Cache (v2)

```
Request Flow:

  Client Request
       |
       v
  +----------+
  | L1 Cache |  In-memory LRU, per-server. Microsecond access.
  +----+-----+  Hit rate: >99% typical
       | miss
       v
  +-----------+
  | L2 Cache  |  Data-center sharded across servers (1024 shards).
  +----+------+  Distributed via Murmur3 hash partitioning.
       | miss
       v
  +----------+
  | L3 Store |  Full replicas via relay. Complete dataset.
  +----------+  Only queried for cold/never-accessed keys.

  Combined hit rate: >= 99.99% (worst case), >99.999% typical
```

### Replication Protocol

Configd uses a **pull-based hierarchical fan-out** replication protocol:

1. Every write at the ROOT node is appended to a **transaction log** with a monotonically increasing sequence number.
2. Downstream nodes periodically pull entries from their upstream by sending their last-known sequence number.
3. The upstream responds with all entries after that sequence.
4. Entries are applied in order to the local store, guaranteeing **sequential consistency** -- if key A was written before key B, no node can see B without also seeing A.

Key properties:
- **500ms batched writes** reduce disk I/O by combining operations within time windows.
- **CRC32 checksums** on every log entry detect corruption.
- **Snappy compression** reduces log storage and network overhead.
- **30-second staleness threshold**: if a node detects its upstream is stale, it automatically rotates to the next available upstream.
- **Sliding window**: proxies maintain a rolling window of recent updates that cannot be evicted, ensuring consistency even when slightly ahead of their upstream replica.

## Components

The project is organized into these packages:

| Package | Purpose | Key Files |
|---------|---------|-----------|
| [`store`](src/main/java/com/aayushatharva/configd/store/) | Storage engine | `RocksDBStore`, `KVStore`, `KVEntry`, `KeyIndex` |
| [`txlog`](src/main/java/com/aayushatharva/configd/txlog/) | Transaction log | `RocksDBTransactionLog`, `TransactionLogEntry`, `BatchingTransactionLogWriter` |
| [`replication`](src/main/java/com/aayushatharva/configd/replication/) | Replication protocol | `ReplicationManager`, `ReplicationPuller`, `ReplicationServer`, `SlidingWindow` |
| [`cache`](src/main/java/com/aayushatharva/configd/cache/) | Three-tier caching | `CacheManager`, `LocalCache`, `ShardedCacheClient`, `PersistentLRUCache`, `ReactivePrefetcher` |
| [`shard`](src/main/java/com/aayushatharva/configd/shard/) | Shard partitioning | `ShardManager`, `HashPartitioner` |
| [`discovery`](src/main/java/com/aayushatharva/configd/discovery/) | Gossip discovery | `GossipProtocol`, `HealthMonitor`, `NodeDiscovery` |
| [`network`](src/main/java/com/aayushatharva/configd/network/) | Inter-node networking | `NetworkServer`, `NetworkClient`, `ConnectionPool`, `MessageCodec` |
| [`api`](src/main/java/com/aayushatharva/configd/api/) | REST API | `RestApiServer`, `ApiResponse` |
| [`node`](src/main/java/com/aayushatharva/configd/node/) | Node identity | `NodeRole`, `NodeMode`, `NodeInfo` |

Detailed documentation for each component is in its package README:

- [Storage Engine](src/main/java/com/aayushatharva/configd/store/README.md)
- [Transaction Log](src/main/java/com/aayushatharva/configd/txlog/README.md)
- [Replication Protocol](src/main/java/com/aayushatharva/configd/replication/README.md)
- [Cache System](src/main/java/com/aayushatharva/configd/cache/README.md)
- [Sharding](src/main/java/com/aayushatharva/configd/shard/README.md)
- [Discovery (Gossip)](src/main/java/com/aayushatharva/configd/discovery/README.md)
- [Network Layer](src/main/java/com/aayushatharva/configd/network/README.md)
- [REST API](src/main/java/com/aayushatharva/configd/api/README.md)

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.8+

### Build

```bash
mvn clean package -DskipTests
```

### Run

```bash
# Start a ROOT replica (write ingestion node)
java -jar target/configd-2.0.0-SNAPSHOT.jar \
  --role ROOT \
  --mode REPLICA \
  --node-id root-1 \
  --port 7400 \
  --api-port 7401 \
  --data-dir /var/lib/configd/data \
  --txlog-dir /var/lib/configd/txlog

# Start a LEAF proxy replicating from root
java -jar target/configd-2.0.0-SNAPSHOT.jar \
  --role LEAF \
  --mode PROXY \
  --node-id proxy-dc2-1 \
  --port 7410 \
  --api-port 7411 \
  --upstream 10.0.1.100:7400 \
  --data-center dc2 \
  --gossip-seeds 10.0.1.100:7402

# Start a RELAY node in the same data center
java -jar target/configd-2.0.0-SNAPSHOT.jar \
  --role LEAF \
  --mode RELAY \
  --node-id relay-dc2-1 \
  --port 7420 \
  --api-port 7421 \
  --upstream 10.0.1.100:7400 \
  --data-center dc2
```

### Configuration File

Instead of CLI arguments, you can use a JSON configuration file:

```json
{
  "nodeId": "root-1",
  "host": "0.0.0.0",
  "internalPort": 7400,
  "apiPort": 7401,
  "role": "ROOT",
  "mode": "REPLICA",
  "dataCenter": "dc1",
  "region": "us-east",
  "dataDir": "/var/lib/configd/data",
  "txLogDir": "/var/lib/configd/txlog",
  "upstreamNodes": [],
  "replicationPullIntervalMs": 1000,
  "batchWriteIntervalMs": 500,
  "replicationBatchSize": 1000,
  "stalenessThresholdMs": 30000,
  "logicalShardCount": 1024,
  "shardingFactor": 1,
  "localCacheMaxBytes": 268435456,
  "shardedCacheMaxBytes": 1073741824,
  "evictionSoftLimitRatio": 0.8,
  "evictionHardLimitRatio": 0.95,
  "mvccRetentionMs": 7200000,
  "gossipPort": 7402,
  "gossipIntervalMs": 1000,
  "gossipSeeds": [],
  "slidingWindowDurationMs": 60000,
  "instanceCount": 1
}
```

```bash
java -jar target/configd-2.0.0-SNAPSHOT.jar config.json
```

## REST API

### Write a Key

```bash
curl -X PUT http://localhost:7401/v1/kv/my-key \
  -d 'my-value'
```

```json
{"success": true, "data": {"key": "my-key", "version": 1}}
```

### Read a Key

```bash
curl http://localhost:7401/v1/kv/my-key
```

```json
{
  "success": true,
  "data": {
    "key": "my-key",
    "value": "bXktdmFsdWU=",
    "version": 1
  }
}
```

Values are Base64-encoded to support binary data.

### MVCC Read (Point-in-Time)

```bash
curl http://localhost:7401/v1/kv/my-key?version=5
```

Returns the value as it existed at version 5, even if a newer version has been written since.

### Delete a Key

```bash
curl -X DELETE http://localhost:7401/v1/kv/my-key
```

### Range Scan

```bash
curl 'http://localhost:7401/v1/scan?start=dns:&end=dns:~&limit=50'
```

### Node Status

```bash
curl http://localhost:7401/v1/status
```

```json
{
  "success": true,
  "data": {
    "nodeId": "root-1",
    "role": "ROOT",
    "mode": "REPLICA",
    "dataCenter": "dc1",
    "currentVersion": 1042,
    "approximateKeys": 50000,
    "cache": {
      "l1Hits": 98234,
      "l2Hits": 1520,
      "l3Hits": 246,
      "misses": 0,
      "hitRate": "1.0000",
      "localCacheEntries": 12400,
      "localCacheUtilization": "0.45"
    }
  }
}
```

### Health Check

```bash
curl http://localhost:7401/v1/health
```

## Multi-Instance Mode

Cloudflare runs 10 Quicksilver instances per server (one for DNS, one for CDN, one for WAF, etc.). Configd supports this with the `instanceCount` config:

```bash
java -jar target/configd-2.0.0-SNAPSHOT.jar \
  --role ROOT --mode REPLICA --instances 10
```

Each instance gets its own storage directory, transaction log, replication pipeline, cache, and port range (base port + instance index).

## Testing

```bash
# Run all tests
mvn test

# Run specific test suite
mvn test -Dtest=RocksDBStoreTest
mvn test -Dtest=ConfigdIntegrationTest
```

The test suite includes:
- **Unit tests** for storage, MVCC, transaction log, sharding, cache, sliding window, Bloom filter index
- **Integration tests** that bootstrap a ROOT replica and LEAF proxy, verify writes, reads, replication, and cache behavior

## Technology Stack

| Component | Technology | Why |
|-----------|-----------|-----|
| Storage | RocksDB (via rocksdbjni) | LSM-tree design. Excellent write throughput, built-in Bloom filters, column families for MVCC. Used by actual Quicksilver v1.5+. |
| Networking | Netty | Non-blocking I/O, zero-copy, channel pipelines. Industry standard for high-performance Java networking. |
| Hashing | Guava (Murmur3) | Fast, well-distributed hash for shard partitioning. Guava also provides Bloom filter implementation. |
| Compression | Snappy | Fast compression/decompression for transaction log entries. Low CPU overhead. Used by actual Quicksilver. |
| Serialization | Jackson | JSON for REST API and gossip protocol messages. |
| Logging | SLF4J + Logback | Standard Java logging stack. |

## Design Decisions

### Why RocksDB Over LMDB?

Quicksilver v1 used LMDB (Lightning Memory-Mapped Database) for its exceptional read latency. However, LMDB's copy-on-write semantics caused severe write amplification (up to 80x observed). Quicksilver v1.5+ migrated to RocksDB, which stores the same data in 40% of the space with much lower write amplification. Configd uses RocksDB with:

- **Default column family** for current values (latest version of each key)
- **MVCC column family** for historical versions (keyed as `[key|0xFF|version]`)
- **Meta column family** for internal metadata (current version counter)
- **Bloom filters** (10 bits/key) on both column families for fast negative lookups

### Why Pull-Based Replication?

Push-based replication requires the upstream to track all downstream consumers and manage retries. Pull-based replication is simpler: each node only needs to remember its own last-seen sequence number. It naturally handles:
- Nodes going offline and catching up later
- New nodes bootstrapping from any point in the log
- Load balancing across multiple upstreams

### Why 1024 Logical Shards?

1024 is a power of 2 that provides fine-grained distribution while keeping the shard map small. When doubling the sharding factor, each physical shard splits evenly. Servers automatically receive a subset of their previous shards without data migration.

## Project Structure

```
src/
  main/java/com/aayushatharva/configd/
    ConfigdServer.java          Main entry point, multi-instance bootstrap
    ConfigdInstance.java        Single instance lifecycle and message routing
    ConfigdConfig.java          Configuration (JSON file or CLI args)
    store/                          Storage engine (RocksDB + MVCC)
    txlog/                          Transaction log (Snappy + CRC)
    replication/                    Pull-based hierarchical replication
    cache/                          Three-tier caching system
    shard/                          1024-shard hash partitioning
    discovery/                      Gossip-based node discovery
    network/                        Netty server/client + wire protocol
    api/                            HTTP REST API
    node/                           Node roles and identity
  main/resources/
    logback.xml                     Logging configuration
  test/java/com/aayushatharva/configd/
    store/                          RocksDB, MVCC, Bloom filter tests
    txlog/                          Transaction log serialization tests
    replication/                    Sliding window tests
    cache/                          LRU cache tests
    shard/                          Hash partitioning tests
    integration/                    Multi-node integration tests
```

## References

- [Quicksilver v2: Evolution of a Globally Distributed Key-Value Store (Part 1)](https://blog.cloudflare.com/quicksilver-v2-evolution-of-a-globally-distributed-key-value-store-part-1/)
- [Quicksilver v2: Evolution of a Globally Distributed Key-Value Store (Part 2)](https://blog.cloudflare.com/quicksilver-v2-evolution-of-a-globally-distributed-key-value-store-part-2-of-2/)
- [Introducing Quicksilver: Configuration Distribution at Internet Scale](https://blog.cloudflare.com/introducing-quicksilver-configuration-distribution-at-internet-scale/)
- [Moving Quicksilver into Production](https://blog.cloudflare.com/moving-quicksilver-into-production/)

## License

This is an educational implementation for learning purposes. Cloudflare's Quicksilver is proprietary software; this project is an independent implementation based on their published engineering blog posts.

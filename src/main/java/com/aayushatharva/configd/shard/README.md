# Sharding

Configd v2 divides the key space into 1024 logical shards using hash-based range partitioning. This enables the data-center-wide sharded cache (L2) and supports horizontal scaling without data migration.

## Architecture

```
  Key: "dns:example.com"
       |
       v
  Murmur3-128 hash
       |
       v
  hash mod 1024 = logical shard 417
       |
       v
  logical shard 417 mod (1024 / shardingFactor) = physical shard N
       |
       v
  Physical shard N -> assigned to node "proxy-dc1-3"
       |
       v
  Route request to proxy-dc1-3
```

## Components

### HashPartitioner

Computes shard assignments using Guava's Murmur3-128 hash function:

- `getLogicalShard(key)` -- maps a key to one of 1024 logical shards. Uses the lower 63 bits of the hash modulo 1024 to ensure non-negative results.
- `getPhysicalShard(logicalShard, shardingFactor)` -- maps a logical shard to a physical shard. Physical shard count = `logicalShardCount / shardingFactor`.

**Why Murmur3?** Fast, well-distributed, and deterministic across platforms. The 128-bit variant provides excellent collision resistance across billions of keys.

**Why 1024?** It's a power of 2, which means:
- Modulo operations are efficient (bit masking)
- When doubling the sharding factor, each physical shard splits evenly in half
- 1024 provides enough granularity for fine-grained load balancing without excessive shard map overhead

### ShardManager

Manages the assignment of physical shards to nodes and routes key lookups:

- `assignShards(node, physicalShards)` -- assigns a set of physical shards to a node
- `routeKey(key)` -- hashes a key and returns the node responsible for its shard
- `getPhysicalShardForKey(key)` -- returns the physical shard ID for a key
- `isLocalShard(nodeId, key)` -- checks if a key belongs to this node's shards
- `rebalance(nodes)` -- evenly distributes all physical shards across a list of nodes
- `getShardsForNode(nodeId)` -- returns which shards a node owns

## Scaling: Doubling the Sharding Factor

When capacity needs increase, the sharding factor is doubled:

```
  Sharding Factor 1: 1024 physical shards
  Sharding Factor 2:  512 physical shards (each covers 2 logical shards)
  Sharding Factor 4:  256 physical shards (each covers 4 logical shards)
```

When a server's sharding factor doubles, it receives a new **subset** of its previous shard range. The superset data remains in the cache and is evicted naturally over time. No bulk data migration is needed.

Example:
```
  Before (factor=1): Node A owns physical shards [0, 1, 2, 3]
  After  (factor=2): Node A owns physical shards [0, 1]
                     Node B owns physical shards [2, 3]

  Logical shards 0-3 previously all mapped to Node A.
  Now logical shard 0 -> physical 0 (Node A)
       logical shard 1 -> physical 1 (Node A)
       logical shard 2 -> physical 0 (Node A) -- still on A, but via different physical shard
       logical shard 3 -> physical 1 (Node A) -- still on A
```

The key insight: doubling the factor halves the physical shard count. Each old physical shard becomes two new ones. Nodes that previously owned the superset naturally cache the subset data already.

## Rebalancing

The `rebalance(nodes)` method distributes physical shards evenly:

```
  1024 shards / 4 nodes = 256 shards each
  Remainder shards are distributed to the first nodes.

  Node 1: shards [0, 255]
  Node 2: shards [256, 511]
  Node 3: shards [512, 767]
  Node 4: shards [768, 1023]
```

This is called when nodes join or leave the data center. The gossip protocol detects membership changes and triggers rebalancing.

## Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| logicalShardCount | 1024 | Number of logical shards (must be power of 2) |
| shardingFactor | 1 | Controls physical shard count (logical / factor) |

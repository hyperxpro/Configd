package com.aayushatharva.configd.shard;

import com.google.common.hash.Hashing;

/**
 * Hash-based range partitioner for Configd v2's 1024 logical shards.
 *
 * Uses Murmur3-128 for consistent, well-distributed key hashing.
 * Logical shards are mapped to physical shards based on the sharding factor.
 */
public class HashPartitioner {

    private final int logicalShardCount;

    public HashPartitioner(int logicalShardCount) {
        if (logicalShardCount <= 0 || (logicalShardCount & (logicalShardCount - 1)) != 0) {
            throw new IllegalArgumentException(
                    "Logical shard count must be a positive power of 2, got: " + logicalShardCount);
        }
        this.logicalShardCount = logicalShardCount;
    }

    /**
     * Compute the logical shard for a given key.
     * Returns a value in [0, logicalShardCount).
     */
    public int getLogicalShard(byte[] key) {
        @SuppressWarnings("deprecation")
        long hash = Hashing.murmur3_128().hashBytes(key).asLong();
        return (int) ((hash & 0x7FFFFFFFFFFFFFFFL) % logicalShardCount);
    }

    /**
     * Map a logical shard to a physical shard given the sharding factor.
     *
     * When the sharding factor doubles, each physical shard covers half as many
     * logical shards. This allows servers to automatically receive new subset
     * shards while retaining superset caches.
     */
    public int getPhysicalShard(int logicalShard, int shardingFactor) {
        int physicalShardCount = logicalShardCount / shardingFactor;
        return logicalShard % physicalShardCount;
    }

    public int getLogicalShardCount() {
        return logicalShardCount;
    }
}

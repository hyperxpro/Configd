package com.aayushatharva.configd.shard;

import com.aayushatharva.configd.node.NodeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages shard assignment and routing for Configd v2.
 *
 * The key-space is divided into 1024 logical shards using hash-based range
 * partitioning. These map to physical shards distributed by server hostname.
 * Each server maintains one physical shard encompassing a range of logical shards.
 *
 * When doubling the sharding factor, the number of physical shards increases.
 * Servers automatically receive a new subset of their previous shards while
 * retaining superset caches, allowing eviction of obsolete entries without
 * data migration.
 */
public class ShardManager {

    private static final Logger log = LoggerFactory.getLogger(ShardManager.class);

    private final HashPartitioner partitioner;
    private final int shardingFactor;

    /** Maps physical shard ID -> assigned node */
    private final ConcurrentHashMap<Integer, NodeInfo> shardAssignment = new ConcurrentHashMap<>();

    /** Maps node ID -> set of physical shards it owns */
    private final ConcurrentHashMap<String, Set<Integer>> nodeShards = new ConcurrentHashMap<>();

    public ShardManager(int logicalShardCount, int shardingFactor) {
        this.partitioner = new HashPartitioner(logicalShardCount);
        this.shardingFactor = shardingFactor;
    }

    /**
     * Assign physical shards to a node.
     * Called during node registration or rebalancing.
     */
    public void assignShards(NodeInfo node, Set<Integer> physicalShards) {
        for (int shard : physicalShards) {
            shardAssignment.put(shard, node);
        }
        nodeShards.put(node.nodeId(), physicalShards);
        log.info("Assigned shards {} to node {}", physicalShards, node.nodeId());
    }

    /**
     * Route a key to the node responsible for its shard.
     */
    public NodeInfo routeKey(byte[] key) {
        int logicalShard = partitioner.getLogicalShard(key);
        int physicalShard = partitioner.getPhysicalShard(logicalShard, shardingFactor);
        return shardAssignment.get(physicalShard);
    }

    /**
     * Get the physical shard ID for a key.
     */
    public int getPhysicalShardForKey(byte[] key) {
        int logicalShard = partitioner.getLogicalShard(key);
        return partitioner.getPhysicalShard(logicalShard, shardingFactor);
    }

    /**
     * Get all physical shards owned by a node.
     */
    public Set<Integer> getShardsForNode(String nodeId) {
        return nodeShards.getOrDefault(nodeId, Set.of());
    }

    /**
     * Check if this node owns the shard for the given key.
     */
    public boolean isLocalShard(String nodeId, byte[] key) {
        int physicalShard = getPhysicalShardForKey(key);
        var shards = nodeShards.get(nodeId);
        return shards != null && shards.contains(physicalShard);
    }

    /**
     * Rebalance shards across a set of nodes (even distribution).
     */
    public void rebalance(List<NodeInfo> nodes) {
        if (nodes.isEmpty()) return;

        int physicalShardCount = partitioner.getLogicalShardCount() / shardingFactor;
        int shardsPerNode = physicalShardCount / nodes.size();
        int remainder = physicalShardCount % nodes.size();

        // Clear existing assignments
        shardAssignment.clear();
        nodeShards.clear();

        int shardIdx = 0;
        for (int i = 0; i < nodes.size(); i++) {
            var node = nodes.get(i);
            int count = shardsPerNode + (i < remainder ? 1 : 0);
            var shards = new HashSet<Integer>();
            for (int j = 0; j < count; j++) {
                shards.add(shardIdx++);
            }
            assignShards(node, shards);
        }

        log.info("Rebalanced {} physical shards across {} nodes", physicalShardCount, nodes.size());
    }

    public int getPhysicalShardCount() {
        return partitioner.getLogicalShardCount() / shardingFactor;
    }

    public HashPartitioner getPartitioner() {
        return partitioner;
    }

    public int getShardingFactor() {
        return shardingFactor;
    }

    public Map<Integer, NodeInfo> getShardAssignment() {
        return Collections.unmodifiableMap(shardAssignment);
    }
}

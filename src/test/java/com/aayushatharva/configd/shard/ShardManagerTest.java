package com.aayushatharva.configd.shard;

import com.aayushatharva.configd.node.NodeInfo;
import com.aayushatharva.configd.node.NodeMode;
import com.aayushatharva.configd.node.NodeRole;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for hash partitioning and shard management.
 */
class ShardManagerTest {

    @Test
    void logicalShardDistribution() {
        var partitioner = new HashPartitioner(1024);

        // Verify all keys map to valid shard range
        var shards = new HashSet<Integer>();
        for (int i = 0; i < 10000; i++) {
            int shard = partitioner.getLogicalShard(("key-" + i).getBytes(StandardCharsets.UTF_8));
            assertThat(shard).isBetween(0, 1023);
            shards.add(shard);
        }

        // Should have reasonable distribution across shards
        assertThat(shards.size()).isGreaterThan(500);
    }

    @Test
    void physicalShardMapping() {
        var partitioner = new HashPartitioner(1024);

        // With sharding factor 1, physical == logical count
        int physical = partitioner.getPhysicalShard(500, 1);
        assertThat(physical).isBetween(0, 1023);

        // With sharding factor 2, physical shard count halves
        int physical2 = partitioner.getPhysicalShard(500, 2);
        assertThat(physical2).isBetween(0, 511);

        // With sharding factor 4, physical shard count quarters
        int physical4 = partitioner.getPhysicalShard(500, 4);
        assertThat(physical4).isBetween(0, 255);
    }

    @Test
    void shardRebalancing() {
        var manager = new ShardManager(1024, 1);

        var nodes = List.of(
                node("node-1"), node("node-2"), node("node-3"), node("node-4")
        );

        manager.rebalance(nodes);

        // Each node should have roughly equal shards
        for (var nodeInfo : nodes) {
            Set<Integer> shards = manager.getShardsForNode(nodeInfo.nodeId());
            assertThat(shards.size()).isBetween(255, 257); // 1024/4 = 256 ± 1
        }

        // Every physical shard should be assigned
        assertThat(manager.getShardAssignment()).hasSize(1024);
    }

    @Test
    void keyRouting() {
        var manager = new ShardManager(1024, 1);
        var node1 = node("node-1");
        manager.assignShards(node1, Set.of(0, 1, 2, 3, 4));

        // Keys that map to shards 0-4 should route to node-1
        byte[] key = "test".getBytes();
        int shard = manager.getPhysicalShardForKey(key);
        if (shard >= 0 && shard <= 4) {
            NodeInfo routed = manager.routeKey(key);
            assertThat(routed.nodeId()).isEqualTo("node-1");
        }
    }

    @Test
    void isLocalShard() {
        var manager = new ShardManager(1024, 1);
        var node1 = node("node-1");
        manager.assignShards(node1, Set.of(42));

        // A key that maps to shard 42 should be local
        // Find a key that maps to shard 42
        for (int i = 0; i < 100000; i++) {
            byte[] key = ("k" + i).getBytes();
            if (manager.getPhysicalShardForKey(key) == 42) {
                assertThat(manager.isLocalShard("node-1", key)).isTrue();
                break;
            }
        }
    }

    @Test
    void doublingShardingFactor() {
        // When sharding factor doubles, physical shard count halves
        var manager1 = new ShardManager(1024, 1);
        assertThat(manager1.getPhysicalShardCount()).isEqualTo(1024);

        var manager2 = new ShardManager(1024, 2);
        assertThat(manager2.getPhysicalShardCount()).isEqualTo(512);

        var manager4 = new ShardManager(1024, 4);
        assertThat(manager4.getPhysicalShardCount()).isEqualTo(256);
    }

    private static NodeInfo node(String id) {
        return new NodeInfo(id, "127.0.0.1", 7400, 7401,
                NodeRole.LEAF, NodeMode.PROXY, "dc1", "us-east");
    }
}

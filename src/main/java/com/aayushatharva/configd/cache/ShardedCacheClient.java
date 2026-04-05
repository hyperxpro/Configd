package com.aayushatharva.configd.cache;

import com.aayushatharva.configd.network.ConnectionPool;
import com.aayushatharva.configd.network.NetworkClient;
import com.aayushatharva.configd.network.protocol.Message;
import com.aayushatharva.configd.network.protocol.MessageType;
import com.aayushatharva.configd.node.NodeInfo;
import com.aayushatharva.configd.shard.ShardManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Level 2 cache client: data-center-wide sharded cache.
 *
 * Distributed across multiple servers within each data center.
 * Shares key-space through the shard manager. Contains keys accessed
 * previously but not recently (between L1 eviction and L3 lookup).
 *
 * A cache miss at L2 is forwarded through relays to L3 (full replicas).
 */
public class ShardedCacheClient {

    private static final Logger log = LoggerFactory.getLogger(ShardedCacheClient.class);

    private final ShardManager shardManager;
    private final ConnectionPool connectionPool;
    private final String localNodeId;

    public ShardedCacheClient(ShardManager shardManager, ConnectionPool connectionPool,
                              String localNodeId) {
        this.shardManager = shardManager;
        this.connectionPool = connectionPool;
        this.localNodeId = localNodeId;
    }

    /**
     * Look up a key in the sharded cache (L2).
     * Routes the request to the node owning the shard for this key.
     */
    public CompletableFuture<byte[]> get(byte[] key) {
        NodeInfo targetNode = shardManager.routeKey(key);
        if (targetNode == null) {
            return CompletableFuture.completedFuture(null);
        }

        // If the shard is local, this would be handled by the local shard store
        if (targetNode.nodeId().equals(localNodeId)) {
            return CompletableFuture.completedFuture(null); // Handled locally
        }

        try {
            var client = connectionPool.getOrReconnect(targetNode.host(), targetNode.internalPort());
            byte[] payload = buildLookupPayload(key);
            var request = new Message(
                    MessageType.CACHE_LOOKUP_REQUEST,
                    client.nextRequestId(),
                    payload
            );

            return client.send(request)
                    .thenApply(response -> parseLookupResponse(response.payload()))
                    .orTimeout(2, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        log.debug("L2 cache lookup failed for shard on {}: {}",
                                targetNode.nodeId(), ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            log.debug("Failed to connect to shard node {}", targetNode.nodeId(), e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Store a key-value pair in the sharded cache.
     */
    public CompletableFuture<Void> put(byte[] key, byte[] value, long version) {
        NodeInfo targetNode = shardManager.routeKey(key);
        if (targetNode == null || targetNode.nodeId().equals(localNodeId)) {
            return CompletableFuture.completedFuture(null);
        }

        try {
            var client = connectionPool.getOrReconnect(targetNode.host(), targetNode.internalPort());
            byte[] payload = buildStorePayload(key, value, version);
            var request = new Message(
                    MessageType.CACHE_STORE_REQUEST,
                    client.nextRequestId(),
                    payload
            );

            client.sendOneWay(request);
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            log.debug("Failed to store in L2 cache on {}", targetNode.nodeId(), e);
            return CompletableFuture.completedFuture(null);
        }
    }

    private byte[] buildLookupPayload(byte[] key) {
        ByteBuffer buf = ByteBuffer.allocate(4 + key.length);
        buf.putInt(key.length);
        buf.put(key);
        return buf.array();
    }

    private byte[] parseLookupResponse(byte[] payload) {
        if (payload == null || payload.length == 0) return null;
        ByteBuffer buf = ByteBuffer.wrap(payload);
        int valueLen = buf.getInt();
        if (valueLen <= 0) return null;
        byte[] value = new byte[valueLen];
        buf.get(value);
        return value;
    }

    private byte[] buildStorePayload(byte[] key, byte[] value, long version) {
        int valueLen = (value != null) ? value.length : 0;
        ByteBuffer buf = ByteBuffer.allocate(4 + key.length + 4 + valueLen + 8);
        buf.putInt(key.length);
        buf.put(key);
        buf.putInt(valueLen);
        if (valueLen > 0) buf.put(value);
        buf.putLong(version);
        return buf.array();
    }
}

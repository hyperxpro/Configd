package com.aayushatharva.configd;

import com.aayushatharva.configd.api.RestApiServer;
import com.aayushatharva.configd.cache.CacheManager;
import com.aayushatharva.configd.discovery.GossipProtocol;
import com.aayushatharva.configd.network.ConnectionPool;
import com.aayushatharva.configd.network.NetworkServer;
import com.aayushatharva.configd.network.protocol.Message;
import com.aayushatharva.configd.network.protocol.MessageType;
import com.aayushatharva.configd.node.NodeInfo;
import com.aayushatharva.configd.node.NodeMode;
import com.aayushatharva.configd.replication.ReplicationManager;
import com.aayushatharva.configd.shard.ShardManager;
import com.aayushatharva.configd.store.KVStore;
import com.aayushatharva.configd.store.KeyIndex;
import com.aayushatharva.configd.store.RocksDBStore;
import com.aayushatharva.configd.txlog.BatchingTransactionLogWriter;
import com.aayushatharva.configd.txlog.RocksDBTransactionLog;
import com.aayushatharva.configd.txlog.TransactionLog;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A single Configd instance.
 *
 * Cloudflare runs 10 Configd instances per server (for DNS, CDN, WAF, etc.).
 * Each instance operates independently with its own storage, transaction log,
 * replication, and cache.
 */
public class ConfigdInstance implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(ConfigdInstance.class);

    private final String instanceId;
    private final ConfigdConfig config;

    // Core components
    private KVStore store;
    private TransactionLog txLog;
    private BatchingTransactionLogWriter writer;
    private ReplicationManager replicationManager;
    private CacheManager cacheManager;
    private ShardManager shardManager;
    private KeyIndex keyIndex;

    // Network
    private NetworkServer networkServer;
    private ConnectionPool connectionPool;

    // API
    private RestApiServer apiServer;

    // Discovery
    private GossipProtocol gossip;

    public ConfigdInstance(String instanceId, ConfigdConfig config) {
        this.instanceId = instanceId;
        this.config = config;
    }

    public void start() throws Exception {
        log.info("Starting Configd instance '{}' as {}/{} in {}",
                instanceId, config.getRole(), config.getMode(), config.getDataCenter());

        // Create data directories
        Path dataDir = Path.of(config.getDataDir(), instanceId);
        Path txLogDir = Path.of(config.getTxLogDir(), instanceId);
        Files.createDirectories(dataDir);
        Files.createDirectories(txLogDir);

        // Initialize storage
        store = new RocksDBStore(dataDir);
        txLog = new RocksDBTransactionLog(txLogDir);
        writer = new BatchingTransactionLogWriter(txLog, store, config.getBatchWriteIntervalMs());

        // Key index for proxy nodes (Bloom filter for negative lookups)
        if (config.getMode() == NodeMode.PROXY) {
            keyIndex = new KeyIndex();
        }

        // Sharding
        shardManager = new ShardManager(config.getLogicalShardCount(), config.getShardingFactor());

        // Connection pool
        connectionPool = new ConnectionPool();

        // Cache manager (three-tier)
        cacheManager = new CacheManager(config, store, shardManager, connectionPool);

        // Replication
        replicationManager = new ReplicationManager(config, store, txLog, connectionPool);
        replicationManager.start();

        // Register prefetcher with replication puller
        if (replicationManager.getPuller() != null) {
            replicationManager.getPuller().addListener(cacheManager.getPrefetcher());
        }

        // Internal network server
        networkServer = new NetworkServer(config.getInternalPort(), this::handleInternalMessage);
        networkServer.start();

        // Discovery (gossip)
        var selfInfo = new NodeInfo(
                config.getNodeId(), config.getHost(),
                config.getInternalPort(), config.getApiPort(),
                config.getRole(), config.getMode(),
                config.getDataCenter(), config.getRegion()
        );
        gossip = new GossipProtocol(selfInfo, config.getGossipPort(),
                config.getGossipIntervalMs(), config.getGossipSeeds());
        gossip.start();

        // REST API
        apiServer = new RestApiServer(config.getApiPort(), store, cacheManager,
                writer, replicationManager, config);
        apiServer.start();

        log.info("Configd instance '{}' started successfully", instanceId);
    }

    /**
     * Handle incoming internal protocol messages.
     * Routes messages to the appropriate handler based on type.
     */
    private void handleInternalMessage(ChannelHandlerContext ctx, Message msg) {
        switch (msg.type()) {
            // Replication
            case REPLICATION_PULL_REQUEST ->
                    replicationManager.handleMessage(ctx, msg);

            // Cache operations (L2 sharded cache serving)
            case CACHE_LOOKUP_REQUEST ->
                    handleCacheLookup(ctx, msg);
            case CACHE_STORE_REQUEST ->
                    handleCacheStore(msg);

            // Relay operations
            case RELAY_GET_REQUEST ->
                    handleRelayGet(ctx, msg);

            // Prefetch notifications
            case PREFETCH_NOTIFY ->
                    cacheManager.getPrefetcher().handlePrefetchNotify(msg);

            // Health checks
            case HEALTH_CHECK ->
                    ctx.writeAndFlush(new Message(MessageType.HEALTH_RESPONSE, msg.requestId(), null));

            default ->
                    log.warn("Unknown message type: {}", msg.type());
        }
    }

    /** Handle L2 sharded cache lookup. */
    private void handleCacheLookup(ChannelHandlerContext ctx, Message msg) {
        ByteBuffer buf = ByteBuffer.wrap(msg.payload());
        int keyLen = buf.getInt();
        byte[] key = new byte[keyLen];
        buf.get(key);

        byte[] value = store.get(key);

        byte[] response;
        if (value != null) {
            response = ByteBuffer.allocate(4 + value.length)
                    .putInt(value.length).put(value).array();
        } else {
            response = ByteBuffer.allocate(4).putInt(0).array();
        }

        ctx.writeAndFlush(new Message(MessageType.CACHE_LOOKUP_RESPONSE, msg.requestId(), response));
    }

    /** Handle L2 cache store. */
    private void handleCacheStore(Message msg) {
        ByteBuffer buf = ByteBuffer.wrap(msg.payload());
        int keyLen = buf.getInt();
        byte[] key = new byte[keyLen];
        buf.get(key);
        int valueLen = buf.getInt();
        byte[] value = null;
        if (valueLen > 0) {
            value = new byte[valueLen];
            buf.get(value);
        }
        long version = buf.getLong();

        if (value != null) {
            store.put(key, value, version);
        }
    }

    /** Handle relay GET: fetch from full replica on behalf of a proxy. */
    private void handleRelayGet(ChannelHandlerContext ctx, Message msg) {
        ByteBuffer buf = ByteBuffer.wrap(msg.payload());
        int keyLen = buf.getInt();
        byte[] key = new byte[keyLen];
        buf.get(key);

        byte[] value = store.get(key);

        byte[] response;
        if (value != null) {
            response = ByteBuffer.allocate(4 + value.length)
                    .putInt(value.length).put(value).array();
        } else {
            response = ByteBuffer.allocate(4).putInt(0).array();
        }

        ctx.writeAndFlush(new Message(MessageType.RELAY_GET_RESPONSE, msg.requestId(), response));
    }

    // --- Accessors ---

    public String getInstanceId() { return instanceId; }
    public KVStore getStore() { return store; }
    public TransactionLog getTransactionLog() { return txLog; }
    public BatchingTransactionLogWriter getWriter() { return writer; }
    public CacheManager getCacheManager() { return cacheManager; }
    public ReplicationManager getReplicationManager() { return replicationManager; }
    public ShardManager getShardManager() { return shardManager; }
    public GossipProtocol getGossip() { return gossip; }
    public ConfigdConfig getConfig() { return config; }

    @Override
    public void close() throws IOException {
        log.info("Shutting down Configd instance '{}'", instanceId);
        if (apiServer != null) apiServer.close();
        if (gossip != null) gossip.close();
        if (networkServer != null) networkServer.close();
        if (replicationManager != null) replicationManager.close();
        if (cacheManager != null) cacheManager.close();
        if (writer != null) writer.close();
        if (txLog != null) txLog.close();
        if (store != null) store.close();
        if (connectionPool != null) connectionPool.close();
        log.info("Configd instance '{}' shut down", instanceId);
    }
}

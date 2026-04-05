package com.aayushatharva.configd.replication;

import com.aayushatharva.configd.ConfigdConfig;
import com.aayushatharva.configd.network.ConnectionPool;
import com.aayushatharva.configd.network.protocol.Message;
import com.aayushatharva.configd.network.protocol.MessageType;
import com.aayushatharva.configd.node.NodeMode;
import com.aayushatharva.configd.store.KVStore;
import com.aayushatharva.configd.txlog.TransactionLog;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates replication for a Configd node.
 *
 * Based on the node's role:
 * - ROOT: only serves replication (no upstream)
 * - INTERMEDIATE/LEAF REPLICA: pulls from upstream AND serves to downstream
 * - RELAY: pulls from replicas within its data center
 * - PROXY: pulls from relays
 *
 * Replication topology is hierarchical fan-out:
 * ROOT -> INTERMEDIATE -> LEAF replicas -> relays -> proxies
 */
public class ReplicationManager implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(ReplicationManager.class);

    private final ConfigdConfig config;
    private final KVStore store;
    private final TransactionLog txLog;
    private final ConnectionPool connectionPool;

    private ReplicationPuller puller;
    private ReplicationServer server;
    private SlidingWindow slidingWindow;

    public ReplicationManager(ConfigdConfig config, KVStore store,
                              TransactionLog txLog, ConnectionPool connectionPool) {
        this.config = config;
        this.store = store;
        this.txLog = txLog;
        this.connectionPool = connectionPool;
    }

    public void start() {
        // All nodes serve replication to downstream
        this.server = new ReplicationServer(txLog);

        // Proxies use a sliding window for consistency
        if (config.getMode() == NodeMode.PROXY) {
            this.slidingWindow = new SlidingWindow(config.getSlidingWindowDurationMs());
        }

        // Non-ROOT nodes pull from upstream
        if (!config.getUpstreamNodes().isEmpty()) {
            var upstreams = parseUpstreams(config.getUpstreamNodes());
            this.puller = new ReplicationPuller(
                    store, connectionPool, upstreams,
                    config.getReplicationPullIntervalMs(),
                    config.getReplicationBatchSize(),
                    config.getStalenessThresholdMs()
            );

            // If we have a sliding window, register it as a listener
            if (slidingWindow != null) {
                puller.addListener(entries -> {
                    for (var entry : entries) {
                        slidingWindow.add(entry.key(), entry.value(), entry.sequenceNumber());
                    }
                });
            }

            puller.start();
        }

        log.info("Replication manager started: mode={}, upstreams={}",
                config.getMode(), config.getUpstreamNodes().size());
    }

    /** Handle incoming replication messages from the network server. */
    public void handleMessage(ChannelHandlerContext ctx, Message msg) {
        switch (msg.type()) {
            case REPLICATION_PULL_REQUEST -> server.handlePullRequest(ctx, msg);
            default -> log.warn("Unexpected replication message type: {}", msg.type());
        }
    }

    public ReplicationServer getServer() {
        return server;
    }

    public ReplicationPuller getPuller() {
        return puller;
    }

    public SlidingWindow getSlidingWindow() {
        return slidingWindow;
    }

    public Map<String, ReplicationState> getDownstreamStates() {
        return server != null ? server.getDownstreamStates() : Map.of();
    }

    private List<ReplicationPuller.UpstreamEndpoint> parseUpstreams(List<String> addresses) {
        var result = new ArrayList<ReplicationPuller.UpstreamEndpoint>();
        for (String addr : addresses) {
            String[] parts = addr.split(":");
            result.add(new ReplicationPuller.UpstreamEndpoint(parts[0], Integer.parseInt(parts[1])));
        }
        return result;
    }

    @Override
    public void close() {
        if (puller != null) puller.close();
        log.info("Replication manager stopped");
    }
}

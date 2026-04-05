package com.aayushatharva.configd.replication;

import com.aayushatharva.configd.network.ConnectionPool;
import com.aayushatharva.configd.network.NetworkClient;
import com.aayushatharva.configd.network.protocol.Message;
import com.aayushatharva.configd.network.protocol.MessageType;
import com.aayushatharva.configd.store.KVStore;
import com.aayushatharva.configd.txlog.Operation;
import com.aayushatharva.configd.txlog.TransactionLogEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/**
 * Pulls transaction log entries from upstream nodes.
 *
 * Implements Configd's pull-based replication protocol:
 * 1. Send last-known sequence number to upstream
 * 2. Receive entries after that sequence
 * 3. Apply entries to local store
 * 4. Update local replication state
 *
 * If lag exceeds the staleness threshold (30 seconds), disconnects and
 * reconnects to a different upstream node.
 */
public class ReplicationPuller implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(ReplicationPuller.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KVStore store;
    private final ConnectionPool connectionPool;
    private final List<UpstreamEndpoint> upstreams;
    private final long pullIntervalMs;
    private final int batchSize;
    private final long stalenessThresholdMs;
    private final ScheduledExecutorService scheduler;
    private final ReplicationState localState;
    private final List<ReplicationListener> listeners = new CopyOnWriteArrayList<>();

    private volatile int currentUpstreamIndex = 0;

    public record UpstreamEndpoint(String host, int port) {}

    public interface ReplicationListener {
        void onEntriesApplied(List<TransactionLogEntry> entries);
    }

    public ReplicationPuller(KVStore store, ConnectionPool connectionPool,
                             List<UpstreamEndpoint> upstreams,
                             long pullIntervalMs, int batchSize, long stalenessThresholdMs) {
        this.store = store;
        this.connectionPool = connectionPool;
        this.upstreams = new ArrayList<>(upstreams);
        this.pullIntervalMs = pullIntervalMs;
        this.batchSize = batchSize;
        this.stalenessThresholdMs = stalenessThresholdMs;
        this.localState = new ReplicationState(store.getCurrentVersion());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "cd-replication-puller");
            t.setDaemon(true);
            return t;
        });
    }

    public void addListener(ReplicationListener listener) {
        listeners.add(listener);
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::pullCycle, 0, pullIntervalMs, TimeUnit.MILLISECONDS);
        log.info("Replication puller started, pulling every {}ms from {} upstream(s)",
                pullIntervalMs, upstreams.size());
    }

    private void pullCycle() {
        if (upstreams.isEmpty()) return;

        try {
            var upstream = upstreams.get(currentUpstreamIndex % upstreams.size());
            var client = connectionPool.getOrReconnect(upstream.host(), upstream.port());

            // Build pull request: send last known sequence
            long lastSeq = localState.getLastAcknowledgedSequence();
            byte[] payload = buildPullRequestPayload(lastSeq, batchSize);

            var request = new Message(
                    MessageType.REPLICATION_PULL_REQUEST,
                    client.nextRequestId(),
                    payload
            );

            Message response = client.send(request).get(5, TimeUnit.SECONDS);
            var entries = parsePullResponse(response.payload());

            if (!entries.isEmpty()) {
                applyEntries(entries);
                long newSeq = entries.get(entries.size() - 1).sequenceNumber();
                localState.setLastAcknowledgedSequence(newSeq);
                log.debug("Applied {} entries, version now {}", entries.size(), newSeq);

                // Notify listeners (e.g., reactive prefetcher)
                for (var listener : listeners) {
                    listener.onEntriesApplied(entries);
                }
            }

            // Check staleness
            if (localState.lagMs() > stalenessThresholdMs && entries.isEmpty()) {
                log.warn("Upstream {}:{} appears stale (lag={}ms), rotating",
                        upstream.host(), upstream.port(), localState.lagMs());
                currentUpstreamIndex++;
            }

        } catch (Exception e) {
            log.error("Replication pull failed, will retry", e);
            currentUpstreamIndex++;
        }
    }

    private void applyEntries(List<TransactionLogEntry> entries) {
        for (var entry : entries) {
            switch (entry.operation()) {
                case SET -> store.put(entry.key(), entry.value(), entry.sequenceNumber());
                case DELETE -> store.delete(entry.key(), entry.sequenceNumber());
            }
        }
        store.setCurrentVersion(entries.get(entries.size() - 1).sequenceNumber());
    }

    private byte[] buildPullRequestPayload(long afterSequence, int limit) {
        // Simple binary: [8 bytes afterSeq][4 bytes limit]
        ByteBuffer buf = ByteBuffer.allocate(12);
        buf.putLong(afterSequence);
        buf.putInt(limit);
        return buf.array();
    }

    /** Parse the replication response payload containing serialized TransactionLogEntry list. */
    private List<TransactionLogEntry> parsePullResponse(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return List.of();
        }
        var entries = new ArrayList<TransactionLogEntry>();
        ByteBuffer buf = ByteBuffer.wrap(payload);

        int count = buf.getInt();
        for (int i = 0; i < count; i++) {
            int entryLen = buf.getInt();
            byte[] entryData = new byte[entryLen];
            buf.get(entryData);
            entries.add(TransactionLogEntry.deserialize(entryData));
        }
        return entries;
    }

    public ReplicationState getLocalState() {
        return localState;
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

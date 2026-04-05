package com.aayushatharva.configd.replication;

import com.aayushatharva.configd.network.protocol.Message;
import com.aayushatharva.configd.network.protocol.MessageType;
import com.aayushatharva.configd.txlog.TransactionLog;
import com.aayushatharva.configd.txlog.TransactionLogEntry;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves transaction log entries to downstream nodes (replication server side).
 *
 * When a downstream node sends a REPLICATION_PULL_REQUEST with its last-known
 * sequence number, this server responds with all entries after that sequence.
 *
 * Tracks per-downstream replication state for monitoring lag and health.
 */
public class ReplicationServer {

    private static final Logger log = LoggerFactory.getLogger(ReplicationServer.class);

    private final TransactionLog txLog;
    private final ConcurrentHashMap<String, ReplicationState> downstreamStates = new ConcurrentHashMap<>();

    public ReplicationServer(TransactionLog txLog) {
        this.txLog = txLog;
    }

    /**
     * Handle a replication pull request from a downstream node.
     */
    public void handlePullRequest(ChannelHandlerContext ctx, Message request) {
        ByteBuffer payload = ByteBuffer.wrap(request.payload());
        long afterSequence = payload.getLong();
        int limit = payload.getInt();

        // Track downstream state
        String peerId = ctx.channel().remoteAddress().toString();
        downstreamStates.computeIfAbsent(peerId, k -> new ReplicationState(afterSequence))
                .setLastAcknowledgedSequence(afterSequence);

        // Read entries from transaction log
        List<TransactionLogEntry> entries = txLog.readAfter(afterSequence, limit);

        // Build response
        byte[] responsePayload = buildPullResponsePayload(entries);
        var response = new Message(
                MessageType.REPLICATION_PULL_RESPONSE,
                request.requestId(),
                responsePayload
        );

        ctx.writeAndFlush(response);

        if (!entries.isEmpty()) {
            log.debug("Served {} entries to {} (afterSeq={})", entries.size(), peerId, afterSequence);
        }
    }

    /**
     * Build the response payload:
     * [4 bytes count][for each entry: [4 bytes len][serialized entry]]
     */
    private byte[] buildPullResponsePayload(List<TransactionLogEntry> entries) {
        if (entries.isEmpty()) {
            return ByteBuffer.allocate(4).putInt(0).array();
        }

        // Calculate total size
        int totalSize = 4; // count
        var serializedEntries = new byte[entries.size()][];
        for (int i = 0; i < entries.size(); i++) {
            serializedEntries[i] = entries.get(i).serialize();
            totalSize += 4 + serializedEntries[i].length;
        }

        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.putInt(entries.size());
        for (byte[] entry : serializedEntries) {
            buf.putInt(entry.length);
            buf.put(entry);
        }
        return buf.array();
    }

    public ConcurrentHashMap<String, ReplicationState> getDownstreamStates() {
        return downstreamStates;
    }

    /** Get the latest sequence number available in this node's transaction log. */
    public long getLatestSequence() {
        return txLog.getLatestSequence();
    }
}

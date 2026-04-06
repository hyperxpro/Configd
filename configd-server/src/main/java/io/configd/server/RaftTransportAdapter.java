package io.configd.server;

import io.configd.common.NodeId;
import io.configd.raft.RaftMessage;
import io.configd.raft.RaftTransport;
import io.configd.transport.FrameCodec;
import io.configd.transport.TcpRaftTransport;

/**
 * Adapts {@link TcpRaftTransport} (transport module, uses {@code Object} messages)
 * to the consensus-core {@link RaftTransport} interface (uses {@link RaftMessage}).
 * <p>
 * Outbound: serializes {@link RaftMessage} to {@link FrameCodec.Frame} via
 * {@link RaftMessageCodec}, then delegates to the TCP transport.
 * <p>
 * Inbound: registers a handler on the TCP transport that deserializes
 * incoming frames back to {@link RaftMessage} and dispatches them to
 * the configured message consumer.
 * <p>
 * This class resolves CRITICAL-2: the interface mismatch between the
 * transport module's {@code RaftTransport} and consensus-core's
 * {@code RaftTransport}.
 */
public final class RaftTransportAdapter implements RaftTransport {

    private final TcpRaftTransport tcpTransport;
    private final int groupId;

    /**
     * Creates an adapter.
     *
     * @param tcpTransport the underlying TCP transport
     * @param groupId      the Raft group ID to use in frame headers
     */
    public RaftTransportAdapter(TcpRaftTransport tcpTransport, int groupId) {
        this.tcpTransport = tcpTransport;
        this.groupId = groupId;
    }

    @Override
    public void send(NodeId target, RaftMessage message) {
        // The encoder may throw IllegalArgumentException for oversized
        // messages (RaftMessageCodec.checkInstallSnapshotFitsFrame,
        // checkAppendEntriesFitsFrame, FrameCodec.checkPayloadFitsFrame).
        // We let it propagate up to RaftNode so the producer can skip
        // any in-flight bookkeeping it would otherwise have done for a
        // successful send — the transport adapter has no view of
        // inflightCount and cannot decide that itself.
        FrameCodec.Frame frame = RaftMessageCodec.encode(message, groupId);
        tcpTransport.send(target, frame);
    }

    /**
     * Registers a handler for inbound Raft messages on the TCP transport.
     * Incoming {@link FrameCodec.Frame} objects are decoded to
     * {@link RaftMessage} via {@link RaftMessageCodec} and dispatched
     * to the given consumer.
     *
     * @param handler consumer of decoded inbound messages
     */
    public void registerInboundHandler(java.util.function.BiConsumer<NodeId, RaftMessage> handler) {
        tcpTransport.registerHandler((from, rawMessage) -> {
            if (rawMessage instanceof FrameCodec.Frame frame) {
                try {
                    RaftMessage raftMessage = RaftMessageCodec.decode(frame);
                    handler.accept(from, raftMessage);
                } catch (Exception e) {
                    System.err.println("Failed to decode Raft message from " + from + ": " + e.getMessage());
                }
            }
        });
    }
}

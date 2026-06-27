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

    private final io.configd.transport.RaftTransport transport;
    private final int groupId;

    /**
     * Creates an adapter.
     *
     * @param transport the underlying transport-module transport — the JDK {@link TcpRaftTransport}
     *                  or the Netty {@code NettyRaftTransport} (M4 / DR-N20: the adapter depends only on
     *                  the {@code io.configd.transport.RaftTransport} send/registerHandler seam, so the
     *                  production cutover swaps the implementation without touching this class)
     * @param groupId   the Raft group ID to use in frame headers
     */
    public RaftTransportAdapter(io.configd.transport.RaftTransport transport, int groupId) {
        this.transport = transport;
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
        transport.send(target, frame);
    }

    /**
     * A consumer of a decoded inbound Raft message together with the Raft group it belongs to.
     *
     * <p>Multi-Raft Phase 1 (DL-P1-06): the {@code groupId} carried in every frame header (offset 6, no
     * wire-format change) is delivered to the handler so the server can DEMULTIPLEX inbound traffic to the
     * correct group ({@code driver.routeMessage(groupId, msg)} on {@code ownerExecutor(groupId)}), instead
     * of collapsing every frame onto a single captured group. At {@code N=1} only group 0 exists, so the
     * delivered {@code groupId} is always 0 and behaviour is byte-identical to the prior single-group path.
     */
    @FunctionalInterface
    public interface InboundHandler {
        /**
         * @param from    the sender node
         * @param groupId the Raft group the frame was stamped with ({@code frame.groupId()})
         * @param message the decoded Raft message
         */
        void accept(NodeId from, int groupId, RaftMessage message);
    }

    /**
     * Registers a handler for inbound Raft messages on the TCP transport.
     * Incoming {@link FrameCodec.Frame} objects are decoded to
     * {@link RaftMessage} via {@link RaftMessageCodec} and dispatched
     * to the given handler together with the frame's {@code groupId}, so a multi-group server can route
     * each message to its own group (Multi-Raft Phase 1, DL-P1-06; no wire-format change — the groupId is
     * already in the frame header).
     *
     * @param handler handler of decoded inbound messages, keyed by group id
     */
    public void registerInboundHandler(InboundHandler handler) {
        transport.registerHandler((from, rawMessage) -> {
            if (rawMessage instanceof FrameCodec.Frame frame) {
                try {
                    RaftMessage raftMessage = RaftMessageCodec.decode(frame);
                    handler.accept(from, frame.groupId(), raftMessage);
                } catch (Exception e) {
                    System.err.println("Failed to decode Raft message from " + from + ": " + e.getMessage());
                }
            }
        });
    }
}

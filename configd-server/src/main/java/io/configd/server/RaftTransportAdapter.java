package io.configd.server;

import io.configd.common.NodeId;
import io.configd.raft.AppendEntriesRequest;
import io.configd.raft.InstallSnapshotRequest;
import io.configd.raft.RaftMessage;
import io.configd.raft.RaftTransport;
import io.configd.raft.RequestVoteRequest;
import io.configd.raft.TimeoutNowRequest;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import io.configd.transport.RaftTransportMetrics;
import io.configd.transport.TcpRaftTransport;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

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
 * This class resolves the interface mismatch between the
 * transport module's {@code RaftTransport} and consensus-core's
 * {@code RaftTransport}.
 */
public final class RaftTransportAdapter implements RaftTransport {

    private static final Logger LOG = Logger.getLogger(RaftTransportAdapter.class.getName());

    /** Min interval between in-body-rejection log lines (ns). The metric is always incremented. */
    private static final long IDENTITY_LOG_INTERVAL_NANOS = 1_000_000_000L; // 1/sec

    private final io.configd.transport.RaftTransport transport;
    private final int groupId;
    /** Whether to enforce the in-body {@code leaderId}/{@code candidateId} binding (WH-08/09). */
    private final boolean enforceIdentity;
    /** Security-event sink for in-body identity rejections. */
    private final RaftTransportMetrics transportMetrics;
    /** Throttle state for the in-body-rejection log (a dropped-frame path an authenticated peer could flood). */
    private final AtomicLong identityLogLastNanos = new AtomicLong(0L);
    private final AtomicLong identityLogSuppressed = new AtomicLong(0L);

    /**
     * Creates an adapter with in-body identity binding disabled (legacy behaviour). Delegates to the
     * fuller constructor; kept so existing single-arg-plus-group call sites (and tests) are unchanged.
     *
     * @param transport the underlying transport-module transport - the JDK {@link TcpRaftTransport}
     *                  or the Netty {@code NettyRaftTransport}
     * @param groupId   the Raft group ID to use in frame headers
     */
    public RaftTransportAdapter(io.configd.transport.RaftTransport transport, int groupId) {
        this(transport, groupId, false, RaftTransportMetrics.NOOP);
    }

    /**
     * Creates an adapter with an explicit in-body identity-binding gate (WH-08/09). When
     * {@code enforceIdentity} is true, a decoded {@link AppendEntriesRequest}/{@link
     * InstallSnapshotRequest}/{@link TimeoutNowRequest} {@code leaderId} or {@link RequestVoteRequest}
     * {@code candidateId} that differs from the transport-authenticated sender is dropped and counted
     * (Layer 2 has already bound the sender to its cert identity). The witness and coalesced-heartbeat
     * paths already derive the sender from the authenticated {@code from}, so they need no extra check.
     *
     * @param enforceIdentity  whether the peer-identity allow-list is active
     * @param transportMetrics security-event sink (never null; pass {@link RaftTransportMetrics#NOOP})
     */
    public RaftTransportAdapter(io.configd.transport.RaftTransport transport, int groupId,
                                boolean enforceIdentity, RaftTransportMetrics transportMetrics) {
        this.transport = transport;
        this.groupId = groupId;
        this.enforceIdentity = enforceIdentity;
        this.transportMetrics = java.util.Objects.requireNonNull(transportMetrics, "transportMetrics");
    }

    /**
     * The routing-significant in-body identity of a request ({@code leaderId} for AppendEntries /
     * InstallSnapshot / TimeoutNow, {@code candidateId} for RequestVote / PreVote), or {@code null}
     * for messages that carry no such field (responses carry a {@code from} that equals the sender on
     * their own connection). Used to bind the in-body id to the authenticated transport sender.
     */
    private static NodeId inBodyRoutingId(RaftMessage message) {
        return switch (message) {
            case AppendEntriesRequest r -> r.leaderId();
            case RequestVoteRequest r -> r.candidateId();
            case InstallSnapshotRequest r -> r.leaderId();
            case TimeoutNowRequest r -> r.leaderId();
            default -> null;
        };
    }

    /**
     * Logs an in-body identity rejection at most once per {@link #IDENTITY_LOG_INTERVAL_NANOS}, carrying
     * the count suppressed since the last line. Unlike a senderId mismatch (which drops the connection,
     * so it is one line per drop), an in-body mismatch drops only the FRAME and keeps the connection, so
     * an authenticated-but-misbehaving peer could otherwise flood the log (the WH-10 anti-pattern). The
     * metric is incremented on every rejection by the caller regardless of throttling.
     */
    private void logInBodyRejectionThrottled(NodeId from, NodeId bodyId, MessageType type) {
        long now = System.nanoTime();
        long last = identityLogLastNanos.get();
        if (now - last >= IDENTITY_LOG_INTERVAL_NANOS && identityLogLastNanos.compareAndSet(last, now)) {
            long suppressed = identityLogSuppressed.getAndSet(0L);
            LOG.warning(() -> "In-body id " + bodyId + " from authenticated sender " + from + " (" + type
                    + ") does not match; dropping frame"
                    + (suppressed > 0 ? " (" + suppressed + " similar suppressed since last log)" : ""));
        } else {
            identityLogSuppressed.incrementAndGet();
        }
    }

    @Override
    public void send(NodeId target, RaftMessage message) {
        // The encoder may throw IllegalArgumentException for oversized
        // messages (RaftMessageCodec.checkInstallSnapshotFitsFrame,
        // checkAppendEntriesFitsFrame, FrameCodec.checkPayloadFitsFrame).
        // We let it propagate up to RaftNode so the producer can skip
        // any in-flight bookkeeping it would otherwise have done for a
        // successful send - the transport adapter has no view of
        // inflightCount and cannot decide that itself.
        FrameCodec.Frame frame = RaftMessageCodec.encode(message, groupId);
        transport.send(target, frame);
    }

    /**
     * A consumer of a decoded inbound Raft message together with the Raft group it belongs to.
     *
     * <p>The {@code groupId} carried in every frame header (offset 6, no
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
     * each message to its own group (the groupId is already in the frame header).
     *
     * @param handler handler of decoded inbound messages, keyed by group id
     */
    public void registerInboundHandler(InboundHandler handler) {
        transport.registerHandler((from, rawMessage) -> {
            if (rawMessage instanceof FrameCodec.Frame frame) {
                try {
                    if (frame.messageType() == MessageType.RAFT_WITNESS
                            || frame.messageType() == MessageType.RAFT_WITNESS_REPLY) {
                        // A witness frame's sender is NOT in its body - it is the transport's
                        // authenticated 4-byte prefix (from). Inject it here (the coalesced-heartbeat
                        // precedent), then route through the SAME per-group owner path as any RaftMessage
                        // so it lands in handleMessage on the group's owner thread.
                        RaftMessage witness = RaftMessageCodec.decodeWitness(frame, from);
                        handler.accept(from, frame.groupId(), witness);
                    } else if (frame.messageType() == MessageType.RAFT_COALESCED_HEARTBEAT) {
                        // A coalesced heartbeat bundles many groups' empty AppendEntries into one
                        // frame (dormant at N=1 - never sent there). DEMUX it and dispatch EACH
                        // group through the SAME per-group inbound path, so every group's
                        // AppendEntries is marshalled onto ITS OWN owner thread, re-using the
                        // unregistered-group drop + the throwable guard.
                        // We deliberately do NOT call driver.routeCoalescedHeartbeat() here: it runs
                        // routeMessage() inline, and a coalesced frame can carry groups with DIFFERENT
                        // owners at N>1 - running them all on this (inbound) thread would execute
                        // handleMessage off-owner and trip RaftNode.assertOwnerThread() / race the
                        // non-synchronized node (ADR-0009).
                        Map<Integer, AppendEntriesRequest> heartbeats =
                                RaftMessageCodec.decodeCoalescedHeartbeat(frame);
                        for (Map.Entry<Integer, AppendEntriesRequest> e : heartbeats.entrySet()) {
                            handler.accept(from, e.getKey(), e.getValue());
                        }
                    } else {
                        RaftMessage raftMessage = RaftMessageCodec.decode(frame);
                        // Layer 2 in-body binding (WH-08/09): a request's self-declared leaderId /
                        // candidateId must equal the transport-authenticated sender. A cert-valid peer
                        // whose senderId matched its cert (so it reached here) but whose body claims a
                        // different node is dropped (frame not dispatched) and counted. Gated on the
                        // allow-list being active so legacy/shared-cert deployments are unaffected.
                        if (enforceIdentity) {
                            NodeId bodyId = inBodyRoutingId(raftMessage);
                            if (bodyId != null && !bodyId.equals(from)) {
                                transportMetrics.onPeerIdentityRejected();
                                logInBodyRejectionThrottled(from, bodyId, frame.messageType());
                                return; // drop the frame; the connection stays (senderId itself is bound)
                            }
                        }
                        handler.accept(from, frame.groupId(), raftMessage);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to decode Raft message from " + from + ": " + e.getMessage());
                }
            }
        });
    }
}

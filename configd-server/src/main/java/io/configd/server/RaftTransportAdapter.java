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


public final class RaftTransportAdapter implements RaftTransport {

    private static final Logger LOG = Logger.getLogger(RaftTransportAdapter.class.getName());

    
    private static final long LOG_THROTTLE_INTERVAL_NANOS = 1_000_000_000L;

    private final io.configd.transport.RaftTransport transport;
    private final int groupId;
    
    private final boolean enforceIdentity;
    
    private final RaftTransportMetrics transportMetrics;
    
    private final AtomicLong identityLogLastNanos = new AtomicLong(0L);
    private final AtomicLong identityLogSuppressed = new AtomicLong(0L);
    
    private final AtomicLong decodeDropLogLastNanos = new AtomicLong(0L);
    private final AtomicLong decodeDropLogSuppressed = new AtomicLong(0L);

    
    public RaftTransportAdapter(io.configd.transport.RaftTransport transport, int groupId) {
        this(transport, groupId, false, RaftTransportMetrics.NOOP);
    }

    
    public RaftTransportAdapter(io.configd.transport.RaftTransport transport, int groupId,
                                boolean enforceIdentity, RaftTransportMetrics transportMetrics) {
        this.transport = transport;
        this.groupId = groupId;
        this.enforceIdentity = enforceIdentity;
        this.transportMetrics = java.util.Objects.requireNonNull(transportMetrics, "transportMetrics");
    }

    
    private static NodeId inBodyRoutingId(RaftMessage message) {
        return switch (message) {
            case AppendEntriesRequest r -> r.leaderId();
            case RequestVoteRequest r -> r.candidateId();
            case InstallSnapshotRequest r -> r.leaderId();
            case TimeoutNowRequest r -> r.leaderId();
            default -> null;
        };
    }

    
    private void logThrottled(AtomicLong lastNanos, AtomicLong suppressed,
                              java.util.function.LongFunction<String> message) {
        long now = System.nanoTime();
        long last = lastNanos.get();
        if (now - last >= LOG_THROTTLE_INTERVAL_NANOS && lastNanos.compareAndSet(last, now)) {
            long n = suppressed.getAndSet(0L);
            LOG.warning(() -> message.apply(n));
        } else {
            suppressed.incrementAndGet();
        }
    }

    
    private void logInBodyRejectionThrottled(NodeId from, NodeId bodyId, MessageType type) {
        logThrottled(identityLogLastNanos, identityLogSuppressed, suppressed ->
                "In-body id " + bodyId + " from authenticated sender " + from + " (" + type
                        + ") does not match; dropping frame"
                        + (suppressed > 0 ? " (" + suppressed + " similar suppressed since last log)" : ""));
    }

    
    private void logDecodeDropThrottled(NodeId from, MessageType type, Exception cause) {
        logThrottled(decodeDropLogLastNanos, decodeDropLogSuppressed, suppressed ->
                "Dropped undecodable inbound frame from " + from + " (type " + type + "): "
                        + cause.getClass().getSimpleName() + ": " + cause.getMessage()
                        + (suppressed > 0 ? " (" + suppressed + " similar suppressed since last log)" : ""));
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

    
    @FunctionalInterface
    public interface InboundHandler {
        
        void accept(NodeId from, int groupId, RaftMessage message);
    }

    
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
                        // non-synchronized node.
                        Map<Integer, AppendEntriesRequest> heartbeats =
                                RaftMessageCodec.decodeCoalescedHeartbeat(frame);
                        // Layer 2 in-body binding for the COALESCED path: a coalesced
                        // heartbeat bundles only the sending leader's own per-group heartbeats, so every
                        // entry's self-declared leaderId must equal the transport-authenticated sender.
                        // A cert-valid but Byzantine peer that forges another node's leaderId inside one
                        // per-group entry is rejected - the WHOLE frame is dropped (not dispatched) and
                        // counted, mirroring the single-message in-body check below. Scanned in full
                        // BEFORE any dispatch so a forgery late in the map cannot slip earlier entries
                        // through. Gated on the allow-list being active (legacy deployments unaffected).
                        if (enforceIdentity) {
                            for (Map.Entry<Integer, AppendEntriesRequest> e : heartbeats.entrySet()) {
                                NodeId bodyId = e.getValue().leaderId();
                                if (!bodyId.equals(from)) {
                                    transportMetrics.onPeerIdentityRejected();
                                    logInBodyRejectionThrottled(from, bodyId, frame.messageType());
                                    return; // drop the frame; the connection stays (senderId is bound)
                                }
                            }
                        }
                        for (Map.Entry<Integer, AppendEntriesRequest> e : heartbeats.entrySet()) {
                            handler.accept(from, e.getKey(), e.getValue());
                        }
                    } else {
                        RaftMessage raftMessage = RaftMessageCodec.decode(frame);
                        // Layer 2 in-body binding: a request's self-declared leaderId /
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
                    // A frame that framed and CRC-verified cleanly but could not be turned into an
                    // actionable RaftMessage - a dormant/undecodable type (PLUMTREE_*/HYPARVIEW_*/HEARTBEAT
                    // hit RaftMessageCodec.decode's default throw) or a structurally-malformed payload. The
                    // connection stays (this is one frame, not a stream desync), so an authenticated-but-
                    // hostile peer could flood the log one line per frame. Count every drop; rate-limit
                    // the WARN.
                    transportMetrics.onInboundFrameDropped();
                    logDecodeDropThrottled(from, frame.messageType(), e);
                }
            }
        });
    }
}

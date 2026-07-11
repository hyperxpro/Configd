package io.configd.server;

import io.configd.common.NodeId;
import io.configd.raft.AppendEntriesRequest;
import io.configd.raft.RaftMessage;
import io.configd.raft.RequestVoteRequest;
import io.configd.transport.FrameCodec;
import io.configd.transport.RaftTransport;
import io.configd.transport.RaftTransportMetrics;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Adversarial tests for the <b>in-body</b> identity binding in {@link RaftTransportAdapter}: a decoded
 * {@code leaderId}/{@code candidateId} that differs from the transport-authenticated sender must be
 * dropped (not dispatched) and counted, but only when the allow-list is enforced. Each control case is
 * verified by a companion test that actually performs the attack.
 *
 * <p>Drives the adapter through a fake {@link RaftTransport} that captures the registered handler and
 * lets the test inject inbound {@code (from, frame)} pairs directly, so the check is exercised without
 * a live socket or TLS.
 */
class RaftTransportAdapterIdentityBindingTest {

    /** A fake transport that captures the registered inbound handler for direct injection. */
    private static final class CapturingTransport implements RaftTransport {
        private MessageHandler handler;
        @Override public void send(NodeId target, Object message) { }
        @Override public void registerHandler(MessageHandler handler) { this.handler = handler; }
        void inject(NodeId from, FrameCodec.Frame frame) { handler.onMessage(from, frame); }
    }

    private static FrameCodec.Frame appendEntriesFrom(NodeId leaderId) {
        return RaftMessageCodec.encode(
                new AppendEntriesRequest(5L, leaderId, 0L, 0L, List.of(), 0L), 0);
    }

    /** A coalesced heartbeat bundling one empty AppendEntries per given leaderId (group ids 0,1,...). */
    private static FrameCodec.Frame coalescedHeartbeat(NodeId... leaders) {
        Map<Integer, AppendEntriesRequest> hb = new LinkedHashMap<>();
        int gid = 0;
        for (NodeId leader : leaders) {
            hb.put(gid++, new AppendEntriesRequest(5L, leader, 0L, 0L, List.of(), 0L));
        }
        return RaftMessageCodec.encodeCoalescedHeartbeat(hb);
    }

    /** A counting {@link RaftTransportMetrics} sink (the SPI keeps a default method, so no lambda). */
    private static RaftTransportMetrics counting(AtomicInteger rejections) {
        return new RaftTransportMetrics() {
            @Override public void onPeerIdentityRejected() { rejections.incrementAndGet(); }
        };
    }

    @Test
    void inBodyLeaderIdMismatchIsDroppedAndCountedWhenEnforced() {
        CapturingTransport transport = new CapturingTransport();
        AtomicInteger rejections = new AtomicInteger();
        RaftTransportAdapter adapter = new RaftTransportAdapter(transport, 0, true, counting(rejections));

        AtomicInteger dispatched = new AtomicInteger();
        adapter.registerInboundHandler((from, gid, message) -> dispatched.incrementAndGet());

        // A cert-valid peer whose senderId matched its cert (from = Node-1) but whose AppendEntries
        // body claims to be the leader Node-9. The frame must be dropped and counted.
        transport.inject(NodeId.of(1), appendEntriesFrom(NodeId.of(9)));

        assertEquals(0, dispatched.get(), "a forged in-body leaderId must not be dispatched");
        assertEquals(1, rejections.get(), "the forged in-body leaderId must increment the mismatch counter");
    }

    @Test
    void inBodyCandidateIdMismatchIsDroppedWhenEnforced() {
        CapturingTransport transport = new CapturingTransport();
        AtomicInteger rejections = new AtomicInteger();
        RaftTransportAdapter adapter = new RaftTransportAdapter(transport, 0, true, counting(rejections));

        AtomicInteger dispatched = new AtomicInteger();
        adapter.registerInboundHandler((from, gid, message) -> dispatched.incrementAndGet());

        FrameCodec.Frame requestVote = RaftMessageCodec.encode(
                new RequestVoteRequest(5L, NodeId.of(9), 0L, 0L, false), 0);
        transport.inject(NodeId.of(1), requestVote);

        assertEquals(0, dispatched.get(), "a forged in-body candidateId must not be dispatched");
        assertEquals(1, rejections.get());
    }

    @Test
    void matchingInBodyIdIsDispatchedWhenEnforced() {
        CapturingTransport transport = new CapturingTransport();
        AtomicInteger rejections = new AtomicInteger();
        RaftTransportAdapter adapter = new RaftTransportAdapter(transport, 0, true, counting(rejections));

        AtomicInteger dispatched = new AtomicInteger();
        RaftMessage[] seen = new RaftMessage[1];
        adapter.registerInboundHandler((from, gid, message) -> { dispatched.incrementAndGet(); seen[0] = message; });

        // Well-formed: senderId (from) == in-body leaderId == Node-4. Passes untouched.
        transport.inject(NodeId.of(4), appendEntriesFrom(NodeId.of(4)));

        assertEquals(1, dispatched.get(), "a same-identity frame must be dispatched");
        assertEquals(0, rejections.get());
        assertNotNull(seen[0]);
    }

    @Test
    void coalescedHeartbeatForgedLeaderIdIsDroppedAndCountedWhenEnforced() {
        // A coalesced heartbeat from Node-1 that bundles an honest group (leader Node-1) and a forged
        // group (leader Node-9). The whole frame is dropped and counted - not even the honest entry
        // slips through - because the sender may only speak for itself.
        CapturingTransport transport = new CapturingTransport();
        AtomicInteger rejections = new AtomicInteger();
        RaftTransportAdapter adapter = new RaftTransportAdapter(transport, 0, true, counting(rejections));

        AtomicInteger dispatched = new AtomicInteger();
        adapter.registerInboundHandler((from, gid, message) -> dispatched.incrementAndGet());

        transport.inject(NodeId.of(1), coalescedHeartbeat(NodeId.of(1), NodeId.of(9)));

        assertEquals(0, dispatched.get(),
                "a coalesced HB carrying any forged per-group leaderId must not dispatch ANY group");
        assertEquals(1, rejections.get(), "the coalesced-path forgery must increment the mismatch counter");
    }

    @Test
    void coalescedHeartbeatMatchingLeaderIdsIsDispatchedWhenEnforced() {
        CapturingTransport transport = new CapturingTransport();
        AtomicInteger rejections = new AtomicInteger();
        RaftTransportAdapter adapter = new RaftTransportAdapter(transport, 0, true, counting(rejections));

        AtomicInteger dispatched = new AtomicInteger();
        adapter.registerInboundHandler((from, gid, message) -> dispatched.incrementAndGet());

        // Every per-group leaderId == from (Node-4): an honest coalesced heartbeat; both groups dispatch.
        transport.inject(NodeId.of(4), coalescedHeartbeat(NodeId.of(4), NodeId.of(4)));

        assertEquals(2, dispatched.get(), "an honest coalesced HB dispatches every per-group heartbeat");
        assertEquals(0, rejections.get());
    }

    @Test
    void coalescedHeartbeatForgeryIsIgnoredWhenUnenforced() {
        CapturingTransport transport = new CapturingTransport();
        AtomicInteger rejections = new AtomicInteger();
        // Legacy 2-arg constructor => enforcement off: the coalesced path preserves legacy dispatch.
        RaftTransportAdapter adapter = new RaftTransportAdapter(transport, 0);

        AtomicInteger dispatched = new AtomicInteger();
        adapter.registerInboundHandler((from, gid, message) -> dispatched.incrementAndGet());

        transport.inject(NodeId.of(1), coalescedHeartbeat(NodeId.of(1), NodeId.of(9)));

        assertEquals(2, dispatched.get(),
                "with enforcement off the coalesced path preserves legacy dispatch (no in-body check)");
        assertEquals(0, rejections.get());
    }

    @Test
    void mismatchIsIgnoredWhenUnenforced() {
        CapturingTransport transport = new CapturingTransport();
        AtomicInteger rejections = new AtomicInteger();
        // Legacy 2-arg constructor => enforcement off, byte-identical dispatch behaviour.
        RaftTransportAdapter adapter = new RaftTransportAdapter(transport, 0);

        AtomicInteger dispatched = new AtomicInteger();
        adapter.registerInboundHandler((from, gid, message) -> dispatched.incrementAndGet());

        // The same forged frame as the enforced case, but with no allow-list configured it is
        // dispatched (the legacy behavior existing cluster tests rely on).
        transport.inject(NodeId.of(1), appendEntriesFrom(NodeId.of(9)));

        assertEquals(1, dispatched.get(),
                "with enforcement off the adapter must preserve legacy dispatch (no in-body check)");
        assertEquals(0, rejections.get());
    }
}

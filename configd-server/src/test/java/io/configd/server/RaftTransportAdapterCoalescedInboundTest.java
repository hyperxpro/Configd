package io.configd.server;

import io.configd.common.NodeId;
import io.configd.raft.AppendEntriesRequest;
import io.configd.raft.RaftMessage;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import io.configd.transport.RaftTransport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inbound coalesced-heartbeat branch of
 * {@link RaftTransportAdapter#registerInboundHandler}. A {@code RAFT_COALESCED_HEARTBEAT} frame must be
 * DEMUXed into one {@code handler.accept(from, gid, ae)} call per group - so each group's heartbeat is
 * dispatched on the SAME per-group path (its own owner thread, the unregistered-group drop), NOT routed
 * inline on the inbound thread (which would run handleMessage off-owner). A malformed
 * coalesced payload is dropped without dispatch and without propagating.
 */
class RaftTransportAdapterCoalescedInboundTest {

    private static final NodeId FROM = NodeId.of(42);

    private static final class CapturingTransport implements RaftTransport {
        private MessageHandler handler;

        @Override public void send(NodeId target, Object message) { }

        @Override public void registerHandler(MessageHandler handler) { this.handler = handler; }

        void fire(NodeId from, Object message) { handler.onMessage(from, message); }
    }

    private record Dispatch(NodeId from, int groupId, RaftMessage message) {}

    @Test
    void coalescedFrameIsDemuxedPerGroup() {
        CapturingTransport transport = new CapturingTransport();
        // The adapter's own groupId (99) must be IGNORED for a coalesced frame - the per-group ids
        // come from the payload, not the adapter/frame header.
        RaftTransportAdapter adapter = new RaftTransportAdapter(transport, 99);
        List<Dispatch> dispatched = new ArrayList<>();
        adapter.registerInboundHandler((from, gid, msg) -> dispatched.add(new Dispatch(from, gid, msg)));

        Map<Integer, AppendEntriesRequest> heartbeats = new LinkedHashMap<>();
        heartbeats.put(5, new AppendEntriesRequest(10L, FROM, 100L, 9L, List.of(), 99L));
        heartbeats.put(2, new AppendEntriesRequest(11L, FROM, 200L, 10L, List.of(), 199L));
        FrameCodec.Frame coalesced = RaftMessageCodec.encodeCoalescedHeartbeat(heartbeats);

        transport.fire(FROM, coalesced);

        assertEquals(2, dispatched.size(), "one dispatch per coalesced group");
        assertEquals(5, dispatched.get(0).groupId());
        assertEquals(2, dispatched.get(1).groupId());
        for (Dispatch d : dispatched) {
            assertEquals(FROM, d.from(), "the transport sender id is delivered as `from`");
            AppendEntriesRequest ae = assertInstanceOf(AppendEntriesRequest.class, d.message());
            assertTrue(ae.entries().isEmpty(), "each demuxed heartbeat is an empty AppendEntries");
        }
        assertEquals(100L, ((AppendEntriesRequest) dispatched.get(0).message()).prevLogIndex());
        assertEquals(200L, ((AppendEntriesRequest) dispatched.get(1).message()).prevLogIndex());
    }

    @Test
    void regularFrameStillDispatchesByFrameGroupId() {
        CapturingTransport transport = new CapturingTransport();
        RaftTransportAdapter adapter = new RaftTransportAdapter(transport, 0);
        List<Dispatch> dispatched = new ArrayList<>();
        adapter.registerInboundHandler((from, gid, msg) -> dispatched.add(new Dispatch(from, gid, msg)));

        FrameCodec.Frame normal =
                RaftMessageCodec.encode(new AppendEntriesRequest(3L, FROM, 0L, 0L, List.of(), 0L), 7);
        transport.fire(FROM, normal);

        assertEquals(1, dispatched.size());
        assertEquals(7, dispatched.get(0).groupId(), "non-coalesced frames dispatch by frame.groupId()");
        assertInstanceOf(AppendEntriesRequest.class, dispatched.get(0).message());
    }

    @Test
    void malformedCoalescedPayloadIsDroppedWithoutDispatchOrThrow() {
        CapturingTransport transport = new CapturingTransport();
        RaftTransportAdapter adapter = new RaftTransportAdapter(transport, 0);
        List<Dispatch> dispatched = new ArrayList<>();
        adapter.registerInboundHandler((from, gid, msg) -> dispatched.add(new Dispatch(from, gid, msg)));

        // A coalesced frame whose payload claims a huge group count in a tiny buffer.
        byte[] payload = new byte[] {0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}; // count = Integer.MAX_VALUE
        FrameCodec.Frame bad = new FrameCodec.Frame(MessageType.RAFT_COALESCED_HEARTBEAT, 0, 0L, payload);

        transport.fire(FROM, bad); // must not throw out of the handler

        assertTrue(dispatched.isEmpty(), "a malformed coalesced frame dispatches nothing");
    }
}

package io.configd.server;

import io.configd.common.NodeId;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import io.configd.transport.RaftTransport;
import io.configd.transport.RaftTransportMetrics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A frame that framed and CRC-verified cleanly but cannot be turned into an actionable
 * {@code RaftMessage} - a dormant/undecodable {@link MessageType} with no consensus codec, or a
 * structurally-malformed payload - must be dropped (not dispatched) and COUNTED, with the WARN log
 * rate-limited so an authenticated-but-hostile peer cannot flood the log one line per frame. The metric
 * is un-throttled: every drop increments it, so a flood of N bad frames counts N (the log is what is
 * throttled, verified by the absence of a per-frame flood, not by counting log lines here).
 *
 * <p>Reuses the {@code CapturingTransport} injection pattern - the check is exercised without a socket.
 */
class RaftTransportAdapterDecodeDropTest {

    private static final class CapturingTransport implements RaftTransport {
        private MessageHandler handler;
        @Override public void send(NodeId target, Object message) { }
        @Override public void registerHandler(MessageHandler handler) { this.handler = handler; }
        void inject(NodeId from, FrameCodec.Frame frame) { handler.onMessage(from, frame); }
    }

    private static RaftTransportMetrics countingDrops(AtomicInteger drops) {
        return new RaftTransportMetrics() {
            @Override public void onInboundFrameDropped() { drops.incrementAndGet(); }
        };
    }

    @Test
    void dormantTypeIsDroppedAndCounted() {
        CapturingTransport transport = new CapturingTransport();
        AtomicInteger drops = new AtomicInteger();
        RaftTransportAdapter adapter = new RaftTransportAdapter(transport, 0, false, countingDrops(drops));

        AtomicInteger dispatched = new AtomicInteger();
        adapter.registerInboundHandler((from, gid, message) -> dispatched.incrementAndGet());

        // PLUMTREE_EAGER_PUSH is a reserved-dormant type with no consensus codec: RaftMessageCodec.decode
        // hits its default throw. The frame is a valid Frame object (fromCode accepts the type byte), so
        // it reaches the message-decode boundary and is dropped there.
        transport.inject(NodeId.of(1),
                new FrameCodec.Frame(MessageType.PLUMTREE_EAGER_PUSH, 0, 1L, new byte[0]));

        assertEquals(0, dispatched.get(), "a dormant-type frame must not be dispatched");
        assertEquals(1, drops.get(), "a dormant-type drop must increment the decode-drop counter");
    }

    @Test
    void malformedPayloadIsDroppedAndCounted() {
        CapturingTransport transport = new CapturingTransport();
        AtomicInteger drops = new AtomicInteger();
        RaftTransportAdapter adapter = new RaftTransportAdapter(transport, 0, false, countingDrops(drops));

        AtomicInteger dispatched = new AtomicInteger();
        adapter.registerInboundHandler((from, gid, message) -> dispatched.incrementAndGet());

        // An APPEND_ENTRIES frame whose payload is far too short for the 32-byte fixed header: the codec
        // rejects it (truncation) at the message-decode boundary.
        transport.inject(NodeId.of(1),
                new FrameCodec.Frame(MessageType.APPEND_ENTRIES, 0, 1L, new byte[]{1, 2, 3}));

        assertEquals(0, dispatched.get(), "a malformed frame must not be dispatched");
        assertEquals(1, drops.get(), "a malformed-payload drop must increment the decode-drop counter");
    }

    @Test
    void everyDropInAFloodIsCounted() {
        CapturingTransport transport = new CapturingTransport();
        AtomicInteger drops = new AtomicInteger();
        RaftTransportAdapter adapter = new RaftTransportAdapter(transport, 0, false, countingDrops(drops));

        AtomicInteger dispatched = new AtomicInteger();
        adapter.registerInboundHandler((from, gid, message) -> dispatched.incrementAndGet());

        final int flood = 1000;
        for (int i = 0; i < flood; i++) {
            transport.inject(NodeId.of(1),
                    new FrameCodec.Frame(MessageType.HYPARVIEW_JOIN, 0, 1L, new byte[0]));
        }

        assertEquals(0, dispatched.get());
        assertEquals(flood, drops.get(), "every dropped frame in a flood must be counted");
    }
}

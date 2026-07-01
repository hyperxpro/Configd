package io.configd.netty;

import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import io.configd.transport.RaftWireProtocol;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-bytes equivalence for the production {@link NettyConsensusFrameEncoder}: the in-pipeline
 * encoder must emit, for every frame shape, the <b>byte-identical</b> wire sequence of the JDK
 * adapter's single source of truth, {@link RaftWireProtocol#encodeWire(int, FrameCodec.Frame)}
 * - {@code [4B big-endian senderId] || FrameCodec frame}.
 *
 * <p>{@code AbstractRaftTransportContract} proves the two transports are functionally interchangeable
 * end-to-end; this test additionally proves the Netty encoder's <em>bytes</em> are exactly the JDK's,
 * so the allocation win measured by {@link NettyConsensusFrameEncoderAllocationTest} is a win on the
 * <em>identical</em> wire, not a different (cheaper) encoding. The encoder is driven the production
 * way - in a Netty pipeline via {@link EmbeddedChannel} - so the bytes under assertion are produced
 * by the real
 * {@link io.netty.handler.codec.MessageToByteEncoder} path, not a hand-rolled replica.
 */
class NettyConsensusFrameEncoderByteIdentityTest {

    /** A spread of sender ids, including 0, the sign bit, and both 32-bit extremes (big-endian prefix). */
    private static final int[] SENDER_IDS = {0, 1, 3, -1, Integer.MAX_VALUE, Integer.MIN_VALUE};

    @Test
    void inPipelineEncoderProducesByteIdenticalWireForEverySenderId() {
        for (int senderId : SENDER_IDS) {
            for (FrameCodec.Frame frame : representativeFrames()) {
                assertWireIdentity(senderId, frame);
            }
        }
    }

    /**
     * Drives the production encoder in a real pipeline for one frame and asserts the outbound bytes
     * equal {@link RaftWireProtocol#encodeWire}. A fresh {@link EmbeddedChannel} per case keeps the
     * per-channel {@code senderId} constant honest and avoids any cross-case state. Releases the
     * encoded {@link ByteBuf}.
     */
    private static void assertWireIdentity(int senderId, FrameCodec.Frame frame) {
        EmbeddedChannel ch = new EmbeddedChannel(new NettyConsensusFrameEncoder(senderId));
        ch.config().setAllocator(PooledByteBufAllocator.DEFAULT); // match production
        try {
            assertTrue(ch.writeOutbound(frame), "encoder must produce an outbound buffer");
            ByteBuf out = ch.readOutbound();
            try {
                byte[] actual = new byte[out.readableBytes()];
                out.getBytes(out.readerIndex(), actual);
                byte[] expected = RaftWireProtocol.encodeWire(senderId, frame);
                String label = "senderId=" + senderId + " type=" + frame.messageType()
                        + " group=" + frame.groupId() + " term=" + frame.term()
                        + " payloadLen=" + frame.payload().length;
                assertArrayEquals(expected, actual, label);
                // The frame is also exactly FrameCodec.decode-able after the 4-byte sender prefix -
                // i.e. the encoder wrote a valid, CRC-correct frame, not merely matching bytes.
                byte[] frameOnly = new byte[actual.length - RaftWireProtocol.SENDER_ID_SIZE];
                System.arraycopy(actual, RaftWireProtocol.SENDER_ID_SIZE, frameOnly, 0, frameOnly.length);
                FrameCodec.Frame round = FrameCodec.decode(frameOnly);
                assertEquals(frame.messageType(), round.messageType(), label);
                assertEquals(frame.groupId(), round.groupId(), label);
                assertEquals(frame.term(), round.term(), label);
                assertArrayEquals(frame.payload(), round.payload(), label);
            } finally {
                out.release();
            }
            assertFalse(ch.finish(), "no residual outbound buffers");
        } finally {
            ch.finishAndReleaseAll();
        }
    }

    /** Varied {@link MessageType}, groupId, term, and payload size (incl. empty, 256B, 4KB) + extremes. */
    private static List<FrameCodec.Frame> representativeFrames() {
        List<FrameCodec.Frame> frames = new ArrayList<>();
        // Empty heartbeat - the coalesced hot path.
        frames.add(new FrameCodec.Frame(MessageType.HEARTBEAT, 0, 0L, new byte[0]));
        // Small + medium + large payloads with varied content.
        frames.add(new FrameCodec.Frame(MessageType.APPEND_ENTRIES, 1, 5L, payload(1)));
        frames.add(new FrameCodec.Frame(MessageType.APPEND_ENTRIES, 7, 42L, payload(17)));
        frames.add(new FrameCodec.Frame(MessageType.APPEND_ENTRIES, 7, 42L, payload(256)));
        frames.add(new FrameCodec.Frame(MessageType.APPEND_ENTRIES, 13, 99L, payload(4096)));
        // Type spread.
        frames.add(new FrameCodec.Frame(MessageType.REQUEST_VOTE, 2, 3L, payload(8)));
        frames.add(new FrameCodec.Frame(MessageType.PRE_VOTE, 4, 7L, payload(0)));
        frames.add(new FrameCodec.Frame(MessageType.INSTALL_SNAPSHOT, 9, 1024L, payload(512)));
        frames.add(new FrameCodec.Frame(MessageType.TIMEOUT_NOW, 5, 11L, payload(4)));
        // groupId / term extremes - exercise the big-endian int/long writes against encodeWire.
        frames.add(new FrameCodec.Frame(MessageType.APPEND_ENTRIES_RESPONSE, Integer.MAX_VALUE,
                Long.MAX_VALUE, payload(33)));
        frames.add(new FrameCodec.Frame(MessageType.REQUEST_VOTE_RESPONSE, Integer.MIN_VALUE,
                Long.MIN_VALUE, payload(64)));
        frames.add(new FrameCodec.Frame(MessageType.HEARTBEAT, -1, -1L, payload(128)));
        return frames;
    }

    private static byte[] payload(int n) {
        byte[] p = new byte[n];
        for (int i = 0; i < n; i++) {
            p[i] = (byte) (i * 31 + 7); // deterministic, non-trivial content (not all-zero)
        }
        return p;
    }
}

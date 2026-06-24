package io.configd.jdkvsnetty;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Charter hard-rule 5: a faster encoder that changes the wire is disqualified. This test proves
 * the BEST-JDK single-pass into-buffer encoders ({@link H2HCodecs}) produce <b>byte-identical</b>
 * output to the production {@link FrameCodec} / {@link EdgeFrameCodec} across the benchmarked
 * payload/batch sizes, and that the bytes round-trip through the production decoder. The Netty
 * encoders are validated against the same expected bytes in their own test once wired.
 */
class WireH2HCorrectnessTest {

    private static final int VALUE_BYTES = 64;
    private static final int ED25519_SIG_BYTES = 64;

    // ---- consensus: [4B BE senderId] || FrameCodec frame ----

    @Test
    void consensusSendWireInto_isByteIdenticalToEncodeWire() {
        int senderId = 3, groupId = 7;
        long term = 42L;
        for (int payloadBytes : new int[]{0, 256, 4096}) {
            MessageType type = (payloadBytes == 0) ? MessageType.HEARTBEAT : MessageType.APPEND_ENTRIES;
            byte[] payload = new byte[payloadBytes];
            for (int i = 0; i < payloadBytes; i++) {
                payload[i] = (byte) i;
            }
            byte[] expected = encodeWireReplica(senderId, type, groupId, term, payload);

            ByteBuffer out = ByteBuffer.allocate(4 + FrameCodec.frameSize(payloadBytes));
            int len = H2HCodecs.encodeSendWireInto(out, senderId, type, groupId, term, payload);
            byte[] actual = H2HCodecs.toBytes(out, len);

            assertArrayEquals(expected, actual,
                    "best-JDK consensus send must be byte-identical (payload=" + payloadBytes + ")");
        }
    }

    /** Exact replica of {@code TcpRaftTransport.encodeWire}: [4B BE senderId] || frame. */
    private static byte[] encodeWireReplica(int senderId, MessageType type, int groupId,
                                            long term, byte[] payload) {
        byte[] encoded = FrameCodec.encode(type, groupId, term, payload);
        byte[] wire = new byte[4 + encoded.length];
        wire[0] = (byte) (senderId >>> 24);
        wire[1] = (byte) (senderId >>> 16);
        wire[2] = (byte) (senderId >>> 8);
        wire[3] = (byte) senderId;
        System.arraycopy(encoded, 0, wire, 4, encoded.length);
        return wire;
    }

    // ---- fan-out: NOTIFY frame ----

    @Test
    void fanOutNotifyInto_isByteIdenticalToEdgeFrameCodec() {
        for (int notifyCount : new int[]{1, 16, 64}) {
            EdgeFrame.Notify frame = buildSignedNotify(notifyCount);
            byte[] expected = EdgeFrameCodec.encode(frame);

            ByteBuffer out = ByteBuffer.allocate(EdgeFrameCodec.MAX_NOTIFY_BATCH_BYTES + 64);
            int len = H2HCodecs.encodeNotifyInto(out, frame);
            byte[] actual = H2HCodecs.toBytes(out, len);

            assertArrayEquals(expected, actual,
                    "best-JDK NOTIFY encode must be byte-identical (count=" + notifyCount + ")");

            // And the bytes must round-trip through the production decoder unchanged.
            EdgeFrame decoded = EdgeFrameCodec.decode(actual);
            assertEquals(EdgeFrame.Notify.class, decoded.getClass());
            assertEquals(notifyCount, ((EdgeFrame.Notify) decoded).notifications().size());
        }
    }

    private static EdgeFrame.Notify buildSignedNotify(int notifyCount) {
        byte[] value = new byte[VALUE_BYTES];
        for (int i = 0; i < VALUE_BYTES; i++) {
            value[i] = (byte) i;
        }
        List<CommitNotification> notifications = new ArrayList<>(notifyCount);
        for (int i = 0; i < notifyCount; i++) {
            List<ConfigMutation> mutations =
                    List.of(new ConfigMutation.Put("config/service/key-" + i, value));
            byte[] signature = new byte[ED25519_SIG_BYTES];
            byte[] nonce = new byte[ConfigDelta.NONCE_LEN];
            ConfigDelta delta = new ConfigDelta(i, i + 1L, mutations, signature, i + 1L, nonce);
            notifications.add(new CommitNotification(i + 1L, 1_700_000_000_000L + i, delta));
        }
        return new EdgeFrame.Notify(notifications);
    }
}

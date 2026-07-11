package io.configd.jdkvsnetty;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * For the BEST-NETTY side: the pooled-{@code ByteBuf} encoders
 * ({@link NettyWireEncoders}) must produce <b>byte-identical</b> output to the production
 * {@link FrameCodec}/{@link EdgeFrameCodec} (and therefore to the best-JDK {@link H2HCodecs}).
 * A Netty pipeline that is faster but emits different wire bytes is disqualified.
 */
class NettyWireH2HCorrectnessTest {

    private static final int VALUE_BYTES = 64;
    private static final int ED25519_SIG_BYTES = 64;

    @Test
    void nettyConsensusSendWire_isByteIdenticalToEncodeWire() {
        int senderId = 3, groupId = 7;
        long term = 42L;
        for (int payloadBytes : new int[]{0, 256, 4096}) {
            MessageType type = (payloadBytes == 0) ? MessageType.HEARTBEAT : MessageType.APPEND_ENTRIES;
            byte[] payload = new byte[payloadBytes];
            for (int i = 0; i < payloadBytes; i++) {
                payload[i] = (byte) i;
            }
            byte[] expected = encodeWireReplica(senderId, type, groupId, term, payload);

            ByteBuf buf = Unpooled.buffer(4 + FrameCodec.frameSize(payloadBytes));
            try {
                NettyWireEncoders.encodeSendWireInto(buf, senderId, type, groupId, term, payload);
                byte[] actual = new byte[buf.readableBytes()];
                buf.getBytes(buf.readerIndex(), actual);
                assertArrayEquals(expected, actual,
                        "best-Netty consensus send must be byte-identical (payload=" + payloadBytes + ")");
            } finally {
                buf.release();
            }
        }
    }

    @Test
    void nettyFanOutNotify_isByteIdenticalToEdgeFrameCodec() {
        for (int notifyCount : new int[]{1, 16, 64}) {
            EdgeFrame.Notify frame = buildSignedNotify(notifyCount);
            byte[] expected = EdgeFrameCodec.encode(frame);

            ByteBuf buf = Unpooled.buffer(EdgeFrameCodec.MAX_NOTIFY_BATCH_BYTES + 64);
            try {
                NettyWireEncoders.encodeNotifyInto(buf, frame);
                byte[] actual = new byte[buf.readableBytes()];
                buf.getBytes(buf.readerIndex(), actual);
                assertArrayEquals(expected, actual,
                        "best-Netty NOTIFY encode must be byte-identical (count=" + notifyCount + ")");
                // And the bytes round-trip through the production decoder unchanged.
                EdgeFrame decoded = EdgeFrameCodec.decode(actual);
                assertEquals(notifyCount, ((EdgeFrame.Notify) decoded).notifications().size());
            } finally {
                buf.release();
            }
        }
    }

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

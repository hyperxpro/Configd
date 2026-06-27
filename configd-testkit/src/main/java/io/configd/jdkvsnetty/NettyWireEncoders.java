package io.configd.jdkvsnetty;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.FrameType;
import io.configd.store.CommandCodec;
import io.configd.store.ConfigDelta;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import io.netty.buffer.ByteBuf;

import java.util.List;
import java.util.zip.CRC32C;

/**
 * BEST-NETTY single-pass, into-{@link ByteBuf} encoders — the "Netty done properly" side of
 * the codec race. Each method writes the production wire bytes <b>directly into one pooled
 * {@code ByteBuf}</b> (the framework/caller obtains it from {@code PooledByteBufAllocator}),
 * exactly as a Netty {@code MessageToByteEncoder} with {@code preferDirect=true} would (see
 * {@code docs/jdk-vs-netty/netty42-api.md} §5.2). The CRC32C trailer is computed over a
 * zero-copy {@code nioBuffer} view of the written region — no intermediate {@code byte[]} for
 * the framing.
 *
 * <p><b>Byte-identity is the contract.</b> {@code NettyWireH2HCorrectnessTest} proves these
 * reproduce the exact bytes of the production {@code FrameCodec}/{@code EdgeFrameCodec} (and
 * therefore of the best-JDK {@link H2HCodecs}). The Netty {@code ByteBuf} default byte order is
 * BIG_ENDIAN, matching the JDK {@code ByteBuffer} default the production codecs use.
 *
 * <p><b>What these encoders, like the JDK ones, do NOT remove:</b> the message-building term —
 * {@link CommandCodec#encodeBatch} blobs and {@link ConfigDelta#signature()} /
 * {@link ConfigDelta#nonce()} defensive clones. Those are heap allocations <em>upstream of the
 * wire</em>; a pooled {@code ByteBuf} (off-heap, direct) does not touch them. This is the crux
 * the head-to-head measures: the pooled buffer only ever addresses the output-buffer term,
 * which a reused JDK buffer addresses just as well.
 */
final class NettyWireEncoders {

    private NettyWireEncoders() {
    }

    /**
     * A reused per-thread {@link CRC32C} — the strongest-Netty form. The JDK best-path's
     * {@code new CRC32C()} is escape-analyzed to zero allocation (it never escapes the reused
     * heap buffer's scalar-replaced scope); the Netty path's does NOT get scalar-replaced (the
     * pooled direct buffer escapes into {@code release()}), so allocating one per op would
     * unfairly tax Netty. Reusing it here gives Netty the same free CRC object EA gives JDK, so
     * the residual heap B/op we measure is the genuine pooled-{@code ByteBuf} cost, not a
     * strawman.
     */
    private static final ThreadLocal<CRC32C> CRC = ThreadLocal.withInitial(CRC32C::new);

    // ---------------------------------------------------------------------
    // Consensus (surface 4): [4B BE senderId] || FrameCodec frame
    // ---------------------------------------------------------------------

    /**
     * BEST-NETTY consensus send into a pooled {@code ByteBuf}: 4-byte big-endian sender id +
     * the Raft frame, byte-identical to {@code TcpRaftTransport.encodeWire}. Replicates the
     * {@link FrameCodec} layout directly into {@code out} (the codec has no ByteBuf overload).
     */
    static void encodeSendWireInto(ByteBuf out, int senderId, MessageType type,
                                   int groupId, long term, byte[] payload) {
        out.writeInt(senderId); // ByteBuf defaults to BIG_ENDIAN → identical to the bit-shift wrap
        int frameStart = out.writerIndex();
        int totalLength = FrameCodec.frameSize(payload.length);
        out.writeInt(totalLength);
        out.writeByte(FrameCodec.WIRE_VERSION);
        out.writeByte((byte) type.code());
        out.writeInt(groupId);
        out.writeLong(term);
        out.writeLong(0L); // v2/D1 reserved epoch — MBZ (dormant); byte-identical to FrameCodec.encode
        out.writeBytes(payload);
        CRC32C crc = CRC.get();
        crc.reset();
        crc.update(out.nioBuffer(frameStart, totalLength - FrameCodec.TRAILER_SIZE));
        out.writeInt((int) crc.getValue());
    }

    // ---------------------------------------------------------------------
    // Fan-out (surface 3): NOTIFY frame, single pass into one pooled ByteBuf
    // ---------------------------------------------------------------------

    /**
     * BEST-NETTY NOTIFY encode into a pooled {@code ByteBuf} — single pass, no intermediate
     * {@code List<byte[]>}, no per-notification buffer, no double payload/out array.
     * Byte-identical to {@link EdgeFrameCodec#encode(EdgeFrame)} for a NOTIFY frame.
     */
    static void encodeNotifyInto(ByteBuf out, EdgeFrame.Notify frame) {
        final int start = out.writerIndex();
        out.writeInt(0); // total-length placeholder, back-patched below
        out.writeByte(EdgeFrameCodec.EDGE_WIRE_VERSION);
        out.writeByte((byte) FrameType.NOTIFY.code());

        List<CommitNotification> ns = frame.notifications();
        out.writeInt(ns.size());
        for (CommitNotification n : ns) {
            ConfigDelta d = n.delta();
            // ---- message-building term (codec-internal; not removed by a pooled buffer) ----
            byte[] batch = CommandCodec.encodeBatch(d.mutations());
            byte[] sig = d.signature(); // defensive clone (null if unsigned)
            byte[] nonce = d.nonce();   // defensive clone (never null)
            // ---- framing written straight into the pooled buffer (no intermediates) ----
            out.writeLong(n.seq());
            out.writeLong(n.commitTimestampMillis());
            out.writeLong(d.fromVersion());
            out.writeLong(d.toVersion());
            out.writeInt(batch.length);
            out.writeBytes(batch);
            if (sig == null) {
                out.writeInt(-1); // explicit null sentinel, exactly as production
            } else {
                out.writeInt(sig.length);
                out.writeBytes(sig);
            }
            out.writeLong(d.epoch());
            out.writeInt(nonce.length);
            out.writeBytes(nonce);
        }

        int payloadEnd = out.writerIndex();
        int totalLen = (payloadEnd - start) + EdgeFrameCodec.TRAILER_SIZE;
        out.setInt(start, totalLen); // back-patch the length prefix
        CRC32C crc = CRC.get();
        crc.reset();
        crc.update(out.nioBuffer(start, payloadEnd - start)); // CRC over [length .. end-of-payload)
        out.writeInt((int) crc.getValue());
    }
}

package io.configd.jdkvsnetty;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.FrameType;
import io.configd.store.CommandCodec;
import io.configd.store.ConfigDelta;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * BEST-JDK single-pass, into-buffer encoders for the head-to-head - the "JDK done properly"
 * side of the race. Each method writes the production wire bytes <b>directly into one caller-
 * supplied buffer</b>, eliminating the intermediate {@code List<byte[]>}, per-element
 * {@code ByteBuffer}s, and double payload/out arrays that the status-quo codecs allocate. The
 * caller reuses the buffer across calls, so the steady-state allocation of the framing itself
 * is ~0 - no Netty, no new dependency.
 *
 * <p><b>Byte-identity is the contract.</b> {@code WireH2HCorrectnessTest} proves every method
 * here reproduces the exact bytes of the production {@code FrameCodec}/{@code EdgeFrameCodec}.
 * A faster encoder that changes the wire is disqualified.
 *
 * <p><b>What these encoders deliberately do NOT remove</b> (the codec-internal /
 * message-building term, central to the verdict): they still call
 * {@link CommandCodec#encodeBatch} (one blob per notification) and
 * {@link ConfigDelta#signature()} / {@link ConfigDelta#nonce()} (a defensive clone each),
 * because those are the public data-model API the production codec calls. That term is
 * <em>upstream of the wire</em> - neither a reused JDK buffer nor a pooled Netty {@code ByteBuf}
 * touches it. Removing it requires data-model into-variants (a JDK-side refactor, orthogonal to
 * the transport). The {@code messageBuildingFloor} bench leg measures exactly this residual.
 */
final class H2HCodecs {

    private H2HCodecs() {
    }

    // ---------------------------------------------------------------------
    // Consensus (surface 4): [4B big-endian senderId] || FrameCodec frame
    // ---------------------------------------------------------------------

    /**
     * BEST-JDK consensus send into a reused buffer: the 4-byte big-endian sender id followed
     * by the codec frame, via the existing {@link FrameCodec#encode(ByteBuffer, MessageType,
     * int, long, byte[])} into-variant. Byte-identical to {@code TcpRaftTransport.encodeWire}.
     *
     * @return the total number of bytes written (4 + frame size)
     */
    static int encodeSendWireInto(ByteBuffer out, int senderId, MessageType type,
                                  int groupId, long term, byte[] payload) {
        out.clear();
        out.putInt(senderId); // ByteBuffer defaults to BIG_ENDIAN -> identical to the bit-shift wrap
        FrameCodec.encode(out, type, groupId, term, payload);
        return out.position();
    }

    // ---------------------------------------------------------------------
    // Fan-out (surface 3): NOTIFY frame, single pass into one buffer
    // ---------------------------------------------------------------------

    /**
     * BEST-JDK NOTIFY encode into a reused heap buffer - single pass, no intermediate
     * {@code List<byte[]>}, no per-notification {@code ByteBuffer}, no payload-then-out double
     * array. Byte-identical to {@link EdgeFrameCodec#encode(EdgeFrame)} for a NOTIFY frame.
     *
     * <p>The buffer MUST be heap-backed ({@link ByteBuffer#allocate}) and large enough for the
     * frame; the caller sizes it once and reuses it.
     *
     * @return the total frame length written (including the 4-byte length prefix and CRC trailer)
     */
    static int encodeNotifyInto(ByteBuffer out, EdgeFrame.Notify frame) {
        out.clear();
        final int base = out.arrayOffset(); // 0 for allocate()
        out.putInt(0); // total-length placeholder, back-patched below
        out.put(EdgeFrameCodec.EDGE_WIRE_VERSION);
        out.put((byte) FrameType.NOTIFY.code());

        List<CommitNotification> ns = frame.notifications();
        out.putInt(ns.size());
        for (CommitNotification n : ns) {
            ConfigDelta d = n.delta();
            // message-building term (codec-internal; not removed by buffer reuse):
            byte[] batch = CommandCodec.encodeBatch(d.mutations());
            byte[] sig = d.signature(); // defensive clone (null if unsigned)
            byte[] nonce = d.nonce();   // defensive clone (never null)
            // framing written straight into the reused buffer, no intermediates:
            out.putLong(n.seq());
            out.putLong(n.commitTimestampMillis());
            out.putLong(d.fromVersion());
            out.putLong(d.toVersion());
            out.putInt(batch.length);
            out.put(batch);
            if (sig == null) {
                out.putInt(-1); // explicit null sentinel, exactly as production
            } else {
                out.putInt(sig.length);
                out.put(sig);
            }
            out.putLong(d.epoch());
            out.putInt(nonce.length);
            out.put(nonce);
        }

        int payloadEnd = out.position();
        int totalLen = payloadEnd + EdgeFrameCodec.TRAILER_SIZE;
        out.putInt(0, totalLen); // back-patch the length prefix at absolute index 0
        CRC32C crc = new CRC32C();
        crc.update(out.array(), base, payloadEnd); // CRC over [length .. end-of-payload)
        out.putInt((int) crc.getValue());
        return totalLen;
    }

    /** Copies the written prefix [0, length) of a heap buffer into a fresh exact-size array. */
    static byte[] toBytes(ByteBuffer buf, int length) {
        byte[] b = new byte[length];
        System.arraycopy(buf.array(), buf.arrayOffset(), b, 0, length);
        return b;
    }
}

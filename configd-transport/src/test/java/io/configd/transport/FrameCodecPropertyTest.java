package io.configd.transport;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.ByteRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jqwik property-based fuzz suite for {@link FrameCodec}.
 *
 * <p>Closes the codec-bounds requirement of Phase 4 (gap-closure §6,
 * row "jqwik for codec bounds"). Targets the wire boundary of the
 * Raft transport — every property covers an adversarial input class
 * that could plausibly arrive on a TLS-terminated socket.
 */
class FrameCodecPropertyTest {

    /**
     * Encode/decode roundtrip preserves all four header fields and the
     * payload bytes, for any well-formed input.
     */
    @Property(tries = 500)
    void encodeDecodeRoundtripPreservesAllFields(
            @ForAll MessageType type,
            @ForAll int groupId,
            @ForAll long term,
            @ForAll @Size(max = 8192) byte[] payload) {

        byte[] frame = FrameCodec.encode(type, groupId, term, payload);
        FrameCodec.Frame decoded = FrameCodec.decode(frame);

        assertEquals(type, decoded.messageType());
        assertEquals(groupId, decoded.groupId());
        assertEquals(term, decoded.term());
        assertArrayEquals(payload, decoded.payload());
    }

    /**
     * The encoded frame's declared length always equals its actual length —
     * a peer that trusts the length prefix to allocate its read buffer must
     * never see an off-by-one or sign-extension mismatch.
     */
    @Property(tries = 500)
    void encodedLengthFieldMatchesActualLength(
            @ForAll MessageType type,
            @ForAll int groupId,
            @ForAll long term,
            @ForAll @Size(max = 8192) byte[] payload) {

        byte[] frame = FrameCodec.encode(type, groupId, term, payload);
        int peeked = FrameCodec.peekLength(frame);
        assertEquals(frame.length, peeked);
        assertEquals(FrameCodec.frameSize(payload.length), frame.length);
    }

    /**
     * The ByteBuffer encode overload produces byte-identical output to the
     * byte[] overload — these two paths must not diverge or a peer using
     * the buffer overload could be silently incompatible with one using
     * the array overload.
     */
    @Property(tries = 200)
    void byteBufferAndArrayEncodeProduceIdenticalBytes(
            @ForAll MessageType type,
            @ForAll int groupId,
            @ForAll long term,
            @ForAll @Size(max = 4096) byte[] payload) {

        byte[] viaArray = FrameCodec.encode(type, groupId, term, payload);

        ByteBuffer buf = ByteBuffer.allocate(FrameCodec.frameSize(payload.length));
        FrameCodec.encode(buf, type, groupId, term, payload);
        buf.flip();
        byte[] viaBuffer = new byte[buf.remaining()];
        buf.get(viaBuffer);

        assertArrayEquals(viaArray, viaBuffer);
    }

    /**
     * Truncating any encoded frame by even one byte must be rejected:
     * partial frames at the application layer are an attack vector and
     * a confused-deputy hazard.
     */
    @Property(tries = 200)
    void truncatedFrameIsRejected(
            @ForAll MessageType type,
            @ForAll int groupId,
            @ForAll long term,
            @ForAll @Size(min = 1, max = 256) byte[] payload,
            @ForAll @IntRange(min = 1, max = 256) int truncateBy) {

        byte[] frame = FrameCodec.encode(type, groupId, term, payload);
        int newLen = Math.max(0, frame.length - truncateBy);
        byte[] truncated = Arrays.copyOf(frame, newLen);

        assertThrows(Exception.class, () -> FrameCodec.decode(truncated));
    }

    /**
     * Any unknown type code (i.e. not a defined MessageType ordinal) must
     * raise — a peer must never accept a frame with a type it does not
     * understand because that would silently bypass dispatch authorisation.
     */
    @Property(tries = 200)
    void unknownTypeCodeIsRejected(
            @ForAll @ByteRange(min = 0, max = 127) byte rawType,
            @ForAll int groupId,
            @ForAll long term,
            @ForAll @Size(max = 64) byte[] payload) {

        boolean known = false;
        for (MessageType t : MessageType.values()) {
            if (t.code() == (rawType & 0xFF)) { known = true; break; }
        }
        if (known) return;  // skip well-known codes; covered by roundtrip property

        // Hand-craft a frame with the bogus type code, including a valid CRC32C
        // trailer so the decoder rejects on type, not on checksum.
        int total = FrameCodec.HEADER_SIZE + payload.length + FrameCodec.TRAILER_SIZE;
        ByteBuffer buf = ByteBuffer.allocate(total);
        buf.putInt(total);
        buf.put(FrameCodec.WIRE_VERSION);
        buf.put(rawType);
        buf.putInt(groupId);
        buf.putLong(term);
        buf.put(payload);
        byte[] frame = buf.array();
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(frame, 0, total - FrameCodec.TRAILER_SIZE);
        ByteBuffer.wrap(frame, total - FrameCodec.TRAILER_SIZE, FrameCodec.TRAILER_SIZE)
                .putInt((int) crc.getValue());

        assertThrows(Exception.class, () -> FrameCodec.decode(frame));
    }

    /**
     * §8.10 (R-005/R-007/R-008): a frame whose wire-version byte differs
     * from {@link FrameCodec#WIRE_VERSION} must be rejected at the decode
     * boundary with {@link FrameCodec.UnsupportedWireVersionException}.
     * A peer that silently downgrades to a foreign wire version is the
     * canonical "smuggle past the deprecation cycle" failure mode.
     */
    @Property(tries = 200)
    void unknownWireVersionIsRejected(
            @ForAll MessageType type,
            @ForAll int groupId,
            @ForAll long term,
            @ForAll @Size(max = 64) byte[] payload,
            @ForAll @ByteRange(min = 0, max = 127) byte rawVersion) {

        if (rawVersion == FrameCodec.WIRE_VERSION) return;  // skip the legitimate version

        // Hand-craft a frame with the bogus wire-version byte and a valid
        // CRC32C trailer so the decoder rejects on version, not on checksum.
        int total = FrameCodec.HEADER_SIZE + payload.length + FrameCodec.TRAILER_SIZE;
        ByteBuffer buf = ByteBuffer.allocate(total);
        buf.putInt(total);
        buf.put(rawVersion);
        buf.put((byte) type.code());
        buf.putInt(groupId);
        buf.putLong(term);
        buf.put(payload);
        byte[] frame = buf.array();
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(frame, 0, total - FrameCodec.TRAILER_SIZE);
        ByteBuffer.wrap(frame, total - FrameCodec.TRAILER_SIZE, FrameCodec.TRAILER_SIZE)
                .putInt((int) crc.getValue());

        FrameCodec.UnsupportedWireVersionException ex = assertThrows(
                FrameCodec.UnsupportedWireVersionException.class,
                () -> FrameCodec.decode(frame));
        assertEquals(rawVersion & 0xFF, ex.observedVersion());
    }

    /**
     * A frame whose declared length disagrees with its actual length must
     * be rejected, even if every other field is well-formed.
     */
    @Property(tries = 200)
    void lengthFieldMismatchIsRejected(
            @ForAll MessageType type,
            @ForAll int groupId,
            @ForAll long term,
            @ForAll @Size(min = 0, max = 256) byte[] payload,
            @ForAll @IntRange(min = -1024, max = 1024) int delta) {

        if (delta == 0) return;

        byte[] frame = FrameCodec.encode(type, groupId, term, payload);
        int corrupted = frame.length + delta;
        if (corrupted < 0 || corrupted > Integer.MAX_VALUE - 8) return;

        // Overwrite the length prefix in-place with a wrong value.
        ByteBuffer.wrap(frame).putInt(corrupted);

        assertThrows(Exception.class, () -> FrameCodec.decode(frame));
    }

    /**
     * Any payload up to a generous upper bound encodes and decodes back
     * byte-for-byte — there is no implicit truncation, no sign-extension
     * surprise on the int-length field for large payloads.
     */
    @Property(tries = 50)
    void largePayloadsRoundtripWithoutTruncation(
            @ForAll @IntRange(min = 0, max = 1_000_000) int size) {

        byte[] payload = new byte[size];
        for (int i = 0; i < size; i++) payload[i] = (byte) (i & 0xFF);

        byte[] frame = FrameCodec.encode(MessageType.APPEND_ENTRIES, 1, 1L, payload);
        FrameCodec.Frame decoded = FrameCodec.decode(frame);
        assertEquals(size, decoded.payload().length);
        assertArrayEquals(payload, decoded.payload());
    }

    /**
     * peekLength on any prefix of a valid frame returns exactly the same
     * value as the full-frame decode path — a streaming reader can rely on
     * the header prefix alone to size its buffer.
     */
    @Property(tries = 200)
    void peekLengthAgreesWithFullDecode(
            @ForAll MessageType type,
            @ForAll int groupId,
            @ForAll long term,
            @ForAll @Size(max = 1024) byte[] payload) {

        byte[] frame = FrameCodec.encode(type, groupId, term, payload);
        byte[] header = Arrays.copyOf(frame, FrameCodec.HEADER_SIZE);
        assertEquals(FrameCodec.peekLength(header), frame.length);
    }

    @Property(tries = 100)
    void groupIdAndTermAreSignedAndPreserved(
            @ForAll @LongRange(min = Long.MIN_VALUE, max = Long.MAX_VALUE) long term,
            @ForAll @IntRange(min = Integer.MIN_VALUE, max = Integer.MAX_VALUE) int groupId) {

        byte[] frame = FrameCodec.encode(MessageType.HEARTBEAT, groupId, term, new byte[0]);
        FrameCodec.Frame decoded = FrameCodec.decode(frame);
        assertEquals(groupId, decoded.groupId());
        assertEquals(term, decoded.term());
    }

    @Provide
    Arbitrary<MessageType> messageType() {
        return Arbitraries.of(MessageType.values());
    }

    @Property(tries = 1)
    void minimumFrameIsHeaderPlusTrailerOnly() {
        byte[] frame = FrameCodec.encode(MessageType.HEARTBEAT, 0, 0L, new byte[0]);
        assertEquals(FrameCodec.HEADER_SIZE + FrameCodec.TRAILER_SIZE, frame.length);
        assertTrue(FrameCodec.decode(frame).payload().length == 0);
    }
}

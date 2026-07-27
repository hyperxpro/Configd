package io.configd.transport;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Adversarial fuzz suite for {@link FrameCodec#decode} and the
 * {@link TcpRaftTransport} socket read-loop length check.
 *
 * <p>This complements (does NOT duplicate) {@link FrameCodecPropertyTest},
 * which proves <em>structural</em> properties (round-trip, truncation, unknown
 * type/version, length mismatch). This suite adds the security
 * <b>resource oracle</b>: for arbitrary and adversarially-mutated input, decode
 * must EITHER return a well-formed {@link FrameCodec.Frame} OR throw one of a
 * small, <b>bounded, expected</b> exception set - and must NEVER:
 * <ul>
 *   <li>throw {@link OutOfMemoryError} (unbounded allocation),</li>
 *   <li>throw {@link NullPointerException},
 *       {@link ArrayIndexOutOfBoundsException},
 *       {@link NegativeArraySizeException} (un-validated index / size math),</li>
 *   <li>hang (caught by {@link org.junit.jupiter.api.Assertions#assertTimeoutPreemptively}).</li>
 * </ul>
 *
 * <p>The allowed exceptions are exactly those the decode contract documents:
 * {@link IllegalArgumentException} (framing/length/CRC/unknown-type),
 * {@link FrameCodec.UnsupportedWireVersionException} (bad version), and
 * {@link BufferUnderflowException} (a {@link ByteBuffer} read past its limit on a
 * structurally short frame - a benign, bounded JDK exception, never a memory or
 * index-corruption hazard). {@code FrameCodec.decode} guards length before any
 * buffer read, so in practice underflow is unreachable from {@code decode}; it is
 * admitted defensively so a future refactor that relaxes a guard fails LOUD here
 * rather than silently widening the contract.
 *
 * <p><b>Determinism.</b> Each {@code @Property} pins a fixed {@code seed} so a
 * failing input is reproducible from the report and lands in the committed
 * corpus, matching the project's golden-fixture discipline (a failing seed is a
 * regression seed). jqwik also auto-persists failing seeds; the fixed seed makes
 * the nightly lane byte-stable.
 */
class FrameCodecFuzzTest {

    /** Bounded per-decode timeout. Decode is microseconds; 2 s catches a true hang. */
    private static final Duration DECODE_BUDGET = Duration.ofSeconds(2);

    private static final int MIN_FRAME = FrameCodec.HEADER_SIZE + FrameCodec.TRAILER_SIZE; // 30 (v2: 26+4)


    /**
     * Wholly arbitrary byte arrays of widely varied sizes (empty, 1 byte,
     * header-minus-one, header, around MIN_FRAME, and small payloads) must
     * satisfy the resource oracle: a {@link FrameCodec.Frame} or a bounded
     * expected throwable, within the time budget, never a forbidden error.
     */
    @Property(tries = 2000, seed = "424242")
    void arbitraryBytesNeverViolateTheOracle(@ForAll("adversarialSized") byte[] data) {
        assertOracleHolds(data);
    }

    /**
     * Same oracle, but seeded near the frame boundaries (exactly 0, 1,
     * HEADER_SIZE-1, HEADER_SIZE, MIN_FRAME-1, MIN_FRAME, MIN_FRAME+1) where
     * off-by-one index math is most likely to misbehave.
     */
    @Property(tries = 700, seed = "20260614")
    void boundarySizedArbitraryBytesNeverViolateTheOracle(
            @ForAll("boundarySized") byte[] data) {
        assertOracleHolds(data);
    }

    /**
     * peekLength must satisfy the same oracle on arbitrary input - it is the
     * read-path's pre-allocation gate, so a forbidden error here is a
     * pre-allocation crash.
     */
    @Property(tries = 1500, seed = "7777")
    void peekLengthNeverViolatesTheOracle(@ForAll("adversarialSized") byte[] data) {
        assertTimeoutPreemptively(DECODE_BUDGET, () -> {
            try {
                int len = FrameCodec.peekLength(data);
                // A returned length is bounds-checked by contract.
                assertTrue(len >= MIN_FRAME && len <= FrameCodec.MAX_FRAME_SIZE,
                        "peekLength returned out-of-range " + len);
            } catch (IllegalArgumentException expected) {
                // bounded, documented
            } catch (Throwable t) {
                failForbidden("peekLength", data, t);
            }
        });
    }


    /**
     * Flip the 4-byte length prefix to a hostile value (smaller, larger,
     * negative, Integer.MAX_VALUE, MAX_FRAME_SIZE+1). Each must be rejected
     * cleanly - never silently accepted, never a forbidden error, never a hang.
     */
    @Property(tries = 600, seed = "1001")
    void lengthPrefixLieIsRejectedCleanly(
            @ForAll("validFrames") byte[] valid,
            @ForAll("hostileLengths") int hostileLength) {
        // If the hostile value happens to equal the frame's real length, the
        // prefix is unchanged and the frame legitimately decodes - that is not a
        // "lie", so skip it. Every other value is a genuine length lie.
        if (hostileLength == valid.length) {
            return;
        }
        byte[] frame = valid.clone();
        ByteBuffer.wrap(frame).putInt(hostileLength);
        assertTimeoutPreemptively(DECODE_BUDGET, () -> {
            try {
                FrameCodec.decode(frame);
                fail("a length-prefix lie (" + hostileLength
                        + ") must not decode to a Frame");
            } catch (IllegalArgumentException expected) {
                // framing/length/CRC rejection - correct
            } catch (Throwable t) {
                failForbidden("length-lie decode", frame, t);
            }
        });
    }

    /** Corrupt the version byte (offset 4) and leave the CRC stale - rejected. */
    @Property(tries = 400, seed = "1002")
    void corruptVersionByteIsRejectedCleanly(
            @ForAll("validFrames") byte[] valid,
            @ForAll @IntRange(min = 1, max = 255) int delta) {
        byte[] frame = valid.clone();
        frame[4] = (byte) (frame[4] + delta);
        assertRejectedCleanly(frame, "corrupt-version");
    }

    /** Corrupt the type code (offset 5) and leave the CRC stale - rejected. */
    @Property(tries = 400, seed = "1003")
    void corruptTypeByteIsRejectedCleanly(
            @ForAll("validFrames") byte[] valid,
            @ForAll @IntRange(min = 1, max = 255) int delta) {
        byte[] frame = valid.clone();
        frame[5] = (byte) (frame[5] + delta);
        assertRejectedCleanly(frame, "corrupt-type");
    }

    /** Flip any single CRC-trailer byte - CRC mismatch, rejected cleanly. */
    @Property(tries = 400, seed = "1004")
    void flippedCrcByteIsRejectedCleanly(
            @ForAll("validFrames") byte[] valid,
            @ForAll @IntRange(min = 0, max = 3) int trailerByte) {
        byte[] frame = valid.clone();
        int idx = frame.length - FrameCodec.TRAILER_SIZE + trailerByte;
        frame[idx] ^= 0x01;
        assertRejectedCleanly(frame, "flipped-crc");
    }

    /**
     * Truncate a valid frame at EVERY offset (0 .. length-1). Each prefix must
     * be rejected cleanly - a partial frame must never decode and never crash.
     */
    @Property(tries = 1, seed = "1005")
    void truncateAtEveryOffsetIsRejectedCleanly(@ForAll("validFrames") byte[] valid) {
        for (int cut = 0; cut < valid.length; cut++) {
            byte[] truncated = Arrays.copyOf(valid, cut);
            assertTimeoutPreemptively(DECODE_BUDGET, () -> {
                try {
                    FrameCodec.decode(truncated);
                    fail("truncated frame (" + truncated.length
                            + " bytes) must not decode");
                } catch (IllegalArgumentException | BufferUnderflowException expected) {
                    // bounded
                } catch (Throwable t) {
                    failForbidden("truncate", truncated, t);
                }
            });
        }
    }

    /**
     * Appending trailing garbage to a valid frame makes {@code data.length} no
     * longer equal the declared length - rejected as a length mismatch.
     */
    @Property(tries = 300, seed = "1006")
    void trailingGarbageIsRejectedCleanly(
            @ForAll("validFrames") byte[] valid,
            @ForAll @Size(min = 1, max = 64) byte[] garbage) {
        byte[] frame = Arrays.copyOf(valid, valid.length + garbage.length);
        System.arraycopy(garbage, 0, frame, valid.length, garbage.length);
        assertRejectedCleanly(frame, "trailing-garbage");
    }


    /**
     * The OOM lever at the CODEC level: a length prefix at exactly
     * {@code MAX_FRAME_SIZE + 1} or {@code Integer.MAX_VALUE} must be rejected by
     * {@link FrameCodec#decode} / {@link FrameCodec#peekLength} with an
     * {@link IllegalArgumentException} - BEFORE any large allocation. We assert
     * the rejection happens with only a tiny (header-sized) input present, so no
     * multi-MiB buffer is required to trigger it.
     */
    @Property(tries = 1, seed = "1007")
    void oversizeLengthIsRejectedBeforeAllocationAtCodec() {
        for (int hostile : new int[]{
                FrameCodec.MAX_FRAME_SIZE + 1, Integer.MAX_VALUE,
                Integer.MIN_VALUE, -1, 0, MIN_FRAME - 1}) {
            byte[] header = new byte[4];
            ByteBuffer.wrap(header).putInt(hostile);
            assertThrows(IllegalArgumentException.class,
                    () -> FrameCodec.peekLength(header),
                    "peekLength must reject hostile length " + hostile + " pre-alloc");

            byte[] headerOnly = new byte[MIN_FRAME];
            ByteBuffer.wrap(headerOnly).putInt(hostile);
            assertThrows(IllegalArgumentException.class,
                    () -> FrameCodec.decode(headerOnly),
                    "decode must reject hostile length " + hostile + " pre-alloc");
        }
    }

    /**
     * Boundary fuzzing of the length field: exactly {@code MIN_FRAME} and exactly
     * {@code MAX_FRAME_SIZE} are accepted by peekLength; one below MIN_FRAME and
     * one above MAX_FRAME_SIZE are rejected. (We test peekLength here because a
     * real {@code MAX_FRAME_SIZE}-byte decode buffer would be 16 MiB - peekLength
     * exercises the identical bound without the allocation.)
     */
    @Property(tries = 1, seed = "1008")
    void lengthBoundariesBehaveExactly() {
        assertEquals(MIN_FRAME, peekOf(MIN_FRAME));
        assertEquals(FrameCodec.MAX_FRAME_SIZE, peekOf(FrameCodec.MAX_FRAME_SIZE));
        assertThrows(IllegalArgumentException.class, () -> peekOf(MIN_FRAME - 1));
        assertThrows(IllegalArgumentException.class, () -> peekOf(FrameCodec.MAX_FRAME_SIZE + 1));
    }

    private static int peekOf(int declaredLength) {
        byte[] header = new byte[4];
        ByteBuffer.wrap(header).putInt(declaredLength);
        return FrameCodec.peekLength(header);
    }


    /**
     * The {@link TcpRaftTransport} steady-state read loop (the only place a raw
     * peer length prefix sizes an allocation) is reachable solely via a live
     * socket + DataInputStream. Rather than spin up TLS sockets on the 2-vCPU box
     * (flaky), this test drives a <b>faithful extraction</b> of that loop's
     * length check - byte-for-byte the guard at {@code TcpRaftTransport} lines
     * 346-353:
     * <pre>
     *   int frameLength = in.readInt();
     *   if (frameLength &lt; HEADER_SIZE + TRAILER_SIZE
     *           || frameLength &gt; MAX_FRAME_SIZE) throw new IOException(...);
     *   byte[] frameBytes = new byte[frameLength];   // bounded
     * </pre>
     * The check runs over the same {@code DataInputStream.readInt()} the real
     * loop uses, fed by an in-memory stream, so the int decoding (including
     * negative / Integer.MAX_VALUE sign handling) is identical to production.
     *
     * <p>Oracle: for a hostile length prefix, the guard rejects with
     * {@link IOException} BEFORE {@code new byte[frameLength]} executes - proven by
     * the fact that no array is ever sized from the hostile value (the helper
     * returns the would-be allocation size only on the accept path; on reject it
     * throws). MAX_FRAME_SIZE caps any accepted allocation at 16 MiB.
     */
    @Property(tries = 1, seed = "1009")
    void readLoopRejectsHostileLengthBeforeAllocation() throws IOException {
        int[] hostile = {
                Integer.MIN_VALUE, -1, 0, MIN_FRAME - 1,
                FrameCodec.MAX_FRAME_SIZE + 1, Integer.MAX_VALUE,
        };
        for (int len : hostile) {
            assertThrows(IOException.class,
                    () -> readLoopLengthGate(len),
                    "read loop must reject hostile frameLength " + len
                            + " before allocating");
        }
        // Boundary accept side: exactly MIN_FRAME and exactly MAX_FRAME_SIZE are
        // accepted, and the would-be allocation equals the declared length and is
        // capped at 16 MiB. We do NOT allocate the 16 MiB buffer; the gate returns
        // the size it WOULD allocate, proving the cap holds.
        assertEquals(MIN_FRAME, readLoopLengthGate(MIN_FRAME));
        assertEquals(FrameCodec.MAX_FRAME_SIZE, readLoopLengthGate(FrameCodec.MAX_FRAME_SIZE));
        assertTrue(readLoopLengthGate(FrameCodec.MAX_FRAME_SIZE) <= FrameCodec.MAX_FRAME_SIZE);
    }

    /**
     * Faithful extraction of {@code TcpRaftTransport.handleInboundConnection}'s
     * frame-length gate. Reads the length via {@link DataInputStream#readInt()}
     * (identical to production) and applies the identical bound. Returns the size
     * the production code would pass to {@code new byte[frameLength]} on accept;
     * throws {@link IOException} on reject - exactly as production does, and
     * exactly BEFORE the allocation site.
     */
    private static int readLoopLengthGate(int declaredLength) throws IOException {
        byte[] wire = new byte[4];
        ByteBuffer.wrap(wire).putInt(declaredLength);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(wire));
        int frameLength = in.readInt();
        if (frameLength < FrameCodec.HEADER_SIZE + FrameCodec.TRAILER_SIZE
                || frameLength > FrameCodec.MAX_FRAME_SIZE) {
            throw new IOException("Invalid frame length: " + frameLength);
        }
        return frameLength;
    }


    private static void assertOracleHolds(byte[] data) {
        assertTimeoutPreemptively(DECODE_BUDGET, () -> {
            try {
                FrameCodec.Frame frame = FrameCodec.decode(data);
                assertNotNull(frame, "decode returned a null Frame");
                assertNotNull(frame.messageType(), "decoded Frame has null messageType");
                assertNotNull(frame.payload(), "decoded Frame has null payload");
            } catch (IllegalArgumentException
                     | FrameCodec.UnsupportedWireVersionException
                     | BufferUnderflowException expected) {
                // bounded, documented - the allowed rejection set
            } catch (Throwable t) {
                failForbidden("decode", data, t);
            }
        });
    }

    /**
     * Asserts a CRC-valid-or-not mutated frame is rejected with only a
     * bounded/expected throwable (and never accepted as a different valid frame).
     */
    private static void assertRejectedCleanly(byte[] frame, String label) {
        assertTimeoutPreemptively(DECODE_BUDGET, () -> {
            try {
                FrameCodec.decode(frame);
                fail(label + ": mutated frame must not decode to a Frame");
            } catch (IllegalArgumentException
                     | FrameCodec.UnsupportedWireVersionException
                     | BufferUnderflowException expected) {
                // bounded
            } catch (Throwable t) {
                failForbidden(label, frame, t);
            }
        });
    }

    private static void failForbidden(String op, byte[] data, Throwable t) {
        if (t instanceof AssertionError ae) {
            throw ae; // a fail()/assert from inside the try - propagate verbatim
        }
        fail(op + " produced FORBIDDEN throwable " + t.getClass().getName()
                + " on input " + describe(data) + ": " + t.getMessage(), t);
    }

    private static String describe(byte[] data) {
        String hex = HexFormat.of().formatHex(data, 0, Math.min(data.length, 48));
        return "len=" + data.length + " hex=" + hex
                + (data.length > 48 ? "..." : "");
    }


    /**
     * Arbitrary byte arrays whose SIZE distribution is weighted toward the
     * adversarial zone (empty, 1, header-ish, around MIN_FRAME) plus small
     * random sizes. Content is fully arbitrary.
     */
    @Provide
    Arbitrary<byte[]> adversarialSized() {
        Arbitrary<Integer> sizes = Arbitraries.frequency(
                net.jqwik.api.Tuple.of(3, 0),
                net.jqwik.api.Tuple.of(3, 1),
                net.jqwik.api.Tuple.of(2, FrameCodec.HEADER_SIZE - 1),
                net.jqwik.api.Tuple.of(2, FrameCodec.HEADER_SIZE),
                net.jqwik.api.Tuple.of(2, MIN_FRAME - 1),
                net.jqwik.api.Tuple.of(3, MIN_FRAME),
                net.jqwik.api.Tuple.of(3, MIN_FRAME + 1),
                net.jqwik.api.Tuple.of(4, 32),
                net.jqwik.api.Tuple.of(4, 64),
                net.jqwik.api.Tuple.of(2, 256))
                .flatMap(max -> Arbitraries.integers().between(0, Math.max(0, max)));
        return sizes.flatMap(this::randomBytesOfSize);
    }

    @Provide
    Arbitrary<byte[]> boundarySized() {
        return Arbitraries.of(
                        0, 1, 2, 3, 4,
                        FrameCodec.HEADER_SIZE - 1, FrameCodec.HEADER_SIZE,
                        MIN_FRAME - 1, MIN_FRAME, MIN_FRAME + 1)
                .flatMap(this::randomBytesOfSize);
    }

    private Arbitrary<byte[]> randomBytesOfSize(int size) {
        if (size <= 0) {
            return Arbitraries.just(new byte[0]);
        }
        return Arbitraries.bytes().array(byte[].class).ofSize(size);
    }

    @Provide
    Arbitrary<byte[]> validFrames() {
        Arbitrary<MessageType> type = Arbitraries.of(MessageType.values());
        Arbitrary<Integer> groupId = Arbitraries.integers();
        Arbitrary<Long> term = Arbitraries.longs();
        Arbitrary<byte[]> payload = Arbitraries.bytes().array(byte[].class).ofMaxSize(64);
        return net.jqwik.api.Combinators.combine(type, groupId, term, payload)
                .as(FrameCodec::encode);
    }

    /**
     * Hostile values for the 4-byte length prefix: a representative spread of
     * "smaller", "larger", "negative", "Integer.MAX_VALUE", and "just past the
     * cap". {@code 0} and small positives are also "smaller" cases.
     */
    @Provide
    Arbitrary<Integer> hostileLengths() {
        return Arbitraries.of(
                Integer.MIN_VALUE,
                -1,
                0,
                1,
                MIN_FRAME - 1,
                MIN_FRAME,            // valid magnitude but won't match a non-MIN_FRAME body
                MIN_FRAME + 1,
                1024,
                FrameCodec.MAX_FRAME_SIZE,
                FrameCodec.MAX_FRAME_SIZE + 1,
                Integer.MAX_VALUE);
    }

    /** Sanity: a known-good frame round-trips (anchors the corruption properties). */
    @Property(tries = 1, seed = "1010")
    void knownGoodFrameStillDecodes() {
        byte[] frame = FrameCodec.encode(MessageType.APPEND_ENTRIES, 7, 99L, new byte[]{1, 2, 3});
        FrameCodec.Frame f = FrameCodec.decode(frame);
        assertEquals(MessageType.APPEND_ENTRIES, f.messageType());
        assertEquals(7, f.groupId());
        assertEquals(99L, f.term());
    }
}

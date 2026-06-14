package io.configd.distribution.wire;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Adversarial fuzz suite for {@link EdgeFrameCodec#decode} and
 * {@link EdgeFrameCodec#peekLength} (S7 charter §6).
 *
 * <p>Complements — does NOT duplicate — {@link EdgeFrameCodecPropertyTest},
 * which already proves round-trip fidelity, truncation, single-bit corruption,
 * oversize-length-at-peek, length mismatch, and wrong-version. This suite adds
 * the security <b>resource oracle</b> for wholly arbitrary / adversarial input.
 *
 * <p><b>The edge contract is strictly narrower than the Raft codec's.</b>
 * {@link EdgeFrameCodec#decode} catches every {@link RuntimeException} from a
 * structurally-valid-but-malformed payload and re-wraps it as a
 * {@link EdgeFrameCodec.CodecException} (FRAME_CORRUPT). So for any non-null
 * input the ONLY permitted escape is a {@code CodecException}. Anything else —
 * {@link OutOfMemoryError}, {@link NullPointerException},
 * {@link ArrayIndexOutOfBoundsException}, {@link NegativeArraySizeException}, a
 * raw {@link java.nio.BufferUnderflowException}, or a hang — is a defect. This is
 * a stronger oracle than the Raft one precisely because the edge codec promises
 * a single typed failure mode to its session layer.
 *
 * <p>Determinism: each {@code @Property} pins a fixed {@code seed}; failing
 * inputs land in the committed corpus.
 */
class EdgeFrameCodecFuzzTest {

    private static final Duration DECODE_BUDGET = Duration.ofSeconds(2);
    private static final int MIN_FRAME = EdgeFrameCodec.HEADER_SIZE + EdgeFrameCodec.TRAILER_SIZE; // 10

    // -----------------------------------------------------------------------
    // 1. Arbitrary bytes.
    // -----------------------------------------------------------------------

    @Property(tries = 2000, seed = "424242")
    void arbitraryBytesYieldFrameOrCodecExceptionOnly(@ForAll("adversarialSized") byte[] data) {
        assertOracleHolds(data);
    }

    @Property(tries = 700, seed = "20260614")
    void boundarySizedArbitraryBytesYieldFrameOrCodecExceptionOnly(
            @ForAll("boundarySized") byte[] data) {
        assertOracleHolds(data);
    }

    @Property(tries = 1500, seed = "7777")
    void peekLengthYieldsLengthOrCodecExceptionOnly(@ForAll("adversarialSized") byte[] data) {
        assertTimeoutPreemptively(DECODE_BUDGET, () -> {
            try {
                int len = EdgeFrameCodec.peekLength(data);
                assertTrue(len >= MIN_FRAME && len <= EdgeFrameCodec.MAX_EDGE_FRAME_SIZE,
                        "peekLength returned out-of-range " + len);
            } catch (EdgeFrameCodec.CodecException expected) {
                // bounded, documented
            } catch (Throwable t) {
                failForbidden("peekLength", data, t);
            }
        });
    }

    // -----------------------------------------------------------------------
    // 2. Structured length-lie on a valid frame.
    // -----------------------------------------------------------------------

    @Property(tries = 500, seed = "1001")
    void lengthPrefixLieIsRejectedAsCodecException(
            @ForAll("validFrames") byte[] valid,
            @ForAll("hostileLengths") int hostileLength) {
        if (hostileLength == valid.length) {
            return; // not a lie — unchanged prefix
        }
        byte[] frame = valid.clone();
        ByteBuffer.wrap(frame).putInt(hostileLength);
        assertTimeoutPreemptively(DECODE_BUDGET, () -> {
            try {
                EdgeFrameCodec.decode(frame);
                fail("a length-prefix lie (" + hostileLength + ") must not decode");
            } catch (EdgeFrameCodec.CodecException expected) {
                // correct
            } catch (Throwable t) {
                failForbidden("length-lie decode", frame, t);
            }
        });
    }

    /**
     * Corrupt a structured-payload field length (e.g. the SUBSCRIBE prefix count,
     * NOTIFY count, or a string length) to a hostile value by flipping payload
     * bytes, then repair the CRC so the codec must rely on its internal
     * payload-bounds checks (not the CRC) to reject. This is the inner-allocation
     * amplification guard for the edge codec.
     */
    @Property(tries = 400, seed = "1002")
    void payloadInnerLengthLieRejectedAfterCrcRepair(
            @ForAll("validFrames") byte[] valid,
            @ForAll @IntRange(min = 0, max = 4095) int offsetSeed,
            @ForAll int hostile) {
        // Frames with no payload (e.g. min-size) have nothing to corrupt here.
        int payloadStart = EdgeFrameCodec.HEADER_SIZE;
        int payloadEnd = valid.length - EdgeFrameCodec.TRAILER_SIZE;
        int payloadLen = payloadEnd - payloadStart;
        if (payloadLen < 4) {
            return;
        }
        byte[] frame = valid.clone();
        int at = payloadStart + (offsetSeed % (payloadLen - 3)); // room for a 4-byte int
        ByteBuffer.wrap(frame).putInt(at, hostile);
        repairCrc(frame);
        // With the CRC valid, the codec must still reject any structurally bad
        // inner length via its own bounds checks — and only as a CodecException.
        assertTimeoutPreemptively(DECODE_BUDGET, () -> {
            try {
                EdgeFrame f = EdgeFrameCodec.decode(frame);
                // It is legitimate that a few random int writes still yield a
                // valid frame (e.g. flipping a seq number). The oracle only
                // forbids a forbidden throwable; a successful decode is fine.
                assertNotNull(f);
            } catch (EdgeFrameCodec.CodecException expected) {
                // correct — inner bounds check fired
            } catch (Throwable t) {
                failForbidden("inner-length-lie", frame, t);
            }
        });
    }

    // -----------------------------------------------------------------------
    // 3 & 4. Oversize / boundary length at the codec level.
    // -----------------------------------------------------------------------

    /**
     * The OOM lever at the edge codec: a length prefix at MAX_EDGE_FRAME_SIZE+1 or
     * Integer.MAX_VALUE is rejected by peekLength/decode BEFORE allocation. Over
     * the cap surfaces as FRAME_TOO_LARGE; below the minimum as FRAME_CORRUPT.
     */
    @Property(tries = 1, seed = "1007")
    void oversizeLengthRejectedBeforeAllocation() {
        record Case(int len, ErrorCode expected) {}
        Case[] cases = {
                new Case(EdgeFrameCodec.MAX_EDGE_FRAME_SIZE + 1, ErrorCode.FRAME_TOO_LARGE),
                new Case(Integer.MAX_VALUE, ErrorCode.FRAME_TOO_LARGE),
                new Case(Integer.MIN_VALUE, ErrorCode.FRAME_CORRUPT),
                new Case(-1, ErrorCode.FRAME_CORRUPT),
                new Case(0, ErrorCode.FRAME_CORRUPT),
                new Case(MIN_FRAME - 1, ErrorCode.FRAME_CORRUPT),
        };
        for (Case c : cases) {
            byte[] header = new byte[4];
            ByteBuffer.wrap(header).putInt(c.len());
            EdgeFrameCodec.CodecException ex = assertThrows(
                    EdgeFrameCodec.CodecException.class,
                    () -> EdgeFrameCodec.peekLength(header),
                    "peekLength must reject hostile length " + c.len());
            assertEquals(c.expected(), ex.code(), "wrong error code for length " + c.len());
        }
    }

    /** Boundary fuzzing: exactly MIN_FRAME and MAX_EDGE_FRAME_SIZE accepted by peek. */
    @Property(tries = 1, seed = "1008")
    void lengthBoundariesBehaveExactly() {
        assertEquals(MIN_FRAME, peekOf(MIN_FRAME));
        assertEquals(EdgeFrameCodec.MAX_EDGE_FRAME_SIZE, peekOf(EdgeFrameCodec.MAX_EDGE_FRAME_SIZE));
        assertThrows(EdgeFrameCodec.CodecException.class, () -> peekOf(MIN_FRAME - 1));
        assertThrows(EdgeFrameCodec.CodecException.class,
                () -> peekOf(EdgeFrameCodec.MAX_EDGE_FRAME_SIZE + 1));
    }

    private static int peekOf(int declaredLength) {
        byte[] header = new byte[4];
        ByteBuffer.wrap(header).putInt(declaredLength);
        return EdgeFrameCodec.peekLength(header);
    }

    /** Sanity anchor: a known-good frame still decodes. */
    @Property(tries = 1, seed = "1010")
    void knownGoodFrameStillDecodes() {
        byte[] wire = EdgeFrameCodec.encode(new EdgeFrame.CursorAck(42));
        EdgeFrame f = EdgeFrameCodec.decode(wire);
        assertEquals(new EdgeFrame.CursorAck(42), f);
    }

    // -----------------------------------------------------------------------
    // Oracle + helpers.
    // -----------------------------------------------------------------------

    private static void assertOracleHolds(byte[] data) {
        assertTimeoutPreemptively(DECODE_BUDGET, () -> {
            try {
                EdgeFrame frame = EdgeFrameCodec.decode(data);
                assertNotNull(frame, "decode returned a null EdgeFrame");
            } catch (EdgeFrameCodec.CodecException expected) {
                assertNotNull(expected.code(), "CodecException with null ErrorCode");
            } catch (Throwable t) {
                failForbidden("decode", data, t);
            }
        });
    }

    private static void repairCrc(byte[] frame) {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(frame, 0, frame.length - EdgeFrameCodec.TRAILER_SIZE);
        int v = (int) crc.getValue();
        ByteBuffer.wrap(frame, frame.length - EdgeFrameCodec.TRAILER_SIZE,
                EdgeFrameCodec.TRAILER_SIZE).putInt(v);
    }

    private static void failForbidden(String op, byte[] data, Throwable t) {
        if (t instanceof AssertionError ae) {
            throw ae;
        }
        fail(op + " produced FORBIDDEN throwable " + t.getClass().getName()
                + " on input " + describe(data) + ": " + t.getMessage(), t);
    }

    private static String describe(byte[] data) {
        String hex = HexFormat.of().formatHex(data, 0, Math.min(data.length, 48));
        return "len=" + data.length + " hex=" + hex + (data.length > 48 ? "..." : "");
    }

    // -----------------------------------------------------------------------
    // Arbitraries.
    // -----------------------------------------------------------------------

    @Provide
    Arbitrary<byte[]> adversarialSized() {
        Arbitrary<Integer> sizes = Arbitraries.frequency(
                        Tuple.of(3, 0),
                        Tuple.of(3, 1),
                        Tuple.of(2, EdgeFrameCodec.HEADER_SIZE - 1),
                        Tuple.of(2, EdgeFrameCodec.HEADER_SIZE),
                        Tuple.of(2, MIN_FRAME - 1),
                        Tuple.of(3, MIN_FRAME),
                        Tuple.of(3, MIN_FRAME + 1),
                        Tuple.of(4, 32),
                        Tuple.of(4, 128),
                        Tuple.of(2, 512))
                .flatMap(max -> Arbitraries.integers().between(0, Math.max(0, max)));
        return sizes.flatMap(this::randomBytesOfSize);
    }

    @Provide
    Arbitrary<byte[]> boundarySized() {
        return Arbitraries.of(
                        0, 1, 2, 3, 4, 5,
                        EdgeFrameCodec.HEADER_SIZE - 1, EdgeFrameCodec.HEADER_SIZE,
                        MIN_FRAME - 1, MIN_FRAME, MIN_FRAME + 1)
                .flatMap(this::randomBytesOfSize);
    }

    private Arbitrary<byte[]> randomBytesOfSize(int size) {
        if (size <= 0) {
            return Arbitraries.just(new byte[0]);
        }
        return Arbitraries.bytes().array(byte[].class).ofSize(size);
    }

    /** A spread of well-formed frames covering several variants and payload sizes. */
    @Provide
    Arbitrary<byte[]> validFrames() {
        Arbitrary<EdgeFrame> simple = Arbitraries.oneOf(
                Arbitraries.longs().between(0, 1_000_000).map(EdgeFrame.CursorAck::new),
                Arbitraries.longs().between(0, 1_000_000).map(EdgeFrame.SnapshotEnd::new),
                Combinators.combine(
                                Arbitraries.longs().between(0, 1_000_000),
                                Arbitraries.longs().between(0, 1_000_000))
                        .as(EdgeFrame.Heartbeat::new),
                Combinators.combine(
                                Arbitraries.of(ErrorCode.values()),
                                Arbitraries.strings().ofMaxLength(32))
                        .as(EdgeFrame.ErrorClose::new),
                Combinators.combine(
                                Arbitraries.integers().between(0, 100_000),
                                Arbitraries.bytes().array(byte[].class).ofMaxSize(128))
                        .as(EdgeFrame.SnapshotChunk::new),
                subscribes());
        return simple.map(EdgeFrameCodec::encode);
    }

    private Arbitrary<EdgeFrame> subscribes() {
        Arbitrary<List<String>> prefixes = Arbitraries.strings()
                .withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8).list().ofMaxSize(3);
        return Combinators.combine(
                        Arbitraries.integers().between(0, 1),
                        prefixes,
                        Arbitraries.longs().between(0, 1_000_000),
                        Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8))
                .as((full, pfx, resume, edgeId) -> {
                    boolean fullStore = full == 1 || pfx.isEmpty();
                    return new EdgeFrame.Subscribe(fullStore,
                            fullStore ? List.of() : pfx, resume, -1L, edgeId);
                });
    }

    @Provide
    Arbitrary<Integer> hostileLengths() {
        return Arbitraries.of(
                Integer.MIN_VALUE, -1, 0, 1,
                MIN_FRAME - 1, MIN_FRAME, MIN_FRAME + 1, 1024,
                EdgeFrameCodec.MAX_EDGE_FRAME_SIZE,
                EdgeFrameCodec.MAX_EDGE_FRAME_SIZE + 1,
                Integer.MAX_VALUE);
    }
}

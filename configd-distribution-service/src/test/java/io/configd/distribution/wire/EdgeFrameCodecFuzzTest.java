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
import java.nio.charset.StandardCharsets;
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
 * {@link EdgeFrameCodec#peekLength}.
 *
 * <p>Complements - does NOT duplicate - {@link EdgeFrameCodecPropertyTest},
 * which already proves round-trip fidelity, truncation, single-bit corruption,
 * oversize-length-at-peek, length mismatch, and wrong-version. This suite adds
 * the security <b>resource oracle</b> for wholly arbitrary / adversarial input.
 *
 * <p><b>The edge contract is strictly narrower than the Raft codec's.</b>
 * {@link EdgeFrameCodec#decode} catches every {@link RuntimeException} from a
 * structurally-valid-but-malformed payload and re-wraps it as a
 * {@link EdgeFrameCodec.CodecException} (FRAME_CORRUPT). So for any non-null
 * input the ONLY permitted escape is a {@code CodecException}. Anything else -
 * {@link OutOfMemoryError}, {@link NullPointerException},
 * {@link ArrayIndexOutOfBoundsException}, {@link NegativeArraySizeException}, a
 * raw {@link java.nio.BufferUnderflowException}, or a hang - is a defect. This is
 * a stronger oracle than the Raft one precisely because the edge codec promises
 * a single typed failure mode to its session layer.
 *
 * <p>Determinism: each {@code @Property} pins a fixed {@code seed}; failing
 * inputs land in the committed corpus.
 */
class EdgeFrameCodecFuzzTest {

    private static final Duration DECODE_BUDGET = Duration.ofSeconds(2);
    private static final int MIN_FRAME = EdgeFrameCodec.HEADER_SIZE + EdgeFrameCodec.TRAILER_SIZE; // 10
    private static final byte V2 = EdgeFrameCodec.EDGE_WIRE_VERSION_V2;

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
            return; // not a lie - unchanged prefix
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
        // inner length via its own bounds checks - and only as a CodecException.
        assertTimeoutPreemptively(DECODE_BUDGET, () -> {
            try {
                EdgeFrame f = EdgeFrameCodec.decode(frame);
                // It is legitimate that a few random int writes still yield a
                // valid frame (e.g. flipping a seq number). The oracle only
                // forbids a forbidden throwable; a successful decode is fine.
                assertNotNull(f);
            } catch (EdgeFrameCodec.CodecException expected) {
                // correct - inner bounds check fired
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
    // 5. Watch frames (0x02). Same single-typed-failure oracle.
    // -----------------------------------------------------------------------

    /** Truncating a valid 0x02 watch frame at any boundary yields a CodecException only. */
    @Property(tries = 500, seed = "2000")
    void watchFrameTruncationYieldsCodecExceptionOnly(
            @ForAll("validWatchFramesV2") byte[] valid,
            @ForAll @IntRange(min = 1, max = 4096) int truncateBy) {
        int newLen = Math.max(0, valid.length - truncateBy);
        if (newLen == valid.length) {
            return;
        }
        byte[] truncated = java.util.Arrays.copyOf(valid, newLen);
        assertTimeoutPreemptively(DECODE_BUDGET, () -> {
            try {
                EdgeFrameCodec.decode(truncated);
                fail("a truncated watch frame must not decode");
            } catch (EdgeFrameCodec.CodecException expected) {
                // correct
            } catch (Throwable t) {
                failForbidden("watch truncation decode", truncated, t);
            }
        });
    }

    /** An inner field-length lie in a watch frame (CRC repaired) escapes only as a CodecException. */
    @Property(tries = 400, seed = "2001")
    void watchFrameInnerLengthLieRejected(
            @ForAll("validWatchFramesV2") byte[] valid,
            @ForAll @IntRange(min = 0, max = 4095) int offsetSeed,
            @ForAll int hostile) {
        int payloadStart = EdgeFrameCodec.HEADER_SIZE;
        int payloadEnd = valid.length - EdgeFrameCodec.TRAILER_SIZE;
        int payloadLen = payloadEnd - payloadStart;
        if (payloadLen < 4) {
            return;
        }
        byte[] frame = valid.clone();
        int at = payloadStart + (offsetSeed % (payloadLen - 3));
        ByteBuffer.wrap(frame).putInt(at, hostile);
        repairCrc(frame);
        assertTimeoutPreemptively(DECODE_BUDGET, () -> {
            try {
                assertNotNull(EdgeFrameCodec.decode(frame));
            } catch (EdgeFrameCodec.CodecException expected) {
                // correct - an inner bounds/invariant check fired
            } catch (Throwable t) {
                failForbidden("watch inner-length-lie", frame, t);
            }
        });
    }

    /** A hostile (negative / huge) cursor component count is rejected as FRAME_CORRUPT before allocation. */
    @Property(tries = 1, seed = "2002")
    void hostileCursorCountRejectedBeforeAllocation() {
        int[] hostile = {-1, Integer.MIN_VALUE, Integer.MAX_VALUE, 1_000_000};
        for (int hc : hostile) {
            byte[] wire = EdgeFrameCodec.encode(
                    new EdgeFrame.WatchProgress(7L, WatchCursor.fromNow(), 123L), V2);
            // cursor count is at offset HEADER(6) + watchId(8) = 14.
            ByteBuffer.wrap(wire).putInt(14, hc);
            repairCrc(wire);
            assertTimeoutPreemptively(DECODE_BUDGET, () -> {
                EdgeFrameCodec.CodecException ex = assertThrows(EdgeFrameCodec.CodecException.class,
                        () -> EdgeFrameCodec.decode(wire), "hostile cursor count " + hc + " must be rejected");
                assertEquals(ErrorCode.FRAME_CORRUPT, ex.code(), "cursor count " + hc);
            });
        }
    }

    /** A duplicate or descending (unsigned) gid in a wire cursor is rejected as FRAME_CORRUPT. */
    @Property(tries = 1, seed = "2003")
    void unsortedOrDuplicateGidCursorRejected() {
        WatchCursor twoComp = new WatchCursor(List.of(
                new WatchCursor.Component(0, 1L), new WatchCursor.Component(1, 2L)));
        // comp0.gid @ 18, comp1.gid @ 30 (see WATCH_PROGRESS layout).
        byte[] dup = EdgeFrameCodec.encode(new EdgeFrame.WatchProgress(7L, twoComp, 9L), V2);
        ByteBuffer.wrap(dup).putInt(30, 0); // comp1.gid := comp0.gid (duplicate)
        repairCrc(dup);
        EdgeFrameCodec.CodecException ex = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(dup));
        assertEquals(ErrorCode.FRAME_CORRUPT, ex.code());

        byte[] desc = EdgeFrameCodec.encode(new EdgeFrame.WatchProgress(7L, twoComp, 9L), V2);
        ByteBuffer.wrap(desc).putInt(18, 5); // comp0.gid := 5, so 5 then 1 is descending (unsigned)
        repairCrc(desc);
        ex = assertThrows(EdgeFrameCodec.CodecException.class, () -> EdgeFrameCodec.decode(desc));
        assertEquals(ErrorCode.FRAME_CORRUPT, ex.code());
    }

    /** A WATCH_EVENT val_len other than the {@code -1} sentinel or a present (&ge;0) length is rejected. */
    @Property(tries = 1, seed = "2004")
    void watchEventBadValLenRejected() {
        EdgeFrame.WatchEvent ev = new EdgeFrame.WatchEvent(
                7L, 0, 1L, 2L, List.of(EdgeFrame.WatchChange.delete("k")));
        // For a 1-byte-key single DELETE change, val_len is at offset 44.
        // watch_event: HEADER 6 + watchId 8 + gid 4 + S 8 + commitTs 8 + changeCount 4
        //            + keyLen 4 + key 1 + kind 1 = 44.
        byte[] belowSentinel = EdgeFrameCodec.encode(ev, V2);
        ByteBuffer.wrap(belowSentinel).putInt(44, -2); // < -1: illegal
        repairCrc(belowSentinel);
        EdgeFrameCodec.CodecException ex = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(belowSentinel));
        assertEquals(ErrorCode.FRAME_CORRUPT, ex.code());

        byte[] hugePos = EdgeFrameCodec.encode(ev, V2);
        ByteBuffer.wrap(hugePos).putInt(44, 1_000_000); // present-length exceeding remaining
        repairCrc(hugePos);
        ex = assertThrows(EdgeFrameCodec.CodecException.class, () -> EdgeFrameCodec.decode(hugePos));
        assertEquals(ErrorCode.FRAME_CORRUPT, ex.code());
    }

    /** A WATCH_* type stamped on a 0x01 frame (CRC repaired) is rejected as FRAME_CORRUPT. */
    @Property(tries = 1, seed = "2005")
    void watchTypeOnV1FrameRejected() {
        byte[] wire = EdgeFrameCodec.encode(new EdgeFrame.WatchCancel(7L), V2);
        wire[4] = EdgeFrameCodec.EDGE_WIRE_VERSION; // 0x02 -> 0x01
        repairCrc(wire);
        EdgeFrameCodec.CodecException ex = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(wire));
        assertEquals(ErrorCode.FRAME_CORRUPT, ex.code());
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

    /** A spread of well-formed 0x02 watch frames covering every WATCH_* type and cursor form. */
    @Provide
    Arbitrary<byte[]> validWatchFramesV2() {
        Arbitrary<EdgeFrame> frames = Arbitraries.oneOf(
                Arbitraries.longs().between(0, 1_000_000).map(EdgeFrame.WatchCancel::new),
                Combinators.combine(
                                Arbitraries.longs().between(0, 1_000_000),
                                Arbitraries.integers().between(0, 0xFF),
                                Arbitraries.integers().between(0, 1), // KEY or PREFIX
                                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8),
                                Arbitraries.longs().between(0, 1_000_000),
                                Arbitraries.integers().between(0, 0xFF))
                        .as((id, scope, kind, p, s, flags) -> new EdgeFrame.WatchCreate(
                                id, scope, kind, p.getBytes(StandardCharsets.UTF_8),
                                s == 0 ? WatchCursor.fromNow() : WatchCursor.of(0, s), flags)),
                Combinators.combine(
                                Arbitraries.longs().between(0, 1_000_000),
                                Arbitraries.integers().between(0, 1_000),
                                Arbitraries.longs().between(0, 1_000_000),
                                Arbitraries.longs().between(0, 1_000_000_000L))
                        .as((id, gid, s, ts) -> new EdgeFrame.WatchEvent(id, gid, s, ts, List.of(
                                EdgeFrame.WatchChange.put("k", new byte[]{1, 2}),
                                EdgeFrame.WatchChange.put("e", new byte[0]),
                                EdgeFrame.WatchChange.delete("d")))),
                Combinators.combine(
                                Arbitraries.longs().between(0, 1_000_000),
                                Arbitraries.longs().between(0, 1_000_000))
                        .as((id, s) -> new EdgeFrame.WatchProgress(id, WatchCursor.of(0, s), 1000L)),
                Combinators.combine(
                                Arbitraries.longs().between(0, 1_000_000),
                                Arbitraries.of(ErrorCode.values()),
                                Arbitraries.strings().ofMaxLength(16))
                        .as((id, code, msg) ->
                                new EdgeFrame.WatchCanceled(id, code, WatchCursor.of(0, 1L), msg)),
                Combinators.combine(
                                Arbitraries.longs().between(0, 1_000_000),
                                Arbitraries.integers().between(0, 1_000))
                        .as((id, gid) -> new EdgeFrame.WatchCreated(id, List.of(
                                new EdgeFrame.ShardMode(gid, 10L, EdgeFrame.Mode.TAIL)))),
                Combinators.combine(
                                Arbitraries.longs().between(0, 1_000_000),
                                Arbitraries.integers().between(0, 1_000))
                        .as((id, gid) -> new EdgeFrame.WatchSnapshotBegin(id, gid, 5L, 2, 100L)),
                Combinators.combine(
                                Arbitraries.longs().between(0, 1_000_000),
                                Arbitraries.bytes().array(byte[].class).ofMaxSize(64))
                        .as((id, b) -> new EdgeFrame.WatchSnapshotChunk(id, 0, 0, b)),
                Arbitraries.longs().between(0, 1_000_000)
                        .map(id -> new EdgeFrame.WatchSnapshotEnd(id, 0, 5L)));
        return frames.map(f -> EdgeFrameCodec.encode(f, V2));
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

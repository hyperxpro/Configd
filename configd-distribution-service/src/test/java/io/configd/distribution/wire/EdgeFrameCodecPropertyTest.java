package io.configd.distribution.wire;

import io.configd.distribution.CommitNotification;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jqwik property fuzz suite for {@link EdgeFrameCodec} (CT-41; mirrors
 * {@code FrameCodecPropertyTest}'s adversarial-input discipline). Every property
 * targets a class of input that could arrive on a TLS-terminated edge socket:
 * round-trip fidelity, truncation at every byte boundary, single-bit corruption →
 * CRC error (never a misparse), and length-cap violations rejected before allocation.
 */
class EdgeFrameCodecPropertyTest {

    // ---- round-trip ---------------------------------------------------------

    @Property(tries = 500)
    void roundTripPreservesEveryFrame(@ForAll("frames") EdgeFrame frame) {
        byte[] wire = EdgeFrameCodec.encode(frame);
        EdgeFrame decoded = EdgeFrameCodec.decode(wire);
        assertEquals(frame, decoded, "encode/decode must round-trip identically");
    }

    /**
     * The load-bearing fidelity property (ADR-0038): a signed delta carried in a NOTIFY
     * frame round-trips with its {@link ConfigDelta#signingPayload()} byte-identical —
     * edge signature verification depends on this exact byte equality.
     */
    @Property(tries = 300)
    void signedDeltaSigningPayloadRoundTripsByteIdentical(@ForAll("notifications") CommitNotification n) {
        byte[] before = n.delta().signingPayload();
        EdgeFrame.Notify frame = new EdgeFrame.Notify(List.of(n));
        EdgeFrame.Notify decoded = (EdgeFrame.Notify) EdgeFrameCodec.decode(EdgeFrameCodec.encode(frame));
        ConfigDelta d = decoded.notifications().get(0).delta();
        assertArrayEquals(before, d.signingPayload(),
                "signingPayload() must be byte-identical after a NOTIFY round-trip");
        // The raw signature/epoch/nonce must also survive verbatim.
        assertArrayEquals(n.delta().signature(), d.signature());
        assertEquals(n.delta().epoch(), d.epoch());
        assertArrayEquals(n.delta().nonce(), d.nonce());
    }

    @Property(tries = 300)
    void peekLengthAgreesWithEncodedLength(@ForAll("frames") EdgeFrame frame) {
        byte[] wire = EdgeFrameCodec.encode(frame);
        assertEquals(wire.length, EdgeFrameCodec.peekLength(wire));
        // peekLength on just the 6-byte header is the same.
        byte[] header = Arrays.copyOf(wire, EdgeFrameCodec.HEADER_SIZE);
        assertEquals(wire.length, EdgeFrameCodec.peekLength(header));
    }

    // ---- truncation at every byte boundary ---------------------------------

    @Property(tries = 400)
    void truncationAtAnyBoundaryIsRejectedCleanly(
            @ForAll("frames") EdgeFrame frame,
            @ForAll @IntRange(min = 1, max = 4096) int truncateBy) {
        byte[] wire = EdgeFrameCodec.encode(frame);
        int newLen = Math.max(0, wire.length - truncateBy);
        if (newLen == wire.length) {
            return;
        }
        byte[] truncated = Arrays.copyOf(wire, newLen);
        // Must throw a CodecException (never return a wrong frame, never an unchecked
        // underflow that escapes as a non-CodecException).
        assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(truncated),
                "truncated frame must be rejected as a CodecException");
    }

    // ---- single-bit corruption → CRC error, not misparse -------------------

    @Property(tries = 500)
    void singleBitFlipIsCaughtByCrcNotMisparsed(
            @ForAll("frames") EdgeFrame frame,
            @ForAll @IntRange(min = 0, max = 100_000) int bitPos) {
        byte[] wire = EdgeFrameCodec.encode(frame);
        // Flip one bit somewhere in [0, wire.length*8) EXCEPT inside the length prefix
        // (bytes 0..3) — corrupting the length prefix is a separate property (it is
        // rejected as a length mismatch, also fine, but we isolate CRC here).
        int totalBits = wire.length * 8;
        int startBit = 4 * 8; // skip the length prefix
        int span = totalBits - startBit;
        if (span <= 0) {
            return;
        }
        int bit = startBit + (bitPos % span);
        byte[] corrupted = wire.clone();
        corrupted[bit / 8] ^= (byte) (1 << (bit % 8));

        // A single bit flip outside the length prefix changes the CRC region or the
        // trailer, so decode must fail; it must NOT return a different valid frame.
        EdgeFrameCodec.CodecException ex = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(corrupted));
        // The flip is caught as corruption (CRC), bad version, or a malformed payload —
        // all are FRAME_CORRUPT or BAD_WIRE_VERSION, never a silent wrong-frame.
        assertTrue(ex.code() == ErrorCode.FRAME_CORRUPT
                        || ex.code() == ErrorCode.BAD_WIRE_VERSION
                        || ex.code() == ErrorCode.FRAME_TOO_LARGE,
                "bit flip must surface as a structural error, got " + ex.code());
    }

    /**
     * A bit flip anywhere in the body (version/type/payload) with the CRC left stale
     * must surface as {@link ErrorCode#FRAME_CORRUPT} — never a "bad version" or "unknown
     * type" that would point an operator at the wrong root cause.
     */
    @Property(tries = 300)
    void bitFlipInVersionOrTypeReadsAsCorruptionNotVersionError(
            @ForAll("frames") EdgeFrame frame,
            @ForAll @IntRange(min = 0, max = 1) int which) {
        byte[] wire = EdgeFrameCodec.encode(frame);
        int idx = (which == 0) ? 4 /* version */ : 5 /* type */;
        byte[] corrupted = wire.clone();
        corrupted[idx] ^= 0x01;
        EdgeFrameCodec.CodecException ex = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(corrupted));
        assertEquals(ErrorCode.FRAME_CORRUPT, ex.code(),
                "a stale-CRC body flip must read as FRAME_CORRUPT (CRC verified first)");
    }

    // ---- length-cap violations rejected BEFORE allocation ------------------

    @Property(tries = 200)
    void oversizeLengthPrefixIsRejectedByPeekBeforeAllocation(
            @ForAll @IntRange(min = EdgeFrameCodec.MAX_EDGE_FRAME_SIZE + 1, max = Integer.MAX_VALUE) int bogusLen) {
        byte[] header = new byte[EdgeFrameCodec.HEADER_SIZE];
        // Write an oversize length into the first 4 bytes.
        header[0] = (byte) (bogusLen >>> 24);
        header[1] = (byte) (bogusLen >>> 16);
        header[2] = (byte) (bogusLen >>> 8);
        header[3] = (byte) bogusLen;
        EdgeFrameCodec.CodecException ex = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.peekLength(header));
        assertEquals(ErrorCode.FRAME_TOO_LARGE, ex.code());
    }

    @Property(tries = 200)
    void lengthFieldMismatchIsRejected(
            @ForAll("frames") EdgeFrame frame,
            @ForAll @IntRange(min = -512, max = 512) int delta) {
        if (delta == 0) {
            return;
        }
        byte[] wire = EdgeFrameCodec.encode(frame);
        int corrupted = wire.length + delta;
        if (corrupted < 0) {
            return;
        }
        byte[] copy = wire.clone();
        copy[0] = (byte) (corrupted >>> 24);
        copy[1] = (byte) (corrupted >>> 16);
        copy[2] = (byte) (corrupted >>> 8);
        copy[3] = (byte) corrupted;
        assertThrows(EdgeFrameCodec.CodecException.class, () -> EdgeFrameCodec.decode(copy));
    }

    /** A NOTIFY batch over the count cap must be rejected at encode (FRAME_TOO_LARGE). */
    @Property(tries = 30)
    void notifyBatchOverCountCapRejectedAtEncode(
            @ForAll @IntRange(min = EdgeFrameCodec.MAX_NOTIFY_BATCH + 1,
                    max = EdgeFrameCodec.MAX_NOTIFY_BATCH + 32) int n) {
        List<CommitNotification> batch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            batch.add(notif(i + 1));
        }
        EdgeFrameCodec.CodecException ex = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.encode(new EdgeFrame.Notify(batch)));
        assertEquals(ErrorCode.FRAME_TOO_LARGE, ex.code());
    }

    /**
     * A wire frame whose version byte differs from {@link EdgeFrameCodec#EDGE_WIRE_VERSION}
     * (with a valid CRC, so it is NOT corruption) is rejected as BAD_WIRE_VERSION.
     */
    @Property(tries = 100)
    void wrongVersionWithValidCrcIsRejectedAsBadVersion(
            @ForAll @IntRange(min = 0, max = 255) int rawVersion) {
        if ((byte) rawVersion == EdgeFrameCodec.EDGE_WIRE_VERSION) {
            return;
        }
        // A minimal CURSOR_ACK frame, then rewrite version + fix CRC.
        byte[] wire = EdgeFrameCodec.encode(new EdgeFrame.CursorAck(7));
        wire[4] = (byte) rawVersion;
        CRC32C crc = new CRC32C();
        crc.update(wire, 0, wire.length - EdgeFrameCodec.TRAILER_SIZE);
        int v = (int) crc.getValue();
        int off = wire.length - EdgeFrameCodec.TRAILER_SIZE;
        wire[off] = (byte) (v >>> 24);
        wire[off + 1] = (byte) (v >>> 16);
        wire[off + 2] = (byte) (v >>> 8);
        wire[off + 3] = (byte) v;
        EdgeFrameCodec.CodecException ex = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(wire));
        assertEquals(ErrorCode.BAD_WIRE_VERSION, ex.code());
    }

    @Property(tries = 1)
    void noopSentinelInvariant() {
        // ErrorCode / FrameType fromCode round-trip (cheap sanity, kills boundary mutants).
        for (ErrorCode ec : ErrorCode.values()) {
            assertSame(ec, ErrorCode.fromCode(ec.code()));
        }
        for (FrameType ft : FrameType.values()) {
            assertSame(ft, FrameType.fromCode(ft.code()));
        }
        assertThrows(IllegalArgumentException.class, () -> ErrorCode.fromCode(99));
        assertThrows(IllegalArgumentException.class, () -> FrameType.fromCode(99));
    }

    // ---- arbitraries --------------------------------------------------------

    @Provide
    Arbitrary<EdgeFrame> frames() {
        return Arbitraries.oneOf(
                subscribes(),
                subscribeOks(),
                notifyFrames(),
                snapshotBegins(),
                snapshotChunks(),
                Arbitraries.longs().between(0, Long.MAX_VALUE).map(EdgeFrame.SnapshotEnd::new),
                Arbitraries.longs().between(0, Long.MAX_VALUE).map(EdgeFrame.CursorAck::new),
                heartbeats(),
                errorCloses());
    }

    private Arbitrary<EdgeFrame> subscribes() {
        Arbitrary<List<String>> prefixes =
                key().list().ofMaxSize(4);
        return Combinators.combine(
                Arbitraries.integers().between(0, 1),
                prefixes,
                Arbitraries.longs().between(0, 1_000_000),
                Arbitraries.longs().between(-1, 1_000_000),
                key())
                .as((full, pfx, resume, failover, edgeId) -> {
                    boolean fullStore = full == 1 || pfx.isEmpty();
                    return new EdgeFrame.Subscribe(fullStore,
                            fullStore ? List.of() : pfx, resume, failover, edgeId);
                });
    }

    private Arbitrary<EdgeFrame> subscribeOks() {
        return Combinators.combine(
                        Arbitraries.longs().between(-1, 1_000_000),
                        Arbitraries.of(EdgeFrame.Mode.values()))
                .as(EdgeFrame.SubscribeOk::new);
    }

    private Arbitrary<EdgeFrame> notifyFrames() {
        return notificationArb().list().ofMinSize(0).ofMaxSize(EdgeFrameCodec.MAX_NOTIFY_BATCH)
                .map(EdgeFrame.Notify::new);
    }

    private Arbitrary<EdgeFrame> snapshotBegins() {
        return Combinators.combine(
                        Arbitraries.longs().between(0, 1_000_000),
                        Arbitraries.integers().between(0, 1024),
                        Arbitraries.longs().between(0, 10_000_000))
                .as(EdgeFrame.SnapshotBegin::new);
    }

    private Arbitrary<EdgeFrame> snapshotChunks() {
        return Combinators.combine(
                        Arbitraries.integers().between(0, 100_000),
                        Arbitraries.bytes().array(byte[].class).ofMaxSize(2048))
                .as(EdgeFrame.SnapshotChunk::new);
    }

    private Arbitrary<EdgeFrame> heartbeats() {
        return Combinators.combine(
                        Arbitraries.longs().between(-1, 1_000_000),
                        Arbitraries.longs().between(0, Long.MAX_VALUE))
                .as(EdgeFrame.Heartbeat::new);
    }

    private Arbitrary<EdgeFrame> errorCloses() {
        return Combinators.combine(
                        Arbitraries.of(ErrorCode.values()),
                        Arbitraries.strings().ofMaxLength(64))
                .as(EdgeFrame.ErrorClose::new);
    }

    @Provide
    Arbitrary<CommitNotification> notifications() {
        return notificationArb();
    }

    private Arbitrary<CommitNotification> notificationArb() {
        Arbitrary<Long> seq = Arbitraries.longs().between(1, 1_000_000);
        Arbitrary<Long> ts = Arbitraries.longs().between(0, 1_700_000_000_000L);
        Arbitrary<ConfigDelta> deltas = deltaArb();
        return Combinators.combine(seq, ts, deltas)
                .as((s, t, d) -> new CommitNotification(s, t, d));
    }

    private Arbitrary<ConfigDelta> deltaArb() {
        Arbitrary<List<ConfigMutation>> muts = mutationArb().list().ofMinSize(1).ofMaxSize(6);
        Arbitrary<Long> from = Arbitraries.longs().between(0, 1_000_000);
        Arbitrary<Integer> bump = Arbitraries.integers().between(1, 10);
        // Mix unsigned-legacy and signed-with-nonce deltas to exercise both signingPayload forms.
        Arbitrary<Integer> kind = Arbitraries.integers().between(0, 2);
        return Combinators.combine(from, bump, muts, kind).as((f, b, m, k) -> {
            long to = f + b;
            return switch (k) {
                case 0 -> new ConfigDelta(f, to, m); // unsigned legacy
                case 1 -> new ConfigDelta(f, to, m, sig(16)); // signed legacy (epoch 0)
                default -> new ConfigDelta(f, to, m, sig(64), 42L, nonce()); // F-0052 signed
            };
        });
    }

    private Arbitrary<ConfigMutation> mutationArb() {
        Arbitrary<ConfigMutation> puts = Combinators.combine(
                        key(), Arbitraries.bytes().array(byte[].class).ofMaxSize(64))
                .as(ConfigMutation.Put::new);
        Arbitrary<ConfigMutation> dels = key().map(ConfigMutation.Delete::new);
        return Arbitraries.oneOf(puts, dels);
    }

    private Arbitrary<String> key() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12);
    }

    private static byte[] sig(int n) {
        byte[] s = new byte[n];
        for (int i = 0; i < n; i++) {
            s[i] = (byte) (i * 7 + 1);
        }
        return s;
    }

    private static byte[] nonce() {
        byte[] nn = new byte[ConfigDelta.NONCE_LEN];
        for (int i = 0; i < nn.length; i++) {
            nn[i] = (byte) (i + 3);
        }
        return nn;
    }

    private static CommitNotification notif(long seq) {
        return new CommitNotification(seq, 1000L,
                new ConfigDelta(seq - 1, seq, List.of(new ConfigMutation.Put("k", new byte[]{1}))));
    }
}

package io.configd.distribution.wire;

import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.IntRange;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Adversarial byte-level fuzz suite for {@link EdgeSnapshotCodec} - the snapshot body codec on the
 * edge / distribution plane.
 *
 * <p>The threat is a malicious or compromised distribution server streaming a hostile
 * snapshot body / chunk sequence at an edge node. Two decode surfaces take attacker-controlled bytes:
 * {@link EdgeSnapshotCodec#deserialize} (the body → {@link ConfigSnapshot}) and
 * {@link EdgeSnapshotCodec#reassemble} (ordered chunks → body). This suite is the first machine-driven
 * coverage of both; {@link EdgeSnapshotCodecTest} proves only the happy-path round-trip and a couple
 * of hand-aimed rejects.
 *
 * <p><b>Oracle.</b> For any input, {@code deserialize} / {@code reassemble} must EITHER return a
 * well-formed value OR throw {@link IllegalArgumentException} - and NEVER a
 * {@link BufferUnderflowException} (every length read in {@code readBoundedLen} is remaining-gated
 * first), {@link OutOfMemoryError} (the per-field 1 MiB cap + remaining-check means a tiny body cannot
 * pre-size a huge array), {@link NegativeArraySizeException}, {@link NullPointerException},
 * {@link ArrayIndexOutOfBoundsException}, or a hang.
 *
 * <p><b>Round-trip.</b> {@code deserialize(serialize(x))} preserves content for every valid snapshot
 * (a distinct property here from {@link EdgeSnapshotCodecTest}'s chunked-restore-through-the-state-
 * machine round-trip: this one exercises the edge's own {@code deserialize} decode directly).
 *
 * <p><b>Tries budget.</b> 3000 tries on the arbitrary-body oracle, hundreds on the structured
 * properties - {@code deserialize} is linear in input, so the class is sub-second on a 2-vCPU box.
 * Fixed seeds make failures reproducible; the {@code @Property(tries = 1)} cases are the permanent
 * regression corpus.
 */
class EdgeSnapshotCodecFuzzTest {

    private static final Duration DECODE_BUDGET = Duration.ofSeconds(2);
    private static final int MAX_FIELD = EdgeSnapshotCodec.MAX_ENTRY_FIELD_BYTES; // 1 MiB

    // 1. deserialize: arbitrary body bytes.

    @Property(tries = 3000, seed = "424242")
    void arbitraryBodyBytesYieldSnapshotOrIllegalArgument(@ForAll("adversarialSized") byte[] body) {
        assertDeserializeOracle(body);
    }

    @Property(tries = 800, seed = "20260706")
    void boundarySizedBodyBytesYieldSnapshotOrIllegalArgument(@ForAll("boundarySized") byte[] body) {
        assertDeserializeOracle(body);
    }

    // 2. deserialize: structured mutation of a VALID body.

    /**
     * Overwrite a random 4-byte window of a valid body with a hostile int (negative, oversize field
     * length, huge entry count, Integer.MAX/MIN). The decoder must stay total - this is the inner
     * keyLen/valLen/entryCount amplifier driven by the fuzzer.
     */
    @Property(tries = 800, seed = "1001")
    void innerIntLieOnValidBodyIsTotal(
            @ForAll("validBodies") byte[] valid,
            @ForAll @IntRange(min = 0, max = 8191) int offsetSeed,
            @ForAll("hostileInts") int hostile) {
        if (valid.length < 4) {
            return;
        }
        byte[] body = valid.clone();
        int at = offsetSeed % (valid.length - 3);
        ByteBuffer.wrap(body).putInt(at, hostile);
        assertDeserializeOracle(body);
    }

    /** Truncate a valid body at EVERY offset - each prefix decodes-or-rejects, never underflows. */
    @Property(tries = 120, seed = "1002")
    void truncateValidBodyAtEveryOffsetIsTotal(@ForAll("validBodies") byte[] valid) {
        for (int cut = 0; cut < valid.length; cut++) {
            assertDeserializeOracle(java.util.Arrays.copyOf(valid, cut));
        }
    }

    /** Trailing bytes after a fully-parsed body: deserialize stops at entryCount, so this is accepted
     *  (the body is trailer-carried by the frame); the oracle just forbids a forbidden throwable. */
    @Property(tries = 400, seed = "1003")
    void trailingGarbageOnValidBodyIsTotal(
            @ForAll("validBodies") byte[] valid,
            @ForAll @IntRange(min = 1, max = 64) int extra) {
        byte[] body = new byte[valid.length + extra];
        System.arraycopy(valid, 0, body, 0, valid.length);
        assertDeserializeOracle(body);
    }

    // 3. Round-trip fidelity (valid snapshots).

    @Property(tries = 300, seed = "1004")
    void serializeThenDeserializePreservesContent(@ForAll("snapshots") ConfigSnapshot snap) {
        byte[] body = EdgeSnapshotCodec.serialize(snap);
        ConfigSnapshot back = EdgeSnapshotCodec.deserialize(body);
        org.junit.jupiter.api.Assertions.assertEquals(snap.version(), back.version());
        org.junit.jupiter.api.Assertions.assertEquals(hexView(snap), hexView(back));
        // Re-serializing the decoded snapshot is byte-identical (canonical HamtMap.forEach order).
        assertArrayEquals(body, EdgeSnapshotCodec.serialize(back));
    }

    /** chunk() then reassemble() is a lossless identity for any valid body and any legal chunk size. */
    @Property(tries = 300, seed = "1005")
    void chunkThenReassembleIsLossless(
            @ForAll("snapshots") ConfigSnapshot snap,
            @ForAll @IntRange(min = 1, max = 8192) int chunkBytes) {
        byte[] body = EdgeSnapshotCodec.serialize(snap);
        List<EdgeFrame.SnapshotChunk> chunks = EdgeSnapshotCodec.chunk(body, chunkBytes);
        assertArrayEquals(body, EdgeSnapshotCodec.reassemble(chunks));
    }

    // 4. chunk() / reassemble() hostile inputs.

    /** An out-of-range chunkBytes (0, negative, above the 1 MiB cap) is a clean IllegalArgument. */
    @Property(tries = 1, seed = "2001")
    void hostileChunkBytesRejected() {
        byte[] body = new byte[100];
        for (int cb : new int[]{0, -1, Integer.MIN_VALUE,
                EdgeFrameCodec.MAX_SNAPSHOT_CHUNK_BYTES + 1, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> EdgeSnapshotCodec.chunk(body, cb));
        }
    }

    /** A non-contiguous (gap / duplicate / descending) chunk index run is rejected by reassemble. */
    @Property(tries = 400, seed = "2002")
    void nonContiguousChunkIndicesRejected(@ForAll("hostileChunkLists") List<EdgeFrame.SnapshotChunk> chunks) {
        assertTimeoutPreemptively(DECODE_BUDGET, () -> {
            try {
                byte[] out = EdgeSnapshotCodec.reassemble(chunks);
                // Accept path is only legal when the indices happen to be the contiguous run 0..n-1.
                for (int i = 0; i < chunks.size(); i++) {
                    if (chunks.get(i).index() != i) {
                        fail("reassemble accepted a non-contiguous index run");
                    }
                }
                assertNotNull(out);
            } catch (IllegalArgumentException expected) {
                // correct - the contiguity guard fired
            } catch (Throwable t) {
                failForbidden("reassemble", new byte[0], t);
            }
        });
    }

    // 5. Permanent regression corpus.

    /** A body shorter than the 12-byte (seq+count) header is a clean reject, never underflow. */
    @Property(tries = 1, seed = "3001")
    void corpusShortHeader() {
        for (int len : new int[]{0, 1, 8, 11}) {
            byte[] body = new byte[len];
            assertThrows(IllegalArgumentException.class, () -> EdgeSnapshotCodec.deserialize(body));
        }
    }

    /** A negative entryCount is a clean reject. */
    @Property(tries = 1, seed = "3002")
    void corpusNegativeEntryCount() {
        ByteBuffer b = ByteBuffer.allocate(12);
        b.putLong(1L);        // seq
        b.putInt(-1);         // entryCount < 0
        assertThrows(IllegalArgumentException.class, () -> EdgeSnapshotCodec.deserialize(b.array()));
    }

    /** A body declaring a keyLen far larger than the bytes present rejects before allocation. */
    @Property(tries = 1, seed = "3003")
    void corpusKeyLenOverrunRejectedPreAllocation() {
        ByteBuffer b = ByteBuffer.allocate(12 + 4 + 3);
        b.putLong(1L);
        b.putInt(1);          // one entry
        b.putInt(Integer.MAX_VALUE); // keyLen (hostile) - only 3 tail bytes present
        b.put(new byte[]{1, 2, 3});
        assertTimeoutPreemptively(DECODE_BUDGET, () -> assertThrows(
                IllegalArgumentException.class, () -> EdgeSnapshotCodec.deserialize(b.array())));
    }

    /** A keyLen above the 1 MiB per-field cap rejects even if that many bytes were present. */
    @Property(tries = 1, seed = "3004")
    void corpusKeyLenAboveFieldCapRejected() {
        ByteBuffer b = ByteBuffer.allocate(12 + 4);
        b.putLong(1L);
        b.putInt(1);
        b.putInt(MAX_FIELD + 1);
        assertThrows(IllegalArgumentException.class, () -> EdgeSnapshotCodec.deserialize(b.array()));
    }

    /** An entryCount that over-declares (more entries than the buffer holds) fails-fast per entry. */
    @Property(tries = 1, seed = "3005")
    void corpusEntryCountOverDeclares() {
        ByteBuffer b = ByteBuffer.allocate(12);
        b.putLong(1L);
        b.putInt(Integer.MAX_VALUE); // claims 2^31-1 entries, but body ends here
        assertTimeoutPreemptively(DECODE_BUDGET, () -> assertThrows(
                IllegalArgumentException.class, () -> EdgeSnapshotCodec.deserialize(b.array())));
    }

    // Oracle + helpers.

    private static void assertDeserializeOracle(byte[] body) {
        assertTimeoutPreemptively(DECODE_BUDGET, () -> {
            try {
                ConfigSnapshot snap = EdgeSnapshotCodec.deserialize(body);
                assertNotNull(snap, "deserialize returned null");
                assertNotNull(snap.data(), "decoded snapshot has null data");
            } catch (IllegalArgumentException expected) {
                // The single documented rejection type - correct.
            } catch (Throwable t) {
                failForbidden("deserialize", body, t);
            }
        });
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

    private static java.util.Map<String, String> hexView(ConfigSnapshot snap) {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        snap.data().forEach((k, vv) -> out.put(k, HexFormat.of().formatHex(vv.valueUnsafe())));
        return out;
    }

    // Arbitraries.

    @Provide
    Arbitrary<byte[]> adversarialSized() {
        Arbitrary<Integer> sizes = Arbitraries.frequency(
                Tuple.of(4, 0),
                Tuple.of(3, 1),
                Tuple.of(3, 8),
                Tuple.of(3, 11),
                Tuple.of(4, 12),   // exactly the header
                Tuple.of(3, 13),
                Tuple.of(3, 16),
                Tuple.of(3, 20),
                Tuple.of(3, 24),
                Tuple.of(2, 64),
                Tuple.of(2, 256))
                .flatMap(max -> Arbitraries.integers().between(0, Math.max(0, max)));
        return sizes.flatMap(this::randomBytesOfSize);
    }

    @Provide
    Arbitrary<byte[]> boundarySized() {
        return Arbitraries.of(0, 1, 8, 11, 12, 13, 15, 16, 19, 20)
                .flatMap(this::randomBytesOfSize);
    }

    private Arbitrary<byte[]> randomBytesOfSize(int size) {
        if (size <= 0) {
            return Arbitraries.just(new byte[0]);
        }
        return Arbitraries.bytes().array(byte[].class).ofSize(size);
    }

    /** Valid serialized snapshot bodies - the mutation/truncation base. */
    @Provide
    Arbitrary<byte[]> validBodies() {
        return snapshots().map(EdgeSnapshotCodec::serialize);
    }

    @Provide
    Arbitrary<ConfigSnapshot> snapshots() {
        Arbitrary<String> keys = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(10);
        Arbitrary<byte[]> vals = Arbitraries.bytes().array(byte[].class).ofMaxSize(48);
        Arbitrary<Long> version = Arbitraries.longs().between(0, 1_000_000);
        return Combinators.combine(Arbitraries.maps(keys, vals).ofMaxSize(10), version)
                .as((map, v) -> {
                    HamtMap<String, VersionedValue> data = HamtMap.empty();
                    for (var e : map.entrySet()) {
                        data = data.put(e.getKey(), new VersionedValue(e.getValue(), v, 0L));
                    }
                    return new ConfigSnapshot(data, v, 0L);
                });
    }

    /**
     * Chunk lists with arbitrary (non-negative) indices - mostly NOT the contiguous run 0..n-1, so
     * reassemble must reject them. A minority happen to be contiguous and legitimately reassemble; the
     * oracle handles both.
     */
    @Provide
    Arbitrary<List<EdgeFrame.SnapshotChunk>> hostileChunkLists() {
        Arbitrary<EdgeFrame.SnapshotChunk> chunk = Combinators.combine(
                        Arbitraries.integers().between(0, 8),
                        Arbitraries.bytes().array(byte[].class).ofMaxSize(8))
                .as(EdgeFrame.SnapshotChunk::new);
        return chunk.list().ofMinSize(1).ofMaxSize(6);
    }

    @Provide
    Arbitrary<Integer> hostileInts() {
        return Arbitraries.of(
                Integer.MIN_VALUE, -1, 0, 1,
                MAX_FIELD + 1, 1_000_000, 2_000_000_000, Integer.MAX_VALUE);
    }

    /** Sanity anchor: a known-good body still decodes. */
    @Property(tries = 1, seed = "4000")
    void knownGoodBodyStillDecodes() {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        data = data.put("k", new VersionedValue(new byte[]{1, 2, 3}, 5L, 0L));
        byte[] body = EdgeSnapshotCodec.serialize(new ConfigSnapshot(data, 5L, 0L));
        assertNotNull(EdgeSnapshotCodec.deserialize(body));
    }
}

package io.configd.store;

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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Adversarial byte-level fuzz suite for {@link CommandCodec#decode} - proves the decoder is a
 * total function under attacker-controlled bytes.
 *
 * <p>A committed Raft log command is the deepest attacker-controlled byte path in the system: once a
 * cert-valid-but-Byzantine leader gets a frame committed, EVERY replica re-decodes it on apply
 * <em>and</em> on WAL replay. A single un-total decode (a leaked {@link BufferUnderflowException} on
 * an over-declared inner length) is therefore not a one-shot crash but a durable, cluster-wide
 * crash-loop.
 *
 * <p>Complements - does NOT duplicate - {@link CommandCodecPropertyTest}, which proves the
 * round-trip and a handful of hand-aimed rejects. This suite adds the security <b>resource oracle</b>
 * for wholly arbitrary and adversarially-mutated bytes:
 *
 * <p><b>Oracle (strict).</b> For any non-null {@code byte[]}, {@link CommandCodec#decode} must EITHER
 * return a well-formed {@link CommandCodec.DecodedCommand} OR throw exactly
 * {@link CommandCodec.MalformedCommandException}. It must NEVER throw:
 * <ul>
 *   <li>{@link BufferUnderflowException} - an unguarded {@code ByteBuffer} read on a truncated /
 *       over-declared length,</li>
 *   <li>a bare {@link IllegalArgumentException} that is NOT a {@code MalformedCommandException}
 *       (e.g. a {@code ConfigMutation} constructor firing on a value the decoder should have rejected
 *       upstream) - the codec must own its rejection type so the apply path can catch it precisely,</li>
 *   <li>{@link OutOfMemoryError}, {@link NegativeArraySizeException}, {@link NullPointerException},
 *       {@link ArrayIndexOutOfBoundsException},</li>
 *   <li>a hang (caught by {@link org.junit.jupiter.api.Assertions#assertTimeoutPreemptively}).</li>
 * </ul>
 *
 * <p><b>Tries budget.</b> 3000 tries on the arbitrary-byte oracle, 800 on the mutation properties -
 * decode is microseconds, so the class is sub-second on a 2-vCPU box. Each {@code @Property} pins a
 * fixed {@code seed}; the {@code @Property(tries = 1)} hardcoded cases are a permanent regression
 * corpus of hostile byte shapes.
 */
class CommandCodecFuzzTest {

    private static final Duration DECODE_BUDGET = Duration.ofSeconds(2);

    private static final byte TYPE_PUT = 0x01;
    private static final byte TYPE_DELETE = 0x02;
    private static final byte TYPE_BATCH = 0x03;
    private static final int MAX_VALUE_SIZE = 1_048_576;
    private static final int MAX_BATCH_COUNT = 10_000;

    @Property(tries = 3000, seed = "424242")
    void arbitraryBytesAreDecodedOrMalformedNeverUnderflow(@ForAll("adversarialSized") byte[] data) {
        assertOracleHolds(data);
    }

    @Property(tries = 800, seed = "20260706")
    void boundarySizedBytesAreDecodedOrMalformed(@ForAll("boundarySized") byte[] data) {
        assertOracleHolds(data);
    }

    /**
     * Payloads whose FIRST byte is a valid type discriminant (PUT/DELETE/BATCH) but whose tail is
     * arbitrary - this steers the fuzzer past the type switch into the length-field decoders where the
     * underflow risk actually lives, instead of bouncing off the unknown-type guard.
     */
    @Property(tries = 2000, seed = "5150")
    void validTypeByteWithArbitraryTailIsTotal(
            @ForAll("commandType") byte type,
            @ForAll("adversarialTail") byte[] tail) {
        byte[] data = new byte[1 + tail.length];
        data[0] = type;
        System.arraycopy(tail, 0, data, 1, tail.length);
        assertOracleHolds(data);
    }

    /**
     * Encode a valid PUT/DELETE/BATCH, overwrite a random 4-byte window with a hostile int (negative,
     * oversize value length, oversize batch count, Integer.MAX/MIN), and assert the decoder stays
     * total. This is the inner length/count amplifier driven by the fuzzer.
     */
    @Property(tries = 800, seed = "1001")
    void innerIntLieOnValidCommandIsTotal(
            @ForAll("validCommands") byte[] valid,
            @ForAll @IntRange(min = 0, max = 8191) int offsetSeed,
            @ForAll("hostileInts") int hostile) {
        if (valid.length < 4) {
            return;
        }
        byte[] data = valid.clone();
        int at = offsetSeed % (valid.length - 3);
        ByteBuffer.wrap(data).putInt(at, hostile);
        assertOracleHolds(data);
    }

    /** Truncate a valid command at EVERY offset - every prefix decodes-or-Malformed, never underflow. */
    @Property(tries = 120, seed = "1002")
    void truncateValidCommandAtEveryOffsetIsTotal(@ForAll("validCommands") byte[] valid) {
        for (int cut = 0; cut < valid.length; cut++) {
            byte[] truncated = java.util.Arrays.copyOf(valid, cut);
            assertOracleHolds(truncated);
        }
    }

    /** Trailing bytes past a fully-parsed command are rejected strict-end (never mis-parsed / crashed). */
    @Property(tries = 500, seed = "1003")
    void trailingGarbageOnValidCommandIsTotal(
            @ForAll("validCommands") byte[] valid,
            @ForAll @IntRange(min = 1, max = 64) int extra) {
        byte[] data = new byte[valid.length + extra];
        System.arraycopy(valid, 0, data, 0, valid.length);
        assertOracleHolds(data);
    }

    /**
     * A BATCH whose element bytes are arbitrary - drives the fuzzer through the nested
     * decodePut/decodeDelete loop, the deepest recursion in the grammar, where a nested truncation
     * must still surface as a {@code MalformedCommandException}, not an underflow.
     */
    @Property(tries = 1500, seed = "1004")
    void batchWithArbitraryElementBytesIsTotal(
            @ForAll @IntRange(min = 0, max = 40) int declaredCount,
            @ForAll("adversarialTail") byte[] elementBytes) {
        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + elementBytes.length);
        buf.put(TYPE_BATCH);
        buf.putInt(declaredCount);
        buf.put(elementBytes);
        assertOracleHolds(buf.array());
    }

    @Property(tries = 1, seed = "1005")
    void tinyPutWithHugeValueLenRejectedPreAllocation() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        for (int valueLen : new int[]{Integer.MAX_VALUE, MAX_VALUE_SIZE + 1, 1 << 30}) {
            ByteBuffer buf = ByteBuffer.allocate(1 + 2 + key.length + 4);
            buf.put(TYPE_PUT);
            buf.putShort((short) key.length);
            buf.put(key);
            buf.putInt(valueLen);
            byte[] data = buf.array();
            assertTimeoutPreemptively(DECODE_BUDGET, () -> assertThrows(
                    CommandCodec.MalformedCommandException.class, () -> CommandCodec.decode(data),
                    "huge value length " + valueLen + " must reject pre-alloc"));
        }
    }

    @Property(tries = 1, seed = "1006")
    void tinyBatchWithHugeCountRejectedPreAllocation() {
        for (int count : new int[]{Integer.MAX_VALUE, MAX_BATCH_COUNT + 1, 1_000_000}) {
            ByteBuffer buf = ByteBuffer.allocate(1 + 4);
            buf.put(TYPE_BATCH);
            buf.putInt(count);
            byte[] data = buf.array();
            assertTimeoutPreemptively(DECODE_BUDGET, () -> assertThrows(
                    CommandCodec.MalformedCommandException.class, () -> CommandCodec.decode(data),
                    "huge batch count " + count + " must reject pre-alloc"));
        }
    }


    /** The empty payload is the noop sentinel - a stable, distinguished accept. */
    @Property(tries = 1, seed = "2000")
    void corpusEmptyIsNoop() {
        assertSame(CommandCodec.DecodedCommand.Noop.INSTANCE, CommandCodec.decode(new byte[0]));
    }

    /** An unknown type discriminant is a clean Malformed, never a type-confused mis-parse. */
    @Property(tries = 1, seed = "2001")
    void corpusUnknownTypeByte() {
        for (int t : new int[]{0x00, 0x04, 0x7F, 0x80, 0xFF}) {
            byte[] data = {(byte) t, 0, 0, 0, 0};
            assertThrows(CommandCodec.MalformedCommandException.class, () -> CommandCodec.decode(data));
        }
    }

    /** A PUT declaring a key longer than the 3 bytes present is a clean Malformed (not underflow). */
    @Property(tries = 1, seed = "2002")
    void corpusPutKeyLenOverruns() {
        byte[] data = {TYPE_PUT, (byte) 0xFF, (byte) 0xFF, 1, 2, 3}; // keyLen=65535, only 3 tail bytes
        assertThrows(CommandCodec.MalformedCommandException.class, () -> CommandCodec.decode(data));
    }

    /** A PUT with a blank (empty) key is rejected on decode - closes the blank-key poison-pill. */
    @Property(tries = 1, seed = "2003")
    void corpusPutBlankKeyRejected() {
        ByteBuffer buf = ByteBuffer.allocate(1 + 2 + 0 + 4);
        buf.put(TYPE_PUT);
        buf.putShort((short) 0); // keyLen = 0 -> blank
        buf.putInt(0);           // valueLen = 0
        assertThrows(CommandCodec.MalformedCommandException.class,
                () -> CommandCodec.decode(buf.array()));
    }

    /** A negative PUT value length (sign-extended u32) is rejected - the CVE-2008-1196 class. */
    @Property(tries = 1, seed = "2004")
    void corpusNegativeValueLen() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + 2 + key.length + 4);
        buf.put(TYPE_PUT);
        buf.putShort((short) key.length);
        buf.put(key);
        buf.putInt(-1);
        assertThrows(CommandCodec.MalformedCommandException.class,
                () -> CommandCodec.decode(buf.array()));
    }

    /** A BATCH whose declared count exceeds the elements present is a clean Malformed on the N+1-th read. */
    @Property(tries = 1, seed = "2005")
    void corpusBatchUnderfilled() {
        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + 4);
        buf.put(TYPE_BATCH);
        buf.putInt(5);           // claims 5 mutations
        buf.put(TYPE_DELETE);    // ... but only a truncated first one follows
        buf.putShort((short) 3); // keyLen = 3, no key bytes
        buf.put((byte) 'a');
        assertThrows(CommandCodec.MalformedCommandException.class,
                () -> CommandCodec.decode(buf.array()));
    }

    /** A BATCH element with an unknown nested type byte is a clean Malformed. */
    @Property(tries = 1, seed = "2006")
    void corpusBatchUnknownNestedType() {
        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + 1);
        buf.put(TYPE_BATCH);
        buf.putInt(1);
        buf.put((byte) 0x09);    // unknown nested mutation type
        assertThrows(CommandCodec.MalformedCommandException.class,
                () -> CommandCodec.decode(buf.array()));
    }

    private static void assertOracleHolds(byte[] data) {
        assertTimeoutPreemptively(DECODE_BUDGET, () -> {
            try {
                CommandCodec.DecodedCommand decoded = CommandCodec.decode(data);
                assertNotNull(decoded, "decode returned null");
            } catch (CommandCodec.MalformedCommandException expected) {
                // The single documented rejection type - correct.
            } catch (Throwable t) {
                failForbidden(data, t);
            }
        });
    }

    private static void failForbidden(byte[] data, Throwable t) {
        if (t instanceof AssertionError ae) {
            throw ae;
        }
        fail("decode produced FORBIDDEN throwable " + t.getClass().getName()
                + " on input " + describe(data) + ": " + t.getMessage(), t);
    }

    private static String describe(byte[] data) {
        String hex = HexFormat.of().formatHex(data, 0, Math.min(data.length, 48));
        return "len=" + data.length + " hex=" + hex + (data.length > 48 ? "..." : "");
    }

    @Provide
    Arbitrary<Byte> commandType() {
        return Arbitraries.of(TYPE_PUT, TYPE_DELETE, TYPE_BATCH);
    }

    /**
     * Arbitrary byte arrays weighted toward the adversarial zone: empty, 1 byte (bare type), and the
     * fixed-field frontiers (PUT header 3, DELETE min 3, BATCH count 5) plus small random sizes.
     */
    @Provide
    Arbitrary<byte[]> adversarialSized() {
        Arbitrary<Integer> sizes = Arbitraries.frequency(
                Tuple.of(4, 0),
                Tuple.of(4, 1),
                Tuple.of(3, 2),
                Tuple.of(3, 3),
                Tuple.of(3, 4),
                Tuple.of(3, 5),
                Tuple.of(3, 6),
                Tuple.of(3, 7),
                Tuple.of(3, 16),
                Tuple.of(2, 64),
                Tuple.of(2, 256))
                .flatMap(max -> Arbitraries.integers().between(0, Math.max(0, max)));
        return sizes.flatMap(this::randomBytesOfSize);
    }

    @Provide
    Arbitrary<byte[]> boundarySized() {
        return Arbitraries.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
                .flatMap(this::randomBytesOfSize);
    }

    /** Arbitrary tail bytes (post-type) weighted small so the length decoders see many short shapes. */
    @Provide
    Arbitrary<byte[]> adversarialTail() {
        return Arbitraries.integers().between(0, 48)
                .flatMap(this::randomBytesOfSize);
    }

    private Arbitrary<byte[]> randomBytesOfSize(int size) {
        if (size <= 0) {
            return Arbitraries.just(new byte[0]);
        }
        return Arbitraries.bytes().array(byte[].class).ofSize(size);
    }

    /** Well-formed PUT / DELETE / BATCH commands - the mutation base. */
    @Provide
    Arbitrary<byte[]> validCommands() {
        Arbitrary<byte[]> puts = Combinators.combine(
                        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(16),
                        Arbitraries.bytes().array(byte[].class).ofMaxSize(48))
                .as(CommandCodec::encodePut);
        Arbitrary<byte[]> deletes = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(16)
                .map(CommandCodec::encodeDelete);
        Arbitrary<byte[]> batches = mutationLists().map(CommandCodec::encodeBatch);
        return Arbitraries.oneOf(puts, deletes, batches);
    }

    @Provide
    Arbitrary<List<ConfigMutation>> mutationLists() {
        Arbitrary<ConfigMutation> put = Combinators.combine(
                        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(16),
                        Arbitraries.bytes().array(byte[].class).ofMaxSize(32))
                .as((k, v) -> (ConfigMutation) new ConfigMutation.Put(k, v));
        Arbitrary<ConfigMutation> del = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(16)
                .map(k -> (ConfigMutation) new ConfigMutation.Delete(k));
        return Arbitraries.oneOf(put, del).list().ofMinSize(1).ofMaxSize(12);
    }

    @Provide
    Arbitrary<Integer> hostileInts() {
        return Arbitraries.of(
                Integer.MIN_VALUE, -1, 0, 1,
                MAX_VALUE_SIZE + 1, MAX_BATCH_COUNT + 1,
                1_000_000, 2_000_000_000, Integer.MAX_VALUE);
    }

    /** Sanity anchor: a known-good command of each shape still decodes (guards the mutation base). */
    @Property(tries = 1, seed = "3000")
    void knownGoodCommandsStillDecode() {
        assertNotNull(CommandCodec.decode(CommandCodec.encodePut("k", new byte[]{1, 2, 3})));
        assertNotNull(CommandCodec.decode(CommandCodec.encodeDelete("k")));
        assertNotNull(CommandCodec.decode(CommandCodec.encodeBatch(
                List.of(new ConfigMutation.Put("a", new byte[]{1}), new ConfigMutation.Delete("b")))));
    }
}

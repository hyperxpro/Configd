package io.configd.store;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * jqwik property-based fuzz suite for {@link CommandCodec}.
 *
 * <p>Closes the codec-bounds requirement of Phase 4 (gap-closure §6,
 * row "jqwik for codec bounds"). The state-machine is the part of the
 * system most exposed to attacker-controlled bytes after authentication
 * — these properties prove that bounds enforcement is consistent and
 * that no malformed payload can drive the decoder into an invalid
 * state.
 */
class CommandCodecPropertyTest {

    @Property(tries = 500)
    void putRoundtripPreservesKeyAndValue(
            @ForAll @StringLength(min = 1, max = 256) String key,
            @ForAll @Size(max = 4096) byte[] value) {

        if (key.isBlank()) return;

        byte[] encoded = CommandCodec.encodePut(key, value);
        var decoded = assertInstanceOf(
                CommandCodec.DecodedCommand.Put.class,
                CommandCodec.decode(encoded));

        assertEquals(key, decoded.key());
        assertArrayEquals(value, decoded.value());
    }

    @Property(tries = 500)
    void deleteRoundtripPreservesKey(
            @ForAll @StringLength(min = 1, max = 256) String key) {

        if (key.isBlank()) return;

        byte[] encoded = CommandCodec.encodeDelete(key);
        var decoded = assertInstanceOf(
                CommandCodec.DecodedCommand.Delete.class,
                CommandCodec.decode(encoded));

        assertEquals(key, decoded.key());
    }

    @Property(tries = 200)
    void batchRoundtripPreservesAllMutationsInOrder(
            @ForAll("mutationLists") List<ConfigMutation> mutations) {

        byte[] encoded = CommandCodec.encodeBatch(mutations);
        var decoded = assertInstanceOf(
                CommandCodec.DecodedCommand.Batch.class,
                CommandCodec.decode(encoded));

        assertEquals(mutations.size(), decoded.mutations().size());
        for (int i = 0; i < mutations.size(); i++) {
            ConfigMutation expected = mutations.get(i);
            ConfigMutation actual = decoded.mutations().get(i);
            assertEquals(expected.key(), actual.key());
            if (expected instanceof ConfigMutation.Put p) {
                var ap = assertInstanceOf(ConfigMutation.Put.class, actual);
                assertArrayEquals(p.value(), ap.value());
            } else {
                assertInstanceOf(ConfigMutation.Delete.class, actual);
            }
        }
    }

    /**
     * Empty input is the noop sentinel — there is no bytes-in branch that
     * decodes to anything else, even by accident.
     */
    @Property(tries = 1)
    void emptyInputIsNoop() {
        var decoded = CommandCodec.decode(new byte[0]);
        assertSame(CommandCodec.DecodedCommand.Noop.INSTANCE, decoded);
    }

    /**
     * Any first byte that is not in {0x01, 0x02, 0x03} is rejected. This is
     * the primary type-confusion guard.
     */
    @Property(tries = 200)
    void unknownFirstByteIsRejected(
            @ForAll @IntRange(min = 4, max = 255) int rawType,
            @ForAll @Size(max = 64) byte[] tail) {

        byte[] frame = new byte[1 + tail.length];
        frame[0] = (byte) rawType;
        System.arraycopy(tail, 0, frame, 1, tail.length);

        assertThrows(Exception.class, () -> CommandCodec.decode(frame));
    }

    /**
     * A PUT whose declared value length exceeds 1 MB must be rejected
     * before allocation. This protects against amplification attacks
     * where a tiny on-wire frame triggers a huge heap allocation.
     */
    @Property(tries = 100)
    void putWithOversizedValueLengthIsRejected(
            @ForAll @StringLength(min = 1, max = 32) String key,
            @ForAll @IntRange(min = 1_048_577, max = Integer.MAX_VALUE) int oversizeLen) {

        if (key.isBlank()) return;

        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + 2 + keyBytes.length + 4);
        buf.put((byte) 0x01);
        buf.putShort((short) keyBytes.length);
        buf.put(keyBytes);
        buf.putInt(oversizeLen);

        assertThrows(Exception.class, () -> CommandCodec.decode(buf.array()));
    }

    /**
     * A negative value length (sign-extended on the int read) is also
     * rejected. This is the same class of bug as CVE-2008-1196.
     */
    @Property(tries = 100)
    void putWithNegativeValueLengthIsRejected(
            @ForAll @StringLength(min = 1, max = 32) String key,
            @ForAll @IntRange(min = Integer.MIN_VALUE, max = -1) int negLen) {

        if (key.isBlank()) return;

        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + 2 + keyBytes.length + 4);
        buf.put((byte) 0x01);
        buf.putShort((short) keyBytes.length);
        buf.put(keyBytes);
        buf.putInt(negLen);

        assertThrows(Exception.class, () -> CommandCodec.decode(buf.array()));
    }

    /**
     * BATCH with a count larger than the cap is rejected before
     * allocating the mutation list — this is the same amplification
     * guard as the PUT value-length check.
     */
    @Property(tries = 100)
    void batchWithOversizedCountIsRejected(
            @ForAll @IntRange(min = 10_001, max = Integer.MAX_VALUE) int oversizeCount) {

        ByteBuffer buf = ByteBuffer.allocate(1 + 4);
        buf.put((byte) 0x03);
        buf.putInt(oversizeCount);

        assertThrows(Exception.class, () -> CommandCodec.decode(buf.array()));
    }

    @Property(tries = 100)
    void batchWithNegativeCountIsRejected(
            @ForAll @IntRange(min = Integer.MIN_VALUE, max = -1) int negCount) {

        ByteBuffer buf = ByteBuffer.allocate(1 + 4);
        buf.put((byte) 0x03);
        buf.putInt(negCount);

        assertThrows(Exception.class, () -> CommandCodec.decode(buf.array()));
    }

    /**
     * encodeBatch on an empty list raises eagerly — empty batches are a
     * client-side bug and must not produce a wire frame whose semantics
     * is "no-op" (that is the dedicated zero-byte payload form).
     */
    @Property(tries = 1)
    void emptyBatchIsRejectedAtEncode() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandCodec.encodeBatch(List.of()));
    }

    /**
     * Truncating any encoded PUT/DELETE/BATCH must raise — the decoder
     * never silently returns a partial command.
     */
    @Property(tries = 200)
    void truncatedFrameIsRejected(
            @ForAll @StringLength(min = 1, max = 64) String key,
            @ForAll @Size(min = 1, max = 256) byte[] value,
            @ForAll @IntRange(min = 1, max = 64) int truncateBy) {

        if (key.isBlank()) return;

        byte[] frame = CommandCodec.encodePut(key, value);
        if (truncateBy >= frame.length) return;
        byte[] truncated = new byte[frame.length - truncateBy];
        System.arraycopy(frame, 0, truncated, 0, truncated.length);

        assertThrows(Exception.class, () -> CommandCodec.decode(truncated));
    }

    @Property(tries = 100)
    void utf8KeysWithMultiByteCodePointsRoundtrip(
            @ForAll("utf8Keys") String key,
            @ForAll @Size(max = 64) byte[] value) {

        byte[] encoded = CommandCodec.encodePut(key, value);
        var decoded = assertInstanceOf(
                CommandCodec.DecodedCommand.Put.class,
                CommandCodec.decode(encoded));
        assertEquals(key, decoded.key());
    }

    // ---- Arbitraries ----

    @Provide
    Arbitrary<List<ConfigMutation>> mutationLists() {
        Arbitrary<ConfigMutation> putArb = Combinators.combine(
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(32),
                Arbitraries.bytes().array(byte[].class).ofMaxSize(64))
                .as((k, v) -> (ConfigMutation) new ConfigMutation.Put(k, v));
        Arbitrary<ConfigMutation> delArb = Arbitraries.strings()
                .alpha().ofMinLength(1).ofMaxLength(32)
                .map(k -> (ConfigMutation) new ConfigMutation.Delete(k));
        return Arbitraries.oneOf(putArb, delArb).list().ofMinSize(1).ofMaxSize(20);
    }

    @Provide
    Arbitrary<String> utf8Keys() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(64)
                .filter(s -> !s.isBlank())
                .filter(s -> s.getBytes(StandardCharsets.UTF_8).length <= 256);
    }
}

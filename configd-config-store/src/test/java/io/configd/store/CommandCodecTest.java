package io.configd.store;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CommandCodec} - encode/decode roundtrip for all command types.
 */
class CommandCodecTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // PUT encode/decode
    // -----------------------------------------------------------------------

    @Nested
    class PutCommand {

        @Test
        void encodePutRoundTrip() {
            byte[] encoded = CommandCodec.encodePut("db.host", bytes("localhost"));
            CommandCodec.DecodedCommand decoded = CommandCodec.decode(encoded);

            assertInstanceOf(CommandCodec.DecodedCommand.Put.class, decoded);
            CommandCodec.DecodedCommand.Put put = (CommandCodec.DecodedCommand.Put) decoded;
            assertEquals("db.host", put.key());
            assertArrayEquals(bytes("localhost"), put.value());
        }

        @Test
        void encodePutWithEmptyValue() {
            byte[] encoded = CommandCodec.encodePut("key", new byte[0]);
            CommandCodec.DecodedCommand.Put put = (CommandCodec.DecodedCommand.Put) CommandCodec.decode(encoded);
            assertEquals("key", put.key());
            assertArrayEquals(new byte[0], put.value());
        }

        @Test
        void encodePutWithLargeValue() {
            byte[] largeValue = new byte[10_000];
            for (int i = 0; i < largeValue.length; i++) {
                largeValue[i] = (byte) (i & 0xFF);
            }
            byte[] encoded = CommandCodec.encodePut("large", largeValue);
            CommandCodec.DecodedCommand.Put put = (CommandCodec.DecodedCommand.Put) CommandCodec.decode(encoded);
            assertEquals("large", put.key());
            assertArrayEquals(largeValue, put.value());
        }

        @Test
        void putAtExactlyMaxValueSizeRoundTripsAndOneOverIsRejected() {
            // The 1 MiB value cap is a decode-side gate (decodePut): a value at exactly the cap must
            // round-trip byte-identically, and one byte over must be refused. Pins the exact boundary,
            // both sides - the largest value any other test drives is 10 KiB.
            final int maxValueSize = 1_048_576; // CommandCodec.MAX_VALUE_SIZE (1 MiB)

            byte[] atLimit = new byte[maxValueSize];
            for (int i = 0; i < atLimit.length; i++) {
                atLimit[i] = (byte) (i & 0xFF);
            }
            CommandCodec.DecodedCommand.Put put = (CommandCodec.DecodedCommand.Put)
                    CommandCodec.decode(CommandCodec.encodePut("cap", atLimit));
            assertEquals("cap", put.key());
            assertArrayEquals(atLimit, put.value(), "a value at exactly the cap decodes byte-identically");

            byte[] oneOver = CommandCodec.encodePut("cap", new byte[maxValueSize + 1]);
            assertThrows(CommandCodec.MalformedCommandException.class, () -> CommandCodec.decode(oneOver),
                    "a value one byte over the cap is refused at decode");
        }

        @Test
        void encodePutWithUnicodeKey() {
            byte[] encoded = CommandCodec.encodePut("config.日本語", bytes("value"));
            CommandCodec.DecodedCommand.Put put = (CommandCodec.DecodedCommand.Put) CommandCodec.decode(encoded);
            assertEquals("config.日本語", put.key());
            assertArrayEquals(bytes("value"), put.value());
        }

        @Test
        void encodePutNullKeyThrows() {
            assertThrows(NullPointerException.class,
                    () -> CommandCodec.encodePut(null, bytes("value")));
        }

        @Test
        void encodePutNullValueThrows() {
            assertThrows(NullPointerException.class,
                    () -> CommandCodec.encodePut("key", null));
        }
    }

    // -----------------------------------------------------------------------
    // DELETE encode/decode
    // -----------------------------------------------------------------------

    @Nested
    class DeleteCommand {

        @Test
        void encodeDeleteRoundTrip() {
            byte[] encoded = CommandCodec.encodeDelete("cache.ttl");
            CommandCodec.DecodedCommand decoded = CommandCodec.decode(encoded);

            assertInstanceOf(CommandCodec.DecodedCommand.Delete.class, decoded);
            CommandCodec.DecodedCommand.Delete del = (CommandCodec.DecodedCommand.Delete) decoded;
            assertEquals("cache.ttl", del.key());
        }

        @Test
        void encodeDeleteNullKeyThrows() {
            assertThrows(NullPointerException.class,
                    () -> CommandCodec.encodeDelete(null));
        }
    }

    // -----------------------------------------------------------------------
    // BATCH encode/decode
    // -----------------------------------------------------------------------

    @Nested
    class BatchCommand {

        @Test
        void encodeBatchWithPutsAndDeletes() {
            List<ConfigMutation> mutations = List.of(
                    new ConfigMutation.Put("a", bytes("1")),
                    new ConfigMutation.Delete("b"),
                    new ConfigMutation.Put("c", bytes("3"))
            );

            byte[] encoded = CommandCodec.encodeBatch(mutations);
            CommandCodec.DecodedCommand decoded = CommandCodec.decode(encoded);

            assertInstanceOf(CommandCodec.DecodedCommand.Batch.class, decoded);
            CommandCodec.DecodedCommand.Batch batch = (CommandCodec.DecodedCommand.Batch) decoded;
            assertEquals(3, batch.mutations().size());

            // Verify order and types
            ConfigMutation.Put put0 = assertInstanceOf(ConfigMutation.Put.class,
                    batch.mutations().get(0));
            assertEquals("a", put0.key());
            assertArrayEquals(bytes("1"), put0.value());

            ConfigMutation.Delete del1 = assertInstanceOf(ConfigMutation.Delete.class,
                    batch.mutations().get(1));
            assertEquals("b", del1.key());

            ConfigMutation.Put put2 = assertInstanceOf(ConfigMutation.Put.class,
                    batch.mutations().get(2));
            assertEquals("c", put2.key());
            assertArrayEquals(bytes("3"), put2.value());
        }

        @Test
        void encodeBatchSingleMutation() {
            List<ConfigMutation> mutations = List.of(
                    new ConfigMutation.Put("only", bytes("one"))
            );

            byte[] encoded = CommandCodec.encodeBatch(mutations);
            CommandCodec.DecodedCommand.Batch batch =
                    (CommandCodec.DecodedCommand.Batch) CommandCodec.decode(encoded);
            assertEquals(1, batch.mutations().size());
            assertEquals("only", batch.mutations().getFirst().key());
        }

        @Test
        void encodeBatchEmptyThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> CommandCodec.encodeBatch(List.of()));
        }

        @Test
        void encodeBatchNullThrows() {
            assertThrows(NullPointerException.class,
                    () -> CommandCodec.encodeBatch(null));
        }
    }

    // -----------------------------------------------------------------------
    // NOOP (empty command)
    // -----------------------------------------------------------------------

    @Nested
    class NoopCommand {

        @Test
        void emptyCommandDecodesAsNoop() {
            CommandCodec.DecodedCommand decoded = CommandCodec.decode(new byte[0]);
            assertInstanceOf(CommandCodec.DecodedCommand.Noop.class, decoded);
        }

        @Test
        void noopIsSingleton() {
            CommandCodec.DecodedCommand a = CommandCodec.decode(new byte[0]);
            CommandCodec.DecodedCommand b = CommandCodec.decode(new byte[0]);
            assertSame(a, b);
        }
    }

    // -----------------------------------------------------------------------
    // Error handling
    // -----------------------------------------------------------------------

    @Nested
    class ErrorHandling {

        @Test
        void unknownCommandTypeThrows() {
            byte[] bad = new byte[]{(byte) 0xFF};
            assertThrows(IllegalArgumentException.class,
                    () -> CommandCodec.decode(bad));
        }

        @Test
        void decodeNullThrows() {
            assertThrows(NullPointerException.class,
                    () -> CommandCodec.decode(null));
        }
    }

    // -----------------------------------------------------------------------
    // WH-01/02/03/04 hardening: decode is TOTAL / fail-closed. Every malformed
    // input surfaces as MalformedCommandException (a subtype of
    // IllegalArgumentException) and NEVER as BufferUnderflowException, so the
    // Raft apply loop can catch the malformed-decode case specifically and skip
    // the poison-pill entry deterministically instead of crash-looping.
    // -----------------------------------------------------------------------

    @Nested
    class TotalDecodeHardening {

        /** Assert decode rejects {@code bad} with the domain type and never leaks BufferUnderflow. */
        private void assertMalformed(byte[] bad) {
            assertThrows(CommandCodec.MalformedCommandException.class,
                    () -> CommandCodec.decode(bad));
            // Belt-and-braces: the exact throwable is NOT a BufferUnderflowException (which is NOT an
            // IllegalArgumentException, so it would have escaped every existing catch site).
            try {
                CommandCodec.decode(bad);
                fail("expected MalformedCommandException");
            } catch (BufferUnderflowException e) {
                fail("decode leaked BufferUnderflowException instead of MalformedCommandException");
            } catch (CommandCodec.MalformedCommandException expected) {
                // ok
            }
        }

        @Test
        void malformedIsAnIllegalArgumentException() {
            // Existing callers catch IllegalArgumentException / RuntimeException - the domain type
            // must remain assignable to both so those sites keep working.
            assertTrue(IllegalArgumentException.class.isAssignableFrom(
                    CommandCodec.MalformedCommandException.class));
        }

        @Test
        void unknownTopLevelType() {
            assertMalformed(new byte[]{(byte) 0x7F});
            assertMalformed(new byte[]{(byte) 0xFF, 0x00, 0x01});
        }

        @Test
        void putTruncatedKeyLen() {
            // type byte present, but only 1 of the 2 key-length bytes.
            assertMalformed(new byte[]{CommandCodec.TYPE_PUT, 0x00});
        }

        @Test
        void putKeyLenExceedsRemaining() {
            // declares a 5-byte key but supplies 2 bytes.
            ByteBuffer buf = ByteBuffer.allocate(1 + 2 + 2);
            buf.put(CommandCodec.TYPE_PUT);
            buf.putShort((short) 5);
            buf.put(new byte[]{'a', 'b'});
            assertMalformed(buf.array());
        }

        @Test
        void putTruncatedValueLen() {
            // valid key, then only 2 of the 4 value-length bytes.
            ByteBuffer buf = ByteBuffer.allocate(1 + 2 + 1 + 2);
            buf.put(CommandCodec.TYPE_PUT);
            buf.putShort((short) 1);
            buf.put((byte) 'k');
            buf.putShort((short) 0);
            assertMalformed(buf.array());
        }

        @Test
        void putValueLenExceedsRemaining() {
            // declares a 1000-byte value but supplies none.
            ByteBuffer buf = ByteBuffer.allocate(1 + 2 + 1 + 4);
            buf.put(CommandCodec.TYPE_PUT);
            buf.putShort((short) 1);
            buf.put((byte) 'k');
            buf.putInt(1000);
            assertMalformed(buf.array());
        }

        @Test
        void putOversizeValueLen() {
            ByteBuffer buf = ByteBuffer.allocate(1 + 2 + 1 + 4);
            buf.put(CommandCodec.TYPE_PUT);
            buf.putShort((short) 1);
            buf.put((byte) 'k');
            buf.putInt(1_048_577); // 1 byte over MAX_VALUE_SIZE
            assertMalformed(buf.array());
        }

        @Test
        void putNegativeValueLen() {
            ByteBuffer buf = ByteBuffer.allocate(1 + 2 + 1 + 4);
            buf.put(CommandCodec.TYPE_PUT);
            buf.putShort((short) 1);
            buf.put((byte) 'k');
            buf.putInt(-1);
            assertMalformed(buf.array());
        }

        @Test
        void deleteTruncatedKey() {
            // declares a 4-byte key but supplies 1.
            ByteBuffer buf = ByteBuffer.allocate(1 + 2 + 1);
            buf.put(CommandCodec.TYPE_DELETE);
            buf.putShort((short) 4);
            buf.put((byte) 'x');
            assertMalformed(buf.array());
        }

        @Test
        void batchTruncatedCount() {
            assertMalformed(new byte[]{CommandCodec.TYPE_BATCH, 0x00, 0x00});
        }

        @Test
        void batchNegativeCount() {
            ByteBuffer buf = ByteBuffer.allocate(1 + 4);
            buf.put(CommandCodec.TYPE_BATCH);
            buf.putInt(-1);
            assertMalformed(buf.array());
        }

        @Test
        void batchOversizeCount() {
            ByteBuffer buf = ByteBuffer.allocate(1 + 4);
            buf.put(CommandCodec.TYPE_BATCH);
            buf.putInt(10_001);
            assertMalformed(buf.array());
        }

        @Test
        void batchCountCannotFitRemaining() {
            // A tiny frame declaring a huge count must be rejected BEFORE the ArrayList allocation:
            // count=10_000 needs >= 30_000 bytes of mutations but the frame carries none.
            ByteBuffer buf = ByteBuffer.allocate(1 + 4);
            buf.put(CommandCodec.TYPE_BATCH);
            buf.putInt(10_000);
            assertMalformed(buf.array());
        }

        @Test
        void batchUnknownNestedMutationType() {
            ByteBuffer buf = ByteBuffer.allocate(1 + 4 + 1);
            buf.put(CommandCodec.TYPE_BATCH);
            buf.putInt(1);
            buf.put((byte) 0x7F); // unknown mutation type
            assertMalformed(buf.array());
        }

        @Test
        void batchNestedMutationTruncated() {
            // count=1, PUT type, then a key-length that overruns the buffer.
            ByteBuffer buf = ByteBuffer.allocate(1 + 4 + 1 + 2);
            buf.put(CommandCodec.TYPE_BATCH);
            buf.putInt(1);
            buf.put(CommandCodec.TYPE_PUT);
            buf.putShort((short) 9);
            assertMalformed(buf.array());
        }

        @Test
        void trailingBytesAfterPutRejected() {
            byte[] valid = CommandCodec.encodePut("k", bytes("v"));
            byte[] padded = new byte[valid.length + 3];
            System.arraycopy(valid, 0, padded, 0, valid.length);
            assertMalformed(padded);
        }

        @Test
        void trailingBytesAfterDeleteRejected() {
            byte[] valid = CommandCodec.encodeDelete("k");
            byte[] padded = new byte[valid.length + 1];
            System.arraycopy(valid, 0, padded, 0, valid.length);
            padded[valid.length] = 0x42;
            assertMalformed(padded);
        }

        @Test
        void trailingBytesAfterBatchRejected() {
            byte[] valid = CommandCodec.encodeBatch(List.of(new ConfigMutation.Put("a", bytes("1"))));
            byte[] padded = new byte[valid.length + 5];
            System.arraycopy(valid, 0, padded, 0, valid.length);
            assertMalformed(padded);
        }

        @Test
        void emptyPutKeyRejected() {
            // keyLen=0 - impossible via the write API (rejects isBlank), so a poison-pill only.
            ByteBuffer buf = ByteBuffer.allocate(1 + 2 + 4);
            buf.put(CommandCodec.TYPE_PUT);
            buf.putShort((short) 0);
            buf.putInt(0);
            assertMalformed(buf.array());
        }

        @Test
        void blankPutKeyRejected() {
            // whitespace-only key: ConfigMutation.Put would throw a plain IllegalArgumentException
            // deep inside apply (out of the malformed-decode catch), so decode must reject it here.
            byte[] keyBytes = "   ".getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(1 + 2 + keyBytes.length + 4);
            buf.put(CommandCodec.TYPE_PUT);
            buf.putShort((short) keyBytes.length);
            buf.put(keyBytes);
            buf.putInt(0);
            assertMalformed(buf.array());
        }

        @Test
        void blankDeleteKeyRejected() {
            byte[] keyBytes = "\t".getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(1 + 2 + keyBytes.length);
            buf.put(CommandCodec.TYPE_DELETE);
            buf.putShort((short) keyBytes.length);
            buf.put(keyBytes);
            assertMalformed(buf.array());
        }

        @Test
        void blankKeyNestedInBatchRejected() {
            byte[] keyBytes = " ".getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(1 + 4 + 1 + 2 + keyBytes.length);
            buf.put(CommandCodec.TYPE_BATCH);
            buf.putInt(1);
            buf.put(CommandCodec.TYPE_DELETE);
            buf.putShort((short) keyBytes.length);
            buf.put(keyBytes);
            assertMalformed(buf.array());
        }

        @Test
        void validCommandsStillDecodeByteIdentical() {
            // Byte-fidelity: every valid command round-trips unchanged. A blank key is NOT a valid
            // command (the write API forbids it), so rejecting it does not regress fidelity.
            byte[] put = CommandCodec.encodePut("db.host", bytes("localhost"));
            assertArrayEquals(put, reencode(CommandCodec.decode(put)));

            byte[] emptyVal = CommandCodec.encodePut("k", new byte[0]);
            assertArrayEquals(emptyVal, reencode(CommandCodec.decode(emptyVal)));

            byte[] del = CommandCodec.encodeDelete("cache.ttl");
            assertArrayEquals(del, reencode(CommandCodec.decode(del)));

            byte[] batch = CommandCodec.encodeBatch(List.of(
                    new ConfigMutation.Put("a", bytes("1")),
                    new ConfigMutation.Delete("b")));
            assertArrayEquals(batch, reencode(CommandCodec.decode(batch)));
        }

        private byte[] reencode(CommandCodec.DecodedCommand decoded) {
            return switch (decoded) {
                case CommandCodec.DecodedCommand.Put p -> CommandCodec.encodePut(p.key(), p.value());
                case CommandCodec.DecodedCommand.Delete d -> CommandCodec.encodeDelete(d.key());
                case CommandCodec.DecodedCommand.Batch b -> CommandCodec.encodeBatch(b.mutations());
                case CommandCodec.DecodedCommand.Noop n -> new byte[0];
            };
        }
    }

    // -----------------------------------------------------------------------
    // Binary format correctness
    // -----------------------------------------------------------------------

    @Nested
    class BinaryFormat {

        @Test
        void putCommandStartsWithCorrectTypeByte() {
            byte[] encoded = CommandCodec.encodePut("k", bytes("v"));
            assertEquals(0x01, encoded[0]);
        }

        @Test
        void deleteCommandStartsWithCorrectTypeByte() {
            byte[] encoded = CommandCodec.encodeDelete("k");
            assertEquals(0x02, encoded[0]);
        }

        @Test
        void batchCommandStartsWithCorrectTypeByte() {
            byte[] encoded = CommandCodec.encodeBatch(List.of(
                    new ConfigMutation.Put("k", bytes("v"))));
            assertEquals(0x03, encoded[0]);
        }
    }
}

package io.configd.store;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CommandCodec} — encode/decode roundtrip for all command types.
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

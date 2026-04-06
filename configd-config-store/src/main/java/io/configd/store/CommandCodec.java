package io.configd.store;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Encodes and decodes commands for the Raft log.
 * <p>
 * Used by {@link ConfigStateMachine} to deserialize committed commands and by
 * write services to serialize them before proposing to the Raft leader.
 * <p>
 * <b>Command format (simple binary):</b>
 * <pre>
 *   byte 0: command type (0x01 = PUT, 0x02 = DELETE, 0x03 = BATCH)
 *   PUT:    [0x01][2-byte key length][key bytes][4-byte value length][value bytes]
 *   DELETE: [0x02][2-byte key length][key bytes]
 *   BATCH:  [0x03][4-byte count][mutation1][mutation2]...
 *     where each mutation is a PUT or DELETE as above
 * </pre>
 * An empty (zero-length) command represents a no-op, committed for leader
 * election. The codec treats this as a special case — see {@link #decode}.
 * <p>
 * This is a stateless utility class. All methods are static. Instances cannot
 * be created.
 */
public final class CommandCodec {

    /** Command type byte for PUT. */
    static final byte TYPE_PUT = 0x01;

    /** Command type byte for DELETE. */
    static final byte TYPE_DELETE = 0x02;

    /** Command type byte for BATCH. */
    static final byte TYPE_BATCH = 0x03;

    private CommandCodec() {
        // utility class — no instances
    }

    // -----------------------------------------------------------------------
    // Encoding
    // -----------------------------------------------------------------------

    /**
     * Encodes a PUT command.
     *
     * @param key   config key (non-null, non-blank)
     * @param value raw config bytes (non-null)
     * @return serialized command bytes
     */
    public static byte[] encodePut(String key, byte[] value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");

        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        // 1 (type) + 2 (key len) + key + 4 (value len) + value
        ByteBuffer buf = ByteBuffer.allocate(1 + 2 + keyBytes.length + 4 + value.length);
        buf.put(TYPE_PUT);
        buf.putShort((short) keyBytes.length);
        buf.put(keyBytes);
        buf.putInt(value.length);
        buf.put(value);
        return buf.array();
    }

    /**
     * Encodes a DELETE command.
     *
     * @param key config key (non-null, non-blank)
     * @return serialized command bytes
     */
    public static byte[] encodeDelete(String key) {
        Objects.requireNonNull(key, "key must not be null");

        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        // 1 (type) + 2 (key len) + key
        ByteBuffer buf = ByteBuffer.allocate(1 + 2 + keyBytes.length);
        buf.put(TYPE_DELETE);
        buf.putShort((short) keyBytes.length);
        buf.put(keyBytes);
        return buf.array();
    }

    /**
     * Encodes a BATCH command from a list of mutations.
     *
     * @param mutations list of mutations (non-null, non-empty)
     * @return serialized command bytes
     * @throws IllegalArgumentException if mutations is empty
     */
    public static byte[] encodeBatch(List<ConfigMutation> mutations) {
        Objects.requireNonNull(mutations, "mutations must not be null");
        if (mutations.isEmpty()) {
            throw new IllegalArgumentException("mutations must not be empty");
        }

        // Pre-compute size: 1 (type) + 4 (count) + sum of mutation sizes
        int size = 1 + 4;
        List<byte[]> encoded = new ArrayList<>(mutations.size());
        for (ConfigMutation mutation : mutations) {
            byte[] bytes = encodeMutation(mutation);
            encoded.add(bytes);
            size += bytes.length;
        }

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(TYPE_BATCH);
        buf.putInt(mutations.size());
        for (byte[] bytes : encoded) {
            buf.put(bytes);
        }
        return buf.array();
    }

    // -----------------------------------------------------------------------
    // Decoding
    // -----------------------------------------------------------------------

    /**
     * Decodes a command from its serialized bytes.
     * <p>
     * An empty (zero-length) command is decoded as {@link DecodedCommand.Noop}.
     *
     * @param command serialized command bytes (non-null)
     * @return decoded command
     * @throws IllegalArgumentException if the command bytes are malformed
     */
    public static DecodedCommand decode(byte[] command) {
        Objects.requireNonNull(command, "command must not be null");

        if (command.length == 0) {
            return DecodedCommand.Noop.INSTANCE;
        }

        ByteBuffer buf = ByteBuffer.wrap(command);
        byte type = buf.get();
        return switch (type) {
            case TYPE_PUT -> decodePut(buf);
            case TYPE_DELETE -> decodeDelete(buf);
            case TYPE_BATCH -> decodeBatch(buf);
            default -> throw new IllegalArgumentException(
                    "Unknown command type: 0x" + String.format("%02x", type));
        };
    }

    // -----------------------------------------------------------------------
    // DecodedCommand sealed hierarchy
    // -----------------------------------------------------------------------

    /**
     * A decoded Raft log command. Sealed to the four permitted variants.
     */
    public sealed interface DecodedCommand
            permits DecodedCommand.Put, DecodedCommand.Delete,
                    DecodedCommand.Batch, DecodedCommand.Noop {

        /**
         * A decoded PUT command.
         *
         * @param key   config key
         * @param value raw config bytes
         */
        record Put(String key, byte[] value) implements DecodedCommand {
            public Put {
                Objects.requireNonNull(key, "key must not be null");
                Objects.requireNonNull(value, "value must not be null");
            }
        }

        /**
         * A decoded DELETE command.
         *
         * @param key config key to remove
         */
        record Delete(String key) implements DecodedCommand {
            public Delete {
                Objects.requireNonNull(key, "key must not be null");
            }
        }

        /**
         * A decoded BATCH command containing multiple mutations.
         *
         * @param mutations ordered list of mutations
         */
        record Batch(List<ConfigMutation> mutations) implements DecodedCommand {
            public Batch {
                Objects.requireNonNull(mutations, "mutations must not be null");
                mutations = List.copyOf(mutations);
            }
        }

        /**
         * A no-op command (empty payload). Committed for leader election.
         * Singleton — use {@link Noop#INSTANCE}.
         */
        record Noop() implements DecodedCommand {
            static final Noop INSTANCE = new Noop();
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Encodes a single mutation (PUT or DELETE) without the BATCH wrapper.
     * Used internally by {@link #encodeBatch} and also reused in the
     * stand-alone {@link #encodePut} / {@link #encodeDelete} methods via
     * the same binary format.
     */
    private static byte[] encodeMutation(ConfigMutation mutation) {
        return switch (mutation) {
            case ConfigMutation.Put put -> encodePut(put.key(), put.valueUnsafe());
            case ConfigMutation.Delete del -> encodeDelete(del.key());
        };
    }

    private static final int MAX_VALUE_SIZE = 1_048_576; // 1 MB

    private static DecodedCommand.Put decodePut(ByteBuffer buf) {
        int keyLen = Short.toUnsignedInt(buf.getShort());
        byte[] keyBytes = new byte[keyLen];
        buf.get(keyBytes);
        String key = new String(keyBytes, StandardCharsets.UTF_8);

        int valueLen = buf.getInt();
        if (valueLen < 0 || valueLen > MAX_VALUE_SIZE) {
            throw new IllegalArgumentException(
                    "Value length out of range: " + valueLen + " (max " + MAX_VALUE_SIZE + ")");
        }
        byte[] value = new byte[valueLen];
        buf.get(value);

        return new DecodedCommand.Put(key, value);
    }

    private static DecodedCommand.Delete decodeDelete(ByteBuffer buf) {
        int keyLen = Short.toUnsignedInt(buf.getShort());
        byte[] keyBytes = new byte[keyLen];
        buf.get(keyBytes);
        String key = new String(keyBytes, StandardCharsets.UTF_8);

        return new DecodedCommand.Delete(key);
    }

    private static final int MAX_BATCH_COUNT = 10_000;

    private static DecodedCommand.Batch decodeBatch(ByteBuffer buf) {
        int count = buf.getInt();
        if (count < 0 || count > MAX_BATCH_COUNT) {
            throw new IllegalArgumentException(
                    "Batch count out of range: " + count + " (max " + MAX_BATCH_COUNT + ")");
        }
        List<ConfigMutation> mutations = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            byte type = buf.get();
            switch (type) {
                case TYPE_PUT -> {
                    DecodedCommand.Put put = decodePut(buf);
                    mutations.add(new ConfigMutation.Put(put.key(), put.value()));
                }
                case TYPE_DELETE -> {
                    DecodedCommand.Delete del = decodeDelete(buf);
                    mutations.add(new ConfigMutation.Delete(del.key()));
                }
                default -> throw new IllegalArgumentException(
                        "Unknown mutation type in batch: 0x" + String.format("%02x", type));
            }
        }

        return new DecodedCommand.Batch(mutations);
    }
}

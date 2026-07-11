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
 * election. The codec treats this as a special case - see {@link #decode}.
 * <p>
 * <b>Carrier-versioned.</b> These bytes carry no format version of their own and
 * never exist standalone: they are always nested inside a self-versioned carrier,
 * so the carrier's version pins this grammar. The three carriers are (1) WAL
 * entries - inside the {@code WALE_MAGIC} {@link io.configd.common.IntegrityEnvelope};
 * (2) NOTIFY deltas - inside the versioned edge frame; (3) snapshot values - inside
 * the {@code SNAP_MAGIC} envelope / the edge snapshot body (itself carried by an edge
 * frame). A redundant inner version byte would bloat every command in every log entry
 * for no decode benefit. The type byte is a <em>discriminant</em>, not a version: the
 * unknown-type-byte throw in {@link #decode} is the assert that keeps decoding safe.
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
        // utility class - no instances
    }

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

    /**
     * Thrown by {@link #decode} for any grammatically-malformed command: an unknown type
     * discriminant, a truncated / over-declared length field, or trailing bytes after a
     * fully-parsed command.
     * <p>
     * Decode is <b>total</b>: it never leaks a {@link java.nio.BufferUnderflowException} or any
     * other unchecked throwable for attacker-controlled bytes - every malformed input surfaces as
     * this single, well-defined domain type. It extends {@link IllegalArgumentException} so the
     * existing callers that catch {@code IllegalArgumentException} / {@code RuntimeException}
     * continue to work unchanged, while the Raft apply path can catch it <em>specifically</em> to
     * distinguish "the committed command bytes are malformed" (a deterministic, skip-safe condition)
     * from a genuine bug elsewhere in apply.
     */
    public static final class MalformedCommandException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        MalformedCommandException(String message) {
            super(message);
        }
    }

    /**
     * Decodes a command from its serialized bytes.
     * <p>
     * An empty (zero-length) command is decoded as {@link DecodedCommand.Noop}.
     * <p>
     * This method is <b>total / fail-closed</b>: for any non-well-formed input it throws
     * {@link MalformedCommandException} (a subtype of {@link IllegalArgumentException}) and never a
     * {@link java.nio.BufferUnderflowException} or other unchecked throwable. Every length field is
     * bounds-checked against the buffer <em>before</em> any allocation, and a fully-parsed command
     * must consume its input exactly - trailing bytes are rejected.
     *
     * @param command serialized command bytes (non-null)
     * @return decoded command
     * @throws MalformedCommandException if the command bytes are malformed
     */
    public static DecodedCommand decode(byte[] command) {
        Objects.requireNonNull(command, "command must not be null");

        if (command.length == 0) {
            return DecodedCommand.Noop.INSTANCE;
        }

        ByteBuffer buf = ByteBuffer.wrap(command);
        byte type = buf.get();
        DecodedCommand decoded = switch (type) {
            case TYPE_PUT -> decodePut(buf);
            case TYPE_DELETE -> decodeDelete(buf);
            case TYPE_BATCH -> decodeBatch(buf);
            default -> throw new MalformedCommandException(
                    "Unknown command type: 0x" + String.format("%02x", type));
        };
        // Strict end: a well-formed PUT/DELETE/BATCH consumes its bytes exactly. Trailing padding is
        // rejected only at this top-level boundary - BATCH nests PUT/DELETE via decodePut/decodeDelete
        // on the SAME buffer, so a strict-end check inside those helpers would (wrongly) fire on every
        // non-final batch element.
        if (buf.hasRemaining()) {
            throw new MalformedCommandException(
                    buf.remaining() + " trailing byte(s) after a fully-parsed "
                            + decoded.getClass().getSimpleName());
        }
        return decoded;
    }

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
         * Singleton - use {@link Noop#INSTANCE}.
         */
        record Noop() implements DecodedCommand {
            static final Noop INSTANCE = new Noop();
        }
    }

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

    /**
     * Smallest number of bytes a single BATCH element can occupy on the wire: 1 (type discriminant)
     * + 2 (u16 key length). The key itself is at least 1 byte (blank keys are rejected), but this
     * conservative lower bound is only used to reject an impossibly-large declared {@code count}
     * before allocating the mutation list.
     */
    private static final int MIN_MUTATION_BYTES = 1 + 2;

    /**
     * Fail-closed bounds guard: throws {@link MalformedCommandException} if fewer than
     * {@code needed} bytes remain, so the caller never triggers a {@link java.nio.BufferUnderflowException}.
     * Mirrors {@code RaftMessageCodec.checkRemaining}.
     */
    private static void checkRemaining(ByteBuffer buf, int needed, String field) {
        if (buf.remaining() < needed) {
            throw new MalformedCommandException(
                    "Truncated " + field + ": need " + needed
                            + " byte(s) but " + buf.remaining() + " remain");
        }
    }

    /**
     * Reads a length-prefixed, UTF-8 config key with full bounds enforcement. Rejects a blank key on
     * decode: no legitimately-encodable command carries one - {@code ConfigWriteService} rejects
     * {@code isBlank} before proposing and {@link ConfigMutation.Put}/{@link ConfigMutation.Delete}
     * reject it at construction - so this preserves byte-fidelity of every valid command while
     * closing a poison-pill path (a blank key that survived decode would throw a plain
     * {@code IllegalArgumentException} deep inside apply, out of reach of the malformed-decode catch).
     */
    private static String readKey(ByteBuffer buf, String field) {
        checkRemaining(buf, 2, field + " length");
        int keyLen = Short.toUnsignedInt(buf.getShort());
        checkRemaining(buf, keyLen, field);
        byte[] keyBytes = new byte[keyLen];
        buf.get(keyBytes);
        String key = new String(keyBytes, StandardCharsets.UTF_8);
        if (key.isBlank()) {
            throw new MalformedCommandException(field + " must not be blank");
        }
        return key;
    }

    private static DecodedCommand.Put decodePut(ByteBuffer buf) {
        String key = readKey(buf, "PUT key");

        checkRemaining(buf, 4, "PUT value length");
        int valueLen = buf.getInt();
        if (valueLen < 0 || valueLen > MAX_VALUE_SIZE) {
            throw new MalformedCommandException(
                    "Value length out of range: " + valueLen + " (max " + MAX_VALUE_SIZE + ")");
        }
        checkRemaining(buf, valueLen, "PUT value");
        byte[] value = new byte[valueLen];
        buf.get(value);

        return new DecodedCommand.Put(key, value);
    }

    private static DecodedCommand.Delete decodeDelete(ByteBuffer buf) {
        String key = readKey(buf, "DELETE key");
        return new DecodedCommand.Delete(key);
    }

    private static final int MAX_BATCH_COUNT = 10_000;

    private static DecodedCommand.Batch decodeBatch(ByteBuffer buf) {
        checkRemaining(buf, 4, "BATCH count");
        int count = buf.getInt();
        if (count < 0 || count > MAX_BATCH_COUNT) {
            throw new MalformedCommandException(
                    "Batch count out of range: " + count + " (max " + MAX_BATCH_COUNT + ")");
        }
        // Reject up front if the declared count cannot possibly fit in the remaining buffer - blocks a
        // tiny adversary frame from pre-sizing a large list (mirrors decodeCoalescedHeartbeat).
        if ((long) count * MIN_MUTATION_BYTES > buf.remaining()) {
            throw new MalformedCommandException(
                    "Batch declares " + count + " mutations but only "
                            + buf.remaining() + " bytes remain");
        }
        List<ConfigMutation> mutations = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            checkRemaining(buf, 1, "BATCH mutation[" + i + "] type");
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
                default -> throw new MalformedCommandException(
                        "Unknown mutation type in batch: 0x" + String.format("%02x", type));
            }
        }

        return new DecodedCommand.Batch(mutations);
    }
}

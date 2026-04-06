package io.configd.transport;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.CRC32C;

/**
 * Encoder/decoder for the Configd wire protocol frame format
 * (ADR-0010 v0; ADR-0029 v1: version byte + CRC32C trailer).
 *
 * <p>Frame layout (v1):
 * <pre>
 *   [Length: 4 bytes]
 *   [Version: 1 byte]
 *   [Type: 1 byte]
 *   [GroupId: 4 bytes]
 *   [Term: 8 bytes]
 *   [Payload: variable]
 *   [CRC32C: 4 bytes]
 * </pre>
 *
 * <ul>
 *   <li><b>Length</b> — total frame size in bytes, including length and
 *       trailer. Big-endian.</li>
 *   <li><b>Version</b> — wire-format major version. Decoder rejects any
 *       value other than {@link #WIRE_VERSION} with
 *       {@link UnsupportedWireVersionException}. Required for any
 *       future N-1/N rolling upgrade.</li>
 *   <li><b>Type</b> — {@link MessageType} code.</li>
 *   <li><b>GroupId</b> — Raft group identifier, big-endian.</li>
 *   <li><b>Term</b> — Raft term, big-endian.</li>
 *   <li><b>Payload</b> — message-specific bytes. May be empty.</li>
 *   <li><b>CRC32C</b> — Castagnoli polynomial checksum
 *       ({@link java.util.zip.CRC32C}) over <em>all preceding
 *       bytes</em> (length through end of payload). Defense-in-depth
 *       against bit flips and bug-induced corruption inside a TLS
 *       session — TLS itself provides the cryptographic integrity
 *       check at the connection layer.</li>
 * </ul>
 *
 * <p>This class is stateless — all methods are static. Decode allocates
 * exactly one {@code byte[]} for the payload and one {@link Frame}
 * record; the CRC computation uses an instance-local {@link CRC32C}
 * but does not allocate beyond it.
 *
 * @see MessageType
 */
public final class FrameCodec {

    /**
     * Current wire-format major version. The decoder rejects any other
     * value with {@link UnsupportedWireVersionException} — at the v1
     * milestone this is a strict tripwire, NOT a negotiation. The
     * version byte exists so that v2 can land with a peer-side Hello
     * handshake (ADR-0030+) and accept both v1 and v2 frames during a
     * rolling upgrade. Until that handshake exists, mixed-version
     * traffic terminates the connection.
     *
     * <p>Bumping this constant is a controlled action governed by the
     * {@code wire-compat} CI job: any change to fixture bytes under
     * {@code wire-fixtures/v<N>/} without a corresponding
     * {@code WIRE_VERSION} bump fails CI.
     */
    public static final byte WIRE_VERSION = (byte) 0x01;

    /**
     * Fixed header size: 4 (length) + 1 (version) + 1 (type) +
     * 4 (groupId) + 8 (term) = 18 bytes.
     */
    public static final int HEADER_SIZE = 18;

    /** Trailer size: 4 bytes for the CRC32C checksum. */
    public static final int TRAILER_SIZE = 4;

    /**
     * Hard upper bound on frame size, in bytes (16 MiB). Bounds any
     * peer's allocation on the read path AND any local encoder's
     * allocation on the write path — the encoder rejects oversize
     * payloads symmetrically with the decoder, so the frame that
     * goes onto the wire is always one the receiver will accept.
     *
     * <p>Public so application-layer codecs (e.g. {@code
     * RaftMessageCodec}) can size their per-message blob caps
     * against the same constant rather than re-deriving the value.
     */
    public static final int MAX_FRAME_SIZE = 16 * 1024 * 1024;

    private FrameCodec() {
        // utility class
    }

    /**
     * Thrown when the decoder encounters a frame whose declared wire
     * version differs from {@link #WIRE_VERSION}. Callers catching
     * this exception can surface a structured "incompatible peer"
     * error without conflating it with framing or checksum errors.
     */
    public static final class UnsupportedWireVersionException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int observedVersion;

        public UnsupportedWireVersionException(int observedVersion) {
            super("Unsupported wire version: 0x"
                    + Integer.toHexString(observedVersion & 0xFF)
                    + " (expected 0x"
                    + Integer.toHexString(WIRE_VERSION & 0xFF) + ")");
            this.observedVersion = observedVersion & 0xFF;
        }

        /** The unsigned wire-version byte observed in the frame. */
        public int observedVersion() {
            return observedVersion;
        }
    }

    /**
     * A decoded frame from the wire.
     *
     * @param messageType the protocol message type
     * @param groupId     the Raft group this frame belongs to
     * @param term        the Raft term
     * @param payload     message-specific payload bytes (may be empty, never null)
     */
    public record Frame(
            MessageType messageType,
            int groupId,
            long term,
            byte[] payload
    ) {
        public Frame {
            Objects.requireNonNull(messageType, "messageType must not be null");
            Objects.requireNonNull(payload, "payload must not be null");
        }
    }

    /**
     * Encodes a frame into a newly allocated byte array.
     *
     * @param messageType the message type
     * @param groupId     the Raft group identifier
     * @param term        the Raft term
     * @param payload     the payload bytes (may be empty)
     * @return the encoded frame bytes (length includes header + payload + trailer)
     */
    public static byte[] encode(MessageType messageType, int groupId, long term, byte[] payload) {
        Objects.requireNonNull(messageType, "messageType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        checkPayloadFitsFrame(payload.length);

        int totalLength = frameSize(payload.length);
        byte[] frame = new byte[totalLength];
        ByteBuffer buf = ByteBuffer.wrap(frame);

        buf.putInt(totalLength);
        buf.put(WIRE_VERSION);
        buf.put((byte) messageType.code());
        buf.putInt(groupId);
        buf.putLong(term);
        buf.put(payload);

        // CRC32C over [length, version, type, groupId, term, payload].
        CRC32C crc = new CRC32C();
        crc.update(frame, 0, totalLength - TRAILER_SIZE);
        buf.putInt((int) crc.getValue());

        return frame;
    }

    /**
     * Encodes a frame into the given {@link ByteBuffer}. The buffer
     * must have at least {@link #frameSize(int)} remaining capacity
     * for the supplied payload.
     *
     * @param buf         the destination buffer
     * @param messageType the message type
     * @param groupId     the Raft group identifier
     * @param term        the Raft term
     * @param payload     the payload bytes
     */
    public static void encode(ByteBuffer buf, MessageType messageType,
                              int groupId, long term, byte[] payload) {
        Objects.requireNonNull(buf, "buf must not be null");
        Objects.requireNonNull(messageType, "messageType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        checkPayloadFitsFrame(payload.length);

        int totalLength = frameSize(payload.length);
        if (buf.remaining() < totalLength) {
            // Fail before any write so the destination buffer stays
            // unmodified — partial writes (length prefix written, body
            // half-written, no trailer) would corrupt the buffer for
            // any subsequent reader.
            throw new IllegalArgumentException(
                    "ByteBuffer has " + buf.remaining()
                            + " bytes remaining but frame needs " + totalLength);
        }
        int frameStart = buf.position();

        buf.putInt(totalLength);
        buf.put(WIRE_VERSION);
        buf.put((byte) messageType.code());
        buf.putInt(groupId);
        buf.putLong(term);
        buf.put(payload);

        // CRC32C over the bytes we just wrote, excluding the trailer.
        // We need a duplicate view positioned at frameStart with limit
        // at frameStart + (totalLength - TRAILER_SIZE) so CRC32C.update
        // sees the bytes regardless of whether buf is heap-backed or
        // direct.
        ByteBuffer crcSlice = buf.duplicate();
        crcSlice.position(frameStart);
        crcSlice.limit(frameStart + totalLength - TRAILER_SIZE);
        CRC32C crc = new CRC32C();
        crc.update(crcSlice);
        buf.putInt((int) crc.getValue());
    }

    /**
     * Decodes a frame from the given byte array. The array must
     * contain exactly one complete frame.
     *
     * <p>Validation order (deliberate — distinct exceptions narrow
     * the diagnostic question):
     * <ol>
     *   <li>Array shorter than {@code HEADER_SIZE + TRAILER_SIZE}
     *       → {@link IllegalArgumentException}.</li>
     *   <li>Length prefix &lt; minimum or &gt; {@link #MAX_FRAME_SIZE}
     *       or != {@code data.length}
     *       → {@link IllegalArgumentException}.</li>
     *   <li>CRC32C trailer mismatch → {@link IllegalArgumentException}
     *       with prefix {@code "CRC32C mismatch"}. Verified BEFORE
     *       version / type / payload so a single bit-flip in any of
     *       those fields surfaces as "corruption" rather than a
     *       misleading "wire version mismatch" or "unknown type".</li>
     *   <li>Version byte != {@link #WIRE_VERSION}
     *       → {@link UnsupportedWireVersionException}.</li>
     *   <li>Type code unknown → {@link IllegalArgumentException}
     *       (delegated to {@link MessageType#fromCode(int)}).</li>
     * </ol>
     *
     * @param data the raw frame bytes
     * @return the decoded frame
     * @throws IllegalArgumentException          on framing, length, or checksum errors
     * @throws UnsupportedWireVersionException   when the version byte is unrecognised
     */
    public static Frame decode(byte[] data) {
        Objects.requireNonNull(data, "data must not be null");
        int minSize = HEADER_SIZE + TRAILER_SIZE;
        if (data.length < minSize) {
            throw new IllegalArgumentException(
                    "Frame too short: " + data.length + " bytes, minimum " + minSize);
        }

        ByteBuffer buf = ByteBuffer.wrap(data);
        int length = buf.getInt();
        if (length < minSize || length > MAX_FRAME_SIZE) {
            throw new IllegalArgumentException(
                    "Frame length out of bounds: " + length
                            + " (min " + minSize + ", max " + MAX_FRAME_SIZE + ")");
        }
        if (length != data.length) {
            throw new IllegalArgumentException(
                    "Frame length mismatch: header says " + length
                            + " but data is " + data.length);
        }

        // Verify CRC32C BEFORE reading any other field. A bit-flip in
        // the version byte or type code must surface as "corruption"
        // rather than as a misleading "wire version mismatch" or
        // "unknown type code" error — those would point operators at
        // the wrong root cause (deployment misconfiguration vs.
        // hardware fault).
        int crcOffset = length - TRAILER_SIZE;
        CRC32C crc = new CRC32C();
        crc.update(data, 0, crcOffset);
        int computed = (int) crc.getValue();
        int trailer = ByteBuffer.wrap(data, crcOffset, TRAILER_SIZE).getInt();
        if (computed != trailer) {
            throw new IllegalArgumentException(
                    "CRC32C mismatch: computed=0x"
                            + Integer.toHexString(computed)
                            + " trailer=0x"
                            + Integer.toHexString(trailer));
        }

        // CRC has confirmed the bytes are intact — now version and
        // type code are trustworthy.
        byte version = buf.get();
        if (version != WIRE_VERSION) {
            throw new UnsupportedWireVersionException(version);
        }

        int typeCode = buf.get() & 0xFF;
        MessageType type = MessageType.fromCode(typeCode);
        int groupId = buf.getInt();
        long term = buf.getLong();

        int payloadLen = data.length - HEADER_SIZE - TRAILER_SIZE;
        byte[] payload = new byte[payloadLen];
        buf.get(payload);

        return new Frame(type, groupId, term, payload);
    }

    /**
     * Reads the frame length from the first 4 bytes without consuming
     * further data. Useful for framing in a streaming decoder — the
     * reader uses this to size its buffer before pulling the rest of
     * the frame off the socket.
     *
     * <p>The returned value is bound-checked against the same
     * {@code [HEADER_SIZE + TRAILER_SIZE, MAX_FRAME_SIZE]} range as
     * {@link #decode}, so a caller can trust it to size its read
     * buffer without a separate validation step. This blocks an
     * adversary from inducing a multi-GiB allocation by claiming a
     * giant length in the first 4 bytes.
     *
     * @param data buffer containing at least 4 bytes
     * @return the total frame length in bytes
     * @throws IllegalArgumentException if the buffer is too short or the
     *                                  declared length is out of range
     */
    public static int peekLength(byte[] data) {
        if (data.length < 4) {
            throw new IllegalArgumentException("Need at least 4 bytes to read frame length");
        }
        int length = ByteBuffer.wrap(data, 0, 4).getInt();
        int minSize = HEADER_SIZE + TRAILER_SIZE;
        if (length < minSize || length > MAX_FRAME_SIZE) {
            throw new IllegalArgumentException(
                    "Frame length out of bounds: " + length
                            + " (min " + minSize + ", max " + MAX_FRAME_SIZE + ")");
        }
        return length;
    }

    /**
     * Returns the required frame size for the given payload length.
     * Always {@code HEADER_SIZE + payloadLength + TRAILER_SIZE}.
     */
    public static int frameSize(int payloadLength) {
        return HEADER_SIZE + payloadLength + TRAILER_SIZE;
    }

    private static void checkPayloadFitsFrame(int payloadLength) {
        if (payloadLength < 0) {
            throw new IllegalArgumentException(
                    "Negative payload length: " + payloadLength);
        }
        // payloadLength + HEADER + TRAILER must fit in MAX_FRAME_SIZE.
        // Use long arithmetic to avoid 32-bit overflow when payloadLength
        // approaches Integer.MAX_VALUE.
        long total = (long) payloadLength + HEADER_SIZE + TRAILER_SIZE;
        if (total > MAX_FRAME_SIZE) {
            throw new IllegalArgumentException(
                    "Frame too large: payload " + payloadLength
                            + " bytes would produce a " + total
                            + "-byte frame, exceeds MAX_FRAME_SIZE="
                            + MAX_FRAME_SIZE);
        }
    }
}

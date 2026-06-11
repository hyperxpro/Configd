package io.configd.distribution.wire;

import io.configd.distribution.CommitNotification;
import io.configd.store.CommandCodec;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32C;

/**
 * Encoder/decoder for the edge streaming protocol-v1 wire format (C1 design §3;
 * ADR-0037). A <b>separate</b> codec and version byte from the Raft {@code FrameCodec}
 * (ADR-0037: the Raft wire and the edge wire evolve on different cadences and must not
 * share a fixture gate), following the identical structural discipline:
 *
 * <pre>
 *   [Length: 4 bytes]      total frame size incl. length + trailer, big-endian
 *   [Version: 1 byte]      {@link #EDGE_WIRE_VERSION}
 *   [Type: 1 byte]         {@link FrameType} code
 *   [Payload: variable]    frame-type-specific
 *   [CRC32C: 4 bytes]      Castagnoli checksum over length..end-of-payload
 * </pre>
 *
 * <h2>Discipline carried verbatim from {@code FrameCodec} (ADR-0037)</h2>
 * <ul>
 *   <li><b>{@link #peekLength} bounds-checks the declared length BEFORE any
 *       allocation</b> — an adversary cannot induce a multi-MiB allocation by lying in
 *       the first 4 bytes.</li>
 *   <li><b>CRC32C is validated BEFORE the version/type bytes are interpreted</b> — a
 *       bit-flip in the version or type byte surfaces as {@link ErrorCode#FRAME_CORRUPT}
 *       (a {@link CodecException} of code {@code FRAME_CORRUPT}), never as a misleading
 *       "bad version" or "unknown type".</li>
 *   <li><b>Explicit frame cap {@link #MAX_EDGE_FRAME_SIZE}</b> (2 MiB — large enough for
 *       a 1 MiB snapshot chunk plus overhead), and a {@link #MAX_SNAPSHOT_CHUNK_BYTES}
 *       (1 MiB) cap on a chunk payload.</li>
 *   <li><b>NOTIFY batch caps enforced at encode</b> ({@link #MAX_NOTIFY_BATCH} = 64
 *       notifications / {@link #MAX_NOTIFY_BATCH_BYTES} = 256 KiB; CT-17 / ADR-0038
 *       frame-level batching).</li>
 * </ul>
 *
 * <h2>Delta byte fidelity (ADR-0038)</h2>
 * Each NOTIFY notification carries its {@link ConfigDelta} so that
 * {@link ConfigDelta#signingPayload()} round-trips <b>byte-identical</b> (signature
 * verification at the edge depends on it): the mutation list is re-encoded with
 * {@link CommandCodec#encodeBatch} (the exact bytes {@code signingPayload} hashes) and
 * the {@code signature}/{@code epoch}/{@code nonce} bytes are carried raw and
 * verbatim. Proven by {@code EdgeFrameCodecPropertyTest}.
 *
 * <h2>Errors</h2>
 * Structural decode failures throw {@link CodecException}, whose {@link CodecException#code()}
 * maps onto the {@link ErrorCode} taxonomy ({@code FRAME_TOO_LARGE}, {@code FRAME_CORRUPT},
 * {@code BAD_WIRE_VERSION}). The class is stateless (all methods static) and carries no
 * {@code java.net} / socket / TLS type — the only transport boundary is the session's
 * {@code TransportSink}.
 */
public final class EdgeFrameCodec {

    /**
     * The edge protocol wire version (ADR-0037: separate from {@code FrameCodec.WIRE_VERSION}).
     * The decoder rejects any other value with {@link ErrorCode#BAD_WIRE_VERSION}.
     *
     * <p><b>Rebaseline rule (golden fixtures):</b> any change to the bytes any frame
     * encodes to — a new field, a reordered field, a changed type/error code — MUST bump
     * this constant and re-generate {@code EdgeFrameCodecGoldenFixtureTest}'s hex
     * constants. The golden test fails on any drift without a version bump, exactly as
     * the Raft {@code WireCompatGoldenBytesTest} guards the consensus wire.
     */
    public static final byte EDGE_WIRE_VERSION = (byte) 0x01;

    /** Fixed header: 4 (length) + 1 (version) + 1 (type) = 6 bytes. */
    public static final int HEADER_SIZE = 6;

    /** Trailer: 4 bytes CRC32C. */
    public static final int TRAILER_SIZE = 4;

    /**
     * Hard frame cap (2 MiB). Must fit a 1 MiB snapshot chunk plus the
     * {@code SNAPSHOT_CHUNK} index field, header, and trailer with ample headroom.
     */
    public static final int MAX_EDGE_FRAME_SIZE = 2 * 1024 * 1024;

    /** Per-chunk snapshot payload cap (1 MiB). */
    public static final int MAX_SNAPSHOT_CHUNK_BYTES = 1024 * 1024;

    /** Max notifications per NOTIFY frame (CT-17 / design §4 {@code batchMaxNotifications}). */
    public static final int MAX_NOTIFY_BATCH = 64;

    /** Max encoded NOTIFY payload bytes (256 KiB; design §4 {@code batchMaxBytes}). */
    public static final int MAX_NOTIFY_BATCH_BYTES = 256 * 1024;

    private EdgeFrameCodec() {
        // utility class
    }

    /**
     * A structural decode (or encode-bounds) failure. {@link #code()} maps the failure
     * onto the {@link ErrorCode} taxonomy so the session can emit the right
     * {@code ERROR_CLOSE} frame.
     */
    public static final class CodecException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final ErrorCode code;

        public CodecException(ErrorCode code, String message) {
            super(message);
            this.code = code;
        }

        /** The taxonomy code this failure maps to. */
        public ErrorCode code() {
            return code;
        }
    }

    // -----------------------------------------------------------------------
    // Encode
    // -----------------------------------------------------------------------

    /**
     * Encodes a frame to a newly allocated byte array.
     *
     * @param frame the frame to encode
     * @return the wire bytes (length includes header + payload + CRC trailer)
     * @throws CodecException if the encoded frame would exceed {@link #MAX_EDGE_FRAME_SIZE}
     *                        or a NOTIFY batch exceeds its caps
     */
    public static byte[] encode(EdgeFrame frame) {
        Objects.requireNonNull(frame, "frame must not be null");
        byte[] payload = encodePayload(frame);

        long total = (long) HEADER_SIZE + payload.length + TRAILER_SIZE;
        if (total > MAX_EDGE_FRAME_SIZE) {
            throw new CodecException(ErrorCode.FRAME_TOO_LARGE,
                    "encoded frame " + total + " bytes exceeds MAX_EDGE_FRAME_SIZE="
                            + MAX_EDGE_FRAME_SIZE + " (type " + frame.type() + ")");
        }
        int totalLen = (int) total;

        byte[] out = new byte[totalLen];
        ByteBuffer buf = ByteBuffer.wrap(out);
        buf.putInt(totalLen);
        buf.put(EDGE_WIRE_VERSION);
        buf.put((byte) frame.type().code());
        buf.put(payload);

        CRC32C crc = new CRC32C();
        crc.update(out, 0, totalLen - TRAILER_SIZE);
        buf.putInt((int) crc.getValue());
        return out;
    }

    private static byte[] encodePayload(EdgeFrame frame) {
        return switch (frame) {
            case EdgeFrame.Subscribe f -> encodeSubscribe(f);
            case EdgeFrame.SubscribeOk f -> encodeSubscribeOk(f);
            case EdgeFrame.Notify f -> encodeNotify(f);
            case EdgeFrame.SnapshotBegin f -> encodeSnapshotBegin(f);
            case EdgeFrame.SnapshotChunk f -> encodeSnapshotChunk(f);
            case EdgeFrame.SnapshotEnd f -> encodeSnapshotEnd(f);
            case EdgeFrame.CursorAck f -> encodeCursorAck(f);
            case EdgeFrame.Heartbeat f -> encodeHeartbeat(f);
            case EdgeFrame.ErrorClose f -> encodeErrorClose(f);
        };
    }

    private static byte[] encodeSubscribe(EdgeFrame.Subscribe f) {
        List<byte[]> prefixBytes = new ArrayList<>(f.prefixes().size());
        int prefixTotal = 0;
        for (String p : f.prefixes()) {
            byte[] b = p.getBytes(StandardCharsets.UTF_8);
            prefixBytes.add(b);
            prefixTotal += 4 + b.length;
        }
        byte[] edgeId = f.edgeId().getBytes(StandardCharsets.UTF_8);
        // [1B fullStore][4B prefixCount][prefixes][8B resume][8B failoverResume][4B edgeIdLen][edgeId]
        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + prefixTotal + 8 + 8 + 4 + edgeId.length);
        buf.put((byte) (f.fullStore() ? 1 : 0));
        buf.putInt(prefixBytes.size());
        for (byte[] b : prefixBytes) {
            buf.putInt(b.length);
            buf.put(b);
        }
        buf.putLong(f.resumeCursor());
        buf.putLong(f.failoverResumeCursor());
        buf.putInt(edgeId.length);
        buf.put(edgeId);
        return buf.array();
    }

    private static byte[] encodeSubscribeOk(EdgeFrame.SubscribeOk f) {
        ByteBuffer buf = ByteBuffer.allocate(8 + 1);
        buf.putLong(f.latestSeq());
        buf.put((byte) f.mode().ordinal());
        return buf.array();
    }

    private static byte[] encodeNotify(EdgeFrame.Notify f) {
        List<CommitNotification> ns = f.notifications();
        if (ns.size() > MAX_NOTIFY_BATCH) {
            throw new CodecException(ErrorCode.FRAME_TOO_LARGE,
                    "NOTIFY batch " + ns.size() + " exceeds MAX_NOTIFY_BATCH=" + MAX_NOTIFY_BATCH);
        }
        // Encode each notification, accumulating into a growable buffer.
        List<byte[]> encoded = new ArrayList<>(ns.size());
        int total = 4; // count
        for (CommitNotification n : ns) {
            byte[] nb = encodeNotification(n);
            encoded.add(nb);
            total += nb.length;
        }
        if (total > MAX_NOTIFY_BATCH_BYTES) {
            throw new CodecException(ErrorCode.FRAME_TOO_LARGE,
                    "NOTIFY payload " + total + " bytes exceeds MAX_NOTIFY_BATCH_BYTES="
                            + MAX_NOTIFY_BATCH_BYTES);
        }
        ByteBuffer buf = ByteBuffer.allocate(total);
        buf.putInt(ns.size());
        for (byte[] nb : encoded) {
            buf.put(nb);
        }
        return buf.array();
    }

    private static byte[] encodeNotification(CommitNotification n) {
        ConfigDelta d = n.delta();
        // Mutations: re-encoded with the SAME CommandCodec.encodeBatch bytes that
        // ConfigDelta.signingPayload() hashes — so signingPayload round-trips byte-identical.
        // encodeBatch throws on empty; a mutating-apply delta always has >= 1 mutation.
        byte[] batch = CommandCodec.encodeBatch(d.mutations());
        byte[] sig = d.signature(); // defensive copy; null if unsigned
        byte[] nonce = d.nonce();   // never null (empty = legacy)

        int size = 8 + 8                // seq, commitTs
                + 8 + 8                 // fromVersion, toVersion
                + 4 + batch.length      // mutations blob
                + 4 + (sig == null ? 0 : sig.length) // signature (len -1 = null sentinel handled below)
                + 8                     // epoch
                + 4 + nonce.length;     // nonce
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putLong(n.seq());
        buf.putLong(n.commitTimestampMillis());
        buf.putLong(d.fromVersion());
        buf.putLong(d.toVersion());
        buf.putInt(batch.length);
        buf.put(batch);
        if (sig == null) {
            buf.putInt(-1); // explicit null sentinel (distinct from empty)
        } else {
            buf.putInt(sig.length);
            buf.put(sig);
        }
        buf.putLong(d.epoch());
        buf.putInt(nonce.length);
        buf.put(nonce);
        return buf.array();
    }

    private static byte[] encodeSnapshotBegin(EdgeFrame.SnapshotBegin f) {
        ByteBuffer buf = ByteBuffer.allocate(8 + 4 + 8);
        buf.putLong(f.snapshotSeq());
        buf.putInt(f.chunkCount());
        buf.putLong(f.totalBytes());
        return buf.array();
    }

    private static byte[] encodeSnapshotChunk(EdgeFrame.SnapshotChunk f) {
        byte[] bytes = f.bytesUnsafe();
        if (bytes.length > MAX_SNAPSHOT_CHUNK_BYTES) {
            throw new CodecException(ErrorCode.FRAME_TOO_LARGE,
                    "snapshot chunk " + bytes.length + " bytes exceeds MAX_SNAPSHOT_CHUNK_BYTES="
                            + MAX_SNAPSHOT_CHUNK_BYTES);
        }
        ByteBuffer buf = ByteBuffer.allocate(4 + bytes.length);
        buf.putInt(f.index());
        buf.put(bytes);
        return buf.array();
    }

    private static byte[] encodeSnapshotEnd(EdgeFrame.SnapshotEnd f) {
        return ByteBuffer.allocate(8).putLong(f.snapshotSeq()).array();
    }

    private static byte[] encodeCursorAck(EdgeFrame.CursorAck f) {
        return ByteBuffer.allocate(8).putLong(f.seq()).array();
    }

    private static byte[] encodeHeartbeat(EdgeFrame.Heartbeat f) {
        ByteBuffer buf = ByteBuffer.allocate(8 + 8);
        buf.putLong(f.latestSeq());
        buf.putLong(f.serverNowMillis());
        return buf.array();
    }

    private static byte[] encodeErrorClose(EdgeFrame.ErrorClose f) {
        byte[] msg = f.message().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + msg.length);
        buf.put((byte) f.code().code());
        buf.putInt(msg.length);
        buf.put(msg);
        return buf.array();
    }

    // -----------------------------------------------------------------------
    // Decode
    // -----------------------------------------------------------------------

    /**
     * Decodes a single complete frame. The array must contain exactly one frame.
     *
     * <p>Validation order (deliberate, mirroring {@code FrameCodec}): length bounds →
     * length==data.length → CRC32C → version → type → payload.
     *
     * @param data the wire bytes
     * @return the decoded frame
     * @throws CodecException with the mapped {@link ErrorCode} on any structural failure
     */
    public static EdgeFrame decode(byte[] data) {
        Objects.requireNonNull(data, "data must not be null");
        int minSize = HEADER_SIZE + TRAILER_SIZE;
        if (data.length < minSize) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT,
                    "frame too short: " + data.length + " bytes, minimum " + minSize);
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        int length = buf.getInt();
        if (length < minSize || length > MAX_EDGE_FRAME_SIZE) {
            throw new CodecException(
                    length > MAX_EDGE_FRAME_SIZE ? ErrorCode.FRAME_TOO_LARGE : ErrorCode.FRAME_CORRUPT,
                    "frame length out of bounds: " + length + " (min " + minSize
                            + ", max " + MAX_EDGE_FRAME_SIZE + ")");
        }
        if (length != data.length) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT,
                    "frame length mismatch: header says " + length + " but data is " + data.length);
        }

        // CRC BEFORE version/type — a flipped version/type byte reads as corruption.
        int crcOffset = length - TRAILER_SIZE;
        CRC32C crc = new CRC32C();
        crc.update(data, 0, crcOffset);
        int computed = (int) crc.getValue();
        int trailer = ByteBuffer.wrap(data, crcOffset, TRAILER_SIZE).getInt();
        if (computed != trailer) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT,
                    "CRC32C mismatch: computed=0x" + Integer.toHexString(computed)
                            + " trailer=0x" + Integer.toHexString(trailer));
        }

        byte version = buf.get();
        if (version != EDGE_WIRE_VERSION) {
            throw new CodecException(ErrorCode.BAD_WIRE_VERSION,
                    "unsupported edge wire version: 0x" + Integer.toHexString(version & 0xFF)
                            + " (expected 0x" + Integer.toHexString(EDGE_WIRE_VERSION & 0xFF) + ")");
        }
        int typeCode = buf.get() & 0xFF;
        FrameType type;
        try {
            type = FrameType.fromCode(typeCode);
        } catch (IllegalArgumentException e) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, e.getMessage());
        }

        // Payload window: [HEADER_SIZE, crcOffset).
        ByteBuffer payload = ByteBuffer.wrap(data, HEADER_SIZE, crcOffset - HEADER_SIZE);
        try {
            EdgeFrame frame = decodePayload(type, payload);
            if (payload.remaining() != 0) {
                throw new CodecException(ErrorCode.FRAME_CORRUPT,
                        "trailing bytes after " + type + " payload: " + payload.remaining());
            }
            return frame;
        } catch (CodecException e) {
            throw e;
        } catch (RuntimeException e) {
            // Any underflow / parse error from a structurally-valid-but-malformed payload.
            throw new CodecException(ErrorCode.FRAME_CORRUPT,
                    "malformed " + type + " payload: " + e.getMessage());
        }
    }

    private static EdgeFrame decodePayload(FrameType type, ByteBuffer p) {
        return switch (type) {
            case SUBSCRIBE -> decodeSubscribe(p);
            case SUBSCRIBE_OK -> decodeSubscribeOk(p);
            case NOTIFY -> decodeNotify(p);
            case SNAPSHOT_BEGIN -> decodeSnapshotBegin(p);
            case SNAPSHOT_CHUNK -> decodeSnapshotChunk(p);
            case SNAPSHOT_END -> new EdgeFrame.SnapshotEnd(p.getLong());
            case CURSOR_ACK -> new EdgeFrame.CursorAck(p.getLong());
            case HEARTBEAT -> new EdgeFrame.Heartbeat(p.getLong(), p.getLong());
            case ERROR_CLOSE -> decodeErrorClose(p);
        };
    }

    private static EdgeFrame decodeSubscribe(ByteBuffer p) {
        boolean fullStore = p.get() != 0;
        int prefixCount = p.getInt();
        if (prefixCount < 0 || prefixCount > p.remaining()) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "bad prefix count: " + prefixCount);
        }
        List<String> prefixes = new ArrayList<>(prefixCount);
        for (int i = 0; i < prefixCount; i++) {
            prefixes.add(readString(p, "prefix"));
        }
        long resume = p.getLong();
        long failover = p.getLong();
        String edgeId = readString(p, "edgeId");
        return new EdgeFrame.Subscribe(fullStore, prefixes, resume, failover, edgeId);
    }

    private static EdgeFrame decodeSubscribeOk(ByteBuffer p) {
        long latestSeq = p.getLong();
        int modeOrd = p.get() & 0xFF;
        EdgeFrame.Mode[] modes = EdgeFrame.Mode.values();
        if (modeOrd >= modes.length) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "bad subscribe mode ordinal: " + modeOrd);
        }
        return new EdgeFrame.SubscribeOk(latestSeq, modes[modeOrd]);
    }

    private static EdgeFrame decodeNotify(ByteBuffer p) {
        int count = p.getInt();
        if (count < 0 || count > MAX_NOTIFY_BATCH) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "bad NOTIFY count: " + count);
        }
        List<CommitNotification> ns = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ns.add(decodeNotification(p));
        }
        return new EdgeFrame.Notify(ns);
    }

    private static CommitNotification decodeNotification(ByteBuffer p) {
        long seq = p.getLong();
        long commitTs = p.getLong();
        long fromVersion = p.getLong();
        long toVersion = p.getLong();
        int batchLen = p.getInt();
        if (batchLen < 0 || batchLen > p.remaining()) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "bad mutations blob length: " + batchLen);
        }
        byte[] batch = new byte[batchLen];
        p.get(batch);
        List<ConfigMutation> mutations = decodeMutations(batch);

        int sigLen = p.getInt();
        byte[] sig;
        if (sigLen == -1) {
            sig = null; // null sentinel
        } else {
            if (sigLen < 0 || sigLen > p.remaining()) {
                throw new CodecException(ErrorCode.FRAME_CORRUPT, "bad signature length: " + sigLen);
            }
            sig = new byte[sigLen];
            p.get(sig);
        }
        long epoch = p.getLong();
        int nonceLen = p.getInt();
        if (nonceLen < 0 || nonceLen > p.remaining()) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "bad nonce length: " + nonceLen);
        }
        byte[] nonce = new byte[nonceLen];
        p.get(nonce);

        ConfigDelta delta = new ConfigDelta(fromVersion, toVersion, mutations, sig, epoch, nonce);
        return new CommitNotification(seq, commitTs, delta);
    }

    /** Decodes a {@code CommandCodec.encodeBatch} blob back to a mutation list. */
    private static List<ConfigMutation> decodeMutations(byte[] batch) {
        CommandCodec.DecodedCommand cmd = CommandCodec.decode(batch);
        return switch (cmd) {
            case CommandCodec.DecodedCommand.Batch b -> b.mutations();
            case CommandCodec.DecodedCommand.Put put ->
                    List.of(new ConfigMutation.Put(put.key(), put.value()));
            case CommandCodec.DecodedCommand.Delete del ->
                    List.of(new ConfigMutation.Delete(del.key()));
            case CommandCodec.DecodedCommand.Noop n ->
                    throw new CodecException(ErrorCode.FRAME_CORRUPT, "NOTIFY delta has no mutations");
        };
    }

    private static EdgeFrame decodeSnapshotBegin(ByteBuffer p) {
        long snapshotSeq = p.getLong();
        int chunkCount = p.getInt();
        long totalBytes = p.getLong();
        return new EdgeFrame.SnapshotBegin(snapshotSeq, chunkCount, totalBytes);
    }

    private static EdgeFrame decodeSnapshotChunk(ByteBuffer p) {
        int index = p.getInt();
        int len = p.remaining();
        if (len > MAX_SNAPSHOT_CHUNK_BYTES) {
            throw new CodecException(ErrorCode.FRAME_TOO_LARGE,
                    "snapshot chunk " + len + " bytes exceeds MAX_SNAPSHOT_CHUNK_BYTES="
                            + MAX_SNAPSHOT_CHUNK_BYTES);
        }
        byte[] bytes = new byte[len];
        p.get(bytes);
        return new EdgeFrame.SnapshotChunk(index, bytes);
    }

    private static EdgeFrame decodeErrorClose(ByteBuffer p) {
        int code = p.get() & 0xFF;
        ErrorCode ec;
        try {
            ec = ErrorCode.fromCode(code);
        } catch (IllegalArgumentException e) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "bad error code: " + code);
        }
        String msg = readString(p, "message");
        return new EdgeFrame.ErrorClose(ec, msg);
    }

    private static String readString(ByteBuffer p, String field) {
        if (p.remaining() < 4) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "truncated reading " + field + " length");
        }
        int len = p.getInt();
        if (len < 0 || len > p.remaining()) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT,
                    "bad " + field + " length: " + len + " (remaining " + p.remaining() + ")");
        }
        byte[] b = new byte[len];
        p.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // peekLength — bounds-check BEFORE allocation (ADR-0037)
    // -----------------------------------------------------------------------

    /**
     * Reads and bounds-checks the declared frame length from the first 4 bytes, so a
     * streaming reader can size its buffer without trusting an unvalidated length. The
     * returned value is in {@code [HEADER_SIZE + TRAILER_SIZE, MAX_EDGE_FRAME_SIZE]} or
     * the call throws — an adversary cannot induce a giant allocation by lying in the
     * length prefix.
     *
     * @param data a buffer with at least 4 bytes
     * @return the declared total frame length
     * @throws CodecException if the buffer is too short or the declared length is out of range
     */
    public static int peekLength(byte[] data) {
        Objects.requireNonNull(data, "data must not be null");
        if (data.length < 4) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT,
                    "need at least 4 bytes to read frame length, have " + data.length);
        }
        int length = ByteBuffer.wrap(data, 0, 4).getInt();
        int minSize = HEADER_SIZE + TRAILER_SIZE;
        if (length < minSize || length > MAX_EDGE_FRAME_SIZE) {
            throw new CodecException(
                    length > MAX_EDGE_FRAME_SIZE ? ErrorCode.FRAME_TOO_LARGE : ErrorCode.FRAME_CORRUPT,
                    "frame length out of bounds: " + length + " (min " + minSize
                            + ", max " + MAX_EDGE_FRAME_SIZE + ")");
        }
        return length;
    }
}

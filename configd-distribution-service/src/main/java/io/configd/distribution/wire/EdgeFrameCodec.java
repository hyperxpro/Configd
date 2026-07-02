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
 * Encoder/decoder for the edge streaming protocol wire format. A <b>separate</b> codec and
 * version byte from the Raft {@code FrameCodec} - the Raft wire and the edge wire evolve
 * on different cadences and must not share a fixture gate. Both follow the same structural
 * discipline:
 *
 * <pre>
 *   [Length: 4 bytes]      total frame size incl. length + trailer, big-endian
 *   [Version: 1 byte]      {@link #EDGE_WIRE_VERSION}
 *   [Type: 1 byte]         {@link FrameType} code
 *   [Payload: variable]    frame-type-specific
 *   [CRC32C: 4 bytes]      Castagnoli checksum over length..end-of-payload
 * </pre>
 *
 * <h2>Decoding discipline</h2>
 * <ul>
 *   <li><b>{@link #peekLength} bounds-checks the declared length BEFORE any
 *       allocation</b> - an adversary cannot induce a multi-MiB allocation by lying in
 *       the first 4 bytes.</li>
 *   <li><b>CRC32C is validated BEFORE the version/type bytes are interpreted</b> - a
 *       bit-flip in the version or type byte surfaces as {@link ErrorCode#FRAME_CORRUPT}
 *       (a {@link CodecException} of code {@code FRAME_CORRUPT}), never as a misleading
 *       "bad version" or "unknown type".</li>
 *   <li><b>Explicit frame cap {@link #MAX_EDGE_FRAME_SIZE}</b> (2 MiB - large enough for
 *       a 1 MiB snapshot chunk plus overhead), and a {@link #MAX_SNAPSHOT_CHUNK_BYTES}
 *       (1 MiB) cap on a chunk payload.</li>
 *   <li><b>NOTIFY batch caps enforced at encode</b> ({@link #MAX_NOTIFY_BATCH} = 64
 *       notifications / {@link #MAX_NOTIFY_BATCH_BYTES} = 256 KiB; frame-level batching).</li>
 * </ul>
 *
 * <h2>Delta byte fidelity</h2>
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
 * {@code java.net} / socket / TLS type - the only transport boundary is the session's
 * {@code TransportSink}.
 */
public final class EdgeFrameCodec {

    /**
     * The edge protocol wire version (separate from the Raft {@code FrameCodec.WIRE_VERSION}).
     * The decoder rejects any other value with {@link ErrorCode#BAD_WIRE_VERSION}.
     *
     * <p><b>Rebaseline rule (golden fixtures):</b> any change to the bytes any frame
     * encodes to - a new field, a reordered field, a changed type/error code - MUST bump
     * this constant and re-generate {@code EdgeFrameCodecGoldenFixtureTest}'s hex
     * constants. The golden test fails on any drift without a version bump, exactly as
     * the Raft {@code WireCompatGoldenBytesTest} guards the consensus wire.
     */
    public static final byte EDGE_WIRE_VERSION = (byte) 0x01;

    /**
     * The watch-capable edge wire version (W1-2). A {@code 0x02} connection stamps {@code 0x02}
     * on <b>every</b> frame it carries - including a reused {@link FrameType#NOTIFY} (the
     * {@code full_chain_verify} carrier, W5-2) - and is the <b>only</b> version under which the
     * {@code WATCH_*} frame types may be encoded or decoded (W5-11). The version byte is the
     * <b>sole</b> wire difference between a {@code 0x01} and a {@code 0x02} connection for a
     * shared frame (W1-3): the {@code 0x01} golden fixtures stay byte-identical, and the watch
     * protocol adds separate {@code 0x02} fixtures rather than rebaselining them. The decoder
     * accepts {@code 0x01} and {@code 0x02}; any other version is {@link ErrorCode#BAD_WIRE_VERSION}.
     */
    public static final byte EDGE_WIRE_VERSION_V2 = (byte) 0x02;

    /**
     * The filtered-fan-out edge wire version (ADR-0045). A {@code 0x03} connection carries the
     * server-side-filtered SUBSCRIBE stream: {@link EdgeFrame.Subscribe} gains an
     * {@code acceptsFiltered} opt-in byte and {@link EdgeFrame.SubscribeOk} gains a
     * {@code filtered} confirm byte, both appended <b>only</b> under {@code 0x03} (mirroring the
     * {@code 0x02} watch-only fields). Every other frame - NOTIFY, HEARTBEAT, SNAPSHOT_*,
     * CURSOR_ACK, ERROR_CLOSE - is byte-identical to its {@code 0x01} form save the version byte
     * (and the CRC over it). The {@code WATCH_*} frames are <b>not</b> legal under {@code 0x03}
     * (they remain {@code 0x02}-only). The decoder accepts {@code 0x01}, {@code 0x02}, and
     * {@code 0x03}; any other version is {@link ErrorCode#BAD_WIRE_VERSION}, so a {@code 0x03}
     * SUBSCRIBE to an old server fails LOUD rather than misparsing.
     */
    public static final byte EDGE_WIRE_VERSION_V3 = (byte) 0x03;

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

    /** Max notifications per NOTIFY frame ({@code batchMaxNotifications}). */
    public static final int MAX_NOTIFY_BATCH = 64;

    /** Max encoded NOTIFY payload bytes (256 KiB; {@code batchMaxBytes}). */
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
    // Encode (single pass into a FrameSink)
    // -----------------------------------------------------------------------

    /**
     * One reused {@link CRC32C} per thread for the trailer. A {@code new CRC32C()} per encode
     * is escape-analyzed to zero only when the buffer never escapes (the JDK reused-buffer
     * case); a pooled {@code ByteBuf} escapes into {@code release()}, so a thread-local
     * guarantees the trailer adds no per-frame allocation on either backend.
     */
    private static final ThreadLocal<CRC32C> TRAILER_CRC = ThreadLocal.withInitial(CRC32C::new);

    /** Default initial capacity for the convenience {@link #encode(EdgeFrame)} heap sink. */
    private static final int ENCODE_INITIAL_CAPACITY = 256;

    /**
     * Encodes a frame to a newly allocated byte array (convenience / cold path: golden and
     * property tests, the JDK edge client, teardown {@code ERROR_CLOSE}). Delegates to
     * {@link #encodeInto} so there is exactly ONE wire-format implementation - the golden
     * fixtures therefore guard the single-pass encoder for free. The hot fan-out path uses
     * {@link #encodeInto} with a reused/pooled {@link FrameSink}.
     *
     * @param frame the frame to encode
     * @return the wire bytes (length includes header + payload + CRC trailer)
     * @throws CodecException if the encoded frame would exceed {@link #MAX_EDGE_FRAME_SIZE}
     *                        or a NOTIFY batch exceeds its caps
     */
    public static byte[] encode(EdgeFrame frame) {
        return encode(frame, EDGE_WIRE_VERSION);
    }

    /**
     * Encodes a frame to a newly allocated byte array, stamping the given edge wire
     * {@code version} (W1-3). Use {@link #EDGE_WIRE_VERSION_V2} for a {@code 0x02}
     * connection; a {@code WATCH_*} frame may be encoded <b>only</b> under {@code 0x02}.
     *
     * @param frame   the frame to encode
     * @param version {@link #EDGE_WIRE_VERSION} or {@link #EDGE_WIRE_VERSION_V2}
     * @return the wire bytes
     * @throws IllegalArgumentException if {@code version} is not a supported edge wire
     *                                  version, or a {@code WATCH_*} frame is encoded under
     *                                  {@code 0x01} (W5-11)
     * @throws CodecException           if the encoded frame would exceed limits (see
     *                                  {@link #encodeInto(EdgeFrame, FrameSink, byte)})
     */
    public static byte[] encode(EdgeFrame frame, byte version) {
        Objects.requireNonNull(frame, "frame must not be null");
        HeapFrameSink sink = new HeapFrameSink(ENCODE_INITIAL_CAPACITY);
        encodeInto(frame, sink, version);
        return sink.toByteArray();
    }

    /**
     * Encodes one frame <b>in a single pass</b> directly into {@code sink}, with no intermediate
     * {@code List<byte[]>}, per-notification {@code ByteBuffer}, or payload-then-out double array.
     * The 4-byte length prefix is written as a placeholder and back-patched once the payload length
     * is known; the CRC32C trailer is computed over the written {@code [start, end-of-payload)}
     * region via {@link FrameSink#crc32cInto}. Byte-identical to the status-quo layout (proven by
     * {@code EdgeFrameCodecGoldenFixtureTest}, which exercises this path through {@link #encode}).
     *
     * <p>When {@code sink} is reused (the JDK writer's per-connection {@link HeapFrameSink}) or
     * pooled (the Netty in-pipeline {@code ByteBuf} sink), the only per-frame allocation that
     * remains is the codec-internal message-building floor ({@code CommandCodec.encodeBatch} +
     * signature/nonce clones).
     *
     * @param frame the frame to encode
     * @param sink  the destination (its current {@link FrameSink#writerIndex()} is the frame start)
     * @throws CodecException if the frame exceeds {@link #MAX_EDGE_FRAME_SIZE} or a NOTIFY batch
     *                        exceeds its caps (the sink may hold a partial frame; the caller
     *                        discards/releases it)
     */
    public static void encodeInto(EdgeFrame frame, FrameSink sink) {
        encodeInto(frame, sink, EDGE_WIRE_VERSION);
    }

    /**
     * Encodes one frame in a single pass into {@code sink}, stamping the given edge wire
     * {@code version} on the version byte (W1-3). Identical to
     * {@link #encodeInto(EdgeFrame, FrameSink)} except the version byte - a legacy frame is
     * byte-identical under either version save that one byte (and the CRC over it), which is
     * the design-A "only the version byte differs" property (W5-11). A {@code WATCH_*} frame
     * is encodable <b>only</b> under {@link #EDGE_WIRE_VERSION_V2}.
     *
     * @param frame   the frame to encode
     * @param sink    the destination (its current {@link FrameSink#writerIndex()} is the start)
     * @param version {@link #EDGE_WIRE_VERSION} or {@link #EDGE_WIRE_VERSION_V2}
     * @throws IllegalArgumentException if {@code version} is unsupported, or a {@code WATCH_*}
     *                                  frame is encoded under {@code 0x01} (W5-11) - a
     *                                  caller/programming error, kept distinct from the
     *                                  wire-decode {@link CodecException} taxonomy
     * @throws CodecException           if the frame exceeds {@link #MAX_EDGE_FRAME_SIZE} or a
     *                                  NOTIFY batch exceeds its caps
     */
    public static void encodeInto(EdgeFrame frame, FrameSink sink, byte version) {
        Objects.requireNonNull(frame, "frame must not be null");
        Objects.requireNonNull(sink, "sink must not be null");
        if (version != EDGE_WIRE_VERSION && version != EDGE_WIRE_VERSION_V2
                && version != EDGE_WIRE_VERSION_V3) {
            throw new IllegalArgumentException("unsupported edge wire version for encode: 0x"
                    + Integer.toHexString(version & 0xFF));
        }
        if (version != EDGE_WIRE_VERSION_V2 && isWatchType(frame.type())) {
            throw new IllegalArgumentException(frame.type()
                    + " is a 0x02 watch frame and cannot be encoded on a 0x"
                    + Integer.toHexString(version & 0xFF) + " connection (W5-11)");
        }
        final int start = sink.writerIndex();
        sink.writeInt(0); // total-length placeholder, back-patched below
        sink.writeByte(version);
        sink.writeByte((byte) frame.type().code());

        encodePayloadInto(frame, sink, version);

        final int payloadEnd = sink.writerIndex();
        long total = (long) (payloadEnd - start) + TRAILER_SIZE;
        if (total > MAX_EDGE_FRAME_SIZE) {
            throw new CodecException(ErrorCode.FRAME_TOO_LARGE,
                    "encoded frame " + total + " bytes exceeds MAX_EDGE_FRAME_SIZE="
                            + MAX_EDGE_FRAME_SIZE + " (type " + frame.type() + ")");
        }
        int totalLen = (int) total;
        sink.setInt(start, totalLen); // back-patch the length prefix
        CRC32C crc = TRAILER_CRC.get();
        crc.reset();
        sink.crc32cInto(crc, start, payloadEnd - start); // CRC over [length .. end-of-payload)
        sink.writeInt((int) crc.getValue());
    }

    private static void encodePayloadInto(EdgeFrame frame, FrameSink sink, byte version) {
        switch (frame) {
            case EdgeFrame.Subscribe f -> encodeSubscribeInto(f, sink, version);
            case EdgeFrame.SubscribeOk f -> encodeSubscribeOkInto(f, sink, version);
            case EdgeFrame.Notify f -> encodeNotifyInto(f, sink);
            case EdgeFrame.SnapshotBegin f -> encodeSnapshotBeginInto(f, sink);
            case EdgeFrame.SnapshotChunk f -> encodeSnapshotChunkInto(f, sink);
            case EdgeFrame.SnapshotEnd f -> sink.writeLong(f.snapshotSeq());
            case EdgeFrame.CursorAck f -> sink.writeLong(f.seq());
            case EdgeFrame.Heartbeat f -> encodeHeartbeatInto(f, sink);
            case EdgeFrame.ErrorClose f -> encodeErrorCloseInto(f, sink);
            case EdgeFrame.WatchCreate f -> encodeWatchCreateInto(f, sink);
            case EdgeFrame.WatchCancel f -> sink.writeLong(f.watchId());
            case EdgeFrame.WatchCreated f -> encodeWatchCreatedInto(f, sink);
            case EdgeFrame.WatchEvent f -> encodeWatchEventInto(f, sink);
            case EdgeFrame.WatchProgress f -> encodeWatchProgressInto(f, sink);
            case EdgeFrame.WatchCanceled f -> encodeWatchCanceledInto(f, sink);
            case EdgeFrame.WatchSnapshotBegin f -> encodeWatchSnapshotBeginInto(f, sink);
            case EdgeFrame.WatchSnapshotChunk f -> encodeWatchSnapshotChunkInto(f, sink);
            case EdgeFrame.WatchSnapshotEnd f -> encodeWatchSnapshotEndInto(f, sink);
        }
    }

    private static void encodeSubscribeInto(EdgeFrame.Subscribe f, FrameSink sink, byte version) {
        // [1B fullStore][4B prefixCount][prefixes][8B resume][8B failoverResume][4B edgeIdLen][edgeId]
        // and, ONLY under 0x03, a trailing [1B acceptsFiltered] (ADR-0045) - so a 0x01/0x02
        // SUBSCRIBE is byte-identical (the extra byte is an appended field, not a reshape).
        sink.writeByte(f.fullStore() ? 1 : 0);
        sink.writeInt(f.prefixes().size());
        for (String p : f.prefixes()) {
            byte[] b = p.getBytes(StandardCharsets.UTF_8);
            sink.writeInt(b.length);
            sink.writeBytes(b);
        }
        sink.writeLong(f.resumeCursor());
        sink.writeLong(f.failoverResumeCursor());
        byte[] edgeId = f.edgeId().getBytes(StandardCharsets.UTF_8);
        sink.writeInt(edgeId.length);
        sink.writeBytes(edgeId);
        if (version == EDGE_WIRE_VERSION_V3) {
            sink.writeByte(f.acceptsFiltered() ? 1 : 0);
        }
    }

    private static void encodeSubscribeOkInto(EdgeFrame.SubscribeOk f, FrameSink sink, byte version) {
        sink.writeLong(f.latestSeq());
        sink.writeByte(f.mode().ordinal());
        // ONLY under 0x03 a trailing [1B filtered] confirm byte (ADR-0045); 0x01/0x02 identical.
        if (version == EDGE_WIRE_VERSION_V3) {
            sink.writeByte(f.filtered() ? 1 : 0);
        }
    }

    private static void encodeNotifyInto(EdgeFrame.Notify f, FrameSink sink) {
        List<CommitNotification> ns = f.notifications();
        if (ns.size() > MAX_NOTIFY_BATCH) {
            throw new CodecException(ErrorCode.FRAME_TOO_LARGE,
                    "NOTIFY batch " + ns.size() + " exceeds MAX_NOTIFY_BATCH=" + MAX_NOTIFY_BATCH);
        }
        final int payloadStart = sink.writerIndex();
        sink.writeInt(ns.size());
        for (CommitNotification n : ns) {
            encodeNotificationInto(n, sink);
        }
        int payloadBytes = sink.writerIndex() - payloadStart;
        if (payloadBytes > MAX_NOTIFY_BATCH_BYTES) {
            throw new CodecException(ErrorCode.FRAME_TOO_LARGE,
                    "NOTIFY payload " + payloadBytes + " bytes exceeds MAX_NOTIFY_BATCH_BYTES="
                            + MAX_NOTIFY_BATCH_BYTES);
        }
    }

    private static void encodeNotificationInto(CommitNotification n, FrameSink sink) {
        ConfigDelta d = n.delta();
        // Re-encode the mutation blob with the SAME CommandCodec.encodeBatch bytes that
        // ConfigDelta.signingPayload() hashes, so signingPayload round-trips byte-identical.
        // encodeBatch throws on empty; a mutating-apply delta always has >= 1 mutation.
        byte[] batch = CommandCodec.encodeBatch(d.mutations());
        byte[] sig = d.signature(); // defensive copy; null if unsigned
        byte[] nonce = d.nonce();   // never null (empty = legacy)
        sink.writeLong(n.seq());
        sink.writeLong(n.commitTimestampMillis());
        sink.writeLong(d.fromVersion());
        sink.writeLong(d.toVersion());
        sink.writeInt(batch.length);
        sink.writeBytes(batch);
        if (sig == null) {
            sink.writeInt(-1); // explicit null sentinel (distinct from empty)
        } else {
            sink.writeInt(sig.length);
            sink.writeBytes(sig);
        }
        sink.writeLong(d.epoch());
        sink.writeInt(nonce.length);
        sink.writeBytes(nonce);
    }

    private static void encodeSnapshotBeginInto(EdgeFrame.SnapshotBegin f, FrameSink sink) {
        sink.writeLong(f.snapshotSeq());
        sink.writeInt(f.chunkCount());
        sink.writeLong(f.totalBytes());
    }

    private static void encodeSnapshotChunkInto(EdgeFrame.SnapshotChunk f, FrameSink sink) {
        byte[] bytes = f.bytesUnsafe();
        if (bytes.length > MAX_SNAPSHOT_CHUNK_BYTES) {
            throw new CodecException(ErrorCode.FRAME_TOO_LARGE,
                    "snapshot chunk " + bytes.length + " bytes exceeds MAX_SNAPSHOT_CHUNK_BYTES="
                            + MAX_SNAPSHOT_CHUNK_BYTES);
        }
        sink.writeInt(f.index());
        sink.writeBytes(bytes);
    }

    private static void encodeHeartbeatInto(EdgeFrame.Heartbeat f, FrameSink sink) {
        sink.writeLong(f.latestSeq());
        sink.writeLong(f.serverNowMillis());
    }

    private static void encodeErrorCloseInto(EdgeFrame.ErrorClose f, FrameSink sink) {
        byte[] msg = f.message().getBytes(StandardCharsets.UTF_8);
        sink.writeByte(f.code().code());
        sink.writeInt(msg.length);
        sink.writeBytes(msg);
    }

    // -----------------------------------------------------------------------
    // Encode - watch frames (0x02 only). Layouts per sections 5.2-5.8 of the RFC.
    // -----------------------------------------------------------------------

    /** Bytes per encoded cursor component on the wire: gid(u32) + S(u64). */
    private static final int CURSOR_COMPONENT_BYTES = 12;

    /** Bytes per encoded {@link EdgeFrame.ShardMode}: gid(u32) + latestSeq(u64) + mode(u8). */
    private static final int SHARD_MODE_BYTES = 13;

    /** Minimum bytes of one encoded {@link EdgeFrame.WatchChange}: keyLen(u32) + kind(u8) + valLen(i32). */
    private static final int MIN_CHANGE_BYTES = 9;

    /** True iff {@code t} is a {@code 0x0A..0x12} watch frame (legal only under 0x02; W5-11). */
    private static boolean isWatchType(FrameType t) {
        return switch (t) {
            case WATCH_CREATE, WATCH_CANCEL, WATCH_CREATED, WATCH_EVENT, WATCH_PROGRESS,
                 WATCH_CANCELED, WATCH_SNAPSHOT_BEGIN, WATCH_SNAPSHOT_CHUNK, WATCH_SNAPSHOT_END -> true;
            default -> false;
        };
    }

    /**
     * Encodes a {@link WatchCursor} as {@code [count u32]( gid u32  S u64 )*count} (W3-5).
     * The cursor's construction-time invariant guarantees the components are already strictly
     * ascending by unsigned {@code gid}, so they are written in list order with no re-sort.
     */
    private static void encodeCursorInto(WatchCursor cursor, FrameSink sink) {
        List<WatchCursor.Component> cs = cursor.components();
        sink.writeInt(cs.size());
        for (WatchCursor.Component c : cs) {
            sink.writeInt(c.gid());
            sink.writeLong(c.s());
        }
    }

    private static void encodeWatchCreateInto(EdgeFrame.WatchCreate f, FrameSink sink) {
        sink.writeLong(f.watchId());
        sink.writeByte(f.scope());
        sink.writeByte(f.targetKind());
        byte[] path = f.pathUnsafe();
        sink.writeInt(path.length);
        sink.writeBytes(path);
        encodeCursorInto(f.cursor(), sink);
        sink.writeByte(f.flags());
    }

    private static void encodeWatchCreatedInto(EdgeFrame.WatchCreated f, FrameSink sink) {
        sink.writeLong(f.watchId());
        List<EdgeFrame.ShardMode> shards = f.shards();
        sink.writeInt(shards.size());
        for (EdgeFrame.ShardMode sm : shards) {
            sink.writeInt(sm.gid());
            sink.writeLong(sm.latestSeq());
            sink.writeByte(sm.mode().ordinal());
        }
    }

    private static void encodeWatchEventInto(EdgeFrame.WatchEvent f, FrameSink sink) {
        sink.writeLong(f.watchId());
        sink.writeInt(f.gid());
        sink.writeLong(f.s());
        sink.writeLong(f.commitTs());
        List<EdgeFrame.WatchChange> changes = f.changes();
        sink.writeInt(changes.size());
        for (EdgeFrame.WatchChange c : changes) {
            byte[] key = c.key().getBytes(StandardCharsets.UTF_8);
            sink.writeInt(key.length);
            sink.writeBytes(key);
            sink.writeByte(c.kind());
            byte[] val = c.valueUnsafe();
            if (val == null) {
                sink.writeInt(-1); // DELETE: the sole SIGNED i32 length sentinel (W5-6)
            } else {
                sink.writeInt(val.length); // >= 0; 0 = empty value present
                sink.writeBytes(val);
            }
        }
    }

    private static void encodeWatchProgressInto(EdgeFrame.WatchProgress f, FrameSink sink) {
        sink.writeLong(f.watchId());
        encodeCursorInto(f.cursor(), sink);
        sink.writeLong(f.serverNowMillis());
    }

    private static void encodeWatchCanceledInto(EdgeFrame.WatchCanceled f, FrameSink sink) {
        sink.writeLong(f.watchId());
        sink.writeByte(f.code().code());
        if (f.oldest() != null) {
            sink.writeByte(1);
            encodeCursorInto(f.oldest(), sink);
        } else {
            sink.writeByte(0);
        }
        byte[] msg = f.message().getBytes(StandardCharsets.UTF_8);
        sink.writeInt(msg.length);
        sink.writeBytes(msg);
    }

    private static void encodeWatchSnapshotBeginInto(EdgeFrame.WatchSnapshotBegin f, FrameSink sink) {
        sink.writeLong(f.watchId());
        sink.writeInt(f.gid());
        sink.writeLong(f.snapshotSeq());
        sink.writeInt(f.chunkCount());
        sink.writeLong(f.totalBytes());
    }

    private static void encodeWatchSnapshotChunkInto(EdgeFrame.WatchSnapshotChunk f, FrameSink sink) {
        byte[] bytes = f.bytesUnsafe();
        if (bytes.length > MAX_SNAPSHOT_CHUNK_BYTES) {
            throw new CodecException(ErrorCode.FRAME_TOO_LARGE,
                    "watch snapshot chunk " + bytes.length + " bytes exceeds MAX_SNAPSHOT_CHUNK_BYTES="
                            + MAX_SNAPSHOT_CHUNK_BYTES);
        }
        sink.writeLong(f.watchId());
        sink.writeInt(f.gid());
        sink.writeInt(f.index());
        sink.writeBytes(bytes);
    }

    private static void encodeWatchSnapshotEndInto(EdgeFrame.WatchSnapshotEnd f, FrameSink sink) {
        sink.writeLong(f.watchId());
        sink.writeInt(f.gid());
        sink.writeLong(f.snapshotSeq());
    }

    // -----------------------------------------------------------------------
    // Decode
    // -----------------------------------------------------------------------

    /**
     * Decodes a single complete frame, accepting either negotiated version. The array must
     * contain exactly one frame.
     *
     * <p>Validation order (deliberate, mirroring {@code FrameCodec}): length bounds ->
     * length==data.length -> CRC32C -> version -> type -> payload.
     *
     * @param data the wire bytes
     * @return the decoded frame
     * @throws CodecException with the mapped {@link ErrorCode} on any structural failure
     */
    public static EdgeFrame decode(byte[] data) {
        return decode(data, null);
    }

    /**
     * Decodes a single complete frame on a connection that negotiated exactly
     * {@code negotiatedVersion} (W1-3 / W5-11). Identical to {@link #decode(byte[])} except
     * that, after the CRC and the {@code {0x01, 0x02}} acceptance check, a frame whose stamped
     * version differs from {@code negotiatedVersion} is rejected with
     * {@link ErrorCode#BAD_WIRE_VERSION} - this is how a per-connection reader enforces "a
     * {@code 0x01} connection MUST fail closed on a {@code 0x02} frame" (and vice versa). Use
     * {@link #peekVersion(byte[])} to establish the negotiated version on the connection's
     * first frame.
     *
     * @param data              the wire bytes
     * @param negotiatedVersion the connection's agreed version ({@link #EDGE_WIRE_VERSION} or
     *                          {@link #EDGE_WIRE_VERSION_V2})
     * @return the decoded frame
     * @throws IllegalArgumentException if {@code negotiatedVersion} is not a supported version
     * @throws CodecException           on any structural failure, or if the frame's stamped
     *                                  version != {@code negotiatedVersion}
     */
    public static EdgeFrame decode(byte[] data, byte negotiatedVersion) {
        if (negotiatedVersion != EDGE_WIRE_VERSION && negotiatedVersion != EDGE_WIRE_VERSION_V2
                && negotiatedVersion != EDGE_WIRE_VERSION_V3) {
            throw new IllegalArgumentException("unsupported negotiated edge wire version: 0x"
                    + Integer.toHexString(negotiatedVersion & 0xFF));
        }
        return decode(data, Byte.valueOf(negotiatedVersion));
    }

    /**
     * The single decode implementation. {@code expectedVersion} is the per-connection pin
     * (W5-11): {@code null} accepts either {@code 0x01}/{@code 0x02}; a non-null value also
     * rejects a frame stamped with the other accepted version as {@link ErrorCode#BAD_WIRE_VERSION}.
     */
    private static EdgeFrame decode(byte[] data, Byte expectedVersion) {
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

        // CRC BEFORE version/type - a flipped version/type byte reads as corruption.
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

        // Accept the built 0x01, the watch-capable 0x02, and the filtered-fan-out 0x03
        // (W1-3 / W5-11 / ADR-0045); any other version is BAD_WIRE_VERSION. The negotiated
        // version is a per-connection property the transport tracks; the codec accepts all
        // three and the FrameType gates which payloads are legal under which version (below).
        byte version = buf.get();
        if (version != EDGE_WIRE_VERSION && version != EDGE_WIRE_VERSION_V2
                && version != EDGE_WIRE_VERSION_V3) {
            throw new CodecException(ErrorCode.BAD_WIRE_VERSION,
                    "unsupported edge wire version: 0x" + Integer.toHexString(version & 0xFF)
                            + " (expected 0x" + Integer.toHexString(EDGE_WIRE_VERSION & 0xFF)
                            + ", 0x" + Integer.toHexString(EDGE_WIRE_VERSION_V2 & 0xFF)
                            + ", or 0x" + Integer.toHexString(EDGE_WIRE_VERSION_V3 & 0xFF) + ")");
        }
        // Per-connection version pin (W5-11): on a connection that negotiated one version, a
        // frame stamped with the OTHER accepted version fails closed as BAD_WIRE_VERSION - so a
        // 0x01 peer cannot be fed a 0x02 watch frame (and vice versa). A null pin accepts either.
        if (expectedVersion != null && version != expectedVersion.byteValue()) {
            throw new CodecException(ErrorCode.BAD_WIRE_VERSION,
                    "frame stamped 0x" + Integer.toHexString(version & 0xFF) + " on a 0x"
                            + Integer.toHexString(expectedVersion & 0xFF) + "-negotiated connection");
        }
        int typeCode = buf.get() & 0xFF;
        FrameType type;
        try {
            type = FrameType.fromCode(typeCode);
        } catch (IllegalArgumentException e) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, e.getMessage());
        }
        // A WATCH_* type is legal only on a 0x02-stamped frame (W5-11); on a 0x01 or 0x03 frame
        // it is a protocol violation surfaced as FRAME_CORRUPT (consistent with the codec's
        // structural-error taxonomy; the CRC has already been verified, so this is a
        // deliberately-constructed frame, not a bit-flip).
        if (version != EDGE_WIRE_VERSION_V2 && isWatchType(type)) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT,
                    type + " is a 0x02 watch frame and is not legal on a 0x"
                            + Integer.toHexString(version & 0xFF) + "-stamped frame");
        }

        // Payload window: [HEADER_SIZE, crcOffset).
        ByteBuffer payload = ByteBuffer.wrap(data, HEADER_SIZE, crcOffset - HEADER_SIZE);
        try {
            EdgeFrame frame = decodePayload(type, payload, version);
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

    private static EdgeFrame decodePayload(FrameType type, ByteBuffer p, byte version) {
        return switch (type) {
            case SUBSCRIBE -> decodeSubscribe(p, version);
            case SUBSCRIBE_OK -> decodeSubscribeOk(p, version);
            case NOTIFY -> decodeNotify(p);
            case SNAPSHOT_BEGIN -> decodeSnapshotBegin(p);
            case SNAPSHOT_CHUNK -> decodeSnapshotChunk(p);
            case SNAPSHOT_END -> new EdgeFrame.SnapshotEnd(p.getLong());
            case CURSOR_ACK -> new EdgeFrame.CursorAck(p.getLong());
            case HEARTBEAT -> new EdgeFrame.Heartbeat(p.getLong(), p.getLong());
            case ERROR_CLOSE -> decodeErrorClose(p);
            case WATCH_CREATE -> decodeWatchCreate(p);
            case WATCH_CANCEL -> new EdgeFrame.WatchCancel(p.getLong());
            case WATCH_CREATED -> decodeWatchCreated(p);
            case WATCH_EVENT -> decodeWatchEvent(p);
            case WATCH_PROGRESS -> decodeWatchProgress(p);
            case WATCH_CANCELED -> decodeWatchCanceled(p);
            case WATCH_SNAPSHOT_BEGIN -> decodeWatchSnapshotBegin(p);
            case WATCH_SNAPSHOT_CHUNK -> decodeWatchSnapshotChunk(p);
            case WATCH_SNAPSHOT_END -> decodeWatchSnapshotEnd(p);
        };
    }

    private static EdgeFrame decodeSubscribe(ByteBuffer p, byte version) {
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
        // The acceptsFiltered opt-in byte is present only under 0x03 (ADR-0045); a 0x01/0x02
        // SUBSCRIBE decodes to false via the back-compat constructor.
        boolean acceptsFiltered = false;
        if (version == EDGE_WIRE_VERSION_V3) {
            if (p.remaining() < 1) {
                throw new CodecException(ErrorCode.FRAME_CORRUPT,
                        "truncated reading SUBSCRIBE acceptsFiltered byte");
            }
            acceptsFiltered = p.get() != 0;
        }
        try {
            return new EdgeFrame.Subscribe(fullStore, prefixes, resume, failover, edgeId, acceptsFiltered);
        } catch (IllegalArgumentException e) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "invalid SUBSCRIBE: " + e.getMessage());
        }
    }

    private static EdgeFrame decodeSubscribeOk(ByteBuffer p, byte version) {
        long latestSeq = p.getLong();
        int modeOrd = p.get() & 0xFF;
        EdgeFrame.Mode[] modes = EdgeFrame.Mode.values();
        if (modeOrd >= modes.length) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "bad subscribe mode ordinal: " + modeOrd);
        }
        // The filtered confirm byte is present only under 0x03 (ADR-0045); a 0x01/0x02
        // SUBSCRIBE_OK decodes to false via the back-compat constructor.
        boolean filtered = false;
        if (version == EDGE_WIRE_VERSION_V3) {
            if (p.remaining() < 1) {
                throw new CodecException(ErrorCode.FRAME_CORRUPT,
                        "truncated reading SUBSCRIBE_OK filtered byte");
            }
            filtered = p.get() != 0;
        }
        return new EdgeFrame.SubscribeOk(latestSeq, modes[modeOrd], filtered);
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

    // -----------------------------------------------------------------------
    // Decode - watch frames. Bounds-before-allocation discipline as above; the
    // WatchCursor / WatchChange / ShardMode constructors enforce value invariants,
    // and their IllegalArgumentException is mapped to FRAME_CORRUPT.
    //
    // Cross-language contract: the sequence/timestamp u64 fields - cursor S,
    // WATCH_EVENT S/commitTs, WATCH_CREATED latestSeq, WATCH_SNAPSHOT_* snapshotSeq /
    // totalBytes, and the WATCH_CANCELED oldest-vector S - are validated >= 0 (their
    // compact ctors reject a negative), so their effective range is [0, 2^63): a
    // high-bit-set u64 decodes as FRAME_CORRUPT. A Rust/Go driver using a true u64
    // MUST keep these fields in [0, 2^63). watch_id and gid stay opaque full-range
    // u64/u32 (no such constraint).
    // -----------------------------------------------------------------------

    /**
     * Decodes a {@link WatchCursor} from {@code [count u32]( gid u32  S u64 )*count} (W3-5).
     * Bounds {@code count} against the remaining bytes BEFORE allocating, and maps an
     * unsorted/duplicate {@code gid} (or a negative {@code S}) to {@link ErrorCode#FRAME_CORRUPT}
     * via the {@link WatchCursor} constructor's invariant.
     */
    private static WatchCursor decodeCursor(ByteBuffer p) {
        if (p.remaining() < 4) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "truncated reading cursor count");
        }
        int count = p.getInt();
        if (count < 0 || (long) count * CURSOR_COMPONENT_BYTES > p.remaining()) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "bad cursor component count: " + count);
        }
        List<WatchCursor.Component> cs = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) {
                cs.add(new WatchCursor.Component(p.getInt(), p.getLong()));
            }
            return new WatchCursor(cs);
        } catch (IllegalArgumentException e) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "invalid cursor: " + e.getMessage());
        }
    }

    private static EdgeFrame decodeWatchCreate(ByteBuffer p) {
        long watchId = p.getLong();
        int scope = p.get() & 0xFF;
        int targetKind = p.get() & 0xFF;
        byte[] path = readBytes(p, "path");
        WatchCursor cursor = decodeCursor(p);
        if (p.remaining() < 1) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "truncated reading WATCH_CREATE flags");
        }
        int flags = p.get() & 0xFF;
        try {
            return new EdgeFrame.WatchCreate(watchId, scope, targetKind, path, cursor, flags);
        } catch (IllegalArgumentException e) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "invalid WATCH_CREATE: " + e.getMessage());
        }
    }

    private static EdgeFrame decodeWatchCreated(ByteBuffer p) {
        long watchId = p.getLong();
        int count = p.getInt();
        if (count < 0 || (long) count * SHARD_MODE_BYTES > p.remaining()) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "bad shard count: " + count);
        }
        EdgeFrame.Mode[] modes = EdgeFrame.Mode.values();
        List<EdgeFrame.ShardMode> shards = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int gid = p.getInt();
            long latestSeq = p.getLong();
            int modeOrd = p.get() & 0xFF;
            if (modeOrd >= modes.length) {
                throw new CodecException(ErrorCode.FRAME_CORRUPT, "bad shard mode ordinal: " + modeOrd);
            }
            shards.add(new EdgeFrame.ShardMode(gid, latestSeq, modes[modeOrd]));
        }
        return new EdgeFrame.WatchCreated(watchId, shards);
    }

    private static EdgeFrame decodeWatchEvent(ByteBuffer p) {
        long watchId = p.getLong();
        int gid = p.getInt();
        long s = p.getLong();
        long commitTs = p.getLong();
        int count = p.getInt();
        // Each change is >= 9 bytes (keyLen 4 + kind 1 + valLen 4), so the minimum encoded size of
        // `count` changes is 9*count - a tight pre-allocation bound (the (long) cast binds before the
        // multiply, so no overflow). Tighter than the looser `count > remaining` legacy pattern.
        if (count < 0 || (long) count * MIN_CHANGE_BYTES > p.remaining()) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "bad change count: " + count);
        }
        List<EdgeFrame.WatchChange> changes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] key = readBytes(p, "change key");
            int kind = p.get() & 0xFF;
            int valLen = p.getInt(); // SIGNED i32: -1 = no value (DELETE); >= 0 = value present
            byte[] val;
            if (valLen == -1) {
                val = null;
            } else if (valLen < 0) {
                throw new CodecException(ErrorCode.FRAME_CORRUPT, "bad change val length: " + valLen);
            } else {
                if (valLen > p.remaining()) {
                    throw new CodecException(ErrorCode.FRAME_CORRUPT,
                            "change val length " + valLen + " exceeds remaining " + p.remaining());
                }
                val = new byte[valLen];
                p.get(val);
            }
            try {
                changes.add(new EdgeFrame.WatchChange(new String(key, StandardCharsets.UTF_8), kind, val));
            } catch (IllegalArgumentException e) {
                // e.g. kind/value mismatch (a DELETE carrying a value, or a PUT with val_len -1).
                throw new CodecException(ErrorCode.FRAME_CORRUPT, "invalid change: " + e.getMessage());
            }
        }
        try {
            return new EdgeFrame.WatchEvent(watchId, gid, s, commitTs, changes);
        } catch (IllegalArgumentException e) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "invalid WATCH_EVENT: " + e.getMessage());
        }
    }

    private static EdgeFrame decodeWatchProgress(ByteBuffer p) {
        long watchId = p.getLong();
        WatchCursor cursor = decodeCursor(p);
        long serverNow = p.getLong();
        try {
            return new EdgeFrame.WatchProgress(watchId, cursor, serverNow);
        } catch (IllegalArgumentException e) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "invalid WATCH_PROGRESS: " + e.getMessage());
        }
    }

    private static EdgeFrame decodeWatchCanceled(ByteBuffer p) {
        long watchId = p.getLong();
        int code = p.get() & 0xFF;
        ErrorCode ec;
        try {
            ec = ErrorCode.fromCode(code);
        } catch (IllegalArgumentException e) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "bad error code: " + code);
        }
        int hasOldest = p.get() & 0xFF;
        WatchCursor oldest;
        if (hasOldest == 1) {
            oldest = decodeCursor(p);
        } else if (hasOldest == 0) {
            oldest = null;
        } else {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "bad has_oldest flag: " + hasOldest);
        }
        String msg = readString(p, "message");
        return new EdgeFrame.WatchCanceled(watchId, ec, oldest, msg);
    }

    private static EdgeFrame decodeWatchSnapshotBegin(ByteBuffer p) {
        long watchId = p.getLong();
        int gid = p.getInt();
        long snapshotSeq = p.getLong();
        int chunkCount = p.getInt();
        long totalBytes = p.getLong();
        try {
            return new EdgeFrame.WatchSnapshotBegin(watchId, gid, snapshotSeq, chunkCount, totalBytes);
        } catch (IllegalArgumentException e) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "invalid WATCH_SNAPSHOT_BEGIN: " + e.getMessage());
        }
    }

    private static EdgeFrame decodeWatchSnapshotChunk(ByteBuffer p) {
        long watchId = p.getLong();
        int gid = p.getInt();
        int index = p.getInt();
        int len = p.remaining();
        if (len > MAX_SNAPSHOT_CHUNK_BYTES) {
            throw new CodecException(ErrorCode.FRAME_TOO_LARGE,
                    "watch snapshot chunk " + len + " bytes exceeds MAX_SNAPSHOT_CHUNK_BYTES="
                            + MAX_SNAPSHOT_CHUNK_BYTES);
        }
        byte[] bytes = new byte[len];
        p.get(bytes);
        try {
            return new EdgeFrame.WatchSnapshotChunk(watchId, gid, index, bytes);
        } catch (IllegalArgumentException e) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "invalid WATCH_SNAPSHOT_CHUNK: " + e.getMessage());
        }
    }

    private static EdgeFrame decodeWatchSnapshotEnd(ByteBuffer p) {
        long watchId = p.getLong();
        int gid = p.getInt();
        long snapshotSeq = p.getLong();
        try {
            return new EdgeFrame.WatchSnapshotEnd(watchId, gid, snapshotSeq);
        } catch (IllegalArgumentException e) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT, "invalid WATCH_SNAPSHOT_END: " + e.getMessage());
        }
    }

    /** Reads a {@code [len u32][bytes]} blob, bounds-checking {@code len} before allocation. */
    private static byte[] readBytes(ByteBuffer p, String field) {
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
        return b;
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
    // peekLength / peekVersion - cheap pre-decode header reads
    // -----------------------------------------------------------------------

    /**
     * Reads and bounds-checks the declared frame length from the first 4 bytes, so a
     * streaming reader can size its buffer without trusting an unvalidated length. The
     * returned value is in {@code [HEADER_SIZE + TRAILER_SIZE, MAX_EDGE_FRAME_SIZE]} or
     * the call throws - an adversary cannot induce a giant allocation by lying in the
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

    /**
     * Reads the stamped edge wire version byte (offset 4, after the 4-byte length prefix) so a
     * per-connection reader can establish or pin the negotiated version on the connection's
     * first frame (W1-3 / W5-11) before committing to {@link #decode(byte[], byte)}.
     *
     * <p><b>This does NOT validate the CRC</b> - it is a cheap pre-decode peek. The returned
     * byte is the raw stamped version (it is NOT range-checked here either; {@link #decode}
     * still performs the full CRC-before-interpret validation and rejects an unsupported
     * version with {@link ErrorCode#BAD_WIRE_VERSION}).
     *
     * @param data a buffer with at least {@link #HEADER_SIZE} bytes
     * @return the stamped version byte (expected {@link #EDGE_WIRE_VERSION} or
     *         {@link #EDGE_WIRE_VERSION_V2})
     * @throws CodecException if the buffer is shorter than {@link #HEADER_SIZE}
     */
    public static byte peekVersion(byte[] data) {
        Objects.requireNonNull(data, "data must not be null");
        if (data.length < HEADER_SIZE) {
            throw new CodecException(ErrorCode.FRAME_CORRUPT,
                    "need at least " + HEADER_SIZE + " bytes to read frame version, have " + data.length);
        }
        return data[4]; // version byte: [length u32][version u8][type u8]
    }
}

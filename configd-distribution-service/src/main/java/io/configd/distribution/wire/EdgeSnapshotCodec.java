package io.configd.distribution.wire;

import io.configd.store.ConfigSnapshot;
import io.configd.store.VersionedValue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializes a {@link ConfigSnapshot} to the <b>ADR-0028 snapshot byte format</b> and
 * splits it into wire chunks for the {@code SNAPSHOT_BEGIN / SNAPSHOT_CHUNK* /
 * SNAPSHOT_END} flow (C1 design §3; ADR-0028 reuse — open decision 3, draft-confirmed:
 * reuse the existing serialization, do not invent a second snapshot format).
 *
 * <h2>Why this and not {@code ConfigStateMachine.snapshot()}</h2>
 * The ADR-0028 producer is {@code ConfigStateMachine.snapshot()}, which reads from a
 * live state machine's store and appends a TLV trailer carrying {@code signingEpoch}.
 * C1's {@code ReplaySource} hands the session a plain {@link ConfigSnapshot} (the HAMT +
 * version), not a state machine, and the edge has no use for the leader's signing epoch
 * (it verifies per-delta signatures, not the snapshot). So we emit exactly the ADR-0028
 * <b>body</b> — {@code [8B seq][4B entryCount][ (4B keyLen, key, 4B valLen, val)* ]} in
 * {@code HamtMap.forEach} order — which is a valid ADR-0028 snapshot with no trailer
 * (the "legacy / no-trailer" form {@code ConfigStateMachine.restoreSnapshot} explicitly
 * accepts). This is byte-format reuse, not a new format: the same layout the durable
 * snapshot store and the InstallSnapshot RPC already use, round-trip-verified against
 * {@code ConfigStateMachine.restoreSnapshot} in {@code EdgeSnapshotCodecTest}.
 *
 * <h2>Bounds</h2>
 * Serialization mirrors the ADR-0028 envelope bounds: per-entry key and value are each
 * capped at {@link #MAX_ENTRY_FIELD_BYTES} (1 MiB, matching
 * {@code CommandCodec.MAX_VALUE_SIZE} and the state machine's snapshot caps), so a
 * pathological snapshot cannot produce an entry the receiver would reject.
 */
public final class EdgeSnapshotCodec {

    /** Per-entry key/value byte cap (ADR-0028 §Bounds: 1 MiB). */
    public static final int MAX_ENTRY_FIELD_BYTES = 1_048_576;

    private EdgeSnapshotCodec() {
        // utility class
    }

    /**
     * Serializes a snapshot to ADR-0028 body bytes (no trailer). Entries are emitted in
     * {@code HamtMap.forEach} order — the same order {@code ConfigStateMachine.snapshot()}
     * uses, so the bytes are interchangeable with the durable form for a trailer-less
     * snapshot.
     *
     * @param snapshot the snapshot to serialize
     * @return the ADR-0028 body bytes
     * @throws IllegalArgumentException if any key/value exceeds {@link #MAX_ENTRY_FIELD_BYTES}
     */
    public static byte[] serialize(ConfigSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        List<byte[]> keys = new ArrayList<>();
        List<byte[]> values = new ArrayList<>();
        snapshot.data().forEach((key, vv) -> {
            keys.add(key.getBytes(StandardCharsets.UTF_8));
            values.add(vv.valueUnsafe());
        });

        long size = 8L + 4L; // seq + entryCount
        for (int i = 0; i < keys.size(); i++) {
            byte[] k = keys.get(i);
            byte[] v = values.get(i);
            if (k.length > MAX_ENTRY_FIELD_BYTES) {
                throw new IllegalArgumentException(
                        "snapshot key exceeds " + MAX_ENTRY_FIELD_BYTES + " bytes: " + k.length);
            }
            if (v.length > MAX_ENTRY_FIELD_BYTES) {
                throw new IllegalArgumentException(
                        "snapshot value exceeds " + MAX_ENTRY_FIELD_BYTES + " bytes: " + v.length);
            }
            size += 4L + k.length + 4L + v.length;
        }
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("snapshot too large to serialize: " + size + " bytes");
        }

        ByteBuffer buf = ByteBuffer.allocate((int) size);
        buf.putLong(snapshot.version());
        buf.putInt(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            byte[] k = keys.get(i);
            byte[] v = values.get(i);
            buf.putInt(k.length);
            buf.put(k);
            buf.putInt(v.length);
            buf.put(v);
        }
        return buf.array();
    }

    /**
     * Splits ADR-0028 snapshot body bytes into {@link EdgeFrame.SnapshotChunk} payloads
     * of at most {@code chunkBytes} each, producing the chunk sequence the
     * {@code SNAPSHOT_BEGIN / chunks / SNAPSHOT_END} flow carries. An empty snapshot
     * body still yields chunks (the 8+4-byte header is one small chunk); zero-length
     * input is never produced by {@link #serialize}.
     *
     * @param body       the serialized snapshot bytes
     * @param chunkBytes the maximum chunk payload size (must be ≥ 1 and
     *                   ≤ {@link EdgeFrameCodec#MAX_SNAPSHOT_CHUNK_BYTES})
     * @return the ordered list of snapshot chunks
     */
    public static List<EdgeFrame.SnapshotChunk> chunk(byte[] body, int chunkBytes) {
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        if (chunkBytes < 1 || chunkBytes > EdgeFrameCodec.MAX_SNAPSHOT_CHUNK_BYTES) {
            throw new IllegalArgumentException(
                    "chunkBytes out of range [1, " + EdgeFrameCodec.MAX_SNAPSHOT_CHUNK_BYTES
                            + "]: " + chunkBytes);
        }
        List<EdgeFrame.SnapshotChunk> chunks = new ArrayList<>();
        int index = 0;
        for (int off = 0; off < body.length; off += chunkBytes) {
            int len = Math.min(chunkBytes, body.length - off);
            byte[] slice = new byte[len];
            System.arraycopy(body, off, slice, 0, len);
            chunks.add(new EdgeFrame.SnapshotChunk(index++, slice));
        }
        if (chunks.isEmpty()) {
            // Defensive: a zero-length body (never produced by serialize) still yields
            // one empty chunk so chunkCount/totalBytes accounting stays consistent.
            chunks.add(new EdgeFrame.SnapshotChunk(0, new byte[0]));
        }
        return chunks;
    }

    /**
     * Reassembles ADR-0028 snapshot body bytes from an ordered chunk list (the
     * driver-side / edge-side counterpart to {@link #chunk}). Chunk indices must be the
     * contiguous run {@code 0..n-1} in order.
     *
     * @param chunks the ordered snapshot chunks
     * @return the reassembled snapshot body bytes
     * @throws IllegalArgumentException if the chunk indices are not the contiguous run 0..n-1
     */
    public static byte[] reassemble(List<EdgeFrame.SnapshotChunk> chunks) {
        if (chunks == null) {
            throw new IllegalArgumentException("chunks must not be null");
        }
        long total = 0;
        for (int i = 0; i < chunks.size(); i++) {
            EdgeFrame.SnapshotChunk c = chunks.get(i);
            if (c.index() != i) {
                throw new IllegalArgumentException(
                        "snapshot chunk out of order: expected index " + i + " got " + c.index());
            }
            total += c.bytesUnsafe().length;
        }
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("reassembled snapshot too large: " + total);
        }
        byte[] out = new byte[(int) total];
        int off = 0;
        for (EdgeFrame.SnapshotChunk c : chunks) {
            byte[] b = c.bytesUnsafe();
            System.arraycopy(b, 0, out, off, b.length);
            off += b.length;
        }
        return out;
    }

    /**
     * Decodes ADR-0028 snapshot body bytes back into a {@link ConfigSnapshot}. This is
     * the inverse of {@link #serialize} (used by the simulator's driver-side sink after
     * reassembly, and available to the edge). Each value is stamped with the snapshot's
     * version (consistent with {@code ConfigStateMachine.restoreSnapshot}, which stamps
     * all restored values with the restored sequence).
     *
     * @param body the ADR-0028 body bytes
     * @return the decoded snapshot
     * @throws IllegalArgumentException on a truncated or out-of-bounds body
     */
    public static ConfigSnapshot deserialize(byte[] body) {
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        ByteBuffer buf = ByteBuffer.wrap(body);
        if (buf.remaining() < 12) {
            throw new IllegalArgumentException(
                    "snapshot body too short: " + body.length + " bytes (need >= 12)");
        }
        long version = buf.getLong();
        int entryCount = buf.getInt();
        if (entryCount < 0) {
            throw new IllegalArgumentException("snapshot entryCount negative: " + entryCount);
        }
        io.configd.store.HamtMap<String, VersionedValue> data = io.configd.store.HamtMap.empty();
        for (int i = 0; i < entryCount; i++) {
            int keyLen = readBoundedLen(buf, "key", i);
            byte[] keyBytes = new byte[keyLen];
            buf.get(keyBytes);
            String key = new String(keyBytes, StandardCharsets.UTF_8);
            int valLen = readBoundedLen(buf, "value", i);
            byte[] value = new byte[valLen];
            buf.get(value);
            // Stamp with the snapshot version (mirrors restoreSnapshot's restoredSequence).
            data = data.put(key, new VersionedValue(value, version, 0L));
        }
        return new ConfigSnapshot(data, version, 0L);
    }

    private static int readBoundedLen(ByteBuffer buf, String field, int entry) {
        if (buf.remaining() < 4) {
            throw new IllegalArgumentException(
                    "snapshot truncated reading " + field + " length at entry " + entry);
        }
        int len = buf.getInt();
        if (len < 0 || len > MAX_ENTRY_FIELD_BYTES) {
            throw new IllegalArgumentException(
                    "snapshot " + field + " length out of range at entry " + entry + ": " + len
                            + " (max " + MAX_ENTRY_FIELD_BYTES + ")");
        }
        if (buf.remaining() < len) {
            throw new IllegalArgumentException(
                    "snapshot truncated: expected " + len + " " + field + " bytes at entry "
                            + entry + ", only " + buf.remaining() + " remaining");
        }
        return len;
    }
}

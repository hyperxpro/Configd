package io.configd.distribution.wire;

import io.configd.store.ConfigSnapshot;
import io.configd.store.VersionedValue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializes ConfigSnapshot to durable snapshot byte format and splits into wire chunks
 * (SNAPSHOT_BEGIN/CHUNK/END flow).
 * <p>
 * Emits snapshot body only ([seq u64][entryCount u32][(keyLen u32, key, valLen u32, val)*]
 * in HamtMap.forEach order), interchangeable with durable form, round-trip-verified vs.
 * ConfigStateMachine.restoreSnapshot.
 * <p>
 * Key/value each capped at MAX_ENTRY_FIELD_BYTES (1 MiB, matching CommandCodec.MAX_VALUE_SIZE
 * and state machine snapshot caps). Body carries no format version; versioned by enclosing
 * edge frame version. Leading u64 is DATA sequence (which snapshot), not format version.
 * Trailer-less by design (edge decodes via deserialize(), unlike durable state-machine
 * snapshot which appends magic-TLV trailer).
 */
public final class EdgeSnapshotCodec {

    public static final int MAX_ENTRY_FIELD_BYTES = 1_048_576;

    private EdgeSnapshotCodec() {
    }

    /** Serializes snapshot to body bytes (no trailer) in HamtMap.forEach order. */
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
        buf.putLong(snapshot.version()); // lead u64 = DATA sequence (which snapshot), not a format version
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
     * Splits snapshot body bytes into {@link EdgeFrame.SnapshotChunk} payloads of at most
     * {@code chunkBytes} each, producing the chunk sequence the
     * {@code SNAPSHOT_BEGIN / chunks / SNAPSHOT_END} flow carries. An empty snapshot body
     * still yields chunks (the 8+4-byte header is one small chunk); zero-length input is
     * never produced by {@link #serialize}.
     *
     * @param body       the serialized snapshot bytes
     * @param chunkBytes the maximum chunk payload size (must be >= 1 and
     *                   <= {@link EdgeFrameCodec#MAX_SNAPSHOT_CHUNK_BYTES})
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
     * Reassembles snapshot body bytes from an ordered chunk list (the driver-side /
     * edge-side counterpart to {@link #chunk}). Chunk indices must be the contiguous run
     * {@code 0..n-1} in order.
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
     * Decodes snapshot body bytes back into a {@link ConfigSnapshot} - the inverse of
     * {@link #serialize} (used by the simulator's driver-side sink after reassembly, and
     * available to the edge). Each value is stamped with the snapshot's version (consistent
     * with {@code ConfigStateMachine.restoreSnapshot}, which stamps all restored values with
     * the restored sequence).
     *
     * @param body the snapshot body bytes
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

package io.configd.raft;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Node-level durability anchor payload: binds topology (epoch, shardCount), audit chain head,
 * and per-shard liveness (shardAnchorDigest) for boot cross-check.
 * Fixed 92-byte wire payload. Immutable; 32-byte fields defensively cloned.
 */
public record NodeAnchorRecord(long nodeAnchorSeq, long topologyEpoch, int shardCount,
                               long auditRecordCount, byte[] auditHeadHash,
                               byte[] shardAnchorDigest) {

    public static final int HASH_LEN = 32;
    public static final int PAYLOAD_LEN = 8 + 8 + 4 + 8 + HASH_LEN + HASH_LEN; // 92
    public static final byte[] ZERO_HASH = new byte[HASH_LEN];

    public NodeAnchorRecord {
        Objects.requireNonNull(auditHeadHash, "auditHeadHash");
        Objects.requireNonNull(shardAnchorDigest, "shardAnchorDigest");
        if (auditHeadHash.length != HASH_LEN) {
            throw new IllegalArgumentException(
                    "auditHeadHash must be " + HASH_LEN + " bytes, got " + auditHeadHash.length);
        }
        if (shardAnchorDigest.length != HASH_LEN) {
            throw new IllegalArgumentException(
                    "shardAnchorDigest must be " + HASH_LEN + " bytes, got " + shardAnchorDigest.length);
        }
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be >= 1, got " + shardCount);
        }
        auditHeadHash = auditHeadHash.clone();
        shardAnchorDigest = shardAnchorDigest.clone();
    }

    /**
     * The bootstrap record a node lays down on first boot: {@code seq=1}, the current topology and
     * shard-liveness digest, and an empty audit head (no records anchored yet).
     *
     * @param topologyEpoch     the deploy-time topology epoch (bound copy)
     * @param shardCount        the deploy-time shard count N (bound copy)
     * @param shardAnchorDigest the digest over the current per-shard {@code (gid, lastDurableIndex)}
     */
    public static NodeAnchorRecord fresh(long topologyEpoch, int shardCount, byte[] shardAnchorDigest) {
        return new NodeAnchorRecord(1L, topologyEpoch, shardCount, 0L, ZERO_HASH, shardAnchorDigest);
    }

    /** Serializes exactly {@link #PAYLOAD_LEN} big-endian bytes. */
    public byte[] encodePayload() {
        ByteBuffer buf = ByteBuffer.allocate(PAYLOAD_LEN);
        buf.putLong(nodeAnchorSeq);
        buf.putLong(topologyEpoch);
        buf.putInt(shardCount);
        buf.putLong(auditRecordCount);
        buf.put(auditHeadHash);
        buf.put(shardAnchorDigest);
        return buf.array();
    }

    /**
     * Parses a {@link #PAYLOAD_LEN}-byte payload. The bytes have already been envelope-verified
     * (MAC/GCM tag + CRC32C + {@code scopeId == NODE_SCOPE}) by the caller, so a wrong length here
     * is a structural bug, not an attack surface.
     */
    public static NodeAnchorRecord decode(byte[] payload) {
        if (payload.length != PAYLOAD_LEN) {
            throw new IllegalArgumentException(
                    "node-anchor payload must be " + PAYLOAD_LEN + " bytes, got " + payload.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        long seq = buf.getLong();
        long epoch = buf.getLong();
        int shardCount = buf.getInt();
        long auditCount = buf.getLong();
        byte[] auditHead = new byte[HASH_LEN];
        buf.get(auditHead);
        byte[] shardDigest = new byte[HASH_LEN];
        buf.get(shardDigest);
        return new NodeAnchorRecord(seq, epoch, shardCount, auditCount, auditHead, shardDigest);
    }

    /** Copy with a new {@code nodeAnchorSeq} (assigned by the writer just before a slot write). */
    public NodeAnchorRecord withSeq(long seq) {
        return new NodeAnchorRecord(seq, topologyEpoch, shardCount,
                auditRecordCount, auditHeadHash, shardAnchorDigest);
    }

    /**
     * Copy advancing the audit head and refreshing the shard-liveness digest (the periodic-cadence
     * update). Topology fields are unchanged - a topology change is a full reshard, not a periodic tick.
     */
    public NodeAnchorRecord withAuditAndDigest(long newAuditCount, byte[] newAuditHead, byte[] newDigest) {
        return new NodeAnchorRecord(nodeAnchorSeq, topologyEpoch, shardCount,
                newAuditCount, newAuditHead, newDigest);
    }

    /**
     * SHA-256 over the sorted {@code (gid, lastDurableIndex)} pairs. Canonicalized by ascending gid so
     * the digest is independent of the caller's iteration order; each pair contributes 12 big-endian
     * bytes {@code [gid:4][lastDurableIndex:8]}. This is the frozen shard-liveness fingerprint: a shard
     * whose durable head resets (a wipe to index 0) changes the digest, and a boot cross-check turns
     * that into a detected node-anchor rollback.
     *
     * @param perShardDurableIndex gid → the shard's {@code raft-anchor.lastDurableIndex}
     * @return the 32-byte digest
     */
    public static byte[] computeShardAnchorDigest(Map<Integer, Long> perShardDurableIndex) {
        Objects.requireNonNull(perShardDurableIndex, "perShardDurableIndex");
        // TreeMap => deterministic ascending-gid iteration regardless of the caller's map type/order.
        TreeMap<Integer, Long> sorted = new TreeMap<>(perShardDurableIndex);
        MessageDigest sha = sha256();
        ByteBuffer pair = ByteBuffer.allocate(Integer.BYTES + Long.BYTES); // reused per entry
        for (Map.Entry<Integer, Long> e : sorted.entrySet()) {
            pair.clear();
            pair.putInt(e.getKey());
            pair.putLong(e.getValue());
            sha.update(pair.array());
        }
        return sha.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated on every conformant JRE (JCA spec); absence is unrecoverable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

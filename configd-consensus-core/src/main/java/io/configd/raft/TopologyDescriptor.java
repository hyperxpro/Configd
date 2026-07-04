package io.configd.raft;

import io.configd.common.IntegrityEnvelope;

import java.nio.ByteBuffer;

/**
 * The authenticated, versioned deploy-time topology descriptor - the frozen-format replacement
 * for the plaintext {@code raft-shard-count.meta} marker. It serves BOTH the fixed-N deploy guard
 * (a different {@code N} on restart is a loud reshard rejection) AND the A4 topology-epoch authority
 * that {@code ShardMap.epoch()} returns.
 *
 * <p>The descriptor is a node-level {@link IntegrityEnvelope} record under {@link RaftArtifactMagic#TOPO_MAGIC}
 * ("RTOP"), scoped {@link IntegrityEnvelope#NODE_SCOPE}, over an 18-byte payload:
 *
 * <pre>
 *   [formatVersion:u16 = 1][shardCount N:u32][topologyEpoch:u64][reserved:u32 = 0 MBZ]
 * </pre>
 *
 * <p>Wrapping it in the same {@code K_integrity} envelope the WAL uses makes the deploy guard
 * <b>tamper-evident</b>: under a key the MAC/GCM tag refuses an attacker who edits {@code N} (to
 * bypass the reshard refusal) or {@code topologyEpoch}; in the keyless posture it is CRC-only, per
 * the uniform posture rules (keyless carries no adversarial guarantee). Unknown magic / rolled
 * {@code formatVersion} / a non-zero reserved field / {@code topologyEpoch = 0} (reserved-illegal,
 * "pre-epoch") all fail loud - the same refuse-to-start class as the old corrupt-marker refusal.
 *
 * <p><b>Magic single-sourcing.</b> {@link RaftArtifactMagic#TOPO_MAGIC} is the frozen registry value
 * and is package-private; this codec lives in the SAME package so it reads that value directly - one
 * source of truth, no cross-module mirror (unlike Gate 1's {@code WAL_FILE_MAGIC}, whose authoritative
 * definition sits in {@code configd-common} and must be mirrored). {@code RaftArtifactMagicTest} pins
 * the value distinct and non-zero.
 *
 * <p>Immutable and stateless beyond its two fields; safe to share.
 */
public final class TopologyDescriptor {

    /**
     * The artifact magic, read directly from the package-private frozen registry (single source of
     * truth). Not re-declared as a literal here so the two can never drift.
     */
    static final int TOPO_MAGIC = RaftArtifactMagic.TOPO_MAGIC;

    /** Inner payload format version (u16). Bumping this is a controlled, envelope-covered action. */
    public static final short FORMAT_VERSION = 1;

    /**
     * The v1 static-N topology epoch. {@code ShardMap.epoch()} returns this for the life of a
     * static-N deployment; a future v2 dynamic reshard bumps it monotonically. Cluster-wide
     * consistent - every node reads the same deploy-time descriptor.
     */
    public static final long INITIAL_EPOCH = 1L;

    /** Reserved-illegal epoch ("unset / pre-epoch"): rejected on decode and forbidden on construction. */
    public static final long EPOCH_UNSET = 0L;

    /** Payload width: formatVersion(2) + shardCount(4) + topologyEpoch(8) + reserved(4). */
    static final int PAYLOAD_SIZE = 2 + 4 + 8 + 4; // 18

    private final int shardCount;
    private final long topologyEpoch;

    /**
     * @param shardCount    the deploy-time shard count {@code N} ({@code >= 1})
     * @param topologyEpoch the topology epoch; {@code 0} ({@link #EPOCH_UNSET}) is reserved-illegal,
     *                      so the effective range is {@code [1, 2^63)}
     * @throws IllegalArgumentException if {@code shardCount < 1} or {@code topologyEpoch} is not in
     *                                  {@code [1, 2^63)}
     */
    public TopologyDescriptor(int shardCount, long topologyEpoch) {
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be >= 1, got " + shardCount);
        }
        if (topologyEpoch <= EPOCH_UNSET) {
            throw new IllegalArgumentException(
                    "topologyEpoch must be in [1, 2^63) (0 is reserved-illegal), got " + topologyEpoch);
        }
        this.shardCount = shardCount;
        this.topologyEpoch = topologyEpoch;
    }

    /** The deploy-time shard count {@code N}. */
    public int shardCount() {
        return shardCount;
    }

    /** The topology epoch (v1 = {@link #INITIAL_EPOCH}); the authority for {@code ShardMap.epoch()}. */
    public long topologyEpoch() {
        return topologyEpoch;
    }

    /**
     * Serializes and wraps this descriptor in a node-level integrity envelope under {@link #TOPO_MAGIC}.
     * The posture (keyless CRC-only / keyed HMAC / encrypting GCM) is the envelope's, matching every
     * other durability artifact.
     *
     * @param envelope the server's Raft integrity envelope (same {@code K_integrity} as the WAL)
     * @return the enveloped descriptor bytes, ready for a durable temp+fsync+rename write
     */
    public byte[] toEnvelope(IntegrityEnvelope envelope) {
        byte[] payload = new byte[PAYLOAD_SIZE];
        ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.putShort(FORMAT_VERSION);
        buf.putInt(shardCount);
        buf.putLong(topologyEpoch);
        buf.putInt(0); // reserved MBZ
        return envelope.wrap(TOPO_MAGIC, IntegrityEnvelope.NODE_SCOPE, payload);
    }

    /**
     * Verifies and parses a descriptor written by {@link #toEnvelope}. The envelope layer
     * (magic / CRC32C / MAC-or-GCM tag / scopeId) is checked by {@link IntegrityEnvelope#unwrap};
     * this method then validates the inner payload fail-closed.
     *
     * @param envelope  the server's Raft integrity envelope (must match the write-time posture/key)
     * @param enveloped the bytes read from {@code topology-descriptor.dat}
     * @return the verified descriptor
     * @throws io.configd.common.IntegrityException if the envelope fails to verify (wrong magic,
     *                                              CRC/MAC/tag mismatch, scope mismatch, tampered
     *                                              bytes) - the tamper-evident refusal
     * @throws IllegalStateException if the inner payload is malformed: wrong length, unsupported
     *                               {@code formatVersion}, a non-zero reserved field, or
     *                               {@code topologyEpoch = 0} (reserved-illegal)
     */
    public static TopologyDescriptor fromEnvelope(IntegrityEnvelope envelope, byte[] enveloped) {
        byte[] payload = envelope.unwrap(TOPO_MAGIC, IntegrityEnvelope.NODE_SCOPE, enveloped);
        if (payload.length != PAYLOAD_SIZE) {
            throw new IllegalStateException("topology descriptor payload must be " + PAYLOAD_SIZE
                    + " bytes, got " + payload.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        short formatVersion = buf.getShort();
        if (formatVersion != FORMAT_VERSION) {
            throw new IllegalStateException("unsupported topology descriptor formatVersion "
                    + formatVersion + " (expected " + FORMAT_VERSION + ")");
        }
        int shardCount = buf.getInt();
        long topologyEpoch = buf.getLong();
        int reserved = buf.getInt();
        // MBZ reserved: reject a non-zero value fail-closed so a future writer that assigns it
        // meaning can never be silently mis-parsed by a v1 reader.
        if (reserved != 0) {
            throw new IllegalStateException("topology descriptor reserved field must be zero, got " + reserved);
        }
        // Epoch 0 is the reserved "pre-epoch / unset" sentinel - never a legitimate deployed value.
        if (topologyEpoch == EPOCH_UNSET) {
            throw new IllegalStateException("topology epoch 0 is reserved-illegal (pre-epoch); the"
                    + " descriptor is corrupt or from an incompatible build");
        }
        if (shardCount < 1) {
            throw new IllegalStateException("topology descriptor shardCount must be >= 1, got " + shardCount);
        }
        return new TopologyDescriptor(shardCount, topologyEpoch);
    }

    @Override
    public String toString() {
        return "TopologyDescriptor[N=" + shardCount + ", epoch=" + topologyEpoch + "]";
    }
}

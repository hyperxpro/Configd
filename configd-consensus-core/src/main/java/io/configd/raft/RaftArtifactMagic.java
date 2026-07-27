package io.configd.raft;

/**
 * Frozen-v1 magic registry: each durability artifact gets its own 4-byte magic.
 * Prevents cross-artifact confusion: envelope from one artifact cannot replay into another
 * even under same K_integrity (magic is discriminator and part of MAC input).
 * ASCII sigils for hexdump grep-ability. Reserved-value discipline: non-zero, never reused.
 */
final class RaftArtifactMagic {

    /**
     * {@code raft.persistent_state} - durable (term, votedFor). ASCII "RFST".
     * <p>
     * RETIRED-RESERVED: in the frozen format the persistent Raft state lives in
     * the per-shard anchor, so this artifact does not exist as a standalone file. The
     * value is kept and NEVER reused, so no future magic can
     * collide with a pre-freeze {@code raft.persistent_state} envelope.
     */
    static final int STATE_MAGIC = 0x5246_5354;

    /** The Raft snapshot blob. ASCII "RSNP". */
    static final int SNAP_MAGIC = 0x5253_4E50;

    /** A single WAL entry payload (inside the FileStorage frame). ASCII "RWAL". */
    static final int WALE_MAGIC = 0x5257_414C;

    /**
     * The WAL container file header. ASCII "RWLF". Mirrors
     * {@code io.configd.common.WalContainer#WAL_FILE_MAGIC} - the authoritative
     * definition lives in {@code configd-common} (which cannot depend on this
     * module); a cross-module collision test keeps the two in lockstep.
     */
    static final int WAL_FILE_MAGIC = 0x5257_4C46;

    /** The chain-bound audit record header (inside a {@code security-audit.wal} frame). ASCII "RAUD". */
    static final int AUDIT_MAGIC = 0x5241_5544;

    /** The per-shard anchor file (container header + slot envelopes). ASCII "RANC". */
    static final int ANCHOR_MAGIC = 0x5241_4E43;

    /** The node anchor file (container header + slot envelopes). ASCII "RNAN". */
    static final int NODE_ANCHOR_MAGIC = 0x524E_414E;

    /** The keyring file (container header + slot envelopes). ASCII "RKYR". */
    static final int KEYRING_MAGIC = 0x524B_5952;

    /** The topology descriptor envelope (replaces the plain shard-count marker). ASCII "RTOP". */
    static final int TOPO_MAGIC = 0x5254_4F50;

    private RaftArtifactMagic() {
        // constants holder
    }
}

package io.configd.raft;

/**
 * The frozen-v1 registry of 4-byte magics for every Configd durability artifact -
 * both the {@link io.configd.common.IntegrityEnvelope} inner-record magics and the
 * container/node-level file magics.
 * <p>
 * Each artifact gets its own magic so an envelope lifted from one artifact cannot be
 * replayed into another (cross-artifact confusion): the magic is both the envelope
 * discriminator and part of the MAC input, so a snapshot blob can never be accepted
 * where {@code raft.persistent_state} is expected, even under the same
 * {@code K_integrity}. Container magics play the same role at the file level (a WAL
 * file cannot masquerade as an anchor file).
 * <p>
 * The values are ASCII sigils for grep-ability in a hexdump. Full catalog:
 * <ul>
 *   <li>{@code RFST} = 0x52465354 - {@code raft.persistent_state} (RETIRED-RESERVED, see below)</li>
 *   <li>{@code RSNP} = 0x52534E50 - the Raft snapshot blob ({@code raft-log.snapshot})</li>
 *   <li>{@code RWAL} = 0x5257414C - a single WAL entry payload ({@code raft-log})</li>
 *   <li>{@code RWLF} = 0x52574C46 - the WAL container file header (mirrors {@code WalContainer})</li>
 *   <li>{@code RAUD} = 0x52415544 - the chain-bound audit record header</li>
 *   <li>{@code RANC} = 0x52414E43 - the per-shard anchor file</li>
 *   <li>{@code RNAN} = 0x524E414E - the node anchor file</li>
 *   <li>{@code RKYR} = 0x524B5952 - the keyring file</li>
 *   <li>{@code RTOP} = 0x52544F50 - the topology descriptor</li>
 * </ul>
 * <p>
 * <b>Reserved-value discipline (frozen).</b> Every magic is non-zero (a zero-filled
 * or torn leading word can never be a valid artifact). No value is ever reused: a
 * retired magic's slot stays permanently reserved so a future reader can never
 * confuse a resurrected value with a different artifact. The collision test pins all
 * values distinct and non-zero, and pins {@link #WAL_FILE_MAGIC} to the authoritative
 * {@code WalContainer.WAL_FILE_MAGIC} in {@code configd-common}.
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

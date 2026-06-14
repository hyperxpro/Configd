package io.configd.raft;

/**
 * Distinct {@link io.configd.common.IntegrityEnvelope} magics for each at-rest
 * Raft durability artifact (ADR-0042, PA-2021).
 * <p>
 * Each artifact gets its own 4-byte magic so an envelope lifted from one
 * artifact cannot be replayed into another (cross-artifact confusion): the magic
 * is both the envelope discriminator and part of the MAC input, so a snapshot
 * blob can never be accepted where {@code raft.persistent_state} is expected,
 * even under the same {@code K_integrity}.
 * <p>
 * The values are ASCII sigils for grep-ability in a hexdump:
 * <ul>
 *   <li>{@code RFST} = 0x52465354 — {@code raft.persistent_state} (term + votedFor)</li>
 *   <li>{@code RSNP} = 0x52534E50 — the Raft snapshot blob ({@code raft-log.snapshot})</li>
 *   <li>{@code RWAL} = 0x5257414C — a single WAL entry payload ({@code raft-log})</li>
 * </ul>
 */
final class RaftArtifactMagic {

    /** {@code raft.persistent_state} — durable (term, votedFor). ASCII "RFST". */
    static final int STATE_MAGIC = 0x5246_5354;

    /** The Raft snapshot blob. ASCII "RSNP". */
    static final int SNAP_MAGIC = 0x5253_4E50;

    /** A single WAL entry payload (inside the FileStorage frame). ASCII "RWAL". */
    static final int WALE_MAGIC = 0x5257_414C;

    private RaftArtifactMagic() {
        // constants holder
    }
}

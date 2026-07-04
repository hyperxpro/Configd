package io.configd.raft;

/**
 * The frozen anti-rollback witness SPI (frozen-format v1 §A1.7). A witness records the strictly
 * monotone per-scope {@code anchorSeq} to some substrate that survives a rollback of the local data
 * directory, and reports the highest {@code anchorSeq} that substrate has seen for a scope. Boot
 * compares the locally-recovered {@code anchorSeq} against {@link #lastSeen(int)}: a
 * {@code storedSeq < lastSeen} means the local anchor was rolled back to a state the witness knows is
 * stale, and the node refuses to start (fail-closed).
 *
 * <p>Because casting a vote is, under the frozen merge, an {@code anchorSeq}-raising anchor write, a
 * within-term {@code votedFor} rollback is an {@code anchorSeq} rollback — so witnessing
 * {@code anchorSeq} monotonicity witnesses the vote, and this scalar SPI needs no {@code votedFor}
 * dimension. See {@code docs/design/anchor-witness-peer-quorum-2026-07-04.md}.
 *
 * <p>The v1 provider is {@link PeerQuorumAnchorWitness}: the substrate is the peers' in-memory
 * witness tables, re-established by continuous re-announce. The interface is deliberately provider-
 * agnostic so a later external-store provider (TPM/RPMB NV-counter, cloud monotonic counter) — which
 * also closes the single-node residual R-a — drops in behind the same seam, and a deployment may run
 * both (refuse if either reports {@code lastSeen > stored}).
 *
 * @see PeerQuorumAnchorWitness
 */
public interface AnchorWitness {

    /**
     * Records that the given scope has reached {@code anchorSeq}. Invoked by the anchor writer after
     * the anchor is durable. Monotone: recording a value no greater than one already recorded is a
     * no-op. Fire-and-forget — it does not block on remote acknowledgement (a strict provider may
     * still gate a dependent action, e.g. a vote, on acknowledgement out of band).
     *
     * @param scopeId the scope: a per-shard {@code gid}, or
     *                {@link io.configd.common.IntegrityEnvelope#NODE_SCOPE} for the node anchor
     * @param anchorSeq the strictly monotone anchor sequence reached for that scope
     */
    void record(int scopeId, long anchorSeq);

    /**
     * The highest {@code anchorSeq} the witness substrate has seen for the scope, or {@code 0} if it
     * has seen none. Invoked at boot: {@code recoveredSeq < lastSeen(scopeId)} ⇒ the local anchor was
     * rolled back ⇒ REFUSE.
     *
     * @param scopeId the scope (a {@code gid}, or {@link io.configd.common.IntegrityEnvelope#NODE_SCOPE})
     * @return the highest witnessed {@code anchorSeq} for the scope, or {@code 0} if none is known
     */
    long lastSeen(int scopeId);
}

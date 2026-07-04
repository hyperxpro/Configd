package io.configd.raft;

import io.configd.common.IntegrityEnvelope;

import java.util.Objects;
import java.util.function.IntFunction;

/**
 * The v1 {@link AnchorWitness} provider: a peer-quorum witness (Gate 3c). It realizes the frozen SPI
 * over the per-group {@link RaftNode}s, which own the actual protocol (the witness wire, tables, boot
 * gate, and vote latch). This class is the thin scope-dispatch façade the anchor-writer/boot seam holds;
 * the {@code scopeToNode} resolver maps a {@code scopeId} to its owning node (the driver's
 * {@code getGroup}). See {@code docs/design/anchor-witness-peer-quorum-2026-07-04.md} §5.
 *
 * <p><b>As-built note.</b> The R-a&#39; closure is driven by each node's own tick/vote machinery
 * (heartbeat-cadence re-announce + after-vote announce + boot QUERY), because a witness broadcast needs
 * the transport, the peer set, and the owner thread that only {@link RaftNode} has - and a broadcast on
 * every durable anchor fsync would be far chattier than the design's cadence. So {@link #record} maps to
 * an explicit announce and {@link #lastSeen} to the node's accumulated witnessed floor; the boot gate
 * consumes the same accumulator internally rather than through a blocking {@code lastSeen} call (the gate
 * must never block the owner thread). The scalar SPI is realized unmodified.
 *
 * <p><b>Node scope.</b> {@link IntegrityEnvelope#NODE_SCOPE} has no Raft group and casts no vote; the
 * node anchor rides freshness only (informational for R-a). This provider therefore treats the node
 * scope as unwitnessed ({@code lastSeen == 0}, {@code record} a no-op) - the R-a / single-node closer is
 * the external-store provider that drops in behind this same interface later.
 *
 * <p>Owner-thread affinity: {@link #record} and {@link #lastSeen} delegate to owner-thread-confined
 * {@link RaftNode} reads/broadcasts, so they must be invoked on the scope's owner thread (or, for
 * {@code lastSeen}, at boot before the owner is bound).
 */
public final class PeerQuorumAnchorWitness implements AnchorWitness {

    private final IntFunction<RaftNode> scopeToNode;

    /**
     * @param scopeToNode resolves a per-shard {@code gid} to its owning {@link RaftNode}, or returns
     *                    {@code null} for an unknown/absent group (e.g. the driver's {@code getGroup})
     */
    public PeerQuorumAnchorWitness(IntFunction<RaftNode> scopeToNode) {
        this.scopeToNode = Objects.requireNonNull(scopeToNode, "scopeToNode");
    }

    @Override
    public void record(int scopeId, long anchorSeq) {
        RaftNode node = resolve(scopeId);
        if (node != null) {
            node.witnessAnnounce();
        }
    }

    @Override
    public long lastSeen(int scopeId) {
        RaftNode node = resolve(scopeId);
        return node == null ? 0L : node.witnessedFloor();
    }

    private RaftNode resolve(int scopeId) {
        if (scopeId == IntegrityEnvelope.NODE_SCOPE) {
            return null; // node anchor: freshness-only, no vote (§5) - closed by the external-store provider
        }
        return scopeToNode.apply(scopeId);
    }
}

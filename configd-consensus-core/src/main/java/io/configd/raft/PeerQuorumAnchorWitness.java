package io.configd.raft;

import io.configd.common.IntegrityEnvelope;

import java.util.Objects;
import java.util.function.IntFunction;

/**
 * AnchorWitness provider: peer-quorum witness. Thin facade delegating to RaftNode
 * (which owns the actual protocol: witness wire, tables, boot gate, vote latch).
 * NODE_SCOPE treated as unwitnessed (single-node gap closed by external-store provider later).
 * Owner-thread affinity: record() and lastSeen() must run on scope's owner thread.
 */
public final class PeerQuorumAnchorWitness implements AnchorWitness {

    private final IntFunction<RaftNode> scopeToNode;

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
            return null; // node anchor: freshness-only, no vote - closed by the external-store provider
        }
        return scopeToNode.apply(scopeId);
    }
}

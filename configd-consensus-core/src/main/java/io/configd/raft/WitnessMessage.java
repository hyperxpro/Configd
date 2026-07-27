package io.configd.raft;

import io.configd.common.NodeId;

/**
 * Peer-quorum anchor-witness gossip: announces per-group anchorSeq (within-term votedFor rollback witness).
 * sender NOT serialized (transport-authenticated prefix, not body field) — prevents hostile peer from
 * spoofing identity into witness tables. query=true (boot path) => recipient replies with WitnessReply.
 */
public record WitnessMessage(
        NodeId sender,
        long selfAnchorSeq,
        long selfTerm,
        int selfVotedFor,
        long seenOfYouSeq,
        boolean query
) implements RaftMessage {
}

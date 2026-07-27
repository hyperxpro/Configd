package io.configd.raft;

import io.configd.common.NodeId;

/**
 * Reply to WitnessMessage query (boot path). seenOfYouSeq=querier's highest witnessed anchorSeq
 * (boot gate compares against bootAnchorSeq). sender transport-authenticated, not serialized.
 */
public record WitnessReply(
        NodeId sender,
        long selfAnchorSeq,
        long selfTerm,
        int selfVotedFor,
        long seenOfYouSeq
) implements RaftMessage {
}

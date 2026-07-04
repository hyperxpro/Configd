package io.configd.raft;

import io.configd.common.NodeId;

/**
 * Reply to a {@link WitnessMessage} whose {@code query} flag was set (the boot path). Same 29-byte
 * body as {@link WitnessMessage} with the QUERY flag cleared; {@code seenOfYouSeq} carries the
 * replier's {@code witnessOfPeer[querier]} so the querier learns the highest {@code anchorSeq} a peer
 * has witnessed of it — the value the boot gate compares against {@code bootAnchorSeq}. See
 * {@link WitnessMessage} for the {@code sender} note (transport-authenticated origin, not on the wire).
 *
 * @param sender        the originating node — the transport-authenticated sender on receive, this
 *                      node's id on send (never serialized)
 * @param selfAnchorSeq the replier's current {@code anchorSeq} for this group
 * @param selfTerm      the replier's {@code currentTerm} (diagnostic term cross-check only)
 * @param selfVotedFor  the replier's {@code votedFor} id, or {@code -1} for none (diagnostic only)
 * @param seenOfYouSeq  the highest {@code anchorSeq} the replier has witnessed FROM the querier
 */
public record WitnessReply(
        NodeId sender,
        long selfAnchorSeq,
        long selfTerm,
        int selfVotedFor,
        long seenOfYouSeq
) implements RaftMessage {
}

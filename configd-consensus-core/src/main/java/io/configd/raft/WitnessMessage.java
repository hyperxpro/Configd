package io.configd.raft;

import io.configd.common.NodeId;

/**
 * Peer-quorum anchor-witness gossip. A node announces its per-group {@code anchorSeq} to a peer
 * so that a within-term {@code votedFor} rollback — which lowers {@code anchorSeq} — is witnessed
 * across the cluster. See the design addendum
 * {@code docs/design/anchor-witness-peer-quorum-2026-07-04.md} and {@link AnchorWitness}.
 *
 * <p>The 29-byte on-wire body is {@code [selfAnchorSeq:8][selfTerm:8][selfVotedFor:4][seenOfYouSeq:8]
 * [flags:1]} (see {@code RaftMessageCodec.encodeWitness}). The {@code sender} is NOT on the wire: like
 * the coalesced heartbeat, it is carried by the transport's authenticated 4-byte sender-id prefix and
 * injected from {@code InboundMessage.from} at decode. On the send path {@code sender} is this node's
 * own id (it is not serialized); on the receive path it is the authenticated origin the handler keys
 * {@code witnessOfPeer}/{@code peerAckOfSelf} on. Using the transport origin (not a body field) is why
 * an authenticated-but-hostile peer cannot spoof another peer's identity into the witness tables.
 *
 * @param sender        the originating node — the transport-authenticated sender on receive, this
 *                      node's id on send (never serialized; see class note)
 * @param selfAnchorSeq the sender's current {@code anchorSeq} for this group (the witnessed quantity)
 * @param selfTerm      the sender's {@code currentTerm} (diagnostic term cross-check only)
 * @param selfVotedFor  the sender's {@code votedFor} id, or {@code -1} for none (diagnostic only)
 * @param seenOfYouSeq  the highest {@code anchorSeq} the sender has witnessed FROM the recipient
 * @param query         when true (the boot path), the recipient replies with a {@link WitnessReply}
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

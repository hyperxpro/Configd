package io.configd.raft;

import io.configd.common.NodeId;

import java.util.Map;

/**
 * The drain target for coalesced heartbeats: the owner calls {@link #sendCoalesced} once per peer at
 * the end of its tick, handing over every group's heartbeat to that peer. The implementation owns the
 * framing decision (M3; {@code docs/phase0-B-stage2-m3/design.md}):
 * <ul>
 *   <li>exactly ONE group for the peer ⇒ send the single {@link AppendEntriesRequest} as a normal
 *       heartbeat — the production wire is unchanged (this is the only case that occurs at N=1);</li>
 *   <li>more than one group ⇒ send a {@link CoalescedHeartbeat} carrying all of them (the N&gt;1
 *       test-only surface; the receiver demultiplexes).</li>
 * </ul>
 * <p>
 * Implementations: the production server (frame each AppendEntries via {@code RaftMessageCodec} onto the
 * node-level {@code TcpRaftTransport}), the deterministic sim harness (hand to {@code SimulatedNetwork}),
 * and the M3 property/counting/demux test transports.
 */
@FunctionalInterface
public interface CoalescedHeartbeatTransport {

    /**
     * Sends the heartbeats {@code group -> empty AppendEntriesRequest} that the owner accumulated for
     * {@code peer} this tick. Never called with an empty map. Must not block (Raft handles loss).
     *
     * @param peer            the destination peer
     * @param groupHeartbeats the per-group heartbeats for this peer (at least one entry)
     */
    void sendCoalesced(NodeId peer, Map<Integer, AppendEntriesRequest> groupHeartbeats);
}

package io.configd.transport;

/**
 * Functional metrics hook for the consensus transports ({@link TcpRaftTransport} and the Netty
 * {@code NettyRaftTransport}), kept in {@code configd-transport} rather than depending directly on
 * {@code configd-observability}. Default no-op methods so a sink need only override the events it
 * cares about.
 * <p>
 * All callbacks must be thread-safe (invoked from event-loop / reader threads) and allocation-free
 * on the steady-state path.
 */
public interface RaftTransportMetrics {

    /**
     * Records a rejected peer-identity binding: a TLS handshake whose certificate
     * identity is not an authorized node, or a frame whose {@code senderId} prefix / in-body
     * {@code leaderId}/{@code candidateId} does not match the connection's verified {@link
     * io.configd.common.NodeId}. Backs the {@code configd_raft_peer_identity_mismatch} alert series.
     * Counted only when a {@link PeerIdentityPolicy} is {@linkplain PeerIdentityPolicy#enforced()
     * enforced}.
     */
    default void onPeerIdentityRejected() {}

    /**
     * Records an inbound frame dropped at the Raft message-decode boundary: a frame that
     * framed and CRC-verified cleanly but could not be turned into an actionable {@code RaftMessage} -
     * a dormant/undecodable {@link MessageType} (e.g. the reserved {@code PLUMTREE_*}/{@code HYPARVIEW_*}
     * /{@code HEARTBEAT} codes) that has no consensus codec, or a structurally-malformed payload
     * (truncation, an out-of-range blob length, a negative field). The frame is discarded and the
     * connection kept, so an authenticated-but-hostile peer could otherwise flood the log one line per
     * frame; this counter makes the drop-rate observable while the log itself is rate-limited. Backs the
     * {@code configd_raft_decode_dropped} series.
     */
    default void onInboundFrameDropped() {}

    /**
     * Records a peer connection dropped at the frame-envelope decode boundary: a frame whose declared
     * length is out of range, whose wire version is unrecognised ({@link FrameCodec.UnsupportedWireVersionException}),
     * or whose CRC / type / reserved-field check failed ({@link IllegalArgumentException} from
     * {@link FrameCodec#decode}). Distinct from {@link #onInboundFrameDropped()}: that keeps the
     * connection (a decodable-but-undispatchable message at a higher layer), whereas this desync closes
     * the peer connection outright. Backs the {@code configd_raft_transport_connection_decode_dropped}
     * series so the version-skew / hostile-peer drop rate is observable while the log line is rate-limited.
     */
    default void onInboundConnectionDropped() {}

    RaftTransportMetrics NOOP = new RaftTransportMetrics() {};
}

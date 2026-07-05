package io.configd.transport;

/**
 * Functional metrics hook for the consensus transports ({@link TcpRaftTransport} and the Netty
 * {@code NettyRaftTransport}). Surfaces security-relevant transport events into the server's
 * {@code MetricsRegistry} without forcing {@code configd-transport} / {@code configd-netty} to depend
 * on {@code configd-observability}.
 *
 * <p>Mirrors the {@code StateMachineMetrics} pattern: an interface with a {@link #NOOP} sentinel for
 * unit tests and pre-wire-up bootstraps, and default no-op methods so a sink need only override the
 * events it cares about. All callbacks must be thread-safe (invoked from event-loop / reader threads)
 * and allocation-free on the steady-state path.
 */
public interface RaftTransportMetrics {

    /**
     * Records a rejected peer-identity binding (WH-08/WH-09): a TLS handshake whose certificate
     * identity is not an authorized node, or a frame whose {@code senderId} prefix / in-body
     * {@code leaderId}/{@code candidateId} does not match the connection's verified {@link
     * io.configd.common.NodeId}. Backs the {@code configd_raft_peer_identity_mismatch} alert series.
     * Counted only when a {@link PeerIdentityPolicy} is {@linkplain PeerIdentityPolicy#enforced()
     * enforced}. Default no-op so existing sinks need not change.
     */
    default void onPeerIdentityRejected() {}

    /** No-op sink - used by tests and bootstraps with no registry. */
    RaftTransportMetrics NOOP = new RaftTransportMetrics() {};
}

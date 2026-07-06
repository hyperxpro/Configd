package io.configd.server;

import io.configd.observability.ConfigdMetrics;
import io.configd.transport.RaftTransportMetrics;

import java.util.Objects;

/**
 * Bridges the {@link RaftTransportMetrics} sink ({@code configd-transport}) into the server's
 * {@link ConfigdMetrics} registry, so consensus-transport security events surface as Prometheus
 * series without {@code configd-transport} / {@code configd-netty} depending on
 * {@code configd-observability}. Mirrors {@link ServerStateMachineMetrics}.
 *
 * <p>Shared by the transport (Layer-1 handshake rejection, Layer-2 {@code senderId} mismatch) and the
 * {@link RaftTransportAdapter} (in-body {@code leaderId}/{@code candidateId} mismatch), so all three
 * WH-08/09 rejection sites increment the single {@code configd_raft_peer_identity_mismatch} counter.
 */
final class ServerRaftTransportMetrics implements RaftTransportMetrics {

    private final ConfigdMetrics metrics;

    ServerRaftTransportMetrics(ConfigdMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    @Override
    public void onPeerIdentityRejected() {
        metrics.raftPeerIdentityMismatch().increment();
    }

    @Override
    public void onInboundFrameDropped() {
        metrics.raftDecodeDropped().increment();
    }
}

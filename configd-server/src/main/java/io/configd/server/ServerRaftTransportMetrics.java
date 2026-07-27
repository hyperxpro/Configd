package io.configd.server;

import io.configd.observability.ConfigdMetrics;
import io.configd.transport.RaftTransportMetrics;

import java.util.Objects;


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
    public void onInboundConnectionDropped() {
        metrics.raftConnectionDecodeDropped().increment();
    }

    @Override
    public void onInboundFrameDropped() {
        metrics.raftDecodeDropped().increment();
    }
}

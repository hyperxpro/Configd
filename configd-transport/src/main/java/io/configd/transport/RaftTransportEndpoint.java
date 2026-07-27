package io.configd.transport;

import java.io.IOException;

/**
 * The lifecycle + observability surface a consensus transport exposes to its wiring
 * ({@code ConfigdServer}), on top of the {@link RaftTransport} send/receive contract.
 * <p>
 * Both the JDK {@link TcpRaftTransport} and the Netty {@code NettyRaftTransport}
 * implement this interface, so a cutover flips a <b>single construction line</b>
 * ({@code new TcpRaftTransport(...)} to {@code new NettyRaftTransport(...)}). The
 * {@code RaftTransportAdapter} bridges to consensus-core through the {@link RaftTransport}
 * methods alone, so it is transport-agnostic by construction.
 */
public interface RaftTransportEndpoint extends RaftTransport, AutoCloseable {

    void start() throws IOException;

    int localPort();

    TlsManager tlsManager();

    /**
     * Outbound frames dropped because no connection was established or the per-peer queue was full.
     * Monotonic. Raft tolerates loss (re-send on the next heartbeat), so drop-on-overflow is correct.
     *
     * @return total dropped frames since construction
     */
    long framesDropped();

    /**
     * Inbound connections refused because the accepted live-set reached the admission cap.
     * Monotonic (slowloris / FD-exhaustion guard).
     *
     * @return total refused inbound connections since construction
     */
    long inboundConnectionsRefused();

    default boolean peerIdentityEnforced() {
        return false;
    }

    @Override
    void close();
}

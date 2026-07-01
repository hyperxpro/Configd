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

    /**
     * Binds the listen socket and begins accepting inbound peer connections.
     *
     * @throws IOException if the listen socket cannot be bound
     */
    void start() throws IOException;

    /**
     * The actual local port the listen socket is bound to (useful when binding to port 0).
     *
     * @return the bound local port
     */
    int localPort();

    /**
     * The {@link TlsManager} this transport enforces mTLS with, or {@code null} for plaintext
     * (test/single-node). Exposed so the wiring can fail-closed when {@code --tls-*} flags are set
     * but the transport received no manager (F-0050).
     *
     * @return the TlsManager, or null if plaintext
     */
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

    /** Gracefully shuts the transport down. Narrowed from {@link AutoCloseable} to not throw. */
    @Override
    void close();
}

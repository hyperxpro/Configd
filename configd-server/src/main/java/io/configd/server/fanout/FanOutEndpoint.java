package io.configd.server.fanout;

import io.configd.distribution.fanout.SlowConsumerGovernor;

import java.io.IOException;

/**
 * The edge fan-out endpoint contract (ADR-0043 M3, DR-N14): the lifecycle + governor surface shared
 * by the JDK {@link FanOutServer} and the Netty {@code NettyFanOutServer}. {@code ConfigdServer}
 * holds the endpoint behind this interface, so the production cutover from the JDK transport to the
 * Netty transport is a single construction-line change (and its {@code git revert} the documented
 * fast-revert); the S7 / behaviour contract builds either implementation behind the same factory.
 */
public interface FanOutEndpoint {

    /** Binds the listen socket and starts accepting edge subscriber connections. */
    void start() throws IOException;

    /** The actual bound port (resolves an ephemeral port 0 after {@link #start()}); -1 if unbound. */
    int localPort();

    /** Stops the endpoint: unblocks accept, closes all live connections, drains threads. */
    void close();

    /** The slow-consumer governor this endpoint enforces (C4; for tests / diagnostics). */
    SlowConsumerGovernor governor();
}

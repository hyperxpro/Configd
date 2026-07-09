package io.configd.client.edge;

import io.configd.client.ConfigdClientConfig;
import io.configd.client.HostileServerLimits;
import io.configd.client.RetryPolicy;
import io.configd.client.UnavailableException;
import io.configd.distribution.wire.EdgeFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.security.KeyPair;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the bounded-reconnect guarantee (§07 E4-2 / E7): an always-rejecting server accrues to the
 * budget and gives up rather than reconnecting forever. This closes a red-team finding (A-1): the ordinary
 * attempt budget is reset by any positive frame (HEARTBEAT / SUBSCRIBE_OK), so a hostile or broken server could
 * emit ONE cheap frame per connection and then drop, resetting the budget every cycle and pinning the client in
 * an unbounded reconnect hot-loop (a self-inflicted DoS). The fix adds a second, markHealthy-independent bound:
 * a connection torn down before it reaches stability ({@code EdgeSession.MIN_STABLE_MILLIS}) counts toward a
 * rapid-failure ceiling that a positive frame cannot reset, so an immediate-drop server gives up while a
 * genuinely healthy (stable-then-flapping) connection is still tolerated.
 */
@Timeout(60)
class EdgeReconnectBudgetBoundTest {

    private static final int MAX_ATTEMPTS = 3;

    @Test
    void aNoProgressServerExhaustsTheBudgetAndGivesUp() throws Exception {
        KeyPair leader = StreamFixtures.ed25519();
        // Accept, read the SUBSCRIBE, send NOTHING, close. No positive frame => the ordinary budget increments
        // strictly and the client MUST give up.
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame(); // drain the SUBSCRIBE, then fall off the handler => socket closes
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config(server.port(), leader))) {
                client.subscribeFullStore(SubscribeOptions.defaults());
                ExecutionException ee = assertThrows(ExecutionException.class,
                        () -> client.terminalFuture().get(15, TimeUnit.SECONDS),
                        "a no-progress server must exhaust the bounded reconnect budget and give up");
                assertInstanceOf(UnavailableException.class, ee.getCause());
                assertTrue(server.connectionCount() <= MAX_ATTEMPTS + 2,
                        "expected a bounded connection count (~" + (MAX_ATTEMPTS + 1) + "), saw "
                                + server.connectionCount() + " (cause: " + ee.getCause() + ")");
            }
        }
    }

    @Test
    void anImmediateDropAfterAPositiveFrameIsBoundedNotAnUnboundedHotLoop() throws Exception {
        KeyPair leader = StreamFixtures.ed25519();
        // Every connection: read the SUBSCRIBE, confirm with SUBSCRIBE_OK, send a single HEARTBEAT (both reset the
        // ordinary attempt budget via markHealthy), then drop IMMEDIATELY. The reset alone used to let this loop
        // forever; now each connection is torn down before it reaches stability, so the rapid-failure ceiling
        // accrues and the client gives up — bounded, DESPITE every connection's budget-resetting positive frame.
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame(); // the SUBSCRIBE
            conn.send(new EdgeFrame.SubscribeOk(1L, EdgeFrame.Mode.TAIL));
            conn.send(new EdgeFrame.Heartbeat(1L, System.currentTimeMillis()));
            // fall off the handler => socket closes immediately => a rapid (pre-stability) failure
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config(server.port(), leader))) {
                client.subscribeFullStore(SubscribeOptions.defaults());
                ExecutionException ee = assertThrows(ExecutionException.class,
                        () -> client.terminalFuture().get(15, TimeUnit.SECONDS),
                        "an immediate-drop-after-a-positive-frame server must be bounded, not an unbounded hot-loop");
                assertInstanceOf(UnavailableException.class, ee.getCause());
                assertTrue(server.connectionCount() <= MAX_ATTEMPTS + 3,
                        "reconnects are bounded near the rapid-failure ceiling (~" + (MAX_ATTEMPTS + 1)
                                + "), saw " + server.connectionCount());
            }
        }
    }

    // -----------------------------------------------------------------------

    private static ConfigdClientConfig config(int port, KeyPair leader) {
        return ConfigdClientConfig.builder()
                .endpoint("127.0.0.1", port)
                .allowPlaintext(true)
                .verifyWith(leader.getPublic())
                .retryPolicy(new RetryPolicy(Duration.ofMillis(2), Duration.ofMillis(10), MAX_ATTEMPTS))
                .limits(longIdle())
                .build();
    }

    private static HostileServerLimits longIdle() {
        HostileServerLimits d = HostileServerLimits.defaults();
        return new HostileServerLimits(d.maxFrameBytes(), d.connectTimeoutMs(), d.handshakeTimeoutMs(),
                30_000, d.maxSnapshotTotalBytes(), d.maxSnapshotChunks());
    }
}

package io.configd.client.edge;

import io.configd.client.AuthFailedException;
import io.configd.client.ConfigdClientConfig;
import io.configd.client.CredentialExpiredException;
import io.configd.client.CredentialSource;
import io.configd.client.RetryPolicy;
import io.configd.client.UnavailableException;
import io.configd.client.edge.session.EdgeConnectionState;
import io.configd.common.auth.Credential;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The auth-mode and connection-lifecycle contract over a plaintext loopback mock — the transport is
 * incidental to auth/framing logic, so these run fast without TLS; the TLS cases live in
 * {@link EdgeTlsTest}. Each test asserts the wire-visible behavior the RFC pins: exactly one pre-auth
 * {@code AUTH}, no hot-loop on rejection, {@code REFRESH_AUTH} renewal, and the {@code CREDENTIAL_EXPIRED}
 * reconnect.
 */
@Timeout(30)
class EdgeConnectionAuthTest {

    @Test
    void tokenAuthSendsSinglePreAuthFrameAndReachesAuthenticated() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();                                   // the AUTH
            conn.send(new EdgeFrame.Heartbeat(0L, 1L));         // a positive liveness confirmation
            conn.parkUntilClosed();
        })) {
            ConfigdClientConfig config = tokenConfig(server.port(), tokens("golden-token"));
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
                assertEquals(AuthMode.TOKEN, client.authMode());
                client.connectAndAuthenticate().get(10, TimeUnit.SECONDS);

                assertEquals(EdgeConnectionState.AUTHENTICATED, client.state());
                await("server received exactly one AUTH", () -> server.authFrameCount() == 1);
                EdgeFrame first = server.received().get(0);
                assertInstanceOf(EdgeFrame.Auth.class, first, "the AUTH must be the first routed frame");
                assertEquals("golden-token", bearer(first));
                assertEquals(1, server.connectionCount());
            }
        }
    }

    @Test
    void basicAuthFramePresented() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.Heartbeat(0L, 1L));
            conn.parkUntilClosed();
        })) {
            CredentialSource basic = CredentialSource.basic("alice", "s3cret".toCharArray());
            ConfigdClientConfig config = tokenConfig(server.port(), basic);
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
                client.connectAndAuthenticate().get(10, TimeUnit.SECONDS);
                await("server received a BASIC AUTH", () -> server.authFrameCount() == 1);
                Credential cred = ((EdgeFrame.Auth) server.received().get(0)).credential();
                assertInstanceOf(Credential.BasicCredential.class, cred);
                assertEquals("alice", ((Credential.BasicCredential) cred).username());
            }
        }
    }

    @Test
    void authRejectRecoversViaBoundedReconnectNeverHotLoop() throws Exception {
        // An edge AUTH_FAIL is not provably permanent (it may be a transient authenticator outage
        // indistinguishable on the wire from a bad credential), so the client recovers via BOUNDED
        // reconnect-with-backoff — a fresh connection per attempt, never a hot-loop of AUTH on one socket. A
        // server that rejects every attempt exhausts the budget and the client gives up terminally.
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();                                   // the AUTH
            conn.send(new EdgeFrame.ErrorClose(ErrorCode.AUTH_FAIL, "bad token"));
            // then close (try-with-resources on Conn closes the socket)
        })) {
            RetryPolicy bounded = new RetryPolicy(Duration.ofMillis(5), Duration.ofMillis(20), 2);
            ConfigdClientConfig config = ConfigdClientConfig.builder()
                    .endpoint("127.0.0.1", server.port())
                    .allowPlaintext(true)
                    .credentialSource(tokens("nope"))
                    .retryPolicy(bounded)
                    .build();
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
                client.connectAndAuthenticate().get(10, TimeUnit.SECONDS); // optimistic-present succeeds

                ExecutionException terminal = assertThrows(ExecutionException.class,
                        () -> client.terminalFuture().get(10, TimeUnit.SECONDS));
                // After the bounded attempts the client gives up: an Unavailable wrapping the AUTH_FAIL cause.
                UnavailableException gaveUp = assertInstanceOf(UnavailableException.class, terminal.getCause());
                assertInstanceOf(AuthFailedException.class, gaveUp.getCause());

                // Bounded, never a hot-loop: exactly maxAttempts + 1 FRESH connections/AUTHs, then it stops.
                await("all bounded attempts made", () -> server.connectionCount() == 3);
                assertEquals(3, server.authFrameCount(), "one AUTH per fresh connection — never re-sent on one");
                assertEquals(0, client.reconnectCount(), "no attempt was ever confirmed healthy");
            }
        }
    }

    @Test
    void immediateRetryableTerminalOnEveryConnectionStopsAfterBoundedAttempts() throws Exception {
        // The regression that locks the hot-loop closed: a hostile/buggy server that accepts + optimistically
        // auths then IMMEDIATELY sends a RETRYABLE terminal on EVERY connection with NO positive frame ever
        // must NOT pin the client in an unbounded reconnect loop. Because no positive frame confirms health,
        // the budget never resets and the client gives up after maxAttempts.
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.ErrorClose(ErrorCode.CREDENTIAL_EXPIRED, "immediately expired"));
        })) {
            RetryPolicy bounded = new RetryPolicy(Duration.ofMillis(5), Duration.ofMillis(20), 3);
            ConfigdClientConfig config = ConfigdClientConfig.builder()
                    .endpoint("127.0.0.1", server.port())
                    .allowPlaintext(true)
                    .credentialSource(tokens("t"))
                    .retryPolicy(bounded)
                    .build();
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
                client.connectAndAuthenticate().get(10, TimeUnit.SECONDS);

                ExecutionException terminal = assertThrows(ExecutionException.class,
                        () -> client.terminalFuture().get(10, TimeUnit.SECONDS));
                assertInstanceOf(UnavailableException.class, terminal.getCause());
                // Bounded: initial + maxAttempts reconnects, then stop — it does NOT keep climbing.
                await("bounded attempts", () -> server.connectionCount() == 4);
                assertEquals(4, server.connectionCount());
                assertEquals(0, client.reconnectCount(), "no attempt was ever confirmed healthy");
            }
        }
    }

    @Test
    void positiveFrameResetsTheOrdinaryBudgetSoASingleHealthyFlapRecovers() throws Exception {
        // A connection that delivers a positive frame (a HEARTBEAT) before it terminals resets the ordinary
        // attempt budget, so ONE healthy-then-expired connection recovers under a one-shot budget: the reconnect
        // to the healthy second connection is confirmed and the client does not give up. (A server that instead
        // drops IMMEDIATELY on every connection is bounded by the rapid-failure ceiling — see
        // EdgeReconnectBudgetBoundTest — so a positive frame is no longer a blank cheque for an unbounded loop.)
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.Heartbeat(0L, 1L)); // a positive frame confirms health → resets the budget
            if (conn.index == 1) {
                conn.send(new EdgeFrame.ErrorClose(ErrorCode.CREDENTIAL_EXPIRED, "expired after a healthy interval"));
            } else {
                conn.parkUntilClosed(); // the recovery connection stays up — genuinely healthy
            }
        })) {
            RetryPolicy oneShot = new RetryPolicy(Duration.ofMillis(5), Duration.ofMillis(20), 1);
            ConfigdClientConfig config = ConfigdClientConfig.builder()
                    .endpoint("127.0.0.1", server.port())
                    .allowPlaintext(true)
                    .credentialSource(tokens("t"))
                    .retryPolicy(oneShot)
                    .build();
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
                client.connectAndAuthenticate().get(10, TimeUnit.SECONDS);
                // Await the LAGGING confirmed-healthy reconnect count (not connectionCount, which ticks the instant
                // the mock ACCEPTS the socket, before that connection's HEARTBEAT has run markHealthy()).
                await("the reconnect to the healthy connection reset the one-shot budget",
                        () -> client.reconnectCount() >= 1);
                assertTrue(server.connectionCount() >= 2, "reached the recovery connection via the healthy reset");
                assertFalse(client.terminalFuture().isDone(), "recovered — not a terminal give-up");
            }
        }
    }

    @Test
    void refreshAuthNowRenewsWithFreshCredential() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();                                   // the AUTH
            conn.send(new EdgeFrame.Heartbeat(0L, 1L));
            conn.parkUntilClosed();                             // will also read the REFRESH_AUTH
        })) {
            ConfigdClientConfig config = tokenConfig(server.port(), tokens("auth-1", "refresh-2"));
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
                client.connectAndAuthenticate().get(10, TimeUnit.SECONDS);
                await("initial AUTH seen", () -> server.authFrameCount() == 1);

                client.refreshAuthNow().get(10, TimeUnit.SECONDS);
                await("REFRESH_AUTH seen", () -> hasRefresh(server));

                EdgeFrame refresh = server.received().stream()
                        .filter(f -> f instanceof EdgeFrame.RefreshAuth).findFirst().orElseThrow();
                assertEquals("refresh-2", bearer(refresh)); // a fresh credential, same identity
            }
        }
    }

    @Test
    void scheduledProactiveRefreshFiresBeforeExpiry() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.Heartbeat(0L, 1L));
            conn.parkUntilClosed();
        })) {
            // The first credential expires imminently (schedules a near-immediate refresh); the refreshed one
            // expires far out (so it reschedules far out and does not loop).
            AtomicInteger call = new AtomicInteger();
            CredentialSource source = CredentialSource.supplier(() -> {
                int n = call.getAndIncrement();
                String token = n == 0 ? "short-lived" : "refreshed";
                Instant exp = n == 0 ? Instant.now().plusSeconds(1) : Instant.now().plusSeconds(3600);
                return new CredentialSource.Provided(new Credential.BearerToken(token), Optional.of(exp));
            });
            ConfigdClientConfig config = tokenConfig(server.port(), source);
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
                client.connectAndAuthenticate().get(10, TimeUnit.SECONDS);
                await("proactive REFRESH_AUTH fired before expiry", () -> hasRefresh(server));
                EdgeFrame refresh = server.received().stream()
                        .filter(f -> f instanceof EdgeFrame.RefreshAuth).findFirst().orElseThrow();
                assertEquals("refreshed", bearer(refresh));
            }
        }
    }

    @Test
    void credentialExpiredTriggersReconnectWithFreshCredential() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame(); // the AUTH on this connection
            if (conn.index == 1) {
                conn.send(new EdgeFrame.ErrorClose(ErrorCode.CREDENTIAL_EXPIRED, "session aged out"));
                // close -> the client must reconnect with a fresh credential
            } else {
                conn.send(new EdgeFrame.Heartbeat(0L, 1L));
                conn.parkUntilClosed();
            }
        })) {
            ConfigdClientConfig config = tokenConfig(server.port(), tokens("t1", "t2"));
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
                client.connectAndAuthenticate().get(10, TimeUnit.SECONDS);

                await("client reconnected on CREDENTIAL_EXPIRED", () -> client.reconnectCount() >= 1);
                await("second connection presented the fresh credential", () -> secondAuthIs(server, "t2"));
                assertTrue(server.connectionCount() >= 2, "a fresh connection was opened for re-auth");
                assertFalse(client.terminalFuture().isDone(),
                        "CREDENTIAL_EXPIRED recovered via reconnect — the second connection is healthy, not a give-up");
            }
        }
    }

    @Test
    void noAuthModeSendsNoFrame() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> conn.parkUntilClosed())) {
            ConfigdClientConfig config = ConfigdClientConfig.builder()
                    .endpoint("127.0.0.1", server.port())
                    .allowPlaintext(true)
                    .retryPolicy(fastRetry())
                    .build();
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
                assertEquals(AuthMode.NO_AUTH, client.authMode());
                client.connectAndAuthenticate().get(10, TimeUnit.SECONDS);
                assertEquals(EdgeConnectionState.AUTHENTICATED, client.state());
                // Give the server a moment to have read anything, then assert it saw no AUTH.
                await("connection established", () -> server.connectionCount() == 1);
                assertEquals(0, server.authFrameCount());
            }
        }
    }

    private static ConfigdClientConfig tokenConfig(int port, CredentialSource source) {
        return ConfigdClientConfig.builder()
                .endpoint("127.0.0.1", port)
                .allowPlaintext(true)
                .credentialSource(source)
                .retryPolicy(fastRetry())
                .build();
    }

    private static RetryPolicy fastRetry() {
        return new RetryPolicy(Duration.ofMillis(5), Duration.ofMillis(50), 5);
    }

    /** A bearer source that returns each token once, then repeats the last (deterministic across reconnects). */
    private static CredentialSource tokens(String... seq) {
        AtomicInteger i = new AtomicInteger();
        return CredentialSource.supplier(() -> {
            String t = seq[Math.min(i.getAndIncrement(), seq.length - 1)];
            return new CredentialSource.Provided(new Credential.BearerToken(t), Optional.empty());
        });
    }

    private static String bearer(EdgeFrame frame) {
        Credential c = frame instanceof EdgeFrame.Auth a ? a.credential()
                : ((EdgeFrame.RefreshAuth) frame).credential();
        return ((Credential.BearerToken) c).token();
    }

    private static boolean hasRefresh(MockEdgeServer server) {
        return server.received().stream().anyMatch(f -> f instanceof EdgeFrame.RefreshAuth);
    }

    private static boolean secondAuthIs(MockEdgeServer server, String token) {
        return server.received().stream()
                .filter(f -> f instanceof EdgeFrame.Auth)
                .skip(1)
                .findFirst()
                .map(EdgeConnectionAuthTest::bearer)
                .filter(token::equals)
                .isPresent();
    }

    private static void await(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5); // a poll interval, not synchronization — returns as soon as the condition holds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("timed out awaiting: " + description);
    }
}

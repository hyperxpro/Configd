package io.configd.client.edge;

import io.configd.client.AuthFailedException;
import io.configd.client.ConfigdClientConfig;
import io.configd.client.CredentialSource;
import io.configd.client.RetryPolicy;
import io.configd.client.UnavailableException;
import io.configd.client.edge.session.EdgeConnectionState;
import io.configd.common.auth.Credential;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
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
 * Conformance Runner II — CLIENT-CONFORMS, binary edge auth lifecycle (§03 §4A / §5). Drives the reference
 * {@link ConfigdEdgeClient} against the scriptable {@link MockEdgeServer} (reached via this package) and asserts
 * the wire the client emits and the reaction it takes: the single pre-auth {@code AUTH} is the first routed
 * frame, a rejected credential recovers only via a bounded fresh-connection reconnect (never a hot-loop),
 * business frames are pipelined <b>behind</b> the credential (never before it), renewal is a {@code REFRESH_AUTH}
 * rather than a second {@code AUTH}, and a {@code CREDENTIAL_EXPIRED} close is a reconnect-with-a-fresh-credential
 * signal. Plaintext loopback keeps the auth/framing logic under test without a TLS handshake (transport-agnostic;
 * the §06 F9 TLS cases live in {@code EdgeTlsTest}). The bodies mirror the Gate-1 {@code EdgeConnectionAuthTest}
 * scenarios, re-expressed and clause-tagged for the frozen conformance contract.
 */
@Timeout(30)
class ClauseAuthLifecycleTest {

    @Test
    @Tag("clause:AU4-4")
    void singlePreAuthFrameIsFirstAndReachesAuthenticated() throws Exception {
        // AU4-4: a certificate-less token driver sends EXACTLY ONE AUTH as its first routed frame, then is
        // authenticated (the wire carries no AUTH-OK, so authentication is optimistic-present).
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();                                   // the AUTH
            conn.send(new EdgeFrame.Heartbeat(0L, 1L));         // a positive liveness confirmation
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(tokenConfig(server.port(), tokens("golden")))) {
                assertEquals(AuthMode.TOKEN, client.authMode());
                client.connectAndAuthenticate().get(10, TimeUnit.SECONDS);

                assertEquals(EdgeConnectionState.AUTHENTICATED, client.state());
                await("exactly one AUTH observed", () -> server.authFrameCount() == 1);
                assertInstanceOf(EdgeFrame.Auth.class, server.received().get(0),
                        "the AUTH must be the FIRST routed frame");
                assertEquals("golden", bearer(server.received().get(0)));
                assertEquals(1, server.connectionCount());
            }
        }
    }

    @Test
    @Tag("clause:AU4-4")
    void rejectedCredentialRecoversViaBoundedReconnectNeverHotLoops() throws Exception {
        // AU4-4: a rejected AUTH closes the connection AUTH_FAIL, so a retry costs a FRESH connection — the
        // driver MUST NOT hot-loop AUTH frames on one socket. It sends exactly one AUTH per fresh connection
        // and, because no positive frame ever confirms health, exhausts the bounded budget and gives up.
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();                                   // the AUTH
            conn.send(new EdgeFrame.ErrorClose(ErrorCode.AUTH_FAIL, "bad token"));
        })) {
            RetryPolicy bounded = new RetryPolicy(Duration.ofMillis(5), Duration.ofMillis(20), 2);
            ConfigdClientConfig config = ConfigdClientConfig.builder()
                    .endpoint("127.0.0.1", server.port())
                    .allowPlaintext(true)
                    .credentialSource(tokens("nope"))
                    .retryPolicy(bounded)
                    .build();
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
                client.connectAndAuthenticate().get(10, TimeUnit.SECONDS);

                ExecutionException terminal = assertThrows(ExecutionException.class,
                        () -> client.terminalFuture().get(10, TimeUnit.SECONDS));
                UnavailableException gaveUp = assertInstanceOf(UnavailableException.class, terminal.getCause());
                assertInstanceOf(AuthFailedException.class, gaveUp.getCause());

                await("all bounded attempts made", () -> server.connectionCount() == 3);
                assertEquals(3, server.authFrameCount(),
                        "one AUTH per FRESH connection — never a second AUTH on one socket");
            }
        }
    }

    @Test
    @Tag("clause:AU4-1")
    @Tag("clause:AU4-7")
    void authenticatesBeforeAnyBusinessSubscribeFrame() throws Exception {
        // AU4-1 / AU4-7: authentication precedes ANY data/subscribe frame — the credential is the first routed
        // frame and the SUBSCRIBE follows it; no business frame ever precedes the AUTH.
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();                                   // the AUTH
            conn.send(new EdgeFrame.Heartbeat(0L, 1L));
            conn.parkUntilClosed();                             // reads the SUBSCRIBE that follows
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(tokenConfig(server.port(), tokens("t")))) {
                client.subscribeFullStore(SubscribeOptions.defaults()); // drives connect → AUTH → SUBSCRIBE
                await("the SUBSCRIBE reached the server", () -> hasSubscribe(server));

                List<EdgeFrame> frames = server.received();
                assertInstanceOf(EdgeFrame.Auth.class, frames.get(0), "AUTH is the first routed frame");
                int subIdx = firstIndexOf(frames, EdgeFrame.Subscribe.class);
                assertTrue(subIdx > 0, "the SUBSCRIBE is a LATER frame, never the first");
                assertTrue(frames.subList(0, subIdx).stream().allMatch(f -> f instanceof EdgeFrame.Auth),
                        "no business frame precedes the credential");
            }
        }
    }

    @Test
    @Tag("clause:AU4-5")
    void pipelinesBusinessFrameBehindAuthWithNoAck() throws Exception {
        // AU4-5: because there is no AUTH-OK ack, the driver MAY pipeline its SUBSCRIBE immediately behind the
        // single AUTH without a round-trip. Here the server sends NOTHING — yet the SUBSCRIBE still arrives,
        // proving the client does not wait on an ack it will never receive. The invariant asserted is that the
        // credential is FIRST (a frame before it, or a second AUTH, would be the PROTOCOL_VIOLATION — not the
        // pipelining), exactly as §03 AU4-5 specifies: the PROTOCOL_VIOLATION is an ordering fault (a frame
        // BEFORE the AUTH, a second AUTH, or more than 8 frames pipelined behind it), never the pipeline-behind
        // this exercises. (The investigation flagged a wording drift here; §03 was corrected in Gate 1.)
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();                                   // the AUTH
            conn.parkUntilClosed();                             // reads the pipelined SUBSCRIBE; sends no ack
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(tokenConfig(server.port(), tokens("t")))) {
                client.subscribeFullStore(SubscribeOptions.defaults());
                await("the pipelined SUBSCRIBE reached the server with no server frame in between",
                        () -> hasSubscribe(server));

                List<EdgeFrame> frames = server.received();
                assertInstanceOf(EdgeFrame.Auth.class, frames.get(0), "the credential is the first frame");
                assertEquals(1, server.authFrameCount(), "exactly one pre-auth AUTH — the SUBSCRIBE is behind it");
            }
        }
    }

    @Test
    @Tag("clause:AU4-6")
    void renewsViaRefreshAuthNotASecondAuth() throws Exception {
        // AU4-6: on an already-authenticated connection the driver renews with a REFRESH_AUTH carrying a FRESH
        // credential for the SAME identity — it does NOT re-authenticate with a second AUTH (a stray AUTH would
        // be a PROTOCOL_VIOLATION). Assert the renewal frame is a REFRESH_AUTH and there is still exactly ONE
        // AUTH on the connection.
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();                                   // the AUTH
            conn.send(new EdgeFrame.Heartbeat(0L, 1L));
            conn.parkUntilClosed();                             // reads the REFRESH_AUTH
        })) {
            ConfigdClientConfig config = tokenConfig(server.port(), tokens("auth-1", "refresh-2"));
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
                client.connectAndAuthenticate().get(10, TimeUnit.SECONDS);
                await("initial AUTH seen", () -> server.authFrameCount() == 1);

                client.refreshAuthNow().get(10, TimeUnit.SECONDS);
                await("REFRESH_AUTH seen", () -> hasRefresh(server));

                EdgeFrame refresh = server.received().stream()
                        .filter(f -> f instanceof EdgeFrame.RefreshAuth).findFirst().orElseThrow();
                assertEquals("refresh-2", bearer(refresh), "a fresh credential renewing the same identity");
                assertEquals(1, server.authFrameCount(), "renewal is a REFRESH_AUTH, never a second AUTH");
            }
        }
    }

    @Test
    @Tag("clause:AU5-6")
    void credentialExpiredIsAReconnectWithAFreshCredential() throws Exception {
        // AU5-6: a CREDENTIAL_EXPIRED(13) close is a re-authenticate/reconnect signal — distinct from AUTH_FAIL
        // and from a permanent 403. The driver opens a FRESH connection and presents its next (rotated)
        // credential; the second connection is healthy, so this is a recovery, not a terminal give-up.
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();                                   // the AUTH on this connection
            if (conn.index == 1) {
                conn.send(new EdgeFrame.ErrorClose(ErrorCode.CREDENTIAL_EXPIRED, "session aged out"));
            } else {
                conn.send(new EdgeFrame.Heartbeat(0L, 1L));
                conn.parkUntilClosed();
            }
        })) {
            ConfigdClientConfig config = tokenConfig(server.port(), tokens("t1", "t2"));
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
                client.connectAndAuthenticate().get(10, TimeUnit.SECONDS);

                await("client reconnected on CREDENTIAL_EXPIRED", () -> client.reconnectCount() >= 1);
                await("the fresh connection presented the rotated credential", () -> secondAuthIs(server, "t2"));
                assertTrue(server.connectionCount() >= 2, "a fresh connection was opened for re-auth");
                assertFalse(client.terminalFuture().isDone(),
                        "CREDENTIAL_EXPIRED recovered via reconnect — the second connection is healthy");
            }
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static ConfigdClientConfig tokenConfig(int port, CredentialSource source) {
        return ConfigdClientConfig.builder()
                .endpoint("127.0.0.1", port)
                .allowPlaintext(true)
                .credentialSource(source)
                // These cases exercise the auth lifecycle, not signed-chain verification, so opt out of
                // verification explicitly — subscribe/watch requires a verification mode to be chosen.
                .trustUnverified()
                .retryPolicy(new RetryPolicy(Duration.ofMillis(5), Duration.ofMillis(50), 5))
                .build();
    }

    /** A bearer source returning each token once, then repeating the last (deterministic across reconnects). */
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

    private static boolean hasSubscribe(MockEdgeServer server) {
        return server.received().stream().anyMatch(f -> f instanceof EdgeFrame.Subscribe);
    }

    private static int firstIndexOf(List<EdgeFrame> frames, Class<? extends EdgeFrame> type) {
        for (int i = 0; i < frames.size(); i++) {
            if (type.isInstance(frames.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean secondAuthIs(MockEdgeServer server, String token) {
        return server.received().stream()
                .filter(f -> f instanceof EdgeFrame.Auth)
                .skip(1)
                .findFirst()
                .map(ClauseAuthLifecycleTest::bearer)
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
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("timed out awaiting: " + description);
    }
}

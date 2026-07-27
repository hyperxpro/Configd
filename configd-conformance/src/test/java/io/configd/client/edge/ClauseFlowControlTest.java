package io.configd.client.edge;

import io.configd.client.BadSubscribeException;
import io.configd.client.ConfigdClientConfig;
import io.configd.client.CredentialSource;
import io.configd.client.HostileServerLimits;
import io.configd.client.ProtocolViolationException;
import io.configd.client.QuarantinedException;
import io.configd.client.RetryPolicy;
import io.configd.client.ServerAddress;
import io.configd.client.UnavailableException;
import io.configd.client.edge.session.EdgeConnection;
import io.configd.common.auth.Credential;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.ErrorCode;
import io.configd.distribution.wire.WatchCursor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(30)
class ClauseFlowControlTest {

    @Test
    @Tag("clause:F10-3")
    void demotedToCatchupIsNonFatalTheConnectionSurvives() throws Exception {
        AtomicBoolean caughtUp = new AtomicBoolean(false);
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.send(new EdgeFrame.ErrorClose(ErrorCode.DEMOTED_TO_CATCHUP, "slow reader; switching to catch-up"));
            conn.parkUntilClosed();
        })) {
            EdgeConnection conn = new EdgeConnection(
                    new ServerAddress("127.0.0.1", server.port()), null, HostileServerLimits.defaults(),
                    new InboundFrameHandler() {
                        @Override
                        public void onCatchUp() {
                            caughtUp.set(true);
                        }
                    }, "flowctl-demote-reader");
            conn.connect();
            await("DEMOTED_TO_CATCHUP delivered as the non-fatal onCatchUp mode switch", caughtUp::get);
            assertFalse(conn.closedFuture().isDone(),
                    "DEMOTED_TO_CATCHUP is non-fatal (§07 E3-2) — the connection stays open");
            assertTrue(conn.readerAlive(), "the reader keeps draining after a non-fatal demotion");
            conn.close();
        }
    }

    @Test
    @Tag("clause:F10-3")
    void quarantinedIsAConnectionFatalTeardown() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn ->
                conn.send(new EdgeFrame.ErrorClose(ErrorCode.QUARANTINED, "quarantined; cooldown 30s")))) {
            EdgeConnection conn = new EdgeConnection(
                    new ServerAddress("127.0.0.1", server.port()), null, HostileServerLimits.defaults(),
                    new InboundFrameHandler() {
                    }, "flowctl-quarantine-reader");
            conn.connect();
            ExecutionException ee = assertThrows(ExecutionException.class,
                    () -> conn.closedFuture().get(10, TimeUnit.SECONDS));
            QuarantinedException ex = assertInstanceOf(QuarantinedException.class, ee.getCause());
            assertEquals(Optional.of(ErrorCode.QUARANTINED), ex.edgeCode());
            await("the reader thread stopped after the quarantine teardown", () -> !conn.readerAlive());
        }
    }

    @Test
    @Tag("clause:F10-3")
    void watchEmitsCursorAckAsMandatoryFlowControl() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL))));
            w(conn, event(wid, 0, 7, "k", "v"));
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.key("/k"), WatchOptions.defaults());
                collect(watch);
                watch.awaitCreated(Duration.ofSeconds(10));
                // Flow control is mandatory: after applying the event at S=7 the client must advance and ack
                // its cursor, so the server sees progress and is never tempted to demote it for ack lag.
                await("the client acked its applied cursor (CURSOR_ACK seq=7)", () -> server.received().stream()
                        .anyMatch(f -> f instanceof EdgeFrame.CursorAck ack && ack.seq() == 7L));
            }
        }
    }

    @Test
    @Tag("clause:F10-1b")
    void cursoredWatchCannotShareAConnection() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long w1 = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(w1, List.of(new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL))));
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch host = client.watch(WatchTarget.key("/a"), WatchOptions.defaults());
                host.awaitCreated(Duration.ofSeconds(10));
                // Fan-out is one shared drain per connection: only the first watch's cursor positions it, so
                // a second independently-resumed watch would silently lose every event between its cursor and
                // the live frontier. The client refuses the share loudly rather than dropping the resume.
                IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                        client.watch(WatchTarget.key("/b"),
                                WatchOptions.defaults().resume(WatchCursor.of(0, 5)).shareConnectionOf(host)));
                assertTrue(ex.getMessage().contains("share"), ex.getMessage());
            }
        }
    }

    @Test
    @Tag("clause:F10-1")
    @Tag("clause:F10-1a")
    void reconnectMintsAFreshWatchIdKeepingOnlyTheCursor() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL))));
            if (conn.index == 1) {
                w(conn, new EdgeFrame.WatchCanceled(wid, ErrorCode.GAP_UNRECOVERABLE, null, "cursor too old"));
            } else {
                conn.parkUntilClosed();
            }
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                client.watch(WatchTarget.key("/k"), WatchOptions.defaults().resume(WatchCursor.of(0, 9)));
                await("reconnected + re-CREATEd after GAP_UNRECOVERABLE", () -> server.connectionCount() >= 2);
                await("the reconnect minted a FRESH watch_id (no server-side session token to resume)", () -> {
                    EdgeFrame.WatchCreate first = watchCreate(server, 0);
                    EdgeFrame.WatchCreate second = watchCreate(server, 1);
                    return first != null && second != null && first.watchId() != second.watchId();
                });
            }
        }
    }

    @Test
    @Tag("clause:F10-1c")
    void clientHandlesASnapshotFirstReBootstrap() throws Exception {
        KeyPair leader = StreamFixtures.ed25519();
        var tail = StreamFixtures.signedPut(leader, 5, 6, 1, "c", "3");
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.SubscribeOk(6L, EdgeFrame.Mode.SNAPSHOT_FIRST));
            for (EdgeFrame f : StreamFixtures.snapshotFrames(5, StreamFixtures.entries("a", "1", "b", "2"), 8)) {
                conn.send(f);
            }
            conn.send(StreamFixtures.notify(6, 100, tail));
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(verifyingConfig(server.port(), leader))) {
                Subscription sub = client.subscribeFullStore(SubscribeOptions.defaults());
                long hydratedVersion = sub.awaitHydrated(Duration.ofSeconds(10));
                assertEquals(5L, hydratedVersion, "the client re-bootstrapped from the snapshot (mode=1), not a tail");
                await("then tails forward over the re-bootstrapped base", () -> sub.view().currentVersion() == 6L);
            }
        }
    }

    @Test
    @Tag("clause:F10-1e")
    void tokenEdgeSendsAuthBeforeAnyBusinessFrame() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.Heartbeat(0L, 1L));
            conn.parkUntilClosed();
        })) {
            ConfigdClientConfig config = tokenConfig(server.port(), tokens("golden-token"));
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
                assertEquals(AuthMode.TOKEN, client.authMode());
                client.connectAndAuthenticate().get(10, TimeUnit.SECONDS);
                await("the server received the AUTH", () -> !server.received().isEmpty());
                assertInstanceOf(EdgeFrame.Auth.class, server.received().get(0),
                        "on a token edge the AUTH (0x04) MUST be the first routed frame, before any business frame");
            }
        }
    }

    @Test
    @Tag("clause:F10-1d")
    void firstRoutedFrameIsSentEagerlyOnConnectNotIdled() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL))));
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                // No Flow.Subscriber is attached and no poll is issued: the client MUST still send its first
                // routed frame (the WATCH_CREATE) EAGERLY on connect -- it does not idle a just-opened connection
                // waiting for consumer demand. awaitCreated can only complete once the server has RECEIVED that
                // WATCH_CREATE and replied, so a green awaitCreated with an empty subscriber proves the eager send.
                Watch watch = client.watch(WatchTarget.key("/k"), WatchOptions.defaults());
                watch.awaitCreated(Duration.ofSeconds(10));
                assertFalse(server.received().isEmpty(), "the client sent its first routed frame without idling");
                assertInstanceOf(EdgeFrame.WatchCreate.class, server.received().get(0),
                        "the first routed frame after the handshake is the WATCH_CREATE (F10-1d: do not idle)");
            }
        }
    }

    @Test
    @Tag("clause:F10-2")
    void silentCloseIsRetryableAndDistinctFromAFrameBearingReject() throws Exception {
        // A silent close (accept then close with NO ErrorCode frame) models the pre-handshake session-cap
        // refusal: the client MUST classify it as a routine capacity/transport condition (a retryable
        // UnavailableException, which the reconnect policy retries with backoff), NEVER a ProtocolViolationException.
        try (MockEdgeServer silent = MockEdgeServer.startPlaintext(conn -> {
            // return immediately: the mock closes the socket with no bytes sent -- a code-less disconnect
        })) {
            EdgeConnection conn = new EdgeConnection(
                    new ServerAddress("127.0.0.1", silent.port()), null, HostileServerLimits.defaults(),
                    new InboundFrameHandler() {
                    }, "flowctl-silent-close-reader");
            conn.connect();
            ExecutionException ee = assertThrows(ExecutionException.class,
                    () -> conn.closedFuture().get(10, TimeUnit.SECONDS));
            assertInstanceOf(UnavailableException.class, ee.getCause(),
                    "a silent, code-less close is a retryable capacity/transport condition");
            assertFalse(ee.getCause() instanceof ProtocolViolationException,
                    "a silent close (no frame) is NOT a protocol error — do not fail closed on it");
            await("reader stopped after the retryable disconnect", () -> !conn.readerAlive());
        }

        // Contrast: a frame-bearing per-connection reject (BAD_SUBSCRIBE) is a DISTINCT, code-classified terminal
        // -- the driver must tell the two apart (retry the silent one; do not retry-storm the frame-bearing one).
        try (MockEdgeServer rejecting = MockEdgeServer.startPlaintext(conn ->
                conn.send(new EdgeFrame.ErrorClose(ErrorCode.BAD_SUBSCRIBE, "malformed subscription")))) {
            EdgeConnection conn = new EdgeConnection(
                    new ServerAddress("127.0.0.1", rejecting.port()), null, HostileServerLimits.defaults(),
                    new InboundFrameHandler() {
                    }, "flowctl-bad-subscribe-reader");
            conn.connect();
            ExecutionException ee = assertThrows(ExecutionException.class,
                    () -> conn.closedFuture().get(10, TimeUnit.SECONDS));
            BadSubscribeException cause = assertInstanceOf(BadSubscribeException.class, ee.getCause());
            assertEquals(Optional.of(ErrorCode.BAD_SUBSCRIBE), cause.edgeCode(),
                    "the frame-bearing reject carries its ErrorCode — distinct from the silent, code-less close");
        }
    }

    @Test
    @Tag("clause:F10-4")
    void quarantinedTriggersOwnBoundedBackoffNotAReconnectStorm() throws Exception {
        // The deterministic, non-timing residue asserted here: on a QUARANTINED teardown the client reconnects
        // through its own bounded RetryPolicy. It must not machine-parse the server's cooldown text (the
        // diagnostic is untrusted and never interpreted) and must not instant-reconnect-storm. A server that
        // quarantines on every connection with no positive frame never resets the budget, so the client makes
        // exactly (initial + maxAttempts) attempts and then gives up -- bounded, not an unbounded loop, and the
        // give-up's root cause is the QUARANTINED terminal. The identity-stateful cross-reconnect refusal and
        // the cooldown duration are server-side / timing concerns and are deliberately not asserted here.
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.ErrorClose(ErrorCode.QUARANTINED, "quarantined; cooldown 30s"));
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
                UnavailableException exhausted = assertInstanceOf(UnavailableException.class, terminal.getCause(),
                        "after exhausting its bounded backoff the client gives up — no unbounded storm");
                assertInstanceOf(QuarantinedException.class, exhausted.getCause(),
                        "the give-up's root cause is the QUARANTINED terminal (F10-4, not a generic retryable)");
                await("bounded: initial + maxAttempts(3) reconnects, then stop", () -> server.connectionCount() == 4);
                assertEquals(4, server.connectionCount(), "the client did NOT reconnect-storm past its budget");
                assertEquals(0, client.reconnectCount(), "no QUARANTINED attempt was ever confirmed healthy");
            }
        }
    }


    /** Sends a server-to-client frame on the 0x02 watch wire (the connection the client pinned via WATCH_CREATE). */
    private static void w(MockEdgeServer.Conn conn, EdgeFrame frame) throws IOException {
        conn.send(frame, EdgeFrameCodec.EDGE_WIRE_VERSION_V2);
    }

    private static EdgeFrame event(long watchId, int gid, long s, String key, String value) {
        return new EdgeFrame.WatchEvent(watchId, gid, s, 100L,
                List.of(EdgeFrame.WatchChange.put(key, value.getBytes(StandardCharsets.UTF_8))));
    }

    private static void collect(Watch watch) {
        watch.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(WatchEvent item) {
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onComplete() {
            }
        });
    }

    private static EdgeFrame.WatchCreate watchCreate(MockEdgeServer server, int index) {
        return server.received().stream().filter(f -> f instanceof EdgeFrame.WatchCreate)
                .map(f -> (EdgeFrame.WatchCreate) f).skip(index).findFirst().orElse(null);
    }

    private static ConfigdClientConfig trustedConfig(int port) {
        return ConfigdClientConfig.builder()
                .endpoint("127.0.0.1", port)
                .allowPlaintext(true)
                .trustUnverified()
                .retryPolicy(fastRetry())
                .limits(longIdle())
                .build();
    }

    private static ConfigdClientConfig verifyingConfig(int port, KeyPair leader) {
        return ConfigdClientConfig.builder()
                .endpoint("127.0.0.1", port)
                .allowPlaintext(true)
                .verifyWith(leader.getPublic())
                .retryPolicy(fastRetry())
                .limits(longIdle())
                .build();
    }

    private static ConfigdClientConfig tokenConfig(int port, CredentialSource source) {
        return ConfigdClientConfig.builder()
                .endpoint("127.0.0.1", port)
                .allowPlaintext(true)
                .credentialSource(source)
                .retryPolicy(fastRetry())
                .build();
    }

    /** A bearer source that returns each token once, then repeats the last (deterministic across reconnects). */
    private static CredentialSource tokens(String... seq) {
        AtomicInteger i = new AtomicInteger();
        return CredentialSource.supplier(() -> {
            String t = seq[Math.min(i.getAndIncrement(), seq.length - 1)];
            return new CredentialSource.Provided(new Credential.BearerToken(t), Optional.empty());
        });
    }

    private static RetryPolicy fastRetry() {
        return new RetryPolicy(Duration.ofMillis(5), Duration.ofMillis(50), 5);
    }

    /** A read-idle deadline long enough that the mock's post-hydrate silence does not trip a reconnect. */
    private static HostileServerLimits longIdle() {
        HostileServerLimits d = HostileServerLimits.defaults();
        return new HostileServerLimits(d.maxFrameBytes(), d.connectTimeoutMs(), d.handshakeTimeoutMs(),
                30_000, d.maxSnapshotTotalBytes(), d.maxSnapshotChunks());
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

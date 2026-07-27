package io.configd.client.edge;

import io.configd.client.ConfigdClientConfig;
import io.configd.client.HostileServerLimits;
import io.configd.client.RetryPolicy;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.store.ConfigDelta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Reactor-side (unit) regression protection for the server-side-filtered ({@code 0x03}) forward-only
 * apply on the reference edge client. The live composition proof is
 * {@code EncryptedFilteredSignedEdgeSweepIT} — an {@code *IT} excluded from the Surefire reactor — so without
 * this {@code *Test} the forward-only logic in {@link Subscription} would carry NO CI regression net: a future
 * change could silently reinstate the strict-contiguity gap check and the re-bootstrap storm it caused.
 *
 * <p>On a filtered session the server drops whole out-of-prefix signed deltas and advances a dense covered-S
 * cursor, so the delivered chain is intentionally non-contiguous. The client must relax gap detection to
 * forward-only: a {@code fromVersion} that jumps AHEAD of the applied version is applied (and delivered as a
 * live change event), and only a position that REGRESSES below the applied version is a genuine gap. The
 * relaxation is gated strictly on the {@code SUBSCRIBE_OK filtered} confirm bit, so a classic ({@code 0x01})
 * session keeps the strict check. These four cases pin exactly that contract, driving the real
 * {@link EdgeFrameCodec} in both directions over {@link MockEdgeServer} (every server frame stamped {@code 0x03}
 * so the client's version-pinned reader accepts it and decodes the {@code filtered} confirm).
 */
@Timeout(30)
class EdgeFilteredForwardApplyTest {

    private static final byte V3 = EdgeFrameCodec.EDGE_WIRE_VERSION_V3;

    /**
     * Case 1: on a FILTERED session a forward jump (a delta whose {@code fromVersion} is ahead of the applied
     * version, as an interleaved out-of-prefix drop produces) is applied natively and delivered as exactly ONE
     * live change event — no {@code GapUnrecoverable}, no re-bootstrap, and the view advances to the jump's
     * {@code toVersion}. Under the old strict check this re-bootstrapped; the mock serves no snapshot on
     * reconnect, so a re-bootstrapping client would never surface {@code app/tier} at all.
     */
    @Test
    void filteredForwardJumpIsAppliedLiveNotReBootstrapped() throws Exception {
        KeyPair leader = StreamFixtures.ed25519();
        ConfigDelta d1 = StreamFixtures.signedPut(leader, 0, 1, 1, "app/name", "configd");
        // fromVersion 2 is AHEAD of the applied version 1 — the out-of-prefix delta at v2 was dropped
        // server-side and its position skipped (epoch 2 is likewise skipped: the client never saw it).
        ConfigDelta forwardJump = StreamFixtures.signedPut(leader, 2, 3, 3, "app/tier", "gold");

        // The server must not stream the notifies until the reactive subscriber is attached: with no
        // subscriber the client drops the change feed (the view is the read model), so gating the notifies
        // behind this latch makes "delivered as a change event" deterministic rather than a thread race.
        CountDownLatch subscriberReady = new CountDownLatch(1);
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.SubscribeOk(0L, EdgeFrame.Mode.TAIL, true), V3);
            subscriberReady.await(10, TimeUnit.SECONDS);
            conn.send(StreamFixtures.notify(1, 100, d1), V3);
            conn.send(StreamFixtures.notify(3, 100, forwardJump), V3);
            conn.parkUntilClosed(); // keep conn 1 open so any (unwanted) re-bootstrap is observable as conn 2
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config(server.port(), leader))) {
                Subscription sub = client.subscribePrefixes(List.of("app/"),
                        SubscribeOptions.defaults().withAcceptFiltered(true));
                List<ConfigChange> changes = new CopyOnWriteArrayList<>();
                sub.subscribe(recordingSubscriber(changes));
                sub.awaitHydrated(Duration.ofSeconds(10));
                subscriberReady.countDown();

                await("app/tier arrives as exactly one live change event (forward-applied, not re-hydrated)",
                        () -> count(changes, "app/tier") == 1);
                await("the view advanced to the forward-jump toVersion",
                        () -> sub.view().currentVersion() == 3L);
                await("both in-prefix keys are materialized",
                        () -> viewHas(sub, "app/name", "configd") && viewHas(sub, "app/tier", "gold"));
                // The forward jump was applied in-session: no gap, no reconnect. The mock parks conn 1, so the
                // only path to conn 2 is a client re-bootstrap — which must not have happened.
                assertEquals(1, server.connectionCount(),
                        "a forward jump on a filtered session must not re-bootstrap (no second connection)");
                assertEquals(0, client.reconnectCount(),
                        "a forward jump on a filtered session must not trigger a reconnect");
            }
        }
    }

    /**
     * Case 2: the forward-only relaxation must NOT swallow a genuine backward gap. On a filtered session a delta
     * whose position REGRESSES below the applied version (here {@code fromVersion 3 < applied 5}, while
     * {@code toVersion 7} keeps it out of the stale path) is still a gap → re-bootstrap at cursor 0.
     */
    @Test
    void filteredBackwardRegressionStillGapsAndReBootstraps() throws Exception {
        KeyPair leader = StreamFixtures.ed25519();
        ConfigDelta d1 = StreamFixtures.signedPut(leader, 0, 5, 1, "app/a", "1");
        ConfigDelta backward = StreamFixtures.signedPut(leader, 3, 7, 2, "app/b", "2");
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.SubscribeOk(0L, EdgeFrame.Mode.TAIL, true), V3);
            if (conn.index == 1) {
                conn.send(StreamFixtures.notify(1, 100, d1), V3);
                conn.send(StreamFixtures.notify(2, 100, backward), V3);
            } else {
                conn.parkUntilClosed();
            }
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config(server.port(), leader))) {
                Subscription sub = client.subscribePrefixes(List.of("app/"),
                        SubscribeOptions.defaults().withAcceptFiltered(true));
                sub.awaitHydrated(Duration.ofSeconds(10));
                await("a backward regression on a filtered session still re-bootstraps",
                        () -> server.connectionCount() >= 2);
                await("re-subscribed at cursor 0 (full re-bootstrap)",
                        () -> secondSubscribeResumeCursor(server) == 0L);
            }
        }
    }

    /**
     * Case 3: the same forward-jump shape on an UNFILTERED ({@code 0x01}, 2-arg {@code SUBSCRIBE_OK}) session
     * STILL gaps — proving the relaxation is gated on the {@code filtered} confirm bit, not the wire version.
     * (Overlaps {@code EdgeSubscribeHostileTest.chainGapReBootstrapsAtCursorZero}, which independently proves
     * the classic strict check; kept here so the filtered/unfiltered contrast on the identical delta is a
     * single, self-evident regression net.)
     */
    @Test
    void unfilteredForwardJumpStillGaps() throws Exception {
        KeyPair leader = StreamFixtures.ed25519();
        ConfigDelta d1 = StreamFixtures.signedPut(leader, 0, 1, 1, "app/a", "1");
        ConfigDelta forwardJump = StreamFixtures.signedPut(leader, 2, 3, 3, "app/b", "2");
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.SubscribeOk(1L, EdgeFrame.Mode.TAIL));
            if (conn.index == 1) {
                conn.send(StreamFixtures.notify(1, 100, d1));
                conn.send(StreamFixtures.notify(3, 100, forwardJump));
            } else {
                conn.parkUntilClosed();
            }
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config(server.port(), leader))) {
                Subscription sub = client.subscribeFullStore(SubscribeOptions.defaults());
                sub.awaitHydrated(Duration.ofSeconds(10));
                await("an unfiltered forward jump re-bootstraps (relaxation is gated on filtered)",
                        () -> server.connectionCount() >= 2);
                await("re-subscribed at cursor 0",
                        () -> secondSubscribeResumeCursor(server) == 0L);
            }
        }
    }

    /**
     * Case 4 (spot check): a cursor-advance HEARTBEAT on a filtered session carries the drained-through
     * covered-S in {@code latestSeq}; the client advances its dense covered/resume cursor to it even though
     * only an earlier seq was delivered as data — so a reconnect resumes near head over the filtered skips.
     */
    @Test
    void filteredHeartbeatAdvancesCoveredCursor() throws Exception {
        KeyPair leader = StreamFixtures.ed25519();
        ConfigDelta d1 = StreamFixtures.signedPut(leader, 0, 1, 1, "app/a", "1");
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.SubscribeOk(0L, EdgeFrame.Mode.TAIL, true), V3);
            conn.send(StreamFixtures.notify(1, 100, d1), V3);
            conn.send(new EdgeFrame.Heartbeat(9L, 200L), V3);
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config(server.port(), leader))) {
                Subscription sub = client.subscribePrefixes(List.of("app/"),
                        SubscribeOptions.defaults().withAcceptFiltered(true));
                sub.awaitHydrated(Duration.ofSeconds(10));
                await("the covered-S HEARTBEAT advanced the resume cursor past the filtered skips",
                        () -> sub.cursor() == 9L);
            }
        }
    }

    private static Flow.Subscriber<ConfigChange> recordingSubscriber(List<ConfigChange> sink) {
        return new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ConfigChange item) {
                sink.add(item);
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onComplete() {
            }
        };
    }

    private static int count(List<ConfigChange> changes, String key) {
        int n = 0;
        for (ConfigChange c : changes) {
            if (key.equals(c.key())) {
                n++;
            }
        }
        return n;
    }

    private static boolean viewHas(Subscription sub, String key, String value) {
        return sub.view().get(key)
                .map(v -> value.equals(new String(v, java.nio.charset.StandardCharsets.UTF_8)))
                .orElse(false);
    }

    private static ConfigdClientConfig config(int port, KeyPair leader) {
        return ConfigdClientConfig.builder()
                .endpoint("127.0.0.1", port)
                .allowPlaintext(true)
                .verifyWith(leader.getPublic())
                .retryPolicy(new RetryPolicy(Duration.ofMillis(5), Duration.ofMillis(50), 5))
                .limits(longIdle())
                .build();
    }

    private static HostileServerLimits longIdle() {
        HostileServerLimits d = HostileServerLimits.defaults();
        return new HostileServerLimits(d.maxFrameBytes(), d.connectTimeoutMs(), d.handshakeTimeoutMs(),
                30_000, d.maxSnapshotTotalBytes(), d.maxSnapshotChunks());
    }

    private static long secondSubscribeResumeCursor(MockEdgeServer server) {
        List<EdgeFrame.Subscribe> subs = server.received().stream()
                .filter(f -> f instanceof EdgeFrame.Subscribe)
                .map(f -> (EdgeFrame.Subscribe) f)
                .toList();
        return subs.size() >= 2 ? subs.get(1).resumeCursor() : -1L;
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

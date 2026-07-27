package io.configd.client.edge;

import io.configd.client.ConfigdClientConfig;
import io.configd.client.HostileServerLimits;
import io.configd.client.RetryPolicy;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.store.ConfigDelta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(30)
class EdgeSubscribeHydrateTest {

    @Test
    void fullStoreHydrateViaTailAppliesSignedChain() throws Exception {
        KeyPair leader = StreamFixtures.ed25519();
        ConfigDelta d1 = StreamFixtures.signedPut(leader, 0, 1, 1, "k1", "v1");
        ConfigDelta d2 = StreamFixtures.signedPut(leader, 1, 2, 2, "k2", "v2");
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.SubscribeOk(2L, EdgeFrame.Mode.TAIL));
            conn.send(StreamFixtures.notify(1, 100, d1));
            conn.send(StreamFixtures.notify(2, 100, d2));
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(verifyingConfig(server.port(), leader))) {
                Subscription sub = client.subscribeFullStore(SubscribeOptions.defaults());
                sub.awaitHydrated(Duration.ofSeconds(10));

                await("chain applied to version 2", () -> sub.view().currentVersion() == 2);
                assertArrayEquals("v1".getBytes(StandardCharsets.UTF_8), sub.view().get("k1").orElseThrow());
                assertArrayEquals("v2".getBytes(StandardCharsets.UTF_8), sub.view().get("k2").orElseThrow());
                await("acked the applied cursor", () -> hasCursorAck(server, 2));
            }
        }
    }

    @Test
    void fullStoreHydrateViaSnapshotFirstThenTails() throws Exception {
        KeyPair leader = StreamFixtures.ed25519();
        ConfigDelta tail = StreamFixtures.signedPut(leader, 5, 6, 1, "c", "3");
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
                assertEquals(5L, hydratedVersion, "hydration completes at the snapshot seq");

                await("tail applied to version 6", () -> sub.view().currentVersion() == 6);
                assertArrayEquals("1".getBytes(StandardCharsets.UTF_8), sub.view().get("a").orElseThrow());
                assertArrayEquals("2".getBytes(StandardCharsets.UTF_8), sub.view().get("b").orElseThrow());
                assertArrayEquals("3".getBytes(StandardCharsets.UTF_8), sub.view().get("c").orElseThrow());
            }
        }
    }

    @Test
    void reactiveStreamDeliversChanges() throws Exception {
        KeyPair leader = StreamFixtures.ed25519();
        ConfigDelta d1 = StreamFixtures.signedPut(leader, 0, 1, 1, "k1", "v1");
        ConfigDelta d2 = StreamFixtures.signedPut(leader, 1, 2, 2, "k2", "v2");
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.SubscribeOk(2L, EdgeFrame.Mode.TAIL));
            conn.send(StreamFixtures.notify(1, 100, d1));
            conn.send(StreamFixtures.notify(2, 100, d2));
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(verifyingConfig(server.port(), leader))) {
                Subscription sub = client.subscribeFullStore(SubscribeOptions.defaults());
                List<ConfigChange> received = new CopyOnWriteArrayList<>();
                sub.subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription s) {
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(ConfigChange item) {
                        received.add(item);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                    }

                    @Override
                    public void onComplete() {
                    }
                });
                await("both changes delivered", () -> received.size() == 2);
                assertEquals("k1", received.get(0).key());
                assertEquals("k2", received.get(1).key());
                assertArrayEquals("v2".getBytes(StandardCharsets.UTF_8), received.get(1).value());
            }
        }
    }

    @Test
    void resumeAfterDisconnectReSubscribesAtCursor() throws Exception {
        KeyPair leader = StreamFixtures.ed25519();
        ConfigDelta d1 = StreamFixtures.signedPut(leader, 0, 1, 1, "k1", "v1");
        ConfigDelta d2 = StreamFixtures.signedPut(leader, 1, 2, 2, "k2", "v2");
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();
            if (conn.index == 1) {
                conn.send(new EdgeFrame.SubscribeOk(2L, EdgeFrame.Mode.TAIL));
                conn.send(StreamFixtures.notify(1, 100, d1));
                conn.send(StreamFixtures.notify(2, 100, d2));
                // then drop (the connection closes) — the client must reconnect and resume at cursor 2
            } else {
                conn.send(new EdgeFrame.SubscribeOk(2L, EdgeFrame.Mode.TAIL));
                conn.parkUntilClosed();
            }
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(verifyingConfig(server.port(), leader))) {
                Subscription sub = client.subscribeFullStore(SubscribeOptions.defaults());
                sub.awaitHydrated(Duration.ofSeconds(10));
                await("applied both before the drop", () -> sub.view().currentVersion() == 2);
                await("re-subscribed on a second connection", () -> server.connectionCount() >= 2);
                await("the resume SUBSCRIBE carried cursor 2", () -> secondSubscribeResumeCursor(server) == 2L);
            }
        }
    }

    private static ConfigdClientConfig verifyingConfig(int port, KeyPair leader) {
        return ConfigdClientConfig.builder()
                .endpoint("127.0.0.1", port)
                .allowPlaintext(true)
                .verifyWith(leader.getPublic())
                .retryPolicy(new RetryPolicy(Duration.ofMillis(5), Duration.ofMillis(50), 5))
                .limits(longIdleLimits())
                .build();
    }

    /** A read-idle deadline long enough that the mock's post-hydrate silence does not trip a reconnect. */
    private static HostileServerLimits longIdleLimits() {
        HostileServerLimits d = HostileServerLimits.defaults();
        return new HostileServerLimits(d.maxFrameBytes(), d.connectTimeoutMs(), d.handshakeTimeoutMs(),
                30_000, d.maxSnapshotTotalBytes(), d.maxSnapshotChunks());
    }

    private static boolean hasCursorAck(MockEdgeServer server, long seq) {
        return server.received().stream()
                .anyMatch(f -> f instanceof EdgeFrame.CursorAck ack && ack.seq() == seq);
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

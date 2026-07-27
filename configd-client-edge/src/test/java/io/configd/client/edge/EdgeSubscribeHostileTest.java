package io.configd.client.edge;

import io.configd.client.ChainVerificationException;
import io.configd.client.ConfigdClientConfig;
import io.configd.client.ConfigdException;
import io.configd.client.GapUnrecoverableException;
import io.configd.client.HostileServerLimits;
import io.configd.client.ProtocolViolationException;
import io.configd.client.RetryPolicy;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.EdgeSnapshotCodec;
import io.configd.distribution.wire.ErrorCode;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The client must be as hardened against a hostile <b>server</b> on the read plane as on the connection plane.
 * A crypto-verification failure (unsigned when verifying, bad signature, signed-on-epoch-0, epoch replay) is
 * fail-closed — the connection is torn down with a {@link ChainVerificationException} and the client does NOT
 * reconnect. A chain gap, a truncated snapshot, or a server {@code GAP_UNRECOVERABLE} is recoverable — the
 * client re-bootstraps (reconnect + re-{@code SUBSCRIBE} at cursor 0). A snapshot declaring sizes over the
 * hard caps is a bounded reject.
 */
@Timeout(30)
class EdgeSubscribeHostileTest {

    @Test
    void unsignedDeltaWhenVerifyingIsFailClosed() throws Exception {
        KeyPair leader = StreamFixtures.ed25519();
        ConfigDelta unsigned = StreamFixtures.unsignedPut(0, 1, "k", "v");
        assertFailClosed(leader, conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.SubscribeOk(1L, EdgeFrame.Mode.TAIL));
            conn.send(StreamFixtures.notify(1, 100, unsigned));
        });
    }

    @Test
    void badSignatureIsFailClosed() throws Exception {
        KeyPair leader = StreamFixtures.ed25519();
        KeyPair impostor = StreamFixtures.ed25519();
        ConfigDelta wrongSig = StreamFixtures.signedPut(impostor, 0, 1, 1, "k", "v");
        assertFailClosed(leader, conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.SubscribeOk(1L, EdgeFrame.Mode.TAIL));
            conn.send(StreamFixtures.notify(1, 100, wrongSig));
        });
    }

    @Test
    void signedOnEpochZeroIsFailClosed() throws Exception {
        KeyPair leader = StreamFixtures.ed25519();
        // Sign the legacy (epoch-0, empty-nonce) payload but present it in VERIFY mode: a signature on epoch 0
        // is refused (it would strip the version-position binding).
        ConfigDelta epochZeroSigned = StreamFixtures.signed(leader, 0, 1, 0,
                List.of(new io.configd.store.ConfigMutation.Put("k", "v".getBytes(StandardCharsets.UTF_8))));
        assertFailClosed(leader, conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.SubscribeOk(1L, EdgeFrame.Mode.TAIL));
            conn.send(StreamFixtures.notify(1, 100, epochZeroSigned));
        });
    }

    @Test
    void epochReplayIsFailClosed() throws Exception {
        KeyPair leader = StreamFixtures.ed25519();
        ConfigDelta first = StreamFixtures.signedPut(leader, 0, 1, 5, "k1", "v1");
        ConfigDelta replay = StreamFixtures.signedPut(leader, 1, 2, 5, "k2", "v2");
        assertFailClosed(leader, conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.SubscribeOk(2L, EdgeFrame.Mode.TAIL));
            conn.send(StreamFixtures.notify(1, 100, first));
            conn.send(StreamFixtures.notify(2, 100, replay));
        });
    }

    @Test
    void chainGapReBootstrapsAtCursorZero() throws Exception {
        KeyPair leader = StreamFixtures.ed25519();
        ConfigDelta d1 = StreamFixtures.signedPut(leader, 0, 1, 1, "k1", "v1");
        ConfigDelta gap = StreamFixtures.signedPut(leader, 2, 3, 2, "k3", "v3");
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.SubscribeOk(1L, EdgeFrame.Mode.TAIL));
            if (conn.index == 1) {
                conn.send(StreamFixtures.notify(1, 100, d1));
                conn.send(StreamFixtures.notify(3, 100, gap));
            } else {
                conn.parkUntilClosed();
            }
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config(server.port(), leader))) {
                Subscription sub = client.subscribeFullStore(SubscribeOptions.defaults());
                sub.awaitHydrated(Duration.ofSeconds(10));
                await("reconnected after the gap", () -> server.connectionCount() >= 2);
                await("re-subscribed at cursor 0 (full re-bootstrap)",
                        () -> secondSubscribeResumeCursor(server) == 0L);
            }
        }
    }

    @Test
    void snapshotBeginOverChunkCapIsBoundedReject() throws Exception {
        KeyPair leader = StreamFixtures.ed25519();
        HostileServerLimits limits = HostileServerLimits.defaults();
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.SubscribeOk(5L, EdgeFrame.Mode.SNAPSHOT_FIRST));
            conn.send(new EdgeFrame.SnapshotBegin(5L, limits.maxSnapshotChunks() + 1, 100L));
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config(server.port(), leader))) {
                Subscription sub = client.subscribeFullStore(SubscribeOptions.defaults());
                ExecutionException ee = assertThrows(ExecutionException.class,
                        () -> client.terminalFuture().get(10, TimeUnit.SECONDS));
                assertInstanceOf(ProtocolViolationException.class, ee.getCause());
            }
        }
    }

    @Test
    void truncatedSnapshotDiscardedAndReBootstraps() throws Exception {
        KeyPair leader = StreamFixtures.ed25519();
        HamtMap<String, VersionedValue> data = HamtMap.<String, VersionedValue>empty()
                .put("a", new VersionedValue("1".getBytes(StandardCharsets.UTF_8), 5L, 0L))
                .put("b", new VersionedValue("2".getBytes(StandardCharsets.UTF_8), 5L, 0L));
        byte[] body = EdgeSnapshotCodec.serialize(new ConfigSnapshot(data, 5L, 0L));
        List<EdgeFrame.SnapshotChunk> chunks = EdgeSnapshotCodec.chunk(body, 4);
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.SubscribeOk(5L, EdgeFrame.Mode.SNAPSHOT_FIRST));
            if (conn.index == 1) {
                conn.send(new EdgeFrame.SnapshotBegin(5L, chunks.size() + 1, (long) body.length + 4));
                for (EdgeFrame.SnapshotChunk c : chunks) {
                    conn.send(c);
                }
                conn.send(new EdgeFrame.SnapshotEnd(5L));
            } else {
                conn.parkUntilClosed();
            }
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config(server.port(), leader))) {
                Subscription sub = client.subscribeFullStore(SubscribeOptions.defaults());
                await("reconnected after the truncated snapshot", () -> server.connectionCount() >= 2);
                await("re-subscribed at cursor 0", () -> secondSubscribeResumeCursor(server) == 0L);
            }
        }
    }

    @Test
    void serverGapUnrecoverableReBootstraps() throws Exception {
        KeyPair leader = StreamFixtures.ed25519();
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame();
            conn.send(new EdgeFrame.SubscribeOk(1L, EdgeFrame.Mode.TAIL));
            if (conn.index == 1) {
                conn.send(new EdgeFrame.ErrorClose(ErrorCode.GAP_UNRECOVERABLE, "cursor too old"));
            } else {
                conn.parkUntilClosed();
            }
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config(server.port(), leader))) {
                Subscription sub = client.subscribeFullStore(SubscribeOptions.defaults());
                await("reconnected after server GAP_UNRECOVERABLE", () -> server.connectionCount() >= 2);
                await("re-subscribed at cursor 0", () -> secondSubscribeResumeCursor(server) == 0L);
            }
        }
    }

    @Test
    void monotonicReadGuardRefusesBehindCursor() {
        LocalConfigView view = new LocalConfigView();
        // The view is at version 0; a read with a cursor ahead of it (5) must be refused.
        assertThrows(IllegalStateException.class, () -> view.get("k", 5L));
    }

    private void assertFailClosed(KeyPair leader, MockEdgeServer.Handler script) throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(script)) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config(server.port(), leader))) {
                client.subscribeFullStore(SubscribeOptions.defaults());
                ExecutionException ee = assertThrows(ExecutionException.class,
                        () -> client.terminalFuture().get(10, TimeUnit.SECONDS));
                assertInstanceOf(ChainVerificationException.class, ee.getCause(),
                        "a crypto-verification failure is fail-closed");
                // Fail-closed ⇒ no reconnect: exactly one connection was made.
                await("no reconnect on a verification failure", () -> client.reconnectCount() == 0);
            }
        }
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

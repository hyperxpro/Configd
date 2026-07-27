package io.configd.client.edge;

import io.configd.client.ConfigdClientConfig;
import io.configd.client.ForbiddenException;
import io.configd.client.HostileServerLimits;
import io.configd.client.RetryPolicy;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.ErrorCode;
import io.configd.distribution.wire.WatchCursor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The shared-connection multiplex: two from-now watches ride ONE connection, each
 * demultiplexed by {@code watch_id}; a per-watch terminal ends only that watch while its sibling keeps
 * streaming (the surviving-siblings guarantee a dedicated-per-watch client can never exercise); and a cursored
 * share is refused loudly.
 */
@Timeout(30)
class EdgeWatchMultiplexTest {

    @Test
    void twoFromNowWatchesShareOneConnection() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long w1 = readCreate(conn);
            w(conn, created(w1));
            long w2 = readCreate(conn);
            w(conn, created(w2));
            w(conn, event(w1, 0, 1, "a", "1"));
            w(conn, event(w2, 0, 1, "b", "2"));
            w(conn, event(w1, 0, 2, "a", "3"));
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch host = client.watch(WatchTarget.key("/a"), WatchOptions.defaults());
                Collector c1 = subscribe(host);
                host.awaitCreated(Duration.ofSeconds(10));

                Watch shared = client.watch(WatchTarget.key("/b"), WatchOptions.defaults().shareConnectionOf(host));
                Collector c2 = subscribe(shared);
                shared.awaitCreated(Duration.ofSeconds(10));

                await("host got its two events", () -> c1.events.size() == 2);
                await("sibling got its one event", () -> c2.events.size() == 1);
                assertEquals(1, server.connectionCount(), "both watches rode ONE shared connection (W6-4)");
                assertEquals("b", c2.events.get(0).changes().get(0).key());
            }
        }
    }

    @Test
    void perWatchRejectLeavesSiblingStreaming() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long w1 = readCreate(conn);
            w(conn, created(w1));
            long w2 = readCreate(conn);
            w(conn, created(w2));
            w(conn, event(w2, 0, 1, "b", "1"));
            w(conn, new EdgeFrame.WatchCanceled(w1, ErrorCode.NOT_AUTHORIZED, null, "revoked"));
            w(conn, event(w2, 0, 2, "b", "2"));
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch host = client.watch(WatchTarget.full(), WatchOptions.defaults());
                Collector c1 = subscribe(host);
                host.awaitCreated(Duration.ofSeconds(10));

                Watch sibling = client.watch(WatchTarget.key("/b"), WatchOptions.defaults().shareConnectionOf(host));
                Collector c2 = subscribe(sibling);
                sibling.awaitCreated(Duration.ofSeconds(10));

                ExecutionException ee = assertThrows(ExecutionException.class,
                        () -> host.terminalFuture().get(10, TimeUnit.SECONDS));
                assertInstanceOf(ForbiddenException.class, ee.getCause());
                await("sibling received both events across the host's cancel", () -> c2.events.size() == 2);
                assertEquals(1, server.connectionCount(), "the per-watch reject did NOT tear down the connection");
                assertTrue(sibling.terminalFuture().isDone() == false, "sibling is still live");
            }
        }
    }

    @Test
    void cancelOneSharedWatchLeavesSiblingAndConnection() throws Exception {
        CompletableFuture<Long> canceledId = new CompletableFuture<>();
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long w1 = readCreate(conn);
            w(conn, created(w1));
            long w2 = readCreate(conn);
            w(conn, created(w2));
            EdgeFrame next = conn.readFrame();
            if (next instanceof EdgeFrame.WatchCancel wc) {
                canceledId.complete(wc.watchId());
            }
            w(conn, event(w2, 0, 1, "b", "1"));
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch host = client.watch(WatchTarget.key("/a"), WatchOptions.defaults());
                subscribe(host);
                host.awaitCreated(Duration.ofSeconds(10));
                Watch sibling = client.watch(WatchTarget.key("/b"), WatchOptions.defaults().shareConnectionOf(host));
                Collector c2 = subscribe(sibling);
                sibling.awaitCreated(Duration.ofSeconds(10));

                host.close();
                assertEquals(host.watchId(), canceledId.get(10, TimeUnit.SECONDS));
                await("sibling keeps streaming after the host is canceled", () -> c2.events.size() == 1);
                assertEquals(1, server.connectionCount(), "canceling one shared watch keeps the connection open");
            }
        }
    }

    @Test
    void cursoredWatchCannotShareAConnection() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long w1 = readCreate(conn);
            w(conn, created(w1));
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch host = client.watch(WatchTarget.key("/a"), WatchOptions.defaults());
                host.awaitCreated(Duration.ofSeconds(10));
                IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                        client.watch(WatchTarget.key("/b"),
                                WatchOptions.defaults().resume(WatchCursor.of(0, 5)).shareConnectionOf(host)));
                assertTrue(ex.getMessage().contains("share"), ex.getMessage());
            }
        }
    }

    private static long readCreate(MockEdgeServer.Conn conn) throws IOException {
        return ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
    }

    private static EdgeFrame created(long watchId) {
        return new EdgeFrame.WatchCreated(watchId, List.of(new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL)));
    }

    private static EdgeFrame event(long watchId, int gid, long s, String key, String value) {
        return new EdgeFrame.WatchEvent(watchId, gid, s, 100L,
                List.of(EdgeFrame.WatchChange.put(key, value.getBytes(StandardCharsets.UTF_8))));
    }

    private static void w(MockEdgeServer.Conn conn, EdgeFrame frame) throws IOException {
        conn.send(frame, EdgeFrameCodec.EDGE_WIRE_VERSION_V2);
    }

    private static Collector subscribe(Watch watch) {
        Collector c = new Collector();
        watch.subscribe(c);
        return c;
    }

    private static final class Collector implements Flow.Subscriber<WatchEvent> {
        final List<WatchEvent> events = new CopyOnWriteArrayList<>();
        final CompletableFuture<Throwable> error = new CompletableFuture<>();

        @Override
        public void onSubscribe(Flow.Subscription s) {
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(WatchEvent item) {
            events.add(item);
        }

        @Override
        public void onError(Throwable t) {
            error.complete(t);
        }

        @Override
        public void onComplete() {
            error.complete(null);
        }
    }

    private static ConfigdClientConfig trustedConfig(int port) {
        return ConfigdClientConfig.builder()
                .endpoint("127.0.0.1", port)
                .allowPlaintext(true)
                .trustUnverified()
                .retryPolicy(new RetryPolicy(Duration.ofMillis(5), Duration.ofMillis(50), 5))
                .limits(longIdle())
                .build();
    }

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

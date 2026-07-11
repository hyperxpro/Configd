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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The watch plane over the plaintext loopback mock: WATCH_CREATE→CREATED→EVENT, the multi-shard
 * UNION merge + (gid,S) dedup, WATCH_PROGRESS idle-advance, resume-from-vector, per-(watch,gid) catch-up, the
 * NOT_AUTHORIZED per-watch terminal, and full_chain_verify (NOTIFY verify + local filter).
 */
@Timeout(30)
class EdgeWatchTest {

    @Test
    void singleKeyWatchDeliversEventsAndAdvancesCursor() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL))));
            w(conn, event(wid, 0, 1, "k1", "v1"));
            w(conn, event(wid, 0, 2, "k2", "v2"));
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.key("/k1"), WatchOptions.defaults());
                List<WatchEvent> got = collect(watch);
                watch.awaitCreated(Duration.ofSeconds(10));
                await("two events delivered", () -> got.size() == 2);
                assertEquals(0, got.get(0).gid());
                assertEquals(1L, got.get(0).s());
                assertArrayEquals("v2".getBytes(StandardCharsets.UTF_8), got.get(1).changes().get(0).value());
                await("cursor advanced to (0,2)", () -> componentS(watch.cursor(), 0) == 2L);
            }
        }
    }

    @Test
    void multiShardUnionMergeDedupsByGidAndS() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(
                    new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL),
                    new EdgeFrame.ShardMode(1, 0, EdgeFrame.Mode.TAIL))));
            w(conn, event(wid, 0, 1, "a", "1"));   // shard 0
            w(conn, event(wid, 1, 1, "b", "2"));   // shard 1 (same S=1, different gid ⇒ NOT a dup)
            w(conn, event(wid, 0, 1, "a", "dup")); // S=1 ≤ cursor[0]=1 ⇒ dropped
            w(conn, event(wid, 0, 2, "a", "3"));   // advances shard 0
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.prefix("/"), WatchOptions.defaults());
                List<WatchEvent> got = collect(watch);
                watch.awaitCreated(Duration.ofSeconds(10));
                await("three events (the (0,1) dup dropped)", () -> got.size() == 3);
                assertEquals(3, got.size(), "the duplicate (gid=0,S=1) was deduped");
                await("cursor (0,2) (1,1)", () -> componentS(watch.cursor(), 0) == 2L
                        && componentS(watch.cursor(), 1) == 1L);
                // The same-key events are same-gid ⇒ ordered; the cross-gid pair is concurrent.
                assertTrue(WatchEvent.ordered(got.get(0), got.get(2))); // gid0 s1 before gid0 s2
                assertFalse(WatchEvent.ordered(got.get(0), got.get(1))); // gid0 vs gid1 ⇒ concurrent
            }
        }
    }

    @Test
    void watchProgressAdvancesIdleCursorComponents() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(
                    new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL),
                    new EdgeFrame.ShardMode(1, 0, EdgeFrame.Mode.TAIL))));
            w(conn, new EdgeFrame.WatchProgress(wid,
                    new WatchCursor(List.of(new WatchCursor.Component(0, 5), new WatchCursor.Component(1, 3))), 111L));
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.prefix("/"), WatchOptions.defaults());
                watch.awaitCreated(Duration.ofSeconds(10));
                await("idle cursors advanced via the bookmark",
                        () -> componentS(watch.cursor(), 0) == 5L && componentS(watch.cursor(), 1) == 3L);
            }
        }
    }

    @Test
    void resumeSendsTheSavedCursorVector() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(new EdgeFrame.ShardMode(0, 7, EdgeFrame.Mode.TAIL))));
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.key("/k"),
                        WatchOptions.defaults().resume(WatchCursor.of(0, 7)));
                watch.awaitCreated(Duration.ofSeconds(10));
                await("the WATCH_CREATE carried the resume vector (0,7)", () -> {
                    EdgeFrame.WatchCreate c = firstWatchCreate(server);
                    return c != null && componentS(c.cursor(), 0) == 7L;
                });
            }
        }
    }

    @Test
    void perShardCatchUpSnapshotHydratesThenTails() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid,
                    List.of(new EdgeFrame.ShardMode(0, 3, EdgeFrame.Mode.SNAPSHOT_FIRST))));
            for (EdgeFrame f : StreamFixtures.watchSnapshotFrames(wid, 0, 3,
                    StreamFixtures.entries("a", "1", "b", "2"), 8)) {
                w(conn, f);
            }
            w(conn, event(wid, 0, 4, "c", "3")); // tail after the snapshot cutover
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.prefix("/").with(WatchTarget.Flag.WITH_INITIAL_SNAPSHOT),
                        WatchOptions.defaults());
                List<WatchEvent> got = collect(watch);
                watch.awaitCreated(Duration.ofSeconds(10));
                await("snapshot entries + tail delivered", () -> countChanges(got) >= 3);
                await("cursor advanced to the tail (0,4)", () -> componentS(watch.cursor(), 0) == 4L);
            }
        }
    }

    @Test
    void notAuthorizedIsPerWatchTerminalNoReconnect() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCanceled(wid, ErrorCode.NOT_AUTHORIZED, null, "over-broad target"));
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.full(), WatchOptions.defaults());
                ExecutionException ee = assertThrows(ExecutionException.class,
                        () -> watch.terminalFuture().get(10, TimeUnit.SECONDS));
                assertInstanceOf(ForbiddenException.class, ee.getCause());
                assertEquals(1, server.connectionCount(), "a 403 watch reject is terminal — no reconnect");
            }
        }
    }

    @Test
    void gapUnrecoverableReBootstrapsTheWatch() throws Exception {
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
                Watch watch = client.watch(WatchTarget.key("/k"),
                        WatchOptions.defaults().resume(WatchCursor.of(0, 9)));
                await("reconnected after GAP_UNRECOVERABLE", () -> server.connectionCount() >= 2);
                await("re-created with a from-now + snapshot re-bootstrap (cursor 0)", () -> {
                    EdgeFrame.WatchCreate second = secondWatchCreate(server);
                    return second != null && second.cursor().isFromNow() && second.withInitialSnapshot();
                });
                // A fresh watch_id is minted per (re)create — never reused across the reconnect.
                assertTrue(firstWatchCreate(server).watchId() != secondWatchCreate(server).watchId());
            }
        }
    }

    @Test
    void badSubscribeRejectIsPerWatchTerminalNoReconnect() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCanceled(wid, ErrorCode.BAD_SUBSCRIBE, null, "malformed target"));
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.key("/k"), WatchOptions.defaults());
                assertThrows(ExecutionException.class, () -> watch.terminalFuture().get(10, TimeUnit.SECONDS));
                assertEquals(1, server.connectionCount(), "a malformed-target reject is terminal — no retry storm");
            }
        }
    }

    @Test
    void midStreamRevocationTearsDownTheWatch() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL))));
            w(conn, event(wid, 0, 1, "k", "v"));
            w(conn, new EdgeFrame.WatchCanceled(wid, ErrorCode.NOT_AUTHORIZED, null, "policy revoked mid-stream"));
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.key("/k"), WatchOptions.defaults());
                List<WatchEvent> got = collect(watch);
                watch.awaitCreated(Duration.ofSeconds(10));
                await("the pre-revocation event was delivered", () -> got.size() == 1);
                ExecutionException ee = assertThrows(ExecutionException.class,
                        () -> watch.terminalFuture().get(10, TimeUnit.SECONDS));
                assertInstanceOf(ForbiddenException.class, ee.getCause());
            }
        }
    }

    @Test
    void blockingPollFacadeDeliversEvents() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL))));
            w(conn, event(wid, 0, 1, "k", "v"));
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.key("/k"), WatchOptions.defaults());
                watch.awaitCreated(Duration.ofSeconds(10));
                WatchEvent e = watch.poll(Duration.ofSeconds(10)); // no reactive subscriber — blocking facade
                assertEquals(1L, e.s());
                assertEquals("k", e.changes().get(0).key());
                await("cursor advanced via the blocking path", () -> componentS(watch.cursor(), 0) == 1L);
            }
        }
    }

    @Test
    void fullChainVerifyVerifiesAndFiltersLocally() throws Exception {
        java.security.KeyPair leader = StreamFixtures.ed25519();
        io.configd.store.ConfigDelta matching = StreamFixtures.signedPut(leader, 0, 1, 1, "/watched", "yes");
        io.configd.store.ConfigDelta other = StreamFixtures.signedPut(leader, 1, 2, 2, "/other", "no");
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL))));
            w(conn, StreamFixtures.notify(1, 100, matching)); // verbatim signed chain
            w(conn, StreamFixtures.notify(2, 100, other));    // filtered out locally (not under /watched)
            conn.parkUntilClosed();
        })) {
            ConfigdClientConfig config = ConfigdClientConfig.builder()
                    .endpoint("127.0.0.1", server.port())
                    .allowPlaintext(true)
                    .verifyWith(leader.getPublic())
                    .retryPolicy(fastRetry())
                    .limits(longIdle())
                    .build();
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
                Watch watch = client.watch(WatchTarget.key("/watched").with(WatchTarget.Flag.FULL_CHAIN_VERIFY),
                        WatchOptions.defaults());
                List<WatchEvent> got = collect(watch);
                watch.awaitCreated(Duration.ofSeconds(10));
                await("only the matching, verified change delivered", () -> countChanges(got) == 1);
                assertEquals("/watched", got.get(0).changes().get(0).key());
            }
        }
    }

    private static EdgeFrame event(long watchId, int gid, long s, String key, String value) {
        return new EdgeFrame.WatchEvent(watchId, gid, s, 100L,
                List.of(EdgeFrame.WatchChange.put(key, value.getBytes(StandardCharsets.UTF_8))));
    }

    /** Sends a server→client frame on the 0x02 watch wire (the connection the client pinned via WATCH_CREATE). */
    private static void w(MockEdgeServer.Conn conn, EdgeFrame frame) throws IOException {
        conn.send(frame, EdgeFrameCodec.EDGE_WIRE_VERSION_V2);
    }

    private static List<WatchEvent> collect(Watch watch) {
        List<WatchEvent> got = new CopyOnWriteArrayList<>();
        watch.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(WatchEvent item) {
                got.add(item);
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onComplete() {
            }
        });
        return got;
    }

    private static int countChanges(List<WatchEvent> events) {
        return events.stream().mapToInt(e -> e.changes().size()).sum();
    }

    private static long componentS(WatchCursor cursor, int gid) {
        return cursor.components().stream().filter(c -> c.gid() == gid).mapToLong(WatchCursor.Component::s)
                .findFirst().orElse(-1L);
    }

    private static EdgeFrame.WatchCreate firstWatchCreate(MockEdgeServer server) {
        return server.received().stream().filter(f -> f instanceof EdgeFrame.WatchCreate)
                .map(f -> (EdgeFrame.WatchCreate) f).findFirst().orElse(null);
    }

    private static EdgeFrame.WatchCreate secondWatchCreate(MockEdgeServer server) {
        return server.received().stream().filter(f -> f instanceof EdgeFrame.WatchCreate)
                .map(f -> (EdgeFrame.WatchCreate) f).skip(1).findFirst().orElse(null);
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

    private static RetryPolicy fastRetry() {
        return new RetryPolicy(Duration.ofMillis(5), Duration.ofMillis(50), 5);
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

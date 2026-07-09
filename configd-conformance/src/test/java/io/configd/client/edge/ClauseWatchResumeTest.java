package io.configd.client.edge;

import io.configd.client.ConfigdClientConfig;
import io.configd.client.HostileServerLimits;
import io.configd.client.RetryPolicy;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.ErrorCode;
import io.configd.distribution.wire.WatchCursor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CLIENT-CONFORMS, watch resume + catch-up (§02 W): the reference {@link Watch} against the scriptable
 * {@link MockEdgeServer} for the per-connection {@code 0x02} negotiation, resume-by-re-sending-the-vector, the
 * {@code WATCH_CREATED} live-signal + per-shard mode vector, the per-{@code (watch_id, gid)} inline catch-up
 * snapshot (others keep streaming), the {@code WATCH_CREATE} flag bits, the {@code GAP_UNRECOVERABLE}
 * re-bootstrap (has_oldest=0, fresh non-reused watch_id), and the {@code WATCH_CREATE}-vs-{@code SUBSCRIBE}
 * distinction. Bodies re-express the already-green Gate-3 scenarios with genuine per-clause assertions.
 */
@Timeout(30)
class ClauseWatchResumeTest {

    @Test
    @Tag("clause:W1-3")
    @Tag("clause:W5-11")
    void watchConnectionNegotiates0x02PerConnectionEndToEnd() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL))));
            w(conn, event(wid, 0, 1, "k", "v"));
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.key("/k"), WatchOptions.defaults());
                List<WatchEvent> got = collect(watch);
                watch.awaitCreated(Duration.ofSeconds(10));
                // The WATCH_* frames exist ONLY on edge wire 0x02 (W1-2). The client pinned 0x02 for this
                // connection (design-A, first-frame-pinned) and ACCEPTS the server's 0x02-stamped frames — the
                // successful end-to-end exchange is the proof the per-connection 0x02 negotiation is live
                // (W1-3 / W5-11). A 0x01-pinned reader would fail closed on these frames.
                await("0x02 server watch frame accepted and delivered", () -> got.size() == 1);
                assertInstanceOf(EdgeFrame.WatchCreate.class, firstWatchCreate(server),
                        "the client sent a WATCH_CREATE — a frame type that exists only at wire version 0x02");
            }
        }
    }

    @Test
    @Tag("clause:W2-6")
    void resumeReSendsTheSavedCursorVectorOnAFreshWatchCreate() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(new EdgeFrame.ShardMode(0, 7, EdgeFrame.Mode.TAIL))));
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.key("/k"),
                        WatchOptions.defaults().resume(WatchCursor.of(0, 7)));
                watch.awaitCreated(Duration.ofSeconds(10));
                // Resume is just a WATCH_CREATE carrying the saved cursor vector — there is no separate resume
                // frame (W2-6 / W5-4). The re-sent vector reproduces the client's held (0,7) position.
                await("the WATCH_CREATE carried the resume vector (0,7)", () -> {
                    EdgeFrame.WatchCreate c = firstWatchCreate(server);
                    return c != null && componentS(c.cursor(), 0) == 7L;
                });
            }
        }
    }

    @Test
    @Tag("clause:W5-5")
    @Tag("clause:W5-1")
    void watchCreatedIsTheLiveSignalCarryingThePerShardModeVector() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            // The first (and only) server frame: a multi-shard WATCH_CREATED (0x0C) carrying a per-shard vector.
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(
                    new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL),
                    new EdgeFrame.ShardMode(1, 0, EdgeFrame.Mode.TAIL))));
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.prefix("/"), WatchOptions.defaults());
                collect(watch);
                // awaitCreated completes ONLY on WATCH_CREATED — the "authorized and live" signal (W5-5).
                watch.awaitCreated(Duration.ofSeconds(10));
                // The per-shard mode vector was consumed: the client recorded BOTH covered shards' components.
                await("both covered shards recorded from the CREATED mode vector",
                        () -> componentS(watch.cursor(), 0) >= 0L && componentS(watch.cursor(), 1) >= 0L);
                assertInstanceOf(EdgeFrame.WatchCreate.class, firstWatchCreate(server)); // CREATE (0x0A) → CREATED (0x0C)
            }
        }
    }

    @Test
    @Tag("clause:W5-3")
    @Tag("clause:W5-10")
    @Tag("clause:W6-3")
    void perShardInlineCatchUpTaggedByWatchIdGidWhileOtherShardKeepsStreaming() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            // shard 0 is behind ⇒ SNAPSHOT_FIRST (a catch-up substream follows); shard 1 tails from the start.
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(
                    new EdgeFrame.ShardMode(0, 3, EdgeFrame.Mode.SNAPSHOT_FIRST),
                    new EdgeFrame.ShardMode(1, 0, EdgeFrame.Mode.TAIL))));
            List<EdgeFrame> snap = StreamFixtures.watchSnapshotFrames(wid, 0, 3,
                    StreamFixtures.entries("a", "1", "b", "2"), 8); // BEGIN/CHUNK*/END tagged (watch_id, gid=0)
            w(conn, snap.get(0));                       // WATCH_SNAPSHOT_BEGIN (gid 0)
            w(conn, event(wid, 1, 1, "c", "3"));        // shard 1 KEEPS STREAMING during shard 0's resync (W6-3)
            for (int i = 1; i < snap.size(); i++) {
                w(conn, snap.get(i));                   // CHUNK…END — cutover only after END (W5-10)
            }
            w(conn, event(wid, 0, 4, "d", "4"));        // shard 0 tails after the snapshot cutover
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.prefix("/").with(WatchTarget.Flag.WITH_INITIAL_SNAPSHOT),
                        WatchOptions.defaults());
                List<WatchEvent> got = collect(watch);
                watch.awaitCreated(Duration.ofSeconds(10));
                // The (watch_id, gid=0) substream hydrated shard 0 (2 entries) then it tailed to S=4; shard 1's
                // event was delivered independently — only the lagging shard snapshotted, the other kept streaming.
                await("snapshot entries + both shards' tail delivered", () -> countChanges(got) >= 4);
                await("shard 0 cursor set to the snapshot then tailed to (0,4)", () -> componentS(watch.cursor(), 0) == 4L);
                await("shard 1 kept streaming to (1,1)", () -> componentS(watch.cursor(), 1) == 1L);
                assertFalse(watch.terminalFuture().isDone(), "a per-shard resync MUST NOT tear down the whole watch");
            }
        }
    }

    @Test
    @Tag("clause:W5-4a")
    void watchCreateFlagsByteEncodesTheThreeRequestBits() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.readFrame(); // record the WATCH_CREATE; no reply needed — we inspect the outbound flags byte
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                // A with_initial_snapshot request sets bit2 on the wire, and only bit2.
                client.watch(WatchTarget.prefix("/").with(WatchTarget.Flag.WITH_INITIAL_SNAPSHOT), WatchOptions.defaults());
                await("the WATCH_CREATE reached the server", () -> !server.received().isEmpty());
                EdgeFrame.WatchCreate c = firstWatchCreate(server);
                assertTrue(c.withInitialSnapshot(), "bit2 = with_initial_snapshot");
                assertFalse(c.fullChainVerify(), "bit0 unset");
                assertFalse(c.prevValue(), "bit1 unset");
            }
        }
        // The flag bits map exactly onto the WATCH_CREATE flags byte the driver emits — flagBits() IS that byte
        // (WatchSession builds the frame flags from target.flagBits()); this is the W5-4a bit0/bit1/bit2 contract.
        assertEquals(0, WatchTarget.key("/k").flagBits());
        assertEquals(EdgeFrame.WATCH_FLAG_FULL_CHAIN_VERIFY,
                WatchTarget.key("/k").with(WatchTarget.Flag.FULL_CHAIN_VERIFY).flagBits());
        assertEquals(EdgeFrame.WATCH_FLAG_PREV_VALUE,
                WatchTarget.key("/k").with(WatchTarget.Flag.PREV_VALUE).flagBits());
        assertEquals(EdgeFrame.WATCH_FLAG_WITH_INITIAL_SNAPSHOT,
                WatchTarget.key("/k").with(WatchTarget.Flag.WITH_INITIAL_SNAPSHOT).flagBits());
        // Two flags OR into one byte (bit0 | bit1).
        assertEquals(EdgeFrame.WATCH_FLAG_FULL_CHAIN_VERIFY | EdgeFrame.WATCH_FLAG_PREV_VALUE,
                WatchTarget.key("/k").with(WatchTarget.Flag.FULL_CHAIN_VERIFY, WatchTarget.Flag.PREV_VALUE).flagBits());
    }

    @Test
    @Tag("clause:W5-9a")
    @Tag("clause:W2-8")
    void gapUnrecoverableHasNoOldestVectorAndDriverReBootstrapsWithAFreshWatchId() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL))));
            if (conn.index == 1) {
                // v1 deferral (W5-9a): the server sends GAP_UNRECOVERABLE with oldest == null, i.e. has_oldest = 0
                // — NOT a per-shard oldest vector. The driver MUST recover WITHOUT depending on a server oldest.
                w(conn, new EdgeFrame.WatchCanceled(wid, ErrorCode.GAP_UNRECOVERABLE, null, "cursor too old"));
            } else {
                conn.parkUntilClosed();
            }
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.key("/k"),
                        WatchOptions.defaults().resume(WatchCursor.of(0, 9)));
                await("reconnected after GAP_UNRECOVERABLE", () -> server.connectionCount() >= 2);
                // The driver did NOT depend on a server-supplied oldest (there is none): it dropped its cursor and
                // re-created from-now with a fresh initial-snapshot re-bootstrap (W5-9a recovery).
                await("re-created from-now + with_initial_snapshot", () -> {
                    EdgeFrame.WatchCreate second = secondWatchCreate(server);
                    return second != null && second.cursor().isFromNow() && second.withInitialSnapshot();
                });
                // A fresh watch_id is minted per (re)create — NEVER reused across the reconnect (W2-8).
                assertTrue(firstWatchCreate(server).watchId() != secondWatchCreate(server).watchId(),
                        "the re-created watch MUST use a fresh watch_id, never the burned one");
            }
        }
    }

    @Test
    @Tag("clause:W5-12")
    void aWatchUsesWatchCreateWithAVectorCursorNotAScalarSubscribe() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(new EdgeFrame.ShardMode(0, 5, EdgeFrame.Mode.TAIL))));
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.key("/k"),
                        WatchOptions.defaults().resume(WatchCursor.of(0, 5)));
                watch.awaitCreated(Duration.ofSeconds(10));
                // The client-facing watch is a per-watch WATCH_CREATE (watch_id + cursor VECTOR), distinct from the
                // connection-level SUBSCRIBE (a scalar resumeCursor). The client MUST keep them distinct (W5-12).
                EdgeFrame.WatchCreate c = firstWatchCreate(server);
                assertInstanceOf(EdgeFrame.WatchCreate.class, c);
                assertTrue(server.received().stream().noneMatch(f -> f instanceof EdgeFrame.Subscribe),
                        "a watch does NOT open a connection-level SUBSCRIBE");
                // The per-watch cursor is a VECTOR (a list of (gid, S) components), not a scalar resumeCursor.
                assertEquals(1, c.cursor().components().size(), "the watch cursor is a one-element vector at N=1");
                assertEquals(5L, componentS(c.cursor(), 0));
            }
        }
    }

    // -----------------------------------------------------------------------

    private static EdgeFrame event(long watchId, int gid, long s, String key, String value) {
        return new EdgeFrame.WatchEvent(watchId, gid, s, 100L,
                List.of(EdgeFrame.WatchChange.put(key, value.getBytes(StandardCharsets.UTF_8))));
    }

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
                .map(f -> (EdgeFrame.WatchCreate) f).findFirst().orElseThrow();
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

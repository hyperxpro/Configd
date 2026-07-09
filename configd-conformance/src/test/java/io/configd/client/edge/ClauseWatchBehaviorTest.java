package io.configd.client.edge;

import io.configd.client.ConfigdClientConfig;
import io.configd.client.ForbiddenException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CLIENT-CONFORMS, watch behavior (§02 W): the reference {@link Watch} against the scriptable
 * {@link MockEdgeServer} on the {@code 0x02} plane — the delivery behaviors (persistence, the UNION merge +
 * {@code (gid,S)} dedup, per-key/per-shard order, bookmark idle-advance + no-regress, batch-atomic events with a
 * signed {@code val_len} DELETE, {@code commit_ts}-is-freshness-not-cursor, {@code full_chain_verify} local
 * verify+filter, the {@code NOT_AUTHORIZED} per-watch terminal, client-initiated cancel, and fail-closed on an
 * un-negotiated extension). The bodies re-express the already-green Gate-3 {@code EdgeWatchTest} scenarios with
 * genuine per-clause assertions; the resume/catch-up half lives in {@link ClauseWatchResumeTest}.
 */
@Timeout(30)
class ClauseWatchBehaviorTest {

    @Test
    @Tag("clause:W2-1..W2-4")
    @Tag("clause:W2-5")
    @Tag("clause:W4-1..W4-5")
    void keyWatchIsPersistentSingleShardAndTargetsAreCanonical() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL))));
            w(conn, event(wid, 0, 1, "k1", "v1"));
            w(conn, event(wid, 0, 2, "k1", "v2")); // a SECOND change on the same key ⇒ the watch is persistent
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.key("/k1"), WatchOptions.defaults());
                List<WatchEvent> got = collect(watch);
                watch.awaitCreated(Duration.ofSeconds(10));
                // Persistent (fires repeatedly, not one-shot; W2-5): two events from one WATCH_CREATE.
                await("two events delivered (persistent)", () -> got.size() == 2);
                // KEY ⇒ exactly ONE shard/component (W4-1); the cursor is a one-element vector even at N=1.
                await("cursor advanced to (0,2)", () -> componentS(watch.cursor(), 0) == 2L);
                assertEquals(1, watch.cursor().components().size(), "a KEY watch addresses exactly one shard");
                // The WATCH_CREATE the client sent carried the KEY target kind + the canonical path + from-now (W2-2/W2-4).
                EdgeFrame.WatchCreate c = firstWatchCreate(server);
                assertEquals(EdgeFrame.WATCH_TARGET_KEY, c.targetKind());
                assertArrayEquals("/k1".getBytes(StandardCharsets.UTF_8), c.path());
                assertTrue(c.cursor().isFromNow(), "a fresh watch is from-now per shard (W3-4)");
            }
        }
        // The three target forms map to the three target_kind bytes (W2-2); FULL carries an empty path.
        assertEquals(EdgeFrame.WATCH_TARGET_KEY, WatchTarget.key("/a").targetKindByte());
        assertEquals(EdgeFrame.WATCH_TARGET_PREFIX, WatchTarget.prefix("/a/").targetKindByte());
        assertEquals(EdgeFrame.WATCH_TARGET_FULL, WatchTarget.full().targetKindByte());
        assertTrue(WatchTarget.full().path().isEmpty());
        // A non-conforming target is rejected CLIENT-SIDE, before the wire (W2-4): relative + over-long.
        assertThrows(IllegalArgumentException.class, () -> WatchTarget.key("relative"));
        assertThrows(IllegalArgumentException.class, () -> WatchTarget.prefix("/" + "a".repeat(1100)));
    }

    @Test
    @Tag("clause:W4-1..W4-5")
    @Tag("clause:W6-1")
    void multiShardUnionMergeDedupsByGidAndSAndPresentsPerShardOrderOnly() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(
                    new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL),
                    new EdgeFrame.ShardMode(1, 0, EdgeFrame.Mode.TAIL)))); // PREFIX scatters across shards (W2-3/W4-1)
            w(conn, event(wid, 0, 1, "a", "1"));   // shard 0
            w(conn, event(wid, 1, 1, "b", "2"));   // shard 1 (same S=1, different gid ⇒ NOT a dup)
            w(conn, event(wid, 0, 1, "a", "dup")); // S=1 ≤ cursor[0]=1 ⇒ dropped (W6-1 dedup)
            w(conn, event(wid, 0, 2, "a", "3"));   // advances shard 0
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.prefix("/"), WatchOptions.defaults());
                List<WatchEvent> got = collect(watch);
                watch.awaitCreated(Duration.ofSeconds(10));
                // The redelivered (gid=0, S=1) is dropped: drop iff S ≤ cursor[gid] (W6-1 at-least-once dedup).
                await("three events (the (0,1) dup dropped)", () -> got.size() == 3);
                assertEquals(3, got.size());
                await("cursor (0,2) (1,1) — a UNION-merged per-shard vector",
                        () -> componentS(watch.cursor(), 0) == 2L && componentS(watch.cursor(), 1) == 1L);
                // Per-shard order only (W6-2a): same-gid events are ORDERED by ascending S; cross-gid is CONCURRENT.
                assertTrue(WatchEvent.ordered(got.get(0), got.get(2)), "gid0 s1 precedes gid0 s2");
                assertFalse(WatchEvent.ordered(got.get(0), got.get(1)), "gid0 vs gid1 ⇒ concurrent, never ordered");
            }
        }
    }

    @Test
    @Tag("clause:W5-7")
    @Tag("clause:W6-5")
    @Tag("clause:W4-1..W4-5")
    void watchProgressBookmarkAdvancesIdleCursorWithoutRegressing() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(
                    new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL),
                    new EdgeFrame.ShardMode(1, 0, EdgeFrame.Mode.TAIL))));
            w(conn, event(wid, 0, 5, "k", "v")); // cursor[0] → 5 from a delivered event
            // One bookmark carries BOTH a lower component for gid0 (must NOT regress) and an advancing one for gid1.
            w(conn, new EdgeFrame.WatchProgress(wid,
                    new WatchCursor(List.of(new WatchCursor.Component(0, 3), new WatchCursor.Component(1, 7))), 999L));
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.prefix("/"), WatchOptions.defaults());
                collect(watch);
                watch.awaitCreated(Duration.ofSeconds(10));
                // Idle shard-1 advances over the bookmark with NO events (W5-7 / W4-4 idle-advance).
                await("idle cursor component advanced via the bookmark", () -> componentS(watch.cursor(), 1) == 7L);
                // Once the bookmark is fully processed (gid1==7), gid0 must still be 5, never regressed to 3
                // (component-wise max-merge; W6-5 never-regress).
                assertEquals(5L, componentS(watch.cursor(), 0), "a lower bookmark component MUST NOT regress the cursor");
            }
        }
    }

    @Test
    @Tag("clause:W5-6")
    void oneWatchEventIsBatchAtomicWithSignedDeleteLenAndAscendingS() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL))));
            // One shard-commit with TWO matching changes (a PUT + a DELETE) — delivered as ONE atomic event.
            w(conn, new EdgeFrame.WatchEvent(wid, 0, 1, 100L, List.of(
                    EdgeFrame.WatchChange.put("k1", "v1".getBytes(StandardCharsets.UTF_8)),
                    EdgeFrame.WatchChange.delete("k2"))));      // DELETE ⇒ val_len == -1 (the sole signed width)
            w(conn, new EdgeFrame.WatchEvent(wid, 0, 2, 100L, List.of(
                    EdgeFrame.WatchChange.put("k3", "v3".getBytes(StandardCharsets.UTF_8))))); // next commit, ascending S
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.prefix("/"), WatchOptions.defaults());
                List<WatchEvent> got = collect(watch);
                watch.awaitCreated(Duration.ofSeconds(10));
                // Two commits ⇒ exactly two events (never split, never coalesced; W5-6).
                await("two events (one per shard-commit)", () -> got.size() == 2);
                // Batch-atomic: both keys of the one BATCH commit arrive together in a single event.
                assertEquals(2, got.get(0).changes().size(), "one shard-commit's matching keys arrive together");
                ConfigChange put = got.get(0).changes().get(0);
                ConfigChange del = got.get(0).changes().get(1);
                assertEquals(ConfigChange.Kind.PUT, put.kind());
                assertArrayEquals("v1".getBytes(StandardCharsets.UTF_8), put.value());
                assertTrue(del.isDelete(), "a DELETE change (val_len == -1) surfaces as Kind.DELETE");
                assertNull(del.value(), "a DELETE carries no value");
                // Ascending S within (watch, gid): the two events are ORDERED by S.
                assertTrue(WatchEvent.ordered(got.get(0), got.get(1)));
                await("cursor advanced to (0,2)", () -> componentS(watch.cursor(), 0) == 2L);
            }
        }
    }

    @Test
    @Tag("clause:W8-3")
    void commitTimestampIsFreshnessNeverTheCursor() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL))));
            // Two commits with the SAME commit_ts but distinct S — the cursor MUST ride S, not the timestamp.
            w(conn, new EdgeFrame.WatchEvent(wid, 0, 1, 111L,
                    List.of(EdgeFrame.WatchChange.put("a", "1".getBytes(StandardCharsets.UTF_8)))));
            w(conn, new EdgeFrame.WatchEvent(wid, 0, 2, 111L,
                    List.of(EdgeFrame.WatchChange.put("b", "2".getBytes(StandardCharsets.UTF_8)))));
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.prefix("/"), WatchOptions.defaults());
                List<WatchEvent> got = collect(watch);
                watch.awaitCreated(Duration.ofSeconds(10));
                await("two events delivered", () -> got.size() == 2);
                // commit_ts is exposed for freshness (W8-3)...
                assertEquals(111L, got.get(0).commitTs());
                assertEquals(111L, got.get(1).commitTs());
                // ...but the cursor advanced by the distinct S despite the identical commit_ts — commit_ts is
                // NOT a cursor (W3-3 / W8-3). The vector carries S components only; no timestamp component.
                await("cursor advanced by S to 2 (not by commit_ts)", () -> componentS(watch.cursor(), 0) == 2L);
            }
        }
    }

    @Test
    @Tag("clause:W8-4")
    @Tag("clause:W5-2")
    void fullChainVerifyVerifiesSignaturesAndFiltersLocally() throws Exception {
        java.security.KeyPair leader = StreamFixtures.ed25519();
        io.configd.store.ConfigDelta matching = StreamFixtures.signedPut(leader, 0, 1, 1, "/watched", "yes");
        io.configd.store.ConfigDelta other = StreamFixtures.signedPut(leader, 1, 2, 2, "/other", "no");
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL))));
            w(conn, StreamFixtures.notify(1, 100, matching)); // the verbatim signed chain rides NOTIFY (W5-2 / W8-4)
            w(conn, StreamFixtures.notify(2, 100, other));    // filtered out LOCALLY (not under /watched)
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
                // In full_chain_verify the client verifies the Ed25519 chain then filters locally — only the
                // matching, verified change is delivered; the untrusted edge's non-matching NOTIFY is dropped.
                await("only the matching, verified change delivered", () -> countChanges(got) == 1);
                assertEquals("/watched", got.get(0).changes().get(0).key());
            }
        }
    }

    @Test
    @Tag("clause:W5-9")
    @Tag("clause:W7-6")
    void notAuthorizedIsAPerWatchTerminalTheClientDoesNotRetry() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            // A per-watch terminal close carrying the 403-class code — no data frame precedes it.
            w(conn, new EdgeFrame.WatchCanceled(wid, ErrorCode.NOT_AUTHORIZED, null, "over-broad target"));
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.full(), WatchOptions.defaults());
                ExecutionException ee = assertThrows(ExecutionException.class,
                        () -> watch.terminalFuture().get(10, TimeUnit.SECONDS));
                assertInstanceOf(ForbiddenException.class, ee.getCause(), "NOT_AUTHORIZED ⇒ 403-class ForbiddenException");
                // W7-6: a 403 is permanently forbidden for this principal+target — the client MUST NOT retry it
                // unchanged. One connection only: no reconnect storm.
                assertEquals(1, server.connectionCount(), "a 403 watch reject is terminal — no retry");
            }
        }
    }

    @Test
    @Tag("clause:W5-8")
    void clientInitiatedCancelSendsWatchCancelByWatchId() throws Exception {
        CompletableFuture<Long> canceledId = new CompletableFuture<>();
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL))));
            w(conn, event(wid, 0, 1, "k", "v"));
            // The client sends a mandatory CURSOR_ACK (F10-3) for the applied event before it closes, so the
            // WATCH_CANCEL is not necessarily the very next frame — drain until we see it.
            EdgeFrame next;
            while ((next = conn.readFrame()) != null) {
                if (next instanceof EdgeFrame.WatchCancel wc) {
                    canceledId.complete(wc.watchId());
                    break;
                }
            }
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.key("/k"), WatchOptions.defaults());
                List<WatchEvent> got = collect(watch);
                watch.awaitCreated(Duration.ofSeconds(10));
                await("first event delivered", () -> got.size() == 1);
                long id = watch.watchId();
                watch.close(); // WATCH_CANCEL (W5-8): stop this watch by its watch_id
                assertEquals(id, canceledId.get(10, TimeUnit.SECONDS).longValue(),
                        "the client canceled by the watch's own id");
            }
        }
    }

    @Test
    @Tag("clause:W10-2..W10-8")
    void prevValueRequestedButUnsupportedStillDeliversEvents() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            w(conn, new EdgeFrame.WatchCreated(wid, List.of(new EdgeFrame.ShardMode(0, 0, EdgeFrame.Mode.TAIL))));
            w(conn, event(wid, 0, 1, "k", "v")); // a plain event — NO prev-image field (the server ignores prev_value)
            conn.parkUntilClosed();
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.key("/k").with(WatchTarget.Flag.PREV_VALUE), WatchOptions.defaults());
                List<WatchEvent> got = collect(watch);
                watch.awaitCreated(Duration.ofSeconds(10));
                // The client requested the prev_value extension (bit1)...
                assertTrue(firstWatchCreate(server).prevValue());
                // ...but a server MAY leave it unsupported; the driver MUST NOT require an un-negotiated extension
                // (fail closed / do-not-assume; W10-2..W10-8). Events still deliver normally, no prev field needed.
                await("event still delivered without any prev-image", () -> got.size() == 1);
                assertArrayEquals("v".getBytes(StandardCharsets.UTF_8), got.get(0).changes().get(0).value());
            }
        }
    }

    // -----------------------------------------------------------------------

    private static EdgeFrame event(long watchId, int gid, long s, String key, String value) {
        return new EdgeFrame.WatchEvent(watchId, gid, s, 100L,
                List.of(EdgeFrame.WatchChange.put(key, value.getBytes(StandardCharsets.UTF_8))));
    }

    /** Sends a server→client frame on the 0x02 watch wire (the version the client pinned via WATCH_CREATE). */
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

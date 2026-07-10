package io.configd.distribution.fanout;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.CommitNotificationSource.Result;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;
import io.configd.distribution.wire.WatchCursor;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end watch veneer matrix driven through {@link FanOutConnectionDriver}. Exercises the
 * security gate (the crux), fail-closed behavior, {@code watch_id} no-reuse, the per-connection
 * watch caps (W8-6), multiplex isolation, cursor resume, the behind-buffer catch-up, and the
 * path-grammar (BAD_SUBSCRIBE) surface. Uses a real {@link FanOutBuffer} + {@link SnapshotReplaySource}, a recording
 * {@link RecordingTransportSink} (the transport delegate behind the veneer), a
 * {@link FakeClock}, and a lambda {@link WatchAuthorizer} - no threads, no I/O.
 *
 * <p>Inbound is driven deterministically: {@link #feed} posts the frame and drains the session
 * command onto the test thread (acting as the session thread); {@link #tick} advances the core.
 */
class WatchVeneerDriverTest {

    private static final WatchAuthorizer ALLOW = (p, r, t) -> true;
    private static final WatchAuthorizer DENY = (p, r, t) -> false;
    private static final WatchAuthorizer BOOM = (p, r, t) -> {
        throw new RuntimeException("authorizer blew up");
    };

    private final FakeClock clock = new FakeClock(1_000L);
    private final RecordingTransportSink out = new RecordingTransportSink();
    private final List<ErrorCode> teardowns = new ArrayList<>();

    private FanOutBuffer buffer;
    private FanOutConnectionDriver driver;

    // ---- harness ------------------------------------------------------------

    private void setup(WatchAuthorizer auth, String identity, FanOutBuffer buf, ReplaySource replay) {
        this.buffer = buf;
        SlowConsumerGovernor gov =
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), FanOutSessionMetrics.NOOP);
        this.driver = new FanOutConnectionDriver(buf, replay, out, FanOutConfig.defaults(),
                FanOutSessionMetrics.NOOP, clock, gov, identity, (c, m) -> teardowns.add(c), auth);
    }

    private void setup(WatchAuthorizer auth) {
        setup(auth, "edge-1", new FanOutBuffer(64), snapshotAt(0));
    }

    /** Posts a frame and runs the resulting session command on the test (session) thread. */
    private void feed(EdgeFrame frame) {
        driver.onInboundFrame(frame);
        driver.drainInboundCommands();
    }

    private void tick() {
        driver.session().tick(clock.now());
    }

    // ---- the security gate (W7) - the crux ---------------------------------

    @Test
    void denyingAuthorizerRejectsWithNotAuthorizedAndZeroDataFrames() {
        setup(DENY);
        feed(keyCreate(1, "/k/a"));
        tick(); // prove no data frame ever materializes

        // Exactly one frame: the 403-class per-watch terminal.
        assertEquals(1, out.sent().size(), "exactly one frame — the reject");
        EdgeFrame.WatchCanceled cancel = assertInstanceOf(EdgeFrame.WatchCanceled.class, out.sent().get(0));
        assertEquals(1L, cancel.watchId());
        assertEquals(ErrorCode.NOT_AUTHORIZED, cancel.code());
        // Zero payload-bearing frames precede it (W7-5).
        assertTrue(out.sentOfType(EdgeFrame.WatchCreated.class).isEmpty());
        assertTrue(out.sentOfType(EdgeFrame.WatchEvent.class).isEmpty());
        assertTrue(out.sentOfType(EdgeFrame.SubscribeOk.class).isEmpty());
        assertTrue(out.sentOfType(EdgeFrame.Notify.class).isEmpty());
    }

    @Test
    void allowingAuthorizerEmitsWatchCreatedThenWatchEvents() {
        setup(ALLOW); // empty buffer => first watch subscribes TAIL
        feed(keyCreate(1, "/k/a"));

        List<EdgeFrame.WatchCreated> created = out.sentOfType(EdgeFrame.WatchCreated.class);
        assertEquals(1, created.size(), "WATCH_CREATED is the first frame for the watch (W5-5)");
        assertEquals(1L, created.get(0).watchId());
        assertTrue(out.sentOfType(EdgeFrame.WatchEvent.class).isEmpty(), "no events before any commit");

        buffer.publish(put(1, "/k/a", "v"));
        tick();

        List<EdgeFrame.WatchEvent> events = out.sentOfType(EdgeFrame.WatchEvent.class);
        assertEquals(1, events.size());
        assertEquals(1L, events.get(0).watchId());
        assertTrue(out.sent().indexOf(created.get(0)) < out.sent().indexOf(events.get(0)),
                "WATCH_CREATED precedes the first WATCH_EVENT");
    }

    @Test
    void fullChainVerifyDenyEmitsZeroNotifyBeforeReject() {
        // The matrix-5 mechanism: a full_chain_verify watch's verbatim carrier is the
        // connection-level NOTIFY. The gate must reject BEFORE the core drain starts, so not a
        // single NOTIFY leaks the full chain to a non-root principal.
        setup(DENY);
        buffer.publish(put(1, "/secret/k", "v")); // data exists - a NOTIFY would fire if subscribed
        feed(fullCreate(1, EdgeFrame.WATCH_FLAG_FULL_CHAIN_VERIFY, WatchCursor.fromNow()));
        tick();
        tick();

        assertTrue(out.sentOfType(EdgeFrame.Notify.class).isEmpty(),
                "ZERO NOTIFY precede a full_chain_verify reject (W7-5)");
        EdgeFrame.WatchCanceled cancel = assertInstanceOf(EdgeFrame.WatchCanceled.class, out.sent().get(0));
        assertEquals(ErrorCode.NOT_AUTHORIZED, cancel.code());
    }

    // ---- fail-closed --------------------------------------------------------

    @Test
    void nullAuthorizerFailsClosed() {
        setup(null, "edge-1", new FanOutBuffer(64), snapshotAt(0));
        feed(keyCreate(1, "/k/a"));
        assertReject(1, ErrorCode.NOT_AUTHORIZED);
    }

    @Test
    void unauthenticatedPlaintextIdentityFailsClosedEvenWhenAuthorizerAllows() {
        setup(ALLOW, "plaintext", new FanOutBuffer(64), snapshotAt(0));
        feed(keyCreate(1, "/k/a"));
        assertReject(1, ErrorCode.NOT_AUTHORIZED);
    }

    @Test
    void throwingAuthorizerFailsClosed() {
        setup(BOOM);
        feed(keyCreate(1, "/k/a"));
        assertReject(1, ErrorCode.NOT_AUTHORIZED);
    }

    // ---- multiplex isolation (matrix 10) -----------------------------------

    @Test
    void cancelOfOneWatchDoesNotPerturbAnother() {
        setup(ALLOW); // empty buffer
        feed(keyCreate(1, "/k/a")); // first watch drives the shared drain (TAIL)
        feed(keyCreate(2, "/k/b")); // second watch rides the shared drain

        buffer.publish(multiPut(1, "/k/a", "a1", "/k/b", "b1"));
        tick();
        List<EdgeFrame.WatchEvent> round1 = out.sentOfType(EdgeFrame.WatchEvent.class);
        assertEquals(2, round1.size(), "both watches receive their filtered event");
        assertTrue(round1.stream().anyMatch(e -> e.watchId() == 1L));
        assertTrue(round1.stream().anyMatch(e -> e.watchId() == 2L));

        out.clear();
        feed(cancel(1)); // cancel A only
        buffer.publish(multiPut(2, "/k/a", "a2", "/k/b", "b2"));
        tick();

        List<EdgeFrame.WatchEvent> round2 = out.sentOfType(EdgeFrame.WatchEvent.class);
        assertEquals(1, round2.size(), "only the surviving watch receives events");
        assertEquals(2L, round2.get(0).watchId());
        assertFalse(round2.stream().anyMatch(e -> e.watchId() == 1L), "the canceled watch receives nothing");
    }

    // ---- watch_id no-reuse (matrix 11) -------------------------------------

    @Test
    void reusingACanceledWatchIdIsRejectedAsBadSubscribe() {
        setup(ALLOW);
        feed(keyCreate(1, "/k/a"));
        feed(cancel(1)); // id 1 stays burned in everUsed
        out.clear();

        feed(keyCreate(1, "/k/b")); // reuse id 1 -> BAD_SUBSCRIBE (W2-8)
        assertEquals(1, out.sent().size());
        assertReject(1, ErrorCode.BAD_SUBSCRIBE);
    }

    // ---- per-connection watch caps (W8-6 abuse control) --------------------

    @Test
    void liveWatchesAreAcceptedUpToTheCapThenTheNextIsRejected() {
        setup(ALLOW); // empty buffer => watches TAIL, no data frames
        // Every watch up to the live cap is accepted (distinct ids; the same target is fine). Driven at
        // the real MAX_LIVE_WATCHES_PER_CONNECTION - no lowered constant, no seam.
        int cap = FanOutConnectionDriver.MAX_LIVE_WATCHES_PER_CONNECTION;
        for (int id = 1; id <= cap; id++) {
            feed(keyCreate(id, "/k/a"));
        }
        assertEquals(cap, out.sentOfType(EdgeFrame.WatchCreated.class).size(),
                "every watch at or below the live cap is acknowledged");
        assertTrue(out.sentOfType(EdgeFrame.WatchCanceled.class).isEmpty(),
                "nothing is rejected at or below the live cap");

        // One more live watch exceeds the cap -> BAD_SUBSCRIBE, and no ack precedes the reject.
        out.clear();
        feed(keyCreate(cap + 1, "/k/a"));
        assertEquals(1, out.sent().size(), "exactly one frame - the reject");
        EdgeFrame.WatchCanceled reject = assertInstanceOf(EdgeFrame.WatchCanceled.class, out.sent().get(0));
        assertEquals(cap + 1L, reject.watchId());
        assertEquals(ErrorCode.BAD_SUBSCRIBE, reject.code());
        assertTrue(reject.message().contains("too many live watches"),
                "the reject names the live-watch cap: " + reject.message());
        assertTrue(out.sentOfType(EdgeFrame.WatchCreated.class).isEmpty(), "no ack for the over-cap watch");
    }

    @Test
    void watchIdBudgetIsAcceptedToTheCapThenExhausted() {
        setup(ALLOW);
        // Churn create+cancel so liveCount stays <= 1 (the live cap never trips) while the
        // never-shrinking watch_id budget (everUsed, W2-8) climbs. Driven at the real
        // MAX_WATCH_IDS_PER_CONNECTION.
        int budget = FanOutConnectionDriver.MAX_WATCH_IDS_PER_CONNECTION;
        for (int id = 1; id < budget; id++) {
            feed(keyCreate(id, "/k/a"));
            feed(cancel(id));
        }
        out.clear();

        // The id at exactly the budget is still accepted (totalUsed == budget-1 < budget); leave it live.
        feed(keyCreate(budget, "/k/a"));
        assertEquals(1, out.sentOfType(EdgeFrame.WatchCreated.class).size(),
                "the id at the budget boundary is accepted");
        out.clear();

        // The next id exhausts the budget (totalUsed == budget) -> BAD_SUBSCRIBE, no ack.
        feed(keyCreate(budget + 1, "/k/a"));
        assertEquals(1, out.sent().size());
        EdgeFrame.WatchCanceled reject = assertInstanceOf(EdgeFrame.WatchCanceled.class, out.sent().get(0));
        assertEquals(budget + 1L, reject.watchId());
        assertEquals(ErrorCode.BAD_SUBSCRIBE, reject.code());
        assertTrue(reject.message().contains("watch_id budget exhausted"),
                "the reject names the watch_id budget: " + reject.message());
        assertTrue(out.sentOfType(EdgeFrame.WatchCreated.class).isEmpty(), "no ack for the over-budget watch");
    }

    // ---- cursor resume (matrix 12) -----------------------------------------

    @Test
    void resumeFromVectorCursorDeliversExactlyReadSinceSet() {
        setup(ALLOW, "edge-1", new FanOutBuffer(64), snapshotAt(0));
        for (long i = 1; i <= 5; i++) {
            buffer.publish(put(i, "/k/" + i, "v")); // retained 1..5
        }
        // FULL watch resuming at the single-component vector (gid=0, S=2).
        feed(fullCreate(1, 0, WatchCursor.of(0, 2)));
        tick();

        List<Long> delivered = out.sentOfType(EdgeFrame.WatchEvent.class).stream()
                .map(EdgeFrame.WatchEvent::s).toList();
        // Cross-check: the delivered set is exactly the scalar readSince(2) set, filtered (FULL => all).
        List<Long> expected = ((Result.Ok) buffer.readSince(2)).notifications().stream()
                .map(CommitNotification::seq).toList();
        assertEquals(List.of(3L, 4L, 5L), delivered);
        assertEquals(expected, delivered, "resume(gid=0,S) == scalar readSince(S)");
    }

    // ---- behind-buffer catch-up (matrix 14) --------------------------------

    @Test
    void resumeOlderThanBufferSurfacesAsWatchSnapshotSubstream() {
        FanOutBuffer tiny = new FanOutBuffer(4);
        for (long i = 1; i <= 10; i++) {
            tiny.publish(put(i, "/k/" + i, "v")); // evicts the early seqs; cursor 2 is long gone
        }
        ReplaySource replay = snapshotAt(10, "/k/7", "v7", "/k/8", "v8", "/k/9", "v9", "/k/10", "v10");
        setup(ALLOW, "edge-1", tiny, replay);

        feed(fullCreate(1, 0, WatchCursor.of(0, 2))); // readSince(2) GAPs -> SNAPSHOT_FIRST
        EdgeFrame.WatchCreated created = out.sentOfType(EdgeFrame.WatchCreated.class).get(0);
        assertEquals(EdgeFrame.Mode.SNAPSHOT_FIRST, created.shards().get(0).mode());

        tick(); // performs the catch-up snapshot transfer
        List<EdgeFrame.WatchSnapshotBegin> begins = out.sentOfType(EdgeFrame.WatchSnapshotBegin.class);
        List<EdgeFrame.WatchSnapshotEnd> ends = out.sentOfType(EdgeFrame.WatchSnapshotEnd.class);
        assertEquals(1, begins.size(), "the connection-level snapshot maps to a per-watch WATCH_SNAPSHOT_*");
        assertEquals(1L, begins.get(0).watchId());
        assertEquals(0, begins.get(0).gid());
        assertEquals(1, ends.size());
        assertEquals(1L, ends.get(0).watchId());
    }

    // ---- path grammar (matrix N4) ------------------------------------------

    @Test
    void malformedTargetsAreRejectedAsBadSubscribe() {
        setup(ALLOW);

        feed(new EdgeFrame.WatchCreate(1, 0, EdgeFrame.WATCH_TARGET_KEY,
                new byte[0], WatchCursor.fromNow(), 0)); // empty KEY path
        assertReject(1, ErrorCode.BAD_SUBSCRIBE);
        out.clear();

        byte[] oversized = ("/" + "a".repeat(1024)).getBytes(StandardCharsets.UTF_8); // 1025 bytes
        feed(new EdgeFrame.WatchCreate(2, 0, EdgeFrame.WATCH_TARGET_KEY,
                oversized, WatchCursor.fromNow(), 0));
        assertReject(2, ErrorCode.BAD_SUBSCRIBE);
        out.clear();

        feed(keyCreate(3, "/a b")); // invalid seg-char (space)
        assertReject(3, ErrorCode.BAD_SUBSCRIBE);
        out.clear();

        feed(keyCreate(4, "relative/x")); // not absolute
        assertReject(4, ErrorCode.BAD_SUBSCRIBE);
        out.clear();

        feed(keyCreate(5, "/a//b")); // empty segment
        assertReject(5, ErrorCode.BAD_SUBSCRIBE);
        out.clear();

        feed(prefixCreate(6, "/a/../b")); // relative segment
        assertReject(6, ErrorCode.BAD_SUBSCRIBE);
    }

    @Test
    void fullTargetWithNonEmptyPathIsStructurallyRejectedAtTheWire() {
        // A FULL target MUST carry an empty path (W5-4). The EdgeFrame.WatchCreate record enforces
        // this structurally, so a malformed FULL frame is a codec FRAME_CORRUPT and never reaches
        // the veneer - there is no veneer path that can build it. Documented here as the structural
        // invariant that discharges the "FULL non-empty -> BAD_SUBSCRIBE" matrix cell.
        assertThrows(IllegalArgumentException.class, () -> new EdgeFrame.WatchCreate(
                1, 0, EdgeFrame.WATCH_TARGET_FULL, "/x".getBytes(StandardCharsets.UTF_8),
                WatchCursor.fromNow(), 0));
    }

    @Test
    void aPrefixSubtreeTrailingSlashIsAcceptedGrammar() {
        // The subtree form /a/ is the canonical PREFIX target - it must NOT be grammar-rejected.
        setup(ALLOW);
        feed(prefixCreate(1, "/a/"));
        assertTrue(out.sentOfType(EdgeFrame.WatchCanceled.class).isEmpty(),
                "the /a/ subtree PREFIX form is valid (not BAD_SUBSCRIBE)");
        assertEquals(1, out.sentOfType(EdgeFrame.WatchCreated.class).size());
    }

    // ---- legacy guards ------------------------------------------------------

    @Test
    void subscribeOnAWatchConnectionIsAProtocolViolation() {
        setup(ALLOW);
        feed(keyCreate(1, "/k/a")); // establishes the watch connection
        driver.onInboundFrame(new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge-1"));
        assertEquals(ErrorCode.PROTOCOL_VIOLATION, teardowns.get(teardowns.size() - 1),
                "SUBSCRIBE cannot be mixed onto a watch connection (W5-12)");
    }

    // ---- helpers ------------------------------------------------------------

    private void assertReject(long watchId, ErrorCode code) {
        EdgeFrame.WatchCanceled cancel = assertInstanceOf(EdgeFrame.WatchCanceled.class, out.sent().get(0));
        assertEquals(watchId, cancel.watchId());
        assertEquals(code, cancel.code());
        assertTrue(out.sentOfType(EdgeFrame.WatchCreated.class).isEmpty(), "no ack precedes a reject");
        assertTrue(out.sentOfType(EdgeFrame.WatchEvent.class).isEmpty(), "no event precedes a reject");
    }

    private static EdgeFrame.WatchCreate keyCreate(long id, String path) {
        return new EdgeFrame.WatchCreate(id, 0, EdgeFrame.WATCH_TARGET_KEY,
                path.getBytes(StandardCharsets.UTF_8), WatchCursor.fromNow(), 0);
    }

    private static EdgeFrame.WatchCreate prefixCreate(long id, String path) {
        return new EdgeFrame.WatchCreate(id, 0, EdgeFrame.WATCH_TARGET_PREFIX,
                path.getBytes(StandardCharsets.UTF_8), WatchCursor.fromNow(), 0);
    }

    private static EdgeFrame.WatchCreate fullCreate(long id, int flags, WatchCursor cursor) {
        return new EdgeFrame.WatchCreate(id, 0, EdgeFrame.WATCH_TARGET_FULL, new byte[0], cursor, flags);
    }

    private static EdgeFrame.WatchCancel cancel(long id) {
        return new EdgeFrame.WatchCancel(id);
    }

    private static CommitNotification put(long seq, String key, String val) {
        return new CommitNotification(seq, 1_000L + seq, new ConfigDelta(seq - 1, seq,
                List.of(new ConfigMutation.Put(key, val.getBytes(StandardCharsets.UTF_8)))));
    }

    private static CommitNotification multiPut(long seq, String k1, String v1, String k2, String v2) {
        return new CommitNotification(seq, 1_000L + seq, new ConfigDelta(seq - 1, seq, List.of(
                new ConfigMutation.Put(k1, v1.getBytes(StandardCharsets.UTF_8)),
                new ConfigMutation.Put(k2, v2.getBytes(StandardCharsets.UTF_8)))));
    }

    private static ReplaySource snapshotAt(long version, String... kv) {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            data = data.put(kv[i], new VersionedValue(kv[i + 1].getBytes(StandardCharsets.UTF_8), version, 0L));
        }
        ConfigSnapshot snap = new ConfigSnapshot(data, version, 0L);
        return new SnapshotReplaySource(() -> snap);
    }
}

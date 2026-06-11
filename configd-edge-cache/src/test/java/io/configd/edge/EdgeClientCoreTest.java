package io.configd.edge;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeSnapshotCodec;
import io.configd.distribution.wire.ErrorCode;
import io.configd.observability.InvariantMonitor;
import io.configd.observability.MetricsRegistry;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit matrix for {@link EdgeClientCore} — the transport-agnostic C2 client engine.
 *
 * <p>Covers (CT-01/07/08/13/16/23/25 at unit level): the frame-handling matrix (SUBSCRIBE_OK
 * mode, NOTIFY verify→filter→apply + per-batch CURSOR_ACK, SNAPSHOT_* reassembly + cutover,
 * backward-snapshot refusal [C1(a)], HEARTBEAT frontier [ADR-0039] incl. {@code latestSeq >
 * cursor} never advancing it, ERROR_CLOSE handling), the tick CURSOR_ACK + heartbeat-silence
 * reconnect directive, and the INV-M1 read seam routing.
 */
class EdgeClientCoreTest {

    static final class TestClock implements Clock {
        long timeMs;
        TestClock(long initial) { this.timeMs = initial; }
        @Override public long currentTimeMillis() { return timeMs; }
        @Override public long nanoTime() { return timeMs * 1_000_000L; }
        void advance(long ms) { timeMs += ms; }
    }

    /** Recording sink: captures every edge→server frame; offer always succeeds. */
    static final class RecordingSink implements EdgeClientCore.FrameSink {
        final List<EdgeFrame> sent = new ArrayList<>();
        boolean block; // when true, offer refuses (would-block)
        @Override public boolean offer(EdgeFrame frame) {
            if (block) return false;
            sent.add(frame);
            return true;
        }
        List<EdgeFrame.CursorAck> acks() {
            List<EdgeFrame.CursorAck> out = new ArrayList<>();
            for (EdgeFrame f : sent) {
                if (f instanceof EdgeFrame.CursorAck a) out.add(a);
            }
            return out;
        }
        long lastAck() {
            List<EdgeFrame.CursorAck> a = acks();
            return a.isEmpty() ? -1 : a.get(a.size() - 1).seq();
        }
    }

    private TestClock clock;
    private RecordingSink sink;
    private MetricsRegistry metrics;
    private InvariantMonitor monitor;
    private EdgeClientCore core;

    @BeforeEach
    void setUp() {
        clock = new TestClock(1_000_000L);
        sink = new RecordingSink();
        metrics = new MetricsRegistry();
        // testMode=true → an INV-M1 monotonic_read violation throws (fails the test).
        monitor = new InvariantMonitor(metrics, true);
        core = new EdgeClientCore(clock, monitor,
                metrics.counter(StalenessTracker.IMPLAUSIBLE_METRIC),
                StrongReadKeyClass.DEFAULT, sink,
                EdgeClientCore.DEFAULT_HEARTBEAT_MS, EdgeClientCore.DEFAULT_SILENCE_FACTOR);
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static CommitNotification notif(long seq, long from, long to, long commitTs,
                                             String... kv) {
        List<ConfigMutation> ms = new ArrayList<>();
        for (int i = 0; i < kv.length; i += 2) {
            ms.add(new ConfigMutation.Put(kv[i], bytes(kv[i + 1])));
        }
        return new CommitNotification(seq, commitTs, new ConfigDelta(from, to, ms));
    }

    private static ConfigSnapshot snapshot(long version, String... kv) {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        for (int i = 0; i < kv.length; i += 2) {
            data = data.put(kv[i], new VersionedValue(bytes(kv[i + 1]), version, version));
        }
        return new ConfigSnapshot(data, version, version);
    }

    /** Builds the SNAPSHOT_BEGIN..CHUNK*..END frame flow for a snapshot at seq. */
    private static List<EdgeFrame> snapshotFrames(ConfigSnapshot snap, long seq) {
        byte[] body = EdgeSnapshotCodec.serialize(snap);
        List<EdgeFrame.SnapshotChunk> chunks = EdgeSnapshotCodec.chunk(body, 4096);
        List<EdgeFrame> frames = new ArrayList<>();
        frames.add(new EdgeFrame.SnapshotBegin(seq, chunks.size(), body.length));
        frames.addAll(chunks);
        frames.add(new EdgeFrame.SnapshotEnd(seq));
        return frames;
    }

    // -----------------------------------------------------------------------
    // SUBSCRIBE_OK
    // -----------------------------------------------------------------------

    @Nested
    class SubscribeOkHandling {

        @Test
        void recordsModeAndSeedsCursorLag() {
            core.onFrame(new EdgeFrame.SubscribeOk(7, EdgeFrame.Mode.SNAPSHOT_FIRST));
            assertEquals(EdgeFrame.Mode.SNAPSHOT_FIRST, core.mode());
            assertEquals(7, core.cursorLag(), "latestSeq 7 − cursor 0 = lag 7");
        }

        @Test
        void tailModeRecorded() {
            core.onFrame(new EdgeFrame.SubscribeOk(0, EdgeFrame.Mode.TAIL));
            assertEquals(EdgeFrame.Mode.TAIL, core.mode());
        }
    }

    // -----------------------------------------------------------------------
    // NOTIFY: verify→filter→apply + per-batch CURSOR_ACK
    // -----------------------------------------------------------------------

    @Nested
    class NotifyHandling {

        @Test
        void singleNotifyAppliesAndAcks() {
            core.onFrame(new EdgeFrame.Notify(List.of(notif(1, 0, 1, clock.timeMs, "a", "1"))));
            assertEquals(1, core.cursor());
            assertEquals(1, core.currentVersion());
            assertTrue(core.get("a").found());
            assertEquals(1, core.appliedCount());
            assertEquals(1, sink.lastAck(), "one CURSOR_ACK for the highest applied seq");
        }

        @Test
        void batchAppliesInSeqOrderAndAcksHighestOnce() {
            core.onFrame(new EdgeFrame.Notify(List.of(
                    notif(1, 0, 1, clock.timeMs, "a", "1"),
                    notif(2, 1, 2, clock.timeMs, "b", "2"),
                    notif(3, 2, 3, clock.timeMs, "c", "3"))));
            assertEquals(3, core.cursor());
            assertEquals(3, core.appliedCount());
            assertEquals(1, sink.acks().size(), "exactly one ack per batch");
            assertEquals(3, sink.lastAck());
        }

        @Test
        void gapMidBatchIsRecordedAndAcksRealCursor() {
            core.onFrame(new EdgeFrame.Notify(List.of(
                    notif(1, 0, 1, clock.timeMs, "a", "1"),
                    notif(3, 2, 3, clock.timeMs, "c", "3")))); // from=2 != current 1 → GAP
            assertEquals(1, core.cursor(), "cursor stops at the last contiguous applied seq");
            assertEquals(1, core.gapsDetected());
            assertEquals(1, sink.lastAck(), "ack reflects the real cursor, not the gapped seq");
        }

        @Test
        void staleNotifyIsRecordedNotApplied() {
            core.onFrame(new EdgeFrame.Notify(List.of(notif(1, 0, 1, clock.timeMs, "a", "1"))));
            // Re-deliver seq 1 (toVersion 1 <= current 1) → STALE_DELTA, no overwrite.
            core.onFrame(new EdgeFrame.Notify(List.of(notif(1, 0, 1, clock.timeMs, "a", "X"))));
            assertEquals(1, core.cursor());
            assertArrayEquals(bytes("1"), core.get("a").value(), "stale delta never overwrites");
        }

        @Test
        void appliedNotifyAdvancesFrontierToCommitTimestamp() {
            clock.timeMs = 1_000_300; // wall-now 300ms ahead of commit ts
            core.onFrame(new EdgeFrame.Notify(List.of(notif(1, 0, 1, 1_000_000, "a", "1"))));
            assertEquals(300, core.stalenessMs(),
                    "frontier = leader commit ts → staleness is data-age, not local clock");
            assertEquals(StalenessTracker.State.CURRENT, core.stalenessState());
        }
    }

    // -----------------------------------------------------------------------
    // ADR-0038 storage filter through the core
    // -----------------------------------------------------------------------

    @Nested
    class StorageFilterThroughCore {

        @Test
        void nonSubscribedKeyNotStoredButCursorAdvances() {
            core.addSubscription("svc/");
            core.onFrame(new EdgeFrame.Notify(List.of(
                    notif(1, 0, 1, clock.timeMs, "svc/a", "1", "other/b", "2"))));
            assertEquals(1, core.cursor());
            assertTrue(core.get("svc/a").found());
            assertFalse(core.get("other/b").found(), "non-subscribed key filtered from storage");
        }

        @Test
        void readStoreStaysInLockstepWithClientStore() {
            core.addSubscription("svc/");
            core.onFrame(new EdgeFrame.Notify(List.of(
                    notif(1, 0, 1, clock.timeMs, "svc/a", "1", "other/b", "2"))));
            // The monitor-wired read store reflects the SAME filtered slice + version.
            assertEquals(1, core.currentVersion());
            assertTrue(core.get("svc/a").found());
            assertFalse(core.get("other/b").found());
        }

        @Test
        void secureKeyStoredThroughCoreEvenWhenNotSubscribed() {
            core.addSubscription("svc/");
            core.onFrame(new EdgeFrame.Notify(List.of(
                    notif(1, 0, 1, clock.timeMs, "secure/k", "ON"))));
            assertTrue(core.get("secure/k").found(),
                    "strong-read keys are always stored (store-and-fail-closed-serve)");
        }
    }

    // -----------------------------------------------------------------------
    // SNAPSHOT flow: reassembly + cutover, backward refusal
    // -----------------------------------------------------------------------

    @Nested
    class SnapshotHandling {

        @Test
        void snapshotReassemblesAndCutsOver() {
            for (EdgeFrame f : snapshotFrames(snapshot(5, "x", "10", "y", "20"), 5)) {
                core.onFrame(f);
            }
            assertEquals(5, core.cursor());
            assertEquals(5, core.currentVersion());
            assertTrue(core.get("x").found());
            assertArrayEquals(bytes("10"), core.get("x").value());
            assertEquals(1, core.snapshotsApplied());
            assertEquals(5, sink.lastAck(), "ack the snapshot cutover seq");
            assertFalse(core.inSnapshot());
        }

        @Test
        void backwardSnapshotIsRefusedAndReAcksRealCursor() {
            // Advance the edge to cursor 8 via notifies.
            for (long s = 1; s <= 8; s++) {
                core.onFrame(new EdgeFrame.Notify(List.of(
                        notif(s, s - 1, s, clock.timeMs, "k" + s, "v"))));
            }
            assertEquals(8, core.cursor());
            int acksBefore = sink.acks().size();

            // A backward snapshot at seq 4 (< cursor 8) must be REFUSED (C1(a) monotonicity).
            for (EdgeFrame f : snapshotFrames(snapshot(4, "x", "1"), 4)) {
                core.onFrame(f);
            }
            assertEquals(8, core.cursor(), "edge never regresses (backward snapshot refused)");
            assertEquals(1, core.backwardSnapshotsRefused());
            assertFalse(core.get("x").found(), "the backward snapshot's state is not loaded");
            assertEquals(8, sink.lastAck(), "re-ack the real (higher) cursor");
            assertTrue(sink.acks().size() > acksBefore, "a re-ack was emitted");
        }

        @Test
        void forwardSnapshotAfterGapHeals() {
            // Create a gap: apply seq 1, then a seq-3 notify (from=2 != 1) → GAP.
            core.onFrame(new EdgeFrame.Notify(List.of(notif(1, 0, 1, clock.timeMs, "a", "1"))));
            core.onFrame(new EdgeFrame.Notify(List.of(notif(3, 2, 3, clock.timeMs, "c", "3"))));
            assertEquals(1, core.gapsDetected());

            // A forward snapshot at seq 3 heals it (cutover + resetGap).
            for (EdgeFrame f : snapshotFrames(snapshot(3, "a", "1", "b", "2", "c", "3"), 3)) {
                core.onFrame(f);
            }
            assertEquals(3, core.cursor());
            assertTrue(core.get("c").found());

            // A subsequent contiguous notify (from=3) applies cleanly post-heal.
            core.onFrame(new EdgeFrame.Notify(List.of(notif(4, 3, 4, clock.timeMs, "d", "4"))));
            assertEquals(4, core.cursor());
        }

        @Test
        void chunkWithoutBeginThrows() {
            assertThrows(IllegalStateException.class,
                    () -> core.onFrame(new EdgeFrame.SnapshotChunk(0, new byte[]{1, 2, 3})));
        }
    }

    // -----------------------------------------------------------------------
    // HEARTBEAT: ADR-0039 frontier
    // -----------------------------------------------------------------------

    @Nested
    class HeartbeatFrontier {

        /** Applies seqs 1..n contiguously (from 0) so the cursor reaches n. */
        private void advanceCursorTo(long n) {
            for (long s = 1; s <= n; s++) {
                core.onFrame(new EdgeFrame.Notify(List.of(
                        notif(s, s - 1, s, clock.timeMs, "k" + s, "v"))));
            }
        }

        @Test
        void cursorMatchedHeartbeatKeepsIdleEdgeCurrent() {
            advanceCursorTo(5); // cursor = 5, fully applied
            // No new deltas, but the server heartbeats "you're caught up" (latestSeq==cursor).
            for (int i = 0; i < 200; i++) {
                clock.advance(250);
                core.onFrame(new EdgeFrame.Heartbeat(5, clock.currentTimeMillis()));
                assertEquals(StalenessTracker.State.CURRENT, core.stalenessState(),
                        "idle-but-heartbeating edge stays CURRENT (ADR-0039)");
            }
            assertEquals(200, core.frontierAdvances());
            assertEquals(0, core.cursorLag());
        }

        @Test
        void behindHeartbeatDoesNotAdvanceFrontierAndShowsLag() {
            advanceCursorTo(5); // cursor = 5
            clock.advance(600); // would be STALE without a frontier advance
            // Server says latestSeq=8 > cursor=5 — genuinely behind by 3.
            core.onFrame(new EdgeFrame.Heartbeat(8, clock.currentTimeMillis()));
            assertEquals(0, core.frontierAdvances(), "behind heartbeat must not advance frontier");
            assertEquals(3, core.cursorLag());
            assertEquals(StalenessTracker.State.STALE, core.stalenessState());
        }

        @Test
        void heartbeatsObservedCounts() {
            core.onFrame(new EdgeFrame.Heartbeat(0, clock.currentTimeMillis()));
            core.onFrame(new EdgeFrame.Heartbeat(0, clock.currentTimeMillis()));
            assertEquals(2, core.heartbeatsObserved());
        }
    }

    // -----------------------------------------------------------------------
    // tick: CURSOR_ACK on advance + heartbeat-silence reconnect
    // -----------------------------------------------------------------------

    @Nested
    class TickBehavior {

        @Test
        void tickReAcksAfterAWouldBlockAck() {
            sink.block = true; // the apply-time ack will be refused
            core.onFrame(new EdgeFrame.Notify(List.of(notif(1, 0, 1, clock.timeMs, "a", "1"))));
            assertTrue(sink.acks().isEmpty(), "the would-block ack was not recorded");
            sink.block = false;
            core.tick(clock.currentTimeMillis());
            assertEquals(1, sink.lastAck(), "tick re-acks the advanced cursor (idempotent)");
        }

        @Test
        void heartbeatSilenceEmitsReconnectDirective() {
            // Establish a heartbeat baseline.
            core.onFrame(new EdgeFrame.Heartbeat(0, clock.currentTimeMillis()));
            assertFalse(core.hasDirective());

            // Stay silent past silenceFactor × heartbeatMs (8 × 250 = 2000ms).
            clock.advance(2001);
            core.tick(clock.currentTimeMillis());

            assertTrue(core.hasDirective(), "silence past the window must queue a reconnect");
            EdgeClientCore.ConnectionDirective d = core.pollDirective();
            assertInstanceOf(EdgeClientCore.ConnectionDirective.ReconnectNextEndpoint.class, d);
            var r = (EdgeClientCore.ConnectionDirective.ReconnectNextEndpoint) d;
            assertEquals(core.cursor(), r.resumeCursor(),
                    "reconnect carries the current cursor as the failover resume cursor");
        }

        @Test
        void noReconnectWhileHeartbeatsArrive() {
            for (int i = 0; i < 20; i++) {
                clock.advance(250);
                core.onFrame(new EdgeFrame.Heartbeat(0, clock.currentTimeMillis()));
                core.tick(clock.currentTimeMillis());
            }
            assertFalse(core.hasDirective(), "a steadily-heartbeating server never reconnects");
        }

        @Test
        void onReconnectedClearsLatchAndRestartsSilenceWindow() {
            core.onFrame(new EdgeFrame.Heartbeat(0, clock.currentTimeMillis()));
            clock.advance(2001);
            core.tick(clock.currentTimeMillis());
            assertTrue(core.hasDirective());
            core.pollDirective();

            core.onReconnected();
            // After reconnect, no heartbeat seen yet → no immediate re-trigger.
            clock.advance(5000);
            core.tick(clock.currentTimeMillis());
            assertFalse(core.hasDirective(),
                    "silence window restarts after reconnect (waits for first heartbeat)");
        }
    }

    // -----------------------------------------------------------------------
    // ERROR_CLOSE
    // -----------------------------------------------------------------------

    @Nested
    class ErrorCloseHandling {

        @Test
        void demotedToCatchupIsInformationalNoReconnect() {
            core.onFrame(new EdgeFrame.ErrorClose(ErrorCode.DEMOTED_TO_CATCHUP, "demoted"));
            assertFalse(core.hasDirective(), "DEMOTED_TO_CATCHUP is informational; snapshot follows");
        }

        @Test
        void fatalCloseQueuesReconnectAtCursor() {
            core.onFrame(new EdgeFrame.Notify(List.of(notif(1, 0, 1, clock.timeMs, "a", "1"))));
            core.onFrame(new EdgeFrame.ErrorClose(ErrorCode.GAP_UNRECOVERABLE, "lapped"));
            assertTrue(core.hasDirective());
            var r = (EdgeClientCore.ConnectionDirective.ReconnectNextEndpoint) core.pollDirective();
            assertEquals(1, r.resumeCursor());
        }
    }

    // -----------------------------------------------------------------------
    // Reads route through the INV-M1 monitor seam (hot path)
    // -----------------------------------------------------------------------

    @Nested
    class MonotonicReadSeam {

        @Test
        void cursorBehindStoreServesValue() {
            core.onFrame(new EdgeFrame.Notify(List.of(notif(2, 0, 2, clock.timeMs, "a", "1"))));
            assertTrue(core.get("a", new VersionCursor(2, 0)).found());
        }

        @Test
        void cursorAheadOfStoreReturnsNotFoundAndFiresMonitor() {
            core.onFrame(new EdgeFrame.Notify(List.of(notif(1, 0, 1, clock.timeMs, "a", "1"))));
            // testMode=true → a cursor ahead of the store throws via the INV-M1 seam.
            assertThrows(AssertionError.class,
                    () -> core.get("a", new VersionCursor(5, 0)));
            assertEquals(1L,
                    metrics.counter("invariant.violation.monotonic_read").get(),
                    "the read store routes through the real monotonic_read seam");
        }
    }

    // -----------------------------------------------------------------------
    // Inbound rejection of edge→server frames (mis-wired shell guard)
    // -----------------------------------------------------------------------

    @Test
    void inboundSubscribeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> core.onFrame(new EdgeFrame.Subscribe(true, List.of(), 0, -1, "e1")));
    }

    @Test
    void inboundCursorAckIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> core.onFrame(new EdgeFrame.CursorAck(3)));
    }

    // -----------------------------------------------------------------------
    // Constructor validation
    // -----------------------------------------------------------------------

    @Nested
    class ConstructorValidation {

        @Test
        void nonPositiveHeartbeatMsRejected() {
            assertThrows(IllegalArgumentException.class, () -> new EdgeClientCore(
                    clock, monitor, null, StrongReadKeyClass.DEFAULT, sink, 0, 8));
        }

        @Test
        void nonPositiveSilenceFactorRejected() {
            assertThrows(IllegalArgumentException.class, () -> new EdgeClientCore(
                    clock, monitor, null, StrongReadKeyClass.DEFAULT, sink, 250, 0));
        }

        @Test
        void nullSinkRejected() {
            assertThrows(NullPointerException.class, () -> new EdgeClientCore(
                    clock, monitor, null, StrongReadKeyClass.DEFAULT, null, 250, 8));
        }

        @Test
        void nullStrongReadKeyClassRejected() {
            assertThrows(NullPointerException.class, () -> new EdgeClientCore(
                    clock, monitor, null, null, sink, 250, 8));
        }
    }

    // -----------------------------------------------------------------------
    // Silence-window boundary (exact threshold: silenceFactor × heartbeatMs)
    // -----------------------------------------------------------------------

    @Nested
    class SilenceWindowBoundary {

        @Test
        void exactlyAtTheSilenceWindowDoesNotReconnect() {
            core.onFrame(new EdgeFrame.Heartbeat(0, clock.currentTimeMillis()));
            // silenceFactor(8) × heartbeatMs(250) = 2000ms. The check is "> window", so
            // exactly 2000ms of silence must NOT reconnect.
            clock.advance(2000);
            core.tick(clock.currentTimeMillis());
            assertFalse(core.hasDirective(), "exactly at the window must not reconnect (> window)");
        }

        @Test
        void onePastTheSilenceWindowReconnects() {
            core.onFrame(new EdgeFrame.Heartbeat(0, clock.currentTimeMillis()));
            clock.advance(2001);
            core.tick(clock.currentTimeMillis());
            assertTrue(core.hasDirective(), "one ms past the window must reconnect");
        }

        @Test
        void neverConnectedSessionDoesNotReconnectOnSilence() {
            // No heartbeat ever seen → the silence detector is dormant (connect is the shell's
            // concern, not a silence reconnect). A long idle must NOT queue a directive.
            clock.advance(100_000);
            core.tick(clock.currentTimeMillis());
            assertFalse(core.hasDirective());
        }
    }
}

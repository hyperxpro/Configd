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
 * Unit matrix for {@link EdgeClientCore} - the transport-agnostic edge client engine.
 *
 * <p>Covers the frame-handling matrix (SUBSCRIBE_OK mode, NOTIFY verify->filter->apply +
 * per-batch CURSOR_ACK, SNAPSHOT_* reassembly + cutover, backward-snapshot refusal,
 * HEARTBEAT covered-frontier including {@code latestSeq > cursor} never advancing it,
 * ERROR_CLOSE handling), the tick CURSOR_ACK + heartbeat-silence reconnect directive,
 * and the monotonic-read seam routing.
 */
class EdgeClientCoreTest {

    static final class TestClock implements Clock {
        long timeMs;
        TestClock(long initial) { this.timeMs = initial; }
        @Override public long currentTimeMillis() { return timeMs; }
        @Override public long nanoTime() { return timeMs * 1_000_000L; }
        void advance(long ms) { timeMs += ms; }
    }

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
        // testMode=true -> a monotonic_read violation throws (fails the test).
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

    private static List<EdgeFrame> snapshotFrames(ConfigSnapshot snap, long seq) {
        byte[] body = EdgeSnapshotCodec.serialize(snap);
        List<EdgeFrame.SnapshotChunk> chunks = EdgeSnapshotCodec.chunk(body, 4096);
        List<EdgeFrame> frames = new ArrayList<>();
        frames.add(new EdgeFrame.SnapshotBegin(seq, chunks.size(), body.length));
        frames.addAll(chunks);
        frames.add(new EdgeFrame.SnapshotEnd(seq));
        return frames;
    }

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
                    notif(3, 2, 3, clock.timeMs, "c", "3"))));
            assertEquals(1, core.cursor(), "cursor stops at the last contiguous applied seq");
            assertEquals(1, core.gapsDetected());
            assertEquals(1, sink.lastAck(), "ack reflects the real cursor, not the gapped seq");
        }

        @Test
        void staleNotifyIsRecordedNotApplied() {
            core.onFrame(new EdgeFrame.Notify(List.of(notif(1, 0, 1, clock.timeMs, "a", "1"))));
            // Re-deliver seq 1 (toVersion 1 <= current 1) -> STALE_DELTA, no overwrite.
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
            for (long s = 1; s <= 8; s++) {
                core.onFrame(new EdgeFrame.Notify(List.of(
                        notif(s, s - 1, s, clock.timeMs, "k" + s, "v"))));
            }
            assertEquals(8, core.cursor());
            int acksBefore = sink.acks().size();

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
            core.onFrame(new EdgeFrame.Notify(List.of(notif(1, 0, 1, clock.timeMs, "a", "1"))));
            core.onFrame(new EdgeFrame.Notify(List.of(notif(3, 2, 3, clock.timeMs, "c", "3"))));
            assertEquals(1, core.gapsDetected());

            // A forward snapshot at seq 3 heals it (cutover + resetGap).
            for (EdgeFrame f : snapshotFrames(snapshot(3, "a", "1", "b", "2", "c", "3"), 3)) {
                core.onFrame(f);
            }
            assertEquals(3, core.cursor());
            assertTrue(core.get("c").found());

            core.onFrame(new EdgeFrame.Notify(List.of(notif(4, 3, 4, clock.timeMs, "d", "4"))));
            assertEquals(4, core.cursor());
        }

        @Test
        void chunkWithoutBeginThrows() {
            assertThrows(IllegalStateException.class,
                    () -> core.onFrame(new EdgeFrame.SnapshotChunk(0, new byte[]{1, 2, 3})));
        }
    }

    @Nested
    class HeartbeatFrontier {

        private void advanceCursorTo(long n) {
            for (long s = 1; s <= n; s++) {
                core.onFrame(new EdgeFrame.Notify(List.of(
                        notif(s, s - 1, s, clock.timeMs, "k" + s, "v"))));
            }
        }

        @Test
        void cursorMatchedHeartbeatKeepsIdleEdgeCurrent() {
            advanceCursorTo(5);
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
            advanceCursorTo(5);
            clock.advance(600); // would be STALE without a frontier advance
            // Server says latestSeq=8 > cursor=5 - genuinely behind by 3.
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
            core.onFrame(new EdgeFrame.Heartbeat(0, clock.currentTimeMillis()));
            assertFalse(core.hasDirective());

            // Stay silent past silenceFactor x heartbeatMs (8 x 250 = 2000ms).
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
            clock.advance(5000);
            core.tick(clock.currentTimeMillis());
            assertFalse(core.hasDirective(),
                    "silence window restarts after reconnect (waits for first heartbeat)");
        }
    }

    @Nested
    class GapResubscribeDirective {

        @Test
        void gapQueuesResubscribeAtCurrentCursorOnce() {
            core.onFrame(new EdgeFrame.Notify(List.of(notif(1, 0, 1, clock.timeMs, "a", "1"))));
            core.onFrame(new EdgeFrame.Notify(List.of(notif(5, 4, 5, clock.timeMs, "a", "5"))));

            assertEquals(1, core.gapsDetected());
            var r = (EdgeClientCore.ConnectionDirective.ReconnectNextEndpoint) core.pollDirective();
            assertNotNull(r, "a detected gap must queue the resubscribe recovery");
            assertEquals(1, r.resumeCursor(),
                    "resubscribe carries the CURRENT cursor — the server's TAIL/SNAPSHOT_FIRST "
                            + "decision resolves replay vs re-bootstrap (no new wire surface)");
            assertTrue(r.reason().startsWith("gap-detected:"), r.reason());

            core.onFrame(new EdgeFrame.Notify(List.of(notif(6, 5, 6, clock.timeMs, "a", "6"))));
            assertEquals(2, core.gapsDetected());
            assertFalse(core.hasDirective(), "one directive per wedge (reconnectPending latch)");
        }

        @Test
        void gapIsSuppressedWhileAServerSnapshotIsAlreadyHealingUs() {
            core.onFrame(new EdgeFrame.Notify(List.of(notif(1, 0, 1, clock.timeMs, "a", "1"))));
            // The server demoted us: a snapshot flow is owed - the in-session heal is
            // already in progress, so a racing gap must NOT bounce the connection.
            core.onFrame(new EdgeFrame.ErrorClose(ErrorCode.DEMOTED_TO_CATCHUP, "ack-lag"));
            core.onFrame(new EdgeFrame.Notify(List.of(notif(5, 4, 5, clock.timeMs, "a", "5"))));

            assertEquals(1, core.gapsDetected());
            assertFalse(core.hasDirective(), "gap suppressed while a snapshot is in flight");

            for (EdgeFrame f : snapshotFrames(snapshot(5, "a", "5"), 5)) {
                core.onFrame(f);
            }
            core.onFrame(new EdgeFrame.Notify(List.of(notif(9, 8, 9, clock.timeMs, "a", "9"))));
            assertTrue(core.hasDirective(), "after the snapshot lands the recovery re-arms");
            var r = (EdgeClientCore.ConnectionDirective.ReconnectNextEndpoint) core.pollDirective();
            assertEquals(5, r.resumeCursor());
        }

        @Test
        void snapshotFirstModeSuppressesTheGapDirectiveUntilTheSnapshotLands() {
            core.onFrame(new EdgeFrame.SubscribeOk(10, EdgeFrame.Mode.SNAPSHOT_FIRST));
            core.onFrame(new EdgeFrame.Notify(List.of(notif(9, 8, 9, clock.timeMs, "a", "9"))));
            assertFalse(core.hasDirective(),
                    "SNAPSHOT_FIRST handshake promises a snapshot — no resubscribe churn");
        }
    }

    @Nested
    class DisconnectedRebootstrapDirective {

        @Test
        void liveTransitionIntoDisconnectedQueuesResubscribeAtCurrentCursor() {
            // Frontier established (CURRENT) and observed by a tick.
            core.onFrame(new EdgeFrame.Notify(List.of(notif(1, 0, 1, clock.timeMs, "a", "1"))));
            core.tick(clock.currentTimeMillis());
            assertEquals(StalenessTracker.State.CURRENT, core.stalenessState());
            assertFalse(core.hasDirective());

            // The frontier stalls past the DISCONNECTED threshold (30s) with NO heartbeat
            // ever seen (so this is not the silence detector firing).
            clock.advance(31_000);
            core.tick(clock.currentTimeMillis());

            assertEquals(StalenessTracker.State.DISCONNECTED, core.stalenessState());
            assertEquals(1, core.disconnectedRebootstraps());
            var r = (EdgeClientCore.ConnectionDirective.ReconnectNextEndpoint) core.pollDirective();
            assertNotNull(r);
            assertEquals(1, r.resumeCursor(),
                    "CT-06: re-bootstrap resubscribes at the CURRENT cursor, NOT 0 — the "
                            + "server decides; cursor 0 is reserved for the poison terminal path");
            assertTrue(r.reason().startsWith("disconnected-rebootstrap:"), r.reason());

            core.onReconnected();
            clock.advance(1_000);
            core.tick(clock.currentTimeMillis());
            assertFalse(core.hasDirective(),
                    "re-baselined at reconnect: an entry observed while disconnected must "
                            + "not bounce the fresh connection");
            assertEquals(1, core.disconnectedRebootstraps());
        }

        @Test
        void bootStateNeverFiresTheRebootstrap() {
            // The boot state IS DISCONNECTED (no frontier yet): process start is the initial
            // bootstrap, not a re-bootstrap - ticking an idle fresh core fires nothing.
            assertEquals(StalenessTracker.State.DISCONNECTED, core.stalenessState());
            for (int i = 0; i < 5; i++) {
                clock.advance(10_000);
                core.tick(clock.currentTimeMillis());
            }
            assertFalse(core.hasDirective());
            assertEquals(0, core.disconnectedRebootstraps());
        }

        @Test
        void recoveryReArmsAfterTheEdgeHealsToCurrent() {
            core.onFrame(new EdgeFrame.Notify(List.of(notif(1, 0, 1, clock.timeMs, "a", "1"))));
            core.tick(clock.currentTimeMillis());
            clock.advance(31_000);
            core.tick(clock.currentTimeMillis());
            assertEquals(1, core.disconnectedRebootstraps());
            core.pollDirective();
            core.onReconnected();

            core.onFrame(new EdgeFrame.Notify(List.of(notif(2, 1, 2, clock.timeMs, "a", "2"))));
            core.tick(clock.currentTimeMillis());
            assertEquals(StalenessTracker.State.CURRENT, core.stalenessState());
            clock.advance(31_000);
            core.tick(clock.currentTimeMillis());
            assertEquals(2, core.disconnectedRebootstraps(), "one firing per DISCONNECTED entry");
        }
    }

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
            // testMode=true -> a cursor ahead of the store throws via the monotonic-read seam.
            assertThrows(AssertionError.class,
                    () -> core.get("a", new VersionCursor(5, 0)));
            assertEquals(1L,
                    metrics.counter("invariant.violation.monotonic_read").get(),
                    "the read store routes through the real monotonic_read seam");
        }
    }

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

    @Nested
    class SilenceWindowBoundary {

        @Test
        void exactlyAtTheSilenceWindowDoesNotReconnect() {
            core.onFrame(new EdgeFrame.Heartbeat(0, clock.currentTimeMillis()));
            // silenceFactor(8) x heartbeatMs(250) = 2000ms. The check is "> window", so
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
            // No heartbeat ever seen -> the silence detector is dormant (connect is the shell's
            // concern, not a silence reconnect). A long idle must NOT queue a directive.
            clock.advance(100_000);
            core.tick(clock.currentTimeMillis());
            assertFalse(core.hasDirective());
        }
    }

    @Nested
    class VerificationSeam {

        @Test
        void signedDeltaWithNoVerifierIsRejectedOnItsOwnCounterNotAsGap() {
            // Fail-closed: a SIGNED delta on a core with no verifier configured is rejected.
            // The rejection is a verification event, NOT a gap - edge_gaps_total must stay
            // an honest gap signal.
            ConfigDelta signed = new ConfigDelta(0, 1,
                    List.of(new ConfigMutation.Put("a", bytes("1"))), new byte[64]);
            core.onFrame(new EdgeFrame.Notify(List.of(
                    new CommitNotification(1, clock.timeMs, signed))));
            assertEquals(1, core.verifyRejections(), "rejection counted on verifyRejections");
            assertEquals(0, core.gapsDetected(), "a verification rejection is not a gap");
            assertEquals(0, core.cursor(), "rejected delta never advances the cursor");
            assertFalse(core.get("a").found(), "rejected delta never applies");
        }

        @Test
        void verifierConstructorAcceptsAndAppliesUnsignedlessFlowViaEpochDir(
                @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
            java.security.KeyPair kp =
                    java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            io.configd.store.ConfigSigner leaderSigner = new io.configd.store.ConfigSigner(kp);
            io.configd.store.ConfigSigner edgeVerifier =
                    new io.configd.store.ConfigSigner(kp.getPublic());

            EdgeClientCore verified = new EdgeClientCore(clock, monitor,
                    metrics.counter(StalenessTracker.IMPLAUSIBLE_METRIC),
                    StrongReadKeyClass.DEFAULT, sink,
                    EdgeClientCore.DEFAULT_HEARTBEAT_MS, EdgeClientCore.DEFAULT_SILENCE_FACTOR,
                    edgeVerifier, tmp);

            ConfigDelta unsigned = new ConfigDelta(0, 1,
                    List.of(new ConfigMutation.Put("k", bytes("v"))), null, 1L, new byte[8]);
            byte[] sig = leaderSigner.sign(unsigned.signingPayload());
            ConfigDelta signed = new ConfigDelta(0, 1, unsigned.mutations(), sig, 1L, new byte[8]);
            verified.onFrame(new EdgeFrame.Notify(List.of(
                    new CommitNotification(1, clock.timeMs, signed))));
            assertEquals(1, verified.cursor(), "a correctly signed delta applies");
            assertEquals(0, verified.verifyRejections());

            // A tampered signature is rejected on the verification counter.
            ConfigDelta bad = new ConfigDelta(1, 2,
                    List.of(new ConfigMutation.Put("k", bytes("x"))), new byte[64], 2L, new byte[8]);
            verified.onFrame(new EdgeFrame.Notify(List.of(
                    new CommitNotification(2, clock.timeMs, bad))));
            assertEquals(1, verified.verifyRejections());
            assertEquals(1, verified.cursor(), "tampered delta never advances the cursor");

            // The epoch sidecar landed in the data dir (metadata only, no values).
            assertTrue(java.nio.file.Files.exists(tmp.resolve("epoch.lock")),
                    "epoch.lock persisted under the supplied dir");
        }
    }

    @Nested
    class SnapshotFrontierIntegrity {

        @Test
        void snapshotCutoverDoesNotTripImplausibilityCounter() {
            core.onFrame(new EdgeFrame.Notify(List.of(notif(1, 0, 1, clock.timeMs, "a", "1"))));
            long before = metrics.counter(StalenessTracker.IMPLAUSIBLE_METRIC).get();

            // A forward snapshot arrives over the wire. EdgeSnapshotCodec bodies carry no
            // commit timestamp (deserialize stamps 0 = unknown) - the cutover must record
            // the version WITHOUT a frontier advance, never as an implausible sample.
            for (EdgeFrame f : snapshotFrames(snapshot(5, "a", "1", "b", "2"), 5)) {
                core.onFrame(f);
            }
            assertEquals(5, core.cursor());
            assertEquals(before, metrics.counter(StalenessTracker.IMPLAUSIBLE_METRIC).get(),
                    "a legitimate snapshot cutover must not fire the CT-08 tripwire");

            // The frontier then heals from the post-snapshot tail (commitTs of the next
            // NOTIFY) - staleness returns to CURRENT.
            core.onFrame(new EdgeFrame.Notify(List.of(notif(6, 5, 6, clock.timeMs, "c", "3"))));
            assertEquals(StalenessTracker.State.CURRENT, core.stalenessState());
        }
    }
}

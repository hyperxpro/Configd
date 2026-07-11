package io.configd.distribution.fanout;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replay-horizon boundary matrix: cursor exactly-at / one-below / one-above the ring's
 * oldest-retained sequence, plus the lapped-after-TAIL-decision race.
 *
 * <p>The replay horizon is the ring's retention: a subscriber cursor at
 * {@code oldestRetainedSeq - 1} is exactly recoverable from the tail; one below is beyond
 * the horizon (GAP -> snapshot re-bootstrap). The interleaving is forced deterministically
 * (single-threaded publish between protocol steps - the only honest way to pin an exact
 * interleaving).
 *
 * <p>Writes keep flowing through every phase (decision, lap, snapshot, resume) and the
 * final edge-model state must byte-equal the authoritative cumulative state - the
 * exactly-once-over-effect judge.
 */
class ReplayHorizonBoundaryTest {

    private final FakeClock clock = new FakeClock(1_000L);
    private final RecordingTransportSink sink = new RecordingTransportSink();

    private HamtMap<String, VersionedValue> auth = HamtMap.empty();
    private long version;

    /** Publishes the next committed write into the buffer AND the authoritative state. */
    private void commit(FanOutBuffer buffer, String key, String val) {
        long seq = ++version;
        auth = auth.put(key, new VersionedValue(bytes(val), seq, 0L));
        buffer.publish(new CommitNotification(seq, 1_000L + seq,
                new ConfigDelta(seq - 1, seq, List.of(new ConfigMutation.Put(key, bytes(val))))));
    }

    /** Snapshot-equivalent CURRENT state at the current seq (the replay seam). */
    private ReplaySource liveReplaySource() {
        return new SnapshotReplaySource(() -> new ConfigSnapshot(auth, version, 0L));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static final class EdgeModel {
        final Map<String, byte[]> state = new HashMap<>();
        long version;
        int snapshotsApplied;

        /** Applies the frames the sink recorded since the last drain; returns ack seq. */
        long apply(List<EdgeFrame> frames) {
            byte[] snapshotBody = null;
            java.io.ByteArrayOutputStream chunks = null;
            long snapshotSeq = -1;
            for (EdgeFrame f : frames) {
                switch (f) {
                    case EdgeFrame.Notify n -> {
                        for (CommitNotification cn : n.notifications()) {
                            ConfigDelta d = cn.delta();
                            if (d.toVersion() <= version) {
                                continue; // stale (idempotent discard)
                            }
                            assertEquals(version, d.fromVersion(),
                                    "verbatim chain must be contiguous at the edge");
                            for (ConfigMutation m : d.mutations()) {
                                if (m instanceof ConfigMutation.Put p) {
                                    state.put(p.key(), p.value());
                                } else if (m instanceof ConfigMutation.Delete del) {
                                    state.remove(del.key());
                                }
                            }
                            version = d.toVersion();
                        }
                    }
                    case EdgeFrame.SnapshotBegin b -> {
                        chunks = new java.io.ByteArrayOutputStream();
                        snapshotSeq = b.snapshotSeq();
                    }
                    case EdgeFrame.SnapshotChunk c -> chunks.writeBytes(c.bytes());
                    case EdgeFrame.SnapshotEnd e -> {
                        snapshotBody = chunks.toByteArray();
                        ConfigSnapshot snap =
                                io.configd.distribution.wire.EdgeSnapshotCodec.deserialize(snapshotBody);
                        state.clear();
                        snap.data().forEach((k, vv) -> state.put(k, vv.value()));
                        version = e.snapshotSeq();
                        snapshotsApplied++;
                        assertEquals(snapshotSeq, e.snapshotSeq());
                    }
                    default -> { /* SUBSCRIBE_OK / HEARTBEAT / ErrorClose: no state effect */ }
                }
            }
            return version;
        }
    }

    private FanOutSessionCore session(FanOutBuffer buffer, FanOutConfig cfg) {
        return new FanOutSessionCore(buffer, liveReplaySource(), sink, cfg,
                FanOutSessionMetrics.NOOP, clock);
    }

    private static EdgeFrame.Subscribe subscribe(long resume) {
        return new EdgeFrame.Subscribe(true, List.of(), resume, -1L, "edge-h");
    }

    /** Capacity 8; seqs 1..20 published -> oldest retained 13; horizon edge cursor = 12. */
    private FanOutBuffer bufferLappedTo20() {
        FanOutBuffer buffer = new FanOutBuffer(8);
        for (int i = 1; i <= 20; i++) {
            commit(buffer, "k" + (i % 5), "v" + i);
        }
        assertEquals(13, buffer.oldestSeq(), "fixture: oldest retained seq");
        return buffer;
    }

    @Test
    void cursorOneBelowHorizonIsBeyondReplayAndGetsSnapshotFirst() {
        FanOutBuffer buffer = bufferLappedTo20();
        FanOutSessionCore s = session(buffer, C1StreamDriverLikeConfig.config());
        s.onSubscribe(subscribe(11)); // horizon edge is 12; 11 is one below -> beyond horizon

        assertEquals(EdgeFrame.Mode.SNAPSHOT_FIRST,
                sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode());

        // Concurrent writes between decision and the snapshot transfer.
        commit(buffer, "late", "L1");
        EdgeModel edge = new EdgeModel();
        runToConvergence(s, buffer, edge);
        assertTrue(edge.snapshotsApplied >= 1, "re-bootstrap path must snapshot");
        assertConverged(edge);
    }

    @Test
    void cursorExactlyAtHorizonEdgeReplaysFromTheTailWithoutSnapshot() {
        FanOutBuffer buffer = bufferLappedTo20();
        FanOutSessionCore s = session(buffer, C1StreamDriverLikeConfig.config());
        s.onSubscribe(subscribe(12)); // exactly at the edge: 13..20 are all retained

        assertEquals(EdgeFrame.Mode.TAIL,
                sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode());

        // The edge model resumes from 12 (it had applied 1..12 before "reconnecting").
        EdgeModel edge = new EdgeModel();
        seedEdgeAt(edge, 12);

        // First drain BEFORE any further write: the at-edge cursor is recoverable only
        // while nothing more is evicted - one more commit into the FULL ring would lap it
        // (exactly the lapped-after-TAIL race, pinned separately below).
        sink.clear();
        clock.advance(10);
        s.tick(clock.now());
        s.onCursorAck(edge.apply(sink.sent()));
        assertEquals(20, edge.version, "the whole retained tail replays in the first drain");

        // Concurrent writes RESUME during the replay phase; the edge tails them with no
        // snapshot - replay territory throughout.
        commit(buffer, "late", "L1");
        runToConvergence(s, buffer, edge);
        assertEquals(0, edge.snapshotsApplied,
                "exactly-at-horizon is REPLAY territory: no snapshot may be sent");
        assertConverged(edge);
    }

    @Test
    void cursorOneAboveHorizonEdgeAlsoReplays() {
        FanOutBuffer buffer = bufferLappedTo20();
        FanOutSessionCore s = session(buffer, C1StreamDriverLikeConfig.config());
        s.onSubscribe(subscribe(13));

        assertEquals(EdgeFrame.Mode.TAIL,
                sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode());
        EdgeModel edge = new EdgeModel();
        seedEdgeAt(edge, 13);
        runToConvergence(s, buffer, edge);
        assertEquals(0, edge.snapshotsApplied);
        assertConverged(edge);
    }

    @Test
    void lappedAfterTailDecisionSelfHealsViaGapDemoteSnapshotResume() {
        FanOutBuffer buffer = bufferLappedTo20();
        FanOutSessionCore s = session(buffer, C1StreamDriverLikeConfig.config());

        // Step 1: the server decides TAIL for the horizon-edge cursor (12 is recoverable).
        s.onSubscribe(subscribe(12));
        assertEquals(EdgeFrame.Mode.TAIL,
                sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode());

        // Step 2: the concurrent writer LAPS the cursor between the decision and the first
        // drain - 10 more commits into the capacity-8 ring evict everything <= 22 > 12.
        for (int i = 21; i <= 30; i++) {
            commit(buffer, "k" + (i % 5), "v" + i);
        }
        assertTrue(buffer.oldestSeq() > 13, "fixture: the ring genuinely lapped the cursor");

        // Step 3: the first drain hits the GAP and demotes - the deterministic forcing of
        // exactly this race.
        sink.clear();
        s.tick(clock.now());
        assertEquals(FanOutSessionCore.SessionState.CATCHUP, s.state(),
                "lapped-after-TAIL must demote to catch-up, never stream a hole");
        assertEquals(DemotionEvent.REASON_GAP, s.lastDemotion().reason());
        assertFalse(sink.sentOfType(EdgeFrame.Notify.class).stream()
                        .anyMatch(n -> !n.notifications().isEmpty()),
                "no NOTIFY may leak across the gap");

        // Step 4: writes keep flowing through the snapshot + resume; the edge converges on
        // the cumulative effect (exactly-once over effect - no hole, no double apply).
        EdgeModel edge = new EdgeModel();
        seedEdgeAt(edge, 12);
        commit(buffer, "during-snapshot", "S1");
        runToConvergence(s, buffer, edge);
        assertTrue(edge.snapshotsApplied >= 1, "the self-heal path is snapshot re-bootstrap");
        assertConverged(edge);
    }

    /** Seeds the edge model as if it had applied the authoritative prefix at or before seq. */
    private void seedEdgeAt(EdgeModel edge, long seq) {
        // Rebuild the prefix deterministically: keys k0..k4 hold v_i for the largest i <= seq
        // with i % 5 == key index (the commit pattern above).
        for (int k = 0; k < 5; k++) {
            long best = -1;
            for (long i = 1; i <= seq; i++) {
                if (i % 5 == k) {
                    best = i;
                }
            }
            if (best > 0) {
                edge.state.put("k" + k, bytes("v" + best));
            }
        }
        edge.version = seq;
    }

    /**
     * Drives the session (tick -> edge applies -> CURSOR_ACK) until the edge reaches the
     * authoritative version, with a hard bound (no sleeps - pure logical stepping).
     */
    private void runToConvergence(FanOutSessionCore s, FanOutBuffer buffer, EdgeModel edge) {
        for (int step = 0; step < 50 && edge.version < version; step++) {
            sink.clear();
            clock.advance(10);
            s.tick(clock.now());
            long ack = edge.apply(sink.sent());
            s.onCursorAck(ack);
        }
        assertEquals(version, edge.version, "edge must converge to the authoritative seq");
    }

    private void assertConverged(EdgeModel edge) {
        int authCount = 0;
        for (Map.Entry<String, byte[]> e : edge.state.entrySet()) {
            VersionedValue vv = auth.get(e.getKey());
            assertTrue(vv != null, "edge holds a key the authority does not: " + e.getKey());
            assertArrayEquals(vv.value(), e.getValue(),
                    "value divergence at key " + e.getKey());
        }
        // And the edge holds EVERY authoritative key (effect-complete).
        var keys = new java.util.ArrayList<String>();
        auth.forEach((k, v) -> keys.add(k));
        for (String k : keys) {
            assertTrue(edge.state.containsKey(k), "missing key " + k);
            authCount++;
        }
        assertEquals(authCount, edge.state.size());
    }

    /** The sim-scaled thresholds (ack-lag 2) so recovery exercises at test scale. */
    private static final class C1StreamDriverLikeConfig {
        static FanOutConfig config() {
            return new FanOutConfig(64, 80, 64, 262_144, 2L, 250L, 5L, 1_048_576);
        }
    }

    /** Captures the subscribe-time decision the session reports. */
    private static final class ModeCapture implements FanOutSessionMetrics {
        Boolean snapshotFirst;
        Long horizonDistance;
        int calls;
        @Override public void onNotifyBatch(int n, int bytes) { }
        @Override public void onQueueDepth(int depth) { }
        @Override public void onSlowConsumerWarning() { }
        @Override public void onDemotion(String reason) { }
        @Override public void onSnapshotTransfer() { }
        @Override public void onHeartbeat() { }
        @Override public void onSessionClosed(String reason) { }
        @Override public void onSubscribeMode(boolean snapshotFirst, long horizonDistance) {
            this.snapshotFirst = snapshotFirst;
            this.horizonDistance = horizonDistance;
            this.calls++;
        }
    }

    private ModeCapture subscribeAndCapture(FanOutBuffer buffer, long cursor) {
        ModeCapture capture = new ModeCapture();
        FanOutSessionCore s = new FanOutSessionCore(buffer, liveReplaySource(), sink,
                C1StreamDriverLikeConfig.config(), capture, clock);
        s.onSubscribe(subscribe(cursor));
        assertEquals(1, capture.calls, "exactly one decision per subscribe");
        return capture;
    }

    @Test
    void subscribeModeMetricReportsTheDecisionAndTheExactHorizonDistance() {
        // Empty ring (nothing evicted, nothing retained): TAIL; distance = cursor + 1.
        ModeCapture empty = subscribeAndCapture(new FanOutBuffer(8), 5);
        assertEquals(false, empty.snapshotFirst);
        assertEquals(6L, empty.horizonDistance,
                "empty ring reports cursor + 1 (trivially recoverable)");

        // Lapped ring, oldest retained 13 -> horizon edge is cursor 12.
        ModeCapture below = subscribeAndCapture(bufferLappedTo20(), 11);
        assertEquals(true, below.snapshotFirst, "one below the edge ⇒ re-bootstrap");
        assertEquals(-1L, below.horizonDistance, "11 − (13 − 1) = −1: beyond the horizon");

        auth = HamtMap.empty(); version = 0; // fresh fixture state per buffer
        ModeCapture atEdge = subscribeAndCapture(bufferLappedTo20(), 12);
        assertEquals(false, atEdge.snapshotFirst, "exactly at the edge ⇒ replay");
        assertEquals(0L, atEdge.horizonDistance, "12 − (13 − 1) = 0: exactly at the edge");

        auth = HamtMap.empty(); version = 0;
        ModeCapture above = subscribeAndCapture(bufferLappedTo20(), 14);
        assertEquals(false, above.snapshotFirst);
        assertEquals(2L, above.horizonDistance, "14 − (13 − 1) = 2: inside the horizon");
    }
}

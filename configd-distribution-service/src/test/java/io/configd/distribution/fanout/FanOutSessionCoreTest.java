package io.configd.distribution.fanout;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeSnapshotCodec;
import io.configd.distribution.wire.ErrorCode;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit matrix for {@link FanOutSessionCore}: SUBSCRIBE_OK mode decision, drain/batch
 * boundaries, Gap->snapshot->resume cursor continuity, heartbeat cadence, and ack
 * accounting. Uses a real {@link FanOutBuffer} + {@link SnapshotReplaySource} and a
 * controllable {@link RecordingTransportSink} / {@link FakeClock} (no threads, no
 * wall-clock).
 */
class FanOutSessionCoreTest {

    private final FakeClock clock = new FakeClock(1_000L);
    private final RecordingTransportSink sink = new RecordingTransportSink();

    private FanOutSessionCore session(FanOutBuffer buffer, ReplaySource replay, FanOutConfig cfg) {
        return new FanOutSessionCore(buffer, replay, sink, cfg,
                FanOutSessionMetrics.NOOP, clock);
    }

    private static ReplaySource snapshotAt(long version, String... kv) {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            data = data.put(kv[i], new VersionedValue(
                    kv[i + 1].getBytes(StandardCharsets.UTF_8), version, 0L));
        }
        ConfigSnapshot snap = new ConfigSnapshot(data, version, 0L);
        return new SnapshotReplaySource(() -> snap);
    }

    private static CommitNotification put(long seq, String key, String val) {
        return new CommitNotification(seq, 1_000L + seq,
                new ConfigDelta(seq - 1, seq,
                        List.of(new ConfigMutation.Put(key, val.getBytes(StandardCharsets.UTF_8)))));
    }

    private static EdgeFrame.Subscribe subscribe(long resume) {
        return new EdgeFrame.Subscribe(true, List.of(), resume, -1L, "edge-1");
    }

    // ---- SUBSCRIBE_OK mode decision matrix ---------------------------------

    @Test
    void subscribeFreshOnEmptyBufferChoosesTail() {
        FanOutBuffer buffer = new FanOutBuffer(16);
        FanOutSessionCore s = session(buffer, snapshotAt(0), FanOutConfig.defaults());
        s.onSubscribe(subscribe(0));
        EdgeFrame.SubscribeOk ok = sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0);
        assertEquals(EdgeFrame.Mode.TAIL, ok.mode());
        assertEquals(SessionState.streaming(), s.state());
    }

    @Test
    void subscribeCaughtUpCursorChoosesTail() {
        FanOutBuffer buffer = new FanOutBuffer(16);
        for (long i = 1; i <= 5; i++) {
            buffer.publish(put(i, "k" + i, "v"));
        }
        // cursor at the latest seq - nothing pending - TAIL.
        FanOutSessionCore s = session(buffer, snapshotAt(5), FanOutConfig.defaults());
        s.onSubscribe(subscribe(5));
        assertEquals(EdgeFrame.Mode.TAIL, sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode());
    }

    @Test
    void subscribeCursorBehindEvictedTailChoosesSnapshotFirst() {
        // Tiny buffer: publish past the cursor so seq 1 is evicted, then a stale cursor GAPs.
        FanOutBuffer buffer = new FanOutBuffer(4);
        for (long i = 1; i <= 10; i++) {
            buffer.publish(put(i, "k" + i, "v")); // evicts oldest, lastEvictedSeq advances
        }
        FanOutSessionCore s = session(buffer, snapshotAt(10), FanOutConfig.defaults());
        s.onSubscribe(subscribe(1)); // cursor 1 is long gone -> GAP -> SNAPSHOT_FIRST
        assertEquals(EdgeFrame.Mode.SNAPSHOT_FIRST,
                sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode());
        assertEquals(SessionState.catchup(), s.state());
    }

    @Test
    void freshSubscriberWithBacklogOverQueueChoosesSnapshotFirst() {
        // queueFrames=4, backlog 6 (> 4) at cursor 0 -> snapshot is cheaper -> SNAPSHOT_FIRST.
        FanOutBuffer buffer = new FanOutBuffer(64);
        for (long i = 1; i <= 6; i++) {
            buffer.publish(put(i, "k" + i, "v"));
        }
        FanOutConfig cfg = new FanOutConfig(4, 80, 64, 262_144, 8_192L, 250L, 5L, 1_048_576);
        FanOutSessionCore s = session(buffer, snapshotAt(6), cfg);
        s.onSubscribe(subscribe(0));
        assertEquals(EdgeFrame.Mode.SNAPSHOT_FIRST,
                sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode());
    }

    // ---- drain / batch boundaries ------------------------------------------

    @Test
    void tickDrainsNotificationsInVerbatimAscendingOrder() {
        // Subscribe on the EMPTY buffer (TAIL), then publish. Note: decideMode promotes a
        // cursor-0 subscriber to SNAPSHOT_FIRST once data exists, so streaming-mechanics
        // fixtures subscribe first (the live-tail shape this test pins is unchanged).
        FanOutBuffer buffer = new FanOutBuffer(64);
        FanOutSessionCore s = session(buffer, snapshotAt(5), FanOutConfig.defaults());
        s.onSubscribe(subscribe(0));
        for (long i = 1; i <= 5; i++) {
            buffer.publish(put(i, "k" + i, "v" + i));
        }
        sink.clear();
        s.tick(clock.now());

        List<EdgeFrame.Notify> notifies = sink.sentOfType(EdgeFrame.Notify.class);
        assertEquals(1, notifies.size(), "default batch cap 64 fits all 5 in one frame");
        List<CommitNotification> ns = notifies.get(0).notifications();
        assertEquals(5, ns.size());
        for (int i = 0; i < ns.size(); i++) {
            assertEquals(i + 1, ns.get(i).seq(), "verbatim ascending seq chain");
        }
        assertEquals(5L, s.cursor());
    }

    @Test
    void batchMaxNotificationsSplitsIntoMultipleFrames() {
        FanOutBuffer buffer = new FanOutBuffer(64);
        FanOutConfig cfg = new FanOutConfig(64, 80, 3 /* batchMax=3 */, 262_144, 8_192L, 250L, 5L, 1_048_576);
        FanOutSessionCore s = session(buffer, snapshotAt(10), cfg);
        s.onSubscribe(subscribe(0)); // on the empty buffer: TAIL (empty => decideMode returns TAIL)
        for (long i = 1; i <= 10; i++) {
            buffer.publish(put(i, "k" + i, "v"));
        }
        sink.clear();
        s.tick(clock.now());

        List<EdgeFrame.Notify> notifies = sink.sentOfType(EdgeFrame.Notify.class);
        assertEquals(4, notifies.size(), "10 notifications / batch 3 = 4 frames (3,3,3,1)");
        assertEquals(3, notifies.get(0).notifications().size());
        assertEquals(1, notifies.get(3).notifications().size());
        // Concatenation is the contiguous chain 1..10, no dup/skip.
        long expected = 1;
        for (EdgeFrame.Notify n : notifies) {
            for (CommitNotification cn : n.notifications()) {
                assertEquals(expected++, cn.seq());
            }
        }
        assertEquals(11, expected);
    }

    // ---- Gap -> snapshot -> resume, cursor continuity ----------------------

    @Test
    void gapMidStreamDemotesThenSnapshotsThenResumesTail() {
        FanOutBuffer buffer = new FanOutBuffer(4);
        buffer.publish(put(1, "k1", "v1"));
        buffer.publish(put(2, "k2", "v2"));
        // The replay source mirrors production: it returns the store's CURRENT state at the
        // current version. We track that via a mutable holder so the snapshot seq equals the
        // buffer's latest after the burst (a clean resume point, no second GAP).
        long[] snapVersion = {2L};
        java.util.Map<String, String>[] snapKv = new java.util.Map[]{
                java.util.Map.of("k1", "v1", "kS", "vS")};
        ReplaySource replay = () -> {
            HamtMap<String, VersionedValue> data = HamtMap.empty();
            for (var e : snapKv[0].entrySet()) {
                data = data.put(e.getKey(),
                        new VersionedValue(e.getValue().getBytes(StandardCharsets.UTF_8), snapVersion[0], 0L));
            }
            return new ReplaySource.Replay(new ConfigSnapshot(data, snapVersion[0], 0L), snapVersion[0]);
        };
        FanOutSessionCore s = session(buffer, replay, FanOutConfig.defaults());
        s.onSubscribe(subscribe(0));
        s.tick(clock.now()); // streams 1,2; cursor=2
        assertEquals(2L, s.cursor());

        // Now evict past the cursor: publish many so seq 2's successor (3) is evicted.
        for (long i = 3; i <= 30; i++) {
            buffer.publish(put(i, "k" + i, "v"));
        }
        snapVersion[0] = 30L; // the store advanced to 30 (the realistic replay point)
        sink.clear();
        s.tick(clock.now()); // readSince(2) GAPs -> demote
        assertEquals(SessionState.catchup(), s.state());
        EdgeFrame.ErrorClose demoteNotice = sink.sentOfType(EdgeFrame.ErrorClose.class).get(0);
        assertEquals(ErrorCode.DEMOTED_TO_CATCHUP, demoteNotice.code());
        assertEquals(DemotionEvent.REASON_GAP, demoteNotice.message());
        assertNotNull(s.lastDemotion());
        assertEquals(2L, s.lastDemotion().cursor(), "cursor evidence in demotion event");

        sink.clear();
        s.tick(clock.now()); // performs snapshot transfer, resumes tail from seq 30
        List<EdgeFrame.SnapshotBegin> begins = sink.sentOfType(EdgeFrame.SnapshotBegin.class);
        List<EdgeFrame.SnapshotChunk> chunks = sink.sentOfType(EdgeFrame.SnapshotChunk.class);
        List<EdgeFrame.SnapshotEnd> ends = sink.sentOfType(EdgeFrame.SnapshotEnd.class);
        assertEquals(1, begins.size());
        assertEquals(30L, begins.get(0).snapshotSeq());
        assertEquals(begins.get(0).chunkCount(), chunks.size());
        assertEquals(1, ends.size());
        assertEquals(30L, ends.get(0).snapshotSeq());
        assertEquals(30L, s.cursor(), "cursor jumps to snapshot seq");
        assertEquals(SessionState.streaming(), s.state(), "resumes TAIL after snapshot (clean resume point)");

        // Reassembled chunks restore the snapshot the replay source produced.
        byte[] body = EdgeSnapshotCodec.reassemble(chunks);
        ConfigSnapshot restored = EdgeSnapshotCodec.deserialize(body);
        assertEquals(30L, restored.version());
        assertNotNull(restored.get("kS"));
    }

    // ---- bounded queue / ack accounting ------------------------------------

    @Test
    void unackedFramesNeverExceedQueueFramesAndAckReleasesThem() {
        // Subscribe caught-up (cursor=latest) so the fresh-bootstrap snapshot heuristic does
        // not fire; THEN publish a backlog larger than the queue so a pure STREAMING overflow
        // demotes. queueFrames=4, batchMax=1 -> each notification is its own frame.
        FanOutBuffer buffer = new FanOutBuffer(256);
        buffer.publish(put(1, "k1", "v"));
        FanOutConfig cfg = new FanOutConfig(4, 80, 1, 262_144, 8_192L, 250L, 5L, 1_048_576);
        FanOutSessionCore s = session(buffer, snapshotAt(7), cfg);
        s.onSubscribe(subscribe(1)); // caught up at seq 1 -> TAIL
        assertEquals(EdgeFrame.Mode.TAIL, sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode());
        for (long i = 2; i <= 7; i++) {
            buffer.publish(put(i, "k" + i, "v")); // 6 pending after subscribe
        }
        sink.clear();
        s.tick(clock.now());
        // 6 pending, queue cap 4 -> 4 frames out, then queue overflow demotes.
        assertTrue(s.inFlightFrames() <= 4, "unacked frames must never exceed queueFrames");
        assertEquals(SessionState.catchup(), s.state(), "overflow demotes to catch-up");
        assertEquals(DemotionEvent.REASON_QUEUE_OVERFLOW, s.lastDemotion().reason());
    }

    @Test
    void cursorAckReleasesInFlightFramesBelowThreshold() {
        FanOutBuffer buffer = new FanOutBuffer(256);
        FanOutConfig cfg = new FanOutConfig(8, 80, 1, 262_144, 8_192L, 250L, 5L, 1_048_576);
        FanOutSessionCore s = session(buffer, snapshotAt(3), cfg);
        s.onSubscribe(subscribe(0)); // on the empty buffer: TAIL (empty => decideMode returns TAIL)
        for (long i = 1; i <= 3; i++) {
            buffer.publish(put(i, "k" + i, "v"));
        }
        s.tick(clock.now());
        assertEquals(3, s.inFlightFrames());
        s.onCursorAck(2);
        assertEquals(1, s.inFlightFrames(), "frames with maxSeq<=2 released");
        assertEquals(2L, s.lastAckedSeq());
        s.onCursorAck(1); // stale -> ignored
        assertEquals(2L, s.lastAckedSeq());
        assertEquals(1, s.inFlightFrames());
    }

    @Test
    void ackLagBreachDemotes() {
        FanOutBuffer buffer = new FanOutBuffer(64);
        for (long i = 1; i <= 5; i++) {
            buffer.publish(put(i, "k" + i, "v"));
        }
        // ackLagDemoteSeqs=2: after streaming 5 with no ack, cursor-lastAcked=5 > 2 -> demote.
        FanOutConfig cfg = new FanOutConfig(64, 80, 64, 262_144, 2L, 250L, 5L, 1_048_576);
        FanOutSessionCore s = session(buffer, snapshotAt(5), cfg);
        s.onSubscribe(subscribe(0));
        s.tick(clock.now()); // streams 1..5, cursor 5, lastAcked 0
        sink.clear();
        s.tick(clock.now()); // ack-lag 5 > 2 -> demote
        assertEquals(SessionState.catchup(), s.state());
        assertEquals(DemotionEvent.REASON_ACK_LAG, s.lastDemotion().reason());
    }

    // ---- prod-threshold ack-lag demotion (8192) ----------------------------
    // The integrated sim can only reach ackLagDemoteSeqs=2 (above: ackLagBreachDemotes).
    // This test exercises the PRODUCTION threshold (FanOutConfig.defaults()=8192) directly,
    // and pins the strict-'>' boundary so an off-by-one (>=) is caught. Construction note:
    // ack-lag is checked once at the TOP of drainStreaming, while queue-overflow is checked
    // per-frame inside the drain loop - so to make ACK_LAG (not QUEUE_OVERFLOW) fire, the
    // frame count at the breach must stay < queueFrames.
    // 8193 seqs / batch 64 = 129 frames < 256 queueFrames: ack-lag wins.

    @Test
    void prodThresholdAckLagOverThresholdDemotes() {
        FanOutConfig cfg = FanOutConfig.defaults(); // queueFrames=256, batch=64, ackLagDemoteSeqs=8192
        long over = cfg.ackLagDemoteSeqs() + 1; // 8193
        FanOutBuffer buffer = new FanOutBuffer(16_384);
        FanOutSessionCore s = session(buffer, snapshotAt(0), cfg);
        s.onSubscribe(subscribe(0)); // empty buffer -> TAIL -> STREAMING (no fresh-backlog snapshot)
        for (long i = 1; i <= over; i++) {
            buffer.publish(put(i, "k" + i, "v"));
        }
        sink.clear();
        s.tick(clock.now()); // streams all 8193 in one drain: 129 frames, cursor=8193, lastAcked=0
        assertEquals(over, s.cursor(), "all offered seqs streamed");
        assertTrue(s.inFlightFrames() < cfg.queueFrames(),
                "ack-lag must be reached BEFORE queue overflow (129 frames < 256) — else the cell "
                        + "would prove QUEUE_OVERFLOW, not ACK_LAG");
        assertEquals(SessionState.streaming(), s.state(), "no demotion yet (ack-lag checked next tick)");

        s.tick(clock.now()); // ack-lag check at top: 8193 - 0 = 8193 > 8192 -> demote
        assertEquals(SessionState.catchup(), s.state(),
                "a consumer lagging the offered cursor by > ackLagDemoteSeqs must demote at the prod threshold");
        assertEquals(DemotionEvent.REASON_ACK_LAG, s.lastDemotion().reason());
        assertEquals(over, s.lastDemotion().cursor(), "demotion event carries the offered cursor");
        assertEquals(0L, s.lastDemotion().lastAckedSeq(), "demotion event carries the (zero) acked position");
    }

    @Test
    void prodThresholdAckLagAtThresholdDoesNotDemote() {
        // The strict-'>' boundary: EXACTLY ackLagDemoteSeqs offered (8192) and unacked must
        // NOT demote. This is the discriminator that an off-by-one (cursor-lastAcked >= T)
        // would break (mutation M-acklag in EXP-005).
        FanOutConfig cfg = FanOutConfig.defaults();
        long atThreshold = cfg.ackLagDemoteSeqs(); // 8192
        FanOutBuffer buffer = new FanOutBuffer(16_384);
        FanOutSessionCore s = session(buffer, snapshotAt(0), cfg);
        s.onSubscribe(subscribe(0));
        for (long i = 1; i <= atThreshold; i++) {
            buffer.publish(put(i, "k" + i, "v"));
        }
        sink.clear();
        s.tick(clock.now()); // streams 8192: 128 frames, cursor=8192
        assertEquals(atThreshold, s.cursor());
        assertTrue(s.inFlightFrames() < cfg.queueFrames(), "no queue overflow at the threshold");
        s.tick(clock.now()); // ack-lag: 8192 - 0 = 8192, NOT > 8192 -> stays streaming
        assertEquals(SessionState.streaming(), s.state(),
                "exactly ackLagDemoteSeqs offered must NOT demote (strict >, not >=)");
        assertEquals(null, s.lastDemotion(), "no demotion event at the threshold");
    }

    // ---- wedged-but-open transport during a paced snapshot transfer --------
    // The would-block pause path when the transport never drains (wedged-but-open, not dead).
    // Characterizes the SAFE degradation and the resume-as-one-envelope guarantee. The only
    // detection proxy today is "stuck in CATCHUP + no SnapshotEnd + queue pinned" - no
    // dedicated stalled-transfer signal is emitted.

    @Test
    void wedgedTransportDuringSnapshotPausesSafelyThenResumesAsOneEnvelope() {
        // A multi-chunk snapshot so the transfer is genuinely paced (small chunk bytes).
        FanOutBuffer buffer = new FanOutBuffer(4);
        for (long i = 1; i <= 10; i++) {
            buffer.publish(put(i, "k" + i, "v" + i)); // evicts oldest; seq 1 long gone
        }
        // snapshotChunkBytes=64 with many keys -> body spans multiple chunks (paced transfer).
        FanOutConfig cfg = new FanOutConfig(256, 80, 64, 262_144, 8_192L, 250L, 5L, 64);
        ReplaySource replay = snapshotAt(10,
                "k1", "value-1", "k2", "value-2", "k3", "value-3", "k4", "value-4",
                "k5", "value-5", "k6", "value-6", "k7", "value-7", "k8", "value-8",
                "k9", "value-9", "k10", "value-10", "k11", "value-11", "k12", "value-12");
        FanOutSessionCore s = session(buffer, replay, cfg);
        s.onSubscribe(subscribe(1)); // cursor 1 evicted -> GAP -> SNAPSHOT_FIRST -> CATCHUP
        assertEquals(EdgeFrame.Mode.SNAPSHOT_FIRST,
                sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode());
        assertEquals(SessionState.catchup(), s.state());
        long cursorBeforeTransfer = s.cursor();
        sink.clear();

        // WEDGE the transport: every offer would-block. (performSnapshotTransfer offers
        // snapshot frames directly, treating refusal as would-block, NOT transport death,
        // so the session is NOT torn down.)
        sink.blockNextOffers(10_000);
        for (int tick = 0; tick < 20; tick++) {
            s.tick(clock.now());
            // Safe, bounded degradation: no cutover, no hot loop, no exception, no progress.
            assertEquals(SessionState.catchup(), s.state(),
                    "a wedged transport must keep the session paused in CATCHUP (no premature cutover)");
            assertEquals(cursorBeforeTransfer, s.cursor(),
                    "cursor must NOT advance while the snapshot transfer is wedged");
        }
        // Nothing was delivered: not even SNAPSHOT_BEGIN was accepted, and certainly no END.
        assertTrue(sink.sentOfType(EdgeFrame.SnapshotBegin.class).isEmpty(),
                "no snapshot frame is delivered while wedged");
        assertTrue(sink.sentOfType(EdgeFrame.SnapshotEnd.class).isEmpty(),
                "the transfer never completes (no cutover) while wedged");
        // The only detection proxy today is "stuck in CATCHUP + no SnapshotEnd / no
        // snapshot_transfers_total increment + queue pinned". Detection IS possible via the
        // proxy, so no new metric is emitted here.

        // UNWEDGE: the transport drains. The transfer resumes the SAME paused envelope and
        // completes - exactly one BEGIN + one END (not a restarted/torn envelope), then cutover.
        sink.blockNextOffers(0);
        s.tick(clock.now());
        assertEquals(SessionState.streaming(), s.state(), "resumes STREAMING after the transfer completes");
        assertEquals(10L, s.cursor(), "cursor jumps to the snapshot seq exactly once, on END acceptance");
        List<EdgeFrame.SnapshotBegin> begins = sink.sentOfType(EdgeFrame.SnapshotBegin.class);
        List<EdgeFrame.SnapshotChunk> chunks = sink.sentOfType(EdgeFrame.SnapshotChunk.class);
        List<EdgeFrame.SnapshotEnd> ends = sink.sentOfType(EdgeFrame.SnapshotEnd.class);
        assertEquals(1, begins.size(), "exactly ONE SnapshotBegin — the wedge did not restart the envelope");
        assertEquals(1, ends.size(), "exactly ONE SnapshotEnd");
        assertEquals(begins.get(0).chunkCount(), chunks.size(),
                "all chunks delivered contiguously after resume (single envelope, RR-102)");
        assertTrue(begins.get(0).chunkCount() > 1, "fixture: the snapshot must be multi-chunk to be a 'paced' transfer");
    }

    // ---- heartbeat cadence -------------------------------------------------

    @Test
    void heartbeatEmittedAfterIdleIntervalOnly() {
        FanOutBuffer buffer = new FanOutBuffer(64);
        FanOutConfig cfg = new FanOutConfig(64, 80, 64, 262_144, 8_192L, 100L, 5L, 1_048_576);
        FanOutSessionCore s = session(buffer, snapshotAt(0), cfg);
        s.onSubscribe(subscribe(0));
        sink.clear();
        s.tick(1_000L); // anchors cadence, no heartbeat yet
        assertTrue(sink.sentOfType(EdgeFrame.Heartbeat.class).isEmpty());
        s.tick(1_050L); // 50ms < 100ms -> no heartbeat
        assertTrue(sink.sentOfType(EdgeFrame.Heartbeat.class).isEmpty());
        s.tick(1_100L); // 100ms elapsed -> heartbeat
        assertEquals(1, sink.sentOfType(EdgeFrame.Heartbeat.class).size());
        s.tick(1_150L); // 50ms since last heartbeat -> none
        assertEquals(1, sink.sentOfType(EdgeFrame.Heartbeat.class).size());
        s.tick(1_200L); // another 100ms -> second heartbeat
        assertEquals(2, sink.sentOfType(EdgeFrame.Heartbeat.class).size());
    }

    @Test
    void notifyTrafficResetsHeartbeatCadence() {
        FanOutBuffer buffer = new FanOutBuffer(64);
        FanOutConfig cfg = new FanOutConfig(64, 80, 64, 262_144, 100L, 100L, 5L, 1_048_576);
        FanOutSessionCore s = session(buffer, snapshotAt(0), cfg);
        s.onSubscribe(subscribe(0));
        s.tick(1_000L); // anchor
        buffer.publish(put(1, "k1", "v"));
        s.tick(1_090L); // streams -> traffic at 1090
        assertFalse(sink.sentOfType(EdgeFrame.Notify.class).isEmpty());
        sink.clear();
        s.tick(1_150L); // only 60ms since traffic -> no heartbeat
        assertTrue(sink.sentOfType(EdgeFrame.Heartbeat.class).isEmpty());
    }

    // ---- closed / no-op ----------------------------------------------------

    @Test
    void tickBeforeSubscribeIsNoOp() {
        FanOutBuffer buffer = new FanOutBuffer(16);
        buffer.publish(put(1, "k", "v"));
        FanOutSessionCore s = session(buffer, snapshotAt(1), FanOutConfig.defaults());
        s.tick(clock.now());
        assertTrue(sink.sent().isEmpty(), "no frames before SUBSCRIBE");
    }

    @Test
    void closeStopsAllEmission() {
        FanOutBuffer buffer = new FanOutBuffer(16);
        buffer.publish(put(1, "k", "v"));
        FanOutSessionCore s = session(buffer, snapshotAt(1), FanOutConfig.defaults());
        s.onSubscribe(subscribe(0));
        s.close();
        assertEquals(SessionState.closed(), s.state());
        assertTrue(sink.closed());
        sink.clear();
        s.tick(clock.now());
        assertTrue(sink.sent().isEmpty());
    }

    @Test
    void duplicateSubscribeIsProtocolViolation() {
        FanOutBuffer buffer = new FanOutBuffer(16);
        FanOutSessionCore s = session(buffer, snapshotAt(0), FanOutConfig.defaults());
        s.onSubscribe(subscribe(0));
        s.onSubscribe(subscribe(0));
        assertSame(ErrorCode.PROTOCOL_VIOLATION, sink.closeCode());
    }

    // ---- tiny helpers to keep the enum references readable -----------------

    private static final class SessionState {
        static FanOutSessionCore.SessionState streaming() {
            return FanOutSessionCore.SessionState.STREAMING;
        }
        static FanOutSessionCore.SessionState catchup() {
            return FanOutSessionCore.SessionState.CATCHUP;
        }
        static FanOutSessionCore.SessionState closed() {
            return FanOutSessionCore.SessionState.CLOSED;
        }
    }
}

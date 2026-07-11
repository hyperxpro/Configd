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
 * Unit matrix for {@link FanOutSessionCore}: SUBSCRIBE_OK mode decision, drain and batch
 * boundaries, gap to snapshot to resume cursor continuity, heartbeat cadence, and ack
 * accounting. Uses a real {@link FanOutBuffer} and {@link SnapshotReplaySource} with a
 * controllable {@link RecordingTransportSink} and {@link FakeClock} (no threads, no wall
 * clock).
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
        FanOutSessionCore s = session(buffer, snapshotAt(5), FanOutConfig.defaults());
        s.onSubscribe(subscribe(5));
        assertEquals(EdgeFrame.Mode.TAIL, sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode());
    }

    @Test
    void subscribeCursorBehindEvictedTailChoosesSnapshotFirst() {
        // A tiny buffer so publishing past the cursor evicts seq 1 before the subscriber
        // can read it, forcing a gap.
        FanOutBuffer buffer = new FanOutBuffer(4);
        for (long i = 1; i <= 10; i++) {
            buffer.publish(put(i, "k" + i, "v"));
        }
        FanOutSessionCore s = session(buffer, snapshotAt(10), FanOutConfig.defaults());
        s.onSubscribe(subscribe(1)); // cursor 1 is long gone, so this gaps to SNAPSHOT_FIRST
        assertEquals(EdgeFrame.Mode.SNAPSHOT_FIRST,
                sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode());
        assertEquals(SessionState.catchup(), s.state());
    }

    @Test
    void freshSubscriberWithBacklogOverQueueChoosesSnapshotFirst() {
        // A backlog of 6 commits exceeds queueFrames (4) at cursor 0, so a snapshot is
        // cheaper than streaming the backlog and decideMode picks SNAPSHOT_FIRST.
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

    @Test
    void tickDrainsNotificationsInVerbatimAscendingOrder() {
        // The subscribe must happen before the publishes: once data exists, decideMode
        // promotes a cursor-0 subscriber to SNAPSHOT_FIRST instead of TAIL, which would
        // change the behavior this test exercises.
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
        // Subscribing on the empty buffer selects TAIL, so the publishes below stream
        // live instead of triggering the snapshot heuristic.
        s.onSubscribe(subscribe(0));
        for (long i = 1; i <= 10; i++) {
            buffer.publish(put(i, "k" + i, "v"));
        }
        sink.clear();
        s.tick(clock.now());

        List<EdgeFrame.Notify> notifies = sink.sentOfType(EdgeFrame.Notify.class);
        assertEquals(4, notifies.size(), "10 notifications / batch 3 = 4 frames (3,3,3,1)");
        assertEquals(3, notifies.get(0).notifications().size());
        assertEquals(1, notifies.get(3).notifications().size());
        // The split must preserve a contiguous chain, with no gap or duplicate across
        // frame boundaries.
        long expected = 1;
        for (EdgeFrame.Notify n : notifies) {
            for (CommitNotification cn : n.notifications()) {
                assertEquals(expected++, cn.seq());
            }
        }
        assertEquals(11, expected);
    }

    @Test
    void gapMidStreamDemotesThenSnapshotsThenResumesTail() {
        FanOutBuffer buffer = new FanOutBuffer(4);
        buffer.publish(put(1, "k1", "v1"));
        buffer.publish(put(2, "k2", "v2"));
        // The replay source mirrors production: it always returns the current state of
        // the store at the current version. A mutable holder lets the snapshot seq track
        // the latest of the buffer after the burst, giving a clean resume point with no
        // second gap.
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
        s.tick(clock.now()); // streams both pending commits
        assertEquals(2L, s.cursor());

        // Publish enough to evict past the cursor, so the successor of seq 2, seq 3, is gone.
        for (long i = 3; i <= 30; i++) {
            buffer.publish(put(i, "k" + i, "v"));
        }
        snapVersion[0] = 30L; // the store has advanced to 30, the point replay resumes from
        sink.clear();
        s.tick(clock.now()); // readSince(2) reports a gap, so the session demotes
        assertEquals(SessionState.catchup(), s.state());
        EdgeFrame.ErrorClose demoteNotice = sink.sentOfType(EdgeFrame.ErrorClose.class).get(0);
        assertEquals(ErrorCode.DEMOTED_TO_CATCHUP, demoteNotice.code());
        assertEquals(DemotionEvent.REASON_GAP, demoteNotice.message());
        assertNotNull(s.lastDemotion());
        assertEquals(2L, s.lastDemotion().cursor(), "cursor evidence in demotion event");

        sink.clear();
        s.tick(clock.now()); // performs the snapshot transfer and resumes tailing from seq 30
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

        // The reassembled chunks must restore exactly the snapshot the replay source produced.
        byte[] body = EdgeSnapshotCodec.reassemble(chunks);
        ConfigSnapshot restored = EdgeSnapshotCodec.deserialize(body);
        assertEquals(30L, restored.version());
        assertNotNull(restored.get("kS"));
    }

    @Test
    void unackedFramesNeverExceedQueueFramesAndAckReleasesThem() {
        // Subscribes caught up, with cursor at the latest seq, so the fresh-bootstrap
        // snapshot heuristic does not fire; publishing a backlog larger than the queue
        // then forces a pure streaming overflow. With queueFrames at 4 and batchMax at 1,
        // each notification becomes its own frame.
        FanOutBuffer buffer = new FanOutBuffer(256);
        buffer.publish(put(1, "k1", "v"));
        FanOutConfig cfg = new FanOutConfig(4, 80, 1, 262_144, 8_192L, 250L, 5L, 1_048_576);
        FanOutSessionCore s = session(buffer, snapshotAt(7), cfg);
        s.onSubscribe(subscribe(1));
        assertEquals(EdgeFrame.Mode.TAIL, sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode());
        for (long i = 2; i <= 7; i++) {
            buffer.publish(put(i, "k" + i, "v"));
        }
        sink.clear();
        s.tick(clock.now());
        // Six commits are pending against a queue cap of 4, so four frames go out before
        // the queue overflows and demotes.
        assertTrue(s.inFlightFrames() <= 4, "unacked frames must never exceed queueFrames");
        assertEquals(SessionState.catchup(), s.state(), "overflow demotes to catch-up");
        assertEquals(DemotionEvent.REASON_QUEUE_OVERFLOW, s.lastDemotion().reason());
    }

    @Test
    void cursorAckReleasesInFlightFramesBelowThreshold() {
        FanOutBuffer buffer = new FanOutBuffer(256);
        FanOutConfig cfg = new FanOutConfig(8, 80, 1, 262_144, 8_192L, 250L, 5L, 1_048_576);
        FanOutSessionCore s = session(buffer, snapshotAt(3), cfg);
        // Subscribing on the empty buffer yields TAIL, since decideMode treats an empty
        // buffer as caught up.
        s.onSubscribe(subscribe(0));
        for (long i = 1; i <= 3; i++) {
            buffer.publish(put(i, "k" + i, "v"));
        }
        s.tick(clock.now());
        assertEquals(3, s.inFlightFrames());
        s.onCursorAck(2);
        assertEquals(1, s.inFlightFrames(), "frames with maxSeq<=2 released");
        assertEquals(2L, s.lastAckedSeq());
        s.onCursorAck(1); // a stale ack, behind the one already acked, must be ignored
        assertEquals(2L, s.lastAckedSeq());
        assertEquals(1, s.inFlightFrames());
    }

    @Test
    void ackLagBreachDemotes() {
        FanOutBuffer buffer = new FanOutBuffer(64);
        for (long i = 1; i <= 5; i++) {
            buffer.publish(put(i, "k" + i, "v"));
        }
        // With ackLagDemoteSeqs at 2, streaming 5 notifications with no ack leaves cursor
        // minus lastAcked at 5, which exceeds 2 and triggers a demotion.
        FanOutConfig cfg = new FanOutConfig(64, 80, 64, 262_144, 2L, 250L, 5L, 1_048_576);
        FanOutSessionCore s = session(buffer, snapshotAt(5), cfg);
        s.onSubscribe(subscribe(0));
        s.tick(clock.now()); // streams seqs 1 through 5; cursor 5, lastAcked 0
        sink.clear();
        s.tick(clock.now()); // ack lag of 5 exceeds the threshold of 2, so it demotes
        assertEquals(SessionState.catchup(), s.state());
        assertEquals(DemotionEvent.REASON_ACK_LAG, s.lastDemotion().reason());
    }

    // The simulated governor above can only reach an ackLagDemoteSeqs of 2 (see
    // ackLagBreachDemotes). This test exercises the production threshold
    // (FanOutConfig.defaults, 8192) directly, and pins the strict greater-than boundary so
    // an off-by-one using greater-than-or-equal would be caught. Ack lag is checked once at
    // the top of drainStreaming, while queue overflow is checked per frame inside the drain
    // loop, so for ACK_LAG rather than QUEUE_OVERFLOW to fire, the frame count at the
    // breach must stay below queueFrames: 8193 seqs at a batch of 64 is 129 frames, well
    // under the 256 queueFrames cap, so ack lag wins.

    @Test
    void prodThresholdAckLagOverThresholdDemotes() {
        FanOutConfig cfg = FanOutConfig.defaults(); // queueFrames 256, batch 64, ackLagDemoteSeqs 8192
        long over = cfg.ackLagDemoteSeqs() + 1;
        FanOutBuffer buffer = new FanOutBuffer(16_384);
        FanOutSessionCore s = session(buffer, snapshotAt(0), cfg);
        // Subscribing on the empty buffer selects TAIL then STREAMING; there is no fresh
        // backlog yet to trigger the snapshot heuristic.
        s.onSubscribe(subscribe(0));
        for (long i = 1; i <= over; i++) {
            buffer.publish(put(i, "k" + i, "v"));
        }
        sink.clear();
        s.tick(clock.now()); // streams all 8193 in one drain: 129 frames; cursor 8193, lastAcked 0
        assertEquals(over, s.cursor(), "all offered seqs streamed");
        assertTrue(s.inFlightFrames() < cfg.queueFrames(),
                "ack-lag must be reached BEFORE queue overflow (129 frames < 256) — else the cell "
                        + "would prove QUEUE_OVERFLOW, not ACK_LAG");
        assertEquals(SessionState.streaming(), s.state(), "no demotion yet (ack-lag checked next tick)");

        s.tick(clock.now()); // ack lag of 8193 minus 0 exceeds 8192, so it demotes
        assertEquals(SessionState.catchup(), s.state(),
                "a consumer lagging the offered cursor by > ackLagDemoteSeqs must demote at the prod threshold");
        assertEquals(DemotionEvent.REASON_ACK_LAG, s.lastDemotion().reason());
        assertEquals(over, s.lastDemotion().cursor(), "demotion event carries the offered cursor");
        assertEquals(0L, s.lastDemotion().lastAckedSeq(), "demotion event carries the (zero) acked position");
    }

    @Test
    void prodThresholdAckLagAtThresholdDoesNotDemote() {
        // The strict greater-than boundary: exactly ackLagDemoteSeqs offered and
        // unacknowledged must NOT demote. This is the case that an off-by-one using
        // greater-than-or-equal would break.
        FanOutConfig cfg = FanOutConfig.defaults();
        long atThreshold = cfg.ackLagDemoteSeqs(); // 8192
        FanOutBuffer buffer = new FanOutBuffer(16_384);
        FanOutSessionCore s = session(buffer, snapshotAt(0), cfg);
        s.onSubscribe(subscribe(0));
        for (long i = 1; i <= atThreshold; i++) {
            buffer.publish(put(i, "k" + i, "v"));
        }
        sink.clear();
        s.tick(clock.now()); // streams all 8192 notifications: 128 frames; cursor reaches 8192
        assertEquals(atThreshold, s.cursor());
        assertTrue(s.inFlightFrames() < cfg.queueFrames(), "no queue overflow at the threshold");
        s.tick(clock.now()); // ack lag is 8192 minus 0, equal to 8192, not greater, so it stays streaming
        assertEquals(SessionState.streaming(), s.state(),
                "exactly ackLagDemoteSeqs offered must NOT demote (strict >, not >=)");
        assertEquals(null, s.lastDemotion(), "no demotion event at the threshold");
    }

    // The would-block pause path exercised below is a transport that never drains
    // (wedged but open, not dead). It characterizes the safe degradation and the
    // resume-as-one-envelope guarantee. The only way to detect this state today is the
    // combination of being stuck in CATCHUP, no SnapshotEnd, and a pinned queue; there is
    // no dedicated stalled-transfer signal.

    @Test
    void wedgedTransportDuringSnapshotPausesSafelyThenResumesAsOneEnvelope() {
        // A multi-chunk snapshot so the transfer is genuinely paced (small chunk bytes).
        FanOutBuffer buffer = new FanOutBuffer(4);
        for (long i = 1; i <= 10; i++) {
            buffer.publish(put(i, "k" + i, "v" + i)); // evicts the oldest; seq 1 is long gone
        }
        // snapshotChunkBytes is 64, and with many keys the body spans multiple chunks,
        // giving a paced transfer.
        FanOutConfig cfg = new FanOutConfig(256, 80, 64, 262_144, 8_192L, 250L, 5L, 64);
        ReplaySource replay = snapshotAt(10,
                "k1", "value-1", "k2", "value-2", "k3", "value-3", "k4", "value-4",
                "k5", "value-5", "k6", "value-6", "k7", "value-7", "k8", "value-8",
                "k9", "value-9", "k10", "value-10", "k11", "value-11", "k12", "value-12");
        FanOutSessionCore s = session(buffer, replay, cfg);
        s.onSubscribe(subscribe(1));
        assertEquals(EdgeFrame.Mode.SNAPSHOT_FIRST,
                sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode());
        assertEquals(SessionState.catchup(), s.state());
        long cursorBeforeTransfer = s.cursor();
        sink.clear();

        // Wedge the transport so every offer would block. performSnapshotTransfer treats
        // a refused offer as backpressure, not transport death, so the session is not
        // torn down.
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
        // The only detection proxy today is being stuck in CATCHUP with no SnapshotEnd,
        // no snapshot_transfers_total increment, and a pinned queue. Detection is
        // possible through that proxy, so no new metric is emitted here.

        // Unwedge the transport so it drains. The transfer resumes the same paused
        // envelope and completes: exactly one BEGIN and one END, not a restarted or torn
        // envelope, then cutover.
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

    @Test
    void heartbeatEmittedAfterIdleIntervalOnly() {
        FanOutBuffer buffer = new FanOutBuffer(64);
        FanOutConfig cfg = new FanOutConfig(64, 80, 64, 262_144, 8_192L, 100L, 5L, 1_048_576);
        FanOutSessionCore s = session(buffer, snapshotAt(0), cfg);
        s.onSubscribe(subscribe(0));
        sink.clear();
        s.tick(1_000L); // anchors cadence, no heartbeat yet
        assertTrue(sink.sentOfType(EdgeFrame.Heartbeat.class).isEmpty());
        s.tick(1_050L); // 50 ms is less than 100 ms, so no heartbeat
        assertTrue(sink.sentOfType(EdgeFrame.Heartbeat.class).isEmpty());
        s.tick(1_100L); // 100 ms have elapsed, so a heartbeat is sent
        assertEquals(1, sink.sentOfType(EdgeFrame.Heartbeat.class).size());
        s.tick(1_150L); // only 50 ms since the last heartbeat, so none
        assertEquals(1, sink.sentOfType(EdgeFrame.Heartbeat.class).size());
        s.tick(1_200L); // another 100 ms elapse, producing a second heartbeat
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
        s.tick(1_090L); // streams, creating traffic at 1090
        assertFalse(sink.sentOfType(EdgeFrame.Notify.class).isEmpty());
        sink.clear();
        s.tick(1_150L); // only 60 ms since the last traffic, so no heartbeat
        assertTrue(sink.sentOfType(EdgeFrame.Heartbeat.class).isEmpty());
    }

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

    /** Thin wrapper so tests read session states as plain names rather than the qualified enum. */
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

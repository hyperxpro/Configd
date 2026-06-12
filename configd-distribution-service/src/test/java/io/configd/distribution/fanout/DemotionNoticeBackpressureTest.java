package io.configd.distribution.fanout;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RR-104 (C5 sign-off F1): {@code demote()} was the LAST close-resurrection site of the
 * RR-102 class. The DEMOTED_TO_CATCHUP notice used to be emitted through {@code emit()},
 * whose refusal semantics mark the session CLOSED and record a phantom
 * {@code onSessionClosed("transport_gone")} — and the demote tail then resurrected the
 * session to CATCHUP. Under a full outbound queue the refusal is near-certain when the
 * demotion reason is TRANSPORT_BLOCK (the queue is full <em>by definition</em>).
 *
 * <p><b>Pinned behavior (the RR-102 WOULD-BLOCK doctrine, applied verbatim):</b> a refused
 * demotion-notice offer is transport backpressure, not transport death. The notice is
 * advisory (the snapshot that follows is the load-bearing signal); it is retained and
 * re-offered each tick AHEAD of the snapshot transfer, so the wire order
 * (notice, BEGIN..chunks..END) is preserved, the notice is delivered exactly once, and the
 * session never records a close it did not perform.
 *
 * <p>Deterministic, no threads — the {@code BootstrapSnapshotBackpressureTest} pattern:
 * the test plays the writer's role by draining the bounded sink between ticks.
 */
class DemotionNoticeBackpressureTest {

    /** Sink capacity 1: the demotion-notice offer at demote() time is GUARANTEED refused. */
    private static final int SINK_CAPACITY = 1;

    /** The FanOutServer.Connection transport model (bounded, non-blocking, drainable). */
    private static final class BoundedDrainingSink implements TransportSink {
        final Deque<EdgeFrame> queued = new ArrayDeque<>();
        final List<EdgeFrame> deliveredToEdge = new ArrayList<>();
        final int capacity;
        boolean closed;

        BoundedDrainingSink(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public boolean offer(EdgeFrame frame) {
            if (closed || queued.size() >= capacity) {
                return false; // non-blocking bounded queue: full = would-block
            }
            queued.addLast(frame);
            return true;
        }

        @Override
        public void close(ErrorCode code, String message) {
            closed = true;
        }

        void drain(int n) {
            for (int i = 0; i < n && !queued.isEmpty(); i++) {
                deliveredToEdge.add(queued.pollFirst());
            }
        }

        <T extends EdgeFrame> List<T> delivered(Class<T> type) {
            List<T> out = new ArrayList<>();
            for (EdgeFrame f : deliveredToEdge) {
                if (type.isInstance(f)) {
                    out.add(type.cast(f));
                }
            }
            return out;
        }
    }

    /** Records the metric stream the register row indicts (phantom session closes). */
    private static final class RecordingMetrics implements FanOutSessionMetrics {
        final List<String> sessionClosedReasons = new ArrayList<>();
        final List<String> demotionReasons = new ArrayList<>();
        @Override public void onNotifyBatch(int n, int bytes) { }
        @Override public void onQueueDepth(int depth) { }
        @Override public void onSlowConsumerWarning() { }
        @Override public void onDemotion(String reason) { demotionReasons.add(reason); }
        @Override public void onSnapshotTransfer() { }
        @Override public void onHeartbeat() { }
        @Override public void onSessionClosed(String reason) { sessionClosedReasons.add(reason); }
    }

    private final FakeClock clock = new FakeClock(1_000L);

    private HamtMap<String, VersionedValue> auth = HamtMap.empty();
    private long version;

    private void commit(FanOutBuffer buffer, String key, String val) {
        long seq = ++version;
        auth = auth.put(key, new VersionedValue(val.getBytes(StandardCharsets.UTF_8), seq, 0L));
        buffer.publish(new CommitNotification(seq, 1_000L + seq, new ConfigDelta(seq - 1, seq,
                List.of(new ConfigMutation.Put(key, val.getBytes(StandardCharsets.UTF_8))))));
    }

    /** A ~1 KiB value so each notification exceeds batchMaxBytes ⇒ one NOTIFY frame each. */
    private static String fatValue(int i) {
        return ("v" + i + "-").repeat(180);
    }

    private FanOutSessionCore newSession(FanOutBuffer buffer, TransportSink sink,
                                         FanOutSessionMetrics metrics) {
        ReplaySource replay = new SnapshotReplaySource(() -> new ConfigSnapshot(auth, version, 0L));
        // batchMaxBytes 64 ⇒ each ~1 KiB notification is its own NOTIFY frame, so a
        // capacity-1 sink refuses the SECOND frame ⇒ TRANSPORT_BLOCK demotion with the
        // queue genuinely full — the exact RR-104 condition.
        FanOutConfig cfg = new FanOutConfig(64, 80, 64, 64, 8_192L, 250L, 5L, 1_024);
        return new FanOutSessionCore(buffer, replay, sink, cfg, metrics, clock);
    }

    @Test
    void refusedDemotionNoticeIsWouldBlockNotTransportDeath() {
        FanOutBuffer buffer = new FanOutBuffer(10_000);
        BoundedDrainingSink sink = new BoundedDrainingSink(SINK_CAPACITY);
        RecordingMetrics metrics = new RecordingMetrics();
        FanOutSessionCore session = newSession(buffer, sink, metrics);

        // Subscribe on the empty buffer ⇒ TAIL/STREAMING (the C1 streaming-mechanics shape).
        session.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge-rr104"));
        sink.drain(1); // SUBSCRIBE_OK onto the wire
        assertEquals(EdgeFrame.Mode.TAIL,
                sink.delivered(EdgeFrame.SubscribeOk.class).get(0).mode());

        // Three fat commits ⇒ three would-be NOTIFY frames. The capacity-1 sink accepts the
        // first and refuses the second ⇒ demote(TRANSPORT_BLOCK) with the queue FULL, so the
        // demotion-notice offer inside demote() is also refused.
        commit(buffer, "rr104/k1", fatValue(1));
        commit(buffer, "rr104/k2", fatValue(2));
        commit(buffer, "rr104/k3", fatValue(3));
        clock.advance(10);
        session.tick(clock.now());

        assertEquals(List.of(DemotionEvent.REASON_TRANSPORT_BLOCK), metrics.demotionReasons,
                "the full sink must demote with TRANSPORT_BLOCK");
        // THE RED/GREEN PIVOT (RR-104): pre-fix, the refused notice routed through emit()
        // marked the session CLOSED + recorded a phantom onSessionClosed("transport_gone"),
        // and the demote tail resurrected it — a close the session never performed.
        assertEquals(List.of(), metrics.sessionClosedReasons,
                "a refused ADVISORY demotion notice is WOULD-BLOCK, not transport death — "
                        + "no session-close may be recorded (RR-104)");
        assertEquals(FanOutSessionCore.SessionState.CATCHUP, session.state(),
                "the demoted session owes a snapshot (CATCHUP), with no close/resurrect");

        // Play the writer: drain and tick until the owed snapshot lands. The retained
        // notice must go out FIRST (wire order preserved), then BEGIN..chunks..END.
        int guard = 0;
        while (sink.delivered(EdgeFrame.SnapshotEnd.class).isEmpty()) {
            assertTrue(++guard < 10_000, "transfer must complete once the writer drains");
            sink.drain(1);
            clock.advance(1);
            session.tick(clock.now());
        }
        sink.drain(Integer.MAX_VALUE);

        List<EdgeFrame.ErrorClose> notices = sink.delivered(EdgeFrame.ErrorClose.class);
        assertEquals(1, notices.size(),
                "the demotion notice is delivered EXACTLY once (no loss, no duplicate)");
        assertEquals(ErrorCode.DEMOTED_TO_CATCHUP, notices.get(0).code());

        int noticeAt = sink.deliveredToEdge.indexOf(notices.get(0));
        int beginAt = sink.deliveredToEdge.indexOf(
                sink.delivered(EdgeFrame.SnapshotBegin.class).get(0));
        assertTrue(noticeAt < beginAt,
                "wire order preserved: the demotion notice precedes the snapshot envelope");

        // The session completed the owed transfer and resumed streaming — and at no point
        // did the metric stream record a close (the lying-metric defect RR-104 names).
        assertEquals(FanOutSessionCore.SessionState.STREAMING, session.state());
        assertEquals(version, session.cursor(), "cutover at the snapshot seq");
        assertEquals(List.of(), metrics.sessionClosedReasons,
                "no phantom onSessionClosed across the whole demote→snapshot→resume flow");
    }

    @Test
    void acceptedDemotionNoticeBehaviorIsUnchangedWhenTheSinkNeverRefuses() {
        // Byte-identity guard (the RR-102 precedent): when the transport never refuses,
        // the notice is emitted at demote() time in the same tick, exactly as before.
        FanOutBuffer buffer = new FanOutBuffer(4); // tiny ring: evictions force a GAP
        BoundedDrainingSink sink = new BoundedDrainingSink(Integer.MAX_VALUE);
        RecordingMetrics metrics = new RecordingMetrics();
        FanOutSessionCore session = newSession(buffer, sink, metrics);

        session.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge-rr104b"));
        // Overflow the 4-slot ring so readSince(0) is a GAP ⇒ demote(GAP) with a sink that
        // accepts everything.
        for (int i = 1; i <= 8; i++) {
            commit(buffer, "rr104b/k" + i, "v" + i);
        }
        clock.advance(10);
        session.tick(clock.now());

        assertEquals(List.of(DemotionEvent.REASON_GAP), metrics.demotionReasons);
        assertEquals(List.of(), metrics.sessionClosedReasons);
        // The owed transfer runs on the NEXT tick (the established demote→tick flow).
        clock.advance(1);
        session.tick(clock.now());
        sink.drain(Integer.MAX_VALUE);

        // Notice emitted in the demote tick itself, before the snapshot envelope.
        List<EdgeFrame.ErrorClose> notices = sink.delivered(EdgeFrame.ErrorClose.class);
        assertEquals(1, notices.size());
        assertEquals(ErrorCode.DEMOTED_TO_CATCHUP, notices.get(0).code());
        int noticeAt = sink.deliveredToEdge.indexOf(notices.get(0));
        int beginAt = sink.deliveredToEdge.indexOf(
                sink.delivered(EdgeFrame.SnapshotBegin.class).get(0));
        assertTrue(noticeAt < beginAt, "notice precedes the snapshot envelope");
        assertEquals(FanOutSessionCore.SessionState.STREAMING, session.state(),
                "transfer completed in one tick (ample sink) and resumed streaming");
    }
}

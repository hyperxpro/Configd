package io.configd.distribution.fanout;

import io.configd.common.Clock;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CT-26 (charter §4 C1; ADR-0037). On outbound overflow the session is DEMOTED from
 * streaming to catch-up (snapshot) mode — never an unbounded queue, never a silent drop.
 * Every demotion carries cursor evidence (a {@link DemotionEvent}), fires a metric, and
 * is recoverable: the subsequent snapshot transfer plus resumed tail loses no committed
 * effect (the edge observes every mutation's effect across the demotion boundary).
 */
class SubscriberOverflowDemotionTest {

    private static final Clock CLOCK = new Clock() {
        @Override public long currentTimeMillis() { return 0L; }
        @Override public long nanoTime() { return 0L; }
    };

    @Test
    void queueOverflowDemotesWithCursorEvidenceMetricAndStructuredEvent() {
        FanOutBuffer buffer = new FanOutBuffer(256);
        buffer.publish(notif(1));
        SubscriberQueueBoundTest.CountingMetrics metrics = new SubscriberQueueBoundTest.CountingMetrics();
        List<DemotionEvent> events = new ArrayList<>();

        // queueFrames=3, batchMax=1; a 6-deep backlog after subscribe overflows.
        FanOutConfig cfg = new FanOutConfig(3, 80, 1, 262_144, 8_192L, 250L, 5L, 1_048_576);
        ReplaySource replay = currentVersionReplay(7L);
        RecordingTransportSink sink = new RecordingTransportSink();
        FanOutSessionCore s = new FanOutSessionCore(buffer, replay, sink, cfg, metrics, CLOCK, events::add);

        s.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 1L, -1L, "e")); // caught up -> TAIL
        for (long i = 2; i <= 7; i++) {
            buffer.publish(notif(i));
        }
        sink.clear();
        s.tick(0L);

        // Demoted, never silently dropped: a DEMOTED_TO_CATCHUP notice on the wire.
        List<EdgeFrame.ErrorClose> notices = sink.sentOfType(EdgeFrame.ErrorClose.class);
        assertEquals(1, notices.size());
        assertEquals(ErrorCode.DEMOTED_TO_CATCHUP, notices.get(0).code());
        assertEquals(DemotionEvent.REASON_QUEUE_OVERFLOW, notices.get(0).message());

        // Cursor evidence + metric + structured event.
        assertEquals(1, metrics.demotions);
        assertEquals(DemotionEvent.REASON_QUEUE_OVERFLOW, metrics.lastDemotionReason);
        assertEquals(1, events.size());
        DemotionEvent ev = events.get(0);
        assertEquals(DemotionEvent.REASON_QUEUE_OVERFLOW, ev.reason());
        assertTrue(ev.cursor() >= 1, "demotion event carries the cursor evidence: " + ev.cursor());
        // The edge resumed at cursor 1 (implicit ack of the resume point) and never acked
        // beyond it, so lastAckedSeq stays at the subscribe resume cursor.
        assertEquals(1L, ev.lastAckedSeq(), "lastAckedSeq is the subscribe resume cursor (1)");
        assertEquals(FanOutSessionCore.SessionState.CATCHUP, s.state());
        assertFalse(sink.closed(), "demotion is NON-fatal (no session close)");
    }

    @Test
    void transportWouldBlockDemotesNotDrops() {
        FanOutBuffer buffer = new FanOutBuffer(256);
        buffer.publish(notif(1));
        for (long i = 2; i <= 4; i++) {
            buffer.publish(notif(i));
        }
        RecordingTransportSink sink = new RecordingTransportSink();
        FanOutConfig cfg = new FanOutConfig(64, 80, 1, 262_144, 8_192L, 250L, 5L, 1_048_576);
        SubscriberQueueBoundTest.CountingMetrics metrics = new SubscriberQueueBoundTest.CountingMetrics();
        FanOutSessionCore s = new FanOutSessionCore(buffer, currentVersionReplay(4L), sink, cfg, metrics, CLOCK);
        s.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 1L, -1L, "e"));
        // Block the transport on the 2nd NOTIFY offer (1 SubscribeOk already consumed at subscribe;
        // sink was not cleared, so block offer index relative to now). Clear first for clarity.
        sink.clear();
        sink.blockNextOffers(1); // first NOTIFY offer would block
        s.tick(0L);
        assertEquals(FanOutSessionCore.SessionState.CATCHUP, s.state(),
                "a transport would-block demotes (never an unbounded buffer)");
        assertEquals(DemotionEvent.REASON_TRANSPORT_BLOCK, s.lastDemotion().reason());
    }

    @Test
    void noCommittedEffectLostAcrossTheDemotionBoundary() {
        // Build a store snapshot that reflects all committed keys; after demotion the
        // snapshot transfer must carry every key so the edge observes every effect.
        FanOutBuffer buffer = new FanOutBuffer(256);
        buffer.publish(notif(1));
        String[] keys = {"a", "b", "c", "d", "e", "f"};
        for (int i = 0; i < keys.length; i++) {
            long seq = i + 2;
            buffer.publish(put(seq, keys[i], "v" + seq));
        }
        // Replay snapshot at version 7 containing every key.
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        data = data.put("k1", new VersionedValue("v1".getBytes(StandardCharsets.UTF_8), 7L, 0L));
        for (int i = 0; i < keys.length; i++) {
            data = data.put(keys[i], new VersionedValue(("v" + (i + 2)).getBytes(StandardCharsets.UTF_8), 7L, 0L));
        }
        ConfigSnapshot snap = new ConfigSnapshot(data, 7L, 0L);
        ReplaySource replay = () -> new ReplaySource.Replay(snap, 7L);

        RecordingTransportSink sink = new RecordingTransportSink();
        FanOutConfig cfg = new FanOutConfig(3, 80, 1, 262_144, 8_192L, 250L, 5L, 1_048_576);
        FanOutSessionCore s = new FanOutSessionCore(buffer, replay, sink, cfg, FanOutSessionMetrics.NOOP, CLOCK);
        s.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 1L, -1L, "e"));
        s.tick(0L); // overflow -> demote (CATCHUP)
        assertEquals(FanOutSessionCore.SessionState.CATCHUP, s.state());
        sink.clear();
        s.tick(0L); // snapshot transfer

        List<EdgeFrame.SnapshotChunk> chunks = sink.sentOfType(EdgeFrame.SnapshotChunk.class);
        assertNotNull(s.lastDemotion());
        byte[] body = EdgeSnapshotCodec.reassemble(chunks);
        ConfigSnapshot restored = EdgeSnapshotCodec.deserialize(body);
        assertEquals(7L, restored.version());
        // Every committed key is present in the snapshot the edge applies -> no effect lost.
        for (String k : keys) {
            assertNotNull(restored.get(k), "snapshot must carry committed key '" + k + "'");
        }
        assertNotNull(restored.get("k1"));
        assertEquals(7L, s.cursor(), "cursor resumes at the snapshot seq");
    }

    private static ReplaySource currentVersionReplay(long version) {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        data = data.put("x", new VersionedValue("x".getBytes(StandardCharsets.UTF_8), version, 0L));
        ConfigSnapshot snap = new ConfigSnapshot(data, version, 0L);
        return () -> new ReplaySource.Replay(snap, version);
    }

    private static CommitNotification notif(long seq) {
        return put(seq, "k" + seq, "v" + seq);
    }

    private static CommitNotification put(long seq, String key, String val) {
        return new CommitNotification(seq, 1_000L + seq,
                new ConfigDelta(seq - 1, seq,
                        List.of(new ConfigMutation.Put(key, val.getBytes(StandardCharsets.UTF_8)))));
    }
}

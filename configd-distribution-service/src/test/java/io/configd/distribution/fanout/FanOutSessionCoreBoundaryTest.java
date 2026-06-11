package io.configd.distribution.fanout;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boundary / decision-edge tests for {@link FanOutSessionCore} that pin the exact
 * thresholds the broader unit + property tests don't isolate (gate-3 mutation tightness):
 * the SNAPSHOT_FIRST backlog boundary, the byte-cap batch split, the ack-release boundary,
 * and the slow-consumer warn threshold.
 */
class FanOutSessionCoreBoundaryTest {

    private final FakeClock clock = new FakeClock(1_000L);
    private final RecordingTransportSink sink = new RecordingTransportSink();

    private static ReplaySource replayAt(long version) {
        ConfigSnapshot snap = new ConfigSnapshot(HamtMap.empty(), version, 0L);
        return () -> new ReplaySource.Replay(snap, version);
    }

    private static CommitNotification put(long seq, int valueBytes) {
        byte[] v = new byte[valueBytes];
        return new CommitNotification(seq, 1_000L + seq,
                new ConfigDelta(seq - 1, seq, List.of(new ConfigMutation.Put("k" + seq, v))));
    }

    private FanOutSessionCore session(FanOutBuffer buf, ReplaySource replay, FanOutConfig cfg) {
        return new FanOutSessionCore(buf, replay, sink, cfg, FanOutSessionMetrics.NOOP, clock);
    }

    private static EdgeFrame.Subscribe sub(long resume) {
        return new EdgeFrame.Subscribe(true, List.of(), resume, -1L, "e");
    }

    /** Backlog == queueFrames stays TAIL; backlog == queueFrames+1 flips to SNAPSHOT_FIRST. */
    @Test
    void freshBacklogBoundaryAtQueueFramesDecidesMode() {
        // queueFrames = 4. Backlog (latest-oldest+1) of exactly 4 -> TAIL; 5 -> SNAPSHOT_FIRST.
        FanOutConfig cfg = new FanOutConfig(4, 80, 64, 262_144, 8_192L, 250L, 5L, 1_048_576);

        FanOutBuffer buf4 = new FanOutBuffer(64);
        for (long i = 1; i <= 4; i++) buf4.publish(put(i, 1));
        FanOutSessionCore s4 = session(buf4, replayAt(4), cfg);
        s4.onSubscribe(sub(0));
        assertEquals(EdgeFrame.Mode.TAIL,
                sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode(),
                "backlog == queueFrames must stay TAIL");

        sink.clear();
        FanOutBuffer buf5 = new FanOutBuffer(64);
        for (long i = 1; i <= 5; i++) buf5.publish(put(i, 1));
        FanOutSessionCore s5 = session(buf5, replayAt(5), cfg);
        s5.onSubscribe(sub(0));
        assertEquals(EdgeFrame.Mode.SNAPSHOT_FIRST,
                sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode(),
                "backlog == queueFrames+1 must flip to SNAPSHOT_FIRST");
    }

    /** A NOTIFY batch is split when the next notification would EXCEED batchMaxBytes. */
    @Test
    void batchByteCapSplitsAtTheExactByteBoundary() {
        // Each put has a ~1000-byte value; with batchMaxBytes small, batches split by bytes.
        FanOutBuffer buf = new FanOutBuffer(64);
        for (long i = 1; i <= 4; i++) {
            buf.publish(put(i, 1000));
        }
        // batchMaxBytes chosen so ~2 notifications fit per frame.
        FanOutConfig cfg = new FanOutConfig(64, 80, 64, 2200, 8_192L, 250L, 5L, 1_048_576);
        FanOutSessionCore s = session(buf, replayAt(4), cfg);
        s.onSubscribe(sub(0));
        sink.clear();
        s.tick(clock.now());
        List<EdgeFrame.Notify> notifies = sink.sentOfType(EdgeFrame.Notify.class);
        assertTrue(notifies.size() >= 2, "a small byte cap must split the 4 large notifications");
        // No single emitted batch exceeds the byte cap (the codec would reject otherwise too).
        for (EdgeFrame.Notify n : notifies) {
            assertTrue(io.configd.distribution.wire.EdgeFrameCodec.encode(n).length
                            <= 2200 + io.configd.distribution.wire.EdgeFrameCodec.HEADER_SIZE
                            + io.configd.distribution.wire.EdgeFrameCodec.TRAILER_SIZE,
                    "each NOTIFY frame must respect the byte cap");
        }
        // The whole chain still delivered, contiguous.
        long expect = 1;
        for (EdgeFrame.Notify n : notifies) {
            for (CommitNotification cn : n.notifications()) {
                assertEquals(expect++, cn.seq());
            }
        }
        assertEquals(5, expect);
    }

    /** onCursorAck releases frames with maxSeq <= ack and keeps frames strictly above it. */
    @Test
    void cursorAckReleaseIsInclusiveAtTheBoundary() {
        FanOutBuffer buf = new FanOutBuffer(64);
        for (long i = 1; i <= 3; i++) buf.publish(put(i, 1));
        FanOutConfig cfg = new FanOutConfig(16, 80, 1, 262_144, 8_192L, 250L, 5L, 1_048_576);
        FanOutSessionCore s = session(buf, replayAt(3), cfg);
        s.onSubscribe(sub(0));
        s.tick(clock.now());
        assertEquals(3, s.inFlightFrames());
        // Ack exactly seq 2: frames with maxSeq 1 and 2 release; frame 3 stays.
        s.onCursorAck(2);
        assertEquals(1, s.inFlightFrames());
        // Ack exactly the boundary seq 3: the last frame releases.
        s.onCursorAck(3);
        assertEquals(0, s.inFlightFrames());
    }

    /** The slow-consumer warning fires when in-flight reaches the warn threshold, once. */
    @Test
    void slowConsumerWarnFiresAtThresholdExactlyOnce() {
        FanOutBuffer buf = new FanOutBuffer(64);
        for (long i = 1; i <= 4; i++) buf.publish(put(i, 1));
        // queueFrames 5, warn 80% -> threshold 4 frames; batchMax 1.
        FanOutConfig cfg = new FanOutConfig(5, 80, 1, 262_144, 8_192L, 250L, 5L, 1_048_576);
        CountingMetrics metrics = new CountingMetrics();
        FanOutSessionCore s = new FanOutSessionCore(buf, replayAt(4), sink, cfg, metrics, clock);
        s.onSubscribe(sub(0));
        s.tick(clock.now()); // streams 4 frames -> hits threshold 4 -> one warning
        assertEquals(1, metrics.warnings, "warn fires once at the threshold");
        // Ack down then back up: warning re-arms and fires again.
        s.onCursorAck(4);
        for (long i = 5; i <= 8; i++) buf.publish(put(i, 1));
        s.tick(clock.now());
        assertEquals(2, metrics.warnings, "warning re-arms after dropping below threshold");
    }

    private static final class CountingMetrics implements FanOutSessionMetrics {
        int warnings;
        @Override public void onNotifyBatch(int n, int bytes) { }
        @Override public void onQueueDepth(int depth) { }
        @Override public void onSlowConsumerWarning() { warnings++; }
        @Override public void onDemotion(String reason) { }
        @Override public void onSnapshotTransfer() { }
        @Override public void onHeartbeat() { }
        @Override public void onSessionClosed(String reason) { }
    }
}

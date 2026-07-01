package io.configd.distribution.fanout;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unacked NOTIFY-frame count NEVER exceeds {@code queueFrames}, under any
 * publish/tick/ack interleaving - the bounded outbound queue is a hard invariant (never an
 * unbounded queue).
 */
class SubscriberQueueBoundTest {

    private static final Clock CLOCK = new Clock() {
        @Override public long currentTimeMillis() { return 0L; }
        @Override public long nanoTime() { return 0L; }
    };

    @Property(tries = 300)
    void unackedFrameCountNeverExceedsQueueFrames(
            @ForAll @IntRange(min = 2, max = 12) int queueFrames,
            @ForAll @IntRange(min = 1, max = 6) int batchMax,
            @ForAll @Size(min = 5, max = 50) List<@IntRange(min = 0, max = 2) Integer> actions) {

        FanOutBuffer buffer = new FanOutBuffer(1024);
        ReplaySource replay = new SnapshotReplaySource(() -> ConfigSnapshot.EMPTY);
        RecordingTransportSink sink = new RecordingTransportSink();
        FanOutConfig cfg = new FanOutConfig(queueFrames, 80, batchMax, 262_144, 8_192L, 250L, 5L, 1_048_576);
        FanOutSessionCore s = new FanOutSessionCore(buffer, replay, sink, cfg,
                FanOutSessionMetrics.NOOP, CLOCK);
        // Subscribe caught-up so streaming (not bootstrap snapshot) drives the queue.
        buffer.publish(notif(1));
        s.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 1L, -1L, "e"));

        long nextSeq = 2;
        long ackTo = 1;
        for (int a : actions) {
            switch (a) {
                case 0 -> { buffer.publish(notif(nextSeq)); nextSeq++; }
                case 1 -> s.tick(0L);
                default -> { ackTo = Math.min(s.cursor(), ackTo + 1); s.onCursorAck(ackTo); }
            }
            assertTrue(s.inFlightFrames() <= queueFrames,
                    "unacked frames " + s.inFlightFrames() + " exceeded queueFrames " + queueFrames);
        }
    }

    @Test
    void queueDepthGaugeAndWarningTrackTheBound() {
        // queueFrames=5, warn at 80% = 4 frames; batchMax=1.
        FanOutBuffer buffer = new FanOutBuffer(256);
        buffer.publish(notif(1));
        CountingMetrics metrics = new CountingMetrics();
        FanOutConfig cfg = new FanOutConfig(5, 80, 1, 262_144, 8_192L, 250L, 5L, 1_048_576);
        ReplaySource replay = new SnapshotReplaySource(() -> ConfigSnapshot.EMPTY);
        FanOutSessionCore s = new FanOutSessionCore(buffer, replay, new RecordingTransportSink(),
                cfg, metrics, CLOCK);
        s.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 1L, -1L, "e"));
        for (long i = 2; i <= 5; i++) {
            buffer.publish(notif(i)); // 4 pending -> 4 frames, hits the 80% warn at 4
        }
        s.tick(0L);
        assertTrue(metrics.slowConsumerWarnings >= 1,
                "crossing 80% of queueFrames must fire a slow-consumer warning");
        assertTrue(s.inFlightFrames() <= 5);
    }

    private static CommitNotification notif(long seq) {
        return new CommitNotification(seq, 1_000L + seq,
                new ConfigDelta(seq - 1, seq,
                        List.of(new ConfigMutation.Put("k" + seq, ("v" + seq).getBytes(StandardCharsets.UTF_8)))));
    }

    /** Minimal counting metrics sink. */
    static final class CountingMetrics implements FanOutSessionMetrics {
        int notifyBatches;
        int slowConsumerWarnings;
        int demotions;
        int snapshotTransfers;
        int heartbeats;
        String lastDemotionReason;

        @Override public void onNotifyBatch(int n, int bytes) { notifyBatches++; }
        @Override public void onQueueDepth(int depth) { }
        @Override public void onSlowConsumerWarning() { slowConsumerWarnings++; }
        @Override public void onDemotion(String reason) { demotions++; lastDemotionReason = reason; }
        @Override public void onSnapshotTransfer() { snapshotTransfers++; }
        @Override public void onHeartbeat() { heartbeats++; }
        @Override public void onSessionClosed(String reason) { }
    }
}

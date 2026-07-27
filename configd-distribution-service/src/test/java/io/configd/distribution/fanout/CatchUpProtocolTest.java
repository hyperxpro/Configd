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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catch-up protocol selection for a resubscribing edge: the gap is resolved
 * server-side. A gap smaller than the window streams deltas; a gap larger than the window
 * sends a chunked snapshot, then streams deltas from the snapshot point. The window is the
 * retention of the boundary ring, the replay horizon; the resubscribe-with-cursor path, a
 * fresh SUBSCRIBE carrying the cursor of the edge, is exactly how a gap recovery re-enters
 * this decision without a new wire surface.
 *
 * <p>The small-gap replay streams retained {@link CommitNotification}s from the ring, the
 * hot-path cache over the durable log, and the beyond-window path replays
 * snapshot-equivalent state, deliberately not a historical WAL scan.
 */
class CatchUpProtocolTest {

    private final FakeClock clock = new FakeClock(1_000L);
    private final RecordingTransportSink sink = new RecordingTransportSink();

    private HamtMap<String, VersionedValue> auth = HamtMap.empty();
    private long version;

    private void commit(FanOutBuffer buffer, String key, String val) {
        long seq = ++version;
        auth = auth.put(key, new VersionedValue(val.getBytes(StandardCharsets.UTF_8), seq, 0L));
        buffer.publish(new CommitNotification(seq, 1_000L + seq, new ConfigDelta(seq - 1, seq,
                List.of(new ConfigMutation.Put(key, val.getBytes(StandardCharsets.UTF_8))))));
    }

    private ReplaySource liveReplaySource() {
        return new SnapshotReplaySource(() -> new ConfigSnapshot(auth, version, 0L));
    }

    private FanOutSessionCore resubscribe(FanOutBuffer buffer, long cursor, FanOutConfig cfg) {
        FanOutSessionCore s = new FanOutSessionCore(buffer, liveReplaySource(), sink, cfg,
                FanOutSessionMetrics.NOOP, clock);
        s.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), cursor, -1L, "edge-c"));
        return s;
    }

    @Test
    void gapWithinTheWindowStreamsRetainedDeltasOnlyNoSnapshot() {
        FanOutBuffer buffer = new FanOutBuffer(64);
        for (int i = 1; i <= 10; i++) {
            commit(buffer, "k" + i, "v" + i);
        }
        // The edge gapped at cursor 4; seqs 5 through 10 are all retained, so the gap is
        // smaller than the window.
        FanOutSessionCore s = resubscribe(buffer, 4, FanOutConfig.defaults());
        assertEquals(EdgeFrame.Mode.TAIL,
                sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode());

        sink.clear();
        s.tick(clock.now());

        assertTrue(sink.sentOfType(EdgeFrame.SnapshotBegin.class).isEmpty(),
                "within-window catch-up must stream deltas, never a snapshot");
        List<EdgeFrame.Notify> notifies = sink.sentOfType(EdgeFrame.Notify.class);
        assertEquals(1, notifies.size());
        List<CommitNotification> run = notifies.get(0).notifications();
        assertEquals(6, run.size(), "exactly the missed tail 5..10");
        long expect = 5;
        for (CommitNotification n : run) {
            assertEquals(expect++, n.seq(), "contiguous ascending replay");
        }
        assertEquals(10L, s.cursor());
    }

    @Test
    void gapBeyondTheWindowSendsChunkedSnapshotThenStreamsFromTheSnapshotPoint() {
        FanOutBuffer buffer = new FanOutBuffer(8);
        for (int i = 1; i <= 30; i++) {
            // 64-byte values so the snapshot body genuinely spans multiple 64-byte chunks.
            commit(buffer, "k" + (i % 4), ("v" + i).repeat(20));
        }
        // cursor 5 is far beyond the horizon (oldest retained is 23), so the gap is larger
        // than the window. Small chunk size so the transfer is genuinely multi-chunk at
        // test scale.
        FanOutConfig cfg = new FanOutConfig(64, 80, 64, 262_144, 8_192L, 250L, 5L, 64);
        FanOutSessionCore s = resubscribe(buffer, 5, cfg);
        assertEquals(EdgeFrame.Mode.SNAPSHOT_FIRST,
                sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode());

        sink.clear();
        s.tick(clock.now()); // performs the snapshot transfer, resumes STREAMING

        List<EdgeFrame.SnapshotBegin> begins = sink.sentOfType(EdgeFrame.SnapshotBegin.class);
        assertEquals(1, begins.size());
        EdgeFrame.SnapshotBegin begin = begins.get(0);
        assertEquals(30L, begin.snapshotSeq(), "snapshot-equivalent state at the current seq");
        assertTrue(begin.chunkCount() > 1, "chunked transfer (multi-chunk at this size)");
        assertEquals(begin.chunkCount(), sink.sentOfType(EdgeFrame.SnapshotChunk.class).size());
        assertEquals(1, sink.sentOfType(EdgeFrame.SnapshotEnd.class).size());
        assertEquals(30L, s.cursor(), "deltas resume from the snapshot point");

        commit(buffer, "post", "p1");
        sink.clear();
        clock.advance(10);
        s.tick(clock.now());
        List<EdgeFrame.Notify> notifies = sink.sentOfType(EdgeFrame.Notify.class);
        assertEquals(1, notifies.size());
        assertEquals(31L, notifies.get(0).notifications().get(0).seq(),
                "the tail resumes at snapshotSeq + 1 — no hole, no duplicate");
    }
}

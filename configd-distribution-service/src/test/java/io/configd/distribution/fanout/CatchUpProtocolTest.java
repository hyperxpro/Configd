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
 * The §7 catch-up protocol selection through the C3 recovery path (CT-31; architecture
 * §7 :269-273): a resubscribing edge's gap is resolved server-side — "gap &lt; window →
 * stream deltas; gap &gt; window → chunked snapshot, then streams deltas from snapshot
 * point". The window IS the boundary ring's retention (the replay horizon, design §1
 * item 2); the resubscribe-with-cursor path (a fresh SUBSCRIBE carrying the edge's
 * cursor) is exactly how a C3 gap recovery re-enters this decision — no new wire surface
 * (screen C3-1).
 *
 * <p>§7's "WAL-delta replay" wording is reconciled here per ADR-0034: the small-gap
 * replay streams retained {@link CommitNotification}s from the ring (the hot-path cache
 * over the durable log), and the beyond-window path replays SNAPSHOT-EQUIVALENT state
 * (ADR-0034 §4 — deliberately not a historical WAL scan). The consolidated doc pass at
 * session close updates §7's text to point at ADR-0034.
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
        // The edge gapped at cursor 4; seqs 5..10 are all retained — gap < window.
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
        // cursor 5 is far beyond the horizon (oldest retained is 23) — gap > window.
        // Small chunk size so the transfer is genuinely multi-chunk at test scale (the
        // 1 MiB production chunking + per-frame CRC is codec-pinned by CT-41's fixtures).
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

        // Post-snapshot writes stream as deltas with seq > snapshot point — contiguous.
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

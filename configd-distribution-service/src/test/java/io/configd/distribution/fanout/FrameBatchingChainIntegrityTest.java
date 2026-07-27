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
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exact invariant: emitted NOTIFY batches form a strictly-ascending, contiguous subsequence
 * of published seqs (verbatim, no dupe/merge/skip), except across DEMOTED+SNAPSHOT boundary
 * where stream jumps to snapshot seq S then resumes contiguously from first published seq > S.
 * Contiguity is over published-list order (not raw arithmetic), accounting for natural seq gaps.
 */
class FrameBatchingChainIntegrityTest {

    private static final Clock CLOCK = new Clock() {
        @Override public long currentTimeMillis() { return 0L; }
        @Override public long nanoTime() { return 0L; }
    };

    @Property(tries = 400)
    void notifyBatchesAreAVerbatimContiguousSubsequenceExceptAtSnapshotBoundaries(
            @ForAll @IntRange(min = 4, max = 16) int bufferCap,
            @ForAll @IntRange(min = 2, max = 8) int batchMax,
            @ForAll @IntRange(min = 2, max = 8) int queueFrames,
            @ForAll @Size(min = 5, max = 60) List<@IntRange(min = 0, max = 3) Integer> actions,
            @ForAll @LongRange(min = 1, max = 100) long ackStride) {

        FanOutBuffer buffer = new FanOutBuffer(bufferCap);
        // The replay source returns the store at its current version - we model "current
        // version" as the highest published seq so far via a holder.
        long[] publishedHigh = {0L};
        ReplaySource replay = () -> {
            HamtMap<String, VersionedValue> data = HamtMap.empty();
            data = data.put("snap", new VersionedValue(
                    "s".getBytes(StandardCharsets.UTF_8), publishedHigh[0], 0L));
            return new ReplaySource.Replay(new ConfigSnapshot(data, publishedHigh[0], 0L), publishedHigh[0]);
        };

        // The authoritative published chain (in publish order).
        List<Long> published = new ArrayList<>();

        RecordingTransportSink sink = new RecordingTransportSink();
        FanOutConfig cfg = new FanOutConfig(queueFrames, 80, batchMax, 262_144, 8_192L, 250L, 5L, 1_048_576);
        FanOutSessionCore session = new FanOutSessionCore(buffer, replay, sink, cfg,
                FanOutSessionMetrics.NOOP, CLOCK);

        session.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "e"));

        long nextSeq = 1;
        long ackTo = 0;
        // Interleave publish / tick / ack / burst-publish actions deterministically.
        for (int a : actions) {
            switch (a) {
                case 0 -> { // publish one
                    buffer.publish(notif(nextSeq));
                    published.add(nextSeq);
                    publishedHigh[0] = nextSeq;
                    nextSeq++;
                }
                case 1 -> session.tick(0L); // drain
                case 2 -> { // ack forward
                    ackTo = Math.min(session.cursor(), ackTo + ackStride);
                    session.onCursorAck(ackTo);
                }
                default -> { // burst-publish (can overflow the small ring -> GAP path)
                    for (int k = 0; k < bufferCap + 2; k++) {
                        buffer.publish(notif(nextSeq));
                        published.add(nextSeq);
                        publishedHigh[0] = nextSeq;
                        nextSeq++;
                    }
                }
            }
        }
        // Final drain ticks to flush.
        for (int i = 0; i < 4; i++) {
            session.tick(0L);
        }

        verifyChainIntegrity(sink.sent(), published);
    }

    /**
     * Walks the emitted frame stream and asserts the invariant: NOTIFY runs are a verbatim,
     * strictly-ascending, contiguous subsequence of the published chain; the only allowed
     * discontinuity is across a SNAPSHOT_BEGIN..SNAPSHOT_END boundary, after which the run
     * resumes contiguously from the first published seq &gt; the snapshot seq.
     */
    private static void verifyChainIntegrity(List<EdgeFrame> frames, List<Long> published) {
        // publishedIndexOf: published seq -> its index in publish order (for contiguity).
        // The published list is strictly ascending here (we publish 1,2,3,...), so the
        // index equals (seq - 1); we keep it general via a search to honor the contract
        // wording ("contiguous over the published chain", not raw seq arithmetic).
        long prevEmitted = -1;          // last emitted seq (-1 before any)
        int prevPublishedIdx = -1;      // index of prevEmitted in published
        boolean afterSnapshot = false;  // a snapshot just reset the stream
        long snapshotSeq = -1;

        for (EdgeFrame f : frames) {
            switch (f) {
                case EdgeFrame.SnapshotBegin b -> {
                    afterSnapshot = true;
                    snapshotSeq = b.snapshotSeq();
                }
                case EdgeFrame.SnapshotEnd e -> {
                    // After a snapshot the next emitted seq must be > snapshotSeq, and the
                    // contiguity baseline resets to the snapshot point.
                    prevEmitted = e.snapshotSeq();
                    prevPublishedIdx = lastPublishedIdxAtOrBelow(published, e.snapshotSeq());
                }
                case EdgeFrame.Notify n -> {
                    for (CommitNotification cn : n.notifications()) {
                        long seq = cn.seq();
                        // verbatim: the emitted notification's seq is a published one.
                        int idx = published.indexOf(seq);
                        if (idx < 0) {
                            fail("emitted seq " + seq + " was never published (merge/fabrication)");
                        }
                        if (afterSnapshot) {
                            assertTrue(seq > snapshotSeq,
                                    "first post-snapshot seq " + seq + " must exceed snapshot seq " + snapshotSeq);
                            afterSnapshot = false;
                        }
                        // strictly ascending
                        assertTrue(seq > prevEmitted,
                                "non-ascending: emitted " + seq + " after " + prevEmitted);
                        // contiguous over the published chain: no published seq strictly
                        // between prevPublishedIdx and idx may be skipped.
                        if (prevPublishedIdx >= 0) {
                            assertTrue(idx == prevPublishedIdx + 1,
                                    "skip in published chain: emitted index " + idx
                                            + " followed " + prevPublishedIdx
                                            + " (seq " + seq + " after " + prevEmitted + ")");
                        }
                        prevEmitted = seq;
                        prevPublishedIdx = idx;
                    }
                }
                default -> { /* SubscribeOk / Heartbeat / ErrorClose / chunks - not chain links */ }
            }
        }
    }

    /** The publish-order index of the highest published seq &lt;= {@code seq}, or -1. */
    private static int lastPublishedIdxAtOrBelow(List<Long> published, long seq) {
        int best = -1;
        for (int i = 0; i < published.size(); i++) {
            if (published.get(i) <= seq) {
                best = i;
            } else {
                break; // published is ascending
            }
        }
        return best;
    }

    private static CommitNotification notif(long seq) {
        return new CommitNotification(seq, 1_000L + seq,
                new ConfigDelta(seq - 1, seq,
                        List.of(new ConfigMutation.Put("k" + seq, ("v" + seq).getBytes(StandardCharsets.UTF_8)))));
    }
}

package io.configd.edge;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeSnapshotCodec;
import io.configd.observability.InvariantMonitor;
import io.configd.observability.MetricsRegistry;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cutover-cursor exactness at the edge core, with CRAFTED frames - the test that would
 * catch a cutover-cursor off-by-one in either direction (the exact cutover cursor is the
 * MECHANISM; idempotent apply is defense-in-depth, and this matrix proves the defense
 * actually holds when the mechanism is violated by a buggy/duplicating/skipping delivery):
 * <ul>
 *   <li><b>Exact:</b> snapshot at S; first tail delta S+1 applies; cursor S+1.</li>
 *   <li><b>Off-by-one low (duplicate, S):</b> a redelivery of seq S - whose effect the
 *       snapshot already carries - with DIFFERENT (poisoned) bytes must be refused as
 *       stale, never double-applied: the snapshot's value survives byte-identically.</li>
 *   <li><b>Off-by-one high (skip, S+2):</b> a first tail delta of S+2 must be refused as
 *       a GAP (never applied, never skipped-over) and must queue the heal directive;
 *       the subsequent in-order S+1, S+2 then apply each exactly once.</li>
 *   <li><b>Duplicate transfer (same S):</b> a redelivered whole snapshot at the same seq
 *       (a dup-channel delivery) is idempotent over effect: same bytes, same cursor,
 *       cutover still exact afterwards.</li>
 * </ul>
 * The monotonic-read monitor is wired in test mode, so any monotonicity regression on the
 * read store fails these tests with an {@link AssertionError} from inside the seam.
 */
class BootstrapCutoverExactnessTest {

    private static final long S = 5L;

    static final class TestClock implements Clock {
        long timeMs = 1_000_000L;
        @Override public long currentTimeMillis() { return timeMs; }
        @Override public long nanoTime() { return timeMs * 1_000_000L; }
    }

    static final class RecordingSink implements EdgeClientCore.FrameSink {
        final List<EdgeFrame> sent = new ArrayList<>();
        @Override public boolean offer(EdgeFrame frame) {
            sent.add(frame);
            return true;
        }
        long lastAck() {
            long ack = -1;
            for (EdgeFrame f : sent) {
                if (f instanceof EdgeFrame.CursorAck a) {
                    ack = a.seq();
                }
            }
            return ack;
        }
    }

    private TestClock clock;
    private RecordingSink sink;
    private EdgeClientCore core;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        sink = new RecordingSink();
        MetricsRegistry metrics = new MetricsRegistry();
        // testMode=true -> a monotonic_read violation throws (fails the test).
        core = new EdgeClientCore(clock, new InvariantMonitor(metrics, true),
                metrics.counter(StalenessTracker.IMPLAUSIBLE_METRIC),
                StrongReadKeyClass.DEFAULT, sink,
                EdgeClientCore.DEFAULT_HEARTBEAT_MS, EdgeClientCore.DEFAULT_SILENCE_FACTOR);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static CommitNotification notif(long seq, String key, String value) {
        return new CommitNotification(seq, 1_000_000L, new ConfigDelta(seq - 1, seq,
                List.of(new ConfigMutation.Put(key, bytes(value)))));
    }

    private static ConfigSnapshot bootstrapStateAtS() {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        for (long seq = 1; seq <= S; seq++) {
            data = data.put("k" + seq, new VersionedValue(bytes("v" + seq), S, S));
        }
        return new ConfigSnapshot(data, S, S);
    }

    /** Plays the full SNAPSHOT_BEGIN..CHUNK*..END flow into the core (real reassembly). */
    private void deliverSnapshot(ConfigSnapshot snap, long seq) {
        byte[] body = EdgeSnapshotCodec.serialize(snap);
        List<EdgeFrame.SnapshotChunk> chunks = EdgeSnapshotCodec.chunk(body, 64); // multi-chunk
        core.onFrame(new EdgeFrame.SnapshotBegin(seq, chunks.size(), body.length));
        for (EdgeFrame.SnapshotChunk c : chunks) {
            core.onFrame(c);
        }
        core.onFrame(new EdgeFrame.SnapshotEnd(seq));
    }

    private void bootstrap() {
        core.onFrame(new EdgeFrame.SubscribeOk(S, EdgeFrame.Mode.SNAPSHOT_FIRST));
        deliverSnapshot(bootstrapStateAtS(), S);
        assertEquals(S, core.cursor(), "fixture: cutover at S");
        assertEquals(1, core.snapshotsApplied());
    }

    @Test
    void exactCutoverFirstTailDeltaIsSPlusOne() {
        bootstrap();
        core.onFrame(new EdgeFrame.Notify(List.of(notif(S + 1, "k6", "v6"))));
        assertEquals(S + 1, core.cursor(), "S+1 applies directly on the cutover cursor");
        assertEquals(1, core.appliedCount());
        assertEquals(0, core.gapsDetected(), "no gap on the exact cutover");
        assertArrayEquals(bytes("v6"), core.get("k6").value());
        assertEquals(S + 1, sink.lastAck());
    }

    @Test
    void redeliveredSeqSWithPoisonedBytesIsRefusedNeverDoubleApplied() {
        bootstrap();
        // The cutover-cursor off-by-one LOW: the wire redelivers seq S - whose effect the
        // snapshot already carries - but with DIFFERENT bytes (the worst case: a
        // double-apply would not be idempotent). It must be discarded as stale.
        core.onFrame(new EdgeFrame.Notify(List.of(notif(S, "k5", "POISONED"))));
        assertEquals(S, core.cursor(), "cursor unchanged by the duplicate");
        assertEquals(0, core.appliedCount(), "the duplicate must never apply");
        assertEquals(0, core.gapsDetected(), "a duplicate is stale, not a gap");
        assertArrayEquals(bytes("v5"), core.get("k5").value(),
                "the snapshot's effect for seq S survives byte-identically — no "
                        + "double-application divergence");
        core.onFrame(new EdgeFrame.Notify(List.of(notif(S + 1, "k6", "v6"))));
        assertEquals(S + 1, core.cursor());
    }

    @Test
    void craftedSkipToSPlus2IsRefusedAndHealedNeverSkipped() {
        bootstrap();
        // The cutover-cursor off-by-one HIGH: the first tail delta arrives as S+2 (seq
        // S+1's effect would be silently lost if this applied). It must be refused as a
        // GAP and the heal directive queued at the REAL cursor S.
        core.onFrame(new EdgeFrame.Notify(List.of(notif(S + 2, "k7", "v7"))));
        assertEquals(S, core.cursor(), "the gapped delta must NOT apply");
        assertEquals(1, core.gapsDetected());
        assertNull(core.get("k7").found() ? core.get("k7") : null,
                "no effect from the refused delta");
        EdgeClientCore.ConnectionDirective directive = core.pollDirective();
        assertInstanceOf(EdgeClientCore.ConnectionDirective.ReconnectNextEndpoint.class,
                directive, "the wedge queues the resubscribe heal");
        assertEquals(S, ((EdgeClientCore.ConnectionDirective.ReconnectNextEndpoint) directive)
                .resumeCursor(), "the heal carries the REAL cursor S — the server's "
                + "decideMode then resolves replay vs re-bootstrap");

        core.onFrame(new EdgeFrame.Notify(List.of(
                notif(S + 1, "k6", "v6"), notif(S + 2, "k7", "v7"))));
        assertEquals(S + 2, core.cursor());
        assertEquals(2, core.appliedCount());
        assertArrayEquals(bytes("v6"), core.get("k6").value());
        assertArrayEquals(bytes("v7"), core.get("k7").value());
    }

    @Test
    void duplicatedSnapshotTransferAtTheSameSeqIsIdempotentOverEffect() {
        bootstrap();
        // A dup-channel delivery of the SAME transfer again. Equal-seq is not backward:
        // it re-applies, and must be a no-op over effect - same bytes, same cursor,
        // monitor silent.
        deliverSnapshot(bootstrapStateAtS(), S);
        assertEquals(S, core.cursor());
        assertEquals(2, core.snapshotsApplied());
        assertEquals(0, core.backwardSnapshotsRefused(), "equal-seq is not backward");
        for (long seq = 1; seq <= S; seq++) {
            assertArrayEquals(bytes("v" + seq), core.get("k" + seq).value());
        }
        assertEquals(S, core.currentVersion());
        core.onFrame(new EdgeFrame.Notify(List.of(notif(S + 1, "k6", "v6"))));
        assertEquals(S + 1, core.cursor());
        assertEquals(0, core.gapsDetected());
    }

    @Test
    void backwardSnapshotAfterTailProgressIsRefusedTheCutoverNeverRegresses() {
        bootstrap();
        // Tail past S, then a STALE transfer (S again) arrives late - e.g. a dup-channel
        // redelivery landing after tail progress. seq S < cursor S+2 -> refused, re-ack.
        core.onFrame(new EdgeFrame.Notify(List.of(
                notif(S + 1, "k6", "v6"), notif(S + 2, "k6", "v6b"))));
        assertEquals(S + 2, core.cursor());
        deliverSnapshot(bootstrapStateAtS(), S);
        assertEquals(S + 2, core.cursor(), "the late duplicate transfer never regresses");
        assertEquals(1, core.backwardSnapshotsRefused());
        assertArrayEquals(bytes("v6b"), core.get("k6").value(),
                "post-cutover progress survives the stale transfer");
        assertEquals(S + 2, sink.lastAck(), "the refusal re-acks the REAL cursor");
        assertTrue(core.currentVersion() == S + 2);
    }
}

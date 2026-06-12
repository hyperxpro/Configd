package io.configd.distribution.fanout;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeSnapshotCodec;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Snapshot-transfer failure recovery (CT-31's "resume on failure" clause — RENEGOTIATED;
 * c1-contract-qa-audit REQUIRED gap 2, resolved here by the C3 design).
 *
 * <p>Architecture §7 (:272-273) words recovery as chunk-level "resume on failure". The
 * implemented protocol deliberately recovers at the TRANSFER level instead: a snapshot
 * transfer is unacknowledged on the wire ({@code performSnapshotTransfer} never advances
 * {@code lastAckedSeq} — the C1(a) bug-fix 1), so a transfer lost in transit rebuilds
 * ack-lag, the session re-demotes, and the WHOLE snapshot is re-sent until the edge's
 * CURSOR_ACK confirms application. Rationale (C3 design / C1 design-note bug fix 1): a
 * chunk-level resume protocol needs per-chunk acks and transfer-resume session state —
 * new wire surface and new failure modes — while transfers are snapshot-equivalent state
 * whose re-send is idempotent; the lossy-network sim measured the re-send loop healing
 * 100% of dropped transfers (vs ~75% of seeds stranded under the pre-fix optimistic ack).
 * §7's intent (a lost transfer must not strand the edge) is preserved; its mechanism
 * wording is superseded — recorded for the consolidated doc pass.
 *
 * <p>This test pins the renegotiated behavior end to end: transfer lost → ack-lag →
 * re-demote → full re-send → edge applies the re-sent transfer → contiguous resume.
 */
class SnapshotChunkResumeTest {

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

    @Test
    void lostSnapshotTransferIsResentWholeUntilAckedAndTheEdgeConverges() {
        FanOutBuffer buffer = new FanOutBuffer(8);
        for (int i = 1; i <= 20; i++) {
            commit(buffer, "k" + (i % 3), "v" + i);
        }
        ReplaySource replay = new SnapshotReplaySource(() -> new ConfigSnapshot(auth, version, 0L));
        // ack-lag 2 (the sim-scaled threshold) so the lost transfer re-demotes at test scale.
        FanOutConfig cfg = new FanOutConfig(64, 80, 64, 262_144, 2L, 250L, 5L, 1_048_576);
        FanOutSessionCore s = new FanOutSessionCore(buffer, replay, sink, cfg,
                FanOutSessionMetrics.NOOP, clock);

        // The edge resubscribes far behind the horizon → SNAPSHOT_FIRST.
        s.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 3L, -1L, "edge-r"));
        assertEquals(EdgeFrame.Mode.SNAPSHOT_FIRST,
                sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode());

        // Transfer #1 is emitted... and LOST in transit (the edge never sees or acks it).
        sink.clear();
        s.tick(clock.now());
        assertEquals(1, sink.sentOfType(EdgeFrame.SnapshotBegin.class).size());
        assertEquals(20L, s.cursor(), "cursor jumps to the snapshot seq");
        assertEquals(3L, s.lastAckedSeq(),
                "the transfer is UNACKNOWLEDGED: lastAckedSeq must NOT advance (C1(a) fix) — "
                        + "this is exactly what makes the lost transfer recoverable");

        // While unacked, the ack-lag (20 - 3 > 2) keeps re-demoting and re-sending the
        // WHOLE transfer — the self-healing re-send loop (idempotent by construction).
        sink.clear();
        clock.advance(10);
        s.tick(clock.now());
        clock.advance(10);
        s.tick(clock.now());
        List<EdgeFrame.SnapshotBegin> resent = sink.sentOfType(EdgeFrame.SnapshotBegin.class);
        assertTrue(resent.size() >= 1, "the lost transfer must be re-sent whole");
        for (EdgeFrame.SnapshotBegin b : resent) {
            assertEquals(20L, b.snapshotSeq(), "every re-send carries the full state at 20");
        }

        // This time the edge receives the LAST re-sent transfer: reassemble + apply + ack.
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int chunksPerTransfer = sink.sentOfType(EdgeFrame.SnapshotChunk.class).size() / resent.size();
        sink.sentOfType(EdgeFrame.SnapshotChunk.class).stream()
                .limit(chunksPerTransfer)
                .forEach(c -> body.writeBytes(c.bytes()));
        ConfigSnapshot applied = EdgeSnapshotCodec.deserialize(body.toByteArray());
        assertEquals(20L, applied.version(), "the re-sent transfer carries the full state");
        s.onCursorAck(20L);
        assertEquals(20L, s.lastAckedSeq());

        // Healed: the re-send loop QUIESCES once acked (at most one already-owed transfer
        // drains), and a new commit then streams as a contiguous NOTIFY, never a snapshot.
        for (int i = 0; i < 3; i++) {
            sink.clear();
            clock.advance(10);
            s.tick(clock.now());
        }
        assertTrue(sink.sentOfType(EdgeFrame.SnapshotBegin.class).isEmpty(),
                "the re-send loop stops once the edge's CURSOR_ACK confirms application");

        commit(buffer, "post", "p1");
        sink.clear();
        clock.advance(10);
        s.tick(clock.now());
        assertTrue(sink.sentOfType(EdgeFrame.SnapshotBegin.class).isEmpty(),
                "an acked transfer is never re-sent");
        assertEquals(21L, sink.sentOfType(EdgeFrame.Notify.class).get(0)
                .notifications().get(0).seq(), "contiguous resume from the snapshot point");
    }
}

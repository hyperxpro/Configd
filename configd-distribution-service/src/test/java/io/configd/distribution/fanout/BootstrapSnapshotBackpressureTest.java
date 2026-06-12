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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * C5 (CT-24) at the server core: the bootstrap snapshot transfer vs the BOUNDED transport
 * queue (the {@code FanOutServer.Connection} model — a non-blocking
 * {@code ArrayBlockingQueue.offer}, default 64 frames, drained by a writer thread).
 *
 * <p><b>The hole this test was written to catch (found RED, fixed in
 * {@code performSnapshotTransfer}):</b> the transfer emitted BEGIN + ALL chunks + END in
 * one burst inside a single {@code tick}. Any transfer whose chunk count exceeded the
 * transport queue's free space had chunks silently REFUSED mid-burst, which (a) marked
 * the session closed ({@code transport_gone}) on the first refused chunk, (b) then
 * resurrected it to STREAMING at the end of the transfer tail, and (c) delivered a TORN
 * chunk sequence to the edge — whose reassembly failure is routed into the ADR-0040
 * poison ladder (bounded retries → quarantine → TERMINAL process exit). Net effect: a
 * zero-state edge with a store larger than {@code transportQueueFrames ×
 * snapshotChunkBytes} (~64 MiB at production defaults; proportionally less for any
 * operator-tuned smaller chunk) could NEVER bootstrap — the exact C5 charter case.
 *
 * <p><b>Pinned behavior (the fix):</b> a refused snapshot-frame offer is transport
 * BACKPRESSURE, not transport death: the transfer pauses and resumes on the next tick
 * exactly where it left off (BEGIN / chunk index / END), completing once the writer has
 * drained queue space. The cutover mutations (cursor=S, resume STREAMING) happen ONLY at
 * completion; {@code lastAckedSeq} stays behind throughout (the C1(a) self-healing
 * discipline); nothing interleaves inside the BEGIN..END envelope; writes committed
 * during the paused transfer are delivered afterwards as the contiguous seq&gt;S tail
 * (the C5-1 exact-cutover clause, now proven under backpressure).
 *
 * <p>Deterministic: no threads — the test plays the writer's role by draining the
 * bounded sink between ticks.
 */
class BootstrapSnapshotBackpressureTest {

    /** Snapshot chunk size for the test: small chunks ⇒ many chunks ⇒ wide transfer. */
    private static final int CHUNK_BYTES = 1_024;
    /** Transport queue capacity (frames) — far below the chunk count, like a slow writer. */
    private static final int QUEUE_CAPACITY = 8;
    /** Store entries; ~1 KiB each ⇒ ≈ STORE_KEYS chunks at CHUNK_BYTES. */
    private static final int STORE_KEYS = 48;

    /**
     * The {@code FanOutServer.Connection} transport model, deterministic: a bounded
     * queue whose {@code offer} refuses when full, and an explicit {@link #drain} the
     * test calls to play the writer thread's role.
     */
    private static final class BoundedDrainingSink implements TransportSink {
        final Deque<EdgeFrame> queued = new ArrayDeque<>();
        final List<EdgeFrame> deliveredToEdge = new ArrayList<>();
        final int capacity;
        boolean closed;
        ErrorCode closeCode;

        BoundedDrainingSink() {
            this(QUEUE_CAPACITY);
        }

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
            closeCode = code;
        }

        /** The writer thread's turn: moves up to {@code n} frames onto the "wire". */
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

    private final FakeClock clock = new FakeClock(1_000L);
    private final BoundedDrainingSink sink = new BoundedDrainingSink();

    private HamtMap<String, VersionedValue> auth = HamtMap.empty();
    private long version;

    private void commit(FanOutBuffer buffer, String key, String val) {
        long seq = ++version;
        auth = auth.put(key, new VersionedValue(val.getBytes(StandardCharsets.UTF_8), seq, 0L));
        buffer.publish(new CommitNotification(seq, 1_000L + seq, new ConfigDelta(seq - 1, seq,
                List.of(new ConfigMutation.Put(key, val.getBytes(StandardCharsets.UTF_8))))));
    }

    /** A ~1 KiB value, unique per seq, so a torn/duplicated apply shows as a byte diff. */
    private static String fatValue(int i) {
        return ("v" + i + "-").repeat(180); // ≈ 1 KiB
    }

    private FanOutSessionCore newSession(FanOutBuffer buffer) {
        return newSession(buffer, sink, FanOutSessionMetrics.NOOP);
    }

    private FanOutSessionCore newSession(FanOutBuffer buffer, TransportSink transportSink,
                                         FanOutSessionMetrics metrics) {
        ReplaySource replay = new SnapshotReplaySource(() -> new ConfigSnapshot(auth, version, 0L));
        FanOutConfig cfg = new FanOutConfig(64, 80, 64, 262_144, 8_192L, 250L, 5L, CHUNK_BYTES);
        return new FanOutSessionCore(buffer, replay, transportSink, cfg, metrics, clock);
    }

    /** Counts {@link FanOutSessionMetrics#onSnapshotTransfer} (exactly-once accounting). */
    private static final class CountingMetrics implements FanOutSessionMetrics {
        int snapshotTransfers;
        @Override public void onNotifyBatch(int n, int bytes) { }
        @Override public void onQueueDepth(int depth) { }
        @Override public void onSlowConsumerWarning() { }
        @Override public void onDemotion(String reason) { }
        @Override public void onSnapshotTransfer() { snapshotTransfers++; }
        @Override public void onHeartbeat() { }
        @Override public void onSessionClosed(String reason) { }
    }

    @Test
    void transferExceedingTheTransportQueuePausesResumesAndDeliversExactChunkSequence() {
        FanOutBuffer buffer = new FanOutBuffer(10_000);
        for (int i = 1; i <= STORE_KEYS; i++) {
            commit(buffer, "boot/k" + i, fatValue(i));
        }
        long s = version; // the snapshot seq S the bootstrap will cut over at
        FanOutSessionCore session = newSession(buffer);

        // Zero-state edge joins: cursor 0 + data exists ⇒ SNAPSHOT_FIRST (the C3 rule).
        session.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge-boot"));
        sink.drain(1); // SUBSCRIBE_OK
        assertEquals(EdgeFrame.Mode.SNAPSHOT_FIRST,
                sink.delivered(EdgeFrame.SubscribeOk.class).get(0).mode());

        // First tick: the transfer MUST NOT die on the bounded queue (this assertion is
        // the red/green pivot: pre-fix the first refused chunk marked the session closed
        // with onSessionClosed("transport_gone"), then resurrected it — both wrong).
        clock.advance(10);
        session.tick(clock.now());
        assertNotEquals(FanOutSessionCore.SessionState.CLOSED, session.state(),
                "a bounded transport queue mid-snapshot is BACKPRESSURE, not transport death");

        // While the transfer is paused on backpressure, writes keep committing — the
        // sustained-concurrent-writes case. They must come out AFTER the snapshot as the
        // contiguous seq>S tail.
        commit(buffer, "straddle/a", "during-1");
        commit(buffer, "straddle/b", "during-2");

        // Play the writer: drain a few frames, tick, repeat until SNAPSHOT_END lands.
        int guard = 0;
        while (sink.delivered(EdgeFrame.SnapshotEnd.class).isEmpty()) {
            assertTrue(++guard < 10_000, "transfer must complete once the writer drains");
            sink.drain(4);
            clock.advance(1);
            session.tick(clock.now());
        }
        sink.drain(Integer.MAX_VALUE); // flush the remainder onto the wire

        // --- exactness of the delivered transfer ---
        List<EdgeFrame.SnapshotBegin> begins = sink.delivered(EdgeFrame.SnapshotBegin.class);
        List<EdgeFrame.SnapshotChunk> chunks = sink.delivered(EdgeFrame.SnapshotChunk.class);
        List<EdgeFrame.SnapshotEnd> ends = sink.delivered(EdgeFrame.SnapshotEnd.class);
        assertEquals(1, begins.size(), "exactly one BEGIN (no torn restart mid-envelope)");
        assertEquals(1, ends.size(), "exactly one END");
        assertEquals(s, begins.get(0).snapshotSeq());
        assertEquals(s, ends.get(0).snapshotSeq());
        assertEquals(begins.get(0).chunkCount(), chunks.size(),
                "every declared chunk delivered — none silently dropped by backpressure");
        assertTrue(chunks.size() > QUEUE_CAPACITY,
                "non-vacuity: the transfer genuinely exceeded the queue capacity ("
                        + chunks.size() + " chunks vs queue " + QUEUE_CAPACITY + ")");
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).index(), "chunks in order, no gap, no duplicate");
        }

        // Nothing may interleave inside the BEGIN..END envelope.
        int beginAt = sink.deliveredToEdge.indexOf(begins.get(0));
        int endAt = sink.deliveredToEdge.indexOf(ends.get(0));
        for (int i = beginAt + 1; i < endAt; i++) {
            assertTrue(sink.deliveredToEdge.get(i) instanceof EdgeFrame.SnapshotChunk,
                    "no frame may interleave inside the snapshot envelope, found "
                            + sink.deliveredToEdge.get(i).getClass().getSimpleName());
        }

        // The reassembled body is the byte-exact state at S (what the edge will load).
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        chunks.forEach(c -> body.writeBytes(c.bytes()));
        ConfigSnapshot received = EdgeSnapshotCodec.deserialize(
                EdgeSnapshotCodec.reassemble(chunks));
        assertEquals(s, received.version(), "the transfer carries the full state at S");
        for (int i = 1; i <= STORE_KEYS; i++) {
            VersionedValue vv = received.data().get("boot/k" + i);
            assertTrue(vv != null && new String(vv.valueUnsafe(), StandardCharsets.UTF_8)
                    .equals(fatValue(i)), "boot/k" + i + " byte-exact in the snapshot");
        }

        // --- the exact cutover under backpressure (C5-1) ---
        // The completion tick legitimately continues into the streaming drain, so the
        // cursor may already be past S (it tails the straddle writes the same tick);
        // the EXACTNESS proof is the first-tail-seq assertion below.
        assertTrue(session.cursor() >= s, "cursor reached S at transfer completion");
        assertEquals(0L, session.lastAckedSeq(),
                "the transfer stays UNACKNOWLEDGED (C1(a) self-healing discipline)");
        assertEquals(FanOutSessionCore.SessionState.STREAMING, session.state());

        // The straddle writes (committed while the transfer was paused) come out as the
        // contiguous seq>S tail — no skip, no re-delivery of anything ≤ S.
        session.onCursorAck(s); // the edge applied the snapshot
        clock.advance(10);
        session.tick(clock.now());
        sink.drain(Integer.MAX_VALUE);
        List<EdgeFrame.Notify> notifies = sink.delivered(EdgeFrame.Notify.class);
        assertTrue(!notifies.isEmpty(), "the straddle writes must be delivered after cutover");
        List<CommitNotification> tail = new ArrayList<>();
        notifies.forEach(n -> tail.addAll(n.notifications()));
        assertEquals(s + 1, tail.get(0).seq(),
                "first tail seq is EXACTLY S+1 — the exact cutover cursor");
        for (int i = 0; i < tail.size(); i++) {
            assertEquals(s + 1 + i, tail.get(i).seq(), "tail contiguous from S+1");
        }
        assertEquals(version, tail.get(tail.size() - 1).seq(),
                "every straddle write delivered");
    }

    @Test
    void fullyBlockedTransportHoldsTheTransferOpenWithoutClosingOrRegressing() {
        FanOutBuffer buffer = new FanOutBuffer(10_000);
        for (int i = 1; i <= STORE_KEYS; i++) {
            commit(buffer, "boot/k" + i, fatValue(i));
        }
        FanOutSessionCore session = newSession(buffer);
        session.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge-stuck"));
        // Do NOT drain: the queue fills at SUBSCRIBE_OK + 7 frames and stays full.
        for (int t = 0; t < 50; t++) {
            clock.advance(10);
            session.tick(clock.now());
            // The session must neither die nor declare the cutover done while blocked.
            assertEquals(FanOutSessionCore.SessionState.CATCHUP, session.state(),
                    "a blocked transfer holds CATCHUP (resumable), never CLOSED, never a "
                            + "premature STREAMING resurrection");
            assertEquals(0L, session.cursor(), "no cutover until the transfer completes");
        }
        assertTrue(!sink.closed, "the core never close()d the healthy-but-slow transport");

        // The writer comes back: the transfer completes from where it paused.
        int guard = 0;
        while (sink.delivered(EdgeFrame.SnapshotEnd.class).isEmpty()) {
            assertTrue(++guard < 10_000, "transfer completes once the writer drains");
            sink.drain(QUEUE_CAPACITY);
            clock.advance(1);
            session.tick(clock.now());
        }
        assertEquals(version, session.cursor());
        assertEquals(FanOutSessionCore.SessionState.STREAMING, session.state());
        List<EdgeFrame.SnapshotChunk> chunks = sink.delivered(EdgeFrame.SnapshotChunk.class);
        sink.drain(Integer.MAX_VALUE);
        chunks = sink.delivered(EdgeFrame.SnapshotChunk.class);
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).index(), "resume continues the SAME envelope");
        }
        if (sink.delivered(EdgeFrame.SnapshotBegin.class).size() != 1) {
            fail("the paused transfer must resume, not restart: "
                    + sink.delivered(EdgeFrame.SnapshotBegin.class).size() + " BEGINs");
        }
    }

    /**
     * The harshest pacing: a ONE-slot transport, never pre-drained — EVERY frame
     * (BEGIN included: the SUBSCRIBE_OK still occupies the slot when the transfer
     * starts) hits the would-block branch at least once. The envelope must still come
     * out exactly once, in order, with the cutover deferred to the accepted END, and
     * {@code onSnapshotTransfer} must fire EXACTLY once (a dropped-BEGIN or
     * dropped-END mutant of the pacing branches fails the envelope or the guard here).
     */
    @Test
    void singleSlotTransportPacesEveryFrameYetTheEnvelopeStaysExactlyOnceInOrder() {
        BoundedDrainingSink oneSlot = new BoundedDrainingSink(1);
        CountingMetrics metrics = new CountingMetrics();
        FanOutBuffer buffer = new FanOutBuffer(10_000);
        for (int i = 1; i <= 6; i++) {
            commit(buffer, "boot/k" + i, fatValue(i));
        }
        long s = version;
        FanOutSessionCore session = newSession(buffer, oneSlot, metrics);

        // SUBSCRIBE_OK fills the single slot — the transfer's BEGIN is refused first.
        session.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge-1slot"));
        clock.advance(10);
        session.tick(clock.now());
        assertTrue(oneSlot.delivered(EdgeFrame.SnapshotBegin.class).isEmpty()
                        && oneSlot.queued.size() == 1,
                "fixture: BEGIN was refused while SUBSCRIBE_OK held the slot");
        assertEquals(0L, session.cursor(), "no cutover while even BEGIN is owed");

        int guard = 0;
        while (oneSlot.delivered(EdgeFrame.SnapshotEnd.class).isEmpty()) {
            assertTrue(++guard < 10_000, "the one-slot-paced transfer must complete");
            oneSlot.drain(1); // the writer frees exactly one slot per turn
            clock.advance(1);
            session.tick(clock.now());
            boolean endAccepted = !oneSlot.delivered(EdgeFrame.SnapshotEnd.class).isEmpty()
                    || oneSlot.queued.stream().anyMatch(f -> f instanceof EdgeFrame.SnapshotEnd);
            if (!endAccepted) {
                assertEquals(0, metrics.snapshotTransfers,
                        "the transfer must not be counted complete before END is ACCEPTED "
                                + "by the transport (acceptance, not edge delivery, is the "
                                + "server-side completion point)");
            }
        }
        oneSlot.drain(Integer.MAX_VALUE);

        assertEquals(1, oneSlot.delivered(EdgeFrame.SnapshotBegin.class).size(),
                "exactly one BEGIN despite its initial refusal (paused, not dropped)");
        List<EdgeFrame.SnapshotChunk> chunks = oneSlot.delivered(EdgeFrame.SnapshotChunk.class);
        assertEquals(oneSlot.delivered(EdgeFrame.SnapshotBegin.class).get(0).chunkCount(),
                chunks.size(), "every declared chunk delivered exactly once");
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).index(), "chunks in order, no gap, no duplicate");
        }
        assertEquals(1, oneSlot.delivered(EdgeFrame.SnapshotEnd.class).size());
        assertEquals(1, metrics.snapshotTransfers,
                "onSnapshotTransfer fires EXACTLY once, at completion");
        assertEquals(s, session.cursor(), "cutover at S, only after END was accepted");
        ConfigSnapshot received = EdgeSnapshotCodec.deserialize(
                EdgeSnapshotCodec.reassemble(chunks));
        assertEquals(s, received.version(), "the paced body is the byte-exact state at S");
    }

    /** The replay-source failure path: fatal close, never a torn/looping transfer. */
    @Test
    void replaySourceFailureClosesGapUnrecoverableAndStopsTheSession() {
        FanOutBuffer buffer = new FanOutBuffer(10_000);
        for (int i = 1; i <= 3; i++) {
            commit(buffer, "boot/k" + i, fatValue(i));
        }
        ReplaySource failing = () -> {
            throw new IllegalStateException("replay range gone");
        };
        FanOutConfig cfg = new FanOutConfig(64, 80, 64, 262_144, 8_192L, 250L, 5L, CHUNK_BYTES);
        FanOutSessionCore session = new FanOutSessionCore(
                buffer, failing, sink, cfg, FanOutSessionMetrics.NOOP, clock);
        session.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge-fail"));
        clock.advance(10);
        session.tick(clock.now());
        assertEquals(FanOutSessionCore.SessionState.CLOSED, session.state(),
                "an unrecoverable replay closes the session — never resurrects");
        assertTrue(sink.closed, "the transport was closed");
        assertEquals(ErrorCode.GAP_UNRECOVERABLE, sink.closeCode);
        // And it stays down (no zombie transfer on later ticks).
        clock.advance(10);
        session.tick(clock.now());
        assertEquals(FanOutSessionCore.SessionState.CLOSED, session.state());
        assertTrue(sink.delivered(EdgeFrame.SnapshotBegin.class).isEmpty(),
                "no transfer frames after the fatal close");
    }
}

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
 * Pins the bootstrap snapshot transfer against the bounded transport queue (the
 * {@code FanOutServer.Connection} model: a non-blocking {@code ArrayBlockingQueue.offer},
 * default 64 frames, drained by a writer thread).
 *
 * <p><b>The failure mode this guards against:</b> a snapshot transfer that emits BEGIN,
 * all chunks, and END in one burst inside a single {@code tick} silently drops chunks
 * mid-burst whenever the chunk count exceeds the free space in the transport queue. That
 * would mark the session closed ({@code transport_gone}) on the first refused chunk, then
 * resurrect it to STREAMING at the end of the transfer tail, and deliver a torn chunk
 * sequence to the edge, whose reassembly failure is routed through the poison ladder
 * (bounded retries, then quarantine, then a terminal process exit). The net effect: a
 * zero-state edge with a store larger than
 * {@code transportQueueFrames x snapshotChunkBytes} (about 64 MiB at production defaults,
 * proportionally less for any operator-tuned smaller chunk) could never bootstrap.
 *
 * <p><b>Pinned behavior:</b> a refused snapshot-frame offer is transport backpressure, not
 * transport death: the transfer pauses and resumes on the next tick exactly where it left
 * off (BEGIN, chunk index, or END), completing once the writer has drained queue space.
 * The cutover mutations (cursor moves to S, resume STREAMING) happen only at completion;
 * {@code lastAckedSeq} stays behind throughout as a self-healing discipline; nothing
 * interleaves inside the BEGIN through END envelope; writes committed during the paused
 * transfer are delivered afterwards as the contiguous tail with seq greater than S.
 *
 * <p>Deterministic, no threads: the test plays the role of the writer by draining the
 * bounded sink between ticks.
 */
class BootstrapSnapshotBackpressureTest {

    /** Snapshot chunk size for the test: small chunks mean many chunks, giving a wide transfer. */
    private static final int CHUNK_BYTES = 1_024;
    /** Transport queue capacity (frames) - far below the chunk count, like a slow writer. */
    private static final int QUEUE_CAPACITY = 8;
    /** Store entries, about 1 KiB each, giving approximately STORE_KEYS chunks at CHUNK_BYTES. */
    private static final int STORE_KEYS = 48;

    /**
     * The {@code FanOutServer.Connection} transport model, deterministic: a bounded queue
     * whose {@code offer} refuses when full, and an explicit {@link #drain} the test calls
     * to play the role of the writer thread.
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
                return false; // a non-blocking bounded queue treats full as would-block
            }
            queued.addLast(frame);
            return true;
        }

        @Override
        public void close(ErrorCode code, String message) {
            closed = true;
            closeCode = code;
        }

        /** The turn of the writer thread: moves up to {@code n} frames onto the wire. */
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

    /** A value of about 1 KiB, unique per seq, so a torn or duplicated apply shows as a byte diff. */
    private static String fatValue(int i) {
        return ("v" + i + "-").repeat(180); // about 1 KiB
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

        // A zero-state edge joins: cursor 0 with data already existing selects SNAPSHOT_FIRST.
        session.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge-boot"));
        sink.drain(1); // SUBSCRIBE_OK
        assertEquals(EdgeFrame.Mode.SNAPSHOT_FIRST,
                sink.delivered(EdgeFrame.SubscribeOk.class).get(0).mode());

        // First tick: the transfer must not die on the bounded queue. A refused chunk
        // here must not mark the session closed with onSessionClosed(transport_gone) and
        // then resurrect it to STREAMING afterward - both would be wrong.
        clock.advance(10);
        session.tick(clock.now());
        assertNotEquals(FanOutSessionCore.SessionState.CLOSED, session.state(),
                "a bounded transport queue mid-snapshot is BACKPRESSURE, not transport death");

        // While the transfer is paused on backpressure, writes keep committing, the
        // sustained concurrent writes case. They must come out after the snapshot as the
        // contiguous tail with seq greater than S.
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

        // Exactness of the delivered transfer.
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

        // Nothing may interleave inside the BEGIN through END envelope.
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

        // The completion tick legitimately continues into the streaming drain, so the
        // cursor may already be past S, since it tails the straddle writes in the same
        // tick. The exact proof is the first-tail-seq assertion below.
        assertTrue(session.cursor() >= s, "cursor reached S at transfer completion");
        assertEquals(0L, session.lastAckedSeq(),
                "the transfer stays UNACKNOWLEDGED (C1(a) self-healing discipline)");
        assertEquals(FanOutSessionCore.SessionState.STREAMING, session.state());

        // The straddle writes, committed while the transfer was paused, come out as the
        // contiguous tail with seq greater than S: no skip, no re-delivery of anything at
        // or below S.
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
        // Do not drain: the queue fills at SUBSCRIBE_OK plus 7 frames and stays full.
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
     * The harshest pacing: a one-slot transport, never pre-drained, so every frame,
     * including BEGIN since the SUBSCRIBE_OK still occupies the slot when the transfer
     * starts, hits the would-block branch at least once. The envelope must still come out
     * exactly once, in order, with the cutover deferred to the accepted END, and
     * {@code onSnapshotTransfer} must fire exactly once: a variant of the pacing logic
     * that drops BEGIN or drops END would fail the envelope check or the guard here.
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

        // SUBSCRIBE_OK fills the single slot, so the BEGIN of the transfer is refused first.
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

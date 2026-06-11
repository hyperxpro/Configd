package io.configd.distribution.fanout;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.CommitNotificationSource.Result;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.EdgeSnapshotCodec;
import io.configd.distribution.wire.ErrorCode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The transport-agnostic, per-subscriber fan-out session engine (C1 design §2/§4;
 * ADR-0034 consumer loop; ADR-0038 verbatim-signed-chain delivery). One instance per
 * subscribed edge: the SAME code the live {@code FanOutServer} (part b) drives from a
 * virtual thread and the simulator drives tick-by-tick as its {@code StreamDriver}.
 *
 * <h2>Determinism &amp; threading</h2>
 * Deterministic and single-threaded-per-instance: no threads, no wall-clock reads, no
 * sleeps inside. Time enters only through {@link #tick(long)}'s {@code nowMillis} (the
 * caller's {@link Clock} / sim clock). It PULLS via {@link CommitNotificationSource#readSince}
 * / {@link ReplaySource}; it never touches the publish path (charter §6 rule 4 / CT-22) and
 * holds no lock.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #onSubscribe(EdgeFrame.Subscribe)} — emits {@code SUBSCRIBE_OK} with the
 *       {@link EdgeFrame.Mode} decision (TAIL vs SNAPSHOT_FIRST).</li>
 *   <li>{@link #tick(long)} — drains {@code readSince(cursor)} into bounded {@code NOTIFY}
 *       batches (verbatim, never merged — ADR-0038); demotes to catch-up on queue
 *       overflow / ack-lag / GAP; runs the snapshot flow; emits a {@code HEARTBEAT} when
 *       idle past {@code heartbeatMs}.</li>
 *   <li>{@link #onCursorAck(long)} — advances the ack watermark, releasing in-flight
 *       frame accounting.</li>
 * </ol>
 *
 * <h2>States ({@link SessionState})</h2>
 * {@code STREAMING → (queue overflow | readSince GAP | ack-lag | transport block) →
 * CATCHUP (snapshot+chunks) → STREAMING}. Never an unbounded queue; never a silent drop —
 * every demotion records a {@link DemotionEvent} (cursor evidence) + a metric (CT-26).
 */
public final class FanOutSessionCore {

    /** The C1 session states (C4 adds quarantine policy on top of the transition events). */
    public enum SessionState {
        /** Tailing {@code readSince(cursor)} into NOTIFY batches. */
        STREAMING,
        /** Demoted: a snapshot transfer is owed before tailing resumes. */
        CATCHUP,
        /** Closed; no further frames are emitted. */
        CLOSED
    }

    private final CommitNotificationSource source;
    private final ReplaySource replaySource;
    private final TransportSink sink;
    private final FanOutConfig config;
    private final FanOutSessionMetrics metrics;
    private final Clock clock;

    /** Optional structured demotion-event listener (CT-26 cursor evidence; C4 substrate). */
    private final Consumer<DemotionEvent> demotionListener;

    private SessionState state = SessionState.STREAMING;
    private boolean subscribed;

    /** Last applied-mutation seq S the session has STREAMED to the edge (the cursor). */
    private long cursor;

    /** Highest seq the edge has acknowledged via CURSOR_ACK. */
    private long lastAckedSeq;

    /** In-flight NOTIFY frames, by their highest contained seq, awaiting CURSOR_ACK. FIFO. */
    private final Deque<Long> inFlightFrameMaxSeq = new ArrayDeque<>();

    /** Sim/wall time (ms) of the last frame this session emitted — heartbeat cadence input. */
    private long lastTrafficMillis = Long.MIN_VALUE;

    /** Whether a slow-consumer warning has already fired since the last drop below threshold. */
    private boolean slowConsumerWarned;

    /** Set when a demotion is pending: the next tick performs the snapshot flow. */
    private boolean catchupSnapshotOwed;

    /** The most recent demotion event (diagnostic; null until the first demotion). */
    private DemotionEvent lastDemotion;

    private int demotionCount;

    public FanOutSessionCore(CommitNotificationSource source, ReplaySource replaySource,
                             TransportSink sink, FanOutConfig config,
                             FanOutSessionMetrics metrics, Clock clock) {
        this(source, replaySource, sink, config, metrics, clock, null);
    }

    /**
     * @param demotionListener optional callback fired with the {@link DemotionEvent} on every
     *                         demotion (in addition to {@link FanOutSessionMetrics#onDemotion});
     *                         may be null
     */
    public FanOutSessionCore(CommitNotificationSource source, ReplaySource replaySource,
                             TransportSink sink, FanOutConfig config,
                             FanOutSessionMetrics metrics, Clock clock,
                             Consumer<DemotionEvent> demotionListener) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.replaySource = Objects.requireNonNull(replaySource, "replaySource must not be null");
        this.sink = Objects.requireNonNull(sink, "sink must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.demotionListener = demotionListener;
    }

    // -----------------------------------------------------------------------
    // Subscribe
    // -----------------------------------------------------------------------

    /**
     * Handles the edge's {@code SUBSCRIBE} and emits {@code SUBSCRIBE_OK}. The session
     * cursor is set to the subscribe's {@link EdgeFrame.Subscribe#effectiveResumeCursor()}
     * (the larger of resume / failover-resume — the §3 reserved field). The TAIL vs
     * SNAPSHOT_FIRST decision:
     * <ul>
     *   <li><b>SNAPSHOT_FIRST</b> if {@code readSince(cursor)} would GAP (the cursor is
     *       behind the cache tail), OR a fresh subscriber ({@code cursor == 0}) whose
     *       backlog ({@code latestSeq - oldestSeq + 1}) exceeds {@code queueFrames} (the
     *       snapshot is cheaper than streaming the whole backlog within the bounded
     *       queue — design §4: "cursor==0 &amp;&amp; snapshot smaller than backlog").</li>
     *   <li><b>TAIL</b> otherwise (the cursor is recoverable from the tail).</li>
     * </ul>
     * On SNAPSHOT_FIRST the snapshot transfer is performed on the next {@link #tick(long)}
     * (the session enters {@link SessionState#CATCHUP}).
     *
     * @param subscribe the subscribe frame
     */
    public void onSubscribe(EdgeFrame.Subscribe subscribe) {
        Objects.requireNonNull(subscribe, "subscribe must not be null");
        if (state == SessionState.CLOSED) {
            return;
        }
        if (subscribed) {
            // One subscribe per connection (§3). A second is a protocol violation.
            closeWith(ErrorCode.PROTOCOL_VIOLATION, "duplicate SUBSCRIBE");
            return;
        }
        subscribed = true;
        this.cursor = subscribe.effectiveResumeCursor();
        this.lastAckedSeq = cursor;

        long latest = source.latestSeq();
        EdgeFrame.Mode mode = decideMode(cursor, latest);

        emit(new EdgeFrame.SubscribeOk(latest, mode));
        if (mode == EdgeFrame.Mode.SNAPSHOT_FIRST) {
            state = SessionState.CATCHUP;
            catchupSnapshotOwed = true;
        } else {
            state = SessionState.STREAMING;
        }
    }

    private EdgeFrame.Mode decideMode(long cursor, long latest) {
        if (latest < 0) {
            // Empty buffer: nothing to snapshot, just tail when data arrives.
            return EdgeFrame.Mode.TAIL;
        }
        Result probe = source.readSince(cursor);
        if (probe.isGap()) {
            return EdgeFrame.Mode.SNAPSHOT_FIRST;
        }
        if (cursor == 0) {
            long oldest = source.oldestSeq();
            long backlog = (oldest < 0) ? 0 : (latest - oldest + 1);
            if (backlog > config.queueFrames()) {
                return EdgeFrame.Mode.SNAPSHOT_FIRST;
            }
        }
        return EdgeFrame.Mode.TAIL;
    }

    // -----------------------------------------------------------------------
    // Tick (the drain / catch-up / heartbeat loop)
    // -----------------------------------------------------------------------

    /**
     * Advances the session one step at logical time {@code nowMillis}: performs an owed
     * snapshot transfer (catch-up), drains new notifications into bounded NOTIFY batches,
     * and emits a heartbeat if idle. Deterministic — all behavior is a function of the
     * source contents, the acks received, and {@code nowMillis}.
     *
     * @param nowMillis the caller's logical/wall time in ms
     */
    public void tick(long nowMillis) {
        if (state == SessionState.CLOSED || !subscribed) {
            return;
        }
        boolean emittedThisTick = false;

        if (state == SessionState.CATCHUP && catchupSnapshotOwed) {
            emittedThisTick |= performSnapshotTransfer();
            // performSnapshotTransfer resumes STREAMING (or closes on an unrecoverable replay).
        }

        if (state == SessionState.STREAMING) {
            emittedThisTick |= drainStreaming(nowMillis);
        }

        if (state == SessionState.STREAMING && !emittedThisTick) {
            maybeHeartbeat(nowMillis);
        }
    }

    /**
     * Drains {@code readSince(cursor)} into bounded NOTIFY batches. Returns true if any
     * frame was emitted. Demotes on GAP / queue overflow / ack-lag / transport block.
     */
    private boolean drainStreaming(long nowMillis) {
        // Ack-lag breach check FIRST (a stuck consumer that never acks must demote even if
        // new data keeps it under the frame cap — design §4 ack-lag signal).
        if (cursor - lastAckedSeq > config.ackLagDemoteSeqs()) {
            demote(DemotionEvent.REASON_ACK_LAG);
            return true; // the DEMOTED_TO_CATCHUP frame was emitted
        }

        Result r = source.readSince(cursor);
        if (r.isGap()) {
            demote(DemotionEvent.REASON_GAP);
            return true;
        }
        List<CommitNotification> pending = ((Result.Ok) r).notifications();
        if (pending.isEmpty()) {
            return false;
        }

        boolean emitted = false;
        int idx = 0;
        while (idx < pending.size()) {
            // Bounded outbound queue: never exceed queueFrames unacked NOTIFY frames.
            if (inFlightFrameMaxSeq.size() >= config.queueFrames()) {
                demote(DemotionEvent.REASON_QUEUE_OVERFLOW);
                return true;
            }
            // Assemble one batch respecting batchMaxNotifications / batchMaxBytes.
            List<CommitNotification> batch = new ArrayList<>();
            int batchBytes = 4; // NOTIFY count field
            long batchMaxSeq = cursor;
            while (idx < pending.size() && batch.size() < config.batchMaxNotifications()) {
                CommitNotification n = pending.get(idx);
                int encodedBytes = encodedNotificationBytes(n);
                if (!batch.isEmpty() && batchBytes + encodedBytes > config.batchMaxBytes()) {
                    break; // byte cap — close this batch, start the next frame
                }
                batch.add(n);
                batchBytes += encodedBytes;
                batchMaxSeq = n.seq();
                idx++;
            }
            EdgeFrame.Notify frame = new EdgeFrame.Notify(batch);
            if (!sink.offer(frame)) {
                // Transport would block — bounded by construction; demote (never buffer
                // unboundedly, never drop silently).
                demote(DemotionEvent.REASON_TRANSPORT_BLOCK);
                return true;
            }
            inFlightFrameMaxSeq.addLast(batchMaxSeq);
            cursor = batchMaxSeq;
            metrics.onNotifyBatch(batch.size(), batchBytes);
            metrics.onQueueDepth(inFlightFrameMaxSeq.size());
            maybeWarnSlowConsumer();
            lastTrafficMillis = nowMillis;
            emitted = true;
        }
        return emitted;
    }

    /** The DEMOTED→snapshot→resume-tail flow (design §4). Returns true if a frame was emitted. */
    private boolean performSnapshotTransfer() {
        catchupSnapshotOwed = false;
        ReplaySource.Replay replay;
        try {
            replay = replaySource.replayFromSnapshot();
        } catch (RuntimeException e) {
            // Replay source unavailable for the needed range — fatal for this session.
            closeWith(ErrorCode.GAP_UNRECOVERABLE, "replay unavailable: " + e.getMessage());
            return true;
        }
        byte[] body = EdgeSnapshotCodec.serialize(replay.snapshot());
        List<EdgeFrame.SnapshotChunk> chunks = EdgeSnapshotCodec.chunk(body, snapshotChunkBytes());

        emit(new EdgeFrame.SnapshotBegin(replay.seq(), chunks.size(), body.length));
        for (EdgeFrame.SnapshotChunk chunk : chunks) {
            emit(chunk);
        }
        emit(new EdgeFrame.SnapshotEnd(replay.seq()));
        metrics.onSnapshotTransfer();

        // Cursor jumps to the snapshot seq so tailing resumes from there; clear stale
        // in-flight accounting and resume TAIL.
        //
        // CRITICAL: the snapshot transfer is unacknowledged on the wire — do NOT advance
        // lastAckedSeq here. Optimistically marking the edge as caught-up would silently
        // strand it if the snapshot frame is lost in transit (the session would then idle,
        // believing the edge converged, while the edge sits at a stale version forever).
        // Leaving lastAckedSeq behind means a lost snapshot rebuilds ack-lag and the session
        // re-demotes + re-snapshots until the edge's CURSOR_ACK confirms application — the
        // robust, self-healing behavior. (Witnessed on the lossy edge-network sim: without
        // this, ~75% of would-converge seeds stranded an edge at an intermediate version.)
        cursor = replay.seq();
        inFlightFrameMaxSeq.clear();
        slowConsumerWarned = false;
        state = SessionState.STREAMING;
        return true;
    }

    private int snapshotChunkBytes() {
        return Math.min(config.snapshotChunkBytes(), EdgeFrameCodec.MAX_SNAPSHOT_CHUNK_BYTES);
    }

    private void maybeHeartbeat(long nowMillis) {
        if (lastTrafficMillis == Long.MIN_VALUE) {
            // First quiet period: anchor the cadence at this tick so the first heartbeat
            // is one interval away (avoids an immediate heartbeat at t=0).
            lastTrafficMillis = nowMillis;
            return;
        }
        if (nowMillis - lastTrafficMillis >= config.heartbeatMs()) {
            emit(new EdgeFrame.Heartbeat(source.latestSeq(), nowMillis));
            metrics.onHeartbeat();
            lastTrafficMillis = nowMillis;
        }
    }

    // -----------------------------------------------------------------------
    // Cursor ack
    // -----------------------------------------------------------------------

    /**
     * Records a {@code CURSOR_ACK}: advances {@link #lastAckedSeq} and releases every
     * in-flight NOTIFY frame whose highest seq is ≤ {@code seq} (bounded-queue accounting).
     * A stale or duplicate ack ({@code seq <= lastAckedSeq}) is ignored.
     *
     * @param seq the highest applied seq the edge acknowledges
     */
    public void onCursorAck(long seq) {
        if (state == SessionState.CLOSED) {
            return;
        }
        if (seq <= lastAckedSeq) {
            return; // stale / duplicate — never moves the watermark backward
        }
        lastAckedSeq = seq;
        while (!inFlightFrameMaxSeq.isEmpty() && inFlightFrameMaxSeq.peekFirst() <= seq) {
            inFlightFrameMaxSeq.pollFirst();
        }
        metrics.onQueueDepth(inFlightFrameMaxSeq.size());
        if (inFlightFrameMaxSeq.size() < config.queueWarnThresholdFrames()) {
            slowConsumerWarned = false; // re-arm the warning once back under threshold
        }
    }

    // -----------------------------------------------------------------------
    // Demotion / close
    // -----------------------------------------------------------------------

    private void demote(String reason) {
        demotionCount++;
        DemotionEvent event = new DemotionEvent(cursor, lastAckedSeq, reason);
        lastDemotion = event;
        // Non-fatal notice to the edge (the demotion code).
        emit(new EdgeFrame.ErrorClose(ErrorCode.DEMOTED_TO_CATCHUP, reason));
        // Drop pending outbound accounting — the snapshot supersedes everything in flight.
        inFlightFrameMaxSeq.clear();
        slowConsumerWarned = false;
        metrics.onDemotion(reason);
        if (demotionListener != null) {
            demotionListener.accept(event);
        }
        state = SessionState.CATCHUP;
        catchupSnapshotOwed = true;
    }

    private void maybeWarnSlowConsumer() {
        if (!slowConsumerWarned
                && inFlightFrameMaxSeq.size() >= config.queueWarnThresholdFrames()
                && config.queueWarnThresholdFrames() > 0) {
            metrics.onSlowConsumerWarning();
            slowConsumerWarned = true;
        }
    }

    private void closeWith(ErrorCode code, String message) {
        if (state == SessionState.CLOSED) {
            return;
        }
        state = SessionState.CLOSED;
        sink.close(code, message);
        metrics.onSessionClosed(code.name());
    }

    /** Closes the session with an orderly {@link ErrorCode#SERVER_SHUTDOWN}. */
    public void close() {
        closeWith(ErrorCode.SERVER_SHUTDOWN, "server shutdown");
    }

    private void emit(EdgeFrame frame) {
        // The session's own bounded accounting governs NOTIFY frames; control frames
        // (SUBSCRIBE_OK, snapshot, heartbeat, demotion notice) are always offered. A
        // false return on a control frame means the transport is gone — close.
        if (!sink.offer(frame) && !(frame instanceof EdgeFrame.Notify)) {
            // Avoid recursion through closeWith's sink.close; mark closed directly.
            if (state != SessionState.CLOSED) {
                state = SessionState.CLOSED;
                metrics.onSessionClosed("transport_gone");
            }
        }
    }

    private static int encodedNotificationBytes(CommitNotification n) {
        // Conservative encoded-size estimate matching EdgeFrameCodec.encodeNotification:
        // fixed header (seq, ts, fromV, toV, batchLen, sigLen, epoch, nonceLen) +
        // mutation batch + signature + nonce. We re-encode the batch to be exact on the
        // dominant term; the small fixed fields are summed precisely.
        io.configd.store.ConfigDelta d = n.delta();
        int batchLen = io.configd.store.CommandCodec.encodeBatch(d.mutations()).length;
        byte[] sig = d.signature();
        int sigLen = (sig == null) ? 0 : sig.length;
        int nonceLen = d.nonce().length;
        return 8 + 8        // seq, commitTs
                + 8 + 8     // fromVersion, toVersion
                + 4 + batchLen
                + 4 + sigLen
                + 8         // epoch
                + 4 + nonceLen;
    }

    // -----------------------------------------------------------------------
    // Read-only state accessors
    // -----------------------------------------------------------------------

    /** The current session state. */
    public SessionState state() {
        return state;
    }

    /** The current cursor (highest seq streamed to the edge). */
    public long cursor() {
        return cursor;
    }

    /** The highest seq the edge has acknowledged. */
    public long lastAckedSeq() {
        return lastAckedSeq;
    }

    /** The number of in-flight (offered-not-acked) NOTIFY frames. */
    public int inFlightFrames() {
        return inFlightFrameMaxSeq.size();
    }

    /** The most recent {@link DemotionEvent}, or null if the session has never demoted. */
    public DemotionEvent lastDemotion() {
        return lastDemotion;
    }

    /** The lifetime demotion count. */
    public int demotionCount() {
        return demotionCount;
    }
}

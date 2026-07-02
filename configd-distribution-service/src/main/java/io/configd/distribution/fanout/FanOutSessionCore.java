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
 * The transport-agnostic, per-subscriber fan-out session engine. One instance per
 * subscribed edge: the SAME code the live server drives from a virtual thread and the
 * simulator drives tick-by-tick as its {@code StreamDriver}.
 *
 * <h2>Determinism and threading</h2>
 * Deterministic and single-threaded-per-instance: no threads, no wall-clock reads, no
 * sleeps inside. Time enters only through {@link #tick(long)}'s {@code nowMillis} (the
 * caller's {@link Clock} / sim clock). It PULLS via {@link CommitNotificationSource#readSince}
 * / {@link ReplaySource}; it never touches the publish path and holds no lock.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #onSubscribe(EdgeFrame.Subscribe)} - emits {@code SUBSCRIBE_OK} with the
 *       {@link EdgeFrame.Mode} decision (TAIL vs SNAPSHOT_FIRST).</li>
 *   <li>{@link #tick(long)} - drains {@code readSince(cursor)} into bounded {@code NOTIFY}
 *       batches (verbatim, never merged); demotes to catch-up on queue overflow / ack-lag /
 *       GAP; runs the snapshot flow; emits a {@code HEARTBEAT} when idle past
 *       {@code heartbeatMs}.</li>
 *   <li>{@link #onCursorAck(long)} - advances the ack watermark, releasing in-flight
 *       frame accounting.</li>
 * </ol>
 *
 * <h2>States ({@link SessionState})</h2>
 * {@code STREAMING -> (queue overflow | readSince GAP | ack-lag | transport block) ->
 * CATCHUP (snapshot+chunks) -> STREAMING}. Never an unbounded queue; never a silent drop -
 * every demotion records a {@link DemotionEvent} (cursor evidence) and a metric.
 */
public final class FanOutSessionCore {

    /**
     * Live-lock backstop for the transient-GAP retry path (see {@link #drainStreaming}).
     * A transient GAP (a lock-free-read race whose data is still retained) is retried on the
     * next tick rather than counted as a slow-consumer demotion. This bounds the pathological
     * case where EVERY read for a long run races - if the consumer gets that many CONSECUTIVE
     * transient GAPs with no clean read in between, it cannot make progress against the write
     * rate, so we demote (a genuine "cannot keep up" signal). A single clean read resets the
     * streak, so steady-state operation - where clean reads interleave the occasional race -
     * never approaches this. At the production 10 ms tick this is ~1.3 s of back-to-back
     * racing reads: unreachable in normal operation (a lock-free read is microseconds; even a
     * 50 w/s writer is ~20 ms apart), yet a firm bound on a degenerate write storm. Kept
     * deliberately high so it never masks the fix (a healthy caught-up edge must never trip it).
     */
    static final int MAX_CONSECUTIVE_TRANSIENT_GAPS = 128;

    /** The session states. */
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

    /** Optional structured demotion-event listener (cursor evidence). */
    private final Consumer<DemotionEvent> demotionListener;

    private SessionState state = SessionState.STREAMING;
    private boolean subscribed;

    /** Last applied-mutation seq S the session has STREAMED to the edge (the cursor). */
    private long cursor;

    /** Highest seq the edge has acknowledged via CURSOR_ACK. */
    private long lastAckedSeq;

    /** In-flight NOTIFY frames, by their highest contained seq, awaiting CURSOR_ACK. FIFO. */
    private final Deque<Long> inFlightFrameMaxSeq = new ArrayDeque<>();

    /** Sim/wall time (ms) of the last frame this session emitted - heartbeat cadence input. */
    private long lastTrafficMillis = Long.MIN_VALUE;

    /** Whether a slow-consumer warning has already fired since the last drop below threshold. */
    private boolean slowConsumerWarned;

    /** Set when a demotion is pending: the next tick performs the snapshot flow. */
    private boolean catchupSnapshotOwed;

    /** The most recent demotion event (diagnostic; null until the first demotion). */
    private DemotionEvent lastDemotion;

    private int demotionCount;

    /**
     * Consecutive transient-GAP ticks (a lock-free-read race whose data is still retained)
     * with no clean read in between. Reset by any clean {@code readSince} (see
     * {@link #drainStreaming}) and by {@link #demote}. Bounded by
     * {@link #MAX_CONSECUTIVE_TRANSIENT_GAPS}, the live-lock backstop.
     */
    private int consecutiveTransientGaps;

    /**
     * The server-side prefix filter for this session (ADR-0045), or null when filtering is
     * inactive (match-all / full-chain passthrough - the byte-identical legacy path). Set at
     * {@link #onSubscribe}.
     */
    private ServerPrefixFilter prefixFilter;

    /** Whether this session filters whole signed deltas server-side (ADR-0045). */
    private boolean filterActive;

    /**
     * The highest covered seq S the edge has been told about - via a delivered NOTIFY's seq or
     * a cursor-advance HEARTBEAT - on a filtered session. Coalesces the cursor-advance emission
     * to at most once per drain pass and only when the covered position actually moved past what
     * the delivered frames already conveyed.
     */
    private long lastAdvertisedCoveredS;

    /**
     * The in-progress (possibly transport-paused) snapshot transfer; null when none.
     * See {@link #performSnapshotTransfer} for the backpressure pacing rationale.
     */
    private PendingSnapshotTransfer pendingTransfer;

    /**
     * A DEMOTED_TO_CATCHUP notice the full transport queue refused at {@link #demote}
     * time; null when none owed. Re-offered each {@link #tick(long)} ahead of the owed
     * snapshot transfer (would-block pacing - the notice is advisory and must never
     * close-mark the session).
     */
    private EdgeFrame.ErrorClose pendingDemotionNotice;

    /**
     * A snapshot transfer paced against transport backpressure: the immutable frame plan
     * (seq, chunks, declared size) plus the emission high-water mark, so a transfer that
     * would overrun the bounded transport queue pauses at the refused frame and resumes
     * there on the next {@link #tick(long)} - the SAME envelope, never a restart.
     */
    private static final class PendingSnapshotTransfer {
        final long seq;
        final List<EdgeFrame.SnapshotChunk> chunks;
        final long totalBytes;
        boolean beginEmitted;
        int nextChunk;

        PendingSnapshotTransfer(long seq, List<EdgeFrame.SnapshotChunk> chunks, long totalBytes) {
            this.seq = seq;
            this.chunks = chunks;
            this.totalBytes = totalBytes;
        }
    }

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
     * (the larger of resume / failover-resume). The TAIL vs SNAPSHOT_FIRST decision:
     * <ul>
     *   <li><b>SNAPSHOT_FIRST</b> if {@code readSince(cursor)} would GAP (the cursor is
     *       behind the cache tail, beyond the replay horizon), OR the subscriber is
     *       fresh/cache-less ({@code cursor == 0}) and any data exists (tail-replaying
     *       history to a cache-less subscriber is unsafe - see {@code decideMode}).</li>
     *   <li><b>TAIL</b> otherwise (the cursor is recoverable from the retained tail).</li>
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
            // One subscribe per connection (protocol rule). A second is a protocol violation.
            closeWith(ErrorCode.PROTOCOL_VIOLATION, "duplicate SUBSCRIBE");
            return;
        }
        subscribed = true;
        this.cursor = subscribe.effectiveResumeCursor();
        this.lastAckedSeq = cursor;
        this.lastAdvertisedCoveredS = cursor;

        // Server-side prefix filtering is active only when the deployment posture is on, the edge
        // opted in (acceptsFiltered), and the subscription is a non-empty prefix set. When inactive
        // the session is the byte-identical full-chain legacy path (ADR-0045).
        this.filterActive = ServerPrefixFilter.isActive(config, subscribe);
        this.prefixFilter = filterActive
                ? new ServerPrefixFilter(subscribe.prefixes(), config.strongReadPrefixes())
                : null;
        metrics.onFilterActive(filterActive);

        long latest = source.latestSeq();
        EdgeFrame.Mode mode = decideMode(cursor, latest);

        // horizonDistance = cursor - (oldest - 1): >= 0 means the cursor is at or above the
        // replay-horizon edge (tail-recoverable); < 0 means it is beyond it. Empty ring
        // (oldest < 0) means nothing evicted yet - report cursor + 1 (trivially recoverable).
        long oldest = source.oldestSeq();
        long horizonDistance = (oldest < 0) ? cursor + 1 : cursor - (oldest - 1);
        metrics.onSubscribeMode(mode == EdgeFrame.Mode.SNAPSHOT_FIRST, horizonDistance);

        // The filtered confirm bit tells the edge to select the filtered-stream apply mode (a
        // dense covered-S cursor + a forward-only version chain). It is set only under a 0x03
        // connection; a 0x01/0x02 SubscribeOk drops it and the edge stays in classic mode.
        emit(new EdgeFrame.SubscribeOk(latest, mode, filterActive));
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
            // A cursor-0 subscriber ALWAYS gets a snapshot when any data exists. Two
            // recovery scenarios proved tail-replaying history to a cache-less subscriber
            // unsafe:
            //  1. Epoch floor (SEC-017): a RESTARTED edge persists its highest-seen signing
            //     epoch (epoch.lock) and - correctly, by design - REJECTS every redelivered
            //     old-epoch delta as a replay. A TAIL decision therefore wedges it at version
            //     0 behind the production ack-lag threshold (8192 seqs). The server cannot
            //     observe the subscriber's epoch floor; the snapshot (unsigned cumulative
            //     state, monotonic-version-guarded) is always epoch-safe.
            //  2. Ring genesis: after a server restart the ring retains only seqs > the
            //     restored version V, so readSince(0) returns a run that does NOT start at
            //     genesis - a TAIL would gap at the edge immediately.
            return EdgeFrame.Mode.SNAPSHOT_FIRST;
        }
        return EdgeFrame.Mode.TAIL;
    }

    // -----------------------------------------------------------------------
    // Tick (the drain / catch-up / heartbeat loop)
    // -----------------------------------------------------------------------

    /**
     * Advances the session one step at logical time {@code nowMillis}: performs an owed
     * snapshot transfer (catch-up), drains new notifications into bounded NOTIFY batches,
     * and emits a heartbeat if idle. Deterministic - all behavior is a function of the
     * source contents, the acks received, and {@code nowMillis}.
     *
     * @param nowMillis the caller's logical/wall time in ms
     */
    public void tick(long nowMillis) {
        if (state == SessionState.CLOSED || !subscribed) {
            return;
        }
        boolean emittedThisTick = false;

        // A demotion notice refused at demote() time (full queue) is owed; it must precede
        // the snapshot envelope on the wire. If the queue is STILL full, the snapshot
        // cannot start either - would-block, retry next tick.
        if (state == SessionState.CATCHUP && pendingDemotionNotice != null) {
            if (!sink.offer(pendingDemotionNotice)) {
                return;
            }
            pendingDemotionNotice = null;
            emittedThisTick = true;
        }

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
        // new data keeps it under the frame cap).
        if (cursor - lastAckedSeq > config.ackLagDemoteSeqs()) {
            demote(DemotionEvent.REASON_ACK_LAG);
            return true; // demoted (the notice is emitted, or parked per RR-104 would-block)
        }

        Result r = source.readSince(cursor);
        if (r.isGap()) {
            return handleGap((Result.Gap) r);
        }
        List<CommitNotification> pending = ((Result.Ok) r).notifications();
        // A clean read (even an empty one - caught up, no race) breaks any transient-GAP streak.
        consecutiveTransientGaps = 0;
        if (pending.isEmpty()) {
            return false;
        }

        boolean emitted = false;
        int idx = 0;
        // The furthest seq consumed from the ring this pass (matching OR filtered). Under
        // filtering the cursor advances over the ENTIRE scanned range - filtering changes only
        // what is emitted, never how far readSince is consumed - so a narrow edge never
        // spuriously falls behind. When filtering is off this equals the last delivered seq.
        long scannedThroughSeq = cursor;
        boolean skippedAny = false;
        while (idx < pending.size()) {
            // Bounded outbound queue: never exceed queueFrames unacked NOTIFY frames.
            if (inFlightFrameMaxSeq.size() >= config.queueFrames()) {
                demote(DemotionEvent.REASON_QUEUE_OVERFLOW);
                return true;
            }
            // Assemble one batch of MATCHING notifications, skipping (dropping whole) any the
            // filter rejects, respecting batchMaxNotifications / batchMaxBytes.
            List<CommitNotification> batch = new ArrayList<>();
            int batchBytes = 4; // NOTIFY count field
            long batchMaxSeq = cursor;
            int filteredInBatch = 0;
            while (idx < pending.size() && batch.size() < config.batchMaxNotifications()) {
                CommitNotification n = pending.get(idx);
                if (filterActive && !prefixFilter.keep(n)) {
                    // Drop the whole signed delta (leg (a): never rewrite/coalesce). The cursor
                    // still advances over it; the edge learns the covered position from the
                    // cursor-advance HEARTBEAT below.
                    scannedThroughSeq = n.seq();
                    skippedAny = true;
                    filteredInBatch++;
                    idx++;
                    continue;
                }
                int encodedBytes = encodedNotificationBytes(n);
                if (!batch.isEmpty() && batchBytes + encodedBytes > config.batchMaxBytes()) {
                    break; // byte cap - close this batch, start the next frame
                }
                batch.add(n);
                batchBytes += encodedBytes;
                batchMaxSeq = n.seq();
                scannedThroughSeq = n.seq();
                idx++;
            }
            if (filteredInBatch > 0) {
                metrics.onFilteredDeltas(filteredInBatch);
            }
            if (batch.isEmpty()) {
                // Every notification scanned this inner pass was filtered out - emit no empty
                // NOTIFY. idx advanced on each skip, so the outer loop exits when it reaches
                // the end of the run.
                continue;
            }
            EdgeFrame.Notify frame = new EdgeFrame.Notify(batch);
            if (!sink.offer(frame)) {
                // Transport would block - bounded by construction; demote (never buffer
                // unboundedly, never drop silently).
                demote(DemotionEvent.REASON_TRANSPORT_BLOCK);
                return true;
            }
            inFlightFrameMaxSeq.addLast(batchMaxSeq);
            cursor = batchMaxSeq;
            // A delivered NOTIFY conveys the covered position through its highest seq.
            lastAdvertisedCoveredS = Math.max(lastAdvertisedCoveredS, batchMaxSeq);
            metrics.onNotifyBatch(batch.size(), batchBytes);
            if (filterActive) {
                metrics.onDeliveredDeltas(batch.size());
            }
            metrics.onQueueDepth(inFlightFrameMaxSeq.size());
            maybeWarnSlowConsumer();
            lastTrafficMillis = nowMillis;
            emitted = true;
        }
        // Advance the cursor over any trailing filtered range (deltas dropped after the last
        // delivered NOTIFY), then tell the edge the new covered position with one coalesced
        // cursor-advance HEARTBEAT.
        if (filterActive) {
            if (scannedThroughSeq > cursor) {
                cursor = scannedThroughSeq;
            }
            if (skippedAny) {
                emitted |= maybeAdvanceCoveredCursor(nowMillis);
            }
        }
        return emitted;
    }

    /**
     * On a filtered session, tells the edge the new covered-through position with a single
     * coalesced cursor-advance HEARTBEAT whose {@code latestSeq} carries the DRAINED-THROUGH
     * cursor (clamped - never the raw buffer tip; the W5-7 lesson). Emitted only when the
     * covered position actually moved past what the delivered NOTIFYs already conveyed. This is
     * what keeps the edge's ack watermark climbing so a healthy narrow-prefix edge is never
     * demoted for ack-lag caused by filtering. Best-effort and idempotent: the cursor is
     * absolute and supersedes, so a refused offer is simply dropped (the next pass carries a
     * fresher cursor) - it NEVER closes or demotes the session (transport pressure is the
     * NOTIFY path's concern). Returns true iff a frame was emitted.
     */
    private boolean maybeAdvanceCoveredCursor(long nowMillis) {
        if (cursor <= lastAdvertisedCoveredS) {
            return false; // the edge already knows this covered position (a delivered NOTIFY reached it)
        }
        if (sink.offer(new EdgeFrame.Heartbeat(cursor, nowMillis))) {
            lastAdvertisedCoveredS = cursor;
            lastTrafficMillis = nowMillis;
            metrics.onCursorAdvance();
            return true;
        }
        return false; // would-block: dropped; the next drain pass re-offers a fresher cursor
    }

    /**
     * Classifies a {@link Result.Gap} from {@code readSince(cursor)} as a GENUINE fall-behind
     * (demote) or a TRANSIENT lock-free-read race (retry next tick). Returns true if a demotion
     * was emitted, false if the GAP was transient and no frame was emitted this tick.
     *
     * <p>{@link io.configd.distribution.FanOutBuffer#readSince} returns a GAP for two very
     * different reasons, and only one of them is a slow-consumer signal:
     *
     * <ol>
     *   <li><b>GENUINE fall-behind.</b> The consumer's needed data was EVICTED from the ring.
     *       The buffer's authoritative test is its fast-path
     *       ({@code FanOutBuffer.readSince}: {@code if (evicted >= 0 && cursor < evicted)}):
     *       a notification with {@code seq > cursor} is already gone. Eviction is drop-oldest
     *       over strictly-ascending seqs, and the appender captures the evicted seq from the
     *       tail slot BEFORE advancing tail, so {@code oldestRetainedSeq}
     *       ({@code FanOutBuffer.oldestSeqInternal}, the seq now at the tail) always sits
     *       strictly above {@code lastEvictedSeq}, i.e. {@code oldestRetainedSeq >=
     *       lastEvictedSeq + 1}. Hence {@code cursor < lastEvictedSeq} implies
     *       {@code oldestRetainedSeq > cursor + 1}: the cursor's SUCCESSOR is no longer
     *       retained. This consumer must re-snapshot - a real slow-consumer signal the
     *       governor must count toward quarantine.</li>
     *   <li><b>TRANSIENT lock-free-read race.</b> The Lamport verify-after-read fallbacks
     *       ({@code h - t1 > capacity}, torn read {@code t2 != t1}, or a not-yet-published
     *       null slot) fire when a read coincides with a concurrent write+eviction at the
     *       FULL-buffer boundary. They are only reachable AFTER the fast-path has already
     *       proved {@code cursor >= lastEvictedSeq}, so the requested data is STILL IN THE
     *       RING ({@code oldestRetainedSeq <= cursor + 1}) and a retry on the next tick
     *       succeeds. This is NOT slow-consumer distress: once the ring is full and writes
     *       continue, a fully caught-up edge hits this on nearly every boundary read, so
     *       counting it as a GAP demotion spuriously walks a healthy edge to QUARANTINED.
     *       Do NOT demote; return without a frame and re-read next tick.</li>
     * </ol>
     *
     * <p>The boundary {@code oldestRetainedSeq > cursor + 1} errs on the SAFE side: it NEVER
     * permanently masks a genuine fall-behind (genuine always implies it, as shown above), and
     * in the common contiguous-seq case it matches the buffer's own {@code cursor <
     * lastEvictedSeq} test exactly. The "genuine implies {@code oldestRetainedSeq > cursor + 1}"
     * argument holds for a consistent view; because {@code oldestRetainedSeq} is read lock-free
     * and independently of the {@code lastEvictedSeq} the buffer fast-path used, a consumer
     * lapped at the exact eviction instant can read as transient for ONE tick before the next
     * eviction advances the tail and re-classifies it genuine - a self-correcting single-tick
     * delay bounded by the live-lock backstop, never a permanently masked demotion.
     *
     * <p>The opposite (over-classification: a still-served transient race read as genuine) is
     * possible but always SAFE - a spurious, self-correcting re-snapshot, never a missed
     * demotion - and negligibly rare at real write rates. Two sources: (1) sparse seqs
     * (no-op/RCFG entries skip sequence numbers) can leave a still-served cursor in a skipped-seq
     * gap above {@code oldestRetainedSeq}; (2) {@code oldestSeqInternal} reads {@code tail} then
     * the tail slot non-atomically, and on a FULL ring the evicting publish overwrites that same
     * slot, so the read can observe a much newer seq and report an {@code oldestRetainedSeq}
     * above the true oldest. Both need a concurrent write at the eviction boundary; at the
     * production tick cadence versus the write rate they essentially never fire, so a caught-up
     * edge is not demoted (the defect this fixes). {@code oldestRetainedSeq == -1} (empty ring)
     * is transient: the buffer never emits a genuine GAP while empty (an eviction leaves the
     * ring at capacity).
     */
    private boolean handleGap(Result.Gap gap) {
        long oldestRetainedSeq = gap.oldestRetainedSeq();
        if (oldestRetainedSeq > cursor + 1) {
            // GENUINE: the cursor's successor is no longer retained - must re-snapshot.
            demote(DemotionEvent.REASON_GAP);
            return true;
        }
        // TRANSIENT: data still retained. Retry next tick unless the race is unrelenting -
        // a long run of back-to-back racing reads with no progress IS a "cannot keep up"
        // signal (the live-lock backstop), at which point demoting is correct.
        if (++consecutiveTransientGaps >= MAX_CONSECUTIVE_TRANSIENT_GAPS) {
            demote(DemotionEvent.REASON_GAP);
            return true;
        }
        return false;
    }

    /**
     * The DEMOTED-to-snapshot-to-resume-tail flow, paced against transport backpressure.
     * Returns true if a frame was emitted.
     *
     * <h4>Backpressure pacing</h4>
     * The transport queue is BOUNDED and non-blocking (default 64 frames), so a transfer
     * whose chunk count exceeds the queue's free space cannot be emitted in one burst: the
     * original code routed every snapshot frame through {@link #emit}, whose refusal
     * semantics ("a refused control frame means the transport is gone") closed the session
     * at the first full-queue chunk, then the unconditional cutover tail resurrected it to
     * STREAMING - delivering a TORN envelope the edge cannot reassemble. Net effect: a
     * zero-state edge whose store exceeds {@code transportQueueFrames x snapshotChunkBytes}
     * could never bootstrap.
     *
     * <p>A refused snapshot-frame offer here is therefore treated as WOULD-BLOCK, not
     * transport death: the transfer pauses at the refused frame and resumes on the next tick
     * once the writer has drained queue space (the session stays CATCHUP with the transfer
     * owed). A genuinely dead transport is the shell's lifecycle concern - it tears the
     * connection down and the session with it (the sim/process shells both do). The cutover
     * bookkeeping (cursor = S, resume STREAMING) runs ONLY when SNAPSHOT_END was actually
     * accepted, so a paused transfer never declares a cutover it did not deliver. Pinned by
     * {@code BootstrapSnapshotBackpressureTest}.
     */
    private boolean performSnapshotTransfer() {
        if (pendingTransfer == null) {
            ReplaySource.Replay replay;
            try {
                replay = replaySource.replayFromSnapshot();
            } catch (RuntimeException e) {
                // Replay source unavailable for the needed range - fatal for this session.
                catchupSnapshotOwed = false;
                closeWith(ErrorCode.GAP_UNRECOVERABLE, "replay unavailable: " + e.getMessage());
                return true;
            }
            byte[] body = EdgeSnapshotCodec.serialize(replay.snapshot());
            pendingTransfer = new PendingSnapshotTransfer(replay.seq(),
                    EdgeSnapshotCodec.chunk(body, snapshotChunkBytes()), body.length);
        }

        PendingSnapshotTransfer t = pendingTransfer;
        boolean emitted = false;
        if (!t.beginEmitted) {
            if (!sink.offer(new EdgeFrame.SnapshotBegin(t.seq, t.chunks.size(), t.totalBytes))) {
                return emitted; // would-block: resume here next tick
            }
            t.beginEmitted = true;
            emitted = true;
        }
        while (t.nextChunk < t.chunks.size()) {
            if (!sink.offer(t.chunks.get(t.nextChunk))) {
                return emitted; // would-block: resume at this chunk next tick
            }
            t.nextChunk++;
            emitted = true;
        }
        if (!sink.offer(new EdgeFrame.SnapshotEnd(t.seq))) {
            return emitted; // would-block: only END is still owed
        }

        // Transfer fully accepted by the transport - NOW the cutover bookkeeping runs:
        // cursor jumps to the snapshot seq so tailing resumes from there; clear stale
        // in-flight accounting and resume TAIL.
        //
        // CRITICAL: the snapshot transfer is unacknowledged on the wire - do NOT advance
        // lastAckedSeq here. Optimistically marking the edge as caught-up would silently
        // strand it if the snapshot frame is lost in transit (the session would then idle,
        // believing the edge converged, while the edge sits at a stale version forever).
        // Leaving lastAckedSeq behind means a lost snapshot rebuilds ack-lag and the session
        // re-demotes + re-snapshots until the edge's CURSOR_ACK confirms application - the
        // robust, self-healing behavior. (Witnessed on the lossy edge-network sim: without
        // this, ~75% of would-converge seeds stranded an edge at an intermediate version.)
        pendingTransfer = null;
        catchupSnapshotOwed = false;
        metrics.onSnapshotTransfer();
        cursor = t.seq;
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
            // On a filtered session the HEARTBEAT.latestSeq is the DRAINED-THROUGH covered-S
            // (clamped), NOT the raw buffer tip: advertising the tip would tell the edge a
            // covered position it has not been delivered/scanned-through for (a silent gap). On
            // an unfiltered session it is the buffer tip, the staleness clock, as before.
            long latest = filterActive ? cursor : source.latestSeq();
            emit(new EdgeFrame.Heartbeat(latest, nowMillis));
            if (filterActive) {
                lastAdvertisedCoveredS = Math.max(lastAdvertisedCoveredS, cursor);
            }
            metrics.onHeartbeat();
            lastTrafficMillis = nowMillis;
        }
    }

    // -----------------------------------------------------------------------
    // Cursor ack
    // -----------------------------------------------------------------------

    /**
     * Records a {@code CURSOR_ACK}: advances {@link #lastAckedSeq} and releases every
     * in-flight NOTIFY frame whose highest seq is {@code <= seq} (bounded-queue accounting).
     * A stale or duplicate ack ({@code seq <= lastAckedSeq}) is ignored.
     *
     * @param seq the highest applied seq the edge acknowledges
     */
    public void onCursorAck(long seq) {
        if (state == SessionState.CLOSED) {
            return;
        }
        if (seq <= lastAckedSeq) {
            return; // stale / duplicate - never moves the watermark backward
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
        // Non-fatal notice to the edge (the demotion code). The notice is ADVISORY - the
        // snapshot that follows is the load-bearing signal - and the outbound queue is full
        // BY DEFINITION when the reason is TRANSPORT_BLOCK, so a refused offer here is
        // WOULD-BLOCK, not transport death. Never route it through emit(), whose refusal
        // semantics close-mark the session. A refused notice is retained and re-offered
        // each tick AHEAD of the snapshot transfer (tick()), preserving wire order:
        // notice, BEGIN..chunks..END.
        EdgeFrame.ErrorClose notice = new EdgeFrame.ErrorClose(ErrorCode.DEMOTED_TO_CATCHUP, reason);
        pendingDemotionNotice = sink.offer(notice) ? null : notice;
        // Drop pending outbound accounting - the snapshot supersedes everything in flight.
        inFlightFrameMaxSeq.clear();
        slowConsumerWarned = false;
        consecutiveTransientGaps = 0; // re-bootstrap is a clean slate for the transient-GAP streak
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
        // false return on a control frame means the transport is gone - close.
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

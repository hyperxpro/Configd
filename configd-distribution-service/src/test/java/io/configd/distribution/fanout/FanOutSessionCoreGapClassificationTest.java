package io.configd.distribution.fanout;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.CommitNotificationSource.Result;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link FanOutSessionCore} distinguishes a GENUINE fall-behind GAP (the
 * consumer's needed data was evicted - demote) from a TRANSIENT lock-free-read-race GAP (the
 * data is still retained - retry next tick), using the {@code oldestRetainedSeq} the
 * {@link Result.Gap} carries.
 *
 * <p>The behavior these tests pin reproduced under sustained writes (a single edge at 50 w/s,
 * an otherwise near-idle box - not CPU contention): once the ring is full and writes continue,
 * a FULLY caught-up edge hits the buffer's Lamport verify-after-read fallbacks on nearly every
 * boundary read. Treating each such GAP as a demotion let {@code gapDemoteLimit} (10 within
 * 60 s) walk a HEALTHY edge straight to QUARANTINED (~28 min cooldown) and freeze its frontier.
 *
 * <p>Most tests drive the session against a {@link ScriptedSource} that returns a chosen
 * transient-vs-genuine GAP deterministically (no threads, no timing), and wire the session's
 * demotion listener to a REAL {@link SlowConsumerGovernor} exactly as
 * {@code FanOutConnectionDriver.onDemotionEvent} does - so the proof is end-to-end into the
 * quarantine ladder. Two further tests drive the REAL {@link FanOutBuffer} (no mock) so the
 * load-bearing eviction invariant ({@code oldestRetainedSeq >= lastEvictedSeq + 1}) the
 * classifier rests on is locked against a future eviction refactor.
 */
class FanOutSessionCoreGapClassificationTest {

    private static final String IDENTITY = "edge-1";

    private final FakeClock clock = new FakeClock(1_000L);
    private final RecordingTransportSink sink = new RecordingTransportSink();
    private final SlowConsumerGovernor governor =
            new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), FanOutSessionMetrics.NOOP);

    /** A programmable {@link CommitNotificationSource}: {@link #behavior} computes readSince's Result. */
    private static final class ScriptedSource implements CommitNotificationSource {
        /** Swap to change readSince's behavior mid-test; the argument is the caller's cursor. */
        volatile LongFunction<Result> behavior = c -> Result.ok(List.of());
        volatile long latest = -1L;
        volatile long oldest = -1L;
        int readSinceCalls;

        @Override public Result readSince(long cursor) {
            readSinceCalls++;
            return behavior.apply(cursor);
        }
        @Override public long latestSeq() { return latest; }
        @Override public long oldestSeq() { return oldest; }
        @Override public long droppedTotal() { return 0L; }
    }

    /** A replay that returns an empty snapshot at a fixed seq (a clean resume point). */
    private static ReplaySource replayAt(long seq) {
        ConfigSnapshot snap = new ConfigSnapshot(HamtMap.empty(), seq, 0L);
        return () -> new ReplaySource.Replay(snap, seq);
    }

    private FanOutSessionCore session(ScriptedSource src, ReplaySource replay) {
        // Wire the demotion listener to the governor exactly as FanOutConnectionDriver does.
        Consumer<DemotionEvent> toGovernor =
                ev -> governor.onDemotion(IDENTITY, ev, clock.currentTimeMillis());
        return new FanOutSessionCore(src, replay, sink, FanOutConfig.defaults(),
                FanOutSessionMetrics.NOOP, clock, toGovernor);
    }

    private static EdgeFrame.Subscribe subscribe(long resume) {
        return new EdgeFrame.Subscribe(true, List.of(), resume, -1L, IDENTITY);
    }

    /** A commit notification with a contiguous, strictly-ascending seq (worst case for the boundary). */
    private static CommitNotification note(long seq) {
        return new CommitNotification(seq, 1_000L + seq,
                new ConfigDelta(seq - 1, seq,
                        List.of(new ConfigMutation.Put("k" + seq, ("v" + seq).getBytes(StandardCharsets.UTF_8)))));
    }

    /** Subscribes caught-up (cursor == latest, non-zero) so decideMode picks TAIL -> STREAMING. */
    private FanOutSessionCore streamingSessionAt(ScriptedSource src, ReplaySource replay, long cursor) {
        src.latest = cursor;
        src.oldest = 1L;
        src.behavior = c -> Result.ok(List.of()); // clean read at subscribe => TAIL (not a GAP)
        FanOutSessionCore s = session(src, replay);
        s.onSubscribe(subscribe(cursor));
        assertEquals(EdgeFrame.Mode.TAIL,
                sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode(),
                "caught-up non-zero cursor must TAIL, not snapshot-first");
        assertEquals(FanOutSessionCore.SessionState.STREAMING, s.state());
        sink.clear();
        return s;
    }

    // ------------------------------------------------------------------------
    // 1. The deterministic reproduction: a caught-up edge at the eviction boundary
    //    getting TRANSIENT (data-still-retained) GAPs must NOT demote or quarantine.
    // ------------------------------------------------------------------------

    @Test
    void caughtUpEdgeAtEvictionBoundaryTransientGapDoesNotDemoteOrQuarantine() {
        ScriptedSource src = new ScriptedSource();
        FanOutSessionCore s = streamingSessionAt(src, replayAt(100L), 100L);

        // TRANSIENT: oldestRetainedSeq sits far BELOW the cursor (the caught-up boundary case) -
        // the cursor's successor is still retained (oldestRetainedSeq <= cursor + 1). This is the
        // exact shape the buffer's Lamport fallbacks produce for a full ring under continuous
        // writes. The cursor never advances (no data > cursor), so every tick re-races.
        src.behavior = c -> Result.gap(Math.max(0L, c - 5_000L));

        // 15 consecutive transient GAPs > gapDemoteLimit(10): pre-fix this quarantined the edge
        // (10 REASON_GAP demotions in the 60 s window). It stays well under the live-lock
        // backstop (128), so the fix must treat every one as a retry, not a demotion.
        for (int i = 0; i < 15; i++) {
            s.tick(clock.now());
        }

        assertEquals(FanOutSessionCore.SessionState.STREAMING, s.state(),
                "a transient race must never leave STREAMING");
        assertEquals(0, s.demotionCount(), "zero demotions on transient (still-retained) GAPs");
        assertEquals(100L, s.cursor(), "cursor is unchanged - nothing was streamed, nothing lost");
        assertTrue(sink.sentOfType(EdgeFrame.ErrorClose.class).isEmpty(),
                "no DEMOTED_TO_CATCHUP notice is emitted for a transient GAP");
        assertEquals(SlowConsumerGovernor.ConsumerState.HEALTHY, governor.state(IDENTITY),
                "the governor never sees a GAP demotion, so the edge stays HEALTHY "
                        + "(pre-fix it would be QUARANTINED)");
    }

    // ------------------------------------------------------------------------
    // 2. The protection is intact: a GENUINE fall-behind (needed data evicted) MUST still
    //    demote(REASON_GAP), and enough genuine GAPs MUST still quarantine.
    // ------------------------------------------------------------------------

    @Test
    void genuineFallBehindStillDemotesAndQuarantines() {
        ScriptedSource src = new ScriptedSource();
        FanOutSessionCore s = streamingSessionAt(src, replayAt(100L), 100L);

        // GENUINE: oldestRetainedSeq is ABOVE cursor + 1 - the cursor's successor is evicted, so
        // the consumer genuinely fell off the retention window and must re-snapshot. (Post-
        // snapshot the cursor jumps back to seq 100, so the next read is genuine again: the
        // consumer keeps failing to keep up, which is precisely what quarantine is for.)
        src.behavior = c -> Result.gap(c + 2L);

        // Each genuine GAP demotes (STREAMING -> CATCHUP), the next tick re-snapshots
        // (CATCHUP -> STREAMING), then it demotes again. Ten REASON_GAP demotions inside the
        // window trip QUARANTINED.
        int ticks = 0;
        while (governor.state(IDENTITY) != SlowConsumerGovernor.ConsumerState.QUARANTINED
                && ticks < 200) {
            s.tick(clock.now());
            ticks++;
        }

        assertEquals(SlowConsumerGovernor.ConsumerState.QUARANTINED, governor.state(IDENTITY),
                "a consumer that genuinely fell off the window must still walk to QUARANTINED");
        assertEquals(DemotionEvent.REASON_GAP, s.lastDemotion().reason(),
                "the demotion reason is GAP (genuine fall-behind)");
        assertTrue(s.demotionCount() >= governor.config().gapDemoteLimit(),
                "at least gapDemoteLimit genuine GAP demotions occurred");
    }

    // ------------------------------------------------------------------------
    // 3. The live-lock backstop: if EVERY read for a long run races (no clean read to reset
    //    the streak), the consumer cannot make progress and demoting IS correct.
    // ------------------------------------------------------------------------

    @Test
    void unrelentingTransientGapsTripLiveLockBackstop() {
        ScriptedSource src = new ScriptedSource();
        FanOutSessionCore s = streamingSessionAt(src, replayAt(100L), 100L);

        // TRANSIENT on EVERY read, with the cursor pinned (never a clean read to reset the
        // streak) - the pathological write-storm live-lock.
        src.behavior = c -> Result.gap(Math.max(0L, c - 5_000L));

        // One short of the threshold: still retrying, no demotion yet.
        for (int i = 0; i < FanOutSessionCore.MAX_CONSECUTIVE_TRANSIENT_GAPS - 1; i++) {
            s.tick(clock.now());
        }
        assertEquals(FanOutSessionCore.SessionState.STREAMING, s.state(),
                "under the backstop threshold the session keeps retrying, never demotes");
        assertEquals(0, s.demotionCount(), "no demotion before the backstop threshold");

        // The threshold-th consecutive transient GAP with no progress trips the backstop.
        s.tick(clock.now());
        assertEquals(FanOutSessionCore.SessionState.CATCHUP, s.state(),
                "the live-lock backstop demotes a consumer that cannot make progress");
        assertEquals(1, s.demotionCount(), "exactly one demotion at the backstop threshold");
        assertEquals(DemotionEvent.REASON_GAP, s.lastDemotion().reason(),
                "the backstop demotion is a genuine 'cannot keep up' GAP signal");
    }

    // ------------------------------------------------------------------------
    // 4. A clean read between transient GAPs resets the streak, so the backstop cannot be
    //    reached by intermittent races - only by an UNBROKEN run. Guards the reset semantics.
    // ------------------------------------------------------------------------

    @Test
    void cleanReadResetsTheTransientGapStreak() {
        ScriptedSource src = new ScriptedSource();
        FanOutSessionCore s = streamingSessionAt(src, replayAt(100L), 100L);

        Result transient_ = Result.gap(Math.max(0L, 100L - 5_000L)); // oldestRetained << cursor
        Result cleanEmpty = Result.ok(List.of());                    // caught up, no race

        // Far more than the backstop's worth of transient GAPs, but a clean read every few
        // ticks resets the consecutive counter, so the backstop must NEVER trip.
        for (int i = 0; i < FanOutSessionCore.MAX_CONSECUTIVE_TRANSIENT_GAPS * 3; i++) {
            src.behavior = (i % 4 == 3) ? c -> cleanEmpty : c -> transient_;
            s.tick(clock.now());
        }

        assertEquals(FanOutSessionCore.SessionState.STREAMING, s.state(),
                "intermittent transient GAPs interleaved with clean reads must never trip the backstop");
        assertEquals(0, s.demotionCount(), "no demotion when clean reads keep resetting the streak");
        assertNotEquals(SlowConsumerGovernor.ConsumerState.QUARANTINED, governor.state(IDENTITY));
    }

    // ------------------------------------------------------------------------
    // 5. REAL FanOutBuffer (no mock), single-threaded: a caught-up session draining a full
    //    ring while single writes continue past capacity must NEVER demote. This exercises the
    //    real drop-oldest eviction boundary and proves the boundary alone (no concurrency race)
    //    never produces a spurious GAP for a reader that keeps up.
    // ------------------------------------------------------------------------

    @Test
    void caughtUpSessionDrainingRealBufferPastCapacityNeverDemotes() {
        int capacity = 8;
        FanOutBuffer buffer = new FanOutBuffer(capacity);
        // Subscribe on the EMPTY buffer -> TAIL -> STREAMING (cursor 0 on an empty ring tails).
        Consumer<DemotionEvent> toGovernor =
                ev -> governor.onDemotion(IDENTITY, ev, clock.currentTimeMillis());
        FanOutSessionCore s = new FanOutSessionCore(buffer, replayAt(0L), sink, FanOutConfig.defaults(),
                FanOutSessionMetrics.NOOP, clock, toGovernor);
        s.onSubscribe(subscribe(0));
        assertEquals(FanOutSessionCore.SessionState.STREAMING, s.state());

        // Publish ONE, tick (stream it), ack it - repeated far past capacity. The reader's cursor
        // tracks the tail every tick, so its needed data is always retained: readSince never GAPs
        // even though every write past the 8th evicts the oldest entry.
        long total = 200;
        for (long seq = 1; seq <= total; seq++) {
            buffer.publish(note(seq));
            s.tick(clock.now());
            s.onCursorAck(seq); // keep the edge fully caught up (no ack-lag, no queue growth)
        }

        assertTrue(buffer.droppedTotal() >= total - capacity,
                "the ring must have evicted past capacity (we are in the eviction-boundary regime)");
        assertEquals(FanOutSessionCore.SessionState.STREAMING, s.state(),
                "a caught-up reader draining the real ring past capacity must never demote");
        assertEquals(0, s.demotionCount(), "no demotion at the real drop-oldest eviction boundary");
        assertEquals(total, s.cursor(), "the reader streamed every committed seq");
        assertEquals(SlowConsumerGovernor.ConsumerState.HEALTHY, governor.state(IDENTITY));
    }

    // ------------------------------------------------------------------------
    // 6. REAL FanOutBuffer (no mock), CONCURRENT hard-lapping writer: locks the SECURITY-CRITICAL
    //    soundness direction - a genuinely lapped reader (cursor 0, whose data is truly evicted)
    //    is NEVER masked, i.e. never receives a GAP the classifier would call transient and
    //    refuse to demote. This is the load-bearing eviction invariant the whole fix rests on:
    //    oldestRetainedSeq >= lastEvictedSeq + 1, so cursor < lastEvictedSeq => oldestRetainedSeq
    //    > cursor + 1 (genuine). It locks that invariant against a future eviction refactor.
    //
    //    The concurrent run exercises the classifier against real-buffer GAPs (proving the
    //    eviction regime is real and the classifier returns genuine under actual races). The
    //    PERMANENT guarantee is then asserted deterministically on the QUIESCENT buffer after the
    //    run: this is the honest form of the invariant, because oldestRetainedSeq is read
    //    lock-free (oldestSeqInternal reads tail, then the slot) and can momentarily lag a
    //    concurrent eviction - so a rare single transient-classified tick is allowed, provided it
    //    self-corrects (see handleGap). The healthy caught-up direction ("never demotes at the
    //    eviction boundary") is covered deterministically by
    //    caughtUpSessionDrainingRealBufferPastCapacityNeverDemotes above, which models the real
    //    10 ms-tick regime rather than a flat-out writer that manufactures nanosecond read-races.
    // ------------------------------------------------------------------------

    @Test
    void realBufferConcurrentLappingNeverMasksAGenuinelyLappedReader() throws InterruptedException {
        int capacity = 128;
        long totalWrites = 100_000L;
        long parked = 0L; // cursor 0 is genuinely lapped the instant anything is evicted
        FanOutBuffer buffer = new FanOutBuffer(capacity);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writeDone = new CountDownLatch(1);
        CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
        AtomicLong parkedGapsSeen = new AtomicLong();
        AtomicLong parkedGenuineSeen = new AtomicLong();

        Thread writer = new Thread(() -> {
            try {
                start.await();
                for (long s = 1; s <= totalWrites; s++) {
                    buffer.publish(note(s));
                }
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                writeDone.countDown();
            }
        }, "gap-real-writer");

        // A reader pinned below the window: once lapped it classifies GENUINE. Under real races a
        // rare lock-free oldestRetainedSeq read can lag one eviction and read transient for a
        // tick, so this loop only proves the classifier is exercised and predominantly genuine;
        // the permanent guarantee is the deterministic quiescent assertion below.
        Thread parkedReader = new Thread(() -> {
            try {
                start.await();
                boolean done = false;
                while (!done) {
                    done = writeDone.await(0, TimeUnit.MILLISECONDS);
                    Result r = buffer.readSince(parked);
                    if (r.isGap()) {
                        parkedGapsSeen.incrementAndGet();
                        if (((Result.Gap) r).oldestRetainedSeq() > parked + 1) {
                            parkedGenuineSeen.incrementAndGet();
                        }
                    }
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        }, "gap-real-parked-reader");

        writer.start();
        parkedReader.start();
        start.countDown();

        assertTrue(writeDone.await(30, TimeUnit.SECONDS), "writer did not finish");
        parkedReader.join(10_000);
        assertTrue(errors.isEmpty(), "concurrent failures: " + errors);

        // Non-vacuity: the ring really lapped and the classifier ran against real-buffer GAPs,
        // returning genuine under actual concurrency (the eviction regime was exercised).
        assertTrue(buffer.droppedTotal() >= totalWrites - capacity,
                "the ring must have lapped hard (the eviction regime was exercised)");
        assertTrue(parkedGapsSeen.get() > 0, "non-vacuous: the parked reader must have seen GAPs");
        assertTrue(parkedGenuineSeen.get() > 0,
                "the classifier returned genuine for the lapped reader under real concurrency");

        // Permanent guarantee (deterministic - the buffer is now quiescent): a genuinely lapped
        // reader gets a GENUINE GAP (oldestRetainedSeq > cursor+1). A future eviction refactor
        // that let oldestRetainedSeq under-report would mask this reader's fall-behind and fail here.
        Result quiescent = buffer.readSince(parked);
        assertTrue(quiescent.isGap(), "a reader below the retention window must GAP");
        assertTrue(((Result.Gap) quiescent).oldestRetainedSeq() > parked + 1,
                "a genuinely lapped reader must be classified GENUINE, never masked as transient");
    }

    // ------------------------------------------------------------------------
    // 7. REAL FanOutBuffer (no mock), end-to-end through the SESSION: a session whose cursor
    //    genuinely fell off the retention window (its successor seq truly evicted, data missed)
    //    must demote on the FIRST tick - not be retried as a transient race. Tests 5 and 6 cover
    //    the real buffer for a caught-up session and for a raw lapped reader; this one closes the
    //    remaining seam by driving the lapped case through the session's own classifier, pinning
    //    the fail-safe direction of the fix (a real fall-behind is never mistaken for a race,
    //    not even for one tick, once the buffer state is settled).
    // ------------------------------------------------------------------------

    @Test
    void genuinelyLappedSessionAgainstRealBufferDemotesOnFirstTick() {
        int capacity = 8;
        FanOutBuffer buffer = new FanOutBuffer(capacity);
        Consumer<DemotionEvent> toGovernor =
                ev -> governor.onDemotion(IDENTITY, ev, clock.currentTimeMillis());

        // Fill the ring exactly to capacity (seqs 1..8, nothing evicted yet) and subscribe
        // caught-up at the head, so the session starts STREAMING via TAIL like a healthy edge.
        for (long seq = 1; seq <= capacity; seq++) {
            buffer.publish(note(seq));
        }
        FanOutSessionCore s = new FanOutSessionCore(buffer, replayAt(capacity), sink,
                FanOutConfig.defaults(), FanOutSessionMetrics.NOOP, clock, toGovernor);
        s.onSubscribe(subscribe(capacity));
        assertEquals(EdgeFrame.Mode.TAIL,
                sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode(),
                "caught-up non-zero cursor must TAIL, not snapshot-first");
        assertEquals(FanOutSessionCore.SessionState.STREAMING, s.state());
        sink.clear();

        // The session never ticks while the writer runs far ahead: publishing through 32 on a
        // capacity-8 ring evicts seqs 1..24, so the ring retains 25..32 and the session's cursor
        // (8) has genuinely missed committed data - its successor (9) is gone.
        for (long seq = capacity + 1; seq <= 32; seq++) {
            buffer.publish(note(seq));
        }
        assertTrue(buffer.droppedTotal() >= 32 - capacity,
                "the ring must have evicted past the session's cursor (a real fall-behind)");

        // FIRST tick on the settled buffer: readSince(8) takes the buffer's fast path
        // (cursor < lastEvictedSeq) and reports oldestRetainedSeq 25 > cursor + 1 = 9, so the
        // classifier must call it GENUINE and demote immediately - a transient-retry here would
        // be the false-negative that silently serves stale reads.
        s.tick(clock.now());

        assertEquals(FanOutSessionCore.SessionState.CATCHUP, s.state(),
                "a genuinely lapped session must leave STREAMING on the first tick");
        assertEquals(1, s.demotionCount(),
                "exactly one demotion on the first tick - no transient-retry grace for a real gap");
        assertEquals(DemotionEvent.REASON_GAP, s.lastDemotion().reason(),
                "the demotion is the genuine fall-behind GAP signal the governor counts");
    }
}

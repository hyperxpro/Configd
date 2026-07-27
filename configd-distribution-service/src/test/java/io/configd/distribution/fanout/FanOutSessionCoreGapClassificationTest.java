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
 * Verifies gap classification: genuine fall-behind (data evicted, must demote) vs. transient
 * lock-free-read-race (data retained, just retry). Pins the fix for an eviction bug where
 * healthy edges caught-up to buffer boundaries hit verify-after-read races on nearly every
 * boundary read, causing gapDemoteLimit (10 within 60s) to walk them to QUARANTINED (28m cooldown).
 * Tests use ScriptedSource for deterministic gap injection into SlowConsumerGovernor,
 * and real FanOutBuffer for eviction invariant: oldestRetainedSeq >= lastEvictedSeq + 1.
 */
class FanOutSessionCoreGapClassificationTest {

    private static final String IDENTITY = "edge-1";

    private final FakeClock clock = new FakeClock(1_000L);
    private final RecordingTransportSink sink = new RecordingTransportSink();
    private final SlowConsumerGovernor governor =
            new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), FanOutSessionMetrics.NOOP);

    private static final class ScriptedSource implements CommitNotificationSource {
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

    private static ReplaySource replayAt(long seq) {
        ConfigSnapshot snap = new ConfigSnapshot(HamtMap.empty(), seq, 0L);
        return () -> new ReplaySource.Replay(snap, seq);
    }

    private FanOutSessionCore session(ScriptedSource src, ReplaySource replay) {
        // Demotion listener wired to governor as FanOutConnectionDriver does (production code path).
        Consumer<DemotionEvent> toGovernor =
                ev -> governor.onDemotion(IDENTITY, ev, clock.currentTimeMillis());
        return new FanOutSessionCore(src, replay, sink, FanOutConfig.defaults(),
                FanOutSessionMetrics.NOOP, clock, toGovernor);
    }

    private static EdgeFrame.Subscribe subscribe(long resume) {
        return new EdgeFrame.Subscribe(true, List.of(), resume, -1L, IDENTITY);
    }

    /** Contiguous strictly-ascending seqs: boundary condition that exposes lock-free races. */
    private static CommitNotification note(long seq) {
        return new CommitNotification(seq, 1_000L + seq,
                new ConfigDelta(seq - 1, seq,
                        List.of(new ConfigMutation.Put("k" + seq, ("v" + seq).getBytes(StandardCharsets.UTF_8)))));
    }

    /** Subscribes caught up, with cursor equal to a non-zero latest, so decideMode picks TAIL then STREAMING. */
    private FanOutSessionCore streamingSessionAt(ScriptedSource src, ReplaySource replay, long cursor) {
        src.latest = cursor;
        src.oldest = 1L;
        src.behavior = c -> Result.ok(List.of()); // a clean read at subscribe time yields TAIL, not a gap
        FanOutSessionCore s = session(src, replay);
        s.onSubscribe(subscribe(cursor));
        assertEquals(EdgeFrame.Mode.TAIL,
                sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode(),
                "caught-up non-zero cursor must TAIL, not snapshot-first");
        assertEquals(FanOutSessionCore.SessionState.STREAMING, s.state());
        sink.clear();
        return s;
    }

    @Test
    void caughtUpEdgeAtEvictionBoundaryTransientGapDoesNotDemoteOrQuarantine() {
        ScriptedSource src = new ScriptedSource();
        FanOutSessionCore s = streamingSessionAt(src, replayAt(100L), 100L);

        // This models the transient case: oldestRetainedSeq sits far below the cursor, the
        // caught-up boundary case, so the successor of the cursor is still retained
        // (oldestRetainedSeq no greater than cursor + 1). This is the exact shape the
        // Lamport fallbacks of the buffer produce for a full ring under continuous writes.
        // The cursor never advances, since there is no data past it, so every tick re-races.
        src.behavior = c -> Result.gap(Math.max(0L, c - 5_000L));

        // 15 consecutive transient gaps exceed gapDemoteLimit (10), so this guards against
        // quarantining a healthy edge on read-race gaps alone: they must all be treated as
        // retries, not demotions, and 15 stays well under the live-lock backstop (128).
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

    @Test
    void genuineFallBehindStillDemotesAndQuarantines() {
        ScriptedSource src = new ScriptedSource();
        FanOutSessionCore s = streamingSessionAt(src, replayAt(100L), 100L);

        // This models the genuine case: oldestRetainedSeq is above cursor + 1, so the
        // successor of the cursor was evicted and the consumer genuinely fell off the
        // retention window and must re-snapshot. After the snapshot the cursor jumps back
        // to seq 100, so the next read is genuine again: the consumer keeps failing to
        // keep up, which is precisely what quarantine is for.
        src.behavior = c -> Result.gap(c + 2L);

        // Each genuine gap demotes from STREAMING to CATCHUP, the next tick re-snapshots
        // back to STREAMING, then it demotes again. Ten REASON_GAP demotions inside the
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

    // The live-lock backstop: if every read for a long run races, with no clean read to
    // reset the streak, the consumer cannot make progress and demoting is the correct
    // outcome.

    @Test
    void unrelentingTransientGapsTripLiveLockBackstop() {
        ScriptedSource src = new ScriptedSource();
        FanOutSessionCore s = streamingSessionAt(src, replayAt(100L), 100L);

        // Transient on every read, with the cursor pinned so there is never a clean read
        // to reset the streak: the pathological write-storm live-lock case.
        src.behavior = c -> Result.gap(Math.max(0L, c - 5_000L));

        // One short of the threshold: still retrying, no demotion yet.
        for (int i = 0; i < FanOutSessionCore.MAX_CONSECUTIVE_TRANSIENT_GAPS - 1; i++) {
            s.tick(clock.now());
        }
        assertEquals(FanOutSessionCore.SessionState.STREAMING, s.state(),
                "under the backstop threshold the session keeps retrying, never demotes");
        assertEquals(0, s.demotionCount(), "no demotion before the backstop threshold");

        // The threshold-th consecutive transient gap with no progress trips the backstop.
        s.tick(clock.now());
        assertEquals(FanOutSessionCore.SessionState.CATCHUP, s.state(),
                "the live-lock backstop demotes a consumer that cannot make progress");
        assertEquals(1, s.demotionCount(), "exactly one demotion at the backstop threshold");
        assertEquals(DemotionEvent.REASON_GAP, s.lastDemotion().reason(),
                "the backstop demotion is a genuine 'cannot keep up' GAP signal");
    }

    // A clean read between transient gaps resets the streak, so the backstop can only be
    // reached by an unbroken run of races, never by intermittent ones. This guards the
    // reset semantics.

    @Test
    void cleanReadResetsTheTransientGapStreak() {
        ScriptedSource src = new ScriptedSource();
        FanOutSessionCore s = streamingSessionAt(src, replayAt(100L), 100L);

        Result transient_ = Result.gap(Math.max(0L, 100L - 5_000L)); // oldestRetained is far below the cursor
        Result cleanEmpty = Result.ok(List.of());                    // caught up, no race

        // This runs far more ticks than the backstop threshold, but a clean read every few
        // ticks resets the consecutive counter, so the backstop must never trip.
        for (int i = 0; i < FanOutSessionCore.MAX_CONSECUTIVE_TRANSIENT_GAPS * 3; i++) {
            src.behavior = (i % 4 == 3) ? c -> cleanEmpty : c -> transient_;
            s.tick(clock.now());
        }

        assertEquals(FanOutSessionCore.SessionState.STREAMING, s.state(),
                "intermittent transient GAPs interleaved with clean reads must never trip the backstop");
        assertEquals(0, s.demotionCount(), "no demotion when clean reads keep resetting the streak");
        assertNotEquals(SlowConsumerGovernor.ConsumerState.QUARANTINED, governor.state(IDENTITY));
    }

    // Exercises a real FanOutBuffer (no mock), single-threaded: a caught-up session
    // draining a full ring while single writes continue past capacity must never demote.
    // This proves that the drop-oldest eviction boundary alone, with no concurrency race,
    // never produces a spurious gap for a reader that keeps up.

    @Test
    void caughtUpSessionDrainingRealBufferPastCapacityNeverDemotes() {
        int capacity = 8;
        FanOutBuffer buffer = new FanOutBuffer(capacity);
        // Subscribing on the empty buffer selects TAIL then STREAMING; cursor 0 on an
        // empty ring tails.
        Consumer<DemotionEvent> toGovernor =
                ev -> governor.onDemotion(IDENTITY, ev, clock.currentTimeMillis());
        FanOutSessionCore s = new FanOutSessionCore(buffer, replayAt(0L), sink, FanOutConfig.defaults(),
                FanOutSessionMetrics.NOOP, clock, toGovernor);
        s.onSubscribe(subscribe(0));
        assertEquals(FanOutSessionCore.SessionState.STREAMING, s.state());

        // Publish one, tick to stream it, then ack it, repeated far past capacity. The
        // cursor of the reader tracks the tail every tick, so the data it needs is always
        // retained: readSince never gaps even though every write past the 8th evicts the
        // oldest entry.
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

    // Exercises a real FanOutBuffer (no mock) with a concurrent hard-lapping writer. This
    // locks the safety-critical soundness direction: a genuinely lapped reader, at cursor
    // 0 whose data is truly evicted, is never masked, meaning it never receives a gap that
    // the classifier would call transient and refuse to demote on. This is the
    // load-bearing eviction invariant the whole fix rests on: oldestRetainedSeq is always
    // at least lastEvictedSeq + 1, so whenever the cursor is behind lastEvictedSeq,
    // oldestRetainedSeq is more than cursor + 1 and the gap is genuine. This test locks
    // that invariant against a future eviction refactor.
    //
    // The concurrent run exercises the classifier against real-buffer gaps, proving the
    // eviction regime is real and that the classifier returns genuine under actual races.
    // The permanent guarantee is then asserted deterministically on the quiescent buffer
    // after the run completes: this is the honest form of the invariant, because
    // oldestRetainedSeq is read lock-free (oldestSeqInternal reads the tail, then the
    // slot) and can momentarily lag a concurrent eviction, so a rare single
    // transient-classified tick is allowed provided it self-corrects (see handleGap). The
    // healthy caught-up direction, never demoting at the eviction boundary, is covered
    // deterministically by caughtUpSessionDrainingRealBufferPastCapacityNeverDemotes
    // above, which models the real 10 millisecond tick regime rather than a writer that
    // runs flat out and manufactures nanosecond read-races.

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

        // A reader pinned below the window classifies genuine once lapped. Under real
        // races a rare lock-free read of oldestRetainedSeq can lag one eviction and read
        // transient for a tick, so this loop only proves the classifier is exercised and
        // predominantly genuine; the permanent guarantee is the deterministic quiescent
        // assertion below.
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

        // Non-vacuity: the ring really lapped and the classifier ran against real-buffer
        // gaps, returning genuine under actual concurrency, so the eviction regime really
        // was exercised.
        assertTrue(buffer.droppedTotal() >= totalWrites - capacity,
                "the ring must have lapped hard (the eviction regime was exercised)");
        assertTrue(parkedGapsSeen.get() > 0, "non-vacuous: the parked reader must have seen GAPs");
        assertTrue(parkedGenuineSeen.get() > 0,
                "the classifier returned genuine for the lapped reader under real concurrency");

        // Permanent guarantee, deterministic now that the buffer is quiescent: a
        // genuinely lapped reader gets a genuine gap, with oldestRetainedSeq greater than
        // cursor + 1. A future eviction refactor that let oldestRetainedSeq under-report
        // would mask this fall-behind and fail here.
        Result quiescent = buffer.readSince(parked);
        assertTrue(quiescent.isGap(), "a reader below the retention window must GAP");
        assertTrue(((Result.Gap) quiescent).oldestRetainedSeq() > parked + 1,
                "a genuinely lapped reader must be classified GENUINE, never masked as transient");
    }

    // Exercises a real FanOutBuffer (no mock) end-to-end through the session: a session
    // whose cursor genuinely fell off the retention window, with its successor seq truly
    // evicted and data missed, must demote on the first tick rather than being retried as
    // a transient race. The previous two tests cover the real buffer for a caught-up
    // session and for a raw lapped reader; this one closes the remaining seam by driving
    // the lapped case through the classifier owned by the session itself, pinning the
    // fail-safe direction: a real fall-behind is never mistaken for a race, not even for
    // one tick, once the buffer state is settled.

    @Test
    void genuinelyLappedSessionAgainstRealBufferDemotesOnFirstTick() {
        int capacity = 8;
        FanOutBuffer buffer = new FanOutBuffer(capacity);
        Consumer<DemotionEvent> toGovernor =
                ev -> governor.onDemotion(IDENTITY, ev, clock.currentTimeMillis());

        // Fill the ring exactly to capacity, seqs 1 through 8 with nothing evicted yet,
        // and subscribe caught up at the head, so the session starts STREAMING through
        // TAIL like a healthy edge.
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

        // The session never ticks while the writer runs far ahead: publishing through 32
        // on a capacity-8 ring evicts seqs 1 through 24, so the ring retains 25 through 32
        // and the cursor of the session, at 8, has genuinely missed committed data; its
        // successor, 9, is gone.
        for (long seq = capacity + 1; seq <= 32; seq++) {
            buffer.publish(note(seq));
        }
        assertTrue(buffer.droppedTotal() >= 32 - capacity,
                "the ring must have evicted past the session's cursor (a real fall-behind)");

        // First tick on the settled buffer: readSince(8) takes the fast path of the
        // buffer, since the cursor is behind lastEvictedSeq, and reports an
        // oldestRetainedSeq of 25, which is more than cursor + 1 (9), so the classifier
        // must call it genuine and demote immediately. A transient retry here would be
        // the false negative that silently serves stale reads.
        s.tick(clock.now());

        assertEquals(FanOutSessionCore.SessionState.CATCHUP, s.state(),
                "a genuinely lapped session must leave STREAMING on the first tick");
        assertEquals(1, s.demotionCount(),
                "exactly one demotion on the first tick - no transient-retry grace for a real gap");
        assertEquals(DemotionEvent.REASON_GAP, s.lastDemotion().reason(),
                "the demotion is the genuine fall-behind GAP signal the governor counts");
    }
}

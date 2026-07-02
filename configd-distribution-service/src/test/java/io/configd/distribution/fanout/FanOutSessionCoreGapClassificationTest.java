package io.configd.distribution.fanout;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.CommitNotificationSource.Result;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression matrix for the Gate 4.5 fan-out reliability fix: {@link FanOutSessionCore}
 * distinguishes a GENUINE fall-behind GAP (the consumer's needed data was evicted - demote)
 * from a TRANSIENT lock-free-read-race GAP (the data is still retained - retry next tick),
 * using the {@code oldestRetainedSeq} the {@link Result.Gap} carries.
 *
 * <p>The confirmed defect (live EC2, single edge at 50 w/s on a near-idle box): once the ring
 * is full and writes continue, a FULLY caught-up edge hits the buffer's Lamport
 * verify-after-read fallbacks on nearly every boundary read. Pre-fix each such GAP called
 * {@code demote(REASON_GAP)}, so {@code gapDemoteLimit} (10 within 60 s) walked a HEALTHY edge
 * straight to QUARANTINED (~28 min cooldown) and froze its frontier.
 *
 * <p>These tests drive the session against a {@link ScriptedSource} that returns a chosen
 * transient-vs-genuine GAP deterministically (no threads, no timing), and wire the session's
 * demotion listener to a REAL {@link SlowConsumerGovernor} exactly as
 * {@code FanOutConnectionDriver.onDemotionEvent} does - so the proof is end-to-end into the
 * quarantine ladder.
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
}

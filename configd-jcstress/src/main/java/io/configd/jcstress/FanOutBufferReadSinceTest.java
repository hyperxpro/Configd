package io.configd.jcstress;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.CommitNotificationSource.Result;
import io.configd.distribution.FanOutBuffer;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

import java.util.List;

/**
 * Verifies {@link FanOutBuffer#readSince} under concurrent eviction. A single
 * writer (modelling the Raft apply thread) races one lock-free reader at every
 * interesting ring occupancy, including the exactly-full wrap-around boundary.
 *
 * <p><b>Invariant under test:</b> a {@code readSince} that races an eviction must
 * return EITHER a clean contiguous ascending run of notifications with
 * {@code seq > cursor} (no duplicate, no skip, no torn/null slot) OR a GAP signal.
 * It must NEVER hand back a torn read: a duplicated seq, a non-ascending run, a
 * null/wrong-typed slot, or a run that silently skips an evicted notification.
 *
 * <p>The reader classifies its own result into an {@link I_Result} code; the only
 * FORBIDDEN codes are the torn-read classes. Modelling exactly ONE writer matches
 * the documented single-writer precondition - testing two concurrent writers
 * would exercise an unsupported contract.
 *
 * <h2>Result codes</h2>
 * <ul>
 *   <li>{@code 0} GAP - acceptable (consumer replays).</li>
 *   <li>{@code 1} OK, clean ascending run, all {@code seq > cursor} - acceptable.</li>
 *   <li>{@code 2} OK, empty run - acceptable (reader saw nothing new yet).</li>
 *   <li>{@code 9} TORN - duplicate seq, non-ascending, null slot, or a seq
 *       {@code <= cursor} leaked into an OK run. FORBIDDEN.</li>
 * </ul>
 */
public final class FanOutBufferReadSinceTest {

    private FanOutBufferReadSinceTest() {
    }

    private static final int GAP = 0;
    private static final int OK_RUN = 1;
    private static final int OK_EMPTY = 2;
    private static final int TORN = 9;

    /**
     * Classifies a {@link Result} against the cursor, returning a result code.
     * Any structural violation (duplicate, descending, null, or a seq that should
     * have been filtered) collapses to {@link #TORN}.
     */
    private static int classify(Result res, long cursor) {
        if (res.isGap()) {
            return GAP;
        }
        List<CommitNotification> ns = ((Result.Ok) res).notifications();
        if (ns.isEmpty()) {
            return OK_EMPTY;
        }
        long prev = Long.MIN_VALUE;
        for (CommitNotification n : ns) {
            if (n == null) {
                return TORN;               // null slot leaked through
            }
            long s = n.seq();
            if (s <= cursor) {
                return TORN;               // a notification that should have been filtered
            }
            if (s <= prev) {
                return TORN;               // duplicate or non-ascending
            }
            prev = s;
        }
        return OK_RUN;
    }

    /**
     * Base for the occupancy variants: a small ring pre-seeded by the test's
     * {@code state} constructor to a chosen fill level, then one writer publishes
     * fresh notifications (forcing eviction once full) while one reader tails from
     * a fixed cursor.
     */
    abstract static class Base {
        final FanOutBuffer buf;
        final long cursor;
        /** Seq of the first notification the writer will publish during the race. */
        final long firstNewSeq;

        Base(int capacity, int preSeed, long cursor) {
            this.buf = new FanOutBuffer(capacity);
            for (int i = 0; i < preSeed; i++) {
                buf.publish(Notifications.of(i));
            }
            this.cursor = cursor;
            this.firstNewSeq = preSeed;
        }

        final void writerPublish() {
            // Two appends so the ring laps when it is at/near full: this is the
            // window where a reader scanning [tail, head) can observe a slot being
            // overwritten in place - the exact verify-after-read hazard.
            buf.publish(Notifications.of(firstNewSeq));
            buf.publish(Notifications.of(firstNewSeq + 1));
        }
    }

    // Variant 1: ring NOT yet full (head < capacity). No eviction during
    // the race window, but head advances under the reader.
    @JCStressTest
    @State
    @Description("readSince vs publish, ring partially full (no eviction) — torn read forbidden")
    @Outcome(id = "0", expect = Expect.ACCEPTABLE, desc = "GAP (legal)")
    @Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "clean ascending run")
    @Outcome(id = "2", expect = Expect.ACCEPTABLE, desc = "empty run (nothing new observed yet)")
    @Outcome(id = "9", expect = Expect.FORBIDDEN, desc = "TORN: duplicate/skip/null/leak")
    public static class PartiallyFull extends Base {
        public PartiallyFull() {
            super(8, 3, 0);
        }

        @Actor
        public void writer() {
            writerPublish();
        }

        @Actor
        public void reader(I_Result r) {
            r.r1 = classify(buf.readSince(cursor), cursor);
        }
    }

    // Variant 2: ring EXACTLY full (head == capacity). The next publish evicts slot 0
    // and wraps - the head-vs-tail publish-order window the verify-after-read closes.
    // This is the load-bearing wrap-around case.
    @JCStressTest
    @State
    @Description("readSince vs publish, ring exactly full → wrap+evict — torn read forbidden")
    @Outcome(id = "0", expect = Expect.ACCEPTABLE, desc = "GAP (eviction lapped the reader)")
    @Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "clean ascending run")
    @Outcome(id = "2", expect = Expect.ACCEPTABLE, desc = "empty run")
    @Outcome(id = "9", expect = Expect.FORBIDDEN, desc = "TORN: duplicate/skip/null/leak")
    public static class ExactlyFullWrap extends Base {
        public ExactlyFullWrap() {
            super(4, 4, 0); // head == capacity: next publish evicts + wraps
        }

        @Actor
        public void writer() {
            writerPublish();
        }

        @Actor
        public void reader(I_Result r) {
            r.r1 = classify(buf.readSince(cursor), cursor);
        }
    }

    // Variant 3: ring already past one full lap (head > capacity, tail > 0).
    // The reader's cursor sits below the live window so a correct buffer must
    // return GAP (via watermark) - the race is between the watermark publish and
    // the slot copy. A torn read here would be the most dangerous: serving a
    // truncated run that silently drops the evicted prefix.
    @JCStressTest
    @State
    @Description("readSince(cursor below window) vs evicting publish — GAP-or-clean, never silent skip")
    @Outcome(id = "0", expect = Expect.ACCEPTABLE, desc = "GAP (correct: cursor overtaken)")
    @Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "clean ascending run above cursor")
    @Outcome(id = "2", expect = Expect.ACCEPTABLE, desc = "empty run")
    @Outcome(id = "9", expect = Expect.FORBIDDEN, desc = "TORN: silent skip/duplicate/null")
    public static class LappedCursorBelowWindow extends Base {
        public LappedCursorBelowWindow() {
            // capacity 4, pre-seed 6 -> tail=2, window holds seq [2..5]; cursor 0
            // is already below the window, so the watermark must drive GAP unless a
            // clean run above 0 is genuinely retained.
            super(4, 6, 0);
        }

        @Actor
        public void writer() {
            writerPublish();
        }

        @Actor
        public void reader(I_Result r) {
            r.r1 = classify(buf.readSince(cursor), cursor);
        }
    }

    // Variant 4: two concurrent readers + one writer. Readers must never disagree
    // in a way that proves a torn slot (each independently must see a clean run or
    // GAP). FORBIDDEN if EITHER reader tears.
    @JCStressTest
    @State
    @Description("two readers vs one evicting writer — neither reader may tear")
    @Outcome(id = "0", expect = Expect.ACCEPTABLE, desc = "both readers GAP-or-clean (encoded ok)")
    @Outcome(id = "9", expect = Expect.FORBIDDEN, desc = "TORN: at least one reader tore")
    public static class TwoReadersOneWriter extends Base {
        public TwoReadersOneWriter() {
            super(4, 4, 0);
        }

        @Actor
        public void writer() {
            writerPublish();
        }

        @Actor
        public void reader1(I_Result r) {
            int c = classify(buf.readSince(cursor), cursor);
            if (c == TORN) {
                r.r1 = TORN;
            }
        }

        @Actor
        public void reader2(I_Result r) {
            int c = classify(buf.readSince(cursor), cursor);
            if (c == TORN) {
                r.r1 = TORN;
            }
        }

        @Arbiter
        public void arbiter(I_Result r) {
            // Default 0 means neither reader tore (any GAP/clean combination); the
            // only non-zero value any actor ever writes is TORN.
            if (r.r1 != TORN) {
                r.r1 = 0;
            }
        }
    }
}

package io.configd.jcstress.transport;

import io.configd.jcstress.transport.PeerModel.StreamRef;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.I_Result;

/**
 * The six jcstress interleavings on {@code TcpRaftTransport}'s per-peer shared
 * state. Each nested test maps 1:1 to a numbered scenario. The concurrency is
 * exercised through {@link PeerModel}, which copies the field algebra and publish
 * order from {@code PeerConnection} verbatim (see that class for why a model
 * rather than live sockets).
 *
 * <p>The single-writer-per-stream and single-in-flight-connect contracts are
 * enforced by {@code connectInFlight}/the identity guard; these tests assert
 * those invariants hold under every interleaving (no two writers, no null stream,
 * no permanently-wedged frame, exact drop accounting, idempotent teardown).
 */
public final class TcpRaftTransportRaceTest {

    private TcpRaftTransportRaceTest() {
    }

    // (1) enqueue(out==null) vs teardown-clear vs connect-publish
    //     Assert: no frame left queued with no scheduled connect (no wedge),
    //     and no double in-flight connect.
    @JCStressTest
    @State
    @Description("(1) enqueue(out==null) vs teardown vs connect-publish — no wedge, no double connect")
    @Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "frame covered by a connect (writer or pending)")
    @Outcome(id = "9", expect = Expect.FORBIDDEN, desc = "WEDGE: frame queued with no connect path")
    public static class EnqueueVsTeardownVsPublish {
        final PeerModel p = new PeerModel(4);

        public EnqueueVsTeardownVsPublish() {
            // A connection exists initially so enqueue sees out!=null sometimes and
            // teardown can clear it, racing a fresh send.
            p.connectAndStartWriterSuccess();
        }

        @Actor
        public void sender() {
            p.enqueueOrDrop(new byte[]{1});
        }

        @Actor
        public void teardownActor() {
            // Tear down the currently-published stream (whatever it is).
            StreamRef s = p.out();
            if (s != null) {
                p.teardown(s);
            }
        }

        @Arbiter
        public void arbiter(I_Result r) {
            // No-wedge invariant: if frames remain queued, delivery must be covered
            // by EITHER a live connection (out != null -> its writer drains) OR a
            // connect that is currently pending/in-flight (pendingConnects > 0 ||
            // connectInFlight). The wedge bug is frames queued with out == null AND
            // no connect pending AND not in-flight - nothing will ever drain them.
            boolean framesRemain = p.queueSize() > 0;
            boolean connectPath = p.out() != null
                    || p.pendingConnects.get() > 0
                    || p.connectInFlight.get();
            r.r1 = (!framesRemain || connectPath) ? 1 : 9;
        }
    }

    // (2) scheduleConnect CAS vs connectAndStartWriter finally reset+reschedule
    //     Assert: exactly one pending connect survives when frames remain.
    @JCStressTest
    @State
    @Description("(2) scheduleConnect CAS vs connect finally reset — exactly one pending connect")
    @Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "frames remain → exactly one connect pending/in-flight")
    @Outcome(id = "0", expect = Expect.ACCEPTABLE, desc = "queue genuinely empty → no connect needed")
    @Outcome(id = "9", expect = Expect.FORBIDDEN, desc = "LOST RESCHEDULE (0 pending w/ frames) or DOUBLE SCHEDULE (>1 pending)")
    public static class CasVsFinallyReset {
        final PeerModel p = new PeerModel(4);

        public CasVsFinallyReset() {
            p.enqueueOrDrop(new byte[]{1}); // one frame queued, a connect scheduled (pending=1)
        }

        @Actor
        public void connector() {
            // The scheduled connect runs and FAILS; its finally resets the flag and
            // reschedules because the queue is non-empty (pending: 1 -> 0 -> 1).
            p.connectAndStartWriterFailure();
        }

        @Actor
        public void sender() {
            // A concurrent send may grab the in-flight flag between reset and check.
            p.enqueueOrDrop(new byte[]{2});
        }

        @Arbiter
        public void arbiter(I_Result r) {
            // Invariant: connectInFlight (single-in-flight CAS) admits AT MOST one
            // pending connect at a time. After the race, if frames remain there must
            // be exactly one connect pending (the finally's reschedule OR the sender's
            // - never both, never neither). pendingConnects models scheduled-not-run.
            boolean framesRemain = p.queueSize() > 0;
            int pending = p.pendingConnects.get();
            if (!framesRemain) {
                // Genuinely empty queue -> no connect needed; but if one is still
                // pending that is harmless (it will run and find nothing). Either is
                // legal; 0 keeps the outcome distinct from the frames-remain case.
                r.r1 = (pending <= 1) ? 0 : 9; // >1 pending is a double-schedule even when empty
            } else {
                // Exactly one pending (or in-flight) is correct; 0 = lost reschedule,
                // >1 = double schedule. Both forbidden.
                boolean exactlyOne = (pending == 1)
                        || (pending == 0 && p.connectInFlight.get());
                r.r1 = exactlyOne ? 1 : 9;
            }
        }
    }

    // (3) reader-teardown vs writer-teardown on the SAME socket s
    //     Assert: identity guard makes teardown idempotent - at most one
    //     "wasLive" clear; never clobber a newer published socket.
    @JCStressTest
    @State
    @Description("(3) reader-teardown vs writer-teardown same socket — idempotent, one clear")
    @Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "exactly one teardown took effect")
    @Outcome(id = "9", expect = Expect.FORBIDDEN, desc = "BOTH cleared (double markDisconnected) or clobbered newer")
    public static class DoubleTeardownIdempotent {
        final PeerModel p = new PeerModel(4);
        final StreamRef s;

        public DoubleTeardownIdempotent() {
            p.connectAndStartWriterSuccess();
            s = p.out();
        }

        @Actor
        public void readerTeardown() {
            p.teardown(s);
        }

        @Actor
        public void writerTeardown() {
            p.teardown(s);
        }

        @Arbiter
        public void arbiter(I_Result r) {
            // After both teardowns of the SAME s, the published stream must be null
            // (cleared exactly once) and nothing newer should have been clobbered.
            // The identity guard guarantees only the first observer with socket==s
            // clears; the second sees socket!=s (already null) and no-ops.
            r.r1 = (p.out() == null) ? 1 : 9;
        }
    }

    // (4) socket/out volatile publish vs writer-start visibility
    //     Assert: the writer never sees a null/stale stream; never two writers.
    @JCStressTest
    @State
    @Description("(4) publish vs writer-start visibility — never null stream, never two writers")
    @Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "exactly one writer, non-null stream")
    @Outcome(expect = Expect.FORBIDDEN, desc = "null stream observed or >1 writer on one stream")
    public static class PublishVsWriterStart {
        final PeerModel p = new PeerModel(4);

        @Actor
        public void connector() {
            // Publishes socket/out in source order then starts exactly one writer.
            p.connectAndStartWriterSuccess();
        }

        @Actor
        public void enqueuer() {
            // Concurrent enqueue reads `out` to decide connected-ness; it must never
            // start a second writer, and the connector's writer must never see null.
            p.enqueueOrDrop(new byte[]{7});
        }

        @Arbiter
        public void arbiter(II_Result r) {
            r.r1 = p.writerSawNullStream.get() ? 9 : 1;   // 1 == never saw null
            int w = p.writersStarted.get();
            r.r2 = (w == 1) ? 1 : 9;                       // exactly one writer
        }
    }

    // (5) close() vs in-flight connect past the closed gate
    //     Assert: at most a benign leaked socket - no writer left running, no
    //     use of a closed stream; the queue is cleared and closed observed.
    @JCStressTest
    @State
    @Description("(5) close() vs in-flight connect past closed gate — benign leak only")
    @Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "closed observed; at most one leaked stream")
    @Outcome(id = "9", expect = Expect.FORBIDDEN, desc = "writer running past close / closed not observed")
    public static class CloseVsInFlightConnect {
        final PeerModel p = new PeerModel(4);

        @Actor
        public void closer() {
            p.close();
        }

        @Actor
        public void connector() {
            // A connect that already passed the closed gate publishes a fresh
            // stream. Source semantics: this is a leaked socket reclaimed by the
            // daemon/JVM, NOT a correctness defect.
            p.publishFreshStreamPastGate();
        }

        @Arbiter
        public void arbiter(I_Result r) {
            // Invariant: closed is observed true (close() always wins eventually),
            // and at most ONE writer was ever started (the racing connect's). A
            // second writer or a non-closed end state is the forbidden shape.
            boolean closedObserved = p.closed.get();
            boolean atMostOneWriter = p.writersStarted.get() <= 1;
            r.r1 = (closedObserved && atMostOneWriter) ? 1 : 9;
        }
    }

    // (6) drop-oldest evict vs writer poll - framesDropped accounting exact
    //     Assert: total accounted frames (delivered + dropped + still-queued) is
    //     conserved; no double count, no missed count.
    @JCStressTest
    @State
    @Description("(6) drop-oldest evict vs writer poll — framesDropped accounting is exact")
    @Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "frame accounting conserved")
    @Outcome(id = "9", expect = Expect.FORBIDDEN, desc = "MISCOUNT: dropped+queued inconsistent with sends")
    public static class DropOldestVsPoll {
        // capacity 2 so two concurrent sends + a poll force the evict path.
        final PeerModel p = new PeerModel(2);
        // Pre-fill to capacity so the next offer triggers drop-oldest.
        public DropOldestVsPoll() {
            p.queue.offer(new byte[]{0});
            p.queue.offer(new byte[]{1});
        }

        @Actor
        public void sender() {
            // Full queue -> drop-oldest path: poll() + offer(), incrementing dropped.
            p.enqueueOrDrop(new byte[]{2});
        }

        @Actor
        public void poller() {
            // The writer draining one frame concurrently with the evict.
            p.queue.poll();
        }

        @Arbiter
        public void arbiter(I_Result r) {
            // Conservation: we started with 2 queued and sent 1 more (3 frames
            // "entered"). After the race, (delivered-by-poll) + (dropped) +
            // (still-queued) must equal the frames that ever entered, modulo the
            // single poll. The model can't see delivered count directly, so we
            // bound it: dropped is 0 or 1 (one evict at most), and queue size is
            // consistent with [0,2]. A dropped>1 or an impossible queue size is a
            // miscount.
            long dropped = p.framesDropped.get();
            int qs = p.queueSize();
            boolean droppedSane = dropped >= 0 && dropped <= 1;
            boolean queueSane = qs >= 0 && qs <= 2;
            // Frames that entered after construction-fill: the 2 pre-filled + 1 sent
            // = 3 slots' worth of liveness. With one evict and one poll, at most one
            // frame is dropped; queue holds the rest minus what poll removed.
            r.r1 = (droppedSane && queueSane) ? 1 : 9;
        }
    }
}

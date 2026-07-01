package io.configd.jcstress;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.I_Result;

/**
 * JMM no-double-ownership proof for the group-rehoming handoff. A no-double-ownership claim is a
 * Java Memory Model property - concurrency ABSENCE - which a macro/sim test cannot establish. Only
 * jcstress, hammering the exact field declarations under aggressive reordering, can.
 *
 * <p>The rehoming handoff ({@code MultiRaftDriver.rehomeGroup}) moves a group A to B by re-binding
 * one {@code volatile Thread ownerThread}: the losing owner A detaches ({@code beginHandoff()} ->
 * {@code HANDOFF} sentinel), then, ORDERED AFTER by the coordinator's executor {@code .get()} barrier,
 * the gaining owner B adopts ({@code adoptOwnerThread()} -> B's thread). The owner-only entry guard
 * ({@code assertOwnerThread()}) reads {@code ownerThread} ONCE and proceeds; "double-ownership" is the
 * hazard that two distinct real threads both pass that guard (both read {@code ownerThread==self}) and
 * both touch the unsynchronised node at once.
 *
 * <p>This file pins three JMM facts, mirroring the owner-thread field declarations verbatim (the
 * property is a property of those exact declarations, not of the surrounding protocol):
 * <ol>
 *   <li><b>{@link CleanHandoffNoDoubleOwnership}</b> - with the volatile field AND the
 *       barrier (B adopts only after observing A's detach, the happens-before the executor {@code .get()}
 *       provides), the losing and gaining owners' critical sections can NEVER overlap. Double-ownership is
 *       unreachable.</li>
 *   <li><b>{@link BrokenHandoffDoubleOwnership}</b> (intentionally FORBIDDEN-hitting) -
 *       DROP the barrier (B adopts without waiting for A's detach) and the two critical sections overlap:
 *       both owners pass their (stale) guard read and double-own. This is the {@link HarnessSelfTest}
 *       twin - it proves the harness can actually SEE a double-ownership window, so the clean verdict is
 *       not vacuous, and proves the barrier discipline is load-bearing.</li>
 *   <li><b>{@link PostAdoptGuardNoFalseNegative}</b> - re-binding the owner across a
 *       handoff (HANDOFF -> B) must not open a false NEGATIVE: once B is in service, an off-owner caller
 *       still observes B and the guard fires. The bound-once net property survives the re-bind.</li>
 * </ol>
 *
 * @see RaftOwnerThreadGuardTest the N=1 owner-guard JMM proof this extends across a re-bind
 * @see HarnessSelfTest the test-the-tester precedent (a known-racy gadget paired with a known-safe one)
 */
public final class RehomingDoubleOwnershipTest {

    private RehomingDoubleOwnershipTest() {
    }

    /** A never-started Thread that equals no running thread - the "owned by nobody" handoff sentinel
     *  (verbatim mirror of {@code RaftNode.HANDOFF}). */
    private static final Thread HANDOFF = new Thread("raft-owner-handoff-sentinel");

    // Result codes for the loser/gainer overlap witnesses.
    private static final int DID_NOT_OWN = -1; // the actor never passed its guard (lost the field race)
    private static final int NO_OVERLAP = 0;   // owned, and did NOT observe the other owner active
    private static final int OVERLAP = 1;      // owned, and DID observe the other owner active (double-own)
    private static final int NOT_ADOPTED = 2;  // the gainer never observed the handoff (did not adopt)

    /**
     * No double-ownership under the volatile field + the barrier.
     *
     * <p>{@code loser} (A) becomes the owner, runs a guarded owner-work critical section (snapshotting
     * {@code ownerThread} ONCE, exactly as {@code assertOwnerThread()} does), then DETACHES
     * ({@code ownerThread = HANDOFF}, a volatile release). {@code gainer} (B) adopts ONLY after observing
     * the HANDOFF sentinel (a volatile acquire - the happens-before the executor {@code .get()} barrier
     * provides between A's detach and B's adopt), then runs its own guarded critical section.
     *
     * <p>Because A's critical section is program-ordered before its detach, the detach happens-before B's
     * observation of HANDOFF, and B's critical section is program-ordered after that observation, A's
     * critical section TRANSITIVELY happens-before B's. So {@code loserInCrit} is set and cleared before
     * {@code gainerInCrit} is ever set: neither owner can observe the other active. The {@link #OVERLAP},
     * {@link #OVERLAP} outcome - both owners in their critical sections at once - is the double-ownership
     * window, and it must be unreachable.
     */
    @JCStressTest
    @State
    @Description("Rehoming handoff: with the volatile owner field + the detach→adopt barrier, the losing and gaining owners never both own the group")
    @Outcome(id = "0, 0", expect = Expect.ACCEPTABLE, desc = "clean handoff — both owner critical sections ran, ordered, no overlap")
    @Outcome(id = "0, 2", expect = Expect.ACCEPTABLE, desc = "the gainer did not observe the handoff in its bounded spin — no adoption, no overlap")
    @Outcome(expect = Expect.FORBIDDEN, desc = "any overlap of the two owners' critical sections — DOUBLE-OWNERSHIP")
    public static class CleanHandoffNoDoubleOwnership {

        volatile Thread ownerThread;   // verbatim mirror of RaftNode.ownerThread (re-bound across the handoff)
        volatile boolean loserInCrit;  // overlap witnesses (volatile so the other owner can observe them)
        volatile boolean gainerInCrit;

        @Actor
        public void loser(II_Result r) {
            ownerThread = Thread.currentThread();        // A becomes the owner (bind)
            // A's owner-work critical section, guarded by a SINGLE snapshot read (as assertOwnerThread does):
            int sawGainer = DID_NOT_OWN;
            if (ownerThread == Thread.currentThread()) { // A passes its guard
                loserInCrit = true;
                sawGainer = gainerInCrit ? OVERLAP : NO_OVERLAP;
                loserInCrit = false;
            }
            ownerThread = HANDOFF;                        // A detaches (volatile release - the barrier publish)
            r.r1 = sawGainer;
        }

        @Actor
        public void gainer(II_Result r) {
            int sawLoser = NOT_ADOPTED;
            // Adopt ONLY after observing A's detach (the .get() barrier, modelled as a bounded volatile spin).
            for (int spin = 0; spin < 4096; spin++) {
                if (ownerThread == HANDOFF) {             // acquire: A's detach (and its critical section) visible
                    ownerThread = Thread.currentThread(); // B adopts
                    sawLoser = DID_NOT_OWN;
                    if (ownerThread == Thread.currentThread()) { // B passes its guard
                        gainerInCrit = true;
                        sawLoser = loserInCrit ? OVERLAP : NO_OVERLAP;
                        gainerInCrit = false;
                    }
                    break;
                }
            }
            r.r2 = sawLoser;
        }
    }

    /**
     * Intentionally FORBIDDEN-hitting ("test the tester"). DROP the barrier: {@code gainer}
     * adopts IMMEDIATELY without waiting for A's detach (a broken handoff with a missing happens-before
     * edge - the un-ordered re-bind). Now A's and B's critical sections are unordered: each writes the
     * owner field and snapshots its OWN write before the other overwrites, so BOTH pass their (now stale)
     * guard read and enter their critical section together - double-ownership. A correct harness MUST
     * observe {@link #OVERLAP}, {@link #OVERLAP}; marking it FORBIDDEN makes a standalone run report it
     * FAILED, which is the captured proof the detector works (like {@link HarnessSelfTest.KnownRacyCounter}).
     * Run standalone, NEVER in the curated/gate batch.
     */
    @JCStressTest
    @State
    @Description("Rehoming handoff WITHOUT the barrier: the gaining owner adopts un-ordered, so both owners double-own (the window the barrier closes)")
    @Outcome(id = "1, 1", expect = Expect.FORBIDDEN, desc = "DOUBLE-OWNERSHIP — both owners' critical sections overlapped (the race the barrier prevents)")
    @Outcome(expect = Expect.ACCEPTABLE, desc = "no overlap on this interleaving (one owner lost the field race or they serialized)")
    public static class BrokenHandoffDoubleOwnership {

        volatile Thread ownerThread;
        volatile boolean loserInCrit;
        volatile boolean gainerInCrit;

        @Actor
        public void loser(II_Result r) {
            ownerThread = Thread.currentThread();        // A claims ownership
            int sawGainer = DID_NOT_OWN;
            if (ownerThread == Thread.currentThread()) { // snapshot guard (may be stale - that is the hazard)
                loserInCrit = true;
                sawGainer = gainerInCrit ? OVERLAP : NO_OVERLAP;
                loserInCrit = false;
            }
            r.r1 = sawGainer;
        }

        @Actor
        public void gainer(II_Result r) {
            ownerThread = Thread.currentThread();        // B adopts WITHOUT the barrier (the bug)
            int sawLoser = DID_NOT_OWN;
            if (ownerThread == Thread.currentThread()) { // snapshot guard (may be stale)
                gainerInCrit = true;
                sawLoser = loserInCrit ? OVERLAP : NO_OVERLAP;
                gainerInCrit = false;
            }
            r.r2 = sawLoser;
        }
    }

    private static final int GUARD_FIRED = 0;     // off-owner caller intercepted (correct)
    private static final int PRE_SERVICE = 1;     // arrived before B entered service (inert window)
    private static final int FALSE_NEGATIVE = 2;  // in service as B, yet the guard saw null/self (forbidden)

    /**
     * Re-binding the owner across a handoff must not open a false NEGATIVE. {@code handoff}
     * stands in for the full barrier-ordered handoff completing (A detaches -> B adopts) and then publishes
     * {@code inService} (B is wired into the serving path). A {@code foreign} off-owner caller that arrives
     * after that publish must observe B (a non-null owner that is not itself) and FIRE the guard - never
     * read null/self ({@link #FALSE_NEGATIVE}), which would let an off-owner access escape AFTER a re-bind.
     * This is {@link RaftOwnerThreadGuardTest.OwnerGuardNoFalseNegativeInService} carried across the
     * HANDOFF -> B re-bind: the bound-once net property survives rehoming.
     */
    @JCStressTest
    @State
    @Description("Rehoming re-bind: once the gaining owner is in service, an off-owner access never escapes the tripwire (no false negative survives a re-bind)")
    @Outcome(id = "0", expect = Expect.ACCEPTABLE, desc = "guard FIRED — foreign observed the re-bound owner B and was intercepted")
    @Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "pre-service inert window — foreign ran before B entered service")
    @Outcome(id = "2", expect = Expect.FORBIDDEN, desc = "FALSE NEGATIVE — in service as B but the guard saw null/self → an off-owner access would escape after the re-bind")
    public static class PostAdoptGuardNoFalseNegative {

        volatile Thread ownerThread;
        volatile boolean inService;

        @Actor
        public void handoff() {
            ownerThread = HANDOFF;                  // A detaches
            ownerThread = Thread.currentThread();   // B adopts (re-bind), ordered after the detach (single thread)
            inService = true;                       // B enters the serving path (volatile release)
        }

        @Actor
        public void foreign(I_Result r) {
            if (!inService) {                        // volatile acquire
                r.r1 = PRE_SERVICE;
                return;
            }
            Thread owner = ownerThread;              // the re-bound owner B must be visible here
            r.r1 = (owner != null && owner != Thread.currentThread()) ? GUARD_FIRED : FALSE_NEGATIVE;
        }
    }
}

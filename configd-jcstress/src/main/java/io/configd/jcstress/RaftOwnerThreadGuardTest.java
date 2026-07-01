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
 * JMM micro-race complement to {@code RaftNodeConcurrencyStressTest}.
 *
 * <p>The macro stress harness ({@code configd-consensus-core}) drives a real {@code RaftNode}
 * through the guarded entry points and proves the {@code assertOwnerThread()} tripwire FIRES on an
 * off-owner call. What it cannot prove - a plain multi-threaded JUnit test runs on a
 * normally-scheduled JVM - is the underlying <em>memory model</em> property the whole net rests on:
 * that the guard has no <b>false negative</b> under aggressive reordering. That is exactly what
 * jcstress is for. This file pins down two JMM facts:
 *
 * <ol>
 *   <li><b>No false negative once in service</b> ({@link OwnerGuardNoFalseNegativeInService}) - after a node's owner is bound and the node is published into service, an
 *       off-owner caller is <em>always</em> intercepted by the tripwire. The {@code volatile}
 *       publication of {@code ownerThread} (plus the in-service release/acquire that models a node
 *       being wired before it serves) makes a "saw {@code null}/saw-self" read - which would let an
 *       off-owner mutation slip through undetected - unreachable.</li>
 *   <li><b>The race is real, and binding is mandatory</b> ({@link UnboundGuardIsInertAndRaces},
 *       intentionally FORBIDDEN-hitting) - an <em>unbound</em> node's guard
 *       is inert, so two off-owner threads racing its non-volatile consensus state reach a lost
 *       update. This is the corruption the bound guard catches, and the proof that
 *       {@code bindOwnerThread()} must be wired for the net to bite. It is the consensus-framed
 *       twin of {@link HarnessSelfTest.KnownRacyCounter}: a detector you have never seen fire is
 *       not a detector.</li>
 * </ol>
 *
 * <p>Both {@code @State} classes mirror the owner-thread tripwire <b>verbatim</b> from
 * {@code RaftNode.java} - the {@code private volatile Thread ownerThread} field and the
 * {@code assertOwnerThread()} comparison shape - because the property under test is a property of
 * those exact field declarations, not of the surrounding protocol logic. The real {@code RaftNode}
 * integration (binding, every guarded entry point, the marshalling discipline) is covered by the
 * macro harness; here we isolate the concurrency primitive so jcstress can hammer it.
 *
 * @see HarnessSelfTest the test-the-tester precedent (a known-racy gadget paired with a known-safe one)
 */
public final class RaftOwnerThreadGuardTest {

    private RaftOwnerThreadGuardTest() {
    }

    private static final int GUARD_FIRED = 0;
    private static final int PRE_SERVICE_INERT = 1;
    private static final int FALSE_NEGATIVE = 2;

    /**
     * The load-bearing net property: <b>no false negative in service.</b>
     *
     * <p>{@code owner} binds itself as the node's owner (bind is the first task on the owner
     * executor) and then publishes {@code inService = true} - modelling the node being wired into
     * the routing/serving path only after it is bound. {@code foreign} is a mis-marshalled
     * off-owner caller:
     *
     * <ul>
     *   <li>if it arrives <em>before</em> the in-service publish it sees the inert pre-bind window
     *       (acceptable - production binds before serving, so this window is closed in prod);</li>
     *   <li>if it arrives <em>after</em>, the {@code volatile} write of {@code ownerThread}
     *       (program-order before the {@code inService} release) is visible across the matching
     *       acquire, so the guard observes a non-null owner that is not this thread and FIRES.</li>
     * </ul>
     *
     * The {@link #FALSE_NEGATIVE} outcome - in service, yet the guard read {@code null}/self and an
     * off-owner mutation would proceed - is forbidden by the JMM and must never be observed. If
     * jcstress ever reports it, the tripwire has a visibility hole and every "off-owner access is
     * caught" claim downstream is worthless.
     */
    @JCStressTest
    @State
    @Description("R-01' owner-thread guard: once a node is in service, an off-owner access never escapes the tripwire (no false negative)")
    @Outcome(id = "0", expect = Expect.ACCEPTABLE, desc = "guard FIRED — foreign observed the binding and was intercepted")
    @Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "pre-service inert window — foreign ran before the node entered service (H-6; closed in prod)")
    @Outcome(id = "2", expect = Expect.FORBIDDEN, desc = "FALSE NEGATIVE — in service but guard saw null/self → an off-owner mutation would escape undetected")
    public static class OwnerGuardNoFalseNegativeInService {

        // Verbatim mirror of the owner-thread tripwire state (RaftNode.java: private volatile Thread
        // ownerThread). inService is the release/acquire that models the node being published
        // into the serving path AFTER bindOwnerThread() - bind is the first task on the owner.
        volatile Thread ownerThread;
        volatile boolean inService;

        @Actor
        public void owner() {
            ownerThread = Thread.currentThread();   // bind: first task on the owner executor
            inService = true;                       // node enters service (volatile release)
        }

        @Actor
        public void foreign(I_Result r) {
            if (!inService) {                       // volatile acquire
                r.r1 = PRE_SERVICE_INERT;           // arrived before the node served - inert window
                return;
            }
            // In service: assertOwnerThread() must see a non-null owner that is NOT this thread and
            // route the violation through invariantChecker (throw in test/sim, metric in prod). A
            // read of null/self here is the false negative the volatile publication must rule out.
            Thread owner = ownerThread;
            boolean guardFires = owner != null && owner != Thread.currentThread();
            r.r1 = guardFires ? GUARD_FIRED : FALSE_NEGATIVE;
        }
    }

    /**
     * Intentionally FORBIDDEN-hitting ("test the tester"). Proves the hazard is real:
     * an <b>unbound</b> node's guard is inert, so the non-volatile consensus state it is supposed to
     * protect genuinely suffers a lost update when two threads touch it off-owner. This is the exact
     * corruption {@code bindOwnerThread()} + the tripwire prevent - and the reason production
     * without binding is unsafe to re-thread. Run standalone, never in the curated/gate batch
     * (a green run here would be a harness failure, like
     * {@link HarnessSelfTest.KnownRacyCounter}).
     */
    @JCStressTest
    @State
    @Description("R-01' is mandatory: an UNBOUND node's guard is inert, so two off-owner threads race its non-volatile consensus state to a lost update — the race a bound guard catches (NOT in the gate batch)")
    @Outcome(id = "1, 2", expect = Expect.ACCEPTABLE, desc = "serialized a→b")
    @Outcome(id = "2, 1", expect = Expect.ACCEPTABLE, desc = "serialized b→a")
    @Outcome(id = "2, 2", expect = Expect.ACCEPTABLE, desc = "both observed the other's write")
    @Outcome(id = "1, 1", expect = Expect.FORBIDDEN, desc = "LOST UPDATE through the inert (unbound) guard — off-owner consensus-state corruption")
    public static class UnboundGuardIsInertAndRaces {

        // Verbatim field; DELIBERATELY never bound. `consensusState` mirrors a
        // RaftNode non-volatile O-class field (e.g. currentTerm) whose safety rests on single-owner
        // access, not on atomics.
        volatile Thread ownerThread;
        int consensusState;

        /** Mirror of assertOwnerThread() + a guarded RMW, with NO bind (the guard is inert). */
        private int guardedIncrement() {
            Thread owner = ownerThread;
            if (owner != null && owner != Thread.currentThread()) {
                return -1;                  // would fire if bound - never taken here (unbound)
            }
            return ++consensusState;        // non-atomic RMW reached through the inert guard
        }

        @Actor
        public void a(II_Result r) {
            r.r1 = guardedIncrement();
        }

        @Actor
        public void b(II_Result r) {
            r.r2 = guardedIncrement();
        }
    }
}

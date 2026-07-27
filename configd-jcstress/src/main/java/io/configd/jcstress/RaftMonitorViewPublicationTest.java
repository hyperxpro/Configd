package io.configd.jcstress;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

/**
 * Pins the JMM micro-race behind the monitoring snapshot publication.
 *
 * <p>Once owners are bound and {@code tick()} fans out, a metrics scrape no longer runs on the
 * group's owner, so the old direct reads of non-volatile consensus state
 * ({@code currentTerm}, {@code log().commitIndex()}, ...) become off-owner races. The resolution
 * ({@code RaftNode.monitorView()}) is the same primitive {@link VersionedConfigStoreReadTest} relies
 * on: the owner publishes an <b>immutable snapshot</b> through a <b>single volatile reference</b>, and
 * any thread reads it with one volatile load. The macro proof (a real {@code RaftNode} +
 * {@code RaftMonitorViewConcurrencyTest}) shows coherence end-to-end; this file pins the underlying
 * memory-model fact: <b>a multi-field snapshot published this way can never be observed torn</b> - and,
 * via the control, that the immutable-record discipline is what makes it so.
 *
 * <p>Both states mirror the mechanism (a {@code volatile} reference to an immutable carrier vs. plain
 * per-field publication), not the whole {@code RaftMetrics} record - the property under test is a
 * property of the publication primitive, exactly as {@link RaftOwnerThreadGuardTest} mirrors the
 * tripwire field rather than importing {@code RaftNode}.
 */
public final class RaftMonitorViewPublicationTest {

    private RaftMonitorViewPublicationTest() {
    }

    private static final int COHERENT = 1;
    private static final int TORN = 99;

    /** Immutable multi-field carrier - the {@code RaftMetrics} stand-in. By construction every instance
     *  satisfies {@code b == a + 1 && c == a + 2}; a reader that ever sees that relation broken has
     *  observed a torn read. */
    static final class View {
        final long a;
        final long b;
        final long c;
        View(long a) { this.a = a; this.b = a + 1; this.c = a + 2; }
        boolean coherent() { return b == a + 1 && c == a + 2; }
    }

    /**
     * The load-bearing property: an immutable snapshot published through one
     * {@code volatile} reference is <b>never observed torn</b>. The owner republishes a new {@code View}
     * (the end-of-tick {@code publishMonitorView()}); the foreign reader does one volatile load and
     * checks the carrier's internal relation. It can only ever see the seed or the published instance -
     * each internally coherent - so {@link #TORN} is JMM-unreachable. If jcstress ever reports TORN, the
     * monitorView() publication has a visibility hole and every "monitoring read is coherent" claim
     * downstream is worthless.
     */
    @JCStressTest
    @State
    @Description("H-3: an immutable snapshot published via a single volatile ref is never observed torn (monitorView())")
    @Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "coherent snapshot — saw the seed or the republished view whole")
    @Outcome(expect = Expect.FORBIDDEN, desc = "TORN — fields spliced across publications; the volatile/immutable publish leaked")
    public static class PublishedSnapshotNeverTears {
        volatile View view = new View(1L);

        @Actor
        public void owner() {
            view = new View(2L);
        }

        @Actor
        public void reader(I_Result r) {
            View v = view;
            r.r1 = v.coherent() ? COHERENT : TORN;
        }
    }

    /**
     * Intentionally tear-exposing ("test the tester"): proves the immutable-record discipline
     * is load-bearing. Here the same three fields are published <b>per-field</b> (plain writes, no
     * immutable carrier, no single-reference publish). A reader can observe a later field updated while an
     * earlier one is not - the relation breaks - so {@link #TORN} is an <b>expected</b> outcome. This is
     * the corruption the published-immutable-snapshot prevents, and the reason monitors must read
     * {@code monitorView()} rather than a bag of separately-updated fields. Excluded from the gate batch
     * (a tear here is correct), like {@link RaftOwnerThreadGuardTest.UnboundGuardIsInertAndRaces} and
     * {@link HarnessSelfTest.KnownRacyCounter}.
     */
    @JCStressTest
    @State
    @Description("H-3 control: per-field (non-snapshot) publication CAN tear — proves the immutable-snapshot discipline is necessary (NOT in the gate batch)")
    @Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "happened to read a coherent set of fields")
    @Outcome(id = "99", expect = Expect.ACCEPTABLE, desc = "TORN — read a later field's new value with an earlier field's old value (the hazard, demonstrated)")
    public static class PerFieldPublishCanTear {
        long a = 1L;
        long b = 2L;
        long c = 3L;

        @Actor
        public void owner() {
            a = 2L;
            b = 3L;
            c = 4L;
        }

        @Actor
        public void reader(I_Result r) {
            long rc = c;                     // read in the OPPOSITE order so a late write is visible before an early one
            long rb = b;
            long ra = a;
            r.r1 = (rb == ra + 1 && rc == ra + 2) ? COHERENT : TORN;
        }
    }
}

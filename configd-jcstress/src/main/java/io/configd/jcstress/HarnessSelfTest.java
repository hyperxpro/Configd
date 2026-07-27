package io.configd.jcstress;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Test-the-tester: prove the jcstress harness actually DETECTS a race before we
 * trust any of its verdicts on the real structures. This file pairs a known-racy
 * gadget (must surface a FORBIDDEN interleaving) with a known-safe one (must be
 * clean). If the racy case ever reports {@code ACCEPTABLE_INTERESTING}/no
 * forbidden, the harness is mis-wired and every "no race" verdict downstream is
 * worthless.
 *
 * <p>This is the jcstress analogue of the assertion-twin "OBSERVED firing"
 * discipline: a detector you have never seen fire is not a detector.
 */
public final class HarnessSelfTest {

    private HarnessSelfTest() {
    }

    /**
     * KNOWN-RACY. Two actors each do a non-atomic read-modify-write
     * ({@code x++}) on the same plain {@code int}. A correct race detector MUST
     * observe the lost-update interleaving where both read 0 and both write 1,
     * so the pair {@code (1, 1)} is reachable. We mark {@code (1, 1)} FORBIDDEN:
     * a green jcstress run on this class is therefore a FAILURE of the harness
     * self-test, and a jcstress run that reports the forbidden outcome proves the
     * detector works. (Run standalone, not in the curated/full gate batch.)
     */
    @JCStressTest
    @Outcome(id = "1, 2", expect = Expect.ACCEPTABLE, desc = "actor1 then actor2 (serialized)")
    @Outcome(id = "2, 1", expect = Expect.ACCEPTABLE, desc = "actor2 then actor1 (serialized)")
    @Outcome(id = "2, 2", expect = Expect.ACCEPTABLE, desc = "both observe each other's write")
    @Outcome(id = "1, 1", expect = Expect.FORBIDDEN, desc = "LOST UPDATE — the race we must catch")
    @State
    public static class KnownRacyCounter {
        int x;

        @Actor
        public void actor1(II_Result r) {
            r.r1 = ++x;
        }

        @Actor
        public void actor2(II_Result r) {
            r.r2 = ++x;
        }
    }

    /**
     * KNOWN-SAFE. The same shape but each actor owns a disjoint field, so there
     * is no shared mutable state and only the serialized outcome {@code (1, 1)}
     * is reachable. A correct harness reports this clean (zero forbidden). Pairs
     * with {@link KnownRacyCounter} to bound the self-test from both sides: the
     * detector fires on a real race AND stays silent on a non-race.
     */
    @JCStressTest
    @Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "disjoint fields — only legal outcome")
    @Outcome(expect = Expect.FORBIDDEN, desc = "any other value is impossible")
    @State
    public static class KnownSafeDisjoint {
        int a;
        int b;

        @Actor
        public void actor1(II_Result r) {
            r.r1 = ++a;
        }

        @Actor
        public void actor2(II_Result r) {
            r.r2 = ++b;
        }
    }
}

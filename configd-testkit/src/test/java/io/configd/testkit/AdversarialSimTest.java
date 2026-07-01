package io.configd.testkit;

import io.configd.raft.RaftLog;
import io.configd.store.VersionedConfigStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the section 4.3 adversarial simulation: faults inject, invariants hold (no
 * safety violation across a batch of seeds), the run is byte-replayable by seed
 * alone (determinism survives the new fault machinery), and the run does
 * real work (activity predicate). A safety violation, were one to occur,
 * would surface as a thrown {@link SimInvariants.SafetyViolation} that fails the
 * seed with replay context.
 */
class AdversarialSimTest {

    private static final int NODES = 5;
    private static final int TICKS = 1_500;

    /**
     * Per-fault-class smoke: a single representative seed exercises the whole
     * adversarial configuration (reorder/drop/dup/delay/partition/crash-arm +
     * concurrent workload) and completes without any safety violation. This is the
     * one-line proof that the fault layer runs end-to-end under continuous checking.
     */
    @Test
    void adversarialRunCompletesWithoutSafetyViolation() {
        AdversarialSim sim = new AdversarialSim(20260611L, NODES, TICKS);
        assertDoesNotThrow(sim::run,
                "Adversarial run must not raise a safety violation on a clean system");
        // The schedule actually contained faults and the run did work.
        assertFalse(sim.schedule().events().isEmpty(), "schedule must contain faults");
        assertTrue(sim.activity().faultsFired() > 0, "faults must have fired");
        assertTrue(sim.activity().leaderElected(),
                "a leader should elect despite faults (else record as liveness stall)");
    }

    /**
     * Safety across a batch: many seeds, all faults, every tick checked. Zero
     * safety violations expected; any violation fails the offending seed by name.
     */
    @Test
    void batchOfSeedsHoldsAllInvariants() {
        int batch = Integer.getInteger("configd.adversarial.batch", 60);
        int leaderElected = 0;
        for (long seed = 0; seed < batch; seed++) {
            AdversarialSim sim = new AdversarialSim(seed, NODES, TICKS);
            sim.run(); // throws SafetyViolation (with seed) on any breach
            if (sim.activity().leaderElected()) {
                leaderElected++;
            }
        }
        // Liveness is reported, not asserted per-seed, but a healthy fraction must
        // elect - an all-stall result would mean the faults are pathological.
        double electRate = (double) leaderElected / batch;
        assertTrue(electRate >= 0.5,
                "Most seeds should still elect under faults (liveness sanity); got "
                        + leaderElected + "/" + batch);
    }

    /**
     * Determinism under faults: the same seed produces a byte-identical
     * execution schedule even with all fault machinery active. This is the
     * foundation - if any new fault source broke same-seed-same-schedule it would
     * be a bug in the new code.
     */
    @Test
    void sameSeedProducesIdenticalScheduleUnderFaults() {
        long seed = 987_654_321L;
        String d1 = digestOfRun(seed);
        String d2 = digestOfRun(seed);
        assertEquals(d1, d2,
                "Adversarial sim must be deterministic under faults (seed=" + seed
                        + "): " + d1 + " vs " + d2);

        // And a different seed must diverge (digest is not vacuously constant).
        assertNotEquals(d1, digestOfRun(seed + 1),
                "Distinct seeds must produce distinct adversarial schedules");
    }

    /**
     * The full-scale adversarial sweep (charter exit criterion). Gated on
     * {@code -Dconfigd.adversarial.nightly=true} so it is not part of the normal
     * suite; the count defaults to 10,000. Prints runtime, election rate, and the
     * count of recorded liveness stalls (reported, never failed). Any safety
     * violation throws with the offending seed.
     */
    @Test
    @EnabledIfSystemProperty(named = "configd.adversarial.nightly", matches = "true")
    void nightlyAdversarialSweep() {
        int count = Integer.getInteger("configd.adversarial.sweepCount", 10_000);
        long start = System.nanoTime();
        int leaderElected = 0;
        int livenessStalls = 0;
        long faults = 0;
        for (long seed = 0; seed < count; seed++) {
            AdversarialSim sim = new AdversarialSim(seed, NODES, TICKS);
            sim.run(); // throws SafetyViolation (with seed) on any breach
            faults += sim.activity().faultsFired();
            if (sim.activity().leaderElected()) {
                leaderElected++;
            } else {
                livenessStalls++; // recorded, not failed
            }
        }
        double secs = (System.nanoTime() - start) / 1e9;
        System.out.printf(
                "[nightly-adversarial] seeds=%d wall=%.1fs (%.2fms/seed) elected=%d"
                        + " livenessStalls=%d totalFaults=%d safetyViolations=0%n",
                count, secs, secs * 1000 / count, leaderElected, livenessStalls, faults);
        // Liveness stalls are reported, not failed; safety violations would have
        // already thrown. Sanity: the sweep did real work.
        assertTrue(faults > 0, "sweep must have injected faults");
    }

    private static String digestOfRun(long seed) {
        AdversarialSim sim = new AdversarialSim(seed, NODES, TICKS);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        ByteArrayOutputStream scratch = new ByteArrayOutputStream(64);
        DataOutputStream dos = new DataOutputStream(scratch);

        for (int t = 0; t < TICKS; t++) {
            sim.tick();
            try {
                dos.writeInt(t);
                for (int i = 0; i < NODES; i++) {
                    dos.writeInt(sim.node(i).role().ordinal());
                    dos.writeLong(sim.node(i).currentTerm());
                    var leader = sim.node(i).leaderId();
                    dos.writeInt(leader == null ? -1 : leader.id());
                    RaftLog log = sim.log(i);
                    dos.writeLong(log.lastIndex());
                    dos.writeLong(log.commitIndex());
                    dos.writeLong(log.lastApplied());
                    VersionedConfigStore store = sim.store(i);
                    dos.writeLong(store.currentVersion());
                }
                dos.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            md.update(scratch.toByteArray());
            scratch.reset();
        }
        return HexFormat.of().formatHex(md.digest());
    }
}

package io.configd.testkit;

import io.configd.raft.RaftLog;
import io.configd.store.VersionedConfigStore;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The simulation must be deterministic - 
 * the same master seed must produce a byte-identical execution schedule.
 * <p>
 * The harness claims "same seed = same execution" (see {@link RaftSimulation}
 * and {@link ConsistencyPropertyTests}), but every RNG consumer must actually
 * be derived from the master seed for that to hold: if any per-node RNG (e.g.
 * the election-timeout RNG) were constructed independently of the seed, election
 * timeouts - and therefore the whole election schedule - would diverge run to run
 * even at a fixed seed, and a failing seed would be unreplayable.
 * <p>
 * This test runs the identical scenario twice in-process under the same seed,
 * folds the full per-tick observable state of every node (role, term, leader,
 * log indices, store version) into a SHA-256 digest, and asserts the two
 * digests are identical. The per-tick fold makes the digest sensitive to
 * election-timeout choices: a divergence in any node's election timeout shifts
 * a role/term transition to a different tick, which changes the digest.
 * <p>
 * It deliberately does <em>not</em> mock anything - it exercises the real
 * {@link RaftSimulation} wiring used by {@link ConsistencyPropertyTests} and
 * {@code SeedSweepTest}.
 */
class SimulationDeterminismTest {

    /**
     * Number of nodes - 5 matches the safety sweep ({@code SeedSweepTest}),
     * the configuration most sensitive to election-timeout jitter.
     */
    private static final int NODE_COUNT = 5;

    /**
     * Total ticks per run. Long enough to drive several election timeouts and
     * at least one failover, so that divergence in the election RNG manifests.
     */
    private static final int TICKS = 1_500;

    @Test
    void sameSeedProducesIdenticalSchedule() {
        long seed = 123_456_789L;

        String digest1 = runScenarioDigest(seed);
        String digest2 = runScenarioDigest(seed);

        assertEquals(digest1, digest2,
                "RR-010: simulation is not deterministic — same seed (" + seed
                        + ") produced divergent execution schedules.\n"
                        + "  run #1 digest = " + digest1 + "\n"
                        + "  run #2 digest = " + digest2 + "\n"
                        + "Every RNG consumer in the sim (including the per-node election"
                        + " timeout RNG) must be derived from the master seed.");
    }

    /**
     * A second seed, to confirm the property holds generally (and that the
     * two seeds genuinely diverge from each other - i.e. the digest is not a
     * constant that would pass vacuously).
     */
    @Test
    void distinctSeedsAreReplayableAndDiffer() {
        String a1 = runScenarioDigest(2L);
        String a2 = runScenarioDigest(2L);
        String b1 = runScenarioDigest(7L);
        String b2 = runScenarioDigest(7L);

        assertEquals(a1, a2, "RR-010: seed 2 must replay identically");
        assertEquals(b1, b2, "RR-010: seed 7 must replay identically");
        // Guard against a degenerate all-constant digest: two different seeds
        // must drive observably different schedules.
        org.junit.jupiter.api.Assertions.assertNotEquals(a1, b1,
                "Distinct seeds must produce distinct schedules (digest is not vacuous)");
    }

    /**
     * Runs one full scenario under {@code seed} and returns a hex SHA-256
     * digest of the entire per-tick execution schedule.
     * <p>
     * The scenario is fixed and seed-independent in structure (elect, write,
     * isolate the leader, let a new leader emerge, keep ticking) so the only
     * source of run-to-run variation is the RNG - exactly what we are testing.
     */
    private static String runScenarioDigest(long seed) {
        ConsistencyPropertyTests.ClusterHarness cluster =
                new ConsistencyPropertyTests.ClusterHarness(seed, NODE_COUNT);

        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 unavailable", e);
        }

        ByteArrayOutputStream scratch = new ByteArrayOutputStream(64);
        DataOutputStream dos = new DataOutputStream(scratch);

        int isolateAt = TICKS / 3; // deterministic, structure-only fault point
        for (int t = 0; t < TICKS; t++) {
            cluster.tick();

            // Drive a couple of proposals once a leader exists, so commit/apply
            // progression also enters the digest.
            if (t == TICKS / 6) {
                int leader = cluster.findLeader();
                if (leader >= 0) {
                    cluster.proposePut(leader, "det-key", "det-val");
                }
            }
            if (t == isolateAt) {
                int leader = cluster.findLeader();
                if (leader >= 0) {
                    cluster.sim().isolateNode(io.configd.common.NodeId.of(leader));
                }
            }

            try {
                dos.writeInt(t);
                for (int i = 0; i < NODE_COUNT; i++) {
                    dos.writeInt(cluster.node(i).role().ordinal());
                    dos.writeLong(cluster.node(i).currentTerm());
                    var leaderId = cluster.node(i).leaderId();
                    dos.writeInt(leaderId == null ? -1 : leaderId.id());
                    RaftLog log = cluster.log(i);
                    dos.writeLong(log.lastIndex());
                    dos.writeLong(log.commitIndex());
                    dos.writeLong(log.lastApplied());
                    VersionedConfigStore store = cluster.store(i);
                    dos.writeLong(store.currentVersion());
                }
                dos.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e); // ByteArrayOutputStream cannot throw
            }
            md.update(scratch.toByteArray());
            scratch.reset();
        }

        return HexFormat.of().formatHex(md.digest());
    }
}

package io.configd.testkit;

import io.configd.raft.RaftLog;

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
 * The load-bearing non-perturbation guarantee (charter "reuse, never fork"): the
 * edge machinery must NOT change the control-plane execution. For three fixed
 * seeds this captures the CP-side digest of a plain {@link AdversarialSim} and
 * asserts it is <b>byte-identical</b> to the CP-side digest of an
 * {@link EdgeFanOutSim} with (a) 0 edges and (b) 3 edges + {@link StreamDriver#NONE}.
 * <p>
 * If this passes, the per-CP-node {@link io.configd.distribution.FanOutBuffer}
 * listener wiring, the second {@link AdversarialNetwork} (seeded from a NEW mixSeed
 * tag), and the edge fault sub-stream provably do not perturb the committed
 * 507-seed adversarial gate set - the existing gate stays valid unchanged.
 */
class EdgeSeedCompatTest {

    private static final int CP_NODES = 5;
    private static final int TICKS = 1_200;
    private static final long[] SEEDS = {4242L, 123_456_789L, 0L};

    @Test
    void edgeMachineryDoesNotPerturbControlPlaneDigest() {
        for (long seed : SEEDS) {
            String plain = plainCpDigest(seed);
            String zeroEdges = edgeSimCpDigest(seed, 0);
            String threeEdges = edgeSimCpDigest(seed, 3);

            assertEquals(plain, zeroEdges,
                    "EdgeFanOutSim with 0 edges must produce a BYTE-IDENTICAL CP digest"
                            + " to plain AdversarialSim (seed=" + seed + ")");
            assertEquals(plain, threeEdges,
                    "EdgeFanOutSim with 3 edges + StreamDriver.NONE must produce a"
                            + " BYTE-IDENTICAL CP digest (the edge plane must not perturb"
                            + " the committed gate set) (seed=" + seed + ")");
        }
    }

    /** CP digest of a plain AdversarialSim (the committed gate behavior). */
    private static String plainCpDigest(long seed) {
        AdversarialSim sim = new AdversarialSim(seed, CP_NODES, TICKS);
        return foldCp(seed, () -> { sim.tick(); return sim; });
    }

    /** CP digest of an EdgeFanOutSim, folding ONLY the CP state. */
    private static String edgeSimCpDigest(long seed, int edges) {
        EdgeFanOutSim sim = new EdgeFanOutSim(seed, CP_NODES, edges, TICKS);
        return foldCp(seed, () -> { sim.tick(); return sim.cpSim(); });
    }

    /** Functional supplier of the CP view after one tick. */
    private interface Stepper { AdversarialSim step(); }

    /**
     * Folds the per-tick CP state (role/term/leader/log indices/store version per
     * node) into a SHA-256 digest - the SAME shape both runs use, so any CP
     * divergence shows as a digest mismatch.
     */
    private static String foldCp(long seed, Stepper stepper) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        ByteArrayOutputStream scratch = new ByteArrayOutputStream(64);
        DataOutputStream dos = new DataOutputStream(scratch);
        for (int t = 0; t < TICKS; t++) {
            AdversarialSim cp = stepper.step();
            try {
                dos.writeInt(t);
                for (int i = 0; i < CP_NODES; i++) {
                    dos.writeInt(cp.node(i).role().ordinal());
                    dos.writeLong(cp.node(i).currentTerm());
                    var leader = cp.node(i).leaderId();
                    dos.writeInt(leader == null ? -1 : leader.id());
                    RaftLog log = cp.log(i);
                    dos.writeLong(log.lastIndex());
                    dos.writeLong(log.commitIndex());
                    dos.writeLong(log.lastApplied());
                    dos.writeLong(cp.store(i).currentVersion());
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

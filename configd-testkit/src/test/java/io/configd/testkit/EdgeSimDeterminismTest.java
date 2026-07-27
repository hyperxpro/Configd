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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Determinism for {@link EdgeFanOutSim} (extended to the edge plane): the
 * same seed must produce a byte-identical execution - CP state AND edge state - and
 * distinct seeds must differ (non-vacuity).
 *
 * <h2>Digest scope</h2>
 * The per-tick fold folds, deterministically, the state that proves replayability of the
 * combined CP+edge machine, and DELIBERATELY OMITS state that is either redundant or not a
 * determinism signal:
 * <ul>
 *   <li><b>Folded - CP per node:</b> {@code role.ordinal}, {@code currentTerm},
 *       {@code leaderId}, {@code log.lastIndex/commitIndex/lastApplied}, and
 *       {@code store.currentVersion()} - the same shape {@code SimulationDeterminismTest} /
 *       {@code AdversarialSimTest} use (CP control-flow + applied version).</li>
 *   <li><b>Folded - edge per actor:</b> {@code incarnation}, {@code cursor},
 *       {@code currentVersion}, {@code staleness().ordinal()}, {@code inboxSize()} - the
 *       observable that captures every crash/restart/lag/partition transition and
 *       every apply.</li>
 *   <li><b>Deliberately OMITTED:</b> per-key store <em>value bytes</em> (a deterministic
 *       function of the identical command stream, so version identity already implies them
 *       -  folding bytes would only slow the digest); the <em>commit timestamps</em> (the
 *       skewed-clock surface - they are an observability signal, not a determinism one,
 *       and a future skew-config change must NOT spuriously flip this digest); and the
 *       edge-network <em>message schedule</em> (an internal detail; its EFFECT shows in
 *       cursor/inbox/version, which ARE folded). The digest thus proves "same seed => same
 *       CP+edge trajectory and applied state", which is what the replayability guarantee
 *       requires, without over- or under-claiming.</li>
 * </ul>
 *
 * <p>Edge faults are ON so the crash/restart/lag/partition paths enter the digest - any
 * non-seed-derived edge randomness would diverge the two runs. A second variant runs the
 * same fold with the real {@link C1StreamDriver} so the live-drain path is also proven
 * deterministic (same seed twice => identical digest).
 */
class EdgeSimDeterminismTest {

    private static final int CP_NODES = 5;
    private static final int EDGES = 3;
    private static final int TICKS = 1_200;

    @Test
    void sameSeedProducesIdenticalEdgeExecution() {
        long seed = 123_456_789L;
        String d1 = digest(seed, StreamDriver.NONE);
        String d2 = digest(seed, StreamDriver.NONE);
        assertEquals(d1, d2,
                "EdgeFanOutSim must be deterministic under faults (seed=" + seed + "): "
                        + d1 + " vs " + d2);
    }

    @Test
    void distinctSeedsProduceDistinctEdgeExecutions() {
        String a1 = digest(2L, StreamDriver.NONE);
        String a2 = digest(2L, StreamDriver.NONE);
        String b1 = digest(7L, StreamDriver.NONE);
        assertEquals(a1, a2, "seed 2 must replay identically");
        assertNotEquals(a1, b1,
                "distinct seeds must drive distinct edge executions (digest is not vacuous)");
    }

    /**
     * The live-drain variant: the same seed run twice with the real {@link C1StreamDriver}
     * must produce a byte-identical digest, AND it must DIFFER from the {@link StreamDriver#NONE}
     * digest (the C1 driver actually delivers - proving the digest sees the drain). The
     * driver is stateful per run, so a fresh instance is supplied for each replay.
     */
    @Test
    void c1DriverIsDeterministicAndDeliversDistinctlyFromNone() {
        long seed = 987_654_321L;
        String c1a = digest(seed, new C1StreamDriver());
        String c1b = digest(seed, new C1StreamDriver());
        assertEquals(c1a, c1b,
                "EdgeFanOutSim with the C1 driver must replay byte-identically (seed=" + seed + ")");
        String none = digest(seed, StreamDriver.NONE);
        assertNotEquals(c1a, none,
                "the C1 driver delivers, so its digest must differ from StreamDriver.NONE "
                        + "(non-vacuity: the drain is observed in the digest)");
    }

    private static String digest(long seed, StreamDriver driver) {
        EdgeFanOutSim sim = new EdgeFanOutSim(seed, CP_NODES, EDGES, TICKS,
                /* edgeFaults */ true, driver,
                AdversarialSchedule.defaultIntensity(), EdgeInvariants.BOUND_MS);

        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        ByteArrayOutputStream scratch = new ByteArrayOutputStream(128);
        DataOutputStream dos = new DataOutputStream(scratch);

        AdversarialSim cp = sim.cpSim();
        for (int t = 0; t < TICKS; t++) {
            sim.tick();
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
                for (EdgeActor edge : sim.edges()) {
                    dos.writeInt(edge.incarnation());
                    dos.writeLong(edge.cursor());
                    dos.writeLong(edge.currentVersion());
                    dos.writeInt(edge.staleness().ordinal());
                    dos.writeInt(edge.inboxSize());
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

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
 * Determinism for {@link EdgeFanOutSim} (RR-010 extended to the edge plane): the
 * same seed must produce a byte-identical execution — CP state AND edge state — and
 * distinct seeds must differ (non-vacuity).
 * <p>
 * The per-tick fold reuses {@link SimulationDeterminismTest}'s CP digest shape
 * (role/term/leader/log indices/store version per CP node) and adds, per edge, the
 * Phase V1 observable: (incarnation, cursor, store version, staleness-state ordinal,
 * inbox size). Edge faults are ON so the crash/restart/lag/partition paths enter the
 * digest — any non-seed-derived edge randomness would diverge the two runs.
 */
class EdgeSimDeterminismTest {

    private static final int CP_NODES = 5;
    private static final int EDGES = 3;
    private static final int TICKS = 1_200;

    @Test
    void sameSeedProducesIdenticalEdgeExecution() {
        long seed = 123_456_789L;
        String d1 = digest(seed);
        String d2 = digest(seed);
        assertEquals(d1, d2,
                "EdgeFanOutSim must be deterministic under faults (seed=" + seed + "): "
                        + d1 + " vs " + d2);
    }

    @Test
    void distinctSeedsProduceDistinctEdgeExecutions() {
        String a1 = digest(2L);
        String a2 = digest(2L);
        String b1 = digest(7L);
        assertEquals(a1, a2, "seed 2 must replay identically");
        assertNotEquals(a1, b1,
                "distinct seeds must drive distinct edge executions (digest is not vacuous)");
    }

    private static String digest(long seed) {
        // Edge faults ON + DirectInjectionDriver.NONE-equivalent: we use the real
        // StreamDriver.NONE so the digest captures the honest (no-delivery) state
        // plus all edge crash/restart/lag/partition transitions.
        EdgeFanOutSim sim = new EdgeFanOutSim(seed, CP_NODES, EDGES, TICKS,
                /* edgeFaults */ true, StreamDriver.NONE,
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
                // CP state — same fold as SimulationDeterminismTest / AdversarialSimTest.
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
                // Edge state — the Phase V1 observable per edge.
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

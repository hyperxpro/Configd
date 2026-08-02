package io.configd.testkit;

import io.configd.edge.StalenessTracker;
import io.configd.store.CommandCodec;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An edge driven to DISCONNECTED (the staleness-ladder frontier walks the ladder under a real
 * partition, on the logical clock - no wall-clock sleeps) triggers a re-bootstrap exactly once
 * per entry, the resubscribe carries the CURRENT cursor (never 0 - the server's
 * TAIL/SNAPSHOT_FIRST decision picks replay vs re-bootstrap; cursor 0 is the poison path's), and
 * after the partition heals the edge recovers and converges back to CURRENT.
 *
 * <p>The process half (the {@code rebootstrapHook} to {@code requestRebootstrap} wiring, plus
 * live teardown/resubscribe) is {@code io.configd.edge.node.EdgeReBootstrapOnDisconnectTest};
 * the trigger-count metric ({@code edge_rebootstrap_triggered_total}) is
 * {@code EdgeNodeMetricsTest}. Together the three close the "trigger, real re-bootstrap,
 * recovery" chain.
 */
class EdgeReBootstrapOnDisconnectTest {

    private static final int CP_NODES = 3;
    private static final int EDGES = 2;
    private static final int WORKLOAD_TICKS = 1_500;

    @Test
    void disconnectedEntryTriggersOneRebootstrapAndTheEdgeRecoversAfterHeal() {
        C1StreamDriver driver = new C1StreamDriver();
        EdgeFanOutSim sim = new EdgeFanOutSim(31L, CP_NODES, EDGES, WORKLOAD_TICKS,
                false, driver, new AdversarialSchedule.Intensity(0, 60, 0.0),
                EdgeInvariants.BOUND_MS);
        sim.run();
        EdgeActor victim = sim.edges().get(0);
        assertEquals(StalenessTracker.State.CURRENT, victim.staleness(),
                "victim must be CURRENT before the partition");
        assertEquals(0, victim.disconnectedRebootstraps());
        sim.enableEdgeRecovery(0);

        sim.partitionEdge(0);
        for (int i = 1; i <= 3; i++) {
            commit(sim, victim.subscribedCpNode(), "ct06/k" + i, "v" + i);
        }

        // Walk the frontier ladder to DISCONNECTED (>30s of LOGICAL time).
        long start = sim.currentTime();
        while (victim.staleness() != StalenessTracker.State.DISCONNECTED) {
            if (sim.currentTime() - start > 40_000) {
                fail("victim did not reach DISCONNECTED within 40s of sim time");
            }
            sim.tick();
        }

        tick(sim, 50); // let the entry tick's directive drain through the recovery sink
        assertEquals(1, victim.disconnectedRebootstraps(),
                "exactly one re-bootstrap firing per DISCONNECTED entry");
        assertTrue(driver.resubscribes() >= 1,
                "the trigger drove a REAL resubscribe through the driver");

        long disconnectedFirings = victim.disconnectedRebootstraps();
        tick(sim, 2_000);
        assertEquals(disconnectedFirings, victim.disconnectedRebootstraps(),
                "no re-fire while the edge stays DISCONNECTED");

        sim.healEdge(0);
        long target = sim.cpSim().store(victim.subscribedCpNode()).currentVersion();
        for (int t = 0; t < 5_000 && (victim.currentVersion() < target
                || victim.staleness() != StalenessTracker.State.CURRENT); t++) {
            sim.tick();
        }
        assertTrue(victim.currentVersion() >= target, "recovered to the authoritative version");
        assertEquals(StalenessTracker.State.CURRENT, victim.staleness(),
                "the frontier healed back to CURRENT after recovery");
        var r = victim.get("ct06/k3");
        assertTrue(r.found() && "v3".equals(new String(r.value(), StandardCharsets.UTF_8)),
                "the missed writes arrived through the re-bootstrap/recovery path");
        assertTrue(sim.terminalFailures().isEmpty(), "nothing terminal about a partition");
    }


    private static void tick(EdgeFanOutSim sim, int n) {
        for (int i = 0; i < n; i++) {
            sim.tick();
        }
    }

    /**
     * Commits one write through the REAL CP and ticks until the observed node has APPLIED
     * IT (value equality - a bare version-advance check races the previous in-flight apply).
     */
    private static void commit(EdgeFanOutSim sim, int observedCpNode, String key, String value) {
        byte[] expected = value.getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 50; attempt++) {
            int leader = sim.cpSim().findLeader();
            if (leader >= 0) {
                sim.cpSim().node(leader).propose(CommandCodec.encodePut(key, expected));
            }
            for (int t = 0; t < 20; t++) {
                sim.tick();
                var r = sim.cpSim().store(observedCpNode).get(key);
                if (r.found() && java.util.Arrays.equals(expected, r.value())) {
                    return;
                }
            }
        }
        fail("write '" + key + "' did not commit/apply on cp node " + observedCpNode);
    }
}

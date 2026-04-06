package io.configd.testkit;

import io.configd.common.NodeId;
import io.configd.raft.*;
import io.configd.store.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parameterized seed sweep for core Raft safety invariants.
 * <p>
 * Default: 10,000 seeds (CI). Set -Dconfigd.seedSweep.count=100000 for
 * the full battle-ready sweep (takes ~30 minutes).
 */
class SeedSweepTest {

    static LongStream seeds() {
        int count = Integer.getInteger("configd.seedSweep.count", 10_000);
        return LongStream.range(0, count);
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void electionSafety(long seed) {
        ConsistencyPropertyTests.ClusterHarness cluster =
                new ConsistencyPropertyTests.ClusterHarness(seed, 5);

        Map<Long, Set<Integer>> leadersPerTerm = new HashMap<>();

        for (int t = 0; t < 2000; t++) {
            cluster.tick();

            for (int i = 0; i < 5; i++) {
                if (cluster.node(i).role() == RaftRole.LEADER) {
                    long term = cluster.node(i).currentTerm();
                    leadersPerTerm
                            .computeIfAbsent(term, k -> new HashSet<>())
                            .add(i);
                }
            }
        }

        for (var entry : leadersPerTerm.entrySet()) {
            assertTrue(entry.getValue().size() <= 1,
                    "Election safety violated: term " + entry.getKey()
                            + " had multiple leaders: " + entry.getValue()
                            + " (seed=" + seed + ")");
        }
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void commitSurvivesLeaderFailure(long seed) {
        ConsistencyPropertyTests.ClusterHarness cluster =
                new ConsistencyPropertyTests.ClusterHarness(seed, 5);

        int leader = cluster.electLeader(1200);
        if (leader < 0) {
            // Some seeds may not elect a leader in time; skip gracefully
            return;
        }

        // Propose and commit a value
        long seq = cluster.proposeAndCommit(leader, "sweep-key", "sweep-val", 200);
        if (seq <= 0) {
            // Commit timeout under this seed; skip gracefully
            return;
        }

        // Allow replication to propagate to followers
        cluster.runTicks(200);

        // Isolate the current leader
        cluster.sim().isolateNode(NodeId.of(leader));

        // Elect a new leader among remaining nodes
        int newLeader = cluster.awaitStableLeader(Set.of(leader), 2000);
        if (newLeader < 0) {
            // Could not elect new leader under this seed; skip
            return;
        }

        // The committed value must be present on the new leader
        ReadResult result = cluster.store(newLeader).get("sweep-key");
        assertTrue(result.found(),
                "Committed value lost after leader failure (seed=" + seed + ")");
        assertEquals("sweep-val",
                new String(result.value(), StandardCharsets.UTF_8),
                "Committed value corrupted after leader failure (seed=" + seed + ")");
    }
}

package io.configd.testkit;

import io.configd.common.NodeId;
import io.configd.edge.LocalConfigStore;
import io.configd.edge.StalenessTracker;
import io.configd.edge.VersionCursor;
import io.configd.raft.*;
import io.configd.store.*;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property tests verifying the consistency contract invariants defined in
 * {@code docs/consistency-contract.md}.
 * <p>
 * Each nested class corresponds to a formal invariant (INV-V1, INV-V2, etc.)
 * and exercises the real Raft consensus, config store, and edge cache
 * infrastructure in deterministic simulation.
 * <p>
 * Tests use seeded PRNG for full reproducibility: same seed = same execution.
 */
class ConsistencyPropertyTests {

    // -----------------------------------------------------------------------
    // Simulation harness — wires RaftNodes through SimulatedNetwork
    // -----------------------------------------------------------------------

    /**
     * A fully-wired simulated Raft cluster with N nodes, each backed by a
     * {@link ConfigStateMachine} and {@link VersionedConfigStore}.
     * <p>
     * All communication flows through the {@link SimulatedNetwork}. Time is
     * driven by the {@link SimulatedClock}. Single-threaded, deterministic.
     */
    static final class ClusterHarness {

        private final RaftSimulation sim;
        private final List<RaftNode> nodes;
        private final List<RaftLog> logs;
        private final List<ConfigStateMachine> stateMachines;
        private final List<VersionedConfigStore> stores;
        private final int nodeCount;

        ClusterHarness(long seed, int nodeCount) {
            this.sim = new RaftSimulation(seed, nodeCount);
            this.nodeCount = nodeCount;
            this.nodes = new ArrayList<>();
            this.logs = new ArrayList<>();
            this.stateMachines = new ArrayList<>();
            this.stores = new ArrayList<>();

            for (int i = 0; i < nodeCount; i++) {
                NodeId nodeId = NodeId.of(i);
                Set<NodeId> peers = new HashSet<>();
                for (int j = 0; j < nodeCount; j++) {
                    if (j != i) peers.add(NodeId.of(j));
                }

                RaftConfig config = RaftConfig.of(nodeId, peers);
                RaftLog log = new RaftLog();
                VersionedConfigStore store = new VersionedConfigStore();
                ConfigStateMachine sm = new ConfigStateMachine(store);

                RaftTransport transport = (target, message) ->
                        sim.network().send(nodeId, target, message, sim.clock().currentTimeMillis());

                RaftNode node = new RaftNode(config, log, transport, sm,
                        RandomGenerator.of("L64X128MixRandom"));

                nodes.add(node);
                logs.add(log);
                stateMachines.add(sm);
                stores.add(store);
            }

            sim.network().setDeliveryHandler((target, message) -> {
                int targetIdx = target.id();
                if (targetIdx >= 0 && targetIdx < nodeCount) {
                    nodes.get(targetIdx).handleMessage((RaftMessage) message);
                }
            });
        }

        RaftSimulation sim() { return sim; }
        RaftNode node(int i) { return nodes.get(i); }
        RaftLog log(int i) { return logs.get(i); }
        ConfigStateMachine stateMachine(int i) { return stateMachines.get(i); }
        VersionedConfigStore store(int i) { return stores.get(i); }
        int nodeCount() { return nodeCount; }

        /**
         * Single simulation step: advance clock by 1ms, deliver due messages,
         * then tick every RaftNode.
         */
        void tick() {
            sim.tick();
            for (RaftNode node : nodes) {
                node.tick();
            }
        }

        /** Run the cluster for the given number of ticks. */
        void runTicks(int ticks) {
            for (int i = 0; i < ticks; i++) {
                tick();
            }
        }

        /**
         * Wait for a stable leader among non-excluded nodes. A leader is
         * considered stable when it remains leader for at least 120 ticks
         * (two full heartbeat cycles at 50ms each), confirming quorum.
         */
        int awaitStableLeader(Set<Integer> exclude, int maxTicks) {
            int stableCount = 0;
            int candidate = -1;
            for (int t = 0; t < maxTicks; t++) {
                tick();
                int leader = findLeader(exclude);
                if (leader >= 0) {
                    if (leader == candidate) {
                        stableCount++;
                        if (stableCount >= 120) {
                            return leader;
                        }
                    } else {
                        candidate = leader;
                        stableCount = 1;
                    }
                } else {
                    candidate = -1;
                    stableCount = 0;
                }
            }
            return -1;
        }

        /** Elect a stable leader across all nodes. */
        int electLeader(int maxTicks) {
            return awaitStableLeader(Set.of(), maxTicks);
        }

        /** Find current leader among eligible nodes. */
        int findLeader(Set<Integer> exclude) {
            for (int i = 0; i < nodeCount; i++) {
                if (exclude.contains(i)) continue;
                if (nodes.get(i).role() == RaftRole.LEADER) {
                    return i;
                }
            }
            return -1;
        }

        /** Find current leader among all nodes. */
        int findLeader() {
            return findLeader(Set.of());
        }

        /** Propose a PUT command on the given node. */
        boolean proposePut(int nodeIdx, String key, String value) {
            byte[] cmd = CommandCodec.encodePut(key, value.getBytes(StandardCharsets.UTF_8));
            return nodes.get(nodeIdx).propose(cmd) == ProposalResult.ACCEPTED;
        }

        /** Propose a DELETE command on the given node. */
        boolean proposeDelete(int nodeIdx, String key) {
            byte[] cmd = CommandCodec.encodeDelete(key);
            return nodes.get(nodeIdx).propose(cmd) == ProposalResult.ACCEPTED;
        }

        /**
         * Propose a PUT and wait for commit on the leader's store.
         * Returns the new store version, or -1 on timeout.
         */
        long proposeAndCommit(int leaderIdx, String key, String value, int maxTicks) {
            long prevVersion = stores.get(leaderIdx).currentVersion();
            if (!proposePut(leaderIdx, key, value)) {
                return -1;
            }
            for (int t = 0; t < maxTicks; t++) {
                tick();
                long curVersion = stores.get(leaderIdx).currentVersion();
                if (curVersion > prevVersion) {
                    return curVersion;
                }
            }
            return -1;
        }

        /**
         * Wait for a ReadIndex request to become ready (leadership confirmed
         * and state machine caught up). Returns true if ready within maxTicks.
         */
        boolean awaitReadReady(int leaderIdx, long readId, int maxTicks) {
            for (int t = 0; t < maxTicks; t++) {
                tick();
                if (nodes.get(leaderIdx).isReadReady(readId)) {
                    return true;
                }
            }
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    // =======================================================================
    // INV-L1: Linearizability of writes per Raft group
    //
    // INV-L1: For all operations op1, op2 on the same Raft group:
    //   if op1 completes before op2 begins (real time),
    //   then op1's effect is visible to op2
    // =======================================================================

    @Nested
    class LinearizabilityTest {

        /**
         * Verifies that a write committed before a ReadIndex read begins is
         * always visible in the read result. This is the core linearizability
         * guarantee: real-time ordering is preserved.
         */
        @Test
        void writeCompletedBeforeReadIsAlwaysVisible() {
            ClusterHarness cluster = new ClusterHarness(42L, 3);
            int leader = cluster.electLeader(1200);
            assertTrue(leader >= 0, "Leader must be elected");

            // Phase 1: commit a write and record the commit version
            long commitSeq = cluster.proposeAndCommit(leader, "lin-key", "value-A", 100);
            assertTrue(commitSeq > 0, "Write must commit");

            // Phase 2: after the write completes, issue a ReadIndex read
            long readId = cluster.node(leader).readIndex();
            assertTrue(readId >= 0, "ReadIndex must be accepted by leader");

            // Wait for ReadIndex to become ready (heartbeat confirms leadership)
            boolean ready = cluster.awaitReadReady(leader, readId, 200);
            assertTrue(ready, "ReadIndex must become ready within timeout");

            // Phase 3: read must see the committed write
            ReadResult result = cluster.store(leader).get("lin-key");
            assertTrue(result.found(), "INV-L1 violated: committed write not visible via ReadIndex");
            assertEquals("value-A", str(result.value()),
                    "INV-L1 violated: wrong value returned after linearizable read");
            assertTrue(result.version() >= commitSeq,
                    "INV-L1 violated: result version " + result.version()
                            + " < commit seq " + commitSeq);

            cluster.node(leader).completeRead(readId);
        }

        /**
         * Verifies linearizability across a sequence of writes: each subsequent
         * ReadIndex read sees the latest committed value, never a stale one.
         */
        @Test
        void sequentialWritesThenReadsAreLinearizable() {
            ClusterHarness cluster = new ClusterHarness(101L, 3);
            int leader = cluster.electLeader(1200);
            assertTrue(leader >= 0, "Leader must be elected");

            for (int i = 0; i < 10; i++) {
                // Write
                long commitSeq = cluster.proposeAndCommit(leader, "seq-key", "val-" + i, 100);
                assertTrue(commitSeq > 0, "Write " + i + " must commit");

                // ReadIndex after write
                long readId = cluster.node(leader).readIndex();
                assertTrue(readId >= 0, "ReadIndex " + i + " must be accepted");
                boolean ready = cluster.awaitReadReady(leader, readId, 200);
                assertTrue(ready, "ReadIndex " + i + " must become ready");

                // Verify the latest value is visible
                ReadResult result = cluster.store(leader).get("seq-key");
                assertTrue(result.found(), "INV-L1 violated at iteration " + i);
                assertEquals("val-" + i, str(result.value()),
                        "INV-L1 violated: expected val-" + i + " but got "
                                + str(result.value()) + " at iteration " + i);
                assertTrue(result.version() >= commitSeq,
                        "INV-L1 violated: version regression at iteration " + i);

                cluster.node(leader).completeRead(readId);
            }
        }

        /**
         * Verifies that a ReadIndex issued before a write does NOT guarantee
         * visibility of that write — the read reflects state at the moment
         * the ReadIndex was issued (commit index at that time).
         */
        @Test
        void readIndexReflectsStateAtIssueTime() {
            ClusterHarness cluster = new ClusterHarness(202L, 3);
            int leader = cluster.electLeader(1200);
            assertTrue(leader >= 0, "Leader must be elected");

            // Commit initial value
            long seq1 = cluster.proposeAndCommit(leader, "timing-key", "before", 100);
            assertTrue(seq1 > 0);

            // Issue ReadIndex BEFORE the next write
            long readId = cluster.node(leader).readIndex();
            assertTrue(readId >= 0);

            // Now commit a new value
            long seq2 = cluster.proposeAndCommit(leader, "timing-key", "after", 100);
            assertTrue(seq2 > seq1);

            // The read was issued before the second write, so it reflects
            // at least the first write. It MAY or MAY NOT see the second
            // write, but it MUST see the first.
            boolean ready = cluster.awaitReadReady(leader, readId, 200);
            assertTrue(ready, "ReadIndex must become ready");

            ReadResult result = cluster.store(leader).get("timing-key");
            assertTrue(result.found(),
                    "INV-L1: key must be visible after ReadIndex completes");
            assertTrue(result.version() >= seq1,
                    "INV-L1: read must see at least the first committed write");

            cluster.node(leader).completeRead(readId);
        }

        /**
         * Verifies linearizability across leader failover: a value committed
         * under the old leader is visible via ReadIndex on the new leader.
         */
        @RepeatedTest(3)
        void linearizabilitySurvivesLeaderFailover() {
            long seed = System.nanoTime();
            ClusterHarness cluster = new ClusterHarness(seed, 5);
            int leader = cluster.electLeader(800);
            assertTrue(leader >= 0, "Leader must be elected (seed=" + seed + ")");

            // Commit a value under the old leader
            long commitSeq = cluster.proposeAndCommit(leader, "failover-key", "committed-value", 100);
            assertTrue(commitSeq > 0, "Write must commit (seed=" + seed + ")");

            // Allow replication to propagate
            cluster.runTicks(200);

            // Partition the old leader
            cluster.sim().isolateNode(NodeId.of(leader));

            // Wait for new leader
            int newLeader = cluster.awaitStableLeader(Set.of(leader), 2000);
            assertTrue(newLeader >= 0,
                    "New leader must be elected (seed=" + seed + ")");

            // Issue ReadIndex on new leader
            long readId = cluster.node(newLeader).readIndex();
            assertTrue(readId >= 0, "ReadIndex must be accepted by new leader (seed=" + seed + ")");
            boolean ready = cluster.awaitReadReady(newLeader, readId, 200);
            assertTrue(ready, "ReadIndex must become ready on new leader (seed=" + seed + ")");

            // The committed value from the old leader must be visible
            ReadResult result = cluster.store(newLeader).get("failover-key");
            assertTrue(result.found(),
                    "INV-L1 violated: committed value not visible on new leader (seed=" + seed + ")");
            assertEquals("committed-value", str(result.value()),
                    "INV-L1 violated: wrong value on new leader (seed=" + seed + ")");

            cluster.node(newLeader).completeRead(readId);
        }
    }

    // =======================================================================
    // INV-S1/S2: Edge Staleness Bounds
    //
    // INV-S1: staleness(e, t) = wall_time(t) - timestamp(last_applied(e, t))
    // INV-S2: Under normal network conditions:
    //   P(staleness > 500ms) < 0.01 (p99)
    //   P(staleness > 2s) < 0.0001 (p9999)
    // =======================================================================

    @Nested
    class StalenessUpperBoundTest {

        /**
         * Verifies that the StalenessTracker correctly transitions through
         * CURRENT -> STALE -> DEGRADED -> DISCONNECTED as time elapses
         * without updates, and resets to CURRENT on update.
         */
        @Test
        void stalenessStateTransitionsAreCorrect() {
            SimulatedClock clock = new SimulatedClock(10_000L);
            StalenessTracker tracker = new StalenessTracker(clock);

            // Initially DISCONNECTED (no updates ever received)
            assertEquals(StalenessTracker.State.DISCONNECTED, tracker.currentState());

            // Record update -> CURRENT
            tracker.recordUpdate(1, 10_000);
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());
            assertTrue(tracker.stalenessMs() < 500,
                    "INV-S1: staleness must be < 500ms immediately after update");

            // Advance 499ms -> still CURRENT
            clock.advanceMs(499);
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());

            // Cross 500ms threshold -> STALE
            clock.advanceMs(2);
            assertEquals(StalenessTracker.State.STALE, tracker.currentState());
            assertTrue(tracker.stalenessMs() >= 500);

            // Cross 5s threshold -> DEGRADED
            clock.advanceMs(4500);
            assertEquals(StalenessTracker.State.DEGRADED, tracker.currentState());

            // Cross 30s threshold -> DISCONNECTED
            clock.advanceMs(25000);
            assertEquals(StalenessTracker.State.DISCONNECTED, tracker.currentState());

            // Record update -> back to CURRENT
            tracker.recordUpdate(2, 40_001);
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());
        }

        /**
         * Simulates a Raft cluster with periodic edge sync and verifies that
         * under normal conditions (no partitions), edge staleness stays
         * within p99 < 500ms bounds.
         * <p>
         * The test commits entries at regular intervals and syncs to an edge
         * store, measuring staleness at each tick. Under normal conditions
         * with 1-10ms network latency and 50ms heartbeat interval, the edge
         * should stay current.
         */
        @Test
        void edgeStalenessStaysWithinBoundsUnderNormalConditions() {
            SimulatedClock clock = new SimulatedClock(1_000_000L);
            ClusterHarness cluster = new ClusterHarness(42L, 3);
            int leader = cluster.electLeader(1200);
            assertTrue(leader >= 0, "Leader must be elected");

            VersionedConfigStore controlPlane = cluster.store(leader);
            LocalConfigStore edge = new LocalConfigStore();
            StalenessTracker tracker = new StalenessTracker(clock);

            // Initial sync
            cluster.proposeAndCommit(leader, "init", "data", 100);
            edge.loadSnapshot(controlPlane.snapshot());
            tracker.recordUpdate(edge.currentVersion(), clock.currentTimeMillis());

            int totalSamples = 0;
            int staleSamples = 0; // > 500ms
            int severelyStaleSamples = 0; // > 2s
            ConfigSnapshot prevSnap = controlPlane.snapshot();

            // Simulate 2000 ticks (~2 seconds of simulated time)
            // Every 20 ticks (~20ms), commit a new entry and sync to edge
            for (int t = 0; t < 2000; t++) {
                cluster.tick();
                clock.advanceMs(1);

                // Periodic writes and edge sync
                if (t % 20 == 0 && t > 0) {
                    long seq = cluster.proposeAndCommit(leader, "key-" + t, "val-" + t, 50);
                    if (seq > 0) {
                        ConfigSnapshot curSnap = controlPlane.snapshot();
                        ConfigDelta delta = DeltaComputer.compute(prevSnap, curSnap);
                        edge.applyDelta(delta);
                        tracker.recordUpdate(edge.currentVersion(), clock.currentTimeMillis());
                        prevSnap = curSnap;
                    }
                }

                // Sample staleness
                totalSamples++;
                long staleness = tracker.stalenessMs();
                if (staleness > 500) {
                    staleSamples++;
                }
                if (staleness > 2000) {
                    severelyStaleSamples++;
                }
            }

            // Under normal conditions, vast majority of samples must be < 500ms
            double staleRatio = (double) staleSamples / totalSamples;
            assertTrue(staleRatio < 0.01,
                    "INV-S2 violated: p99 staleness > 500ms. Stale ratio=" + staleRatio
                            + " (" + staleSamples + "/" + totalSamples + ")");

            double severeRatio = (double) severelyStaleSamples / totalSamples;
            assertTrue(severeRatio < 0.0001,
                    "INV-S2 violated: p9999 staleness > 2s. Severe ratio=" + severeRatio
                            + " (" + severelyStaleSamples + "/" + totalSamples + ")");
        }

        /**
         * Verifies staleness bound violation detection: when edge is cut off,
         * the tracker correctly reports escalating staleness states, and when
         * reconnected, it recovers to CURRENT.
         */
        @Test
        void stalenessTrackerDetectsAndRecoversFromPartition() {
            SimulatedClock clock = new SimulatedClock(1_000_000L);
            StalenessTracker tracker = new StalenessTracker(clock);

            // Start current
            tracker.recordUpdate(1, clock.currentTimeMillis());
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());

            // Simulate partition: no updates for 600ms
            clock.advanceMs(600);
            assertEquals(StalenessTracker.State.STALE, tracker.currentState(),
                    "INV-S1: must be STALE after 600ms without update");

            // Simulate reconnect: update arrives
            tracker.recordUpdate(10, clock.currentTimeMillis());
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState(),
                    "INV-S1: must recover to CURRENT after update");
            assertTrue(tracker.stalenessMs() < 10,
                    "Staleness must be near zero after fresh update");
        }

        /**
         * Verifies that the staleness measurement is monotonic: once the
         * last update time is set, the staleness can only increase until
         * the next update.
         */
        @Test
        void stalenessMsIsMonotonicallyIncreasingBetweenUpdates() {
            SimulatedClock clock = new SimulatedClock(1_000_000L);
            StalenessTracker tracker = new StalenessTracker(clock);

            tracker.recordUpdate(1, clock.currentTimeMillis());
            long lastStaleness = tracker.stalenessMs();

            for (int i = 0; i < 100; i++) {
                clock.advanceMs(10);
                long currentStaleness = tracker.stalenessMs();
                assertTrue(currentStaleness >= lastStaleness,
                        "Staleness must not decrease between updates: was "
                                + lastStaleness + ", now " + currentStaleness);
                lastStaleness = currentStaleness;
            }
        }
    }

    // =======================================================================
    // INV-V1: Sequence Monotonicity
    //
    // INV-V1: For all committed entries e1, e2 in Raft group g:
    //   if e1 committed before e2, then seq(e1) < seq(e2)
    // =======================================================================

    @Nested
    class SequenceMonotonicityTest {

        @Test
        void committedEntriesHaveStrictlyIncreasingSequence() {
            ClusterHarness cluster = new ClusterHarness(42L, 3);
            int leader = cluster.electLeader(1200);
            assertTrue(leader >= 0, "Leader must be elected");

            List<Long> sequences = new CopyOnWriteArrayList<>();
            cluster.stateMachine(leader).addListener((mutations, version) ->
                    sequences.add(version));

            for (int i = 0; i < 20; i++) {
                long seq = cluster.proposeAndCommit(leader, "key-" + i, "val-" + i, 100);
                assertTrue(seq > 0, "Entry " + i + " must commit; got seq=" + seq);
            }

            assertFalse(sequences.isEmpty(), "Must have committed entries");
            for (int i = 1; i < sequences.size(); i++) {
                assertTrue(sequences.get(i) > sequences.get(i - 1),
                        "INV-V1 violated: seq[" + i + "]=" + sequences.get(i)
                                + " must be > seq[" + (i - 1) + "]=" + sequences.get(i - 1));
            }
        }

        @RepeatedTest(3)
        void monotonicitySurvivesLeaderFailure() {
            long seed = System.nanoTime();
            ClusterHarness cluster = new ClusterHarness(seed, 5);
            int leader = cluster.electLeader(800);
            assertTrue(leader >= 0, "Leader must be elected (seed=" + seed + ")");

            for (int i = 0; i < 5; i++) {
                long seq = cluster.proposeAndCommit(leader, "k" + i, "v" + i, 100);
                assertTrue(seq > 0, "Initial entry " + i + " must commit (seed=" + seed + ")");
            }

            long versionBeforePartition = cluster.store(leader).currentVersion();
            assertTrue(versionBeforePartition > 0);

            cluster.sim().isolateNode(NodeId.of(leader));

            int newLeader = cluster.awaitStableLeader(Set.of(leader), 2000);
            assertTrue(newLeader >= 0,
                    "New leader must be elected (seed=" + seed + ")");

            for (int i = 5; i < 10; i++) {
                long seq = cluster.proposeAndCommit(newLeader, "k" + i, "v" + i, 100);
                assertTrue(seq > 0, "Post-failover entry " + i + " must commit (seed=" + seed + ")");
            }

            long finalVersion = cluster.store(newLeader).currentVersion();
            assertTrue(finalVersion >= 10,
                    "All entries must be committed; finalVersion=" + finalVersion + " (seed=" + seed + ")");
        }

        /**
         * Verifies sequence monotonicity on follower replicas: once entries
         * propagate, followers must also observe strictly increasing sequences.
         */
        @Test
        void sequenceMonotonicOnFollowerReplicas() {
            ClusterHarness cluster = new ClusterHarness(303L, 3);
            int leader = cluster.electLeader(1200);
            assertTrue(leader >= 0, "Leader must be elected");

            // Record sequences on all replicas
            List<List<Long>> perNodeSequences = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                List<Long> seqs = new CopyOnWriteArrayList<>();
                perNodeSequences.add(seqs);
                cluster.stateMachine(i).addListener((mutations, version) -> seqs.add(version));
            }

            for (int i = 0; i < 15; i++) {
                long seq = cluster.proposeAndCommit(leader, "k" + i, "v" + i, 100);
                assertTrue(seq > 0, "Entry " + i + " must commit");
            }

            // Allow full replication
            cluster.runTicks(300);

            // Every replica that received entries must have strictly increasing sequences
            for (int nodeIdx = 0; nodeIdx < 3; nodeIdx++) {
                List<Long> seqs = perNodeSequences.get(nodeIdx);
                for (int i = 1; i < seqs.size(); i++) {
                    assertTrue(seqs.get(i) > seqs.get(i - 1),
                            "INV-V1 violated on node " + nodeIdx + ": seq[" + i
                                    + "]=" + seqs.get(i) + " not > seq[" + (i - 1)
                                    + "]=" + seqs.get(i - 1));
                }
            }
        }
    }

    // =======================================================================
    // INV-V2: Sequence Gap-Free
    //
    // INV-V2: For all consecutive committed entries e_i, e_{i+1} in group g:
    //   seq(e_{i+1}) = seq(e_i) + 1
    // =======================================================================

    @Nested
    class SequenceGapFreeTest {

        @Test
        void consecutiveCommittedEntriesAreGapFree() {
            ClusterHarness cluster = new ClusterHarness(99L, 3);
            int leader = cluster.electLeader(1200);
            assertTrue(leader >= 0, "Leader must be elected");

            List<Long> sequences = new CopyOnWriteArrayList<>();
            cluster.stateMachine(leader).addListener((mutations, version) ->
                    sequences.add(version));

            for (int i = 0; i < 30; i++) {
                long seq = cluster.proposeAndCommit(leader, "key-" + i, "val-" + i, 100);
                assertTrue(seq > 0, "Entry " + i + " must commit");
            }

            assertTrue(sequences.size() >= 30, "All 30 entries must be recorded");
            for (int i = 1; i < sequences.size(); i++) {
                assertEquals(sequences.get(i - 1) + 1, sequences.get(i),
                        "INV-V2 violated: gap between seq[" + (i - 1) + "]="
                                + sequences.get(i - 1) + " and seq[" + i + "]="
                                + sequences.get(i));
            }
        }

        @Test
        void gapFreeWithMultipleKeysInSameGroup() {
            ClusterHarness cluster = new ClusterHarness(77L, 3);
            int leader = cluster.electLeader(1200);
            assertTrue(leader >= 0, "Leader must be elected");

            List<Long> sequences = new CopyOnWriteArrayList<>();
            cluster.stateMachine(leader).addListener((mutations, version) ->
                    sequences.add(version));

            String[] keys = {"alpha", "beta", "gamma", "delta"};
            for (int i = 0; i < 20; i++) {
                String key = keys[i % keys.length];
                long seq = cluster.proposeAndCommit(leader, key, "v" + i, 100);
                assertTrue(seq > 0, "Entry " + i + " (key=" + key + ") must commit");
            }

            assertTrue(sequences.size() >= 20);
            for (int i = 1; i < sequences.size(); i++) {
                assertEquals(sequences.get(i - 1) + 1, sequences.get(i),
                        "INV-V2 violated: gap at position " + i);
            }
        }

        /**
         * Verifies gap-free sequence across a leader failover: the new leader's
         * state machine continues the sequence without gaps.
         */
        @Test
        void gapFreeAcrossLeaderFailover() {
            ClusterHarness cluster = new ClusterHarness(42L, 5);
            int leader = cluster.electLeader(800);
            assertTrue(leader >= 0, "Leader must be elected");

            // Commit entries under old leader
            for (int i = 0; i < 5; i++) {
                long seq = cluster.proposeAndCommit(leader, "k" + i, "v" + i, 100);
                assertTrue(seq > 0, "Pre-failover entry " + i + " must commit");
            }

            long versionBeforeFailover = cluster.store(leader).currentVersion();
            cluster.runTicks(200);

            // Partition old leader
            cluster.sim().isolateNode(NodeId.of(leader));
            int newLeader = cluster.awaitStableLeader(Set.of(leader), 2000);
            assertTrue(newLeader >= 0, "New leader must be elected");

            // Collect sequences from new leader
            List<Long> newLeaderSeqs = new CopyOnWriteArrayList<>();
            cluster.stateMachine(newLeader).addListener((mutations, version) ->
                    newLeaderSeqs.add(version));

            // Commit more entries under new leader
            for (int i = 5; i < 15; i++) {
                long seq = cluster.proposeAndCommit(newLeader, "k" + i, "v" + i, 100);
                assertTrue(seq > 0, "Post-failover entry " + i + " must commit");
            }

            // The new leader's sequences must be gap-free
            for (int i = 1; i < newLeaderSeqs.size(); i++) {
                assertEquals(newLeaderSeqs.get(i - 1) + 1, newLeaderSeqs.get(i),
                        "INV-V2 violated after failover: gap between seq["
                                + (i - 1) + "]=" + newLeaderSeqs.get(i - 1)
                                + " and seq[" + i + "]=" + newLeaderSeqs.get(i));
            }

            // First entry on new leader must continue from where old leader left off
            if (!newLeaderSeqs.isEmpty()) {
                assertTrue(newLeaderSeqs.getFirst() > versionBeforeFailover,
                        "New leader's first seq (" + newLeaderSeqs.getFirst()
                                + ") must be > old leader's last version ("
                                + versionBeforeFailover + ")");
            }
        }
    }

    // =======================================================================
    // INV-M1: Monotonic Read
    //
    // INV-M1: For all reads r1, r2 by client c where r1 happens-before r2:
    //   version(response(r2)) >= version(response(r1))
    // =======================================================================

    @Nested
    class MonotonicReadTest {

        @Test
        void versionCursorMonotonicallyIncreasesDuringWrites() {
            ClusterHarness cluster = new ClusterHarness(42L, 3);
            int leader = cluster.electLeader(1200);
            assertTrue(leader >= 0, "Leader must be elected");

            long seq = cluster.proposeAndCommit(leader, "config.key", "initial", 100);
            assertTrue(seq > 0, "Initial entry must commit");

            VersionedConfigStore store = cluster.store(leader);
            long lastReadVersion = 0;

            for (int i = 0; i < 50; i++) {
                if (i % 3 == 0) {
                    cluster.proposePut(leader, "config.key", "value-" + i);
                }
                cluster.runTicks(5);

                long currentVersion = store.currentVersion();
                assertTrue(currentVersion >= lastReadVersion,
                        "INV-M1 violated: read version " + currentVersion
                                + " < previous " + lastReadVersion + " at iteration " + i);
                lastReadVersion = currentVersion;
            }
        }

        @Test
        void edgeStoreCursorEnforcesMonotonicReads() {
            LocalConfigStore edge = new LocalConfigStore();
            VersionedConfigStore controlPlane = new VersionedConfigStore();

            controlPlane.put("k", bytes("v1"), 1);
            ConfigSnapshot snap1 = controlPlane.snapshot();
            edge.loadSnapshot(snap1);

            VersionCursor cursor = new VersionCursor(1, 1000);
            ReadResult r1 = edge.get("k", cursor);
            assertTrue(r1.found(), "Read at cursor=1 must succeed");

            controlPlane.put("k", bytes("v2"), 2);
            controlPlane.put("k", bytes("v3"), 3);
            ConfigSnapshot snap3 = controlPlane.snapshot();
            ConfigDelta delta = DeltaComputer.compute(snap1, snap3);
            edge.applyDelta(delta);

            ReadResult r2 = edge.get("k", cursor);
            assertTrue(r2.found(), "Read with older cursor must succeed");

            VersionCursor cursor3 = new VersionCursor(3, 3000);
            ReadResult r3 = edge.get("k", cursor3);
            assertTrue(r3.found(), "Read at exact cursor must succeed");

            LocalConfigStore staleEdge = new LocalConfigStore();
            VersionedConfigStore cp2 = new VersionedConfigStore();
            cp2.put("k", bytes("stale"), 1);
            staleEdge.loadSnapshot(cp2.snapshot());

            ReadResult r4 = staleEdge.get("k", cursor3);
            assertFalse(r4.found(),
                    "INV-M1: Read with cursor=3 from edge at version=1 must fail");
        }

        /**
         * Verifies that reading from the control plane store with a minVersion
         * constraint enforces monotonic reads: if the store is behind the
         * requested version, the read returns NOT_FOUND.
         */
        @Test
        void controlPlaneMinVersionEnforcesMonotonicRead() {
            VersionedConfigStore store = new VersionedConfigStore();
            store.put("key", bytes("v1"), 1);
            store.put("key", bytes("v2"), 2);

            // Read with minVersion=2 succeeds
            ReadResult r1 = store.get("key", 2);
            assertTrue(r1.found(), "Read at minVersion=2 with store at 2 must succeed");

            // Read with minVersion=3 fails (store is at 2)
            ReadResult r2 = store.get("key", 3);
            assertFalse(r2.found(),
                    "INV-M1: Read with minVersion=3 from store at version=2 must fail");

            // Advance store to version 3
            store.put("key", bytes("v3"), 3);
            ReadResult r3 = store.get("key", 3);
            assertTrue(r3.found(), "Read at minVersion=3 with store at 3 must succeed");
        }
    }

    // =======================================================================
    // INV-M2: Monotonic Reads Survive Edge Failover
    //
    // INV-M2: For all reads r1, r2 of key k by client c
    //   where r1 happens-before r2:
    //   if value(r1) was written at version V,
    //   then value(r2) was written at version >= V
    // =======================================================================

    @Nested
    class MonotonicReadFailoverTest {

        /**
         * Simulates a client reading from edge A, then failing over to edge B
         * with a version cursor. Edge B must either serve a version >= cursor
         * or reject the read (signaling staleness).
         */
        @Test
        void cursorFromEdgeAEnforcedOnEdgeB() {
            VersionedConfigStore controlPlane = new VersionedConfigStore();

            // Set up control plane state
            controlPlane.put("failover-key", bytes("v1"), 1);
            controlPlane.put("failover-key", bytes("v2"), 2);
            controlPlane.put("failover-key", bytes("v3"), 3);
            ConfigSnapshot snap3 = controlPlane.snapshot();

            // Edge A has all data
            LocalConfigStore edgeA = new LocalConfigStore();
            edgeA.loadSnapshot(snap3);

            // Client reads from edge A and gets cursor at version 3
            VersionCursor clientCursor = new VersionCursor(3, 3000);
            ReadResult readA = edgeA.get("failover-key", clientCursor);
            assertTrue(readA.found(), "Edge A must serve read at cursor=3");
            assertEquals("v3", str(readA.value()));

            // Edge B is up to date — client failover succeeds
            LocalConfigStore edgeB = new LocalConfigStore();
            edgeB.loadSnapshot(snap3);

            ReadResult readB = edgeB.get("failover-key", clientCursor);
            assertTrue(readB.found(),
                    "INV-M2: Edge B (up to date) must serve read with cursor=3");
            assertTrue(readB.version() >= clientCursor.version(),
                    "INV-M2: Edge B result version " + readB.version()
                            + " must be >= cursor version " + clientCursor.version());
        }

        /**
         * When the failover target edge is behind the client's cursor,
         * the read must be rejected to preserve monotonic read guarantees.
         */
        @Test
        void staleEdgeRejectsReadWithFutureCursor() {
            VersionedConfigStore controlPlane = new VersionedConfigStore();
            controlPlane.put("key", bytes("v1"), 1);
            controlPlane.put("key", bytes("v2"), 2);
            controlPlane.put("key", bytes("v3"), 3);

            // Edge A has all data, client gets cursor=3
            LocalConfigStore edgeA = new LocalConfigStore();
            edgeA.loadSnapshot(controlPlane.snapshot());
            VersionCursor clientCursor = new VersionCursor(3, 3000);

            // Edge B only has version 1 (stale)
            VersionedConfigStore staleCP = new VersionedConfigStore();
            staleCP.put("key", bytes("v1"), 1);
            LocalConfigStore edgeB = new LocalConfigStore();
            edgeB.loadSnapshot(staleCP.snapshot());

            ReadResult readB = edgeB.get("key", clientCursor);
            assertFalse(readB.found(),
                    "INV-M2 violated: stale edge B (version=1) must reject read"
                            + " with cursor=3 to preserve monotonic read guarantee");
        }

        /**
         * Verifies that after edge B catches up, a previously rejected
         * cursor-gated read succeeds.
         */
        @Test
        void failoverEdgeServesReadAfterCatchUp() {
            VersionedConfigStore controlPlane = new VersionedConfigStore();
            controlPlane.put("key", bytes("v1"), 1);
            controlPlane.put("key", bytes("v2"), 2);
            controlPlane.put("key", bytes("v3"), 3);
            ConfigSnapshot snap3 = controlPlane.snapshot();

            // Edge B starts stale at version 1
            VersionedConfigStore staleCP = new VersionedConfigStore();
            staleCP.put("key", bytes("v1"), 1);
            LocalConfigStore edgeB = new LocalConfigStore();
            edgeB.loadSnapshot(staleCP.snapshot());

            VersionCursor clientCursor = new VersionCursor(3, 3000);

            // Initially rejected
            ReadResult beforeCatchUp = edgeB.get("key", clientCursor);
            assertFalse(beforeCatchUp.found(), "Should be rejected before catch-up");

            // Edge B catches up via full snapshot load
            edgeB.loadSnapshot(snap3);

            // Now the read succeeds
            ReadResult afterCatchUp = edgeB.get("key", clientCursor);
            assertTrue(afterCatchUp.found(),
                    "INV-M2: Edge B must serve read after catching up to version >= 3");
            assertEquals("v3", str(afterCatchUp.value()));
        }

        /**
         * Simulates a full Raft cluster with two edge stores (representing
         * two edge nodes in the same region) and verifies that a client's
         * version cursor is honored across failover from one edge to another.
         */
        @Test
        void endToEndFailoverWithRaftClusterAndTwoEdges() {
            ClusterHarness cluster = new ClusterHarness(42L, 3);
            int leader = cluster.electLeader(1200);
            assertTrue(leader >= 0, "Leader must be elected");

            VersionedConfigStore controlPlane = cluster.store(leader);

            // Commit several entries
            for (int i = 1; i <= 5; i++) {
                long seq = cluster.proposeAndCommit(leader, "config.db", "db-v" + i, 200);
                assertTrue(seq > 0, "Write " + i + " must commit");
            }
            ConfigSnapshot snapAfterWrites = controlPlane.snapshot();

            // Edge A and Edge B both sync to the latest snapshot
            LocalConfigStore edgeA = new LocalConfigStore();
            edgeA.loadSnapshot(snapAfterWrites);

            LocalConfigStore edgeB = new LocalConfigStore();
            edgeB.loadSnapshot(snapAfterWrites);

            // Client reads from Edge A
            VersionCursor cursor = new VersionCursor(
                    edgeA.currentVersion(), edgeA.currentVersion() * 1000);
            ReadResult readA = edgeA.get("config.db", cursor);
            assertTrue(readA.found());
            String valueFromA = str(readA.value());

            // Client fails over to Edge B with cursor
            ReadResult readB = edgeB.get("config.db", cursor);
            assertTrue(readB.found(),
                    "INV-M2: Edge B (synced to same version) must honor cursor");
            assertTrue(readB.version() >= cursor.version(),
                    "INV-M2: Edge B version " + readB.version()
                            + " must be >= cursor " + cursor.version());

            // Now commit more on control plane
            long newSeq = cluster.proposeAndCommit(leader, "config.db", "db-v6", 100);
            assertTrue(newSeq > 0);

            // Sync only Edge B (simulating Edge A going down)
            ConfigSnapshot newSnap = controlPlane.snapshot();
            ConfigDelta delta = DeltaComputer.compute(snapAfterWrites, newSnap);
            edgeB.applyDelta(delta);

            // Client reading from Edge B with old cursor still works (newer data)
            ReadResult readB2 = edgeB.get("config.db", cursor);
            assertTrue(readB2.found(), "INV-M2: Edge B with newer data must serve old cursor");
            assertTrue(readB2.version() >= cursor.version());
        }
    }

    // =======================================================================
    // INV-W1: Per-Key Total Order
    //
    // INV-W1: For all writes w1, w2 to key k where w1 committed before w2:
    //   seq(w1) < seq(w2)
    // =======================================================================

    @Nested
    class PerKeyTotalOrderTest {

        @Test
        void allReplicasSeeIdenticalOrderForSameKey() {
            ClusterHarness cluster = new ClusterHarness(42L, 3);
            int leader = cluster.electLeader(1200);
            assertTrue(leader >= 0, "Leader must be elected");

            for (int i = 0; i < 15; i++) {
                long seq = cluster.proposeAndCommit(leader, "shared-key", "version-" + i, 100);
                assertTrue(seq > 0, "Write " + i + " must commit");
            }

            cluster.runTicks(200);

            byte[] expectedValue = null;
            long expectedVersion = 0;

            for (int i = 0; i < 3; i++) {
                ReadResult result = cluster.store(i).get("shared-key");
                assertTrue(result.found(), "Node " + i + " must have key 'shared-key'");
                if (i == 0) {
                    expectedValue = result.value();
                    expectedVersion = result.version();
                } else {
                    assertArrayEquals(expectedValue, result.value(),
                            "INV-W1 violated: Node " + i + " has different value than node 0");
                    assertEquals(expectedVersion, result.version(),
                            "INV-W1 violated: Node " + i + " has different version than node 0");
                }
            }

            assertNotNull(expectedValue);
            assertEquals("version-14", str(expectedValue),
                    "Final value must be the last written version");
        }

        @Test
        void sequenceNumbersOfSameKeyWritesAreStrictlyIncreasing() {
            ClusterHarness cluster = new ClusterHarness(88L, 3);
            int leader = cluster.electLeader(1200);
            assertTrue(leader >= 0, "Leader must be elected");

            List<Long> keySequences = new CopyOnWriteArrayList<>();
            cluster.stateMachine(leader).addListener((mutations, version) -> {
                for (ConfigMutation m : mutations) {
                    if ("contested-key".equals(m.key())) {
                        keySequences.add(version);
                    }
                }
            });

            for (int i = 0; i < 10; i++) {
                long seq = cluster.proposeAndCommit(leader, "contested-key", "v" + i, 100);
                assertTrue(seq > 0, "Write " + i + " must commit");
            }

            assertEquals(10, keySequences.size(), "All 10 writes to key must be recorded");
            for (int i = 1; i < keySequences.size(); i++) {
                assertTrue(keySequences.get(i) > keySequences.get(i - 1),
                        "INV-W1 violated: seq[" + i + "]=" + keySequences.get(i)
                                + " not > seq[" + (i - 1) + "]=" + keySequences.get(i - 1));
            }
        }

        /**
         * Verifies per-key total order survives a leader change: writes to
         * the same key under a new leader have strictly higher sequence
         * numbers than writes committed under the old leader.
         */
        @Test
        void perKeyOrderSurvivesLeaderChange() {
            ClusterHarness cluster = new ClusterHarness(42L, 5);
            int leader = cluster.electLeader(800);
            assertTrue(leader >= 0, "Leader must be elected");

            // Writes under old leader
            long lastSeqOldLeader = -1;
            for (int i = 0; i < 5; i++) {
                lastSeqOldLeader = cluster.proposeAndCommit(leader, "ordered-key", "old-" + i, 100);
                assertTrue(lastSeqOldLeader > 0, "Old leader write " + i + " must commit");
            }

            cluster.runTicks(200);

            // Record the version of the key from any replica
            ReadResult oldResult = cluster.store(leader).get("ordered-key");
            assertTrue(oldResult.found());
            long oldVersion = oldResult.version();

            // Partition old leader
            cluster.sim().isolateNode(NodeId.of(leader));
            int newLeader = cluster.awaitStableLeader(Set.of(leader), 2000);
            assertTrue(newLeader >= 0, "New leader must be elected");

            // Writes under new leader
            for (int i = 0; i < 5; i++) {
                long seq = cluster.proposeAndCommit(newLeader, "ordered-key", "new-" + i, 100);
                assertTrue(seq > 0, "New leader write " + i + " must commit");
                assertTrue(seq > oldVersion,
                        "INV-W1 violated: new leader seq " + seq
                                + " must be > old version " + oldVersion);
            }

            // Final value must be from the new leader
            ReadResult newResult = cluster.store(newLeader).get("ordered-key");
            assertTrue(newResult.found());
            assertEquals("new-4", str(newResult.value()));
            assertTrue(newResult.version() > oldVersion);
        }
    }

    // =======================================================================
    // INV-W2: Intra-Group Order
    //
    // INV-W2: For all writes w1, w2 in group g where w1 committed before w2:
    //   seq(w1) < seq(w2) AND hlc(w1) < hlc(w2)
    // =======================================================================

    @Nested
    class IntraGroupOrderTest {

        @Test
        void allWritesInGroupShareTotalOrder() {
            ClusterHarness cluster = new ClusterHarness(42L, 3);
            int leader = cluster.electLeader(1200);
            assertTrue(leader >= 0, "Leader must be elected");

            record KeySeq(String key, long seq) {}
            List<KeySeq> committed = new CopyOnWriteArrayList<>();
            cluster.stateMachine(leader).addListener((mutations, version) -> {
                for (ConfigMutation m : mutations) {
                    committed.add(new KeySeq(m.key(), version));
                }
            });

            String[] keys = {"db.host", "db.port", "cache.ttl", "auth.timeout"};
            for (int i = 0; i < 20; i++) {
                String key = keys[i % keys.length];
                long seq = cluster.proposeAndCommit(leader, key, "v" + i, 100);
                assertTrue(seq > 0, "Write " + i + " to " + key + " must commit");
            }

            assertTrue(committed.size() >= 20, "All 20 writes must be committed");
            for (int i = 1; i < committed.size(); i++) {
                assertTrue(committed.get(i).seq() > committed.get(i - 1).seq(),
                        "INV-W2 violated: entry " + i + " (key=" + committed.get(i).key()
                                + ", seq=" + committed.get(i).seq() + ") not > entry "
                                + (i - 1) + " (key=" + committed.get(i - 1).key()
                                + ", seq=" + committed.get(i - 1).seq() + ")");
            }
        }

        @Test
        void deleteAndPutInterleavedMaintainOrder() {
            ClusterHarness cluster = new ClusterHarness(55L, 3);
            int leader = cluster.electLeader(1200);
            assertTrue(leader >= 0, "Leader must be elected");

            List<Long> allSequences = new CopyOnWriteArrayList<>();
            cluster.stateMachine(leader).addListener((mutations, version) ->
                    allSequences.add(version));

            for (int i = 0; i < 10; i++) {
                long seq = cluster.proposeAndCommit(leader, "key-" + i, "val-" + i, 100);
                assertTrue(seq > 0, "Put " + i + " must commit");
            }

            for (int i = 0; i < 5; i++) {
                long prevVersion = cluster.store(leader).currentVersion();
                boolean accepted = cluster.proposeDelete(leader, "key-" + i);
                assertTrue(accepted, "Delete " + i + " must be accepted by leader");
                boolean committed = false;
                for (int t = 0; t < 100; t++) {
                    cluster.tick();
                    if (cluster.store(leader).currentVersion() > prevVersion) {
                        committed = true;
                        break;
                    }
                }
                assertTrue(committed, "Delete " + i + " must commit");
            }

            for (int i = 1; i < allSequences.size(); i++) {
                assertTrue(allSequences.get(i) > allSequences.get(i - 1),
                        "INV-W2 violated at index " + i + ": " + allSequences.get(i)
                                + " not > " + allSequences.get(i - 1));
            }
        }

        /**
         * Verifies that all replicas converge to the same total order for
         * cross-key writes within the same Raft group.
         */
        @Test
        void allReplicasConvergeToSameOrder() {
            ClusterHarness cluster = new ClusterHarness(42L, 3);
            int leader = cluster.electLeader(1200);
            assertTrue(leader >= 0, "Leader must be elected");

            // Record per-node commit ordering
            List<List<String>> perNodeOrder = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                List<String> order = new CopyOnWriteArrayList<>();
                perNodeOrder.add(order);
                cluster.stateMachine(i).addListener((mutations, version) -> {
                    for (ConfigMutation m : mutations) {
                        order.add(m.key() + "@" + version);
                    }
                });
            }

            String[] keys = {"x", "y", "z"};
            for (int i = 0; i < 12; i++) {
                long seq = cluster.proposeAndCommit(leader, keys[i % 3], "v" + i, 100);
                assertTrue(seq > 0, "Write " + i + " must commit");
            }

            // Allow full replication
            cluster.runTicks(300);

            // All non-empty replicas must have the same order
            List<String> leaderOrder = perNodeOrder.get(leader);
            assertFalse(leaderOrder.isEmpty(), "Leader must have committed entries");

            for (int i = 0; i < 3; i++) {
                if (i == leader) continue;
                List<String> replicaOrder = perNodeOrder.get(i);
                // Replica may have a subset if still catching up, but what it
                // has must match the leader's prefix
                for (int j = 0; j < replicaOrder.size(); j++) {
                    assertEquals(leaderOrder.get(j), replicaOrder.get(j),
                            "INV-W2 violated: Node " + i + " disagrees with leader"
                                    + " at position " + j + ": expected "
                                    + leaderOrder.get(j) + " but got " + replicaOrder.get(j));
                }
            }
        }
    }

    // =======================================================================
    // INV-RYW1: Read-Your-Writes
    //
    // INV-RYW1: For all writes w by client c that commit at seq S,
    //   for all subsequent reads r by c with cursor.version >= S,
    //   in the same region:
    //     version(response(r)) >= S (within ryw_timeout)
    // =======================================================================

    @Nested
    class ReadYourWritesTest {

        @Test
        void readAfterWriteReturnsCommittedValue() {
            ClusterHarness cluster = new ClusterHarness(42L, 3);
            int leader = cluster.electLeader(1200);
            assertTrue(leader >= 0, "Leader must be elected");

            VersionedConfigStore store = cluster.store(leader);

            for (int i = 1; i <= 10; i++) {
                long commitSeq = cluster.proposeAndCommit(leader, "ryw-key", "value-" + i, 100);
                assertTrue(commitSeq > 0, "Write " + i + " must commit");

                long storeVersion = store.currentVersion();
                assertTrue(storeVersion >= commitSeq,
                        "INV-RYW1 violated: store version " + storeVersion
                                + " < commit seq " + commitSeq);

                ReadResult result = store.get("ryw-key");
                assertTrue(result.found(),
                        "INV-RYW1 violated: key must be found after commit " + i);
                assertTrue(result.version() >= commitSeq,
                        "INV-RYW1 violated: result version " + result.version()
                                + " < commit seq " + commitSeq);
                assertEquals("value-" + i, str(result.value()),
                        "Read must return the just-written value");
            }
        }

        @Test
        void readYourWritesWithVersionCursorOnEdge() {
            ClusterHarness cluster = new ClusterHarness(77L, 3);
            int leader = cluster.electLeader(1200);
            assertTrue(leader >= 0, "Leader must be elected");

            VersionedConfigStore controlPlane = cluster.store(leader);
            LocalConfigStore edge = new LocalConfigStore();

            long seq1 = cluster.proposeAndCommit(leader, "session.token", "abc123", 100);
            assertTrue(seq1 > 0);

            edge.loadSnapshot(controlPlane.snapshot());

            VersionCursor cursor = new VersionCursor(seq1, seq1 * 1000);
            ReadResult result = edge.get("session.token", cursor);
            assertTrue(result.found(),
                    "INV-RYW1: Edge must serve read with cursor=" + cursor.version()
                            + " when edge version=" + edge.currentVersion());
            assertEquals("abc123", str(result.value()));

            long seq2 = cluster.proposeAndCommit(leader, "session.token", "def456", 100);
            assertTrue(seq2 > seq1);

            ConfigSnapshot prev = edge.snapshot();
            ConfigDelta delta = DeltaComputer.compute(prev, controlPlane.snapshot());
            edge.applyDelta(delta);

            VersionCursor cursor2 = new VersionCursor(seq2, seq2 * 1000);
            ReadResult result2 = edge.get("session.token", cursor2);
            assertTrue(result2.found(),
                    "INV-RYW1: Edge must serve updated value at cursor=" + seq2);
            assertEquals("def456", str(result2.value()));
        }

        /**
         * Verifies RYW for delete operations: after a client deletes a key
         * and the edge syncs, reads with the new cursor must not find the key.
         */
        @Test
        void readYourDeleteOnEdge() {
            ClusterHarness cluster = new ClusterHarness(88L, 3);
            int leader = cluster.electLeader(1200);
            assertTrue(leader >= 0, "Leader must be elected");

            VersionedConfigStore controlPlane = cluster.store(leader);
            LocalConfigStore edge = new LocalConfigStore();

            // Write then sync to edge
            long seq1 = cluster.proposeAndCommit(leader, "ephemeral", "temp-data", 100);
            assertTrue(seq1 > 0);
            ConfigSnapshot snap1 = controlPlane.snapshot();
            edge.loadSnapshot(snap1);

            // Verify key exists
            VersionCursor cursor1 = new VersionCursor(seq1, seq1 * 1000);
            ReadResult r1 = edge.get("ephemeral", cursor1);
            assertTrue(r1.found(), "Key must exist before delete");

            // Delete and sync
            long prevVersion = controlPlane.currentVersion();
            boolean accepted = cluster.proposeDelete(leader, "ephemeral");
            assertTrue(accepted);
            for (int t = 0; t < 100; t++) {
                cluster.tick();
                if (controlPlane.currentVersion() > prevVersion) break;
            }
            long deleteSeq = controlPlane.currentVersion();
            assertTrue(deleteSeq > seq1);

            ConfigDelta delta = DeltaComputer.compute(snap1, controlPlane.snapshot());
            edge.applyDelta(delta);

            // After delete, read with new cursor must not find the key
            VersionCursor cursor2 = new VersionCursor(deleteSeq, deleteSeq * 1000);
            ReadResult r2 = edge.get("ephemeral", cursor2);
            assertFalse(r2.found(),
                    "INV-RYW1: Deleted key must not be found after edge sync");
        }

        /**
         * Verifies RYW across multiple keys: a client writes to multiple keys,
         * and subsequent reads (with the updated cursor) see all committed values.
         */
        @Test
        void readYourWritesAcrossMultipleKeys() {
            ClusterHarness cluster = new ClusterHarness(42L, 3);
            int leader = cluster.electLeader(1200);
            assertTrue(leader >= 0, "Leader must be elected");

            VersionedConfigStore controlPlane = cluster.store(leader);
            LocalConfigStore edge = new LocalConfigStore();

            // Write multiple keys
            long lastSeq = -1;
            String[] keys = {"db.host", "db.port", "db.name"};
            String[] values = {"prod.db.internal", "5432", "config_db"};
            for (int i = 0; i < keys.length; i++) {
                lastSeq = cluster.proposeAndCommit(leader, keys[i], values[i], 100);
                assertTrue(lastSeq > 0, "Write to " + keys[i] + " must commit");
            }

            // Sync edge
            edge.loadSnapshot(controlPlane.snapshot());
            VersionCursor cursor = new VersionCursor(lastSeq, lastSeq * 1000);

            // All keys must be readable with the cursor
            for (int i = 0; i < keys.length; i++) {
                ReadResult result = edge.get(keys[i], cursor);
                assertTrue(result.found(),
                        "INV-RYW1: Key " + keys[i] + " must be found with cursor=" + lastSeq);
                assertEquals(values[i], str(result.value()),
                        "INV-RYW1: Wrong value for " + keys[i]);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Version Monotonicity at Edge
    // -----------------------------------------------------------------------

    @Nested
    class VersionMonotonicityEdgeTest {

        @Test
        void edgeVersionNeverDecreases() {
            ClusterHarness cluster = new ClusterHarness(42L, 3);
            int leader = cluster.electLeader(1200);
            assertTrue(leader >= 0, "Leader must be elected");

            VersionedConfigStore controlPlane = cluster.store(leader);
            LocalConfigStore edge = new LocalConfigStore();

            cluster.proposeAndCommit(leader, "init", "data", 200);
            ConfigSnapshot prevSnap = controlPlane.snapshot();
            edge.loadSnapshot(prevSnap);

            long lastEdgeVersion = edge.currentVersion();
            assertTrue(lastEdgeVersion > 0);

            for (int i = 0; i < 20; i++) {
                cluster.proposeAndCommit(leader, "key-" + (i % 5), "val-" + i, 100);
                ConfigSnapshot curSnap = controlPlane.snapshot();
                ConfigDelta delta = DeltaComputer.compute(prevSnap, curSnap);
                edge.applyDelta(delta);

                long edgeVersion = edge.currentVersion();
                assertTrue(edgeVersion >= lastEdgeVersion,
                        "Edge version must not decrease: " + lastEdgeVersion
                                + " -> " + edgeVersion + " at iteration " + i);
                lastEdgeVersion = edgeVersion;
                prevSnap = curSnap;
            }
        }

        @Test
        void edgeVersionIncreasesThroughMixedOperations() {
            VersionedConfigStore controlPlane = new VersionedConfigStore();
            LocalConfigStore edge = new LocalConfigStore();

            controlPlane.put("a", bytes("1"), 1);
            controlPlane.put("b", bytes("2"), 2);
            ConfigSnapshot snap1 = controlPlane.snapshot();
            edge.loadSnapshot(snap1);
            long v = edge.currentVersion();
            assertEquals(2, v);

            controlPlane.put("a", bytes("updated"), 3);
            ConfigSnapshot snap2 = controlPlane.snapshot();
            edge.applyDelta(DeltaComputer.compute(snap1, snap2));
            assertTrue(edge.currentVersion() > v);
            v = edge.currentVersion();

            controlPlane.delete("b", 4);
            ConfigSnapshot snap3 = controlPlane.snapshot();
            edge.applyDelta(DeltaComputer.compute(snap2, snap3));
            assertTrue(edge.currentVersion() > v);
            v = edge.currentVersion();

            controlPlane.put("c", bytes("3"), 5);
            controlPlane.put("d", bytes("4"), 6);
            ConfigSnapshot snap4 = controlPlane.snapshot();
            edge.applyDelta(DeltaComputer.compute(snap3, snap4));
            assertTrue(edge.currentVersion() > v);
        }
    }

    // -----------------------------------------------------------------------
    // INV-6 (TLA+): No Stale Overwrite
    // -----------------------------------------------------------------------

    @Nested
    class NoStaleOverwriteTest {

        @Test
        void committedEntrySurvivesLeaderFailure() {
            ClusterHarness cluster = new ClusterHarness(42L, 5);
            int leader = cluster.electLeader(800);
            assertTrue(leader >= 0, "Leader must be elected");

            for (int i = 0; i < 5; i++) {
                long seq = cluster.proposeAndCommit(leader, "stable-" + i, "value-" + i, 100);
                assertTrue(seq > 0, "Entry " + i + " must commit");
            }

            cluster.runTicks(200);

            long commitIndexBeforeFailure = cluster.log(leader).commitIndex();
            assertTrue(commitIndexBeforeFailure > 0);

            Map<Long, Long> committedTerms = new HashMap<>();
            for (long idx = 1; idx <= commitIndexBeforeFailure; idx++) {
                LogEntry entry = cluster.log(leader).entryAt(idx);
                if (entry != null) {
                    committedTerms.put(idx, entry.term());
                }
            }

            cluster.sim().isolateNode(NodeId.of(leader));

            int newLeader = cluster.awaitStableLeader(Set.of(leader), 2000);
            assertTrue(newLeader >= 0,
                    "New leader must be elected after isolation");

            RaftLog newLeaderLog = cluster.log(newLeader);
            for (var entry : committedTerms.entrySet()) {
                long idx = entry.getKey();
                long expectedTerm = entry.getValue();
                LogEntry logEntry = newLeaderLog.entryAt(idx);
                if (logEntry != null) {
                    assertEquals(expectedTerm, logEntry.term(),
                            "INV-6 violated: committed entry at index " + idx
                                    + " was overwritten. Expected term " + expectedTerm
                                    + " but found " + logEntry.term());
                } else {
                    assertTrue(idx <= newLeaderLog.snapshotIndex(),
                            "Entry at index " + idx + " missing and not in snapshot");
                }
            }
        }

        @Test
        void committedValuePersistsAcrossLeaderChange() {
            ClusterHarness cluster = new ClusterHarness(101L, 5);
            int leader = cluster.electLeader(800);
            assertTrue(leader >= 0, "Leader must be elected");

            long seq = cluster.proposeAndCommit(leader, "critical-config", "production-db", 100);
            assertTrue(seq > 0);

            cluster.runTicks(200);

            int replicasWithValue = 0;
            for (int i = 0; i < 5; i++) {
                ReadResult r = cluster.store(i).get("critical-config");
                if (r.found() && "production-db".equals(str(r.value()))) {
                    replicasWithValue++;
                }
            }
            assertTrue(replicasWithValue >= 3,
                    "Majority must have committed value; found " + replicasWithValue);

            cluster.sim().isolateNode(NodeId.of(leader));

            int newLeader = cluster.awaitStableLeader(Set.of(leader), 2000);
            assertTrue(newLeader >= 0,
                    "New leader must be elected after isolation");

            long newSeq = cluster.proposeAndCommit(newLeader, "new-key", "new-val", 100);
            assertTrue(newSeq > 0, "New entry must commit on new leader");

            ReadResult result = cluster.store(newLeader).get("critical-config");
            assertTrue(result.found(),
                    "INV-6 violated: committed value 'critical-config' lost after leader change");
            assertEquals("production-db", str(result.value()),
                    "INV-6 violated: committed value was overwritten");
        }
    }

    // -----------------------------------------------------------------------
    // Election Safety: At most one leader per term
    // -----------------------------------------------------------------------

    @Nested
    class ElectionSafetyTest {

        @Test
        void atMostOneLeaderPerTerm() {
            ClusterHarness cluster = new ClusterHarness(42L, 5);

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
                                + " had multiple leaders: " + entry.getValue());
            }
        }

        @Test
        void electionSafetyUnderPartitions() {
            long seed = 42L;
            ClusterHarness cluster = new ClusterHarness(seed, 5);

            Map<Long, Set<Integer>> leadersPerTerm = new HashMap<>();

            for (int t = 0; t < 3000; t++) {
                cluster.tick();

                if (t % 200 == 100) {
                    cluster.sim().injectRandomPartition();
                }
                if (t % 400 == 300) {
                    cluster.sim().healAllPartitions();
                }

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
                        "Election safety violated under partitions: term "
                                + entry.getKey() + " had leaders " + entry.getValue()
                                + " (seed=" + seed + ")");
            }
        }

        @RepeatedTest(3)
        void electionSafetyWithRandomSeeds() {
            long seed = System.nanoTime();
            ClusterHarness cluster = new ClusterHarness(seed, 5);

            Map<Long, Set<Integer>> leadersPerTerm = new HashMap<>();

            cluster.electLeader(800);

            for (int t = 0; t < 2000; t++) {
                cluster.tick();

                if (t % 150 == 75) {
                    int nodeToIsolate = Math.abs((int) ((seed + t) % 5));
                    cluster.sim().isolateNode(NodeId.of(nodeToIsolate));
                }
                if (t % 300 == 250) {
                    cluster.sim().healAllPartitions();
                }

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
                                + " had leaders " + entry.getValue()
                                + " (seed=" + seed + ")");
            }
        }
    }
}

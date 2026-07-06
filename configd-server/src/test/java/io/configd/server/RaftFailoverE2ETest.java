package io.configd.server;

import io.configd.common.NodeId;
import io.configd.common.Storage;
import io.configd.netty.NettyRaftTransport;
import io.configd.raft.AppendEntriesRequest;
import io.configd.raft.CoalescingRaftTransport;
import io.configd.raft.HeartbeatCoalescer;
import io.configd.raft.ProposalResult;
import io.configd.raft.ProposeOutcome;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.store.CommandCodec;
import io.configd.store.ConfigStateMachine;
import io.configd.store.ReadResult;
import io.configd.store.VersionedConfigStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate-4 scenario 1 — <b>leader failover over the REAL Netty consensus wire, with no data loss.</b>
 *
 * <p>The multi-Raft memory records that {@code N>1} replication is wired and green but the failover
 * path (kill the leader, re-elect, confirm nothing committed is lost) was never exercised over the real
 * transport. {@code EncryptedMultiShardClusterCompositionTest} proves replication + a follower
 * restart-rejoin; this proves the harder property — <em>a LEADER dying mid-flight forces a re-election on
 * the survivors and every acknowledged write survives it.</em>
 *
 * <p>Wiring mirrors {@code NettyConsensusLivenessTest.RealWireCluster} (the proven real-wire pattern):
 * three nodes, each on its own owner thread behind its own plaintext {@link NettyRaftTransport}, wrapped by
 * a {@link RaftTransportAdapter} then a {@link CoalescingRaftTransport} (coalescing ACTIVE), driving a real
 * {@link RaftNode} over real localhost TCP. Each node keeps its own {@link VersionedConfigStore} so a
 * committed value can be read back on any survivor. Everything is deadline-polled — no sleep-as-sync; the
 * per-method {@link Timeout} is pure hang detection.
 *
 * <h2>The proof</h2>
 * <ol>
 *   <li>Elect a stable leader; commit a batch of writes and confirm each replicates + applies on all three.</li>
 *   <li>KILL the leader (close its transport, stop its ticks) — the equivalent of a node crash mid-stream.</li>
 *   <li>A NEW leader (a different node) is elected on the two survivors within the election budget.</li>
 *   <li><b>No data loss:</b> every pre-failover committed key is still present with its exact value on both
 *       survivors — read straight from their stores.</li>
 *   <li>The new leader accepts a fresh write that commits + replicates on both survivors (the cluster is
 *       live again, from a 2-of-3 quorum).</li>
 * </ol>
 */
@Timeout(180) // hang detection on the throttled 2-vCPU box; every phase bounds itself with explicit deadlines
final class RaftFailoverE2ETest {

    private static final int NODES = 3;
    private static final int GROUP = 0;
    private static final long BASE_SEED = 0xFA170FL;

    // Election budget: heartbeat 50ms (5 ticks) << election 1000-2000ms (100-200 ticks) => ratio 20, the
    // NettyConsensusLivenessTest-proven-stable range, so 2-vCPU jitter cannot manufacture a spurious election.
    private static final int TICK_PERIOD_MS = 10;
    private static final int HEARTBEAT_MS = 50;
    private static final int ELECTION_MIN_MS = 1000;
    private static final int ELECTION_MAX_MS = 2000;

    private static final int POLL_MS = 25;
    private static final int STABLE_OBSERVATIONS = 40; // ~1s of steady single-leadership
    private static final long STABILIZE_BUDGET_MS = 30_000;
    private static final long FAILOVER_BUDGET_MS = 30_000; // >> 2s election max
    private static final long REPLICATE_BUDGET_MS = 20_000;

    private Cluster cluster;
    private String savedWorkerThreads;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.close();
            cluster = null;
        }
        if (savedWorkerThreads == null) {
            System.clearProperty("configd.raft.netty.workerThreads");
        } else {
            System.setProperty("configd.raft.netty.workerThreads", savedWorkerThreads);
        }
    }

    @Test
    void killingTheLeaderReElectsOnSurvivorsWithNoDataLoss() throws Exception {
        savedWorkerThreads = System.getProperty("configd.raft.netty.workerThreads");
        System.setProperty("configd.raft.netty.workerThreads", "1"); // less event-loop contention on 2 vCPU
        cluster = new Cluster();

        // --- 1) elect + replicate a batch across all three nodes ---
        int leader0 = cluster.electStableLeader(STABILIZE_BUDGET_MS);
        assertTrue(leader0 >= 0, "a stable leader must be elected on the real Netty wire");
        long term0 = cluster.maxTerm();

        final int keyCount = 5;
        for (int k = 0; k < keyCount; k++) {
            long committed = cluster.commitAndAwaitReplication(leader0, "k" + k, "v" + k, /*excluded=*/-1);
            assertTrue(committed > 0, "write k" + k + " must reach a committed index pre-failover");
        }
        // Every committed key is present on ALL three before we kill anyone (baseline).
        for (int i = 0; i < NODES; i++) {
            for (int k = 0; k < keyCount; k++) {
                assertValue(cluster.stores[i], "k" + k, "v" + k, "node " + i + " pre-failover");
            }
        }

        // --- 2) KILL the leader mid-stream (crash equivalent: transport closed, ticks stopped) ---
        cluster.killNode(leader0);

        // --- 3) a NEW leader is elected among the two survivors ---
        int leader1 = cluster.awaitStableLeaderExcluding(leader0, FAILOVER_BUDGET_MS);
        assertTrue(leader1 >= 0,
                "the two survivors must elect a new stable leader after the leader was killed");
        assertNotEquals(leader0, leader1, "the new leader must be a survivor, not the killed node");
        assertTrue(cluster.maxTerm() > term0,
                "a real re-election must advance the term past the pre-failover term " + term0);

        // --- 4) NO DATA LOSS: every pre-failover committed key survives on both survivors ---
        for (int i = 0; i < NODES; i++) {
            if (i == leader0) {
                continue;
            }
            for (int k = 0; k < keyCount; k++) {
                assertValue(cluster.stores[i], "k" + k, "v" + k,
                        "survivor " + i + " must retain committed key across failover");
            }
        }

        // --- 5) the cluster is live again: a fresh write commits + replicates on both survivors ---
        long postCommit = cluster.commitAndAwaitReplication(leader1, "post", "after-failover", leader0);
        assertTrue(postCommit > 0, "a post-failover write must commit on the new leader's 2-of-3 quorum");
        for (int i = 0; i < NODES; i++) {
            if (i == leader0) {
                continue;
            }
            assertValue(cluster.stores[i], "post", "after-failover",
                    "survivor " + i + " must apply the post-failover write");
        }

        System.out.println("[G4-FAILOVER] leader " + leader0 + " (term " + term0 + ") killed; survivors elected "
                + leader1 + " (term " + cluster.maxTerm() + "); " + keyCount
                + " pre-failover keys intact + a post-failover write replicated — no data loss");
    }

    private static void assertValue(VersionedConfigStore store, String key, String expected, String ctx) {
        ReadResult r = store.get(key);
        assertTrue(r.found(), ctx + ": key '" + key + "' must be present (no data loss)");
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), r.value(),
                ctx + ": key '" + key + "' must hold its committed value");
    }

    // =======================================================================
    // real-wire cluster (mirrors NettyConsensusLivenessTest.RealWireCluster; stores retained, node kill added)
    // =======================================================================

    private final class Cluster {
        private final NodeId[] ids = new NodeId[NODES];
        private final RaftNode[] nodes = new RaftNode[NODES];
        private final VersionedConfigStore[] stores = new VersionedConfigStore[NODES];
        private final NettyRaftTransport[] transports = new NettyRaftTransport[NODES];
        private final RaftTransportAdapter[] adapters = new RaftTransportAdapter[NODES];
        private final HeartbeatCoalescer[] coalescers = new HeartbeatCoalescer[NODES];
        private final ScheduledExecutorService[] owners = new ScheduledExecutorService[NODES];
        private final ScheduledFuture<?>[] tickFutures = new ScheduledFuture<?>[NODES];
        private final boolean[] dead = new boolean[NODES];
        private volatile boolean closed;

        Cluster() throws Exception {
            try {
                build();
            } catch (Exception e) {
                close();
                throw e;
            }
        }

        private void build() throws Exception {
            int[] ports = reserveDistinctPorts(NODES);
            for (int i = 0; i < NODES; i++) {
                ids[i] = NodeId.of(i);
            }
            for (int i = 0; i < NODES; i++) {
                Map<NodeId, InetSocketAddress> peerAddrs = new HashMap<>();
                Set<NodeId> peerIds = new HashSet<>();
                for (int j = 0; j < NODES; j++) {
                    if (j != i) {
                        peerAddrs.put(ids[j], new InetSocketAddress("127.0.0.1", ports[j]));
                        peerIds.add(ids[j]);
                    }
                }
                transports[i] = new NettyRaftTransport(ids[i],
                        new InetSocketAddress("127.0.0.1", ports[i]), peerAddrs, null, null);
                adapters[i] = new RaftTransportAdapter(transports[i], GROUP);
                coalescers[i] = new HeartbeatCoalescer();
                CoalescingRaftTransport coalescing = new CoalescingRaftTransport(adapters[i], GROUP);
                final int idx = i;
                coalescing.bindCoalescer(() -> coalescers[idx]);

                RaftConfig config = new RaftConfig(ids[i], peerIds,
                        ELECTION_MIN_MS, ELECTION_MAX_MS, HEARTBEAT_MS,
                        64, 256 * 1024, 1024, 10, TICK_PERIOD_MS);
                stores[i] = new VersionedConfigStore();
                ConfigStateMachine sm = new ConfigStateMachine(stores[i]);
                nodes[i] = new RaftNode(config, new RaftLog(), coalescing, sm,
                        new Random(BASE_SEED + i), Storage.inMemory(), RaftNode.InvariantChecker.NOOP);
                owners[i] = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "failover-owner-" + idx);
                    t.setDaemon(true);
                    return t;
                });
            }
            for (int i = 0; i < NODES; i++) {
                final int idx = i;
                owners[i].execute(() -> nodes[idx].bindOwnerThread());
            }
            for (int i = 0; i < NODES; i++) {
                final int idx = i;
                adapters[i].registerInboundHandler((from, gid, msg) ->
                        owners[idx].execute(() -> nodes[idx].handleMessage(msg)));
            }
            for (int i = 0; i < NODES; i++) {
                transports[i].start();
            }
            for (int i = 0; i < NODES; i++) {
                final int idx = i;
                tickFutures[i] = owners[i].scheduleWithFixedDelay(
                        () -> tickOnce(idx), TICK_PERIOD_MS, TICK_PERIOD_MS, TimeUnit.MILLISECONDS);
            }
        }

        private void tickOnce(int i) {
            if (dead[i]) {
                return;
            }
            HeartbeatCoalescer hc = coalescers[i];
            hc.beginTick();
            try {
                nodes[i].tick();
            } finally {
                drainHeartbeats(i, hc);
            }
        }

        private void drainHeartbeats(int i, HeartbeatCoalescer hc) {
            Map<NodeId, Map<Integer, AppendEntriesRequest>> drained = hc.drainAndEndTick();
            if (dead[i] || drained.isEmpty()) {
                return;
            }
            for (Map.Entry<NodeId, Map<Integer, AppendEntriesRequest>> peerEntry : drained.entrySet()) {
                for (AppendEntriesRequest hb : peerEntry.getValue().values()) {
                    adapters[i].send(peerEntry.getKey(), hb);
                }
            }
        }

        /** Crash a node: stop its ticks (no more heartbeats/appends) and close its transport (drop the wire). */
        void killNode(int i) {
            dead[i] = true;
            ScheduledFuture<?> f = tickFutures[i];
            if (f != null) {
                f.cancel(false);
            }
            transports[i].close();
        }

        long maxTerm() {
            long max = 0;
            for (int i = 0; i < NODES; i++) {
                if (!dead[i]) {
                    max = Math.max(max, nodes[i].monitorView().currentTerm());
                }
            }
            return max;
        }

        RaftRole role(int i) {
            return nodes[i].monitorView().role();
        }

        long lastApplied(int i) {
            return nodes[i].monitorView().lastApplied();
        }

        /** The sole node reporting LEADER (excluding dead nodes), or -1 for none / a transient split. */
        private int singleLeader(int excluded) {
            int leader = -1;
            for (int i = 0; i < NODES; i++) {
                if (i == excluded || dead[i]) {
                    continue;
                }
                if (role(i) == RaftRole.LEADER) {
                    if (leader >= 0) {
                        return -1;
                    }
                    leader = i;
                }
            }
            return leader;
        }

        int electStableLeader(long budgetMs) throws InterruptedException {
            return awaitStableLeaderExcluding(-1, budgetMs);
        }

        int awaitStableLeaderExcluding(int excluded, long budgetMs) throws InterruptedException {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
            int candidate = -1;
            int stable = 0;
            while (System.nanoTime() < end) {
                int leader = singleLeader(excluded);
                if (leader >= 0 && leader == candidate) {
                    if (++stable >= STABLE_OBSERVATIONS) {
                        return leader;
                    }
                } else {
                    candidate = leader;
                    stable = (leader >= 0) ? 1 : 0;
                }
                Thread.sleep(POLL_MS);
            }
            return -1;
        }

        /**
         * Proposes a PUT on {@code leader}'s owner and waits until every non-excluded, live node applies past
         * its pre-propose index. Retries across a leadership change until accepted. Returns the leader's
         * committed index.
         */
        long commitAndAwaitReplication(int leader, String key, String value, int excluded) throws Exception {
            byte[] cmd = CommandCodec.encodePut(key, value.getBytes(StandardCharsets.UTF_8));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REPLICATE_BUDGET_MS);
            long committed = -1;
            while (System.nanoTime() < deadline && committed < 0) {
                if (dead[leader] || role(leader) != RaftRole.LEADER) {
                    Thread.sleep(POLL_MS);
                    continue;
                }
                long before = lastApplied(leader);
                ProposeOutcome outcome;
                try {
                    outcome = owners[leader].submit(() -> nodes[leader].propose(cmd)).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    Thread.sleep(POLL_MS);
                    continue;
                }
                if (outcome.result() != ProposalResult.ACCEPTED) {
                    Thread.sleep(POLL_MS);
                    continue;
                }
                while (System.nanoTime() < deadline) {
                    long now = lastApplied(leader);
                    if (now > before) {
                        committed = now;
                        break;
                    }
                    Thread.sleep(POLL_MS);
                }
            }
            assertTrue(committed > 0, "leader " + leader + " did not commit '" + key + "' in time");
            final long target = committed;
            for (int i = 0; i < NODES; i++) {
                if (i == excluded || dead[i]) {
                    continue;
                }
                final int idx = i;
                assertTrue(awaitUntil(deadline, () -> lastApplied(idx) >= target),
                        "node " + i + " did not replicate+apply '" + key + "' up to index " + target
                                + " (got " + lastApplied(i) + ")");
            }
            return committed;
        }

        private boolean awaitUntil(long deadlineNanos, java.util.function.BooleanSupplier cond)
                throws InterruptedException {
            while (System.nanoTime() < deadlineNanos) {
                if (cond.getAsBoolean()) {
                    return true;
                }
                Thread.sleep(POLL_MS);
            }
            return cond.getAsBoolean();
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            for (ScheduledFuture<?> f : tickFutures) {
                if (f != null) {
                    f.cancel(false);
                }
            }
            for (NettyRaftTransport t : transports) {
                if (t != null) {
                    t.close();
                }
            }
            for (ScheduledExecutorService o : owners) {
                if (o != null) {
                    o.shutdownNow();
                }
            }
            for (ScheduledExecutorService o : owners) {
                if (o != null) {
                    try {
                        o.awaitTermination(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private static int[] reserveDistinctPorts(int n) throws Exception {
        ServerSocket[] socks = new ServerSocket[n];
        int[] ports = new int[n];
        try {
            for (int i = 0; i < n; i++) {
                socks[i] = new ServerSocket(0);
                ports[i] = socks[i].getLocalPort();
            }
        } finally {
            for (ServerSocket s : socks) {
                if (s != null) {
                    s.close();
                }
            }
        }
        return ports;
    }
}

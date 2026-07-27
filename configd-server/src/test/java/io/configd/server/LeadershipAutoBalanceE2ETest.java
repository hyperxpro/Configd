package io.configd.server;

import io.configd.common.NodeId;
import io.configd.raft.ProposalResult;
import io.configd.raft.ProposeOutcome;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.replication.MultiRaftDriver;
import io.configd.store.CommandCodec;
import io.configd.store.SigningKeyStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live proof that the <b>decentralized leadership auto-balance loop actually rebalances a real
 * multi-node cluster.</b>
 *
 * <p>Every other test of the balancer proves a fragment: {@code LeaderBalancePlannerTest} proves the
 * pure decision is convergent, {@code LeaderBalanceLoopTest} proves the loop's control behaviour over a
 * {@code FakeCluster} model, and {@code EncryptedMultiShardClusterCompositionTest} stands up a real
 * cluster but pins the loop OFF so its leadership-stability assertions cannot race a shed. None of them
 * proves the loop, wired into a live {@link ConfigdServer}, driving the real owner-confined
 * {@code transferLeadership} path over loopback TCP: takes a genuinely concentrated cluster and spreads
 * it back out. That is the property here.
 *
 * <p>Both scenarios boot a real three-node, six-shard cluster over loopback, encryption OFF to isolate
 * the balance behaviour, then deliberately concentrate every shard's leadership onto node 0 by driving
 * the same {@link RaftNode#transferLeadership} primitive the admin endpoint and the loop use.
 *
 * <p><b>Why concentration is clean even with the loop live.</b> The loop's instability gate backs the
 * whole cycle off while any group's term bumped within {@code instabilityWindowMs}. Each transfer this
 * test issues bumps a term, so while the test is actively concentrating, the live loop is held in
 * {@code term_churn} back-off and cannot erode the concentration out from under it; it only begins
 * shedding once the test stops transferring and the window clears.
 *
 * <p>The cadence/cooldown/instability knobs below are shortened from their production defaults so a
 * bounded test converges in tens of seconds; the planner's real safety logic (actionable threshold,
 * jitter/cooldown/churn back-off) is untouched.
 */
@Timeout(180) // hang detection only; each phase bounds itself with an explicit deadline-poll
class LeadershipAutoBalanceE2ETest {

    private static final int NODES = 3;
    private static final int SHARDS = 6;
    // The balanced target for 6 groups over 3 nodes is {2,2,2}: a spread of 0. "Converged" means the
    // spread has fallen to the unavoidable-imbalance floor of 1 or below.
    private static final int BALANCED_SPREAD = 1;

    private static final long STABILIZE_MS = 60_000;
    private static final long CONCENTRATE_MS = 30_000;
    private static final long CONVERGE_MS = 45_000;
    private static final long OBSERVE_MS = 40_000;
    private static final long REPLICATE_MS = 20_000;
    private static final long POLL_MS = 50;
    private static final int STABLE_OBSERVATIONS = 10; // ~0.5s of a steady reading before it is "settled"

    private static final NodeId NODE0 = NodeId.of(0);

    // Shortened balance cadence for a bounded test. The threshold stays at the production default of 2 so
    // the planner's real actionability logic is exercised; only the timing is compressed. instabilityWindow
    // (1s) is kept below the interval (1.5s) so a settled transfer's own term bump has cleared the churn
    // gate by the next cadence, and cooldown (1.5s) matches the interval so one shed lands per cadence.
    private static final String[] PROPS = {
            "configd.raft.encryption.enabled",
            "configd.raft.shardCount",
            "configd.raft.ownerPoolSize",
            "configd.raft.electionTimeoutMinMs",
            "configd.raft.electionTimeoutMaxMs",
            "configd.raft.heartbeatIntervalMs",
            "configd.raft.netty.workerThreads",
            "configd.raft.autobalance.enabled",
            "configd.raft.autobalance.intervalMs",
            "configd.raft.autobalance.jitterPct",
            "configd.raft.autobalance.cooldownMs",
            "configd.raft.autobalance.imbalanceThreshold",
            "configd.raft.autobalance.instabilityWindowMs",
    };
    private final Map<String, String> saved = new HashMap<>();
    private final List<ConfigdServer> running = new ArrayList<>();

    @BeforeEach
    void setPosture() {
        for (String p : PROPS) {
            saved.put(p, System.getProperty(p));
        }
        System.setProperty("configd.raft.encryption.enabled", "false");
        System.setProperty("configd.raft.shardCount", Integer.toString(SHARDS));
        // Two owner threads carry the six groups (floorMod). Fewer threads than shards keeps the tick load
        // light on the 2-vCPU box; the WARNING the server logs about it is expected and harmless here.
        System.setProperty("configd.raft.ownerPoolSize", "2");
        // Heartbeat 100ms << election 1500-3000ms (ratio ~15-30): a concentrated node 0 leading all six
        // groups still heartbeats every follower well inside the election floor, so it never involuntarily
        // sheds leadership through a missed heartbeat, leaving the auto-balance loop as the ONLY thing that
        // can move a leader off node 0.
        System.setProperty("configd.raft.electionTimeoutMinMs", "1500");
        System.setProperty("configd.raft.electionTimeoutMaxMs", "3000");
        System.setProperty("configd.raft.heartbeatIntervalMs", "100");
        System.setProperty("configd.raft.netty.workerThreads", "1");
        System.setProperty("configd.raft.autobalance.intervalMs", "1500");
        System.setProperty("configd.raft.autobalance.jitterPct", "0"); // deterministic cadence for a bounded test
        System.setProperty("configd.raft.autobalance.cooldownMs", "1500");
        System.setProperty("configd.raft.autobalance.imbalanceThreshold", "2"); // production default, unchanged
        System.setProperty("configd.raft.autobalance.instabilityWindowMs", "1000");
        // Each test sets `enabled` itself.
    }

    @AfterEach
    void tearDown() {
        for (ConfigdServer s : running) {
            try {
                s.shutdown();
            } catch (RuntimeException ignored) {
            }
        }
        running.clear();
        for (String p : PROPS) {
            String v = saved.get(p);
            if (v == null) {
                System.clearProperty(p);
            } else {
                System.setProperty(p, v);
            }
        }
    }

    @Test
    void autobalanceOnRebalancesAConcentratedCluster(@TempDir Path root) throws Exception {
        System.setProperty("configd.raft.autobalance.enabled", "true");
        ConfigdServer[] servers = bootCluster(root);

        awaitAllShardsLed(servers);

        concentrateOnNode0(servers);
        int concentrated = awaitConcentration(servers);
        assertTrue(concentrated >= SHARDS - 1,
                "node 0 must lead all-but-at-most-one shard before we test rebalancing (led " + concentrated
                        + " of " + SHARDS + "): " + distribution(servers));
        System.out.println("[G8-BALANCE-ON] concentrated: node 0 leads " + concentrated + "/" + SHARDS
                + " shards (spread " + settledSpread(servers) + ") — " + distribution(servers));

        assertTrue(awaitSpreadAtMost(servers, BALANCED_SPREAD, CONVERGE_MS),
                "the auto-balance loop must converge the concentrated cluster to spread <= " + BALANCED_SPREAD
                        + " within " + CONVERGE_MS + "ms; last seen " + distribution(servers));

        // The convergence was via genuine transfers the loop drove: 6 leaders over 3 nodes cannot reach a
        // spread of 1 without moving at least four leaders off node 0, and each initiation increments this
        // counter on the running server. Sum across nodes (only node 0 sheds while it is the sole
        // max-holder, but summing is robust to any incidental peer shed).
        long transfers = totalTransfersInitiated(servers);
        assertTrue(transfers >= 2,
                "the loop must have initiated genuine leadership transfers (transfers_initiated=" + transfers + ")");
        System.out.println("[G8-BALANCE-ON] converged to spread " + settledSpread(servers)
                + " via " + transfers + " loop-initiated transfers — " + distribution(servers));

        long committed = commitAndAwaitReplication(servers, 0, "cfg/post-balance", "v-post");
        assertTrue(committed > 0, "a post-rebalance write must commit + replicate across the whole cluster");
        System.out.println("[G8-BALANCE-ON] post-rebalance write committed at index " + committed
                + " and replicated on all " + NODES + " nodes");
    }

    @Test
    void killSwitchOffLeavesAConcentratedClusterConcentrated(@TempDir Path root) throws Exception {
        System.setProperty("configd.raft.autobalance.enabled", "false");
        ConfigdServer[] servers = bootCluster(root);

        awaitAllShardsLed(servers);
        concentrateOnNode0(servers);
        int concentrated = awaitConcentration(servers);
        assertTrue(concentrated >= SHARDS - 1,
                "node 0 must lead all-but-at-most-one shard to make the control meaningful (led " + concentrated
                        + " of " + SHARDS + "): " + distribution(servers));
        System.out.println("[G8-BALANCE-OFF] concentrated: node 0 leads " + concentrated + "/" + SHARDS
                + " shards (spread " + settledSpread(servers) + ") — " + distribution(servers));

        // With the loop off, nothing rebalances: over a window comfortably longer than the ON cluster took
        // to converge, the spread must NOT fall to the balanced floor. (awaitSpreadAtMost polls the full
        // window and only returns true if it ever reaches the target, so a false return is the proof that
        // it never did.) This is what isolates the loop as the cause of the ON convergence.
        assertFalse(awaitSpreadAtMost(servers, BALANCED_SPREAD, OBSERVE_MS),
                "without the auto-balance loop the concentrated cluster must NOT self-balance within "
                        + OBSERVE_MS + "ms; " + distribution(servers));
        int endSpread = settledSpread(servers);
        assertTrue(endSpread >= 2,
                "the un-balanced cluster must remain concentrated (spread " + endSpread + "): "
                        + distribution(servers));
        System.out.println("[G8-BALANCE-OFF] after " + OBSERVE_MS + "ms the concentrated cluster stayed at spread "
                + endSpread + " — no self-balancing without the loop: " + distribution(servers));
    }

    private ConfigdServer[] bootCluster(Path root) throws Exception {
        // ONE shared cluster signing key, kept outside every node's data dir (a co-located key would
        // be readable by anyone with access to the data dir, defeating the at-rest integrity
        // guarantee it backs) and pre-created so the concurrent boots never race to mint it.
        Path signingKey = root.resolve("secrets").resolve("signing-key.bin");
        Files.createDirectories(signingKey.getParent());
        SigningKeyStore.loadOrCreate(signingKey);

        int[] bindPorts = reserveDistinctPorts(NODES);
        ConfigdServer[] servers = new ConfigdServer[NODES];
        for (int i = 0; i < NODES; i++) {
            servers[i] = ConfigdServer.start(nodeConfig(i, bindPorts, root.resolve("node-" + i), signingKey));
            running.add(servers[i]);
        }
        return servers;
    }

    private ServerConfig nodeConfig(int nodeId, int[] bindPorts, Path dataDir, Path signingKey) {
        StringBuilder peers = new StringBuilder();
        StringBuilder peerAddrs = new StringBuilder();
        for (int j = 0; j < NODES; j++) {
            if (j == nodeId) {
                continue;
            }
            if (peers.length() > 0) {
                peers.append(',');
                peerAddrs.append(',');
            }
            peers.append(j);
            peerAddrs.append(j).append("=127.0.0.1:").append(bindPorts[j]);
        }
        return ServerConfig.parse(new String[]{
                "--node-id", Integer.toString(nodeId),
                "--data-dir", dataDir.toString(),
                "--peers", peers.toString(),
                "--peer-addresses", peerAddrs.toString(),
                "--bind-port", Integer.toString(bindPorts[nodeId]),
                "--api-port", "0",
                "--signing-key-file", signingKey.toString(),
        });
    }

    /** The single node reporting LEADER for {@code gid}, or -1 (none yet, or a transient split). */
    private static int shardLeader(ConfigdServer[] servers, int gid) {
        int leader = -1;
        for (int i = 0; i < servers.length; i++) {
            RaftNode node = servers[i].driver().getGroup(gid);
            if (node != null && node.monitorView().role() == RaftRole.LEADER) {
                if (leader >= 0) {
                    return -1; // two leaders observed (transient), not settled
                }
                leader = i;
            }
        }
        return leader;
    }

    /** Per-node leader counts over all shards, or {@code null} if any shard is not settled on one leader. */
    private static int[] leaderCountsOrNull(ConfigdServer[] servers) {
        int[] counts = new int[servers.length];
        for (int gid = 0; gid < SHARDS; gid++) {
            int leader = shardLeader(servers, gid);
            if (leader < 0) {
                return null; // a shard is mid-election / split, the whole reading is not settled
            }
            counts[leader]++;
        }
        return counts;
    }

    private static int spread(int[] counts) {
        int max = 0;
        int min = Integer.MAX_VALUE;
        for (int c : counts) {
            max = Math.max(max, c);
            min = Math.min(min, c);
        }
        return max - min;
    }

    /** A settled spread reading, or -1 if the cluster is momentarily unsettled. */
    private static int settledSpread(ConfigdServer[] servers) {
        int[] counts = leaderCountsOrNull(servers);
        return counts == null ? -1 : spread(counts);
    }

    private static String distribution(ConfigdServer[] servers) {
        int[] counts = leaderCountsOrNull(servers);
        if (counts == null) {
            StringBuilder sb = new StringBuilder("leaders=[unsettled ");
            for (int gid = 0; gid < SHARDS; gid++) {
                sb.append('g').append(gid).append(':').append(shardLeader(servers, gid)).append(' ');
            }
            return sb.append(']').toString();
        }
        StringBuilder sb = new StringBuilder("leaders-per-node=[");
        for (int i = 0; i < counts.length; i++) {
            sb.append(i).append(':').append(counts[i]).append(' ');
        }
        return sb.append("] spread=").append(spread(counts)).toString();
    }

    private static void awaitAllShardsLed(ConfigdServer[] servers) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STABILIZE_MS);
        int stable = 0;
        while (System.nanoTime() < end) {
            if (leaderCountsOrNull(servers) != null) {
                if (++stable >= STABLE_OBSERVATIONS) {
                    return;
                }
            } else {
                stable = 0;
            }
            Thread.sleep(POLL_MS);
        }
        throw new AssertionError("not all " + SHARDS + " shards elected a stable leader within " + STABILIZE_MS
                + "ms: " + distribution(servers));
    }

    /**
     * Sweeps repeatedly (rather than one pass) so term bumps stay frequent, holding the ON loop's
     * churn gate off throughout concentration; each sweep also re-issues any transfer that did not land.
     */
    private void concentrateOnNode0(ConfigdServer[] servers) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONCENTRATE_MS);
        while (System.nanoTime() < end) {
            int[] counts = leaderCountsOrNull(servers);
            if (counts != null && counts[0] >= SHARDS) {
                return;
            }
            for (int gid = 0; gid < SHARDS; gid++) {
                int leader = shardLeader(servers, gid);
                if (leader > 0) { // a node other than node 0 leads it, only its leader can move it
                    tryTransfer(servers[leader], gid, NODE0);
                }
            }
            Thread.sleep(POLL_MS);
        }
    }

    /**
     * Best-effort leadership transfer of {@code gid} to {@code target}, driven on the group's owner thread
     * exactly as {@code DriverLeadershipAdmin} does. A failure (no longer leader, target not caught up, a
     * concurrent election) is swallowed, the caller re-sweeps until the leadership actually lands.
     */
    private static void tryTransfer(ConfigdServer from, int gid, NodeId target) {
        MultiRaftDriver driver = from.driver();
        try {
            driver.ownerExecutor(gid).submit(() -> {
                RaftNode node = driver.getGroup(gid);
                return node != null && node.transferLeadership(target);
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private int awaitConcentration(ConfigdServer[] servers) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(10_000);
        int best = 0;
        while (System.nanoTime() < end) {
            int[] counts = leaderCountsOrNull(servers);
            if (counts != null) {
                best = Math.max(best, counts[0]);
                if (best >= SHARDS) {
                    return best;
                }
            }
            Thread.sleep(POLL_MS);
        }
        return best;
    }

    /**
     * Requires several consecutive settled readings at or below {@code target} (not just one) to rule out
     * a transient dip while a shed group is mid-election.
     */
    private static boolean awaitSpreadAtMost(ConfigdServer[] servers, int target, long budgetMs)
            throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        int stable = 0;
        while (System.nanoTime() < end) {
            int s = settledSpread(servers);
            if (s >= 0 && s <= target) {
                if (++stable >= STABLE_OBSERVATIONS) {
                    return true;
                }
            } else {
                stable = 0;
            }
            Thread.sleep(POLL_MS);
        }
        return false;
    }

    private static long totalTransfersInitiated(ConfigdServer[] servers) {
        long total = 0;
        for (ConfigdServer s : servers) {
            total += readCounter(s.scrapeMetrics(), "configd_raft_autobalance_transfers_initiated_total");
        }
        return total;
    }

    private static long readCounter(String scrape, String metricName) {
        for (String line : scrape.split("\n")) {
            if (line.startsWith(metricName + " ")) {
                return (long) Double.parseDouble(line.substring(metricName.length() + 1).trim());
            }
        }
        return 0L;
    }

    private static long appliedIndex(ConfigdServer server, int gid) {
        RaftNode node = server.driver().getGroup(gid);
        return node == null ? -1 : node.monitorView().lastApplied();
    }

    /**
     * Retries across a leadership change so a late auto-balance shed of {@code gid} (the loop is still
     * live) does not fail an in-flight PUT; returns the committed index once all nodes apply it.
     */
    private long commitAndAwaitReplication(ConfigdServer[] servers, int gid, String key, String value)
            throws Exception {
        byte[] cmd = CommandCodec.encodePut(key, value.getBytes(StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REPLICATE_MS);
        long committed = -1;
        while (System.nanoTime() < deadline && committed < 0) {
            int leader = shardLeader(servers, gid);
            if (leader < 0) {
                Thread.sleep(POLL_MS);
                continue;
            }
            MultiRaftDriver driver = servers[leader].driver();
            long before = appliedIndex(servers[leader], gid);
            ProposeOutcome outcome;
            try {
                outcome = driver.ownerExecutor(gid)
                        .submit(() -> driver.propose(gid, cmd)).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                Thread.sleep(POLL_MS);
                continue;
            }
            if (outcome.result() != ProposalResult.ACCEPTED) {
                Thread.sleep(POLL_MS);
                continue;
            }
            while (System.nanoTime() < deadline) {
                long now = appliedIndex(servers[leader], gid);
                if (now > before) {
                    committed = now;
                    break;
                }
                Thread.sleep(POLL_MS);
            }
        }
        assertTrue(committed > 0, "shard " + gid + " leader did not commit '" + key + "' in time");

        final long target = committed;
        for (int i = 0; i < servers.length; i++) {
            final ConfigdServer s = servers[i];
            boolean applied = awaitUntil(deadline, () -> appliedIndex(s, gid) >= target);
            assertTrue(applied, "node " + i + " did not replicate+apply shard " + gid + " up to index "
                    + target + " (got " + appliedIndex(s, gid) + ")");
        }
        return committed;
    }

    private static boolean awaitUntil(long deadlineNanos, java.util.function.BooleanSupplier cond)
            throws InterruptedException {
        while (System.nanoTime() < deadlineNanos) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(POLL_MS);
        }
        return cond.getAsBoolean();
    }

    /** Reserves {@code n} distinct free loopback ports by opening all n at once, then closing them. */
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

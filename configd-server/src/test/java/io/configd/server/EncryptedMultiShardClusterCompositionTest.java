package io.configd.server;

import io.configd.distribution.WatchEvent;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the frozen-format features TOGETHER on a real, in-process, three-node ConfigdServer
 * cluster over loopback TCP: <b>encryption at rest ON x N&gt;1 (3 real nodes) x multi-shard (2
 * shards) x the peer-quorum AnchorWitness armed (strict-boot default) x live watches</b>, plus a
 * real node restart that recovers through the term-versioned anchors and keyring. Each mechanism
 * has its own suite proving it in isolation; this is the interaction test proving they still work
 * when they have to coexist on one running cluster.
 *
 * <h2>What each assertion proves is composed</h2>
 * <ul>
 *   <li><b>Witness x N&gt;1</b>: the witness is armed on every group (real peer addresses mean
 *       {@code tcpTransport != null}, which means {@code armAnchorWitness}). A node cannot start an election
 *       or grant a vote until its strict-boot gate clears at a peer quorum, so a stable elected
 *       leader per shard is itself the proof the boot gate cleared at quorum on a fresh cluster.</li>
 *   <li><b>Encryption x replication</b>: every node persists its WAL/anchor/keyring under AES-256-GCM
 *       (encryption ON). A committed write replicates and applies on ALL THREE nodes (each node's
 *       {@code lastApplied} reaches the committed index), so replication works end-to-end while every
 *       node is writing ciphertext to disk.</li>
 *   <li><b>Multi-shard</b>: writes to shard 0 and shard 1 each commit + replicate on their own group,
 *       and a shard-0 write does not advance shard 1 (independent groups).</li>
 *   <li><b>Watches x replication x encryption</b>: a watch registered on a FOLLOWER of shard 0 fires
 *       when a shard-0 write replicates to it: the change travels from leader to follower over the wire,
 *       applies against an encrypting state machine, and drives the follower's WatchService.</li>
 *   <li><b>Restart recovery</b>: a follower is stopped and restarted from the same data dir; it
 *       recovers through the encrypted keyring + term-versioned anchors (no fail-closed REFUSE, no
 *       false witness rollback), rejoins, and catches its shards' {@code lastApplied} back up.</li>
 * </ul>
 *
 * <p>The Raft wire here is plaintext loopback: this proof isolates the FUNCTIONAL composition
 * (boot-gate quorum, replication, watch, recovery); the witness's anti-spoof guarantee is a
 * separate mTLS concern proven by the transport contract tests. Deadlines are generous for the
 * throttled 2-vCPU box and everything is deadline-polled, no sleep-as-synchronization; the
 * per-method {@link Timeout} is pure hang detection. The election budget is widened via system
 * properties to the ratio proven stable by {@code NettyConsensusLivenessTest} so scheduling jitter
 * on a busy box cannot manufacture spurious churn.
 */
// Pure hang detection: set ABOVE the sum of the internal deadline-poll budgets (2x STABILIZE + the
// REPLICATE calls + WATCH + 2x RESTART ~= 735s worst-case) so a genuinely slow-but-progressing run on
// the throttled box is not aborted as a hang before its own deadline-polls conclude.
@Timeout(780)
class EncryptedMultiShardClusterCompositionTest {

    private static final int NODES = 3;
    private static final int SHARDS = 2;
    private static final int PRIMARY = 0; // the WatchService is bound to the primary group (DEFAULT_RAFT_GROUP)

    private static final long STABILIZE_MS = 120_000;
    private static final long REPLICATE_MS = 45_000;
    private static final long WATCH_MS = 45_000;
    private static final long RESTART_MS = 90_000;
    private static final long POLL_MS = 50;

    private static final String[] PROPS = {
            "configd.raft.encryption.enabled",
            "configd.raft.shardCount",
            "configd.raft.ownerPoolSize",
            "configd.raft.electionTimeoutMinMs",
            "configd.raft.electionTimeoutMaxMs",
            "configd.raft.heartbeatIntervalMs",
            "configd.raft.netty.workerThreads",
            "configd.raft.autobalance.enabled",
    };
    private final Map<String, String> saved = new HashMap<>();

    private final List<ConfigdServer> running = new ArrayList<>();

    @BeforeEach
    void setPosture() {
        for (String p : PROPS) {
            saved.put(p, System.getProperty(p));
        }
        System.setProperty("configd.raft.encryption.enabled", "true");
        System.setProperty("configd.raft.shardCount", Integer.toString(SHARDS));
        System.setProperty("configd.raft.ownerPoolSize", Integer.toString(SHARDS));
        // Generous election budget (ratio ~15-20, the NettyConsensusLivenessTest-proven-stable range):
        // heartbeat 100ms << election 1500-3000ms, so 2-vCPU jitter cannot trip a spurious election
        // while three full servers contend for two cores.
        System.setProperty("configd.raft.electionTimeoutMinMs", "1500");
        System.setProperty("configd.raft.electionTimeoutMaxMs", "3000");
        System.setProperty("configd.raft.heartbeatIntervalMs", "100");
        System.setProperty("configd.raft.netty.workerThreads", "1");
        // This test asserts a stable, fixed leader per shard (including across a kill/restart) on a
        // multi-node multi-shard cluster - exactly the regime the leadership auto-balance loop acts in.
        // Pin the loop off so its 30s-cadence shed cannot race the leadership-stability assertions on a
        // slow box; this is an opt-out for a determinism-sensitive test, not a behavior regression.
        System.setProperty("configd.raft.autobalance.enabled", "false");
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
    void encryptedThreeNodeMultiShardClusterCommitsReplicatesWatchesAndRecovers(@TempDir Path root)
            throws Exception {
        // ONE shared cluster signing key, kept outside every node's data dir (a co-located key would
        // be readable by anyone with access to the data dir, defeating the at-rest integrity
        // guarantee it backs) and pre-created so the three concurrent boots never race to mint it.
        Path signingKey = root.resolve("secrets").resolve("signing-key.bin");
        Files.createDirectories(signingKey.getParent());
        SigningKeyStore.loadOrCreate(signingKey);

        int[] bindPorts = reserveDistinctPorts(NODES);
        ServerConfig[] configs = new ServerConfig[NODES];
        for (int i = 0; i < NODES; i++) {
            configs[i] = nodeConfig(i, bindPorts, root.resolve("node-" + i), signingKey);
        }

        ConfigdServer[] servers = new ConfigdServer[NODES];
        for (int i = 0; i < NODES; i++) {
            servers[i] = ConfigdServer.start(configs[i]);
            running.add(servers[i]);
        }

        // Every node minted the frozen at-rest artifacts under encryption: the dual-slot keyring
        // (131080 B) and the authenticated topology descriptor.
        for (int i = 0; i < NODES; i++) {
            Path data = root.resolve("node-" + i);
            assertEquals(131080L, Files.size(data.resolve("raft-keyring")),
                    "node " + i + " must mint the frozen preallocated keyring under encryption");
            assertTrue(Files.exists(data.resolve("topology-descriptor.dat")),
                    "node " + i + " must persist the authenticated topology descriptor at N>1");
        }

        // The armed strict-boot witness gate must not wrongly block a healthy cluster: with the
        // witness armed on every group, a stable leader per shard is still reachable, i.e. the gate
        // clears at a peer quorum instead of deadlocking progress. (That the gate correctly REFUSES a
        // rolled-back node is a distinct property, proven by the AnchorWitness red-team tests; a
        // successful election here does not by itself prove that enforcement.)
        for (int gid = 0; gid < SHARDS; gid++) {
            int leader = awaitStableLeader(servers, gid, STABILIZE_MS);
            assertTrue(leader >= 0, "shard " + gid + " must elect a single stable leader within "
                    + STABILIZE_MS + "ms (the armed witness gate clears at quorum rather than deadlocking): "
                    + leadershipSnapshot(servers, gid));
        }

        // A write commits on the shard-0 leader and replicates + applies on all three encrypted
        // nodes; a co-committed shard-1 write proves independent multi-shard replication.
        // First let shard 1 fully converge (all nodes at the same applied index) so the cross-shard
        // isolation check below cannot race a still-applying shard-1 leader no-op and spuriously fail.
        assertTrue(awaitUntil(STABILIZE_MS, () -> {
            long[] a = appliedIndexes(servers, 1);
            for (long x : a) {
                if (x != a[0]) {
                    return false;
                }
            }
            return true;
        }), "shard 1 must converge on one applied index before the cross-shard isolation baseline");
        long[] before1 = appliedIndexes(servers, 1);
        long committed0 = commitAndAwaitReplication(servers, 0, "cfg/alpha", "v-alpha");
        assertTrue(committed0 > 0, "shard-0 write must reach a committed index");
        long[] after1beforeWrite = appliedIndexes(servers, 1);
        assertArrayEqualsL(before1, after1beforeWrite,
                "a shard-0 write must not advance any node's shard-1 applied index (cross-shard isolation)");

        long committed1 = commitAndAwaitReplication(servers, 1, "cfg/beta", "v-beta");
        assertTrue(committed1 > 0, "shard-1 write must reach a committed index");

        int leader0 = leaderFor(servers, 0);
        int follower0 = firstFollower(servers, 0, leader0);
        assertTrue(follower0 >= 0, "shard 0 must have at least one follower node");
        CopyOnWriteArrayList<WatchEvent> events = new CopyOnWriteArrayList<>();
        AtomicInteger fired = new AtomicInteger();
        servers[follower0].watchService().register("watched/", ev -> {
            events.add(ev);
            fired.incrementAndGet();
        });

        commitAndAwaitReplication(servers, PRIMARY, "watched/key1", "hello-watch");
        boolean watchFired = awaitUntil(WATCH_MS, () -> events.stream()
                .anyMatch(ev -> ev.affectedKeys().contains("watched/key1")));
        assertTrue(watchFired, "the follower's watch must fire on the replicated shard-0 write; fired="
                + fired.get() + " events=" + events.size());

        // Restart a follower: it recovers through the encrypted keyring + term-versioned anchors and
        // rejoins, catching its shards' applied indexes back up to the cluster.
        // Pick a node that leads NEITHER shard, so dropping it triggers no re-election (the two
        // survivors keep their leadership and still form a 2-of-3 commit quorum). With <=2 leaders
        // across 3 nodes such a node always exists.
        int restartTarget = nodeFollowingAllShards(servers);
        assertTrue(restartTarget >= 0, "a node following every shard must exist to restart cleanly: "
                + leadershipSnapshot(servers, 0) + " " + leadershipSnapshot(servers, 1));
        // Advance the cluster while the node is down, so recovery must reconcile a real gap.
        servers[restartTarget].shutdown();
        running.remove(servers[restartTarget]);

        long postDown0 = commitAndAwaitReplicationExcluding(servers, 0, restartTarget, "cfg/gamma", "v-gamma");
        long postDown1 = commitAndAwaitReplicationExcluding(servers, 1, restartTarget, "cfg/delta", "v-delta");

        // Restart from the SAME data dir + config; recovery through the encrypted anchors/keyring must
        // NOT fail closed and must NOT false-trip the witness rollback (clean shutdown persisted the anchor).
        ConfigdServer restarted = ConfigdServer.start(configs[restartTarget]);
        servers[restartTarget] = restarted;
        running.add(restarted);

        boolean caughtUp0 = awaitUntil(RESTART_MS,
                () -> appliedIndex(restarted, 0) >= postDown0);
        boolean caughtUp1 = awaitUntil(RESTART_MS,
                () -> appliedIndex(restarted, 1) >= postDown1);
        assertTrue(caughtUp0, "restarted node must recover + catch shard 0 up to " + postDown0
                + " (got " + appliedIndex(restarted, 0) + ") — recovery through the encrypted anchors/keyring");
        assertTrue(caughtUp1, "restarted node must recover + catch shard 1 up to " + postDown1
                + " (got " + appliedIndex(restarted, 1) + ")");

        long finalCommit = commitAndAwaitReplication(servers, 0, "cfg/epsilon", "v-epsilon");
        assertTrue(finalCommit > postDown0, "a post-recovery write commits + replicates across the whole cluster");
    }

    /**
     * The encrypted <b>leader-kill</b> failover companion to the follower-restart composition above: on
     * the SAME encrypted 3-node x 2-shard cluster, a genuine <b>shard leader</b> is killed (not a
     * follower): a real leadership change under encryption, co-tested across two shards where the
     * single-shard {@code RealClusterFailoverIT} stops.
     *
     * <p>Proves, all under encryption at rest ON: (1) the two survivors <b>re-elect</b> a NEW leader for
     * every shard the victim led; (2) <b>no committed data is lost</b>: each survivor still holds the
     * pre-kill commit indexes (applied index never regresses); (3) a <b>post-kill write commits</b> on the
     * new leader and replicates to the survivors at a higher index; (4) the killed leader, restarted from
     * its own data dir, <b>recovers by decrypting</b> its term-versioned anchors + keyring (no fail-closed
     * REFUSE) and catches BOTH shards up past the post-kill commits: then the whole restored cluster
     * commits again. The mechanism is proven plaintext + single-shard by {@code RealClusterFailoverIT};
     * this is its encrypted, multi-shard sibling.
     */
    @Test
    void encryptedMultiShardLeaderKillReElectsCommitsWithNoLossAndRejoinDecrypts(@TempDir Path root)
            throws Exception {
        Path signingKey = root.resolve("secrets").resolve("signing-key.bin");
        Files.createDirectories(signingKey.getParent());
        SigningKeyStore.loadOrCreate(signingKey);

        int[] bindPorts = reserveDistinctPorts(NODES);
        ServerConfig[] configs = new ServerConfig[NODES];
        for (int i = 0; i < NODES; i++) {
            configs[i] = nodeConfig(i, bindPorts, root.resolve("node-" + i), signingKey);
        }
        ConfigdServer[] servers = new ConfigdServer[NODES];
        for (int i = 0; i < NODES; i++) {
            servers[i] = ConfigdServer.start(configs[i]);
            running.add(servers[i]);
        }

        for (int gid = 0; gid < SHARDS; gid++) {
            assertTrue(awaitStableLeader(servers, gid, STABILIZE_MS) >= 0,
                    "shard " + gid + " must elect a stable leader before the kill: "
                            + leadershipSnapshot(servers, gid));
        }
        long preKill0 = commitAndAwaitReplication(servers, 0, "cfg/kill-alpha", "v-alpha");
        long preKill1 = commitAndAwaitReplication(servers, 1, "cfg/kill-beta", "v-beta");

        int victim = leaderFor(servers, 0);
        assertTrue(victim >= 0, "shard 0 must have a single stable leader to kill: " + leadershipSnapshot(servers, 0));
        servers[victim].shutdown();
        running.remove(servers[victim]);

        // The two survivors re-elect a NEW leader for shard 0 (and shard 1, whether or not the victim
        // led it): a genuine leadership change, not just a follower rejoin.
        int newLeader0 = awaitStableLeaderExcluding(servers, 0, victim, STABILIZE_MS);
        assertTrue(newLeader0 >= 0 && newLeader0 != victim,
                "the two survivors must elect a NEW shard-0 leader after killing node " + victim + ": "
                        + leadershipSnapshot(servers, 0));
        int newLeader1 = awaitStableLeaderExcluding(servers, 1, victim, STABILIZE_MS);
        assertTrue(newLeader1 >= 0 && newLeader1 != victim,
                "shard 1 must have a live leader among the survivors after the kill: "
                        + leadershipSnapshot(servers, 1));

        // No committed data lost: each survivor still holds the pre-kill commits (applied index is
        // monotonic; a leadership change never rolls a committed entry back).
        for (int i = 0; i < NODES; i++) {
            if (i == victim) {
                continue;
            }
            assertTrue(appliedIndex(servers[i], 0) >= preKill0,
                    "survivor " + i + " must retain the pre-kill shard-0 commit (index " + preKill0
                            + ", got " + appliedIndex(servers[i], 0) + ") — no data loss across the failover");
            assertTrue(appliedIndex(servers[i], 1) >= preKill1,
                    "survivor " + i + " must retain the pre-kill shard-1 commit (index " + preKill1
                            + ", got " + appliedIndex(servers[i], 1) + ")");
        }

        long postKill0 = commitAndAwaitReplicationExcluding(servers, 0, victim, "cfg/kill-gamma", "v-gamma");
        long postKill1 = commitAndAwaitReplicationExcluding(servers, 1, victim, "cfg/kill-delta", "v-delta");
        assertTrue(postKill0 > preKill0, "a post-kill shard-0 write must commit at a higher index on the new leader");
        assertTrue(postKill1 > preKill1, "a post-kill shard-1 write must commit on the survivors");

        ConfigdServer rejoined = ConfigdServer.start(configs[victim]);
        servers[victim] = rejoined;
        running.add(rejoined);
        assertTrue(awaitUntil(RESTART_MS, () -> appliedIndex(rejoined, 0) >= postKill0),
                "the rejoined ex-leader must decrypt its state and catch shard 0 up to " + postKill0
                        + " (got " + appliedIndex(rejoined, 0) + ")");
        assertTrue(awaitUntil(RESTART_MS, () -> appliedIndex(rejoined, 1) >= postKill1),
                "the rejoined ex-leader must catch shard 1 up to " + postKill1
                        + " (got " + appliedIndex(rejoined, 1) + ")");

        long finalCommit = commitAndAwaitReplication(servers, 0, "cfg/kill-epsilon", "v-epsilon");
        assertTrue(finalCommit > postKill0,
                "a post-recovery write commits + replicates across the whole restored cluster");
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

    private static long appliedIndex(ConfigdServer server, int gid) {
        RaftNode node = server.driver().getGroup(gid);
        return node == null ? -1 : node.monitorView().lastApplied();
    }

    private static long[] appliedIndexes(ConfigdServer[] servers, int gid) {
        long[] out = new long[servers.length];
        for (int i = 0; i < servers.length; i++) {
            out[i] = appliedIndex(servers[i], gid);
        }
        return out;
    }

    /** The index of the single node reporting LEADER for {@code gid}, or -1 (none, or a split). */
    private static int leaderFor(ConfigdServer[] servers, int gid) {
        int leader = -1;
        for (int i = 0; i < servers.length; i++) {
            RaftNode node = servers[i].driver().getGroup(gid);
            if (node != null && node.monitorView().role() == RaftRole.LEADER) {
                if (leader >= 0) {
                    return -1;
                }
                leader = i;
            }
        }
        return leader;
    }

    private static int firstFollower(ConfigdServer[] servers, int gid, int leader) {
        for (int i = 0; i < servers.length; i++) {
            if (i != leader && servers[i].driver().getGroup(gid) != null) {
                return i;
            }
        }
        return -1;
    }

    /** A node that is LEADER of no shard (so dropping it forces no re-election), or -1 if none. */
    private static int nodeFollowingAllShards(ConfigdServer[] servers) {
        for (int i = 0; i < servers.length; i++) {
            boolean leadsSomething = false;
            for (int gid = 0; gid < SHARDS; gid++) {
                RaftNode node = servers[i].driver().getGroup(gid);
                if (node != null && node.monitorView().role() == RaftRole.LEADER) {
                    leadsSomething = true;
                    break;
                }
            }
            if (!leadsSomething) {
                return i;
            }
        }
        return -1;
    }

    private static int awaitStableLeader(ConfigdServer[] servers, int gid, long budgetMs)
            throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        int candidate = -1;
        int stable = 0;
        while (System.nanoTime() < end) {
            int leader = leaderFor(servers, gid);
            if (leader >= 0 && leader == candidate) {
                if (++stable >= 10) { // ~0.5s of steady single-leadership
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

    /** {@link #leaderFor} but skipping {@code excluded} (a killed node whose stale monitorView still reads
     *  LEADER after shutdown; reading it would manufacture a spurious split). {@code excluded == -1}
     *  behaves exactly like {@link #leaderFor}. */
    private static int leaderForExcluding(ConfigdServer[] servers, int gid, int excluded) {
        int leader = -1;
        for (int i = 0; i < servers.length; i++) {
            if (i == excluded) {
                continue;
            }
            RaftNode node = servers[i].driver().getGroup(gid);
            if (node != null && node.monitorView().role() == RaftRole.LEADER) {
                if (leader >= 0) {
                    return -1;
                }
                leader = i;
            }
        }
        return leader;
    }

    private static int awaitStableLeaderExcluding(ConfigdServer[] servers, int gid, int excluded, long budgetMs)
            throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        int candidate = -1;
        int stable = 0;
        while (System.nanoTime() < end) {
            int leader = leaderForExcluding(servers, gid, excluded);
            if (leader >= 0 && leader == candidate) {
                if (++stable >= 10) {
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

    private static String leadershipSnapshot(ConfigdServer[] servers, int gid) {
        StringBuilder sb = new StringBuilder("[gid=").append(gid).append(" roles=");
        for (int i = 0; i < servers.length; i++) {
            RaftNode node = servers[i].driver().getGroup(gid);
            sb.append(i).append(':').append(node == null ? "none" : node.monitorView().role()).append(' ');
        }
        return sb.append(']').toString();
    }

    private long commitAndAwaitReplication(ConfigdServer[] servers, int gid, String key, String value)
            throws Exception {
        return commitAndAwaitReplicationExcluding(servers, gid, -1, key, value);
    }

    private long commitAndAwaitReplicationExcluding(ConfigdServer[] servers, int gid, int excluded,
                                                    String key, String value) throws Exception {
        byte[] cmd = CommandCodec.encodePut(key, value.getBytes(StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REPLICATE_MS);
        long committed = -1;
        // Propose on the current leader; retry across leadership changes until accepted + applied. Exclude
        // the intentionally-down node from the leader read: a killed leader's stale monitorView still reads
        // LEADER, which would otherwise read as a permanent split and stall this loop.
        while (System.nanoTime() < deadline && committed < 0) {
            int leader = leaderForExcluding(servers, gid, excluded);
            if (leader < 0 || leader == excluded) {
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
            if (i == excluded) {
                continue;
            }
            final ConfigdServer s = servers[i];
            boolean applied = awaitUntil(deadlineRemainingMs(deadline),
                    () -> appliedIndex(s, gid) >= target);
            assertTrue(applied, "node " + i + " did not replicate+apply shard " + gid + " up to index "
                    + target + " (got " + appliedIndex(s, gid) + ")");
        }
        return committed;
    }

    private static long deadlineRemainingMs(long deadlineNanos) {
        long remain = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        return Math.max(remain, 1_000L); // always allow a floor so a late per-node check still gets a chance
    }

    private interface Cond {
        boolean met();
    }

    private static boolean awaitUntil(long budgetMs, Cond cond) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        while (System.nanoTime() < end) {
            if (cond.met()) {
                return true;
            }
            Thread.sleep(POLL_MS);
        }
        return cond.met();
    }

    private static void assertArrayEqualsL(long[] expected, long[] actual, String msg) {
        assertEquals(expected.length, actual.length, msg);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], msg + " (index " + i + ")");
        }
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

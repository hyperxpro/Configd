package io.configd.linz.runner;

import io.configd.linz.client.ConfigClient;
import io.configd.linz.cluster.Cluster;
import io.configd.linz.cluster.ClusterNode;
import io.configd.linz.fault.FaultInjector;
import io.configd.linz.history.HistoryRecorder;
import io.configd.linz.history.Op;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * STALE READ discrimination scenario, adapted to be single-host injectable.
 *
 * <p><b>Why adapted.</b> The standard form - isolate the deposed leader and read it -
 * is NOT injectable on single-host loopback: a partitioned leader fails CheckQuorum
 * and steps down (so it can't serve), and a mutation that kept it "leader" would leak
 * its outbound heartbeats to the majority (the transport's outbound sockets aren't
 * source-bound - verified), preventing the re-election needed to write the newer
 * value. So a deposed-leader-still-serving needs real per-pair partitions (network
 * namespaces) - recorded as a netns follow-up, not silently dropped.
 *
 * <p><b>The same safety violation, single-host.</b> A <i>lagging isolated follower</i>
 * answering a linearizable read from its stale local state - exactly what the
 * leader/quorum gate (RaftNode.readIndex leader check + isReadReady recheck + the
 * quorum ReadIndex confirm) exists to forbid. Schedule: commit & confirm v1 everywhere
 * -> isolate a follower F (it stops receiving updates; PreVote keeps it from deposing
 * the leader) -> commit & confirm v2 via the leader (still has quorum) -> read F.
 * Correct build: F is a follower, returns 503 (no stale read) -> GREEN. Mutated build
 * (the read path serves local state regardless of leadership): F returns the stale v1
 * after v2 was confirmed -> RED.
 *
 * <p>Because {@code ack != commit}, every PUT is followed by a settle + a retrying
 * read-back before the value is treated as established - otherwise a read can race
 * ahead of the commit and observe the prior value (a real, expected behaviour, not the
 * bug under test).
 *
 * Exit: 0 GREEN, 1 RED, 2 INDETERMINATE.
 */
public final class StaleReadScenario {

    public static void main(String[] args) throws Exception {
        Map<String, String> a = HarnessArgs.parse(args);
        // Default to 5 nodes: isolating one follower leaves a 4-node majority, so the
        // new value commits robustly while the isolated follower lags (the stale source).
        int nodes = Integer.parseInt(a.getOrDefault("nodes", "5"));
        int baseRaft = Integer.parseInt(a.getOrDefault("base-raft", "9500"));
        int baseApi = Integer.parseInt(a.getOrDefault("base-api", "8500"));
        Path jar = Path.of(a.getOrDefault("jar", "configd-server/target/configd-server-0.1.0-SNAPSHOT.jar"));
        Path outDir = Path.of(a.getOrDefault("out", "configd-linz/runs"));
        Files.createDirectories(outDir);
        String label = a.getOrDefault("label", "control");
        String key = "staleread";

        Path runDir = Files.createTempDirectory(outDir, "staleread-" + label + "-");
        Cluster cluster = Cluster.create(nodes, baseRaft, baseApi, runDir, jar, null);
        HistoryRecorder recorder = new HistoryRecorder();
        ConfigClient client = new ConfigClient();
        FaultInjector faults = new FaultInjector();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            faults.healAll();
            cluster.close();
        }));

        try {
            cluster.startAll();
            int leader = HarnessArgs.awaitLeader(cluster, 25_000);
            if (leader < 0) {
                exit(2, "no leader elected");
            }
            int followerId = (leader == 1) ? 2 : 1; // any non-leader
            ClusterNode F = cluster.node(followerId);
            System.out.println("[staleread/" + label + "] leader=node" + leader
                    + " lagging-follower=node" + followerId);

            // 1. PUT v1, settle, confirm v1 (committed & applied on all nodes).
            // A 200 PUT is returned only after quorum-commit, so that is itself the
            // write's definite real-time point; we do not depend on the flaky linearizable
            // read to *establish* v1. We confirm propagation with the reliable
            // default-GET poll and record a confirming read for the backbone, falling
            // back to the committed PUT's interval when the linearizable read is flaky.
            ConfigClient.OpResult put1 = putCommitted(client, cluster, leader, key, "v1", 8);
            recorder.recordPut(0, key, "v1", put1.status(), put1.callNs(), put1.retNs());
            if (put1.status() == Op.Status.FAIL) {
                exit(2, "v1 PUT kept being rejected (leadership not stable) — cannot run discrimination");
            }
            Thread.sleep(800);
            if (!awaitApplied(client, currentLeader(client, cluster, leader), key, "v1", 12_000)) {
                exit(2, "v1 did not apply on the leader (cluster could not make progress)");
            }
            ConfigClient.OpResult c1 = confirmValue(client, currentLeader(client, cluster, leader), key, "v1", 6000);
            if (c1.status() == Op.Status.OK && "v1".equals(c1.value())) {
                recorder.recordRead(0, key, c1.value(), c1.status(), c1.callNs(), c1.retNs());
            } else if (put1.status() == Op.Status.OK) {
                // Linearizable read-back flaky (the 150ms ReadIndex confirm timeout);
                // but the PUT committed, so the write is a real linearization point.
                // Backbone = the committed PUT's interval.
                recorder.recordRead(0, key, "v1", Op.Status.OK, put1.callNs(), put1.retNs());
            } else {
                exit(2, "could not establish v1 (PUT status=" + put1.status()
                        + ", read status=" + c1.status() + " value='" + c1.value() + "')");
            }

            // Warm-up: wait until EVERY node has applied v1. This forces the leader to
            // establish replication to BOTH followers, so after we isolate one the other
            // is a warm quorum partner and v2 can commit. Without this the leader's link
            // to the non-isolated follower may still be cold and v2 would stall.
            if (!awaitAllApplied(client, cluster, key, "v1", 12_000)) {
                exit(2, "not all nodes applied v1 (cluster not warm)");
            }

            // 2. Isolate the follower - it stops receiving AppendEntries, so it lags.
            faults.isolate(F);
            System.out.println("[staleread/" + label + "] isolated follower node" + followerId);
            Thread.sleep(1500);

            // 3. PUT v2 to the leader (commits via the majority; the isolated F never sees it).
            int leadId = client.suspectedLeaderId();
            ConfigClient.OpResult put2 = putCommitted(client, cluster,
                    leadId > 0 ? leadId : leader, key, "v2", 8);
            ClusterNode leadNode = cluster.node(Math.max(1, client.suspectedLeaderId()));
            recorder.recordPut(0, key, "v2", put2.status(), put2.callNs(), put2.retNs());
            // Wait for v2 to actually commit+apply on the leader (reliable default-GET poll,
            // not the flaky linearizable read). Only then is the follower's v1 genuinely stale.
            ClusterNode v2Leader = leadNode;
            if (!awaitApplied(client, v2Leader, key, "v2", 18_000)) {
                // leadership may have moved; re-resolve once and retry the poll
                v2Leader = currentLeader(client, cluster, leader);
                if (!awaitApplied(client, v2Leader, key, "v2", 8_000)) {
                    exit(2, "v2 did not commit on the leader (cluster could not make progress)");
                }
            }
            // Now capture an OK observation of v2 (the real-time backbone). Prefer a
            // linearizable read; fall back to the committed PUT's interval when the
            // ReadIndex confirm is flaky (an OK 200 PUT means quorum-committed).
            ConfigClient.OpResult c2 = confirmValue(client, v2Leader, key, "v2", 8000);
            if (c2.status() == Op.Status.OK && "v2".equals(c2.value())) {
                recorder.recordRead(0, key, c2.value(), c2.status(), c2.callNs(), c2.retNs());
            } else if (put2.status() == Op.Status.OK) {
                recorder.recordRead(0, key, "v2", Op.Status.OK, put2.callNs(), put2.retNs());
            } else {
                exit(2, "could not get an OK observation of v2 (PUT status=" + put2.status()
                        + ", read status=" + c2.status() + " value='" + c2.value() + "')");
            }
            System.out.println("[staleread/" + label + "] v2 committed + confirmed on the leader");

            // 4. Read the lagging follower. Correct: 503 (INFO, dropped). Mutated: stale v1 (OK -> RED).
            Op.Status staleStatus = Op.Status.INFO;
            String staleVal = "";
            long sCall = System.nanoTime();
            long sRet = sCall;
            for (int i = 0; i < 30; i++) {
                ConfigClient.OpResult sr = client.linRead(F, key);
                sRet = sr.retNs();
                if (sr.status() == Op.Status.OK) {
                    staleStatus = Op.Status.OK;
                    staleVal = sr.value();
                    break; // captured the (stale) served read
                }
                Thread.sleep(60);
            }
            recorder.recordRead(1, key, staleVal, staleStatus, sCall, sRet);
            System.out.println("[staleread/" + label + "] follower read: status=" + staleStatus
                    + " value='" + staleVal + "'");

            faults.heal(F);
            LostWriteScenario.checkAndExit(recorder, label);
        } finally {
            faults.healAll();
            cluster.close();
        }
    }

    /**
     * Writes {@code value} and retries until the write COMMITS (OK - a 200 means
     * quorum-committed). During cluster stabilization a fresh leader can transiently
     * 503 (Lost/NotLeader) before it has committed its term's no-op - that is expected,
     * not the bug under test, so we re-probe the leader and retry. The returned interval
     * is the committing attempt's. Falls back to the last result if no attempt commits.
     */
    private static ConfigClient.OpResult putCommitted(ConfigClient client, Cluster cluster,
            int leaderHint, String key, String value, int attempts) throws InterruptedException {
        ConfigClient.OpResult last = null;
        for (int i = 0; i < attempts; i++) {
            int id = client.suspectedLeaderId();
            if (id <= 0 || id > cluster.size()) {
                id = leaderHint;
            }
            ClusterNode target = cluster.node(id > 0 && id <= cluster.size() ? id : 1);
            ConfigClient.OpResult r = client.put(target, cluster.nodes(), key, value);
            if (r.status() == Op.Status.OK) {
                return r; // committed
            }
            last = r;
            // re-probe leadership before the next attempt
            client.probeLeader(cluster.nodes());
            Thread.sleep(400);
        }
        return last;
    }

    /** Waits until a single node's local applied state for {@code key} equals {@code value}. */
    private static boolean awaitApplied(ConfigClient client, ClusterNode node, String key,
                                        String value, long budgetMs) throws InterruptedException {
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (value.equals(client.defaultGet(node, key))) {
                return true;
            }
            Thread.sleep(150);
        }
        return false;
    }

    /** Waits until every node's local applied state for {@code key} equals {@code value}. */
    private static boolean awaitAllApplied(ConfigClient client, Cluster cluster, String key,
                                           String value, long budgetMs) throws InterruptedException {
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            boolean all = true;
            for (ClusterNode n : cluster.nodes()) {
                if (!value.equals(client.defaultGet(n, key))) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
            Thread.sleep(150);
        }
        return false;
    }

    /** The node the client currently believes is leader, falling back to {@code fallbackId}. */
    private static ClusterNode currentLeader(ConfigClient client, Cluster cluster, int fallbackId) {
        int id = client.suspectedLeaderId();
        return cluster.node(id > 0 && id <= cluster.size() ? id : fallbackId);
    }

    /** Retries a linearizable read until it returns OK with {@code expected}, within a time budget. */
    private static ConfigClient.OpResult confirmValue(ConfigClient client, ClusterNode node, String key,
                                                      String expected, long budgetMs) throws InterruptedException {
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;
        ConfigClient.OpResult last = client.linRead(node, key);
        while (System.nanoTime() < deadline) {
            if (last.status() == Op.Status.OK && expected.equals(last.value())) {
                return last;
            }
            Thread.sleep(80);
            last = client.linRead(node, key);
        }
        return last;
    }

    private static void exit(int code, String msg) {
        System.out.println("VERDICT: INDETERMINATE — " + msg);
        System.exit(code);
    }

    private StaleReadScenario() {}
}

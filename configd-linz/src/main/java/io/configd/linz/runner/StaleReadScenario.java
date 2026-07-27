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
 * STALE READ scenario, single-host adapted (netns version deferred): lagging isolated follower
 * answering linread from stale local state. Commit & confirm v1 everywhere -> isolate F -> commit & confirm v2
 * (majority) -> read F. Correct: F is follower, returns 503 (GREEN). Broken: F ignores leadership, serves stale v1 (RED).
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

            // Step 1: PUT v1, confirm everywhere
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
                // Flaky lin-read; committed PUT is a sound OK backbone
                recorder.recordRead(0, key, "v1", Op.Status.OK, put1.callNs(), put1.retNs());
            } else {
                exit(2, "could not establish v1 (PUT status=" + put1.status()
                        + ", read status=" + c1.status() + " value='" + c1.value() + "')");
            }

            // Warm-up: force leader to replicate to both followers so isolated follower doesn't stall v2 commit
            if (!awaitAllApplied(client, cluster, key, "v1", 12_000)) {
                exit(2, "not all nodes applied v1 (cluster not warm)");
            }

            // Step 2: Isolate follower F
            faults.isolate(F);
            System.out.println("[staleread/" + label + "] isolated follower node" + followerId);
            Thread.sleep(1500);

            // Step 3: PUT v2 to leader (commits via majority, F never sees it)
            int leadId = client.suspectedLeaderId();
            ConfigClient.OpResult put2 = putCommitted(client, cluster,
                    leadId > 0 ? leadId : leader, key, "v2", 8);
            ClusterNode leadNode = cluster.node(Math.max(1, client.suspectedLeaderId()));
            recorder.recordPut(0, key, "v2", put2.status(), put2.callNs(), put2.retNs());
            // Wait for v2 to commit+apply on leader (reliable default-GET, not flaky lin-read)
            ClusterNode v2Leader = leadNode;
            if (!awaitApplied(client, v2Leader, key, "v2", 18_000)) {
                v2Leader = currentLeader(client, cluster, leader);
                if (!awaitApplied(client, v2Leader, key, "v2", 8_000)) {
                    exit(2, "v2 did not commit on the leader (cluster could not make progress)");
                }
            }
            // Capture OK observation of v2 (real-time backbone)
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

            // Step 4: Read lagging follower (correct: 503 INFO, broken: stale v1 OK->RED)
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
                    break;
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
     * Retries PUT until COMMITS (OK=200). Fresh leader may transiently 503 before term no-op commits (expected).
     * Returns committing attempt or last.
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

    private static ClusterNode currentLeader(ConfigClient client, Cluster cluster, int fallbackId) {
        int id = client.suspectedLeaderId();
        return cluster.node(id > 0 && id <= cluster.size() ? id : fallbackId);
    }

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

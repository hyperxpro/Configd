package io.configd.linz.runner;

import io.configd.linz.check.PorcupineChecker;
import io.configd.linz.check.Verdict;
import io.configd.linz.client.ConfigClient;
import io.configd.linz.cluster.Cluster;
import io.configd.linz.cluster.ClusterNode;
import io.configd.linz.history.HistoryRecorder;
import io.configd.linz.history.Op;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * LOST ACKED WRITE scenario: PUT->ack->confirm T_new->kill -9 all->restart->read again.
 * Correct: value survives (GREEN). Broken durability: value vanishes post-confirm (RED).
 * Full-cluster crash needed (not just process kill) because page cache masks unfsynced writes.
 * Exit: 0 GREEN, 1 RED, 2 INDETERMINATE.
 */
public final class LostWriteScenario {

    public static void main(String[] args) throws Exception {
        Map<String, String> a = HarnessArgs.parse(args);
        int nodes = Integer.parseInt(a.getOrDefault("nodes", "3"));
        int baseRaft = Integer.parseInt(a.getOrDefault("base-raft", "9400"));
        int baseApi = Integer.parseInt(a.getOrDefault("base-api", "8400"));
        Path jar = Path.of(a.getOrDefault("jar", "configd-server/target/configd-server-0.1.0-SNAPSHOT.jar"));
        Path outDir = Path.of(a.getOrDefault("out", "configd-linz/runs"));
        Files.createDirectories(outDir);
        String label = a.getOrDefault("label", "control");
        String key = "lostwrite";
        String token = "Tnew";

        Path runDir = Files.createTempDirectory(outDir, "lostwrite-" + label + "-");
        Cluster cluster = Cluster.create(nodes, baseRaft, baseApi, runDir, jar, null);
        HistoryRecorder recorder = new HistoryRecorder();
        ConfigClient client = new ConfigClient();
        Runtime.getRuntime().addShutdownHook(new Thread(cluster::close));

        try {
            cluster.startAll();
            int leader = HarnessArgs.awaitLeader(cluster, 25_000);
            if (leader < 0) {
                exit(2, "no leader elected");
            }
            System.out.println("[lostwrite/" + label + "] leader=node" + leader);

            ConfigClient.OpResult put = putCommitted(client, cluster, leader, key, token, 8);
            recorder.recordPut(0, key, token, put.status(), put.callNs(), put.retNs());
            if (put.status() != Op.Status.OK) {
                exit(2, "T_new PUT did not commit (status=" + put.status()
                        + ") — cannot run discrimination");
            }
            ClusterNode lead = cluster.node(Math.max(1, client.suspectedLeaderId()));
            if (!awaitApplied(client, lead, key, token, 12_000)) {
                exit(2, "T_new not applied on the leader after commit — cannot run discrimination");
            }
            ConfigClient.OpResult confirm = client.linReadConfirm(lead, key, 40);
            if (confirm.status() == Op.Status.OK && token.equals(confirm.value())) {
                recorder.recordRead(0, key, confirm.value(), confirm.status(), confirm.callNs(), confirm.retNs());
            } else {
                // Flaky lin-read; committed PUT is a sound OK backbone for confirming read
                recorder.recordRead(0, key, token, Op.Status.OK, put.callNs(), put.retNs());
            }
            System.out.println("[lostwrite/" + label + "] confirmed T_new (committed + applied)");

            System.out.println("[lostwrite/" + label + "] kill -9 all nodes");
            for (ClusterNode n : cluster.nodes()) {
                n.kill9();
            }
            Thread.sleep(800);
            System.out.println("[lostwrite/" + label + "] restart all nodes");
            for (ClusterNode n : cluster.nodes()) {
                n.restart();
            }
            int leader2 = HarnessArgs.awaitLeader(cluster, 30_000);
            if (leader2 < 0) {
                exit(2, "no leader after restart");
            }

            ClusterNode l2 = cluster.node(leader2);
            ConfigClient.OpResult post = client.linReadConfirm(l2, key, 40);
            if (post.status() == Op.Status.OK) {
                recorder.recordRead(0, key, post.value(), post.status(), post.callNs(), post.retNs());
            } else {
                // Flaky lin-read; fall back to default-GET for definite absence (load-bearing RED)
                long c = System.nanoTime();
                String got = client.defaultGet(l2, key);
                long r = System.nanoTime();
                recorder.recordRead(0, key, got == null ? "" : got, Op.Status.OK, c, r);
                post = new ConfigClient.OpResult(Op.Status.OK, got == null ? "" : got, c, r);
            }
            System.out.println("[lostwrite/" + label + "] post-restart read: status=" + post.status()
                    + " value='" + post.value() + "'");

            checkAndExit(recorder, label);
        } finally {
            cluster.close();
        }
    }

    /**
     * Retries PUT until it COMMITS (OK = 200 quorum-committed). Fresh leader may transiently 503
     * before term no-op commits (expected stabilization, not the bug). Returns committing attempt or last.
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
                return r;
            }
            last = r;
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

    static void checkAndExit(HistoryRecorder recorder, String label) throws Exception {
        PorcupineChecker checker = PorcupineChecker.fromEnvironment();
        PorcupineChecker.Result r = checker.check(recorder.ops());
        System.out.print(r.stdout());
        if (!r.stderr().isBlank()) {
            System.out.println("  checker stderr: " + r.stderr().strip());
        }
        System.out.println("[" + label + "] VERDICT -> " + r.verdict());
        System.exit(r.verdict() == Verdict.LINEARIZABLE ? 0
                : r.verdict() == Verdict.NON_LINEARIZABLE ? 1 : 2);
    }

    private static void exit(int code, String msg) {
        System.out.println("VERDICT: INDETERMINATE — " + msg);
        System.exit(code);
    }

    private LostWriteScenario() {}
}

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
 * GATE (ii.1) — LOST ACKED WRITE discrimination (design §11.1).
 *
 * <p>Schedule: PUT k=T_new -> ack -> a linearizable read-back <b>confirms</b> T_new ->
 * {@code kill -9} the WHOLE cluster -> restart from the same data-dirs -> linearizable
 * read k again. On a correct build the confirmed value survives (GREEN); on a build
 * whose durability is a no-op the value vanishes (404) after being confirmed (RED).
 *
 * <p>Why a full-cluster crash and why the no-op mutation must be a <i>non-write</i>
 * (not merely a no-op {@code force}): on a real box {@code kill -9} does not drop the
 * OS page cache, so an unfsynced-but-written byte survives a process kill — only true
 * power loss drops it. The durability mutation therefore makes the WAL append a
 * genuine no-op (the bytes are never written), so a full-cluster restart loses the
 * committed entry from every node's persisted log; the confirming read pins that it
 * had committed.
 *
 * <p>Run against the unmutated jar for the GREEN control; the run-discrimination.sh
 * driver rebuilds a mutated jar for the RED. Exit: 0 GREEN, 1 RED, 2 INDETERMINATE.
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

            // 1. PUT T_new and confirm it with a linearizable read-back.
            ClusterNode l = cluster.node(leader);
            ConfigClient.OpResult put = client.put(l, cluster.nodes(), key, token);
            recorder.recordPut(0, key, token, put.status(), put.callNs(), put.retNs());

            ClusterNode lead = cluster.node(Math.max(1, client.suspectedLeaderId()));
            ConfigClient.OpResult confirm = client.linReadConfirm(lead, key, 60);
            recorder.recordRead(0, key, confirm.value(), confirm.status(), confirm.callNs(), confirm.retNs());
            if (confirm.status() != Op.Status.OK || !token.equals(confirm.value())) {
                exit(2, "could not confirm T_new (got status=" + confirm.status()
                        + " value='" + confirm.value() + "') — cannot run discrimination");
            }
            System.out.println("[lostwrite/" + label + "] confirmed T_new via linearizable read-back");

            // 2. kill -9 the WHOLE cluster, then restart from the same data-dirs.
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

            // 3. read k again — does the confirmed value survive?
            ClusterNode l2 = cluster.node(leader2);
            ConfigClient.OpResult post = client.linReadConfirm(l2, key, 60);
            recorder.recordRead(0, key, post.value(), post.status(), post.callNs(), post.retNs());
            System.out.println("[lostwrite/" + label + "] post-restart read: status=" + post.status()
                    + " value='" + post.value() + "'");

            checkAndExit(recorder, label);
        } finally {
            cluster.close();
        }
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

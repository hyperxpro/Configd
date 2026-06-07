package io.configd.linz.runner;

import io.configd.linz.client.ConfigClient;
import io.configd.linz.cluster.Cluster;

import java.util.HashMap;
import java.util.Map;

/** Small shared helpers for the runner mains. */
final class HarnessArgs {

    static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].startsWith("--")) {
                m.put(args[i].substring(2), args[i + 1]);
            }
        }
        return m;
    }

    /** Polls for an elected leader; returns its node id or -1 on timeout. */
    static int awaitLeader(Cluster cluster, long timeoutMs) throws InterruptedException {
        ConfigClient c = new ConfigClient();
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            int l = c.probeLeader(cluster.nodes());
            if (l > 0) {
                return l;
            }
            Thread.sleep(250);
        }
        return -1;
    }

    /** Polls until the elected leader is some node other than {@code excludeId}; -1 on timeout. */
    static int awaitLeaderOtherThan(Cluster cluster, int excludeId, long timeoutMs) throws InterruptedException {
        ConfigClient c = new ConfigClient();
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            int l = c.probeLeader(cluster.nodes());
            if (l > 0 && l != excludeId) {
                return l;
            }
            Thread.sleep(250);
        }
        return -1;
    }

    private HarnessArgs() {}
}

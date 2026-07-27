package io.configd.linz.runner;

import io.configd.linz.client.ConfigClient;
import io.configd.linz.cluster.Cluster;

import java.util.HashMap;
import java.util.Map;

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

    /**
     * Polls for elected leader via probeLeader; returns node id or -1 on timeout.
     */
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

    private HarnessArgs() {}
}

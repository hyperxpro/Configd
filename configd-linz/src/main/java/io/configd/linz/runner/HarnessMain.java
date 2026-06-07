package io.configd.linz.runner;

import io.configd.linz.check.PorcupineChecker;
import io.configd.linz.check.Verdict;
import io.configd.linz.client.ConfigClient;
import io.configd.linz.cluster.Cluster;
import io.configd.linz.cluster.ClusterNode;
import io.configd.linz.fault.FaultInjector;
import io.configd.linz.history.HistoryRecorder;
import io.configd.linz.history.Op;
import io.configd.linz.schedule.Schedule;
import io.configd.linz.schedule.ScheduleJson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GATE (iii)/(iv) runner: brings up a real {@code n}-node cluster, drives a seeded
 * concurrent workload over a small keyspace while a seeded fault schedule runs
 * continuously, records a faithful client-side history, and checks it with the
 * trusted Porcupine checker.
 *
 * <pre>
 *   HarnessMain --seed S --nodes N [--clients C] [--keys K] [--duration MS]
 *               --jar path/to/configd-server.jar [--base-raft 9300] [--base-api 8300]
 *               [--out DIR]
 * </pre>
 *
 * Exit: 0 LINEARIZABLE, 1 NON-LINEARIZABLE, 2 INDETERMINATE/error.
 */
public final class HarnessMain {

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parse(args);
        long seed = Long.parseLong(a.getOrDefault("seed", "1"));
        int nodes = Integer.parseInt(a.getOrDefault("nodes", "3"));
        int clients = Integer.parseInt(a.getOrDefault("clients", "6"));
        int keys = Integer.parseInt(a.getOrDefault("keys", "4"));
        long duration = Long.parseLong(a.getOrDefault("duration", "20000"));
        int baseRaft = Integer.parseInt(a.getOrDefault("base-raft", "9300"));
        int baseApi = Integer.parseInt(a.getOrDefault("base-api", "8300"));
        int readPct = Integer.parseInt(a.getOrDefault("read-pct", "72"));
        int intervalMs = Integer.parseInt(a.getOrDefault("op-interval", "55"));
        Path jar = Path.of(a.getOrDefault("jar", "configd-server/target/configd-server-0.1.0-SNAPSHOT.jar"));
        Path outDir = Path.of(a.getOrDefault("out", "configd-linz/runs"));
        Files.createDirectories(outDir);

        Schedule schedule = Schedule.generate(seed, nodes, clients, keys, duration, readPct, intervalMs);
        Path schedFile = outDir.resolve("schedule-" + seed + "-n" + nodes + ".json");
        ScheduleJson.write(schedule, schedFile);
        System.out.println("[harness] seed=" + seed + " nodes=" + nodes + " clients=" + clients
                + " keys=" + keys + " duration=" + duration + "ms");
        int totalOps = schedule.workload.stream().mapToInt(List::size).sum();
        System.out.println("[harness] schedule -> " + schedFile + " (" + schedule.faults.size()
                + " faults, " + totalOps + " planned ops)");

        // Reproducibility proof: just (deterministically) generate + write the schedule and exit;
        // two runs of the same seed must produce a byte-identical schedule file.
        if (Boolean.parseBoolean(a.getOrDefault("schedule-only", "false"))) {
            System.exit(0);
        }

        Path runDir = Files.createTempDirectory(outDir, "cluster-" + seed + "-n" + nodes + "-");
        Cluster cluster = Cluster.create(nodes, baseRaft, baseApi, runDir, jar, null);
        FaultInjector faults = new FaultInjector();
        HistoryRecorder recorder = new HistoryRecorder();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            faults.healAll();
            cluster.close();
        }));

        try {
            cluster.startAll();
            int leader = awaitLeader(cluster, 25_000);
            if (leader < 0) {
                System.out.println("VERDICT: INDETERMINATE (no leader elected)");
                System.exit(2);
            }
            System.out.println("[harness] initial leader = node " + leader);

            long t0 = System.nanoTime();

            // ---- workload threads ----
            List<Thread> workers = new ArrayList<>();
            for (int c = 0; c < clients; c++) {
                final int clientId = c;
                final List<Schedule.WorkOp> plan = schedule.workload.get(c);
                Thread t = Thread.ofVirtual().unstarted(
                        () -> runClient(clientId, plan, cluster, recorder, t0, leader));
                workers.add(t);
            }

            // ---- fault thread (sequential single faults) ----
            Thread faultThread = Thread.ofPlatform().unstarted(
                    () -> runFaults(schedule.faults, cluster, faults, t0));

            workers.forEach(Thread::start);
            faultThread.start();

            for (Thread t : workers) {
                t.join();
            }
            faultThread.join();

            // settle: heal anything still active, then let the cluster quiesce briefly
            faults.healAll();
            for (ClusterNode n : cluster.nodes()) {
                if (!n.isAlive()) {
                    n.restart();
                }
            }
            Thread.sleep(500);

            // ---- check ----
            List<Op> ops = recorder.ops();
            Path histFile = outDir.resolve("history-" + seed + "-n" + nodes + ".json");
            io.configd.linz.history.PorcupineHistoryWriter.write(ops, histFile);
            System.out.println("[harness] recorded " + ops.size() + " ops; history -> " + histFile + "; checking...");
            PorcupineChecker checker = PorcupineChecker.fromEnvironment();
            PorcupineChecker.Result r = checker.check(ops);
            System.out.print(r.stdout());
            if (!r.stderr().isBlank()) {
                System.out.println("  checker stderr: " + r.stderr().strip());
            }
            System.out.println("[harness] seed=" + seed + " nodes=" + nodes
                    + " faults=" + schedule.faults.size() + " ops=" + ops.size()
                    + " -> " + r.verdict());
            System.exit(r.verdict() == Verdict.LINEARIZABLE ? 0
                    : r.verdict() == Verdict.NON_LINEARIZABLE ? 1 : 2);
        } finally {
            faults.healAll();
            cluster.close();
        }
    }

    private static void runClient(int clientId, List<Schedule.WorkOp> plan, Cluster cluster,
                                  HistoryRecorder recorder, long t0, int initialLeader) {
        ConfigClient client = new ConfigClient();
        client.probeLeader(cluster.nodes()); // seed leader guess
        for (Schedule.WorkOp op : plan) {
            sleepUntil(t0, op.offsetMs());
            String key = "k" + op.keyIndex();
            int guess = client.suspectedLeaderId() > 0 ? client.suspectedLeaderId() : initialLeader;
            ClusterNode target = cluster.node(clamp(guess, cluster.size()));
            switch (op.kind()) {
                case PUT -> {
                    ConfigClient.OpResult res = client.put(target, cluster.nodes(), key, op.token());
                    recorder.recordPut(clientId, key, op.token(), res.status(), res.callNs(), res.retNs());
                }
                case DELETE -> {
                    ConfigClient.OpResult res = client.delete(target, cluster.nodes(), key);
                    recorder.recordDelete(clientId, key, res.status(), res.callNs(), res.retNs());
                }
                case READ -> {
                    ConfigClient.OpResult res = client.linRead(target, key);
                    recorder.recordRead(clientId, key, res.value(), res.status(), res.callNs(), res.retNs());
                }
            }
        }
    }

    private static void runFaults(List<Schedule.FaultEvent> events, Cluster cluster,
                                  FaultInjector faults, long t0) {
        ConfigClient probe = new ConfigClient();
        for (Schedule.FaultEvent f : events) {
            sleepUntil(t0, f.offsetMs());
            int targetId = f.nodeId();
            if (targetId < 0) { // *_LEADER: resolve at injection time
                targetId = probe.probeLeader(cluster.nodes());
                if (targetId < 0) {
                    continue; // no leader right now; skip this fault
                }
            }
            ClusterNode node = cluster.node(clamp(targetId, cluster.size()));
            try {
                switch (f.kind()) {
                    case ISOLATE_LEADER, ISOLATE_NODE -> {
                        System.out.println("[fault] +" + f.offsetMs() + "ms isolate node " + node.id()
                                + " for " + f.durationMs() + "ms");
                        faults.isolate(node);
                        sleepDur(f.durationMs());
                        faults.heal(node);
                    }
                    case KILL_LEADER, KILL_NODE -> {
                        System.out.println("[fault] +" + f.offsetMs() + "ms kill -9 node " + node.id()
                                + ", restart in " + f.durationMs() + "ms");
                        node.kill9();
                        sleepDur(f.durationMs());
                        node.restart();
                    }
                }
            } catch (Exception e) {
                System.err.println("[fault] error: " + e.getMessage());
            }
        }
    }

    private static int awaitLeader(Cluster cluster, long timeoutMs) throws InterruptedException {
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

    private static int clamp(int id, int size) {
        if (id < 1) {
            return 1;
        }
        return Math.min(id, size);
    }

    private static void sleepUntil(long t0, long offsetMs) {
        long target = t0 + offsetMs * 1_000_000L;
        long remMs = (target - System.nanoTime()) / 1_000_000L;
        if (remMs > 0) {
            try {
                Thread.sleep(remMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void sleepDur(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].startsWith("--")) {
                m.put(args[i].substring(2), args[i + 1]);
            }
        }
        return m;
    }

    private HarnessMain() {}
}

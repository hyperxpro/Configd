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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Real n-node cluster harness: seeded workload + seeded fault schedule, checked via Porcupine.
 * Faults scheduled independently (apply at offsetMs, heal at offsetMs+durationMs).
 * SEQUENTIAL: non-overlapping (one-at-a-time), ADVERSARIAL: overlapping bursts (quorum-breaking).
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
        int maxConcurrent = Integer.parseInt(a.getOrDefault("max-concurrent", "3"));
        Schedule.Mode mode = "adversarial".equalsIgnoreCase(a.getOrDefault("mode", "sequential"))
                ? Schedule.Mode.ADVERSARIAL : Schedule.Mode.SEQUENTIAL;
        Path jar = Path.of(a.getOrDefault("jar", "configd-server/target/configd-server-0.1.0-SNAPSHOT.jar"));
        Path outDir = Path.of(a.getOrDefault("out", "configd-linz/runs"));
        Files.createDirectories(outDir);

        String authToken = a.getOrDefault("auth-token", null);
        boolean encryptAtRest = Boolean.parseBoolean(a.getOrDefault("encrypt-at-rest", "false"));
        long clockSkew = Long.parseLong(a.getOrDefault("clock-skew", "0"));
        Path faketimeLib = a.containsKey("faketime-lib") ? Path.of(a.get("faketime-lib")) : null;
        int shardCount = Integer.parseInt(a.getOrDefault("shards", "1"));
        ClusterNode.Posture base = new ClusterNode.Posture(authToken, encryptAtRest, 0, null, shardCount);
        ClusterNode.Posture skewed = new ClusterNode.Posture(authToken, encryptAtRest, clockSkew, faketimeLib, shardCount);
        final boolean applySkew = clockSkew != 0 && faketimeLib != null;

        Schedule schedule = mode == Schedule.Mode.ADVERSARIAL
                ? Schedule.generateAdversarial(seed, nodes, clients, keys, duration, readPct, intervalMs, maxConcurrent)
                : Schedule.generate(seed, nodes, clients, keys, duration, readPct, intervalMs);
        Path schedFile = outDir.resolve("schedule-" + seed + "-n" + nodes + ".json");
        ScheduleJson.write(schedule, schedFile);
        System.out.println("[harness] seed=" + seed + " nodes=" + nodes + " clients=" + clients
                + " keys=" + keys + " duration=" + duration + "ms mode=" + mode
                + " posture{auth=" + (authToken != null) + " encrypt=" + encryptAtRest
                + " clockSkew=" + (applySkew ? clockSkew + "s@n1" : "none")
                + " shards=" + shardCount + "}");
        int totalOps = schedule.workload.stream().mapToInt(List::size).sum();
        System.out.println("[harness] schedule -> " + schedFile + " (" + schedule.faults.size()
                + " faults, " + totalOps + " planned ops)");

        // Schedule-only: deterministic generation proves reproducibility (byte-identical files on same seed)
        if (Boolean.parseBoolean(a.getOrDefault("schedule-only", "false"))) {
            System.exit(0);
        }

        Path runDir = Files.createTempDirectory(outDir, "cluster-" + seed + "-n" + nodes + "-");
        Cluster cluster = Cluster.create(nodes, baseRaft, baseApi, runDir, jar, null,
                id -> (applySkew && id == 1) ? skewed : base);
        FaultInjector faults = new FaultInjector();
        HistoryRecorder recorder = new HistoryRecorder();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            faults.healAll();
            cluster.close();
        }));

        try {
            cluster.startAll();
            int leader = awaitLeader(cluster, 25_000, authToken);
            if (leader < 0) {
                System.out.println("VERDICT: INDETERMINATE (no leader elected)");
                System.exit(2);
            }
            System.out.println("[harness] initial leader = node " + leader);

            long t0 = System.nanoTime();

            List<Thread> workers = new ArrayList<>();
            for (int c = 0; c < clients; c++) {
                final int clientId = c;
                final List<Schedule.WorkOp> plan = schedule.workload.get(c);
                Thread t = Thread.ofVirtual().unstarted(
                        () -> runClient(clientId, plan, cluster, recorder, t0, leader, authToken));
                workers.add(t);
            }

            ScheduledExecutorService faultPool = Executors.newScheduledThreadPool(Math.max(4, nodes + 2));
            scheduleFaults(schedule.faults, cluster, faults, faultPool, t0, authToken);

            workers.forEach(Thread::start);
            for (Thread t : workers) {
                t.join();
            }

            // Settle: stop scheduling faults, resume paused nodes, heal partitions/loss, restart dead nodes,
            // let cluster quiesce for final read backbone.
            faultPool.shutdownNow();
            faultPool.awaitTermination(10, TimeUnit.SECONDS);
            faults.healAll();
            for (ClusterNode n : cluster.nodes()) {
                n.resume();
                if (!n.isAlive()) {
                    n.restart();
                }
            }
            Thread.sleep(1500);

            // check
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
            System.out.println("[harness] seed=" + seed + " nodes=" + nodes + " mode=" + mode
                    + " faults=" + schedule.faults.size() + " ops=" + ops.size()
                    + " -> " + r.verdict());
            System.exit(r.verdict() == Verdict.LINEARIZABLE ? 0
                    : r.verdict() == Verdict.NON_LINEARIZABLE ? 1 : 2);
        } finally {
            faults.healAll();
            for (ClusterNode n : cluster.nodes()) {
                n.resume();
            }
            cluster.close();
        }
    }

    private static void runClient(int clientId, List<Schedule.WorkOp> plan, Cluster cluster,
                                  HistoryRecorder recorder, long t0, int initialLeader, String authToken) {
        ConfigClient client = new ConfigClient(java.time.Duration.ofSeconds(3), authToken);
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

    /**
     * Schedules each fault independently (apply at offset, heal at offset+duration), allowing overlapping.
     * *_LEADER kinds resolve to current leader at apply time and pin it for heal.
     */
    private static void scheduleFaults(List<Schedule.FaultEvent> events, Cluster cluster,
                                       FaultInjector faults, ScheduledExecutorService pool,
                                       long t0, String authToken) {
        ConfigClient probe = new ConfigClient(java.time.Duration.ofSeconds(2), authToken);
        for (Schedule.FaultEvent f : events) {
            long applyDelayMs = Math.max(0, f.offsetMs() - elapsedMs(t0));
            pool.schedule(() -> applyFault(f, cluster, faults, probe, pool), applyDelayMs, TimeUnit.MILLISECONDS);
        }
    }

    private static void applyFault(Schedule.FaultEvent f, Cluster cluster, FaultInjector faults,
                                   ConfigClient probe, ScheduledExecutorService pool) {
        int targetId = f.nodeId();
        if (targetId < 0) {
            // *_LEADER: resolve to current leader at apply time
            targetId = probe.probeLeader(cluster.nodes());
            if (targetId < 0) {
                return;
            }
        }
        final ClusterNode node = cluster.node(clamp(targetId, cluster.size()));
        // Heal: separate scheduled task (doesn't block pool thread). Leftover on pool shutdown is swept by final settle.
        try {
            switch (f.kind()) {
                case ISOLATE_LEADER, ISOLATE_NODE -> {
                    log(f, "isolate node " + node.id());
                    faults.isolate(node);
                    scheduleHeal(pool, f.durationMs(), () -> faults.heal(node));
                }
                case KILL_LEADER, KILL_NODE -> {
                    log(f, "kill -9 node " + node.id() + ", restart in " + f.durationMs() + "ms");
                    node.kill9();
                    scheduleHeal(pool, f.durationMs(), node::restart);
                }
                case PAUSE_LEADER, PAUSE_NODE -> {
                    log(f, "SIGSTOP node " + node.id() + ", SIGCONT in " + f.durationMs() + "ms");
                    node.pause();
                    scheduleHeal(pool, f.durationMs(), node::resume);
                }
                case LOSS_NODE -> {
                    log(f, "packet loss " + f.param() + "% on node " + node.id());
                    faults.lossy(node, f.param());
                    scheduleHeal(pool, f.durationMs(), () -> faults.healLossy(node, f.param()));
                }
            }
        } catch (Exception e) {
            System.err.println("[fault] error applying " + f.kind() + ": " + e.getMessage());
        }
    }

    private static void scheduleHeal(ScheduledExecutorService pool, long durationMs, ThrowingRunnable heal) {
        try {
            pool.schedule(() -> {
                try {
                    heal.run();
                } catch (Exception e) {
                    System.err.println("[fault] heal error: " + e.getMessage());
                }
            }, durationMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void log(Schedule.FaultEvent f, String what) {
        System.out.println("[fault] +" + f.offsetMs() + "ms " + what + " (dur " + f.durationMs() + "ms)");
    }

    private static int awaitLeader(Cluster cluster, long timeoutMs, String authToken) throws InterruptedException {
        ConfigClient c = new ConfigClient(java.time.Duration.ofSeconds(2), authToken);
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

    private static long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
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

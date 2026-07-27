package io.configd.bench;

import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.raft.InMemoryRaftCluster;
import io.configd.raft.ProposeOutcome;
import io.configd.raft.RaftNode;
import io.configd.store.CommandCodec;

import org.HdrHistogram.Histogram;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

public final class WriteCommitDriver {

    private WriteCommitDriver() {}

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: WriteCommitDriver <bakeoff|commit-latency> ...");
            System.exit(2);
        }
        switch (args[0]) {
            case "bakeoff" -> bakeoff(args);
            case "commit-latency" -> commitLatency(args);
            default -> {
                System.err.println("unknown mode: " + args[0]);
                System.exit(2);
            }
        }
    }

    private static void bakeoff(String[] args) {
        int clusterSize = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        int durationSec = args.length > 2 ? Integer.parseInt(args[2]) : 30;
        int valueBytes = args.length > 3 ? Integer.parseInt(args[3]) : 256;

        InMemoryRaftCluster cluster = InMemoryRaftCluster.realStateMachines(clusterSize);
        cluster.electLeader();
        RaftNode leader = cluster.leader();

        byte[] value = new byte[valueBytes];
        ThreadLocalRandom.current().nextBytes(value);

        // Time-bounded warmup: fixed-count dominates on throttled boxes; cap at ~10s.
        long warmDeadline = System.nanoTime() + 10_000_000_000L;
        long warmI = 0;
        while (System.nanoTime() < warmDeadline) {
            driveOneCommit(cluster, leader, key(warmI++), value);
        }

        long deadline = System.nanoTime() + durationSec * 1_000_000_000L;
        long ops = 0;
        long bytesProposed = 0;
        long start = System.nanoTime();
        while (System.nanoTime() < deadline) {
            for (int b = 0; b < 1000; b++) {
                byte[] cmd = CommandCodec.encodePut(key(ops), value);
                bytesProposed += cmd.length;
                ProposeOutcome out = leader.propose(cmd);
                if (out.accepted()) {
                    cluster.driveToCommit(out.index());
                }
                ops++;
            }
        }
        long elapsedNs = System.nanoTime() - start;
        double sec = elapsedNs / 1e9;
        double opsPerSec = ops / sec;
        double mbProposed = bytesProposed / (1024.0 * 1024.0);

        System.out.printf("BAKEOFF-RESULT clusterSize=%d durationSec=%.2f valueBytes=%d ops=%d ops_per_sec=%.0f cmd_bytes_total_MB=%.2f cmd_bytes_per_op=%d%n",
                clusterSize, sec, valueBytes, ops, opsPerSec, mbProposed, bytesProposed / ops);
        System.out.printf("BAKEOFF-NOTE allocation+GC-pause distribution come from the -Xlog:gc* log; this line is the throughput half of the per-collector table.%n");
    }

    private static void commitLatency(String[] args) {
        int clusterSize = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        int warmupOps = args.length > 2 ? Integer.parseInt(args[2]) : 100_000;
        int measureOps = args.length > 3 ? Integer.parseInt(args[3]) : 500_000;
        int valueBytes = args.length > 4 ? Integer.parseInt(args[4]) : 256;

        InMemoryRaftCluster cluster = InMemoryRaftCluster.realStateMachines(clusterSize);
        cluster.electLeader();
        RaftNode leader = cluster.leader();

        byte[] value = new byte[valueBytes];
        ThreadLocalRandom.current().nextBytes(value);

        for (int i = 0; i < warmupOps; i++) {
            driveOneCommit(cluster, leader, key(i), value);
        }

        Histogram h = new Histogram(1L, 60_000_000_000L, 3);
        for (int i = 0; i < measureOps; i++) {
            byte[] cmd = CommandCodec.encodePut(key((long) warmupOps + i), value);
            long t0 = System.nanoTime();
            ProposeOutcome out = leader.propose(cmd);
            if (out.accepted()) {
                cluster.driveToCommit(out.index());
            }
            long dt = System.nanoTime() - t0;
            if (dt < 1) dt = 1;
            h.recordValue(dt);
        }

        System.out.printf("COMMIT-LATENCY clusterSize=%d valueBytes=%d samples=%d (in-memory transport+storage; CPU-only local quorum cost, no network, no fsync)%n",
                clusterSize, valueBytes, h.getTotalCount());
        printHistogramMicros(h);
    }

    private static void driveOneCommit(InMemoryRaftCluster cluster, RaftNode leader, String key, byte[] value) {
        byte[] cmd = CommandCodec.encodePut(key, value);
        ProposeOutcome out = leader.propose(cmd);
        if (out.accepted()) {
            cluster.driveToCommit(out.index());
        }
    }

    private static String key(long i) {
        return "config/service/bench/" + (i & 0xFFFFF); // Bounded space for HAMT overwrite churn.
    }

    private static void printHistogramMicros(Histogram h) {
        // Convert ns to us (commit is sub-ms locally).
        System.out.printf("COMMIT-HISTOGRAM unit=us count=%d p50=%.3f p99=%.3f p999=%.3f p9999=%.3f max=%.3f mean=%.3f%n",
                h.getTotalCount(),
                h.getValueAtPercentile(50.0) / 1000.0,
                h.getValueAtPercentile(99.0) / 1000.0,
                h.getValueAtPercentile(99.9) / 1000.0,
                h.getValueAtPercentile(99.99) / 1000.0,
                h.getMaxValue() / 1000.0,
                h.getMean() / 1000.0);
        // Thin tail indicates low-confidence percentiles.
        long n = h.getTotalCount();
        long above999 = countAbovePercentile(h, 99.9);
        long above9999 = countAbovePercentile(h, 99.99);
        System.out.printf("COMMIT-TAIL-SAMPLES total=%d above_p999=%d above_p9999=%d%n", n, above999, above9999);
    }

    private static long countAbovePercentile(Histogram h, double pct) {
        long threshold = h.getValueAtPercentile(pct);
        long count = 0;
        for (var v : h.recordedValues()) {
            if (v.getValueIteratedTo() >= threshold) {
                count += v.getCountAtValueIteratedTo();
            }
        }
        return count;
    }
}

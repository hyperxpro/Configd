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

/**
 * Session 5 / Workstream B — write-path bake-off and <b>local quorum-commit
 * latency</b> driver, on a real in-memory 3-node (or 5-node) Raft cluster wired with
 * {@link InMemoryRaftCluster}. Each node runs a real {@code ConfigStateMachine} over a
 * {@code VersionedConfigStore}, so a committed write actually decodes the command
 * ({@link CommandCodec}) and applies a HAMT {@code put} — the realistic ~2–5 KB/op
 * allocation profile of the production write path (not a no-op state machine).
 *
 * <h2>Two modes (subcommand = arg[0])</h2>
 * <ul>
 *   <li><b>{@code bakeoff}</b> — drive the cluster as fast as the CPU allows for a fixed
 *       wall-clock duration; print achieved ops + ops/s + total bytes proposed. This is
 *       a <em>closed-loop allocation generator</em>: it is run UNDER {@code -Xlog:gc*}
 *       and the JVM's own GC accounting so the comparison is allocation-rate / GC-pause
 *       distribution / throughput per collector. Closed-loop is correct here because we
 *       are NOT reporting a latency percentile — only allocation/GC/throughput, for which
 *       coordinated omission does not apply (methodology §3b: "A closed-loop driver MAY be
 *       used to find the saturation throughput").</li>
 *   <li><b>{@code commit-latency}</b> — measure the per-commit CPU cost of the local
 *       quorum (propose → append → in-memory replicate → commit → apply) as an HdrHistogram
 *       SampleTime-style distribution. This is the {@code local_commit_component} of
 *       methodology §2's cross-region model. <b>No real network, no fsync</b> (in-memory
 *       transport + in-memory storage) — the number is the in-process consensus CPU cost,
 *       stated as such (LOCAL-VERIFIED for the local component only).</li>
 * </ul>
 *
 * <h2>Why this is not the tick-driven JMH benchmark</h2>
 * {@link RaftCommitBenchmark} ticks a fixed number of times per op and uses a no-op state
 * machine; it is ideal for the GC pause character but under-drives allocation (few GCs per
 * fork). This driver runs a real apply and a tight real-time loop, so allocation accumulates
 * and the GC log carries a populated pause distribution — the methodology's "no
 * ZGC-because-low-pause without the pause histogram" requirement.
 *
 * <h2>Cross-region note</h2>
 * This driver measures ONLY the local component. The cross-region total is
 * {@code local_commit_component + RTT(quorum)} per methodology §2 and is computed in the
 * result doc, labelled {@code ENV-BLOCKED (M-1)} / {@code PENDING real-hardware confirmation}.
 *
 * <h2>Invocation</h2>
 * <pre>
 *   java --enable-preview -cp configd-testkit/target/benchmarks.jar \
 *       io.configd.bench.WriteCommitDriver bakeoff        &lt;clusterSize&gt; &lt;durationSec&gt; &lt;valueBytes&gt;
 *   java --enable-preview -cp configd-testkit/target/benchmarks.jar \
 *       io.configd.bench.WriteCommitDriver commit-latency &lt;clusterSize&gt; &lt;warmupOps&gt; &lt;measureOps&gt; &lt;valueBytes&gt;
 * </pre>
 */
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

    // ------------------------------------------------------------------
    // bakeoff: closed-loop allocation generator (collector comparison)
    // ------------------------------------------------------------------
    private static void bakeoff(String[] args) {
        int clusterSize = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        int durationSec = args.length > 2 ? Integer.parseInt(args[2]) : 30;
        int valueBytes = args.length > 3 ? Integer.parseInt(args[3]) : 256;

        InMemoryRaftCluster cluster = InMemoryRaftCluster.realStateMachines(clusterSize);
        cluster.electLeader();
        RaftNode leader = cluster.leader();

        byte[] value = new byte[valueBytes];
        ThreadLocalRandom.current().nextBytes(value);

        // Warm the JIT a little before the timed window so the first GCs are steady-state.
        // Time-bounded (not a fixed op count): the first ops run interpreted and are slow on
        // a throttled box, so a fixed-count warmup can dominate wall-clock; cap it at ~10 s.
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
            // batch a chunk between clock reads to keep the loop tight
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

    // ------------------------------------------------------------------
    // commit-latency: per-op HdrHistogram of the local quorum-commit cost
    // ------------------------------------------------------------------
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

        // Warmup (JIT + steady-state log growth) — not recorded.
        for (int i = 0; i < warmupOps; i++) {
            driveOneCommit(cluster, leader, key(i), value);
        }

        // HdrHistogram in NANOSECONDS: 1 ns .. 60 s, 3 sig digits.
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

    // ------------------------------------------------------------------
    // shared helpers
    // ------------------------------------------------------------------

    /** Encode a real PUT command and drive it to commit (decode + HAMT apply on every node). */
    private static void driveOneCommit(InMemoryRaftCluster cluster, RaftNode leader, String key, byte[] value) {
        byte[] cmd = CommandCodec.encodePut(key, value);
        ProposeOutcome out = leader.propose(cmd);
        if (out.accepted()) {
            cluster.driveToCommit(out.index());
        }
    }

    private static String key(long i) {
        return "config/service/bench/" + (i & 0xFFFFF); // bounded key-space -> overwrite churn
    }

    private static void printHistogramMicros(Histogram h) {
        // Report in microseconds (commit is sub-ms locally); HdrHistogram stores ns.
        System.out.printf("COMMIT-HISTOGRAM unit=us count=%d p50=%.3f p99=%.3f p999=%.3f p9999=%.3f max=%.3f mean=%.3f%n",
                h.getTotalCount(),
                h.getValueAtPercentile(50.0) / 1000.0,
                h.getValueAtPercentile(99.0) / 1000.0,
                h.getValueAtPercentile(99.9) / 1000.0,
                h.getValueAtPercentile(99.99) / 1000.0,
                h.getMaxValue() / 1000.0,
                h.getMean() / 1000.0);
        // Tail-bin sample counts (methodology §3a F1: a thin tail is low-confidence).
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

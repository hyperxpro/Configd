package io.configd.linz.schedule;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * A fully-deterministic fault + workload plan derived purely from a seed (design
 * §9). Because the binary has no determinism seam (election RNG is nanoTime-seeded),
 * reproducibility is of the <b>inputs</b> — which faults and ops at which logical
 * offsets — not of which node wins. Two runs of the same seed therefore produce a
 * byte-identical {@code schedule-<seed>.json}; the recorded history differs by design.
 *
 * <p>Faults are <b>sequential single faults</b> (each heals before the next begins),
 * so at most one node is ever faulted — quorum is preserved (n &ge; 3), keeping the
 * cluster available while still continuously adversarial. A correct system stays
 * linearizable throughout.
 */
public final class Schedule {

    public enum OpKind { PUT, READ, DELETE }

    public enum FaultKind { ISOLATE_LEADER, ISOLATE_NODE, KILL_LEADER, KILL_NODE }

    /** One client operation at a logical offset (ms from t0). {@code token} is the PUT value. */
    public record WorkOp(long offsetMs, OpKind kind, int keyIndex, String token) {}

    /** One scheduled fault. {@code nodeId} is the target for *_NODE kinds; -1 means "the current leader". */
    public record FaultEvent(long offsetMs, FaultKind kind, int nodeId, long durationMs) {}

    public final long seed;
    public final int nodes;
    public final int clients;
    public final int keys;
    public final long durationMs;
    public final List<FaultEvent> faults;
    public final List<List<WorkOp>> workload; // per client

    public Schedule(long seed, int nodes, int clients, int keys, long durationMs,
                    List<FaultEvent> faults, List<List<WorkOp>> workload) {
        this.seed = seed;
        this.nodes = nodes;
        this.clients = clients;
        this.keys = keys;
        this.durationMs = durationMs;
        this.faults = faults;
        this.workload = workload;
    }

    public static Schedule generate(long seed, int nodes, int clients, int keys, long durationMs) {
        return generate(seed, nodes, clients, keys, durationMs, 72, 55);
    }

    /**
     * @param readPct       percent of ops that are reads (the rest are 90% PUT / 10% DELETE).
     *                      The gate uses a read-heavy mix (72) so the confirm-bound pins most
     *                      writes and Porcupine stays tractable; lower values stress the checker.
     * @param intervalBase  base ms between a client's ops (a 0..+intervalBase jitter is added).
     */
    public static Schedule generate(long seed, int nodes, int clients, int keys, long durationMs,
                                    int readPct, int intervalBase) {
        SplittableRandom root = new SplittableRandom(seed);
        SplittableRandom faultRng = root.split();
        SplittableRandom workRoot = root.split();
        int putCut = readPct + (100 - readPct) * 9 / 10; // 90% of the non-reads are PUT

        // ---- workload: each client a steady, jittered op stream ----
        List<List<WorkOp>> workload = new ArrayList<>();
        for (int c = 0; c < clients; c++) {
            SplittableRandom r = workRoot.split();
            List<WorkOp> ops = new ArrayList<>();
            long t = 50 + r.nextLong(60);   // small per-client stagger
            int seq = 0;
            while (t < durationMs) {
                int roll = r.nextInt(100);
                OpKind kind = roll < readPct ? OpKind.READ : (roll < putCut ? OpKind.PUT : OpKind.DELETE);
                int key = r.nextInt(keys);
                String token = kind == OpKind.PUT ? ("s" + seed + ":c" + c + ":" + seq) : "";
                ops.add(new WorkOp(t, kind, key, token));
                seq++;
                t += intervalBase + r.nextInt(Math.max(1, intervalBase / 2 + 13));
            }
            workload.add(ops);
        }

        // ---- faults: sequential single faults after the cluster settles ----
        List<FaultEvent> faults = new ArrayList<>();
        long t = 2500 + faultRng.nextLong(800);
        while (t < durationMs - 1500) {
            FaultKind kind = switch (faultRng.nextInt(4)) {
                case 0 -> FaultKind.ISOLATE_LEADER;
                case 1 -> FaultKind.ISOLATE_NODE;
                case 2 -> FaultKind.KILL_LEADER;
                default -> FaultKind.KILL_NODE;
            };
            int nodeId = (kind == FaultKind.ISOLATE_NODE || kind == FaultKind.KILL_NODE)
                    ? 1 + faultRng.nextInt(nodes) : -1;
            long dur = 900 + faultRng.nextLong(1600);
            faults.add(new FaultEvent(t, kind, nodeId, dur));
            t += dur + 700 + faultRng.nextLong(1000);   // gap after heal/restart
        }

        return new Schedule(seed, nodes, clients, keys, durationMs, faults, workload);
    }
}

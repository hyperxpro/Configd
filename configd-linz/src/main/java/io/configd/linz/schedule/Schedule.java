package io.configd.linz.schedule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;

/**
 * A fully-deterministic fault + workload plan derived purely from a seed. Because the
 * binary has no determinism seam (election RNG is nanoTime-seeded), reproducibility
 * is of the <b>inputs</b> - which faults and ops at which logical offsets - not of
 * which node wins. Two runs of the same seed therefore produce a byte-identical
 * {@code schedule-<seed>.json}; the recorded history differs by design.
 *
 * <p>Two generation modes:
 * <ul>
 *   <li>{@link Mode#SEQUENTIAL} - the original gate schedule: sequential single faults
 *       (each heals before the next begins), at most one node faulted at a time, so quorum
 *       is preserved ({@code n >= 3}) and the cluster stays continuously available. A correct
 *       system stays linearizable throughout. This is the fast CI/reproducibility schedule.</li>
 *   <li>{@link Mode#ADVERSARIAL} - the Jepsen-grade schedule: overlapping <b>combination</b>
 *       faults drawn from the full nemesis set (isolate, kill+restart, SIGSTOP/SIGCONT pause,
 *       probabilistic packet loss), targeting multiple nodes at once. It deliberately breaks
 *       quorum in bursts (total unavailability windows) separated by recovery windows. During a
 *       quorum-loss window a correct system serves NO linearizable read/commit (they become
 *       indeterminate and are dropped); the ops that DO complete must still be linearizable, and
 *       the system must recover cleanly. The fault timers run concurrently, so faults overlap.</li>
 * </ul>
 */
public final class Schedule {

    public enum OpKind { PUT, READ, DELETE }

    /**
     * Fault classes. {@code *_LEADER} kinds resolve their target to the current leader at
     * injection time; {@code *_NODE} kinds target a fixed node id.
     * <ul>
     *   <li>ISOLATE_* - full REJECT on the node's raft port (symmetric quorum-isolation).</li>
     *   <li>KILL_* - SIGKILL, then restart-into-the-live-cluster after the duration (the
     *       transport-rejoin + WAL-recovery path).</li>
     *   <li>PAUSE_* - SIGSTOP (freeze, sockets stay open), then SIGCONT after the duration
     *       (the stale-leader / stop-the-world nemesis).</li>
     *   <li>LOSS_NODE - probabilistic packet loss ({@code param}%) on the node's raft port.</li>
     * </ul>
     */
    public enum FaultKind {
        ISOLATE_LEADER, ISOLATE_NODE,
        KILL_LEADER, KILL_NODE,
        PAUSE_LEADER, PAUSE_NODE,
        LOSS_NODE
    }

    public enum Mode { SEQUENTIAL, ADVERSARIAL }

    /** One client operation at a logical offset (ms from t0). {@code token} is the PUT value. */
    public record WorkOp(long offsetMs, OpKind kind, int keyIndex, String token) {}

    /**
     * One scheduled fault. {@code nodeId} is the target for {@code *_NODE} kinds; -1 means
     * "the current leader". {@code param} is kind-specific (the loss percentage for LOSS_NODE;
     * 0 and unused for other kinds).
     */
    public record FaultEvent(long offsetMs, FaultKind kind, int nodeId, long durationMs, int param) {}

    public final long seed;
    public final int nodes;
    public final int clients;
    public final int keys;
    public final long durationMs;
    public final Mode mode;
    public final List<FaultEvent> faults;
    public final List<List<WorkOp>> workload; // per client

    public Schedule(long seed, int nodes, int clients, int keys, long durationMs, Mode mode,
                    List<FaultEvent> faults, List<List<WorkOp>> workload) {
        this.seed = seed;
        this.nodes = nodes;
        this.clients = clients;
        this.keys = keys;
        this.durationMs = durationMs;
        this.mode = mode;
        this.faults = faults;
        this.workload = workload;
    }

    public static Schedule generate(long seed, int nodes, int clients, int keys, long durationMs) {
        return generate(seed, nodes, clients, keys, durationMs, 72, 55);
    }

    /**
     * The original SEQUENTIAL gate schedule.
     *
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

        List<List<WorkOp>> workload = buildWorkload(workRoot, clients, keys, durationMs, seed, readPct, intervalBase);

        // faults: sequential single faults after the cluster settles
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
            faults.add(new FaultEvent(t, kind, nodeId, dur, 0));
            t += dur + 700 + faultRng.nextLong(1000);   // gap after heal/restart
        }

        return new Schedule(seed, nodes, clients, keys, durationMs, Mode.SEQUENTIAL, faults, workload);
    }

    /**
     * The ADVERSARIAL Jepsen-grade schedule: overlapping combination faults from the full nemesis
     * set, in bursts that may break quorum, separated by recovery windows. Faults within a burst
     * target DISTINCT nodes (a node is never double-faulted at the same instant) and overlap in time.
     * The default op mix is write-heavier (lower readPct) than the sequential gate to sharpen the
     * checker's discrimination.
     *
     * @param maxConcurrent upper bound on faults active in one burst (clamped to n-1 so at least one
     *                      node is always fault-free at the START of a burst; overlap still lets a
     *                      burst reach total unavailability when a resolved leader coincides).
     */
    public static Schedule generateAdversarial(long seed, int nodes, int clients, int keys, long durationMs,
                                               int readPct, int intervalBase, int maxConcurrent) {
        SplittableRandom root = new SplittableRandom(seed);
        SplittableRandom faultRng = root.split();
        SplittableRandom workRoot = root.split();

        List<List<WorkOp>> workload = buildWorkload(workRoot, clients, keys, durationMs, seed, readPct, intervalBase);

        int burstCap = Math.max(1, Math.min(maxConcurrent, nodes - 1));
        List<FaultEvent> faults = new ArrayList<>();
        long t = 2500 + faultRng.nextLong(800);   // let the cluster settle first
        while (t < durationMs - 2000) {
            int burst = 1 + faultRng.nextInt(burstCap);
            // Distinct node targets for this burst (shuffled 1..n); *_LEADER faults use -1 and do
            // not consume a fixed target, so they can coincide with a *_NODE fault on the same node.
            List<Integer> pool = new ArrayList<>();
            for (int k = 1; k <= nodes; k++) {
                pool.add(k);
            }
            shuffle(pool, faultRng);
            int poolIdx = 0;
            long burstEnd = t;
            for (int b = 0; b < burst; b++) {
                FaultKind kind = ADVERSARIAL_KINDS[faultRng.nextInt(ADVERSARIAL_KINDS.length)];
                boolean leaderKind = kind == FaultKind.ISOLATE_LEADER || kind == FaultKind.KILL_LEADER
                        || kind == FaultKind.PAUSE_LEADER;
                int nodeId;
                if (leaderKind) {
                    nodeId = -1;
                } else if (poolIdx < pool.size()) {
                    nodeId = pool.get(poolIdx++);
                } else {
                    continue; // ran out of distinct nodes for *_NODE faults this burst
                }
                long stagger = faultRng.nextLong(400);          // faults in a burst start within 400ms
                long dur = 700 + faultRng.nextLong(2200);        // 0.7 - 2.9s, deliberately overlapping
                int param = kind == FaultKind.LOSS_NODE ? 10 + faultRng.nextInt(41) : 0; // 10-50% loss
                long off = t + stagger;
                faults.add(new FaultEvent(off, kind, nodeId, dur, param));
                burstEnd = Math.max(burstEnd, off + dur);
            }
            t = burstEnd + 900 + faultRng.nextLong(1400);        // recovery window before the next burst
        }

        return new Schedule(seed, nodes, clients, keys, durationMs, Mode.ADVERSARIAL, faults, workload);
    }

    private static final FaultKind[] ADVERSARIAL_KINDS = {
            FaultKind.ISOLATE_LEADER, FaultKind.ISOLATE_NODE,
            FaultKind.KILL_LEADER, FaultKind.KILL_NODE,
            FaultKind.PAUSE_LEADER, FaultKind.PAUSE_NODE,
            FaultKind.LOSS_NODE
    };

    /** Each client a steady, jittered op stream. Shared by both schedule modes. */
    private static List<List<WorkOp>> buildWorkload(SplittableRandom workRoot, int clients, int keys,
                                                    long durationMs, long seed, int readPct, int intervalBase) {
        int putCut = readPct + (100 - readPct) * 9 / 10; // 90% of the non-reads are PUT
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
        return workload;
    }

    /** Deterministic Fisher-Yates using the schedule RNG so the shuffled order is seed-reproducible. */
    private static void shuffle(List<Integer> list, SplittableRandom rng) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            Collections.swap(list, i, j);
        }
    }
}

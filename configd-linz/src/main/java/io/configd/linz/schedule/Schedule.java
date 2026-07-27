package io.configd.linz.schedule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Deterministic fault+workload plan from seed (reproducible inputs, not node elections).
 * SEQUENTIAL: single faults one-at-a-time, quorum preserved, fast CI.
 * ADVERSARIAL: overlapping bursts (quorum-breaking), recovery windows—Jepsen-grade.
 */
public final class Schedule {

    public enum OpKind { PUT, READ, DELETE }

    /**
     * *_LEADER kinds resolve to current leader at injection; *_NODE kinds target fixed node.
     * ISOLATE: full REJECT on raft port. KILL: SIGKILL + restart. PAUSE: SIGSTOP + SIGCONT.
     * LOSS_NODE: probabilistic DROP (param%).
     */
    public enum FaultKind {
        ISOLATE_LEADER, ISOLATE_NODE,
        KILL_LEADER, KILL_NODE,
        PAUSE_LEADER, PAUSE_NODE,
        LOSS_NODE
    }

    public enum Mode { SEQUENTIAL, ADVERSARIAL }

    public record WorkOp(long offsetMs, OpKind kind, int keyIndex, String token) {}

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
     * SEQUENTIAL gate schedule.
     * readPct: percent reads (rest 90% PUT/10% DELETE); gate uses 72 so confirm-bound pins writes.
     * intervalBase: base ms between ops (0..+intervalBase jitter added).
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
     * ADVERSARIAL Jepsen-grade: overlapping combination faults in quorum-breaking bursts + recovery windows.
     * Distinct node targets per burst; write-heavier op mix sharpens checker discrimination.
     * maxConcurrent: upper bound on active faults per burst (clamped to n-1 for initial fault-free node).
     */
    public static Schedule generateAdversarial(long seed, int nodes, int clients, int keys, long durationMs,
                                               int readPct, int intervalBase, int maxConcurrent) {
        SplittableRandom root = new SplittableRandom(seed);
        SplittableRandom faultRng = root.split();
        SplittableRandom workRoot = root.split();

        List<List<WorkOp>> workload = buildWorkload(workRoot, clients, keys, durationMs, seed, readPct, intervalBase);

        int burstCap = Math.max(1, Math.min(maxConcurrent, nodes - 1));
        List<FaultEvent> faults = new ArrayList<>();
        long t = 2500 + faultRng.nextLong(800);
        while (t < durationMs - 2000) {
            int burst = 1 + faultRng.nextInt(burstCap);
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
                    continue;
                }
                long stagger = faultRng.nextLong(400);
                long dur = 700 + faultRng.nextLong(2200);
                int param = kind == FaultKind.LOSS_NODE ? 10 + faultRng.nextInt(41) : 0;
                long off = t + stagger;
                faults.add(new FaultEvent(off, kind, nodeId, dur, param));
                burstEnd = Math.max(burstEnd, off + dur);
            }
            t = burstEnd + 900 + faultRng.nextLong(1400);
        }

        return new Schedule(seed, nodes, clients, keys, durationMs, Mode.ADVERSARIAL, faults, workload);
    }

    private static final FaultKind[] ADVERSARIAL_KINDS = {
            FaultKind.ISOLATE_LEADER, FaultKind.ISOLATE_NODE,
            FaultKind.KILL_LEADER, FaultKind.KILL_NODE,
            FaultKind.PAUSE_LEADER, FaultKind.PAUSE_NODE,
            FaultKind.LOSS_NODE
    };

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

    private static void shuffle(List<Integer> list, SplittableRandom rng) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            Collections.swap(list, i, j);
        }
    }
}

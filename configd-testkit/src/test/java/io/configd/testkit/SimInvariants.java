package io.configd.testkit;

import io.configd.raft.RaftLog;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.store.VersionedConfigStore;

import java.util.HashMap;
import java.util.Map;

/**
 * Continuous, cross-node safety-invariant checker for the deterministic
 * simulation. One instance is bound to a
 * {@link ConsistencyPropertyTests.ClusterHarness} and {@link #checkAll()} is
 * called <em>after every tick</em>; any violated predicate throws
 * {@link SafetyViolation}, which fails the seed with full replay context.
 * <p>
 * Two complementary seams cover the invariant list:
 * <ul>
 *   <li><b>In-node, per-event</b> - {@link RaftNode.InvariantChecker} wired via the
 *       7-arg ctor by {@link ConsistencyPropertyTests.ClusterHarness}; supplies
 *       {@code election_safety}, {@code leader_completeness}, {@code log_matching},
 *       {@code state_machine_safety}, {@code version_monotonicity},
 *       {@code single_server_invariant}, {@code no_op_before_reconfig},
 *       {@code reconfig_safety}, {@code durable_prefix_no_gap} at their exact
 *       mutation sites. See {@link #throwingNodeChecker()}.</li>
 *   <li><b>Sim-level, cross-node</b> - this class, which needs a global view of all
 *       nodes: single-leader-per-term, log-matching across replicas, state-machine
 *       safety across replicas, per-observer version monotonicity, and no-stale
 *       overwrite.</li>
 * </ul>
 * <p>
 * <b>Safety vs liveness.</b> Everything here is a <em>safety</em> property: a
 * violation is a real bug and FAILS the seed. Liveness goals (a leader is elected,
 * a proposal commits) are tracked separately by {@link Activity} and a stall is
 * <em>recorded, never failed</em> - liveness findings are registered, not hidden.
 * <p>
 * Not thread-safe; the simulation is single-threaded.
 */
final class SimInvariants {

    static final class SafetyViolation extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SafetyViolation(String message) {
            super(message);
        }
    }

    private final ClusterView cluster;
    private final long seed;
    private final int nodeCount;

    private final long[] lastVersionPerNode;

    private final Map<Long, String> committedCommandByIndex = new HashMap<>();

    SimInvariants(ClusterView cluster, long seed) {
        this.cluster = cluster;
        this.seed = seed;
        this.nodeCount = cluster.nodeCount();
        this.lastVersionPerNode = new long[nodeCount];
    }

    /**
     * A throwing {@link RaftNode.InvariantChecker}: turns any in-node invariant
     * breach into a {@link SafetyViolation} tagged with the seed. Pass to the
     * harness so the 8 named in-node checks (plus {@code durable_prefix_no_gap})
     * fire at their mutation sites instead of being NOOP.
     */
    RaftNode.InvariantChecker throwingNodeChecker() {
        return (name, condition, message) -> {
            if (!condition) {
                throw new SafetyViolation("IN-NODE invariant '" + name
                        + "' violated (seed=" + seed + "): " + message);
            }
        };
    }

    void checkAll() {
        checkSingleLeaderPerTerm();
        checkVersionMonotonicityPerObserver();
        checkLogMatchingAcrossReplicas();
        checkStateMachineSafetyAcrossReplicas();
    }

    private void checkSingleLeaderPerTerm() {
        Map<Long, Integer> leaderByTerm = new HashMap<>();
        for (int i = 0; i < nodeCount; i++) {
            RaftNode n = cluster.node(i);
            if (n.role() == RaftRole.LEADER) {
                long term = n.currentTerm();
                Integer prior = leaderByTerm.put(term, i);
                if (prior != null) {
                    throw new SafetyViolation("single-leader-per-term violated (seed="
                            + seed + "): term " + term + " has leaders " + prior + " and " + i);
                }
            }
        }
    }

    private void checkVersionMonotonicityPerObserver() {
        for (int i = 0; i < nodeCount; i++) {
            long v = cluster.store(i).currentVersion();
            if (v < lastVersionPerNode[i]) {
                throw new SafetyViolation("version monotonicity violated at node " + i
                        + " (seed=" + seed + "): version went " + lastVersionPerNode[i]
                        + " -> " + v);
            }
            lastVersionPerNode[i] = v;
        }
    }

    private void checkLogMatchingAcrossReplicas() {
        for (int a = 0; a < nodeCount; a++) {
            for (int b = a + 1; b < nodeCount; b++) {
                RaftLog la = cluster.log(a);
                RaftLog lb = cluster.log(b);
                long upTo = Math.min(la.commitIndex(), lb.commitIndex());
                // Start above any compacted prefix on either side.
                long from = Math.max(la.snapshotIndex(), lb.snapshotIndex()) + 1;
                for (long idx = from; idx <= upTo; idx++) {
                    long ta = la.termAt(idx);
                    long tb = lb.termAt(idx);
                    if (ta != tb) {
                        throw new SafetyViolation("Log Matching violated across nodes "
                                + a + "/" + b + " (seed=" + seed + ") at index " + idx
                                + ": term " + ta + " != " + tb);
                    }
                }
            }
        }
    }

    /**
     * State Machine Safety / no-stale-overwrite at the committed-prefix level: a
     * committed index, once observed with a given (term) identity, must never be
     * seen with a different one - neither across replicas nor across time. We use
     * (index@term) as the command identity because Log Matching guarantees that an
     * equal (index,term) implies an equal command (Raft section 5.3), so a mismatch here
     * is a genuine divergent-commit / stale-overwrite RED.
     */
    private void checkStateMachineSafetyAcrossReplicas() {
        for (int i = 0; i < nodeCount; i++) {
            RaftLog log = cluster.log(i);
            long from = log.snapshotIndex() + 1;
            for (long idx = from; idx <= log.commitIndex(); idx++) {
                long term = log.termAt(idx);
                if (term < 0) {
                    continue; // not present locally (e.g. compacted) - skip
                }
                String identity = idx + "@" + term;
                String prior = committedCommandByIndex.putIfAbsent(idx, identity);
                if (prior != null && !prior.equals(identity)) {
                    throw new SafetyViolation("State Machine Safety violated (seed=" + seed
                            + ") at committed index " + idx + ": was " + prior
                            + ", node " + i + " now reports " + identity
                            + " (divergent commit / stale overwrite)");
                }
            }
        }
    }
}

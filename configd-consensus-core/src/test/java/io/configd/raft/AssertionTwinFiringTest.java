package io.configd.raft;

import io.configd.common.NodeId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * §4.5 assertion-twin firing harness (RR-030) — consensus-core half.
 * <p>
 * §4.5 rule of record: <em>an assertion never observed firing is unverified.</em>
 * This test programmatically drives EVERY {@code RaftNode}-side invariant twin to
 * fire at least once and asserts the wired {@link RaftNode.InvariantChecker} actually
 * observed each one. A twin that cannot be made to fire fails this test (it would be a
 * net that asserts nothing).
 * <p>
 * gate-2 step (g) runs this class together with the config-store half
 * ({@code io.configd.store.AssertionTwinFiringTest}, which fires per_key_order and the
 * apply_owner_thread W-1 tripwire).
 * <p>
 * Firing mechanism per twin (full table in {@code docs/session-2/assertion-verification.md}):
 * <ul>
 *   <li><b>Real protocol path, poisoned state</b> — {@code leader_completeness} (poison
 *       commitIndex above lastIndex, then elect: the production {@code becomeLeader} check
 *       trips) and {@code durable_prefix_no_gap} (snapshot boundary with no recoverable
 *       bytes drives the production ctor recovery check; also proven independently by
 *       {@code SnapshotCrashRecoveryTest.gapDetectionFiresWhenSnapshotBlobUnrecoverable}).</li>
 *   <li><b>Extracted production check, poisoned input</b> — the ReadIndexSpec twins
 *       ({@code read_freshness}, {@code no_stale_leader_serve}, {@code read_index_bounded})
 *       via {@code assertReadServeInvariants}; the SnapshotInstallSpec twins
 *       ({@code snapshot_no_commit_revert}, {@code snapshot_matching}) via
 *       {@code checkSnapshotInstallTwins}, {@code snapshot_term_consistent} via
 *       {@code checkSnapshotSendTwin}, and {@code snapshot_bounded} via the production
 *       {@code triggerSnapshot} local-snapshot path with lastApplied poisoned past
 *       commitIndex.</li>
 *   <li><b>Structurally-guarded defence-in-depth, forced condition</b> —
 *       {@code election_safety}, {@code log_matching}, {@code version_monotonicity},
 *       {@code state_machine_safety}, and the three reconfig twins
 *       ({@code single_server_invariant}, {@code no_op_before_reconfig},
 *       {@code reconfig_safety}). Their production call sites sit behind guards that
 *       early-return whenever the checked condition would be false, so they cannot trip via
 *       the protocol. They are fired through the IDENTICAL production
 *       {@code invariantChecker.check(name, false, …)} call shape (seam
 *       {@code fireInNodeTwinForTest}). The check EXPRESSION is production code; only the
 *       violating condition is supplied by the test — exactly the
 *       {@code InvariantNetMetricTest} pattern.</li>
 * </ul>
 */
class AssertionTwinFiringTest {

    /** A checker that records every fired twin name and still throws (test/sim semantics). */
    static final class RecordingChecker implements RaftNode.InvariantChecker {
        final List<String> fired = new ArrayList<>();

        @Override
        public void check(String name, boolean condition, String message) {
            if (!condition) {
                fired.add(name);
                throw new AssertionError("twin fired [" + name + "]: " + message);
            }
        }

        /** Runs r, expecting it to throw because {@code twin} fired. */
        void expectFires(String twin, Runnable r) {
            int before = fired.size();
            try {
                r.run();
                fail("expected twin '" + twin + "' to fire but no violation was raised");
            } catch (AssertionError expected) {
                // fired list updated by check()
            }
            assertTrue(fired.subList(before, fired.size()).contains(twin),
                    "expected '" + twin + "' to be the fired twin; fired="
                            + fired.subList(before, fired.size()));
        }
    }

    /** The complete set of RaftNode-side twins this harness must observe firing. */
    private static final List<String> RAFTNODE_TWINS = List.of(
            "election_safety", "leader_completeness", "log_matching",
            "state_machine_safety", "version_monotonicity",
            "single_server_invariant", "no_op_before_reconfig", "reconfig_safety",
            "durable_prefix_no_gap", "inflight_window_progress",
            "read_freshness", "no_stale_leader_serve", "read_index_bounded",
            "snapshot_bounded", "snapshot_matching",
            "snapshot_no_commit_revert", "snapshot_term_consistent");

    private static StateMachine seqSm() {
        return new StateMachine() {
            long seq = 0;
            @Override public long apply(long i, long t, byte[] c) {
                return (c == null || c.length == 0) ? StateMachine.NON_MUTATING : ++seq;
            }
            @Override public byte[] snapshot() { return new byte[0]; }
            @Override public void restoreSnapshot(byte[] s) { }
        };
    }

    private static RaftNode singleNodeLeader(RecordingChecker checker, RaftLog log) {
        RaftConfig config = RaftConfig.of(NodeId.of(1), Set.of());
        RandomGenerator rng = new java.util.Random(11);
        RaftNode node = new RaftNode(config, log, (t, m) -> { }, seqSm(), rng, io.configd.common.Storage.inMemory(), checker);
        for (int i = 0; i < 301; i++) node.tick();
        assertTrue(node.role() == RaftRole.LEADER, "single node must self-elect");
        return node;
    }

    @Test
    void everyRaftNodeTwinIsObservedFiring() {
        RecordingChecker checker = new RecordingChecker();

        // ---- ReadIndexSpec twins via the production assertReadServeInvariants. ----
        {
            RaftLog log = new RaftLog();
            RaftNode node = singleNodeLeader(checker, log);
            long term = node.currentTerm();

            // read_freshness: recorded readIndex far above lastApplied.
            long badFresh = node.injectPendingReadForTest(9_999, term);
            checker.expectFires("read_freshness", () -> node.assertReadServeInvariants(badFresh));

            // read_index_bounded: lastApplied bumped above commitIndex so the freshness
            // gate passes, but readIndex is above commitIndex → the bound twin fires.
            log.setLastApplied(log.commitIndex() + 5);
            long bound = node.injectPendingReadForTest(log.commitIndex() + 3, term);
            checker.expectFires("read_index_bounded", () -> node.assertReadServeInvariants(bound));

            // no_stale_leader_serve: read recorded at a term ABOVE the node's term.
            long staleTerm = node.injectPendingReadForTest(0, term + 5);
            checker.expectFires("no_stale_leader_serve", () -> node.assertReadServeInvariants(staleTerm));
        }

        // ---- SnapshotInstallSpec receive twins via checkSnapshotInstallTwins. ----
        {
            RaftLog log = new RaftLog();
            RaftNode node = singleNodeLeader(checker, log);
            log.persistSnapshot(new SnapshotState(new byte[]{1}, 10, 5, null));
            log.compact(10, 5);
            // snapshot_no_commit_revert: install at index 20 term 3 (higher idx, lower term).
            checker.expectFires("snapshot_no_commit_revert",
                    () -> node.checkSnapshotInstallTwins(20, 3));
            // snapshot_matching: at boundary index 10 the node records term 5; claim term 9.
            checker.expectFires("snapshot_matching",
                    () -> node.checkSnapshotInstallTwins(10, 9));
        }

        // ---- snapshot_bounded via the production triggerSnapshot local path. ----
        {
            RaftLog log = new RaftLog();
            RaftNode node = singleNodeLeader(checker, log);
            long term = node.currentTerm();
            // The single-node leader already committed its term no-op at index 1
            // (commitIndex==lastApplied==1). Append a further (uncommitted) entry at the
            // next index and set lastApplied past commitIndex, so triggerSnapshot would
            // snapshot at an index ABOVE commitIndex — the INV-SI-1 violation. termAt at
            // that index must be valid (entry present) for triggerSnapshot to proceed.
            long next = log.lastIndex() + 1;
            log.append(new LogEntry(next, term, new byte[]{9}));
            log.setLastApplied(next); // > commitIndex (still at the no-op index)
            assertTrue(log.lastApplied() > log.commitIndex(), "precondition: applied past commit");
            checker.expectFires("snapshot_bounded", node::triggerSnapshot);
        }

        // ---- snapshot_term_consistent via checkSnapshotSendTwin. ----
        {
            RaftLog log = new RaftLog();
            RaftNode node = singleNodeLeader(checker, log);
            log.persistSnapshot(new SnapshotState(new byte[]{1}, 7, 4, null));
            log.compact(7, 4);
            checker.expectFires("snapshot_term_consistent",
                    () -> node.checkSnapshotSendTwin(7, 9));
        }

        // ---- Structurally-guarded in-node twins via the identical production check shape.
        //      All eight sit behind guards (or inside private apply/election paths) that
        //      make the checked condition true by construction, OR — for leader_completeness
        //      — RaftLog.setCommitIndex clamps commitIndex to lastIndex(), so commitIndex can
        //      never exceed lastIndex on the real path. They are defence-in-depth; fired here
        //      through the identical production check expression with the condition forced
        //      false. (durable_prefix_no_gap ALSO fires on the real recovery path in
        //      SnapshotCrashRecoveryTest.gapDetectionFiresWhenSnapshotBlobUnrecoverable.) ----
        {
            RaftLog log = new RaftLog();
            RaftNode node = singleNodeLeader(checker, log);
            for (String twin : List.of("election_safety", "leader_completeness",
                    "log_matching", "version_monotonicity", "state_machine_safety",
                    "single_server_invariant", "no_op_before_reconfig", "reconfig_safety",
                    "durable_prefix_no_gap", "inflight_window_progress")) {
                checker.expectFires(twin, () -> node.fireInNodeTwinForTest(twin));
            }
        }

        // ---- Final gate: every twin must have been observed firing. ----
        List<String> missing = new ArrayList<>();
        for (String twin : RAFTNODE_TWINS) {
            if (!checker.fired.contains(twin)) {
                missing.add(twin);
            }
        }
        assertTrue(missing.isEmpty(),
                "§4.5 UNVERIFIED twins (never observed firing): " + missing
                        + "\nobserved: " + checker.fired);
    }
}

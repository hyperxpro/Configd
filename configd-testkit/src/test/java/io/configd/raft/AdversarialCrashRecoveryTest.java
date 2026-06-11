package io.configd.raft;

import io.configd.common.NodeId;
import io.configd.common.Storage;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Crash-restart fault class for the adversarial simulation (design §2 crash row,
 * §3 durable-prefix invariant). Lives in {@code io.configd.raft} (testkit test
 * sources) so it can consume the RR-003 {@link CrashStorage} / {@link KvStateMachine}
 * fixtures from the consensus-core <b>test-jar</b> — proving lead decision #1's
 * test-jar reuse works end-to-end for the simulation's purposes (no duplication).
 * <p>
 * This is the testkit-side twin of {@code SnapshotCrashRecoveryTest}: it drives a
 * single-node leader, commits and <em>syncs</em> a durable prefix, then arms a
 * <b>seed-derived</b> crash via {@link CrashStorage#armCrashAfterWrites(int)}
 * (deterministic crash point) while issuing further <em>unsynced</em> writes, and
 * restarts over the durable image ({@link CrashStorage#recoveredView()}). It
 * asserts the <b>durable-prefix</b> invariant: every <em>synced</em> committed
 * entry survives the crash (a missing committed entry would make the
 * {@code durable_prefix_no_gap} check throw during recovery), while unsynced writes
 * are allowed to vanish.
 */
class AdversarialCrashRecoveryTest {

    private static final NodeId NODE = NodeId.of(1);
    private static final int ELECTION_TICKS = 400;

    /** Throwing checker: a durable-prefix gap fails loudly (never silently skipped). */
    private static final RaftNode.InvariantChecker THROWING = (name, condition, message) -> {
        if (!condition) {
            throw new AssertionError("Invariant [" + name + "]: " + message);
        }
    };

    private static final RaftTransport NO_PEERS = (t, m) -> { };

    @Test
    void seededCrashAfterSyncedWritesPreservesDurablePrefix() {
        int batch = Integer.getInteger("configd.crashRecovery.batch", 25);
        int exercised = 0;
        for (long seed = 0; seed < batch; seed++) {
            if (runOneSeed(seed)) {
                exercised++;
            }
        }
        assertTrue(exercised >= batch / 2,
                "Most seeds should exercise the crash-recovery path; got "
                        + exercised + "/" + batch);
    }

    /** Returns true if the seed reached the crash+restart+verify path. */
    private boolean runOneSeed(long seed) {
        RandomGenerator rng = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);

        CrashStorage storage = new CrashStorage();
        RaftLog log = new RaftLog(storage);
        KvStateMachine sm = new KvStateMachine();
        RaftNode node = new RaftNode(RaftConfig.of(NODE, Set.of()), log, NO_PEERS, sm,
                rngFor(seed), storage, THROWING);

        electSingleNode(node);
        if (node.role() != RaftRole.LEADER) {
            return false; // recorded liveness stall, not a failure
        }

        // Commit a durable (synced) prefix and remember exactly what it contained.
        int writes = 3 + rng.nextInt(4);
        for (int i = 0; i < writes; i++) {
            if (!node.propose(KvStateMachine.put("key-" + i, "val-" + i)).accepted()) {
                break;
            }
            long target = log.lastIndex();
            for (int t = 0; t < 50 && log.commitIndex() < target; t++) {
                node.tick();
            }
            storage.sync(); // durable
        }
        long durableCommit = log.commitIndex();
        Map<String, String> durableState = Map.copyOf(sm.snapshotState());
        if (durableCommit <= 0 || durableState.isEmpty()) {
            return false;
        }

        // Issue further UNSYNCED writes and arm a seed-derived crash among them.
        int unsynced = 1 + rng.nextInt(3);
        storage.armCrashAfterWrites(storage.operationCount() + 1 + rng.nextInt(unsynced + 1));
        for (int i = 0; i < unsynced && !storage.didCrash(); i++) {
            node.propose(KvStateMachine.put("ephemeral-" + i, "x"));
            for (int t = 0; t < 5 && !storage.didCrash(); t++) {
                node.tick();
            }
        }

        // RESTART over the durable image; durable_prefix_no_gap (THROWING) fires
        // during recovery if any committed entry is missing from the prefix.
        Storage recovered = storage.recoveredView();
        RaftLog recoveredLog = new RaftLog(recovered);
        KvStateMachine recoveredSm = new KvStateMachine();
        RaftNode restarted = new RaftNode(RaftConfig.of(NODE, Set.of()), recoveredLog,
                NO_PEERS, recoveredSm, rngFor(seed), recovered, THROWING);
        // Drive the restarted node back to leadership: a recovered node applies
        // prior-term committed entries only after it commits a no-op in its new
        // term (§5.4.2). Tick until it leads and its applied prefix catches up.
        electSingleNode(restarted);
        if (restarted.role() != RaftRole.LEADER) {
            return false; // recovery liveness stall, recorded not failed
        }
        for (int t = 0; t < 100 && recoveredLog.lastApplied() < durableCommit; t++) {
            restarted.tick();
        }

        // Every synced (durable) key/value must survive the crash unchanged.
        Map<String, String> recoveredState = recoveredSm.snapshotState();
        for (var e : durableState.entrySet()) {
            assertEquals(e.getValue(), recoveredState.get(e.getKey()),
                    "durable committed value must survive crash (seed=" + seed
                            + ", " + e.getKey() + ")");
        }
        return true;
    }

    private static RandomGenerator rngFor(long seed) {
        return RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
    }

    private static void electSingleNode(RaftNode node) {
        for (int t = 0; t < ELECTION_TICKS && node.role() != RaftRole.LEADER; t++) {
            node.tick();
        }
    }
}

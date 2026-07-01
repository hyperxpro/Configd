package io.configd.raft;

import io.configd.common.NodeId;
import io.configd.common.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Covers restart-after-compaction silent data loss.
 * <p>
 * The crash-recovery matrix. Each cell takes a single-node leader through a
 * sequence of committed writes, takes a snapshot (which compacts the WAL
 * prefix), commits more writes, then crashes the node at one of three
 * durability-critical points and restarts it over the bytes that actually
 * reached durable storage ({@link CrashStorage}). The invariant under test is
 * the <b>durable-prefix</b> invariant: at every instant a complete prefix
 * (persisted snapshot at index S + WAL suffix [S+1 .. lastIndex]) is on durable
 * storage, so the recovered state machine equals the full pre-crash committed
 * state.
 * <p>
 * Crash points:
 * <ul>
 *   <li><b>(a) BEFORE snapshot persist</b> - crash before any snapshot byte is
 *       durable. The pre-snapshot WAL must still be fully intact, so recovery
 *       replays the whole log and loses nothing.</li>
 *   <li><b>(b) AFTER persist, BEFORE WAL truncate</b> - the snapshot is durable
 *       but the WAL prefix has not yet been deleted. Recovery may use either the
 *       snapshot or the still-present WAL; either way no committed entry is
 *       lost.</li>
 *   <li><b>(c) AFTER truncate</b> - the steady state after a successful
 *       snapshot: the WAL prefix is gone and the only record of [1..S] is the
 *       persisted snapshot. This is the cell that loses data pre-fix.</li>
 * </ul>
 * Plus a torn-tail cell: a crash mid-append of the final WAL record (the partial
 * trailing frame must be discarded as never-durable, and no committed entry
 * below it may be lost).
 *
 * @see CrashStorage harness modelling unsynced-write loss (also the foundation
 *      for the fsync-mutation work)
 */
class SnapshotCrashRecoveryTest {

    private static final NodeId NODE = NodeId.of(1);
    /** Ticks to drive a single-node election to LEADER. */
    private static final int ELECTION_TICKS = 400;
    /** Durable storage key under which the fix persists the snapshot blob. */
    private static final String SNAPSHOT_KEY = "raft-log.snapshot";
    /** WAL log name (RaftLog.WAL_NAME). */
    private static final String WAL_NAME = "raft-log";

    /** A throwing invariant checker so the gap-detection assertion fails loudly in tests. */
    private static final RaftNode.InvariantChecker THROWING = (name, condition, message) -> {
        if (!condition) {
            throw new AssertionError("Invariant violated [" + name + "]: " + message);
        }
    };

    /** No-op transport; single-node cluster sends to no peers. */
    private static final RaftTransport NO_PEERS = (target, message) -> { };

    /** Drives a fresh single-node leader over the given storage and returns it. */
    private static Harness boot(CrashStorage storage) {
        RaftConfig config = RaftConfig.of(NODE, Set.of());
        RaftLog log = new RaftLog(storage);
        KvStateMachine sm = new KvStateMachine();
        RandomGenerator rng = RandomGenerator.of("L64X128MixRandom");
        RaftNode node = new RaftNode(config, log, NO_PEERS, sm, rng, storage, THROWING);
        return new Harness(node, log, sm);
    }

    private record Harness(RaftNode node, RaftLog log, KvStateMachine sm) {
        void electLeader() {
            for (int i = 0; i < ELECTION_TICKS && node.role() != RaftRole.LEADER; i++) {
                node.tick();
            }
            assertEquals(RaftRole.LEADER, node.role(), "single node must become leader");
        }

        void commitPut(String key, String value) {
            ProposeOutcome outcome = node.propose(KvStateMachine.put(key, value));
            assertEquals(ProposalResult.ACCEPTED, outcome.result(),
                    "single-node propose should be accepted (and commit immediately)");
        }
    }

    // The matrix

    /**
     * (c) AFTER truncate - the steady state. This is the restart-after-compaction cell:
     * the WAL prefix is gone and only the persisted snapshot remembers [1..S].
     */
    @Test
    void recoversAfterSnapshotAndWalTruncate() {
        runMatrixSeed(/*seed*/ 1, CrashPoint.AFTER_TRUNCATE);
    }

    /** (b) AFTER persist, BEFORE WAL truncate. */
    @Test
    void recoversWhenCrashedBetweenPersistAndTruncate() {
        runMatrixSeed(2, CrashPoint.AFTER_PERSIST_BEFORE_TRUNCATE);
    }

    /** (a) BEFORE snapshot persist. */
    @Test
    void recoversWhenCrashedBeforeSnapshotPersist() {
        runMatrixSeed(3, CrashPoint.BEFORE_PERSIST);
    }

    /** Sweep every cell across many seeds (distinct write counts / snapshot points). */
    @Test
    void matrixHoldsAcrossSeeds() {
        int seeds = Integer.getInteger("configd.rr003.seeds", 60);
        int violations = 0;
        StringBuilder report = new StringBuilder();
        for (CrashPoint cp : CrashPoint.values()) {
            for (int seed = 0; seed < seeds; seed++) {
                String v = runMatrixSeedCollecting(seed, cp);
                if (v != null) {
                    violations++;
                    if (report.length() < 4000) {
                        report.append(v).append('\n');
                    }
                }
            }
        }
        if (violations > 0) {
            fail("RR-003: " + violations + " durable-prefix violation(s) across the crash matrix:\n"
                    + report);
        }
    }

    /**
     * Torn-tail cell: a crash mid-append of the final WAL record leaves a partial
     * trailing frame on disk. Recovery (FileStorage.readLog) must discard
     * the never-fully-fsynced trailing record and recover exactly the entries
     * below it - without violating the durable-prefix/no-gap invariant. Uses a
     * real {@link io.configd.common.FileStorage} so the byte-level torn frame is
     * genuine (the frame-granular CrashStorage cannot model a sub-frame tear).
     */
    @Test
    void recoversCleanlyFromTornFinalWalRecord(@TempDir Path tempDir) throws Exception {
        Storage storage = Storage.file(tempDir);

        // Build a node, commit three writes (durable WAL frames), capture state.
        RaftConfig config = RaftConfig.of(NODE, Set.of());
        RaftLog log = new RaftLog(storage);
        KvStateMachine sm = new KvStateMachine();
        RaftNode node = new RaftNode(config, log, NO_PEERS, sm,
                RandomGenerator.of("L64X128MixRandom"), storage, THROWING);
        for (int i = 0; i < ELECTION_TICKS && node.role() != RaftRole.LEADER; i++) {
            node.tick();
        }
        assertEquals(RaftRole.LEADER, node.role());
        node.propose(KvStateMachine.put("a", "1"));
        node.propose(KvStateMachine.put("b", "2"));
        Map<String, String> committedBeforeTear = sm.snapshotState();
        assertEquals(Map.of("a", "1", "b", "2"), committedBeforeTear);

        // Simulate a torn final record: append a partial frame (a length header
        // with no complete data/CRC) directly to the WAL file - exactly what a
        // crash mid-appendToLog leaves behind.
        Path wal = tempDir.resolve("raft-log.wal");
        byte[] tornFrame = new byte[] {0, 0, 0, 32, 1, 2, 3}; // claims len=32, only 3 bytes follow
        Files.write(wal, tornFrame, java.nio.file.StandardOpenOption.APPEND);

        // Restart: a fresh RaftLog/RaftNode over the same directory.
        RaftLog log2 = new RaftLog(storage);
        KvStateMachine sm2 = new KvStateMachine();
        RaftNode node2 = new RaftNode(config, log2, NO_PEERS, sm2,
                RandomGenerator.of("L64X128MixRandom"), storage, THROWING);
        for (int i = 0; i < ELECTION_TICKS && node2.role() != RaftRole.LEADER; i++) {
            node2.tick();
        }
        assertEquals(RaftRole.LEADER, node2.role(), "recovers and re-elects despite the torn tail");

        // The torn trailing frame was discarded; the two committed writes survive;
        // no durable_prefix_no_gap violation fired (THROWING would have thrown).
        Map<String, String> recovered = sm2.snapshotState();
        assertTrue(recovered.entrySet().containsAll(committedBeforeTear.entrySet()),
                "torn-tail recovery dropped a committed entry: expected superset of "
                        + committedBeforeTear + " but got " + recovered);
        assertEquals("1", recovered.get("a"));
        assertEquals("2", recovered.get("b"));
    }

    /**
     * Gap-detection: when a snapshot boundary exists on disk but the snapshot
     * BYTES are genuinely unrecoverable (e.g. the blob file is lost to disk
     * corruption), recovery must FAIL LOUDLY - the no-gap invariant
     * ({@code durable_prefix_no_gap}) fires rather than the pre-fix silent skip
     * that advanced lastApplied past the hole and served an empty store. This is
     * the defense-in-depth that the persist-before-truncate fix backstops: even
     * if durability is somehow defeated, the loss is observable, never silent.
     */
    @Test
    void gapDetectionFiresWhenSnapshotBlobUnrecoverable(@TempDir Path tempDir) throws Exception {
        Storage storage = Storage.file(tempDir);
        RaftConfig config = RaftConfig.of(NODE, Set.of());

        RaftLog log = new RaftLog(storage);
        KvStateMachine sm = new KvStateMachine();
        RaftNode node = new RaftNode(config, log, NO_PEERS, sm,
                RandomGenerator.of("L64X128MixRandom"), storage, THROWING);
        for (int i = 0; i < ELECTION_TICKS && node.role() != RaftRole.LEADER; i++) {
            node.tick();
        }
        assertEquals(RaftRole.LEADER, node.role());
        node.propose(KvStateMachine.put("k0", "v0"));
        node.propose(KvStateMachine.put("k1", "v1"));
        // Snapshot+compact: the WAL prefix is now gone; the blob is the only
        // record of [1..snapshotIndex].
        assertTrue(node.triggerSnapshot());
        assertTrue(log.snapshotIndex() > 0);

        // Simulate UNRECOVERABLE loss of the snapshot bytes (disk corruption):
        // delete the persisted blob file while the boundary meta + (empty) WAL
        // remain. This is not the silent-loss scenario (persistent snapshot prevents it) - it
        // models a hardware/medium failure to prove the invariant is a real net.
        Files.deleteIfExists(tempDir.resolve("raft-log.snapshot.dat"));

        RaftLog log2 = new RaftLog(storage);
        KvStateMachine sm2 = new KvStateMachine();
        // Recovery (constructor + any apply) walks into the [1..snapshotIndex]
        // hole with no bytes to fill it. The throwing checker must fire - the
        // pre-fix silent skip would have advanced lastApplied over the hole and
        // booted with an empty store.
        AssertionError thrown = null;
        try {
            RaftNode node2 = new RaftNode(config, log2, NO_PEERS, sm2,
                    RandomGenerator.of("L64X128MixRandom"), storage, THROWING);
            for (int i = 0; i < ELECTION_TICKS && node2.role() != RaftRole.LEADER; i++) {
                node2.tick();
            }
        } catch (AssertionError e) {
            thrown = e;
        }
        org.junit.jupiter.api.Assertions.assertNotNull(thrown,
                "durable_prefix_no_gap must fire when the snapshot is unrecoverable; "
                        + "a silent skip would have advanced lastApplied over the hole");
        assertTrue(thrown.getMessage().contains("durable_prefix_no_gap"),
                "wrong invariant fired: " + thrown.getMessage());
    }

    /**
     * fsync-LIE (S4/B-rest, contract: "verify fsync is actually durable, not just called").
     * A node that "fsynced" the snapshot blob (the device ACKed the write) then lost it on a
     * power cut must, on restart, DETECT the resulting gap and FAIL LOUD
     * ({@code durable_prefix_no_gap}) - never silently serve missing committed state.
     * <p>
     * This is the injection-path-agnostic twin of
     * {@link #gapDetectionFiresWhenSnapshotBlobUnrecoverable}: there the blob file was deleted
     * (disk corruption); here {@link CrashStorage#lieOnSyncForKey} models the firmware lie - the
     * {@code put} returns success but the bytes never reach the platter - so the recovered durable
     * image is IDENTICAL (no blob + truncated WAL = a gap below the snapshot boundary), and the
     * same oracle catches it. Proving it via the lie path closes the kill-matrix fsync-lie cell.
     * <p>
     * The REAL-firmware detection boundary (a device with a volatile write cache under a true
     * power cut) stays environment-blocked - staging recipe in
     * {@code docs/session-4/storage-fault-layer-design.md section 3} ({@code hdparm -W1}, no {@code fua}).
     */
    @Test
    void gapDetectionFiresWhenSnapshotFsyncLied() {
        CrashStorage storage = new CrashStorage();
        Harness h = boot(storage);
        h.electLeader();
        h.commitPut("k0", "v0");
        h.commitPut("k1", "v1");
        assertTrue(h.log().lastApplied() > 0, "writes must have committed+applied");

        // Arm the fsync-lie on the snapshot blob, then snapshot+compact: persistSnapshot puts the
        // blob (LIED -> working only, returns success), compact truncates the WAL prefix + sync()
        // (durable). The live node sees a consistent snapshot; the durable image has NO blob and a
        // truncated WAL.
        storage.lieOnSyncForKey(SNAPSHOT_KEY);
        assertTrue(h.node().triggerSnapshot());
        assertTrue(h.log().snapshotIndex() > 0, "snapshot boundary advanced in-memory");

        // Power cut: the lied blob is gone; the WAL truncation (synced) stays. Recovered = a gap.
        storage.crash();
        CrashStorage recovered = storage.recoveredView();

        // Restart: recovery walks the [1..snapshotIndex] hole with no bytes to fill it. The
        // throwing checker MUST fire - a silent skip would advance lastApplied over the hole and
        // boot with missing committed state.
        AssertionError thrown = null;
        try {
            Harness h2 = boot(recovered);
            h2.electLeader();
        } catch (AssertionError e) {
            thrown = e;
        }
        org.junit.jupiter.api.Assertions.assertNotNull(thrown,
                "durable_prefix_no_gap must fire when a lied fsync drops the snapshot blob — "
                        + "a node must never silently serve state it only claimed to have synced");
        assertTrue(thrown.getMessage().contains("durable_prefix_no_gap"),
                "wrong invariant fired: " + thrown.getMessage());
    }

    enum CrashPoint { BEFORE_PERSIST, AFTER_PERSIST_BEFORE_TRUNCATE, AFTER_TRUNCATE, NONE }

    private void runMatrixSeed(int seed, CrashPoint cp) {
        String violation = runMatrixSeedCollecting(seed, cp);
        if (violation != null) {
            fail(violation);
        }
    }

    /**
     * Runs one matrix cell. Returns null on success, or a violation description.
     * <p>
     * Shape: commit a deterministic batch of writes; snapshot+compact (crashing
     * at the configured point relative to that compaction); if it did not crash
     * yet, commit a second batch; then restart and assert the recovered state
     * machine equals the full committed key/value state captured before the
     * crash.
     */
    private String runMatrixSeedCollecting(int seed, CrashPoint cp) {
        CrashStorage storage = new CrashStorage();
        Harness h = boot(storage);
        h.electLeader();

        // Deterministic, seed-varied first batch (1..8 writes).
        int firstBatch = 1 + (seed % 8);
        Map<String, String> expected = new LinkedHashMap<>();
        for (int i = 0; i < firstBatch; i++) {
            String k = "k" + i;
            String val = "v" + seed + "-" + i;
            h.commitPut(k, val);
            expected.put(k, val);
        }

        // Capture the committed state the snapshot will fold in.
        long preSnapshotApplied = h.log.lastApplied();
        assertTrue(preSnapshotApplied >= firstBatch, "writes must have committed+applied");

        // Drive a snapshot, arming the crash at the requested point.
        boolean crashed = takeSnapshotCrashingAt(h, storage, cp);

        if (!crashed) {
            // Second batch only reached when the crash point is after the whole
            // compaction (or NONE). These writes go into the post-snapshot WAL
            // suffix and must also survive.
            int secondBatch = 1 + ((seed / 8) % 5);
            for (int i = 0; i < secondBatch; i++) {
                String k = "post" + i;
                String val = "p" + seed + "-" + i;
                h.commitPut(k, val);
                expected.put(k, val);
            }
            // Make the post-snapshot WAL durable, then crash (point (c)).
            if (cp == CrashPoint.AFTER_TRUNCATE) {
                storage.crash(); // drop nothing extra - the suffix was synced by append/commit
            }
        }

        // Restart over the surviving durable image
        CrashStorage recovered = storage.recoveredView();
        Harness h2;
        try {
            h2 = boot(recovered);
        } catch (Throwable t) {
            return violation(seed, cp, "recovery construction threw: " + t);
        }
        // Re-elect: a single-node leader commits a no-op, advancing commitIndex
        // and replaying the durable WAL suffix (the production recovery path).
        try {
            h2.electLeader();
        } catch (Throwable t) {
            return violation(seed, cp, "recovery re-election threw: " + t);
        }

        Map<String, String> actual = h2.sm.snapshotState();
        if (!expected.equals(actual)) {
            return violation(seed, cp,
                    "recovered state machine != committed state. expected=" + expected
                            + " actual=" + actual
                            + " (snapshotIndex=" + h2.log.snapshotIndex()
                            + ", lastApplied=" + h2.log.lastApplied() + ")");
        }
        return null;
    }

    /**
     * Takes a snapshot, crashing at the configured point relative to the
     * compaction's durable steps. Arming is semantic (keyed on the snapshot blob
     * key / the WAL log), not on raw op counts, so it is robust to the exact
     * number of storage calls the path makes. Returns true iff the node crashed
     * during the snapshot.
     * <p>
     * Note: pre-fix there is no snapshot-blob persist, so the BEFORE_PERSIST and
     * AFTER_PERSIST_BEFORE_TRUNCATE triggers never fire - the cell then runs as
     * a clean compaction followed by restart, which still loses the compacted
     * prefix (the bug), so those cells fail pre-fix too.
     */
    private boolean takeSnapshotCrashingAt(Harness h, CrashStorage storage, CrashPoint cp) {
        switch (cp) {
            case BEFORE_PERSIST ->
                // Crash before the snapshot blob is written: blob not durable,
                // WAL fully intact. Recovery must replay the whole log.
                    storage.crashBeforeKeyPut(SNAPSHOT_KEY);
            case AFTER_PERSIST_BEFORE_TRUNCATE -> {
                // Crash after the blob is fsynced but before the WAL prefix
                // delete lands: both the blob AND the old WAL are on disk.
                storage.crashAfterKeyDurable(SNAPSHOT_KEY);
                storage.crashBeforeWalDelete(WAL_NAME); // belt-and-suspenders
            }
            case AFTER_TRUNCATE -> {
                // Let the whole compaction complete durably, then crash - the
                // steady state where only the persisted snapshot remembers
                // [1..S].
                h.node.triggerSnapshot();
                storage.crash();
                return true;
            }
            case NONE -> {
                h.node.triggerSnapshot();
                return false;
            }
            default -> { /* unreachable */ }
        }
        h.node.triggerSnapshot();
        return storage.didCrash();
    }

    private static String violation(int seed, CrashPoint cp, String detail) {
        return "RR-003 VIOLATION [" + cp + ", seed=" + seed + "]: " + detail;
    }
}

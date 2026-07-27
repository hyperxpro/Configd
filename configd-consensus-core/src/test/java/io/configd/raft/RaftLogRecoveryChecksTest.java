package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
import io.configd.common.NodeId;
import io.configd.common.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recovery-integrity tests for {@link RaftLog}: the {@code scopeId} cross-shard-splice
 * assert at each at-rest read call-site, the whole-log recovery checks (contiguity / term
 * monotonicity / snapshot-join) that catch a physically reordered or spliced WAL even when every
 * record still authenticates, and the fail-closed refusal of a non-enveloped WAL record (the
 * legacy raw-record fallback is deleted).
 * <p>
 * WALs are crafted directly ({@link ChainedWal.Writer} builds properly hash-chained
 * {@code [index][term][prevHash][command]} payloads and appends them as FileStorage frames) so the
 * tests can produce shapes the normal append path never writes - a term regression, an index gap, an
 * overlapping snapshot join, a cross-shard record - and prove recovery REFUSES them. The authenticated
 * posture chains, so every well-formed record is a valid chain link; the check under test fires first.
 */
class RaftLogRecoveryChecksTest {

    private static final RaftNode.InvariantChecker THROWING = (name, condition, message) -> {
        if (!condition) {
            throw new AssertionError("Invariant violated [" + name + "]: " + message);
        }
    };
    private static final RaftTransport NO_PEERS = (target, message) -> { };

    // scopeId assert: one negative test per at-rest read call-site.

    /** WAL replay site: a record authored under gid=1, replayed by a gid=0 reader, is refused. */
    @Test
    void walRecordFromAnotherShardIsRefused(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = SnapshotIntegrityTest.keyedEnvelope();
        new ChainedWal.Writer(storage, env, 1).append(1, 1, "shard-1-cmd"); // authored under gid=1

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(storage, env, 0));
        assertTrue(ex.getMessage().contains("scope mismatch"),
                "a cross-shard WAL record must be refused on replay, got: " + ex.getMessage());
    }

    /** Snapshot persist->reload site: a blob authored under gid=1, reloaded by a gid=0 reader, refused. */
    @Test
    void snapshotBlobFromAnotherShardIsRefusedOnReload(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = SnapshotIntegrityTest.keyedEnvelope();
        // Persist a gid=1 blob in its OWN dir (its gid=1 anchor must not shadow the gid=0 reader's).
        Path shard1Dir = tempDir.resolve("shard1");
        new RaftLog(Storage.file(shard1Dir), env, 1)
                .persistSnapshot(new SnapshotState("shard-1-state".getBytes(StandardCharsets.UTF_8), 5, 2, null));
        byte[] foreignBlob = java.nio.file.Files.readAllBytes(shard1Dir.resolve("raft-log.snapshot.dat"));

        // A gid=0 shard with its OWN valid anchor, then the gid=1 blob planted over it: the reload's
        // snapshot scope assert is what fires.
        Path shard0Dir = tempDir.resolve("shard0");
        new RaftLog(Storage.file(shard0Dir), env, 0).closeAnchor();
        java.nio.file.Files.write(shard0Dir.resolve("raft-log.snapshot.dat"), foreignBlob);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(shard0Dir), env, 0));
        assertTrue(ex.getMessage().contains("scope mismatch"),
                "a cross-shard snapshot blob must be refused on reload, got: " + ex.getMessage());
    }

    /**
     * InstallSnapshot re-persist->reload site: the receiver re-wraps the installed blob under its
     * OWN gid before the local at-rest read, so a subsequent reload under a different gid is refused.
     * This proves the re-wrap uses the receiver's gid and the scope assert covers the local read.
     */
    @Test
    void installedSnapshotReloadedUnderWrongShardIsRefused(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = SnapshotIntegrityTest.keyedEnvelope();
        // A follower on shard 1 installs a snapshot in its OWN dir, re-wrapping the blob under gid=1.
        Path shard1Dir = tempDir.resolve("shard1");
        Storage shard1Storage = Storage.file(shard1Dir);
        RaftLog log = new RaftLog(shard1Storage, env, 1);
        KvStateMachine sm = new KvStateMachine();
        NodeId leader = NodeId.of(2);
        RaftConfig config = RaftConfig.of(NodeId.of(1), Set.of(leader));
        RaftNode follower = new RaftNode(config, log, NO_PEERS, sm,
                RandomGenerator.of("L64X128MixRandom"), shard1Storage, THROWING, env);

        KvStateMachine src = new KvStateMachine();
        src.apply(1, 1, KvStateMachine.put("installed", "X"));
        byte[] snapData = src.snapshot();
        InstallSnapshotRequest req = new InstallSnapshotRequest(1, leader, 10, 1, 0, snapData, true);
        follower.handleMessage(req);
        byte[] reWrappedBlob = java.nio.file.Files.readAllBytes(shard1Dir.resolve("raft-log.snapshot.dat"));

        // Plant the gid=1-wrapped blob over a gid=0 shard with its own valid anchor: the reload's scope
        // assert refuses it, proving the re-wrap used the receiver's (gid=1) scope.
        Path shard0Dir = tempDir.resolve("shard0");
        new RaftLog(Storage.file(shard0Dir), env, 0).closeAnchor();
        java.nio.file.Files.write(shard0Dir.resolve("raft-log.snapshot.dat"), reWrappedBlob);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(shard0Dir), env, 0));
        assertTrue(ex.getMessage().contains("scope mismatch"),
                "a re-persisted installed snapshot must be refused when reloaded under the wrong gid, got: "
                        + ex.getMessage());
    }

    // Every WAL record must be enveloped; the legacy raw-record fallback is deleted.

    @Test
    void nonEnvelopedWalRecordRejectedUnderKey(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = SnapshotIntegrityTest.keyedEnvelope();
        storage.appendToLog("raft-log", "not-an-integrity-envelope-record".getBytes(StandardCharsets.UTF_8));

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(storage, env, 0));
        assertTrue(ex.getMessage().contains("refused") || ex.getMessage().contains("magic"),
                "a keyed reader must refuse a non-enveloped WAL record, got: " + ex.getMessage());
    }

    @Test
    void nonEnvelopedWalRecordRejectedKeyless(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = IntegrityEnvelope.keyless();
        storage.appendToLog("raft-log", "not-an-integrity-envelope-record".getBytes(StandardCharsets.UTF_8));

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(storage, env, 0));
        assertTrue(ex.getMessage().contains("non-enveloped"),
                "a keyless reader must also refuse a non-enveloped WAL record (fallback deleted), got: "
                        + ex.getMessage());
    }

    @Test
    void contiguityGapRefused(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = SnapshotIntegrityTest.keyedEnvelope();
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, 0);
        w.append(1, 1, "a");
        w.append(3, 1, "c"); // index 2 spliced out (the writer chains, but contiguity fires first)

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(storage, env, 0));
        assertTrue(ex.getMessage().contains("contiguity"),
                "an index gap must be refused on recovery, got: " + ex.getMessage());
    }

    @Test
    void reorderRefused(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = SnapshotIntegrityTest.keyedEnvelope();
        // Two authentic records written in swapped order: embedded indices 2 then 1.
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, 0);
        w.append(2, 1, "b");
        w.append(1, 1, "a");

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(storage, env, 0));
        assertTrue(ex.getMessage().contains("contiguity"),
                "a physically reordered WAL must be refused on recovery, got: " + ex.getMessage());
    }

    @Test
    void termRegressionRefused(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = SnapshotIntegrityTest.keyedEnvelope();
        // Contiguous indices [1,2] but term goes DOWN (5 -> 3), which Raft never writes.
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, 0);
        w.append(1, 5, "a");
        w.append(2, 3, "b");

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(storage, env, 0));
        assertTrue(ex.getMessage().contains("term regression"),
                "a mid-log term regression must be refused on recovery, got: " + ex.getMessage());
    }

    @Test
    void snapshotJoinViolationRefused(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = SnapshotIntegrityTest.keyedEnvelope();
        // A WAL starting at index 5 (so the snapshot boundary should be 4), a valid chain...
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, 0);
        w.append(5, 1, "e");
        w.append(6, 1, "f");
        w.append(7, 1, "g");
        // ...but the anchor's snapshot boundary claims 6, i.e. AT/beyond the WAL's first index (5):
        // the WAL retains entries the snapshot supposedly compacted. Illegal join (WAL checks pass).
        w.setSnapshot(6, 1);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(storage, env, 0));
        assertTrue(ex.getMessage().contains("snapshot-join"),
                "an overlapping snapshot/WAL join must be refused on recovery, got: " + ex.getMessage());
    }

    /** Non-vacuity: a well-formed single-shard WAL still recovers (the checks refuse tampering, not health). */
    @Test
    void wellFormedWalRecoversCleanly(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = SnapshotIntegrityTest.keyedEnvelope();
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, 0);
        w.append(1, 1, "a");
        w.append(2, 1, "b");
        w.append(3, 2, "c");

        RaftLog log = new RaftLog(storage, env, 0);
        org.junit.jupiter.api.Assertions.assertEquals(3, log.size());
        org.junit.jupiter.api.Assertions.assertEquals(3, log.lastIndex());
        org.junit.jupiter.api.Assertions.assertEquals(2, log.lastTerm());
    }
}

package io.configd.testkit;

import io.configd.common.NodeId;
import io.configd.common.Storage;
import io.configd.raft.ProposalResult;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.raft.StateMachine;

import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENOSPC during WAL append, at the CONSENSUS layer (the
 * self-test pins only the storage-level throw; this pins how {@link RaftNode}/{@link RaftLog}
 * REACT). Oracle (`storage-fault-layer-design.md section 2` / arch section 11): a disk-full append must be
 * SURFACED (not swallowed into a mute zombie), must NOT silently advance the log (no partial /
 * no lost-but-acked write), and the node must recover cleanly once space returns - defined
 * degradation, never a crash-loop or silent loss.
 *
 * <p>The load-bearing invariant is {@link RaftLog#append}'s durable-FIRST ordering
 * ({@code storage.appendToLog(...)} BEFORE {@code entries.add(...)}): an ENOSPC throw leaves the
 * in-memory log == the durable log (neither has the failed entry). Mutation: swap that
 * order -> after ENOSPC the in-memory log advances past durable -> this test's no-silent-advance
 * assertion fails.
 */
class StorageEnospcConsensusReactionTest {

    static final class RecordingSM implements StateMachine {
        @Override public long apply(long index, long term, byte[] command) { return StateMachine.NON_MUTATING; }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    @Test
    void enospcOnWalAppendSurfacesAndNeverSilentlyAdvancesTheLog() {
        FaultInjectingStorage storage = new FaultInjectingStorage(Storage.inMemory());
        RaftConfig config = RaftConfig.of(NodeId.of(1), Set.of());
        RaftLog log = new RaftLog(storage);
        RaftNode node = new RaftNode(config, log, (target, message) -> { }, new RecordingSM(),
                new Random(1), storage);
        for (int i = 0; i < 400; i++) {
            node.tick(); // self-elect (no peers); the election no-op appends under no limit yet
        }
        assertEquals(RaftRole.LEADER, node.role());

        // Establish committed state on a healthy disk.
        for (int i = 0; i < 3; i++) {
            assertEquals(ProposalResult.ACCEPTED, node.propose(("ok" + i).getBytes()).result());
        }
        long committedBefore = log.commitIndex();
        long lastIndexBefore = log.lastIndex();
        assertTrue(committedBefore >= 3, "the healthy writes must have committed");

        // Disk full: any further append now exceeds the cumulative-byte limit and throws ENOSPC.
        storage.enospcAfterBytes(storage.bytesAppended());

        // (1) SURFACED - the disk-full append propagates, never swallowed into a mute zombie.
        UncheckedIOException ex = assertThrows(UncheckedIOException.class,
                () -> node.propose("over-the-limit".getBytes()),
                "ENOSPC on the WAL append must surface, not be silently swallowed");
        String msg = ex.getMessage() + (ex.getCause() == null ? "" : " / " + ex.getCause().getMessage());
        assertTrue(msg.contains("ENOSPC"), "the surfaced error must name ENOSPC: " + msg);

        // (2) NO SILENT ADVANCE - the failed entry never entered the log (durable-first append),
        // so a later commit/replication can never pick up an entry that was never durable.
        assertEquals(lastIndexBefore, log.lastIndex(),
                "an ENOSPC append must NOT advance the log (durable-first: storage before in-memory)");
        assertEquals(committedBefore, log.commitIndex(),
                "the failed entry must not be committed (no lost-but-acked write)");

        // (3) DEFINED DEGRADATION, NOT A WEDGE - once space returns the node appends again.
        storage.enospcAfterBytes(-1); // disarm (space reclaimed)
        assertEquals(ProposalResult.ACCEPTED, node.propose("after-recovery".getBytes()).result(),
                "the node must recover and accept writes after ENOSPC clears");
        assertTrue(log.lastIndex() > lastIndexBefore, "post-recovery write advances the log");
    }

    /**
     * ENOSPC during the SNAPSHOT write (distinct from the WAL-append cell above). Oracle
     * (design section 2): `triggerSnapshot` persists the blob BEFORE compaction truncates the
     * WAL prefix, so a disk-full on the blob `put` must abort the snapshot with the **WAL prefix
     * intact** - no truncation, no snapshot-index advance, NO loss, no `durable_prefix_no_gap` on
     * a later restart. (The persist-before-truncate ordering this relies on is a durable-log invariant,
     * mutation-covered by SnapshotCrashRecoveryTest's persist-after-compact revert; here the trigger
     * is an ENOSPC throw rather than a crash.)
     */
    @Test
    void enospcDuringSnapshotWriteLeavesWalIntactNoLoss() {
        FaultInjectingStorage storage = new FaultInjectingStorage(Storage.inMemory());
        RaftConfig config = RaftConfig.of(NodeId.of(1), Set.of());
        RaftLog log = new RaftLog(storage);
        RaftNode node = new RaftNode(config, log, (target, message) -> { }, new RecordingSM(),
                new Random(1), storage);
        for (int i = 0; i < 400; i++) {
            node.tick();
        }
        assertEquals(RaftRole.LEADER, node.role());
        for (int i = 0; i < 3; i++) {
            assertEquals(ProposalResult.ACCEPTED, node.propose(("e" + i).getBytes()).result());
        }
        long lastIndexBefore = log.lastIndex();
        long snapBefore = log.snapshotIndex();
        long committedBefore = log.commitIndex();
        assertEquals(0L, snapBefore, "no snapshot yet");

        // Disk full exactly when the snapshot blob is written: the NEXT write (persistSnapshot's
        // put of the blob) throws - BEFORE compaction truncates the WAL prefix.
        storage.failNextWrites(1);
        assertThrows(UncheckedIOException.class, node::triggerSnapshot,
                "a failed snapshot-blob write must surface, not be swallowed");

        // Oracle: WAL prefix NOT truncated (no loss), snapshot boundary NOT advanced.
        assertEquals(snapBefore, log.snapshotIndex(),
                "snapshotIndex must NOT advance when the blob write failed");
        assertEquals(lastIndexBefore, log.lastIndex(),
                "the WAL prefix must NOT be truncated when the snapshot write failed (persist-before-truncate, no loss)");
        assertEquals(committedBefore, log.commitIndex(), "committed prefix intact");

        // Defined degradation: once the disk recovers, a later snapshot succeeds and DOES compact.
        assertEquals(ProposalResult.ACCEPTED, node.propose("more".getBytes()).result());
        assertTrue(node.triggerSnapshot(), "a later snapshot succeeds once the disk recovers");
        assertTrue(log.snapshotIndex() > snapBefore, "snapshot now advances");
    }
}

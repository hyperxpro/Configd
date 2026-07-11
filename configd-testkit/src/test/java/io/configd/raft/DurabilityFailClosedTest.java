package io.configd.raft;

import io.configd.common.NodeId;
import io.configd.common.Storage;
import io.configd.testkit.FaultInjectingStorage;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fsync fail-closed policy at the durability seam. A WAL-fsync OR an anchor-fsync that throws
 * in a live leader flush must: NOT advance {@code durableIndex}, NOT commit, NOT ack, and hand off
 * to the {@link RaftNode.DurabilityFailureHandler} (which panics/exits in production). This wires
 * the failure at BOTH seams into a live-RaftNode flush cycle and asserts no-durable-advance +
 * no-commit + the panic. It also pins the boot-time anchor-preallocation ENOSPC cell.
 *
 * <p>In this package (io.configd.raft) so it can reach the package-private anchor sync-fault seam.
 */
class DurabilityFailClosedTest {

    static final class RecordingSM implements StateMachine {
        @Override public long apply(long index, long term, byte[] command) { return StateMachine.NON_MUTATING; }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    /** Elects a single-node leader over the given storage, then returns node + log. */
    private static RaftNode leaderOver(Storage storage) {
        RaftConfig config = RaftConfig.of(NodeId.of(1), Set.of());
        RaftLog log = new RaftLog(storage);
        RaftNode node = new RaftNode(config, log, (target, message) -> { }, new RecordingSM(),
                new Random(1), storage);
        for (int i = 0; i < 400; i++) {
            node.tick();
        }
        assertEquals(RaftRole.LEADER, node.role(), "precondition: single node must self-elect");
        return node;
    }

    @Test
    void walFsyncThrowInFlushPanicsWithNoDurableAdvance() {
        FaultInjectingStorage storage = new FaultInjectingStorage(Storage.inMemory());
        RaftNode node = leaderOver(storage);

        // Defer the flush so the fault lands on THIS flush cycle, deterministically.
        Deque<Runnable> pending = new ArrayDeque<>();
        node.setGroupCommit((flush, delayMicros) -> pending.add(flush), 4096, 0);
        String[] firedSeam = {null};
        Throwable[] firedCause = {null};
        node.setDurabilityFailureHandler((seam, cause) -> {
            firedSeam[0] = seam;
            firedCause[0] = cause;
            // Model the production panic without killing the test JVM: surface it, do not exit.
            throw new IllegalStateException("panic:" + seam);
        });

        long committedBefore = node.log().commitIndex();
        assertEquals(ProposalResult.ACCEPTED, node.propose("x".getBytes()).result());
        assertEquals(1, pending.size(), "propose must have buffered exactly one flush");

        // The WAL fsync (syncLog) throws on this flush.
        storage.failNextSyncs(1);
        assertThrows(IllegalStateException.class, () -> pending.poll().run(),
                "a WAL fsync failure in the flush must panic");

        assertEquals("leader-flush", firedSeam[0], "the leader-flush seam must report the failure");
        assertNotNull(firedCause[0]);
        assertTrue(firedCause[0] instanceof UncheckedIOException, "the cause is the storage fsync throw");
        assertEquals(committedBefore, node.log().commitIndex(),
                "commit (and hence ack) must NOT advance when the WAL fsync failed");
        assertEquals(1, node.durabilityFsyncFailures(), "the fsync failure must be counted");
    }

    @Test
    void anchorFsyncThrowInFlushPanicsWithNoDurableAdvance() {
        FaultInjectingStorage storage = new FaultInjectingStorage(Storage.inMemory());
        RaftNode node = leaderOver(storage);

        Deque<Runnable> pending = new ArrayDeque<>();
        node.setGroupCommit((flush, delayMicros) -> pending.add(flush), 4096, 0);
        String[] firedSeam = {null};
        Throwable[] firedCause = {null};
        node.setDurabilityFailureHandler((seam, cause) -> {
            firedSeam[0] = seam;
            firedCause[0] = cause;
            throw new IllegalStateException("panic:" + seam);
        });

        long committedBefore = node.log().commitIndex();
        assertEquals(ProposalResult.ACCEPTED, node.propose("y".getBytes()).result());

        // The WAL fsync succeeds but the ANCHOR fdatasync throws on this flush (the second seam).
        node.log().anchor().armSyncFailure(1);
        assertThrows(IllegalStateException.class, () -> pending.poll().run(),
                "an anchor fsync failure in the flush must panic identically to the WAL seam");

        assertEquals("leader-flush", firedSeam[0]);
        assertTrue(firedCause[0] instanceof UncheckedIOException);
        assertTrue(firedCause[0].getMessage().contains("anchor"),
                "the failure must be the anchor fdatasync, got: " + firedCause[0].getMessage());
        assertEquals(committedBefore, node.log().commitIndex(),
                "commit must NOT advance when the anchor fsync failed (INV-ANCHOR-ACK)");
        assertEquals(1, node.durabilityFsyncFailures());
    }

    @Test
    void bootTimeAnchorPreallocationEnospcRefusesLoud() {
        // The anchor is preallocated once at creation; a disk-full there must fail the boot loudly
        // (the only ENOSPC window for the anchor - steady-state anchor writes never allocate).
        FaultInjectingStorage storage = new FaultInjectingStorage(Storage.inMemory());
        storage.failNextWrites(1); // the anchor's createPreallocated put is the first write on a fresh shard
        assertThrows(UncheckedIOException.class, () -> new RaftLog(storage),
                "a disk-full during the one-time anchor preallocation must refuse to boot, not proceed");
    }
}

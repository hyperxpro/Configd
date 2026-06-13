package io.configd.raft;

import io.configd.common.NodeId;
import io.configd.common.Storage;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.Set;

import static io.configd.raft.ProposalResult.ACCEPTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session 4 / Workstream B-rest — RR-005 (1): Raft-log compaction must be REACHABLE by a
 * size/interval trigger. Before the fix the only {@code triggerSnapshot()} caller was the
 * circular {@code sendInstallSnapshot}, so a running node never compacted and its WAL grew
 * for the life of the process (eventually crash-looping recovery at the 2 GiB
 * {@code FileStorage} read cap — RR-005 (2)). {@link RaftNode#maybeCompact(long)} is the
 * threshold trigger the server tick loop now calls.
 *
 * <p>Discriminator: a healthy applied span BELOW the threshold must NOT compact; ABOVE it
 * MUST compact (snapshotIndex advances, the retained applied span drops within the
 * threshold, the WAL prefix is truncated). Mutation (EXP-006): reverting
 * {@code maybeCompact} to a no-op leaves the WAL unbounded → the above-threshold case fails.
 */
class RaftLogCompactionTriggerTest {

    /** Minimal recording state machine with a non-empty snapshot blob. */
    static final class RecordingSM implements StateMachine {
        @Override public long apply(long index, long term, byte[] command) { return StateMachine.NON_MUTATING; }
        @Override public byte[] snapshot() { return new byte[]{1, 2, 3}; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    /** A single-node leader (empty peer set self-elects; proposals commit + apply at once). */
    private static RaftNode singleNodeLeader() {
        Storage storage = Storage.inMemory();
        RaftConfig config = RaftConfig.of(NodeId.of(1), Set.of());
        RaftNode node = new RaftNode(config, new RaftLog(storage),
                (target, message) -> { }, new RecordingSM(), new Random(1), storage);
        for (int i = 0; i < 400; i++) {
            node.tick(); // self-elect (no peers -> immediate)
        }
        assertEquals(RaftRole.LEADER, node.role(), "a single-node cluster must self-elect");
        return node;
    }

    @Test
    void maybeCompactTriggersSnapshotOnlyAboveThreshold() {
        RaftNode node = singleNodeLeader();

        // Single-node: each propose commits + applies immediately. Drive applied entries up.
        for (int i = 0; i < 20; i++) {
            assertEquals(ACCEPTED, node.propose(("k" + i).getBytes()).result());
        }
        for (int i = 0; i < 5; i++) {
            node.tick(); // flush any pending apply
        }
        long appliedSpan = node.log().lastApplied() - node.log().snapshotIndex();
        assertTrue(appliedSpan >= 20, "single-node proposals must apply; span=" + appliedSpan);
        assertEquals(0, node.log().snapshotIndex(), "no compaction has happened yet (the bug: it never would)");

        // BELOW threshold: compaction must NOT fire — the WAL is not yet large enough.
        long highThreshold = appliedSpan + 100;
        assertFalse(node.maybeCompact(highThreshold), "must NOT compact below the threshold");
        assertEquals(0, node.log().snapshotIndex(), "snapshotIndex must be unchanged below the threshold");

        // ABOVE threshold: compaction MUST fire — snapshotIndex advances, retained span drops,
        // the WAL prefix is truncated (this is exactly what was unreachable in the wired server).
        long lowThreshold = 5;
        assertTrue(node.maybeCompact(lowThreshold), "must compact above the threshold");
        assertTrue(node.log().snapshotIndex() > 0, "snapshotIndex must advance after compaction");
        assertTrue(node.log().lastApplied() - node.log().snapshotIndex() <= lowThreshold,
                "post-compaction the retained applied span must be within the threshold");
        assertTrue(node.log().lastIndex() >= node.log().snapshotIndex(),
                "log stays consistent after WAL-prefix truncation");
    }
}

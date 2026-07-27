package io.configd.raft;

import io.configd.common.NodeId;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused unit tests for the {@code whenCommitOutcome} seam predicates. These
 * directly kill mutants in the changed region that the end-to-end
 * {@code AckEqualsCommitTest} cannot reach - in particular the term-match
 * predicate that distinguishes COMMITTED from LOST on a surviving registrant whose
 * slot was overwritten by a different term, and the per-index seq threading.
 */
class CommitOutcomeSeamTest {

    /**
     * A state machine that assigns a monotonic applied-mutation seq to each
     * non-empty command, starting at a large OFFSET so the seq is decorrelated
     * from the log index. The offset is load-bearing for the mutation check: it
     * ensures the COMMITTED seq can only be right if it was threaded from
     * {@code apply}'s return value, not derived from the index / lastApplied
     * (which would coincidentally match a seq that started at 0).
     */
    private static final class SeqStateMachine implements StateMachine {
        static final long SEQ_OFFSET = 1_000;
        long seq = SEQ_OFFSET;
        @Override public long apply(long index, long term, byte[] command) {
            if (command == null || command.length == 0) return StateMachine.NON_MUTATING;
            return ++seq;
        }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    private static RaftNode singleNodeLeader(SeqStateMachine sm) {
        RaftConfig config = RaftConfig.of(NodeId.of(1), Set.of());
        RaftLog log = new RaftLog();
        RandomGenerator rng = new java.util.Random(11);
        RaftNode node = new RaftNode(config, log, (t, m) -> { }, sm, rng);
        for (int i = 0; i < 301; i++) node.tick();
        assertEquals(RaftRole.LEADER, node.role());
        return node;
    }

    /**
     * COMMITTED carries the applied-mutation seq of THIS entry. A single-node
     * leader commits + applies inline, so registering at the real (index, term)
     * resolves immediately to COMMITTED with the correct seq.
     */
    @Test
    void committedCarriesTheAppliedMutationSeqForThisIndex() {
        SeqStateMachine sm = new SeqStateMachine();
        RaftNode node = singleNodeLeader(sm);

        ProposeOutcome first = node.propose(new byte[]{1});
        ProposeOutcome second = node.propose(new byte[]{2});
        assertTrue(first.accepted() && second.accepted());

        AtomicReference<CommitOutcome> o1 = new AtomicReference<>();
        AtomicReference<CommitOutcome> o2 = new AtomicReference<>();
        node.whenCommitOutcome(first.index(), first.term(), o1::set);
        node.whenCommitOutcome(second.index(), second.term(), o2::set);

        assertNotNull(o1.get(), "first outcome must resolve inline (single-node immediate commit)");
        assertNotNull(o2.get());
        assertEquals(CommitOutcome.Kind.COMMITTED, o1.get().kind());
        assertEquals(CommitOutcome.Kind.COMMITTED, o2.get().kind());
        assertEquals(SeqStateMachine.SEQ_OFFSET + 1, o1.get().seq(),
                "first write's COMMITTED seq must be its own applied-mutation seq");
        assertEquals(SeqStateMachine.SEQ_OFFSET + 2, o2.get().seq(),
                "second write's COMMITTED seq must be its own applied-mutation seq");
    }

    /**
     * LOST predicate (the sole definite-loss trigger): a callback registered for
     * an index with a DIFFERENT term than the entry that actually applies at that
     * index must resolve to LOST, never COMMITTED. This is exactly the
     * surviving-registrant-observes-overwrite case (Log Matching makes the slot
     * permanent), and it is the mutant the e2e crash test cannot reach because
     * there the registrant is the killed leader.
     */
    @Test
    void differentTermAppliedAtIndexIsLostNotCommitted() {
        SeqStateMachine sm = new SeqStateMachine();
        RaftNode node = singleNodeLeader(sm);

        ProposeOutcome p = node.propose(new byte[]{42});
        assertTrue(p.accepted());
        long index = p.index();
        long realTerm = p.term();

        AtomicReference<CommitOutcome> lost = new AtomicReference<>();
        node.whenCommitOutcome(index, realTerm + 5, lost::set);

        assertNotNull(lost.get(), "outcome must resolve inline (index already applied)");
        assertEquals(CommitOutcome.Kind.LOST, lost.get().kind(),
                "a different term applied at the index must be LOST, not COMMITTED");
        assertEquals(CommitOutcome.NO_SEQ, lost.get().seq(), "LOST carries no commit seq");

        // And the real (index, realTerm) must still be COMMITTED - the predicate is
        // a discriminator, not a blanket LOST.
        AtomicReference<CommitOutcome> committed = new AtomicReference<>();
        node.whenCommitOutcome(index, realTerm, committed::set);
        assertEquals(CommitOutcome.Kind.COMMITTED, committed.get().kind());
    }

    /**
     * A callback registered BEFORE the entry commits stays pending, then fires
     * exactly once when apply advances. (Multi-node: the entry needs replication
     * before it commits, so registration is genuinely deferred.)
     */
    @Test
    void pendingCallbackFiresOnceWhenApplyAdvances() {
        RaftNodeTest.TestCluster cluster = new RaftNodeTest.TestCluster(3);
        cluster.electLeader(NodeId.of(1));
        RaftNode leader = cluster.nodes.get(NodeId.of(1));
        assertEquals(RaftRole.LEADER, leader.role());

        ProposeOutcome p = leader.propose(new byte[]{7});
        assertTrue(p.accepted());

        AtomicReference<CommitOutcome> outcome = new AtomicReference<>();
        int[] fireCount = {0};
        leader.whenCommitOutcome(p.index(), p.term(), o -> { fireCount[0]++; outcome.set(o); });
        assertNull(outcome.get(), "must stay pending until quorum-commit + apply");

        cluster.deliverAllMessages(10);
        cluster.tickLeaderHeartbeatAndDeliver();
        cluster.deliverAllMessages(10);

        assertNotNull(outcome.get(), "callback must fire once the entry commits + applies");
        assertEquals(CommitOutcome.Kind.COMMITTED, outcome.get().kind());
        assertEquals(1, fireCount[0], "one-shot: callback must fire exactly once");
    }

    /**
     * The per-index seq must be threaded from {@code apply} - when MULTIPLE
     * mutations apply in a SINGLE applyCommitted pass, each index's COMMITTED
     * outcome must carry ITS OWN applied-mutation seq, not the seq of the last
     * entry applied in the batch. This kills the mutant that records the current
     * (latest) sequence for every index instead of the per-entry value: if the wrong
     * mutant is alive the callback for each index reads sequenceCounter() after the
     * sweep and gets the seq of the LAST applied mutation, not that index's own seq.
     */
    @Test
    void multipleEntriesAppliedInOnePassEachCarryTheirOwnSeq() {
        RaftNodeTest.TestCluster cluster = new RaftNodeTest.TestCluster(3);
        cluster.electLeader(NodeId.of(1));
        RaftNode leader = cluster.nodes.get(NodeId.of(1));
        assertEquals(RaftRole.LEADER, leader.role());

        // Propose three mutations WITHOUT delivering between them, so they append
        // back-to-back and then commit together in one applyCommitted pass.
        ProposeOutcome a = leader.propose(new byte[]{1});
        ProposeOutcome b = leader.propose(new byte[]{2});
        ProposeOutcome c = leader.propose(new byte[]{3});
        assertTrue(a.accepted() && b.accepted() && c.accepted());

        AtomicReference<CommitOutcome> oa = new AtomicReference<>();
        AtomicReference<CommitOutcome> ob = new AtomicReference<>();
        AtomicReference<CommitOutcome> oc = new AtomicReference<>();
        leader.whenCommitOutcome(a.index(), a.term(), oa::set);
        leader.whenCommitOutcome(b.index(), b.term(), ob::set);
        leader.whenCommitOutcome(c.index(), c.term(), oc::set);

        cluster.deliverAllMessages(10);
        cluster.tickLeaderHeartbeatAndDeliver();
        cluster.deliverAllMessages(10);

        assertNotNull(oa.get()); assertNotNull(ob.get()); assertNotNull(oc.get());
        assertEquals(CommitOutcome.Kind.COMMITTED, oa.get().kind());
        assertEquals(CommitOutcome.Kind.COMMITTED, ob.get().kind());
        assertEquals(CommitOutcome.Kind.COMMITTED, oc.get().kind());
        // The three seqs must be DISTINCT and strictly increasing by index - proving
        // each index reported its own seq, not the batch's last seq.
        long sa = oa.get().seq(), sb = ob.get().seq(), sc = oc.get().seq();
        assertTrue(sa < sb && sb < sc,
                "per-index seqs must be distinct & increasing: got " + sa + ", " + sb + ", " + sc);
        assertEquals(sa + 1, sb, "consecutive mutations are gap-free per index");
        assertEquals(sb + 1, sc, "consecutive mutations are gap-free per index");
    }

    @Test
    void nonMutatingEntryResolvesCommittedWithCurrentSeq() {
        SeqStateMachine sm = new SeqStateMachine();
        RaftNode node = singleNodeLeader(sm);
        // The election no-op is at index 1 (term 1). It is non-mutating, so the
        // state machine returned NON_MUTATING for it; the seam must still report
        // COMMITTED (any S <= current version satisfies RYW for a no-op).
        AtomicReference<CommitOutcome> outcome = new AtomicReference<>();
        node.whenCommitOutcome(1L, 1L, outcome::set);
        assertNotNull(outcome.get());
        assertEquals(CommitOutcome.Kind.COMMITTED, outcome.get().kind());
        assertTrue(outcome.get().seq() >= 0, "non-mutating COMMITTED seq must be a valid current seq");
    }
}

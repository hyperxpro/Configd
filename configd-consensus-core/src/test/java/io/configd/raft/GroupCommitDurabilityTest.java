package io.configd.raft;

import io.configd.common.NodeId;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Group-commit durability verification. The leader buffers a proposed entry with
 * {@link RaftLog#appendNoSync} and only force-syncs it on a coalescing flush; until that flush runs,
 * {@link RaftNode#maybeAdvanceCommitIndex} must NOT count the leader's own (not-yet-durable) copy
 * toward a commit quorum. These tests inject a <em>deferred</em> {@link RaftNode.FlushScheduler} that
 * parks the flush in a queue the test pumps by hand, so the "proposed but not yet fsynced" window -
 * impossible to hit with the INLINE default - is made explicit and deterministic.
 * <p>
 * Two invariants, both load-bearing for Raft safety under group commit:
 * <ol>
 *   <li><b>Gate blocks premature commit:</b> with a follower ACK already in hand (quorum-1), the entry
 *       must still NOT commit until the leader's own flush makes it durable. Counting a buffered
 *       self-copy here would let a leader crash lose a "committed" entry.</li>
 *   <li><b>Step-down stale flush is inert:</b> if the leader steps down before its queued flush runs,
 *       pumping that stale flush must not commit anything (the {@code role != LEADER} early-return in
 *       {@code maybeAdvanceCommitIndex}).</li>
 * </ol>
 */
class GroupCommitDurabilityTest {

    private static final NodeId N1 = NodeId.of(1);
    private static final NodeId N2 = NodeId.of(2);
    private static final NodeId N3 = NodeId.of(3);

    static final class RecordingTransport implements RaftTransport {
        record Sent(NodeId target, RaftMessage message) {}
        final List<Sent> sent = new ArrayList<>();
        @Override public void send(NodeId target, RaftMessage message) { sent.add(new Sent(target, message)); }
        void clear() { sent.clear(); }
    }

    static final class CountingStateMachine implements StateMachine {
        @Override public long apply(long index, long term, byte[] command) { return index; }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    /** A leader of cluster {1,2,3} (node 1), elected, its no-op committed. Mirrors the routing
     *  bring-up used by {@link RaftNodeReplicationUnitTest}. */
    private static RaftNode electedLeader(RecordingTransport transport) {
        Map<NodeId, RaftNode> nodes = new HashMap<>();
        Map<NodeId, RecordingTransport> ts = new HashMap<>();
        List<NodeId> all = List.of(N1, N2, N3);
        for (NodeId id : all) {
            Set<NodeId> peers = new HashSet<>(all);
            peers.remove(id);
            RecordingTransport t = id.equals(N1) ? transport : new RecordingTransport();
            RaftConfig config = RaftConfig.of(id, peers);
            RaftNode node = new RaftNode(config, new RaftLog(), t, new CountingStateMachine(),
                    new java.util.Random(id.id() * 31L + 7));
            nodes.put(id, node);
            ts.put(id, t);
        }
        for (int i = 0; i < 301; i++) nodes.get(N1).tick();
        for (int r = 0; r < 20; r++) {
            Map<NodeId, List<RaftMessage>> box = new HashMap<>();
            boolean any = false;
            for (var e : ts.entrySet()) {
                for (var s : e.getValue().sent) {
                    box.computeIfAbsent(s.target(), k -> new ArrayList<>()).add(s.message());
                    any = true;
                }
                e.getValue().clear();
            }
            if (!any) break;
            for (var e : box.entrySet()) {
                RaftNode tgt = nodes.get(e.getKey());
                if (tgt != null) for (RaftMessage m : e.getValue()) tgt.handleMessage(m);
            }
        }
        RaftNode leader = nodes.get(N1);
        assertEquals(RaftRole.LEADER, leader.role(), "precondition: node 1 must be leader");
        transport.clear();
        return leader;
    }

    @Test
    void gateBlocksCommitUntilLeaderEntryIsDurable() {
        RecordingTransport t = new RecordingTransport();
        RaftNode leader = electedLeader(t);

        // Defer the flush into a queue the test pumps by hand (no INLINE auto-flush).
        Deque<Runnable> pending = new ArrayDeque<>();
        leader.setGroupCommit((flush, delayMicros) -> pending.add(flush), 4096, 0);

        long base = leader.log().commitIndex();
        ProposeOutcome out = leader.propose("x".getBytes());
        assertTrue(out.accepted(), "leader must accept the proposal");
        long idx = out.index();
        assertEquals(base + 1, idx);
        assertEquals(1, pending.size(), "propose must have scheduled exactly one coalescing flush");

        // A follower (N2) ACKs the entry - quorum-1 of three. Even WITH this ACK the entry must NOT
        // commit, because the leader's own copy is still only buffered (durableIndex < idx).
        leader.handleMessage(new AppendEntriesResponse(leader.currentTerm(), true, idx, N2));
        assertEquals(base, leader.log().commitIndex(),
                "must NOT commit before the leader's own fsync — counting a buffered self-copy would be a safety bug");

        // Pump the deferred flush: the leader is now durable up to idx and self-counts -> quorum {self,N2}.
        pending.poll().run();
        assertEquals(idx, leader.log().commitIndex(),
                "commits once the leader's entry is force-synced AND a follower quorum exists");
    }

    @Test
    void queuedFlushAfterStepDownDoesNotCommitAsFollower() {
        RecordingTransport t = new RecordingTransport();
        RaftNode leader = electedLeader(t);

        Deque<Runnable> pending = new ArrayDeque<>();
        leader.setGroupCommit((flush, delayMicros) -> pending.add(flush), 4096, 0);

        long base = leader.log().commitIndex();
        ProposeOutcome out = leader.propose("y".getBytes());
        assertTrue(out.accepted());
        long idx = out.index();
        // Give it a follower ACK too, so the ONLY thing standing between it and commit is the leader's
        // own durability - making the step-down gate the sole reason it stays uncommitted.
        leader.handleMessage(new AppendEntriesResponse(leader.currentTerm(), true, idx, N2));
        assertEquals(base, leader.log().commitIndex(), "not committed yet (leader copy still buffered)");

        // Step down via a higher-term message BEFORE the queued flush runs.
        leader.handleMessage(new RequestVoteResponse(leader.currentTerm() + 5, false, N2, false));
        assertEquals(RaftRole.FOLLOWER, leader.role(), "higher term must step the leader down");

        // Pump the now-stale flush. It advances durableIndex and calls maybeAdvanceCommitIndex, which
        // must early-return because role != LEADER - so NOTHING commits as a follower.
        assertEquals(1, pending.size());
        pending.poll().run();
        assertEquals(base, leader.log().commitIndex(),
                "a stale queued flush must not advance commit while the node is a follower (role gate)");
    }
}

package io.configd.raft;

import io.configd.common.NodeId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies vote (and term) persistence across a crash-restart.
 * <p>
 * Election Safety (Raft section 5.2) requires that {@code votedFor} survive a process
 * restart: a node must never grant a second vote in the same term to a
 * <em>different</em> candidate. The grant path ({@code RaftNode.handleRequestVote}) persists
 * the vote via {@code durableState.vote(candidate)} before updating the in-memory field.
 * <p>
 * If that persistence call were missing, the in-memory {@code votedFor} would still be set,
 * so a same-process double-vote would still be rejected; the loss only shows up across a
 * restart, where the recovered {@code votedFor} is null.
 * <p>
 * Separately, {@code Storage.put} is self-durable (temp+force+atomic-rename+dir-fsync in
 * {@code FileStorage}, modelled faithfully by {@code CrashStorage.put}), so a trailing
 * {@code sync()} call after a {@code put} is redundant - removing it is behaviorally
 * invisible. This test pins the vote's durability itself (which the load-bearing
 * {@code put} provides), not that redundant sync.
 * <p>
 * The test models a real power loss with {@link CrashStorage}: the live node's persisted
 * vote reaches the durable image immediately (self-durable {@code put}); a
 * {@link CrashStorage#recoveredView()} boots a fresh node over exactly the bytes that
 * reached the platter. Removing the {@code durableState.vote(...)} call makes the
 * recovered {@code votedFor} null and the post-restart double-vote succeed - which this
 * test catches.
 */
class VotePersistenceCrashTest {

    private static final NodeId SELF = NodeId.of(1);
    private static final NodeId CAND_A = NodeId.of(2);
    private static final NodeId CAND_B = NodeId.of(3);

    private static final class RecordingTransport implements RaftTransport {
        final List<RequestVoteResponse> voteResponses = new ArrayList<>();

        @Override
        public void send(NodeId target, RaftMessage message) {
            if (message instanceof RequestVoteResponse r && !r.preVote()) {
                voteResponses.add(r);
            }
        }

        RequestVoteResponse lastVoteResponse() {
            assertFalse(voteResponses.isEmpty(), "expected a RequestVoteResponse to have been sent");
            return voteResponses.get(voteResponses.size() - 1);
        }
    }

    private static final RaftNode.InvariantChecker THROWING = (name, condition, message) -> {
        if (!condition) {
            throw new AssertionError("Invariant violated [" + name + "]: " + message);
        }
    };

    private static RaftNode boot(CrashStorage storage, RecordingTransport transport) {
        // A 3-node cluster so SELF is a voter (a non-voter would reject votes for
        // a different reason). Peers never receive anything (RecordingTransport
        // only captures vote responses for assertions).
        RaftConfig config = RaftConfig.of(SELF, Set.of(CAND_A, CAND_B));
        RaftLog log = new RaftLog(storage);
        StateMachine sm = new StateMachine() {
            @Override public long apply(long i, long t, byte[] c) { return StateMachine.NON_MUTATING; }
            @Override public byte[] snapshot() { return new byte[0]; }
            @Override public void restoreSnapshot(byte[] s) { }
        };
        RandomGenerator rng = new java.util.Random(7);
        return new RaftNode(config, log, transport, sm, rng, storage, THROWING);
    }

    /** A non-PreVote RequestVote from {@code candidate} at {@code term} with an empty (tie) log. */
    private static RequestVoteRequest voteFrom(NodeId candidate, long term) {
        return new RequestVoteRequest(term, candidate, 0L, 0L, false);
    }

    @Test
    void recoveredNodeRemembersItsVoteAndRefusesToDoubleVoteAcrossRestart() {
        CrashStorage storage = new CrashStorage();
        RecordingTransport t1 = new RecordingTransport();
        RaftNode node = boot(storage, t1);

        node.handleMessage(voteFrom(CAND_A, 5));
        RequestVoteResponse granted = t1.lastVoteResponse();
        assertTrue(granted.voteGranted(), "the first vote (for A) must be granted");
        assertEquals(5, granted.term(), "the grant is at the advanced term 5");
        assertEquals(CAND_A, node.votedFor(), "in-memory votedFor must be A after granting");

        storage.crash();
        RecordingTransport t2 = new RecordingTransport();
        RaftNode recovered = boot(storage.recoveredView(), t2);

        assertEquals(5, recovered.currentTerm(),
                "RR-086: currentTerm must survive the restart (persisted before in-memory update)");
        assertEquals(CAND_A, recovered.votedFor(),
                "RR-085 #2: votedFor must survive the restart — a deleted durableState.vote(candidate) "
                        + "call leaves this null and enables a post-crash double-vote");

        recovered.handleMessage(voteFrom(CAND_B, 5));
        RequestVoteResponse second = t2.lastVoteResponse();
        assertNotNull(second);
        assertFalse(second.voteGranted(),
                "RR-085 #2 / split-brain: after a restart the node must NOT grant a SECOND vote in "
                        + "term 5 to a different candidate (B) — it already voted for A. A lost vote "
                        + "persistence makes this double-vote succeed.");
        assertEquals(CAND_A, recovered.votedFor(), "the recovered vote for A must be unchanged");
    }
}

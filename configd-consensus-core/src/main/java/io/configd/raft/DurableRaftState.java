package io.configd.raft;

import io.configd.common.NodeId;
import io.configd.common.Storage;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Persists Raft's required durable state: {@code currentTerm} and
 * {@code votedFor} (Raft §5.2, Figure 2).
 * <p>
 * Raft requires these two values to survive process restarts. Without
 * persistence, a restarted node can:
 * <ul>
 *   <li>Vote twice in the same term (breaking Election Safety)</li>
 *   <li>Regress to term 0, accepting stale AppendEntries</li>
 * </ul>
 * <p>
 * Every mutation of term or votedFor MUST go through this class to
 * guarantee durability before the in-memory state is updated.
 * <p>
 * Storage format: 12 bytes — [term:8 bytes (long)] + [votedFor:4 bytes (int, -1 for null)]
 */
public final class DurableRaftState {

    private static final String STORAGE_KEY = "raft.persistent_state";
    private static final int VOTED_FOR_NULL = -1;

    private final Storage storage;
    private long currentTerm;
    private NodeId votedFor;

    /**
     * Creates a new DurableRaftState, loading any previously persisted state.
     *
     * @param storage the durable storage backend
     */
    public DurableRaftState(Storage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
        load();
    }

    /**
     * Returns the current term, loaded from durable storage.
     */
    public long currentTerm() {
        return currentTerm;
    }

    /**
     * Returns the node this server voted for in the current term,
     * or null if no vote was cast.
     */
    public NodeId votedFor() {
        return votedFor;
    }

    /**
     * Updates the current term. Persists to durable storage before returning.
     * Clears votedFor when the term advances (Raft §5.2: vote is per-term).
     * <p>
     * <b>Crash safety:</b> Persistence happens BEFORE in-memory update.
     * If persist() throws (disk full, I/O error), in-memory state remains
     * unchanged, preventing the node from operating with a term that is
     * not durable (which could cause double-voting after a crash).
     *
     * @param newTerm the new term (must be >= currentTerm)
     */
    public void setTerm(long newTerm) {
        if (newTerm < currentTerm) {
            throw new IllegalArgumentException(
                    "Term cannot decrease: current=" + currentTerm + ", new=" + newTerm);
        }
        if (newTerm > currentTerm) {
            // Persist BEFORE updating in-memory state (crash safety)
            persistValues(newTerm, null);
            this.currentTerm = newTerm;
            this.votedFor = null; // Clear vote on term change
        }
    }

    /**
     * Records a vote for the given candidate in the current term.
     * Persists to durable storage before returning.
     * <p>
     * <b>Crash safety:</b> Persistence happens BEFORE in-memory update.
     *
     * @param candidate the node being voted for (non-null)
     * @throws IllegalStateException if already voted for a different candidate
     *         in this term
     */
    public void vote(NodeId candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (votedFor != null && !votedFor.equals(candidate)) {
            throw new IllegalStateException(
                    "Already voted for " + votedFor + " in term " + currentTerm
                            + "; cannot vote for " + candidate);
        }
        // Persist BEFORE updating in-memory state (crash safety)
        persistValues(currentTerm, candidate);
        this.votedFor = candidate;
    }

    /**
     * Updates both term and vote atomically. Used when stepping up
     * to candidate (vote for self in new term).
     * <p>
     * <b>Crash safety:</b> Persistence happens BEFORE in-memory update.
     *
     * @param newTerm   the new term
     * @param candidate the candidate to vote for
     */
    public void setTermAndVote(long newTerm, NodeId candidate) {
        // Persist BEFORE updating in-memory state (crash safety)
        persistValues(newTerm, candidate);
        this.currentTerm = newTerm;
        this.votedFor = candidate;
    }

    // ---- Serialization ----

    /**
     * Persists the given values to durable storage. Called with the NEW values
     * BEFORE they are written to the in-memory fields, ensuring that if persist
     * throws, the in-memory state remains at the old values (crash safety).
     */
    private void persistValues(long term, NodeId voted) {
        ByteBuffer buf = ByteBuffer.allocate(12);
        buf.putLong(term);
        buf.putInt(voted != null ? voted.id() : VOTED_FOR_NULL);
        storage.put(STORAGE_KEY, buf.array());
        storage.sync();
    }

    private void load() {
        byte[] data = storage.get(STORAGE_KEY);
        if (data == null || data.length < 12) {
            this.currentTerm = 0;
            this.votedFor = null;
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        this.currentTerm = buf.getLong();
        int votedForId = buf.getInt();
        this.votedFor = (votedForId == VOTED_FOR_NULL) ? null : NodeId.of(votedForId);
    }
}

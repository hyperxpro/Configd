package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.NodeId;
import io.configd.common.Storage;

import java.nio.ByteBuffer;
import java.util.Objects;

import static io.configd.raft.RaftArtifactMagic.STATE_MAGIC;

/**
 * Persists Raft's required durable state: {@code currentTerm} and
 * {@code votedFor} (Raft section 5.2, Figure 2).
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
 * Storage format: 12 bytes - [term:8 bytes (long)] + [votedFor:4 bytes (int, -1 for null)]
 */
public final class DurableRaftState {

    private static final String STORAGE_KEY = "raft.persistent_state";
    private static final int VOTED_FOR_NULL = -1;

    private final Storage storage;
    /**
     * At-rest integrity codec over the 12-byte
     * {@code [term][votedFor]} payload. Never null - a keyless
     * {@link IntegrityEnvelope} is the default (in-memory mode and existing tests
     * unchanged, reads legacy raw bytes); the server wires a keyed one so a forged
     * {@code votedFor} (recomputed CRC, no valid MAC) is refused on load.
     */
    private final IntegrityEnvelope integrity;
    /**
     * The Raft group id, stamped as the envelope {@code scopeId} on the persistent-state
     * artifact and asserted on load. Per-shard at N&gt;1 (each group has its own term/vote);
     * {@code 0} at N=1. Frozen to {@code [0, NODE_SCOPE)}.
     */
    private final int gid;
    private long currentTerm;
    private NodeId votedFor;

    /**
     * Creates a new DurableRaftState, loading any previously persisted state.
     *
     * @param storage the durable storage backend
     */
    public DurableRaftState(Storage storage) {
        this(storage, IntegrityEnvelope.keyless());
    }

    /**
     * Creates a new DurableRaftState with an explicit at-rest integrity codec scoped to
     * {@code gid=0} (the N=1 single group).
     *
     * @param storage   the durable storage backend
     * @param integrity the at-rest integrity codec (non-null)
     */
    public DurableRaftState(Storage storage, IntegrityEnvelope integrity) {
        this(storage, integrity, 0);
    }

    /**
     * Creates a new DurableRaftState with an explicit at-rest integrity codec and Raft
     * group id, loading any previously persisted state.
     * <p>
     * {@code raft.persistent_state} is an atomic-rename artifact (never torn), so a
     * structurally-complete-but-MAC-invalid / scope-wrong file under a keyed codec is
     * unambiguously tamper and {@link #load()} fails loud - a forged {@code votedFor}
     * must never load (it would violate Election Safety).
     *
     * @param storage   the durable storage backend
     * @param integrity the at-rest integrity codec (non-null)
     * @param gid       the Raft group id, stamped as {@code scopeId}; in {@code [0, NODE_SCOPE)}
     */
    public DurableRaftState(Storage storage, IntegrityEnvelope integrity, int gid) {
        if (gid == IntegrityEnvelope.NODE_SCOPE) {
            throw new IllegalArgumentException("gid " + Integer.toHexString(gid)
                    + " collides with the reserved NODE_SCOPE sentinel");
        }
        this.storage = Objects.requireNonNull(storage, "storage");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
        this.gid = gid;
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
     * Clears votedFor when the term advances (Raft section 5.2: vote is per-term).
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

    // Serialization

    /**
     * Persists the given values to durable storage. Called with the NEW values
     * BEFORE they are written to the in-memory fields, ensuring that if persist
     * throws, the in-memory state remains at the old values (crash safety).
     */
    private void persistValues(long term, NodeId voted) {
        ByteBuffer buf = ByteBuffer.allocate(12);
        buf.putLong(term);
        buf.putInt(voted != null ? voted.id() : VOTED_FOR_NULL);
        // wrap the 12-byte payload in the at-rest integrity envelope, scoped to gid,
        // before persisting.
        storage.put(STORAGE_KEY, integrity.wrap(STATE_MAGIC, gid, buf.array()));
        storage.sync();
    }

    private void load() {
        byte[] data = storage.get(STORAGE_KEY);
        // unwrapOrNull returns null ONLY for structurally-absent or too-short bytes (fresh
        // node / first boot), or, under a keyless codec, legacy raw (pre-envelope) bytes. A
        // structurally-complete-but-tampered envelope (keyed-MAC mismatch, CRC32C mismatch,
        // rolled version, scope mismatch, or algId=NONE downgrade under a key) THROWS
        // IntegrityException, which propagates: a forged votedFor must not load (fail loud).
        // (DurableRaftState is removed in a later gate when the vote merges into the anchor;
        // its keyless legacy-raw back-compat path is retained here until then.)
        byte[] payload = integrity.unwrapOrNull(STATE_MAGIC, gid, data);
        if (payload == null) {
            payload = data; // keyless legacy raw bytes, or absent (handled below)
        }
        if (payload == null || payload.length < 12) {
            this.currentTerm = 0;
            this.votedFor = null;
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        this.currentTerm = buf.getLong();
        int votedForId = buf.getInt();
        this.votedFor = (votedForId == VOTED_FOR_NULL) ? null : NodeId.of(votedForId);
    }
}

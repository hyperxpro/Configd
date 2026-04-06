package io.configd.raft;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks pending linearizable read requests using the ReadIndex protocol.
 * <p>
 * When a client requests a linearizable read, the leader must confirm it
 * still holds authority before serving the read. The protocol is:
 * <ol>
 *   <li>Record the current commit index as the "read index".</li>
 *   <li>Send a heartbeat round to confirm leadership (a majority must respond).</li>
 *   <li>Wait until {@code lastApplied >= readIndex}.</li>
 *   <li>Execute the read against the state machine.</li>
 * </ol>
 * <p>
 * This class is designed for single-threaded access from the Raft I/O
 * thread (ADR-0009). No synchronization is used.
 *
 * @see RaftNode#readIndex()
 */
public final class ReadIndexState {

    /**
     * Internal state of a single pending read request.
     */
    private record PendingRead(long readIndex, int ackCount, boolean leadershipConfirmed) {

        PendingRead withAck(int newAckCount) {
            return new PendingRead(readIndex, newAckCount, leadershipConfirmed);
        }

        PendingRead confirmed() {
            return new PendingRead(readIndex, ackCount, true);
        }
    }

    private final Map<Long, PendingRead> pendingReads = new LinkedHashMap<>();
    private long nextReadId;

    /**
     * Starts a new read request. Records the current commit index as the
     * threshold that must be applied before the read can be served.
     *
     * @param commitIndex the current commit index at the time of the read request
     * @return a unique read ID used to track this request
     */
    public long startRead(long commitIndex) {
        long readId = nextReadId++;
        pendingReads.put(readId, new PendingRead(commitIndex, 1, false)); // 1 = self
        return readId;
    }

    /**
     * Confirms leadership for a pending read after a heartbeat round.
     * <p>
     * Called when heartbeat responses arrive. Once {@code ackCount >= quorumSize},
     * the read's leadership is confirmed and it can proceed once the state
     * machine has caught up.
     *
     * @param readId     the read request identifier
     * @param ackCount   total acknowledgement count (including self)
     * @param quorumSize the majority threshold
     */
    public void confirmLeadership(long readId, int ackCount, int quorumSize) {
        PendingRead pending = pendingReads.get(readId);
        if (pending == null) {
            return;
        }
        PendingRead updated = pending.withAck(ackCount);
        if (ackCount >= quorumSize) {
            updated = updated.confirmed();
        }
        pendingReads.put(readId, updated);
    }

    /**
     * Checks whether the read can be served.
     * <p>
     * A read is ready when:
     * <ol>
     *   <li>Leadership has been confirmed via a heartbeat majority.</li>
     *   <li>The state machine has applied all entries up through the read index
     *       ({@code lastApplied >= readIndex}).</li>
     * </ol>
     *
     * @param readId      the read request identifier
     * @param lastApplied the current lastApplied index from the log
     * @return true if the read can be safely served
     */
    public boolean isReady(long readId, long lastApplied) {
        PendingRead pending = pendingReads.get(readId);
        if (pending == null) {
            return false;
        }
        return pending.leadershipConfirmed() && lastApplied >= pending.readIndex();
    }

    /**
     * Returns the read index (commit index at the time of the read request)
     * for the given read ID, or -1 if the read ID is not found.
     *
     * @param readId the read request identifier
     * @return the read index, or -1 if unknown
     */
    public long readIndex(long readId) {
        PendingRead pending = pendingReads.get(readId);
        return pending != null ? pending.readIndex() : -1;
    }

    /**
     * Removes a completed (or cancelled) read from the pending set.
     *
     * @param readId the read request identifier
     */
    public void complete(long readId) {
        pendingReads.remove(readId);
    }

    /**
     * Returns the number of pending read requests.
     */
    public int pendingCount() {
        return pendingReads.size();
    }

    /**
     * Confirms leadership for all pending reads that have not yet been confirmed.
     * Called after the caller has already verified that a quorum is active
     * (e.g., via {@code clusterConfig.isQuorum(activeSet)}).
     * <p>
     * The caller is responsible for the quorum check because during joint
     * consensus, quorum requires dual-majority validation that cannot be
     * expressed as a simple count comparison.
     */
    public void confirmAllLeadership() {
        pendingReads.replaceAll((id, pending) ->
                pending.leadershipConfirmed() ? pending : pending.confirmed());
    }

    /**
     * Confirms leadership for all pending reads that have not yet been confirmed.
     * Called after a successful heartbeat round demonstrates quorum.
     *
     * @param ackCount   total acknowledgement count (including self)
     * @param quorumSize the majority threshold
     * @deprecated Use {@link #confirmAllLeadership()} after an external quorum check
     *             via {@code clusterConfig.isQuorum()} for correct joint consensus handling.
     */
    @Deprecated
    public void confirmAll(int ackCount, int quorumSize) {
        if (ackCount < quorumSize) {
            return;
        }
        confirmAllLeadership();
    }

    /**
     * Clears all pending reads. Called when the node steps down from leadership,
     * since reads cannot be served by a non-leader.
     */
    public void clear() {
        pendingReads.clear();
    }
}

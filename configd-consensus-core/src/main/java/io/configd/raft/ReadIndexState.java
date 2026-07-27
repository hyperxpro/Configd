package io.configd.raft;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks pending linearizable read requests using the ReadIndex protocol.
 * <p>
 * Single-threaded access from the Raft I/O thread. No synchronization is used.
 */
public final class ReadIndexState {

    private record PendingRead(long readIndex, long term, int ackCount, boolean leadershipConfirmed) {

        PendingRead withAck(int newAckCount) {
            return new PendingRead(readIndex, term, newAckCount, leadershipConfirmed);
        }

        PendingRead confirmed() {
            return new PendingRead(readIndex, term, ackCount, true);
        }
    }

    private final Map<Long, PendingRead> pendingReads = new LinkedHashMap<>();
    private long nextReadId;

    /**
     * Records the term so a read that is ready to serve can be rejected if the node's term has
     * since advanced, which means leadership was lost after the heartbeat confirmation.
     */
    public long startRead(long commitIndex, long term) {
        long readId = nextReadId++;
        pendingReads.put(readId, new PendingRead(commitIndex, term, 1, false)); // ackCount 1 = self
        return readId;
    }

    /** Records term 0, meaning unknown: the caller opts out of the stale-leader term check. */
    public long startRead(long commitIndex) {
        return startRead(commitIndex, 0L);
    }

    /** Returns the term recorded when the read started, or -1 if the read ID is not found. */
    public long termOf(long readId) {
        PendingRead pending = pendingReads.get(readId);
        return pending != null ? pending.term() : -1;
    }

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

    public boolean isReady(long readId, long lastApplied) {
        PendingRead pending = pendingReads.get(readId);
        if (pending == null) {
            return false;
        }
        return pending.leadershipConfirmed() && lastApplied >= pending.readIndex();
    }

    /** Returns the recorded read index, or -1 if the read ID is not found. */
    public long readIndex(long readId) {
        PendingRead pending = pendingReads.get(readId);
        return pending != null ? pending.readIndex() : -1;
    }

    public void complete(long readId) {
        pendingReads.remove(readId);
    }

    public int pendingCount() {
        return pendingReads.size();
    }

    /**
     * The caller must have already verified quorum. The check cannot be done here because under
     * joint consensus quorum requires dual-majority validation, not a count comparison.
     */
    public void confirmAllLeadership() {
        pendingReads.replaceAll((id, pending) ->
                pending.leadershipConfirmed() ? pending : pending.confirmed());
    }

    /**
     * @deprecated Use {@link #confirmAllLeadership()} after an external {@code clusterConfig.isQuorum()}
     *             check, which handles joint consensus correctly.
     */
    @Deprecated
    public void confirmAll(int ackCount, int quorumSize) {
        if (ackCount < quorumSize) {
            return;
        }
        confirmAllLeadership();
    }

    public void clear() {
        pendingReads.clear();
    }
}

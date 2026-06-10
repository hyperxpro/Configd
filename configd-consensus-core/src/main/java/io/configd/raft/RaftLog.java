package io.configd.raft;

import io.configd.common.Storage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory Raft log with append, truncation, and snapshot compaction.
 * <p>
 * Log indices are 1-based. Index 0 is a sentinel representing the
 * state before any entries (term 0). After a snapshot at index N,
 * entries [1..N] are discarded and snapshotIndex/snapshotTerm record
 * the last compacted entry.
 * <p>
 * <b>Persistence model:</b> Log entries are persisted via WAL. The
 * {@code commitIndex} and {@code lastApplied} fields are volatile
 * (not persisted) per Raft Figure 2. After a restart, committed
 * entries are re-applied from the WAL up to the snapshot point.
 * Snapshot frequency bounds the replay cost on recovery.
 * <p>
 * This implementation is designed for single-threaded access from the
 * Raft I/O thread (ADR-0009). No synchronization is used.
 */
public final class RaftLog {

    /**
     * Entries stored after the snapshot point. entries.get(0) corresponds
     * to index (snapshotIndex + 1).
     */
    private final ArrayList<LogEntry> entries;

    /**
     * The index of the last entry included in the most recent snapshot.
     * 0 when no snapshot has been taken.
     */
    private long snapshotIndex;

    /**
     * The term of the last entry included in the most recent snapshot.
     */
    private long snapshotTerm;

    /**
     * The highest log index known to be committed. Committed entries
     * are safe to apply to the state machine.
     */
    private long commitIndex;

    /**
     * The highest log index that has been applied to the state machine.
     */
    private long lastApplied;

    /**
     * Optional durable storage for WAL persistence. Null means in-memory mode.
     */
    private final Storage storage;

    private static final String WAL_NAME = "raft-log";
    private static final String WAL_TMP_NAME = "raft-log.tmp";
    private static final String SNAPSHOT_META_KEY = "raft-log.snapshot-meta";
    /**
     * RR-003: durable storage key for the snapshot BYTES (the state-machine
     * bytes plus the SnapshotState envelope). Distinct from
     * {@link #SNAPSHOT_META_KEY}, which carries only the (index, term) boundary.
     * Before this fix the snapshot bytes lived only in a RAM field of RaftNode,
     * so a restart after compaction silently lost all state at/below the
     * snapshot point.
     */
    private static final String SNAPSHOT_BLOB_KEY = "raft-log.snapshot";

    /**
     * The snapshot recovered from durable storage on construction, or {@code null}
     * if none is on disk or it does not match the recovered WAL boundary. Read
     * once by {@link #recoveredSnapshot()} so the owning RaftNode can restore the
     * state machine before replaying the WAL suffix. See the recovery rule in the
     * constructor.
     */
    private final SnapshotState recoveredSnapshot;

    public RaftLog() {
        this.entries = new ArrayList<>(1024);
        this.snapshotIndex = 0;
        this.snapshotTerm = 0;
        this.commitIndex = 0;
        this.lastApplied = 0;
        this.storage = null;
        this.recoveredSnapshot = null;
    }

    /**
     * Creates a RaftLog backed by durable storage.
     * Existing entries are recovered from the WAL on construction.
     * <p>
     * If the first recovered entry has index > 1, it means entries
     * were compacted before the prior shutdown. The snapshotIndex is
     * inferred as (firstEntry.index - 1) to restore correct offset
     * arithmetic. The snapshotTerm is recovered from the persisted
     * snapshot metadata key if available.
     *
     * @param storage the durable storage implementation
     */
    public RaftLog(Storage storage) {
        this.entries = new ArrayList<>(1024);
        this.snapshotIndex = 0;
        this.snapshotTerm = 0;
        this.commitIndex = 0;
        this.lastApplied = 0;
        this.storage = storage;

        // Clean up any leftover temp WAL from an incomplete rewrite
        storage.truncateLog(WAL_TMP_NAME);

        // Recover entries from the WAL
        List<byte[]> walEntries = storage.readLog(WAL_NAME);
        for (byte[] raw : walEntries) {
            ByteBuffer buf = ByteBuffer.wrap(raw);
            long index = buf.getLong();
            long term = buf.getLong();
            byte[] command = new byte[buf.remaining()];
            buf.get(command);
            entries.add(new LogEntry(index, term, command));
        }

        // Recover snapshot boundary from persisted metadata (written by compact()).
        // Format: [8-byte snapshotIndex][8-byte snapshotTerm].
        // This handles both cases:
        //   (a) WAL has entries starting after index 1 (partial compaction)
        //   (b) WAL is empty (full compaction — all entries were in the snapshot)
        byte[] snapMeta = storage.get(SNAPSHOT_META_KEY);
        if (snapMeta != null && snapMeta.length >= 16) {
            ByteBuffer metaBuf = ByteBuffer.wrap(snapMeta);
            this.snapshotIndex = metaBuf.getLong();
            this.snapshotTerm = metaBuf.getLong();
        } else if (!entries.isEmpty()) {
            // Legacy fallback: infer snapshotIndex from first entry's index.
            // This handles WALs written before the metadata format was extended
            // to include snapshotIndex.
            long firstIndex = entries.getFirst().index();
            if (firstIndex > 1) {
                this.snapshotIndex = firstIndex - 1;
                if (snapMeta != null && snapMeta.length >= 8) {
                    this.snapshotTerm = ByteBuffer.wrap(snapMeta).getLong();
                }
            }
        }

        // Cross-validate: if WAL entries exist, the metadata's snapshotIndex
        // must equal (firstEntry.index - 1). Stale metadata from a prior
        // compaction can disagree if a crash occurred between WAL rewrite and
        // metadata persist. In that case, trust the WAL (source of truth).
        if (!entries.isEmpty()) {
            long expectedSnapshotIndex = entries.getFirst().index() - 1;
            if (this.snapshotIndex != expectedSnapshotIndex) {
                this.snapshotIndex = expectedSnapshotIndex;
                // Stale metadata snapshotTerm is also suspect — reset to 0.
                // termAt(snapshotIndex) returns 0, which is conservative for
                // isAtLeastAsUpToDate: may reject a valid vote but never
                // grants an invalid one.
                this.snapshotTerm = 0;
            }
        }

        // RR-003: recover the durable snapshot bytes (the state-machine state at
        // the snapshot boundary) so the owning RaftNode can restore the state
        // machine BEFORE replaying the WAL suffix. Acceptance rule:
        //
        //   accept iff blob.lastIncludedIndex == snapshotIndex (the consistent,
        //   post-compaction steady state).
        //
        // If they disagree, the WAL is authoritative and still holds the entries
        // the blob would have restored (a crash between persisting the blob and
        // rewriting the WAL leaves snapshotIndex < blob.lastIncludedIndex with
        // the full WAL intact), so we ignore the ahead-of-WAL blob and let the
        // full WAL replay. A null/short/corrupt blob is treated as absent.
        SnapshotState blob = readSnapshotBlob();
        if (blob != null && blob.lastIncludedIndex() == this.snapshotIndex && this.snapshotIndex > 0) {
            this.recoveredSnapshot = blob;
            // The blob's term is authoritative for the snapshot boundary even if
            // the WAL/meta cross-validation conservatively reset snapshotTerm.
            this.snapshotTerm = blob.lastIncludedTerm();
        } else {
            this.recoveredSnapshot = null;
        }
    }

    // ---- Query methods ----

    /**
     * Returns the index of the last log entry, or snapshotIndex if log is empty.
     */
    public long lastIndex() {
        if (entries.isEmpty()) {
            return snapshotIndex;
        }
        return entries.getLast().index();
    }

    /**
     * Returns the term of the last log entry, or snapshotTerm if log is empty.
     */
    public long lastTerm() {
        if (entries.isEmpty()) {
            return snapshotTerm;
        }
        return entries.getLast().term();
    }

    /**
     * Returns the term at the given index, or -1 if the index is not in the log.
     * Returns 0 for index 0 (sentinel).
     */
    public long termAt(long index) {
        if (index == 0) {
            return 0;
        }
        if (index == snapshotIndex) {
            return snapshotTerm;
        }
        if (index < snapshotIndex || index > lastIndex()) {
            return -1;
        }
        int offset = toOffset(index);
        return entries.get(offset).term();
    }

    /**
     * Returns the entry at the given index, or null if not in log.
     */
    public LogEntry entryAt(long index) {
        if (index <= snapshotIndex || index > lastIndex()) {
            return null;
        }
        return entries.get(toOffset(index));
    }

    /**
     * Returns a sublist of entries from startIndex to endIndex (inclusive).
     * Indices out of range are clamped.
     */
    public List<LogEntry> entriesFrom(long startIndex, long endIndex) {
        if (startIndex > endIndex || startIndex > lastIndex() || endIndex <= snapshotIndex) {
            return Collections.emptyList();
        }
        long effectiveStart = Math.max(startIndex, snapshotIndex + 1);
        long effectiveEnd = Math.min(endIndex, lastIndex());
        int fromOffset = toOffset(effectiveStart);
        int toOffset = toOffset(effectiveEnd) + 1;
        // Return an unmodifiable view to avoid external mutation without copying
        return Collections.unmodifiableList(entries.subList(fromOffset, toOffset));
    }

    /**
     * Returns entries from startIndex to the end of the log.
     */
    public List<LogEntry> entriesFrom(long startIndex) {
        return entriesFrom(startIndex, lastIndex());
    }

    /**
     * Returns entries suitable for an AppendEntries batch, respecting
     * maximum batch size and byte limits.
     */
    public List<LogEntry> entriesBatch(long startIndex, int maxSize, int maxBytes) {
        if (startIndex > lastIndex() || startIndex <= snapshotIndex) {
            return Collections.emptyList();
        }
        int fromOffset = toOffset(startIndex);
        int count = 0;
        int totalBytes = 0;
        int limit = Math.min(fromOffset + maxSize, entries.size());
        for (int i = fromOffset; i < limit; i++) {
            int entryBytes = entries.get(i).command().length;
            if (count > 0 && totalBytes + entryBytes > maxBytes) {
                break;
            }
            totalBytes += entryBytes;
            count++;
        }
        return Collections.unmodifiableList(entries.subList(fromOffset, fromOffset + count));
    }

    public long commitIndex() {
        return commitIndex;
    }

    public long lastApplied() {
        return lastApplied;
    }

    public long snapshotIndex() {
        return snapshotIndex;
    }

    public long snapshotTerm() {
        return snapshotTerm;
    }

    /**
     * The number of entries currently stored (excludes snapshotted entries).
     */
    public int size() {
        return entries.size();
    }

    // ---- Mutation methods ----

    /**
     * Appends a new entry to the end of the log.
     * The entry's index must equal lastIndex() + 1.
     *
     * @throws IllegalArgumentException if the index is not sequential
     */
    public void append(LogEntry entry) {
        long expectedIndex = lastIndex() + 1;
        if (entry.index() != expectedIndex) {
            throw new IllegalArgumentException(
                    "Expected index " + expectedIndex + " but got " + entry.index());
        }
        if (storage != null) {
            storage.appendToLog(WAL_NAME, serializeEntry(entry));
        }
        entries.add(entry);
    }

    /**
     * Appends multiple entries. Each entry must have sequential indices
     * starting from lastIndex() + 1.
     */
    public void appendAll(List<LogEntry> newEntries) {
        for (LogEntry entry : newEntries) {
            append(entry);
        }
    }

    /**
     * Handles entries received in an AppendEntries RPC.
     * <p>
     * Implements the log matching property (Raft §5.3):
     * <ol>
     *   <li>If an existing entry conflicts with a new one (same index,
     *       different term), truncate the log from that point.</li>
     *   <li>Append any new entries not already in the log.</li>
     * </ol>
     *
     * @param prevLogIndex the index preceding the first new entry
     * @param prevLogTerm  the term at prevLogIndex
     * @param newEntries   entries to replicate
     * @return true if the prevLogIndex/prevLogTerm matched (or prevLogIndex == 0)
     */
    public boolean appendEntries(long prevLogIndex, long prevLogTerm, List<LogEntry> newEntries) {
        // Check that the log contains the entry at prevLogIndex with the correct term.
        if (prevLogIndex > 0) {
            long existingTerm = termAt(prevLogIndex);
            if (existingTerm == -1 || existingTerm != prevLogTerm) {
                return false;
            }
        }

        // Process each new entry
        for (LogEntry newEntry : newEntries) {
            long idx = newEntry.index();
            if (idx <= snapshotIndex) {
                // Entry is already in snapshot, skip
                continue;
            }
            long existingTerm = termAt(idx);
            if (existingTerm == -1) {
                // Entry beyond current log — append
                append(newEntry);
            } else if (existingTerm != newEntry.term()) {
                // Conflict — truncate from this index and append
                truncateFrom(idx);
                append(newEntry);
            }
            // else: entry already in log with same term — skip (idempotent)
        }
        return true;
    }

    /**
     * Truncates all entries from the given index (inclusive) to the end.
     * Used for conflict resolution when a follower's log diverges from
     * the leader's.
     *
     * @param fromIndex the index from which to truncate (inclusive)
     */
    public void truncateFrom(long fromIndex) {
        if (fromIndex <= snapshotIndex) {
            throw new IllegalArgumentException(
                    "Cannot truncate at index " + fromIndex + " which is <= snapshotIndex " + snapshotIndex);
        }
        if (fromIndex > lastIndex()) {
            return;
        }
        int offset = toOffset(fromIndex);
        entries.subList(offset, entries.size()).clear();
        if (storage != null) {
            rewriteWal();
            // F-0012 fix: fsync the directory after WAL rewrite to ensure
            // the rename is durable. Without this, a crash on Linux ext4
            // after renameLog() but before directory metadata sync could
            // lose the truncation, leaving stale entries that violate the
            // log matching property on recovery.
            storage.sync();
        }
    }

    /**
     * Advances the commit index to the given value.
     * The commit index only moves forward.
     *
     * @param newCommitIndex the new commit index
     */
    public void setCommitIndex(long newCommitIndex) {
        if (newCommitIndex > commitIndex) {
            this.commitIndex = Math.min(newCommitIndex, lastIndex());
        }
    }

    /**
     * Advances the lastApplied index.
     */
    public void setLastApplied(long index) {
        if (index > lastApplied) {
            this.lastApplied = index;
        }
    }

    /**
     * RR-003: durably persists the snapshot bytes (state-machine state + the
     * {@link SnapshotState} envelope) to storage, fsynced, before returning.
     * <p>
     * <b>Ordering contract (the durable-prefix invariant).</b> This MUST be
     * called and MUST complete BEFORE {@link #compact(long, long)} deletes the
     * WAL prefix [1..index]. Persist-before-truncate guarantees that at every
     * instant a complete prefix exists on durable storage: either the snapshot
     * bytes (once persisted) OR the still-present WAL. A crash between this call
     * and {@code compact()} leaves the full WAL intact (recovery replays it and
     * ignores the ahead-of-WAL blob); a crash after {@code compact()} has the
     * snapshot bytes durable. There is no instant at which both the WAL prefix
     * and the snapshot bytes for an index are missing.
     * <p>
     * No-op in the in-memory ({@code storage == null}) mode.
     *
     * @param snapshot the snapshot to persist; its {@code lastIncludedIndex} is
     *                 the boundary that {@code compact} will subsequently set
     */
    public void persistSnapshot(SnapshotState snapshot) {
        if (storage == null) {
            return; // in-memory mode: nothing durable to write
        }
        // Storage.put writes a temp file, fsyncs it, atomic-renames, and fsyncs
        // the directory before returning — so the blob is on durable storage
        // when this returns, independent of any later WAL rewrite.
        storage.put(SNAPSHOT_BLOB_KEY, serializeSnapshot(snapshot));
    }

    /**
     * RR-003: the snapshot recovered from durable storage on construction, used
     * by RaftNode to restore the state machine before replaying the WAL suffix,
     * or {@code null} if none is on disk or it does not match the recovered WAL
     * boundary (see the recovery rule in the constructor). Idempotent.
     */
    public SnapshotState recoveredSnapshot() {
        return recoveredSnapshot;
    }

    /**
     * Compacts entries up to the given index (inclusive) for snapshot.
     * After compaction, entries [1..snapshotIndex] are discarded.
     * <p>
     * RR-003: callers that take a real snapshot (not just a follower's
     * InstallSnapshot of already-known state) MUST {@link #persistSnapshot} the
     * snapshot bytes durably BEFORE invoking this, so the WAL prefix is never the
     * sole record of committed state.
     *
     * @param index the index of the last entry included in the snapshot
     * @param term  the term of the last entry included in the snapshot
     */
    public void compact(long index, long term) {
        if (index <= snapshotIndex) {
            return; // Already compacted past this point
        }
        if (index > lastIndex()) {
            // Snapshot includes entries we don't have — clear everything
            entries.clear();
        } else {
            int offset = toOffset(index);
            // Remove entries [0..offset] inclusive
            entries.subList(0, offset + 1).clear();
        }
        this.snapshotIndex = index;
        this.snapshotTerm = term;
        if (storage != null) {
            // Rewrite the WAL FIRST, then persist snapshot metadata.
            //
            // Crash safety analysis:
            // - Crash after rewriteWal() but before metadata persist:
            //   Recovery infers snapshotIndex from first WAL entry (correct),
            //   snapshotTerm defaults to 0 (safe but imprecise — only affects
            //   termAt(snapshotIndex) and isAtLeastAsUpToDate comparisons).
            //
            // - Crash before rewriteWal():
            //   Old WAL is intact, compaction effectively didn't happen.
            //   Any stale metadata from a prior run is harmless — it's only
            //   read when firstIndex > 1, which is consistent with a prior
            //   successful compaction.
            //
            // The reverse order (metadata first, WAL second) is UNSAFE:
            // crashing after metadata but before WAL rewrite leaves a new
            // snapshotTerm paired with the old WAL's snapshotIndex inference.
            rewriteWal();
            ByteBuffer metaBuf = ByteBuffer.allocate(16);
            metaBuf.putLong(index);
            metaBuf.putLong(term);
            storage.put(SNAPSHOT_META_KEY, metaBuf.array());
            storage.sync();
        }
    }

    /**
     * Checks whether candidate's log is at least as up-to-date as this log.
     * Used for vote decisions (Raft §5.4.1).
     * <p>
     * A log is "at least as up-to-date" if its last term is greater,
     * or if terms are equal and its last index is >= this log's last index.
     */
    public boolean isAtLeastAsUpToDate(long candidateLastLogTerm, long candidateLastLogIndex) {
        long myLastTerm = lastTerm();
        if (candidateLastLogTerm != myLastTerm) {
            return candidateLastLogTerm > myLastTerm;
        }
        return candidateLastLogIndex >= lastIndex();
    }

    // ---- Internal helpers ----

    /**
     * Converts a 1-based log index to a 0-based offset into the entries list,
     * accounting for the snapshot offset.
     */
    private int toOffset(long index) {
        return (int) (index - snapshotIndex - 1);
    }

    // ---- WAL persistence helpers ----

    /**
     * Serializes a LogEntry for WAL storage.
     * Format: [8-byte index][8-byte term][N-byte command]
     */
    private static byte[] serializeEntry(LogEntry entry) {
        byte[] command = entry.command();
        ByteBuffer buf = ByteBuffer.allocate(8 + 8 + command.length);
        buf.putLong(entry.index());
        buf.putLong(entry.term());
        buf.put(command);
        return buf.array();
    }

    // ---- Snapshot blob persistence (RR-003) ----
    //
    // RaftLog-level envelope around the state-machine snapshot bytes. This frames
    // the SnapshotState (index/term/clusterConfig + the state-machine `data`),
    // NOT the state machine's internal byte format — the latter is ADR-0028 and
    // is owned by ConfigStateMachine, which serializes/deserializes `data`. A
    // -1 length encodes a null clusterConfigData (legacy snapshots).
    //
    //   [8 lastIncludedIndex][8 lastIncludedTerm]
    //   [4 dataLen][data][4 configLen (-1 == null)][config]

    private static byte[] serializeSnapshot(SnapshotState s) {
        byte[] data = s.data();
        byte[] cfg = s.clusterConfigData();
        int cfgLen = (cfg == null) ? -1 : cfg.length;
        int cfgBytes = (cfg == null) ? 0 : cfg.length;
        ByteBuffer buf = ByteBuffer.allocate(8 + 8 + 4 + data.length + 4 + cfgBytes);
        buf.putLong(s.lastIncludedIndex());
        buf.putLong(s.lastIncludedTerm());
        buf.putInt(data.length);
        buf.put(data);
        buf.putInt(cfgLen);
        if (cfg != null) {
            buf.put(cfg);
        }
        return buf.array();
    }

    /**
     * Reads and deserializes the persisted snapshot blob, or returns {@code null}
     * if absent or structurally invalid (a torn/short blob is treated as absent —
     * the WAL remains authoritative for recovery).
     */
    private SnapshotState readSnapshotBlob() {
        byte[] raw = storage.get(SNAPSHOT_BLOB_KEY);
        if (raw == null || raw.length < 8 + 8 + 4) {
            return null;
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(raw);
            long index = buf.getLong();
            long term = buf.getLong();
            int dataLen = buf.getInt();
            if (dataLen < 0 || buf.remaining() < dataLen + 4) {
                return null; // torn blob
            }
            byte[] data = new byte[dataLen];
            buf.get(data);
            int cfgLen = buf.getInt();
            byte[] cfg = null;
            if (cfgLen >= 0) {
                if (buf.remaining() < cfgLen) {
                    return null; // torn blob
                }
                cfg = new byte[cfgLen];
                buf.get(cfg);
            }
            return new SnapshotState(data, index, term, cfg);
        } catch (RuntimeException e) {
            // Defensive: a malformed blob must not crash recovery; the WAL is
            // authoritative. Treat as absent.
            return null;
        }
    }

    /**
     * Rewrites the entire WAL from the current in-memory entries atomically.
     * Writes to a temp WAL first, then replaces the original via rename.
     * This ensures a crash cannot lose both old and new data.
     */
    private void rewriteWal() {
        if (entries.isEmpty()) {
            // No entries left after compaction — just delete the WAL.
            // No temp-rename needed: losing an empty WAL on crash is safe.
            storage.truncateLog(WAL_NAME);
            storage.truncateLog(WAL_TMP_NAME);
            return;
        }

        // 1. Remove any stale temp file from a previous incomplete rewrite
        storage.truncateLog(WAL_TMP_NAME);

        // 2. Write all current entries to the temp WAL
        for (LogEntry entry : entries) {
            storage.appendToLog(WAL_TMP_NAME, serializeEntry(entry));
        }

        // 3. Atomically replace the old WAL with the temp WAL.
        // Files.move with ATOMIC_MOVE | REPLACE_EXISTING handles this in one step.
        // Do NOT delete the old WAL first — that creates a crash window where
        // both files are gone (the old deleted, the rename not yet complete).
        storage.renameLog(WAL_TMP_NAME, WAL_NAME);
    }
}

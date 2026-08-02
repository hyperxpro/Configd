package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
import io.configd.common.Storage;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static io.configd.raft.RaftArtifactMagic.SNAP_MAGIC;
import static io.configd.raft.RaftArtifactMagic.WALE_MAGIC;

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
 * Raft I/O thread. No synchronization is used.
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

    private long snapshotTerm;

    private long commitIndex;

    private long lastApplied;

    /**
     * Optional durable storage for WAL persistence. Null means in-memory mode.
     */
    private final Storage storage;

    /**
     * At-rest integrity codec applied as a pure transform over the WAL-entry and
     * snapshot-blob payloads. Never null - a keyless {@link IntegrityEnvelope}
     * (version + CRC32C, reads legacy raw bytes) is the default so the in-memory
     * mode and every existing test are unchanged in behavior. The server wires a
     * keyed envelope (HMAC-SHA-256, fail-closed) derived from the cluster signing key.
     */
    private final IntegrityEnvelope integrity;

    /**
     * The Raft group id this log belongs to, stamped as the {@code scopeId} on every WAL entry
     * and snapshot blob and asserted on every recovery read. This is the cross-shard-splice
     * defense: a record physically copied from another shard's WAL still authenticates as bytes
     * (the at-rest key is node-wide), but its embedded {@code scopeId} announces its true shard,
     * so the reader refuses it. At {@code N=1} the single group is {@code gid=0}. Frozen to the
     * range {@code [0, NODE_SCOPE)} - the {@link IntegrityEnvelope#NODE_SCOPE} sentinel is reserved
     * for node-level artifacts and illegal here, so a per-shard reader can never be fooled by one.
     */
    private final int gid;

    /**
     * SHA-256 output size and the genesis (all-zero) prev-hash the first record chains from.
     */
    private static final int HASH_SIZE = 32;
    private static final byte[] GENESIS_PREV_HASH = new byte[HASH_SIZE]; // 32 zero bytes, read-only

    /**
     * Whether this log runs the per-record hash chain: on in the authenticated postures (keyed HMAC
     * or encrypting GCM), off in the keyless posture. The chain is a security control - it binds each
     * WAL record to the SHA-256 of its predecessor's serialized payload, and that {@code prevHash} is
     * inside the record's authenticated payload (envelope MAC / GCM tag), so an interior record cannot
     * be rolled back to an older authentic version without breaking either the successor's chain link
     * or the record's own authenticator. Keyless stays byte-identical (no {@code prevHash}), which is
     * sound because keyless carries no adversarial guarantees anyway.
     */
    private final boolean chained;

    /**
     * The running chain head: SHA-256 of the last in-memory record's serialized inner payload, or
     * {@link #GENESIS_PREV_HASH} when the log is empty. The next appended record stamps this as its
     * {@code prevHash}. Meaningful only when {@link #chained}.
     */
    private byte[] chainHead;

    /**
     * The {@code prevHash} each in-memory entry was serialized with, kept parallel to {@link #entries}
     * (populated only when {@link #chained}). Needed so {@link #rewriteWal()} reproduces each record's
     * exact bytes on a truncation/compaction rewrite, and so a truncation can recompute the head. A
     * surviving entry keeps the {@code prevHash} it was written with even after its predecessor is
     * compacted away (that boundary is bound by the snapshot anchor, not re-verified here).
     */
    private final ArrayList<byte[]> chainPrevHashes;

    private static final String WAL_NAME = "raft-log";
    private static final String WAL_TMP_NAME = "raft-log.tmp";
    /**
     * Durable storage key for the snapshot BYTES (state-machine bytes plus the
     * SnapshotState envelope). Distinct from {@link #SNAPSHOT_META_KEY}, which
     * carries only the (index, term) boundary.
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

    /**
     * The per-shard durability anchor (dual-slot {@code raft-anchor}). Non-null exactly when
     * this log is durable ({@code storage != null}); {@code null} in the in-memory mode. It carries
     * {@code currentTerm}/{@code votedFor} (formerly {@code raft.persistent_state}),
     * {@code snapshotIndex}/{@code snapshotTerm} (formerly the bare {@code snapshot-meta}),
     * and the {@code lastDurableIndex} high-water mark recovery reconciles the WAL against. Every WAL
     * durability barrier ({@link #syncWal()}) raises it after the WAL fsync (INV-ANCHOR-ACK); conflict
     * truncation lowers it before the WAL rewrite (INV-ANCHOR-LOWER); compaction advances its snapshot
     * boundary last. The owning {@link RaftNode} reads {@code currentTerm}/{@code votedFor} from it and
     * persists term/vote through {@link #persistTermVote(long, int)} (persist-before-memory).
     */
    private final AnchorFile anchor;

    public RaftLog() {
        this.entries = new ArrayList<>(1024);
        this.snapshotIndex = 0;
        this.snapshotTerm = 0;
        this.commitIndex = 0;
        this.lastApplied = 0;
        this.storage = null;
        this.integrity = IntegrityEnvelope.keyless();
        this.gid = 0;
        this.chained = false; // keyless in-memory mode: no hash chain
        this.chainHead = GENESIS_PREV_HASH.clone();
        this.chainPrevHashes = new ArrayList<>();
        this.recoveredSnapshot = null;
        this.anchor = null; // in-memory mode: no durable anchor
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
        this(storage, IntegrityEnvelope.keyless());
    }

    /**
     * Creates a RaftLog backed by durable storage with an explicit at-rest integrity
     * codec, scoped to {@code gid=0} (the N=1 single group). Equivalent to
     * {@link #RaftLog(Storage, IntegrityEnvelope, int)} with {@code gid=0}.
     *
     * @param storage   the durable storage implementation
     * @param integrity the at-rest integrity codec (non-null; use
     *                  {@link IntegrityEnvelope#keyless()} for no authentication)
     */
    public RaftLog(Storage storage, IntegrityEnvelope integrity) {
        this(storage, integrity, 0);
    }

    /**
     * Creates a RaftLog backed by durable storage with an explicit at-rest integrity
     * codec and Raft group id. A keyed {@link IntegrityEnvelope} authenticates the WAL
     * entries and snapshot blob (fail-closed: a tampered artifact is refused on recovery);
     * a keyless one applies version + CRC32C only. The {@code gid} is stamped as the
     * envelope {@code scopeId} on every write and asserted on every recovery read
     * (cross-shard-splice defense); at {@code N=1} the single group is {@code gid=0}.
     * <p>
     * On construction the recovered WAL is checked for structural integrity beyond the
     * per-record envelope: <b>contiguity</b> (embedded indices are exactly consecutive),
     * <b>term monotonicity</b> (terms never regress), the <b>snapshot-join</b> (the persisted
     * snapshot boundary sits below the WAL's first surviving index), and - in the authenticated
     * posture - the <b>hash chain</b> (each record binds its predecessor's hash, catching an
     * index-preserving content rollback the position checks miss). Any violation ⇒
     * {@link IntegrityException} — a physically reordered, spliced, or interior-rolled-back record
     * that still authenticates as bytes is refused here.
     *
     * @param storage   the durable storage implementation
     * @param integrity the at-rest integrity codec (non-null)
     * @param gid       the Raft group id, stamped as {@code scopeId}; must be in
     *                  {@code [0, NODE_SCOPE)}
     */
    public RaftLog(Storage storage, IntegrityEnvelope integrity, int gid) {
        if (gid == IntegrityEnvelope.NODE_SCOPE) {
            // The NODE_SCOPE sentinel is reserved for node-level artifacts; a per-shard log
            // must never stamp it, or a per-shard reader could be fooled by a node-level one.
            throw new IllegalArgumentException("gid " + Integer.toHexString(gid)
                    + " collides with the reserved NODE_SCOPE sentinel");
        }
        this.entries = new ArrayList<>(1024);
        this.snapshotIndex = 0;
        this.snapshotTerm = 0;
        this.commitIndex = 0;
        this.lastApplied = 0;
        this.storage = storage;
        this.integrity = java.util.Objects.requireNonNull(integrity, "integrity");
        this.gid = gid;
        this.chained = integrity.isKeyed() || integrity.isEncrypting();
        this.chainHead = GENESIS_PREV_HASH.clone();
        this.chainPrevHashes = new ArrayList<>();

        // Open the per-shard anchor beside the WAL. It carries currentTerm/votedFor (formerly
        // raft.persistent_state) and snapshotIndex/snapshotTerm (formerly raft-log.snapshot-meta),
        // plus the durable-head high-water mark. A real FileStorage backs it with a dedicated
        // dual-slot file in the WAL directory; every other backing carries the same image as one
        // self-durable value so a crash model still captures it.
        this.anchor = AnchorFile.openOverIO(anchorIOFor(storage), gid, integrity);

        // Clean up any leftover temp WAL from an incomplete rewrite
        storage.truncateLog(WAL_TMP_NAME);

        // Recover entries from the WAL. FileStorage has already dropped any torn
        // trailing frame (incomplete length/data/CRC32) BEFORE these bytes reach us,
        // so every `raw` here is a complete, CRC32-valid frame. deserializeEntry then
        // verifies the at-rest integrity envelope: a complete-but-tampered frame (MAC
        // mismatch / scope mismatch under a keyed codec) fails loudly (torn-vs-tamper
        // rule), never silently dropped. A non-enveloped record is now refused too
        // (the legacy raw-record fallback is deleted). In the authenticated posture
        // deserializeEntry also records each entry's chain prevHash into chainPrevHashes.
        List<byte[]> walEntries = storage.readLog(WAL_NAME);
        for (byte[] raw : walEntries) {
            entries.add(deserializeEntry(raw));
        }

        // Recovery-time structural checks (beyond the per-record envelope). Two layers, in this
        // order so the more specific error surfaces first:
        //  1. Position checks (contiguity + term monotonicity): catch index permutations, gaps,
        //     and duplicates - a physically reordered/spliced-by-index WAL. These do NOT catch an
        //     index-preserving, term-monotonic content substitution (an interior rollback to an
        //     older authentic record) - that is the job of the chain below.
        //  2. Hash chain (authenticated posture only): each record binds SHA-256 of its
        //     predecessor's serialized payload, so an interior stale-content splice breaks the
        //     link and is REFUSED. Runs AFTER the position checks so a reorder still reports
        //     "contiguity", not a chain break.
        verifyRecoveredEntries(entries);
        if (chained) {
            verifyRecoveredChain();
        }

        // Anchor recovery: presence gate (FRESH vs REFUSE), snapshot boundary from the authenticated
        // anchor, the term-witness gate, and the head reconciliation (W==A accept / W>A
        // accept-forward / W<A REFUSE) plus the WAL-head-term check. This sets
        // snapshotIndex/snapshotTerm and may rewrite the anchor forward.
        recoverWithAnchor();

        // Recover the durable snapshot bytes (state-machine state at the snapshot
        // boundary) so the owning RaftNode can restore the state machine BEFORE
        // replaying the WAL suffix. Acceptance rule:
        //
        //   accept iff blob.lastIncludedIndex == snapshotIndex (consistent,
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
            // The blob's term matches the anchor's snapshotTerm in a legal execution; adopt it as
            // authoritative for the restored boundary.
            this.snapshotTerm = blob.lastIncludedTerm();
        } else {
            this.recoveredSnapshot = null;
        }
    }


    public long lastIndex() {
        if (entries.isEmpty()) {
            return snapshotIndex;
        }
        return entries.getLast().index();
    }

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

    public LogEntry entryAt(long index) {
        if (index <= snapshotIndex || index > lastIndex()) {
            return null;
        }
        return entries.get(toOffset(index));
    }

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

    public List<LogEntry> entriesFrom(long startIndex) {
        return entriesFrom(startIndex, lastIndex());
    }

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
     * The Raft group id this log is scoped to (the envelope {@code scopeId} on its WAL
     * entries, snapshot blob, and merged anchor). {@code 0} at N=1. Frozen to
     * {@code [0, NODE_SCOPE)}.
     */
    public int gid() {
        return gid;
    }

    /**
     * The current term recovered from the anchor (0 for a fresh node / the in-memory mode). The
     * owning {@link RaftNode} seeds its in-memory {@code currentTerm} from this.
     */
    long recoveredCurrentTerm() {
        return anchor == null ? 0L : anchor.current().currentTerm();
    }

    /**
     * The candidate this node voted for in {@link #recoveredCurrentTerm()}, or {@code -1} for none
     * (or the in-memory mode). The owning {@link RaftNode} seeds its {@code votedFor} from this.
     */
    int recoveredVotedForId() {
        return anchor == null ? AnchorRecord.VOTED_FOR_NULL : anchor.current().votedFor();
    }

    /**
     * Persist-before-memory term/vote write through the anchor. This is a STANDALONE
     * durable barrier - it MUST NOT be folded into the flush-cycle head write, or the Step-2.5
     * invariant {@code anchor.currentTerm >= lastWALTerm} breaks and recovery false-positives. The
     * durable head and snapshot boundary are unchanged; only currentTerm/votedFor advance. On the
     * in-memory ({@code anchor == null}) path this is a no-op - term/vote live only in memory. The
     * caller updates its in-memory currentTerm/votedFor only AFTER this returns.
     */
    void persistTermVote(long term, int votedForId) {
        if (anchor != null) {
            anchor.writeTermVote(term, votedForId);
        }
    }

    /** Releases the anchor's file handle (idempotent, no-op in the in-memory mode). */
    void closeAnchor() {
        if (anchor != null) {
            anchor.close();
        }
    }

    /** The merged durability anchor, or {@code null} in the in-memory mode. Package-private for tests. */
    AnchorFile anchor() {
        return anchor;
    }

    /**
     * The durable-head high-water mark this shard's {@code raft-anchor} records ({@code 0} for a
     * fresh node / the in-memory mode). This is the value the node-anchor's {@code shardAnchorDigest}
     * fingerprints: a shard wiped to FRESH resets it to 0, changing the digest.
     *
     * <p>Read at boot (single-threaded, after recovery, before the owner thread is bound) or on this
     * group's owner thread (via {@code RaftNode.log()}). It is a plain read of the anchor's in-memory
     * record - the anchor is owner-thread-confined, so off-owner callers must marshal onto the owner.
     */
    public long lastDurableIndex() {
        return anchor == null ? 0L : anchor.current().lastDurableIndex();
    }

    /**
     * The strictly-monotone anti-rollback index this shard's {@code raft-anchor} currently records
     * ({@code 0} for the in-memory mode, which has no durable anchor). Every anchor write - term/vote,
     * durable-head advance, snapshot - bumps it (see {@link AnchorFile}), so a within-term
     * {@code votedFor} rollback lowers it. This is the quantity the peer-quorum {@link AnchorWitness}
     * gossips and the value {@code RaftNode} snapshots at boot as {@code bootAnchorSeq}.
     *
     * <p>Read at boot (single-threaded, after recovery, before the owner thread is bound) or on this
     * group's owner thread. A plain read of the owner-thread-confined anchor's in-memory record.
     */
    public long anchorSeq() {
        return anchor == null ? 0L : anchor.current().anchorSeq();
    }

    /**
     * Whether this shard's {@code raft-anchor} file existed at open (false ⇒ the shard booted FRESH -
     * no anchor file - which, once the node-anchor proves the node was already initialized, is the
     * wipe signature the node-anchor cross-check REFUSEs). {@code false} in the in-memory mode.
     */
    public boolean anchorExistedAtOpen() {
        return anchor != null && anchor.existedAtOpen();
    }

    /**
     * The number of entries currently stored (excludes snapshotted entries).
     */
    public int size() {
        return entries.size();
    }


    /**
     * Appends a new entry to the end of the log.
     * The entry's index must equal lastIndex() + 1.
     *
     * @throws IllegalArgumentException if the index is not sequential
     */
    public void append(LogEntry entry) {
        appendNoSync(entry);
        syncWal();
    }

    /**
     * Appends a new entry to the in-memory log and the WAL <b>without</b> fsyncing it.
     * The entry is NOT durable until a subsequent {@link #syncWal()} returns; any caller
     * that counts this entry toward Raft commitment - the leader's own match, or a
     * follower's AppendEntries ACK - MUST call {@link #syncWal()} first. RaftNode gates
     * its leader durable-index on this so a non-durable self-copy is never counted in a
     * commit quorum. The entry's index must equal {@code lastIndex() + 1}.
     *
     * @throws IllegalArgumentException if the index is not sequential
     */
    public void appendNoSync(LogEntry entry) {
        long expectedIndex = lastIndex() + 1;
        if (entry.index() != expectedIndex) {
            throw new IllegalArgumentException(
                    "Expected index " + expectedIndex + " but got " + entry.index());
        }
        if (storage != null) {
            if (anchor != null && entry.term() > anchor.current().currentTerm()) {
                // Term-adoption discipline: a term-T entry is only appended after the node has durably
                // adopted term T. RaftNode persists that (with correct vote handling) BEFORE it appends,
                // so in every production path anchor.currentTerm already covers entry.term and this is a
                // no-op. It fires only for direct-RaftLog callers (tests that append without a RaftNode),
                // maintaining the anchor.currentTerm >= WAL-term invariant recovery's Step-2.5 gate
                // relies on. It is a STANDALONE persist-before-append term write (advancing the term
                // clears the per-term vote, Raft 5.2), never folded into the flush-cycle head write.
                anchor.writeTermVote(entry.term(), AnchorRecord.VOTED_FOR_NULL);
            }
            // In the authenticated posture the record chains from the current head: prevHash is the
            // running chainHead, and after writing, chainHead advances to this record's hash. Keyless
            // stays byte-identical (prevHash == null, no chain tracking).
            byte[] prevHash = chained ? chainHead : null;
            byte[] inner = serializeInner(entry, prevHash);
            storage.appendToLogNoSync(WAL_NAME, integrity.wrap(WALE_MAGIC, gid, inner));
            if (chained) {
                chainPrevHashes.add(prevHash);
                chainHead = sha256(inner);
            }
        }
        entries.add(entry);
    }

    /**
     * Forces every entry appended via {@link #appendNoSync} since the last sync to durable
     * storage. After this returns, all entries up to {@link #lastIndex()} are durable. No-op in
     * the in-memory ({@code storage == null}) mode. One {@code syncLog} amortizes the fsync
     * across the whole batch - the group-commit win over per-entry force.
     */
    public void syncWal() {
        if (storage == null) {
            return;
        }
        storage.syncLog(WAL_NAME);
        if (anchor != null) {
            // INV-ANCHOR-ACK: the anchor fsync joins the WAL barrier. Raise the durable head to the
            // WAL head AFTER the WAL is durable (W-fsync strictly before A-write before A-fsync), so
            // any index the caller is about to count toward commit / report as matchIndex is already
            // anchor-covered. A no-op when the head is unchanged (an empty flush).
            anchor.writeDurableHead(lastIndex(), lastTerm());
        }
    }

    /**
     * Appends multiple entries with a single fsync for the whole batch (group commit). Each
     * entry must have sequential indices starting from lastIndex() + 1. On return, all appended
     * entries are durable.
     */
    public void appendAll(List<LogEntry> newEntries) {
        if (newEntries.isEmpty()) {
            return;
        }
        for (LogEntry entry : newEntries) {
            appendNoSync(entry);
        }
        syncWal();
    }

    /**
     * Handles entries received in an AppendEntries RPC.
     * <p>
     * Implements the log matching property (Raft section 5.3):
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
        if (prevLogIndex > 0) {
            long existingTerm = termAt(prevLogIndex);
            if (existingTerm == -1 || existingTerm != prevLogTerm) {
                return false;
            }
        }

        // Buffer the appends and fsync ONCE for the whole RPC batch (appendNoSync + a single
        // trailing syncWal) instead of one fsync per entry. Persist-before-ACK is preserved:
        // syncWal() completes before this method returns, and the follower's AppendEntries
        // response is sent only after it returns - so the matchIndex the follower reports is
        // always already durable.
        boolean appended = false;
        for (LogEntry newEntry : newEntries) {
            long idx = newEntry.index();
            if (idx <= snapshotIndex) {
                continue;
            }
            long existingTerm = termAt(idx);
            if (existingTerm == -1) {
                appendNoSync(newEntry);
                appended = true;
            } else if (existingTerm != newEntry.term()) {
                // Conflict - truncate from this index (durably rewrites the WAL) and append
                truncateFrom(idx);
                appendNoSync(newEntry);
                appended = true;
            }
            // else: entry already in log with same term - skip (idempotent)
        }
        if (appended) {
            syncWal();
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
        if (chained) {
            // Drop the truncated tail's prevHashes and recompute the head from the record now at the
            // tail (index fromIndex-1). A legitimate conflict truncation must not trip the chain on
            // recovery, so the surviving prefix keeps its original prevHashes and the head re-points to
            // the new last record's hash; if truncated to empty the head resets to GENESIS (a re-append
            // then starts a fresh link, whose first record is either true genesis (index 1, verified) or
            // a post-compaction boundary (index > 1, bound by the anchor, not re-verified)).
            chainPrevHashes.subList(Math.min(offset, chainPrevHashes.size()), chainPrevHashes.size()).clear();
            chainHead = headHashOfTail();
        }
        if (storage != null) {
            if (anchor != null) {
                // INV-ANCHOR-LOWER: lower the anchor's durable head to the post-truncation head and
                // fsync BEFORE the WAL rewrite. Lowering first means a crash between the lower and the
                // rewrite leaves anchor.lastDurableIndex <= WAL head (W>=A -> accept-forward), never
                // anchor > WAL head (a spurious W<A REFUSE on a legal Raft conflict truncation). The
                // conflict point is always > commitIndex (Raft never truncates a committed entry), so
                // this downward move never uncovers a committed-and-acked index. The re-append that
                // follows (in appendEntries) raises the anchor again via syncWal().
                anchor.writeDurableHead(lastIndex(), lastTerm());
            }
            rewriteWal();
            // Fsync the directory after WAL rewrite to ensure the rename is durable.
            // Without this, a crash on Linux ext4 after renameLog() but before
            // directory metadata sync could lose the truncation, leaving stale entries
            // that violate the log matching property on recovery.
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

    public void setLastApplied(long index) {
        if (index > lastApplied) {
            this.lastApplied = index;
        }
    }

    public void persistSnapshot(SnapshotState snapshot) {
        if (storage == null) {
            return;
        }
        storage.put(SNAPSHOT_BLOB_KEY, serializeSnapshot(snapshot));
    }

    public SnapshotState recoveredSnapshot() {
        return recoveredSnapshot;
    }

    public void compact(long index, long term) {
        if (index <= snapshotIndex) {
            return;
        }
        if (index > lastIndex()) {
            // Snapshot includes entries we don't have - clear everything
            entries.clear();
            if (chained) {
                chainPrevHashes.clear();
            }
        } else {
            int offset = toOffset(index);
            entries.subList(0, offset + 1).clear();
            if (chained) {
                // Drop the compacted PREFIX's prevHashes, keeping the survivors' original prevHashes
                // (a survivor still binds its now-compacted predecessor - that boundary is bound by
                // the snapshot anchor, not re-verified). chainHead (the TAIL's hash) is
                // unchanged: compaction removes a prefix, never the head, so appends keep chaining.
                chainPrevHashes.subList(0, Math.min(offset + 1, chainPrevHashes.size())).clear();
            }
        }
        this.snapshotIndex = index;
        this.snapshotTerm = term;
        if (storage != null) {
            // Order: persistSnapshot (blob durable, done by the caller before compact) ->
            // rewriteWal -> dir sync -> advance the anchor's snapshot boundary LAST.
            //
            // Crash safety:
            // - Crash after rewriteWal() but before the anchor advance:
            //   the WAL now starts at index+1 while the anchor still names the OLD snapshot
            //   boundary; recovery adopts boundary = WAL.firstIndex-1 = index and REQUIRES the
            //   blob@index that persistSnapshot already made durable (else REFUSE) - the
            //   "trust the durable WAL/blob over the lagging anchor" accept-forward for the snapshot.
            // - Crash before rewriteWal(): the old WAL is intact and the anchor is unchanged;
            //   compaction effectively did not happen.
            // Anchor-last (never before the WAL rewrite) is the compaction analogue of
            // INV-ANCHOR-LOWER: the boundary is only committed once the durable prefix it names
            // (blob + rewritten WAL) is in place.
            rewriteWal();
            storage.sync();
            if (anchor != null) {
                anchor.writeSnapshot(index, term, lastIndex(), lastTerm());
            }
        }
    }

    public boolean isAtLeastAsUpToDate(long candidateLastLogTerm, long candidateLastLogIndex) {
        long myLastTerm = lastTerm();
        if (candidateLastLogTerm != myLastTerm) {
            return candidateLastLogTerm > myLastTerm;
        }
        return candidateLastLogIndex >= lastIndex();
    }

    private int toOffset(long index) {
        return (int) (index - snapshotIndex - 1);
    }

    /**
     * Verifies the recovered WAL entries satisfy the two POSITION invariants that per-record
     * authentication alone cannot: <b>contiguity</b> (each embedded index is exactly one more than
     * its predecessor, so the run has no gap, duplicate, or reorder) and <b>term monotonicity</b>
     * (terms never regress, which Raft guarantees because a node never writes a lower term at a
     * later index). A physically reordered, duplicated, gapped, or different-index-substituted record
     * still authenticates as bytes - its own envelope MAC/tag is intact - so these whole-log checks
     * are what detect an index PERMUTATION. What they do NOT catch is an index-preserving,
     * term-monotonic content substitution (an interior record rolled back to an older authentic
     * version at the SAME index and a non-decreasing term): that passes both checks and is closed by
     * the per-record hash chain ({@link #verifyRecoveredChain()}), not here. Any violation ⇒
     * {@link IntegrityException} (recovery REFUSES). Legitimate compaction (a run that starts at
     * {@code firstIndex > 1}) is fine: the run must merely be internally consecutive, not start at 1.
     */
    /** Picks the anchor backend: a real dual-slot file for FileStorage, else a self-durable value. */
    private static AnchorIO anchorIOFor(Storage storage) {
        return storage.storageDirectory()
                .<AnchorIO>map(FileAnchorIO::new)
                .orElseGet(() -> new StorageAnchorIO(storage));
    }

    /** Whether a durable snapshot blob is present (part of the "non-empty shard dir" presence test). */
    private boolean snapshotBlobPresent() {
        return storage.get(SNAPSHOT_BLOB_KEY) != null;
    }

    /**
     * Runs the anchor-backed recovery gates, after the WAL
     * structural checks (contiguity / term-monotonicity / hash chain) have already run:
     * <ol>
     *   <li><b>Presence.</b> No anchor + empty shard dir ⇒ FRESH (lay down the bootstrap anchor);
     *       no anchor + non-empty shard dir ⇒ REFUSE (an anchor was deleted); present with both
     *       slots invalid ⇒ REFUSE (tamper, distinct from FRESH).</li>
     *   <li><b>Snapshot-join.</b> {@code WAL.firstIndex == anchor.snapshotIndex + 1}.</li>
     *   <li><b>Step-2.5 term-witness.</b> {@code lastWALTerm <= anchor.currentTerm} (a WAL term above
     *       the anchor's current term is an anchor rollback across a witnessed vote boundary).</li>
     *   <li><b>Head reconciliation.</b> {@code W == A} accept (and require {@code WAL[W].term ==
     *       anchor.lastDurableTerm}, the tail-content-rollback closer); {@code W > A} accept-forward
     *       (adopt the WAL head, keep currentTerm/votedFor verbatim, rewrite the anchor);
     *       {@code W < A} REFUSE (a committed-and-acked durable entry vanished).</li>
     * </ol>
     * Any violation ⇒ {@link IntegrityException} (recovery REFUSES, fail closed).
     */
    private void recoverWithAnchor() {
        boolean shardNonEmpty = !entries.isEmpty() || snapshotBlobPresent();
        if (!anchor.existedAtOpen()) {
            if (shardNonEmpty) {
                throw new IntegrityException("WAL recovery for gid " + gid
                        + " found data (WAL/snapshot present) but no raft-anchor - an anchor was"
                        + " deleted; refusing to boot without the durable anchor (fail closed)");
            }
            // FRESH node: lay down the bootstrap anchor (seq=1, all zero). snapshotIndex/Term stay 0.
            anchor.bootstrapFresh();
            return;
        }
        if (!anchor.hasValidRecord()) {
            throw new IntegrityException("WAL recovery for gid " + gid
                    + " found a raft-anchor with both slots invalid - refusing (tamper; distinct from a"
                    + " fresh node, which has no anchor file at all)");
        }

        AnchorRecord a = anchor.current();
        // Snapshot-join reconciliation. Let expectedSnap = WAL.firstIndex - 1 (the boundary the WAL
        // implies). Three cases against the anchor's snapshot boundary:
        //   expectedSnap == anchor.snapshotIndex : clean join (take the anchor's boundary + term).
        //   expectedSnap  > anchor.snapshotIndex : WAL-AHEAD accept-forward - a compaction rewrote the
        //       WAL to start at firstIndex but its anchor snapshot-advance was lost (crash/fsync-fail
        //       AFTER the WAL rewrite). The durable WAL is authoritative for the boundary; adopt it. The
        //       matching authenticated blob@expectedSnap must be present (enforced by the snapshot-blob
        //       recovery + the owner's durable_prefix_no_gap check), and snapshotTerm comes from it.
        //   expectedSnap  < anchor.snapshotIndex : REFUSE - the WAL retains entries at/below a boundary
        //       the anchor asserts was committed-and-compacted (a snapshot rollback / pre-snapshot splice).
        this.snapshotIndex = a.snapshotIndex();
        this.snapshotTerm = a.snapshotTerm();
        if (!entries.isEmpty()) {
            long firstIndex = entries.getFirst().index();
            long expectedSnap = firstIndex - 1;
            if (expectedSnap < a.snapshotIndex()) {
                throw new IntegrityException("WAL recovery snapshot-join violation for gid " + gid
                        + ": WAL first index " + firstIndex + " is at/below the anchor's snapshot boundary "
                        + a.snapshotIndex() + " (the WAL retains entries the anchor compacted - rollback refused)");
            }
            if (expectedSnap > a.snapshotIndex()) {
                // Require the matching authenticated blob@expectedSnap; without it this is not a lagging
                // compaction but a FRONT TRUNCATION fabricating a phantom compaction (committed indices
                // silently dropped) - REFUSE. With it, the durable WAL+blob are authoritative.
                SnapshotState blob = readSnapshotBlob();
                if (blob == null || blob.lastIncludedIndex() != expectedSnap) {
                    throw new IntegrityException("WAL recovery front-truncation refused for gid " + gid
                            + ": WAL first index " + firstIndex + " implies a snapshot boundary "
                            + expectedSnap + " above the anchor's " + a.snapshotIndex()
                            + " but no matching authenticated snapshot blob is present (phantom compaction)");
                }
                this.snapshotIndex = expectedSnap;
                this.snapshotTerm = blob.lastIncludedTerm();
            }
        }

        long walHead = entries.isEmpty() ? a.snapshotIndex() : entries.getLast().index();
        long walHeadTerm = entries.isEmpty() ? a.snapshotTerm() : entries.getLast().term();

        // Step 2.5: anchor.currentTerm dominates every WAL term in every legal execution.
        if (walHeadTerm > a.currentTerm()) {
            throw new IntegrityException("WAL recovery term-witness violation for gid " + gid
                    + ": WAL last term " + walHeadTerm + " exceeds anchor.currentTerm "
                    + a.currentTerm() + " (anchor rollback across a vote boundary refused)");
        }

        long anchorHead = a.lastDurableIndex();
        if (walHead == anchorHead) {
            // Tail-content-rollback closer: at a LIVE head (above the snapshot boundary) the WAL's
            // term must equal the anchor's durable term. At/below the boundary the snapshot binds it.
            if (walHead > a.snapshotIndex() && walHeadTerm != a.lastDurableTerm()) {
                throw new IntegrityException("WAL recovery head-term mismatch for gid " + gid
                        + " at index " + walHead + ": WAL term " + walHeadTerm
                        + " != anchor.lastDurableTerm " + a.lastDurableTerm()
                        + " (tail content rollback refused)");
            }
        } else if (walHead > anchorHead) {
            // Accept-forward: (A, W] were never committed-and-client-acked (INV-ANCHOR-ACK). Adopt the
            // WAL head for the LOG only; currentTerm/votedFor stay verbatim (Step-2.5 proved the anchor
            // term already dominates, so no repair and no votedFor clear); rewrite the anchor forward.
            anchor.writeDurableHead(walHead, walHeadTerm);
        } else {
            throw new IntegrityException("WAL recovery head-rollback for gid " + gid
                    + ": WAL last index " + walHead + " is below anchor.lastDurableIndex " + anchorHead
                    + " (a committed-and-acked durable entry vanished - refusing, fail closed)");
        }
    }

    private void verifyRecoveredEntries(List<LogEntry> recovered) {
        if (recovered.isEmpty()) {
            return;
        }
        long firstIndex = recovered.getFirst().index();
        long prevTerm = -1;
        for (int k = 0; k < recovered.size(); k++) {
            LogEntry e = recovered.get(k);
            long expectedIndex = firstIndex + k;
            if (e.index() != expectedIndex) {
                throw new IntegrityException("WAL recovery contiguity violation for gid " + gid
                        + " at position " + k + ": embedded index " + e.index()
                        + " but expected " + expectedIndex + " (gap/duplicate/reorder refused)");
            }
            if (e.term() < prevTerm) {
                throw new IntegrityException("WAL recovery term regression for gid " + gid
                        + " at index " + e.index() + ": term " + e.term()
                        + " follows higher term " + prevTerm + " (reorder/splice refused)");
            }
            prevTerm = e.term();
        }
    }


    /**
     * Builds the WAL record's inner payload (the bytes handed to the integrity envelope). Its shape is
     * posture-dependent:
     * <ul>
     *   <li>authenticated (chained): {@code [index:8][term:8][prevHash:32][command:N]} - the
     *       {@code prevHash} binds this record to its predecessor, and because it lives inside the
     *       envelope's authenticated body (HMAC input / GCM AAD-and-ciphertext) it is unforgeable
     *       in place;</li>
     *   <li>keyless: {@code [index:8][term:8][command:N]} - byte-identical to the pre-chain format
     *       (no {@code prevHash}); keyless carries no adversarial guarantees.</li>
     * </ul>
     * The {@code prevHash} argument is ignored in the keyless posture.
     */
    private byte[] serializeInner(LogEntry entry, byte[] prevHash) {
        byte[] command = entry.command();
        if (chained) {
            ByteBuffer buf = ByteBuffer.allocate(8 + 8 + HASH_SIZE + command.length);
            buf.putLong(entry.index());
            buf.putLong(entry.term());
            buf.put(prevHash);
            buf.put(command);
            return buf.array();
        }
        ByteBuffer buf = ByteBuffer.allocate(8 + 8 + command.length);
        buf.putLong(entry.index());
        buf.putLong(entry.term());
        buf.put(command);
        return buf.array();
    }

    /**
     * Deserializes a WAL record, verifying the at-rest integrity envelope (including the
     * {@code scopeId == gid} assert) first, then parsing the inner payload. In the authenticated
     * posture the record's {@code prevHash} is extracted and appended to {@link #chainPrevHashes}
     * (kept parallel to {@link #entries}) for the chain verification / later rewrites; the returned
     * {@link LogEntry} carries only {@code (index, term, command)} - {@code prevHash} is WAL-internal
     * chain metadata, not part of the logical entry.
     * <p>
     * The frame reaching here is already complete and CRC32-valid (FileStorage dropped any torn
     * trailing frame). A complete-but-tampered frame fails the envelope's MAC/CRC32C/version/scope
     * check and throws {@link IntegrityException} - recovery refuses rather than replaying forged
     * committed state (torn-vs-tamper rule). Every WAL record MUST be enveloped: the legacy
     * raw-record fallback is DELETED; {@code unwrapOrNull} returns {@code null}
     * only for a present-but-non-enveloped complete frame, which is refused fail-closed here.
     */
    private LogEntry deserializeEntry(byte[] raw) {
        byte[] payload = integrity.unwrapOrNull(WALE_MAGIC, gid, raw);
        if (payload == null) {
            throw new IntegrityException("WAL record for gid " + gid
                    + " is not a valid WALE_MAGIC integrity envelope (non-enveloped record refused)");
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        if (buf.remaining() < 8 + 8 + (chained ? HASH_SIZE : 0)) {
            throw new IntegrityException("WAL record for gid " + gid + " is too short ("
                    + payload.length + " bytes) to carry the "
                    + (chained ? "index/term/prevHash" : "index/term") + " header (refused)");
        }
        long index = buf.getLong();
        long term = buf.getLong();
        if (chained) {
            byte[] prevHash = new byte[HASH_SIZE];
            buf.get(prevHash);
            chainPrevHashes.add(prevHash);
        }
        byte[] command = new byte[buf.remaining()];
        buf.get(command);
        return new LogEntry(index, term, command);
    }

    /**
     * Verifies the recovered hash chain (authenticated posture only), AFTER the position checks so a
     * reorder still reports as a contiguity violation. For surviving records r_0..r_m:
     * <ul>
     *   <li>r_0 at index 1 (true genesis, no compaction): its {@code prevHash} MUST be GENESIS
     *       (all-zero) - a fabricated head is refused;</li>
     *   <li>r_0 at index &gt; 1 (a compacted prefix): its {@code prevHash} refers to a record we no
     *       longer hold, so it is NOT verified here - the snapshot anchor binds that boundary;</li>
     *   <li>each r_k (k &ge; 1): its {@code prevHash} MUST equal SHA-256 of r_{k-1}'s serialized
     *       payload - a mismatch is an interior splice / content rollback and is REFUSED.</li>
     * </ul>
     * On success {@link #chainHead} is set to the last record's hash so subsequent appends chain on.
     */
    private void verifyRecoveredChain() {
        if (entries.isEmpty()) {
            chainHead = GENESIS_PREV_HASH.clone();
            return;
        }
        byte[] runningHash = null;
        for (int k = 0; k < entries.size(); k++) {
            byte[] prevHash = chainPrevHashes.get(k);
            byte[] inner = serializeInner(entries.get(k), prevHash);
            if (k == 0) {
                if (entries.get(0).index() == 1 && !Arrays.equals(prevHash, GENESIS_PREV_HASH)) {
                    throw new IntegrityException("WAL chain: genesis record at index 1 for gid " + gid
                            + " has a non-GENESIS prevHash (fabricated chain head refused)");
                }
                // index > 1: the predecessor is compacted; the anchor binds this boundary.
            } else if (!Arrays.equals(prevHash, runningHash)) {
                throw new IntegrityException("WAL chain break at index " + entries.get(k).index()
                        + " for gid " + gid + ": prevHash does not match the predecessor's record hash "
                        + "(interior splice / content rollback refused)");
            }
            runningHash = sha256(inner);
        }
        chainHead = runningHash;
    }

    /** The chain head implied by the current tail: the last record's hash, or GENESIS if empty. */
    private byte[] headHashOfTail() {
        if (!chained || entries.isEmpty()) {
            return GENESIS_PREV_HASH.clone();
        }
        int last = entries.size() - 1;
        return sha256(serializeInner(entries.get(last), chainPrevHashes.get(last)));
    }

    /** SHA-256 over the exact serialized inner-payload bytes - the chain's record hash. */
    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // Snapshot blob persistence.
    //
    // RaftLog-level envelope around the state-machine snapshot bytes. This frames the
    // SnapshotState (index/term/clusterConfig + the state-machine `data`), NOT the state
    // machine's internal byte format (which is owned by ConfigStateMachine). A -1 length
    // encodes a null clusterConfigData (legacy snapshots).
    //
    //   [8 lastIncludedIndex][8 lastIncludedTerm]
    //   [4 dataLen][data][4 configLen (-1 == null)][config]

    private byte[] serializeSnapshot(SnapshotState s) {
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
        // Wrap the snapshot-envelope bytes in the at-rest integrity envelope, scoped to gid.
        // The snapshot blob is an atomic-rename artifact (never torn), so any keyed-MAC / scope
        // mismatch on recovery is unambiguously tamper/cross-shard and fails loud in readSnapshotBlob.
        return integrity.wrap(SNAP_MAGIC, gid, buf.array());
    }

    /**
     * Reads and deserializes the persisted snapshot blob, or returns {@code null}
     * if the blob is structurally absent or too short to be an envelope (legitimate
     * first boot / torn-short blob - the WAL remains authoritative).
     * <p>
     * A structurally-complete blob whose integrity envelope FAILS verification
     * (keyed-MAC mismatch, CRC32C mismatch, rolled version, or a downgrade to
     * algId=NONE under a configured key) propagates as an
     * {@link io.configd.common.IntegrityException} - it MUST NOT be swallowed to
     * {@code null}. Returning null would treat the tampered blob as "absent" and
     * fall back to the WAL, re-enabling a silent-downgrade attack. The snapshot blob
     * is written by {@link Storage#put} (temp + fsync + atomic rename) so it is
     * never torn - any MAC mismatch is unambiguously tamper.
     */
    private SnapshotState readSnapshotBlob() {
        byte[] raw = storage.get(SNAPSHOT_BLOB_KEY);
        // unwrapOrNull returns null ONLY for structurally-absent/too-short bytes (or, under a
        // keyless codec, legacy non-enveloped bytes); it THROWS IntegrityException on a
        // present-but-tampered / scope-wrong envelope. That throw deliberately propagates
        // (a tampered blob must not be silently treated as absent, which would fall back to the
        // WAL and re-enable a silent-downgrade). Under a keyed/encrypting codec the raw fallback
        // below is never reached for a present blob (unwrapOrNull throws rather than returns null),
        // so it only ever parses genuinely-absent bytes (-> null) or a keyless legacy blob.
        byte[] payload = integrity.unwrapOrNull(SNAP_MAGIC, gid, raw);
        if (payload == null) {
            // Keyless back-compat: legacy raw (pre-envelope) blob - parse directly.
            // (A keyed codec never returns null for a present non-enveloped blob: it throws.)
            payload = raw;
        }
        if (payload == null || payload.length < 8 + 8 + 4) {
            return null; // absent / first boot / torn-short
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(payload);
            long index = buf.getLong();
            long term = buf.getLong();
            int dataLen = buf.getInt();
            if (dataLen < 0 || buf.remaining() < dataLen + 4) {
                return null; // torn blob (only reachable for legacy raw bytes)
            }
            byte[] data = new byte[dataLen];
            buf.get(data);
            int cfgLen = buf.getInt();
            byte[] cfg = null;
            if (cfgLen >= 0) {
                if (buf.remaining() < cfgLen) {
                    return null; // torn blob (legacy raw only)
                }
                cfg = new byte[cfgLen];
                buf.get(cfg);
            }
            return new SnapshotState(data, index, term, cfg);
        } catch (RuntimeException e) {
            // Defensive: a malformed LEGACY (keyless, pre-envelope) blob must not
            // crash recovery; the WAL is authoritative. Treat as absent. Note this
            // does NOT mask a tamper: an integrity-verified envelope's payload is
            // well-formed by construction, and an IntegrityException from unwrap
            // already propagated above before reaching this parse.
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
            // No entries left after compaction - just delete the WAL.
            // No temp-rename needed: losing an empty WAL on crash is safe.
            storage.truncateLog(WAL_NAME);
            storage.truncateLog(WAL_TMP_NAME);
            return;
        }

        // 1. Remove any stale temp file from a previous incomplete rewrite
        storage.truncateLog(WAL_TMP_NAME);

        // 2. Write all current entries to the temp WAL. Each record is re-serialized with the
        // SAME prevHash it was originally written with (kept in chainPrevHashes, parallel to
        // entries), so the rewrite reproduces the record's exact bytes -> identical record hashes
        // -> the chain still verifies on the next recovery. Keyless has no prevHash.
        for (int k = 0; k < entries.size(); k++) {
            byte[] prevHash = chained ? chainPrevHashes.get(k) : null;
            storage.appendToLog(WAL_TMP_NAME, integrity.wrap(WALE_MAGIC, gid, serializeInner(entries.get(k), prevHash)));
        }

        // 3. Atomically replace the old WAL with the temp WAL.
        // Files.move with ATOMIC_MOVE | REPLACE_EXISTING handles this in one step.
        // Do NOT delete the old WAL first - that creates a crash window where
        // both files are gone (the old deleted, the rename not yet complete).
        storage.renameLog(WAL_TMP_NAME, WAL_NAME);
    }
}

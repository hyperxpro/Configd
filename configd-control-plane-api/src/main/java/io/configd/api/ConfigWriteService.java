package io.configd.api;

import io.configd.common.ConfigScope;
import io.configd.common.NodeId;

import java.util.Objects;

/**
 * Handles config write requests. Routes writes to the appropriate Raft group
 * based on key scope (GLOBAL/REGIONAL/LOCAL).
 * <p>
 * The write path:
 * <ol>
 *   <li>Validate the request (key, value, ACL)</li>
 *   <li>Determine the Raft group by key scope</li>
 *   <li>Encode the command and propose to the Raft leader</li>
 *   <li><b>Block until the proposal commits (applies) or the write deadline
 *       expires</b>, then return the typed outcome</li>
 * </ol>
 * <p>
 * RR-004 / ADR-0033: acknowledgement is <b>commit-confirmed</b>. A write returns
 * {@link WriteResult.Committed} only after the entry is quorum-committed AND
 * applied — carrying the applied-mutation sequence {@code seq} (the client's read
 * cursor; contract §6 read-your-writes). The failure taxonomy is explicit:
 * {@link WriteResult.NotLeader} (pre-append, definite), {@link WriteResult.Lost}
 * (post-append, definite — leadership lost before commit, safe to retry),
 * {@link WriteResult.Indeterminate} (deadline expired with the outcome unknown —
 * the write MAY still commit later; safe to retry or re-read on an idempotent
 * last-writer-wins payload).
 * <p>
 * Thread safety: methods are safe for concurrent access from API handler
 * threads. The underlying Raft node is single-threaded by design; safety relies
 * on the supplied {@link RaftProposer} marshalling the proposal AND the
 * commit-outcome registration onto the single Raft tick thread (see
 * {@code ConfigdServer.raftProposer}; R-01).
 */
public final class ConfigWriteService {

    /**
     * Result of a write operation. RR-004 / ADR-0033 taxonomy.
     */
    public sealed interface WriteResult {
        /**
         * The write was quorum-committed and applied. {@code seq} is the
         * applied-mutation sequence assigned to this write — the client's read
         * cursor for read-your-writes (contract §6).
         */
        record Committed(long seq) implements WriteResult {}
        /** This node is not the leader for the target group (pre-append, definite). */
        record NotLeader(NodeId leaderId) implements WriteResult {}
        /**
         * The entry was appended but leadership was lost before commit, and the
         * slot is now permanently occupied by a different proposal — the write
         * definitely did not commit. Safe to retry. {@code leaderHint} may be null.
         */
        record Lost(NodeId leaderHint) implements WriteResult {}
        /**
         * The write deadline expired with the outcome unknown (quorum slow,
         * leadership in flux). The write MAY still commit later. Distinguishable
         * from both success and definite failure; safe to retry or re-read.
         */
        record Indeterminate() implements WriteResult {}
        /** Validation failed (permanent). */
        record ValidationFailed(String reason) implements WriteResult {}
        /** The system is overloaded — the client should retry later. */
        record Overloaded() implements WriteResult {}
    }

    /**
     * The terminal commit outcome of a proposed command, as surfaced by the
     * {@link RaftProposer}. RR-004 / ADR-0033: the proposer performs propose +
     * commit-outcome registration on the tick thread and blocks the calling
     * (HTTP write) thread until the outcome is known or the write deadline
     * expires; it then returns one of these. The {@link ConfigWriteService} maps
     * these to {@link WriteResult}, attaching the leader hint where appropriate.
     */
    public sealed interface ProposeCommitResult {
        /** Quorum-committed and applied; carries the applied-mutation seq. */
        record Committed(long seq) implements ProposeCommitResult {}
        /** Rejected pre-append: this node is not the leader. */
        record NotLeader() implements ProposeCommitResult {}
        /** Appended then definitely lost (different-term entry committed at the slot). */
        record Lost() implements ProposeCommitResult {}
        /** Deadline expired with the outcome unknown. */
        record Indeterminate() implements ProposeCommitResult {}
        /** Rejected pre-append: backpressure (too many uncommitted entries). */
        record Overloaded() implements ProposeCommitResult {}
    }

    /**
     * Abstraction for proposing commands to the correct Raft group and awaiting
     * the commit outcome.
     */
    @FunctionalInterface
    public interface RaftProposer {
        /**
         * Proposes a command to the Raft group for the given scope and blocks the
         * calling thread until the commit outcome is known or the write deadline
         * expires.
         *
         * @param scope   determines which Raft group handles this write
         * @param command the encoded command bytes
         * @return the terminal commit outcome
         */
        ProposeCommitResult propose(ConfigScope scope, byte[] command);
    }

    /**
     * Validates a write before proposing.
     */
    @FunctionalInterface
    public interface WriteValidator {
        /**
         * Validates a key-value pair.
         *
         * @param key   the config key
         * @param value the config value (null for deletes)
         * @return null if valid, or an error message
         */
        String validate(String key, byte[] value);
    }

    /**
     * Supplies the current leader's NodeId (may return null if unknown).
     */
    @FunctionalInterface
    public interface LeaderHintSupplier {
        NodeId currentLeader();
    }

    private final RaftProposer proposer;
    private final WriteValidator validator;
    private final RateLimiter rateLimiter;
    private final LeaderHintSupplier leaderHintSupplier;

    /**
     * Creates a write service.
     *
     * @param proposer           routes proposals to the correct Raft group and awaits commit
     * @param validator          validates writes (may be null for no validation)
     * @param rateLimiter        rate limiter (may be null for no rate limiting)
     * @param leaderHintSupplier supplies the current leader hint (may be null)
     */
    public ConfigWriteService(RaftProposer proposer, WriteValidator validator,
                               RateLimiter rateLimiter,
                               LeaderHintSupplier leaderHintSupplier) {
        this.proposer = Objects.requireNonNull(proposer, "proposer must not be null");
        this.validator = validator;
        this.rateLimiter = rateLimiter;
        this.leaderHintSupplier = leaderHintSupplier;
    }

    /**
     * Creates a write service without leader hint support (backward compatibility).
     */
    public ConfigWriteService(RaftProposer proposer, WriteValidator validator,
                               RateLimiter rateLimiter) {
        this(proposer, validator, rateLimiter, null);
    }

    /**
     * Writes a config key-value pair. Blocks until the write commits (applies) or
     * the write deadline expires.
     *
     * @param key   the config key (non-null, non-blank)
     * @param value the config value (non-null)
     * @param scope the replication scope
     * @return the write result
     */
    public WriteResult put(String key, byte[] value, ConfigScope scope) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        // FIND-0025: Enforce size limits (use UTF-8 byte length for accurate wire-format check)
        if (key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024) {
            return new WriteResult.ValidationFailed("key length exceeds maximum of 1024 bytes");
        }
        if (value.length > 1_048_576) {  // 1 MB
            return new WriteResult.ValidationFailed("value size exceeds maximum of 1048576 bytes (1 MB)");
        }

        if (key.isBlank()) {
            return new WriteResult.ValidationFailed("key must not be blank");
        }

        if (rateLimiter != null && !rateLimiter.tryAcquire()) {
            return new WriteResult.Overloaded();
        }

        if (validator != null) {
            String error = validator.validate(key, value);
            if (error != null) {
                return new WriteResult.ValidationFailed(error);
            }
        }

        byte[] command = encodeCommand((byte) 0x01, key, value);
        return mapOutcome(proposer.propose(scope, command));
    }

    /**
     * Deletes a config key. Blocks until the delete commits (applies) or the
     * write deadline expires.
     *
     * @param key   the config key (non-null, non-blank)
     * @param scope the replication scope
     * @return the write result
     */
    public WriteResult delete(String key, ConfigScope scope) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        if (key.isBlank()) {
            return new WriteResult.ValidationFailed("key must not be blank");
        }

        if (rateLimiter != null && !rateLimiter.tryAcquire()) {
            return new WriteResult.Overloaded();
        }

        byte[] command = encodeCommand((byte) 0x02, key, null);
        return mapOutcome(proposer.propose(scope, command));
    }

    /**
     * Maps a terminal {@link ProposeCommitResult} to a {@link WriteResult},
     * attaching the leader hint to the redirect/loss cases.
     */
    private WriteResult mapOutcome(ProposeCommitResult outcome) {
        return switch (outcome) {
            case ProposeCommitResult.Committed c -> new WriteResult.Committed(c.seq());
            case ProposeCommitResult.NotLeader ignored -> new WriteResult.NotLeader(leaderHint());
            case ProposeCommitResult.Lost ignored -> new WriteResult.Lost(leaderHint());
            case ProposeCommitResult.Indeterminate ignored -> new WriteResult.Indeterminate();
            case ProposeCommitResult.Overloaded ignored -> new WriteResult.Overloaded();
        };
    }

    private NodeId leaderHint() {
        if (leaderHintSupplier != null) {
            return leaderHintSupplier.currentLeader();
        }
        return null;
    }

    /**
     * Simple command encoding: [type][2-byte key length][key bytes][4-byte value length][value bytes].
     */
    private static byte[] encodeCommand(byte type, String key, byte[] value) {
        byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int valueLen = (value != null) ? value.length : 0;
        int totalLen = 1 + 2 + keyBytes.length + (type == 0x01 ? 4 + valueLen : 0);
        byte[] buf = new byte[totalLen];
        int pos = 0;
        buf[pos++] = type;
        buf[pos++] = (byte) (keyBytes.length >> 8);
        buf[pos++] = (byte) keyBytes.length;
        System.arraycopy(keyBytes, 0, buf, pos, keyBytes.length);
        pos += keyBytes.length;
        if (type == 0x01 && value != null) {
            buf[pos++] = (byte) (valueLen >> 24);
            buf[pos++] = (byte) (valueLen >> 16);
            buf[pos++] = (byte) (valueLen >> 8);
            buf[pos++] = (byte) valueLen;
            System.arraycopy(value, 0, buf, pos, valueLen);
        }
        return buf;
    }
}

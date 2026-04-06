package io.configd.api;

import io.configd.common.ConfigScope;
import io.configd.common.NodeId;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles config write requests. Routes writes to the appropriate Raft group
 * based on key scope (GLOBAL/REGIONAL/LOCAL).
 * <p>
 * The write path:
 * <ol>
 *   <li>Validate the request (key, value, ACL)</li>
 *   <li>Determine the Raft group by key scope</li>
 *   <li>Encode the command and propose to the Raft leader</li>
 *   <li>Return success or failure (not leader, validation error, etc.)</li>
 * </ol>
 * <p>
 * Thread safety: methods are safe for concurrent access from API handler
 * threads. The underlying Raft propose is thread-safe (message passing).
 */
public final class ConfigWriteService {

    /**
     * Result of a write operation.
     */
    public sealed interface WriteResult {
        /** Write was accepted by the Raft leader and will be replicated. */
        record Accepted(long proposalId) implements WriteResult {}
        /** This node is not the leader for the target group. */
        record NotLeader(NodeId leaderId) implements WriteResult {}
        /** Validation failed. */
        record ValidationFailed(String reason) implements WriteResult {}
        /** The system is overloaded — the client should retry later. */
        record Overloaded() implements WriteResult {}
    }

    /**
     * Abstraction for proposing commands to the correct Raft group.
     */
    @FunctionalInterface
    public interface RaftProposer {
        /**
         * Proposes a command to the Raft group for the given scope.
         *
         * @param scope   determines which Raft group handles this write
         * @param command the encoded command bytes
         * @return true if the proposal was accepted (this node is the leader)
         */
        boolean propose(ConfigScope scope, byte[] command);
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
    private final AtomicLong nextProposalId;
    private final LeaderHintSupplier leaderHintSupplier;

    /**
     * Creates a write service.
     *
     * @param proposer           routes proposals to the correct Raft group
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
        this.nextProposalId = new AtomicLong(1);
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
     * Writes a config key-value pair.
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
        boolean accepted = proposer.propose(scope, command);
        if (!accepted) {
            return new WriteResult.NotLeader(leaderHint());
        }
        return new WriteResult.Accepted(nextProposalId.getAndIncrement());
    }

    /**
     * Deletes a config key.
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
        boolean accepted = proposer.propose(scope, command);
        if (!accepted) {
            return new WriteResult.NotLeader(leaderHint());
        }
        return new WriteResult.Accepted(nextProposalId.getAndIncrement());
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

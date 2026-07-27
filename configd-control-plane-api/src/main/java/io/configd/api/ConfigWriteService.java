package io.configd.api;

import io.configd.common.ConfigScope;
import io.configd.common.NodeId;

import java.util.List;
import java.util.Objects;

public final class ConfigWriteService {

    public sealed interface WriteResult {
        record Committed(long seq) implements WriteResult {}
        record NotLeader(NodeId leaderId) implements WriteResult {}
        record Lost(NodeId leaderHint) implements WriteResult {}
        record Indeterminate() implements WriteResult {}
        record ValidationFailed(String reason) implements WriteResult {}
        record Overloaded() implements WriteResult {}
    }

    public sealed interface ProposeCommitResult {
        record Committed(long seq) implements ProposeCommitResult {}
        record NotLeader() implements ProposeCommitResult {}
        record Lost() implements ProposeCommitResult {}
        record Indeterminate() implements ProposeCommitResult {}
        record Overloaded() implements ProposeCommitResult {}
        record CrossShardRejected(String reason) implements ProposeCommitResult {}
    }

    @FunctionalInterface
    public interface RaftProposer {
        ProposeCommitResult propose(ConfigScope scope, List<String> keys, byte[] command);
    }

    @FunctionalInterface
    public interface WriteValidator {
        String validate(String key, byte[] value);
    }

    @FunctionalInterface
    public interface LeaderHintSupplier {
        NodeId currentLeader(ConfigScope scope, String key);
    }

    private final RaftProposer proposer;
    private final WriteValidator validator;
    private final RateLimiter rateLimiter;
    private final LeaderHintSupplier leaderHintSupplier;

    private final java.util.function.Supplier<RateLimiter> perPrincipalLimiterFactory;
    private final java.util.concurrent.ConcurrentHashMap<String, RateLimiter> principalLimiters =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_PRINCIPAL_LIMITERS = 10_000;

    /**
     * @param proposer           routes proposals to the correct Raft group and awaits commit
     * @param validator          validates writes (may be null for no validation)
     * @param rateLimiter        rate limiter (may be null for no rate limiting)
     * @param leaderHintSupplier supplies the current leader hint (may be null)
     */
    public ConfigWriteService(RaftProposer proposer, WriteValidator validator,
                               RateLimiter rateLimiter,
                               LeaderHintSupplier leaderHintSupplier) {
        this(proposer, validator, rateLimiter, leaderHintSupplier, null);
    }

    public ConfigWriteService(RaftProposer proposer, WriteValidator validator,
                               RateLimiter rateLimiter) {
        this(proposer, validator, rateLimiter, null, null);
    }

    public ConfigWriteService(RaftProposer proposer, WriteValidator validator,
                               RateLimiter rateLimiter, LeaderHintSupplier leaderHintSupplier,
                               java.util.function.Supplier<RateLimiter> perPrincipalLimiterFactory) {
        this.proposer = Objects.requireNonNull(proposer, "proposer must not be null");
        this.validator = validator;
        this.rateLimiter = rateLimiter;
        this.leaderHintSupplier = leaderHintSupplier;
        this.perPrincipalLimiterFactory = perPrincipalLimiterFactory;
    }

    public WriteResult put(String key, byte[] value, ConfigScope scope) {
        return put(key, value, scope, null);
    }

    public WriteResult put(String key, byte[] value, ConfigScope scope, String principal) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        // Enforce size limits (use UTF-8 byte length for accurate wire-format check)
        if (key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024) {
            return new WriteResult.ValidationFailed("key length exceeds maximum of 1024 bytes");
        }
        if (value.length > 1_048_576) {  // 1 MB
            return new WriteResult.ValidationFailed("value size exceeds maximum of 1048576 bytes (1 MB)");
        }

        if (key.isBlank()) {
            return new WriteResult.ValidationFailed("key must not be blank");
        }

        if (!acquire(principal)) {
            return new WriteResult.Overloaded();
        }

        if (validator != null) {
            String error = validator.validate(key, value);
            if (error != null) {
                return new WriteResult.ValidationFailed(error);
            }
        }

        byte[] command = encodeCommand((byte) 0x01, key, value);
        return mapOutcome(proposer.propose(scope, List.of(key), command), scope, key);
    }

    public WriteResult delete(String key, ConfigScope scope) {
        return delete(key, scope, null);
    }

    public WriteResult delete(String key, ConfigScope scope, String principal) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        if (key.isBlank()) {
            return new WriteResult.ValidationFailed("key must not be blank");
        }

        if (!acquire(principal)) {
            return new WriteResult.Overloaded();
        }

        byte[] command = encodeCommand((byte) 0x02, key, null);
        return mapOutcome(proposer.propose(scope, List.of(key), command), scope, key);
    }

    private boolean acquire(String principal) {
        if (perPrincipalLimiterFactory == null || principal == null || principal.isBlank()) {
            return rateLimiter == null || rateLimiter.tryAcquire();
        }
        RateLimiter limiter = principalLimiters.get(principal);
        if (limiter == null) {
            if (principalLimiters.size() >= MAX_PRINCIPAL_LIMITERS) {
                return rateLimiter == null || rateLimiter.tryAcquire();
            }
            limiter = principalLimiters.computeIfAbsent(principal, p -> perPrincipalLimiterFactory.get());
        }
        return limiter.tryAcquire();
    }

    private WriteResult mapOutcome(ProposeCommitResult outcome, ConfigScope scope, String key) {
        return switch (outcome) {
            case ProposeCommitResult.Committed c -> new WriteResult.Committed(c.seq());
            case ProposeCommitResult.NotLeader ignored -> new WriteResult.NotLeader(leaderHint(scope, key));
            case ProposeCommitResult.Lost ignored -> new WriteResult.Lost(leaderHint(scope, key));
            case ProposeCommitResult.Indeterminate ignored -> new WriteResult.Indeterminate();
            case ProposeCommitResult.Overloaded ignored -> new WriteResult.Overloaded();
            case ProposeCommitResult.CrossShardRejected cr -> new WriteResult.ValidationFailed(cr.reason());
        };
    }

    private NodeId leaderHint(ConfigScope scope, String key) {
        if (leaderHintSupplier != null) {
            return leaderHintSupplier.currentLeader(scope, key);
        }
        return null;
    }

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

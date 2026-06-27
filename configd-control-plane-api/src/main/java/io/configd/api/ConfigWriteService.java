package io.configd.api;

import io.configd.common.ConfigScope;
import io.configd.common.NodeId;

import java.util.List;
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
        /**
         * Multi-Raft Phase 1 (Seam D, DISCLAIM): rejected pre-append because the write's keys span more
         * than one shard. Configd does not offer cross-shard atomicity (ADR adr-multiraft-cross-shard,
         * D-C); the cross-shard write guard catches it before any Raft work. {@code reason} names the
         * offending keys. Mapped to {@link WriteResult.ValidationFailed} (permanent — retrying the same
         * spanning write cannot succeed).
         */
        record CrossShardRejected(String reason) implements ProposeCommitResult {}
    }

    /**
     * Abstraction for proposing commands to the correct Raft group and awaiting
     * the commit outcome.
     */
    @FunctionalInterface
    public interface RaftProposer {
        /**
         * Proposes a command to the Raft group that owns the write, blocking the calling thread until the
         * commit outcome is known or the write deadline expires.
         *
         * <p>Multi-Raft Phase 1 (Seam D): the {@code keys} of the write select the shard via the
         * implementation's {@code ShardMap} ({@code shardFor(scope, key)}). For a single-key
         * {@code put}/{@code delete} this is one key ⇒ one shard. For a multi-key write the implementation
         * runs the cross-shard guard: all keys must co-locate on ONE shard, else
         * {@link ProposeCommitResult.CrossShardRejected} (DISCLAIM). At {@code N=1} every key resolves to
         * group 0 (byte-identical to the prior single-group path).
         *
         * @param scope   the write's configuration scope (folded into the shard hash)
         * @param keys    the key(s) of the write (non-empty); selects the owning shard + drives the guard
         * @param command the encoded command bytes
         * @return the terminal commit outcome
         */
        ProposeCommitResult propose(ConfigScope scope, List<String> keys, byte[] command);
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
        /**
         * The current leader of the Raft group that owns {@code (scope, key)} — the redirect target for a
         * {@code NotLeader}/{@code Lost} write. Multi-Raft Phase 1 (Seam D): keyed so the hint points at
         * the OWNING shard's leader (a keyless hint would loop forever at N&gt;1, redirecting every shard's
         * write to one group's leader). At {@code N=1} every key resolves to group 0. May return
         * {@code null} if the leader is unknown.
         *
         * @param scope the write's configuration scope
         * @param key   the write's key (selects the shard)
         */
        NodeId currentLeader(ConfigScope scope, String key);
    }

    private final RaftProposer proposer;
    private final WriteValidator validator;
    private final RateLimiter rateLimiter;
    private final LeaderHintSupplier leaderHintSupplier;

    /**
     * S7.5 per-principal rate limiting (Med residual): a factory for a fresh per-principal token
     * bucket (null = the legacy single global limiter). Keyed by authenticated principal so one
     * noisy/hostile tenant cannot consume the whole write budget and starve others. The gate stays
     * BEFORE the Raft proposal (sheds at the edge, never enqueues onto the tick thread; RR-002-safe —
     * a lock-free CAS on the HTTP request vthread).
     */
    private final java.util.function.Supplier<RateLimiter> perPrincipalLimiterFactory;
    private final java.util.concurrent.ConcurrentHashMap<String, RateLimiter> principalLimiters =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Memory bound on distinct per-principal buckets; beyond it, principals share the global limiter. */
    private static final int MAX_PRINCIPAL_LIMITERS = 10_000;

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
        this(proposer, validator, rateLimiter, leaderHintSupplier, null);
    }

    /**
     * Creates a write service without leader hint support (backward compatibility).
     */
    public ConfigWriteService(RaftProposer proposer, WriteValidator validator,
                               RateLimiter rateLimiter) {
        this(proposer, validator, rateLimiter, null, null);
    }

    /**
     * Creates a write service with PER-PRINCIPAL rate limiting (S7.5). {@code rateLimiter} is the
     * global ceiling / fallback (and the limiter used by the no-principal {@code put}/{@code delete}
     * overloads and once the per-principal map hits {@link #MAX_PRINCIPAL_LIMITERS});
     * {@code perPrincipalLimiterFactory} mints a fresh bucket per authenticated principal so one
     * tenant's flood cannot starve the others.
     */
    public ConfigWriteService(RaftProposer proposer, WriteValidator validator,
                               RateLimiter rateLimiter, LeaderHintSupplier leaderHintSupplier,
                               java.util.function.Supplier<RateLimiter> perPrincipalLimiterFactory) {
        this.proposer = Objects.requireNonNull(proposer, "proposer must not be null");
        this.validator = validator;
        this.rateLimiter = rateLimiter;
        this.leaderHintSupplier = leaderHintSupplier;
        this.perPrincipalLimiterFactory = perPrincipalLimiterFactory;
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
        return put(key, value, scope, null);
    }

    /**
     * Per-principal-rate-limited put (S7.5). {@code principal} is the authenticated identity; its
     * OWN token bucket is charged, so one tenant's flood cannot starve the others. A {@code null}/
     * blank principal (or a service constructed without a per-principal factory) falls back to the
     * global limiter — backward compatible.
     *
     * @param principal the authenticated principal whose rate budget to charge (may be null)
     */
    public WriteResult put(String key, byte[] value, ConfigScope scope, String principal) {
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
        // Multi-Raft Phase 1 (Seam D): a single-key write — the proposer routes it to shardFor(scope,key)
        // and the cross-shard guard is trivially satisfied (one key ⇒ one shard).
        return mapOutcome(proposer.propose(scope, List.of(key), command), scope, key);
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
        return delete(key, scope, null);
    }

    /** Per-principal-rate-limited delete (S7.5); see {@link #put(String, byte[], ConfigScope, String)}. */
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

    /**
     * Charges one permit against the caller's rate budget. With a per-principal factory configured and
     * a non-blank principal, charges that principal's OWN bucket (created lazily, memory-bounded by
     * {@link #MAX_PRINCIPAL_LIMITERS} — beyond which principals share the global limiter). Otherwise
     * charges the global limiter (legacy behavior). Lock-free CAS on the HTTP request thread, BEFORE
     * the Raft proposal (RR-002-safe).
     *
     * @return true if a permit was granted (proceed), false if the bucket is empty (shed Overloaded)
     */
    private boolean acquire(String principal) {
        if (perPrincipalLimiterFactory == null || principal == null || principal.isBlank()) {
            return rateLimiter == null || rateLimiter.tryAcquire();
        }
        RateLimiter limiter = principalLimiters.get(principal);
        if (limiter == null) {
            // Memory bound: an attacker spraying distinct principals cannot grow the map without
            // bound — beyond the cap, new principals fall back to the shared global limiter.
            if (principalLimiters.size() >= MAX_PRINCIPAL_LIMITERS) {
                return rateLimiter == null || rateLimiter.tryAcquire();
            }
            limiter = principalLimiters.computeIfAbsent(principal, p -> perPrincipalLimiterFactory.get());
        }
        return limiter.tryAcquire();
    }

    /**
     * Maps a terminal {@link ProposeCommitResult} to a {@link WriteResult}, attaching the
     * SHARD-AWARE leader hint (resolved for {@code (scope, key)}) to the redirect/loss cases
     * (Multi-Raft Phase 1, Seam D) and mapping a cross-shard rejection to a permanent
     * {@link WriteResult.ValidationFailed}.
     */
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

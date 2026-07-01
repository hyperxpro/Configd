package io.configd.api;

import io.configd.common.ConfigScope;
import io.configd.store.ReadResult;

import java.util.Map;
import java.util.Objects;

/**
 * Handles config read requests from the control plane API.
 * <p>
 * Supports two read modes:
 * <ul>
 *   <li><b>Linearizable</b> - confirms leadership via ReadIndex before serving.
 *       Guarantees the read reflects all committed writes up to the moment
 *       the request was received.</li>
 *   <li><b>Stale</b> - reads directly from the local store without leadership
 *       confirmation. Faster but may serve slightly stale data.</li>
 * </ul>
 * <p>
 * Thread safety: reads delegate to the underlying store which uses a
 * volatile snapshot pointer (safe for concurrent reads).
 */
public final class ConfigReadService {

    /**
     * Provides access to the config store for reads.
     */
    public interface ConfigReader {
        ReadResult get(String key);
        ReadResult get(String key, long minVersion);
        Map<String, ReadResult> getPrefix(String prefix);
        long currentVersion();

        /**
         * Scope-aware point read. A sharded reader folds {@code scope} into
         * {@code shardFor(scope, key)} so the read resolves the SAME shard the write of
         * {@code (scope, key)} used (read-your-writes). The default ignores scope and delegates to
         * {@link #get(String)} - correct for a single-store reader and byte-identical at {@code N=1}
         * (every scope -> group 0). Only a sharded reader overrides this.
         */
        default ReadResult get(ConfigScope scope, String key) {
            return get(key);
        }

        /** Scope-aware {@link #get(String, long)}; see {@link #get(ConfigScope, String)}. */
        default ReadResult get(ConfigScope scope, String key, long minVersion) {
            return get(key, minVersion);
        }
    }

    /**
     * Confirms that this node is still the leader (ReadIndex protocol).
     */
    @FunctionalInterface
    public interface LeadershipConfirmer {
        /**
         * Confirms leadership of the Raft group that owns {@code (scope, key)} by verifying quorum
         * contact (ReadIndex). Keyed and scoped so the confirmation runs on the shard that owns
         * {@code (scope, key)} = {@code shardFor(scope, key)} - the SAME shard the write of
         * {@code (scope, key)} used (a scopeless confirm would verify the wrong shard's leadership at
         * N&gt;1). At {@code N=1} every {@code (scope, key)} resolves to group 0.
         *
         * @param scope the read's configuration scope (folded into the shard hash)
         * @param key   the key being read (selects the shard)
         * @return true if this node is confirmed as the current leader of that shard
         */
        boolean confirmLeadership(ConfigScope scope, String key);

        /**
         * Confirms leadership in the {@code GLOBAL} scope for the legacy key-only path.
         * Delegates to {@link #confirmLeadership(ConfigScope, String)}.
         */
        default boolean confirmLeadership(String key) {
            return confirmLeadership(ConfigScope.GLOBAL, key);
        }
    }

    private final ConfigReader reader;
    private final LeadershipConfirmer leadershipConfirmer;

    /**
     * Creates a read service.
     *
     * @param reader               the config store reader
     * @param leadershipConfirmer  leadership confirmation (may be null for stale-only reads)
     */
    public ConfigReadService(ConfigReader reader, LeadershipConfirmer leadershipConfirmer) {
        this.reader = Objects.requireNonNull(reader, "reader must not be null");
        this.leadershipConfirmer = leadershipConfirmer;
    }

    /**
     * Performs a linearizable read in the {@code GLOBAL} scope. Equivalent to
     * {@link #linearizableRead(ConfigScope, String)} with {@link ConfigScope#GLOBAL}.
     *
     * @param key the config key
     * @return the read result, or null if leadership confirmation fails
     *         (caller should return 503 / Not Leader, not 404)
     */
    public ReadResult linearizableRead(String key) {
        return linearizableRead(ConfigScope.GLOBAL, key);
    }

    /**
     * Performs a linearizable read of {@code (scope, key)}. Confirms leadership of the shard that owns
     * {@code (scope, key)} before serving, so the read routes to the SAME shard the write of
     * {@code (scope, key)} used (read-your-writes). At {@code N=1} every scope resolves to group 0
     * (byte-identical to the GLOBAL-pinned path).
     *
     * @param scope the read's configuration scope (folded into the shard hash)
     * @param key   the config key
     * @return the read result, or null if leadership confirmation fails
     *         (caller should return 503 / Not Leader, not 404)
     */
    public ReadResult linearizableRead(ConfigScope scope, String key) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(key, "key must not be null");

        // Confirm leadership on the shard that OWNS (scope, key).
        if (leadershipConfirmer != null && !leadershipConfirmer.confirmLeadership(scope, key)) {
            return null; // not leader - caller must distinguish from "key not found"
        }
        return reader.get(scope, key);
    }

    /**
     * Performs a stale read in the {@code GLOBAL} scope. Equivalent to
     * {@link #staleRead(ConfigScope, String)} with {@link ConfigScope#GLOBAL}.
     *
     * @param key the config key
     * @return the read result
     */
    public ReadResult staleRead(String key) {
        return staleRead(ConfigScope.GLOBAL, key);
    }

    /**
     * Performs a stale read of {@code (scope, key)} directly from the local store, routed to the shard
     * that owns {@code (scope, key)} (the same shard the write used). At {@code N=1} every scope
     * resolves to group 0 (byte-identical).
     *
     * @param scope the read's configuration scope (folded into the shard hash)
     * @param key   the config key
     * @return the read result
     */
    public ReadResult staleRead(ConfigScope scope, String key) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(key, "key must not be null");
        return reader.get(scope, key);
    }

    /**
     * Performs a stale read with a minimum version requirement.
     *
     * @param key        the config key
     * @param minVersion the minimum acceptable version
     * @return the read result (NOT_FOUND if store is behind minVersion)
     */
    public ReadResult staleRead(String key, long minVersion) {
        Objects.requireNonNull(key, "key must not be null");
        return reader.get(key, minVersion);
    }

    /**
     * Reads all keys matching a prefix.
     *
     * @param prefix the key prefix
     * @return map of matching key-value pairs
     */
    public Map<String, ReadResult> prefixRead(String prefix) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        return reader.getPrefix(prefix);
    }

    /**
     * Returns the current store version.
     */
    public long currentVersion() {
        return reader.currentVersion();
    }
}

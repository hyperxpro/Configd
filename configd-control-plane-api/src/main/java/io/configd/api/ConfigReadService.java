package io.configd.api;

import io.configd.store.ReadResult;

import java.util.Map;
import java.util.Objects;

/**
 * Handles config read requests from the control plane API.
 * <p>
 * Supports two read modes:
 * <ul>
 *   <li><b>Linearizable</b> — confirms leadership via ReadIndex before serving.
 *       Guarantees the read reflects all committed writes up to the moment
 *       the request was received.</li>
 *   <li><b>Stale</b> — reads directly from the local store without leadership
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
    }

    /**
     * Confirms that this node is still the leader (ReadIndex protocol).
     */
    @FunctionalInterface
    public interface LeadershipConfirmer {
        /**
         * Confirms leadership by verifying quorum contact.
         *
         * @return true if this node is confirmed as the current leader
         */
        boolean confirmLeadership();
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
     * Performs a linearizable read. Confirms leadership before serving.
     *
     * @param key the config key
     * @return the read result, or null if leadership confirmation fails
     *         (caller should return 503 / Not Leader, not 404)
     */
    public ReadResult linearizableRead(String key) {
        Objects.requireNonNull(key, "key must not be null");

        if (leadershipConfirmer != null && !leadershipConfirmer.confirmLeadership()) {
            return null; // not leader — caller must distinguish from "key not found"
        }
        return reader.get(key);
    }

    /**
     * Performs a stale read directly from the local store.
     *
     * @param key the config key
     * @return the read result
     */
    public ReadResult staleRead(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return reader.get(key);
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

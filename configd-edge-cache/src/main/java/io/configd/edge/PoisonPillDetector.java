package io.configd.edge;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects and quarantines config entries that cause repeated application
 * failures ("poison pills"). A config entry becomes poisoned after
 * {@code maxRetries} consecutive application failures for the same key.
 * <p>
 * Once poisoned, the key is quarantined: subsequent deltas for that key
 * are silently skipped (with a metric increment), preventing one bad
 * config from cascading failures across the edge node. Quarantined keys
 * can be manually released via {@link #release(String)}.
 * <p>
 * <b>Thread safety:</b> All methods are thread-safe via ConcurrentHashMap.
 * <p>
 * <b>Observability:</b> Poisoned keys are exposed via {@link #poisonedKeys()}
 * for alerting. In production, pair with an InvariantMonitor check.
 */
public final class PoisonPillDetector {

    /** Default number of consecutive failures before quarantine. */
    private static final int DEFAULT_MAX_RETRIES = 3;

    private final int maxRetries;

    /** Tracks consecutive failure counts per key. */
    private final ConcurrentHashMap<String, Integer> failureCounts = new ConcurrentHashMap<>();

    /** Keys that have been quarantined (exceeded maxRetries). */
    private final Set<String> quarantined = ConcurrentHashMap.newKeySet();

    /** Callback for poison events (alerting, metrics). */
    @FunctionalInterface
    public interface PoisonListener {
        void onPoisoned(String key, int failureCount, String reason);
        PoisonListener NOOP = (key, count, reason) -> {};
    }

    private final PoisonListener listener;

    public PoisonPillDetector(int maxRetries, PoisonListener listener) {
        if (maxRetries < 1) {
            throw new IllegalArgumentException("maxRetries must be >= 1: " + maxRetries);
        }
        this.maxRetries = maxRetries;
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public PoisonPillDetector(int maxRetries) {
        this(maxRetries, PoisonListener.NOOP);
    }

    public PoisonPillDetector() {
        this(DEFAULT_MAX_RETRIES, PoisonListener.NOOP);
    }

    /**
     * Checks whether a key is currently quarantined.
     *
     * @param key the config key
     * @return true if the key is poisoned and should be skipped
     */
    public boolean isPoisoned(String key) {
        return quarantined.contains(key);
    }

    /**
     * Records a successful application for a key. Resets the failure
     * counter, indicating the key is healthy.
     *
     * @param key the config key
     */
    public void recordSuccess(String key) {
        failureCounts.remove(key);
    }

    /**
     * Records a failed application attempt for a key. If the consecutive
     * failure count reaches {@code maxRetries}, the key is quarantined.
     *
     * @param key    the config key that failed
     * @param reason a human-readable reason for the failure
     * @return true if this failure caused the key to be quarantined
     */
    public boolean recordFailure(String key, String reason) {
        int count = failureCounts.merge(key, 1, Integer::sum);
        if (count >= maxRetries) {
            quarantined.add(key);
            listener.onPoisoned(key, count, reason);
            return true;
        }
        return false;
    }

    /**
     * Releases a quarantined key, allowing it to be processed again.
     * Call this after the root cause has been addressed (e.g., a
     * corrected config value has been pushed).
     *
     * @param key the config key to release
     * @return true if the key was quarantined and is now released
     */
    public boolean release(String key) {
        failureCounts.remove(key);
        return quarantined.remove(key);
    }

    /**
     * Returns an unmodifiable view of all currently quarantined keys.
     *
     * @return set of poisoned key names
     */
    public Set<String> poisonedKeys() {
        return Collections.unmodifiableSet(quarantined);
    }

    /**
     * Returns the number of currently quarantined keys.
     */
    public int poisonedCount() {
        return quarantined.size();
    }
}

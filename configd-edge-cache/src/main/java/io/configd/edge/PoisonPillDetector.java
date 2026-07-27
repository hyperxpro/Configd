package io.configd.edge;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects and quarantines entries after {@code maxRetries} consecutive failures.
 * Thread-safe via ConcurrentHashMap. Observability: {@link #poisonedKeys()} for alerts.
 */
public final class PoisonPillDetector {

    private static final int DEFAULT_MAX_RETRIES = 3;

    private final int maxRetries;
    private final ConcurrentHashMap<String, Integer> failureCounts = new ConcurrentHashMap<>();
    private final Set<String> quarantined = ConcurrentHashMap.newKeySet();

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

    public boolean isPoisoned(String key) {
        return quarantined.contains(key);
    }

    public void recordSuccess(String key) {
        failureCounts.remove(key);
    }

    public boolean recordFailure(String key, String reason) {
        int count = failureCounts.merge(key, 1, Integer::sum);
        if (count >= maxRetries) {
            quarantined.add(key);
            listener.onPoisoned(key, count, reason);
            return true;
        }
        return false;
    }

    public boolean release(String key) {
        failureCounts.remove(key);
        return quarantined.remove(key);
    }

    public Set<String> poisonedKeys() {
        return Collections.unmodifiableSet(quarantined);
    }

    public int poisonedCount() {
        return quarantined.size();
    }
}

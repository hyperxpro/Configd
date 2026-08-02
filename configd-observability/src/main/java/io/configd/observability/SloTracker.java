package io.configd.observability;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Tracks SLO (Service Level Objective) compliance by recording success and
 * failure events and computing SLI (Service Level Indicator) values over
 * sliding time windows.
 * <p>
 * Each SLO is defined with a target compliance ratio (e.g., 0.999 for 99.9%)
 * and a sliding window duration (e.g., 30 minutes). Events older than the
 * window are lazily evicted on the next compliance query.
 * <p>
 * Thread safety: all methods are safe for concurrent use. Event recording
 * uses {@link ConcurrentLinkedDeque} (lock-free append); compliance queries
 * iterate the deque with lazy eviction.
 * <p>
 * Time source: uses {@link System#nanoTime()} for monotonic elapsed time
 * measurement. This avoids issues with wall-clock adjustments (NTP, leap
 * seconds) and is consistent with the project's {@link io.configd.common.Clock}
 * design philosophy.
 *
 * @see SloStatus
 */
public final class SloTracker {

    private final ConcurrentHashMap<String, SloDefinition> definitions = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<SloEvent>> events = new ConcurrentHashMap<>();

    public void defineSlo(String name, double target, Duration window) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (target < 0.0 || target > 1.0) {
            throw new IllegalArgumentException("target must be in [0.0, 1.0]: " + target);
        }
        Objects.requireNonNull(window, "window must not be null");
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive: " + window);
        }

        definitions.put(name, new SloDefinition(target, window.toNanos()));
        events.put(name, new ConcurrentLinkedDeque<>());
    }

    public void recordSuccess(String sloName) {
        recordEvent(sloName, true);
    }

    public void recordFailure(String sloName) {
        recordEvent(sloName, false);
    }

    public double compliance(String sloName) {
        SloDefinition def = requireDefined(sloName);
        ConcurrentLinkedDeque<SloEvent> deque = events.get(sloName);

        long cutoff = System.nanoTime() - def.windowNanos();
        evict(deque, cutoff);

        long total = 0;
        long failures = 0;
        for (SloEvent event : deque) {
            if (event.timestampNanos() >= cutoff) {
                total++;
                if (!event.success()) {
                    failures++;
                }
            }
        }

        if (total == 0) {
            return 1.0;
        }
        return (double) (total - failures) / total;
    }

    public boolean isBreaching(String sloName) {
        SloDefinition def = requireDefined(sloName);
        return compliance(sloName) < def.target();
    }

    public Map<String, SloStatus> snapshot() {
        return definitions.keySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        name -> name,
                        this::statusFor));
    }

    public record SloStatus(
            String name,
            double target,
            double current,
            long totalEvents,
            long failedEvents,
            boolean breaching
    ) {
        public SloStatus {
            Objects.requireNonNull(name, "name must not be null");
        }
    }

    private void recordEvent(String sloName, boolean success) {
        requireDefined(sloName);
        ConcurrentLinkedDeque<SloEvent> deque = events.get(sloName);
        deque.addLast(new SloEvent(System.nanoTime(), success));
    }

    private SloDefinition requireDefined(String sloName) {
        Objects.requireNonNull(sloName, "sloName must not be null");
        SloDefinition def = definitions.get(sloName);
        if (def == null) {
            throw new IllegalArgumentException("SLO not defined: " + sloName);
        }
        return def;
    }

    /**
     * Lazily evicts events older than the cutoff from the front of the deque.
     * Since events are appended in monotonic time order, we can evict from
     * the head until we find an event within the window.
     */
    private void evict(ConcurrentLinkedDeque<SloEvent> deque, long cutoffNanos) {
        SloEvent head;
        while ((head = deque.peekFirst()) != null && head.timestampNanos() < cutoffNanos) {
            deque.pollFirst();
        }
    }

    private SloStatus statusFor(String sloName) {
        SloDefinition def = definitions.get(sloName);
        ConcurrentLinkedDeque<SloEvent> deque = events.get(sloName);

        long cutoff = System.nanoTime() - def.windowNanos();
        evict(deque, cutoff);

        long total = 0;
        long failures = 0;
        for (SloEvent event : deque) {
            if (event.timestampNanos() >= cutoff) {
                total++;
                if (!event.success()) {
                    failures++;
                }
            }
        }

        double current = (total == 0) ? 1.0 : (double) (total - failures) / total;
        boolean breaching = current < def.target();

        return new SloStatus(sloName, def.target(), current, total, failures, breaching);
    }

    private record SloDefinition(double target, long windowNanos) {}

    private record SloEvent(long timestampNanos, boolean success) {}
}

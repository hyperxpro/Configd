package io.configd.observability;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Runtime invariant checker bridging TLA+ specifications to Java assertions.
 * <p>
 * Every TLA+ invariant in the formal model should have a corresponding
 * Java assertion registered here. This ensures that safety properties
 * verified in the model are continuously checked at runtime.
 * <p>
 * <b>Modes:</b>
 * <ul>
 *   <li><b>Test mode</b> ({@code testMode=true}): violations throw
 *       {@link AssertionError} immediately. Use this in unit and
 *       integration tests to fail fast on invariant violations.</li>
 *   <li><b>Production mode</b> ({@code testMode=false}): violations
 *       increment a counter in the {@link MetricsRegistry} and are
 *       recorded for later inspection. The system continues running —
 *       operators are expected to alert on the violation counter.</li>
 * </ul>
 * <p>
 * Thread safety: all methods are safe for concurrent use. Violation
 * counts are backed by {@link LongAdder} for lock-free increments.
 * Registered invariants are stored in a {@link ConcurrentHashMap}.
 *
 * @see MetricsRegistry
 */
public final class InvariantMonitor {

    /** Prefix for invariant violation counter metrics. */
    private static final String METRIC_PREFIX = "invariant.violation.";

    private final MetricsRegistry metrics;
    private final boolean testMode;

    /** Registered invariants: name -> registration. */
    private final ConcurrentHashMap<String, InvariantRegistration> invariants = new ConcurrentHashMap<>();

    /** Per-invariant violation counts. */
    private final ConcurrentHashMap<String, LongAdder> violationCounts = new ConcurrentHashMap<>();

    /**
     * Creates an invariant monitor.
     *
     * @param metrics  the metrics registry for recording violation counts (non-null)
     * @param testMode if {@code true}, violations throw {@link AssertionError};
     *                 if {@code false}, violations are recorded silently
     */
    public InvariantMonitor(MetricsRegistry metrics, boolean testMode) {
        Objects.requireNonNull(metrics, "metrics must not be null");
        this.metrics = metrics;
        this.testMode = testMode;
    }

    /**
     * Checks an invariant condition. If the condition is {@code false}:
     * <ul>
     *   <li>In test mode: throws {@link AssertionError} with the given message.</li>
     *   <li>In production mode: increments the violation counter for this
     *       invariant name.</li>
     * </ul>
     *
     * @param invariantName      a unique name for this invariant (non-null, non-blank)
     * @param condition          the invariant condition ({@code true} means OK)
     * @param messageOnViolation the message to include in the error or log (non-null)
     * @throws AssertionError if {@code testMode} is {@code true} and {@code condition} is {@code false}
     */
    public void check(String invariantName, boolean condition, String messageOnViolation) {
        Objects.requireNonNull(invariantName, "invariantName must not be null");
        Objects.requireNonNull(messageOnViolation, "messageOnViolation must not be null");

        if (condition) {
            return;
        }

        // Record violation
        recordViolation(invariantName);

        if (testMode) {
            throw new AssertionError("Invariant violated [" + invariantName + "]: " + messageOnViolation);
        }
    }

    // -----------------------------------------------------------------------
    // Data-plane invariant helpers (INV-M1, INV-S1) — F-0073
    //
    // These bridge the two data-plane invariants from consistency-contract.md
    // §8 into InvariantMonitor. Until F-0073, the edge read path and
    // StalenessTracker enforced the invariants structurally but never
    // incremented `configd.invariant.violation.*` counters, so alerting
    // would silently miss data-plane correctness drift.
    // -----------------------------------------------------------------------

    /**
     * Invariant name (INV-M1): a monotonic-read session must never observe
     * a version go backwards. Exposed as
     * {@code configd.invariant.violation.monotonic_read} in metrics.
     */
    public static final String MONOTONIC_READ = "monotonic_read";

    /**
     * Invariant name (INV-S1): edge-cache staleness must stay under the
     * configured upper bound. Exposed as
     * {@code configd.invariant.violation.staleness_bound}.
     */
    public static final String STALENESS_BOUND = "staleness_bound";

    /**
     * Asserts the INV-M1 monotonic-read invariant: for a single session /
     * cursor, the version we return now ({@code newVersion}) must be at
     * least as high as the previously observed version ({@code seenVersion}).
     * <p>
     * Called from the edge read path ({@code LocalConfigStore.get(...)}) when
     * a cursor is present. A violation indicates either an out-of-order
     * delta apply or a client-server version mismatch — both fatal for
     * read-your-writes semantics.
     *
     * @param key          the config key (for diagnostic messages, may be null)
     * @param seenVersion  the cursor version previously returned to the client
     * @param newVersion   the version we are about to return
     * @throws AssertionError in test mode if {@code newVersion < seenVersion}
     */
    public void assertMonotonicRead(String key, long seenVersion, long newVersion) {
        boolean ok = newVersion >= seenVersion;
        if (!ok) {
            String msg = "monotonic read violated: key=" + key
                    + " seenVersion=" + seenVersion
                    + " newVersion=" + newVersion;
            check(MONOTONIC_READ, false, msg);
        }
    }

    /**
     * Asserts the INV-S1 staleness-bound invariant: the observed staleness
     * between this edge cache and the leader must not exceed the configured
     * upper bound (defaulting to {@code STALE_THRESHOLD_MS} from
     * {@code StalenessTracker}, 500ms per ADR-0007).
     * <p>
     * Called from {@code StalenessTracker.isStale(...)} / the delta apply
     * path when a fresh leader version is observed. The {@code localVersion}
     * and {@code remoteVersion} are informational; the trigger is purely on
     * elapsed-time staleness.
     *
     * @param localVersion   the local cache's current version
     * @param remoteVersion  the most recently observed leader version
     * @param staleMs        elapsed milliseconds since last successful update
     * @param thresholdMs    the upper bound (violation if staleMs > thresholdMs)
     * @throws AssertionError in test mode if {@code staleMs > thresholdMs}
     */
    public void assertStalenessBound(long localVersion, long remoteVersion,
                                     long staleMs, long thresholdMs) {
        boolean ok = staleMs <= thresholdMs;
        if (!ok) {
            String msg = "staleness bound exceeded: local=" + localVersion
                    + " remote=" + remoteVersion
                    + " staleMs=" + staleMs
                    + " thresholdMs=" + thresholdMs;
            check(STALENESS_BOUND, false, msg);
        }
    }

    /**
     * Registers a named invariant with a check function and description.
     * <p>
     * Registered invariants can be checked as a batch via {@link #checkAll()}.
     * If an invariant with the same name is already registered, it is replaced.
     *
     * @param invariantName the unique invariant name (non-null, non-blank)
     * @param check         the invariant check ({@code true} means OK) (non-null)
     * @param description   human-readable description of the invariant (non-null)
     */
    public void register(String invariantName, BooleanSupplier check, String description) {
        Objects.requireNonNull(invariantName, "invariantName must not be null");
        if (invariantName.isBlank()) {
            throw new IllegalArgumentException("invariantName must not be blank");
        }
        Objects.requireNonNull(check, "check must not be null");
        Objects.requireNonNull(description, "description must not be null");

        invariants.put(invariantName, new InvariantRegistration(check, description));
    }

    /**
     * Runs all registered invariants. Each failing invariant is processed
     * according to the current mode (test mode throws on first failure;
     * production mode records all failures).
     *
     * @throws AssertionError if {@code testMode} is {@code true} and any invariant fails
     */
    public void checkAll() {
        for (var entry : invariants.entrySet()) {
            String name = entry.getKey();
            InvariantRegistration reg = entry.getValue();
            boolean result = reg.check().getAsBoolean();
            check(name, result, reg.description());
        }
    }

    /**
     * Returns the violation counts for all invariants that have been violated
     * at least once.
     *
     * @return unmodifiable map of invariant name to violation count
     */
    public Map<String, Long> violations() {
        return violationCounts.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> e.getValue().sum()));
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private void recordViolation(String invariantName) {
        violationCounts.computeIfAbsent(invariantName, k -> new LongAdder()).increment();
        metrics.counter(METRIC_PREFIX + invariantName).increment();
    }

    /**
     * Registration record for a named invariant.
     */
    private record InvariantRegistration(BooleanSupplier check, String description) {
        InvariantRegistration {
            Objects.requireNonNull(check, "check must not be null");
            Objects.requireNonNull(description, "description must not be null");
        }
    }
}

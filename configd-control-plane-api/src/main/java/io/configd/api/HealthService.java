package io.configd.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Health check service for liveness, readiness, and detailed diagnostics.
 * <p>
 * Provides three levels of health checks:
 * <ul>
 *   <li><b>Liveness</b> - is the process alive? Always true if reachable.</li>
 *   <li><b>Readiness</b> - can this node serve traffic? Checks Raft state,
 *       store health, and connectivity.</li>
 *   <li><b>Detailed</b> - full diagnostic report for operators.</li>
 * </ul>
 */
public final class HealthService {

    @FunctionalInterface
    public interface HealthCheck {
        CheckResult check();
    }

    public record CheckResult(String name, boolean healthy, String detail) {
        public CheckResult {
            Objects.requireNonNull(name, "name must not be null");
        }

        public static CheckResult healthy(String name) {
            return new CheckResult(name, true, "OK");
        }

        public static CheckResult unhealthy(String name, String detail) {
            return new CheckResult(name, false, detail);
        }
    }

    public record HealthStatus(boolean healthy, List<CheckResult> checks) {
        public HealthStatus {
            Objects.requireNonNull(checks, "checks must not be null");
            checks = List.copyOf(checks);
        }
    }

    private final List<HealthCheck> readinessChecks;

    public HealthService() {
        this.readinessChecks = new ArrayList<>();
    }

    public void registerReadinessCheck(HealthCheck check) {
        Objects.requireNonNull(check, "check must not be null");
        readinessChecks.add(check);
    }

    public HealthStatus liveness() {
        return new HealthStatus(true, List.of(CheckResult.healthy("liveness")));
    }

    public HealthStatus readiness() {
        List<CheckResult> results = new ArrayList<>(readinessChecks.size());
        boolean allHealthy = true;

        for (HealthCheck check : readinessChecks) {
            CheckResult result = check.check();
            results.add(result);
            if (!result.healthy()) {
                allHealthy = false;
            }
        }

        if (results.isEmpty()) {
            results.add(CheckResult.healthy("default"));
        }

        return new HealthStatus(allHealthy, results);
    }

    /** Currently identical to {@link #readiness()}; no additional diagnostics are collected yet. */
    public HealthStatus detailed() {
        return readiness();
    }
}

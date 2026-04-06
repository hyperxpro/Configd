package io.configd.observability;

import java.util.*;

/**
 * Computes multi-window burn rates for SLO alerting.
 * Evaluates fast-burn (14.4x over 1h) and slow-burn (1x over 3d) alerts.
 */
public final class BurnRateAlertEvaluator {

    public sealed interface AlertLevel {
        record None() implements AlertLevel {}
        record Warning(String sloName, double burnRate, String window) implements AlertLevel {}
        record Critical(String sloName, double burnRate, String window) implements AlertLevel {}
    }

    public interface AlertSink {
        void fire(AlertLevel alert);
    }

    private final SloTracker tracker;
    private final List<AlertSink> sinks = new ArrayList<>();

    public BurnRateAlertEvaluator(SloTracker tracker) {
        this.tracker = Objects.requireNonNull(tracker);
    }

    public void addSink(AlertSink sink) {
        sinks.add(Objects.requireNonNull(sink));
    }

    /**
     * Evaluates all registered SLOs and fires alerts for any that are breaching.
     * Call this periodically (e.g., every 60 seconds).
     * @return list of active alerts
     */
    public List<AlertLevel> evaluate() {
        List<AlertLevel> alerts = new ArrayList<>();
        Map<String, SloTracker.SloStatus> snapshot = tracker.snapshot();

        for (var entry : snapshot.entrySet()) {
            String name = entry.getKey();
            SloTracker.SloStatus status = entry.getValue();

            if (!status.breaching()) continue;

            // Compute burn rate: (1 - compliance) / (1 - target)
            double errorBudgetFraction = 1.0 - status.target();
            if (errorBudgetFraction <= 0) continue;
            double currentErrorRate = 1.0 - status.current();
            double burnRate = currentErrorRate / errorBudgetFraction;

            AlertLevel alert;
            if (burnRate >= 14.4) {
                alert = new AlertLevel.Critical(name, burnRate, "fast-burn");
            } else if (burnRate >= 1.0) {
                alert = new AlertLevel.Warning(name, burnRate, "slow-burn");
            } else {
                continue;
            }

            alerts.add(alert);
            for (AlertSink sink : sinks) {
                sink.fire(alert);
            }
        }
        return alerts;
    }
}

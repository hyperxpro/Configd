package io.configd.observability;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Drives {@link BurnRateAlertEvaluator} on a periodic cadence and publishes the outcome to the metrics
 * surface, so the ratified burn-rate thresholds evaluate against a running node rather than sitting
 * unreachable behind a constructed-but-never-called evaluator.
 * <p>
 * This is the node's own view of the alert rules in {@code ops/alerts/configd-slo-alerts.yaml}. That
 * YAML is still the production alerting path; it runs the same burn-rate arithmetic in Prometheus over
 * scraped series. This path needs no Prometheus, which is what makes it useful on a single node and
 * during a drill.
 * <p>
 * {@link #runOnce()} never throws. A {@link java.util.concurrent.ScheduledExecutorService} silently
 * cancels all future executions of a task whose run escapes a throwable, so a throwing evaluation would
 * not merely lose one sample - it would disable alerting for the life of the process, silently. The
 * failure is counted instead ({@link ConfigdMetrics#NAME_SLO_BURN_EVALUATIONS_FAILED}) and the next
 * cadence runs normally.
 */
public final class SloBurnRateAlerting {

    private static final Logger LOG = Logger.getLogger(SloBurnRateAlerting.class.getName());

    private final BurnRateAlertEvaluator evaluator;
    private final ConfigdMetrics metrics;

    public SloBurnRateAlerting(BurnRateAlertEvaluator evaluator, ConfigdMetrics metrics) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        evaluator.addSink(metrics::onBurnRateAlert);
        evaluator.addSink(SloBurnRateAlerting::log);
    }

    /**
     * Runs one evaluation. Returns the alerts active at this evaluation, or an empty list if the
     * evaluation failed.
     */
    public List<BurnRateAlertEvaluator.AlertLevel> runOnce() {
        try {
            List<BurnRateAlertEvaluator.AlertLevel> active = evaluator.evaluate();
            metrics.onBurnRateEvaluation(active.size());
            return active;
        } catch (Throwable t) {
            metrics.onBurnRateEvaluationFailed();
            LOG.log(Level.WARNING, "SLO burn-rate evaluation failed; alerting continues next cadence", t);
            return List.of();
        }
    }

    private static void log(BurnRateAlertEvaluator.AlertLevel alert) {
        switch (alert) {
            case BurnRateAlertEvaluator.AlertLevel.Critical c -> LOG.log(Level.SEVERE,
                    "SLO burn-rate CRITICAL: slo={0} burnRate={1} window={2}",
                    new Object[] {c.sloName(), c.burnRate(), c.window()});
            case BurnRateAlertEvaluator.AlertLevel.Warning w -> LOG.log(Level.WARNING,
                    "SLO burn-rate WARNING: slo={0} burnRate={1} window={2}",
                    new Object[] {w.sloName(), w.burnRate(), w.window()});
            case BurnRateAlertEvaluator.AlertLevel.None ignored -> { }
        }
    }
}

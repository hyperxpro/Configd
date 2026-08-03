package io.configd.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the burn-rate evaluator is actually reachable in a running node, which is what it was not: it
 * was constructed and never called, so the ratified thresholds evaluated nothing.
 * <p>
 * The three properties that make the wiring real rather than nominal are an alert firing when its
 * condition is met, no alert when it is not, and a scheduler that keeps evaluating after a failed
 * evaluation. That last one is the load-bearing case: {@code ScheduledExecutorService} cancels all
 * future executions of a task that lets a throwable escape, so a single failure would silently disable
 * alerting for the life of the process, and the metrics surface would keep reporting the last-known
 * alert count as though it were current.
 */
class SloBurnRateAlertingTest {

    private MetricsRegistry registry;
    private ConfigdMetrics metrics;
    private SloTracker tracker;
    private BurnRateAlertEvaluator evaluator;
    private SloBurnRateAlerting alerting;

    @BeforeEach
    void setUp() {
        registry = new MetricsRegistry();
        metrics = new ConfigdMetrics(registry, null);
        tracker = new SloTracker();
        evaluator = new BurnRateAlertEvaluator(tracker);
        alerting = new SloBurnRateAlerting(evaluator, metrics);
    }

    private long metric(String name) {
        MetricsRegistry.MetricValue v = registry.snapshot().metrics().get(name);
        return v == null ? -1 : v.value();
    }

    @Test
    void everySeriesIsRegisteredBeforeTheFirstEvaluation() {
        // Alerts query these series; a series that only appears after the first firing cannot alert on
        // the firing that created it.
        assertEquals(0, metric(ConfigdMetrics.NAME_SLO_BURN_ALERTS_ACTIVE));
        assertEquals(0, metric(ConfigdMetrics.NAME_SLO_BURN_EVALUATIONS));
        assertEquals(0, metric(ConfigdMetrics.NAME_SLO_BURN_EVALUATIONS_FAILED));
        assertEquals(0, metric(ConfigdMetrics.NAME_SLO_BURN_ALERTS_FIRED_BASE + ".critical"));
        assertEquals(0, metric(ConfigdMetrics.NAME_SLO_BURN_ALERTS_FIRED_BASE + ".warning"));
    }

    @Test
    void firesCriticalAndPublishesItWhenTheConditionIsMet() {
        tracker.defineSlo("fast.burn", 0.99, Duration.ofHours(1));
        for (int i = 0; i < 80; i++) {
            tracker.recordSuccess("fast.burn");
        }
        for (int i = 0; i < 20; i++) {
            tracker.recordFailure("fast.burn");
        }

        List<BurnRateAlertEvaluator.AlertLevel> active = alerting.runOnce();

        assertEquals(1, active.size());
        assertInstanceOf(BurnRateAlertEvaluator.AlertLevel.Critical.class, active.getFirst());
        assertEquals(1, metric(ConfigdMetrics.NAME_SLO_BURN_ALERTS_ACTIVE));
        assertEquals(1, metric(ConfigdMetrics.NAME_SLO_BURN_ALERTS_FIRED_BASE + ".critical"));
        assertEquals(0, metric(ConfigdMetrics.NAME_SLO_BURN_ALERTS_FIRED_BASE + ".warning"));
        assertEquals(1, metric(ConfigdMetrics.NAME_SLO_BURN_EVALUATIONS));
        assertEquals(0, metric(ConfigdMetrics.NAME_SLO_BURN_EVALUATIONS_FAILED));
    }

    @Test
    void firesWarningOnSlowBurn() {
        tracker.defineSlo("slow.burn", 0.99, Duration.ofHours(1));
        for (int i = 0; i < 95; i++) {
            tracker.recordSuccess("slow.burn");
        }
        for (int i = 0; i < 5; i++) {
            tracker.recordFailure("slow.burn");
        }

        assertEquals(1, alerting.runOnce().size());
        assertEquals(1, metric(ConfigdMetrics.NAME_SLO_BURN_ALERTS_FIRED_BASE + ".warning"));
        assertEquals(0, metric(ConfigdMetrics.NAME_SLO_BURN_ALERTS_FIRED_BASE + ".critical"));
    }

    @Test
    void doesNotFireWhenTheConditionIsNotMet() {
        tracker.defineSlo("healthy", 0.99, Duration.ofHours(1));
        for (int i = 0; i < 500; i++) {
            tracker.recordSuccess("healthy");
        }

        assertTrue(alerting.runOnce().isEmpty());
        assertEquals(0, metric(ConfigdMetrics.NAME_SLO_BURN_ALERTS_ACTIVE));
        assertEquals(0, metric(ConfigdMetrics.NAME_SLO_BURN_ALERTS_FIRED_BASE + ".critical"));
        assertEquals(0, metric(ConfigdMetrics.NAME_SLO_BURN_ALERTS_FIRED_BASE + ".warning"));
        // The evaluation still happened; a flat evaluations counter is how a wedged evaluator is told
        // apart from a genuinely quiet one.
        assertEquals(1, metric(ConfigdMetrics.NAME_SLO_BURN_EVALUATIONS));
    }

    @Test
    void theActiveGaugeTracksAlertsClearing() {
        // The window is an hour and nothing is evicted during the test: recovery here is dilution by
        // later successes, not the failures ageing out. A short window would make the second
        // evaluation depend on how long the first one took, which is not a property worth asserting.
        tracker.defineSlo("recovers", 0.99, Duration.ofHours(1));
        for (int i = 0; i < 20; i++) {
            tracker.recordFailure("recovers");
        }
        alerting.runOnce();
        assertEquals(1, metric(ConfigdMetrics.NAME_SLO_BURN_ALERTS_ACTIVE));

        // 3000 successes against 20 failures is 99.34% compliance, clear of the 99% target whether or
        // not the failures are still in the window.
        for (int i = 0; i < 3_000; i++) {
            tracker.recordSuccess("recovers");
        }
        alerting.runOnce();
        assertEquals(0, metric(ConfigdMetrics.NAME_SLO_BURN_ALERTS_ACTIVE),
                "the gauge must fall back to zero, or a cleared alert reads as still firing");
    }

    @Test
    void aFailedEvaluationIsCountedAndNeverPropagates() {
        tracker.defineSlo("burning", 0.99, Duration.ofHours(1));
        for (int i = 0; i < 20; i++) {
            tracker.recordFailure("burning");
        }
        alerting.runOnce();
        assertEquals(1, metric(ConfigdMetrics.NAME_SLO_BURN_ALERTS_ACTIVE));

        // An Error passes through the evaluator's per-sink RuntimeException guard, so this reaches
        // runOnce as an escaping throwable - the exact shape that would cancel the scheduled task.
        evaluator.addSink(alert -> {
            throw new StackOverflowError("synthetic");
        });

        assertTrue(alerting.runOnce().isEmpty(), "a failed evaluation reports no alerts, it does not throw");
        assertEquals(1, metric(ConfigdMetrics.NAME_SLO_BURN_EVALUATIONS_FAILED));
        assertEquals(1, metric(ConfigdMetrics.NAME_SLO_BURN_EVALUATIONS),
                "the failed evaluation is not counted as a completed one");
        assertEquals(1, metric(ConfigdMetrics.NAME_SLO_BURN_ALERTS_ACTIVE),
                "the last known count is kept; zeroing it would render a failure as 'no alerts'");
    }

    @Test
    void aFailingEvaluationDoesNotWedgeTheScheduler() throws Exception {
        tracker.defineSlo("burning", 0.99, Duration.ofHours(1));
        for (int i = 0; i < 20; i++) {
            tracker.recordFailure("burning");
        }
        AtomicInteger fires = new AtomicInteger();
        CountDownLatch thirdRun = new CountDownLatch(3);
        evaluator.addSink(alert -> {
            fires.incrementAndGet();
            thirdRun.countDown();
            throw new StackOverflowError("synthetic");
        });

        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-slo-alerts");
            t.setDaemon(true);
            return t;
        });
        try {
            exec.scheduleAtFixedRate(alerting::runOnce, 0, 10, TimeUnit.MILLISECONDS);
            assertTrue(thirdRun.await(5, TimeUnit.SECONDS),
                    "the task stopped after a failure: only " + fires.get() + " run(s) happened, so a "
                            + "single bad evaluation disabled alerting for the process lifetime");
        } finally {
            exec.shutdownNow();
        }
        // The latch is released inside the sink, before it throws, so the newest run's failure may not
        // be recorded yet. The two before it are, and two is already more than a wedged task manages.
        assertTrue(metric(ConfigdMetrics.NAME_SLO_BURN_EVALUATIONS_FAILED) >= 2,
                "repeat failures should be counted, not just the first");
    }

    @Test
    void everyProductionSloIsEvaluated() {
        ProductionSloDefinitions.register(tracker);
        // No events recorded: compliance is vacuous, so nothing should breach. The point is that the
        // registered set evaluates at all rather than throwing on some definition.
        assertTrue(alerting.runOnce().isEmpty());
        assertEquals(1, metric(ConfigdMetrics.NAME_SLO_BURN_EVALUATIONS));
        assertFalse(tracker.snapshot().isEmpty(), "the production SLO set must be non-empty");
    }
}

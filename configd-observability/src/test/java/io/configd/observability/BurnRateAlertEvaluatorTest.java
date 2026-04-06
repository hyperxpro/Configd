package io.configd.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BurnRateAlertEvaluator} — burn-rate alerting on SLO breaches.
 */
class BurnRateAlertEvaluatorTest {

    private SloTracker tracker;
    private BurnRateAlertEvaluator evaluator;
    private List<BurnRateAlertEvaluator.AlertLevel> firedAlerts;

    @BeforeEach
    void setUp() {
        tracker = new SloTracker();
        evaluator = new BurnRateAlertEvaluator(tracker);
        firedAlerts = new ArrayList<>();
        evaluator.addSink(firedAlerts::add);
    }

    @Test
    void noAlertsWhenSlosAreHealthy() {
        tracker.defineSlo("healthy.slo", 0.99, Duration.ofHours(1));
        // Record 100 successes, 0 failures -> 100% compliance
        for (int i = 0; i < 100; i++) {
            tracker.recordSuccess("healthy.slo");
        }

        List<BurnRateAlertEvaluator.AlertLevel> alerts = evaluator.evaluate();

        assertTrue(alerts.isEmpty(), "No alerts expected when SLO is healthy");
        assertTrue(firedAlerts.isEmpty(), "No alerts should have been fired to sink");
    }

    @Test
    void noAlertsWhenNoEventsRecorded() {
        tracker.defineSlo("empty.slo", 0.99, Duration.ofHours(1));

        List<BurnRateAlertEvaluator.AlertLevel> alerts = evaluator.evaluate();

        assertTrue(alerts.isEmpty(), "No alerts expected with no events (vacuous compliance)");
    }

    @Test
    void warningAlertOnSlowBurn() {
        // Target 99% -> error budget = 1%
        // To get a burn rate between 1.0 and 14.4, we need error rate between 1% and 14.4%
        // With 100 events: 5 failures = 5% error rate -> burn rate = 5.0 (slow burn)
        tracker.defineSlo("slow.burn.slo", 0.99, Duration.ofHours(1));
        for (int i = 0; i < 95; i++) {
            tracker.recordSuccess("slow.burn.slo");
        }
        for (int i = 0; i < 5; i++) {
            tracker.recordFailure("slow.burn.slo");
        }

        List<BurnRateAlertEvaluator.AlertLevel> alerts = evaluator.evaluate();

        assertEquals(1, alerts.size(), "Expected exactly one alert");
        assertInstanceOf(BurnRateAlertEvaluator.AlertLevel.Warning.class, alerts.getFirst());

        BurnRateAlertEvaluator.AlertLevel.Warning warning =
                (BurnRateAlertEvaluator.AlertLevel.Warning) alerts.getFirst();
        assertEquals("slow.burn.slo", warning.sloName());
        assertEquals("slow-burn", warning.window());
        assertTrue(warning.burnRate() >= 1.0 && warning.burnRate() < 14.4,
                "Burn rate should be in slow-burn range: " + warning.burnRate());

        assertEquals(1, firedAlerts.size(), "Sink should have received one alert");
    }

    @Test
    void criticalAlertOnFastBurn() {
        // Target 99% -> error budget = 1%
        // With 100 events: 20 failures = 20% error rate -> burn rate = 20.0 (fast burn, >= 14.4)
        tracker.defineSlo("fast.burn.slo", 0.99, Duration.ofHours(1));
        for (int i = 0; i < 80; i++) {
            tracker.recordSuccess("fast.burn.slo");
        }
        for (int i = 0; i < 20; i++) {
            tracker.recordFailure("fast.burn.slo");
        }

        List<BurnRateAlertEvaluator.AlertLevel> alerts = evaluator.evaluate();

        assertEquals(1, alerts.size(), "Expected exactly one alert");
        assertInstanceOf(BurnRateAlertEvaluator.AlertLevel.Critical.class, alerts.getFirst());

        BurnRateAlertEvaluator.AlertLevel.Critical critical =
                (BurnRateAlertEvaluator.AlertLevel.Critical) alerts.getFirst();
        assertEquals("fast.burn.slo", critical.sloName());
        assertEquals("fast-burn", critical.window());
        assertTrue(critical.burnRate() >= 14.4,
                "Burn rate should be >= 14.4 for fast-burn: " + critical.burnRate());

        assertEquals(1, firedAlerts.size(), "Sink should have received one alert");
    }

    @Test
    void multipleSlosCanFireIndependently() {
        tracker.defineSlo("slo.a", 0.99, Duration.ofHours(1));
        tracker.defineSlo("slo.b", 0.99, Duration.ofHours(1));

        // SLO A: healthy
        for (int i = 0; i < 100; i++) {
            tracker.recordSuccess("slo.a");
        }
        // SLO B: breaching
        for (int i = 0; i < 80; i++) {
            tracker.recordSuccess("slo.b");
        }
        for (int i = 0; i < 20; i++) {
            tracker.recordFailure("slo.b");
        }

        List<BurnRateAlertEvaluator.AlertLevel> alerts = evaluator.evaluate();

        assertEquals(1, alerts.size(), "Only breaching SLO should fire");
    }
}

package io.configd.edge.node;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.edge.EdgeClientCore;
import io.configd.edge.StrongReadKeyClass;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S6/WS-D — the CI-sized GAME-DAY DRILL subset (charter §8 / §9 / gate-6): proves the
 * alert → runbook → recovery loop CLOSES end-to-end for at least one scenario, in-process and
 * deterministically (the long full multi-node drill — region loss, rolling upgrade, rollback —
 * runs in the nightly/ops lane via {@code gates/game-day-drill.sh}, captured).
 *
 * <p>Scenario = charter drill step 3, "inject a lagging edge → slow-consumer/staleness alert →
 * runbook → recovery": the live {@code edge_staleness_ms} signal must cross the alert thresholds
 * under an injected fan-out stall and return under the runbook's recovery action. The matching
 * alert rules ({@code ConfigdEdgeStalenessWarn} > 500 ms, {@code ConfigdEdgeStalenessDegraded}
 * > 2 s) are independently proven to FIRE on these series values and STAY QUIET below them by
 * {@code ops/alerts/configd-slo-alerts.test.yaml} (promtool); this test closes the loop by driving
 * the real signal through HEALTHY → FAULT → RECOVERY and asserting each phase at the metric surface.
 * Runbook: {@code ops/runbooks/propagation-delay.md} (+ {@code edge-catchup-storm.md}).
 */
class GameDayDrillTest {

    private static final class DrillClock implements Clock {
        long timeMs = 1_000_000L;
        @Override public long currentTimeMillis() { return timeMs; }
        @Override public long nanoTime() { return timeMs * 1_000_000L; }
        void advance(long ms) { timeMs += ms; }
    }

    private static double stalenessMs(MetricsRegistry registry) {
        String scrape = new PrometheusExporter(registry).export();
        return scrape.lines()
                .filter(l -> l.startsWith("edge_staleness_ms "))
                .map(l -> Double.parseDouble(l.substring("edge_staleness_ms ".length()).trim()))
                .findFirst().orElseThrow(() -> new AssertionError("edge_staleness_ms not exported"));
    }

    private static void deliver(EdgeClientCore core, DrillClock clock, long seq, String key, String val) {
        ConfigDelta delta = new ConfigDelta(seq - 1, seq,
                List.of(new ConfigMutation.Put(key, val.getBytes(StandardCharsets.UTF_8))));
        core.onFrame(new EdgeFrame.Notify(List.of(new CommitNotification(seq, clock.timeMs, delta))));
    }

    @Test
    void laggingEdgeAlertRunbookRecoveryLoopCloses() {
        DrillClock clock = new DrillClock();
        MetricsRegistry registry = new MetricsRegistry();
        EdgeNodeMetrics metrics = new EdgeNodeMetrics(registry);
        EdgeClientCore core = new EdgeClientCore(clock, null, metrics.implausibleCounter(),
                StrongReadKeyClass.DEFAULT, EdgeClientCore.FrameSink.NONE,
                EdgeClientCore.DEFAULT_HEARTBEAT_MS, EdgeClientCore.DEFAULT_SILENCE_FACTOR);
        metrics.bind(core);

        // ---- 1. HEALTHY: a fresh delivery at wall-now → CURRENT, alert quiet (< 500 ms) -----
        deliver(core, clock, 1, "svc/a", "v1");
        metrics.syncFromCore(core, null);
        assertTrue(stalenessMs(registry) < 500,
                "HEALTHY: edge_staleness_ms must be below the warn threshold (alert quiet)");

        // ---- 2. INJECT FAULT: fan-out stalls (lagging edge) — no delivery as wall advances --
        // 2.5 s of silence past the last frontier crosses BOTH the 500 ms warn and 2 s degraded
        // thresholds → the correct alert (ConfigdEdgeStalenessDegraded) would fire.
        clock.advance(2_500);
        metrics.syncFromCore(core, null);
        double underFault = stalenessMs(registry);
        assertTrue(underFault > 2_000,
                "FAULT: edge_staleness_ms must cross the 2 s degraded threshold (alert fires); got "
                        + underFault);

        // ---- 3. RUNBOOK RECOVERY: re-establish fan-out delivery / catch-up (propagation-delay.md)
        // A fresh commit at the current wall heals the frontier → staleness collapses, alert clears.
        deliver(core, clock, 2, "svc/a", "v2");
        metrics.syncFromCore(core, null);
        assertTrue(stalenessMs(registry) < 500,
                "RECOVERY: after the runbook's catch-up, edge_staleness_ms returns below warn "
                        + "(alert clears) — the alert→runbook→recovery loop closed");
    }
}

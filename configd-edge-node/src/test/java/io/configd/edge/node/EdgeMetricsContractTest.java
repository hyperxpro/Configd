package io.configd.edge.node;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.fanout.DemotionEvent;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.edge.EdgeClientCore;
import io.configd.edge.StrongReadKeyClass;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.server.fanout.RegistryFanOutSessionMetrics;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CT-38 — the consolidated metrics presence+emission gate (charter §6 rule 8 / §7 DoD:
 * "Metrics emitted for every policy threshold and propagation stage (named list in
 * handoff)"). This class IS the machine-checked named list: Session 6 wires dashboards
 * against the literal exported series names asserted here, so every name below is a
 * deliberate string literal — renaming a series (or the Java constant behind it) must
 * break this test loudly, never silently re-point a dashboard at nothing.
 *
 * <p>This module sees both halves of the data plane: {@code configd-edge-node} owns the
 * edge-process series ({@link EdgeNodeMetrics}) and has {@code configd-server} in TEST
 * scope, so the server-side fan-out series ({@link RegistryFanOutSessionMetrics}) are
 * instantiable here over their own registry. Presence is assertable on the FIRST scrape
 * with zero traffic because of the RR-013 eager-registration discipline; emission
 * spot-checks are deliberately light — per-series movement semantics are already pinned
 * in {@code EdgeNodeMetricsTest} (this module) and {@code RegistryFanOutSessionMetricsTest}
 * / {@code FanOutServerQuarantineTest} (configd-server).
 *
 * <h2>CT-38 required-series checklist — 1:1 disposition</h2>
 * (Checklist and priced deviations per the CT-38 row, docs/session-3/contract-test-map.md.)
 * <ol>
 *   <li><b>edge staleness gauge</b> — {@code edge_staleness_ms} + {@code edge_staleness_state}:
 *       presence asserted ({@link #edgeProcessSeriesAreAllPresentOnFirstScrape}); emission
 *       asserted against the LIVE core tracker at scrape time
 *       ({@link #stalenessGaugesAndViolationCounterTrackTheLiveCore}).</li>
 *   <li><b>edge staleness histogram</b> — DEVIATION (priced in the row): no Prometheus
 *       histogram series exists by design; the staleness DISTRIBUTION is the charter §3 V2
 *       probe's {@code PROBE-HISTOGRAM} output, emitted in BOTH probe modes. Substitutes
 *       asserted: the two live gauges (here) plus the probe report-format contract — which
 *       cannot be asserted from this module because {@code PropagationProbe} lives in
 *       {@code configd-testkit}, which DEPENDS ON {@code configd-edge-node} (a direct
 *       reference would be a dependency cycle). The tiny format-contract unit assertion is
 *       {@code ProbeMechanismTest#reportEmitsOneGreppableProbeHistogramLinePerScope}
 *       (configd-testkit), and gate-3 step (d) runs both live modes and greps the lines.</li>
 *   <li><b>edge cursor lag</b> ({@code latestSeq − last_applied_seq}) —
 *       {@code edge_cursor_lag} gauge: presence + heartbeat-driven movement
 *       ({@link #cursorLagGaugeExportsTheCoreLag}).</li>
 *   <li><b>per-subscriber queue depth</b> — DEVIATION (priced in the row):
 *       {@link MetricsRegistry} is label-free, so this is the PROCESS-LEVEL high-water
 *       gauge {@code edge_fanout_queue_depth}, not per-subscriber (a per-session breakdown
 *       needs a label-capable backend). Presence + high-water semantics asserted
 *       ({@link #fanOutCallbackSeamsMoveTheChecklistSeries}).</li>
 *   <li><b>slow-consumer state-transition counters (one per CT-27..CT-30)</b> —
 *       CT-27 {@code edge_fanout_slow_transitions_total}; CT-28
 *       {@code edge_fanout_quarantines_total} + {@code edge_fanout_sessions_closed_quarantined_total};
 *       CT-29 {@code edge_fanout_reconnects_refused_total} + {@code edge_fanout_readmissions_total};
 *       CT-30 {@code edge_fanout_unhealthy_total}; plus the per-state
 *       {@code edge_fanout_consumer_state_*} gauges (the design's {@code consumer_state{state}}
 *       label, per-suffix encoded — the established label-free-registry deviation).
 *       Presence + one movement per family asserted.</li>
 *   <li><b>fan-out connected-subscriber count</b> — {@code edge_fanout_connected_subscribers}
 *       gauge: presence + connect/disconnect movement.</li>
 *   <li><b>{@code configd.edge.staleness_violation_total}</b> (CT-04) — exported as
 *       {@code configd_edge_staleness_violation_total}: presence + movement on a STALE entry
 *       driven through the real core/clock. Full transition semantics (once per entry,
 *       pass-through counting, boot exclusion) are CT-04's, pinned in
 *       {@code EdgeNodeMetricsTest}.</li>
 *   <li><b>{@code configd.edge.poison_pill}</b> (CT-33) — exported as
 *       {@code configd_edge_poison_pill_total} (+ {@code configd_edge_poison_pill_terminal_total},
 *       {@code edge_poison_retries_total}): presence + wiring from the named counter handles
 *       {@link EdgeNodeMetrics} hands to {@code PoisonPillPolicy}
 *       ({@link #poisonPillCounterHandlesMoveTheExportedSeries}); ladder semantics are
 *       CT-33's, pinned in the configd-edge-cache policy tests.</li>
 * </ol>
 *
 * <p>Out of this gate's scope (pre-existing, pinned elsewhere, per the row):
 * {@code fanout_buffer_dropped_total} and the {@code invariant.violation.*} series are
 * registered by other components and verified by {@code FanOutServerIntegrationTest} and
 * the invariant-seam tests.
 */
class EdgeMetricsContractTest {

    // ------------------------------------------------------------------------------
    // The named list (Session 6 dashboard contract): exact exported sample-line names.
    // Counters export `<name>_total`, gauges bare, histograms `<name>_count` (+ quantile
    // lines once non-empty). Literals on purpose — see class javadoc.
    // ------------------------------------------------------------------------------

    private static final List<String> EDGE_PROCESS_SERIES = List.of(
            // staleness surface (checklist items 1, 7)
            "edge_staleness_ms",
            "edge_staleness_state",
            "configd_edge_staleness_violation_total",
            "edge_staleness_implausible_total",
            // cursor / apply pipeline (item 3)
            "edge_cursor_lag",
            "edge_applied_total",
            "edge_gaps_total",
            "edge_snapshots_applied_total",
            "edge_verify_rejections_total",
            // read-serving surface
            "edge_reads_total",
            "edge_read_refusals_cursor_behind_total",
            "edge_read_refusals_strong_read_total",
            "edge_read_refusals_not_subscribed_total",
            // lifecycle
            "edge_reconnects_total",
            "edge_rebootstrap_triggered_total",
            // poison-pill ladder (item 8, ADR-0040)
            "edge_poison_retries_total",
            "configd_edge_poison_pill_total",
            "configd_edge_poison_pill_terminal_total");

    private static final List<String> FAN_OUT_SERIES = List.of(
            // stream mechanics
            "edge_fanout_notify_batches_total",
            "edge_fanout_notify_batch_size_count", // histogram sample line
            "edge_fanout_heartbeats_total",
            "edge_fanout_snapshot_transfers_total",
            "edge_fanout_slow_consumer_warnings_total",
            // demotions (per-reason suffix encoding — priced deviation)
            "edge_fanout_demotions_queue_overflow_total",
            "edge_fanout_demotions_ack_lag_total",
            "edge_fanout_demotions_gap_total",
            "edge_fanout_demotions_transport_block_total",
            "edge_fanout_demotions_other_total",
            // session closes (per-reason suffix encoding)
            "edge_fanout_sessions_closed_server_shutdown_total",
            "edge_fanout_sessions_closed_protocol_violation_total",
            "edge_fanout_sessions_closed_frame_corrupt_total",
            "edge_fanout_sessions_closed_bad_wire_version_total",
            "edge_fanout_sessions_closed_auth_fail_total",
            "edge_fanout_sessions_closed_gap_unrecoverable_total",
            "edge_fanout_sessions_closed_quarantined_total",
            "edge_fanout_sessions_closed_transport_gone_total",
            "edge_fanout_sessions_closed_other_total",
            // admission + subscribe-time decision (C3)
            "edge_fanout_sessions_refused_total",
            "edge_fanout_subscribe_tail_total",
            "edge_fanout_subscribe_snapshot_first_total",
            "edge_fanout_subscribe_horizon_distance",
            // checklist items 4 + 6
            "edge_fanout_queue_depth",
            "edge_fanout_connected_subscribers",
            // checklist item 5: C4 governor counters (CT-27..CT-30) + per-state gauges
            "edge_fanout_slow_transitions_total",
            "edge_fanout_quarantines_total",
            "edge_fanout_reconnects_refused_total",
            "edge_fanout_readmissions_total",
            "edge_fanout_unhealthy_total",
            "edge_fanout_consumer_state_healthy",
            "edge_fanout_consumer_state_slow",
            "edge_fanout_consumer_state_catchup",
            "edge_fanout_consumer_state_quarantined",
            "edge_fanout_consumer_state_unhealthy");

    // ------------------------------------------------------------------------------
    // Edge-process half (EdgeNodeMetrics over a real EdgeClientCore + test clock)
    // ------------------------------------------------------------------------------

    private static final class TestClock implements Clock {
        long timeMs = 1_000_000L;
        @Override public long currentTimeMillis() { return timeMs; }
        @Override public long nanoTime() { return timeMs * 1_000_000L; }
        void advance(long ms) { timeMs += ms; }
    }

    /** One fully wired edge-process metrics surface (the EdgeNodeMain wiring, minus IO). */
    private record EdgeHalf(TestClock clock, MetricsRegistry registry,
                            EdgeNodeMetrics metrics, EdgeClientCore core) {

        static EdgeHalf create() {
            TestClock clock = new TestClock();
            MetricsRegistry registry = new MetricsRegistry();
            EdgeNodeMetrics metrics = new EdgeNodeMetrics(registry);
            EdgeClientCore core = new EdgeClientCore(clock, null, metrics.implausibleCounter(),
                    StrongReadKeyClass.DEFAULT, EdgeClientCore.FrameSink.NONE,
                    EdgeClientCore.DEFAULT_HEARTBEAT_MS, EdgeClientCore.DEFAULT_SILENCE_FACTOR);
            metrics.bind(core);
            return new EdgeHalf(clock, registry, metrics, core);
        }

        void apply(long seq, String key, String value) {
            ConfigDelta delta = new ConfigDelta(seq - 1, seq,
                    List.of(new ConfigMutation.Put(key, value.getBytes(StandardCharsets.UTF_8))));
            core.onFrame(new EdgeFrame.Notify(List.of(
                    new CommitNotification(seq, clock.timeMs, delta))));
        }

        String scrape() {
            return new PrometheusExporter(registry).export();
        }
    }

    @Test
    void edgeProcessSeriesAreAllPresentOnFirstScrape() {
        // RR-013: the very first scrape — zero traffic, zero events — already exports
        // every edge-process series in the named list, each as a real sample line.
        assertAllSeriesPresent(EdgeHalf.create().scrape(), EDGE_PROCESS_SERIES);
    }

    @Test
    void stalenessGaugesAndViolationCounterTrackTheLiveCore() {
        EdgeHalf edge = EdgeHalf.create();

        // Boot truth (no frontier yet): DISCONNECTED at scrape time, not a frozen copy.
        String boot = edge.scrape();
        assertEquals(3, seriesValue(boot, "edge_staleness_state"), "boot = DISCONNECTED ordinal");
        assertTrue(seriesValue(boot, "edge_staleness_ms") > 30_000, "boot staleness past threshold");

        // A committed delta at wall-now heals the frontier → CURRENT, staleness 0.
        edge.apply(1, "a", "1");
        edge.metrics.syncFromCore(edge.core, null);
        String current = edge.scrape();
        assertEquals(0, seriesValue(current, "edge_staleness_state"), "CURRENT ordinal");
        assertEquals(0, seriesValue(current, "edge_staleness_ms"), "frontier at wall-now");
        assertEquals(0, seriesValue(current, "configd_edge_staleness_violation_total"));

        // Cross the STALE threshold (>500ms): the gauge moves at scrape time and the
        // CT-04 entry counter increments exactly once for the entry.
        edge.clock.advance(600);
        edge.metrics.syncFromCore(edge.core, null);
        String stale = edge.scrape();
        assertEquals(1, seriesValue(stale, "edge_staleness_state"), "STALE ordinal");
        assertEquals(600, seriesValue(stale, "edge_staleness_ms"));
        assertEquals(1, seriesValue(stale, "configd_edge_staleness_violation_total"),
                "the STALE entry moved the CT-04 series at the Prometheus surface");
    }

    @Test
    void cursorLagGaugeExportsTheCoreLag() {
        EdgeHalf edge = EdgeHalf.create();
        // A heartbeat advertising latestSeq=7 against cursor 0 → lag 7 (CT-38 item 3:
        // latestSeq − last_applied_seq; exact semantics pinned in EdgeNodeMetricsTest).
        edge.core.onFrame(new EdgeFrame.Heartbeat(7, edge.clock.timeMs));
        edge.metrics.syncFromCore(edge.core, null);
        assertEquals(7, seriesValue(edge.scrape(), "edge_cursor_lag"));
    }

    @Test
    void poisonPillCounterHandlesMoveTheExportedSeries() {
        // The handles EdgeNodeMetrics hands to PoisonPillPolicy (ADR-0040) are wired to
        // the exported names — the ladder's semantics are CT-33's, pinned in edge-cache.
        EdgeHalf edge = EdgeHalf.create();
        edge.metrics.poisonRetriesCounter().increment(2);
        edge.metrics.poisonPillCounter().increment();
        edge.metrics.poisonTerminalCounter().increment();
        String out = edge.scrape();
        assertEquals(2, seriesValue(out, "edge_poison_retries_total"));
        assertEquals(1, seriesValue(out, "configd_edge_poison_pill_total"));
        assertEquals(1, seriesValue(out, "configd_edge_poison_pill_terminal_total"));
    }

    // ------------------------------------------------------------------------------
    // Server-side fan-out half (RegistryFanOutSessionMetrics over its own registry)
    // ------------------------------------------------------------------------------

    @Test
    void fanOutSeriesAreAllPresentOnFirstScrape() {
        MetricsRegistry registry = new MetricsRegistry();
        new RegistryFanOutSessionMetrics(registry); // constructor registers everything (RR-013)
        assertAllSeriesPresent(new PrometheusExporter(registry).export(), FAN_OUT_SERIES);
    }

    @Test
    void fanOutCallbackSeamsMoveTheChecklistSeries() {
        MetricsRegistry registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics m = new RegistryFanOutSessionMetrics(registry);

        // Item 4: queue depth is the process-level HIGH-WATER gauge (priced deviation).
        m.onQueueDepth(7);
        m.onQueueDepth(3);

        // Item 6: connected-subscriber count follows connect/disconnect.
        m.onSubscriberConnected();
        m.onSubscriberConnected();
        m.onSubscriberDisconnected();

        // Item 5: one movement per CT-27..CT-30 transition family.
        m.onSlowTransition();                                   // CT-27
        m.onQuarantine();                                       // CT-28
        m.onSessionClosed("QUARANTINED");                       // CT-28 (close half)
        m.onReconnectRefused();                                 // CT-29
        m.onReadmission();                                      // CT-29 (readmit half)
        m.onUnhealthy();                                        // CT-30
        m.onConsumerStates(4, 3, 2, 1, 5);

        // Stream-mechanics spot-check (one batch through the FanOutSessionMetrics seam).
        m.onNotifyBatch(5, 100);
        m.onDemotion(DemotionEvent.REASON_ACK_LAG);

        String out = new PrometheusExporter(registry).export();
        assertEquals(7, seriesValue(out, "edge_fanout_queue_depth"), "high-water, not last");
        assertEquals(1, seriesValue(out, "edge_fanout_connected_subscribers"));
        assertEquals(1, seriesValue(out, "edge_fanout_slow_transitions_total"));
        assertEquals(1, seriesValue(out, "edge_fanout_quarantines_total"));
        assertEquals(1, seriesValue(out, "edge_fanout_sessions_closed_quarantined_total"));
        assertEquals(1, seriesValue(out, "edge_fanout_reconnects_refused_total"));
        assertEquals(1, seriesValue(out, "edge_fanout_readmissions_total"));
        assertEquals(1, seriesValue(out, "edge_fanout_unhealthy_total"));
        assertEquals(4, seriesValue(out, "edge_fanout_consumer_state_healthy"));
        assertEquals(3, seriesValue(out, "edge_fanout_consumer_state_slow"));
        assertEquals(2, seriesValue(out, "edge_fanout_consumer_state_catchup"));
        assertEquals(1, seriesValue(out, "edge_fanout_consumer_state_quarantined"));
        assertEquals(5, seriesValue(out, "edge_fanout_consumer_state_unhealthy"));
        assertEquals(1, seriesValue(out, "edge_fanout_notify_batches_total"));
        assertEquals(1, seriesValue(out, "edge_fanout_notify_batch_size_count"));
        assertEquals(1, seriesValue(out, "edge_fanout_demotions_ack_lag_total"));
    }

    // ------------------------------------------------------------------------------
    // Helpers: exact sample-line assertions (a `# TYPE` comment or a longer name with
    // the same prefix must never satisfy a presence check).
    // ------------------------------------------------------------------------------

    private static void assertAllSeriesPresent(String export, List<String> series) {
        List<String> missing = series.stream()
                .filter(name -> export.lines().noneMatch(line -> line.startsWith(name + " ")))
                .toList();
        assertTrue(missing.isEmpty(),
                "series missing from the first scrape (RR-013 eager registration): "
                        + missing + "\n--- scrape ---\n" + export);
    }

    private static long seriesValue(String export, String name) {
        return export.lines()
                .filter(line -> line.startsWith(name + " "))
                .map(line -> Long.parseLong(line.substring(name.length() + 1).trim()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "series not exported: " + name + "\n--- scrape ---\n" + export));
    }
}

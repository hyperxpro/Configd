package io.configd.edge.node;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.edge.EdgeClientCore;
import io.configd.edge.StrongReadKeyClass;
import io.configd.observability.MetricsRegistry;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeNodeMetricsTest {

    static final class TestClock implements Clock {
        long timeMs = 1_000_000L;
        @Override public long currentTimeMillis() { return timeMs; }
        @Override public long nanoTime() { return timeMs * 1_000_000L; }
        void advance(long ms) { timeMs += ms; }
    }

    private TestClock clock;
    private MetricsRegistry registry;
    private EdgeNodeMetrics metrics;
    private EdgeClientCore core;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        registry = new MetricsRegistry();
        metrics = new EdgeNodeMetrics(registry);
        core = new EdgeClientCore(clock, null, metrics.implausibleCounter(),
                StrongReadKeyClass.DEFAULT, EdgeClientCore.FrameSink.NONE,
                EdgeClientCore.DEFAULT_HEARTBEAT_MS, EdgeClientCore.DEFAULT_SILENCE_FACTOR);
        metrics.bind(core);
    }

    private void apply(long seq, String key, String value) {
        ConfigDelta delta = new ConfigDelta(seq - 1, seq,
                List.of(new ConfigMutation.Put(key, value.getBytes(StandardCharsets.UTF_8))));
        core.onFrame(new EdgeFrame.Notify(List.of(
                new CommitNotification(seq, clock.timeMs, delta))));
    }

    @Test
    void everySeriesIsRegisteredEagerly() {
        // A fresh registry snapshot must already contain every series this class can ever
        // write — no metric blinks into existence after its first event.
        var names = registry.snapshot().metrics().keySet();
        for (String name : List.of(
                "edge.applied", "edge.gaps", "edge.snapshots_applied", "edge.verify_rejections",
                "edge.reads", "edge.read_refusals.cursor_behind", "edge.read_refusals.strong_read",
                "edge.reconnects", "configd.edge.staleness_violation", "edge.rebootstrap_triggered",
                "edge.staleness.implausible", "edge.cursor_lag",
                "edge.staleness_ms", "edge.staleness_state")) {
            assertTrue(names.contains(name), "eagerly registered: " + name);
        }
    }

    @Test
    void deltaPumpTracksCoreCountersExactly() {
        apply(1, "a", "1");
        apply(2, "a", "2");
        // A gap: from=5 != current 2.
        apply(6, "x", "y");
        metrics.syncFromCore(core, null);
        assertEquals(2, registry.counter("edge.applied").get());
        assertEquals(1, registry.counter("edge.gaps").get());

        // Pump is idempotent across syncs (deltas, not absolutes).
        metrics.syncFromCore(core, null);
        assertEquals(2, registry.counter("edge.applied").get());
        assertEquals(1, registry.counter("edge.gaps").get());

        apply(3, "a", "3");
        metrics.syncFromCore(core, null);
        assertEquals(3, registry.counter("edge.applied").get());
    }

    @Test
    void snapshotAndVerifyRejectionPumpsTrackTheCore() {
        // A snapshot cutover (BEGIN/CHUNK/END through the real reassembly path).
        io.configd.store.HamtMap<String, io.configd.store.VersionedValue> data =
                io.configd.store.HamtMap.<String, io.configd.store.VersionedValue>empty()
                        .put("a", new io.configd.store.VersionedValue(
                                "1".getBytes(StandardCharsets.UTF_8), 5, 5));
        byte[] body = io.configd.distribution.wire.EdgeSnapshotCodec.serialize(
                new io.configd.store.ConfigSnapshot(data, 5, 5));
        var chunks = io.configd.distribution.wire.EdgeSnapshotCodec.chunk(body, 4096);
        core.onFrame(new EdgeFrame.SnapshotBegin(5, chunks.size(), body.length));
        for (var c : chunks) {
            core.onFrame(c);
        }
        core.onFrame(new EdgeFrame.SnapshotEnd(5));

        // A SIGNED delta on a verifier-less core -> rejected fail-closed.
        ConfigDelta signed = new ConfigDelta(5, 6,
                List.of(new ConfigMutation.Put("b", "2".getBytes(StandardCharsets.UTF_8))),
                new byte[64]);
        core.onFrame(new EdgeFrame.Notify(List.of(
                new CommitNotification(6, clock.timeMs, signed))));

        metrics.syncFromCore(core, null);
        assertEquals(1, registry.counter("edge.snapshots_applied").get(),
                "edge_snapshots_applied_total pumps from the core");
        assertEquals(1, registry.counter("edge.verify_rejections").get(),
                "edge_verify_rejections_total pumps from the core");
        assertEquals(0, registry.counter("edge.gaps").get(),
                "a verification rejection is not a gap");
    }

    @Test
    void stalenessGaugesReadTheLiveTracker() {
        // At boot (no frontier) the gauges must show the truth at SCRAPE time — a frozen
        // copy would lie on an idle/disconnected edge.
        var boot = registry.snapshot().metrics();
        assertTrue(boot.get("edge.staleness_ms").value() > 30_000,
                "boot staleness is past the DISCONNECTED threshold");
        assertEquals(3, boot.get("edge.staleness_state").value(), "DISCONNECTED ordinal");

        apply(1, "a", "1");
        var current = registry.snapshot().metrics();
        assertEquals(0, current.get("edge.staleness_ms").value(), "frontier at wall-now");
        assertEquals(0, current.get("edge.staleness_state").value(), "CURRENT ordinal");
    }

    @Test
    void cursorLagGaugeMirrorsTheCore() {
        core.onFrame(new EdgeFrame.Heartbeat(7, clock.timeMs));
        metrics.syncFromCore(core, null);
        assertEquals(7L, registry.snapshot().metrics().get("edge.cursor_lag").value(),
                "latestSeq 7 − cursor 0 = lag 7");
    }

    @Test
    void staleTransitionIsCountedOncePerEntry() {
        apply(1, "a", "1"); // frontier at wall-now → CURRENT
        metrics.syncFromCore(core, null);
        assertEquals(0, metrics.stalenessViolationsCount());

        clock.advance(600); // → STALE
        metrics.syncFromCore(core, null);
        assertEquals(1, metrics.stalenessViolationsCount(), "transition into STALE counts");
        metrics.syncFromCore(core, null);
        assertEquals(1, metrics.stalenessViolationsCount(), "staying STALE does not re-count");

        apply(2, "a", "2"); // frontier heals → CURRENT
        metrics.syncFromCore(core, null);
        clock.advance(600); // → STALE again
        metrics.syncFromCore(core, null);
        assertEquals(2, metrics.stalenessViolationsCount(), "each re-entry counts");
    }

    @Test
    void stalePassThroughToDegradedCountsOnce() {
        apply(1, "a", "1");
        metrics.syncFromCore(core, null);
        // A single observed jump CURRENT → DEGRADED still counts exactly one STALE+ entry.
        clock.advance(6_000);
        metrics.syncFromCore(core, null);
        assertEquals(1, metrics.stalenessViolationsCount());
    }

    @Test
    void disconnectedTransitionFiresTheRebootstrapSeamOnce() {
        AtomicInteger hookRuns = new AtomicInteger();
        apply(1, "a", "1"); // CURRENT
        metrics.syncFromCore(core, hookRuns::incrementAndGet);
        assertEquals(0, metrics.rebootstrapTriggeredCount());

        clock.advance(31_000); // past the 30s DISCONNECTED threshold
        metrics.syncFromCore(core, hookRuns::incrementAndGet);
        assertEquals(1, metrics.rebootstrapTriggeredCount(), "the CT-06 trigger fired");
        assertEquals(1, hookRuns.get(), "the C3 orchestration seam ran");

        metrics.syncFromCore(core, hookRuns::incrementAndGet);
        assertEquals(1, hookRuns.get(), "staying DISCONNECTED does not re-trigger");
    }

    @Test
    void bootStateDoesNotCountAsTransition() {
        // bind() seeded the baseline with the boot state (DISCONNECTED — no frontier yet):
        // process start is the initial bootstrap, not a staleness violation or a re-bootstrap.
        AtomicInteger hookRuns = new AtomicInteger();
        metrics.syncFromCore(core, hookRuns::incrementAndGet);
        assertEquals(0, metrics.stalenessViolationsCount());
        assertEquals(0, metrics.rebootstrapTriggeredCount());
        assertEquals(0, hookRuns.get());
    }

    @Test
    void unknownRefusalReasonIsRejectedLoudly() {
        metrics.onReadRefused(EdgeNodeMetrics.REASON_CURSOR_BEHIND);
        metrics.onReadRefused(EdgeNodeMetrics.REASON_STRONG_READ);
        assertEquals(1, registry.counter("edge.read_refusals.cursor_behind").get());
        assertEquals(1, registry.counter("edge.read_refusals.strong_read").get());
        assertThrows(IllegalArgumentException.class, () -> metrics.onReadRefused("mystery"),
                "a new refusal reason must be registered, never silently dropped");
    }
}

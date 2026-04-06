package io.configd.server;

import io.configd.observability.ConfigdMetrics;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.observability.SafeLog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H-009 (iter-2) regression — ensures that the tick-loop unhandled-throwable
 * path bumps the {@code configd_tick_loop_throwable_total{class}} counter
 * family AND emits a structured SEVERE log record (not a stderr
 * {@code printStackTrace}).
 *
 * <p>Drives {@link ConfigdServer#handleTickLoopThrowable(Throwable, ConfigdMetrics)}
 * directly so the test does not need to start a full server / scheduler.
 * The logic under test is the catch-block body lifted into a testable
 * method during the H-009 fix.
 */
class TickLoopThrowableHandlerTest {

    /** Captures every record emitted by the ConfigdServer logger. */
    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<>();

        @Override public void publish(LogRecord record) { records.add(record); }
        @Override public void flush() {}
        @Override public void close() throws SecurityException {}
    }

    private Logger serverLogger;
    private CapturingHandler handler;
    private Level previousLevel;
    private boolean previousUseParentHandlers;

    @BeforeEach
    void installHandler() {
        serverLogger = Logger.getLogger(ConfigdServer.class.getName());
        previousLevel = serverLogger.getLevel();
        previousUseParentHandlers = serverLogger.getUseParentHandlers();
        serverLogger.setLevel(Level.ALL);
        serverLogger.setUseParentHandlers(false);
        handler = new CapturingHandler();
        handler.setLevel(Level.ALL);
        serverLogger.addHandler(handler);
    }

    @AfterEach
    void removeHandler() {
        serverLogger.removeHandler(handler);
        serverLogger.setLevel(previousLevel);
        serverLogger.setUseParentHandlers(previousUseParentHandlers);
    }

    @Test
    void tickLoopThrowableIncrementsCounterAndLogsSevere() {
        MetricsRegistry registry = new MetricsRegistry();
        ConfigdMetrics metrics = new ConfigdMetrics(registry, () -> 0L);

        RuntimeException boom = new RuntimeException("synthetic tick failure");

        ConfigdServer.handleTickLoopThrowable(boom, metrics);

        // (a) Counter family was incremented for the bucketed class label.
        String expectedLabel = SafeLog.cardinalityGuard("RuntimeException");
        String registryName = ConfigdMetrics.NAME_TICK_LOOP_THROWABLE_BASE + "." + expectedLabel;
        var snapshot = registry.snapshot().metrics();
        assertNotNull(snapshot.get(registryName),
                "expected counter " + registryName + " to be registered after one throw");
        assertEquals(1L, snapshot.get(registryName).value(),
                "tick-loop throwable counter should be incremented exactly once");

        // (b) A SEVERE log record was published with the Throwable attached
        //     (so the JUL formatter prints the stack trace) and the message
        //     identifies both the simple class name and the bucketed label.
        List<LogRecord> severe = handler.records.stream()
                .filter(r -> r.getLevel().equals(Level.SEVERE))
                .toList();
        assertEquals(1, severe.size(),
                "exactly one SEVERE record should be emitted per tick-loop throwable");
        LogRecord rec = severe.get(0);
        assertSame(boom, rec.getThrown(),
                "SEVERE record must carry the original throwable so JUL prints stack trace");
        assertTrue(rec.getMessage().contains("tick loop unhandled throwable"),
                "log message must identify itself: " + rec.getMessage());
        assertTrue(rec.getMessage().contains("class=RuntimeException"),
                "log message must identify the throwable class: " + rec.getMessage());
        assertTrue(rec.getMessage().contains("bucket=" + expectedLabel),
                "log message must include the cardinality-bounded bucket label: " + rec.getMessage());
    }

    @Test
    void distinctThrowableClassesGetDistinctCounterSeries() {
        MetricsRegistry registry = new MetricsRegistry();
        ConfigdMetrics metrics = new ConfigdMetrics(registry, () -> 0L);

        ConfigdServer.handleTickLoopThrowable(new RuntimeException("a"), metrics);
        ConfigdServer.handleTickLoopThrowable(new IllegalStateException("b"), metrics);
        ConfigdServer.handleTickLoopThrowable(new IllegalStateException("c"), metrics);

        String runtimeName = ConfigdMetrics.NAME_TICK_LOOP_THROWABLE_BASE + "."
                + SafeLog.cardinalityGuard("RuntimeException");
        String illegalName = ConfigdMetrics.NAME_TICK_LOOP_THROWABLE_BASE + "."
                + SafeLog.cardinalityGuard("IllegalStateException");

        var snap = registry.snapshot().metrics();
        assertEquals(1L, snap.get(runtimeName).value());
        assertEquals(2L, snap.get(illegalName).value());

        // PrometheusExporter must surface the new counter family under
        // the sanitized name (dots → underscores) plus the _total suffix.
        PrometheusExporter exporter = new PrometheusExporter(registry);
        String text = exporter.export();
        assertTrue(text.contains("configd_tick_loop_throwable_"),
                "exporter must surface tick_loop_throwable counter family\n" + text);
    }

    @Test
    void nullThrowableDoesNotNpe() {
        MetricsRegistry registry = new MetricsRegistry();
        ConfigdMetrics metrics = new ConfigdMetrics(registry, () -> 0L);

        // Defensive: a null throwable should not crash the tick loop —
        // the silent-failure mode H-009 closes is "tick-loop dies on
        // any unhandled throwable", and the handler itself must not be
        // a new source of throws.
        assertDoesNotThrow(() -> ConfigdServer.handleTickLoopThrowable(null, metrics));
    }
}

package io.configd.server;

import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.observability.ConfigdMetrics;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.SafeLog;
import io.configd.raft.AppendEntriesRequest;
import io.configd.replication.MultiRaftDriver;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftMessage;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftTransport;
import io.configd.raft.StateMachine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression - the inbound Raft routing task must SURFACE an escaping
 * Throwable (counter + structured SEVERE log) instead of letting the executor swallow it.
 * <p>
 * The tick-loop fix covered only the tick lambda; the inbound-routing path
 * ({@code raftExecutor.execute(() -> driver.routeMessage(...))}) had no try/catch, so a
 * Throwable from message handling - e.g. a disk write failing during
 * {@code applyCommitted -> apply} on a follower - went to the executor's default uncaught
 * handler (stderr, invisible to log aggregation), with NO metric and NO ack: a mute zombie.
 * <p>
 * Two legs: the handler's observable side-effects (driven directly, like
 * {@code TickLoopThrowableHandlerTest}); and the wiring - a follower whose response
 * {@code transport.send} throws makes {@code routeMessage} throw, and the production
 * {@link ConfigdServer#raftInboundHandler} must catch + surface it while the executor
 * keeps serving subsequent messages.
 */
class InboundRoutingThrowableHandlerTest {

    private static final int GROUP = 0;

    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<>();
        @Override public void publish(LogRecord record) { records.add(record); }
        @Override public void flush() {}
        @Override public void close() {}
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
    void inboundRoutingThrowableIncrementsCounterAndLogsSevere() {
        MetricsRegistry registry = new MetricsRegistry();
        ConfigdMetrics metrics = new ConfigdMetrics(registry, () -> 0L);

        ConfigdServer.handleInboundRoutingThrowable(
                new RuntimeException("synthetic disk fault during apply"), metrics);

        String expectedLabel = SafeLog.cardinalityGuard("RuntimeException");
        String registryName = ConfigdMetrics.NAME_INBOUND_ROUTING_THROWABLE_BASE + "." + expectedLabel;
        assertNotNull(registry.snapshot().metrics().get(registryName),
                "exporter must surface the inbound_routing_throwable counter family");
        assertTrue(handler.records.stream().anyMatch(r -> r.getLevel() == Level.SEVERE),
                "a SEVERE record must be emitted (not a stderr printStackTrace)");
    }

    private static final class ThrowingTransport implements RaftTransport {
        @Override public void send(NodeId target, RaftMessage message) {
            throw new RuntimeException("synthetic transport/IO failure on response");
        }
    }

    private static final class NoopStateMachine implements StateMachine {
        @Override public long apply(long index, long term, byte[] command) { return StateMachine.NON_MUTATING; }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    @Test
    void inboundHandlerCatchesRouteMessageThrowableAndExecutorSurvives() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        ConfigdMetrics metrics = new ConfigdMetrics(registry, () -> 0L);

        // A follower whose response send throws: handleAppendEntries always ends by
        // sending an AppendEntriesResponse, so routeMessage throws for any inbound append.
        RaftConfig config = RaftConfig.of(NodeId.of(1), Set.of(NodeId.of(2), NodeId.of(3)));
        RaftNode node = new RaftNode(config, new RaftLog(), new ThrowingTransport(),
                new NoopStateMachine(), new java.util.Random(7));
        MultiRaftDriver driver = new MultiRaftDriver(NodeId.of(1), Clock.system());
        driver.addGroup(GROUP, node);

        AtomicInteger ran = new AtomicInteger();
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
            ran.incrementAndGet();
            r.run();
        }, "raft-test"));
        try {
            BiConsumer<NodeId, RaftMessage> inbound =
                    ConfigdServer.raftInboundHandler(driver, GROUP, exec, metrics);

            // Poison message: the follower will try to send a response -> transport throws.
            inbound.accept(NodeId.of(2),
                    new AppendEntriesRequest(1L, NodeId.of(2), 0L, 0L, List.of(), 0L));
            // A following message must still be processed (executor not permanently dead).
            inbound.accept(NodeId.of(2),
                    new AppendEntriesRequest(1L, NodeId.of(2), 0L, 0L, List.of(), 0L));

            // Barrier: FIFO single-thread executor guarantees the two routing tasks ran.
            CountDownLatch done = new CountDownLatch(1);
            exec.execute(done::countDown);
            assertTrue(done.await(5, TimeUnit.SECONDS), "executor must keep serving tasks");

            String label = SafeLog.cardinalityGuard("RuntimeException");
            String registryName = ConfigdMetrics.NAME_INBOUND_ROUTING_THROWABLE_BASE + "." + label;
            var counter = registry.snapshot().metrics().get(registryName);
            assertNotNull(counter,
                    "inbound routing Throwable must be surfaced as a counter (RR-008) — "
                            + "PRE-FIX the executor swallows it: no metric, no ack, mute zombie");
            assertEquals(2L, counter.value(),
                    "both poison messages' Throwables must be counted");
        } finally {
            exec.shutdownNow();
        }
    }
}

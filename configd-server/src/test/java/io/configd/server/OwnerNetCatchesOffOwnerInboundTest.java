package io.configd.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.observability.InvariantMonitor;
import io.configd.observability.MetricsRegistry;
import io.configd.raft.AppendEntriesRequest;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftMessage;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.raft.RaftTransport;
import io.configd.raft.StateMachine;
import io.configd.replication.MultiRaftDriver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Test the tester" for the owner-executor pool wiring.
 *
 * <p>{@link RaftInboundMarshallingTest} proves the inbound seam marshals; {@link
 * RaftNodeConcurrencyStressTest} proves the {@code assertOwnerThread()} tripwire catches an off-owner
 * touch in isolation. This test closes the gap: the net must still CATCH a missed marshalling hop
 * when consensus is routed through the owner executor and {@code bindOwnerThread()} activates the
 * guard in PRODUCTION mode (metric, not throw). A guard that stops firing after a refactor is worse
 * than no guard.
 *
 * <p>Wiring mirrors {@link ConfigdServer}: a real {@link InvariantMonitor} in PRODUCTION mode
 * ({@code testMode=false}) is the {@code RaftNode.InvariantChecker}, so a tripwire fire increments
 * the {@code invariant.violation.raft_owner_thread} counter (exported as
 * {@code invariant_violation_raft_owner_thread_total}) and keeps serving - exactly the live server
 * behaviour. The node's owner is bound to a dedicated owner executor (the N=1 {@code
 * ownerExecutor(gid)} stand-in), and inbound delivery goes through the PRODUCTION {@link
 * ConfigdServer#raftInboundHandler} seam.
 *
 * <ul>
 *   <li><b>{@link #correctlyMarshalledInboundDoesNotTripTheNet()}</b> - with the hop intact, routing
 *       lands on the owner thread -> the guard stays silent -> counter 0 (the clean-path half).</li>
 *   <li><b>{@link #offOwnerInboundTripsTheNetUnderTheNewWiring()}</b> - the routing is invoked
 *       off-owner (the missed hop), so {@code handleMessage} touches the {@code RaftNode} on a
 *       foreign thread and the tripwire fires -> counter &gt;= 1. This is the same red a scratch
 *       edit removing {@code raftExecutor.execute(...)} in {@code raftInboundHandler} produces,
 *       captured here deterministically.</li>
 * </ul>
 */
class OwnerNetCatchesOffOwnerInboundTest {

    @TempDir
    Path tempDir;

    private static final int GROUP = 0;
    private static final String OWNER_THREAD = "raft-owner-net-test";
    private static final String VIOLATION_METRIC = "invariant.violation.raft_owner_thread";

    private static ScheduledExecutorService ownerExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, OWNER_THREAD);
            t.setDaemon(true);
            return t;
        });
    }

    /** A stale-term AppendEntries - a leader (term >= 1) rejects it and replies, so it exercises state. */
    private static AppendEntriesRequest staleAppendEntries() {
        return new AppendEntriesRequest(0L, NodeId.of(2), 0L, 0L, List.of(), 0L);
    }

    private static final class NoopTransport implements RaftTransport {
        @Override public void send(NodeId target, RaftMessage message) { }
    }

    private static final class NoopStateMachine implements StateMachine {
        @Override public long apply(long index, long term, byte[] command) { return StateMachine.NON_MUTATING; }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    /**
     * Builds a single-node leader whose owner is bound to {@code owner} and whose checker is the
     * PRODUCTION-mode {@link InvariantMonitor} (records the violation metric, does not throw) - the
     * wired-server discipline. Binds the owner as the FIRST task on the owner executor (the bind
     * must land before anything else touches the node), then self-elects on it (single-node), all
     * on-owner so the bind/elect path is clean.
     */
    private static RaftNode buildBoundLeader(MetricsRegistry registry,
                                             ScheduledExecutorService owner) throws Exception {
        InvariantMonitor monitor = new InvariantMonitor(registry, false); // false = production: metric, no throw
        RaftNode.InvariantChecker checker = monitor::check;
        NodeId id = NodeId.of(1);
        RaftConfig config = RaftConfig.of(id, Set.of());
        RaftNode node = new RaftNode(config, new RaftLog(), new NoopTransport(),
                new NoopStateMachine(), new java.util.Random(42),
                io.configd.common.Storage.inMemory(), checker);
        owner.submit(() -> {
            node.bindOwnerThread();
            for (int i = 0; i < 400; i++) node.tick();
        }).get(5, TimeUnit.SECONDS);
        assertEquals(RaftRole.LEADER, node.role(), "single-node cluster should self-elect to LEADER");
        return node;
    }

    private static MultiRaftDriver driverFor(RaftNode node) {
        MultiRaftDriver driver = new MultiRaftDriver(NodeId.of(1), Clock.system());
        driver.addGroup(GROUP, node);
        return driver;
    }

    private static long counter(MetricsRegistry registry) {
        return registry.counter(VIOLATION_METRIC).get();
    }

    @Test
    void correctlyMarshalledInboundDoesNotTripTheNet() throws Exception {
        ScheduledExecutorService owner = ownerExecutor();
        try {
            MetricsRegistry registry = new MetricsRegistry();
            RaftNode node = buildBoundLeader(registry, owner);
            MultiRaftDriver driver = driverFor(node);

            // The PRODUCTION seam, targeting the SAME executor the owner is bound to -> on-owner.
            var inbound = ConfigdServer.raftInboundHandler(driver, GROUP, owner);

            CountDownLatch routed = new CountDownLatch(1);
            // Drive an inbound message from a FOREIGN thread (as the transport accept loop would).
            Thread foreign = new Thread(() -> {
                inbound.accept(NodeId.of(2), staleAppendEntries());
                routed.countDown();
            }, "foreign-inbound");
            foreign.start();
            assertTrue(routed.await(5, TimeUnit.SECONDS), "inbound should be dispatched");
            // Drain the owner so the marshalled routeMessage actually executed on it.
            owner.submit(() -> { }).get(5, TimeUnit.SECONDS);

            assertEquals(0L, counter(registry),
                    "correctly-marshalled inbound must NOT trip raft_owner_thread (routing ran on the owner)");
        } finally {
            owner.shutdownNow();
        }
    }

    @Test
    void offOwnerInboundTripsTheNetUnderTheNewWiring() throws Exception {
        ScheduledExecutorService owner = ownerExecutor();
        try {
            MetricsRegistry registry = new MetricsRegistry();
            RaftNode node = buildBoundLeader(registry, owner);
            MultiRaftDriver driver = driverFor(node);

            // THE MISSED HOP (the scratch-break shape): route the inbound message INLINE on a foreign
            // thread, i.e. driver.routeMessage(...) WITHOUT owner.execute(...) - exactly what removing
            // `raftExecutor.execute` in ConfigdServer.raftInboundHandler would do. node.handleMessage()
            // then touches the RaftNode off its bound owner, so assertOwnerThread() must fire.
            CountDownLatch routed = new CountDownLatch(1);
            Thread foreign = new Thread(() -> {
                driver.routeMessage(GROUP, staleAppendEntries());
                routed.countDown();
            }, "foreign-inbound-no-hop");
            foreign.start();
            assertTrue(routed.await(5, TimeUnit.SECONDS), "off-owner routing should run");

            assertTrue(counter(registry) >= 1,
                    "R-01' net REGRESSION: an off-owner inbound RaftNode touch did NOT trip "
                            + "raft_owner_thread under the pool wiring — the guard stopped firing");
        } finally {
            owner.shutdownNow();
        }
    }

    /**
     * A fully-wired {@link ConfigdServer} - owner bound on owner[0], the consensus tick on owner[0],
     * a metrics scrape + a linearizable read exercising the read double-hop onto the owner - must
     * produce ZERO off-owner violations. The net is ACTIVE in production
     * (prod-mode {@code InvariantMonitor}), so a latent missed hop at any wired call site would leave
     * the {@code invariant_violation_raft_owner_thread_total} counter present/non-zero in the live
     * {@code /metrics} exposition. This is the full-boot regression guard complementing the seam-level
     * halves above.
     */
    @Test
    @Timeout(30)
    void wiredServerCleanRunHasNoOffOwnerViolation() throws Exception {
        ServerConfig config = ServerConfig.parse(new String[]{
                "--node-id", "0",
                "--data-dir", tempDir.toString(),
                "--peers", "1,2",
                "--api-port", "0"
        });
        ConfigdServer server = ConfigdServer.start(config);
        try {
            // Let the owner bind + several consensus ticks + a metrics scrape run on owner[0].
            Thread.sleep(300);
            // Exercise the linearizable-read double-hop onto the owner (a no-quorum node returns 503,
            // but the readIndex()/completeRead() still run ON the owner - a missed hop would trip).
            int port = apiPort(server);
            HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + port
                                    + "/v1/config/anything?consistency=linearizable"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            String metrics = scrapeMetrics(server, port);
            long violations = counterValue(metrics, "invariant_violation_raft_owner_thread_total");
            // -1 = the counter line is absent (never fired) - the desired clean state; 0 also clean.
            assertTrue(violations <= 0,
                    "Verification D: the live server's raft_owner_thread violation counter must be 0 "
                            + "(absent) on a clean run — a non-zero value means a wired call site reads "
                            + "RaftNode off its owner under the active net. Value=" + violations);
        } finally {
            server.shutdown();
        }
    }

    private static int apiPort(ConfigdServer server) {
        // The admin server is Netty-based; use the public bound-port accessor rather than
        // reflecting into a transport-specific internal field.
        return server.apiPort();
    }

    private static String scrapeMetrics(ConfigdServer server, int port) throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/metrics"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "/metrics should be public in the minimal config");
        return resp.body();
    }

    /** Reads a Prometheus counter value ("name value"); -1 if the line is absent. */
    private static long counterValue(String exposition, String metricName) {
        for (String line : exposition.split("\n")) {
            String t = line.trim();
            if (t.startsWith(metricName + " ")) {
                String[] parts = t.split("\\s+");
                return (long) Double.parseDouble(parts[parts.length - 1]);
            }
        }
        return -1;
    }
}

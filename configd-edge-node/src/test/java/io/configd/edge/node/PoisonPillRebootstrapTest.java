package io.configd.edge.node;

import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.edge.EdgeClientCore;
import io.configd.edge.PoisonPillPolicy;
import io.configd.observability.MetricsRegistry;
import io.configd.server.ConfigdServer;
import io.configd.server.ServerConfig;
import io.configd.server.fanout.FanOutServer;
import io.configd.server.fanout.RegistryFanOutSessionMetrics;
import io.configd.store.SigningKeyStore;
import io.configd.store.VerifyKeyExporter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The poison-pill ladder at PROCESS level, over the real wire: a real {@link ConfigdServer},
 * a real {@link EdgeNodeMain}, and an apply fault injected through the TEST-ONLY
 * {@link EdgeClientCore.ApplyFaultInjector} seam (Configd stores opaque bytes — no real delta
 * can be made to throw; everything downstream of the injected throw, including every reconnect
 * cycle, resubscribe cursor, snapshot and process-exit decision, is production code).
 *
 * <ul>
 *   <li><b>Recovery leg:</b> against a fan-out endpoint whose bounded queue is small
 *       (queueFrames 4), the post-quarantine cursor-0 resubscribe gets SNAPSHOT_FIRST
 *       (backlog > queue): the snapshot covers the poison seq, the edge converges, the
 *       process LIVES.</li>
 *   <li><b>Terminal leg ("if the snapshot itself fails to apply"):</b> every snapshot cutover
 *       throws; the bounded retries exhaust, the quarantine forces a re-bootstrap, and ITS
 *       snapshot fails too — the edge can neither advance nor re-bootstrap, so the injected
 *       terminal action (production: {@code System.exit(EXIT_POISON_TERMINAL)}) runs after
 *       {@code configd_edge_poison_pill_terminal_total} is emitted. Never a hot loop.
 *       (The TAIL-redelivered-poison corner is pinned at core level in edge-cache's
 *       {@code PoisonPillRebootstrapTest} — a cursor-0 resubscribe always snapshots when
 *       data exists, so that corner is defense-in-depth over this wire.)</li>
 * </ul>
 */
@Timeout(120)
class PoisonPillRebootstrapTest {

    private static final Pattern COMMITTED_SEQ = Pattern.compile("seq=(\\d+)");
    private static final Duration DEADLINE = Duration.ofSeconds(45);

    @TempDir
    Path tempDir;

    private ConfigdServer server;
    private FanOutServer smallQueueEndpoint;
    private EdgeNodeMain edge;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void tearDown() {
        if (edge != null) {
            edge.shutdown();
        }
        if (smallQueueEndpoint != null) {
            smallQueueEndpoint.close();
        }
        if (server != null) {
            server.shutdown();
        }
    }

    /** Volatile-field injector: the edge session thread reads what the test thread set. */
    static final class SeqPoison implements EdgeClientCore.ApplyFaultInjector {
        volatile long poisonSeq = -1;
        volatile boolean poisonAllApplies;
        volatile boolean poisonSnapshots;

        @Override
        public void beforeApply(long seq) {
            if (poisonAllApplies || seq == poisonSeq) {
                throw new IllegalStateException("injected apply defect at seq " + seq);
            }
        }

        @Override
        public void beforeSnapshotLoad(long snapshotSeq) {
            if (poisonSnapshots) {
                throw new IllegalStateException(
                        "injected snapshot apply defect at seq " + snapshotSeq);
            }
        }
    }

    @Test
    void quarantineForcesSnapshotRebootstrapThatHealsPastThePoisonSeq() throws Exception {
        startServer();
        // A separate fan-out endpoint over the SAME seams (same commit-notification source)
        // with queueFrames=4: a cursor-0 subscriber whose backlog exceeds 4 gets
        // SNAPSHOT_FIRST — the forced re-bootstrap genuinely snapshots at process level.
        smallQueueEndpoint = new FanOutServer(
                new InetSocketAddress("127.0.0.1", 0), null,
                server.commitNotificationSource(), server.replaySource(),
                new FanOutConfig(4, 80, 64, 262_144, 8_192L, 250L, 5L, 1_048_576),
                FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES,
                new RegistryFanOutSessionMetrics(new MetricsRegistry()), Clock.system());
        smallQueueEndpoint.start();

        AtomicInteger terminalRuns = new AtomicInteger();
        edge = startEdge("edge-poison-heal", smallQueueEndpoint.localPort(),
                terminalRuns::incrementAndGet);
        String serverBase = "http://127.0.0.1:" + server.apiPort();
        SeqPoison poison = new SeqPoison();
        edge.core().setApplyFaultInjectorForTest(poison);

        // Build a backlog > queueFrames(4) and converge.
        long last = 0;
        for (int i = 1; i <= 6; i++) {
            last = putCommitted(serverBase, "svc/p" + i, "v" + i);
        }
        final long converged = last;
        await("edge converged on the backlog", () -> edge.core().currentVersion() >= converged);

        // Poison the NEXT seq, then commit it.
        poison.poisonSeq = edge.core().currentVersion() + 1;
        long poisonedSeq = putCommitted(serverBase, "svc/poisoned", "the-poison-payload");
        assertEquals(poison.poisonSeq, poisonedSeq, "fixture: the poisoned seq is the next commit");

        // Bounded retries → quarantine → forced cursor-0 resubscribe → SNAPSHOT_FIRST
        // (backlog 7 > 4) → the snapshot covers the poison seq → recovery, process LIVES.
        await("snapshot re-bootstrap healed past the poison",
                () -> edge.core().currentVersion() >= poisonedSeq);

        PoisonPillPolicy policy = edge.core().poisonPolicy();
        // The quarantine release is the session thread's last step of the cutover —
        // await it (polling the version alone can race the single-writer mid-method).
        await("quarantine released on recovery", () -> policy.quarantinedSeq() == -1);
        assertEquals(1, policy.quarantines(), "exactly one quarantine");
        assertEquals(0, policy.terminals(), "healed — never terminal");
        assertEquals(0, terminalRuns.get(), "the process must NOT exit on a healed poison");
        assertTrue(edge.core().snapshotsApplied() >= 1, "the heal was a snapshot, not a delta");
        assertFalse(edge.core().isTerminal());

        // The poisoned key's value arrived VIA THE SNAPSHOT (the delta never re-applied).
        HttpResponse<String> read = get("http://127.0.0.1:" + edge.apiPort()
                + "/v1/config/svc/poisoned");
        assertEquals(200, read.statusCode());
        assertEquals("the-poison-payload", read.body());

        // And the chain continues normally past the healed wedge.
        long next = putCommitted(serverBase, "svc/after", "alive");
        final long nextSeq = next;
        await("post-recovery convergence", () -> edge.core().currentVersion() >= nextSeq);
    }

    @Test
    void snapshotThatFailsToApplyIsTerminalFailLoudNotAHotLoop() throws Exception {
        startServer();
        AtomicInteger terminalRuns = new AtomicInteger();

        // Start the edge against the still-EMPTY server: its initial SUBSCRIBE(0) gets
        // TAIL on the empty ring, so it idles — the injector is installed before ANY data
        // exists and nothing can race the installation.
        edge = startEdge("edge-poison-term", server.fanOutServer().localPort(),
                terminalRuns::incrementAndGet);
        SeqPoison poison = new SeqPoison();
        poison.poisonAllApplies = true; // every delta apply throws
        poison.poisonSnapshots = true;  // every snapshot cutover throws
        edge.core().setApplyFaultInjectorForTest(poison);

        // The first commit starts the ladder: TAIL apply fails -> resubscribe(0) ->
        // SNAPSHOT_FIRST (data exists) -> snapshot fails -> bounded retries exhaust ->
        // quarantine -> forced re-bootstrap -> ITS snapshot fails too -> TERMINAL:
        // the injected exit action runs (production: System.exit non-zero).
        String serverBase = "http://127.0.0.1:" + server.apiPort();
        putCommitted(serverBase, "svc/t", "v1");
        await("terminal action invoked (production: System.exit non-zero)",
                () -> terminalRuns.get() > 0);

        PoisonPillPolicy policy = edge.core().poisonPolicy();
        assertEquals(1, terminalRuns.get(), "terminal runs exactly once — latched, no hot loop");
        assertEquals(1, policy.quarantines());
        assertEquals(1, policy.terminals());
        assertTrue(edge.core().isTerminal());
        assertEquals(0, edge.core().currentVersion(),
                "nothing ever applied — no divergence, the edge died at version 0");

        // The terminal metric was emitted BEFORE the exit action (scrape-visible).
        String metrics = get("http://127.0.0.1:" + edge.apiPort() + "/metrics").body();
        assertTrue(metrics.lines().anyMatch(l ->
                        l.startsWith("configd_edge_poison_pill_terminal_total ") && l.endsWith(" 1")),
                "configd_edge_poison_pill_terminal_total must read 1:\n"
                        + metrics.lines().filter(l -> l.contains("poison"))
                                .reduce("", (a, b) -> a + b + "\n"));
        assertTrue(metrics.lines().anyMatch(l ->
                        l.startsWith("configd_edge_poison_pill_total ") && l.endsWith(" 1")));
    }

    // Fixture and helpers

    private void startServer() throws Exception {
        Path signingKey = tempDir.resolve("signing-key.bin");
        SigningKeyStore.loadOrCreate(signingKey);
        server = ConfigdServer.start(new ServerConfig(
                NodeId.of(0), tempDir.resolve("server-data"), Set.of(), "127.0.0.1",
                0, 0, null, null, null, null, Map.of(), signingKey, Set.of("secure/"), 0));
    }

    private EdgeNodeMain startEdge(String id, int fanOutPort, Runnable terminalAction)
            throws Exception {
        Path verifyKey = tempDir.resolve(id + "-verify.der");
        VerifyKeyExporter.export(tempDir.resolve("signing-key.bin"), verifyKey);
        EdgeNodeConfig cfg = new EdgeNodeConfig(id,
                List.of(InetSocketAddress.createUnresolved("127.0.0.1", fanOutPort)),
                0, tempDir.resolve(id + "-data"), verifyKey, List.of(),
                null, null, null, 50L, EdgeNodeConfig.DEFAULT_HEARTBEAT_SILENCE_FACTOR,
                EdgeNodeConfig.DEFAULT_POISON_MAX_RETRIES);
        return EdgeNodeMain.start(cfg, null, terminalAction);
    }

    private void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // transient during churn — keep polling
            }
            Thread.onSpinWait();
        }
        fail("condition not reached within deadline: " + what);
    }

    private HttpResponse<String> get(String url) {
        try {
            return http.send(HttpRequest.newBuilder().uri(URI.create(url)).GET()
                            .timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("GET " + url + " failed: " + e.getMessage(), e);
        }
    }

    private long putCommitted(String base, String key, String body) throws Exception {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                                .uri(URI.create(base + "/v1/config/" + key))
                                .timeout(DEADLINE)
                                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    Matcher m = COMMITTED_SEQ.matcher(resp.body());
                    if (m.find()) {
                        return Long.parseLong(m.group(1));
                    }
                }
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IllegalStateException("write '" + key + "' not committed in time", last);
    }
}

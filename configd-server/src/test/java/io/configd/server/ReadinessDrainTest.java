package io.configd.server;

import io.configd.api.HealthService;
import io.configd.common.NodeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B7 (Class-B): readiness is SHARD-AWARE and honors a shutdown drain flag.
 * <ul>
 *   <li>A group-0-blind readiness LIES at N&gt;1: a node that lost quorum on shards 1..N-1 would still
 *       report READY. Readiness must reflect EVERY hosted group.</li>
 *   <li>On SIGTERM {@code shutdown()} flips a drain flag BEFORE closing anything, so readiness reports
 *       NOT-ready first and an LB stops routing while in-flight work drains.</li>
 * </ul>
 * The decision is unit-tested directly through {@link ConfigdServer#evaluateReadiness} (a stub leader
 * source + a draining flag - no server, no RaftNode), and the N=1 healthy path is proven end-to-end
 * against a real single-node server's {@code /health/ready}.
 */
@Timeout(40)
class ReadinessDrainTest {

    @TempDir
    Path tempDir;

    private static final NodeId L = NodeId.of(1); // a stand-in "known leader"

    // ---- shard-aware decision (unit) ----

    @Test
    void singleShardWithLeaderIsReady() {
        // N=1 byte-identical: one hosted group with a known leader -> ready, named "raft-leader".
        HealthService.CheckResult r = ConfigdServer.evaluateReadiness(false, new int[]{0}, gid -> L);
        assertTrue(r.healthy());
        assertEquals("raft-leader", r.name());
    }

    @Test
    void singleShardWithoutLeaderIsNotReady() {
        HealthService.CheckResult r = ConfigdServer.evaluateReadiness(false, new int[]{0}, gid -> null);
        assertFalse(r.healthy());
        assertEquals("raft-leader", r.name());
    }

    @Test
    void allShardsWithLeaderIsReady() {
        HealthService.CheckResult r =
                ConfigdServer.evaluateReadiness(false, new int[]{0, 1, 2}, gid -> L);
        assertTrue(r.healthy());
    }

    @Test
    void anyShardWithoutLeaderMakesNodeNotReady() {
        // Group 0 has a leader but shard 2 lost quorum -> the whole node is NOT ready, and names shard 2.
        IntFunction<NodeId> leaderOf = gid -> gid == 2 ? null : L;
        HealthService.CheckResult r =
                ConfigdServer.evaluateReadiness(false, new int[]{0, 1, 2}, leaderOf);
        assertFalse(r.healthy(), "a single quorum-less shard must fail the node's readiness at N>1");
        assertTrue(r.detail().contains("2"), "the reason must name the offending shard: " + r.detail());
    }

    @Test
    void drainingIsNotReadyEvenWhenAllShardsHaveLeader() {
        // Draining is checked FIRST: even a fully-healthy node reports NOT-ready once shutdown begins.
        HealthService.CheckResult r =
                ConfigdServer.evaluateReadiness(true, new int[]{0, 1, 2}, gid -> L);
        assertFalse(r.healthy());
        assertEquals("draining", r.name());
    }

    // ---- end-to-end: a real single-node server reports ready once it self-elects ----

    @Test
    void singleNodeServerBecomesReadyOverHttp() throws Exception {
        ServerConfig config = ServerConfig.parse(new String[]{
                "--node-id", "1", "--data-dir", tempDir.resolve("ready").toString(),
                "--peers", "", "--api-port", "0"
        });
        ConfigdServer server = ConfigdServer.start(config);
        try {
            int port = server.apiPort();
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest ready = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/health/ready")).GET().build();

            // A zero-state single node self-elects; /health/ready then flips 503 -> 200.
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            int status = -1;
            while (System.nanoTime() < deadline) {
                status = http.send(ready, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status == 200) {
                    break;
                }
                Thread.sleep(100);
            }
            assertEquals(200, status,
                    "the shard-aware readiness check must report READY once the single node has a leader");
        } finally {
            server.shutdown();
        }
    }

    /**
     * The drain guarantee end-to-end: {@code shutdown()} must flip readiness to 503 BEFORE it closes the
     * listeners, so an LB stops routing while in-flight work drains (no dropped request on restart). Proven
     * by requesting an explicit quiet window and observing a 503 from {@code /health/ready} while the HTTP
     * listener is STILL OPEN (a real 503 response, not a refused connection) - i.e. the flip precedes the
     * close. The unit tests above prove the draining precedence; this proves the FLIP-then-CLOSE ordering.
     */
    @Test
    void shutdownFlipsReadinessToDrainingBeforeClosingListeners() throws Exception {
        // Request a quiet window (the pom sets it to 0 module-wide; override for THIS test). At N=1 an
        // explicit value is honoured (the default would be 0), so the window exists to observe the flip.
        String saved = System.getProperty("configd.shutdown.drainQuietMs");
        long quietMs = 2000L;
        System.setProperty("configd.shutdown.drainQuietMs", Long.toString(quietMs));
        ConfigdServer server = null;
        Thread shutdownThread = null;
        try {
            ServerConfig config = ServerConfig.parse(new String[]{
                    "--node-id", "1", "--data-dir", tempDir.resolve("drainflip").toString(),
                    "--peers", "", "--api-port", "0"
            });
            server = ConfigdServer.start(config);
            int port = server.apiPort();
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest ready = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/health/ready")).GET().build();

            // Precondition: the single node self-elects and reports READY.
            long readyDeadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            int status = -1;
            while (System.nanoTime() < readyDeadline) {
                status = http.send(ready, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status == 200) {
                    break;
                }
                Thread.sleep(100);
            }
            assertEquals(200, status, "precondition: the single node must be READY before drain");

            // Trigger shutdown() on a background thread: it sets draining=true, then pauses drainQuietMs
            // BEFORE closing the listener. The main thread polls /health/ready DURING that window and must
            // see a 503 while the listener is still accepting connections.
            final ConfigdServer serverToDrain = server;
            shutdownThread = new Thread(serverToDrain::shutdown, "test-drain-shutdown");
            shutdownThread.start();

            boolean saw503WhileOpen = false;
            long windowDeadline = System.nanoTime() + Duration.ofMillis(quietMs - 500L).toNanos();
            while (System.nanoTime() < windowDeadline) {
                try {
                    int s = http.send(ready, HttpResponse.BodyHandlers.discarding()).statusCode();
                    if (s == 503) {
                        saw503WhileOpen = true; // a 503 with the listener still open => the flip preceded close
                        break;
                    }
                } catch (IOException listenerClosed) {
                    break; // the listener already closed - the open-window flip was missed
                }
                Thread.sleep(50);
            }
            assertTrue(saw503WhileOpen,
                    "readiness must flip to 503 (draining) while the listener is STILL OPEN - i.e. shutdown() "
                            + "sets draining before it closes the listeners (no in-flight drop on restart)");
        } finally {
            // The background thread OWNS the shutdown; join it (the quiet-period sleep + teardown are
            // bounded) rather than calling shutdown() concurrently. If it never started (early failure),
            // shut down here.
            if (shutdownThread != null) {
                shutdownThread.join(Duration.ofSeconds(30).toMillis());
            } else if (server != null) {
                server.shutdown();
            }
            if (saved == null) {
                System.clearProperty("configd.shutdown.drainQuietMs");
            } else {
                System.setProperty("configd.shutdown.drainQuietMs", saved);
            }
        }
    }
}

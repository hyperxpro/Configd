package io.configd.server;

import io.configd.api.HealthService;
import io.configd.common.NodeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

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
}

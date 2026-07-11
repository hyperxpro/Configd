package io.configd.server;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.configd.store.CommandCodec;
import io.configd.store.ConfigStateMachine;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The runtime invariant safety net must be ON in a RUNNING server (not NOOP) AND
 * observable - a real invariant violation must increment a NAMED counter visible in the live
 * Prometheus {@code /metrics} exposition. A net that fires into an unwatched log is no better
 * than NOOP.
 *
 * <p>Discrimination: reverting the {@link ConfigdServer} wiring back to {@code InvariantChecker.NOOP}
 * leaves the counter absent (value 0) and this test fails.
 */
@Timeout(20)
class InvariantNetMetricTest {

    @TempDir
    Path tempDir;

    private ServerConfig minimalConfig(Path dataDir) {
        return ServerConfig.parse(new String[]{
                "--node-id", "0",
                "--data-dir", dataDir.toString(),
                "--peers", "1,2",
                "--api-port", "0"
        });
    }

    @Test
    void perKeyOrderViolationIsObservedInLivePrometheusExposition() throws Exception {
        ConfigdServer server = ConfigdServer.start(minimalConfig(tempDir));
        try {
            ConfigStateMachine sm = server.stateMachine();

            // Establish key "k" at version 1.
            sm.apply(1L, 1L, CommandCodec.encodePut("k", new byte[]{1}));

            // Inject the precondition a real corruption (e.g. a sequence-counter rewind from a stale
            // non-volatile read racing the apply thread, or a snapshot-restore bug) would create:
            // rewind the counter so the next PUT to "k" computes a version <= the existing version.
            // The invariant detection, metric increment, and Prometheus exposition that follow are
            // all the REAL production path through the wired checker - only the precondition is injected.
            Field scf = ConfigStateMachine.class.getDeclaredField("sequenceCounter");
            scf.setAccessible(true);
            scf.setLong(sm, 0L);

            // This PUT violates per_key_order (new version 1 not > existing 1). In production mode
            // the wired checker records the violation (metric + SEVERE log) and keeps serving (no
            // throw); the per_key_order metric is incremented BEFORE the store write, so a later
            // store rejection of the non-monotonic version does not affect the assertion.
            try {
                sm.apply(2L, 1L, CommandCodec.encodePut("k", new byte[]{2}));
            } catch (RuntimeException expectedStoreRejection) {
                // store may reject the non-monotonic version; the violation metric is already recorded.
            }

            String metrics = scrapeMetrics(server);
            assertTrue(metrics.contains("invariant_violation_per_key_order_total"),
                    "R-02: the per_key_order violation counter must appear in the live /metrics "
                            + "exposition (the runtime net must be WIRED, not NOOP). Body:\n" + metrics);
            long value = counterValue(metrics, "invariant_violation_per_key_order_total");
            assertTrue(value >= 1,
                    "R-02: per_key_order violation counter must be >= 1 in the live exposition, was " + value);
        } finally {
            server.shutdown();
        }
    }

    /** Scrapes the running server's /metrics endpoint (public in the minimal, no-auth config). */
    private static String scrapeMetrics(ConfigdServer server) throws Exception {
        // Admin server is Netty-based; the public bound-port accessor is transport-agnostic.
        int port = server.apiPort();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/metrics"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(resp.statusCode() == 200,
                "/metrics should be public in the minimal config; got " + resp.statusCode());
        return resp.body();
    }

    /** Reads the value of a Prometheus counter line ("name value"); -1 if absent. */
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

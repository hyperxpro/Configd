package io.configd.edge.node;

import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.distribution.fanout.FanOutConfig;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * C5 / CT-24 at PROCESS level: a REAL {@link EdgeNodeMain} joins a live
 * {@link ConfigdServer} MID-write-storm (a writer thread driving sequential HTTP PUTs
 * with per-write-UNIQUE values — the double-apply tripwire: any duplicate application
 * with different effect resurrects an older unique value and fails the final byte
 * sweep), and must converge with cursor-exactness observable at the HTTP surface.
 *
 * <ul>
 *   <li><b>Production-defaults leg:</b> the server's own {@code --edge-port} endpoint
 *       ({@code FanOutConfig.defaults()}). Pins: SNAPSHOT_FIRST was the mechanism
 *       (cursor-0 join against a populated store), EXACTLY one transfer, zero gaps /
 *       zero backward refusals / zero verify rejections over ordered loopback TCP, tail
 *       deltas applied after the cutover, final store byte-equal to the server's, and
 *       the consistent-refusal discipline on every in-flight read (each response is
 *       either the cursor-behind refusal or a version ≥ the cursor — never stale data
 *       under a cursor).</li>
 *   <li><b>Wide-window leg (big store / small chunks / small transport queue):</b> a
 *       second fan-out endpoint over the SAME ADR-0034 seams (the EdgeFailoverTest
 *       recipe) with {@code snapshotChunkBytes=2048} and {@code transportQueueFrames=8}
 *       against a pre-populated multi-hundred-KiB store — hundreds of chunks through an
 *       8-frame bounded queue, so the snapshot transfer is genuinely PACED against
 *       transport backpressure over a real socket while the storm commits into the
 *       window. HARD non-vacuity (screen C5-2): at least one write commits between edge
 *       start and cutover completion. This leg rides the C5 backpressure fix in
 *       {@code FanOutSessionCore.performSnapshotTransfer} — pre-fix, the burst emission
 *       tore the transfer on the first full-queue chunk and the bootstrap could never
 *       complete ({@code BootstrapSnapshotBackpressureTest} pins the core-level
 *       red/green).</li>
 * </ul>
 *
 * <p>Deadline-polling only — no sleep-as-synchronization; the {@link Timeout} is hang
 * detection, generous for the throttled 2-vCPU box (RR-094).
 */
@Timeout(180)
class EdgeBootstrapUnderSustainedWritesProcessTest {

    private static final Pattern COMMITTED_SEQ = Pattern.compile("seq=(\\d+)");
    private static final Duration WRITE_DEADLINE = Duration.ofSeconds(30);
    private static final Duration POLL_DEADLINE = Duration.ofSeconds(60);
    private static final int STORM_KEYS = 8;

    @TempDir
    Path tempDir;

    private ConfigdServer server;
    private FanOutServer endpointB;
    private EdgeNodeMain edge;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void tearDown() {
        if (edge != null) {
            edge.shutdown();
        }
        if (endpointB != null) {
            endpointB.close();
        }
        if (server != null) {
            server.shutdown();
        }
    }

    // -----------------------------------------------------------------------
    // The write storm (one sequential writer; unique value per write)
    // -----------------------------------------------------------------------

    /** A sustained sequential write storm on a virtual thread; per-write-unique values. */
    private final class WriteStorm {
        final AtomicInteger written = new AtomicInteger();
        final AtomicLong lastSeq = new AtomicLong();
        /** key → the last value written to it (the writer is sequential, so exact). */
        final Map<String, String> expected = new ConcurrentHashMap<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile boolean stop;
        private final Thread thread;

        WriteStorm(String serverBase) {
            this.thread = Thread.ofVirtual().name("c5-write-storm").start(() -> {
                try {
                    int i = 0;
                    while (!stop) {
                        String key = "svc/k" + (i % STORM_KEYS);
                        String value = "w-" + i; // unique per write
                        long seq = putCommitted(serverBase, key, value);
                        expected.put(key, value);
                        lastSeq.set(seq);
                        written.incrementAndGet();
                        i++;
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }

        void stopAndJoin() throws InterruptedException {
            stop = true;
            thread.join(30_000);
            assertTrue(!thread.isAlive(), "write storm must stop");
            if (failure.get() != null) {
                fail("write storm failed: " + failure.get());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Leg 1 — production defaults
    // -----------------------------------------------------------------------

    @Test
    void freshEdgeJoinsMidStormAndConvergesWithExactCutoverAtProductionDefaults()
            throws Exception {
        server = startServer();
        Path verifyKey = exportVerifyKey();
        String serverBase = "http://127.0.0.1:" + server.apiPort();

        WriteStorm storm = new WriteStorm(serverBase);
        await("a populated store exists before the join (BEFORE-phase writes)",
                () -> storm.written.get() >= 30);

        // The zero-state edge joins MID-storm against the production-default endpoint.
        int writtenAtEdgeStart = storm.written.get();
        edge = startEdge("edge-c5-defaults", verifyKey, server.fanOutServer().localPort());
        String edgeBase = "http://127.0.0.1:" + edge.apiPort();

        // While bootstrapping, the HTTP surface must hold the consistent-refusal line:
        // every response to a read at the writer's current frontier is either the
        // cursor-behind refusal or a served version ≥ that cursor — NEVER stale-under-cursor.
        long deadline = System.nanoTime() + POLL_DEADLINE.toNanos();
        while (edge.core().snapshotsApplied() == 0) {
            assertTrue(System.nanoTime() < deadline, "bootstrap transfer must land");
            long cursor = storm.lastSeq.get();
            if (cursor > 0) {
                HttpResponse<String> r = get(edgeBase + "/v1/config/svc/k0",
                        EdgeHttpServer.HDR_CURSOR, String.valueOf(cursor));
                if (r.statusCode() == 200) {
                    long version = Long.parseLong(r.headers()
                            .firstValue(EdgeHttpServer.HDR_VERSION).orElseThrow());
                    assertTrue(version >= 0, "served version parses");
                    long served = Long.parseLong(r.headers()
                            .firstValue(EdgeHttpServer.HDR_CURSOR).orElseThrow());
                    assertTrue(served >= cursor,
                            "NEVER serve below the cursor during bootstrap: served cursor "
                                    + served + " < " + cursor);
                } else {
                    assertEquals(404, r.statusCode(), "in-flight reads refuse, never error");
                    assertEquals("cursor-behind", r.headers()
                                    .firstValue(EdgeHttpServer.HDR_REFUSED).orElse("missing"),
                            "the consistent refusal during bootstrap");
                }
            }
        }
        assertTrue(storm.written.get() >= writtenAtEdgeStart,
                "the storm kept writing through the join");

        // AFTER-phase: keep the storm going past the cutover, then stop and fence.
        int targetAfter = storm.written.get() + 30;
        await("AFTER-phase writes flow past the cutover",
                () -> storm.written.get() >= targetAfter);
        storm.stopAndJoin();
        long fenceSeq = putCommitted(serverBase, "svc/fence", "fence-1");

        // Convergence at the HTTP surface: served at the fence cursor, refusal-or-≥cursor
        // on every poll along the way (pollUntilServed enforces it per response).
        HttpResponse<String> fenced = pollUntilServed(edgeBase, "svc/fence", fenceSeq);
        assertEquals("fence-1", fenced.body());

        // Byte sweep: every storm key serves EXACTLY the last written unique value — a
        // double-apply with different effect (an older w-i resurrecting) fails here.
        for (int k = 0; k < STORM_KEYS; k++) {
            String key = "svc/k" + k;
            String want = storm.expected.get(key);
            if (want == null) {
                continue; // storm may not have reached every key — fine
            }
            HttpResponse<String> r = pollUntilServed(edgeBase, key, fenceSeq);
            assertEquals(want, r.body(),
                    "key " + key + " must serve the LAST written unique value");
        }

        // Cutover exactness, observable on the core counters over ordered loopback TCP:
        assertEquals(io.configd.distribution.wire.EdgeFrame.Mode.SNAPSHOT_FIRST,
                edge.core().mode(),
                "a zero-state join against a populated store IS the snapshot bootstrap");
        assertEquals(1, edge.core().snapshotsApplied(),
                "EXACTLY one transfer (no re-sends at production ack-lag, no tears)");
        assertEquals(0, edge.core().gapsDetected(),
                "no seq skipped across the cutover (exact cutover cursor)");
        assertEquals(0, edge.core().backwardSnapshotsRefused(), "no regression attempt");
        assertEquals(0, edge.core().verifyRejections(), "the signed chain verified");
        assertTrue(edge.core().appliedCount() > 0,
                "tail deltas applied AFTER the snapshot (snapshot+tail, not snapshot-only)");
        assertEquals(fenceSeq, edge.core().currentVersion(),
                "the edge store version equals the last committed seq");
    }

    // -----------------------------------------------------------------------
    // Leg 2 — wide transfer window: big store / small chunks / small queue
    // -----------------------------------------------------------------------

    @Test
    void wideWindowBootstrapPacedByBoundedTransportStraddlesWritesAndConvergesExactly()
            throws Exception {
        server = startServer();
        Path verifyKey = exportVerifyKey();
        String serverBase = "http://127.0.0.1:" + server.apiPort();

        // BIG store: ~96 × 8 KiB ≈ 760 KiB snapshot body ⇒ ~370 chunks at 2 KiB —
        // through an 8-frame transport queue: the transfer is genuinely paced.
        String bulkValue = "B".repeat(8_192);
        for (int i = 0; i < 96; i++) {
            putCommitted(serverBase, "bulk/k" + i, bulkValue + "-" + i);
        }

        // The wide-window endpoint over the SAME ADR-0034 seams (EdgeFailoverTest recipe).
        FanOutConfig smallChunks = new FanOutConfig(
                256, 80, 64, 262_144, 8_192L, 250L, 5L, 2_048);
        endpointB = new FanOutServer(
                new InetSocketAddress("127.0.0.1", 0), null,
                server.commitNotificationSource(), server.replaySource(),
                smallChunks, /* transportQueueFrames */ 8,
                new RegistryFanOutSessionMetrics(new MetricsRegistry()), Clock.system());
        endpointB.start();

        WriteStorm storm = new WriteStorm(serverBase);
        await("storm warmed up", () -> storm.written.get() >= 10);

        int writtenAtEdgeStart = storm.written.get();
        edge = startEdge("edge-c5-wide", verifyKey, endpointB.localPort());
        String edgeBase = "http://127.0.0.1:" + edge.apiPort();

        await("the paced multi-hundred-chunk transfer must complete (the C5 "
                        + "backpressure fix: pre-fix this bootstrap could never finish)",
                () -> edge.core().snapshotsApplied() >= 1);
        int writtenAtCutover = storm.written.get();
        assertTrue(writtenAtCutover > writtenAtEdgeStart,
                "HARD non-vacuity (C5-2): writes must commit DURING the wide transfer "
                        + "window; storm advanced " + writtenAtEdgeStart + " → "
                        + writtenAtCutover);

        // Keep writing past the cutover, then fence and judge.
        int targetAfter = storm.written.get() + 20;
        await("AFTER-phase writes", () -> storm.written.get() >= targetAfter);
        storm.stopAndJoin();
        long fenceSeq = putCommitted(serverBase, "svc/fence", "fence-2");
        HttpResponse<String> fenced = pollUntilServed(edgeBase, "svc/fence", fenceSeq);
        assertEquals("fence-2", fenced.body());

        // The snapshot CONTENT arrived intact through the paced transfer (sampled), and
        // the storm keys serve their last unique values (no double-apply divergence).
        for (int i = 0; i < 96; i += 8) {
            HttpResponse<String> r = pollUntilServed(edgeBase, "bulk/k" + i, fenceSeq);
            assertEquals(bulkValue + "-" + i, r.body(), "bulk/k" + i + " byte-exact");
        }
        for (int k = 0; k < STORM_KEYS; k++) {
            String key = "svc/k" + k;
            String want = storm.expected.get(key);
            if (want != null) {
                assertEquals(want, pollUntilServed(edgeBase, key, fenceSeq).body(), key);
            }
        }

        assertEquals(io.configd.distribution.wire.EdgeFrame.Mode.SNAPSHOT_FIRST,
                edge.core().mode());
        // 1 on a fast box; 2 is legitimate on a loaded runner: the post-cutover CURSOR_ACK
        // can lag one RTT behind a server whose test-scaled ack-lag threshold then fires a
        // redundant (idempotent, forward) re-demote envelope — the deliberate C1(a)
        // self-healing design, signed off as c5-signoff-review F3. First seen as a CI
        // flake on 1c39615 (gate-1, expected <1> but was <2>). A TORN/RESTARTED transfer
        // cannot hide here: it yields gaps/refusals/content divergence, all pinned to
        // exact zeros below — snapshotsApplied is NOT the torn-transfer discriminator.
        int paced = edge.core().snapshotsApplied();
        assertTrue(paced >= 1 && paced <= 2,
                "one paced transfer (+ at most one F3 redundant re-demote envelope), got "
                        + paced);
        assertEquals(0, edge.core().gapsDetected(),
                "the tail resumed at exactly S+1 after the paced transfer");
        assertEquals(0, edge.core().backwardSnapshotsRefused());
        assertEquals(0, edge.core().verifyRejections());
        assertEquals(fenceSeq, edge.core().currentVersion());
    }

    // -----------------------------------------------------------------------
    // Fixture (the EdgeNodeIntegrationTest pattern)
    // -----------------------------------------------------------------------

    private ConfigdServer startServer() throws Exception {
        Path signingKey = tempDir.resolve("signing-key.bin");
        SigningKeyStore.loadOrCreate(signingKey);
        ServerConfig cfg = new ServerConfig(
                NodeId.of(0), tempDir.resolve("server-data"), Set.of(), "127.0.0.1",
                0, 0, null, null, null, null,
                Map.of(), signingKey, Set.of("secure/"), 0);
        return ConfigdServer.start(cfg);
    }

    private Path exportVerifyKey() throws Exception {
        Path verifyKey = tempDir.resolve("verify-key.der");
        VerifyKeyExporter.export(tempDir.resolve("signing-key.bin"), verifyKey);
        return verifyKey;
    }

    private EdgeNodeMain startEdge(String id, Path verifyKey, int... edgePorts) {
        List<InetSocketAddress> endpoints = java.util.Arrays.stream(edgePorts)
                .mapToObj(p -> InetSocketAddress.createUnresolved("127.0.0.1", p))
                .toList();
        EdgeNodeConfig cfg = new EdgeNodeConfig(id, endpoints, 0,
                tempDir.resolve(id + "-data"), verifyKey, List.of(),
                null, null, null, 50L, EdgeNodeConfig.DEFAULT_HEARTBEAT_SILENCE_FACTOR,
                EdgeNodeConfig.DEFAULT_POISON_MAX_RETRIES);
        return EdgeNodeMain.start(cfg);
    }

    // -----------------------------------------------------------------------
    // Helpers (deadline-polling; no sleep-as-sync)
    // -----------------------------------------------------------------------

    /** Polls until served at the cursor; every non-200 must be the consistent refusal. */
    private HttpResponse<String> pollUntilServed(String edgeBase, String key, long seq)
            throws Exception {
        long deadline = System.nanoTime() + POLL_DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> r = get(edgeBase + "/v1/config/" + key,
                    EdgeHttpServer.HDR_CURSOR, String.valueOf(seq));
            if (r.statusCode() == 200) {
                long version = Long.parseLong(
                        r.headers().firstValue(EdgeHttpServer.HDR_VERSION).orElseThrow());
                assertTrue(version >= 0, "served version parses");
                long served = Long.parseLong(
                        r.headers().firstValue(EdgeHttpServer.HDR_CURSOR).orElseThrow());
                assertTrue(served >= seq,
                        "NEVER serve below the cursor: served cursor " + served
                                + " < cursor " + seq);
                return r;
            }
            assertEquals(404, r.statusCode(), "catch-up responses are refusals, never errors");
            assertEquals("cursor-behind",
                    r.headers().firstValue(EdgeHttpServer.HDR_REFUSED).orElse("missing"),
                    "a cursor-ahead read during catch-up must carry the refusal header");
            Thread.onSpinWait();
        }
        fail("edge did not serve " + key + " at cursor " + seq + " within the deadline");
        return null;
    }

    private void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + POLL_DEADLINE.toNanos();
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

    private HttpResponse<String> get(String url, String... headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url)).GET().timeout(Duration.ofSeconds(10));
            for (int i = 0; i < headers.length; i += 2) {
                b.header(headers[i], headers[i + 1]);
            }
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("GET " + url + " failed: " + e.getMessage(), e);
        }
    }

    private long putCommitted(String base, String key, String body) {
        long deadline = System.nanoTime() + WRITE_DEADLINE.toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(base + "/v1/config/" + key))
                        .timeout(WRITE_DEADLINE)
                        .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    Matcher m = COMMITTED_SEQ.matcher(resp.body());
                    if (m.find()) {
                        return Long.parseLong(m.group(1));
                    }
                }
                // 503/504 — retry
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted writing '" + key + "'", e);
            }
        }
        throw new IllegalStateException("write '" + key + "' not committed in time", last);
    }
}

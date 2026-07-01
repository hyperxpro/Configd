package io.configd.server.fanout;

import io.configd.common.NodeId;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.server.ConfigdServer;
import io.configd.server.ServerConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end integration test for the live {@link FanOutServer} over plaintext loopback: a real
 * single-node {@link ConfigdServer} (empty peers -> self-elects), a raw {@link EdgeProtocolClient}
 * speaking protocol v1, and committed writes driven through the real HTTP API. Verifies the full
 * C1 server path: SUBSCRIBE->SUBSCRIBE_OK, verbatim NOTIFY of committed deltas, CURSOR_ACK
 * flow-control, demotion->SNAPSHOT recovery when the edge stops acking, idle HEARTBEAT cadence,
 * and that the {@code edge_fanout_*} metrics actually move.
 *
 * <p>Deadline-polling only - no {@code sleep} as synchronization. Deadlines are generous for the
 * throttled 2-vCPU box; a per-method {@link Timeout} is pure hang detection.
 */
@Timeout(120)
class FanOutServerIntegrationTest {

    private static final Pattern COMMITTED_SEQ = Pattern.compile("seq=(\\d+)");
    private static final Duration WRITE_DEADLINE = Duration.ofSeconds(30);

    @TempDir
    Path tempDir;

    private ConfigdServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.shutdown();
        }
    }

    /** Single-node config with an ephemeral API port AND an ephemeral edge port (plaintext). */
    private ServerConfig plaintextConfig() {
        return new ServerConfig(
                NodeId.of(0), tempDir, Set.of(), "127.0.0.1",
                0 /* bind */, 0 /* api ephemeral */,
                null, null, null, null,
                Map.of(), null, Set.of("secure/"),
                0 /* edge port ephemeral */);
    }

    @Test
    void subscribeReceivesNotifiesAndAcksFlowControlAndRecoversAndHeartbeats() throws Exception {
        server = ConfigdServer.start(plaintextConfig());
        int edgePort = server.fanOutServer().localPort();
        assertTrue(edgePort > 0, "edge endpoint must be bound");
        String base = "http://127.0.0.1:" + server.apiPort();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(edgePort, 15_000)) {
            // --- SUBSCRIBE -> SUBSCRIBE_OK ---
            edge.subscribeFullStore("edge-1", 0L);
            EdgeFrame.SubscribeOk ok = (EdgeFrame.SubscribeOk) readUntil(edge, EdgeFrame.SubscribeOk.class);
            assertNotNull(ok, "must receive SUBSCRIBE_OK");
            // Fresh store, empty backlog -> TAIL.
            assertEquals(EdgeFrame.Mode.TAIL, ok.mode());

            // --- drive committed writes -> receive verbatim NOTIFY ---
            long seq1 = putCommitted(http, base, "svc/a", "v-a");
            long lastSeq = collectNotifiedSeqUpTo(edge, seq1);
            assertEquals(seq1, lastSeq, "the committed seq must be delivered as a NOTIFY");
            edge.cursorAck(lastSeq);

            // A second write also flows through verbatim, in order.
            long seq2 = putCommitted(http, base, "svc/b", "v-b");
            long lastSeq2 = collectNotifiedSeqUpTo(edge, seq2);
            assertEquals(seq2, lastSeq2);
            edge.cursorAck(lastSeq2);

            // --- HEARTBEAT cadence when idle (no writes) ---
            EdgeFrame.Heartbeat hb = (EdgeFrame.Heartbeat) readUntil(edge, EdgeFrame.Heartbeat.class);
            assertNotNull(hb, "must observe a HEARTBEAT when the stream is idle");
            assertTrue(hb.serverNowMillis() > 0);

            // --- STOP acking + flood writes -> DEMOTED_TO_CATCHUP then SNAPSHOT then resumed NOTIFY ---
            long floodTarget = lastSeq2;
            for (int i = 0; i < 400; i++) {
                floodTarget = putCommitted(http, base, "flood/" + i, "x" + i);
            }
            // The edge no longer acks; the server's ack-lag (8192) is large, but the bounded
            // transport queue (64) fills because we never read fast enough -> queue_overflow OR
            // ack_lag demotion. We DO keep reading (so we see the frames), but we DO NOT ack, so
            // ack-lag is the trigger once the server has streamed > ackLagDemoteSeqs past lastAck.
            // With defaults ackLag=8192 that needs many writes; instead the bounded queue fills
            // first because the writer can outpace our read. Either way a demotion must occur.
            EdgeFrame.ErrorClose demote = (EdgeFrame.ErrorClose)
                    readUntilDemotionDraining(edge);
            assertEquals(io.configd.distribution.wire.ErrorCode.DEMOTED_TO_CATCHUP, demote.code());

            // After demotion the server sends a chunked snapshot.
            EdgeFrame.SnapshotBegin begin = (EdgeFrame.SnapshotBegin) readUntil(edge, EdgeFrame.SnapshotBegin.class);
            assertNotNull(begin, "demotion must be followed by SNAPSHOT_BEGIN");
            List<EdgeFrame.SnapshotChunk> chunks = new ArrayList<>();
            EdgeFrame f;
            while (!((f = edge.readFrame()) instanceof EdgeFrame.SnapshotEnd)) {
                if (f instanceof EdgeFrame.SnapshotChunk c) {
                    chunks.add(c);
                }
                if (f == null) {
                    fail("stream ended before SNAPSHOT_END");
                }
            }
            EdgeFrame.SnapshotEnd end = (EdgeFrame.SnapshotEnd) f;
            assertEquals(begin.snapshotSeq(), end.snapshotSeq(), "BEGIN/END snapshot seq must match");
            assertEquals(begin.chunkCount(), chunks.size(), "all announced chunks must arrive");
            // The snapshot is taken at demotion time (when the bounded transport queue filled),
            // which is a valid committed seq <= the final flood target - NOT necessarily near the
            // end, since the flood continues after the early overflow-demotion.
            assertTrue(end.snapshotSeq() > 0 && end.snapshotSeq() <= floodTarget,
                    "snapshot seq must be a valid committed seq in (0, floodTarget]: " + end.snapshotSeq());

            // Ack the snapshot point; the session resumes TAIL and the post-snapshot tail of the
            // flood (plus a fresh write) is delivered. We drain forward until we observe a NOTIFY
            // with seq > the snapshot seq, proving tailing resumed cleanly.
            edge.cursorAck(end.snapshotSeq());
            putCommitted(http, base, "after/snap", "post");
            long resumedSeq = collectNotifiedSeqAtLeast(edge, end.snapshotSeq() + 1);
            assertTrue(resumedSeq >= end.snapshotSeq() + 1, "tail resumes after the snapshot");
        }

        // --- metrics moved ---
        String metrics = scrapeMetrics(http, base);
        assertMetricPresentAndMoved(metrics, "edge_fanout_notify_batches_total");
        assertMetricPresentAndMoved(metrics, "edge_fanout_heartbeats_total");
        assertTrue(metrics.contains("edge_fanout_connected_subscribers"),
                "connected-subscribers gauge must be exported");
        // A demotion counter for SOME reason moved.
        assertTrue(metrics.lines().anyMatch(l ->
                        l.startsWith("edge_fanout_demotions_") && l.endsWith(" 0") == false
                                && l.contains("_total")),
                "a demotion counter must have moved:\n" + grep(metrics, "edge_fanout_demotions"));
    }

    @Test
    void garbageFirstFrameClosesWithoutCrashingTheServer() throws Exception {
        server = ConfigdServer.start(plaintextConfig());
        int edgePort = server.fanOutServer().localPort();

        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(edgePort, 10_000)) {
            // A length prefix that declares a valid-size frame but garbage body -> CRC/decode
            // error -> server closes the connection (FRAME_CORRUPT), without dying.
            byte[] garbage = new byte[20];
            garbage[0] = 0x00;
            garbage[1] = 0x00;
            garbage[2] = 0x00;
            garbage[3] = 0x14; // length 20
            for (int i = 4; i < 20; i++) {
                garbage[i] = (byte) 0xEE;
            }
            edge.sendRaw(garbage);
            // The server should close the connection (we read EOF or an ERROR_CLOSE then EOF).
            boolean closed = drainUntilClosed(edge);
            assertTrue(closed, "server must close the connection on a corrupt first frame");
        }

        // The server is still alive - a fresh, well-behaved subscriber still works.
        try (EdgeProtocolClient edge2 = EdgeProtocolClient.connectPlaintext(edgePort, 10_000)) {
            edge2.subscribeFullStore("edge-2", 0L);
            assertNotNull(readUntil(edge2, EdgeFrame.SubscribeOk.class),
                    "server must still serve new subscribers after a bad connection");
        }
    }

    @Test
    void nonSubscribeFirstFrameIsProtocolViolation() throws Exception {
        server = ConfigdServer.start(plaintextConfig());
        int edgePort = server.fanOutServer().localPort();
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(edgePort, 10_000)) {
            // Sending CURSOR_ACK before SUBSCRIBE is a protocol violation -> close.
            edge.cursorAck(5);
            assertTrue(drainUntilClosed(edge),
                    "a non-SUBSCRIBE first frame must close the connection");
        }
    }

    // -----------------------------------------------------------------------
    // helpers (deadline-polling; no sleep-as-sync)
    // -----------------------------------------------------------------------

    /** Reads frames until one of {@code type} arrives or the deadline elapses; returns it or fails. */
    private static EdgeFrame readUntil(EdgeProtocolClient edge, Class<? extends EdgeFrame> type) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                continue; // poll again until the deadline
            }
            if (f == null) {
                fail("stream closed while waiting for " + type.getSimpleName());
            }
            if (type.isInstance(f)) {
                return f;
            }
        }
        fail("did not receive a " + type.getSimpleName() + " within the deadline");
        return null;
    }

    /** Reads NOTIFY frames, returning the highest seq seen once it reaches {@code target}. */
    private static long collectNotifiedSeqUpTo(EdgeProtocolClient edge, long target) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        long max = -1;
        while (System.nanoTime() < deadline) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            if (f == null) {
                fail("stream closed before reaching seq " + target);
            }
            if (f instanceof EdgeFrame.Notify n) {
                for (var cn : n.notifications()) {
                    max = Math.max(max, cn.seq());
                }
                if (max >= target) {
                    return max;
                }
            }
        }
        fail("did not receive NOTIFY up to seq " + target + " (max=" + max + ")");
        return max;
    }

    private static long collectNotifiedSeqAtLeast(EdgeProtocolClient edge, long minSeq) throws IOException {
        return collectNotifiedSeqUpTo(edge, minSeq);
    }

    /** Drains NOTIFY/HEARTBEAT/SNAPSHOT frames (without acking) until a DEMOTED_TO_CATCHUP arrives. */
    private static EdgeFrame readUntilDemotionDraining(EdgeProtocolClient edge) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            if (f == null) {
                fail("stream closed before demotion");
            }
            if (f instanceof EdgeFrame.ErrorClose ec
                    && ec.code() == io.configd.distribution.wire.ErrorCode.DEMOTED_TO_CATCHUP) {
                return ec;
            }
        }
        fail("no DEMOTED_TO_CATCHUP within the deadline");
        return null;
    }

    private static boolean drainUntilClosed(EdgeProtocolClient edge) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                EdgeFrame f = edge.readFrame();
                if (f == null) {
                    return true; // EOF -> server closed
                }
                // An ERROR_CLOSE then EOF is also a close.
            } catch (java.net.SocketTimeoutException e) {
                // keep polling
            } catch (IOException e) {
                return true; // reset -> closed
            }
        }
        return false;
    }

    private static long putCommitted(HttpClient http, String base, String key, String body) throws Exception {
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
                // 503/504 leader churn - retry.
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IllegalStateException("write '" + key + "' not committed in time", last);
    }

    private static String scrapeMetrics(HttpClient http, String base) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/metrics")).GET().timeout(Duration.ofSeconds(10)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static void assertMetricPresentAndMoved(String metrics, String name) {
        String line = metrics.lines()
                .filter(l -> l.startsWith(name + " "))
                .findFirst().orElse(null);
        assertNotNull(line, name + " must be exported:\n" + grep(metrics, "edge_fanout"));
        long value = Long.parseLong(line.substring(name.length()).trim());
        assertTrue(value > 0, name + " must have moved (>0), was: " + line);
        assertFalse(line.contains("NaN"));
    }

    private static String grep(String text, String needle) {
        StringBuilder sb = new StringBuilder();
        text.lines().filter(l -> l.contains(needle)).forEach(l -> sb.append(l).append('\n'));
        return sb.toString();
    }
}

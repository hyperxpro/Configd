package io.configd.server.fanout;

import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.common.Clock;
import io.configd.observability.MetricsRegistry;
import io.configd.store.ConfigSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code edge.fanout.transport.maxSessions} admission bound (hard rule 4: no
 * unbounded designs on a public endpoint): beyond the bound the accept loop closes the
 * connection immediately — BEFORE any handshake/read — and counts it on
 * {@code edge_fanout_sessions_refused_total}. Half-open (never-writing) connections count
 * toward the bound, so a slowloris cannot exhaust fds/virtual threads.
 */
@Timeout(60)
class FanOutServerAdmissionBoundTest {

    private FanOutServer server;
    private final List<Socket> clients = new ArrayList<>();

    @AfterEach
    void tearDown() throws IOException {
        for (Socket s : clients) {
            s.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void connectionsBeyondMaxSessionsAreRefusedAndCounted() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics metrics = new RegistryFanOutSessionMetrics(registry);
        FanOutBuffer buffer = new FanOutBuffer(16);
        server = new FanOutServer(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                null /* plaintext */, buffer,
                new SnapshotReplaySource(() -> ConfigSnapshot.EMPTY),
                FanOutConfig.defaults(), FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES,
                2 /* maxSessions */, metrics, Clock.system());
        server.start();
        int port = server.localPort();

        // Two half-open connections (never write a frame) occupy the bound.
        clients.add(new Socket(InetAddress.getLoopbackAddress(), port));
        clients.add(new Socket(InetAddress.getLoopbackAddress(), port));

        // The third is admitted at TCP level (backlog) but must be closed by the
        // accept loop without ever being served: its read sees EOF promptly.
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        boolean refusedObserved = false;
        while (System.nanoTime() < deadline && !refusedObserved) {
            try (Socket third = new Socket(InetAddress.getLoopbackAddress(), port)) {
                third.setSoTimeout(5_000);
                try (InputStream in = third.getInputStream()) {
                    refusedObserved = (in.read() == -1); // EOF = closed by the server, nothing served
                }
            } catch (IOException e) {
                refusedObserved = true; // reset-on-close is an equally valid refusal observation
            }
        }
        assertTrue(refusedObserved, "third connection must be refused (EOF/reset), never served");

        long refused = registry.snapshot().metrics()
                .get("edge.fanout.sessions_refused").value();
        assertTrue(refused >= 1, "edge.fanout.sessions_refused must count the refusal, was " + refused);
    }

    @Test
    void nonPositiveMaxSessionsRejectedAtConstruction() {
        MetricsRegistry registry = new MetricsRegistry();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new FanOutServer(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                        null, new FanOutBuffer(16),
                        new SnapshotReplaySource(() -> ConfigSnapshot.EMPTY),
                        FanOutConfig.defaults(), FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES,
                        0, new RegistryFanOutSessionMetrics(registry), Clock.system()));
        assertEquals("maxSessions must be positive: 0", e.getMessage());
    }
}

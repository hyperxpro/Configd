package io.configd.server.fanout;

import io.configd.common.Clock;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.store.ConfigSnapshot;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WH-11 slow-loris: the pre-SUBSCRIBE first-frame deadline on the edge fan-out endpoint. A peer
 * that completes admission (post-mTLS, or plaintext here) then sends nothing parks a session slot
 * + FD + cumulator until the OS reaps it. The fix reaps such a connection after a bounded window
 * ({@link FanOutServer#FIRST_FRAME_DEADLINE_PROP}); a peer that DOES send its first routed frame
 * (SUBSCRIBE) then idles - the legitimate fan-out subscriber, idle by design - is NOT reaped (the
 * deadline is disarmed, and liveness rides the existing server{@code ->}client HEARTBEAT).
 *
 * <p>Proven on BOTH transports (JDK {@link FanOutServer} soTimeout, Netty {@link NettyFanOutServer}
 * scheduled task), mirroring the Raft {@code InboundReadDeadlineFuzzTest} intent against the real
 * server rather than a bare socket. Assertions are on the authoritative
 * {@code edge_fanout_first_frame_timeouts_total} counter (event-driven poll for the reap; a bounded
 * wait past the deadline for the non-reap), never on fragile socket-state reasoning.
 */
@Timeout(120)
class EdgeFirstFrameDeadlineTest {

    /** A short first-frame deadline so the test is fast; well above scheduling jitter. */
    private static final int DEADLINE_MS = 300;

    private static String priorDeadline;

    private FanOutEndpoint server;
    private MetricsRegistry registry;
    private Socket client;

    @BeforeAll
    static void armShortDeadline() {
        priorDeadline = System.getProperty(FanOutServer.FIRST_FRAME_DEADLINE_PROP);
        System.setProperty(FanOutServer.FIRST_FRAME_DEADLINE_PROP, Integer.toString(DEADLINE_MS));
    }

    @AfterAll
    static void restoreDeadline() {
        if (priorDeadline == null) {
            System.clearProperty(FanOutServer.FIRST_FRAME_DEADLINE_PROP);
        } else {
            System.setProperty(FanOutServer.FIRST_FRAME_DEADLINE_PROP, priorDeadline);
        }
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            try { client.close(); } catch (Exception ignored) { }
        }
        if (server != null) {
            server.close();
        }
    }

    private int startPlaintext(boolean netty) throws Exception {
        registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics metrics = new RegistryFanOutSessionMetrics(registry);
        InetSocketAddress bind = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        FanOutBuffer buffer = new FanOutBuffer(1_024);
        SnapshotReplaySource replay = new SnapshotReplaySource(() -> ConfigSnapshot.EMPTY);
        FanOutConfig config = FanOutConfig.defaults();
        Clock clock = Clock.system();
        server = netty
                ? new NettyFanOutServer(bind, null, buffer, replay, config,
                        FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES,
                        FanOutServer.DEFAULT_MAX_SESSIONS, metrics, clock)
                : new FanOutServer(bind, null, buffer, replay, config,
                        FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES,
                        FanOutServer.DEFAULT_MAX_SESSIONS, metrics, clock);
        server.start();
        return server.localPort();
    }

    private long firstFrameTimeouts() {
        String scrape = new PrometheusExporter(registry).export();
        String name = "edge_fanout_first_frame_timeouts_total";
        return scrape.lines()
                .filter(l -> l.startsWith(name + " "))
                .map(l -> Long.parseLong(l.substring(name.length() + 1).trim()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("counter not exported:\n" + scrape));
    }

    /** Polls the reap counter up to a bound (event-driven; the reap fires at ~DEADLINE_MS). */
    private void awaitReap() throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(30L * DEADLINE_MS);
        while (System.nanoTime() < deadlineNanos) {
            if (firstFrameTimeouts() >= 1) {
                return;
            }
            Thread.sleep(20);
        }
        assertEquals(1, firstFrameTimeouts(), "slow-loris was not reaped within the bound");
    }

    private void stalledPeerIsReaped(boolean netty) throws Exception {
        int port = startPlaintext(netty);
        // Admission (plaintext) then send NOTHING - the slow-loris.
        client = new Socket();
        client.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
        awaitReap();
        assertEquals(1, firstFrameTimeouts());
    }

    private void subscribedPeerIsNotReaped(boolean netty) throws Exception {
        int port = startPlaintext(netty);
        client = new Socket();
        client.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
        // Send the first routed frame (SUBSCRIBE) promptly - this DISARMS the deadline.
        OutputStream out = client.getOutputStream();
        out.write(EdgeFrameCodec.encode(new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge-x")));
        out.flush();
        // Wait well past the deadline: an established, idle subscriber must NOT be reaped.
        Thread.sleep(3L * DEADLINE_MS);
        assertEquals(0, firstFrameTimeouts(),
                "an established subscriber that idles must not be first-frame-reaped");
        assertTrue(client.isConnected());
    }

    @Test
    void jdkStalledPeerIsReaped() throws Exception {
        stalledPeerIsReaped(false);
    }

    @Test
    void jdkSubscribedPeerIsNotReaped() throws Exception {
        subscribedPeerIsNotReaped(false);
    }

    @Test
    void nettyStalledPeerIsReaped() throws Exception {
        stalledPeerIsReaped(true);
    }

    @Test
    void nettySubscribedPeerIsNotReaped() throws Exception {
        subscribedPeerIsNotReaped(true);
    }
}

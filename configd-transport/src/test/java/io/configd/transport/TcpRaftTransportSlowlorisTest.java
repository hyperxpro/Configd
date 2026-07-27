package io.configd.transport;

import io.configd.common.NodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slowloris / FD-exhaustion end-to-end tests against the real {@link TcpRaftTransport} inbound
 * path. The mechanism is pinned at the socket level by {@link InboundReadDeadlineFuzzTest};
 * these assert the fix is wired into the transport:
 * <ol>
 *   <li>a slow-drip peer that connects and then stalls is dropped by the server within the
 *       {@code inboundReadTimeoutMs} read-idle deadline (reader vthread + FD released), and</li>
 *   <li>once {@code maxInboundConnections} sockets are live, further inbound connections are
 *       refused (closed + counted) - the node stays available instead of exhausting FDs.</li>
 * </ol>
 * Plaintext transport (tlsManager=null) keeps the attacker a bare socket. The guard runs on
 * transport virtual threads, never {@code configd-tick}, so it cannot park the tick thread.
 */
class TcpRaftTransportSlowlorisTest {

    private final List<TcpRaftTransport> transports = new ArrayList<>();
    private final List<Socket> attackers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Socket s : attackers) {
            try { s.close(); } catch (Exception ignored) { }
        }
        for (TcpRaftTransport t : transports) {
            t.close();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private TcpRaftTransport startTransport(int port) throws Exception {
        TcpRaftTransport t = new TcpRaftTransport(
                NodeId.of(1),
                new InetSocketAddress("127.0.0.1", port),
                Map.of(),          // no peers needed for the inbound-only test
                null,              // plaintext
                msg -> { });
        transports.add(t);
        t.start();
        return t;
    }

    private Socket connectAttacker(int port) throws Exception {
        Socket s = new Socket("127.0.0.1", port);
        attackers.add(s);
        return s;
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void slowDripPeerIsDroppedWithinTheReadIdleDeadline() throws Exception {
        String savedTimeout = System.getProperty("configd.raft.inboundReadTimeoutMs");
        try {
            System.setProperty("configd.raft.inboundReadTimeoutMs", "500"); // short deadline for the test
            int port = freePort();
            startTransport(port);

            Socket attacker = connectAttacker(port);
            // Slow drip: send NOTHING. The server's first readInt() (sender id) blocks, then trips the
            // 500 ms read-idle deadline and closes the connection - which the attacker observes as EOF.
            long t0 = System.nanoTime();
            attacker.setSoTimeout(5_000); // bound the attacker's own read so the test can never hang
            InputStream in = attacker.getInputStream();
            int b = in.read();
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            assertEquals(-1, b,
                    "the server must CLOSE a slow-drip connection (EOF), not park its reader forever");
            assertTrue(elapsedMs < 3_000,
                    "the slow-drip peer must be dropped within ~the read-idle deadline, was " + elapsedMs + "ms");
        } finally {
            if (savedTimeout == null) System.clearProperty("configd.raft.inboundReadTimeoutMs");
            else System.setProperty("configd.raft.inboundReadTimeoutMs", savedTimeout);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void inboundConnectionsAreCappedAndExcessIsRefused() throws Exception {
        String savedCap = System.getProperty("configd.raft.maxInboundConnections");
        String savedTimeout = System.getProperty("configd.raft.inboundReadTimeoutMs");
        try {
            int cap = 3;
            System.setProperty("configd.raft.maxInboundConnections", Integer.toString(cap));
            // Long deadline so the first `cap` stalled sockets STAY in the live-set while we flood more.
            System.setProperty("configd.raft.inboundReadTimeoutMs", "10000");
            int port = freePort();
            TcpRaftTransport t = startTransport(port);

            // Open well beyond the cap; each connects and stalls (sends nothing).
            int flood = cap + 5;
            for (int i = 0; i < flood; i++) {
                connectAttacker(port);
            }

            // The accept loop refuses (closes + counts) every socket beyond the cap. Poll for it.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            long refused = 0;
            while (System.nanoTime() < deadline) {
                refused = t.inboundConnectionsRefused();
                if (refused >= 1) break;
                Thread.sleep(50);
            }
            assertTrue(refused >= 1,
                    "inbound connections beyond maxInboundConnections=" + cap
                            + " must be refused (node stays available); refused=" + refused);
        } finally {
            if (savedCap == null) System.clearProperty("configd.raft.maxInboundConnections");
            else System.setProperty("configd.raft.maxInboundConnections", savedCap);
            if (savedTimeout == null) System.clearProperty("configd.raft.inboundReadTimeoutMs");
            else System.setProperty("configd.raft.inboundReadTimeoutMs", savedTimeout);
        }
    }
}

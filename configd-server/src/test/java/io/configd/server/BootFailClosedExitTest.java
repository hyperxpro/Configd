package io.configd.server;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * H-BOOT: a fail-closed boot must EXIT cleanly, never hang. The Raft/API/edge transports start
 * NON-daemon Netty event loops, so a fail-closed throw AFTER the transport has started (an unreachable
 * IdP during auth-chain build, a missing provider module, an API/edge port already in use, a
 * TLS-without-manager refusal) used to kill the main thread while the event loops kept the JVM alive -
 * the process printed a stack trace and hung, bound but serving nothing.
 *
 * <p>The fix is two-sided: {@link ConfigdServer#start} now closes whatever it created (in reverse
 * order) before the failure propagates, and {@code main()} turns the propagated failure into a
 * non-zero {@link System#exit}. This test exercises the first half in-JVM (a {@code System.exit} in a
 * test would kill the runner): it forces a post-transport-start boot failure and proves start()
 * (a) returns promptly rather than hanging, and (b) released the Raft transport's bound port - i.e.
 * the non-daemon event loops that used to keep a dead process alive were torn down.
 */
class BootFailClosedExitTest {

    @Test
    @Timeout(60)
    void failClosedBootAfterTransportStartTearsDownAndDoesNotHang(@TempDir Path tempDir)
            throws Exception {
        // A known-free loopback port for the Raft transport: the server binds it during start()
        // (a real, non-null transport requires a configured peer address), and after the failed boot
        // we prove it was released.
        int raftPort = freeLoopbackPort();

        // Hold the API port OPEN so httpApiServer.start() fails to bind - a fail-closed throw that
        // lands AFTER tcpTransport.start(), exactly the class of failure that used to hang main().
        try (ServerSocket apiHog = new ServerSocket()) {
            apiHog.setReuseAddress(true);
            apiHog.bind(new InetSocketAddress("127.0.0.1", 0));
            int apiPort = apiHog.getLocalPort();

            ServerConfig config = ServerConfig.parse(new String[]{
                    "--node-id", "0",
                    "--data-dir", tempDir.resolve("data").toString(),
                    "--peers", "0,1",
                    "--peer-addresses", "1=127.0.0.1:1", // parses; the peer is never reached at boot
                    "--bind-address", "127.0.0.1",
                    "--bind-port", Integer.toString(raftPort),
                    "--api-port", Integer.toString(apiPort)
            });

            // The boot MUST fail (API port in use) and return promptly - a bounded-time assertion so a
            // regression that reintroduces the hang fails here rather than wedging the suite.
            assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                RuntimeException ex = assertThrows(RuntimeException.class,
                        () -> ConfigdServer.start(config));
                assertTrue(ex.getMessage() != null && ex.getMessage().contains("HTTP API server"),
                        "the boot failure must be the post-transport API bind: " + ex.getMessage());
            });
        }

        // Clean-teardown proof: the Raft transport that start() had already started is closed on the
        // failure path, so its bind port is free again. Were the transport leaked (the pre-fix hang),
        // a live non-daemon Netty event loop would still hold this port.
        assertPortReleased(raftPort);
    }

    /** Grabs an ephemeral loopback port and frees it for the server to bind. */
    private static int freeLoopbackPort() throws IOException {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress("127.0.0.1", 0));
            return s.getLocalPort();
        }
    }

    /** Asserts {@code port} can be re-bound within a short grace window (transport was torn down). */
    private static void assertPortReleased(int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try (ServerSocket s = new ServerSocket()) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress("127.0.0.1", port));
                return; // re-bound -> the transport that held it was closed on the failed boot
            } catch (IOException e) {
                last = e;
                Thread.sleep(100);
            }
        }
        throw new AssertionError("Raft transport port " + port + " was not released after a failed boot"
                + " - the transport leaked and main() would hang: " + last);
    }
}

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
 * Non-daemon Netty event loops from an already-started transport keep the JVM alive even after a
 * fail-closed throw kills the main thread, so the fix must close whatever {@link ConfigdServer#start}
 * already created before the failure propagates. This test forces a post-transport-start failure
 * in-JVM (a real boot would {@link System#exit}) and proves start() returns promptly and releases
 * the port the Raft transport had bound.
 */
class BootFailClosedExitTest {

    @Test
    @Timeout(60)
    void failClosedBootAfterTransportStartTearsDownAndDoesNotHang(@TempDir Path tempDir)
            throws Exception {
        int raftPort = freeLoopbackPort();

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

            assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                RuntimeException ex = assertThrows(RuntimeException.class,
                        () -> ConfigdServer.start(config));
                assertTrue(ex.getMessage() != null && ex.getMessage().contains("HTTP API server"),
                        "the boot failure must be the post-transport API bind: " + ex.getMessage());
            });
        }

        assertPortReleased(raftPort);
    }

    private static int freeLoopbackPort() throws IOException {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress("127.0.0.1", 0));
            return s.getLocalPort();
        }
    }

    private static void assertPortReleased(int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try (ServerSocket s = new ServerSocket()) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress("127.0.0.1", port));
                return;
            } catch (IOException e) {
                last = e;
                Thread.sleep(100);
            }
        }
        throw new AssertionError("Raft transport port " + port + " was not released after a failed boot"
                + " - the transport leaked and main() would hang: " + last);
    }
}

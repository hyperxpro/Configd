package io.configd.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slow-drip ("slowloris") resource-exhaustion assessment for the
 * {@link TcpRaftTransport} inbound read path.
 *
 * <p>Without a read deadline on accepted sockets, a peer that completes the TLS handshake
 * and then drips bytes - or sends the 4-byte sender id / length prefix and then stalls -
 * parks a reader thread and holds a socket file descriptor indefinitely. Thousands of such
 * connections exhaust file descriptors (the accept() loop then fails and cannot admit
 * legitimate peers): a resource-exhaustion DoS that no malformed-frame rejection covers,
 * because the bytes are well-formed - just slow.
 *
 * <p>This test does NOT spin a flaky timing race. It pins the mechanism deterministically:
 * <ol>
 *   <li><b>Default socket reads have no deadline</b> - a freshly accepted socket has
 *       {@code getSoTimeout() == 0}, and a {@code read()} with no data available blocks
 *       (we prove it blocks past a short bound, then unblock it by closing, so the test
 *       always terminates).</li>
 *   <li><b>The contrast:</b> the same read WITH a small {@code setSoTimeout} throws
 *       {@link SocketTimeoutException} promptly - a read deadline is the standard
 *       mitigation for this vector.</li>
 * </ol>
 */
class InboundReadDeadlineFuzzTest {

    /**
     * A blocking {@code read()} on a socket with the default timeout (0) does not
     * return on its own while the peer holds the connection open and sends
     * nothing - the exact condition a slow-drip attacker creates against the
     * inbound reader, which uses {@code DataInputStream.readInt()/readFully} with
     * no deadline. We bound the test by closing the socket from another thread so
     * it always terminates (the read then throws), proving the read was blocked
     * (not spinning, not returning EOF) up to that point.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void defaultSocketReadHasNoDeadlineAndBlocksOnAStalledPeer() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            int port = server.getLocalPort();

            try (Socket attacker = new Socket("127.0.0.1", port);
                 Socket accepted = server.accept()) {

                assertEquals(0, accepted.getSoTimeout(),
                        "accepted socket default soTimeout is 0 (no deadline) — "
                                + "this is what the inbound reader relies on");

                InputStream in = accepted.getInputStream();
                AtomicReference<Object> outcome = new AtomicReference<>();
                Thread reader = new Thread(() -> {
                    try {
                        int b = in.read();
                        outcome.set(b);
                    } catch (IOException e) {
                        outcome.set(e);    // set when we close the socket below
                    }
                }, "stalled-inbound-reader");
                reader.setDaemon(true);
                reader.start();

                reader.join(1_500);
                assertTrue(reader.isAlive(),
                        "read() returned without data — a real deadline would be needed "
                                + "to bound a slow-drip peer, and none is present");
                assertNull(outcome.get(),
                        "no outcome yet: the read is genuinely blocked, holding the "
                                + "reader thread + socket FD with no timeout");

                accepted.close();
                reader.join(5_000);
                assertTrue(!reader.isAlive(), "reader should unblock once the socket closes");
                assertInstanceOf(IOException.class, outcome.get(),
                        "the blocked read terminates via close (IOException), not a deadline");
            }
        }
    }

    /**
     * The mitigation is standard and available: a small {@code setSoTimeout}
     * makes the identical stalled-peer read fail fast with
     * {@link SocketTimeoutException}. This is the recommendation for the inbound
     * path - an idle/read deadline (plus a cap on concurrent inbound connections)
     * bounds the slow-drip vector.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aReadDeadlineWouldBoundTheStalledPeer() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            int port = server.getLocalPort();
            try (Socket attacker = new Socket("127.0.0.1", port);
                 Socket accepted = server.accept()) {

                accepted.setSoTimeout(300); // the deadline the inbound path lacks
                InputStream in = accepted.getInputStream();
                assertThrows(SocketTimeoutException.class, in::read,
                        "with a read deadline, a stalled slow-drip peer fails fast");
                // keep 'attacker' referenced so it isn't GC-closed before the read
                assertTrue(attacker.isConnected());
            }
        }
    }
}

package io.configd.client.edge;

import io.configd.client.ConfigdException;
import io.configd.client.HostileServerLimits;
import io.configd.client.ProtocolViolationException;
import io.configd.client.ServerAddress;
import io.configd.client.UnavailableException;
import io.configd.client.edge.session.EdgeConnection;
import io.configd.common.auth.Credential;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The client must be as hardened against a hostile <b>server</b> as the server is against a hostile client.
 * These drive {@link EdgeConnection} directly (bypassing the reconnect policy) so each hostile input is
 * asserted to fail <b>clean</b>: the right exception type + edge code, the connection torn down, the reader
 * thread gone (no leak), and never a hang, OOM, or misparse. Bounds 1–6 are the shared codec's; the deadlines
 * and sanitize are the state machine's.
 */
@Timeout(30)
class EdgeHostileServerTest {

    @Test
    void oversizeDeclaredLengthIsBoundedRejectNoAllocation() throws Exception {
        // A length prefix of 0x7FFFFFFF (> 2 MiB) must be rejected by peekLength BEFORE any buffer is sized.
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn ->
                conn.sendRaw(new byte[]{0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}))) {
            EdgeConnection conn = connect(server);
            assertTerminal(conn, ProtocolViolationException.class, ErrorCode.FRAME_TOO_LARGE);
        }
    }

    @Test
    void badCrcIsFrameCorrupt() throws Exception {
        byte[] heartbeat = EdgeFrameCodec.encode(new EdgeFrame.Heartbeat(1L, 2L));
        heartbeat[heartbeat.length - 1] ^= (byte) 0xFF; // flip a CRC byte
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> conn.sendRaw(heartbeat))) {
            EdgeConnection conn = connect(server);
            assertTerminal(conn, ProtocolViolationException.class, ErrorCode.FRAME_CORRUPT);
        }
    }

    @Test
    void garbagePayloadIsFrameCorrupt() throws Exception {
        // A well-formed length prefix (12) over 8 garbage bytes: CRC fails before version/type is trusted.
        byte[] garbage = new byte[]{0, 0, 0, 12, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88};
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> conn.sendRaw(garbage))) {
            EdgeConnection conn = connect(server);
            assertTerminal(conn, ProtocolViolationException.class, ErrorCode.FRAME_CORRUPT);
        }
    }

    @Test
    void truncatedFrameIsFrameCorrupt() throws Exception {
        // Declare a 20-byte frame then send only 5 bytes and close: a mid-frame end-of-stream is corruption.
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn ->
                conn.sendRaw(new byte[]{0, 0, 0, 20, 0x01, 0x02, 0x03, 0x04, 0x05}))) {
            EdgeConnection conn = connect(server);
            assertTerminal(conn, ProtocolViolationException.class, ErrorCode.FRAME_CORRUPT);
        }
    }

    @Test
    void serverAuthFrameIsProtocolViolation() throws Exception {
        // AUTH/REFRESH_AUTH are client->server only; a server that sends one is a protocol violation.
        byte[] serverAuth = EdgeFrameCodec.encode(
                new EdgeFrame.Auth(new Credential.BearerToken("x")), EdgeFrameCodec.EDGE_WIRE_VERSION_V4);
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> conn.sendRaw(serverAuth))) {
            EdgeConnection conn = connect(server);
            assertTerminal(conn, ProtocolViolationException.class, null);
        }
    }

    @Test
    void errorCloseMessageWithControlBytesIsSanitized() throws Exception {
        // A hostile diagnostic with a real newline, ESC (ANSI CSI), and NUL — log-forging / terminal-injection.
        String hostile = "line-one\n" + (char) 0x1B + "[31mred" + (char) 0x00 + "bell";
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn ->
                conn.send(new EdgeFrame.ErrorClose(ErrorCode.SERVER_SHUTDOWN, hostile)))) {
            EdgeConnection conn = connect(server);
            ExecutionException ee = assertThrows(ExecutionException.class,
                    () -> conn.closedFuture().get(10, TimeUnit.SECONDS));
            // SERVER_SHUTDOWN on an ERROR_CLOSE is a retryable connection close.
            UnavailableException ex = assertInstanceOf(UnavailableException.class, ee.getCause());
            String sanitized = ex.serverMessage().orElseThrow();
            for (int i = 0; i < sanitized.length(); i++) {
                char c = sanitized.charAt(i);
                assertTrue(c >= 0x20 && !(c >= 0x7F && c <= 0x9F),
                        "sanitized diagnostic must carry no control/ANSI/NUL byte, found 0x"
                                + Integer.toHexString(c));
            }
            assertTrue(sanitized.contains("[31mred") && sanitized.contains("bell"),
                    "printable content is preserved");
            assertTrue(sanitized.contains("\\u001b") && sanitized.contains("\\u0000"),
                    "the ESC and NUL are rendered as visible escapes");
            await(() -> !conn.readerAlive());
        }
    }

    @Test
    void silentServerDoesNotHangAndClosesCleanly() throws Exception {
        // The server accepts then sends nothing (a fan-out subscriber is idle by design pre-stream). The
        // reader must not hang, and an explicit close must stop it promptly with no leak.
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(MockEdgeServer.Conn::parkUntilClosed)) {
            EdgeConnection conn = connect(server);
            assertTrue(conn.readerAlive(), "reader is parked on the idle socket, not spinning");
            conn.close();
            await(() -> !conn.readerAlive());
            conn.closedFuture().get(10, TimeUnit.SECONDS); // completed normally (client-initiated close)
        }
    }

    private static EdgeConnection connect(MockEdgeServer server) {
        EdgeConnection conn = new EdgeConnection(
                new ServerAddress("127.0.0.1", server.port()), null,
                HostileServerLimits.defaults(), new InboundFrameHandler() {
        }, "hostile-test-reader");
        conn.connect();
        return conn;
    }

    private static void assertTerminal(EdgeConnection conn, Class<? extends ConfigdException> type,
                                       ErrorCode expectedCode) {
        ExecutionException ee = assertThrows(ExecutionException.class,
                () -> conn.closedFuture().get(10, TimeUnit.SECONDS));
        ConfigdException ex = assertInstanceOf(type, ee.getCause());
        if (expectedCode != null) {
            assertEquals(Optional.of(expectedCode), ex.edgeCode());
        }
        await(() -> !conn.readerAlive()); // the reader thread stopped — no leak
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("timed out awaiting a condition (leak or hang?)");
    }
}

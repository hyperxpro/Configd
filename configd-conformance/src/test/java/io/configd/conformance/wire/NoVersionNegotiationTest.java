package io.configd.conformance.wire;

import com.sun.net.httpserver.HttpServer;
import io.configd.client.ProtocolViolationException;
import io.configd.client.http.ConfigdHttpClient;
import io.configd.client.http.GetOptions;
import io.configd.client.http.GetResult;
import io.configd.client.http.NodeEndpoints;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.EdgeFrameCodec.CodecException;
import io.configd.distribution.wire.EdgeFrameGoldenBytes;
import io.configd.distribution.wire.ErrorCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A permanent review invariant (§00 OV4-2 / OV7-3 / OV7-4): Configd has <b>no version negotiation</b> -- no
 * hello / capabilities / downgrade exchange on either plane, unlike every reference-client analog
 * (libpq/etcd/grpc negotiate down). This suite carries explicit NEGATIVE cases proving the client NEVER
 * attempts a downgrade and <b>fails closed</b> on an unknown version/path -- a guard against a future
 * contributor adding a downgrade path.
 */
@Timeout(30)
@Tag("clause:OV4-2")
@Tag("clause:OV7-4_1")
@Tag("clause:OV7-4_2")
@Tag("clause:OV8-2")
class NoVersionNegotiationTest {

    @Test
    void edgeUnknownWireVersionFailsClosedNoDowngrade() {
        // The client's inbound bounds ARE the codec (it reuses EdgeFrameCodec). An unknown version byte on a
        // server frame is BAD_WIRE_VERSION -- a fail-closed reject, NOT a downgrade to a version the client
        // "also speaks". There is no negotiation path to fall back to.
        byte[] golden = EdgeFrameGoldenBytes.forVersion(1).values().iterator().next();
        byte[] unknownVersion = golden.clone();
        unknownVersion[4] = (byte) 0x05; // the version byte
        recrc(unknownVersion);
        CodecException ex = assertThrows(CodecException.class, () -> EdgeFrameCodec.decode(unknownVersion));
        assertEquals(ErrorCode.BAD_WIRE_VERSION, ex.code());
    }

    @Test
    void edgeFirstFramePinRejectsAnyOtherAcceptedVersionNoDowngrade() {
        // A 0x02 frame on a 0x01-pinned connection is BAD_WIRE_VERSION -- the client does not "negotiate up" to
        // 0x02 because the peer sent one; the first-frame pin IS the whole negotiation (§06 F4 / OV7-4).
        byte[] watch = pick(EdgeFrameGoldenBytes.forVersion(2), "watch_create");
        CodecException ex = assertThrows(CodecException.class,
                () -> EdgeFrameCodec.decode(watch, EdgeFrameCodec.EDGE_WIRE_VERSION));
        assertEquals(ErrorCode.BAD_WIRE_VERSION, ex.code());
    }

    @Test
    void everyFrameStampsAPinnedVersionNoUnversionedPreamble() {
        // There is no hello/capabilities frame TYPE in the protocol; every frame -- including the first business
        // frame and the auth frame -- carries a version byte at offset 4 (after the 4-byte length). Prove it
        // across the golden corpus: there is NO un-versioned pre-amble a driver could turn into a negotiation.
        for (int version = 1; version <= 4; version++) {
            for (Map.Entry<String, byte[]> e : EdgeFrameGoldenBytes.forVersion(version).entrySet()) {
                assertEquals((byte) version, e.getValue()[4],
                        "fixture " + e.getKey() + " must stamp the pinned version at offset 4");
            }
        }
    }

    @Test
    void httpClientOnlyAddressesV1AndTreatsUnknownPathAsTerminal() throws Exception {
        List<String> requestedPaths = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            // Simulate a deployment that does not know this path: everything 404s.
            byte[] body = "Not Found".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            try (ConfigdHttpClient client = ConfigdHttpClient.builder()
                    .endpoints(NodeEndpoints.of(base)).allowPlaintext(true).build()) {
                // A GET of a key: a 404 is a DEFINITE "absent" -- never a trigger to probe /v2/ or a capabilities path.
                GetResult r = client.blocking().get("some/key", GetOptions.defaults());
                assertFalse(r.found());
                // A 404 on a WRITE is terminal, fail-closed (the router returns the 404 and the body is not
                // "Committed: seq=<N>" means a ProtocolViolation) -- the client never falls back or negotiates a path.
                assertThrows(ProtocolViolationException.class,
                        () -> client.blocking().put("some/key", "v".getBytes(StandardCharsets.UTF_8),
                                io.configd.client.http.WriteOptions.defaults()));
            }
            assertFalse(requestedPaths.isEmpty());
            for (String path : requestedPaths) {
                assertTrue(path.startsWith("/v1/config/"),
                        "the client addressed a non-/v1/config path (a negotiation/discovery attempt?): " + path);
            }
        } finally {
            server.stop(0);
        }
    }

    private static void recrc(byte[] frame) {
        int crcOffset = frame.length - EdgeFrameCodec.TRAILER_SIZE;
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(frame, 0, crcOffset);
        int v = (int) crc.getValue();
        frame[crcOffset] = (byte) (v >>> 24);
        frame[crcOffset + 1] = (byte) (v >>> 16);
        frame[crcOffset + 2] = (byte) (v >>> 8);
        frame[crcOffset + 3] = (byte) v;
    }

    private static byte[] pick(Map<String, byte[]> m, String keySubstr) {
        for (Map.Entry<String, byte[]> e : m.entrySet()) {
            if (e.getKey().contains(keySubstr)) {
                return e.getValue();
            }
        }
        throw new IllegalStateException("no fixture matching " + keySubstr);
    }
}

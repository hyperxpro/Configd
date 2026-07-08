package io.configd.client.edge;

import com.sun.net.httpserver.HttpServer;
import io.configd.client.Configd;
import io.configd.client.edge.session.EdgeConnectionState;
import io.configd.client.http.ConfigdHttpClient;
import io.configd.client.http.GetOptions;
import io.configd.client.http.GetResult;
import io.configd.client.http.NodeEndpoints;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unified {@link Configd} facade (the configd-client aggregator) vends BOTH plane clients over one shared
 * config + scheduler and closes them on {@link Configd#close()}: {@link Configd#edge()} against a real loopback
 * edge server (the reused {@code MockEdgeServer} fixture) and {@link Configd#http()} against a real loopback
 * HTTP server. This is the relocated Gate-1 facade test, extended for the Gate-4 HTTP plane.
 */
@Timeout(30)
class ConfigdFacadeTest {

    @Test
    void facadeVendsEdgeClientAndClosesIt() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(MockEdgeServer.Conn::parkUntilClosed)) {
            ConfigdEdgeClient edge;
            try (Configd configd = Configd.builder()
                    .endpoint("127.0.0.1", server.port())
                    .allowPlaintext(true)
                    .build()) {
                edge = configd.edge();
                assertEquals(AuthMode.NO_AUTH, edge.authMode());
                edge.connectAndAuthenticate().get(10, TimeUnit.SECONDS);
                assertEquals(EdgeConnectionState.AUTHENTICATED, edge.state());
            }
            // The facade's close() closed the vended edge client.
            assertEquals(EdgeConnectionState.CLOSED, edge.state());
        }
    }

    @Test
    void facadeVendsHttpClientAndClosesIt() throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/v1/config/facade-key", exchange -> {
            byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().set("X-Config-Version", "7");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        http.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + http.getAddress().getPort());
            try (Configd configd = Configd.builder()
                    .httpNodes(NodeEndpoints.of(base))
                    .allowPlaintext(true)
                    .build()) {
                ConfigdHttpClient client = configd.http();
                GetResult result = client.blocking().get("facade-key", GetOptions.defaults());
                assertTrue(result.found());
                assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), result.valueOrThrow());
                assertEquals(7L, result.version());
            }
        } finally {
            http.stop(0);
        }
    }
}

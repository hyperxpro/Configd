package io.configd.client.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A scriptable loopback HTTP control-plane double for the reference-client unit tests. Each incoming request
 * pops the next enqueued {@link Response} (a FIFO applied to any request), so a retry sequence is scripted as
 * {@code enqueue(503); enqueue(200)}. Every request is recorded (method / path / query / headers / body) for
 * assertions -- the query especially, to prove the {@code consistency=linearizable} literal and the replay
 * headers are emitted exactly. Bodies are plaintext under {@code application/json} to mirror the real server,
 * so the "branch on code, not body" contract is exercised.
 */
final class MockControlPlane implements AutoCloseable {

    record Response(int status, Map<String, String> headers, byte[] body) {
        static Response of(int status, Map<String, String> headers, String body) {
            return new Response(status, headers, body.getBytes(StandardCharsets.UTF_8));
        }

        static Response text(int status, String body) {
            return of(status, Map.of(), body);
        }

        static Response value(String body, long version) {
            return of(200, Map.of("Content-Type", "application/octet-stream",
                    "X-Config-Version", Long.toString(version)), body);
        }

        static Response committed(long seq) {
            return of(200, Map.of("Content-Type", "application/json"), "Committed: seq=" + seq);
        }
    }

    record Recorded(String method, String path, String query, Map<String, String> headers, byte[] body) {
    }

    private final HttpServer server;
    private final Queue<Response> responses = new ConcurrentLinkedQueue<>();
    private final List<Recorded> recorded = Collections.synchronizedList(new ArrayList<>());

    MockControlPlane() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    void enqueue(Response response) {
        responses.add(response);
    }

    int port() {
        return server.getAddress().getPort();
    }

    URI baseUri() {
        return URI.create("http://127.0.0.1:" + port());
    }

    List<Recorded> recorded() {
        return recorded;
    }

    Recorded lastRequest() {
        synchronized (recorded) {
            return recorded.isEmpty() ? null : recorded.get(recorded.size() - 1);
        }
    }

    int requestCount() {
        return recorded.size();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        // Case-insensitive: com.sun.net.httpserver normalizes header names (e.g. X-Configd-Nonce).
        Map<String, String> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, v.isEmpty() ? "" : v.get(0)));
        recorded.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getQuery(), headers, body));

        Response response = responses.poll();
        if (response == null) {
            byte[] b = "no scripted response".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, b.length);
            exchange.getResponseBody().write(b);
            exchange.close();
            return;
        }
        response.headers().forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
        if (response.body().length == 0) {
            exchange.sendResponseHeaders(response.status(), -1);
        } else {
            exchange.sendResponseHeaders(response.status(), response.body().length);
            exchange.getResponseBody().write(response.body());
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

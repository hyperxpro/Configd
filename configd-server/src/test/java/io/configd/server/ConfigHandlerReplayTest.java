package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.AuthInterceptor;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.api.ReplayGuard;
import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.store.VersionedConfigStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S7/D-3 — HTTP-level replay-protection attack. With the {@link ReplayGuard}
 * wired into the production {@code ConfigHandler}, this captures the EXACT bytes
 * of a valid PUT (headers + body) and replays them verbatim, asserting the
 * replay is rejected while a fresh nonce is accepted and a stale timestamp is
 * rejected. Charter prime directive §2.1: the control is verified by a passing
 * negative test that performs the attack.
 */
final class ConfigHandlerReplayTest {

    private HttpApiServer server;
    private HttpClient client;
    private final AtomicLong now = new AtomicLong(2_000_000_000_000L); // fixed wall clock

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static AuthInterceptor authInterceptor() {
        return new AuthInterceptor(token -> "good-writer".equals(token)
                ? new AuthInterceptor.AuthResult.Authenticated("writer", Set.of("write"))
                : new AuthInterceptor.AuthResult.Denied("unknown token"));
    }

    private static AclService aclService() {
        AclService acl = new AclService();
        acl.grant("app/", "writer", Set.of(AclService.Permission.READ, AclService.Permission.WRITE));
        return acl;
    }

    private static ConfigWriteService committingWriteService() {
        return new ConfigWriteService(
                (scope, command) -> new ConfigWriteService.ProposeCommitResult.Committed(1L), null, null);
    }

    private Clock clock() {
        return new Clock() {
            @Override public long currentTimeMillis() { return now.get(); }
            @Override public long nanoTime() { return now.get() * 1_000_000L; }
        };
    }

    private int start(ReplayGuard guard) throws Exception {
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry registry = new MetricsRegistry();
        server = new HttpApiServer(
                0, null, new HealthService(), new PrometheusExporter(registry),
                store, committingWriteService(), null, authInterceptor(), aclService(),
                StrongReadPolicy.defaultPolicy(), () -> NodeId.of(1),
                /* auditLog */ null, guard);
        server.start();
        client = HttpClient.newHttpClient();
        return server.port();
    }

    /** Builds a PUT carrying the bearer token + the given replay headers. */
    private HttpRequest put(int port, String key, String body, long timestampMs, String nonce) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/config/" + key))
                .header("Authorization", "Bearer good-writer")
                .header(ReplayGuard.TIMESTAMP_HEADER, String.valueOf(timestampMs))
                .header(ReplayGuard.NONCE_HEADER, nonce)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    @Test
    void verbatimReplayIsRejectedWhileFreshNonceIsAccepted() throws Exception {
        int port = start(new ReplayGuard(clock(), 300_000L, 1000));

        // 1) A valid PUT — capture the EXACT request (same headers + body).
        HttpRequest original = put(port, "app/feature", "on", now.get(), "nonce-A");
        HttpResponse<String> first = client.send(original, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, first.statusCode(), "the original valid PUT must commit: " + first.body());

        // 2) Replay the captured request VERBATIM -> rejected as a replay (409).
        HttpResponse<String> replay = client.send(original, HttpResponse.BodyHandlers.ofString());
        assertEquals(409, replay.statusCode(),
                "a verbatim capture-and-replay must be rejected (409 Conflict): " + replay.body());

        // 3) A fresh PUT with a NEW nonce (same token, current time) -> accepted.
        HttpRequest fresh = put(port, "app/feature", "off", now.get(), "nonce-B");
        HttpResponse<String> third = client.send(fresh, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, third.statusCode(), "a fresh nonce must be accepted: " + third.body());
    }

    @Test
    void staleTimestampIsRejected() throws Exception {
        int port = start(new ReplayGuard(clock(), 300_000L, 1000));
        // A request whose timestamp is well outside the ±300s window.
        HttpRequest stale = put(port, "app/feature", "on", now.get() - 600_000L, "nonce-S");
        HttpResponse<String> resp = client.send(stale, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode(), "a stale-timestamp request must be rejected (401): " + resp.body());
    }

    @Test
    void missingReplayHeadersAreRejectedWhenGuardEnabled() throws Exception {
        int port = start(new ReplayGuard(clock(), 300_000L, 1000));
        // Authenticated, but no replay headers at all -> MALFORMED -> 401.
        HttpRequest noHeaders = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/config/app/feature"))
                .header("Authorization", "Bearer good-writer")
                .PUT(HttpRequest.BodyPublishers.ofString("on"))
                .build();
        HttpResponse<String> resp = client.send(noHeaders, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode(),
                "an authenticated PUT missing replay headers must be rejected when the guard is on");
    }

    @Test
    void guardOffMeansHeadersAreNotRequired() throws Exception {
        // Back-compat: with no guard wired (default), a PUT without replay headers
        // commits as before. This is the pre-production default.
        int port = start(/* guard */ null);
        HttpRequest plain = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/config/app/feature"))
                .header("Authorization", "Bearer good-writer")
                .PUT(HttpRequest.BodyPublishers.ofString("on"))
                .build();
        HttpResponse<String> resp = client.send(plain, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "with the guard off, no replay headers are needed: " + resp.body());
    }
}

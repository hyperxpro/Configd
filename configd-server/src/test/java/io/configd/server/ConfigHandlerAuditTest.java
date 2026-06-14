package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.AuditLog;
import io.configd.api.AuthInterceptor;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.common.Storage;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.store.VersionedConfigStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S7/D-2 — HTTP-level audit-log completeness through the production
 * {@code ConfigHandler}. Drives a committed PUT, a committed DELETE, a denied
 * write (403), and an unauthenticated attempt (401), then asserts each produced
 * exactly one correct audit record and the chain verifies clean.
 */
final class ConfigHandlerAuditTest {

    private HttpApiServer server;
    private HttpClient client;
    private AuditLog auditLog;
    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static AuthInterceptor authInterceptor() {
        return new AuthInterceptor(token -> switch (token) {
            case "good-reader" -> new AuthInterceptor.AuthResult.Authenticated("reader", Set.of("read"));
            case "good-writer" -> new AuthInterceptor.AuthResult.Authenticated("writer", Set.of("write"));
            default -> new AuthInterceptor.AuthResult.Denied("unknown token");
        });
    }

    private static AclService aclService() {
        AclService acl = new AclService();
        acl.grant("app/", "reader", Set.of(AclService.Permission.READ));
        acl.grant("app/", "writer", Set.of(AclService.Permission.READ, AclService.Permission.WRITE));
        return acl;
    }

    private static ConfigWriteService committingWriteService() {
        return new ConfigWriteService(
                (scope, command) -> new ConfigWriteService.ProposeCommitResult.Committed(42L), null, null);
    }

    private int start() throws Exception {
        Clock clock = new Clock() {
            @Override public long currentTimeMillis() { return now.getAndIncrement(); }
            @Override public long nanoTime() { return now.get() * 1_000_000L; }
        };
        auditLog = new AuditLog(Storage.inMemory(), clock);
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry registry = new MetricsRegistry();
        server = new HttpApiServer(
                0, null, new HealthService(), new PrometheusExporter(registry),
                store, committingWriteService(), null, authInterceptor(), aclService(),
                StrongReadPolicy.defaultPolicy(), () -> NodeId.of(1),
                auditLog, /* replayGuard */ null);
        server.start();
        client = HttpClient.newHttpClient();
        return server.port();
    }

    private HttpResponse<String> send(int port, String method, String key, String token, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/config/" + key));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        b.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void everySecurityEventProducesExactlyOneAuditRecordAndChainVerifies() throws Exception {
        int port = start();

        // 1) committed PUT
        assertEquals(200, send(port, "PUT", "app/feature", "good-writer", "on").statusCode());
        // 2) committed DELETE
        assertEquals(200, send(port, "DELETE", "app/feature", "good-writer", null).statusCode());
        // 3) denied write (reader lacks WRITE) -> 403
        assertEquals(403, send(port, "PUT", "app/feature", "good-reader", "x").statusCode());
        // 4) unauthenticated attempt -> 401
        assertEquals(401, send(port, "PUT", "app/feature", null, "x").statusCode());

        List<AuditLog.Record> records = auditLog.records();
        assertEquals(4, records.size(), "four security events -> exactly four audit records");

        AuditLog.Record put = records.get(0);
        assertEquals("writer", put.actor());
        assertEquals("PUT", put.action());
        assertEquals("app/feature", put.target());
        assertEquals("committed seq=42", put.outcome());

        AuditLog.Record del = records.get(1);
        assertEquals("DELETE", del.action());
        assertEquals("committed seq=42", del.outcome());

        AuditLog.Record denied = records.get(2);
        assertEquals("reader", denied.actor(), "a forbidden write records the authenticated principal");
        assertTrue(denied.outcome().contains("FORBIDDEN"), "the deny outcome names the decision: " + denied.outcome());

        AuditLog.Record unauth = records.get(3);
        assertEquals("-", unauth.actor(), "an unauthenticated attempt records actor '-'");
        assertTrue(unauth.outcome().contains("UNAUTHENTICATED"), unauth.outcome());

        assertTrue(auditLog.verify().valid(), "the audit chain must verify clean");
        assertTrue(auditLog.verifyPersisted().valid(), "the persisted audit chain must verify clean");
    }

    @Test
    void noTokenStringEverAppearsInTheAuditTrail() throws Exception {
        int port = start();
        // The bearer credential value is "good-writer". After a committed PUT the
        // record must contain the principal "writer", never the token.
        assertEquals(200, send(port, "PUT", "app/secret", "good-writer", "v").statusCode());
        AuditLog.Record r = auditLog.records().get(0);
        assertEquals("writer", r.actor());
        assertTrue(!r.toString().contains("good-writer"), "the bearer token must never be in the audit record");
    }
}

package io.configd.conformance;

import io.configd.api.AclService;
import io.configd.api.AuthInterceptor;
import io.configd.api.ConfigWriteService;
import io.configd.api.AdminService;
import io.configd.client.CredentialSource;
import io.configd.client.ForbiddenException;
import io.configd.client.RetryPolicy;
import io.configd.client.http.ConfigdHttpClient;
import io.configd.client.http.GetOptions;
import io.configd.client.http.GetResult;
import io.configd.client.http.NodeEndpoints;
import io.configd.client.http.WriteOptions;
import io.configd.client.http.WriteOutcome;
import io.configd.common.NodeId;
import io.configd.api.HealthService;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.server.AdminApiHandler;
import io.configd.server.HttpApiServer;
import io.configd.server.StrongReadPolicy;
import io.configd.store.VersionedConfigStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-server conformance for the HTTP control plane: drives the thin {@link ConfigdHttpClient} against a
 * live {@link HttpApiServer} + {@link AdminApiHandler} (the actual routes, statuses, headers, bearer auth, ACL,
 * and the leadership-transfer 5th route) -- not a mock. Proves the client's get/put/delete wire (seq-from-body,
 * version-from-header), bearer authentication, the reserved-prefix ADMIN gate (403 without ADMIN), and the
 * transfer-leadership route interoperate with the real server.
 */
@Timeout(60)
@Tag("clause:D2-1")
@Tag("clause:D2-3")
@Tag("clause:D3-1")
@Tag("clause:D3-2")
@Tag("clause:D3-7")
@Tag("clause:D4-1")
@Tag("clause:D4-2")
@Tag("clause:D4-7")
@Tag("clause:D5-1..D5-5")
@Tag("clause:D6-1..D6-5")
@Tag("clause:A5-4")
@Tag("clause:A7-1")
@Tag("clause:A7-2")
class RealServerHttpTest {

    private HttpApiServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void getPutDeleteAndAdminAgainstRealServer() throws Exception {
        VersionedConfigStore store = new VersionedConfigStore();
        store.put("app/name", "configd".getBytes(StandardCharsets.UTF_8), 5L); // seed a readable key

        AuthInterceptor auth = new AuthInterceptor(token -> switch (token) {
            case "writer-tok" -> new AuthInterceptor.AuthResult.Authenticated("writer", Set.of("writer"));
            case "admin-tok" -> new AuthInterceptor.AuthResult.Authenticated("admin", Set.of("admin"));
            default -> new AuthInterceptor.AuthResult.Denied("unknown token");
        });
        AclService acl = new AclService();
        acl.grant("app/", "writer", Set.of(AclService.Permission.READ, AclService.Permission.WRITE));
        acl.grant("app/", "admin", Set.of(AclService.Permission.READ, AclService.Permission.WRITE));
        acl.grant("_acl/", "admin", Set.of(AclService.Permission.ADMIN));
        acl.grant("_system/", "admin", Set.of(AclService.Permission.ADMIN));

        ConfigWriteService writeService = new ConfigWriteService(
                (scope, keys, command) -> new ConfigWriteService.ProposeCommitResult.Committed(77L), null, null);

        AdminApiHandler.LeadershipAdmin leadership = new AdminApiHandler.LeadershipAdmin() {
            @Override
            public boolean hasGroup(int groupId) {
                return groupId == 0;
            }

            @Override
            public AdminService.AdminResult transferLeadership(int groupId, NodeId target) {
                return new AdminService.AdminResult.Success("initiated");
            }
        };

        MetricsRegistry registry = new MetricsRegistry();
        server = new HttpApiServer(0, null /* plaintext */, new HealthService(), new PrometheusExporter(registry),
                store, writeService, null /* readService: stale via store */, auth, acl,
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(1), null /* auditLog */,
                null /* replayGuard */, leadership);
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.port());

        // The writer principal: get a seeded key, put, delete.
        try (ConfigdHttpClient writer = client(base, "writer-tok")) {
            GetResult read = writer.blocking().get("app/name", GetOptions.defaults());
            assertTrue(read.found());
            assertArrayEquals("configd".getBytes(StandardCharsets.UTF_8), read.valueOrThrow());
            assertEquals(5L, read.version());

            WriteOutcome put = writer.blocking().put("app/color", "green".getBytes(StandardCharsets.UTF_8),
                    WriteOptions.defaults());
            assertEquals(77L, put.seq(), "seq parsed from the real server's 'Committed: seq=<N>' body");

            WriteOutcome del = writer.blocking().delete("app/color", WriteOptions.defaults());
            assertEquals(77L, del.seq());

            // The writer lacks ADMIN: a reserved-prefix read is 403 (policy disclosure closed).
            assertThrows(ForbiddenException.class, () -> writer.blocking().get("_acl/roles/x", GetOptions.defaults()));
            // ...and the ADMIN-gated transfer route is 403 for the writer.
            assertThrows(ForbiddenException.class, () -> writer.blocking().transferLeadership(0, 2));
        }

        // The admin principal: reserved-prefix read passes the ADMIN gate, transfer is initiated.
        try (ConfigdHttpClient admin = client(base, "admin-tok")) {
            // ADMIN gate passes (not 403): the key is absent, so this is a definite 404 and an empty result.
            GetResult reserved = admin.blocking().get("_acl/roles/x", GetOptions.defaults());
            assertFalse(reserved.found(), "admin passes the ADMIN gate; the key is simply absent (404)");

            // The 5th route: a 200 means transfer INITIATED (asynchronous). No exception means success.
            admin.blocking().transferLeadership(0, 2);
        }
    }

    private static ConfigdHttpClient client(URI base, String token) {
        return ConfigdHttpClient.builder()
                .endpoints(NodeEndpoints.of(base))
                .credentialSource(CredentialSource.staticBearer(token))
                .allowPlaintext(true)
                .retryPolicy(new RetryPolicy(Duration.ofMillis(10), Duration.ofMillis(100), 4))
                .build();
    }
}

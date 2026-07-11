package io.configd.conformance;

import io.configd.api.AclService;
import io.configd.api.AdminService;
import io.configd.api.AuthInterceptor;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.common.NodeId;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.server.AdminApiHandler;
import io.configd.server.HttpApiServer;
import io.configd.server.StrongReadPolicy;
import io.configd.store.VersionedConfigStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runner II -- SERVER-OBEYS, §05 routing: drives raw HTTP against a live {@link HttpApiServer} + real
 * {@link AdminApiHandler} (real routes, auth, ACL, and the {@code NotLeader}-to-{@code 503}+{@code X-Leader-Hint}
 * emission) -- a raw client, not {@link io.configd.client.http.ConfigdHttpClient}, so the exact status and
 * headers are observable. It proves the two server-side §05 guarantees a driver relies on:
 *
 * <ul>
 *   <li><b>R3-2</b> -- there is NO topology / shard-map / membership discovery endpoint; a driver cannot learn
 *       the cluster map, {@code N}, or peer endpoints from the wire (they are operator config). Every
 *       discovery-shaped path is a {@code 404}.</li>
 *   <li><b>R8-4</b> -- the {@code X-Leader-Hint} is <b>authorization-gated</b>: {@code checkAuth} precedes every
 *       hint site, so an unauthenticated caller gets {@code 401} and an unauthorized caller {@code 403} with
 *       <b>no</b> hint. An attacker does not learn a leader {@code NodeId} from a hint; only a principal already
 *       authorized for the key sees one.</li>
 * </ul>
 */
@Timeout(60)
class ServerObeysRoutingTest {

    private HttpApiServer server;
    private URI base;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void startServer() throws Exception {
        VersionedConfigStore store = new VersionedConfigStore();

        AuthInterceptor auth = new AuthInterceptor(token -> switch (token) {
            case "writer-tok" -> new AuthInterceptor.AuthResult.Authenticated("writer", Set.of("writer"));
            case "reader-tok" -> new AuthInterceptor.AuthResult.Authenticated("reader", Set.of("reader"));
            default -> new AuthInterceptor.AuthResult.Denied("unknown token");
        });
        AclService acl = new AclService();
        acl.grant("app/", "writer", Set.of(AclService.Permission.READ, AclService.Permission.WRITE));
        acl.grant("app/", "reader", Set.of(AclService.Permission.READ)); // authenticated but NOT permitted to write

        // A proposer that always returns NotLeader, so an AUTHORIZED write reaches the redirect path and the
        // server emits 503 + X-Leader-Hint: 2 -- the positive control that proves the hint IS emitted, but only
        // after auth. The hint value is filled by the ConfigWriteService's own (keyed) LeaderHintSupplier.
        ConfigWriteService writeService = new ConfigWriteService(
                (scope, keys, command) -> new ConfigWriteService.ProposeCommitResult.NotLeader(),
                null, null, (scope, key) -> NodeId.of(2));

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
                store, writeService, null /* readService */, auth, acl,
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(2), null /* auditLog */,
                null /* replayGuard */, leadership);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.port());
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @Tag("clause:R8-4")
    void theLeaderHintIsAuthorizationGatedNoAnonymousTopologyDisclosure() throws Exception {
        // Positive control: an AUTHORIZED writer's write reaches the NotLeader redirect and DOES receive the
        // hint -- a bare numeric NodeId (R2-2), disclosed only to a principal already authorized for the key.
        HttpResponse<String> authorized = put("/v1/config/app/x", "writer-tok", "v");
        assertEquals(503, authorized.statusCode(), "an authorized NotLeader write is a 503");
        assertEquals(Optional.of("2"), authorized.headers().firstValue("X-Leader-Hint"),
                "the hint is a bare numeric NodeId, emitted for the authorized principal");

        // Unauthenticated: 401 BEFORE any hint -- checkAuth precedes every hint site, so the attacker never
        // reaches the redirect and learns no leader NodeId.
        HttpResponse<String> anonymous = put("/v1/config/app/x", null, "v");
        assertEquals(401, anonymous.statusCode(), "no credential ⇒ 401");
        assertTrue(anonymous.headers().firstValue("WWW-Authenticate").isPresent(), "401 carries WWW-Authenticate");
        assertFalse(anonymous.headers().firstValue("X-Leader-Hint").isPresent(),
                "an unauthenticated caller gets NO leader hint (topology non-disclosure)");

        // Authenticated but unauthorized (reader lacks WRITE): 403 BEFORE any hint.
        HttpResponse<String> forbidden = put("/v1/config/app/x", "reader-tok", "v");
        assertEquals(403, forbidden.statusCode(), "authenticated but not permitted ⇒ 403");
        assertFalse(forbidden.headers().firstValue("X-Leader-Hint").isPresent(),
                "an unauthorized caller gets NO leader hint either — the hint is authz-gated");
    }

    @Test
    @Tag("clause:R3-2")
    void thereIsNoTopologyOrMembershipDiscoveryEndpoint() throws Exception {
        // R3-2: the server exposes no /shards, /topology, /members, /peers, or membership endpoint -- a driver
        // CANNOT discover the cluster map, N, or peer endpoints from the wire; it MUST be configured with the
        // NodeId-to-api-endpoint map. Every discovery-shaped path is a 404 (routed with a valid credential so the
        // 404 is the routing verdict, not an auth artifact).
        for (String path : new String[]{"/shards", "/topology", "/members", "/peers", "/v1/shards",
                "/v1/topology", "/v1/members"}) {
            assertEquals(404, get(path, "writer-tok").statusCode(), path + " must not exist (no discovery endpoint)");
        }
        // The leadership-transfer control route exists but discloses nothing about membership: a discovery-shaped
        // sub-resource under its namespace is a 404, not a members listing.
        assertEquals(404, get("/v1/admin/groups/0/members", "writer-tok").statusCode(),
                "the admin-groups namespace exposes no membership sub-resource");
    }

    private HttpResponse<String> put(String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .method("PUT", HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10)).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}

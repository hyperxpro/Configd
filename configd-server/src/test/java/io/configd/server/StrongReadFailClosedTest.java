package io.configd.server;

import io.configd.api.ConfigReadService;
import io.configd.api.HealthService;
import io.configd.common.NodeId;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.store.ReadResult;
import io.configd.store.VersionedConfigStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RR-020 / ADR-0030 INV-1: GLOBAL/security ("strong-read") keys MUST be served
 * via the fail-closed linearizable path. These tests drive a real
 * {@link HttpApiServer} over loopback and flip the leadership confirmer to model
 * a leader vs. a follower / partitioned leader.
 *
 * <p>The leadership confirmer ({@link ConfigReadService.LeadershipConfirmer})
 * returns {@code false} exactly when a linearizable read cannot be confirmed
 * (not leader / ReadIndex confirm fails / timeout). On a follower it returns
 * false, so {@code linearizableRead} returns null and the handler must DENY a
 * strong-read key — never serve the stale local copy that the backing store
 * still holds.
 */
final class StrongReadFailClosedTest {

    private HttpApiServer server;
    private HttpClient client;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------

    /** Backing store seeded so a stale local read WOULD succeed if allowed. */
    private VersionedConfigStore seededStore() {
        VersionedConfigStore store = new VersionedConfigStore();
        // A security key and an ordinary key both present locally (a follower's
        // local state). The fail-closed contract is the only thing that should
        // stop the security key from being served from here.
        store.put("secure/killswitch", "DENY".getBytes(), 7);
        store.put("app/feature", "on".getBytes(), 8);
        return store;
    }

    private ConfigReadService readService(VersionedConfigStore store, AtomicBoolean isLeader) {
        ConfigReadService.ConfigReader reader = new ConfigReadService.ConfigReader() {
            @Override public ReadResult get(String key) { return store.get(key); }
            @Override public ReadResult get(String key, long minVersion) { return store.get(key, minVersion); }
            @Override public Map<String, ReadResult> getPrefix(String prefix) { return store.getPrefix(prefix); }
            @Override public long currentVersion() { return store.currentVersion(); }
        };
        // confirmLeadership() == isLeader: a follower (false) makes
        // linearizableRead return null, modelling an unconfirmable read.
        return new ConfigReadService(reader, isLeader::get);
    }

    private int start(VersionedConfigStore store, ConfigReadService readService,
                      StrongReadPolicy policy, Supplier<NodeId> leaderHint) throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        server = new HttpApiServer(
                0, null, new HealthService(), new PrometheusExporter(registry),
                store, null, readService, null, null, policy, leaderHint);
        server.start();
        client = HttpClient.newHttpClient();
        java.lang.reflect.Field f = HttpApiServer.class.getDeclaredField("server");
        f.setAccessible(true);
        com.sun.net.httpserver.HttpServer s = (com.sun.net.httpserver.HttpServer) f.get(server);
        return s.getAddress().getPort();
    }

    private HttpResponse<String> get(int port, String pathAndQuery) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + pathAndQuery))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------
    // Strong-read served linearizably on the leader
    // ------------------------------------------------------------------

    @Test
    void leaderServesStrongReadKeyLinearizably() throws Exception {
        VersionedConfigStore store = seededStore();
        AtomicBoolean isLeader = new AtomicBoolean(true);
        int port = start(store, readService(store, isLeader),
                StrongReadPolicy.defaultPolicy(), () -> NodeId.of(1));

        // No consistency param: a strong-read key is ALWAYS linearizable.
        HttpResponse<String> resp = get(port, "/v1/config/secure/killswitch");
        assertEquals(200, resp.statusCode());
        assertEquals("DENY", resp.body());
        assertEquals("linearizable", resp.headers().firstValue("X-Consistency").orElse(""));
        assertEquals("true", resp.headers().firstValue("X-Strong-Read").orElse(""));
    }

    // ------------------------------------------------------------------
    // Fail-closed on a follower / partitioned leader  (the core INV-1 case)
    // ------------------------------------------------------------------

    @Test
    void followerFailsClosedForStrongReadKeyNeverServesStale() throws Exception {
        VersionedConfigStore store = seededStore();
        AtomicBoolean isLeader = new AtomicBoolean(false); // a follower
        int port = start(store, readService(store, isLeader),
                StrongReadPolicy.defaultPolicy(), () -> NodeId.of(3));

        HttpResponse<String> resp = get(port, "/v1/config/secure/killswitch");

        // 503 fail-closed, NOT the stale local "DENY" with 200.
        assertEquals(503, resp.statusCode(),
                "INV-1: a strong-read key on a non-leader must DENY, not serve local state");
        assertNotEquals("DENY", resp.body(),
                "the stale local value must NEVER appear in a fail-closed body");
        assertEquals("strong-read", resp.headers().firstValue("X-Fail-Closed").orElse(""));
        assertEquals("3", resp.headers().firstValue("X-Leader-Hint").orElse(""),
                "X-Leader-Hint must point the client at the known leader for a retry");
        assertTrue(resp.body().contains("ADR-0030 INV-1"));
    }

    @Test
    void leaderThatLosesConfirmationFailsClosed() throws Exception {
        // Model a partitioned leader: it believes it is leader but ReadIndex
        // cannot confirm quorum, so confirmLeadership() flips to false.
        VersionedConfigStore store = seededStore();
        AtomicBoolean confirmable = new AtomicBoolean(true);
        int port = start(store, readService(store, confirmable),
                StrongReadPolicy.defaultPolicy(), () -> NodeId.of(2));

        // Confirmable: served linearizably.
        assertEquals(200, get(port, "/v1/config/secure/killswitch").statusCode());

        // ReadIndex can no longer confirm leadership -> fail closed.
        confirmable.set(false);
        HttpResponse<String> resp = get(port, "/v1/config/secure/killswitch");
        assertEquals(503, resp.statusCode());
        assertEquals("strong-read", resp.headers().firstValue("X-Fail-Closed").orElse(""));
        assertNotEquals("DENY", resp.body());
    }

    @Test
    void strongReadKeyIgnoresExplicitStaleRequest() throws Exception {
        // Even if the client explicitly asks for stale, a strong-read key on a
        // follower must still fail closed — the requested consistency is ignored.
        VersionedConfigStore store = seededStore();
        AtomicBoolean isLeader = new AtomicBoolean(false);
        int port = start(store, readService(store, isLeader),
                StrongReadPolicy.defaultPolicy(), () -> NodeId.of(3));

        HttpResponse<String> resp = get(port, "/v1/config/secure/killswitch?consistency=stale");
        assertEquals(503, resp.statusCode(),
                "a strong-read key cannot be downgraded to stale via the query param");
        assertEquals("strong-read", resp.headers().firstValue("X-Fail-Closed").orElse(""));
    }

    @Test
    void failsClosedWhenNoLinearizableReadPathConfigured() throws Exception {
        // readService == null (stale-only node): a strong-read key has no safe
        // answer and must fail closed rather than fall through to the store.
        VersionedConfigStore store = seededStore();
        MetricsRegistry registry = new MetricsRegistry();
        server = new HttpApiServer(
                0, null, new HealthService(), new PrometheusExporter(registry),
                store, null, /* readService */ null, null, null,
                StrongReadPolicy.defaultPolicy(), () -> null);
        server.start();
        client = HttpClient.newHttpClient();
        java.lang.reflect.Field f = HttpApiServer.class.getDeclaredField("server");
        f.setAccessible(true);
        int port = ((com.sun.net.httpserver.HttpServer) f.get(server)).getAddress().getPort();

        HttpResponse<String> resp = get(port, "/v1/config/secure/killswitch");
        assertEquals(503, resp.statusCode());
        assertEquals("strong-read", resp.headers().firstValue("X-Fail-Closed").orElse(""));
        assertNotEquals("DENY", resp.body());
    }

    // ------------------------------------------------------------------
    // Ordinary (non-strong) key: stale serving on a follower is STILL allowed
    // ------------------------------------------------------------------

    @Test
    void ordinaryKeyOnFollowerStillServesStale() throws Exception {
        // The fail-closed contract applies ONLY to strong-read keys. An ordinary
        // key on a follower must keep serving its bounded-stale local copy — the
        // fix must not turn every follower read into a denial.
        VersionedConfigStore store = seededStore();
        AtomicBoolean isLeader = new AtomicBoolean(false);
        int port = start(store, readService(store, isLeader),
                StrongReadPolicy.defaultPolicy(), () -> NodeId.of(3));

        HttpResponse<String> resp = get(port, "/v1/config/app/feature");
        assertEquals(200, resp.statusCode());
        assertEquals("on", resp.body());
        assertEquals("stale", resp.headers().firstValue("X-Consistency").orElse(""));
        assertTrue(resp.headers().firstValue("X-Strong-Read").isEmpty());
    }

    // ------------------------------------------------------------------
    // Policy unit checks
    // ------------------------------------------------------------------

    @Test
    void blankPrefixRejected() {
        // A blank prefix would match every key and silently make everything a
        // strong read; the policy must reject it.
        assertThrows(IllegalArgumentException.class,
                () -> new StrongReadPolicy(Set.of("")));
    }

    @Test
    void emptyPolicyDisablesEnforcement() {
        StrongReadPolicy none = new StrongReadPolicy(Set.of());
        assertFalse(none.isStrongReadKey("secure/killswitch"));
    }

    @Test
    void customPrefixHonored() {
        StrongReadPolicy policy = new StrongReadPolicy(Set.of("global/", "acl/"));
        assertTrue(policy.isStrongReadKey("global/region-map"));
        assertTrue(policy.isStrongReadKey("acl/tenant-7"));
        assertFalse(policy.isStrongReadKey("secure/killswitch")); // not in this set
        assertFalse(policy.isStrongReadKey("app/feature"));
    }
}

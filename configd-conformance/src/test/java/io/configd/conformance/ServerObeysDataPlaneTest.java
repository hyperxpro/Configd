package io.configd.conformance;

import io.configd.api.AclService;
import io.configd.api.AuthInterceptor;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.client.BadRequestException;
import io.configd.client.CredentialSource;
import io.configd.client.RetryPolicy;
import io.configd.client.http.ConfigdHttpClient;
import io.configd.client.http.NodeEndpoints;
import io.configd.client.http.WriteOptions;
import io.configd.client.http.WriteOutcome;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.server.HttpApiServer;
import io.configd.server.StrongReadPolicy;
import io.configd.store.VersionedConfigStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runner II -- SERVER-OBEYS, HTTP data plane (§04 D-clauses that are a SERVER obligation). Drives requests against
 * a live {@link HttpApiServer} + {@code AdminApiHandler} (the actual routes, statuses, headers, auth gate, key
 * validation, ACL policy validation, strong-read fail-close, and the operational endpoints) -- no mock -- and
 * asserts the server obeys. Raw {@link HttpClient} is used where the assertion is on the exact status / header /
 * body the server emits (so nothing is masked by the reference client's exception mapping); the reference
 * {@link ConfigdHttpClient} is used for {@code _acl/} policy validation, where the client's typed
 * commit-vs-BadRequest surface is itself part of the contract.
 */
@Timeout(60)
class ServerObeysDataPlaneTest {

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private HttpApiServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // Routing (D2-2) and empty-key pre-auth (D2-4).

    @Test
    @Tag("clause:D2-2")
    void routingIsExactForFixedEndpointsPrefixForConfigElse404() throws Exception {
        URI base = startAuthOff();
        // prefix-match: /v1/config/{key} routes to the seeded key (200).
        assertEquals(200, raw("GET", base.resolve("/v1/config/app/name"), null, null).statusCode(),
                "/v1/config/ is prefix-routed (D2-2)");
        // exact-match: the fixed endpoint routes...
        assertEquals(200, raw("GET", base.resolve("/health/live"), null, null).statusCode(),
                "/health/live is exact-routed (D2-2)");
        // ...but a suffix variant of a fixed endpoint does not match -- it is 404, never the real endpoint.
        assertEquals(404, raw("GET", base.resolve("/metricsZ"), null, null).statusCode(),
                "a suffix variant of a fixed endpoint is 404, not the endpoint (D2-2)");
        // any other path is 404.
        assertEquals(404, raw("GET", base.resolve("/totally/unknown/path"), null, null).statusCode(),
                "an unknown path is 404 (D2-2)");
    }

    @Test
    @Tag("clause:D2-4")
    void emptyKeyIs400EmittedBeforeAuthentication() throws Exception {
        URI base = startAuthOn(); // auth is ON, yet the empty-key 400 is emitted at routing time, PRE-auth...
        HttpResponse<byte[]> r = raw("GET", base.resolve("/v1/config/"), null /* no bearer */, null);
        assertEquals(400, r.statusCode(),
                "/v1/config/ with no key is 400 at routing time, BEFORE auth (not 401) (D2-4)");
        assertTrue(bodyText(r).contains("Missing config key"), "the pre-auth routing 400 names the missing key");
    }

    @Test
    @Tag("clause:D3-5_D3-5a")
    void strongReadKeyFailsClosedWithNoLinearizablePathWired() throws Exception {
        URI base = startAuthOff(); // readService is null, so no linearizable path is wired
        // A strong-read (default secure/) key is force-linearizable; with no linearizable path it fails closed:
        // 503 + X-Fail-Closed: strong-read, and serves no stale value -- never a 200 with a local copy (D3-5).
        HttpResponse<byte[]> r = raw("GET", base.resolve("/v1/config/secure/kill-switch"), null, null);
        assertEquals(503, r.statusCode(), "a strong-read key with no linearizable path is 503, never stale (D3-5)");
        assertEquals(Optional.of("strong-read"), r.headers().firstValue("X-Fail-Closed"),
                "the fail-close is distinguished by X-Fail-Closed: strong-read (D3-5)");
        assertTrue(bodyText(r).contains("Fail-closed"), "the body is the fail-closed diagnostic, not a stale value");
    }

    @Test
    @Tag("clause:D4-5")
    void aclPutIsValidatedAsPolicyPreCommitAndIncompleteIsNotAnError() throws Exception {
        URI base = startAuthOn();
        try (ConfigdHttpClient admin = httpClient(base, "admin-tok")) {
            // A malformed policy shape (an unknown role-line effect) is a 400 "Invalid ACL policy" pre-commit
            // (the store is unchanged) -- a policy-shape rejection distinct from a key/value-limit 400 (D4-5).
            assertThrows(BadRequestException.class,
                    () -> admin.blocking().put("_acl/roles/reader",
                            "not-an-effect READ app/".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()),
                    "a malformed _acl/ policy is rejected 400 pre-commit (D4-5)");
            // A well-formed but incomplete policy (a binding to a not-yet-defined role) is intentionally not an
            // error -- it parses and commits (D4-5).
            WriteOutcome committed = admin.blocking().put("_acl/bindings/alice",
                    "some-undefined-role".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults());
            assertEquals(77L, committed.seq(), "a well-formed (even incomplete) _acl/ policy commits (D4-5)");
        }
    }

    // Key and value validation (D8).

    @Test
    @Tag("clause:D8-1")
    void serverEnforcesOnlyNonBlankAnd1024ByteKeyLengthBeforeBlank() throws Exception {
        URI base = startAuthOff();
        // Over-length key: 400 with the length reason.
        String overLong = "a".repeat(1025);
        HttpResponse<byte[]> tooLong = raw("PUT", base.resolve("/v1/config/" + overLong), null, bytes("v"));
        assertEquals(400, tooLong.statusCode(), "a >1024-byte key is rejected (D8-1)");
        assertTrue(bodyText(tooLong).contains("1024"), "the reason is the length limit (D8-1)");
        // Length-before-blank: a key that is BOTH over-length AND blank (1025 spaces) yields the LENGTH reason.
        HttpResponse<byte[]> longBlank = raw("PUT", base.resolve("/v1/config/" + "%20".repeat(1025)), null, bytes("v"));
        assertEquals(400, longBlank.statusCode());
        assertTrue(bodyText(longBlank).contains("length"), "length is checked before blank (D8-1)");
        // A blank (but short) key: the non-blank reason.
        HttpResponse<byte[]> blank = raw("PUT", base.resolve("/v1/config/%20"), null, bytes("v"));
        assertEquals(400, blank.statusCode());
        assertTrue(bodyText(blank).contains("blank"), "a blank key is rejected non-blank (D8-1)");
    }

    @Test
    @Tag("clause:D8-2")
    void serverAcceptsAKeyThatViolatesTheClientSidePathGrammar() throws Exception {
        URI base = startAuthOff();
        // §1's strict path grammar (seg-char only, no empty segments) is a CLIENT-side contract; the server does
        // NOT enforce it and ACCEPTS a violating legacy key verbatim (D8-2). An empty-segment key `a//b`...
        HttpResponse<byte[]> emptySeg = raw("PUT", base.resolve("/v1/config/a//b"), null, bytes("v"));
        assertEquals(200, emptySeg.statusCode(), "an empty-segment key (§1-illegal) is accepted by the server (D8-2)");
        assertTrue(bodyText(emptySeg).contains("Committed: seq="), "the write committed");
        // ...and a key bearing a non-seg-char (a space) is likewise accepted.
        HttpResponse<byte[]> spaced = raw("PUT", base.resolve("/v1/config/a%20b"), null, bytes("v"));
        assertEquals(200, spaced.statusCode(), "a space-bearing key (§1-illegal) is accepted by the server (D8-2)");
    }

    @Test
    @Tag("clause:D8-3")
    void serverRejectsAValueOverOneMebibyte() throws Exception {
        URI base = startAuthOff();
        byte[] tooBig = new byte[1_048_577]; // 1 MiB + 1
        HttpResponse<byte[]> r = raw("PUT", base.resolve("/v1/config/big"), null, tooBig);
        assertEquals(400, r.statusCode(), "a >1 MiB value is rejected as ValidationFailed → 400 (D8-3)");
        assertTrue(bodyText(r).contains("value size exceeds"), "the reason is the value-size limit (D8-3)");
    }

    @Test
    @Tag("clause:D8-4")
    void keyValidationIsPostAuthExceptTheEmptyKey() throws Exception {
        URI base = startAuthOn();
        // A bad (over-length) key without a token is 401 -- authentication runs before key validation, so the
        // caller gets 401, never a 400 that would leak that the key was malformed (D8-4).
        String overLong = "a".repeat(1025);
        HttpResponse<byte[]> unauth = raw("PUT", base.resolve("/v1/config/" + overLong), null, bytes("v"));
        assertEquals(401, unauth.statusCode(), "auth precedes key validation (401 before 400) (D8-4)");
        // The ONE exception: the empty-key 400 is emitted at routing time, pre-auth (still 400 without a token).
        HttpResponse<byte[]> empty = raw("GET", base.resolve("/v1/config/"), null, null);
        assertEquals(400, empty.statusCode(), "the empty-key 400 is the pre-auth exception (D8-4)");
    }

    // Operational endpoints (D10).

    @Test
    @Tag("clause:D10-1")
    void healthIsGetOnlyRealJsonAndUnauthenticated() throws Exception {
        URI base = startAuthOn(); // auth is configured, yet /health/* is NOT authenticated
        HttpResponse<byte[]> live = raw("GET", base.resolve("/health/live"), null /* no token */, null);
        assertEquals(200, live.statusCode(), "/health/live is unauthenticated and 200 when healthy (D10-1)");
        assertEquals(Optional.of("application/json"), live.headers().firstValue("Content-Type"),
                "/health/* is REAL JSON (the exception to the plaintext-body rule) (D10-1)");
        String body = bodyText(live);
        assertTrue(body.startsWith("{\"healthy\":") && body.contains("\"checks\":"),
                "the health body is the structured JSON shape (D10-1)");
        // GET-only.
        assertEquals(405, raw("POST", base.resolve("/health/live"), null, bytes("x")).statusCode(),
                "/health/* is GET-only (405 otherwise) (D10-1)");
    }

    @Test
    @Tag("clause:D10-2")
    void metricsIsGetOnlyPrometheusBearerGatedAndExactPath() throws Exception {
        URI base = startAuthOn();
        // Bearer-gated when auth is configured: a missing token gets 401 + WWW-Authenticate: Bearer (authentication,
        // not authorization -- there is no ACL for scraping).
        HttpResponse<byte[]> noToken = raw("GET", base.resolve("/metrics"), null, null);
        assertEquals(401, noToken.statusCode(), "/metrics is bearer-gated when auth is on (D10-2)");
        assertEquals(Optional.of("Bearer"), noToken.headers().firstValue("WWW-Authenticate"),
                "the 401 carries WWW-Authenticate: Bearer (D10-2)");
        // A valid token: 200 Prometheus text exposition.
        HttpResponse<byte[]> ok = raw("GET", base.resolve("/metrics"), "admin-tok", null);
        assertEquals(200, ok.statusCode());
        assertTrue(ok.headers().firstValue("Content-Type").orElse("").startsWith("text/plain; version=0.0.4"),
                "/metrics is Prometheus text exposition (D10-2)");
        // GET-only.
        assertEquals(405, raw("POST", base.resolve("/metrics"), "admin-tok", bytes("x")).statusCode(),
                "/metrics is GET-only (405 otherwise) (D10-2)");
        // Exact-match path: a suffix variant is 404, not the metrics endpoint.
        assertEquals(404, raw("GET", base.resolve("/metricsZ"), "admin-tok", null).statusCode(),
                "/metrics is exact-match; /metricsZ is 404 (D10-2)");
    }

    // Fixtures.

    /** An auth-OFF server (readService null) for routing / key-validation / strong-read fail-close. */
    private URI startAuthOff() throws IOException {
        VersionedConfigStore store = new VersionedConfigStore();
        store.put("app/name", "configd".getBytes(StandardCharsets.UTF_8), 5L);
        ConfigWriteService write = new ConfigWriteService(
                (scope, keys, command) -> new ConfigWriteService.ProposeCommitResult.Committed(77L), null, null);
        server = new HttpApiServer(0, null, new HealthService(), new PrometheusExporter(new MetricsRegistry()),
                store, write, null /* readService */, null /* auth OFF */, null /* acl */,
                StrongReadPolicy.defaultPolicy(), (scope, key) -> null /* leader unknown */);
        server.start();
        return URI.create("http://127.0.0.1:" + server.port());
    }

    /** An auth-ON server (bearer admin-tok, ACL with ADMIN on _acl/) for pre-auth / bearer-gate / policy tests. */
    private URI startAuthOn() throws IOException {
        VersionedConfigStore store = new VersionedConfigStore();
        AuthInterceptor auth = new AuthInterceptor(token -> switch (token) {
            case "admin-tok" -> new AuthInterceptor.AuthResult.Authenticated("admin", Set.of("admin"));
            default -> new AuthInterceptor.AuthResult.Denied("unknown token");
        });
        AclService acl = new AclService();
        acl.grant("_acl/", "admin", Set.of(AclService.Permission.ADMIN));
        acl.grant("app/", "admin", Set.of(AclService.Permission.READ, AclService.Permission.WRITE));
        ConfigWriteService write = new ConfigWriteService(
                (scope, keys, command) -> new ConfigWriteService.ProposeCommitResult.Committed(77L), null, null);
        server = new HttpApiServer(0, null, new HealthService(), new PrometheusExporter(new MetricsRegistry()),
                store, write, null /* readService */, auth, acl,
                StrongReadPolicy.defaultPolicy(), (scope, key) -> null);
        server.start();
        return URI.create("http://127.0.0.1:" + server.port());
    }

    private ConfigdHttpClient httpClient(URI base, String token) {
        return ConfigdHttpClient.builder()
                .endpoints(NodeEndpoints.of(base))
                .credentialSource(CredentialSource.staticBearer(token))
                .allowPlaintext(true)
                .retryPolicy(new RetryPolicy(Duration.ofMillis(10), Duration.ofMillis(100), 4))
                .build();
    }

    private static HttpResponse<byte[]> raw(String method, URI uri, String bearer, byte[] body) throws Exception {
        HttpRequest.BodyPublisher pub = (body == null)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).method(method, pub);
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String bodyText(HttpResponse<byte[]> r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }
}

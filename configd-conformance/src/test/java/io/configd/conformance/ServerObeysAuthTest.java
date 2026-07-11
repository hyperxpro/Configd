package io.configd.conformance;

import com.sun.net.httpserver.HttpServer;
import io.configd.api.AclService;
import io.configd.api.AdminService;
import io.configd.api.AuthInterceptor;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.client.AuthFailedException;
import io.configd.client.ConfigdClientConfig;
import io.configd.client.ConfigdException;
import io.configd.client.CredentialSource;
import io.configd.client.ForbiddenException;
import io.configd.client.HostileServerLimits;
import io.configd.client.RetryPolicy;
import io.configd.client.UnavailableException;
import io.configd.client.edge.ConfigdEdgeClient;
import io.configd.client.edge.SubscribeOptions;
import io.configd.client.edge.Subscription;
import io.configd.client.http.ConfigdHttpClient;
import io.configd.client.http.GetOptions;
import io.configd.client.http.NodeEndpoints;
import io.configd.client.http.WriteOptions;
import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.common.auth.AuthResult;
import io.configd.common.auth.Authenticator;
import io.configd.common.auth.AuthenticatorChain;
import io.configd.common.auth.Credential;
import io.configd.common.auth.DenyReason;
import io.configd.common.auth.Principal;
import io.configd.common.config.ConfigSource;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.fanout.ShardResolver;
import io.configd.distribution.fanout.SlowConsumerGovernor;
import io.configd.distribution.fanout.SlowConsumerPolicyConfig;
import io.configd.distribution.fanout.WatchAuthorizer;
import io.configd.distribution.fanout.WatchTarget;
import io.configd.distribution.wire.WatchCursor;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.server.AdminApiHandler;
import io.configd.server.HttpApiServer;
import io.configd.server.StrongReadPolicy;
import io.configd.server.fanout.EdgeAuthConfig;
import io.configd.server.fanout.EdgeCertGate;
import io.configd.server.fanout.FanOutServer;
import io.configd.server.fanout.RegistryFanOutSessionMetrics;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedConfigStore;
import io.configd.store.VersionedValue;
import io.configd.client.tls.ClientTls;
import io.configd.transport.TlsConfig;
import io.configd.transport.TlsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Conformance Runner II -- SERVER-OBEYS (and, where the clause binds the driver's reaction to a hostile control
 * plane, CLIENT-CONFORMS) for the §03 authentication lifecycle. Drives the reference clients against a live
 * {@link HttpApiServer} + {@link AdminApiHandler} and a live {@link FanOutServer} + {@code EdgeAuthGateHandler},
 * plus a small scriptable HTTP double for the fail-closed cases the real server does not naturally emit (an
 * unknown {@code WWW-Authenticate} challenge, a server-issued session token). Each test genuinely asserts the
 * wire outcome the RFC pins: the 401/403 split, per-request bearer, fail-closed authenticator-unavailable (503
 * retryable vs 401 reauth), no credential echo, and the edge invariant that authentication precedes
 * authorization precedes any data frame.
 */
@Timeout(120)
class ServerObeysAuthTest {

    private static final ShardResolver SINGLE_SHARD = t -> new int[]{0};
    private static final long T0 = 1_700_000_000_000L;
    private static final char[] PASS = "changeit".toCharArray();
    private static final List<String> TLS13 = List.of("TLSv1.3");
    private static final List<String> TLS13_CIPHERS = List.of("TLS_AES_256_GCM_SHA384", "TLS_AES_128_GCM_SHA256");

    /** Leadership seam stub: no group is local, so the transfer route is never the subject under test here. */
    private static final AdminApiHandler.LeadershipAdmin NO_LEADERSHIP = new AdminApiHandler.LeadershipAdmin() {
        @Override
        public boolean hasGroup(int groupId) {
            return false;
        }

        @Override
        public AdminService.AdminResult transferLeadership(int groupId, NodeId target) {
            return new AdminService.AdminResult.Success("noop");
        }
    };

    private final List<AutoCloseable> cleanups = new ArrayList<>();

    @TempDir
    Path certDir;
    private Path serverKeystore;
    private Path serverTruststore;
    private Path clientKeystore;
    private Path clientTruststore;

    @AfterEach
    void tearDown() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            try {
                cleanups.get(i).close();
            } catch (Exception ignored) {
                // best-effort teardown
            }
        }
        cleanups.clear();
    }

    @Test
    @Tag("clause:AU5-1")
    @Tag("clause:AU6-2")
    void badCredentialIs401AuthenticatedButUnauthorizedIs403() throws Exception {
        // AU5-1: a missing/invalid credential is 401 (authentication); an authenticated principal that lacks the
        // capability is 403 (authorization). AU6-2: a valid credential proves only WHO the caller is -- the same
        // principal is still 403 on a key it may not touch, so authentication is not authorization.
        VersionedConfigStore store = new VersionedConfigStore();
        store.put("app/name", "configd".getBytes(UTF_8), 5L);
        AclService acl = new AclService();
        acl.grant("app/", "writer", Set.of(AclService.Permission.READ, AclService.Permission.WRITE));
        URI base = startHttp(twoTokenAuth(), acl, null, store);

        try (ConfigdHttpClient bad = httpClient(base, "nope-token")) {
            assertThrows(AuthFailedException.class, () -> bad.blocking().get("app/name", GetOptions.defaults()),
                    "an unknown credential is a 401 (authentication), surfaced as AuthFailedException");
        }
        try (ConfigdHttpClient writer = httpClient(base, "writer-tok")) {
            // Authentication SUCCEEDS (else this would be a 401): the reserved-prefix read needs ADMIN, so the
            // authenticated writer is a 403 -- authentication is not authorization.
            assertThrows(ForbiddenException.class, () -> writer.blocking().get("_acl/roles/x", GetOptions.defaults()),
                    "authenticated but not authorized ⇒ 403 (ForbiddenException), never 401");
        }
    }

    @Test
    @Tag("clause:AU4-2")
    void bearerIsPresentedPerRequestNotPersistedServerSide() throws Exception {
        // AU4-2: an HTTP bearer is a PER-REQUEST credential -- the server keeps no session, and the driver
        // re-presents it on every request. Proven two ways: (1) one client doing two writes authenticates twice
        // (the token is on both requests -- a client that assumed server-side persistence and dropped the header
        // on the 2nd would 401); (2) a credential-less client is 401 regardless of the earlier authenticated
        // request (no ambient session).
        List<String> tokensSeen = new CopyOnWriteArrayList<>();
        AuthInterceptor counting = new AuthInterceptor(token -> {
            tokensSeen.add(token);
            return "good-tok".equals(token)
                    ? new AuthInterceptor.AuthResult.Authenticated("svc", Set.of("writer"))
                    : new AuthInterceptor.AuthResult.Denied("unknown token");
        });
        AclService acl = new AclService();
        acl.grant("app/", "svc", Set.of(AclService.Permission.READ, AclService.Permission.WRITE));
        URI base = startHttp(counting, acl, null, new VersionedConfigStore());

        try (ConfigdHttpClient c = httpClient(base, "good-tok")) {
            c.blocking().put("app/a", "1".getBytes(UTF_8), WriteOptions.defaults());
            c.blocking().put("app/b", "2".getBytes(UTF_8), WriteOptions.defaults());
        }
        assertEquals(List.of("good-tok", "good-tok"), tokensSeen,
                "the bearer rides EVERY request — the server persisted nothing between them");

        try (ConfigdHttpClient noCred = ConfigdHttpClient.builder()
                .endpoints(NodeEndpoints.of(base)).allowPlaintext(true).retryPolicy(fastHttp()).build()) {
            assertThrows(AuthFailedException.class,
                    () -> noCred.blocking().put("app/c", "3".getBytes(UTF_8), WriteOptions.defaults()),
                    "no credential ⇒ 401 despite a prior authenticated request — no server-side session");
        }
    }

    @Test
    @Tag("clause:AU4-3")
    void driverPresentsAgainstAuthDisabledYet401StillMeansAuthRequired() throws Exception {
        // AU4-3: a driver stays ready to authenticate even against an auth-DISABLED deployment (it presents its
        // credential; the server ignores it and the op still works), and it MUST treat a 401 from an
        // auth-ENABLED deployment as "authentication is required here" -- it never infers "auth is off".
        URI open = startHttp(null, new AclService(), null, new VersionedConfigStore()); // auth disabled
        try (ConfigdHttpClient credentialed = httpClient(open, "some-token")) {
            credentialed.blocking().put("app/x", "v".getBytes(UTF_8), WriteOptions.defaults());
        }

        URI secured = startHttp(twoTokenAuth(), new AclService(), null, new VersionedConfigStore());
        try (ConfigdHttpClient sameDriver = httpClient(secured, "not-a-known-token")) {
            assertThrows(AuthFailedException.class,
                    () -> sameDriver.blocking().get("app/x", GetOptions.defaults()),
                    "a 401 is 'authenticate here' — the driver does not carry over 'auth is off'");
        }
    }

    @Test
    @Tag("clause:AU5-2")
    void authenticatorUnavailableIs503RetryableWhileBadCredentialIs401Reauth() throws Exception {
        // AU5-2: a configured authenticator that is UNAVAILABLE fails CLOSED to a retryable 503 (never a silent
        // downgrade to anonymous, never a 401); a genuinely bad credential is a 401 (re-authenticate). The
        // driver must distinguish the two: 503 means retryable (UnavailableException after the bounded budget),
        // 401 means reauth (AuthFailedException, not retried). Driven through the external AuthenticatorChain path,
        // whose AuthResult.Unavailable the handler maps to 503 (the in-core AuthInterceptor cannot express it).
        AuthenticatorChain chain = chainByToken(token -> switch (token) {
            case "idp-outage" -> new AuthResult.Unavailable("JWKS unreachable");
            default -> new AuthResult.Denied(DenyReason.INVALID_CREDENTIAL, "bad token");
        });
        URI base = startHttp(null, new AclService(), chain, new VersionedConfigStore());

        try (ConfigdHttpClient outage = httpClient(base, "idp-outage")) {
            // Every attempt is 503; the client retries within the bounded budget then surfaces a RETRYABLE
            // Unavailable -- it never collapses a transient authenticator outage into a permanent auth failure.
            // 503-unavailable surfaces as the RETRYABLE UnavailableException -- a sibling of, never a subtype
            // of, AuthFailedException -- so the thrown type itself is the "not a reauth signal" proof, and the
            // bad-credential block below proves the converse (an invalid credential IS AuthFailedException).
            assertThrows(UnavailableException.class,
                    () -> outage.blocking().put("app/x", "v".getBytes(UTF_8), WriteOptions.defaults()),
                    "a transient authenticator outage is retryable Unavailable, never collapsed to a 401 reauth");
        }
        try (ConfigdHttpClient bad = httpClient(base, "forged")) {
            assertThrows(AuthFailedException.class,
                    () -> bad.blocking().put("app/x", "v".getBytes(UTF_8), WriteOptions.defaults()),
                    "an invalid credential is a 401 reauth — distinct from the retryable 503");
        }
    }

    @Test
    @Tag("clause:AU5-4")
    void forbiddenIsTerminalAndUnauthenticatedDoesNotHotLoop() throws Exception {
        // AU5-4: a 403 is permanently forbidden for this principal (do not retry unchanged); a 401 is
        // (re)authenticate (do not hot-loop the same credential). Asserted against a scripted control plane by
        // the request COUNT: exactly one request in each case -- no blind retry.
        try (ScriptedHttp s = new ScriptedHttp()) {
            s.enqueue(403, Map.of(), "denied");
            try (ConfigdHttpClient c = httpClient(s.baseUri(), "tok")) {
                assertThrows(ForbiddenException.class, () -> c.blocking().get("k", GetOptions.defaults()));
            }
            assertEquals(1, s.requestCount(), "403 ⇒ do not retry unchanged");
        }
        try (ScriptedHttp s = new ScriptedHttp()) {
            s.enqueue(401, Map.of("WWW-Authenticate", "Bearer"), "Unauthorized");
            try (ConfigdHttpClient c = httpClient(s.baseUri(), "tok")) {
                assertThrows(AuthFailedException.class, () -> c.blocking().get("k", GetOptions.defaults()));
            }
            assertEquals(1, s.requestCount(), "401 ⇒ reauthenticate, never hot-loop the same credential");
        }
    }

    @Test
    @Tag("clause:AU5-3")
    void aRejectionNeverEchoesTheCredentialOnEitherPlane() throws Exception {
        // AU5-3: a rejection MUST NOT echo the presented credential. HTTP: the 401 body/headers carry no copy of
        // the token; edge: the AUTH_FAIL diagnostic (surfaced through the client's sanitized exception chain)
        // carries no copy either. A raw HTTP probe inspects the real server response the reference client never
        // exposes to the caller.
        String secret = "s3cr3t-do-not-echo-9f3a2b";
        URI base = startHttp(twoTokenAuth(), new AclService(), null, seededStore());
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(base.resolve("/v1/config/app/name"))
                        .header("Authorization", "Bearer " + secret).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode());
        assertFalse(resp.body().contains(secret), "the 401 body must not echo the presented token");
        assertFalse(resp.headers().map().toString().contains(secret), "no response header echoes the token");

        try (EdgeHarness edge = new EdgeHarness(bearerChain("edge-good", "p"), null)) {
            ConfigdException terminal = subscribeToTerminal(edge.port, secret, 1);
            assertFalse(exceptionText(terminal).contains(secret),
                    "the edge AUTH_FAIL diagnostic must not echo the presented token");
        }
    }

    @Test
    @Tag("clause:AU7-1")
    void anUnknownAuthChallengeFailsClosed() throws Exception {
        // AU7-1: a driver MUST fail closed on a WWW-Authenticate challenge scheme it does not recognize -- it
        // MUST NOT downgrade to a weaker scheme, attempt an unknown handshake, or proceed unauthenticated. The
        // client treats the unknown-challenge 401 as a plain reauth failure and makes exactly ONE request (no
        // follow-up Negotiate/Digest leg, no anonymous retry).
        try (ScriptedHttp s = new ScriptedHttp()) {
            s.enqueue(401, Map.of("WWW-Authenticate", "Negotiate, Digest realm=\"x\""), "Unauthorized");
            try (ConfigdHttpClient c = httpClient(s.baseUri(), "tok")) {
                assertThrows(AuthFailedException.class, () -> c.blocking().get("k", GetOptions.defaults()));
            }
            assertEquals(1, s.requestCount(),
                    "the client did not attempt the unknown scheme nor proceed unauthenticated");
        }
    }

    @Test
    @Tag("clause:AU7-3")
    void driverIgnoresAServerIssuedSessionAndRePresentsItsOwnCredential() throws Exception {
        // AU7-3 (with AU2-3): a Configd-issued auth session/token would be a NAMED FORWARD EXTENSION the protocol
        // does not define today. A driver MUST NOT adopt a server-offered session/cookie it could replay; it
        // re-presents its OWN credential on the next request. Here the first 200 hands back a Set-Cookie + an
        // X-Configd-Session; the second request must still carry the ORIGINAL bearer and no adopted session.
        try (ScriptedHttp s = new ScriptedHttp()) {
            s.enqueue(200, Map.of("Content-Type", "application/octet-stream", "X-Config-Version", "1",
                    "Set-Cookie", "sid=server-issued", "X-Configd-Session", "srv-token-xyz"), "v1");
            s.enqueue(200, Map.of("Content-Type", "application/octet-stream", "X-Config-Version", "2"), "v2");
            try (ConfigdHttpClient c = httpClient(s.baseUri(), "orig-tok")) {
                c.blocking().get("k", GetOptions.defaults());
                c.blocking().get("k", GetOptions.defaults());
            }
            Map<String, String> second = s.requestHeaders(1);
            assertEquals("Bearer orig-tok", second.get("Authorization"),
                    "the driver re-presents its OWN credential, not a server-issued session");
            assertFalse(second.containsKey("Cookie"), "the driver did not adopt the server-issued session cookie");
        }
    }

    @Test
    @Tag("clause:AU7-2")
    void aNewServerAuthenticatorDoesNotRequireADriverChange() throws Exception {
        // AU7-2: because the bearer token is opaque (AU2-2) and the contract is stable regardless of how the
        // server verifies it (AU2-1), a deployment may change/add authenticators without any driver change. The
        // IDENTICAL client bytes (same token, same config) authenticate against two DIFFERENT server chains: a
        // lone bearer authenticator, and a two-authenticator chain where a foreign one declines (NOT_THIS) and
        // the bearer accepts.
        try (EdgeHarness lone = new EdgeHarness(bearerChain("shared-tok", "svc"), admitAll())) {
            lone.publish(0, 1, "app/k", "v1");
            assertHydrates(lone.port, "shared-tok", "app/k", "v1");
        }
        AuthenticatorChain composed = new AuthenticatorChain(List.of(
                declining("foreign"), accepting("shared-tok", "svc")));
        try (EdgeHarness added = new EdgeHarness(composed, admitAll())) {
            added.publish(0, 1, "app/k", "v2");
            assertHydrates(added.port, "shared-tok", "app/k", "v2");
        }
    }

    @Test
    @Tag("clause:AU6-1")
    void oneCredentialIsOnePrincipalOnBothPlanes() throws Exception {
        // AU6-1: the same credential presented on the HTTP and edge planes resolves to the SAME principal. The
        // HTTP AuthInterceptor records the principal it derives; the edge authorizer records the principal the
        // chain derived (the authorizer's principal argument). The two are asserted equal -- one credential, one
        // identity, both planes.
        AtomicReference<String> httpPrincipal = new AtomicReference<>();
        AuthInterceptor auth = new AuthInterceptor(token -> {
            if ("shared-tok".equals(token)) {
                httpPrincipal.set("svc-principal");
                return new AuthInterceptor.AuthResult.Authenticated("svc-principal", Set.of("reader"));
            }
            return new AuthInterceptor.AuthResult.Denied("unknown token");
        });
        AclService acl = new AclService();
        acl.grant("app/", "svc-principal", Set.of(AclService.Permission.READ));
        URI base = startHttp(auth, acl, null, seededStore());
        try (ConfigdHttpClient c = httpClient(base, "shared-tok")) {
            assertArrayEquals("configd".getBytes(UTF_8),
                    c.blocking().get("app/name", GetOptions.defaults()).valueOrThrow());
        }

        AtomicReference<String> edgePrincipal = new AtomicReference<>();
        WatchAuthorizer capture = new WatchAuthorizer() {
            @Override
            public boolean authorizeWatch(String principal, Set<String> roles, WatchTarget target) {
                return true;
            }

            @Override
            public boolean authorizeSubscribe(String principal, Set<String> roles) {
                edgePrincipal.set(principal);
                return true;
            }
        };
        try (EdgeHarness edge = new EdgeHarness(bearerChain("shared-tok", "svc-principal"), capture)) {
            edge.publish(0, 1, "app/k", "v");
            assertHydrates(edge.port, "shared-tok", "app/k", "v");
        }

        assertNotNull(httpPrincipal.get(), "HTTP resolved a principal");
        assertEquals(httpPrincipal.get(), edgePrincipal.get(),
                "the same credential is the SAME principal on both planes");
    }

    @Test
    @Tag("clause:AU8-1..8-4")
    void edgeAuthnPrecedesAuthzPrecedesDataWithZeroDataOnAnyTerminal() throws Exception {
        // AU8-1: the 401/403 split holds on the edge -- a bad credential is a 401-class terminal (AUTH_FAIL maps to
        // AuthFailedException), an authenticated-but-unauthorized subscription is a 403-class terminal
        // (NOT_AUTHORIZED maps to ForbiddenException). AU8-2: authentication (the credential) precedes authorization
        // (the subscription check) precedes any data frame, and the terminal close carries ZERO preceding data.
        // AU8-3/AU8-4 are exercised structurally: the principal the chain derives is the one the authorizer
        // evaluates, and the edge credential rides the AUTH frame (transport mapping). A denying authorizer +
        // a chain that accepts only "good" separates the two failures on one server.
        WatchAuthorizer denyAll = (p, r, t) -> false; // authorizeSubscribe defaults to false, so it denies too
        try (EdgeHarness edge = new EdgeHarness(bearerChain("good", "p"), denyAll)) {
            edge.publish(0, 1, "app/secret", "leak-me-not");

            // (a) bad credential: authentication fails FIRST (before any authorization or data): a 401-class
            // terminal, and the view never saw a byte.
            EdgeProbe badAuth = subscribe(edge.port, "bad", 2);
            try (ConfigdEdgeClient client = badAuth.client) {
                ConfigdException terminal = assertThrows(ConfigdException.class,
                        () -> badAuth.sub.awaitHydrated(Duration.ofSeconds(20)));
                assertTrue(causeChainHas(terminal, AuthFailedException.class),
                        "bad credential ⇒ 401-class AUTH_FAIL, distinct from a 403");
                assertTrue(badAuth.sub.view().get("app/secret").isEmpty(), "no data frame preceded the terminal");
            }

            // (b) valid credential, unauthorized subscription: authentication SUCCEEDS, then authorization
            // denies: a 403-class terminal, again with zero data.
            EdgeProbe denied = subscribe(edge.port, "good", 2);
            try (ConfigdEdgeClient client = denied.client) {
                assertThrows(ForbiddenException.class, () -> denied.sub.awaitHydrated(Duration.ofSeconds(20)),
                        "authenticated but unauthorized ⇒ 403-class NOT_AUTHORIZED, never a data frame first");
                assertTrue(denied.sub.view().get("app/secret").isEmpty(), "no data frame preceded the terminal");
            }
        }
    }

    @Test
    @Tag("clause:F6A-5")
    void anOverCapCredentialIsRejectedBeforeVerificationWithZeroData() throws Exception {
        // F6A-5 (§06): the edge enforces credential caps and refuses an OVER-CAP credential BEFORE the
        // (possibly expensive) verification runs -- a 401-class AUTH_FAIL with zero data frames. Configured with
        // a low token cap (32 B) and a comfortably higher pre-auth frame ceiling, a ~300 B token clears the
        // frame decoder but exceeds the token cap, so the gate closes AUTH_FAIL ("credential exceeds the
        // permitted size") without ever authenticating or streaming.
        String overCapToken = "x".repeat(300); // > the 32 B cap, < the 16 KiB pre-auth frame ceiling
        try (EdgeHarness edge = new EdgeHarness(bearerChain("small-ok", "p"), admitAll(), 32, null)) {
            edge.publish(0, 1, "app/x", "leak-me-not");
            EdgeProbe probe = subscribe(edge.port, overCapToken, 2);
            try (ConfigdEdgeClient client = probe.client) {
                ConfigdException terminal = assertThrows(ConfigdException.class,
                        () -> probe.sub.awaitHydrated(Duration.ofSeconds(20)));
                assertTrue(causeChainHas(terminal, AuthFailedException.class),
                        "an over-cap credential is a 401-class AUTH_FAIL");
                assertTrue(probe.sub.view().get("app/x").isEmpty(),
                        "the over-cap credential was refused before any data frame");
            }
        }
    }

    @Test
    @Tag("clause:F9-3")
    void mtlsCertDnIsAuthoritativeOverAnAdvisoryEdgeId() throws Exception {
        // F9-3 (§06): under mTLS the authoritative identity is the VERIFIED client-cert Subject DN; a
        // self-asserted edgeId in the SUBSCRIBE frame is advisory and MUST NOT be trusted -- the server overrides
        // it with the cert identity. The client here presents a cert with DN "CN=edge-client,..." AND asserts a
        // spoofed edgeId "CN=spoofed-admin,..."; the principal the server hands the authorizer must be the cert
        // DN, never the spoofed edgeId.
        generateCerts();
        TlsManager serverTls = new TlsManager(new TlsConfig(serverKeystore, serverKeystore, serverTruststore,
                true, TLS13_CIPHERS, TLS13, PASS));
        AtomicReference<String> edgePrincipal = new AtomicReference<>();
        WatchAuthorizer capture = new WatchAuthorizer() {
            @Override
            public boolean authorizeWatch(String principal, Set<String> roles, WatchTarget target) {
                return true;
            }

            @Override
            public boolean authorizeSubscribe(String principal, Set<String> roles) {
                edgePrincipal.set(principal);
                return true;
            }
        };
        try (EdgeHarness edge = new EdgeHarness(mtlsChain(), capture, 8192, serverTls)) {
            edge.publish(0, 1, "app/k", "v");
            ClientTls clientTls = ClientTls.mutualTls(clientKeystore, PASS, clientTruststore, PASS);
            ConfigdClientConfig config = ConfigdClientConfig.builder()
                    .endpoint("localhost", edge.port)
                    .tls(clientTls)
                    .trustUnverified()
                    .retryPolicy(new RetryPolicy(Duration.ofMillis(10), Duration.ofMillis(100), 5))
                    .limits(longIdle())
                    .build();
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
                Subscription sub = client.subscribeFullStore(
                        SubscribeOptions.defaults().withEdgeId("CN=spoofed-admin,O=attacker"));
                sub.awaitHydrated(Duration.ofSeconds(30));
            }
        }
        assertNotNull(edgePrincipal.get(), "the server authorized the subscription on a derived identity");
        assertTrue(edgePrincipal.get().contains("edge-client"),
                "the server-derived identity is the client CERTIFICATE DN, not a wire field");
        assertFalse(edgePrincipal.get().contains("spoofed-admin"),
                "the self-asserted advisory edgeId was NOT trusted — the cert DN overrode it");
    }

    private URI startHttp(AuthInterceptor auth, AclService acl, AuthenticatorChain chain,
                          VersionedConfigStore store) throws IOException {
        MetricsRegistry registry = new MetricsRegistry();
        ConfigWriteService writeService = new ConfigWriteService(
                (scope, keys, command) -> new ConfigWriteService.ProposeCommitResult.Committed(77L), null, null);
        HttpApiServer server = new HttpApiServer(0, null, new HealthService(), new PrometheusExporter(registry),
                store, writeService, null /* readService: stale via store */, auth, acl,
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(1), null /* audit */,
                null /* replayGuard */, NO_LEADERSHIP, chain);
        server.start();
        cleanups.add(() -> server.stop(0));
        return URI.create("http://127.0.0.1:" + server.port());
    }

    private static AuthInterceptor twoTokenAuth() {
        AclDrivenTokens tokens = new AclDrivenTokens();
        return new AuthInterceptor(tokens);
    }

    /** writer-tok / admin-tok map to their roles; everything else is denied (the RealServerHttpTest shape). */
    private static final class AclDrivenTokens implements AuthInterceptor.TokenValidator {
        @Override
        public AuthInterceptor.AuthResult validate(String token) {
            return switch (token) {
                case "writer-tok" -> new AuthInterceptor.AuthResult.Authenticated("writer", Set.of("writer"));
                case "admin-tok" -> new AuthInterceptor.AuthResult.Authenticated("admin", Set.of("admin"));
                default -> new AuthInterceptor.AuthResult.Denied("unknown token");
            };
        }
    }

    private static VersionedConfigStore seededStore() {
        VersionedConfigStore store = new VersionedConfigStore();
        store.put("app/name", "configd".getBytes(UTF_8), 5L);
        return store;
    }

    private ConfigdHttpClient httpClient(URI base, String token) {
        return ConfigdHttpClient.builder()
                .endpoints(NodeEndpoints.of(base))
                .credentialSource(CredentialSource.staticBearer(token))
                .allowPlaintext(true)
                .retryPolicy(fastHttp())
                .build();
    }

    private static RetryPolicy fastHttp() {
        return new RetryPolicy(Duration.ofMillis(5), Duration.ofMillis(30), 3);
    }

    private static AuthenticatorChain chainByToken(Function<String, AuthResult> byToken) {
        return new AuthenticatorChain(List.of(new Authenticator() {
            @Override
            public String type() {
                return "test-bearer";
            }

            @Override
            public boolean canAttempt(Credential credential) {
                return credential instanceof Credential.BearerToken;
            }

            @Override
            public AuthResult authenticate(Credential credential) {
                return byToken.apply(((Credential.BearerToken) credential).token());
            }
        }));
    }

    /** An authenticator that OWNS bearer tokens and accepts exactly {@code token} (else INVALID_CREDENTIAL). */
    private static Authenticator accepting(String token, String principal) {
        return new Authenticator() {
            @Override
            public String type() {
                return "bearer";
            }

            @Override
            public boolean canAttempt(Credential credential) {
                return credential instanceof Credential.BearerToken;
            }

            @Override
            public AuthResult authenticate(Credential credential) {
                String presented = ((Credential.BearerToken) credential).token();
                return token.equals(presented)
                        ? new AuthResult.Authenticated(new Principal(principal, Set.of(), "test"))
                        : new AuthResult.Denied(DenyReason.INVALID_CREDENTIAL, "bad token");
            }
        };
    }

    /** A foreign authenticator that declines every bearer token (NOT_THIS_AUTHENTICATOR, so the chain continues). */
    private static Authenticator declining(String type) {
        return new Authenticator() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public boolean canAttempt(Credential credential) {
                return credential instanceof Credential.BearerToken;
            }

            @Override
            public AuthResult authenticate(Credential credential) {
                return new AuthResult.Denied(DenyReason.NOT_THIS_AUTHENTICATOR, "foreign issuer");
            }
        };
    }

    private static AuthenticatorChain bearerChain(String token, String principal) {
        return AuthenticatorChain.build(List.of("bearer"), mapConfig(Map.of(
                "configd.auth.bearer.token", token,
                "configd.auth.bearer.principal", principal)));
    }

    /** An mTLS chain for the AUTH-frame seam; a cert client authenticates at the handshake regardless. */
    private static AuthenticatorChain mtlsChain() {
        return AuthenticatorChain.build(List.of("mtls"), mapConfig(Map.of()));
    }

    private static WatchAuthorizer admitAll() {
        return new WatchAuthorizer() {
            @Override
            public boolean authorizeWatch(String principal, Set<String> roles, WatchTarget target) {
                return true;
            }

            @Override
            public boolean authorizeSubscribe(String principal, Set<String> roles) {
                return true;
            }
        };
    }

    private void assertHydrates(int port, String token, String key, String value) throws Exception {
        EdgeProbe probe = subscribe(port, token, 5);
        try (ConfigdEdgeClient client = probe.client) {
            probe.sub.awaitHydrated(Duration.ofSeconds(30));
            await("hydrated " + key, () -> probe.sub.view().get(key)
                    .map(v -> new String(v, UTF_8).equals(value)).orElse(false));
            assertArrayEquals(value.getBytes(UTF_8), probe.sub.view().get(key).orElseThrow());
        }
    }

    /** Opens an authenticated full-store subscription; the caller owns the returned client (closes it). */
    private EdgeProbe subscribe(int port, String token, int maxAttempts) {
        ConfigdClientConfig config = ConfigdClientConfig.builder()
                .endpoint("127.0.0.1", port)
                .allowPlaintext(true)
                .trustUnverified()
                .credentialSource(CredentialSource.staticBearer(token))
                .retryPolicy(new RetryPolicy(Duration.ofMillis(10), Duration.ofMillis(60), maxAttempts))
                .limits(longIdle())
                .build();
        ConfigdEdgeClient client = ConfigdEdgeClient.open(config);
        return new EdgeProbe(client, client.subscribeFullStore(SubscribeOptions.defaults()));
    }

    /** Subscribes and blocks until the auth terminal, returning the raised {@link ConfigdException}. */
    private ConfigdException subscribeToTerminal(int port, String token, int maxAttempts) throws Exception {
        EdgeProbe probe = subscribe(port, token, maxAttempts);
        try (ConfigdEdgeClient client = probe.client) {
            probe.sub.awaitHydrated(Duration.ofSeconds(20));
            throw new AssertionError("expected an auth terminal, but hydration succeeded");
        } catch (ConfigdException e) {
            return e;
        }
    }

    /** A live edge client + its full-store subscription; a plain holder (the caller closes the client). */
    private static final class EdgeProbe {
        final ConfigdEdgeClient client;
        final Subscription sub;

        EdgeProbe(ConfigdEdgeClient client, Subscription sub) {
            this.client = client;
            this.sub = sub;
        }
    }

    /** A live single-shard FanOutServer with a pluggable auth chain + authorizer; plaintext loopback. */
    private static final class EdgeHarness implements AutoCloseable {
        private final FanOutServer server;
        private final FanOutBuffer buffer;
        private final AtomicReference<ConfigSnapshot> state = new AtomicReference<>(ConfigSnapshot.EMPTY);
        final int port;

        EdgeHarness(AuthenticatorChain chain, WatchAuthorizer authorizer) throws Exception {
            this(chain, authorizer, 8_192, null);
        }

        EdgeHarness(AuthenticatorChain chain, WatchAuthorizer authorizer, int maxAuthTokenBytes,
                    TlsManager tls) throws Exception {
            MetricsRegistry registry = new MetricsRegistry();
            RegistryFanOutSessionMetrics metrics = new RegistryFanOutSessionMetrics(registry);
            this.buffer = new FanOutBuffer(10_000);
            SlowConsumerGovernor governor = new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), metrics);
            EdgeAuthConfig edgeAuth = new EdgeAuthConfig(chain, 16_384, maxAuthTokenBytes,
                    Duration.ofHours(1).toMillis());
            this.server = new FanOutServer(
                    Map.of(0, buffer),
                    Map.of(0, new SnapshotReplaySource(state::get)),
                    new int[]{0}, SINGLE_SHARD, WatchCursor.INITIAL_TOPOLOGY_EPOCH,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    tls, FanOutConfig.defaults(),
                    FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES, FanOutServer.DEFAULT_MAX_SESSIONS,
                    governor, metrics, Clock.system(), authorizer, edgeAuth, EdgeCertGate.OFF);
            this.server.start();
            this.port = server.localPort();
        }

        void publish(long from, long to, String key, String value) {
            byte[] bytes = value.getBytes(UTF_8);
            ConfigDelta delta = new ConfigDelta(from, to, List.of(new ConfigMutation.Put(key, bytes)));
            HamtMap<String, VersionedValue> data = state.get().data().put(key, new VersionedValue(bytes, to, T0));
            state.set(new ConfigSnapshot(data, to, T0));
            buffer.publish(new CommitNotification(to, T0, delta));
        }

        @Override
        public void close() {
            server.close();
        }
    }

    private static HostileServerLimits longIdle() {
        HostileServerLimits d = HostileServerLimits.defaults();
        return new HostileServerLimits(d.maxFrameBytes(), d.connectTimeoutMs(), d.handshakeTimeoutMs(),
                30_000, d.maxSnapshotTotalBytes(), d.maxSnapshotChunks());
    }

    // mTLS cert material, generated lazily -- only the F9-3 case needs it.

    private void generateCerts() throws Exception {
        serverKeystore = certDir.resolve("server-ks.p12");
        serverTruststore = certDir.resolve("server-ts.p12");
        clientKeystore = certDir.resolve("client-ks.p12");
        clientTruststore = certDir.resolve("client-ts.p12");
        Path serverPem = certDir.resolve("server.pem");
        Path clientPem = certDir.resolve("client.pem");
        genKeyPair(serverKeystore, "server", "CN=localhost,O=configd-conformance", "san=dns:localhost,ip:127.0.0.1");
        genKeyPair(clientKeystore, "client", "CN=edge-client,O=configd-conformance", null);
        exportCert(serverKeystore, "server", serverPem);
        exportCert(clientKeystore, "client", clientPem);
        importCert(clientTruststore, "server", serverPem);  // the client verifies the server
        importCert(serverTruststore, "client", clientPem);  // the server verifies the client cert
    }

    private static void genKeyPair(Path keystore, String alias, String dname, String san) throws Exception {
        var cmd = new ArrayList<>(List.of(
                "keytool", "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-dname", dname, "-validity", "2",
                "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", "changeit",
                "-keypass", "changeit"));
        if (san != null) {
            cmd.add("-ext");
            cmd.add(san);
        }
        run(cmd.toArray(new String[0]));
    }

    private static void exportCert(Path keystore, String alias, Path pem) throws Exception {
        run("keytool", "-exportcert", "-alias", alias, "-keystore", keystore.toString(),
                "-storepass", "changeit", "-rfc", "-file", pem.toString());
    }

    private static void importCert(Path truststore, String alias, Path pem) throws Exception {
        run("keytool", "-importcert", "-alias", alias, "-file", pem.toString(),
                "-keystore", truststore.toString(), "-storepass", "changeit", "-storetype", "PKCS12", "-noprompt");
    }

    private static void run(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + command[1] + "\n" + out);
        }
    }

    /** A minimal FIFO-scripted loopback HTTP server that records each request's headers, for client assertions. */
    private static final class ScriptedHttp implements AutoCloseable {
        private final HttpServer server;
        private final Queue<Scripted> responses = new ConcurrentLinkedQueue<>();
        private final List<Map<String, String>> requests = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger count = new AtomicInteger();

        private record Scripted(int status, Map<String, String> headers, String body) {
        }

        ScriptedHttp() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, v.isEmpty() ? "" : v.get(0)));
                requests.add(headers);
                count.incrementAndGet();
                Scripted r = responses.poll();
                if (r == null) {
                    r = new Scripted(500, Map.of(), "no scripted response");
                }
                r.headers().forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
                byte[] body = r.body().getBytes(StandardCharsets.UTF_8);
                if (body.length == 0) {
                    exchange.sendResponseHeaders(r.status(), -1);
                } else {
                    exchange.sendResponseHeaders(r.status(), body.length);
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            server.start();
        }

        void enqueue(int status, Map<String, String> headers, String body) {
            responses.add(new Scripted(status, headers, body));
        }

        URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        int requestCount() {
            return count.get();
        }

        Map<String, String> requestHeaders(int index) {
            synchronized (requests) {
                return requests.get(index);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static ConfigSource mapConfig(Map<String, String> m) {
        return new ConfigSource() {
            @Override
            public Optional<String> getString(String key) {
                return Optional.ofNullable(m.get(key));
            }

            @Override
            public Set<String> keysWithPrefix(String prefix) {
                return m.keySet().stream().filter(k -> k.startsWith(prefix)).collect(Collectors.toSet());
            }
        };
    }

    private static boolean causeChainHas(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    private static String exceptionText(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(String.valueOf(c.getMessage())).append('\n');
        }
        return sb.toString();
    }

    private static void await(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("timed out awaiting: " + description);
    }
}

package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.AuditLog;
import io.configd.api.AuthInterceptor;
import io.configd.api.ConfigReadService;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.api.ReplayGuard;
import io.configd.common.Clock;
import io.configd.common.ConfigScope;
import io.configd.common.NodeId;
import io.configd.common.Storage;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.store.ReadResult;
import io.configd.store.VersionedConfigStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The full control-plane contract for the admin / control-plane HTTP API, run identically against
 * <b>all three</b> transports: {@link JdkAdminApiServerContractTest} starts the
 * JDK {@link HttpApiServer}, {@link NettyAdminApiServerContractTest} starts the Netty
 * {@link NettyHttpApiServer} on its auto-selected tier, and {@link NettyAdminApiServerNioFallbackTest}
 * forces the pure-Java NIO tier. Because all three adapters delegate to the same
 * {@link AdminApiHandler}, every security control - authn (Bearer/401 + {@code WWW-Authenticate}), authz
 * (ACL/403), privilege-escalation refusal, audit completeness + chain verification, replay
 * (401/409), strong-read fail-close, and the path-normalization evasion
 * vectors - must hold byte-for-byte on each. A control that passes on JDK but not Netty is a
 * migration regression, so this is an equivalence proof, not a re-implementation.
 *
 * <p>The per-server bind is the only thing the subclasses vary; everything else - assertions,
 * failure messages, behaviour - is shared here. Each subclass exposes its bound port via
 * {@link ServerHandle#port()} rather than reflection on a private field, since the Netty adapter has
 * no such field to reflect on (the JDK adapter's {@link HttpApiServer#port()} is wrapped the same way).
 *
 * <p><b>(load-bearing, do not weaken).</b> The strong-read key is derived from
 * {@link URI#getPath()} (percent-decoded, not normalized, not lower-cased), and BOTH adapters build
 * that {@code java.net.URI} the identical way ({@code getRequestURI()} on the JDK exchange, {@code new
 * URI(request.uri())} on Netty), so the classification is byte-identical across transports. The
 * five evasion-vector cases ({@code %73ecure}, {@code secure%2F}, {@code secure/../}, {@code
 * //secure}, query string) are sent with the path-and-query VERBATIM ({@link #getRaw}) so the encoded
 * bytes reach the server unmodified, and they genuinely fail closed on the Netty and NIO subclasses,
 * not just on the JDK one.
 */
@Timeout(60)
abstract class AbstractAdminApiServerContract {

    /** A started server: its bound port and a stop hook (transport-agnostic). */
    interface ServerHandle {
        int port();

        void stop();
    }

    /**
     * Every constructor argument the two server adapters share (the identical 13-arg shape). The
     * subclasses build their transport from this carrier; {@code sslContext}, {@code writeService},
     * {@code readService}, {@code authInterceptor}, {@code aclService}, {@code auditLog} and
     * {@code replayGuard} default to {@code null} (a control under test wires only what it needs),
     * while {@code strongReadPolicy} and {@code leaderHint} are always supplied.
     */
    record ServerSpec(SSLContext sslContext,
                      HealthService healthService,
                      PrometheusExporter prometheusExporter,
                      VersionedConfigStore configStore,
                      ConfigWriteService writeService,
                      ConfigReadService readService,
                      AuthInterceptor authInterceptor,
                      AclService aclService,
                      StrongReadPolicy strongReadPolicy,
                      BiFunction<ConfigScope, String, NodeId> leaderHint,
                      AuditLog auditLog,
                      ReplayGuard replayGuard) {
    }

    /** Starts the transport under test from {@code spec} on an ephemeral port (0). */
    abstract ServerHandle startServer(ServerSpec spec) throws Exception;

    private ServerHandle server;
    private HttpClient client;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private int start(ServerSpec spec) throws Exception {
        server = startServer(spec);
        client = HttpClient.newHttpClient();
        return server.port();
    }

    /** "good-reader" -> reader principal; "good-writer" -> writer principal; else denied. */
    private static AuthInterceptor authInterceptor() {
        return new AuthInterceptor(token -> switch (token) {
            case "good-reader" -> new AuthInterceptor.AuthResult.Authenticated("reader", Set.of("read"));
            case "good-writer" -> new AuthInterceptor.AuthResult.Authenticated("writer", Set.of("write"));
            default -> new AuthInterceptor.AuthResult.Denied("unknown token");
        });
    }

    /** reader: READ on "app/"; writer: READ+WRITE on "app/". Neither has access to "locked/". */
    private static AclService aclService() {
        AclService acl = new AclService();
        acl.grant("app/", "reader", Set.of(AclService.Permission.READ));
        acl.grant("app/", "writer", Set.of(AclService.Permission.READ, AclService.Permission.WRITE));
        return acl;
    }

    /** A write service whose proposer always commits - auth is the gate under test. */
    private static ConfigWriteService committingWriteService() {
        ConfigWriteService.RaftProposer proposer =
                (scope, keys, command) -> new ConfigWriteService.ProposeCommitResult.Committed(1L);
        return new ConfigWriteService(proposer, null, null);
    }

    /** A write service whose proposer commits at seq=42 - the audit-completeness fixture. */
    private static ConfigWriteService committingWriteServiceSeq42() {
        return new ConfigWriteService(
                (scope, keys, command) -> new ConfigWriteService.ProposeCommitResult.Committed(42L), null, null);
    }

    /** Spec used by the auth + escalation cases: committing writer, the two principals, the per-prefix ACL. */
    private ServerSpec authSpec() {
        VersionedConfigStore store = new VersionedConfigStore();
        store.put("app/feature", "on".getBytes(), 1);
        store.put("locked/secret", "shh".getBytes(), 2);
        MetricsRegistry registry = new MetricsRegistry();
        return new ServerSpec(null, new HealthService(), new PrometheusExporter(registry),
                store, committingWriteService(), /* readService */ null, authInterceptor(), aclService(),
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(1), /* auditLog */ null, /* replayGuard */ null);
    }

    /** A request to {@code /v1/config/<path>} with an optional bearer token + optional body. */
    private HttpResponse<String> send(int port, String method, String path, String token, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpRequest.BodyPublisher pub = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        b.method(method, pub);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A GET of the given (already-encoded) path-and-query, sent VERBATIM (no client re-encoding). */
    private HttpResponse<String> get(int port, String pathAndQuery) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + pathAndQuery))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Sends a GET with the path-and-query used VERBATIM (no client-side re-encoding); used for the
     *  path-normalization evasion vectors. */
    private HttpResponse<String> getRaw(int port, String rawPathAndQuery) throws Exception {
        return client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + rawPathAndQuery))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void getWithNoTokenIsUnauthenticated() throws Exception {
        int port = start(authSpec());
        HttpResponse<String> resp = send(port, "GET", "/v1/config/app/feature", null, null);
        assertEquals(401, resp.statusCode(), "a missing bearer token must be 401 (authenticate(null) -> Denied)");
        assertEquals("Bearer", resp.headers().firstValue("WWW-Authenticate").orElse(null),
                "a 401 MUST carry a WWW-Authenticate: Bearer challenge (RFC 7235 §3.1)");
        assertNotEquals("on", resp.body(), "no value may be served without authentication");
    }

    @Test
    void getWithInvalidTokenIsUnauthenticated() throws Exception {
        int port = start(authSpec());
        HttpResponse<String> resp = send(port, "GET", "/v1/config/app/feature", "bogus", null);
        assertEquals(401, resp.statusCode(), "an unknown token must be 401");
        assertEquals("Bearer", resp.headers().firstValue("WWW-Authenticate").orElse(null));
        assertTrue(resp.body().contains("authentication required"),
                "the 401 body must say authentication is required: " + resp.body());
    }

    @Test
    void getWithMalformedAuthorizationHeaderIsUnauthenticated() throws Exception {
        // A non-"Bearer " header leaves the token null, so authenticate(null) denies it (401, not 403).
        int port = start(authSpec());
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/config/app/feature"))
                .header("Authorization", "Basic Zm9vOmJhcg==") // not Bearer
                .GET().build();
        HttpResponse<String> basic = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, basic.statusCode(), "a non-Bearer Authorization header must not authenticate (401)");
        assertEquals("Bearer", basic.headers().firstValue("WWW-Authenticate").orElse(null));
    }

    // PUT/DELETE with missing/malformed/invalid credentials must also 401, not 403 - tested
    // independently since each method is its own attack surface.

    @Test
    void putWithNoTokenIsUnauthenticated() throws Exception {
        int port = start(authSpec());
        HttpResponse<String> resp = send(port, "PUT", "/v1/config/app/feature", null, "off");
        assertEquals(401, resp.statusCode(), "an unauthenticated PUT must be 401");
        assertEquals("Bearer", resp.headers().firstValue("WWW-Authenticate").orElse(null));
    }

    @Test
    void deleteWithNoTokenIsUnauthenticated() throws Exception {
        int port = start(authSpec());
        HttpResponse<String> resp = send(port, "DELETE", "/v1/config/app/feature", null, null);
        assertEquals(401, resp.statusCode(), "an unauthenticated DELETE must be 401");
        assertEquals("Bearer", resp.headers().firstValue("WWW-Authenticate").orElse(null));
    }

    @Test
    void putWithMalformedAuthorizationHeaderIsUnauthenticated() throws Exception {
        int port = start(authSpec());
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/config/app/feature"))
                .header("Authorization", "Basic Zm9vOmJhcg==")
                .PUT(HttpRequest.BodyPublishers.ofString("off")).build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode(), "a non-Bearer PUT must not authenticate (401)");
    }

    @Test
    void putWithInvalidTokenIsUnauthenticated() throws Exception {
        int port = start(authSpec());
        HttpResponse<String> resp = send(port, "PUT", "/v1/config/app/feature", "bogus", "off");
        assertEquals(401, resp.statusCode(), "a PUT with an unknown token must be 401");
    }

    @Test
    void readerCanGetButCannotWrite() throws Exception {
        int port = start(authSpec());

        HttpResponse<String> get = send(port, "GET", "/v1/config/app/feature", "good-reader", null);
        assertEquals(200, get.statusCode(), "reader with READ must be allowed to GET");
        assertEquals("on", get.body());

        HttpResponse<String> put = send(port, "PUT", "/v1/config/app/feature", "good-reader", "off");
        assertEquals(403, put.statusCode(), "reader without WRITE must be denied a PUT");

        HttpResponse<String> del = send(port, "DELETE", "/v1/config/app/feature", "good-reader", null);
        assertEquals(403, del.statusCode(), "reader without WRITE must be denied a DELETE");
    }

    @Test
    void writerCanReadWriteAndDelete() throws Exception {
        int port = start(authSpec());

        HttpResponse<String> get = send(port, "GET", "/v1/config/app/feature", "good-writer", null);
        assertEquals(200, get.statusCode());

        HttpResponse<String> put = send(port, "PUT", "/v1/config/app/feature", "good-writer", "off");
        assertEquals(200, put.statusCode(), "writer with WRITE must be allowed to PUT (committing proposer)");
        assertTrue(put.body().startsWith("Committed"), "a committed write returns 200 Committed: " + put.body());

        HttpResponse<String> del = send(port, "DELETE", "/v1/config/app/feature", "good-writer", null);
        assertEquals(200, del.statusCode(), "writer with WRITE must be allowed to DELETE");
    }

    @Test
    void perKeyAclDeniesAccessOutsideGrantedPrefix() throws Exception {
        int port = start(authSpec());
        HttpResponse<String> get = send(port, "GET", "/v1/config/locked/secret", "good-reader", null);
        assertEquals(403, get.statusCode(), "a valid principal with no ACL on this prefix must be denied");
        assertNotEquals("shh", get.body(), "the value outside the granted prefix must not leak");

        HttpResponse<String> put = send(port, "PUT", "/v1/config/locked/secret", "good-writer", "x");
        assertEquals(403, put.statusCode(), "even the writer has no grant on locked/");
    }

    // Privilege-escalation coverage: an authenticated caller must not be able to escalate a READ
    // grant into a WRITE/DELETE, nor reach a key outside its granted prefix - each case is a 403
    // (authenticated, unauthorized), never a 401 and never a success. There is no HTTP membership or
    // restore endpoint (membership goes through Raft's proposeConfigChange; restore is an operator
    // script), so privilege control for those lives at the Raft/CLI layer, not here.

    @Test
    void readScopedPrincipalCannotEscalateToWrite() throws Exception {
        int port = start(authSpec());
        HttpResponse<String> put = send(port, "PUT", "/v1/config/app/feature", "good-reader", "off");
        assertEquals(403, put.statusCode(), "read-scoped principal must not escalate to WRITE");
    }

    @Test
    void readScopedPrincipalCannotEscalateToDelete() throws Exception {
        int port = start(authSpec());
        HttpResponse<String> del = send(port, "DELETE", "/v1/config/app/feature", "good-reader", null);
        assertEquals(403, del.statusCode(), "read-scoped principal must not escalate to DELETE");
    }

    @Test
    void writerCannotCrossIntoAnUngrantedPrefixOnDelete() throws Exception {
        int port = start(authSpec());
        HttpResponse<String> del = send(port, "DELETE", "/v1/config/locked/secret", "good-writer", null);
        assertEquals(403, del.statusCode(), "a writer must not cross into an ungranted prefix on DELETE");
    }

    @Test
    void putWithEmptyBodyIsRejectedAfterAuth() throws Exception {
        int port = start(authSpec());
        HttpResponse<String> put = send(port, "PUT", "/v1/config/app/feature", "good-writer", "");
        assertEquals(400, put.statusCode(), "an empty PUT body must be rejected with 400 after the auth gate");
    }

    @Test
    void missingKeyInPathIsRejected() throws Exception {
        int port = start(authSpec());
        HttpResponse<String> resp = send(port, "GET", "/v1/config/", "good-reader", null);
        assertEquals(400, resp.statusCode(), "a request with no config key must be rejected");
    }

    // The following tests assert on the AuditLog after the requests; it is built here, wired into the
    // spec, and reachable for the post-request assertion. Requests are sent sequentially (client.send
    // blocks for the response, and the handler audits before writing the response), so the record
    // order is deterministic on every transport.

    private final AtomicLong auditNow = new AtomicLong(1_700_000_000_000L);

    /** Builds the audit fixture: a fresh AuditLog over an in-memory store + a monotonic clock. */
    private AuditLog newAuditLog() {
        Clock clock = new Clock() {
            @Override public long currentTimeMillis() { return auditNow.getAndIncrement(); }
            @Override public long nanoTime() { return auditNow.get() * 1_000_000L; }
        };
        return new AuditLog(Storage.inMemory(), clock);
    }

    private int startAudit(AuditLog auditLog) throws Exception {
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry registry = new MetricsRegistry();
        return start(new ServerSpec(null, new HealthService(), new PrometheusExporter(registry),
                store, committingWriteServiceSeq42(), null, authInterceptor(), aclService(),
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(1), auditLog, /* replayGuard */ null));
    }

    private HttpResponse<String> sendKey(int port, String method, String key, String token, String body)
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
        AuditLog auditLog = newAuditLog();
        int port = startAudit(auditLog);

        assertEquals(200, sendKey(port, "PUT", "app/feature", "good-writer", "on").statusCode());
        assertEquals(200, sendKey(port, "DELETE", "app/feature", "good-writer", null).statusCode());
        assertEquals(403, sendKey(port, "PUT", "app/feature", "good-reader", "x").statusCode());
        assertEquals(401, sendKey(port, "PUT", "app/feature", null, "x").statusCode());

        java.util.List<AuditLog.Record> records = auditLog.records();
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
        AuditLog auditLog = newAuditLog();
        int port = startAudit(auditLog);
        assertEquals(200, sendKey(port, "PUT", "app/secret", "good-writer", "v").statusCode());
        AuditLog.Record r = auditLog.records().get(0);
        assertEquals("writer", r.actor());
        assertTrue(!r.toString().contains("good-writer"), "the bearer token must never be in the audit record");
    }

    private final AtomicLong replayNow = new AtomicLong(2_000_000_000_000L); // fixed wall clock

    private static AuthInterceptor writerOnlyAuthInterceptor() {
        return new AuthInterceptor(token -> "good-writer".equals(token)
                ? new AuthInterceptor.AuthResult.Authenticated("writer", Set.of("write"))
                : new AuthInterceptor.AuthResult.Denied("unknown token"));
    }

    private static AclService writerOnlyAclService() {
        AclService acl = new AclService();
        acl.grant("app/", "writer", Set.of(AclService.Permission.READ, AclService.Permission.WRITE));
        return acl;
    }

    private Clock replayClock() {
        return new Clock() {
            @Override public long currentTimeMillis() { return replayNow.get(); }
            @Override public long nanoTime() { return replayNow.get() * 1_000_000L; }
        };
    }

    private int startReplay(ReplayGuard guard) throws Exception {
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry registry = new MetricsRegistry();
        return start(new ServerSpec(null, new HealthService(), new PrometheusExporter(registry),
                store, committingWriteService(), null, writerOnlyAuthInterceptor(), writerOnlyAclService(),
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(1), /* auditLog */ null, guard));
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
        int port = startReplay(new ReplayGuard(replayClock(), 300_000L, 1000));

        HttpRequest original = put(port, "app/feature", "on", replayNow.get(), "nonce-A");
        HttpResponse<String> first = client.send(original, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, first.statusCode(), "the original valid PUT must commit: " + first.body());

        // Resend the SAME request object (identical headers + body) to simulate a captured replay.
        HttpResponse<String> replay = client.send(original, HttpResponse.BodyHandlers.ofString());
        assertEquals(409, replay.statusCode(),
                "a verbatim capture-and-replay must be rejected (409 Conflict): " + replay.body());

        HttpRequest fresh = put(port, "app/feature", "off", replayNow.get(), "nonce-B");
        HttpResponse<String> third = client.send(fresh, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, third.statusCode(), "a fresh nonce must be accepted: " + third.body());
    }

    @Test
    void staleTimestampIsRejected() throws Exception {
        int port = startReplay(new ReplayGuard(replayClock(), 300_000L, 1000));
        HttpRequest stale = put(port, "app/feature", "on", replayNow.get() - 600_000L, "nonce-S");
        HttpResponse<String> resp = client.send(stale, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode(), "a stale-timestamp request must be rejected (401): " + resp.body());
    }

    @Test
    void missingReplayHeadersAreRejectedWhenGuardEnabled() throws Exception {
        int port = startReplay(new ReplayGuard(replayClock(), 300_000L, 1000));
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
        // With no guard wired (the default), a PUT without replay headers commits normally.
        int port = startReplay(/* guard */ null);
        HttpRequest plain = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/config/app/feature"))
                .header("Authorization", "Bearer good-writer")
                .PUT(HttpRequest.BodyPublishers.ofString("on"))
                .build();
        HttpResponse<String> resp = client.send(plain, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "with the guard off, no replay headers are needed: " + resp.body());
    }

    // Strong reads are served linearizably on the leader and fail closed elsewhere; the five
    // path-normalization evasion vectors below are load-bearing on all three transports.

    /** Backing store seeded so a stale local read WOULD succeed if allowed. */
    private VersionedConfigStore seededStore() {
        VersionedConfigStore store = new VersionedConfigStore();
        // Both a security key and an ordinary key are present locally (a follower's local state);
        // only the fail-closed contract should stop the security key being served from here.
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
        // confirmLeadership(scope,key) == isLeader: a follower (false) makes linearizableRead
        // return null, modelling an unconfirmable read.
        return new ConfigReadService(reader, (scope, key) -> isLeader.get());
    }

    private int startStrong(VersionedConfigStore store, ConfigReadService readService,
                            StrongReadPolicy policy, BiFunction<ConfigScope, String, NodeId> leaderHint) throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        return start(new ServerSpec(null, new HealthService(), new PrometheusExporter(registry),
                store, /* writeService */ null, readService, /* auth */ null, /* acl */ null,
                policy, leaderHint, /* auditLog */ null, /* replayGuard */ null));
    }

    @Test
    void leaderServesStrongReadKeyLinearizably() throws Exception {
        VersionedConfigStore store = seededStore();
        AtomicBoolean isLeader = new AtomicBoolean(true);
        int port = startStrong(store, readService(store, isLeader),
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(1));

        // No consistency param: a strong-read key is always linearizable.
        HttpResponse<String> resp = get(port, "/v1/config/secure/killswitch");
        assertEquals(200, resp.statusCode());
        assertEquals("DENY", resp.body());
        assertEquals("linearizable", resp.headers().firstValue("X-Consistency").orElse(""));
        assertEquals("true", resp.headers().firstValue("X-Strong-Read").orElse(""));
    }

    @Test
    void followerFailsClosedForStrongReadKeyNeverServesStale() throws Exception {
        VersionedConfigStore store = seededStore();
        AtomicBoolean isLeader = new AtomicBoolean(false); // a follower
        int port = startStrong(store, readService(store, isLeader),
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(3));

        HttpResponse<String> resp = get(port, "/v1/config/secure/killswitch");

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
        // Models a partitioned leader: it believes it is leader but ReadIndex cannot confirm
        // quorum, so confirmLeadership() flips to false.
        VersionedConfigStore store = seededStore();
        AtomicBoolean confirmable = new AtomicBoolean(true);
        int port = startStrong(store, readService(store, confirmable),
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(2));

        assertEquals(200, get(port, "/v1/config/secure/killswitch").statusCode());

        confirmable.set(false);
        HttpResponse<String> resp = get(port, "/v1/config/secure/killswitch");
        assertEquals(503, resp.statusCode());
        assertEquals("strong-read", resp.headers().firstValue("X-Fail-Closed").orElse(""));
        assertNotEquals("DENY", resp.body());
    }

    @Test
    void strongReadKeyIgnoresExplicitStaleRequest() throws Exception {
        VersionedConfigStore store = seededStore();
        AtomicBoolean isLeader = new AtomicBoolean(false);
        int port = startStrong(store, readService(store, isLeader),
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(3));

        HttpResponse<String> resp = get(port, "/v1/config/secure/killswitch?consistency=stale");
        assertEquals(503, resp.statusCode(),
                "a strong-read key cannot be downgraded to stale via the query param");
        assertEquals("strong-read", resp.headers().firstValue("X-Fail-Closed").orElse(""));
    }

    @Test
    void failsClosedWhenNoLinearizableReadPathConfigured() throws Exception {
        // readService == null (a stale-only node): a strong-read key has no safe answer and must
        // fail closed rather than fall through to the store.
        VersionedConfigStore store = seededStore();
        int port = startStrong(store, /* readService */ null,
                StrongReadPolicy.defaultPolicy(), (scope, key) -> null);

        HttpResponse<String> resp = get(port, "/v1/config/secure/killswitch");
        assertEquals(503, resp.statusCode());
        assertEquals("strong-read", resp.headers().firstValue("X-Fail-Closed").orElse(""));
        assertNotEquals("DENY", resp.body());
    }

    @Test
    void ordinaryKeyOnFollowerStillServesStale() throws Exception {
        // The fail-closed contract applies only to strong-read keys; an ordinary key on a follower
        // must keep serving its bounded-stale local copy, not turn every follower read into a denial.
        VersionedConfigStore store = seededStore();
        AtomicBoolean isLeader = new AtomicBoolean(false);
        int port = startStrong(store, readService(store, isLeader),
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(3));

        HttpResponse<String> resp = get(port, "/v1/config/app/feature");
        assertEquals(200, resp.statusCode());
        assertEquals("on", resp.body());
        assertEquals("stale", resp.headers().firstValue("X-Consistency").orElse(""));
        assertTrue(resp.headers().firstValue("X-Strong-Read").isEmpty());
    }

    // Encoded-key bypass resistance. The strong-read classification keys off the SAME
    // percent-decoded path (getRequestURI().getPath()) that resolves the value, so the
    // classification key is structurally identical to the store-resolution key - a strong key's
    // value cannot be read under a non-strong classification via encoding tricks. These tests lock
    // that decode-before-check property against a future change (e.g. switching to
    // getRawPath()/normalize/toLowerCase) by pinning the classification for each evasion vector.
    // They assert via the observable behavior (fail-closed on a follower), not internals, so they
    // survive refactors but still catch a real bypass; both the Netty and NIO adapters build
    // new URI(request.uri()), so the percent-decoded path is byte-identical across transports and
    // these must pass there too.

    @Test
    void percentEncodedPrefixIsClassifiedStrongAndFailsClosed() throws Exception {
        // %73 == 's': "/v1/config/%73ecure/killswitch" decodes to "secure/killswitch". The decode
        // happens before the strong-read check, so this is still a strong key and a follower must
        // fail closed - not serve the stale local DENY.
        VersionedConfigStore store = seededStore();
        AtomicBoolean isLeader = new AtomicBoolean(false);
        int port = startStrong(store, readService(store, isLeader),
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(3));

        HttpResponse<String> resp = getRaw(port, "/v1/config/%73ecure/killswitch");
        assertEquals(503, resp.statusCode(),
                "RR-020: a percent-encoded 's' (%73ecure/) must still classify as strong and fail closed");
        assertEquals("strong-read", resp.headers().firstValue("X-Fail-Closed").orElse(""));
        assertNotEquals("DENY", resp.body(), "the stale local value must never leak via %73-encoding");
    }

    @Test
    void encodedSlashInPrefixIsClassifiedStrongAndFailsClosed() throws Exception {
        // %2F == '/': "/v1/config/secure%2Fkillswitch" decodes to "secure/killswitch",
        // which startsWith("secure/") -> strong. A follower must fail closed.
        VersionedConfigStore store = seededStore();
        AtomicBoolean isLeader = new AtomicBoolean(false);
        int port = startStrong(store, readService(store, isLeader),
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(3));

        HttpResponse<String> resp = getRaw(port, "/v1/config/secure%2Fkillswitch");
        assertEquals(503, resp.statusCode(),
                "RR-020: an encoded slash (secure%2F) must still classify as strong and fail closed");
        assertEquals("strong-read", resp.headers().firstValue("X-Fail-Closed").orElse(""));
        assertNotEquals("DENY", resp.body());
    }

    @Test
    void dotDotInsideStrongPrefixStaysStrong() throws Exception {
        // "secure/../killswitch" still startsWith("secure/") (no path normalization
        // in the server), so it is strong and fails closed on a follower. A bypass
        // would require the classification to normalize the key away from the prefix.
        VersionedConfigStore store = seededStore();
        AtomicBoolean isLeader = new AtomicBoolean(false);
        int port = startStrong(store, readService(store, isLeader),
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(3));

        HttpResponse<String> resp = getRaw(port, "/v1/config/secure/../killswitch");
        assertEquals(503, resp.statusCode(),
                "RR-020: 'secure/../' must NOT be normalized away from the strong prefix");
        assertEquals("strong-read", resp.headers().firstValue("X-Fail-Closed").orElse(""));
        assertNotEquals("DENY", resp.body());
    }

    @Test
    void leadingDoubleSlashIsADifferentKeyNotAStrongLeak() throws Exception {
        // "//secure/killswitch" -> key "/secure/killswitch" (leading slash), which
        // does NOT startWith("secure/"). It is therefore NOT a strong key - but
        // crucially it is also a DIFFERENT store key, so it cannot leak the strong
        // key's value: the seeded "secure/killswitch" is not stored under this key,
        // so an ordinary stale read returns 404 (no value), never the DENY.
        VersionedConfigStore store = seededStore();
        AtomicBoolean isLeader = new AtomicBoolean(false);
        int port = startStrong(store, readService(store, isLeader),
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(3));

        HttpResponse<String> resp = getRaw(port, "/v1/config//secure/killswitch");
        assertNotEquals("DENY", resp.body(),
                "RR-020: a different (//-prefixed) key must not surface the strong key's stored value — "
                        + "the classification key and the store-resolution key are the same decoded path, "
                        + "so this resolves to a DIFFERENT (absent) key, never a stale leak of secure/killswitch");
    }

    @Test
    void queryStringCannotSupplyTheStrongKey() throws Exception {
        // The key is taken from the PATH only; a query param cannot inject a strong
        // key under an ordinary path. An ordinary path stays ordinary (200 stale),
        // and the strong "secure/killswitch" value is not reachable this way.
        VersionedConfigStore store = seededStore();
        AtomicBoolean isLeader = new AtomicBoolean(false);
        int port = startStrong(store, readService(store, isLeader),
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(3));

        HttpResponse<String> resp = getRaw(port, "/v1/config/app/feature?key=secure/killswitch");
        assertEquals(200, resp.statusCode(),
                "an ordinary path must remain ordinary regardless of query params");
        assertEquals("on", resp.body(), "the query string must not redirect to the strong key's value");
    }

    // The /metrics Bearer gate must hold on Netty and NIO, not just a direct JDK server. The
    // handler's /metrics gate checks the authInterceptor only (no ACL), so any valid token is 200
    // regardless of grants.

    /** Spec for the metrics gate: the two-principal authInterceptor, no ACL, a metrics-bearing exporter. */
    private int startMetricsGate() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        return start(new ServerSpec(null, new HealthService(), new PrometheusExporter(registry),
                new VersionedConfigStore(), /* writeService */ null, /* readService */ null,
                authInterceptor(), /* aclService */ null, StrongReadPolicy.defaultPolicy(),
                (scope, key) -> NodeId.of(1), /* auditLog */ null, /* replayGuard */ null));
    }

    @Test
    void metricsRequiresBearerTokenWhenAuthConfigured() throws Exception {
        // With auth configured, an unauthenticated scrape leaks the exposition (a reconnaissance
        // surface), so /metrics must 401 + advertise Bearer; any valid token is then accepted since
        // this gate checks authentication only, not ACL.
        int port = startMetricsGate();

        HttpResponse<String> noTok = client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, noTok.statusCode(), "F-0055: /metrics must return 401 without an auth header");
        assertEquals("Bearer", noTok.headers().firstValue("WWW-Authenticate").orElse(null),
                "a 401 MUST carry a WWW-Authenticate: Bearer challenge (RFC 7235 §3.1)");

        HttpResponse<String> authed = client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/metrics"))
                        .header("Authorization", "Bearer good-reader").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, authed.statusCode(), "F-0055: /metrics must return 200 with a valid bearer token");
        assertTrue(authed.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"),
                "the metrics exposition is text/plain: " + authed.headers().firstValue("Content-Type").orElse(""));
    }

    // The write-overload contract: a bounded-queue 429 carrying a Retry-After backoff, modelled by
    // a proposer that returns ProposeCommitResult.Overloaded.

    /** A write service whose proposer always reports backpressure (Overloaded). */
    private static ConfigWriteService overloadedWriteService() {
        ConfigWriteService.RaftProposer proposer =
                (scope, keys, command) -> new ConfigWriteService.ProposeCommitResult.Overloaded();
        return new ConfigWriteService(proposer, null, null);
    }

    @Test
    void overloadedWriteIsRejectedWith429AndRetryAfter() throws Exception {
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry registry = new MetricsRegistry();
        int port = start(new ServerSpec(null, new HealthService(), new PrometheusExporter(registry),
                store, overloadedWriteService(), /* readService */ null, authInterceptor(), aclService(),
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(1), /* auditLog */ null, /* replayGuard */ null));

        HttpResponse<String> put = send(port, "PUT", "/v1/config/app/feature", "good-writer", "off");
        assertEquals(429, put.statusCode(), "a backpressured write must be rejected with 429 Overloaded");
        assertEquals("1", put.headers().firstValue("Retry-After").orElse(null),
                "the 429 MUST carry a Retry-After backoff signal (§11 write-overload contract)");
    }

    @Test
    void unsupportedMethodOnConfigEndpointIs405() throws Exception {
        int port = start(authSpec());
        // PATCH is not GET/PUT/DELETE, so it falls through the config() switch's default -> 405,
        // even for a valid writer.
        HttpResponse<String> patch = send(port, "PATCH", "/v1/config/app/feature", "good-writer", "x");
        assertEquals(405, patch.statusCode(), "an unsupported method on /v1/config/{key} must be 405");
    }

    @Test
    void nonGetOnFixedEndpointIs405() throws Exception {
        int port = start(authSpec());
        HttpResponse<String> post = send(port, "POST", "/health/live", "good-writer", "x");
        assertEquals(405, post.statusCode(), "a non-GET on a fixed endpoint (/health/live) must be 405");
    }

    // Server-side TLS: exercises the Netty SslHandler path and the JDK HttpsServer path. A server
    // SSLContext (self-signed cert) is passed via ServerSpec.sslContext (the JDK adapter wraps it in
    // an HttpsServer; the Netty adapter wraps it in a server-mode SslHandler - both server-side, no
    // client auth). A trusting client GETs the public /health/live over HTTPS -> 200. The
    // keystore/truststore are generated once per subclass (@BeforeAll runs once per concrete test
    // container in JUnit 5) via keytool rather than io.netty's SelfSignedCertificate, which has
    // JDK-25 module-access issues. The cert carries a SAN for 127.0.0.1 so the JDK HttpClient's
    // default HTTPS endpoint identification succeeds.

    private static final char[] TLS_PASS = "changeit".toCharArray();
    private static Path tlsFixtureDir;
    private static Path tlsKeyStore;
    private static Path tlsTrustStore;

    @BeforeAll
    static void generateTlsFixture() throws Exception {
        tlsFixtureDir = Files.createTempDirectory("configd-admin-tls-");
        tlsKeyStore = tlsFixtureDir.resolve("server-ks.p12");
        tlsTrustStore = tlsFixtureDir.resolve("server-ts.p12");
        Path serverCert = tlsFixtureDir.resolve("server.pem");
        // EC keypair, CN=localhost + SAN dns:localhost,ip:127.0.0.1 so HTTPS hostname
        // verification of 127.0.0.1 passes.
        runKeytool("keytool", "-genkeypair", "-alias", "server",
                "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-validity", "1",
                "-dname", "CN=localhost,O=configd-test", "-ext", "san=dns:localhost,ip:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", tlsKeyStore.toString(),
                "-storepass", "changeit", "-keypass", "changeit");
        runKeytool("keytool", "-exportcert", "-alias", "server",
                "-keystore", tlsKeyStore.toString(), "-storepass", "changeit",
                "-rfc", "-file", serverCert.toString());
        runKeytool("keytool", "-importcert", "-alias", "server", "-file", serverCert.toString(),
                "-keystore", tlsTrustStore.toString(), "-storepass", "changeit",
                "-storetype", "PKCS12", "-noprompt");
    }

    @AfterAll
    static void deleteTlsFixture() throws Exception {
        if (tlsFixtureDir == null) {
            return;
        }
        try (var paths = Files.walk(tlsFixtureDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup of a temp fixture
                }
            });
        }
    }

    @Test
    void serverSideTlsServesHealthOverHttps() throws Exception {
        // Server context = the keystore (server identity); client context = the truststore (trusts it).
        SSLContext serverCtx = serverSslContext(tlsKeyStore);
        SSLContext clientCtx = trustingClientContext(tlsTrustStore);

        MetricsRegistry registry = new MetricsRegistry();
        int port = start(new ServerSpec(serverCtx, new HealthService(), new PrometheusExporter(registry),
                new VersionedConfigStore(), /* writeService */ null, /* readService */ null,
                /* auth */ null, /* acl */ null, StrongReadPolicy.defaultPolicy(),
                (scope, key) -> NodeId.of(1), /* auditLog */ null, /* replayGuard */ null));

        // A dedicated HttpClient that trusts the server cert. /health/live is public (no auth fixture).
        try (HttpClient tlsClient = HttpClient.newBuilder()
                .sslContext(clientCtx).connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpResponse<String> resp = tlsClient.send(HttpRequest.newBuilder()
                            .uri(URI.create("https://127.0.0.1:" + port + "/health/live"))
                            .GET().timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode(), "server-side TLS must serve /health/live over HTTPS: 200");
            assertTrue(resp.body().contains("\"healthy\":true"), "the health body is returned: " + resp.body());
        }
    }

    /** A server-side SSLContext keyed by {@code keyStore} (server identity; no client-auth trust needed). */
    private static SSLContext serverSslContext(Path keyStore) throws Exception {
        KeyStore ks = loadStore(keyStore);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, TLS_PASS);
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    /** A client SSLContext that trusts {@code trustStore} and presents no client cert. */
    private static SSLContext trustingClientContext(Path trustStore) throws Exception {
        KeyStore ts = loadStore(trustStore);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    private static KeyStore loadStore(Path p) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(p)) {
            ks.load(in, TLS_PASS);
        }
        return ks;
    }

    private static void runKeytool(String... command) throws Exception {
        int rc = new ProcessBuilder(command).redirectErrorStream(true).inheritIO().start().waitFor();
        assertEquals(0, rc, "keytool failed: " + String.join(" ", command));
    }

    // ADMIN gate over real HTTP: a key under `_acl/` (or `_system/`) requires ADMIN for every method
    // (closing both policy mutation and disclosure), fail-closed, and byte-identical in production
    // (only root touches _acl/, and root holds ADMIN). The gate decision is the handler's, but the
    // decode-before-gate property rides each adapter's `new URI(request.uri())`, so a regression in
    // either adapter's URI handling surfaces here on all three transports. The percent-decoding
    // evasion vectors (%5Facl/, _acl%2F, _acl/../) are sent verbatim via sendRaw so the encoded bytes
    // reach the server unmodified and are decoded by the same path the strong-read evasion vectors
    // rely on. Write-time validation rejects malformed _acl/ policy with a 400.

    /** root -> allOf (break-glass); admin -> ADMIN on `_acl/`; writer -> broad WRITE but NOT ADMIN. */
    private static AuthInterceptor gateAuthInterceptor() {
        return new AuthInterceptor(token -> switch (token) {
            case "root" -> new AuthInterceptor.AuthResult.Authenticated("root", Set.of());
            case "admin" -> new AuthInterceptor.AuthResult.Authenticated("adminP", Set.of());
            case "writer" -> new AuthInterceptor.AuthResult.Authenticated("writerP", Set.of());
            default -> new AuthInterceptor.AuthResult.Denied("unknown token");
        });
    }

    private static AclService gateAclService() {
        AclService acl = new AclService();
        acl.grant("", "root", EnumSet.allOf(AclService.Permission.class));
        acl.grant("_acl/", "adminP", Set.of(AclService.Permission.ADMIN));
        acl.grant("", "writerP", Set.of(AclService.Permission.READ, AclService.Permission.WRITE));
        return acl;
    }

    /** Spec for the gate: the three-principal authenticator + ACL, a committing write service, a seeded _acl/ key. */
    private int startGate() throws Exception {
        VersionedConfigStore store = new VersionedConfigStore();
        store.put("_acl/roles/seed", "allow READ app.".getBytes(), 1);
        MetricsRegistry registry = new MetricsRegistry();
        return start(new ServerSpec(null, new HealthService(), new PrometheusExporter(registry),
                store, committingWriteService(), /* readService */ null, gateAuthInterceptor(), gateAclService(),
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(1), /* auditLog */ null, /* replayGuard */ null));
    }

    /** A request to a VERBATIM (already-encoded) path with an optional bearer token + body (evasion vectors). */
    private HttpResponse<String> sendRaw(int port, String method, String rawPathAndQuery, String token, String body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + rawPathAndQuery));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        b.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void reservedPrefixRequiresAdminForEveryMethod() throws Exception {
        int port = startGate();
        assertEquals(403, send(port, "GET", "/v1/config/_acl/roles/seed", "writer", null).statusCode(),
                "a non-ADMIN GET of _acl/ must be 403 (policy DISCLOSURE closed)");
        assertEquals(403, send(port, "PUT", "/v1/config/_acl/roles/x", "writer", "allow READ app.").statusCode(),
                "a non-ADMIN PUT of _acl/ must be 403 (escalation closed)");
        assertEquals(403, send(port, "DELETE", "/v1/config/_acl/roles/seed", "writer", null).statusCode(),
                "a non-ADMIN DELETE of _acl/ must be 403");
    }

    @Test
    void adminAndRootReachReservedPrefix() throws Exception {
        int port = startGate();
        assertEquals(200, send(port, "GET", "/v1/config/_acl/roles/seed", "admin", null).statusCode(),
                "ADMIN may read _acl/");
        assertEquals(200, send(port, "PUT", "/v1/config/_acl/roles/x", "admin", "allow READ app.").statusCode(),
                "ADMIN may write a valid _acl/ policy");
        assertEquals(200, send(port, "GET", "/v1/config/_acl/roles/seed", "root", null).statusCode(),
                "root (allOf) may read _acl/");
        assertEquals(200, send(port, "DELETE", "/v1/config/_acl/roles/seed", "root", null).statusCode(),
                "root may delete _acl/");
    }

    @Test
    void writeTimeValidationRejectsMalformedReservedPolicyOverHttp() throws Exception {
        int port = startGate();
        assertEquals(400, send(port, "PUT", "/v1/config/_acl/roles/x", "admin", "allow NOPE app.").statusCode(),
                "a malformed _acl/ policy body must be rejected 400 even for an ADMIN principal");
        assertEquals(400, send(port, "PUT", "/v1/config/_acl/roles/admin", "admin", "allow READ app.").statusCode(),
                "defining the reserved role 'admin' must be rejected 400");
    }

    @Test
    void percentDecodingEvasionVectorsAreStillAdminGated() throws Exception {
        int port = startGate();
        // Each decodes to an `_acl/` key before the gate (the adapter's new URI(...).getPath() decodes it),
        // so a non-ADMIN writer is still 403 - the gate keys off the decoded key, exactly like the store.
        assertEquals(403, sendRaw(port, "PUT", "/v1/config/%5Facl/roles/x", "writer", "v").statusCode(),
                "%5Facl/ decodes to _acl/ → ADMIN-gated");
        assertEquals(403, sendRaw(port, "PUT", "/v1/config/_acl%2Froles/x", "writer", "v").statusCode(),
                "_acl%2F decodes to _acl/ → ADMIN-gated");
        assertEquals(403, sendRaw(port, "GET", "/v1/config/_acl/../roles/x", "writer", null).statusCode(),
                "_acl/../ is NOT normalized away from the reserved prefix → ADMIN-gated");
    }

    @Test
    void reservedWriteIsRefusedWhenAuthDisabled() throws Exception {
        // With auth + ACL off (the non-production mode), an _acl/ write is refused even though an
        // ordinary write still commits (auth-off is otherwise fully open) - closing the bring-up
        // footgun of silently poisoning policy before auth is configured.
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry registry = new MetricsRegistry();
        int port = start(new ServerSpec(null, new HealthService(), new PrometheusExporter(registry),
                store, committingWriteService(), /* readService */ null, /* auth */ null, /* acl */ null,
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(1), /* auditLog */ null, /* replayGuard */ null));

        assertNotEquals(200, send(port, "PUT", "/v1/config/_acl/roles/x", null, "allow READ app.").statusCode(),
                "auth-off: an _acl/ write must be refused (non-2xx)");
        assertEquals(200, send(port, "PUT", "/v1/config/app/feature", null, "on").statusCode(),
                "auth-off: an ordinary write still commits (only reserved writes are refused)");
    }
}

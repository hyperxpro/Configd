package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.AuthInterceptor;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.common.NodeId;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RR-087 — real HTTP-level coverage of {@code HttpApiServer$ConfigHandler}'s auth
 * gate ({@code checkAuth}, Session-1: 0/14 branches) and the GET/PUT/DELETE
 * branches. The deployable artifact's authz gate had ZERO branch executions, so
 * an authz regression would ship green. These tests drive real requests over
 * loopback through the production handler with auth + per-key ACLs wired:
 * <ul>
 *   <li>missing / malformed / invalid / valid bearer token;</li>
 *   <li>READ permission for GET vs WRITE permission for PUT/DELETE;</li>
 *   <li>per-key-prefix ACL allow vs deny.</li>
 * </ul>
 */
final class ConfigHandlerAuthTest {

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
    // Fixture: a token validator with two principals, and a per-prefix ACL.
    // ------------------------------------------------------------------

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

    /** A write service whose proposer always commits — auth is the gate under test. */
    private static ConfigWriteService committingWriteService() {
        ConfigWriteService.RaftProposer proposer =
                (scope, command) -> new ConfigWriteService.ProposeCommitResult.Committed(1L);
        return new ConfigWriteService(proposer, null, null);
    }

    private int start(AuthInterceptor auth, AclService acl) throws Exception {
        VersionedConfigStore store = new VersionedConfigStore();
        store.put("app/feature", "on".getBytes(), 1);
        store.put("locked/secret", "shh".getBytes(), 2);
        MetricsRegistry registry = new MetricsRegistry();
        server = new HttpApiServer(
                0, null, new HealthService(), new PrometheusExporter(registry),
                store, committingWriteService(), /* readService */ null, auth, acl,
                StrongReadPolicy.defaultPolicy(), () -> NodeId.of(1));
        server.start();
        client = HttpClient.newHttpClient();
        java.lang.reflect.Field f = HttpApiServer.class.getDeclaredField("server");
        f.setAccessible(true);
        return ((com.sun.net.httpserver.HttpServer) f.get(server)).getAddress().getPort();
    }

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

    // ------------------------------------------------------------------
    // Token presence / validity (checkAuth token branches)
    // ------------------------------------------------------------------

    @Test
    void getWithNoTokenIsUnauthenticated() throws Exception {
        // S7/D-1 (corrected semantics): a MISSING credential is AUTHENTICATION,
        // not authorization — RFC 7235 + charter §7 require 401 (was 403 pre-S7),
        // with a WWW-Authenticate: Bearer challenge.
        int port = start(authInterceptor(), aclService());
        HttpResponse<String> resp = send(port, "GET", "/v1/config/app/feature", null, null);
        assertEquals(401, resp.statusCode(), "a missing bearer token must be 401 (authenticate(null) -> Denied)");
        assertEquals("Bearer", resp.headers().firstValue("WWW-Authenticate").orElse(null),
                "a 401 MUST carry a WWW-Authenticate: Bearer challenge (RFC 7235 §3.1)");
        assertNotEquals("on", resp.body(), "no value may be served without authentication");
    }

    @Test
    void getWithInvalidTokenIsUnauthenticated() throws Exception {
        // S7/D-1: an INVALID credential is still authentication failure -> 401.
        int port = start(authInterceptor(), aclService());
        HttpResponse<String> resp = send(port, "GET", "/v1/config/app/feature", "bogus", null);
        assertEquals(401, resp.statusCode(), "an unknown token must be 401");
        assertEquals("Bearer", resp.headers().firstValue("WWW-Authenticate").orElse(null));
        assertTrue(resp.body().contains("authentication required"),
                "the 401 body must say authentication is required: " + resp.body());
    }

    @Test
    void getWithMalformedAuthorizationHeaderIsUnauthenticated() throws Exception {
        // S7/D-1: a non-"Bearer " header leaves token null -> authenticate(null)
        // -> Denied -> 401 (not 403).
        int port = start(authInterceptor(), aclService());
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/config/app/feature"))
                .header("Authorization", "Basic Zm9vOmJhcg==") // not Bearer
                .GET().build();
        HttpResponse<String> basic = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, basic.statusCode(), "a non-Bearer Authorization header must not authenticate (401)");
        assertEquals("Bearer", basic.headers().firstValue("WWW-Authenticate").orElse(null));
    }

    // ------------------------------------------------------------------
    // S7/D-1: mutating calls (PUT/DELETE) must ALSO return 401 (not 403)
    // for missing/malformed/invalid credentials. Each is its own attack.
    // ------------------------------------------------------------------

    @Test
    void putWithNoTokenIsUnauthenticated() throws Exception {
        int port = start(authInterceptor(), aclService());
        HttpResponse<String> resp = send(port, "PUT", "/v1/config/app/feature", null, "off");
        assertEquals(401, resp.statusCode(), "an unauthenticated PUT must be 401");
        assertEquals("Bearer", resp.headers().firstValue("WWW-Authenticate").orElse(null));
    }

    @Test
    void deleteWithNoTokenIsUnauthenticated() throws Exception {
        int port = start(authInterceptor(), aclService());
        HttpResponse<String> resp = send(port, "DELETE", "/v1/config/app/feature", null, null);
        assertEquals(401, resp.statusCode(), "an unauthenticated DELETE must be 401");
        assertEquals("Bearer", resp.headers().firstValue("WWW-Authenticate").orElse(null));
    }

    @Test
    void putWithMalformedAuthorizationHeaderIsUnauthenticated() throws Exception {
        int port = start(authInterceptor(), aclService());
        // A "Bearer "-less header leaves the token null -> 401.
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/config/app/feature"))
                .header("Authorization", "Basic Zm9vOmJhcg==")
                .PUT(HttpRequest.BodyPublishers.ofString("off")).build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode(), "a non-Bearer PUT must not authenticate (401)");
    }

    @Test
    void putWithInvalidTokenIsUnauthenticated() throws Exception {
        int port = start(authInterceptor(), aclService());
        HttpResponse<String> resp = send(port, "PUT", "/v1/config/app/feature", "bogus", "off");
        assertEquals(401, resp.statusCode(), "a PUT with an unknown token must be 401");
    }

    // ------------------------------------------------------------------
    // Permission: READ vs WRITE, and per-key ACL allow/deny
    // ------------------------------------------------------------------

    @Test
    void readerCanGetButCannotWrite() throws Exception {
        int port = start(authInterceptor(), aclService());

        // reader has READ on app/ -> GET allowed.
        HttpResponse<String> get = send(port, "GET", "/v1/config/app/feature", "good-reader", null);
        assertEquals(200, get.statusCode(), "reader with READ must be allowed to GET");
        assertEquals("on", get.body());

        // reader lacks WRITE on app/ -> PUT denied (the WRITE-permission branch).
        HttpResponse<String> put = send(port, "PUT", "/v1/config/app/feature", "good-reader", "off");
        assertEquals(403, put.statusCode(), "reader without WRITE must be denied a PUT");

        // reader lacks WRITE -> DELETE denied.
        HttpResponse<String> del = send(port, "DELETE", "/v1/config/app/feature", "good-reader", null);
        assertEquals(403, del.statusCode(), "reader without WRITE must be denied a DELETE");
    }

    @Test
    void writerCanReadWriteAndDelete() throws Exception {
        int port = start(authInterceptor(), aclService());

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
        int port = start(authInterceptor(), aclService());
        // Neither principal has any grant on "locked/" -> denied even with a valid token.
        HttpResponse<String> get = send(port, "GET", "/v1/config/locked/secret", "good-reader", null);
        assertEquals(403, get.statusCode(), "a valid principal with no ACL on this prefix must be denied");
        assertNotEquals("shh", get.body(), "the value outside the granted prefix must not leak");

        HttpResponse<String> put = send(port, "PUT", "/v1/config/locked/secret", "good-writer", "x");
        assertEquals(403, put.statusCode(), "even the writer has no grant on locked/");
    }

    // ------------------------------------------------------------------
    // S7/D-4: privilege-escalation coverage. An authenticated caller must NOT
    // be able to escalate a READ grant into a WRITE/DELETE, nor reach a key
    // outside its granted prefix. Each is a 403 (authenticated, unauthorized) —
    // never a 401, and never a success. NOTE: there is NO HTTP membership or
    // restore endpoint (membership = Raft proposeConfigChange; restore =
    // ops/scripts/restore-snapshot.sh), so privilege control for those lives at
    // the Raft/CLI layer, not here — there is no endpoint to attack.
    // ------------------------------------------------------------------

    @Test
    void readScopedPrincipalCannotEscalateToWrite() throws Exception {
        int port = start(authInterceptor(), aclService());
        // reader has READ on app/ but NOT WRITE: a PUT is an escalation attempt -> 403.
        HttpResponse<String> put = send(port, "PUT", "/v1/config/app/feature", "good-reader", "off");
        assertEquals(403, put.statusCode(), "read-scoped principal must not escalate to WRITE");
    }

    @Test
    void readScopedPrincipalCannotEscalateToDelete() throws Exception {
        int port = start(authInterceptor(), aclService());
        // DELETE requires WRITE; the reader has none -> 403 (not 401, not success).
        HttpResponse<String> del = send(port, "DELETE", "/v1/config/app/feature", "good-reader", null);
        assertEquals(403, del.statusCode(), "read-scoped principal must not escalate to DELETE");
    }

    @Test
    void writerCannotCrossIntoAnUngrantedPrefixOnDelete() throws Exception {
        int port = start(authInterceptor(), aclService());
        // writer has WRITE on app/ but NO grant on locked/: a cross-prefix DELETE -> 403.
        HttpResponse<String> del = send(port, "DELETE", "/v1/config/locked/secret", "good-writer", null);
        assertEquals(403, del.statusCode(), "a writer must not cross into an ungranted prefix on DELETE");
    }

    @Test
    void putWithEmptyBodyIsRejectedAfterAuth() throws Exception {
        int port = start(authInterceptor(), aclService());
        // Authorized writer, but an empty body -> 400 (the handlePut body branch).
        HttpResponse<String> put = send(port, "PUT", "/v1/config/app/feature", "good-writer", "");
        assertEquals(400, put.statusCode(), "an empty PUT body must be rejected with 400 after the auth gate");
    }

    @Test
    void missingKeyInPathIsRejected() throws Exception {
        int port = start(authInterceptor(), aclService());
        // "/v1/config/" with no key -> 400 (the handle() path-parsing branch).
        HttpResponse<String> resp = send(port, "GET", "/v1/config/", "good-reader", null);
        assertEquals(400, resp.statusCode(), "a request with no config key must be rejected");
    }
}

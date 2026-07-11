package io.configd.conformance;

import io.configd.api.AclService;
import io.configd.api.AuthInterceptor;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The redteam NON-ALIASING lock for §01 A3-4 -- the SERVER-side complement to the client-side canonicalization
 * (which rejects these spellings before the wire; see {@code ClausePathGrammarTest}). Because the reference
 * client refuses a non-canonical key, this test uses RAW {@link HttpClient} to push the hostile spellings past
 * the client and prove the SERVER never resolves a traversal (`..`) or empty-segment (`//`) spelling to a
 * DIFFERENT (sensitive) key.
 *
 * <p>The server deliberately does NOT normalize the key ({@code AdminApiHandler} takes the percent-decoded
 * {@code URI#getPath()} verbatim -- §04 D8-2), so {@code app/public/../secret/x} and {@code app//secret/x} are
 * DISTINCT literal keys, not aliases of {@code app/secret/x}. The threat this locks out: a principal granted
 * {@code app/public/} tricking the server into serving {@code app/secret/x} by requesting
 * {@code app/public/../secret/x}. The traversal is sent as {@code %2E%2E} so a literal {@code ..} survives on the
 * wire (no client/URI dot-segment normalization collapses it) and the server decodes it to the verbatim key. Had
 * the server normalized, this would have resolved to the sensitive key (a 403 on {@code app/secret/} or, worse, a
 * leak); instead it addresses its own distinct literal key that simply does not exist (404).
 */
@Timeout(60)
@Tag("clause:A3-4")
class ServerObeysPathAliasingTest {

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final String SECRET = "TOPSECRET-value-of-app-secret-x";

    private HttpApiServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void serverNeverAliasesTraversalOrEmptySegmentSpellingsToASensitiveKey() throws Exception {
        URI base = start();

        // Baseline A: the sensitive key app/secret/x exists but is ACL-protected -- the principal (granted only
        // app/public/ READ) is denied, and a 403 never carries the value.
        HttpResponse<byte[]> secret = raw(uri(base, "/v1/config/app/secret/x"), "user-tok");
        assertEquals(403, secret.statusCode(), "app/secret/x is READ-denied to the principal (baseline)");
        assertFalse(bodyText(secret).contains(SECRET), "a 403 never carries the sensitive value");

        // Baseline B: the grant genuinely works -- a legitimate key under app/public/ reads back 200.
        HttpResponse<byte[]> legit = raw(uri(base, "/v1/config/app/public/y"), "user-tok");
        assertEquals(200, legit.statusCode(), "a legit key under the app/public/ grant reads 200 (grant is real)");

        // (1) Dot-dot traversal. `%2E%2E` keeps a literal '..' on the wire (no transport/URI dot-segment
        // normalization collapses it), so the server decodes the key VERBATIM as `app/public/../secret/x` -- a
        // DISTINCT literal key: it is legitimately under the principal's app/public/ READ grant (so ACL permits
        // it), but no such literal key was ever stored, so it is a definite 404 -- NEVER traversed to
        // app/secret/x. Had the server normalized '..', this would instead resolve to app/secret/x (a 403, or a
        // leak); a 404 proves the literal, un-normalized resolution.
        HttpResponse<byte[]> dotdot = raw(uri(base, "/v1/config/app/public/%2E%2E/secret/x"), "user-tok");
        assertEquals(404, dotdot.statusCode(), "the '..' spelling is a distinct, non-existent literal key (A3-4)");
        assertFalse(bodyText(dotdot).contains(SECRET), "the '..' spelling NEVER surfaces app/secret/x's value (A3-4)");

        // (2) Empty-segment spelling. `app//secret/x` is a DISTINCT literal key that does NOT start with the
        // app/public/ grant prefix, so it is default-denied (403) -- never collapsed to app/secret/x, never the
        // value.
        HttpResponse<byte[]> doubleSlash = raw(uri(base, "/v1/config/app//secret/x"), "user-tok");
        assertEquals(403, doubleSlash.statusCode(), "the '//' spelling is a distinct literal key, default-denied (A3-4)");
        assertFalse(bodyText(doubleSlash).contains(SECRET), "the '//' spelling NEVER surfaces app/secret/x's value (A3-4)");
    }

    /** Auth+ACL ON: principal `user` holds READ on `app/public/` ONLY; `app/secret/` is ungranted (default-deny). */
    private URI start() throws IOException {
        VersionedConfigStore store = new VersionedConfigStore();
        store.put("app/secret/x", SECRET.getBytes(StandardCharsets.UTF_8), 5L); // the sensitive key
        store.put("app/public/y", "public".getBytes(StandardCharsets.UTF_8), 6L);
        AuthInterceptor auth = new AuthInterceptor(token -> switch (token) {
            case "user-tok" -> new AuthInterceptor.AuthResult.Authenticated("user", Set.of());
            default -> new AuthInterceptor.AuthResult.Denied("unknown token");
        });
        AclService acl = new AclService();
        acl.grant("app/public/", "user", Set.of(AclService.Permission.READ)); // READ on app/public/ ONLY
        ConfigWriteService write = new ConfigWriteService(
                (scope, keys, command) -> new ConfigWriteService.ProposeCommitResult.Committed(77L), null, null);
        server = new HttpApiServer(0, null, new HealthService(), new PrometheusExporter(new MetricsRegistry()),
                store, write, null /* readService */, auth, acl,
                StrongReadPolicy.defaultPolicy(), (scope, key) -> null /* leader unknown */);
        server.start();
        return URI.create("http://127.0.0.1:" + server.port());
    }

    /** Build the target from a raw string -- NO {@code resolve()}/{@code normalize()}, so `%2E%2E` and `//` survive. */
    private static URI uri(URI base, String rawPathAndQuery) {
        return URI.create(base.toString() + rawPathAndQuery);
    }

    private static HttpResponse<byte[]> raw(URI uri, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).method("GET", HttpRequest.BodyPublishers.noBody());
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String bodyText(HttpResponse<byte[]> r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }
}

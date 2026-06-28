package io.configd.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.configd.api.ConfigReadService;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.common.ConfigScope;
import io.configd.common.NodeId;
import io.configd.store.ReadResult;
import io.configd.store.VersionedConfigStore;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Wiring Increment 1 — the edge contract for scope-through-the-API + the superset key-validation gate,
 * driven through the transport-agnostic {@link AdminApiHandler} decision core (both the JDK and Netty
 * HTTP adapters delegate to it, so this is the single source of truth). Proves:
 *
 * <ul>
 *   <li><b>Superset key validation</b> (DL-W1-01): a corpus of currently-valid keys all pass; only
 *       blank / &gt;1024-byte keys are rejected 400 — no currently-valid key becomes invalid. The strict
 *       RFC §1 A3 grammar (absolute, seg-char, canonical) would reject several corpus keys ({@code db.host}
 *       not absolute, {@code a//b} empty segment, {@code secure/../killswitch} dot-dot, {@code :}/{@code @}),
 *       which is exactly why it is NOT applied to this legacy flat-key surface.</li>
 *   <li><b>Scope parsing</b> (DL-W1-02): {@code ?scope=} is parsed case-insensitively and threaded to the
 *       write path (PUT/DELETE) and the read path (GET); absent ⇒ {@code GLOBAL} (A2-3 default, byte
 *       identical); an unknown value ⇒ 400 and NEVER routes (fail-closed, no silent mis-route).</li>
 * </ul>
 *
 * <p>See {@code ShardedRoutingTest} for the read-your-writes-per-scope proof over the REAL routing seams,
 * and {@code docs/wiring/increment-1-scope-and-path-validation.md}.
 */
class ScopeAndPathValidationTest {

    /** Captures the scope/keys the wiring routes to the write and read paths. */
    private static final class Recorder {
        final AtomicReference<ConfigScope> writeScope = new AtomicReference<>();
        final AtomicReference<List<String>> writeKeys = new AtomicReference<>();
        final AtomicReference<ConfigScope> readScope = new AtomicReference<>();
    }

    private static AdminApiHandler handler(Recorder rec) {
        ConfigWriteService writeService = new ConfigWriteService(
                (scope, keys, command) -> {
                    rec.writeScope.set(scope);
                    rec.writeKeys.set(keys);
                    return new ConfigWriteService.ProposeCommitResult.Committed(1L);
                }, null, null);
        ConfigReadService.ConfigReader reader = new ConfigReadService.ConfigReader() {
            @Override public ReadResult get(String key) {
                return ReadResult.found("v".getBytes(StandardCharsets.UTF_8), 1);
            }
            @Override public ReadResult get(String key, long minVersion) { return get(key); }
            @Override public ReadResult get(ConfigScope scope, String key) {
                rec.readScope.set(scope);
                return get(key);
            }
            @Override public ReadResult get(ConfigScope scope, String key, long minVersion) {
                rec.readScope.set(scope);
                return get(key);
            }
            @Override public Map<String, ReadResult> getPrefix(String prefix) { return Map.of(); }
            @Override public long currentVersion() { return 1; }
        };
        ConfigReadService readService = new ConfigReadService(reader, (scope, key) -> true);
        return new AdminApiHandler(
                new HealthService(), /* exporter */ null, new VersionedConfigStore(), writeService,
                readService, /* auth */ null, /* acl */ null, StrongReadPolicy.defaultPolicy(),
                key -> NodeId.of(1), /* auditLog */ null, /* replayGuard */ null);
    }

    /** Builds a request whose {@code uri().getPath()} decodes to {@code /v1/config/<key>}. */
    private static AdminApiHandler.AdminRequest req(String method, String key, String query, byte[] body) {
        final URI uri;
        try {
            // The 5-arg URI constructor percent-encodes the path/query; getPath()/getQuery() decode back,
            // exactly mirroring how the production adapters obtain a decoded path from the request line.
            uri = new URI(null, null, "/v1/config/" + key, query, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("bad test URI for key '" + key + "'", e);
        }
        return new AdminApiHandler.AdminRequest() {
            @Override public String method() { return method; }
            @Override public URI uri() { return uri; }
            @Override public String header(String name) { return null; }
            @Override public byte[] body() { return body; }
        };
    }

    // ---- superset key-validation gate ---------------------------------------------------------

    @Test
    void supersetGateAcceptsEveryCurrentlyValidKey() throws Exception {
        AdminApiHandler h = handler(new Recorder());
        String boundary = "x".repeat(1024); // exactly the deployed 1024-byte limit (A3-5)
        List<String> valid = List.of(
                "db.host",                 // legacy dotted key (not absolute → A3 would reject)
                "app/feature",             // slashed (not absolute → A3 would reject)
                "secure/../killswitch",    // dot-dot — strong-read suite depends on this passing
                "a//b",                    // empty segment (A3 would reject)
                "key_with-mixed.chars",
                "service:port",            // colon (not a seg-char → A3 would reject)
                "team@payments",           // at (not a seg-char → A3 would reject)
                "naïve-café",   // multi-byte UTF-8
                "/leading-slash",
                boundary);
        for (String key : valid) {
            assertEquals(200, h.handle(req("PUT", key, null, "body".getBytes(StandardCharsets.UTF_8))).status(),
                    "PUT of currently-valid key must pass the superset gate: '" + key + "'");
            assertEquals(200, h.handle(req("GET", key, null, new byte[0])).status(),
                    "GET of currently-valid key must pass the superset gate: '" + key + "'");
            assertEquals(200, h.handle(req("DELETE", key, null, new byte[0])).status(),
                    "DELETE of currently-valid key must pass the superset gate: '" + key + "'");
        }
    }

    @Test
    void supersetGateRejectsOnlyBlankAndOverlongKeys() throws Exception {
        AdminApiHandler h = handler(new Recorder());
        String tooLong = "x".repeat(1025);
        for (String method : List.of("GET", "PUT", "DELETE")) {
            byte[] body = "body".getBytes(StandardCharsets.UTF_8);
            AdminApiHandler.AdminResponse blank = h.handle(req(method, " ", null, body));
            assertEquals(400, blank.status(), method + " of a blank key must be 400");
            assertTrue(new String(blank.body(), StandardCharsets.UTF_8).contains("blank"),
                    method + " blank-key 400 must explain the reason");
            AdminApiHandler.AdminResponse over = h.handle(req(method, tooLong, null, body));
            assertEquals(400, over.status(), method + " of a >1024-byte key must be 400");
            assertTrue(new String(over.body(), StandardCharsets.UTF_8).contains("1024"),
                    method + " over-length 400 must cite the 1024-byte limit");
        }
        // The boundary (exactly 1024 bytes) is accepted — the rejection is strictly > 1024.
        assertNotEquals(400, h.handle(req("PUT", "x".repeat(1024), null, "b".getBytes(StandardCharsets.UTF_8))).status());
    }

    // ---- scope parsing (DL-W1-02) -------------------------------------------------------------

    @Test
    void scopeQueryParamIsThreadedToWriteAndReadPaths() throws Exception {
        Recorder rec = new Recorder();
        AdminApiHandler h = handler(rec);

        // PUT carries an explicit scope to the proposer (case-insensitive).
        assertEquals(200, h.handle(req("PUT", "k", "scope=REGIONAL", "b".getBytes(StandardCharsets.UTF_8))).status());
        assertEquals(ConfigScope.REGIONAL, rec.writeScope.get());
        assertEquals(200, h.handle(req("PUT", "k", "scope=local", "b".getBytes(StandardCharsets.UTF_8))).status());
        assertEquals(ConfigScope.LOCAL, rec.writeScope.get(), "scope value parse is case-insensitive");

        // Absent scope ⇒ GLOBAL (A2-3 default — byte-identical to the prior surface).
        assertEquals(200, h.handle(req("PUT", "k", null, "b".getBytes(StandardCharsets.UTF_8))).status());
        assertEquals(ConfigScope.GLOBAL, rec.writeScope.get());

        // DELETE carries scope to the proposer as well.
        assertEquals(200, h.handle(req("DELETE", "k", "scope=REGIONAL", new byte[0])).status());
        assertEquals(ConfigScope.REGIONAL, rec.writeScope.get());

        // GET carries scope to the scope-aware reader (read-your-writes: same (scope,key) as the write).
        assertEquals(200, h.handle(req("GET", "k", "scope=LOCAL", new byte[0])).status());
        assertEquals(ConfigScope.LOCAL, rec.readScope.get());
        assertEquals(200, h.handle(req("GET", "k", null, new byte[0])).status());
        assertEquals(ConfigScope.GLOBAL, rec.readScope.get(), "absent scope reads GLOBAL");

        // A scope param coexists with the existing consistency param (order-independent parse).
        assertEquals(200, h.handle(req("GET", "k", "consistency=linearizable&scope=REGIONAL", new byte[0])).status());
        assertEquals(ConfigScope.REGIONAL, rec.readScope.get());
    }

    @Test
    void unknownScopeIsRejected400AndNeverRoutes() throws Exception {
        Recorder rec = new Recorder();
        AdminApiHandler h = handler(rec);
        for (String method : List.of("GET", "PUT", "DELETE")) {
            AdminApiHandler.AdminResponse resp =
                    h.handle(req(method, "k", "scope=BOGUS", "b".getBytes(StandardCharsets.UTF_8)));
            assertEquals(400, resp.status(), method + " with an unknown scope must be 400");
            assertTrue(new String(resp.body(), StandardCharsets.UTF_8).contains("Unknown scope"),
                    method + " unknown-scope 400 must name the offending value");
        }
        // Fail-closed: a rejected scope NEVER reached the write/read path (closes scope-confusion).
        assertNull(rec.writeScope.get(), "an unknown scope must not route to the proposer");
        assertNull(rec.readScope.get(), "an unknown scope must not route to the reader");
    }
}

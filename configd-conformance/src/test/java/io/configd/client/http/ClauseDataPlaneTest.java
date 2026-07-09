package io.configd.client.http;

import io.configd.client.BadRequestException;
import io.configd.client.CredentialSource;
import io.configd.client.RetryPolicy;
import io.configd.client.UnavailableException;
import io.configd.client.edge.ConfigdEdgeClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static io.configd.client.http.MockControlPlane.Response;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runner II — CLIENT-CONFORMS, HTTP data plane (§04 D-clauses). Drives the reference {@link ConfigdHttpClient}
 * against the scriptable {@link MockControlPlane} (reused via the configd-client-http test-jar) and asserts the
 * client obeys the normative §04 data-plane MUSTs the {@code RealServerHttpTest} / {@code ClientConformsHttpTest}
 * pair does not already cover: the {@code /v1/} version pin, the misleading-{@code Content-Type} plaintext-body
 * trap, the strong-read header-vs-name distinction, the write outcome→status table and named-code reactions, the
 * scope query, the replay-guard headers, and the fail-closed-on-unknown / named-omission surface. Each test is
 * {@code @Tag}-ed with the clause-ids it genuinely asserts; grouping (one test, several tags) is used where a
 * single genuine assertion binds several clauses.
 */
@Timeout(30)
class ClauseDataPlaneTest {

    private static final RetryPolicy FAST = new RetryPolicy(Duration.ofMillis(2), Duration.ofMillis(10), 4);

    // -----------------------------------------------------------------------
    // Versioning + surface (D1, D2)
    // -----------------------------------------------------------------------

    @Test
    @Tag("clause:D1-1_D1-2")
    void addressesTheV1PrefixWithNoNegotiationHandshake() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.value("v", 1));
            c.blocking().get("app/name", GetOptions.defaults());
            // The HTTP API version is the /v1/ path prefix ALONE: the request goes straight to /v1/config/...
            assertTrue(s.lastRequest().path().startsWith("/v1/config/"),
                    "the data plane is addressed under the literal /v1/ prefix (D1-1)");
            // No capabilities/hello/downgrade exchange precedes the operation, and there is no version header —
            // the single request IS the read (D1-1: no Accept/version negotiation on the HTTP surface).
            assertEquals(1, s.requestCount(), "no pre-flight negotiation request (D1-1)");
            Map<String, String> h = s.lastRequest().headers();
            assertNull(h.get("Accept-Version"), "no version-negotiation header (D1-1)");
            assertNull(h.get("X-Api-Version"), "versioning is by the path prefix only, never a header (D1-1/D1-2)");
        }
    }

    @Test
    @Tag("clause:D2-5_D2-5a")
    void bodiesAreParsedAsPlaintextAndValuesAsOpaqueBytesNeverJson() throws Exception {
        // Write 200: Content-Type is a MISLEADING application/json but the body is the plaintext
        // "Committed: seq=<N>". The client parses it as plaintext (a JSON.parse would fail) — D2-5a.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.committed(42)); // 200, Content-Type: application/json, plaintext body
            WriteOutcome w = c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults());
            assertEquals(42L, w.seq(), "seq recovered by PLAINTEXT parse under a misleading application/json (D2-5a)");
        }
        // Read 200: the value is raw application/octet-stream bytes, returned verbatim and opaque even when the
        // bytes happen to look like JSON — the client never interprets/transcodes a value (D2-5/D3-1).
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            byte[] jsonLooking = "{\"looks\":\"like-json\"}".getBytes(StandardCharsets.UTF_8);
            s.enqueue(Response.of(200, Map.of("Content-Type", "application/octet-stream",
                    "X-Config-Version", "7"), new String(jsonLooking, StandardCharsets.UTF_8)));
            GetResult r = c.blocking().get("k", GetOptions.defaults());
            assertArrayEquals(jsonLooking, r.valueOrThrow(),
                    "a value is opaque bytes returned verbatim, not parsed (D2-5)");
        }
    }

    @Test
    @Tag("clause:D2-6")
    void writesLiveOnlyOnTheHttpPlaneNotTheEdgePlane() {
        // The binary edge plane is read/watch/fan-out only — it exposes NO write surface (D2-6). Asserted
        // structurally: the edge client has subscribe/watch but no put/delete/write; the HTTP client has both.
        Set<String> edgeMethods = publicMethodNames(ConfigdEdgeClient.class);
        assertFalse(edgeMethods.contains("put"), "the edge plane carries no put (D2-6)");
        assertFalse(edgeMethods.contains("delete"), "the edge plane carries no delete (D2-6)");
        assertFalse(edgeMethods.contains("write"), "the edge plane carries no write (D2-6)");
        Set<String> httpMethods = publicMethodNames(ConfigdHttpClient.class);
        assertTrue(httpMethods.contains("put") && httpMethods.contains("delete"),
                "put/delete exist ONLY on the HTTP data plane (D2-6)");
    }

    // -----------------------------------------------------------------------
    // Read consistency + strong-read (D3)
    // -----------------------------------------------------------------------

    @Test
    @Tag("clause:D3-2a")
    void xConsistencyLinearizableOnAnOrdinaryKeyIsNotAFreshnessProof() throws Exception {
        // A stale-only deployment echoes X-Consistency: linearizable on an ordinary key WITHOUT X-Strong-Read.
        // The client MUST NOT treat that echo as certified freshness — only X-Strong-Read: true certifies it.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.of(200, Map.of("Content-Type", "application/octet-stream",
                    "X-Config-Version", "5", "X-Consistency", "linearizable"), "v"));
            GetResult r = c.blocking().get("ordinary/key", GetOptions.defaults().consistency(Consistency.LINEARIZABLE));
            assertTrue(r.found());
            assertFalse(r.strongRead(),
                    "X-Consistency: linearizable without X-Strong-Read is NOT a freshness proof (D3-2a)");
            assertEquals(Consistency.LINEARIZABLE, r.requested(), "requested-mode is preserved, distinct from proof");
        }
    }

    @Test
    @Tag("clause:D3-5_D3-5a")
    void strongReadFreshnessIsHeaderCertifiedNotNameInferred() throws Exception {
        // D3-5a: the strong-read class is server-side config (default secure/, MAY be disabled). The client
        // relies on the X-Strong-Read HEADER, never the key NAME. A secure/-named response WITHOUT the header
        // (a disabled-strong-read deployment) is NOT certified fresh...
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.of(200, Map.of("Content-Type", "application/octet-stream",
                    "X-Config-Version", "9", "X-Consistency", "stale"), "v"));
            GetResult r = c.blocking().get("secure/token", GetOptions.defaults());
            assertFalse(r.strongRead(), "the secure/ NAME alone does not confer the guarantee (D3-5a)");
        }
        // ...and only the header present makes it certified.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.of(200, Map.of("Content-Type", "application/octet-stream",
                    "X-Config-Version", "9", "X-Consistency", "linearizable", "X-Strong-Read", "true"), "v"));
            GetResult r = c.blocking().get("secure/token", GetOptions.defaults());
            assertTrue(r.strongRead(), "X-Strong-Read: true certifies the leader-confirmed-fresh read (D3-5)");
        }
    }

    @Test
    @Tag("clause:D3-5_D3-5a")
    void strongReadFailClosedNeverServesAStaleValue() throws Exception {
        // D3-5: a strong-read fail-close (503 + X-Fail-Closed: strong-read) that never resolves is exhausted
        // as Unavailable — the client NEVER substitutes a stale value for a classified strong-read key.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            for (int i = 0; i < FAST.maxAttempts(); i++) {
                s.enqueue(Response.of(503, Map.of("X-Fail-Closed", "strong-read"), "Fail-closed: strong-read"));
            }
            assertThrows(UnavailableException.class, () -> c.blocking().get("secure/x", GetOptions.defaults()),
                    "fail-closed strong-read never yields a stale value (D3-5)");
        }
    }

    @Test
    @Tag("clause:D3-6")
    void linearizableOnAnOrdinaryKeyThatCannotBeServedIsRetryableNotTerminal() throws Exception {
        // An ordinary-key linearizable read the node can't serve is a 503 WITHOUT X-Fail-Closed (a stale read
        // of the key is contract-permitted). It is a normal retryable 503 — distinct from the strong-read
        // fail-close — so the client retries the same endpoint and returns the value on resolution (D3-6).
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(503, "Not Leader - cannot serve linearizable read")); // no X-Fail-Closed, no hint
            s.enqueue(Response.value("fresh", 12));
            GetResult r = c.blocking().get("ordinary/key", GetOptions.defaults().consistency(Consistency.LINEARIZABLE));
            assertArrayEquals("fresh".getBytes(StandardCharsets.UTF_8), r.valueOrThrow());
            assertEquals(2, s.requestCount(), "an ordinary-key linearizable 503 is retryable, not a fail-close (D3-6)");
            assertEquals("consistency=linearizable", s.recorded().get(0).query(),
                    "the request was in fact a linearizable read (the exact literal, D3-4)");
        }
    }

    @Test
    @Tag("clause:D3-8")
    void getIsSideEffectFreeAndFreelyRetried() throws Exception {
        // A GET never mutates, so the client retries a transient 503 freely to a definite answer, and re-reads
        // re-issue (no side-effect / no dedup). 504 is a write-only outcome — a GET is only ever value/404/retry.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(503, "couldn't serve now")); // transient, hintless
            s.enqueue(Response.value("v", 1));
            GetResult r1 = c.blocking().get("k", GetOptions.defaults());
            assertTrue(r1.found());
            assertEquals(2, s.requestCount(), "a GET is safe to retry — re-read on a transient 503 (D3-8)");

            s.enqueue(Response.value("v", 1));
            c.blocking().get("k", GetOptions.defaults());
            assertEquals(3, s.requestCount(), "a repeated read re-issues to the server (side-effect-free, D3-8)");
        }
    }

    // -----------------------------------------------------------------------
    // Write scope + outcome table (D4)
    // -----------------------------------------------------------------------

    @Test
    @Tag("clause:D4-4")
    void putHonorsScopeAndOmitsItWhenGlobal() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.committed(1));
            c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults());
            assertNull(s.lastRequest().query(), "GLOBAL (the default) is omitted, byte-identical to pre-scope (D4-4)");
        }
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.committed(1));
            c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults().scope(Scope.REGIONAL));
            assertEquals("scope=REGIONAL", s.lastRequest().query(), "?scope= is an exact param on a write (D4-4)");
        }
    }

    @Test
    @Tag("clause:D4-6")
    void writeOutcomeStatusTableMapsEachOutcomeToItsReaction() throws Exception {
        // committed 200 → record seq
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.committed(7));
            assertEquals(7L, c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq(),
                    "committed ⇒ 200 ⇒ record seq (D4-6)");
        }
        // not leader 503 + X-Leader-Hint → follow the hint to the named node
        try (MockControlPlane n1 = new MockControlPlane(); MockControlPlane n2 = new MockControlPlane()) {
            n1.enqueue(Response.of(503, Map.of("X-Leader-Hint", "2"), "Not Leader (leader=Node-2)"));
            n2.enqueue(Response.committed(8));
            Map<Integer, URI> map = new LinkedHashMap<>();
            map.put(1, n1.baseUri());
            map.put(2, n2.baseUri());
            try (ConfigdHttpClient c = ConfigdHttpClient.builder().endpoints(NodeEndpoints.ofMap(map))
                    .allowPlaintext(true).retryPolicy(FAST).build()) {
                assertEquals(8L, c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq());
                assertEquals(1, n2.requestCount(), "not-leader 503+hint ⇒ follow the hint (D4-6)");
            }
        }
        // indeterminate 504 → retry-to-definite (idempotent LWW)
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(504, "Commit unconfirmed within deadline"));
            s.enqueue(Response.committed(9));
            assertEquals(9L, c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq(),
                    "indeterminate 504 ⇒ retry to a definite outcome (D4-6/D4-8)");
        }
        // validation 400 → permanent, not retried
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(400, "Validation failed: bad"));
            assertThrows(BadRequestException.class,
                    () -> c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()));
            assertEquals(1, s.requestCount(), "validation 400 ⇒ permanent, not retried (D4-6)");
        }
        // overloaded 429 + Retry-After → back off then retry
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.of(429, Map.of("Retry-After", "0"), "Overloaded"));
            s.enqueue(Response.committed(10));
            assertEquals(10L, c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq(),
                    "overloaded 429 ⇒ honor Retry-After then retry (D4-6)");
        }
    }

    // -----------------------------------------------------------------------
    // list deferral (D9)
    // -----------------------------------------------------------------------

    @Test
    @Tag("clause:D9-1")
    void theClientExposesNoListOrEnumerationSurface() {
        // v1 ships no list wire and the reference client MUST NOT synthesize one. Asserted structurally: the
        // client has get/put/delete/transferLeadership and NO enumeration method a caller could mistake for it.
        Set<String> methods = publicMethodNames(ConfigdHttpClient.class);
        for (String forbidden : new String[]{"list", "enumerate", "keys", "scan", "children", "listPrefix"}) {
            assertFalse(methods.contains(forbidden), "no synthesized '" + forbidden + "' enumeration surface (D9-1)");
        }
        assertTrue(methods.containsAll(Set.of("get", "put", "delete")), "the built unary surface is present");
    }

    // -----------------------------------------------------------------------
    // Composition / forward-compat (D11)
    // -----------------------------------------------------------------------

    @Test
    @Tag("clause:D11-1")
    void everyWriteCanRedirectAndAHintless503IsRetriedEvenAtN1() throws Exception {
        // Leader-following is REQUIRED even at N=1: a single-node client's only 503 window is the pre-election
        // gap, where the leader is unknown so the 503 carries NO X-Leader-Hint. The client MUST back off and
        // retry the SAME endpoint (there is no distinct leader to follow) — never require the hint to be present.
        try (MockControlPlane s = new MockControlPlane();
             ConfigdHttpClient c = ConfigdHttpClient.builder().endpoints(NodeEndpoints.of(s.baseUri()))
                     .allowPlaintext(true).retryPolicy(FAST).build()) {
            s.enqueue(Response.text(503, "Not Leader")); // hintless — the normal N=1 election window
            s.enqueue(Response.committed(4));
            assertEquals(4L, c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq());
            assertEquals(2, s.requestCount(), "a hintless 503 is backed-off-and-retried on the same endpoint (D11-1)");
        }
    }

    @Test
    @Tag("clause:D11-2")
    void everyNamedStatusCarriesItsInlineReaction() throws Exception {
        // The §04 named codes each map to exactly one driver reaction — proven by the client's typed surface.
        assertReadStatus(404, r -> assertFalse(r.found(), "404 ⇒ a definite absent (empty result), not an error"));
        assertReadThrows(401, io.configd.client.AuthFailedException.class); // (re)authenticate; no hot-loop
        assertReadThrows(403, io.configd.client.ForbiddenException.class);  // permanently forbidden
        assertReadThrows(400, BadRequestException.class);                   // permanent request error
        assertWriteThrows(405, BadRequestException.class);                  // method error, permanent
        // 200 read ⇒ value; 200 write ⇒ committed seq.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.value("v", 3));
            assertTrue(c.blocking().get("k", GetOptions.defaults()).found(), "200 read ⇒ the value");
        }
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.committed(3));
            assertEquals(3L, c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq(),
                    "200 write ⇒ committed seq");
        }
    }

    @Test
    @Tag("clause:D11-3")
    void replayGuardIsOffByDefaultAndStampsAFreshNoncePerAttemptWhenOn() throws Exception {
        // Default OFF: a mutation carries NO replay headers (no client populates them absent an enabled guard).
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.committed(1));
            c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults());
            Map<String, String> h = s.lastRequest().headers();
            assertNull(h.get(ReplayGuardSigner.NONCE_HEADER), "no nonce header when the guard is off (D11-3)");
            assertNull(h.get(ReplayGuardSigner.TIMESTAMP_HEADER), "no timestamp header when the guard is off (D11-3)");
        }
        // Enabled: each attempt is stamped with a FRESH timestamp+nonce, so a replayed-nonce 409 retry does not
        // re-send the rejected material (re-sending would self-inflict another 409/401 — D11-3 / D4-3).
        try (MockControlPlane s = new MockControlPlane();
             ConfigdHttpClient c = ConfigdHttpClient.builder().endpoints(NodeEndpoints.of(s.baseUri()))
                     .allowPlaintext(true).retryPolicy(FAST).replayGuard(true).build()) {
            s.enqueue(Response.text(409, "Conflict: replayed request (nonce already seen)"));
            s.enqueue(Response.committed(2));
            assertEquals(2L, c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq());
            String n1 = s.recorded().get(0).headers().get(ReplayGuardSigner.NONCE_HEADER);
            String n2 = s.recorded().get(1).headers().get(ReplayGuardSigner.NONCE_HEADER);
            assertNotNull(n1, "attempt 1 is stamped (D11-3)");
            assertNotNull(n2, "attempt 2 is stamped (D11-3)");
            assertNotEquals(n1, n2, "a FRESH nonce per attempt (D11-3)");
            assertNotNull(s.recorded().get(0).headers().get(ReplayGuardSigner.TIMESTAMP_HEADER), "and a timestamp");
        }
    }

    @Test
    @Tag("clause:D11-4")
    void failsClosedOnAnUnknownStatusAndExposesNoneOfTheNamedOmissions() throws Exception {
        // Fail closed on a status it does not specifically recognize: an unexpected 4xx is a permanent request
        // error, NEVER coerced to success (§04 D11-4 / §01 A1.3 fail-closed-on-unknown).
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(418, "I'm a teapot"));
            assertThrows(BadRequestException.class,
                    () -> c.blocking().get("k", GetOptions.defaults()), "an unrecognized status fails closed (D11-4)");
        }
        // The named v1 omissions are absent from the API — a driver cannot accidentally assume them:
        //   - no conditional-write / If-Match / CAS on a write (WriteOptions carries only scope),
        //   - no "read at version ≥ N" parameter on a read (GetOptions carries only scope + consistency),
        //   - no list/batch method on the client.
        Set<String> writeOpts = recordComponentNames(WriteOptions.class);
        assertEquals(Set.of("scope"), writeOpts, "no If-Match/CAS/version write option (D11-4)");
        Set<String> getOpts = recordComponentNames(GetOptions.class);
        assertEquals(Set.of("scope", "consistency"), getOpts, "no read-at-version option (D11-4)");
        Set<String> methods = publicMethodNames(ConfigdHttpClient.class);
        for (String forbidden : new String[]{"list", "batch", "compareAndSet", "putIfMatch", "getAtVersion"}) {
            assertFalse(methods.contains(forbidden), "no '" + forbidden + "' forward-extension surface (D11-4)");
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private void assertReadStatus(int status, java.util.function.Consumer<GetResult> check) throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(status, "body"));
            check.accept(c.blocking().get("k", GetOptions.defaults()));
        }
    }

    private void assertReadThrows(int status, Class<? extends Throwable> expected) throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.of(status, Map.of("WWW-Authenticate", "Bearer"), "denied"));
            assertThrows(expected, () -> c.blocking().get("k", GetOptions.defaults()));
            assertEquals(1, s.requestCount(), "a terminal " + status + " is not retried");
        }
    }

    private void assertWriteThrows(int status, Class<? extends Throwable> expected) throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(status, "denied"));
            assertThrows(expected, () -> c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()));
        }
    }

    private static Set<String> publicMethodNames(Class<?> type) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Method m : type.getMethods()) {
            names.add(m.getName());
        }
        return names;
    }

    private static Set<String> recordComponentNames(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static ConfigdHttpClient client(MockControlPlane server) {
        return ConfigdHttpClient.builder()
                .endpoints(NodeEndpoints.of(server.baseUri()))
                .credentialSource(CredentialSource.staticBearer("t0ken"))
                .allowPlaintext(true)
                .retryPolicy(FAST)
                .build();
    }
}

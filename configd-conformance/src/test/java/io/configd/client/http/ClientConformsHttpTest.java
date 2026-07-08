package io.configd.client.http;

import io.configd.client.BadRequestException;
import io.configd.client.CredentialSource;
import io.configd.client.ForbiddenException;
import io.configd.client.IndeterminateException;
import io.configd.client.RetryPolicy;
import io.configd.client.UnavailableException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static io.configd.client.http.MockControlPlane.Response;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runner II — CLIENT-CONFORMS, HTTP control plane: drives the reference {@link ConfigdHttpClient} against the
 * scriptable {@link MockControlPlane} (reused via the configd-client-http test-jar) and asserts the client obeys
 * each normative §04/§05/§07 MUST — the cursor placement (seq-in-body vs version-in-header), the status→reaction
 * taxonomy, the 503 sub-cause disambiguation, the indeterminate-write contract, and branch-on-code-not-body.
 * Each test is {@code @Tag}-ed with the clause-ids it genuinely asserts (the coverage audit maps them).
 */
@Timeout(30)
class ClientConformsHttpTest {

    private static final RetryPolicy FAST = new RetryPolicy(Duration.ofMillis(2), Duration.ofMillis(10), 4);

    @Test
    @Tag("clause:D3-1")
    @Tag("clause:D3-2")
    @Tag("clause:D2-7")
    void readReturnsValueAndVersionFromHeaderEmptyValueIsPresent() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.value("hello", 42));
            GetResult r = c.blocking().get("app/name", GetOptions.defaults());
            assertTrue(r.found());
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), r.valueOrThrow());
            assertEquals(42L, r.version(), "the read version comes from the X-Config-Version HEADER (D3-2)");
        }
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.value("", 3));
            GetResult r = c.blocking().get("empty", GetOptions.defaults());
            assertTrue(r.found(), "a present key with an empty value is a 200 zero-length body, not a 404 (D2-7)");
            assertEquals(0, r.valueOrThrow().length);
        }
    }

    @Test
    @Tag("clause:D3-3")
    void notFoundIsADefiniteAbsentNotAnError() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(404, "Not Found"));
            GetResult r = c.blocking().get("missing", GetOptions.defaults());
            assertFalse(r.found(), "404 is a definite answer, surfaced as an empty result, never an exception (D3-3)");
        }
    }

    @Test
    @Tag("clause:D4-2")
    @Tag("clause:D4-7")
    void writeParsesSeqFromTheBodyNotAHeader() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.committed(42));
            WriteOutcome w = c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults());
            assertEquals(42L, w.seq(), "seq is parsed from the 'Committed: seq=<N>' BODY (D4-2), a committed-and-applied 200 (D4-7)");
        }
    }

    @Test
    @Tag("clause:D2-3")
    void badRequest400IsPermanentNotRetried() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(400, "invalid key"));
            assertThrows(BadRequestException.class,
                    () -> c.blocking().put("bad", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()));
            assertEquals(1, s.requestCount(), "a 400 is permanent — the client does not retry it (E7-1)");
        }
    }

    @Test
    @Tag("clause:A7-2")
    void forbidden403IsTerminalUnauthenticated401DoesNotHotLoop() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(403, "denied"));
            assertThrows(ForbiddenException.class, () -> c.blocking().get("k", GetOptions.defaults()));
            assertEquals(1, s.requestCount(), "403 ⇒ don't retry unchanged (A7-2)");
        }
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.of(401, Map.of("WWW-Authenticate", "Bearer"), "Unauthorized"));
            assertThrows(io.configd.client.AuthFailedException.class, () -> c.blocking().get("k", GetOptions.defaults()));
            assertEquals(1, s.requestCount(), "401 without the replay guard ⇒ (re)authenticate, never hot-loop the credential (A7-2)");
        }
    }

    @Test
    @Tag("clause:R2-3")
    @Tag("clause:R6-1")
    void hintless503BacksOffAndRetriesTheSameEndpoint() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(503, "Not Leader")); // no X-Leader-Hint (election window — the normal N=1 case)
            s.enqueue(Response.committed(9));
            WriteOutcome w = c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults());
            assertEquals(9L, w.seq());
            assertEquals(2, s.requestCount(), "a hintless 503 is retried within the bounded budget (R2-3/R6-1)");
        }
    }

    @Test
    @Tag("clause:E2-2")
    void strongReadFailClosed503NeverServesStale() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            for (int i = 0; i < FAST.maxAttempts(); i++) {
                s.enqueue(Response.of(503, Map.of("X-Fail-Closed", "strong-read"), "Fail-closed: strong-read"));
            }
            // The client exhausts as Unavailable rather than EVER returning a stale value for the strong-read key.
            assertThrows(UnavailableException.class, () -> c.blocking().get("secure/x", GetOptions.defaults()));
        }
    }

    @Test
    @Tag("clause:R2-4")
    @Tag("clause:D4-8")
    void indeterminate504RetriesToDefiniteAndSurfacesUnknownOnExhaustion() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(504, "unconfirmed"));
            s.enqueue(Response.committed(13));
            assertEquals(13L, c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq(),
                    "a 504 (no hint — not a redirect, R2-4) is retried-to-definite (idempotent LWW, D4-8)");
        }
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            for (int i = 0; i < FAST.maxAttempts(); i++) {
                s.enqueue(Response.text(504, "unconfirmed"));
            }
            assertThrows(IndeterminateException.class,
                    () -> c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()),
                    "budget exhausted while indeterminate ⇒ UNKNOWN, never a false definite failure (D4-8)");
        }
    }

    @Test
    @Tag("clause:D4-3")
    @Tag("clause:R6-2")
    void writesAreIdempotentAndSafeToRetry() throws Exception {
        // The client retries a mutation across a transient 5xx precisely because PUT/DELETE are idempotent LWW.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(502, "bad gateway"));
            s.enqueue(Response.committed(1));
            assertEquals(1L, c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq());
        }
    }

    @Test
    @Tag("clause:R6-4")
    void replayGuard409RetriesWithAFreshNonce() throws Exception {
        try (MockControlPlane s = new MockControlPlane();
             ConfigdHttpClient c = ConfigdHttpClient.builder().endpoints(NodeEndpoints.of(s.baseUri()))
                     .allowPlaintext(true).retryPolicy(FAST).replayGuard(true).build()) {
            s.enqueue(Response.text(409, "Conflict: replayed request (nonce already seen)"));
            s.enqueue(Response.committed(1));
            assertEquals(1L, c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq());
            String n1 = s.recorded().get(0).headers().get(ReplayGuardSigner.NONCE_HEADER);
            String n2 = s.recorded().get(1).headers().get(ReplayGuardSigner.NONCE_HEADER);
            assertTrue(n1 != null && n2 != null && !n1.equals(n2), "a FRESH nonce is minted per attempt (R6-4)");
        }
    }

    @Test
    @Tag("clause:E6-1")
    void branchesOnTheStatusCodeNotABodyThatLooksLikeJson() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            // A 403 whose plaintext body looks like a success JSON under application/json — still a Forbidden.
            s.enqueue(Response.of(403, Map.of("Content-Type", "application/json"), "{\"granted\":true}"));
            assertThrows(ForbiddenException.class, () -> c.blocking().get("k", GetOptions.defaults()));
        }
    }

    @Test
    @Tag("clause:D3-4")
    @Tag("clause:D7-1..D7-4")
    void consistencyLiteralIsExactAndScopeIsAnExactParam() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.value("v", 1));
            c.blocking().get("k", GetOptions.defaults().consistency(Consistency.LINEARIZABLE));
            assertEquals("consistency=linearizable", s.lastRequest().query(),
                    "the exact literal, never composed elsewhere (the loose-substring trap, D3-4)");
        }
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.value("v", 1));
            c.blocking().get("k", GetOptions.defaults().scope(Scope.REGIONAL));
            assertEquals("scope=REGIONAL", s.lastRequest().query(), "scope is an exact parameter (D7)");
        }
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

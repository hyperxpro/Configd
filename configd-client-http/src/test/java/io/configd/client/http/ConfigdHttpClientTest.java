package io.configd.client.http;

import io.configd.client.AuthFailedException;
import io.configd.client.BadRequestException;
import io.configd.client.CredentialSource;
import io.configd.client.ForbiddenException;
import io.configd.client.IndeterminateException;
import io.configd.client.RetryPolicy;
import io.configd.client.UnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

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
 * Tests the HTTP control-plane client against the scriptable {@link MockControlPlane}: the get/put/delete happy
 * paths (seq-from-body, version-from-header), every status reaction, leader-following (hint follow-once /
 * hintless N=1 loop / unresolvable-hint / anti-SSRF), the indeterminate-write contract, the replay guard, the
 * strong-read fail-close, the query composition (the {@code consistency=linearizable} loose-substring literal /
 * exact {@code scope=}), branch-on-code-not-body, and the transfer-leadership route.
 */
@Timeout(30)
class ConfigdHttpClientTest {

    private static final RetryPolicy FAST = new RetryPolicy(Duration.ofMillis(2), Duration.ofMillis(10), 4);

    // happy paths

    @Test
    void getReturnsValueAndVersionFromHeader() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.value("hello", 42));
            GetResult r = c.blocking().get("app/name", GetOptions.defaults());
            assertTrue(r.found());
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), r.valueOrThrow());
            assertEquals(42L, r.version());
            assertEquals("/v1/config/app/name", s.lastRequest().path());
            assertNull(s.lastRequest().query(), "GLOBAL/stale get carries no query");
        }
    }

    @Test
    void getAbsentIs404NotAnError() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(404, "Not Found"));
            GetResult r = c.blocking().get("missing", GetOptions.defaults());
            assertFalse(r.found());
        }
    }

    @Test
    void getEmptyValueIsPresentZeroLength() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.value("", 3));
            GetResult r = c.blocking().get("empty", GetOptions.defaults());
            assertTrue(r.found(), "a present key with an empty value is 200, distinct from 404");
            assertEquals(0, r.valueOrThrow().length);
        }
    }

    @Test
    void putParsesSeqFromBody() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.committed(42));
            WriteOutcome w = c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults());
            assertEquals(42L, w.seq());
            assertEquals("PUT", s.lastRequest().method());
            assertArrayEquals("v".getBytes(StandardCharsets.UTF_8), s.lastRequest().body());
        }
    }

    @Test
    void deleteParsesSeqAndSendsNoBody() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.committed(43));
            WriteOutcome w = c.blocking().delete("k", WriteOptions.defaults());
            assertEquals(43L, w.seq());
            assertEquals("DELETE", s.lastRequest().method());
        }
    }

    // status reactions

    @Test
    void badRequest400IsTerminal() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(400, "invalid key"));
            assertThrows(BadRequestException.class,
                    () -> c.blocking().put("bad", "v".getBytes(), WriteOptions.defaults()));
            assertEquals(1, s.requestCount(), "a 400 is permanent — not retried");
        }
    }

    @Test
    void forbidden403IsTerminal() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(403, "Access denied"));
            assertThrows(ForbiddenException.class, () -> c.blocking().get("secret", GetOptions.defaults()));
            assertEquals(1, s.requestCount());
        }
    }

    @Test
    void unauthenticated401IsTerminalNoHotLoop() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.of(401, Map.of("WWW-Authenticate", "Bearer"), "Unauthorized"));
            assertThrows(AuthFailedException.class, () -> c.blocking().get("k", GetOptions.defaults()));
            assertEquals(1, s.requestCount(), "401 without the replay guard does not hot-loop the credential");
        }
    }

    @Test
    void methodNotAllowed405IsTerminal() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(405, "Method Not Allowed"));
            assertThrows(BadRequestException.class,
                    () -> c.blocking().put("k", "v".getBytes(), WriteOptions.defaults()));
        }
    }

    @Test
    void overloaded429HonorsRetryAfterThenSucceeds() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.of(429, Map.of("Retry-After", "0"), "Overloaded"));
            s.enqueue(Response.committed(5));
            WriteOutcome w = c.blocking().put("k", "v".getBytes(), WriteOptions.defaults());
            assertEquals(5L, w.seq());
            assertEquals(2, s.requestCount());
        }
    }

    // leader following

    @Test
    void hintless503RetriesSameEndpointThenSucceeds() throws Exception {
        // The election loop that is required even at N=1: a hintless 503 is retried within the bounded budget.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(503, "Not Leader")); // no X-Leader-Hint (leader unknown)
            s.enqueue(Response.committed(9));
            WriteOutcome w = c.blocking().put("k", "v".getBytes(), WriteOptions.defaults());
            assertEquals(9L, w.seq());
            assertEquals(2, s.requestCount());
        }
    }

    @Test
    void leaderHintFollowedToTheHintedNode() throws Exception {
        try (MockControlPlane n1 = new MockControlPlane(); MockControlPlane n2 = new MockControlPlane()) {
            n1.enqueue(Response.of(503, Map.of("X-Leader-Hint", "2"), "Not Leader (leader=Node-2)"));
            n2.enqueue(Response.committed(11));
            // A LinkedHashMap pins the entry order so node 1 is the deterministic starting endpoint (Map.of
            // iteration order is unspecified -- any node is a valid entry point, so the client is order-agnostic).
            java.util.Map<Integer, java.net.URI> map = new java.util.LinkedHashMap<>();
            map.put(1, n1.baseUri());
            map.put(2, n2.baseUri());
            NodeEndpoints nodes = NodeEndpoints.ofMap(map);
            try (ConfigdHttpClient c = ConfigdHttpClient.builder().endpoints(nodes)
                    .allowPlaintext(true).retryPolicy(FAST).build()) {
                WriteOutcome w = c.blocking().put("k", "v".getBytes(), WriteOptions.defaults());
                assertEquals(11L, w.seq());
                assertEquals(1, n1.requestCount(), "hit node 1 once");
                assertEquals(1, n2.requestCount(), "followed the hint to node 2");
            }
        }
    }

    @Test
    void unresolvableHintDegradesToHintlessRetry() throws Exception {
        // Anti-SSRF: a hint naming a NodeId not in the map is NOT chased -- it degrades to a hintless retry.
        try (MockControlPlane s = new MockControlPlane()) {
            s.enqueue(Response.of(503, Map.of("X-Leader-Hint", "99"), "Not Leader")); // 99 not in the map
            s.enqueue(Response.committed(7));
            NodeEndpoints nodes = NodeEndpoints.ofMap(Map.of(1, s.baseUri()));
            try (ConfigdHttpClient c = ConfigdHttpClient.builder().endpoints(nodes)
                    .allowPlaintext(true).retryPolicy(FAST).build()) {
                WriteOutcome w = c.blocking().put("k", "v".getBytes(), WriteOptions.defaults());
                assertEquals(7L, w.seq());
                assertEquals(2, s.requestCount(), "retried the known endpoint, never chased node 99");
            }
        }
    }

    // indeterminate write

    @Test
    void indeterminate504RetriesToDefinite() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(504, "Commit unconfirmed; safe to retry or re-read"));
            s.enqueue(Response.committed(13));
            WriteOutcome w = c.blocking().put("k", "v".getBytes(), WriteOptions.defaults());
            assertEquals(13L, w.seq(), "the retried idempotent write reached a definite 200");
        }
    }

    @Test
    void indeterminate504ExhaustsToIndeterminateException() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            for (int i = 0; i < FAST.maxAttempts(); i++) {
                s.enqueue(Response.text(504, "unconfirmed"));
            }
            // Budget exhausted with the last outcome still indeterminate: the result is unknown, never a
            // false definite failure.
            assertThrows(IndeterminateException.class,
                    () -> c.blocking().put("k", "v".getBytes(), WriteOptions.defaults()));
        }
    }

    // strong-read fail-close

    @Test
    void strongReadFailClosedNeverServesStale() throws Exception {
        // A strong-read fail-closed 503 (X-Fail-Closed) that never resolves exhausts as Unavailable -- the client
        // never returns a stale value for it.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            for (int i = 0; i < FAST.maxAttempts(); i++) {
                s.enqueue(Response.of(503, Map.of("X-Fail-Closed", "strong-read"), "Fail-closed: strong-read"));
            }
            assertThrows(UnavailableException.class, () -> c.blocking().get("secure/x", GetOptions.defaults()));
        }
    }

    // replay guard

    @Test
    void replayGuard409RetriesWithAFreshNonce() throws Exception {
        try (MockControlPlane s = new MockControlPlane();
             ConfigdHttpClient c = ConfigdHttpClient.builder().endpoints(NodeEndpoints.of(s.baseUri()))
                     .allowPlaintext(true).retryPolicy(FAST).replayGuard(true).build()) {
            s.enqueue(Response.text(409, "Conflict: replayed request (nonce already seen)"));
            s.enqueue(Response.committed(1));
            WriteOutcome w = c.blocking().put("k", "v".getBytes(), WriteOptions.defaults());
            assertEquals(1L, w.seq());
            assertEquals(2, s.requestCount());
            String nonce1 = s.recorded().get(0).headers().get(ReplayGuardSigner.NONCE_HEADER);
            String nonce2 = s.recorded().get(1).headers().get(ReplayGuardSigner.NONCE_HEADER);
            assertNotNull(nonce1);
            assertNotNull(nonce2);
            assertNotEquals(nonce1, nonce2, "a fresh nonce is minted per attempt (R6-4)");
        }
    }

    // query composition

    @Test
    void linearizableEmitsExactLiteralAndScopeIsExact() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.value("v", 1));
            c.blocking().get("k", GetOptions.defaults().consistency(Consistency.LINEARIZABLE));
            assertEquals("consistency=linearizable", s.lastRequest().query(),
                    "exactly the literal, nothing else (the loose-substring trap)");
        }
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.value("v", 1));
            c.blocking().get("k", GetOptions.defaults().scope(Scope.REGIONAL));
            assertEquals("scope=REGIONAL", s.lastRequest().query());
        }
    }

    // branch on code, not body

    @Test
    void errorBodyThatLooksLikeJsonIsNotParsedAsSuccess() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            // A 403 whose plaintext body happens to look like JSON under application/json is still Forbidden.
            s.enqueue(Response.of(403, Map.of("Content-Type", "application/json"), "{\"granted\":true}"));
            assertThrows(ForbiddenException.class, () -> c.blocking().get("k", GetOptions.defaults()));
        }
    }

    // transfer-leadership (the admin route)

    @Test
    void transferLeadershipInitiated() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(200, "Leadership transfer to Node-2 for group 0 initiated"));
            c.blocking().transferLeadership(0, 2);
            assertEquals("POST", s.lastRequest().method());
            assertEquals("/v1/admin/groups/0/transfer-leadership", s.lastRequest().path());
            assertEquals("target=2", s.lastRequest().query());
        }
    }

    @Test
    void transferLeadership409PreconditionIsTerminalNotReplayRetry() throws Exception {
        // A 409 on the transfer route is a precondition failure (target == self, or not a voter), not a replayed
        // nonce -- it must be terminal, never retried as a fresh-nonce replay.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(409, "Leadership transfer rejected: target is not a voter"));
            assertThrows(BadRequestException.class, () -> c.blocking().transferLeadership(0, 2));
            assertEquals(1, s.requestCount(), "the precondition 409 is terminal — not retried");
        }
    }

    @Test
    void transferLeadership403WithoutAdmin() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(403, "Access denied: ADMIN required"));
            assertThrows(ForbiddenException.class, () -> c.blocking().transferLeadership(0, 2));
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

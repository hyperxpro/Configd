package io.configd.client.http;

import io.configd.client.BadRequestException;
import io.configd.client.CredentialSource;
import io.configd.client.RetryPolicy;
import io.configd.client.UnavailableException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.configd.client.http.MockControlPlane.Response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runner II -- CLIENT-CONFORMS, §05 routing / leader-following: drives the reference {@link ConfigdHttpClient}
 * against the scriptable {@link MockControlPlane} (reused via the configd-client-http test-jar) and asserts the
 * client obeys the normative routing MUSTs -- read the hint from the {@code X-Leader-Hint} <b>header</b> only
 * (never the body), resolve the bare numeric {@code NodeId} through its own map (anti-SSRF), follow-and-retry
 * even at N = 1, be ready for a {@code 503} on any read, do <b>no</b> client-side sharding (and never cache a
 * hint across keys), and fail closed on an HTTP {@code 3xx} / a richer wire-supplied address.
 *
 * <p>The server-side half of §05 (R3-2 no discovery endpoint, R8-4 the hint is authz-gated) is proven against
 * the live {@code HttpApiServer} in {@code ServerObeysRoutingTest}.
 */
@Timeout(30)
class ClauseRoutingTest {

    private static final RetryPolicy FAST = new RetryPolicy(Duration.ofMillis(2), Duration.ofMillis(10), 4);

    @Test
    @Tag("clause:R2-1")
    @Tag("clause:R2-2")
    void hintIsReadFromTheHeaderNotTheBodyAndResolvedThroughTheMap() throws Exception {
        // R2-1: the redirect is advisory via the X-Leader-Hint HEADER -- never the 503 body (plaintext under a
        // misleading application/json, rendering Node-<id> and echoing the key). Here the header names node 2
        // while the body misleadingly names node 1 (self); the client MUST follow the HEADER (node 2). A client
        // that parsed the body would retry self (node 1) and NEVER contact node 2 -- so node-2-committed is
        // proof the header, not the body, was read. R2-2: the hint "2" is a bare numeric NodeId resolved to a
        // connection target ONLY through the operator NodeEndpoints map (the wire never supplies an address).
        try (MockControlPlane n1 = new MockControlPlane(); MockControlPlane n2 = new MockControlPlane()) {
            n1.enqueue(Response.of(503, Map.of("X-Leader-Hint", "2"), "Not Leader (leader=Node-1)"));
            n2.enqueue(Response.committed(11));
            try (ConfigdHttpClient c = twoNodeClient(n1, n2)) {
                assertEquals(11L, c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq());
                assertEquals(1, n1.requestCount(), "hit the entry node once");
                assertEquals(1, n2.requestCount(), "followed the HEADER hint (2) to node 2 — not the body's Node-1");
            }
        }
    }

    @Test
    @Tag("clause:R2-2")
    void unresolvableHintDegradesToHintlessNeverAWireAddress() throws Exception {
        // Anti-SSRF (R2-2): a hint naming a NodeId absent from the driver's map is NOT chased -- it degrades to a
        // hintless 503 (retry the endpoints it DOES know). A compromised node cannot steer the driver anywhere
        // outside the operator-configured set.
        try (MockControlPlane s = new MockControlPlane()) {
            s.enqueue(Response.of(503, Map.of("X-Leader-Hint", "99"), "Not Leader")); // 99 is not in the map
            s.enqueue(Response.committed(7));
            try (ConfigdHttpClient c = client(s)) {
                assertEquals(7L, c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq());
                assertEquals(2, s.requestCount(), "retried the known endpoint, never chased node 99");
            }
        }
    }

    @Test
    @Tag("clause:R4-1_R4-2")
    void leaderFollowingIsRequiredEvenAtN1AndTheHintlessLoopIsBounded() throws Exception {
        // R4-1/R4-2: a single node is NOT exempt. Before it wins its initial election a write returns a
        // HINTLESS 503 (leaderId() is null), and the ONLY correct action is back off + retry the SAME endpoint
        // until it becomes leader. A driver MUST implement the 503-then-backoff-retry loop even at N=1.
        try (MockControlPlane s = new MockControlPlane()) {
            s.enqueue(Response.text(503, "Not Leader")); // no X-Leader-Hint -- the normal N=1 election window
            s.enqueue(Response.committed(9));
            try (ConfigdHttpClient c = client(s)) {
                assertEquals(9L, c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq());
                assertEquals(2, s.requestCount(), "retried the same single endpoint through the election window");
            }
        }
        // Bounded: a node stuck mid-election (always 503) terminates as a bounded Unavailable, never an infinite
        // hang (R6-3) -- the loop is finite even at N=1.
        try (MockControlPlane s = new MockControlPlane()) {
            for (int i = 0; i < FAST.maxAttempts(); i++) {
                s.enqueue(Response.text(503, "Not Leader"));
            }
            try (ConfigdHttpClient c = client(s)) {
                assertThrows(UnavailableException.class,
                        () -> c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()));
                assertEquals(FAST.maxAttempts(), s.requestCount(), "the hintless loop is bounded by the attempt budget");
            }
        }
    }

    @Test
    @Tag("clause:R4-4")
    void anyReadCan503BecauseTheStrongReadClassIsServerSideInvisible() throws Exception {
        // R4-4: a driver MUST NOT assume a read avoids the redirect loop just because it did not request
        // ?consistency=linearizable. The server's strong-read prefix set is invisible to the driver and
        // fail-closes a stale-requested read with 503 + X-Fail-Closed: strong-read. A conforming driver applies
        // R6 (back off + retry) to a plain read and completes when the key can be served -- it does not crash or
        // treat the 503 as an error unique to writes.
        try (MockControlPlane s = new MockControlPlane()) {
            s.enqueue(Response.of(503, Map.of("X-Fail-Closed", "strong-read"), "Fail-closed: strong-read"));
            s.enqueue(Response.value("v", 4));
            try (ConfigdHttpClient c = client(s)) {
                GetResult r = c.blocking().get("app/ordinary", GetOptions.defaults()); // a plain stale read
                assertTrue(r.found());
                assertEquals(4L, r.version());
                assertEquals(2, s.requestCount(), "the plain read was retried through a 503 — R6 applied to a read");
            }
        }
    }

    @Test
    @Tag("clause:R5-1..R5-4")
    void noClientSideShardingTheFullKeyGoesInThePathWithNoShardOrNParameter() throws Exception {
        // R5-1/R5-2: key placement is decided server-side by hashing the full (scope, path); the driver never
        // computes a shard, replicates the hash, or needs N. The request carries only the literal key path --
        // no ?shard=, no shard-count param -- and routing is byte-for-byte identical at N=1 and N>1.
        try (MockControlPlane s = new MockControlPlane()) {
            s.enqueue(Response.value("v", 1));
            try (ConfigdHttpClient c = client(s)) {
                c.blocking().get("app/svc/x", GetOptions.defaults());
                assertEquals("/v1/config/app/svc/x", s.lastRequest().path(), "the full key path, no shard computed");
                assertNull(s.lastRequest().query(), "a stale GLOBAL read carries no shard/N/consistency query");
            }
        }
    }

    @Test
    @Tag("clause:R5-1..R5-4")
    void aHintIsPerRequestAndIsNeverCachedAcrossKeys() throws Exception {
        // R5-3: the X-Leader-Hint is resolved as the leader of the shard that owns THIS (scope, key); a
        // different key may live on a different shard with a different leader. A driver MUST NOT cache a hint and
        // reuse it for another key. Here key "a" is redirected to node 2; the very next op on key "b" MUST start
        // at the configured entry node (node 1), not reuse node 2 -- proving each request routes independently.
        try (MockControlPlane n1 = new MockControlPlane(); MockControlPlane n2 = new MockControlPlane()) {
            n1.enqueue(Response.of(503, Map.of("X-Leader-Hint", "2"), "Not Leader")); // key "a" follows to node 2
            n2.enqueue(Response.committed(1));
            n1.enqueue(Response.committed(2));                                         // key "b" served at entry node 1
            try (ConfigdHttpClient c = twoNodeClient(n1, n2)) {
                assertEquals(1L, c.blocking().put("a", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq());
                assertEquals(2L, c.blocking().put("b", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq());
                assertEquals(2, n1.requestCount(), "key b restarted at the entry node — the key-a hint was not cached");
                assertEquals(1, n2.requestCount(), "node 2 was contacted only for key a's hint, never reused for key b");
                assertEquals("/v1/config/b", n1.lastRequest().path(), "node 1's second contact was for key b");
            }
        }
    }

    @Test
    @Tag("clause:R7-1_R7-2")
    void failsClosedOnAn3xxRedirectAndOnARicherWireSuppliedAddress() throws Exception {
        // R7-2: named forward extensions a driver MUST fail closed on. (a) An HTTP 3xx / Location redirect is
        // NOT part of the contract (the redirect surface is the 503 X-Leader-Hint header only, R2-1) -- a 301 is
        // a permanent request error the client refuses to chase, never following Location.
        try (MockControlPlane s = new MockControlPlane()) {
            s.enqueue(Response.of(301, Map.of("Location", "http://evil.example/v1/config/k"), "Moved"));
            try (ConfigdHttpClient c = client(s)) {
                assertThrows(BadRequestException.class,
                        () -> c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()));
                assertEquals(1, s.requestCount(), "a 3xx is terminal — the client never chases Location");
            }
        }
        // (b) A "richer" hint that carries a host:port instead of a bare numeric NodeId is unparseable, so the
        // client degrades it to hintless (R2-2 anti-SSRF: it MUST NOT accept a wire-supplied address) and
        // retries the endpoints it knows.
        try (MockControlPlane s = new MockControlPlane()) {
            s.enqueue(Response.of(503, Map.of("X-Leader-Hint", "10.0.0.5:8080"), "Not Leader")); // not a bare NodeId
            s.enqueue(Response.committed(3));
            try (ConfigdHttpClient c = client(s)) {
                assertEquals(3L, c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq());
                assertEquals(2, s.requestCount(), "the host:port hint was ignored; the known endpoint was retried");
            }
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

    /** A two-node client with a deterministic entry order (node 1 first) so the starting endpoint is fixed. */
    private static ConfigdHttpClient twoNodeClient(MockControlPlane n1, MockControlPlane n2) {
        Map<Integer, URI> map = new LinkedHashMap<>();
        map.put(1, n1.baseUri());
        map.put(2, n2.baseUri());
        return ConfigdHttpClient.builder()
                .endpoints(NodeEndpoints.ofMap(map))
                .allowPlaintext(true)
                .retryPolicy(FAST)
                .build();
    }
}

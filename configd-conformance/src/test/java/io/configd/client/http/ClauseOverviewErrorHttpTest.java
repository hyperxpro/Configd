package io.configd.client.http;

import io.configd.client.AuthFailedException;
import io.configd.client.BadRequestException;
import io.configd.client.CredentialSource;
import io.configd.client.ForbiddenException;
import io.configd.client.IndeterminateException;
import io.configd.client.ProtocolViolationException;
import io.configd.client.RetryPolicy;
import io.configd.client.edge.ConfigdEdgeClient;
import io.configd.distribution.wire.WatchCursor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.configd.client.http.MockControlPlane.Response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client-conforms tests for the architectural invariants (the plane split, the two version mechanisms, the
 * forward-compat MUSTs, fail-closed-on-unknown) asserted structurally against the reference client facades,
 * plus the HTTP error taxonomy (the full status table, the 401-vs-403 split, the retry classification
 * buckets) asserted against the scriptable {@link MockControlPlane}. The edge half of the error taxonomy
 * (the catch-up ladder, the (code, carrier) scope rule, and the edge column of the 401-vs-403 split) is in
 * {@code ClauseEdgeErrorTest}.
 */
@Timeout(30)
class ClauseOverviewErrorHttpTest {

    private static final RetryPolicy FAST = new RetryPolicy(Duration.ofMillis(2), Duration.ofMillis(10), 4);

    @Test
    @Tag("clause:OV2-1")
    void writesAreHttpOnlyTheEdgePlaneHasNoWriteSurface() {
        // All mutations happen on the HTTP plane; the binary edge plane is read/watch/fan-out only and
        // carries no writes. Structurally: the HTTP facade exposes put/delete; the edge facade exposes
        // neither (nor any write frame) -- there is no way to mutate over the edge.
        Set<String> http = publicMethodNames(ConfigdHttpClient.class);
        Set<String> edge = publicMethodNames(ConfigdEdgeClient.class);
        assertTrue(http.contains("put") && http.contains("delete"), "the HTTP client mutates: " + http);
        assertFalse(edge.contains("put") || edge.contains("delete") || containsIgnoreCase(edge, "write"),
                "the edge client exposes no write surface: " + edge);
    }

    @Test
    @Tag("clause:OV2-3")
    void thePlanesAreNotInterchangeableNoWatchOverHttpNoWriteOverEdge() {
        // You cannot watch over HTTP and you cannot write over the edge. Structurally: the HTTP facade
        // exposes no watch/subscribe method; the edge facade exposes watch/subscribe but no put/delete.
        Set<String> http = publicMethodNames(ConfigdHttpClient.class);
        Set<String> edge = publicMethodNames(ConfigdEdgeClient.class);
        assertFalse(containsIgnoreCase(http, "watch") || containsIgnoreCase(http, "subscribe"),
                "no watch/subscribe route on the HTTP plane: " + http);
        assertTrue(containsIgnoreCase(edge, "watch") && containsIgnoreCase(edge, "subscribe"),
                "the edge plane is where watch/subscribe live: " + edge);
        assertFalse(edge.contains("put") || edge.contains("delete"),
                "no write over the edge: " + edge);
    }

    @Test
    @Tag("clause:OV4-1")
    void thereAreTwoIndependentVersionMechanismsAndNeitherIsANegotiation() throws Exception {
        // The HTTP plane is versioned solely by the /v1/ path prefix -- no Accept/Content-Type version
        // negotiation, no version header, no capabilities/hello exchange. A single request goes straight to
        // the fixed /v1/ surface with no preamble round-trip.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.value("v", 1));
            c.blocking().get("k", GetOptions.defaults());
            assertEquals(1, s.requestCount(), "no hello/capabilities preamble — one request, straight to /v1/");
            assertTrue(s.lastRequest().path().startsWith("/v1/config/"), "the version IS the fixed path prefix");
        }
        // "Neither is a negotiation": no negotiate/hello/handshake/capabilities method exists on either facade;
        // the client cannot and does not renegotiate or downgrade a version on either plane.
        for (Class<?> facade : new Class<?>[]{ConfigdHttpClient.class, ConfigdEdgeClient.class}) {
            Set<String> names = publicMethodNames(facade);
            for (String forbidden : new String[]{"negotiat", "hello", "handshake", "capabilit", "downgrade"}) {
                assertFalse(containsIgnoreCase(names, forbidden),
                        facade.getSimpleName() + " must expose no version-" + forbidden + " method: " + names);
            }
        }
    }

    @Test
    @Tag("clause:OV5-4")
    void aV1DriverIsVectorNativeAndLeaderFollowingEvenAtN1() throws Exception {
        // Leader-following even at N=1: a single-endpoint client back-off-retries a hintless 503 (the normal
        // election window) rather than failing, so an N=1 driver stays forward-compatible.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(503, "Not Leader")); // hintless -- leader unknown during election
            s.enqueue(Response.committed(5));
            assertEquals(5L, c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq());
            assertEquals(2, s.requestCount(), "leader-following retry works with a single (N=1) endpoint");
        }
        // Vector-native cursor: even a single-shard (N=1) resume cursor is a (gid, S) vector, not a global
        // scalar, so it does not silently break when the cluster shards. The wire cursor type the client
        // shares across surfaces is keyed by gid.
        WatchCursor n1 = WatchCursor.of(0, 5);
        assertEquals(1, n1.components().size(), "a single-shard cursor is still a one-component vector, not a scalar");
        assertEquals(0, n1.components().get(0).gid(), "the component is keyed by gid (per-shard), not global");
        assertEquals(5L, n1.components().get(0).s(), "and carries that shard's sequence");
    }

    @Test
    @Tag("clause:OV7-3")
    void failsClosedOnAnUnknownStatusAndOnAnUnrecognizedSuccessBody() throws Exception {
        // A driver must fail closed on anything it does not recognize, never a weaker interpretation.
        // (a) An unknown/unmapped HTTP status (418) is a clean terminal error, not silently treated as success.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(418, "I'm a teapot"));
            assertThrows(BadRequestException.class, () -> c.blocking().get("k", GetOptions.defaults()));
            assertEquals(1, s.requestCount(), "an unknown status is a permanent, mapped rejection — not a retry, not success");
        }
        // (b) A 200 whose write body is not the recognized "Committed: seq=<N>" contract is a fail-closed
        // ProtocolViolation: the client never fabricates a seq from an unrecognized payload.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.of(200, Map.of("Content-Type", "application/json"), "{\"ok\":true}"));
            assertThrows(ProtocolViolationException.class,
                    () -> c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()));
        }
    }

    @Test
    @Tag("clause:E2-1")
    void theCompleteHttpStatusTableMapsToTheRequiredReaction() throws Exception {
        // The full status set the HTTP data plane returns, each with the normative driver reaction.
        // 200 read: value and version served.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.value("hello", 42));
            GetResult r = c.blocking().get("k", GetOptions.defaults());
            assertTrue(r.found());
            assertEquals(42L, r.version());
        }
        // 200 write: seq parsed from the body.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.committed(7));
            assertEquals(7L, c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq());
        }
        // 404: a definite "absent", surfaced as an empty result, never an exception or a routing retry.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(404, "Not Found"));
            assertFalse(c.blocking().get("missing", GetOptions.defaults()).found());
            assertEquals(1, s.requestCount(), "404 is a real answer — not retried");
        }
        // 400 / 405: permanent request error, do not retry unchanged.
        for (int status : new int[]{400, 405}) {
            try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
                s.enqueue(Response.text(status, "bad"));
                assertThrows(BadRequestException.class,
                        () -> c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()));
                assertEquals(1, s.requestCount(), status + " is permanent");
            }
        }
        // 401: (re)authenticate; do not hot-loop the same credential.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.of(401, Map.of("WWW-Authenticate", "Bearer"), "Unauthorized"));
            assertThrows(AuthFailedException.class, () -> c.blocking().get("k", GetOptions.defaults()));
            assertEquals(1, s.requestCount(), "401 without the replay guard does not hot-loop");
        }
        // 403: permanently forbidden for this principal.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(403, "denied"));
            assertThrows(ForbiddenException.class, () -> c.blocking().get("secret", GetOptions.defaults()));
            assertEquals(1, s.requestCount());
        }
        // 429: honor Retry-After, then retry.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.of(429, Map.of("Retry-After", "0"), "Overloaded"));
            s.enqueue(Response.committed(8));
            assertEquals(8L, c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq());
            assertEquals(2, s.requestCount());
        }
        // 503: back off and retry (here hintless, the election case).
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(503, "Not Leader"));
            s.enqueue(Response.committed(9));
            assertEquals(9L, c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq());
        }
        // 504: indeterminate; the write MAY have committed; retry-to-definite.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(504, "unconfirmed"));
            s.enqueue(Response.committed(10));
            assertEquals(10L, c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq());
        }
    }

    @Test
    @Tag("clause:E4-1")
    void the401Vs403SplitIsAuthenticationVsAuthorization() throws Exception {
        // Authentication failure (401) means (re)authenticate, a distinct re-auth-class reaction;
        // authorization failure (403) means permanently forbidden, a distinct terminal reaction. The client
        // surfaces them as different exception types so a caller reacts differently.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.of(401, Map.of("WWW-Authenticate", "Bearer"), "Unauthorized"));
            assertThrows(AuthFailedException.class, () -> c.blocking().get("k", GetOptions.defaults()));
        }
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(403, "denied"));
            assertThrows(ForbiddenException.class, () -> c.blocking().get("k", GetOptions.defaults()));
        }
    }

    @Test
    @Tag("clause:E7-1")
    void everyOutcomeFallsIntoTheCorrectRetryClass() throws Exception {
        // The retry classification buckets. TERMINAL (no retry): a 400 is not retried.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(400, "bad"));
            assertThrows(BadRequestException.class,
                    () -> c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()));
            assertEquals(1, s.requestCount(), "terminal bucket: 400 is not retried");
        }
        // RETRY (transient / backoff): a 503 is retried within the budget.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.text(503, "Not Leader"));
            s.enqueue(Response.committed(1));
            assertEquals(1L, c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()).seq());
            assertEquals(2, s.requestCount(), "transient bucket: 503 is retried");
        }
        // INDETERMINATE (idempotent-LWW retry-to-definite; on exhaustion UNKNOWN, never a false failure, NO RMW):
        // a 504 that never resolves surfaces IndeterminateException; the write MAY still have committed.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            for (int i = 0; i < FAST.maxAttempts(); i++) {
                s.enqueue(Response.text(504, "unconfirmed"));
            }
            assertThrows(IndeterminateException.class,
                    () -> c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()),
                    "indeterminate bucket: budget exhausted while indeterminate ⇒ UNKNOWN, not a definite failure");
        }
        // RETRY-ONLY-AFTER-CHANGING-CREDENTIAL: a 401 is not hot-looped -- it surfaces immediately for re-auth.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.of(401, Map.of("WWW-Authenticate", "Bearer"), "Unauthorized"));
            assertThrows(AuthFailedException.class,
                    () -> c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()));
            assertEquals(1, s.requestCount(), "cred-change bucket: 401 is not hot-looped on the same credential");
        }
    }

    // -----------------------------------------------------------------------

    private static ConfigdHttpClient client(MockControlPlane server) {
        return ConfigdHttpClient.builder()
                .endpoints(NodeEndpoints.of(server.baseUri()))
                .credentialSource(CredentialSource.staticBearer("t0ken"))
                .allowPlaintext(true)
                .retryPolicy(FAST)
                .build();
    }

    /** The public (non-Object) method names of a facade: the structural surface a driver-writer sees. */
    private static Set<String> publicMethodNames(Class<?> type) {
        Set<String> names = new TreeSet<>();
        for (Method m : type.getMethods()) {
            if (m.getDeclaringClass() != Object.class) {
                names.add(m.getName());
            }
        }
        return names;
    }

    private static boolean containsIgnoreCase(Set<String> names, String needle) {
        String n = needle.toLowerCase(Locale.ROOT);
        for (String s : names) {
            if (s.toLowerCase(Locale.ROOT).contains(n)) {
                return true;
            }
        }
        return false;
    }
}

package io.configd.client.http;

import io.configd.client.CredentialSource;
import io.configd.client.RetryPolicy;
import io.configd.client.edge.WatchTarget;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;

import static io.configd.client.http.MockControlPlane.Response;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Runner II — CLIENT-CONFORMS, §01 paths / access (the "A" clauses that bind the client side): the address model
 * ({@code (scope, path)} with {@code scope} a typed field, never a path segment — A2-1/A2-3/A8-2), the path-grammar
 * rejections the reference client performs <b>before</b> any byte reaches the wire (A3-1 absolute, A3-5 ≤ 1024 B),
 * the no-recursive-delete surface (A4-7), and the closed-enum fail-closed posture (A9-4). The HTTP-plane facts are
 * asserted against the scriptable {@link MockControlPlane} (recorded request path/query); the edge-plane
 * client-side validation is asserted against the public {@link WatchTarget} constructor.
 *
 * <p>The <b>server-side</b> halves of these clauses — the live edge server rejecting a seg-char / non-canonical
 * path (A3-1..A3-3, A3-4), the watch-authorization contract (A5-2, A6-x, A9-3), and the server fail-closed on an
 * unrecognized scope ordinal (A9-4) — live in {@code io.configd.conformance.ServerObeysPathAuthzTest}. Each clause
 * that binds BOTH sides carries its tag on both tests.
 */
@Timeout(30)
class ClausePathGrammarTest {

    private static final RetryPolicy FAST = new RetryPolicy(Duration.ofMillis(2), Duration.ofMillis(10), 4);

    @Test
    @Tag("clause:A2-1")
    @Tag("clause:A8-2")
    void scopeIsATypedFieldNeverAPathSegment() throws Exception {
        // HTTP plane (A8-2: the deployed admin surface): a non-GLOBAL scope rides the typed `?scope=` query
        // parameter; it is NEVER folded into the key path. The recorded path is the bare key; the scope is the
        // whole query, matched exactly (not composed with the key).
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.committed(1));
            c.blocking().put("app/name", "v".getBytes(StandardCharsets.UTF_8),
                    WriteOptions.defaults().scope(Scope.REGIONAL));
            MockControlPlane.Recorded r = s.lastRequest();
            assertEquals("/v1/config/app/name", r.path(), "the key path carries no scope segment (A2-1)");
            assertEquals("scope=REGIONAL", r.query(), "scope is a typed, exact query parameter (A2-1/A8-2)");
            assertFalse(r.path().contains("REGIONAL"), "the scope enum name never appears in the path (A2-1)");
        }
        // Edge plane (A8-2: the binary protocol carries scope as a typed field): the WatchTarget scope is a
        // distinct u8 field, orthogonal to the path bytes — the address is genuinely the pair (scope, path).
        WatchTarget regional = new WatchTarget(1 /* REGIONAL ordinal */, WatchTarget.Kind.KEY, "/app/name",
                EnumSet.noneOf(WatchTarget.Flag.class));
        assertEquals(1, regional.scope(), "edge scope is a typed field on the target (A8-2)");
        assertArrayEquals("/app/name".getBytes(StandardCharsets.UTF_8), regional.pathBytes(),
                "the path bytes carry no scope — scope is orthogonal (A2-1)");
    }

    @Test
    @Tag("clause:A2-3")
    @Tag("clause:A8-2")
    void scopeDefaultsToGlobalAndIsOmittedOnTheHttpWire() throws Exception {
        // A2-3: a caller that does not specify scope defaults to GLOBAL. A8-2: on the GLOBAL-only HTTP surface
        // GLOBAL is the identity — it is OMITTED from the wire (no `?scope=`), so a default request is
        // byte-identical to the pre-scope client (D7-4 compat).
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.value("v", 1));
            c.blocking().get("k", GetOptions.defaults());
            assertEquals(null, s.lastRequest().query(), "GLOBAL default ⇒ no scope parameter on the wire (A2-3)");
        }
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.committed(1));
            c.blocking().put("k", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults());
            assertEquals(null, s.lastRequest().query(), "a default (GLOBAL) write sends no scope parameter (A2-3)");
        }
        // Even an EXPLICIT GLOBAL is omitted (GLOBAL is the wire default, not a distinct transmitted value).
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.value("v", 1));
            c.blocking().get("k", GetOptions.defaults().scope(Scope.GLOBAL));
            assertEquals(null, s.lastRequest().query(), "explicit GLOBAL is still omitted (A2-3/A8-2)");
        }
    }

    @Test
    @Tag("clause:A3-1..A3-3")
    void clientRejectsANonAbsolutePathBeforeTheWireAndEncodesUtf8() {
        // A3-1: a path MUST be absolute. The edge client validates this in the WatchTarget constructor — a
        // non-absolute (or empty) KEY/PREFIX path is rejected LOCALLY, so no malformed target ever reaches the
        // wire (there is no MockEdgeServer here precisely because the reject is pre-transport).
        assertThrows(IllegalArgumentException.class, () -> WatchTarget.key("relative/no/leading/slash"),
                "a non-absolute path is rejected client-side (A3-1)");
        assertThrows(IllegalArgumentException.class, () -> WatchTarget.prefix("also/relative"),
                "a non-absolute prefix is rejected client-side (A3-1)");
        assertThrows(IllegalArgumentException.class, () -> WatchTarget.key(""),
                "an empty KEY path is rejected client-side (A3-2 non-empty segment / A3-1)");
        // A3-3 (encoding): a well-formed absolute path constructs and is carried as UTF-8 bytes on the wire.
        WatchTarget ok = WatchTarget.key("/app/name");
        assertArrayEquals("/app/name".getBytes(StandardCharsets.UTF_8), ok.pathBytes(),
                "the path is encoded as UTF-8 bytes on the wire (A3-3)");
    }

    @Test
    @Tag("clause:A3-5")
    void clientRejectsAnOversizePathBeforeTheWire() {
        // A3-5: a path MUST NOT exceed 1024 UTF-8 bytes. The edge client enforces the ceiling in the
        // WatchTarget constructor — a 1025-byte path is rejected LOCALLY (never sent), the deployed key-length
        // limit as a client-side pre-check.
        assertEquals(1024, WatchTarget.MAX_PATH_BYTES, "the ceiling is the deployed 1024-byte key limit (A3-5)");
        String tooLong = "/" + "a".repeat(1024); // 1 + 1024 = 1025 bytes
        assertThrows(IllegalArgumentException.class, () -> WatchTarget.key(tooLong),
                "a > 1024-byte path is rejected client-side (A3-5)");
        // Boundary: exactly 1024 bytes is accepted (the limit is inclusive).
        String exactly = "/" + "a".repeat(1023); // 1 + 1023 = 1024 bytes
        assertEquals(1024, WatchTarget.key(exactly).pathBytes().length, "exactly 1024 bytes is accepted (A3-5)");
    }

    @Test
    @Tag("clause:A4-7")
    void deleteAddressesExactlyOneConcreteKeyWithNoRecursiveSurface() throws Exception {
        // A4-7: v1 defines NO recursive/subtree delete. A delete addresses exactly one concrete key — a single
        // request to that key's path, never a subtree fan-out.
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.committed(7));
            c.blocking().delete("a/b", WriteOptions.defaults());
            assertEquals(1, s.requestCount(), "one concrete-key delete, not a subtree scatter (A4-7)");
            MockControlPlane.Recorded r = s.lastRequest();
            assertEquals("DELETE", r.method());
            assertEquals("/v1/config/a/b", r.path(), "the exact concrete key, no wildcard/subtree form (A4-7)");
        }
        // Structural: the client surface exposes NO recursive/subtree delete operation to call.
        assertFalse(hasRecursiveDeleteMethod(ConfigdHttpClient.class),
                "the client exposes no subtree/recursive delete (A4-7)");
        assertFalse(hasRecursiveDeleteMethod(ConfigdHttpClient.Blocking.class),
                "the blocking facade exposes no subtree/recursive delete (A4-7)");
    }

    @Test
    @Tag("clause:A9-4")
    void clientCannotExpressAnUnrecognizedCapabilityOrTargetIdentifier() {
        // A9-4 / A1.3 (fail closed on the unrecognized), CLIENT half: the driver's request-side identifier
        // surfaces are CLOSED enums, so a driver structurally cannot ASSUME or EMIT a capability/flag/kind it
        // does not recognize. The WATCH_CREATE flag set is exactly the three negotiated bits (W5-4a) and the
        // target kind is exactly {KEY, PREFIX, FULL} — there is no widening path to an unknown identifier.
        assertEquals(3, WatchTarget.Flag.values().length, "the flag set is closed to the negotiated bits (A9-4)");
        assertEquals(3, WatchTarget.Kind.values().length, "the target kind set is closed (A9-4)");
        // flagBits() only ever ORs known bits — it can never produce an out-of-set flag identifier.
        int allKnown = 0;
        for (WatchTarget.Flag f : WatchTarget.Flag.values()) {
            allKnown |= new WatchTarget(0, WatchTarget.Kind.KEY, "/k", EnumSet.of(f)).flagBits();
        }
        WatchTarget everyFlag = new WatchTarget(0, WatchTarget.Kind.KEY, "/k", EnumSet.allOf(WatchTarget.Flag.class));
        assertEquals(allKnown, everyFlag.flagBits(), "the emitted flag bits are exactly the known set (A9-4)");
        // The server-side fail-closed on an unrecognized scope ordinal (the one unrecognized identifier a
        // conforming client can still put on the wire) is asserted in ServerObeysPathAuthzTest (same tag).
    }

    // -----------------------------------------------------------------------

    private static boolean hasRecursiveDeleteMethod(Class<?> type) {
        for (Method m : type.getMethods()) {
            String n = m.getName().toLowerCase();
            if (n.contains("delete") && (n.contains("subtree") || n.contains("recursive") || n.contains("prefix"))) {
                return true;
            }
        }
        return false;
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

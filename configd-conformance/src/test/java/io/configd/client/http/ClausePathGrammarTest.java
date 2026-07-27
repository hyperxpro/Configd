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
 * Runner II -- CLIENT-CONFORMS, §01 paths / access (the "A" clauses that bind the client side). HTTP-plane
 * facts are asserted against the scriptable {@link MockControlPlane} (recorded request path/query; and, for a
 * rejection, that NOTHING reached the wire); edge-plane client-side validation is asserted against the public
 * {@link WatchTarget} constructor.
 *
 * <p>The <b>server-side</b> halves of these clauses live in {@code io.configd.conformance.ServerObeysPathAuthzTest}
 * / {@code ServerObeysPathAliasingTest}. Each clause that binds BOTH sides carries its tag on both tests.
 */
@Timeout(30)
class ClausePathGrammarTest {

    private static final RetryPolicy FAST = new RetryPolicy(Duration.ofMillis(2), Duration.ofMillis(10), 4);

    @Test
    @Tag("clause:A2-1")
    @Tag("clause:A8-2")
    void scopeIsATypedFieldNeverAPathSegment() throws Exception {
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.committed(1));
            c.blocking().put("app/name", "v".getBytes(StandardCharsets.UTF_8),
                    WriteOptions.defaults().scope(Scope.REGIONAL));
            MockControlPlane.Recorded r = s.lastRequest();
            assertEquals("/v1/config/app/name", r.path(), "the key path carries no scope segment (A2-1)");
            assertEquals("scope=REGIONAL", r.query(), "scope is a typed, exact query parameter (A2-1/A8-2)");
            assertFalse(r.path().contains("REGIONAL"), "the scope enum name never appears in the path (A2-1)");
        }
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
        // GLOBAL is omitted from the wire specifically so a default request stays byte-identical to the
        // pre-scope client (D7-4 compat).
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
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.value("v", 1));
            c.blocking().get("k", GetOptions.defaults().scope(Scope.GLOBAL));
            assertEquals(null, s.lastRequest().query(), "explicit GLOBAL is still omitted (A2-3/A8-2)");
        }
    }

    @Test
    @Tag("clause:A3-1..A3-3")
    void clientRejectsIllegalPathsBeforeTheWire() throws Exception {
        // No MockEdgeServer here — the reject is pre-transport, so there's nothing to reach the wire and record.
        assertThrows(IllegalArgumentException.class, () -> WatchTarget.key("relative/no/leading/slash"),
                "a non-absolute path is rejected client-side (A3-1)");
        assertThrows(IllegalArgumentException.class, () -> WatchTarget.prefix("also/relative"),
                "a non-absolute prefix is rejected client-side (A3-1)");
        assertThrows(IllegalArgumentException.class, () -> WatchTarget.key(""),
                "an empty KEY path is rejected client-side (A3-2 non-empty segment / A3-1)");
        // seg-char is [A-Za-z0-9._-]; a space and a tab (control byte) both fall outside it (A3-3).
        assertThrows(IllegalArgumentException.class, () -> WatchTarget.key("/app/a b"),
                "a space is not seg-char — rejected client-side (A3-3)");
        assertThrows(IllegalArgumentException.class, () -> WatchTarget.key("/app/a\tb"),
                "a tab (control byte) is not seg-char — rejected client-side (A3-3)");
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            assertThrows(IllegalArgumentException.class, () -> c.blocking().get("a b", GetOptions.defaults()));
            assertThrows(IllegalArgumentException.class,
                    () -> c.blocking().put("a:b", "v".getBytes(StandardCharsets.UTF_8), WriteOptions.defaults()));
            assertThrows(IllegalArgumentException.class, () -> c.blocking().delete("a\tb", WriteOptions.defaults()));
            assertEquals(0, s.requestCount(),
                    "an illegal key is rejected client-side — nothing reaches the wire (A3-3)");
        }
        // Proves the rejects above are the grammar, not a blanket refusal — a clean path/key still reaches
        // the server (A3-3).
        WatchTarget ok = WatchTarget.key("/app/name");
        assertArrayEquals("/app/name".getBytes(StandardCharsets.UTF_8), ok.pathBytes(),
                "the path is encoded as UTF-8 bytes on the wire (A3-3)");
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.value("v", 1));
            c.blocking().get("app/name", GetOptions.defaults());
            assertEquals("/v1/config/app/name", s.lastRequest().path(), "a clean key reaches the server (A3-3)");
        }
    }

    @Test
    @Tag("clause:A3-4")
    void clientRejectsNonCanonicalPathsBeforeTheWire() throws Exception {
        // Non-canonical spellings are rejected, never silently rewritten — normalizing them would let two
        // spellings a caller thinks are "the same" fragment into distinct server keys (A3-4).
        assertThrows(IllegalArgumentException.class, () -> WatchTarget.key("/a/../b"),
                "a '..' traversal segment is rejected client-side (A3-4)");
        assertThrows(IllegalArgumentException.class, () -> WatchTarget.key("/a/./b"),
                "a '.' segment is rejected client-side (A3-4)");
        assertThrows(IllegalArgumentException.class, () -> WatchTarget.key("/a//b"),
                "an empty '//' segment is rejected client-side (A3-4)");
        // The one tolerated non-strict form: a PREFIX subtree target MAY carry a single trailing slash
        // (`/a/` is the same as `/a/**`), and a canonical concrete path constructs -- neither throws.
        WatchTarget.prefix("/app/");
        WatchTarget.key("/a/b");
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            assertThrows(IllegalArgumentException.class, () -> c.blocking().get("a/../b", GetOptions.defaults()));
            assertThrows(IllegalArgumentException.class, () -> c.blocking().get("a//b", GetOptions.defaults()));
            assertThrows(IllegalArgumentException.class, () -> c.blocking().delete("a/./b", WriteOptions.defaults()));
            assertEquals(0, s.requestCount(),
                    "a non-canonical key is rejected client-side — nothing reaches the wire (A3-4)");
        }
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.value("v", 1));
            c.blocking().get("a/b", GetOptions.defaults());
            assertEquals("/v1/config/a/b", s.lastRequest().path(), "the canonical key reaches the server (A3-4)");
            assertEquals(1, s.requestCount());
        }
    }

    @Test
    @Tag("clause:A3-5")
    void clientRejectsAnOversizePathBeforeTheWire() {
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
        try (MockControlPlane s = new MockControlPlane(); ConfigdHttpClient c = client(s)) {
            s.enqueue(Response.committed(7));
            c.blocking().delete("a/b", WriteOptions.defaults());
            assertEquals(1, s.requestCount(), "one concrete-key delete, not a subtree scatter (A4-7)");
            MockControlPlane.Recorded r = s.lastRequest();
            assertEquals("DELETE", r.method());
            assertEquals("/v1/config/a/b", r.path(), "the exact concrete key, no wildcard/subtree form (A4-7)");
        }
        assertFalse(hasRecursiveDeleteMethod(ConfigdHttpClient.class),
                "the client exposes no subtree/recursive delete (A4-7)");
        assertFalse(hasRecursiveDeleteMethod(ConfigdHttpClient.Blocking.class),
                "the blocking facade exposes no subtree/recursive delete (A4-7)");
    }

    @Test
    @Tag("clause:A9-4")
    void clientCannotExpressAnUnrecognizedCapabilityOrTargetIdentifier() {
        // Fail-closed, CLIENT half (A9-4/A1.3): request-side identifier surfaces are CLOSED enums, so a driver
        // structurally cannot emit a capability/flag/kind it does not recognize.
        assertEquals(3, WatchTarget.Flag.values().length, "the flag set is closed to the negotiated bits (A9-4)");
        assertEquals(3, WatchTarget.Kind.values().length, "the target kind set is closed (A9-4)");
        // flagBits() only ever ORs known bits -- it can never produce an out-of-set flag identifier.
        int allKnown = 0;
        for (WatchTarget.Flag f : WatchTarget.Flag.values()) {
            allKnown |= new WatchTarget(0, WatchTarget.Kind.KEY, "/k", EnumSet.of(f)).flagBits();
        }
        WatchTarget everyFlag = new WatchTarget(0, WatchTarget.Kind.KEY, "/k", EnumSet.allOf(WatchTarget.Flag.class));
        assertEquals(allKnown, everyFlag.flagBits(), "the emitted flag bits are exactly the known set (A9-4)");
        // The server-side fail-closed on an unrecognized scope ordinal (the one unrecognized identifier a
        // conforming client can still put on the wire) is asserted in ServerObeysPathAuthzTest (same tag).
    }

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

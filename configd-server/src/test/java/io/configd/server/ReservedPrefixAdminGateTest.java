package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.AuthInterceptor;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.common.ConfigScope;
import io.configd.common.NodeId;
import io.configd.observability.MetricsRegistry;
import io.configd.store.VersionedConfigStore;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The reserved-prefix ADMIN gate, write-time validation, and the auth-disabled refusal,
 * proven through the transport-agnostic {@link AdminApiHandler} decision core (both HTTP adapters delegate
 * to it, so this is the single source of truth for the gate logic). The cross-transport HTTP lift of the
 * security controls lives in {@link AbstractAdminApiServerContract}; the prefix-evasion vectors
 * that require real percent-decoding at the transport->handler boundary (e.g. {@code %5Facl/}) live there
 * too. This class drives the decoded-key gate decisions directly with full control over the ACL, the
 * authenticator, and - via a capturing proposer - whether a write ever reached Raft.
 *
 * <p><b>"Store unchanged" proxy.</b> A {@link CapturingProposer} records whether {@code propose} was ever
 * invoked. The store is strictly DOWNSTREAM of the proposer (a write reaches the store only via
 * Raft commit + apply), so {@code calls == 0} after a request is a sound, pre-commit "store unchanged"
 * proof - stronger than inspecting an empty store, because it pins the rejection BEFORE the proposal.
 *
 * <p><b>Predicate-alignment in action.</b> The gate, the loader, and the store key off the SAME
 * post-strip key with the SAME {@code startsWith(PolicySerializer.ACL_PREFIX)} predicate (no normalization),
 * so a key that slips the gate (leading-slash {@code /_acl/...}, upper-case {@code _ACL/...}) is also a DISTINCT
 * store key the loader never matches - "evades the gate" and "is real policy" are mutually exclusive. The
 * over-gating cases ({@code _acl} no-slash, {@code _aclx}) prove the rule is EXACTLY the {@code _acl/}
 * prefix, nothing wider; the dot-dot case ({@code _acl/../x}) proves the gate does NOT normalize away from
 * the prefix.
 */
class ReservedPrefixAdminGateTest {


    private static final class CapturingProposer implements ConfigWriteService.RaftProposer {
        final AtomicInteger calls = new AtomicInteger();
        volatile List<String> lastKeys;

        @Override
        public ConfigWriteService.ProposeCommitResult propose(ConfigScope scope, List<String> keys, byte[] command) {
            calls.incrementAndGet();
            lastKeys = keys;
            return new ConfigWriteService.ProposeCommitResult.Committed(1L);
        }
    }

    private static AuthInterceptor auth() {
        return new AuthInterceptor(token -> switch (token) {
            case "root" -> new AuthInterceptor.AuthResult.Authenticated("root", Set.of());
            case "admin" -> new AuthInterceptor.AuthResult.Authenticated("adminP", Set.of());
            case "writer" -> new AuthInterceptor.AuthResult.Authenticated("writerP", Set.of());
            default -> new AuthInterceptor.AuthResult.Denied("unknown token");
        });
    }

    /**
         * root gets the un-carveable break-glass ({@code allOf} at {@code ""}); writerP deliberately gets
         * broad {@code READ+WRITE} at {@code ""} but explicitly NOT {@code ADMIN} - the escalation principal
         * whose broad WRITE must NOT reach the reserved subtree.
         */
    private static AclService acl() {
        AclService acl = new AclService();
        acl.grant("", "root", EnumSet.allOf(AclService.Permission.class));
        acl.grant("_acl/", "adminP", Set.of(AclService.Permission.ADMIN));
        acl.grant("", "writerP", Set.of(AclService.Permission.READ, AclService.Permission.WRITE));
        return acl;
    }

    private static AdminApiHandler handler(AclService acl, AuthInterceptor auth, CapturingProposer proposer) {
        return handler(acl, auth, proposer, new VersionedConfigStore());
    }

    private static AdminApiHandler handler(AclService acl, AuthInterceptor auth,
                                           CapturingProposer proposer, VersionedConfigStore store) {
        ConfigWriteService writeService = new ConfigWriteService(proposer, null, null);
        return new AdminApiHandler(new HealthService(), /* exporter */ null, store, writeService,
                /* readService */ null, auth, acl, StrongReadPolicy.defaultPolicy(),
                (scope, key) -> NodeId.of(1), /* auditLog */ null, /* replayGuard */ null);
    }

    private static AdminApiHandler.AdminRequest req(String method, String key, String token, byte[] body) {
        final URI uri;
        try {
            uri = new URI(null, null, "/v1/config/" + key, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("bad test URI for key '" + key + "'", e);
        }
        return new AdminApiHandler.AdminRequest() {
            @Override public String method() { return method; }
            @Override public URI uri() { return uri; }
            @Override public String header(String name) {
                return ("Authorization".equalsIgnoreCase(name) && token != null) ? "Bearer " + token : null;
            }
            @Override public byte[] body() { return body == null ? new byte[0] : body; }
        };
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static int status(AdminApiHandler h, String method, String key, String token, String body)
            throws Exception {
        return h.handle(req(method, key, token, body == null ? null : b(body))).status();
    }


    @Test
    void writeButNotAdminPrincipalCannotWriteReservedPrefix() throws Exception {
        CapturingProposer proposer = new CapturingProposer();
        AdminApiHandler h = handler(acl(), auth(), proposer);

        assertEquals(403, status(h, "PUT", "_acl/roles/x", "writer", "allow READ app."),
                "WRITE-but-not-ADMIN must be forbidden from PUTting an _acl/ key (escalation closed)");
        assertEquals(403, status(h, "DELETE", "_acl/roles/x", "writer", null),
                "WRITE-but-not-ADMIN must be forbidden from DELETEing an _acl/ key");
        assertEquals(403, status(h, "PUT", "_acl/bindings/writerP", "writer", "some-role"),
                "a principal cannot grant itself a role by writing its own _acl/ binding");
        assertEquals(0, proposer.calls.get(), "no reserved-prefix write may reach Raft without ADMIN");
    }


    @Test
    void adminPrincipalAndRootMayWriteReservedPrefix() throws Exception {
        CapturingProposer proposer = new CapturingProposer();
        AdminApiHandler h = handler(acl(), auth(), proposer);

        assertEquals(200, status(h, "PUT", "_acl/roles/x", "admin", "allow READ app."),
                "an ADMIN grant on _acl/ must allow a valid policy write");
        assertEquals(200, status(h, "PUT", "_acl/roles/y", "root", "allow READ app."),
                "root (allOf) must always reach _acl/");
        assertEquals(200, status(h, "DELETE", "_acl/roles/x", "admin", null),
                "ADMIN must also allow DELETE of an _acl/ key");
        assertEquals(3, proposer.calls.get(), "each authorized reserved write must reach the proposer");
    }


    @Test
    void dotDotInsideReservedPrefixStaysGated() throws Exception {
        CapturingProposer proposer = new CapturingProposer();
        AdminApiHandler h = handler(acl(), auth(), proposer);
        // "_acl/../x".startsWith("_acl/") is TRUE - the gate does NOT normalize away from the prefix, so a
        // non-ADMIN is still forbidden (the predicate-alignment: the store would key it verbatim too).
        assertEquals(403, status(h, "PUT", "_acl/../x", "writer", "v"),
                "a dot-dot inside the reserved prefix must NOT be normalized away from the ADMIN gate");
        assertEquals(0, proposer.calls.get(), "the gated dot-dot write must not reach Raft");
    }

    @Test
    void leadingSlashAndUpperCaseAreDistinctUngatedKeysNotAReservedLeak() throws Exception {
        CapturingProposer proposer = new CapturingProposer();
        AdminApiHandler h = handler(acl(), auth(), proposer);

        // "/_acl/roles/x" (the //_acl/ decoded form) does NOT startWith "_acl/" => NOT gated; writerP has
        // WRITE on "" => 200. Crucially it is a DISTINCT store key the loader's startsWith("_acl/") never
        // matches, so it cannot corrupt real policy - the predicate-alignment proof.
        assertEquals(200, status(h, "PUT", "/_acl/roles/x", "writer", "v"),
                "a leading-slash key is a DIFFERENT, ungated key (not a reserved write)");
        assertEquals(List.of("/_acl/roles/x"), proposer.lastKeys,
                "the write must route to the leading-slash key VERBATIM — distinct from _acl/roles/x");

        // "_ACL/roles/x" (upper-case) likewise is not the reserved prefix (case-sensitive) => a distinct,
        // ungated key.
        assertEquals(200, status(h, "PUT", "_ACL/roles/x", "writer", "v"),
                "upper-case _ACL/ is a DIFFERENT, ungated key");
        assertEquals(List.of("_ACL/roles/x"), proposer.lastKeys, "routes to the verbatim upper-case key");
    }


    @Test
    void nonAdminIsRejectedForBothPutAndDeleteOnReserved() throws Exception {
        CapturingProposer proposer = new CapturingProposer();
        AclService acl = acl();
        acl.grant("", "readerP", Set.of(AclService.Permission.READ));
        AuthInterceptor auth = new AuthInterceptor(token -> "reader".equals(token)
                ? new AuthInterceptor.AuthResult.Authenticated("readerP", Set.of())
                : auth().authenticate(token));
        AdminApiHandler h = handler(acl, auth, proposer);

        assertEquals(403, status(h, "PUT", "_acl/roles/x", "reader", "allow READ app."), "reader PUT _acl/ → 403");
        assertEquals(403, status(h, "DELETE", "_acl/roles/x", "reader", null), "reader DELETE _acl/ → 403");
        assertEquals(403, status(h, "GET", "_acl/roles/x", "reader", null),
                "a non-ADMIN GET of _acl/ must be 403 — policy DISCLOSURE is closed");
        assertEquals(403, status(h, "GET", "_acl/roles/x", "writer", null),
                "even a broad-WRITE principal cannot READ _acl/ without ADMIN (disclosure)");
        assertEquals(0, proposer.calls.get(), "none of the denied reserved requests reached Raft");
    }

    // Self-deny is survivable via break-glass: an adversarial config policy denying adminP's ADMIN
    // (and even an attempt to carve root) does NOT lock root out. The HTTP/handler lift of
    // AclConfigPolicyLoaderTest#rootIsUncarveableByAnyConfigRole.

    @Test
    void rootStillReachesReservedAfterAnAdversarialSelfDenyPolicy() throws Exception {
        CapturingProposer proposer = new CapturingProposer();
        AclService acl = acl();
        VersionedConfigStore store = new VersionedConfigStore();
        AclConfigPolicyLoader loader = new AclConfigPolicyLoader(acl, store,
                AclConfigPolicyLoader.RESERVED_ROLES, AclConfigPolicyLoader.RESERVED_PRINCIPALS, new MetricsRegistry());

        // adminP authors a policy that DENIES ADMIN on _acl/ and binds itself to it (self-deny). This is a
        // valid, authorized write for adminP; here we install its EFFECT (store + rebuild) to model the
        // committed apply.
        long seq = 0;
        store.put("_acl/roles/lockout", b("deny ADMIN _acl/"), ++seq);
        store.put("_acl/bindings/adminP", b("lockout"), ++seq);
        loader.rebuild();

        AdminApiHandler h = handler(acl, auth(), proposer, store);

        // adminP is now locked out of _acl/ (config DENY of ADMIN is absolute, composes across layers)...
        assertEquals(403, status(h, "PUT", "_acl/roles/x", "admin", "allow READ app."),
                "adminP self-denied its ADMIN — it is now locked out of _acl/");
        assertEquals(200, status(h, "PUT", "_acl/roles/x", "root", "allow READ app."),
                "root must STILL be able to write _acl/ (break-glass un-carveable)");
        assertEquals(200, status(h, "DELETE", "_acl/bindings/adminP", "root", null),
                "root must STILL be able to delete the lockout binding to repair the situation");

        // An even more direct attack - binding ROOT itself to the deny role - is rejected wholesale by the
        // loader (reserved principal), so the load fails closed and root keeps full authority.
        store.put("_acl/bindings/root", b("lockout"), ++seq);
        loader.rebuild();
        assertEquals(200, status(h, "PUT", "_acl/roles/z", "root", "allow READ app."),
                "binding root to a deny role is rejected wholesale — root remains authorized");
    }


    @Test
    void malformedOrReservedAclWriteIsRejected400PreCommit() throws Exception {
        CapturingProposer proposer = new CapturingProposer();
        AdminApiHandler h = handler(acl(), auth(), proposer);

        assertEquals(400, status(h, "PUT", "_acl/roles/x", "admin", "allow NOPE app."),
                "a malformed role line must be rejected 400 at write-time");
        assertEquals(400, status(h, "PUT", "_acl/zzz", "admin", "junk"),
                "an unrecognized _acl/ key shape must be rejected 400 at write-time");
        assertEquals(400, status(h, "PUT", "_acl/roles/admin", "admin", "allow READ app."),
                "defining the reserved role 'admin' must be rejected 400 at write-time");
        assertEquals(400, status(h, "PUT", "_acl/bindings/root", "admin", "some-role"),
                "binding the reserved principal 'root' must be rejected 400 at write-time");
        assertEquals(0, proposer.calls.get(), "no rejected _acl/ write may reach Raft (store unchanged)");

        // Well-formed-but-incomplete (a binding to a not-yet-defined role) is NOT an error: it
        // parses, passes validation, and commits - single-key validation is exactly the right granularity.
        assertEquals(200, status(h, "PUT", "_acl/bindings/alice", "admin", "not-yet-defined-role"),
                "a binding to an undefined role is well-formed-but-incomplete — accepted, not rejected");
        assertEquals(1, proposer.calls.get(), "the well-formed incomplete binding commits");
    }


    @Test
    void authDisabledRefusesReservedWritesButNotOrdinaryWritesOrReservedReads() throws Exception {
        CapturingProposer proposer = new CapturingProposer();
        VersionedConfigStore store = new VersionedConfigStore();
        store.put("_acl/roles/x", b("allow READ app."), 1);
        AdminApiHandler h = handler(/* acl */ null, /* auth */ null, proposer, store);

        assertEquals(403, status(h, "PUT", "_acl/roles/y", null, "allow READ app."),
                "auth-off: an _acl/ PUT must be refused (it would be seeded into policy on the first secured boot)");
        assertEquals(403, status(h, "DELETE", "_acl/roles/x", null, null),
                "auth-off: an _acl/ DELETE must be refused");
        assertEquals(403, status(h, "PUT", "_system/x", null, "v"),
                "auth-off: a _system/ write must be refused too");
        assertEquals(0, proposer.calls.get(), "no reserved write reaches Raft while auth is disabled (store unchanged)");

        assertEquals(200, status(h, "PUT", "app/feature", null, "on"),
                "auth-off: an ordinary write must still commit (only reserved writes are refused)");
        assertEquals(1, proposer.calls.get(), "the ordinary write reached Raft");

        assertNotEquals(403, status(h, "GET", "_acl/roles/x", null, null),
                "auth-off: a reserved READ is not refused (reads stay open when auth is disabled)");
    }


    @Test
    void reservedKeyFailsClosedWhenAuthOnButNoAclService() throws Exception {
        // AdminApiHandler treats authInterceptor and aclService as INDEPENDENTLY nullable. The standard
        // server wires them together, but the gate must not assume that: with auth on and acl off, a
        // reserved key's ADMIN is unevaluable => 403 for GET/PUT/DELETE; an ordinary key stays authn-only.
        CapturingProposer proposer = new CapturingProposer();
        AdminApiHandler h = handler(/* acl */ null, auth(), proposer);

        assertEquals(403, status(h, "GET", "_acl/roles/x", "admin", null),
                "reserved GET with auth-on but no ACL service must fail closed");
        assertEquals(403, status(h, "PUT", "_acl/roles/x", "admin", "allow READ app."),
                "reserved PUT with auth-on but no ACL service must fail closed");
        assertEquals(403, status(h, "DELETE", "_acl/roles/x", "admin", null),
                "reserved DELETE with auth-on but no ACL service must fail closed");
        assertEquals(0, proposer.calls.get(), "no reserved write reached Raft (store unchanged)");

        assertEquals(200, status(h, "PUT", "app/feature", "admin", "on"),
                "an ordinary key with no ACL service is authn-only (unchanged)");
        assertEquals(1, proposer.calls.get(), "only the ordinary write reached Raft");
    }


    @Test
    void overGatingBoundsAreExactlyTheAclPrefix() throws Exception {
        CapturingProposer proposer = new CapturingProposer();
        AdminApiHandler h = handler(acl(), auth(), proposer);

        // The trailing slash in "_acl/" is load-bearing: none of these are reserved, so writerP (WRITE-only)
        // commits them all - proving the gate is not over-broad.
        for (String ordinary : List.of("_acl", "_aclx", "_acladmin", "acl/roles/x", "app/feature", "secure-ish")) {
            assertEquals(200, status(h, "PUT", ordinary, "writer", "v"),
                    "a non-_acl/ key must NOT be ADMIN-gated: '" + ordinary + "'");
        }
        assertEquals(6, proposer.calls.get(), "every non-reserved write by the WRITE-only principal committed");

        // Byte-identity at the gate for an ordinary key: writerP's READ still serves a GET, and its lack of
        // ADMIN is irrelevant to ordinary keys (the gate only overrides for reserved prefixes).
        VersionedConfigStore store = new VersionedConfigStore();
        store.put("app/feature", b("on"), 1);
        AdminApiHandler hr = handler(acl(), auth(), new CapturingProposer(), store);
        assertEquals(200, status(hr, "GET", "app/feature", "writer", null),
                "an ordinary GET is decided exactly as before (READ on '') — the gate changes no ordinary decision");
    }
}

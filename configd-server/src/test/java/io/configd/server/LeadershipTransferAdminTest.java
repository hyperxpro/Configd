package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.AdminService;
import io.configd.api.AuthInterceptor;
import io.configd.api.HealthService;
import io.configd.api.ReplayGuard;
import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.store.VersionedConfigStore;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADMIN-gated leadership-transfer endpoint decided through the transport-agnostic
 * {@link AdminApiHandler} core, with a fake {@link AdminApiHandler.LeadershipAdmin} seam so the routing,
 * the ADMIN gate (401/403 / auth-off refusal / fail-closed), the response mapping, and the
 * "transfer-not-attempted" cases are proven deterministically without a real driver or owner threads. The
 * owner-thread posting through the REAL driver is proven in {@link DriverLeadershipAdminOwnerThreadTest};
 * the mechanism (a transfer actually moving leadership, with no committed-write loss and restorability) is
 * proven by {@code RaftNodeTest.LeadershipTransferTests}.
 */
class LeadershipTransferAdminTest {

    private static final String PATH = "/v1/admin/groups/0/transfer-leadership";

    // ---------------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------------

    /** A fake seam: records the (attempted) transfers, and returns a configurable result or throws timeout. */
    private static final class FakeLeadershipAdmin implements AdminApiHandler.LeadershipAdmin {
        final Set<Integer> knownGroups;
        final AtomicInteger attempts = new AtomicInteger();
        final List<NodeId> targets = new CopyOnWriteArrayList<>();
        volatile BiFunction<Integer, NodeId, AdminService.AdminResult> result =
                (g, t) -> new AdminService.AdminResult.Success("initiated");
        volatile boolean throwTimeout;

        FakeLeadershipAdmin(Integer... groups) {
            this.knownGroups = Set.of(groups);
        }

        @Override public boolean hasGroup(int groupId) {
            return knownGroups.contains(groupId);
        }

        @Override public AdminService.AdminResult transferLeadership(int groupId, NodeId target) {
            attempts.incrementAndGet();
            targets.add(target);
            if (throwTimeout) {
                throw new AdminApiHandler.LeadershipTransferTimeout(groupId, 5_000L);
            }
            return result.apply(groupId, target);
        }
    }

    /** root -> "root" (no roles); admin -> "adminP"; writer -> "writerP"; anything else denied. */
    private static AuthInterceptor auth() {
        return new AuthInterceptor(token -> switch (token) {
            case "root" -> new AuthInterceptor.AuthResult.Authenticated("root", Set.of());
            case "admin" -> new AuthInterceptor.AuthResult.Authenticated("adminP", Set.of());
            case "writer" -> new AuthInterceptor.AuthResult.Authenticated("writerP", Set.of());
            default -> new AuthInterceptor.AuthResult.Denied("unknown token");
        });
    }

    /**
     * root: {@code allOf} at {@code ""} (break-glass). adminP: {@code ADMIN} on the reserved
     * {@code _system/} control namespace (so it covers the transfer resource key). writerP: broad
     * {@code READ+WRITE} on {@code ""} but explicitly NOT {@code ADMIN} - the escalation principal whose
     * write authority must NOT reach a control operation.
     */
    private static AclService acl() {
        AclService acl = new AclService();
        acl.grant("", "root", EnumSet.allOf(AclService.Permission.class));
        acl.grant("_system/", "adminP", Set.of(AclService.Permission.ADMIN));
        acl.grant("", "writerP", Set.of(AclService.Permission.READ, AclService.Permission.WRITE));
        return acl;
    }

    private static AdminApiHandler handler(AclService acl, AuthInterceptor auth,
                                           AdminApiHandler.LeadershipAdmin seam) {
        return new AdminApiHandler(new HealthService(), /* exporter */ null, new VersionedConfigStore(),
                /* writeService */ null, /* readService */ null, auth, acl, StrongReadPolicy.defaultPolicy(),
                (scope, key) -> NodeId.of(1), /* auditLog */ null, /* replayGuard */ null, seam);
    }

    private static AdminApiHandler.AdminRequest req(String method, String path, String query, String token) {
        final URI uri;
        try {
            uri = new URI(null, null, path, query, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("bad test URI: " + path + "?" + query, e);
        }
        return new AdminApiHandler.AdminRequest() {
            @Override public String method() { return method; }
            @Override public URI uri() { return uri; }
            @Override public String header(String name) {
                return ("Authorization".equalsIgnoreCase(name) && token != null) ? "Bearer " + token : null;
            }
            @Override public byte[] body() { return new byte[0]; }
        };
    }

    private static AdminApiHandler.AdminResponse post(AdminApiHandler h, String path, String query, String token)
            throws Exception {
        return h.handle(req("POST", path, query, token));
    }

    // =============================================================================================
    // ADMIN gate: 401 unauthenticated, 403 non-admin, allow admin/root.
    // =============================================================================================

    @Test
    void unauthenticatedRequestIsRejected401AndTransferNotAttempted() throws Exception {
        FakeLeadershipAdmin seam = new FakeLeadershipAdmin(0);
        AdminApiHandler h = handler(acl(), auth(), seam);
        assertEquals(401, post(h, PATH, "target=2", /* no token */ null).status(),
                "an unauthenticated leadership transfer must be 401");
        assertEquals(0, seam.attempts.get(), "an unauthenticated transfer must never reach the mechanism");
    }

    @Test
    void nonAdminAuthenticatedPrincipalIsForbidden403AndTransferNotAttempted() throws Exception {
        FakeLeadershipAdmin seam = new FakeLeadershipAdmin(0);
        AdminApiHandler h = handler(acl(), auth(), seam);
        // writerP holds broad WRITE but not ADMIN: a control op requires ADMIN -> 403, never attempted.
        assertEquals(403, post(h, PATH, "target=2", "writer").status(),
                "a WRITE-but-not-ADMIN principal must be forbidden from a leadership transfer");
        assertEquals(0, seam.attempts.get(), "a forbidden transfer must never reach the mechanism");
    }

    @Test
    void adminPrincipalAndRootMayTransfer_initiated200() throws Exception {
        FakeLeadershipAdmin seam = new FakeLeadershipAdmin(0);
        AdminApiHandler h = handler(acl(), auth(), seam);
        assertEquals(200, post(h, PATH, "target=2", "admin").status(),
                "an ADMIN grant on _system/ must allow the transfer");
        assertEquals(200, post(h, PATH, "target=2", "root").status(),
                "root (allOf break-glass) must always be allowed");
        assertEquals(2, seam.attempts.get(), "both authorized transfers reached the mechanism");
        assertEquals(List.of(NodeId.of(2), NodeId.of(2)), seam.targets,
                "the parsed ?target must be handed to the mechanism verbatim");
    }

    // =============================================================================================
    // Auth-off posture: a control op is REFUSED when auth is disabled (bring-up footgun closed).
    // =============================================================================================

    @Test
    void authDisabledRefusesTransfer403() throws Exception {
        FakeLeadershipAdmin seam = new FakeLeadershipAdmin(0);
        // Auth OFF and ACL OFF (the loudly-warned non-production mode).
        AdminApiHandler h = handler(/* acl */ null, /* auth */ null, seam);
        assertEquals(403, post(h, PATH, "target=2", null).status(),
                "auth-off: a leadership transfer (a privileged control op) must be refused, not open");
        assertEquals(0, seam.attempts.get(), "no transfer is attempted while auth is disabled");
    }

    @Test
    void failsClosedWhenAuthOnButNoAclService403() throws Exception {
        FakeLeadershipAdmin seam = new FakeLeadershipAdmin(0);
        // Auth on, ACL off: ADMIN cannot be evaluated -> DENY (never fall through to allowed).
        AdminApiHandler h = handler(/* acl */ null, auth(), seam);
        assertEquals(403, post(h, PATH, "target=2", "admin").status(),
                "auth-on but no ACL service: the transfer must fail closed (ADMIN unevaluable)");
        assertEquals(0, seam.attempts.get(), "a fail-closed transfer must never reach the mechanism");
    }

    // =============================================================================================
    // Response mapping: NotLeader -> 503 + hint; Failure -> 409; timeout -> 503.
    // =============================================================================================

    @Test
    void notLeaderReturns503WithLeaderHint() throws Exception {
        FakeLeadershipAdmin seam = new FakeLeadershipAdmin(0);
        seam.result = (g, t) -> new AdminService.AdminResult.NotLeader(NodeId.of(7));
        AdminApiHandler h = handler(acl(), auth(), seam);
        AdminApiHandler.AdminResponse resp = post(h, PATH, "target=2", "root");
        assertEquals(503, resp.status(), "a transfer requested on a non-leader must be 503");
        assertEquals("7", resp.headers().get("X-Leader-Hint"),
                "the 503 must carry the leader hint so the operator retries the right node");
    }

    @Test
    void preconditionFailureReturns409() throws Exception {
        FakeLeadershipAdmin seam = new FakeLeadershipAdmin(0);
        seam.result = (g, t) -> new AdminService.AdminResult.Failure("target is not a voter");
        AdminApiHandler h = handler(acl(), auth(), seam);
        assertEquals(409, post(h, PATH, "target=2", "root").status(),
                "a failed transfer precondition (self / non-voter / config-change-pending) must be 409");
    }

    @Test
    void ownerThreadTimeoutReturns503() throws Exception {
        FakeLeadershipAdmin seam = new FakeLeadershipAdmin(0);
        seam.throwTimeout = true;
        AdminApiHandler h = handler(acl(), auth(), seam);
        assertEquals(503, post(h, PATH, "target=2", "root").status(),
                "a bounded-wait timeout on the owner thread must map to 503 (unknown, retryable)");
    }

    // =============================================================================================
    // Input validation: malformed group id / target, unknown group, method, sub-resource, null seam.
    // =============================================================================================

    @Test
    void malformedGroupIdReturns400() throws Exception {
        FakeLeadershipAdmin seam = new FakeLeadershipAdmin(0);
        AdminApiHandler h = handler(acl(), auth(), seam);
        // A non-integer group segment is the caller's own malformed URL -> 400 (no server state leaked).
        assertEquals(400, post(h, "/v1/admin/groups/abc/transfer-leadership", "target=2", "root").status(),
                "a non-integer group id must be 400");
        // A nested path in the group segment is not a single group id.
        assertEquals(400, post(h, "/v1/admin/groups/0/nested/transfer-leadership", "target=2", "root").status(),
                "a nested group segment must be 400");
        // The group id omitted entirely (prefix abuts suffix) is a malformed request, not a crash.
        assertEquals(400, post(h, "/v1/admin/groups/transfer-leadership", "target=2", "root").status(),
                "an omitted group id must be 400 (no substring fault)");
        assertEquals(0, seam.attempts.get(), "a malformed group id never reaches the mechanism");
    }

    @Test
    void missingOrMalformedTargetReturns400() throws Exception {
        FakeLeadershipAdmin seam = new FakeLeadershipAdmin(0);
        AdminApiHandler h = handler(acl(), auth(), seam);
        assertEquals(400, post(h, PATH, /* no target */ null, "root").status(),
                "a missing ?target must be 400");
        assertEquals(400, post(h, PATH, "target=", "root").status(),
                "a blank ?target must be 400");
        assertEquals(400, post(h, PATH, "target=notanode", "root").status(),
                "a non-integer ?target must be 400");
        assertEquals(0, seam.attempts.get(), "a malformed target never reaches the mechanism");
    }

    @Test
    void unknownGroupReturns400AfterTheAdminGate() throws Exception {
        FakeLeadershipAdmin seam = new FakeLeadershipAdmin(0); // only group 0 is registered
        AdminApiHandler h = handler(acl(), auth(), seam);
        assertEquals(400, post(h, "/v1/admin/groups/99/transfer-leadership", "target=2", "root").status(),
                "a transfer for an unregistered group must be 400");
        // ...but an UNAUTHORIZED caller sees 403, never the group-existence 400 (no probing).
        assertEquals(403, post(h, "/v1/admin/groups/99/transfer-leadership", "target=2", "writer").status(),
                "an unauthorized caller must not learn whether a group exists (403, not 400)");
        assertEquals(0, seam.attempts.get(), "an unknown-group transfer never reaches the mechanism");
    }

    @Test
    void wrongMethodReturns405AndUnknownSubResourceReturns404() throws Exception {
        FakeLeadershipAdmin seam = new FakeLeadershipAdmin(0);
        AdminApiHandler h = handler(acl(), auth(), seam);
        assertEquals(405, h.handle(req("GET", PATH, "target=2", "root")).status(),
                "a non-POST on the transfer endpoint must be 405");
        assertEquals(404, h.handle(req("POST", "/v1/admin/groups/0/frobnicate", null, "root")).status(),
                "an unknown admin-groups sub-resource must be 404");
    }

    @Test
    void withoutTheSeamTheEndpointIsAbsent404() throws Exception {
        // The 11-arg constructor wires no seam: the route is byte-identical to before the endpoint existed.
        AdminApiHandler h = new AdminApiHandler(new HealthService(), null, new VersionedConfigStore(),
                null, null, auth(), acl(), StrongReadPolicy.defaultPolicy(),
                (BiFunction<io.configd.common.ConfigScope, String, NodeId>) (scope, key) -> NodeId.of(1),
                null, null);
        assertEquals(404, post(h, PATH, "target=2", "root").status(),
                "with no leadership seam wired, the transfer path must fall through to 404");
    }

    // =============================================================================================
    // Replay protection: a captured, valid-bearer transfer must not be replayable to force churn.
    // =============================================================================================

    @Test
    void replayedTransferIsRejected409AndNotAttempted() throws Exception {
        FakeLeadershipAdmin seam = new FakeLeadershipAdmin(0);
        Clock clock = Clock.system();
        ReplayGuard guard = new ReplayGuard(clock);
        AdminApiHandler h = new AdminApiHandler(new HealthService(), null, new VersionedConfigStore(),
                null, null, auth(), acl(), StrongReadPolicy.defaultPolicy(),
                (scope, key) -> NodeId.of(1), null, guard, seam);
        String ts = String.valueOf(clock.currentTimeMillis());
        String nonce = "transfer-nonce-1";

        // The first (fresh) request passes the guard and reaches the mechanism.
        assertEquals(200, h.handle(transferReqWithReplay("root", ts, nonce)).status(),
                "the first transfer request is accepted through the replay guard");
        assertEquals(1, seam.attempts.get(), "the first transfer reached the mechanism");

        // Replaying the SAME captured request (same nonce) is rejected 409, and NOT attempted again.
        assertEquals(409, h.handle(transferReqWithReplay("root", ts, nonce)).status(),
                "a replayed transfer request must be rejected (replayed nonce)");
        assertEquals(1, seam.attempts.get(), "a replayed transfer must not reach the mechanism");
    }

    private static AdminApiHandler.AdminRequest transferReqWithReplay(String token, String ts, String nonce) {
        final URI uri;
        try {
            uri = new URI(null, null, PATH, "target=2", null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
        return new AdminApiHandler.AdminRequest() {
            @Override public String method() { return "POST"; }
            @Override public URI uri() { return uri; }
            @Override public String header(String name) {
                if ("Authorization".equalsIgnoreCase(name)) {
                    return token != null ? "Bearer " + token : null;
                }
                if (ReplayGuard.TIMESTAMP_HEADER.equalsIgnoreCase(name)) return ts;
                if (ReplayGuard.NONCE_HEADER.equalsIgnoreCase(name)) return nonce;
                return null;
            }
            @Override public byte[] body() { return new byte[0]; }
        };
    }

    // =============================================================================================
    // Restorability at the endpoint layer: a second transfer (back to the original) is equally allowed.
    // =============================================================================================

    @Test
    void leadershipIsOperatorManageableInBothDirections() throws Exception {
        FakeLeadershipAdmin seam = new FakeLeadershipAdmin(0);
        AdminApiHandler h = handler(acl(), auth(), seam);
        assertEquals(200, post(h, PATH, "target=2", "root").status(), "transfer to node 2 is allowed");
        assertEquals(200, post(h, PATH, "target=1", "root").status(), "transfer back to node 1 is equally allowed");
        assertEquals(List.of(NodeId.of(2), NodeId.of(1)), seam.targets,
                "leadership placement is not one-way: both directions reach the mechanism");
        assertTrue(seam.attempts.get() == 2, "both directional transfers were attempted");
    }
}

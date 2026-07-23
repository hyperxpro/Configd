package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.AdminService;
import io.configd.api.AuthInterceptor;
import io.configd.api.HealthService;
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
 * The ADMIN-gated Raft cluster endpoints ({@code GET /v1/admin/raft/status},
 * {@code POST /v1/admin/raft/add-server}) decided through the transport-agnostic {@link AdminApiHandler}
 * core with a fake {@link AdminApiHandler.RaftClusterAdmin} seam, so routing, the ADMIN gate (401/403 /
 * auth-off refusal / fail-closed), the add-server result mapping, and the not-attempted cases are proven
 * deterministically without a real driver. The owner-thread mechanism (a voter actually added, on-owner) is
 * proven against the real driver in {@link DriverRaftClusterAdminAddServerTest}.
 */
class RaftClusterAdminTest {

    private static final String STATUS = "/v1/admin/raft/status";
    private static final String ADD = "/v1/admin/raft/add-server";

    /** A fake seam: records add-server attempts, returns a configurable result or throws timeout. */
    private static final class FakeRaftClusterAdmin implements AdminApiHandler.RaftClusterAdmin {
        final Set<Integer> knownGroups;
        final AtomicInteger addAttempts = new AtomicInteger();
        final List<NodeId> addTargets = new CopyOnWriteArrayList<>();
        volatile List<AdminApiHandler.GroupStatus> statusResult = List.of(
                new AdminApiHandler.GroupStatus(0, "LEADER", NodeId.of(1), 3, 10, 10,
                        Set.of(NodeId.of(1), NodeId.of(2), NodeId.of(3))));
        volatile BiFunction<Integer, NodeId, AdminService.AdminResult> addResult =
                (g, t) -> new AdminService.AdminResult.Success("proposed");
        volatile boolean throwStatusTimeout;
        volatile boolean throwAddTimeout;

        FakeRaftClusterAdmin(Integer... groups) {
            this.knownGroups = Set.of(groups);
        }

        @Override public boolean hasGroup(int groupId) {
            return knownGroups.contains(groupId);
        }

        @Override public List<AdminApiHandler.GroupStatus> status() {
            if (throwStatusTimeout) {
                throw new AdminApiHandler.RaftAdminTimeout("raft-status voter read for group 0", 5_000L);
            }
            return statusResult;
        }

        @Override public AdminService.AdminResult addServer(int groupId, NodeId target) {
            addAttempts.incrementAndGet();
            addTargets.add(target);
            if (throwAddTimeout) {
                throw new AdminApiHandler.RaftAdminTimeout("add-server propose for group " + groupId, 5_000L);
            }
            return addResult.apply(groupId, target);
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

    private static AclService acl() {
        AclService acl = new AclService();
        acl.grant("", "root", EnumSet.allOf(AclService.Permission.class));
        acl.grant("_system/", "adminP", Set.of(AclService.Permission.ADMIN));
        acl.grant("", "writerP", Set.of(AclService.Permission.READ, AclService.Permission.WRITE));
        return acl;
    }

    private static AdminApiHandler handler(AclService acl, AuthInterceptor auth,
                                           AdminApiHandler.RaftClusterAdmin seam) {
        return new AdminApiHandler(new HealthService(), null, new VersionedConfigStore(),
                null, null, auth, acl, StrongReadPolicy.defaultPolicy(),
                (scope, key) -> NodeId.of(1), null, null,
                /* leadershipAdmin */ null, /* chain */ null, seam, /* keyringRotator */ null);
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

    private static AdminApiHandler.AdminResponse call(AdminApiHandler h, String method, String path,
                                                      String query, String token) throws Exception {
        return h.handle(req(method, path, query, token));
    }

    // ---- status: ADMIN gate ----

    @Test
    void statusUnauthenticatedIsRejected401() throws Exception {
        AdminApiHandler h = handler(acl(), auth(), new FakeRaftClusterAdmin(0));
        assertEquals(401, call(h, "GET", STATUS, null, null).status(),
                "an unauthenticated status read must be 401");
    }

    @Test
    void statusNonAdminIsForbidden403() throws Exception {
        AdminApiHandler h = handler(acl(), auth(), new FakeRaftClusterAdmin(0));
        assertEquals(403, call(h, "GET", STATUS, null, "writer").status(),
                "a WRITE-but-not-ADMIN principal must not read cluster status");
    }

    @Test
    void statusAuthDisabledIsRefused403() throws Exception {
        // Auth off + ACL off: cluster status is control-plane state, refused during an insecure bring-up.
        AdminApiHandler h = handler(null, null, new FakeRaftClusterAdmin(0));
        assertEquals(403, call(h, "GET", STATUS, null, null).status(),
                "auth-off: status (control-plane topology) must be refused, not open");
    }

    @Test
    void statusHappyPathReturnsExpectedFields() throws Exception {
        AdminApiHandler h = handler(acl(), auth(), new FakeRaftClusterAdmin(0));
        AdminApiHandler.AdminResponse resp = call(h, "GET", STATUS, null, "admin");
        assertEquals(200, resp.status(), "an ADMIN principal may read status");
        assertEquals("application/json", resp.headers().get("Content-Type"));
        String body = new String(resp.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("\"groupId\":0"), "status must carry the group id: " + body);
        assertTrue(body.contains("\"role\":\"LEADER\""), "status must carry the role: " + body);
        assertTrue(body.contains("\"leaderId\":1"), "status must carry the leader id: " + body);
        assertTrue(body.contains("\"currentTerm\":3"), "status must carry the term: " + body);
        assertTrue(body.contains("\"commitIndex\":10"), "status must carry the commit index: " + body);
        assertTrue(body.contains("\"lastApplied\":10"), "status must carry the applied index: " + body);
        assertTrue(body.contains("\"voters\":[1,2,3]"), "status must carry the voter set: " + body);
    }

    @Test
    void statusNullLeaderSerializesAsJsonNull() throws Exception {
        FakeRaftClusterAdmin seam = new FakeRaftClusterAdmin(0);
        seam.statusResult = List.of(new AdminApiHandler.GroupStatus(0, "FOLLOWER", null, 5, 2, 2,
                Set.of(NodeId.of(1))));
        AdminApiHandler h = handler(acl(), auth(), seam);
        String body = new String(call(h, "GET", STATUS, null, "admin").body(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("\"leaderId\":null"), "an unknown leader must serialize as null: " + body);
    }

    @Test
    void statusOwnerTimeoutIs503() throws Exception {
        FakeRaftClusterAdmin seam = new FakeRaftClusterAdmin(0);
        seam.throwStatusTimeout = true;
        AdminApiHandler h = handler(acl(), auth(), seam);
        assertEquals(503, call(h, "GET", STATUS, null, "admin").status(),
                "a wedged owner during a status voter read must map to 503");
    }

    @Test
    void statusWrongMethodIs405() throws Exception {
        AdminApiHandler h = handler(acl(), auth(), new FakeRaftClusterAdmin(0));
        assertEquals(405, call(h, "POST", STATUS, null, "admin").status(),
                "a non-GET on status must be 405");
    }

    // ---- add-server: ADMIN gate + mapping ----

    @Test
    void addServerUnauthenticatedIsRejected401AndNotAttempted() throws Exception {
        FakeRaftClusterAdmin seam = new FakeRaftClusterAdmin(0);
        AdminApiHandler h = handler(acl(), auth(), seam);
        assertEquals(401, call(h, "POST", ADD, "group=0&node=2", null).status(),
                "an unauthenticated add-server must be 401");
        assertEquals(0, seam.addAttempts.get(), "an unauthenticated add-server must never reach the mechanism");
    }

    @Test
    void addServerNonAdminIsForbidden403AndNotAttempted() throws Exception {
        FakeRaftClusterAdmin seam = new FakeRaftClusterAdmin(0);
        AdminApiHandler h = handler(acl(), auth(), seam);
        assertEquals(403, call(h, "POST", ADD, "group=0&node=2", "writer").status(),
                "a WRITE-but-not-ADMIN principal must be forbidden from add-server");
        assertEquals(0, seam.addAttempts.get(), "a forbidden add-server must never reach the mechanism");
    }

    @Test
    void addServerAuthDisabledIsRefused403() throws Exception {
        FakeRaftClusterAdmin seam = new FakeRaftClusterAdmin(0);
        AdminApiHandler h = handler(null, null, seam);
        assertEquals(403, call(h, "POST", ADD, "group=0&node=2", null).status(),
                "auth-off: a membership change (privileged control op) must be refused");
        assertEquals(0, seam.addAttempts.get(), "no add-server is attempted while auth is disabled");
    }

    @Test
    void addServerFailsClosedWhenAuthOnButNoAcl403() throws Exception {
        FakeRaftClusterAdmin seam = new FakeRaftClusterAdmin(0);
        AdminApiHandler h = handler(null, auth(), seam);
        assertEquals(403, call(h, "POST", ADD, "group=0&node=2", "admin").status(),
                "auth-on but no ACL: add-server must fail closed (ADMIN unevaluable)");
        assertEquals(0, seam.addAttempts.get(), "a fail-closed add-server must never reach the mechanism");
    }

    @Test
    void addServerHappyPathIsInitiated200() throws Exception {
        FakeRaftClusterAdmin seam = new FakeRaftClusterAdmin(0);
        AdminApiHandler h = handler(acl(), auth(), seam);
        assertEquals(200, call(h, "POST", ADD, "group=0&node=2", "admin").status(),
                "an ADMIN grant must allow the add-server");
        assertEquals(200, call(h, "POST", ADD, "group=0&node=2", "root").status(),
                "root (allOf break-glass) must always be allowed");
        assertEquals(2, seam.addAttempts.get(), "both authorized add-servers reached the mechanism");
        assertEquals(List.of(NodeId.of(2), NodeId.of(2)), seam.addTargets,
                "the parsed ?node must be handed to the mechanism verbatim");
    }

    @Test
    void addServerDefaultsGroupToZeroWhenAbsent() throws Exception {
        FakeRaftClusterAdmin seam = new FakeRaftClusterAdmin(0);
        AdminApiHandler h = handler(acl(), auth(), seam);
        assertEquals(200, call(h, "POST", ADD, "node=2", "admin").status(),
                "an absent ?group must default to group 0");
        assertEquals(1, seam.addAttempts.get(), "the default-group add-server reached the mechanism");
    }

    @Test
    void addServerNotLeaderIs503WithHint() throws Exception {
        FakeRaftClusterAdmin seam = new FakeRaftClusterAdmin(0);
        seam.addResult = (g, t) -> new AdminService.AdminResult.NotLeader(NodeId.of(7));
        AdminApiHandler h = handler(acl(), auth(), seam);
        AdminApiHandler.AdminResponse resp = call(h, "POST", ADD, "group=0&node=2", "root");
        assertEquals(503, resp.status(), "add-server on a non-leader must be 503");
        assertEquals("7", resp.headers().get("X-Leader-Hint"),
                "the 503 must carry the leader hint so the operator retries the right node");
    }

    @Test
    void addServerPreconditionFailureIs409() throws Exception {
        FakeRaftClusterAdmin seam = new FakeRaftClusterAdmin(0);
        seam.addResult = (g, t) -> new AdminService.AdminResult.Failure("node 2 is already a voter of group 0");
        AdminApiHandler h = handler(acl(), auth(), seam);
        AdminApiHandler.AdminResponse resp = call(h, "POST", ADD, "group=0&node=2", "root");
        assertEquals(409, resp.status(), "an already-a-voter / pending-change precondition must be 409");
        assertTrue(new String(resp.body(), java.nio.charset.StandardCharsets.UTF_8).contains("already a voter"),
                "the 409 must carry the clear precondition reason");
    }

    @Test
    void addServerOwnerTimeoutIs503() throws Exception {
        FakeRaftClusterAdmin seam = new FakeRaftClusterAdmin(0);
        seam.throwAddTimeout = true;
        AdminApiHandler h = handler(acl(), auth(), seam);
        assertEquals(503, call(h, "POST", ADD, "group=0&node=2", "root").status(),
                "a bounded-wait timeout on the owner thread must map to 503");
    }

    // ---- add-server: input validation ----

    @Test
    void addServerMissingOrMalformedNodeIs400BeforeGate() throws Exception {
        FakeRaftClusterAdmin seam = new FakeRaftClusterAdmin(0);
        AdminApiHandler h = handler(acl(), auth(), seam);
        assertEquals(400, call(h, "POST", ADD, "group=0", "root").status(), "a missing ?node must be 400");
        assertEquals(400, call(h, "POST", ADD, "group=0&node=", "root").status(), "a blank ?node must be 400");
        assertEquals(400, call(h, "POST", ADD, "group=0&node=nope", "root").status(),
                "a non-integer ?node must be 400");
        assertEquals(400, call(h, "POST", ADD, "group=xyz&node=2", "root").status(),
                "a non-integer ?group must be 400");
        assertEquals(0, seam.addAttempts.get(), "a malformed request never reaches the mechanism");
    }

    @Test
    void addServerUnknownGroupIs400AfterGateButNoProbing() throws Exception {
        FakeRaftClusterAdmin seam = new FakeRaftClusterAdmin(0); // only group 0 registered
        AdminApiHandler h = handler(acl(), auth(), seam);
        assertEquals(400, call(h, "POST", ADD, "group=99&node=2", "root").status(),
                "add-server for an unregistered group must be 400");
        assertEquals(403, call(h, "POST", ADD, "group=99&node=2", "writer").status(),
                "an unauthorized caller must not learn whether a group exists (403, not 400)");
        assertEquals(0, seam.addAttempts.get(), "an unknown-group add-server never reaches the mechanism");
    }

    @Test
    void unknownRaftSubResourceIs404AndNullSeamIsAbsent404() throws Exception {
        AdminApiHandler h = handler(acl(), auth(), new FakeRaftClusterAdmin(0));
        assertEquals(404, call(h, "GET", "/v1/admin/raft/frobnicate", null, "root").status(),
                "an unknown admin-raft sub-resource must be 404");

        // With no seam wired, the whole /v1/admin/raft/ namespace is absent (falls through to 404).
        AdminApiHandler noSeam = new AdminApiHandler(new HealthService(), null, new VersionedConfigStore(),
                null, null, auth(), acl(), StrongReadPolicy.defaultPolicy(),
                (BiFunction<io.configd.common.ConfigScope, String, NodeId>) (scope, key) -> NodeId.of(1),
                null, null);
        assertEquals(404, noSeam.handle(req("GET", STATUS, null, "root")).status(),
                "with no raft-cluster seam wired, status must fall through to 404");
        assertEquals(404, noSeam.handle(req("POST", ADD, "group=0&node=2", "root")).status(),
                "with no raft-cluster seam wired, add-server must fall through to 404");
    }
}

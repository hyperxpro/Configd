package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.AdminService;
import io.configd.api.AuditLog;
import io.configd.api.AuthInterceptor;
import io.configd.api.ConfigReadService;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.api.PolicyParseException;
import io.configd.api.PolicySerializer;
import io.configd.api.ReplayGuard;
import io.configd.common.ConfigScope;
import io.configd.common.NodeId;
import io.configd.common.auth.AuthenticatorChain;
import io.configd.common.auth.Credential;
import io.configd.observability.PrometheusExporter;
import io.configd.store.ReadResult;
import io.configd.store.VersionedConfigStore;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;


public final class AdminApiHandler {

    private final HealthService healthService;
    private final PrometheusExporter prometheusExporter;
    private final VersionedConfigStore configStore;
    private final ConfigWriteService writeService;     // nullable: read-only deployments
    private final ConfigReadService readService;       // nullable: stale-only deployments
    private final AuthInterceptor authInterceptor;     // nullable: auth disabled (legacy bearer path)
    // nullable: the SPI authenticator chain (none/basic/mtls, or a mixed chain). When present it
    // supersedes authInterceptor for credential resolution and adds the Unavailable -> 503 outcome;
    // when null, credential resolution falls back to the legacy authInterceptor path unchanged.
    private final AuthenticatorChain chain;
    private final AclService aclService;               // nullable: ACLs disabled
    private final StrongReadPolicy strongReadPolicy;   // non-null
    // KEYED + SCOPED leader hint - resolves the leader of the shard that OWNS
    // (scope, key), so a read 503's X-Leader-Hint points at the right shard's leader for the read's scope
    // (a keyless/scopeless hint would loop forever at N>1, mis-routing a REGIONAL/LOCAL retry to the
    // GLOBAL shard's leader). Non-null.
    private final BiFunction<ConfigScope, String, NodeId> leaderHintSupplier;
    private final AuditLog auditLog;                   // nullable: auditing disabled
    private final ReplayGuard replayGuard;             // nullable: replay protection off
    // nullable: when null the leadership-transfer route falls through to 404 (unrouted).
    private final LeadershipAdmin leadershipAdmin;
    // nullable: when null the /v1/admin/raft/ status + add-server routes fall through to 404 (unrouted).
    private final RaftClusterAdmin raftClusterAdmin;
    // nullable: when null the /v1/admin/keyring/rotate route falls through to 404 (unrouted, e.g. a
    // keyless / no-at-rest-keyring posture where there is nothing to rotate).
    private final KeyringRotationAdmin keyringRotator;

    public AdminApiHandler(HealthService healthService,
                           PrometheusExporter prometheusExporter,
                           VersionedConfigStore configStore,
                           ConfigWriteService writeService,
                           ConfigReadService readService,
                           AuthInterceptor authInterceptor,
                           AclService aclService,
                           StrongReadPolicy strongReadPolicy,
                           BiFunction<ConfigScope, String, NodeId> leaderHintSupplier,
                           AuditLog auditLog,
                           ReplayGuard replayGuard) {
        this(healthService, prometheusExporter, configStore, writeService, readService, authInterceptor,
                aclService, strongReadPolicy, leaderHintSupplier, auditLog, replayGuard, null);
    }

    public AdminApiHandler(HealthService healthService,
                           PrometheusExporter prometheusExporter,
                           VersionedConfigStore configStore,
                           ConfigWriteService writeService,
                           ConfigReadService readService,
                           AuthInterceptor authInterceptor,
                           AclService aclService,
                           StrongReadPolicy strongReadPolicy,
                           BiFunction<ConfigScope, String, NodeId> leaderHintSupplier,
                           AuditLog auditLog,
                           ReplayGuard replayGuard,
                           LeadershipAdmin leadershipAdmin) {
        this(healthService, prometheusExporter, configStore, writeService, readService, authInterceptor,
                aclService, strongReadPolicy, leaderHintSupplier, auditLog, replayGuard, leadershipAdmin, null);
    }

    public AdminApiHandler(HealthService healthService,
                           PrometheusExporter prometheusExporter,
                           VersionedConfigStore configStore,
                           ConfigWriteService writeService,
                           ConfigReadService readService,
                           AuthInterceptor authInterceptor,
                           AclService aclService,
                           StrongReadPolicy strongReadPolicy,
                           BiFunction<ConfigScope, String, NodeId> leaderHintSupplier,
                           AuditLog auditLog,
                           ReplayGuard replayGuard,
                           LeadershipAdmin leadershipAdmin,
                           AuthenticatorChain chain) {
        this(healthService, prometheusExporter, configStore, writeService, readService, authInterceptor,
                aclService, strongReadPolicy, leaderHintSupplier, auditLog, replayGuard, leadershipAdmin,
                chain, null, null);
    }

    public AdminApiHandler(HealthService healthService,
                           PrometheusExporter prometheusExporter,
                           VersionedConfigStore configStore,
                           ConfigWriteService writeService,
                           ConfigReadService readService,
                           AuthInterceptor authInterceptor,
                           AclService aclService,
                           StrongReadPolicy strongReadPolicy,
                           BiFunction<ConfigScope, String, NodeId> leaderHintSupplier,
                           AuditLog auditLog,
                           ReplayGuard replayGuard,
                           LeadershipAdmin leadershipAdmin,
                           AuthenticatorChain chain,
                           RaftClusterAdmin raftClusterAdmin,
                           KeyringRotationAdmin keyringRotator) {
        this.healthService = healthService;
        this.prometheusExporter = prometheusExporter;
        this.configStore = configStore;
        this.writeService = writeService;
        this.readService = readService;
        this.authInterceptor = authInterceptor;
        this.chain = chain;
        this.aclService = aclService;
        this.strongReadPolicy = Objects.requireNonNull(strongReadPolicy, "strongReadPolicy must not be null");
        this.leaderHintSupplier = Objects.requireNonNull(leaderHintSupplier, "leaderHintSupplier must not be null");
        this.auditLog = auditLog;
        this.replayGuard = replayGuard;
        this.leadershipAdmin = leadershipAdmin;
        this.raftClusterAdmin = raftClusterAdmin;
        this.keyringRotator = keyringRotator;
    }

    
    public interface AdminRequest {
        String method();

        URI uri();

        
        String header(String name);

        
        byte[] body() throws IOException;

        
        default java.util.List<java.security.cert.X509Certificate> peerCertificates() {
            return java.util.List.of();
        }
    }

    
    public record AdminResponse(int status, Map<String, String> headers, byte[] body) {
    }

    
    public interface LeadershipAdmin {
        
        boolean hasGroup(int groupId);

        
        AdminService.AdminResult transferLeadership(int groupId, NodeId target);
    }

    
    public static final class LeadershipTransferTimeout extends RuntimeException {
        public LeadershipTransferTimeout(int groupId, long awaitMillis) {
            super("leadership transfer for group " + groupId
                    + " was not confirmed within " + awaitMillis + "ms");
        }
    }

    
    public interface RaftClusterAdmin {
        
        boolean hasGroup(int groupId);

        
        List<GroupStatus> status();

        
        AdminService.AdminResult addServer(int groupId, NodeId target);
    }

    
    public record GroupStatus(int groupId, String role, NodeId leaderId, long currentTerm,
                              long commitIndex, long lastApplied, Set<NodeId> voters) {
        public GroupStatus {
            voters = voters == null ? Set.of() : Set.copyOf(voters);
        }
    }

    
    public static final class RaftAdminTimeout extends RuntimeException {
        public RaftAdminTimeout(String operation, long awaitMillis) {
            super(operation + " was not confirmed within " + awaitMillis + "ms");
        }
    }

    
    public interface KeyringRotationAdmin {
        
        int rotate();
    }

    
    public AdminResponse handle(AdminRequest req) throws IOException {
        String path = req.uri().getPath();
        if ("/health/live".equals(path)) {
            return health(req, healthService.liveness());
        }
        if ("/health/ready".equals(path)) {
            return health(req, healthService.readiness());
        }
        if ("/metrics".equals(path)) {
            return metrics(req);
        }
        if (path != null && path.startsWith("/v1/config/")) {
            return config(req, path);
        }
        // The leadership-transfer control endpoint is only routed when the seam is wired; a null seam
        // leaves the admin-groups namespace unrouted (falls through to 404 below).
        if (leadershipAdmin != null && path != null && path.startsWith(ADMIN_GROUPS_PREFIX)) {
            return transferLeadership(req, path);
        }
        // The Raft cluster admin endpoints (status + add-server) are only routed when the seam is wired.
        if (raftClusterAdmin != null && path != null && path.startsWith(ADMIN_RAFT_PREFIX)) {
            return raftAdmin(req, path);
        }
        // The keyring term-rotation trigger is only routed when a rotator is wired (the at-rest keyring
        // posture); a null rotator leaves it unrouted (falls through to 404).
        if (keyringRotator != null && KEYRING_ROTATE_PATH.equals(path)) {
            return keyringRotate(req);
        }
        return json(404, "Not Found");
    }

    private AdminResponse health(AdminRequest req, HealthService.HealthStatus status) {
        if (!"GET".equals(req.method())) {
            return json(405, "Method Not Allowed");
        }
        int code = status.healthy() ? 200 : 503;
        return json(code, formatHealthStatus(status));
    }

    private AdminResponse metrics(AdminRequest req) {
        if (!"GET".equals(req.method())) {
            return json(405, "Method Not Allowed");
        }
        // Enforce auth on /metrics when auth is configured. 401 (not 403): this is authentication, not
        // authorization (there is no ACL for metrics scraping). Never echo the credential.
        if (chain != null) {
            Credential cred = credentialFrom(req);
            io.configd.common.auth.AuthResult r = resolveAndWipe(cred);
            if (r instanceof io.configd.common.auth.AuthResult.Unavailable) {
                return json(503, "authentication temporarily unavailable");
            }
            if (r instanceof io.configd.common.auth.AuthResult.Denied denied) {
                Map<String, String> h = jsonHeaders();
                h.put("WWW-Authenticate", "Bearer");
                return new AdminResponse(401, h, bytes("Unauthorized: " + denied.detail()));
            }
            // Authenticated: /metrics has no ACL, so any authenticated principal may scrape.
        } else if (authInterceptor != null) {
            AuthInterceptor.AuthResult authResult = authInterceptor.authenticate(bearerToken(req));
            if (authResult instanceof AuthInterceptor.AuthResult.Denied denied) {
                Map<String, String> h = jsonHeaders();
                h.put("WWW-Authenticate", "Bearer");
                return new AdminResponse(401, h, bytes("Unauthorized: " + denied.reason()));
            }
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        return new AdminResponse(200, h, bytes(prometheusExporter.export()));
    }

    private AdminResponse config(AdminRequest req, String path) throws IOException {
        String prefix = "/v1/config/";
        if (path.length() <= prefix.length()) {
            return json(400, "Missing config key in path");
        }
        String key = path.substring(prefix.length());
        return switch (req.method()) {
            case "GET" -> handleGet(req, key);
            case "PUT" -> handlePut(req, key);
            case "DELETE" -> handleDelete(req, key);
            default -> json(405, "Method Not Allowed");
        };
    }

    private AdminResponse handleGet(AdminRequest req, String key) {
        AuthCheck authCheck = checkAuth(req, key, AclService.Permission.READ);
        if (authCheck.decision() != AuthDecision.OK) {
            // A read AUTH FAILURE (401/403) is security-relevant and audited; a successful read is
            // not a mutating attempt and is not audited (auditing every read is a DoS concern).
            audit(authCheck.principal(), "GET", key,
                    "denied: " + authCheck.decision() + " (" + authCheck.reason() + ")");
            return authDenial(authCheck);
        }

        // A read auth FAILURE is audited above; an invalid-key/scope GET is not a mutating attempt and
        // is not audited (auditing every bad read is a DoS concern, consistent with successful reads).
        String keyError = keyValidationReason(key);
        if (keyError != null) {
            return json(400, keyError);
        }
        ScopeResult scopeResult = parseScope(req);
        if (scopeResult.error() != null) {
            return json(400, scopeResult.error());
        }
        ConfigScope scope = scopeResult.scope();

        // GLOBAL/security ("strong-read") keys MUST be served via the
        // fail-closed linearizable path. Requested consistency is IGNORED for these keys, and if the
        // linearizable read cannot be confirmed we DENY (503), never serving a stale value.
        boolean strongReadKey = strongReadPolicy.isStrongReadKey(key);

        String query = req.uri().getQuery();
        boolean linearizableRequested = query != null && query.contains("consistency=linearizable");
        boolean linearizable = strongReadKey || linearizableRequested;

        ReadResult result;
        if (strongReadKey) {
            if (readService == null) {
                // No linearizable path wired (stale-only deployment): fail closed.
                return failClosed(scope, key, "no linearizable read path is configured on this node");
            }
            result = readService.linearizableRead(scope, key);
            if (result == null) {
                // Leadership / ReadIndex confirmation failed: fail CLOSED.
                return failClosed(scope, key,
                        "linearizable read could not be confirmed (not leader / ReadIndex unconfirmed)");
            }
        } else if (linearizableRequested && readService != null) {
            result = readService.linearizableRead(scope, key);
            if (result == null) {
                // Ordinary key, explicit linearizable request that can't be served: reported as Not
                // Leader. NOT a strong-read fail-close (a stale read of this key is contract-permitted).
                Map<String, String> h = jsonHeaders();
                NodeId hint = leaderHintSupplier.apply(scope, key);
                if (hint != null) {
                    h.put("X-Leader-Hint", String.valueOf(hint.id()));
                }
                return new AdminResponse(503, h, bytes("Not Leader - cannot serve linearizable read"));
            }
        } else {
            // Route the STALE read through the sharded reader so a key on shard k is read from ITS
            // store, not the captured group-0 store (read-your-writes at N>1). ConfigReadService.staleRead
            // delegates to the sharded ConfigReader; at N=1 it resolves group 0. The raw group-0
            // configStore is the fallback only for a stale-only deployment with no read service wired.
            result = (readService != null) ? readService.staleRead(scope, key) : configStore.get(key);
        }

        if (!result.found()) {
            return json(404, "Not Found");
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/octet-stream");
        h.put("X-Config-Version", String.valueOf(result.version()));
        h.put("X-Consistency", linearizable ? "linearizable" : "stale");
        if (strongReadKey) {
            h.put("X-Strong-Read", "true");
        }
        return new AdminResponse(200, h, result.value());
    }

    
    private AdminResponse failClosed(ConfigScope scope, String key, String reason) {
        Map<String, String> h = jsonHeaders();
        h.put("X-Fail-Closed", "strong-read");
        NodeId hint = leaderHintSupplier.apply(scope, key);
        if (hint != null) {
            h.put("X-Leader-Hint", String.valueOf(hint.id()));
        }
        String body = "Fail-closed: strong-read (GLOBAL/security) key '" + key
                + "' must be served linearizably (ADR-0030 INV-1) but " + reason
                + "; refusing to serve a stale value"
                + (hint != null ? " (leader=" + hint + ")" : "");
        return new AdminResponse(503, h, bytes(body));
    }

    private AdminResponse handlePut(AdminRequest req, String key) throws IOException {
        AuthCheck authCheck = checkAuth(req, key, AclService.Permission.WRITE);
        if (authCheck.decision() != AuthDecision.OK) {
            audit(authCheck.principal(), "PUT", key,
                    "denied: " + authCheck.decision() + " (" + authCheck.reason() + ")");
            return authDenial(authCheck);
        }

        String keyError = keyValidationReason(key);
        if (keyError != null) {
            audit(authCheck.principal(), "PUT", key, "rejected: " + keyError);
            return json(400, keyError);
        }
        ScopeResult scopeResult = parseScope(req);
        if (scopeResult.error() != null) {
            audit(authCheck.principal(), "PUT", key, "rejected: " + scopeResult.error());
            return json(400, scopeResult.error());
        }
        ConfigScope scope = scopeResult.scope();

        AdminResponse replay = replayRejected(req, authCheck.principal(), "PUT", key);
        if (replay != null) {
            return replay;
        }

        byte[] body = req.body();
        if (body.length == 0) {
            audit(authCheck.principal(), "PUT", key, "rejected: empty body");
            return json(400, "Request body must not be empty");
        }

        // Write-time validation of reserved `_acl/` policy. Runs the IDENTICAL
        // PolicySerializer.parse + reserved-name check as the reload path (AclConfigPolicyLoader), on the
        // singleton {key: value}, so a key that passes the write can never freeze a later whole-subtree
        // reload (never a second hand-rolled validator). Malformed shape / role-line / binding grammar, or
        // a reserved name (`_acl/roles/admin`, `_acl/bindings/root`) -> 400 PRE-COMMIT (store unchanged). A
        // well-formed-but-incomplete policy (a binding to a not-yet-defined role) is intentionally NOT an
        // error. Only `_acl/` carries a policy format; `_system/` is gated (ADMIN) but unparsed.
        if (key.startsWith(PolicySerializer.ACL_PREFIX)) {
            try {
                AclConfigPolicyLoader.validateAclWrite(key, body);
            } catch (PolicyParseException e) {
                audit(authCheck.principal(), "PUT", key, "rejected: invalid ACL policy: " + e.getMessage());
                return json(400, "Invalid ACL policy: " + e.getMessage());
            }
        }

        ConfigWriteService.WriteResult result =
                writeService.put(key, body, scope, authCheck.principal()); // per-principal limit
        audit(authCheck.principal(), "PUT", key, auditOutcome(result));
        return writeResult(result);
    }

    private AdminResponse handleDelete(AdminRequest req, String key) throws IOException {
        AuthCheck authCheck = checkAuth(req, key, AclService.Permission.WRITE);
        if (authCheck.decision() != AuthDecision.OK) {
            audit(authCheck.principal(), "DELETE", key,
                    "denied: " + authCheck.decision() + " (" + authCheck.reason() + ")");
            return authDenial(authCheck);
        }

        String keyError = keyValidationReason(key);
        if (keyError != null) {
            audit(authCheck.principal(), "DELETE", key, "rejected: " + keyError);
            return json(400, keyError);
        }
        ScopeResult scopeResult = parseScope(req);
        if (scopeResult.error() != null) {
            audit(authCheck.principal(), "DELETE", key, "rejected: " + scopeResult.error());
            return json(400, scopeResult.error());
        }
        ConfigScope scope = scopeResult.scope();

        AdminResponse replay = replayRejected(req, authCheck.principal(), "DELETE", key);
        if (replay != null) {
            return replay;
        }

        ConfigWriteService.WriteResult result =
                writeService.delete(key, scope, authCheck.principal()); // per-principal limit
        audit(authCheck.principal(), "DELETE", key, auditOutcome(result));
        return writeResult(result);
    }

    
    private AdminResponse writeResult(ConfigWriteService.WriteResult result) {
        return switch (result) {
            case ConfigWriteService.WriteResult.Committed c -> json(200, "Committed: seq=" + c.seq());
            case ConfigWriteService.WriteResult.NotLeader nl -> {
                Map<String, String> h = jsonHeaders();
                if (nl.leaderId() != null) {
                    h.put("X-Leader-Hint", String.valueOf(nl.leaderId().id()));
                }
                yield new AdminResponse(503, h, bytes("Not Leader"
                        + (nl.leaderId() != null ? " (leader=" + nl.leaderId() + ")" : "")));
            }
            case ConfigWriteService.WriteResult.Lost lost -> {
                Map<String, String> h = jsonHeaders();
                if (lost.leaderHint() != null) {
                    h.put("X-Leader-Hint", String.valueOf(lost.leaderHint().id()));
                }
                yield new AdminResponse(503, h, bytes("Lost leadership before commit"
                        + (lost.leaderHint() != null ? " (leader=" + lost.leaderHint() + ")" : "")));
            }
            case ConfigWriteService.WriteResult.Indeterminate ignored -> json(504,
                    "Commit unconfirmed within deadline; outcome unknown; safe to retry or re-read");
            case ConfigWriteService.WriteResult.ValidationFailed vf -> json(400, "Validation failed: " + vf.reason());
            case ConfigWriteService.WriteResult.Overloaded ignored -> {
                // The write-overload contract: a bounded-queue 429 with a Retry-After backoff signal.
                // The reject is counted by the write_rejected_overloaded metric at the raftProposer
                // site, which an operational alert watches - don't rename it without updating that alert.
                Map<String, String> h = jsonHeaders();
                h.put("Retry-After", "1");
                yield new AdminResponse(429, h, bytes("Overloaded"));
            }
        };
    }

    private static final String ADMIN_GROUPS_PREFIX = "/v1/admin/groups/";
    private static final String TRANSFER_LEADERSHIP_SUFFIX = "/transfer-leadership";
    private static final String ADMIN_RAFT_PREFIX = "/v1/admin/raft/";
    private static final String RAFT_STATUS_PATH = "/v1/admin/raft/status";
    private static final String RAFT_ADD_SERVER_PATH = "/v1/admin/raft/add-server";
    private static final String KEYRING_ROTATE_PATH = "/v1/admin/keyring/rotate";

    
    private AdminResponse transferLeadership(AdminRequest req, String path) {
        if (!path.endsWith(TRANSFER_LEADERSHIP_SUFFIX)) {
            return json(404, "Not Found"); // an unknown sub-resource under the admin-groups namespace
        }
        if (!"POST".equals(req.method())) {
            return json(405, "Method Not Allowed");
        }
        // The group-id segment lies between the prefix and the suffix. Guard the bounds: when the prefix
        // and suffix overlap or abut ({@code segEnd <= prefix length}) there is no group id at all
        // (e.g. `/v1/admin/groups/transfer-leadership`), which is a malformed request, not a substring fault.
        int segEnd = path.length() - TRANSFER_LEADERSHIP_SUFFIX.length();
        if (segEnd <= ADMIN_GROUPS_PREFIX.length()) {
            return json(400, "Invalid group id in path");
        }
        String groupIdSegment = path.substring(ADMIN_GROUPS_PREFIX.length(), segEnd);
        // A segment carrying a nested path ('/') is not a single group id.
        if (groupIdSegment.indexOf('/') >= 0) {
            return json(400, "Invalid group id in path");
        }
        final int groupId;
        try {
            groupId = Integer.parseInt(groupIdSegment);
        } catch (NumberFormatException e) {
            return json(400, "Invalid group id '" + groupIdSegment + "' (expected an integer)");
        }

        // The ADMIN gate. The transfer is authorized against the group's reserved `_system/` control
        // resource (ADMIN reaches the `_system/` subtree by design), fail-closed and STRICTER than the
        // config gate: refused outright when auth is off (a control op must never be issuable during an
        // insecure bring-up).
        String resourceKey = SYSTEM_PREFIX + "raft/groups/" + groupId + "/leadership";
        AuthCheck authCheck = checkAdmin(req, resourceKey);
        if (authCheck.decision() != AuthDecision.OK) {
            audit(authCheck.principal(), "TRANSFER_LEADERSHIP", resourceKey,
                    "denied: " + authCheck.decision() + " (" + authCheck.reason() + ")");
            return authDenial(authCheck);
        }

        String targetRaw = queryParam(req.uri().getQuery(), "target");
        if (targetRaw == null || targetRaw.isBlank()) {
            audit(authCheck.principal(), "TRANSFER_LEADERSHIP", resourceKey, "rejected: missing target");
            return json(400, "Missing required query parameter 'target' (the node id to transfer leadership to)");
        }
        final NodeId target;
        try {
            target = NodeId.of(Integer.parseInt(targetRaw.trim()));
        } catch (NumberFormatException e) {
            audit(authCheck.principal(), "TRANSFER_LEADERSHIP", resourceKey,
                    "rejected: invalid target '" + targetRaw + "'");
            return json(400, "Invalid target node id '" + targetRaw + "' (expected an integer)");
        }

        // Group existence is decided AFTER the ADMIN gate (an unauthorized caller must not learn which
        // groups exist).
        if (!leadershipAdmin.hasGroup(groupId)) {
            audit(authCheck.principal(), "TRANSFER_LEADERSHIP", resourceKey, "rejected: unknown group " + groupId);
            return json(400, "Unknown group " + groupId);
        }

        // Replay protection, exactly as the config mutations apply it: a leadership transfer is a mutating
        // privileged control op, so a captured (valid-bearer) request must not be replayable to force
        // leadership churn. 401 on stale/malformed replay headers, 409 on a replayed nonce; the transfer is
        // NOT attempted when rejected. No-op when no replay guard is configured (opt-in, default off).
        AdminResponse replay = replayRejected(req, authCheck.principal(), "TRANSFER_LEADERSHIP", resourceKey);
        if (replay != null) {
            return replay;
        }

        try {
            AdminService.AdminResult result = leadershipAdmin.transferLeadership(groupId, target);
            return transferResult(authCheck.principal(), resourceKey, groupId, target, result);
        } catch (LeadershipTransferTimeout timeout) {
            // The owner thread did not confirm within its bound: unknown outcome, safe to retry -> 503,
            // distinct from a precondition failure (409).
            audit(authCheck.principal(), "TRANSFER_LEADERSHIP", resourceKey, "timeout");
            return json(503, "Leadership transfer to " + target + " for group " + groupId
                    + " could not be confirmed within the deadline; unknown outcome, safe to retry");
        }
    }

    
    private AdminResponse transferResult(String actor, String resourceKey, int groupId, NodeId target,
                                         AdminService.AdminResult result) {
        return switch (result) {
            case AdminService.AdminResult.Success ignored -> {
                audit(actor, "TRANSFER_LEADERSHIP", resourceKey, "initiated: target=" + target);
                yield json(200, "Leadership transfer to " + target + " for group " + groupId
                        + " initiated (asynchronous; confirm via the new leader)");
            }
            case AdminService.AdminResult.NotLeader nl -> {
                audit(actor, "TRANSFER_LEADERSHIP", resourceKey, "not-leader");
                Map<String, String> h = jsonHeaders();
                if (nl.leaderId() != null) {
                    h.put("X-Leader-Hint", String.valueOf(nl.leaderId().id()));
                }
                yield new AdminResponse(503, h, bytes("Not Leader: this node does not lead group " + groupId
                        + (nl.leaderId() != null ? " (leader=" + nl.leaderId() + ")" : "")
                        + "; retry against the group leader"));
            }
            case AdminService.AdminResult.Failure f -> {
                audit(actor, "TRANSFER_LEADERSHIP", resourceKey, "rejected: " + f.reason());
                yield json(409, "Leadership transfer rejected: " + f.reason());
            }
        };
    }

    
    private AdminResponse raftAdmin(AdminRequest req, String path) {
        if (RAFT_STATUS_PATH.equals(path)) {
            return raftStatus(req);
        }
        if (RAFT_ADD_SERVER_PATH.equals(path)) {
            return raftAddServer(req);
        }
        return json(404, "Not Found"); // an unknown sub-resource under the admin-raft namespace
    }

    
    private AdminResponse raftStatus(AdminRequest req) {
        if (!"GET".equals(req.method())) {
            return json(405, "Method Not Allowed");
        }
        String resourceKey = SYSTEM_PREFIX + "raft/status";
        AuthCheck authCheck = checkAdmin(req, resourceKey);
        if (authCheck.decision() != AuthDecision.OK) {
            audit(authCheck.principal(), "RAFT_STATUS", resourceKey,
                    "denied: " + authCheck.decision() + " (" + authCheck.reason() + ")");
            return authDenial(authCheck);
        }
        try {
            List<GroupStatus> groups = raftClusterAdmin.status();
            return new AdminResponse(200, jsonHeaders(), bytes(formatRaftStatus(groups)));
        } catch (RaftAdminTimeout timeout) {
            audit(authCheck.principal(), "RAFT_STATUS", resourceKey, "timeout");
            return json(503, "Raft status could not be resolved within the deadline (a group owner is"
                    + " wedged or overloaded); safe to retry");
        }
    }

    
    private AdminResponse raftAddServer(AdminRequest req) {
        if (!"POST".equals(req.method())) {
            return json(405, "Method Not Allowed");
        }
        String query = req.uri().getQuery();
        String groupRaw = queryParam(query, "group");
        int groupId = 0; // absent group defaults to group 0 (the sole group at N=1)
        if (groupRaw != null && !groupRaw.isBlank()) {
            try {
                groupId = Integer.parseInt(groupRaw.trim());
            } catch (NumberFormatException e) {
                return json(400, "Invalid group '" + groupRaw + "' (expected an integer)");
            }
        }
        String nodeRaw = queryParam(query, "node");
        if (nodeRaw == null || nodeRaw.isBlank()) {
            return json(400, "Missing required query parameter 'node' (the node id to add as a voter)");
        }
        final NodeId target;
        try {
            target = NodeId.of(Integer.parseInt(nodeRaw.trim()));
        } catch (NumberFormatException e) {
            return json(400, "Invalid node id '" + nodeRaw + "' (expected an integer)");
        }

        // The ADMIN gate: the same strict fail-closed posture as the leadership transfer (refused when auth
        // is off), authorized against the group's reserved control resource.
        String resourceKey = SYSTEM_PREFIX + "raft/groups/" + groupId + "/membership";
        AuthCheck authCheck = checkAdmin(req, resourceKey);
        if (authCheck.decision() != AuthDecision.OK) {
            audit(authCheck.principal(), "RAFT_ADD_SERVER", resourceKey,
                    "denied: " + authCheck.decision() + " (" + authCheck.reason() + ")");
            return authDenial(authCheck);
        }

        // Group existence is decided AFTER the ADMIN gate (an unauthorized caller must not learn which
        // groups exist).
        if (!raftClusterAdmin.hasGroup(groupId)) {
            audit(authCheck.principal(), "RAFT_ADD_SERVER", resourceKey, "rejected: unknown group " + groupId);
            return json(400, "Unknown group " + groupId);
        }

        // Replay protection: adding a voter is a mutating privileged control op, so a captured
        // (valid-bearer) request must not be replayable to force repeated reconfiguration.
        AdminResponse replay = replayRejected(req, authCheck.principal(), "RAFT_ADD_SERVER", resourceKey);
        if (replay != null) {
            return replay;
        }

        try {
            AdminService.AdminResult result = raftClusterAdmin.addServer(groupId, target);
            return addServerResult(authCheck.principal(), resourceKey, groupId, target, result);
        } catch (RaftAdminTimeout timeout) {
            audit(authCheck.principal(), "RAFT_ADD_SERVER", resourceKey, "timeout");
            return json(503, "Add-server for node " + target + " to group " + groupId
                    + " could not be confirmed within the deadline; unknown outcome, safe to retry");
        }
    }

    
    private AdminResponse addServerResult(String actor, String resourceKey, int groupId, NodeId target,
                                          AdminService.AdminResult result) {
        return switch (result) {
            case AdminService.AdminResult.Success ignored -> {
                audit(actor, "RAFT_ADD_SERVER", resourceKey, "initiated: node=" + target);
                yield json(200, "Add-server for node " + target + " to group " + groupId
                        + " initiated (asynchronous; the membership change is proposed - confirm via"
                        + " GET /v1/admin/raft/status)");
            }
            case AdminService.AdminResult.NotLeader nl -> {
                audit(actor, "RAFT_ADD_SERVER", resourceKey, "not-leader");
                Map<String, String> h = jsonHeaders();
                if (nl.leaderId() != null) {
                    h.put("X-Leader-Hint", String.valueOf(nl.leaderId().id()));
                }
                yield new AdminResponse(503, h, bytes("Not Leader: this node does not lead group " + groupId
                        + (nl.leaderId() != null ? " (leader=" + nl.leaderId() + ")" : "")
                        + "; retry against the group leader"));
            }
            case AdminService.AdminResult.Failure f -> {
                audit(actor, "RAFT_ADD_SERVER", resourceKey, "rejected: " + f.reason());
                yield json(409, "Add-server rejected: " + f.reason());
            }
        };
    }

    
    private AdminResponse keyringRotate(AdminRequest req) {
        if (!"POST".equals(req.method())) {
            return json(405, "Method Not Allowed");
        }
        String resourceKey = SYSTEM_PREFIX + "keyring/rotate";
        AuthCheck authCheck = checkAdmin(req, resourceKey);
        if (authCheck.decision() != AuthDecision.OK) {
            audit(authCheck.principal(), "KEYRING_ROTATE", resourceKey,
                    "denied: " + authCheck.decision() + " (" + authCheck.reason() + ")");
            return authDenial(authCheck);
        }
        AdminResponse replay = replayRejected(req, authCheck.principal(), "KEYRING_ROTATE", resourceKey);
        if (replay != null) {
            return replay;
        }
        try {
            int newActiveTerm = keyringRotator.rotate();
            audit(authCheck.principal(), "KEYRING_ROTATE", resourceKey, "rotated: activeTerm=" + newActiveTerm);
            return json(200, "Keyring term rotated: new activeTerm=" + newActiveTerm
                    + " (durable, non-destructive; new writes adopt it after the next restart - a rolling"
                    + " restart across the cluster - and old-term data still decrypts)");
        } catch (RuntimeException e) {
            // Fail closed and loud: a rotation IO/crypto failure must never read as a silent success. The
            // rotation is crash-atomic, so the previous keyring is intact and a retry is safe.
            audit(authCheck.principal(), "KEYRING_ROTATE", resourceKey, "failed: " + e.getMessage());
            return json(503, "Keyring rotation failed: " + e.getMessage()
                    + "; the previous keyring is intact (crash-atomic) - safe to retry");
        }
    }

    
    private static String formatRaftStatus(List<GroupStatus> groups) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"groups\":[");
        boolean firstGroup = true;
        for (GroupStatus g : groups) {
            if (!firstGroup) sb.append(',');
            firstGroup = false;
            sb.append("{\"groupId\":").append(g.groupId())
                    .append(",\"role\":\"").append(escapeJson(g.role())).append('"')
                    .append(",\"leaderId\":").append(g.leaderId() == null ? "null" : g.leaderId().id())
                    .append(",\"currentTerm\":").append(g.currentTerm())
                    .append(",\"commitIndex\":").append(g.commitIndex())
                    .append(",\"lastApplied\":").append(g.lastApplied())
                    .append(",\"voters\":[");
            boolean firstVoter = true;
            // Ascending node-id order so the voter set (and the whole status payload) is stable across
            // scrapes regardless of the set's iteration order.
            for (NodeId v : new java.util.TreeSet<>(g.voters())) {
                if (!firstVoter) sb.append(',');
                firstVoter = false;
                sb.append(v.id());
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    
    private AuthCheck checkAdmin(AdminRequest req, String resourceKey) {
        if (chain != null) {
            return checkAdminViaChain(req, resourceKey);
        }
        if (authInterceptor == null) {
            return AuthCheck.forbidden("-",
                    "Access denied: this control operation requires authentication, which is disabled");
        }
        AuthInterceptor.AuthResult authResult = authInterceptor.authenticate(bearerToken(req));
        if (authResult instanceof AuthInterceptor.AuthResult.Denied denied) {
            return AuthCheck.unauthenticated("authentication required: " + denied.reason());
        }
        if (authResult instanceof AuthInterceptor.AuthResult.Authenticated authed) {
            if (aclService == null) {
                return AuthCheck.forbidden(authed.principal(),
                        "Access denied: this control operation requires ADMIN but no ACL is configured");
            }
            if (!aclService.isAllowed(authed.principal(), authed.roles(), resourceKey,
                    AclService.Permission.ADMIN)) {
                return AuthCheck.forbidden(authed.principal(),
                        "Access denied: ADMIN required for this control operation");
            }
            return AuthCheck.ok(authed.principal());
        }
        // Defensive fail-closed for any future AuthResult variant (the sealed type is {Authenticated, Denied}).
        return AuthCheck.forbidden("-", "Access denied: this control operation requires ADMIN");
    }

    private enum AuthDecision { OK, UNAUTHENTICATED, FORBIDDEN, UNAVAILABLE }

    private record AuthCheck(AuthDecision decision, String principal, String reason) {
        static AuthCheck ok(String principal) {
            return new AuthCheck(AuthDecision.OK, principal, null);
        }
        static AuthCheck unauthenticated(String reason) {
            return new AuthCheck(AuthDecision.UNAUTHENTICATED, null, reason);
        }
        static AuthCheck forbidden(String principal, String reason) {
            return new AuthCheck(AuthDecision.FORBIDDEN, principal, reason);
        }
        static AuthCheck unavailable(String reason) {
            return new AuthCheck(AuthDecision.UNAVAILABLE, null, reason);
        }
    }

    
    private AdminResponse authDenial(AuthCheck check) {
        return switch (check.decision()) {
            case UNAUTHENTICATED -> {
                Map<String, String> h = jsonHeaders();
                h.put("WWW-Authenticate", "Bearer");
                yield new AdminResponse(401, h, bytes("Unauthorized: " + check.reason()));
            }
            case FORBIDDEN -> json(403, check.reason());
            case UNAVAILABLE -> json(503, check.reason());
            case OK -> throw new AssertionError("authDenial called for an OK decision");
        };
    }

    
    private AuthCheck checkAuth(AdminRequest req, String key, AclService.Permission permission) {
        boolean reserved = isReserved(key);

        if (chain != null) {
            return checkAuthViaChain(req, key, permission, reserved);
        }

        if (authInterceptor == null) {
            // Auth disabled: the gate is open EXCEPT a reserved-prefix WRITE, refused to close the
            // bring-up poison footgun (an _acl/ key written here would be seeded into policy on the first
            // secured boot). Reads stay open (consistent with auth-off being fully open otherwise).
            if (reserved && permission == AclService.Permission.WRITE) {
                return AuthCheck.forbidden("-",
                        "Access denied: reserved key '" + key + "' cannot be written while authentication is disabled");
            }
            return AuthCheck.ok("-"); // auth not configured: no resolved principal
        }

        AuthInterceptor.AuthResult authResult = authInterceptor.authenticate(bearerToken(req));
        if (authResult instanceof AuthInterceptor.AuthResult.Denied denied) {
            // No/blank/malformed/invalid credential: AUTHENTICATION, not authorization. Never echo the token.
            return AuthCheck.unauthenticated("authentication required: " + denied.reason());
        }

        // Reserved-prefix keys require ADMIN for ALL methods; otherwise the caller's GET->READ /
        // PUT|DELETE->WRITE mapping stands. The fail-closed corner below denies when ADMIN can't be evaluated.
        AclService.Permission required = reserved ? AclService.Permission.ADMIN : permission;

        if (authResult instanceof AuthInterceptor.AuthResult.Authenticated authed) {
            if (aclService != null) {
                if (!aclService.isAllowed(authed.principal(), authed.roles(), key, required)) {
                    return AuthCheck.forbidden(authed.principal(),
                            "Access denied: insufficient permissions for key '" + key + "'");
                }
                return AuthCheck.ok(authed.principal());
            }
            // No ACL service: an ordinary key is authn-only (unchanged). A reserved key REQUIRES ADMIN,
            // which cannot be evaluated without an ACL - FAIL CLOSED rather than fall through to ok
            // (acl/auth are independently nullable, so the deny must be explicit).
            if (reserved) {
                return AuthCheck.forbidden(authed.principal(),
                        "Access denied: reserved key '" + key + "' requires ADMIN but no ACL is configured");
            }
            return AuthCheck.ok(authed.principal());
        }

        // Unreachable for the sealed AuthResult ({Authenticated, Denied}; Denied handled above), but the
        // fail-closed corner guarantees a reserved key NEVER reaches ok if a future variant is added.
        if (reserved) {
            return AuthCheck.forbidden("-", "Access denied: reserved key '" + key + "' requires ADMIN");
        }
        return AuthCheck.ok("-");
    }

    
    private static final String SYSTEM_PREFIX = "_system/";

    
    private static boolean isReserved(String key) {
        return key.startsWith(PolicySerializer.ACL_PREFIX) || key.startsWith(SYSTEM_PREFIX);
    }

    private static String bearerToken(AdminRequest req) {
        String authHeader = req.header("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring("Bearer ".length());
        }
        return null;
    }

    
    private io.configd.common.auth.AuthResult resolveAndWipe(Credential cred) {
        if (cred == null) {
            return new io.configd.common.auth.AuthResult.Denied(
                    io.configd.common.auth.DenyReason.NO_CREDENTIAL, "missing auth token");
        }
        try {
            return chain.resolve(cred);
        } finally {
            cred.wipeSecret();
        }
    }

    
    private static Credential credentialFrom(AdminRequest req) {
        String authHeader = req.header("Authorization");
        if (authHeader != null) {
            if (authHeader.startsWith("Bearer ")) {
                return new Credential.BearerToken(authHeader.substring("Bearer ".length()));
            }
            if (authHeader.startsWith("Basic ")) {
                String encoded = authHeader.substring("Basic ".length()).trim();
                byte[] decoded;
                try {
                    decoded = java.util.Base64.getDecoder().decode(encoded);
                } catch (IllegalArgumentException e) {
                    return null; // malformed base64: no usable credential
                }
                String userPass = new String(decoded, StandardCharsets.UTF_8);
                int colon = userPass.indexOf(':');
                if (colon < 0) {
                    return null; // not user:pass
                }
                return new Credential.BasicCredential(userPass.substring(0, colon),
                        userPass.substring(colon + 1).toCharArray());
            }
            return null; // an Authorization header was sent but in an unrecognized scheme
        }
        // No Authorization header: fall back to a verified client certificate (mTLS on the HTTP plane).
        java.util.List<java.security.cert.X509Certificate> certs = req.peerCertificates();
        if (!certs.isEmpty()) {
            return new Credential.ClientCertificate(certs);
        }
        return null;
    }

    
    private AuthCheck checkAuthViaChain(AdminRequest req, String key, AclService.Permission permission, boolean reserved) {
        Credential cred = credentialFrom(req);
        io.configd.common.auth.AuthResult r = resolveAndWipe(cred);
        if (r instanceof io.configd.common.auth.AuthResult.Unavailable) {
            return AuthCheck.unavailable("authentication temporarily unavailable");
        }
        if (r instanceof io.configd.common.auth.AuthResult.Denied denied) {
            return AuthCheck.unauthenticated("authentication required: " + denied.detail());
        }
        io.configd.common.auth.Principal p =
                ((io.configd.common.auth.AuthResult.Authenticated) r).principal();
        AclService.Permission required = reserved ? AclService.Permission.ADMIN : permission;
        if (aclService != null) {
            if (!aclService.isAllowed(p.id(), p.roles(), key, required)) {
                return AuthCheck.forbidden(p.id(),
                        "Access denied: insufficient permissions for key '" + key + "'");
            }
            return AuthCheck.ok(p.id());
        }
        // No ACL service: an ordinary key is authn-only; a reserved key REQUIRES ADMIN, which cannot be
        // evaluated without an ACL - fail closed rather than fall through to ok.
        if (reserved) {
            return AuthCheck.forbidden(p.id(),
                    "Access denied: reserved key '" + key + "' requires ADMIN but no ACL is configured");
        }
        return AuthCheck.ok(p.id());
    }

    
    private AuthCheck checkAdminViaChain(AdminRequest req, String resourceKey) {
        Credential cred = credentialFrom(req);
        io.configd.common.auth.AuthResult r = resolveAndWipe(cred);
        if (r instanceof io.configd.common.auth.AuthResult.Unavailable) {
            return AuthCheck.unavailable("authentication temporarily unavailable");
        }
        if (r instanceof io.configd.common.auth.AuthResult.Denied denied) {
            return AuthCheck.unauthenticated("authentication required: " + denied.detail());
        }
        io.configd.common.auth.Principal p =
                ((io.configd.common.auth.AuthResult.Authenticated) r).principal();
        if (aclService == null) {
            return AuthCheck.forbidden(p.id(),
                    "Access denied: this control operation requires ADMIN but no ACL is configured");
        }
        if (!aclService.isAllowed(p.id(), p.roles(), resourceKey, AclService.Permission.ADMIN)) {
            return AuthCheck.forbidden(p.id(), "Access denied: ADMIN required for this control operation");
        }
        return AuthCheck.ok(p.id());
    }

    
    private void audit(String actor, String action, String key, String outcome) {
        if (auditLog != null) {
            auditLog.record(actor == null ? "-" : actor, action, key, outcome);
        }
    }

    private static String auditOutcome(ConfigWriteService.WriteResult result) {
        return switch (result) {
            case ConfigWriteService.WriteResult.Committed c -> "committed seq=" + c.seq();
            case ConfigWriteService.WriteResult.NotLeader ignored -> "failed: not-leader";
            case ConfigWriteService.WriteResult.Lost ignored -> "failed: lost-leadership";
            case ConfigWriteService.WriteResult.Indeterminate ignored -> "indeterminate";
            case ConfigWriteService.WriteResult.ValidationFailed vf -> "rejected: " + vf.reason();
            case ConfigWriteService.WriteResult.Overloaded ignored -> "rejected: overloaded";
        };
    }

    
    private AdminResponse replayRejected(AdminRequest req, String actor, String action, String key) {
        if (replayGuard == null) {
            return null; // opt-in: default off
        }
        String ts = req.header(ReplayGuard.TIMESTAMP_HEADER);
        String nonce = req.header(ReplayGuard.NONCE_HEADER);
        ReplayGuard.Decision decision = replayGuard.check(ts, nonce);
        return switch (decision) {
            case ACCEPTED -> null;
            case STALE, MALFORMED -> {
                audit(actor, action, key, "denied: replay-window (" + decision + ")");
                Map<String, String> h = jsonHeaders();
                h.put("WWW-Authenticate", "Bearer");
                yield new AdminResponse(401, h, bytes("Unauthorized: stale/future or missing replay headers"));
            }
            case REPLAY -> {
                audit(actor, action, key, "denied: replay");
                yield json(409, "Conflict: replayed request (nonce already seen)");
            }
        };
    }

    
    private record ScopeResult(ConfigScope scope, String error) {}

    
    private static ScopeResult parseScope(AdminRequest req) {
        String raw = queryParam(req.uri().getQuery(), "scope");
        if (raw == null || raw.isBlank()) {
            return new ScopeResult(ConfigScope.GLOBAL, null);
        }
        try {
            return new ScopeResult(ConfigScope.valueOf(raw.trim().toUpperCase(Locale.ROOT)), null);
        } catch (IllegalArgumentException e) {
            return new ScopeResult(null,
                    "Unknown scope '" + raw + "' (expected GLOBAL, REGIONAL, or LOCAL)");
        }
    }

    
    private static String keyValidationReason(String key) {
        // Length-before-blank mirrors ConfigWriteService.put's order, so a key that is both yields the
        // same 400 reason at the edge and in the write service.
        if (key.getBytes(StandardCharsets.UTF_8).length > 1024) {
            return "key length exceeds maximum of 1024 bytes";
        }
        if (key.isBlank()) {
            return "key must not be blank";
        }
        return null;
    }

    
    private static String queryParam(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = (eq >= 0) ? pair.substring(0, eq) : pair;
            if (k.equals(name)) {
                return (eq >= 0) ? pair.substring(eq + 1) : "";
            }
        }
        return null;
    }

    private static Map<String, String> jsonHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        return h;
    }

    private static AdminResponse json(int status, String body) {
        return new AdminResponse(status, jsonHeaders(), bytes(body));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String formatHealthStatus(HealthService.HealthStatus status) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"healthy\":").append(status.healthy());
        sb.append(",\"checks\":[");
        boolean first = true;
        for (HealthService.CheckResult check : status.checks()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":\"").append(escapeJson(check.name()))
                    .append("\",\"healthy\":").append(check.healthy())
                    .append(",\"detail\":\"").append(escapeJson(check.detail())).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

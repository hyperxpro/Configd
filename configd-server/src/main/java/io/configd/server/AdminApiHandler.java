package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.AuditLog;
import io.configd.api.AuthInterceptor;
import io.configd.api.ConfigReadService;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.api.ReplayGuard;
import io.configd.common.ConfigScope;
import io.configd.common.NodeId;
import io.configd.observability.PrometheusExporter;
import io.configd.store.ReadResult;
import io.configd.store.VersionedConfigStore;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Transport-agnostic decision core for the admin / control-plane HTTP API (ADR-0043 M2; the DR-N2
 * "extract shared decision logic" pattern proven on the edge-read surface in M1).
 *
 * <p>Both the JDK {@link HttpApiServer} adapter and the Netty {@code NettyHttpApiServer} adapter
 * delegate to this single source of truth, mapping their transport's request to an {@link AdminRequest}
 * and writing back the returned {@link AdminResponse}. Every S7 control — authn (Bearer/401), authz
 * (ACL/403), audit, replay (401/409), 429 overload, strong-read fail-closed, the {@code /metrics}
 * Bearer gate, method 405 — is therefore decided once and re-proven by the identical contract on each
 * transport (equivalence by construction, not by hopeful re-implementation).
 *
 * <p><b>C6 / RR-020 path handling (load-bearing, do not weaken).</b> The strong-read key is derived
 * from {@link URI#getPath()} — the percent-<em>decoded</em> path — and that SAME decoded key is used
 * both to classify the key (strong vs. ordinary) and to resolve the value from the store, so the two
 * cannot diverge by an encoding trick. Both adapters build that {@link URI} the identical way
 * ({@code java.net.URI}: the JDK exchange's {@code getRequestURI()}, and {@code new URI(request.uri())}
 * on Netty), so the classification is byte-identical across transports. The path is NOT raw, NOT
 * normalized (no {@code ..} collapsing), and NOT lower-cased — {@code StrongReadFailClosedTest} pins
 * each evasion vector (percent-encoded prefix, encoded slash, dot-dot, double-slash, query string).
 *
 * <p><b>Routing is exact-match for the fixed endpoints</b> ({@code /health/live}, {@code /health/ready},
 * {@code /metrics}) and prefix-match for {@code /v1/config/} — the DR-N4 tightening applied to the admin
 * surface, so a suffix variant (e.g. {@code /metricsZ}) cannot reach the Prometheus exposition. Unknown
 * paths return 404 {@code "Not Found"}. (The incumbent JDK {@code createContext} prefix routing was a
 * framework artifact; no test depended on it.)
 */
public final class AdminApiHandler {

    private final HealthService healthService;
    private final PrometheusExporter prometheusExporter;
    private final VersionedConfigStore configStore;
    private final ConfigWriteService writeService;     // nullable: read-only deployments
    private final ConfigReadService readService;       // nullable: stale-only deployments
    private final AuthInterceptor authInterceptor;     // nullable: auth disabled
    private final AclService aclService;               // nullable: ACLs disabled
    private final StrongReadPolicy strongReadPolicy;   // non-null
    // Wiring Increment 1: KEYED + SCOPED leader hint — resolves the leader of the shard that OWNS
    // (scope, key), so a read 503's X-Leader-Hint points at the right shard's leader for the read's scope
    // (a keyless/scopeless hint would loop forever at N>1, mis-routing a REGIONAL/LOCAL retry to the
    // GLOBAL shard's leader). At N=1 every (scope, key) resolves to group 0. Non-null.
    private final BiFunction<ConfigScope, String, NodeId> leaderHintSupplier;
    private final AuditLog auditLog;                   // nullable: auditing disabled
    private final ReplayGuard replayGuard;             // nullable: replay protection off

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
        this.healthService = healthService;
        this.prometheusExporter = prometheusExporter;
        this.configStore = configStore;
        this.writeService = writeService;
        this.readService = readService;
        this.authInterceptor = authInterceptor;
        this.aclService = aclService;
        this.strongReadPolicy = Objects.requireNonNull(strongReadPolicy, "strongReadPolicy must not be null");
        this.leaderHintSupplier = Objects.requireNonNull(leaderHintSupplier, "leaderHintSupplier must not be null");
        this.auditLog = auditLog;
        this.replayGuard = replayGuard;
    }

    // -----------------------------------------------------------------------
    // Transport-agnostic request / response descriptors
    // -----------------------------------------------------------------------

    /**
     * A transport's view of one request, reduced to what the decision logic needs. Both adapters
     * MUST build {@link #uri()} via {@code java.net.URI} (see the C6 note on the class) so path
     * decoding is identical. {@link #body()} is read lazily — the decision logic only invokes it for
     * a PUT, and only after auth + replay have passed, so an unauthenticated caller's body is never
     * drained.
     */
    public interface AdminRequest {
        String method();

        URI uri();

        /** First value of {@code name} (case-insensitive), or {@code null} if absent. */
        String header(String name);

        /** The full request body; only called for an authenticated, replay-cleared PUT. */
        byte[] body() throws IOException;
    }

    /**
     * The decided response: status, the exact headers to set (including {@code Content-Type}), and the
     * body bytes. A 0-length body is written as such (the incumbent behaviour for an empty config value).
     */
    public record AdminResponse(int status, Map<String, String> headers, byte[] body) {
    }

    // -----------------------------------------------------------------------
    // Routing
    // -----------------------------------------------------------------------

    /** Routes a request to the owning endpoint and returns the decided response. */
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
        return json(404, "Not Found");
    }

    // -----------------------------------------------------------------------
    // Health + metrics
    // -----------------------------------------------------------------------

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
        // F-0055: enforce bearer-token auth on /metrics when auth is configured. 401 (not 403):
        // this is authentication, not authorization (no ACL for metrics scraping). Never echo the token.
        if (authInterceptor != null) {
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

    // -----------------------------------------------------------------------
    // /v1/config/{key}
    // -----------------------------------------------------------------------

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

        // Wiring Increment 1: superset key-validation gate (post-auth) + scope parse (DL-W1-01/02).
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

        // RR-020 / ADR-0030 INV-1: GLOBAL/security ("strong-read") keys MUST be served via the
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
            // Multi-Raft Phase 1 (Seam D): route the STALE read through the sharded reader so a key on
            // shard k≠0 is read from ITS store, not the captured group-0 store (read-your-writes at N>1).
            // ConfigReadService.staleRead delegates to the sharded ConfigReader; at N=1 it resolves group 0
            // (byte-identical). The raw group-0 configStore is the fallback only for a stale-only
            // deployment with no read service wired (single-group by construction).
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

    /**
     * The RR-020 / ADR-0030 INV-1 fail-closed response: 503 with a distinguishing body and
     * {@code X-Fail-Closed: strong-read} header, plus {@code X-Leader-Hint} when a leader is known.
     * The local/stale value is NEVER served for a strong-read key on this path.
     */
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

        // Wiring Increment 1: superset key-validation gate (post-auth) + scope parse (DL-W1-01/02).
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

        ConfigWriteService.WriteResult result =
                writeService.put(key, body, scope, authCheck.principal()); // S7.5 per-principal limit
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

        // Wiring Increment 1: superset key-validation gate (post-auth) + scope parse (DL-W1-01/02).
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
                writeService.delete(key, scope, authCheck.principal()); // S7.5 per-principal limit
        audit(authCheck.principal(), "DELETE", key, auditOutcome(result));
        return writeResult(result);
    }

    /**
     * RR-004 / ADR-0033 HTTP mapping. 200 is returned ONLY after quorum commit + local apply
     * (the {@code Committed} variant); no other path returns 200.
     */
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
                // S6/D-1 (RR-110): the §11 write-overload contract — a bounded-queue 429 with a
                // Retry-After backoff signal. The reject is counted by write_rejected_overloaded at
                // the raftProposer site (the tested series behind the "sustained 429 rate" alert).
                Map<String, String> h = jsonHeaders();
                h.put("Retry-After", "1");
                yield new AdminResponse(429, h, bytes("Overloaded"));
            }
        };
    }

    // -----------------------------------------------------------------------
    // Auth gate (charter §7, RFC 7235)
    // -----------------------------------------------------------------------

    private enum AuthDecision { OK, UNAUTHENTICATED, FORBIDDEN }

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
    }

    /**
     * Maps a non-OK {@link AuthCheck} to its HTTP denial: {@code UNAUTHENTICATED} → 401 +
     * {@code WWW-Authenticate: Bearer} (RFC 7235 §3.1); {@code FORBIDDEN} → 403.
     */
    private AdminResponse authDenial(AuthCheck check) {
        return switch (check.decision()) {
            case UNAUTHENTICATED -> {
                Map<String, String> h = jsonHeaders();
                h.put("WWW-Authenticate", "Bearer");
                yield new AdminResponse(401, h, bytes("Unauthorized: " + check.reason()));
            }
            case FORBIDDEN -> json(403, check.reason());
            case OK -> throw new AssertionError("authDenial called for an OK decision");
        };
    }

    /**
     * Authentication + ACL. A missing/blank/malformed/invalid credential is AUTHENTICATION failure →
     * {@link AuthDecision#UNAUTHENTICATED} (401); an authenticated principal the ACL does not permit →
     * {@link AuthDecision#FORBIDDEN} (403). When auth is not configured the gate is open.
     */
    private AuthCheck checkAuth(AdminRequest req, String key, AclService.Permission permission) {
        if (authInterceptor == null) {
            return AuthCheck.ok("-"); // auth not configured: no resolved principal
        }

        AuthInterceptor.AuthResult authResult = authInterceptor.authenticate(bearerToken(req));
        if (authResult instanceof AuthInterceptor.AuthResult.Denied denied) {
            // No/blank/malformed/invalid credential: AUTHENTICATION, not authorization. Never echo the token.
            return AuthCheck.unauthenticated("authentication required: " + denied.reason());
        }

        String principal = (authResult instanceof AuthInterceptor.AuthResult.Authenticated authed)
                ? authed.principal() : "-";

        if (aclService != null && authResult instanceof AuthInterceptor.AuthResult.Authenticated authed) {
            if (!aclService.isAllowed(authed.principal(), key, permission)) {
                return AuthCheck.forbidden(authed.principal(),
                        "Access denied: insufficient permissions for key '" + key + "'");
            }
        }

        return AuthCheck.ok(principal);
    }

    private static String bearerToken(AdminRequest req) {
        String authHeader = req.header("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring("Bearer ".length());
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Audit + replay (S7 controls)
    // -----------------------------------------------------------------------

    /**
     * Records a mutating attempt (or its denial) if an audit log is configured. The actor is the
     * resolved principal, or {@code "-"} when unauthenticated. NEVER receives a credential. A
     * persistence failure propagates (fail-loud): for a tamper-evident control, an inability to
     * record an event is itself security-relevant and must not be silently dropped.
     */
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

    /**
     * Applies the replay guard (if enabled) to an already-authenticated mutating request. Returns the
     * rejection {@link AdminResponse} when the request is rejected, or {@code null} when accepted /
     * the guard is off. A stale/future stamp or missing headers → 401; a replayed nonce → 409.
     */
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

    // -----------------------------------------------------------------------
    // Wiring Increment 1 — scope parsing + superset key validation (RFC §1 A2/A3)
    // -----------------------------------------------------------------------

    /**
     * The parsed {@code ?scope=} result: a valid {@link ConfigScope} (with {@code error == null}), or a
     * {@code null} scope carrying an {@code error} reason for an unrecognized value (mapped to 400).
     */
    private record ScopeResult(ConfigScope scope, String error) {}

    /**
     * Wiring Increment 1 (DL-W1-02): parses the optional {@code ?scope=} query parameter
     * case-insensitively into a {@link ConfigScope}. Absent/blank ⇒ {@link ConfigScope#GLOBAL} (the A2-3
     * default — byte-identical to the prior GLOBAL-pinned surface). An unrecognized value yields an error
     * (mapped to 400 by the caller) — fail-closed, never a silent coercion that could mis-route (closes
     * the scope-confusion red-team angle). Scope is a typed field, NEVER a path segment (A2-1).
     */
    private static ScopeResult parseScope(AdminRequest req) {
        String raw = queryParam(req.uri().getQuery(), "scope");
        if (raw == null || raw.isBlank()) {
            return new ScopeResult(ConfigScope.GLOBAL, null); // A2-3 default
        }
        try {
            return new ScopeResult(ConfigScope.valueOf(raw.trim().toUpperCase(Locale.ROOT)), null);
        } catch (IllegalArgumentException e) {
            return new ScopeResult(null,
                    "Unknown scope '" + raw + "' (expected GLOBAL, REGIONAL, or LOCAL)");
        }
    }

    /**
     * Wiring Increment 1 (DL-W1-01): the SUPERSET key-validation gate. Enforces only the RFC §1 A3 rules
     * that are a true superset of the deployed flat keyspace — <b>non-blank</b> and <b>≤ 1024 bytes
     * UTF-8</b> (the deployed key-length limit, A3-5). Returns the rejection reason, or {@code null} when
     * the key is acceptable. The key is NOT rewritten/normalized — the strong-read classification
     * (C6/RR-020) depends on the raw decoded key, and the strict A3 path grammar (absolute, seg-char,
     * canonical) is the binary/driver-surface contract, deliberately NOT applied to this legacy flat-key
     * surface (see docs/wiring/increment-1-scope-and-path-validation.md §2).
     */
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

    /**
     * Returns the first value of query parameter {@code name} from the (already percent-decoded)
     * {@link URI#getQuery()} string, or {@code null} if the parameter is absent. A present parameter with
     * no {@code =} yields an empty string. Parameter names are matched exactly (case-sensitive).
     */
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

    // -----------------------------------------------------------------------
    // Response + formatting helpers
    // -----------------------------------------------------------------------

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

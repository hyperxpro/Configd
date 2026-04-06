# Phase 1 — Production Audit, Cluster D

**Scope:** `configd-server/`, `configd-control-plane-api/`, `configd-transport/`
**Commit:** `22d2bf3` (HEAD, 2026-04-17)
**Auditor role:** principal SRE + security engineer
**Reference carry-forwards:** R-13 (AuditLogger absent), F-0050/F-0051/F-0054/F-0055 (V2 closures — verified below)
**ID range:** PA-4001 — PA-4999 (this pass: PA-4001 through PA-4024)

## Coverage

| File | LoC | Read |
|---|---:|:-:|
| `configd-server/ConfigdServer.java` | 729 | full |
| `configd-server/HttpApiServer.java` | 391 | full |
| `configd-server/RaftMessageCodec.java` | 249 | full |
| `configd-server/RaftTransportAdapter.java` | 66 | full |
| `configd-server/ServerConfig.java` | 223 | full |
| `configd-control-plane-api/AclService.java` | 103 | full |
| `configd-control-plane-api/AdminService.java` | 125 | full |
| `configd-control-plane-api/AuthInterceptor.java` | 56 | full |
| `configd-control-plane-api/ConfigReadService.java` | 118 | full |
| `configd-control-plane-api/ConfigWriteService.java` | 211 | full |
| `configd-control-plane-api/HealthService.java` | 112 | full |
| `configd-control-plane-api/RateLimiter.java` | 149 | full |
| `configd-transport/BatchEncoder.java` | 183 | full |
| `configd-transport/ConnectionManager.java` | 156 | full |
| `configd-transport/FrameCodec.java` | 158 | full |
| `configd-transport/MessageRouter.java` | 111 | full |
| `configd-transport/MessageType.java` | 48 | full |
| `configd-transport/RaftTransport.java` | 31 | full |
| `configd-transport/TcpRaftTransport.java` | 413 | full |
| `configd-transport/TlsConfig.java` | 44 | full |
| `configd-transport/TlsManager.java` | 106 | full |
| **Total** | **3,782** | **21/21 files** |

Note: the task brief stated ConfigdServer.java was ~1,658 LoC. On HEAD it
is 729 LoC. Either the brief is stale or an earlier heavier version was
reduced. Read in full regardless.

## V2-closure re-verification

| ID | Claim | Status on HEAD |
|---|---|---|
| F-0050 | `TlsManager` threaded through `TcpRaftTransport`; fail-closed guard | **CONFIRMED FIXED** — `ConfigdServer.java:192-224` lifts TLS wiring above transport construction and the guard at lines 220–224 throws when `tlsEnabled()` but the transport reports `tlsManager() == null`. |
| F-0051 | Hostname verification on client sockets | **CONFIRMED FIXED** — `TcpRaftTransport.java:290-306` uses `getHostString()` (not `InetAddress`) and sets `setEndpointIdentificationAlgorithm("HTTPS")`. |
| F-0054 | Write rate limit documented at 10K/s | **CONFIRMED FIXED** — `ConfigdServer.java:357-360` wires 10K/s with 10K burst and prints the effective envelope at boot. |
| F-0055 | `/metrics` requires bearer auth when auth configured | **CONFIRMED FIXED** — `HttpApiServer.java:166-178` enforces bearer auth and emits `WWW-Authenticate: Bearer` on 401. `/health/*` remains public (information-disclosure review below: OK). |
| R-13 | `AuditLogger` implementation | **CONFIRMED ABSENT** — `glob **/AuditLogger.java` returns no files. Filed as **PA-4001 S1**. |

---

## Findings

## PA-4001: AuditLogger never implemented (R-13 carry-forward)
- **Severity:** S1
- **Location:** repo-wide (expected `configd-control-plane-api/src/main/java/io/configd/api/AuditLogger.java`)
- **Category:** security, ops
- **Evidence:** `glob **/AuditLogger.java` → no files. Referenced in `docs/prr/inventory.md:236,446`, `docs/rewrite-plan.md:162`, `docs/inventory.md:74,115,171`. `HttpApiServer.ConfigHandler` has zero log / audit calls on `handlePut` / `handleDelete` / `checkAuth` — writes succeed or fail silently at the service layer. `ConfigWriteService.put` / `delete` do not emit any audit event either.
- **Impact:** No accountability for mutating operations. Post-incident "who set key X to value Y at time T on behalf of principal P" is unanswerable from application logs alone. Violates compliance-grade change-tracking requirements for a distributed config store. Also blocks forensic response to a token leak or insider abuse — there is no immutable record of what a compromised credential did.
- **Fix direction:** Implement `AuditLogger` emitting structured JSON events: `{ts, principal, operation, key, scope, proposalId, outcome, remoteAddr, token-fingerprint (not the token), term, leaderId}` on every `handlePut` / `handleDelete` / admin operation. Persist to an append-only sink (separate file with rotation, or forwarded to a log-shipper). Include auth failures (denied token attempts) for intrusion detection. Wire via `HttpApiServer` constructor and from `ConfigWriteService`.
- **Owner:** security + api

## PA-4002: HTTP request body read is unbounded
- **Severity:** S1
- **Location:** `HttpApiServer.java:269` (`handlePut`: `exchange.getRequestBody().readAllBytes()`)
- **Category:** security, reliability
- **Evidence:** `handlePut` calls `readAllBytes()` on the request body with no upstream size cap. Although `ConfigWriteService.put` rejects values > 1 MB at `ConfigWriteService.java:127`, the rejection happens **after** the full body is already in memory. A single malicious client can send a Content-Length-advertised (or chunked) body of arbitrary size, causing the virtual-thread handler to allocate that much before validation. With JDK `HttpServer` + virtual threads, 10K concurrent attackers each pushing 1 GiB bodies trigger 10 TiB of allocation intent and a fast OOM.
- **Impact:** Remote DoS with trivial cost. A single node loss cascades to the cluster losing quorum if the attacker picks two nodes. Worst-case: heap exhaustion mid-tick crashes the node during consensus and flips leadership.
- **Fix direction:** Wrap `exchange.getRequestBody()` in a `BoundedInputStream` that caps at, e.g., 2 MiB (2× the value limit to allow framing overhead) and returns 413 Payload Too Large on overflow. Additionally, read `Content-Length` header and reject up-front when advertised length exceeds the cap. Extend `ServerConfig` with `--max-request-body-bytes` defaulting to 2 MiB.
- **Owner:** server

## PA-4003: HTTP server has no socket read/write timeout (slow-loris)
- **Severity:** S1
- **Location:** `HttpApiServer.java:66-86` — `HttpServer` / `HttpsServer` created without any timeout configuration.
- **Category:** security, reliability
- **Evidence:** JDK's `com.sun.net.httpserver.HttpServer` has no built-in request header / request body read timeout. The server is constructed via `HttpServer.create(...)` / `HttpsServer.create(...)` with no custom `Filter`, no `SO_TIMEOUT`, no `setIdleTimeout`. A client can open a TCP connection, TLS-handshake, send partial headers, and hold the virtual thread indefinitely.
- **Impact:** Slow-loris exhaustion. Even with virtual threads (cheap), the underlying NIO carrier threads can be pinned and file-descriptors consumed. With `ulimit -n` typical 65,535, an attacker from a /24 can exhaust the FD table in seconds without sending a single complete request.
- **Fix direction:** Install a custom connection filter or switch to a server that supports socket timeouts (Helidon Nima, Jetty, Vert.x). Minimal fix: set `HttpServerImpl`-level timeouts via reflection or wrap behind a TCP reverse proxy that enforces idle timeouts (envoy / nginx — document as a deployment requirement). Also add request-body read deadline.
- **Owner:** server, sre

## PA-4004: `--auth-token` without `--tls` — plaintext bearer token transmission
- **Severity:** S0
- **Location:** `ServerConfig.java:66-162` + `HttpApiServer.java:328-337` + `ConfigdServer.java:311-333`
- **Category:** security
- **Evidence:** `ServerConfig` accepts `--auth-token` and `--tls-*` independently with zero cross-validation. `ConfigdServer.start` happily starts an HTTP (non-HTTPS) listener when `sslContext == null` while simultaneously instantiating `AuthInterceptor` with a real token. The token is carried in the `Authorization: Bearer <token>` header over cleartext TCP; any on-path attacker can read it in one packet.
- **Impact:** Token exfiltration on any operator deployment that mis-configures (forgets `--tls-cert`). Captured token grants full write/delete/admin on the cluster. This is the canonical "auth without transport encryption" S0.
- **Fix direction:** In `ServerConfig.parse` (or a `validate()` method called from `ConfigdServer.start`): if `authEnabled() && !tlsEnabled()`, refuse to start with an actionable error: `"--auth-token requires --tls-cert/--tls-key/--tls-trust-store (refusing to transmit bearer tokens over plaintext)"`. Allow an explicit opt-out flag `--i-know-this-is-insecure-token-over-plaintext` for dev only, printing the same warning banner the auth-disabled branch prints.
- **Owner:** server, security

## PA-4005: TLS cert hot-reload does NOT refresh the server-side socket
- **Severity:** S1
- **Location:** `TcpRaftTransport.java:113-122` (start) + `314-335` (`createServerSocket`) + `TlsManager.java:84-86` (reload) + `ConfigdServer.java:528-537` (tls reload schedule)
- **Category:** security, reliability
- **Evidence:** `ServerSocket` is constructed **once** inside `start()` using `tlsManager.currentContext()` at bind time. `TlsManager.reload()` only swaps `currentContext` for **new** sockets. Inbound connections arriving after a reload still use the original `SSLContext` embedded in the already-running `SSLServerSocket`. When the original cert expires, the server socket keeps serving the expired material until process restart — contradicting the runbook `docs/runbooks/cert-rotation.md` §3 promise that `TlsManager` "reloads the keystore without restarting the JVM."
- **Impact:** Silent divergence between inbound and outbound trust paths. After rotation: the node's outbound client sockets present the NEW cert to peers (because `createClientSocket` calls `tlsManager.currentContext()` per socket) while its inbound server side still accepts against the OLD truststore and presents the OLD cert. A peer that has already purged the old CA from its truststore will succeed in one direction and fail in the other, producing half-broken consensus links. Runbook `docs/runbooks/cert-rotation.md` step 5 ("remove old CA after all nodes have new certs") will brick inbound connectivity to any non-restarted node.
- **Fix direction:** Rebuild the `SSLServerSocket` on reload. Pattern: store the server socket and a mutex; on reload, create a new `SSLServerSocket`, bind it to an ephemeral port, atomically swap the accept loop to the new socket, close the old one. Alternatively: use `SSLContext`'s `X509ExtendedKeyManager` with a `DelegatingKeyManager` that re-resolves on each `chooseEngineServerAlias` call (lets one SSLContext track reloads without socket rebuild — preferred for production). Add integration test: start transport, rotate cert, assert an inbound connection negotiates the new cert.
- **Owner:** transport, security, sre

## PA-4006: TLS reload has no failure metric and no alarm on repeated failure
- **Severity:** S1
- **Location:** `ConfigdServer.java:529-536`
- **Category:** observability, security, ops
- **Evidence:** The reload task catches `Exception` and prints to `System.err` only. No metric is incremented, no alert surfaces, no state is tracked. If the cert file is removed or the PKCS12 password changes on-disk, the reload silently fails every 60 s. When the current cert expires, the node suddenly stops accepting handshakes with no prior warning.
- **Impact:** Runbook `cert-rotation.md` promises alert `configd_transport_tls_cert_expiry_days < 30` and `configd_transport_tls_handshake_failures_total` — neither metric is implemented (grep for them: only present in the runbook doc). Operators have no signal between "rotation in place" and "handshakes failing in production."
- **Fix direction:** Add `configd_transport_tls_reload_total{result=success|failure}` counter, `configd_transport_tls_cert_expiry_seconds` gauge (parse `X509Certificate.getNotAfter()` post-load), and `configd_transport_tls_handshake_failures_total` counter (hook into `SSLServerSocket.accept()` error path). Wire through `MetricsRegistry`. Add a burn-rate-style alert: 3 consecutive reload failures → log at ERROR and set readiness check to unhealthy.
- **Owner:** transport, sre, observability

## PA-4007: mTLS `needClientAuth` fails with opaque error and no client-identity downstream
- **Severity:** S1
- **Location:** `TcpRaftTransport.java:321-323` + `217-263` (inbound handler path)
- **Category:** security
- **Evidence:** Server uses `setNeedClientAuth(true)` so clients without a valid cert chain fail the handshake. But (a) no exception is translated into a useful log — the handshake failure lands in `acceptLoop` → `System.err.println("Accept error: ...")` at line 211 with no client identity, (b) once the handshake succeeds, the peer's verified certificate principal is **never read** (`socket.getSession().getPeerCertificates()` is nowhere in the code). The message handler only trusts `senderId = in.readInt()` (line 222) — a 4-byte field the **peer claims**, not one bound to the TLS identity.
- **Impact:** A node whose mTLS cert authorizes it as NodeId=3 can impersonate NodeId=1 at the application layer by writing `1` in the sender field after a valid handshake. TLS authenticates the transport but the Raft layer uses unauthenticated integer claims. In a cluster where only some nodes should be leaders, this lets any authenticated node forge RequestVote messages on behalf of another. Combined with `AppendEntriesRequest` carrying the leader term, a compromised non-leader cert can trigger split-brain recovery flows.
- **Fix direction:** After handshake, extract `sslSocket.getSession().getPeerPrincipal()` / SAN, map to an expected `NodeId` via a principal-to-nodeId binding (derive from cert CN/SAN, or a configured map), and on every inbound message assert `claimedSenderId == certifiedNodeId`. Reject mismatches and increment a `configd_transport_spoofed_sender_total` counter. Log handshake failures with remote address + failure reason.
- **Owner:** transport, security

## PA-4008: Auth-token comparison timing-safe — but different-length tokens fail fast
- **Severity:** S2
- **Location:** `ConfigdServer.java:320-329`
- **Category:** security
- **Evidence:** `MessageDigest.isEqual` is constant-time **within equal-length arrays** (per JDK docs). When lengths differ, the method short-circuits at a length check. An attacker can thus learn the server-side token's length by timing which request prefixes are rejected at different rates, even though the byte-by-byte comparison is safe.
- **Impact:** Token-length leak reduces brute-force space. Minor on its own because practical tokens should be ≥ 32 bytes, but it is the exact class of leak F-V7-01 targeted. Combined with PA-4004 (plaintext bearer) this amplifies.
- **Fix direction:** Pad both tokens to a fixed length before comparison (`Arrays.copyOf(expected, max(a,b))` and `Arrays.copyOf(provided, max)`) then compare, then compare lengths at the end. Or: compare HMAC-SHA-256 digests of both (constant-length output, independent of input length).
- **Owner:** api, security

## PA-4009: `/health/ready` potentially leaks cluster topology and error strings
- **Severity:** S2
- **Location:** `HttpApiServer.java:128-146` + `HealthService.java:42-57` + `ConfigdServer.java:338-346`
- **Category:** security
- **Evidence:** The readiness handler returns `HealthStatus` rendered as JSON by `formatHealthStatus` including the raw `check.detail()`. The registered check at `ConfigdServer.java:344` emits `"no leader elected"` — innocuous — but `HealthService` accepts arbitrary checks whose `detail` may surface internal state (Raft term, peer count, storage errors with file paths). `/health/ready` is intentionally unauthenticated (task note). Future readiness checks that include stack traces or internal paths will leak them to any unauthenticated scraper.
- **Impact:** Recon surface. Discovery of unreachable peers, leader identity, storage layout. Not a direct compromise today, but a footgun for future readiness checks.
- **Fix direction:** Document a rule in `HealthService` javadoc: "detail strings returned from public readiness checks MUST be free of PII, credentials, file paths, stack traces, and internal IP addresses." Add a `CheckResult.sanitizedDetail()` that runs a redaction pass (strip stack traces, strip paths with `[REDACTED]`). Apply in `formatHealthStatus`. Consider a two-tier model: shallow public probe (just boolean) vs. authenticated deep probe.
- **Owner:** api, security

## PA-4010: JSON escape in `formatHealthStatus` does not handle control characters
- **Severity:** S3
- **Location:** `HttpApiServer.java:369-376`
- **Category:** security (log/JSON injection), correctness
- **Evidence:** `escapeJson` handles `\`, `"`, `\n`, `\r`, `\t` only. Characters 0x00–0x08, 0x0B, 0x0C, 0x0E–0x1F are passed through unescaped into the JSON string body, which is invalid per RFC 8259 §7. A crafted check name / detail containing 0x01 would produce malformed JSON, breaking scrapers that use strict parsers.
- **Impact:** Health endpoint returns invalid JSON if any check detail contains control chars. Breaks Kubernetes probes that use strict JSON parsers (`kubectl get --raw`). Minor today because `ConfigdServer.java:344` emits only ASCII strings, but any future check registering operator-supplied strings could corrupt the response.
- **Fix direction:** In `escapeJson`, walk characters and emit `\u00XX` for any code point < 0x20 not already handled. Prefer a dependency-free standard escape routine or switch to JDK 21+'s built-in JSON builder.
- **Owner:** server

## PA-4011: Root ACL principal cannot override per-prefix grants (longest-prefix deny)
- **Severity:** S1
- **Location:** `AclService.java:79-102` + `ConfigdServer.java:330-332`
- **Category:** correctness, security, ops
- **Evidence:** `ConfigdServer` grants root full perms on prefix `""`. `AclService.isAllowed` uses longest-prefix match and returns **immediately** on the first prefix that starts-with the key — if that principal map does not include the caller's grant, returns `false`. If an operator later grants `bob` READ on `app/`, root loses WRITE on `app/...` because the longest match is `app/`, which has only `bob` + READ. Root principal map is never consulted.
- **Impact:** Confusing denial semantics. A break-glass root account cannot recover write access to a subtree once per-prefix ACLs are introduced — the only recovery is `revoke("app/", ...)` which is itself a mutation. Inverts the typical admin-override model. Time-of-use/time-of-check: `AclService.isAllowed` reads from `ConcurrentSkipListMap`, walks backward with `lowerKey`, and reads the value via `acls.get(candidate)`. A concurrent `revoke` during walk returns a stale view — may incorrectly allow or deny a single request mid-revocation.
- **Fix direction:** Two-layer policy. For each candidate prefix (longest first), check the principal; if granted, allow; if that principal is absent at that prefix, continue walking to shorter prefixes. Explicitly document "default-deny with positive-grant inheritance up the prefix tree." Alternative: distinguish `admin` as a role that short-circuits `isAllowed` regardless of prefix, consulted only for `AclService.Permission.ADMIN`. Add concurrency test exercising grant/revoke/read interleavings.
- **Owner:** api, security

## PA-4012: `AdminService` defined but never wired into any HTTP endpoint
- **Severity:** S1
- **Location:** `AdminService.java:1-125` + `HttpApiServer.java:74-86`
- **Category:** ops, observability
- **Evidence:** `grep -r AdminService configd-server/` returns zero hits. `HttpApiServer` registers only `/health/*`, `/metrics`, `/v1/config/`. There is no path to invoke `AdminService.addNode` / `removeNode` / `transferLeadership` / `clusterStatus` from outside the JVM. Runbook `leader-stuck.md` / `reconfiguration-rollback.md` presume such endpoints.
- **Impact:** Operator has no in-band way to drive cluster membership changes, trigger leadership transfer, or read cluster status. Breaks PRR §1.4 "operator control plane." Recovery paths in runbooks (leader-stuck, reconfiguration-rollback) require `jcmd`-style reflection workarounds. Also implies the `/admin/tls-reload` endpoint promised in `cert-rotation.md:44` does not exist.
- **Fix direction:** Add `AdminHandler` under `/v1/admin/` with routes `GET /cluster`, `POST /membership/add`, `POST /membership/remove`, `POST /leadership/transfer`, `POST /tls-reload`. All must gate on `AclService.Permission.ADMIN`. Wire from `ConfigdServer.start` — the `MultiRaftDriver` already exposes the membership primitives needed. Simultaneously, implement the runbook-referenced `/admin/tls-reload` endpoint.
- **Owner:** server, api, sre

## PA-4013: Rate limit is per-node, not cluster-wide; admin operations share the bucket
- **Severity:** S2
- **Location:** `RateLimiter.java:1-149` + `ConfigWriteService.java:135-170`
- **Category:** reliability, ops, docs
- **Evidence:** `RateLimiter` is a single `AtomicLong`-backed bucket instantiated per `ConfigdServer`. In a 5-node cluster configured at 10,000/s each, the **cluster** can absorb up to 50,000/s at the leader (though only the leader mutates, so effectively one node). Docs at `performance.md` / `ADR-0017` claim "cluster-wide 10K/s" — not true. The same bucket gates all `put` / `delete` traffic. When saturated under, say, a flood of synthetic writes from a misbehaving client, legitimate admin operations (which currently also flow through writes — see PA-4012 — or would, when admin endpoints are wired) get 429'd alongside.
- **Impact:** Admin recovery during an overload is degraded: "break-glass" operations cannot preempt a rate-limit storm. Docs misalignment (F-0054 re-opens if the claim is "cluster-wide"; only "per-node" is correct).
- **Fix direction:** Distinguish write-data and write-admin buckets (or use a priority queue where admin bypasses the normal bucket). For cluster-wide: leader-enforced global rate via Raft-consensus-ordered admission. At minimum, correct the documentation to say **per-node**, print the per-node value at boot (already done at `ConfigdServer.java:360` — good), and publish `configd_write_ratelimit_permits_available` gauge.
- **Owner:** api, sre, docs

## PA-4014: `readDispatchExecutor` queue saturation has no backpressure
- **Severity:** S1
- **Location:** `ConfigdServer.java:381-385` + `415-472` + `HttpApiServer.java:236-245`
- **Category:** reliability, perf
- **Evidence:** `readDispatchExecutor` is a single-threaded `ScheduledExecutorService` created via `Executors.newSingleThreadScheduledExecutor(...)` with the default unbounded `LinkedBlockingQueue`. Linearizable reads from HTTP handlers submit to it (via `readDispatchExecutor.execute(...)`). If tick thread stalls for even 200 ms, 10K/s of read requests pile up in the queue — unbounded. Each pending task holds a `CompletableFuture` and an `AtomicLong` — ~100 bytes. 1M pending = 100 MiB. HTTP threads are virtual threads (cheap) so they block on `resultFuture.get(150ms)` — eventually they time out and return false, but meanwhile the queue still grows faster than it drains.
- **Impact:** Silent memory bloat during transient tick-thread stalls. Worst case: tick thread OOMs from the queue growth, which prevents recovery. HoL blocking is NOT prevented — it is merely deferred from the tick thread into the dispatch queue.
- **Fix direction:** Replace the underlying executor with a `ThreadPoolExecutor(1, 1, ..., new ArrayBlockingQueue<>(bound), CallerRunsPolicy)` or `AbortPolicy`. With `AbortPolicy`, the HTTP handler catches `RejectedExecutionException` and returns 503 Service Unavailable immediately — visible backpressure. Publish `configd_read_dispatch_queue_depth` gauge. Document the bound (e.g., 10K).
- **Owner:** server, sre

## PA-4015: `ConfigdServer.start` is not transactional — partial startup leaks resources
- **Severity:** S1
- **Location:** `ConfigdServer.java:141-546`
- **Category:** reliability
- **Evidence:** `start()` constructs subsystems in sequence. Between `tcpTransport.start()` (line 246) and `httpApiServer.start()` (line 484) several things can throw — creating `ScheduledExecutorService`s (376–390), constructing `PrometheusExporter` (477), HTTP bind (485). If any throws, earlier resources (started `TcpRaftTransport`, created executors, allocated `ConfigSigner`, Raft WAL file handles) are never closed — nothing catches and rolls back. Shutdown hook is registered at line 540, AFTER most of the construction, so a throw above line 540 leaves no cleanup path at all. The process will exit from the unhandled exception propagating out of `main`, but any in-flight I/O (WAL fsync) is not flushed cleanly.
- **Impact:** Restart loops (e.g., under a misconfigured port) corrupt resources. File descriptors leak across failed-startup → retry cycles. On container platforms that auto-restart, a boot-time TCP bind conflict can compound quickly.
- **Fix direction:** Wrap the body of `start()` in a try / catch that, on exception, walks a "started components" list in reverse order and calls `close()` on each before rethrowing. The existing `shutdownExecutor` / `tcpTransport.close()` logic is reusable. Add a `ConfigdServerStartupException` wrapper. Unit-test: force `httpApiServer` construction to throw; assert `tcpTransport` was closed and no threads leak.
- **Owner:** server

## PA-4016: Shutdown ordering stops HTTP first but executors keep draining read callbacks to possibly-closed transport
- **Severity:** S2
- **Location:** `ConfigdServer.java:557-573`
- **Category:** reliability, correctness
- **Evidence:** `shutdown()` sequence: (1) `httpApiServer.stop(2)`, (2) shut `readDispatchExecutor` (2 s timeout), (3) shut `tickExecutor` (5 s timeout), (4) shut `tlsReloadExecutor`, (5) `tcpTransport.close()`. Problem: tick thread during its 5-second drain still calls `driver.tick()` which may send AppendEntries via `TcpRaftTransport.send` — but `tcpTransport.close()` hasn't run yet, so that's OK. However, the HTTP stop runs BEFORE tick stops, so the tick thread may attempt to complete in-flight linearizable read futures whose HTTP response handlers are already gone (the `sendResponseHeaders` call races with the JDK's closed `HttpServer`). Also: `tickExecutor` draining calls `whenReadReady(readId, cb)`; if the tick drain races with the HTTP stop, the callback completes a future whose waiter is an already-expired virtual thread — harmless, but the state machine lag metric misattributes.
- **Impact:** Cosmetic today. If future code adds side-effects inside the read-completion callback (audit log emission, metrics flush), the side-effect occurs after shutdown started, crossing closed-resource boundaries.
- **Fix direction:** Consider inverting: shut `readDispatchExecutor` first, then `httpApiServer.stop(...)`, then `tickExecutor`. This ensures no HTTP handler is blocked on a read future that will never complete. Alternatively: in HTTP handlers, if the read future times out during shutdown, return 503 immediately with a shutdown flag.
- **Owner:** server

## PA-4017: `RaftMessageCodec` decode paths lack bounds checks on length fields
- **Severity:** S1
- **Location:** `RaftMessageCodec.java:107-124` (AppendEntries), `182-218` (InstallSnapshot)
- **Category:** security, correctness
- **Evidence:** `decodeAppendEntries` reads `int numEntries = buf.getInt()` (line 113) and then `new ArrayList<>(numEntries)`. No cap. A peer (or, post-PA-4007, spoofed peer) can send `numEntries = Integer.MAX_VALUE` → `new ArrayList<>(2^31-1)` allocates the backing array of `Object[2^31-1]` ≈ 16 GiB address space → OOM. Next: `new byte[cmdLen]` at line 119 with unchecked `cmdLen` — if an attacker crafts `numEntries=1`, `cmdLen=Integer.MAX_VALUE`, the `new byte[]` fails, but before that, the length is read from the wire without comparing to remaining buffer bytes. `decodeInstallSnapshot` has the same issue at `dataLen` (line 205) and `configLen` (line 210).
- **Impact:** Cross-peer OOM. `FrameCodec.decode` caps the overall frame at 16 MiB (`TcpRaftTransport.java:227`), so `numEntries * minEntryBytes < 16 MiB` — an attacker cannot claim 2^31 entries because the frame itself would exceed 16 MiB. BUT `new ArrayList<>(capacity)` allocates the `Object[capacity]` immediately — Java allows `new ArrayList<>(1_000_000_000)` within a 16 MiB frame that claims `numEntries = 1_000_000_000`. This pre-allocates ~8 GiB of pointers before any entry is read. Same for `new byte[cmdLen]` where `cmdLen` could be 16 MiB — attacker can force many allocations by sending `numEntries=100, cmdLen=100000` each, but each allocation is bounded by frame size. The `ArrayList` presizing is the real bug.
- **Fix direction:** Before `new ArrayList<>(numEntries)`, assert `numEntries <= buf.remaining() / minEntryBytes` where `minEntryBytes = 8 + 8 + 4 = 20`. Before `new byte[cmdLen]`, assert `cmdLen <= buf.remaining()`. Same for `dataLen` / `configLen`. Reject with `IllegalArgumentException("malformed frame: length exceeds payload")`. Fuzz-test with `jqwik`.
- **Owner:** server, security

## PA-4018: `FrameCodec.decode` accepts 16 MiB frames — no per-peer rate on large frames
- **Severity:** S2
- **Location:** `TcpRaftTransport.java:226-229` + `FrameCodec.java:113-136`
- **Category:** reliability, resource
- **Evidence:** Inbound frame size is capped at 16 MiB per frame. A peer sending 10 frames/sec of 16 MiB each = 160 MB/s per peer. With 4 peers, a rogue cluster member forces 640 MB/s of allocation on the target node. JVM GC pressure spikes; no per-peer rate limit or flow control exists. No detection of "peer is generating unusual traffic" either.
- **Impact:** A single compromised node (still holding a valid mTLS cert, has not been rotated out) can saturate memory bandwidth of a peer and force tick-loop stalls.
- **Fix direction:** Add per-peer token bucket on inbound byte rate (configurable, default 10 MB/s). On exceeding, pause reading from the socket via `socket.getChannel().configureBlocking(false)` or simply `Thread.sleep` in the read loop (virtual threads make this cheap). Publish `configd_transport_peer_inbound_bytes_per_sec`. Separately: reduce the 16 MiB per-frame cap to 4 MiB if application-layer InstallSnapshot chunks are smaller.
- **Owner:** transport, sre

## PA-4019: `TcpRaftTransport` has no per-peer send queue / backpressure — `sendFrame` blocks under `sendLock`
- **Severity:** S1
- **Location:** `TcpRaftTransport.java:353-412` (`PeerConnection.sendFrame`, `sendLock`)
- **Category:** reliability, perf
- **Evidence:** `sendFrame` holds `sendLock` across `ensureConnected()` + `out.writeInt` + `out.write(encoded)` + `out.flush()`. If the peer's TCP window is full (slow receiver), `out.write` blocks until kernel drains. The caller is `RaftTransportAdapter.send` → invoked on the tick thread (via `driver.tick()` → `raftNode` → transport.send). **A slow peer blocks the tick thread directly**, which is the one thread that must never block (task brief explicitly: "consensus progress MUST NOT be blocked"). No `SO_SNDTIMEO`, no async send queue, no `SocketChannel` with write-ready selection.
- **Impact:** Single slow peer freezes leader. Heartbeats miss. Follower elections trigger. Entire cluster can be destabilized by one misbehaving peer. This is the canonical lesson of multi-raft transport design — known-bad pattern.
- **Fix direction:** Per-peer bounded `ArrayBlockingQueue<byte[]>` (size e.g. 1024) with a dedicated virtual thread draining it into the socket. `send()` does `queue.offer(...)` with `return false` on full → transport logs "dropped for slow peer X" and increments `configd_transport_send_dropped_total{peer}`. Tick thread never blocks. Combine with `socket.setSoTimeout` on reads and configurable `SO_SNDBUF`. This is a required GA-blocker; currently the transport is trivially DoS-able by any peer that slows its receive.
- **Owner:** transport, sre

## PA-4020: Duplicate reader thread on outbound connection causes double-dispatch
- **Severity:** S1
- **Location:** `TcpRaftTransport.java:390-397`
- **Category:** correctness
- **Evidence:** When `ensureConnected()` succeeds, it submits a reader task for the outbound socket (`executor.submit(() -> handleInboundConnection(readerSocket))`). But the peer's own `acceptLoop` accepts the inbound side of the same TCP connection as a separate fd and also runs `handleInboundConnection` on it. **Net: two reader threads per logical link, one per direction.** If the peer sends a response on the outbound socket (which a well-behaved peer does NOT do — each direction of a bidirectional logical link uses separate TCP connections), the reader thread dispatches to `messageHandler`. But the same peer also opens a second outbound connection back to us, where our `acceptLoop` starts yet another reader. So every logical peer-pair has up to **four** concurrent reader threads, any of which can dispatch duplicate messages to the state machine if the peer happens to write on both sockets.
- **Impact:** Under the current Raft wire pattern (client writes, server reads), this is benign most of the time — peers don't write back on the client-side socket. But it is unsafe-by-design: any future batch/heartbeat reply piggybacked on the outbound socket would double-dispatch to `RaftTransportAdapter.registerInboundHandler` and `MultiRaftDriver.routeMessage`, producing duplicate votes / duplicate AppendEntriesResponses. At best, this confuses metrics; at worst, it corrupts Raft quorum counting.
- **Fix direction:** Remove the outbound-side reader (lines 392–393). Enforce "one direction per TCP socket" convention: a node never sends application data on a socket it opened, only on the one it accepted. Or: use full-duplex with de-duplication keyed by `(from, term, messageId)` — heavier. Simplest fix is to drop the outbound reader submission.
- **Owner:** transport

## PA-4021: `ConnectionManager` state reads race with outbound `PeerConnection` mutations
- **Severity:** S2
- **Location:** `TcpRaftTransport.java:275-281` (`handleSendFailure`) + `393-397` (`ensureConnected`) + `ConnectionManager.java:23-146`
- **Category:** correctness, reliability
- **Evidence:** `ConnectionManager` doc says "designed for single-threaded access from the transport I/O thread. No synchronization is used." But `TcpRaftTransport` accesses it from **multiple** threads: send-path (tick thread), `handleSendFailure` (whoever throws from `sendFrame`, i.e., tick thread or reader thread), `ensureConnected` (whoever is first to establish, may be a virtual thread for an inbound-triggered out-of-band reconnect). Guards use `synchronized (connectionManager)` at lines 278 and 395 but the `state(peer)` method (line 101) is called from `ConnectionManager.canSend` from **anywhere** without synchronization. `state()` mutates `conn.state` (line 109) during the backoff-expiry read path — a race between one thread mutating via `markConnected` / `markDisconnected` and another reading via `state()` that itself mutates.
- **Impact:** ConnectionManager's internal `HashMap` and `PeerConnection.state` are accessed unsafely. Under contention: torn-writes on `state` (no volatile), lost `markConnected` if `markDisconnected` races, and — because `HashMap.get` is being called concurrently with `putIfAbsent` inside `addPeer` — possible infinite loop on resize (the classic `HashMap` concurrency bug). `addPeer` is called only at construction, so the infinite loop risk is contained unless future code adds dynamic peers.
- **Fix direction:** Either (1) convert `ConnectionManager` to genuinely thread-safe: `ConcurrentHashMap<NodeId, PeerConnection>` + `AtomicReference<ConnectionState>` or `volatile` fields on `PeerConnection`; or (2) funnel all calls through a single dedicated "connection control" thread via an `ArrayBlockingQueue<ConnCommand>` and keep the "no-sync" invariant. Option (1) is simpler. Update the javadoc accordingly.
- **Owner:** transport

## PA-4022: `TlsConfig` silently defaults store password to empty `char[0]`
- **Severity:** S2
- **Location:** `TlsConfig.java:19-26` + `TlsManager.java:52`
- **Category:** security, ops
- **Evidence:** Constructor accepts `storePassword == null` and silently substitutes `new char[0]`. `TlsConfig.mtls(...)` factory passes `new char[0]` by default. `ServerConfig` has no `--tls-store-password` flag — there is no way to use a password-protected PKCS12. Also: same password is used for both keystore and trust store, which is a known limitation (called out in prior audits). If operators generate keystores with a password (the default `keytool` behavior), the empty-string password will fail with an opaque `UnrecoverableKeyException`.
- **Impact:** Production TLS deployments using `keytool` default-password PKCS12 files cannot load; operators left debugging cryptic JCA errors. Doc `cert-rotation.md` step 1 uses `keytool -genkeypair` which prompts for a password — the configd server then cannot open the resulting file.
- **Fix direction:** Add `--tls-store-password-file` (read password from file, never a CLI arg to avoid process-listing leak), or `--tls-store-password-env` (env var). Validate at boot: if the store requires a password and none is supplied, fail with a clear error. Allow separate key vs trust passwords.
- **Owner:** transport, server, sre

## PA-4023: Cert-rotation runbook references endpoints and metrics that do not exist
- **Severity:** S2
- **Location:** `docs/runbooks/cert-rotation.md:7, 12, 44, 56, 57, 58`
- **Category:** docs, ops
- **Evidence:**
  - `/metrics` exposes `configd_transport_tls_cert_expiry_days`, `configd_transport_tls_handshake_failures_total`, `configd_transport_connections_active` — **none exist** in the code (grep returns only the runbook). `/admin/tls-reload` — **no admin endpoint is registered** (see PA-4012). `configd_raft_heartbeat_sent_total` — also not implemented.
  - Runbook is therefore unexecutable as written. During a real incident, operators cannot follow the steps.
- **Impact:** Runbook gap on a real incident scenario (cert expiry). S1 for availability if the primary mitigation fails; filed S2 here because manual restart is still possible. F-0050/F-0051 closures imply TLS is production-grade — the runbook contradicts that.
- **Fix direction:** Either implement the referenced endpoints/metrics (preferred; tracked here via PA-4006 + PA-4012) and verify the runbook end-to-end in staging, or rewrite the runbook to document what actually works today (JVM restart with new cert file staged). Include a "runbook tested in staging YYYY-MM-DD" footer with rotation in the quarterly schedule.
- **Owner:** sre, docs

## PA-4024: No visible logging on hot path, no log-injection guard on slow path
- **Severity:** S3
- **Location:** multiple: `ConfigdServer.java:518`, `TcpRaftTransport.java:211`, `256`, `260`, `RaftTransportAdapter.java:61`
- **Category:** observability, security (log injection)
- **Evidence:** All logging uses `System.err.println` / `System.out.println` directly. No slf4j or structured logger. `RaftTransportAdapter.java:61` emits `"Failed to decode Raft message from " + from + ": " + e.getMessage()` — `e.getMessage()` may contain attacker-controlled content (a crafted frame with a malformed type can produce an `IllegalArgumentException` whose message includes the bad byte). That content is printed verbatim to stderr; if stderr flows into a log system that parses newlines, an attacker can inject fake log lines. Hot-path logging is absent today (no `INFO` per request — good) but re-introduction is only one line away.
- **Impact:** Log injection opens path to forged audit trails (when PA-4001 lands). Lack of a structured logger makes it impossible to enforce redaction rules. Lack of log rotation / size limits means stderr grows unbounded.
- **Fix direction:** Adopt `java.lang.System.Logger` or a light slf4j binding with JSON layout; redact newlines/control chars in any interpolated attacker-controlled string; route to a rotated file (size + age); enforce "no log on HTTP hot path at INFO or below."
- **Owner:** observability, security

---

## Severity tally

| Severity | Count | IDs |
|---|---:|---|
| S0 | 1 | PA-4004 |
| S1 | 11 | PA-4001, PA-4002, PA-4003, PA-4005, PA-4006, PA-4007, PA-4011, PA-4012, PA-4014, PA-4015, PA-4017, PA-4019, PA-4020 |
| S2 | 8 | PA-4008, PA-4009, PA-4013, PA-4016, PA-4018, PA-4021, PA-4022, PA-4023 |
| S3 | 2 | PA-4010, PA-4024 |

(S1 row miscounts — actual: 13 S1. Recounted below.)

| Severity | Count |
|---|---:|
| S0 | 1 |
| S1 | 13 |
| S2 | 8 |
| S3 | 2 |
| **Total** | **24** |

## Must-fix before GA (author recommendation)

1. **PA-4004** (S0) — plaintext-token guard. One-line fix.
2. **PA-4001** (S1, carry R-13) — implement `AuditLogger`.
3. **PA-4019** (S1) — per-peer send queue / async send. Tick thread freeze under any slow peer is not acceptable for GA.
4. **PA-4005** (S1) — TLS reload rebuild server socket. Runbook currently lies.
5. **PA-4017** (S1) — `RaftMessageCodec` bounds checks. Cross-peer OOM.
6. **PA-4007** (S1) — mTLS identity binding. NodeId spoofing inside mTLS is a quiet compromise vector.
7. **PA-4002** + **PA-4003** (S1) — HTTP request-body cap and slow-loris mitigation. Trivial remote DoS.
8. **PA-4011** (S1) — ACL admin-override semantics; unblockable without this.
9. **PA-4012** (S1) — wire `AdminService` + `/admin/tls-reload`.
10. **PA-4020** (S1) — remove the duplicate outbound reader before double-dispatch ever manifests under a peer that replies on both legs.

GA-blockers at count 10 of 13 S1 + 1 S0 = 11 blockers. Remaining S1s (PA-4006, PA-4014, PA-4015) are hardening that should land but not gate.

## Open questions for Phase 2

- Does the `MultiRaftDriver` already support per-group rate limiting, or is `RateLimiter` the only bucket? (inform PA-4013)
- Is the intent that `/v1/config/` serve JSON or raw bytes? `handleGet` returns `application/octet-stream` but `handlePut` accepts raw bytes with no content-type check — inconsistent API contract.
- Where should `AuditLogger` sink go for compliance — local append-only file, or Kafka, or a separate Raft group for audit records?

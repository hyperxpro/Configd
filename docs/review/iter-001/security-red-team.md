# security-red-team — iter-001
**Reviewer:** security-red-team
**Iteration:** 1
**Severity floor:** S3
**Findings:** 16

Note on scope: items already YELLOW/RED in `docs/ga-review.md` (S1
DeltaVerifier, S2 SubscriptionAuthorizer, S4 peer-id binding, S5 TLS
reload, S6 auth-token-requires-TLS, S7 root-principal override, S8
AuditLogger, B12 ADR for `--enable-preview`, O8 OTel) are NOT
re-counted. Findings below are NEW gaps or false-GREENs.

---

## SEC-001 — Auth-token leaked via process argv (`/proc/$pid/cmdline`)
- **Severity:** S1
- **Location:** `configd-server/src/main/java/io/configd/server/ServerConfig.java:118-121`, `configd-server/src/main/java/io/configd/server/ConfigdServer.java:319` (consumer); deployment guidance in `deploy/kubernetes/configd-statefulset.yaml:79-104` (no Secret-mount example)
- **Category:** secret-leak
- **Evidence:**
  ```
  case "--auth-token" -> {
      requireNextArg(args, i, "--auth-token");
      authToken = args[++i];
  }
  ```
- **Impact:** The bearer token used for ALL API/admin write operations
  is supplied as a command-line flag. On Linux it is world-readable in
  `/proc/<pid>/cmdline` (any local UID/sidecar container in the same
  PID namespace), shows up in `ps`, in K8s `describe pod`, in container
  runtime audit, and in any crash/heap dump that captures argv. An
  attacker with read access to the node — including a co-tenant pod, a
  compromised sidecar, or a kernel exporter scraping `/proc` — recovers
  the root-equivalent admin credential. This is *new* relative to the
  S6 RED gate, which only flags "auth-token requires TLS at startup";
  it does not call out the argv exposure itself.
- **Fix direction:** Add `--auth-token-file <path>` (read once, then
  zero the buffer) and `CONFIGD_AUTH_TOKEN` env var, and deprecate the
  raw `--auth-token` flag (warn + reject in next minor). The K8s
  manifest should mount a `Secret` and reference via env or
  `subPath`-mounted file. SafeLog the token fingerprint at startup, not
  the value.
- **Proposed owner:** `configd-server` / `ServerConfig`

## SEC-002 — HTTP request body unbounded (`readAllBytes()`) — false-GREEN re: prod-audit PA-4002
- **Severity:** S1
- **Location:** `configd-server/src/main/java/io/configd/server/HttpApiServer.java:269`
- **Category:** api-surface
- **Evidence:**
  ```
  byte[] body = exchange.getRequestBody().readAllBytes();
  ```
  ServerConfig has no `--max-request-body-bytes` flag; `ConfigWriteService`'s
  internal 1 MiB check (`ConfigWriteService.java:127`) only triggers
  *after* the entire body is buffered.
- **Impact:** Any unauthenticated client (auth check is per-request but
  `readAllBytes` runs *before* the size check — wait, auth check at
  L262 runs first; still, an authenticated low-privilege client, or any
  client when auth is disabled, can PUT arbitrary-sized requests. With
  virtual-thread-per-request and no JVM heap bound on body size beyond
  `-Xmx2g`, ~20 concurrent 100-MiB PUTs OOM the server. Slowloris-style
  body streaming ties up virtual threads indefinitely (no socket
  read-timeout is set on the JDK HttpServer). PA-4002 in
  `docs/prod-audit.md` flagged this; it remains unfixed despite the
  Phase 7 GREEN claim.
- **Fix direction:** Wrap `getRequestBody()` in a `BoundedInputStream`
  capped at, e.g., 2 MiB; reject early on `Content-Length` header.
  Configure `HttpServer` with a per-exchange read timeout. Add a
  `--max-request-body-bytes` flag (default 2 MiB). Surface a
  `configd_api_request_body_bytes` histogram for capacity planning.
- **Proposed owner:** `configd-server` / `HttpApiServer`

## SEC-003 — Inbound Raft `senderId` is attacker-controlled (no peer-id ↔ TLS-cert binding)
- **Severity:** S1
- **Location:** `configd-transport/src/main/java/io/configd/transport/TcpRaftTransport.java:222-223` (decode), `:243-249` (dispatch)
- **Category:** mtls
- **Evidence:**
  ```
  int senderId = in.readInt();
  NodeId from = NodeId.of(senderId);
  ...
  inboundHandler.accept(new InboundMessage(from, frame));
  ```
  The peer's NodeId arrives in-band from the socket; `setNeedClientAuth(true)`
  is enabled (`TcpRaftTransport.java:322`) but the certified principal
  from `SSLSession.getPeerPrincipal()` is never extracted, never compared
  to the claimed `senderId`, and never used to gate dispatch.
- **Impact:** A peer holding ANY trust-store-issued cert can spoof
  another peer's NodeId on every inbound frame — votes, heartbeats,
  AppendEntriesResponse, InstallSnapshotResponse — all dispatched as
  if from the spoofed NodeId. With even one compromised peer cert, an
  attacker disrupts elections, fabricates quorum acks (matchIndex
  advances against the wrong follower), and forges
  `RequestVoteResponse{voteGranted=true}` from a peer that did not
  vote. This *is* the S4 RED gate per ga-review.md, but I'm restating
  here because the failure mode is wider than the gate description
  suggests (it spans response messages too, not just identification on
  connect).
- **Fix direction:** Per gate S4 plan: extract
  `SSLSocket.getSession().getPeerCertificates()[0]` after handshake,
  derive expected `NodeId` from a configured CN/SAN→NodeId map (or
  encode NodeId as a SAN URI like `urn:configd:node:42`), and assert
  `senderId == certifiedNodeId` on EVERY inbound frame, not just
  connect. Reject + drop + increment `configd_transport_spoofed_sender_total`.
- **Proposed owner:** `configd-transport` / `TcpRaftTransport`

## SEC-004 — Snapshot `MAX_SNAPSHOT_ENTRIES = 100,000,000` allows decode-time amplification
- **Severity:** S1
- **Location:** `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:381`
- **Category:** fuzzing
- **Evidence:**
  ```
  private static final int MAX_SNAPSHOT_ENTRIES = 100_000_000;
  ```
  Combined with `MAX_SNAPSHOT_KEY_LEN = 1_048_576` and
  `MAX_SNAPSHOT_VALUE_LEN = 1_048_576`, an `InstallSnapshot` claiming
  100M entries triggers `new ArrayList`-equivalent allocations and
  forces the receiver into a loop the decoder can't bound (the actual
  byte budget is the InstallSnapshot chunk cap — 16 MiB — but the
  outer `entryCount` is read before the byte budget is consulted).
- **Impact:** A peer (legitimately certified, see SEC-003) can flood
  the receiver with snapshots that take O(entryCount) to validate
  before the byte-budget check fails. With 16 MiB chunks claiming 100M
  entries each, the receiver enters a CPU-bound parse loop that stalls
  the apply thread (single-threaded — see `ConfigStateMachine` doc) and
  starves consensus tick. This is decoder amplification: a small-bytes
  attacker causes large-CPU work.
- **Fix direction:** Cap `MAX_SNAPSHOT_ENTRIES` at the actual capacity
  the chunk size could carry (16 MiB / 16 bytes per entry header ≈
  1M). Add a check that `entryCount * MIN_ENTRY_BYTES <= remainingBytes`
  immediately after reading `entryCount`.
- **Proposed owner:** `configd-config-store` / `ConfigStateMachine`

## SEC-005 — Audit log absent — admin/write actions leave NO durable trail
- **Severity:** S1
- **Location:** `configd-server/src/main/java/io/configd/server/HttpApiServer.java:261-291` (handlePut), `:293-317` (handleDelete), and `configd-control-plane-api/src/main/java/io/configd/api/AdminService.java:63-100` (addNode/removeNode/transferLeadership)
- **Category:** audit-tamper
- **Evidence:** Neither handler nor `AdminService` writes to an
  append-only audit log. Only ad-hoc `System.err.println` /
  `LOG.warning` lines exist (`ConfigdServer.java:518`,
  `RaftTransportAdapter.java:61`). No hash-chain, no off-host shipping.
- **Impact:** S8 is RED in ga-review (deferred to v0.2 blocked on F3 HLC).
  This is an explicit reflag because *the runbooks
  (`ops/runbooks/disaster-recovery.md` and `poison-config.md`) instruct
  operators to "review the audit log" during incident response*. There
  is no audit log to review. Forensic story for "who deleted /v1/config/foo"
  is currently empty. After-incident attribution is impossible.
- **Fix direction:** Even ahead of full HLC integration, write a
  best-effort audit line per write/delete/admin to a separate
  `audit.log` file under `--data-dir/audit/` with `(wallTime, principal,
  method, key, version, sigHash)`. Hash-chain the file (each line's
  hash includes prev-line hash). v0.2 will tighten with HLC + off-host
  shipping. Document explicitly in disaster-recovery runbook that v0.1
  cannot reconstruct admin actions.
- **Proposed owner:** `configd-control-plane-api` / new `AuditLogger`

## SEC-006 — TLS `getSocketFactory().createSocket(host, port)` does NOT enforce a handshake timeout (slowloris)
- **Severity:** S2
- **Location:** `configd-transport/src/main/java/io/configd/transport/TcpRaftTransport.java:283-308` (`createClientSocket`); `:217-262` (`handleInboundConnection`) on the server side
- **Category:** api-surface / mtls
- **Evidence:** `factory.createSocket(host, port)` immediately connects;
  `socket.startHandshake()` is called without `setSoTimeout`, and the
  inbound handler does not call `setSoTimeout` either. There is no
  `acceptTimeout` on the `ServerSocket`. Read loop blocks indefinitely
  on `in.readInt()`.
- **Impact:** A network-position attacker opens a TCP connection to
  `:9090`, sends nothing (or a partial TLS ClientHello), and pins one
  virtual thread per connection forever. With virtual threads it isn't
  a thread-exhaustion DoS, but the `ConnectionManager` registry and
  socket FDs grow unbounded; per-process FD limits become the
  bottleneck. Same shape as a classic slowloris on the Raft port.
- **Fix direction:** Set `socket.setSoTimeout(handshakeTimeoutMs)` and
  call `setSoTimeout` again with a longer steady-state timeout after
  handshake. Add an idle-connection reaper. Wrap the accept loop with
  per-connection budget tracking.
- **Proposed owner:** `configd-transport` / `TcpRaftTransport`

## SEC-007 — Logged exception messages echo arbitrary peer-controlled bytes
- **Severity:** S2
- **Location:** `configd-transport/src/main/java/io/configd/transport/TcpRaftTransport.java:211, 256, 260`; `configd-server/src/main/java/io/configd/server/RaftTransportAdapter.java:61`
- **Category:** secret-leak
- **Evidence:**
  ```
  System.err.println("Failed to decode Raft message from " + from + ": " + e.getMessage());
  ```
  `e.getMessage()` for the codec includes raw values such as
  `"InstallSnapshot data length out of range: 1234567"` and
  `"Frame length mismatch: header says X but data is Y"` — both are
  attacker-controlled integers. SafeLog (`SafeLog.java`) exists but is
  not used here.
- **Impact:** Two issues: (1) attacker chooses log content, enabling
  log-injection (CRLF) into operator dashboards parsing stderr; (2)
  these messages can grow unbounded under attack and fill `/data/jvm.log`
  on the read-only-rootFS pod (only `/data` is writable — log churn
  fills the same PVC that holds Raft WAL → write stall). O7 (SafeLog)
  is GREEN per ga-review but is not actually applied on hot paths.
- **Fix direction:** Replace `System.err.println` with a `java.util.logging`
  Logger; sanitize via `SafeLog.isSafeForLog` before formatting; emit
  a metric `configd_transport_decode_failures_total{reason}` instead
  of free-text messages. Apply rate limiting via the existing
  cardinality guard.
- **Proposed owner:** `configd-transport` + `configd-observability`

## SEC-008 — `Files.readAllBytes(signing-key.bin)` then no zeroisation; key persists in JVM heap
- **Severity:** S2
- **Location:** `configd-config-store/src/main/java/io/configd/store/SigningKeyStore.java:77, 95-103`
- **Category:** crypto
- **Evidence:**
  ```
  byte[] bytes = Files.readAllBytes(path);
  ...
  byte[] privBytes = new byte[privLen];
  buf.get(privBytes);
  ...
  var priv = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
  ```
  `privBytes` and `bytes` are not zeroed; `keyPair.getPrivate().getEncoded()`
  is also dumped at write-time without zeroisation. No `MemorySegment`
  + explicit clear; relies entirely on GC. `docs/security-heap-dump-policy.md`
  exists but no JVM-arg/disable for `-XX:+HeapDumpOnOutOfMemoryError` in
  Dockerfile.runtime.
- **Impact:** A heap dump from `kill -3`, OOM (Dockerfile sets
  `+ExitOnOutOfMemoryError` but `-XX:HeapDumpOnOutOfMemoryError` isn't
  explicitly *off*), or `jcmd GC.heap_dump` recovers the Ed25519 private
  key plaintext. Image is signed for distribution-chain integrity; the
  signing key being recoverable from a routine memory snapshot defeats
  the chain.
- **Fix direction:** Add `-XX:-HeapDumpOnOutOfMemoryError` to
  `Dockerfile.runtime` ENTRYPOINT *and* the K8s StatefulSet command.
  Use `MemorySegment.ofArray` and explicit `Arrays.fill(privBytes,
  (byte) 0)` after `KeyFactory` consumes them. Document the procedure
  to integrate with KMS (HashiCorp Vault / GCP KMS / AWS KMS) for v0.2.
- **Proposed owner:** `configd-config-store` + `docker/Dockerfile.runtime`

## SEC-009 — TLS keystore password (PKCS12) silently accepts empty default
- **Severity:** S2
- **Location:** `configd-transport/src/main/java/io/configd/transport/TlsConfig.java:23-26, 39-43`
- **Category:** crypto / secret-in-repo
- **Evidence:**
  ```
  if (storePassword == null) {
      storePassword = new char[0];
  }
  ```
  And `TlsConfig.mtls(...)` *always* uses `new char[0]`. There is no
  CLI flag in `ServerConfig` to plumb a password from operator input;
  the constructed PKCS12 keystore is therefore unencrypted at rest.
- **Impact:** An attacker with read on `--tls-key` (e.g., the K8s
  Secret directory mounted at `/data` if cert rotation copies into the
  PVC, or any backup of the data dir) recovers the TLS private key
  plaintext. PKCS12 with an empty passphrase provides only obfuscation,
  no confidentiality. Standard ops practice (and PCI-DSS / SOC2
  expectations) is to require a passphrase.
- **Fix direction:** Add `--tls-keystore-password-file` flag. Load from
  file (POSIX 0600 check) into a `char[]`, then zero. Refuse to start
  if the keystore file is readable but the password is empty. Document
  in `ops/runbooks/cert-rotation.md`.
- **Proposed owner:** `configd-transport` / `configd-server`

## SEC-010 — `MetricsHandler` constant-time token comparison missing
- **Severity:** S2
- **Location:** `configd-server/src/main/java/io/configd/server/HttpApiServer.java:166-178` (uses `AuthInterceptor.authenticate`); the actual comparison is in `ConfigdServer.java:323-326` (uses `MessageDigest.isEqual` — good), but the `MetricsHandler.handle` reuses the same path without rate-limiting.
- **Category:** authn-authz
- **Evidence:** No `RateLimiter` is applied to `/metrics` or
  `/health/*`. Token comparison is constant-time, but timing-based
  *length* probes are still possible because `token.getBytes(...)` is
  a different length than `expectedToken.getBytes(...)` returns
  immediately false in `MessageDigest.isEqual` (Java's impl mixes
  length and content checks; see JDK-8210835).
- **Impact:** An off-path attacker can mount an unbounded probing loop
  against `/metrics` to learn the token length, then narrow to content.
  Combined with SEC-001 this is a smaller add-on, but still removes a
  defence layer.
- **Fix direction:** Apply the existing `RateLimiter` to `/metrics`
  (e.g., 10/s per source IP). Pad token to a fixed length before
  comparison (or HMAC the input once and compare digests). Document a
  minimum-token-length floor (e.g., 32 bytes) and reject shorter ones
  at startup.
- **Proposed owner:** `configd-server` / `HttpApiServer`

## SEC-011 — `tla2tools.jar` SHA pin matches v1.8.0 release artifact only — no signature/cosign-like attestation
- **Severity:** S2
- **Location:** `.github/workflows/ci.yml:60-66`
- **Category:** dep-provenance
- **Evidence:**
  ```
  TLA_TOOLS_SHA256: 4c1d62e0...
  curl -fL -o tools/tla2tools.jar https://github.com/tlaplus/.../tla2tools.jar
  echo "$TLA_TOOLS_SHA256  tools/tla2tools.jar" | sha256sum -c -
  ```
  The pin is an SHA-256 of the artifact at first download; if the
  upstream GitHub release is force-updated (rare but allowed) or the
  CDN is MITMed, the pin catches it — but there's no verification that
  the *original* artifact was actually published by the TLA+ project.
  No GPG signature check, no Sigstore attestation.
- **Impact:** First-fetch trust. If the original release was
  compromised before our pin was set, every subsequent CI run uses the
  malicious `tla2tools.jar` — and TLA+ tooling runs unsandboxed Java
  with full reactor read on the spec under test.
- **Fix direction:** Cross-reference the SHA-256 with the published
  release-asset checksum in the TLA+ project's announcement. Mirror
  the artifact under our own organization's package registry with our
  attestation chain. Document the original-publication trust source
  in an ADR.
- **Proposed owner:** repo / CI

## SEC-012 — No NetworkPolicy egress restriction on the API port to non-peer destinations
- **Severity:** S2
- **Location:** `deploy/kubernetes/configd-statefulset.yaml:198-239`
- **Category:** container
- **Evidence:**
  ```
  egress:
    - to: [{namespaceSelector: {}}]
      ports: [{protocol: UDP, port: 53}, {protocol: TCP, port: 53}]
    - to: [{podSelector: {matchLabels: {app: configd}}}]
      ports: [{protocol: TCP, port: 9090}]
  ```
  The DNS allow `to: [{namespaceSelector: {}}]` permits DNS to
  *any* namespace's pods, not specifically `kube-system`'s `kube-dns`.
  No restriction on which DNS *names* can be resolved; no egress to
  the audit log shipper (because there isn't one), to KMS endpoints,
  to OpenTelemetry collector (O8 stub), to the container registry for
  pull (covered at node level, but a Job in the same namespace would
  be unconstrained).
- **Impact:** A compromised configd pod can resolve arbitrary
  external hostnames via the in-cluster resolver, then exfiltrate
  via DNS-over-TCP responses, since DNS is the only outbound
  permission besides peer Raft. Standard data-exfil channel.
- **Fix direction:** Restrict DNS allow to `namespaceSelector:
  {matchLabels: {kubernetes.io/metadata.name: kube-system}},
  podSelector: {matchLabels: {k8s-app: kube-dns}}`. Add an explicit
  egress for the metrics scraper (Prometheus) only if push model is
  used — otherwise rely on ingress-only scraping.
- **Proposed owner:** `deploy/kubernetes`

## SEC-013 — `--enable-preview` in production runtime — bytecode covered by *experimental* preview-feature warranty
- **Severity:** S2
- **Location:** `docker/Dockerfile.runtime:69-75`, `deploy/kubernetes/configd-statefulset.yaml:80-92`
- **Category:** other
- **Evidence:** Both the Dockerfile ENTRYPOINT and the StatefulSet
  command pass `--enable-preview`. ga-review.md B12 acknowledges
  "ADR for `--enable-preview` in prod — drafted, not committed (low
  priority — no producibility risk)".
- **Impact:** Preview features are explicitly marked "may be modified
  or removed in a future release" by Oracle. A JDK 25.0.x patch could
  silently change semantics of a preview feature in use (e.g., string
  templates, structured concurrency). Production correctness depends
  on a JDK update policy that does not guarantee API stability for
  preview flags. This is *not* zero-risk; an emergency CVE patch JDK
  release could land semantic changes.
- **Fix direction:** Either (a) commit the ADR justifying preview-flag
  use and pin JDK *patch* version (not just `25-jre-noble`) in
  Dockerfile.runtime, OR (b) refactor to avoid preview features and
  drop the flag. Pinning the JDK image by digest already pins binary
  but not the EA→GA path.
- **Proposed owner:** `docker/Dockerfile.runtime` + ADR

## SEC-014 — `RaftMessageCodec.MAX_SNAPSHOT_CHUNK_BYTES = 16 MiB` per chunk × no per-peer aggregate cap
- **Severity:** S2
- **Location:** `configd-server/src/main/java/io/configd/server/RaftMessageCodec.java:48`
- **Category:** fuzzing
- **Evidence:** Each `InstallSnapshot` chunk is bounded at 16 MiB,
  but a peer (with valid TLS, see SEC-003) can stream millions of
  chunks back-to-back. There is no aggregate-bytes cap per peer per
  unit-time, no fan-in throttle on the receiver.
- **Impact:** A misbehaving leader (or a NodeId-spoofing attacker)
  exhausts disk on the receiving follower by streaming endless
  InstallSnapshot chunks; the receiver's WAL grows past PVC quota
  (10 GiB in the manifest) and the node goes read-only. Recovery
  requires PVC expansion. Not exploitable without leader compromise,
  but defence-in-depth is missing.
- **Fix direction:** Add a per-peer rate limiter on InstallSnapshot
  acceptance (bytes/sec) on the receiver. Reject chunks that would
  exceed `lastIncludedIndex - currentIndex` * average-entry-size by
  more than 4×.
- **Proposed owner:** `configd-server` / `RaftMessageCodec` consumers

## SEC-015 — `printStackTrace(System.err)` in tick loop leaks stack frames containing config values
- **Severity:** S3
- **Location:** `configd-server/src/main/java/io/configd/server/ConfigdServer.java:519`
- **Category:** secret-leak
- **Evidence:**
  ```
  System.err.println("CRITICAL: Exception in tick loop (continuing): " + t);
  t.printStackTrace(System.err);
  ```
- **Impact:** Stack traces from the apply path can include
  `IllegalArgumentException` messages embedding config keys or values
  (e.g., from `CommandCodec`). Per existing SafeLog policy these
  should be redacted. Same secondary issue as SEC-007 but on a
  different code path.
- **Fix direction:** Use a Logger; pass `t` as the throwable parameter
  so the formatter can apply SafeLog. Strip user-supplied bytes from
  the message field before logging.
- **Proposed owner:** `configd-server`

## SEC-016 — `HttpsConfigurator` default — TLS 1.2 ciphers may be enabled by JDK default
- **Severity:** S3
- **Location:** `configd-server/src/main/java/io/configd/server/HttpApiServer.java:67-69`
- **Category:** mtls
- **Evidence:**
  ```
  HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(port), 0);
  httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
  ```
  Unlike `TcpRaftTransport.createServerSocket`, the HTTPS API does
  *not* override `configure(HttpsParameters)` to call
  `setEnabledProtocols({"TLSv1.3"})` and `setEnabledCipherSuites(...)`.
  The JDK default for `SSLContext.getInstance("TLSv1.3")` allows
  TLSv1.2 fallback (depending on JDK); for v0.1 the same TlsConfig
  is shared but the HTTP server doesn't apply protocol/cipher pins.
- **Impact:** API surface may negotiate TLS 1.2 with weaker cipher
  suites permitted by JDK defaults. The Raft port pins TLS 1.3 only;
  the API port doesn't, creating an inconsistency.
- **Fix direction:** Subclass `HttpsConfigurator` to override
  `configure(HttpsParameters params)` and explicitly set protocols and
  cipher suites matching `TlsConfig.mtls`. Add a regression test that
  asserts `Set.of("TLSv1.3").equals(actualProtocols)`.
- **Proposed owner:** `configd-server` / `HttpApiServer`

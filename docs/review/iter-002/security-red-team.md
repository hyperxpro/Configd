# security-red-team — iter-002
**Reviewer:** security-red-team
**Iteration:** 2
**Severity floor:** S3
**HEAD:** `22d2bf3` + uncommitted iter-1 fixers
**Findings:** 11

Note on scope. iter-1 already filed: SEC-001 argv token, SEC-002 unbounded
body, SEC-003 spoofed senderId, SEC-004 snapshot entry-count amplification,
SEC-005 audit log, SEC-006 handshake setSoTimeout, SEC-007 logged peer
bytes, SEC-008 key zeroisation / heap-dump, SEC-009 PKCS12 empty pwd,
SEC-010 metrics token-length probing, SEC-011 tla2tools provenance,
SEC-012 DNS-egress wildcard, SEC-013 `--enable-preview` ADR, SEC-014
InstallSnapshot per-peer aggregate, SEC-015 tick-loop printStackTrace,
SEC-016 HTTPS `setEnabledProtocols`. Items already RED/YELLOW in
`docs/ga-review.md` (S1/S2/S4/S5/S6/S7/S8, B6/B12, O7/O8) are not
re-counted. Findings below are NEW gaps that escaped iter-1 or that
the iter-1 closure inadvertently widened.

---

## SEC-017 — Edge `highestSeenEpoch` is in-memory only; epoch replay protection (F-0052) collapses on edge restart
- **Severity:** S1
- **Location:** `configd-edge-cache/src/main/java/io/configd/edge/DeltaApplier.java:86, 95-102, 196-198`
- **Category:** crypto / replay
- **Evidence:**
  ```
  /** F-0052: highest epoch seen in a successfully verified delta. */
  private long highestSeenEpoch;     // <- plain field, never persisted
  ...
  this.highestSeenEpoch = 0L;        // ctor resets to zero on every JVM start
  ...
  if (delta.epoch() > highestSeenEpoch) { highestSeenEpoch = delta.epoch(); }
  ```
  D-004 closed the *server-side* gap (snapshot now carries `signingEpoch`
  trailer so a NEW LEADER cannot regress epochs after `InstallSnapshot`).
  Mirror gap on the *edge*: `DeltaApplier` recomputes `highestSeenEpoch`
  from a fresh `0L` on every process start. There is no persistence
  hook, no `LocalConfigStore` sidecar, no envelope inside `EdgeConfigClient`.
- **Attack scenario (concrete):** Attacker on the broadcast network
  records a signed delta `D = {epoch=42, mutations=[put k=v_old]}`
  produced at time T0. After legitimate updates at the leader move
  the system to `epoch=100, k=v_new`, the edge reaches that state
  too and `highestSeenEpoch = 100`. The attacker waits for an edge
  restart (deploy, OOM, K8s rolling update). At T1 the edge boots,
  `highestSeenEpoch` is reset to 0, and the attacker re-injects the
  recorded `D`. Signature still valid (Ed25519 over an unchanged
  payload), epoch 42 > 0, gap-detection check passes if the edge had
  not yet caught up to fromVersion. The edge briefly serves the
  rolled-back value `v_old` until the next legitimate delta arrives.
  This is a textbook signed-but-replayable window — the very threat
  F-0052 was designed to close.
- **Fix direction:** Persist `highestSeenEpoch` alongside the local
  snapshot. Two options: (a) embed in `LocalConfigStore.loadSnapshot`'s
  durable state (analogous to D-004 on the leader), or (b) write a
  separate `epoch.lock` file with `[8B epoch][4B CRC32C]` that
  `DeltaApplier` reads in its constructor. Add a regression test
  `epochReplayRejectedAcrossRestart` that round-trips through a
  serialise / deserialise cycle. Document in
  `docs/decisions/adr-0027-sign-or-fail-close.md` as the symmetric
  edge-side requirement.
- **Proposed owner:** `configd-edge-cache` / `DeltaApplier` +
  `EdgeConfigClient` durability surface

## SEC-018 — `signCommand` mutates store before signing; signing throw aborts listener notification but leaves store mutated → fan-out skipped silently
- **Severity:** S1
- **Location:** `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:253-272` (Put case), `:274-285` (Delete), `:286-297` (Batch); throw site `:567-571`
- **Category:** authn-authz / consistency
- **Evidence:** Apply order in every mutating case is:
  1. `store.put(...)` / `applyBatch(...)`  — mutates the persistent state
  2. `signCommand(command)`               — increments `signingEpoch`, calls `signer.sign(...)`
  3. `notifyListeners(...)`               — fan-out to FanOutBuffer + WatchService
  S3 (PA-1004) "fail-close" raises `IllegalStateException` from step 2
  if the signer is verify-only or `GeneralSecurityException` is wrapped.
  The listener step is *never reached*. The throw propagates out of
  `apply`, `commitSucceeded` stays `false`, `onWriteCommitFailure()`
  fires, and Raft treats it as a hard fault. **But the store was
  already mutated in step 1.** A subsequent successful apply (after
  the operator restores the signing key) will continue from the
  mutated state, but the original mutation has *no `ConfigDelta`
  emitted* and *no `WatchService.onConfigChange` event*. Edges will
  never receive that mutation as a delta — they will only observe
  it after the next snapshot install, which can be hours away.
- **Attack scenario:** An attacker who can transiently DoS the signer
  (e.g., flood the JCE provider with bogus operations on a shared
  key-handle, force `Provider.getService` lookups to fail, or — more
  realistically — wait for a misconfiguration where the operator
  rotates to a verify-only key) causes apply to take the fail-close
  branch *after* `store.put`. The stale-state window is bounded by
  the next snapshot install but observable from the attacker's edge
  for the duration. More important: this is also a *correctness*
  failure mode — an operator-induced verify-only-key misconfig
  causes silent edge divergence with no metric surfaced for the
  divergence itself (`writeCommitFailed` increments but the *delta
  loss* has no signal).
- **Fix direction:** Reorder apply: sign **before** mutating. Compute
  the canonical payload + signature first, then `store.put`, then
  `notifyListeners`. If signing throws, return without mutating the
  store. Alternatively, wrap step 1+2+3 in a single atomic unit and
  on throw rewind the store via `store.restoreSnapshot(snapBefore)` —
  but that costs a snapshot allocation per commit and is the wrong
  trade-off. The ordering swap is essentially free.
- **Proposed owner:** `configd-config-store` / `ConfigStateMachine.apply`

## SEC-019 — Inbound Raft frame `senderId` accepts any `int`; no membership check before allocation
- **Severity:** S2
- **Location:** `configd-transport/src/main/java/io/configd/transport/TcpRaftTransport.java:222-240` (`handleInboundConnection`); membership reference: `peerAddresses` map at construction time
- **Category:** api-surface / fuzzing
- **Evidence:**
  ```
  int senderId = in.readInt();         // any int32 accepted
  NodeId from = NodeId.of(senderId);   // no membership check
  int frameLength = in.readInt();
  if (frameLength < minFrame || frameLength > 16 * 1024 * 1024) { ... }
  byte[] frameBytes = new byte[frameLength];   // up to 16 MiB allocation
  ```
  This is the *non-cert-binding* layer of the same problem SEC-003
  flagged. SEC-003 is about a peer with a valid trust-store cert
  spoofing *another* peer's NodeId; SEC-019 is one level lower — a
  peer can send `senderId = 0xDEADBEEF` for a NodeId that *was never
  configured* and the receiver still buffers a 16 MiB frame for it.
  Combined with the absence of `setSoTimeout` (iter-1 SEC-006), an
  attacker who can pass mTLS but is not a configured peer can pin
  unlimited socket descriptors at 16 MiB each.
- **Attack scenario:** Cluster of three nodes `{1, 2, 3}`. Operator
  accidentally trusts the corporate CA (broad trust store) when
  configuring the Raft trust store. A laptop on the same VPN with
  any cert from that CA opens a TCP+TLS session to port 9090 and
  streams `[senderId=999][frameLength=16777216][zeros...]`. The
  receiver allocates a 16 MiB byte[] for an unknown-peer message,
  decodes it as a malformed frame (CRC32C closes the connection),
  but the laptop reconnects immediately. Per-attacker amplification:
  one TCP SYN ≈ 16 MiB heap and one short virtual-thread stack.
- **Fix direction:** Reject the connection at the first `readInt` if
  `!peerAddresses.containsKey(NodeId.of(senderId))`. Increment
  `configd_transport_unknown_peer_total{senderId=$bucketed}`
  (use `SafeLog.cardinalityGuard` to bound label cardinality). Pair
  with iter-1 SEC-003 (cert→nodeId binding) and SEC-006 (handshake
  timeout) for full coverage.
- **Proposed owner:** `configd-transport` / `TcpRaftTransport`

## SEC-020 — `SafeLog` exists but has zero production call-sites; O7 demoted-YELLOW understates the gap
- **Severity:** S2
- **Location:** `configd-observability/src/main/java/io/configd/observability/SafeLog.java`
  (helper); call-sites: **none in production code**, verified via
  `grep -rn 'SafeLog\.' --include='*.java' src/main` (only test
  imports under `configd-observability/src/test/`)
- **Category:** secret-leak
- **Evidence:** `grep -rn 'SafeLog\.' configd-*/src/main` returns
  zero matches. The helper is wired in tests only. The hot-path
  formatters that *should* use it — log lines in
  `TcpRaftTransport.handleInboundConnection` (`peer_id` + raw
  hex CRC), `RaftTransportAdapter.registerInboundHandler`
  (`from + ": " + e.getMessage()`), `ConfigdServer:582` (tick
  exception with a free-form `Throwable.toString()`), the new
  `MetricsHandler` 401 path (`"Unauthorized: " + denied.reason()`)
  — all bypass SafeLog and emit raw peer / token / config-key
  bytes directly into the structured-log JSON pseudo-format.
- **Impact:** ga-review.md retains O7 as YELLOW with reason
  "test count was overstated as 17 in the original gate annotation;
  helper itself is sound". The actual residual is much wider:
  the helper is **not used anywhere in production**. PA-5012/13/19
  ("must never log raw config values, raw client IPs, raw auth
  tokens, raw config keys with unbounded cardinality") is still
  violated by the four call-sites listed above. An attacker who
  controls peer-id strings, exception messages, or denied-token
  reasons can inject CRLF + ANSI escapes into operator log
  pipelines and (per SEC-007 in iter-1) can spam the `/data` PVC
  to write-stall the WAL.
- **Fix direction:** Concrete wiring tasks (one PR per call-site is
  fine):
  1. `TcpRaftTransport:248,260` — replace inline JSON with a
     `Logger` call; sanitize `from.toString()` via
     `SafeLog.isSafeForLog` (NodeId is `int`, always safe), and
     keep CRC values as already-numeric.
  2. `RaftTransportAdapter:61` — switch to `Logger.warning` and
     emit `peerId=<int>, error=<class-name>` instead of
     `e.getMessage()`. Surface a counter
     `configd_transport_decode_failures_total{messageType}`.
  3. `ConfigdServer:582-583` — use `LOG.log(Level.SEVERE, "...", t)`
     so the Throwable-aware formatter applies; never emit
     `t.toString()` directly.
  4. `HttpApiServer.MetricsHandler:202` — return a constant
     `"Unauthorized"` body (or `{"error":"unauthorized"}`); the
     `denied.reason()` value is operator-debugging only and should
     be on a metric, not in a 401 body.
  Promote O7 to GREEN only after these four are landed.
- **Proposed owner:** `configd-observability` (helper) +
  `configd-transport`, `configd-server` (call-sites)

## SEC-021 — Trivy CI step has `ignore-unfixed: true`; HIGH/CRITICAL CVEs without upstream fix silently pass
- **Severity:** S2
- **Location:** `.github/workflows/ci.yml:108-116` (`supply-chain-scan` job)
- **Category:** dep-provenance
- **Evidence:**
  ```
  - name: Trivy filesystem scan
    uses: aquasecurity/trivy-action@0.20.0
    with:
      ...
      severity: HIGH,CRITICAL
      exit-code: '1'
      ignore-unfixed: true   # <-- silently drops CVEs without a fix
  ```
- **Impact:** A CVE that has no available patch (`Status: affected,
  no fix available`) is exactly the class an operator most needs to
  see — there is no automatic remediation, so it must be manually
  mitigated (workaround, isolation, or pinning the dep down). With
  `ignore-unfixed: true` the CI shows GREEN for vulnerable code
  shipping into production. Combined with the absence of dependabot
  (`.github/dependabot.yml` does not exist), the supply-chain story
  is "we'll fail when an upstream patch lands"; for unpatched
  vulnerabilities, no signal at all.
- **Fix direction:** Set `ignore-unfixed: false` and route the
  unfixed-but-HIGH set into a separate informational SARIF upload
  via `format: sarif` + `actions/upload-sarif`, gated by a manual
  `triage-required` review. Add `.github/dependabot.yml` with
  `package-ecosystem: maven` daily on `main` so dependency drift
  is auto-PR'd. Document the policy in `docs/decisions/`.
- **Proposed owner:** repo / CI

## SEC-022 — `gitleaks` allowlist uses unanchored substring regex; secrets next to "cosign verify" or "gh attestation verify" are silently allowed
- **Severity:** S2
- **Location:** `.gitleaks.toml:35-37`
- **Category:** secret-leak / dep-provenance
- **Evidence:**
  ```
  regexes = [
      '''4c1d62e0f67c1d89f833619d7edad9d161e74a54b153f4f81dcef6043ea0d618''',
      '''4ec3f26f55ed59b40d9b5d7eb24cd936bb96e2c34e2e9a3a0a8d9e0f1a2b3c4d''',
      '''(?i)gh attestation verify''',
      '''(?i)cosign verify''',
  ]
  ```
  The `(?i)gh attestation verify` and `(?i)cosign verify` patterns
  are unanchored substring matches against the *full file content*.
  gitleaks treats any commit blob containing those substrings as
  allowlisted **for the entire blob**, not just for the matched
  region. A documentation page that says "after running `cosign
  verify`, paste your private signing key here:" with the literal
  PEM block on the next line is allow-listed wholesale.
- **Attack scenario:** Insider opens a PR titled "improve cosign
  docs" that prepends a runbook section containing `cosign verify`
  near the top of `ops/runbooks/release.md`, then commits a real
  PEM private key further down the same file. gitleaks scans the
  blob, hits the allowlist regex, marks the entire blob clean.
  Reviewer sees a docs-only PR title and approves. The key lands.
  Trivy `secret` scanner *might* catch it, but Trivy uses different
  rules than gitleaks (the iter-1 commentary says the two are
  belt-and-braces — that protection is broken here).
- **Fix direction:** Replace the two doc-allowlist regexes with
  `path`-scoped allowlists for `ops/runbooks/release.md` and
  `docs/runbooks/cert-rotation.md` only, and within those files
  use anchored *line* regexes (gitleaks supports `match-pattern`
  per finding category). Or remove the allowlist entries and
  rewrite the doc lines to avoid the literal phrase.
- **Proposed owner:** repo / `.gitleaks.toml`

## SEC-023 — `escapeJson` does not escape U+0000–U+001F (other than \n\r\t); `<`/`>`; or U+2028/U+2029
- **Severity:** S3
- **Location:** `configd-server/src/main/java/io/configd/server/HttpApiServer.java:429-436`
- **Category:** api-surface / log-injection
- **Evidence:**
  ```
  return value.replace("\\", "\\\\")
               .replace("\"", "\\\"")
               .replace("\n", "\\n")
               .replace("\r", "\\r")
               .replace("\t", "\\t");
  ```
  Missing: `\b` (U+0008), `\f` (U+000C), the rest of U+0000–U+001F,
  and the JS-string-terminator surrogates U+2028 / U+2029. Today
  the only call-sites are `HealthService.CheckResult.name/detail`
  which are operator-controlled — but the helper is general-purpose
  and the contract suggests "JSON-safe", which it is not.
- **Impact:** Today: minimal — health checks are operator-defined.
  Tomorrow: any call-site that lifts user-controllable bytes into
  `escapeJson(...)` (e.g., a future `/v1/diagnostics` endpoint that
  echoes a request label, or a multi-tenant readiness check that
  reports "tenant=$rawTenantId not ready") will silently produce
  parse-broken JSON, or — worse — JSON that is well-formed under
  some parsers and not others (parser differential), which is the
  classic primer for HTTP-desync-ish attacks against downstream
  log shippers.
- **Fix direction:** Use `\u%04x` for any code point < 0x20 not
  already in the {n,r,t} set. Optionally also escape `<`, `>`,
  `&`, U+2028, U+2029 for embedding-in-HTML safety. There are
  small libs (Jackson's `JsonStringEncoder`) but for a 30-line
  helper, hand-rolling is fine.
- **Proposed owner:** `configd-server` / `HttpApiServer`

## SEC-024 — Health endpoints are unauthenticated and disclose `leaderId` to anyone who can reach `:8080`
- **Severity:** S3
- **Location:** `configd-server/src/main/java/io/configd/server/HttpApiServer.java:100-101` (handlers); `ConfigdServer.java:396-402` (`raftNode.leaderId()` is invoked by readiness check)
- **Category:** authn-authz
- **Evidence:** `MetricsHandler` was hardened in F-0055 to require
  `Bearer` auth when `authInterceptor != null`, on the rationale
  that "metrics exposition can leak operational state (leader
  identity, follower lag, key cardinality)". The same data is
  exposed unauthenticated through `/health/ready`'s
  `formatHealthStatus(...)` body — `"raft-leader: no leader
  elected"` or the leader's NodeId is rendered into JSON detail.
- **Impact:** A network-position attacker who can reach `:8080`
  (even without a token) can poll `/health/ready` to discover
  current leader identity, infer election rounds, and detect
  outages well before Prometheus alerts fire. This is exactly
  the threat model that motivated SEC-001's metric-protection
  posture. Either both should be public or both should require
  auth — the current asymmetry is a defence-in-depth gap.
- **Fix direction:** Two acceptable solutions: (a) strip
  `raft-leader` detail from the `readiness()` response body
  (return only `healthy: true/false`), and surface the leader
  hint via the existing `/metrics` (already auth-gated); or (b)
  add an opt-in `--health-auth-required` flag that gates
  `/health/*` behind the same Bearer check. K8s liveness probe
  can be wired to a separate `/internal/live` on `:9090`-side
  that's network-policy isolated.
- **Proposed owner:** `configd-server` / `HttpApiServer` +
  `configd-control-plane-api` / `HealthService`

## SEC-025 — `MetricsHandler` 401 echoes the validator's `denied.reason()` to any unauthenticated probe
- **Severity:** S3
- **Location:** `configd-server/src/main/java/io/configd/server/HttpApiServer.java:200-203`
- **Category:** authn-authz
- **Evidence:**
  ```
  if (authResult instanceof AuthInterceptor.AuthResult.Denied denied) {
      exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
      sendResponse(exchange, 401, "Unauthorized: " + denied.reason());
      ...
  ```
  `AuthInterceptor.AuthResult.Denied("missing auth token")`
  vs `Denied("invalid token")` — two distinct messages distinguish
  "no token sent" from "token sent but wrong". An off-path
  attacker can use the difference to confirm which header form
  the server expects, narrow down to `Authorization: Bearer ...`,
  and probe with the iter-1 SEC-010 timing/length channel.
- **Impact:** Mild user-enumeration analogue (server-side: confirms
  whether a token was sent, useful for distinguishing authenticated
  pentest from unauthenticated reconnaissance).
- **Fix direction:** Constant 401 body: `"Unauthorized"`. Move the
  reason to a `LOG.fine(...)` line and a metric
  `configd_api_auth_denials_total{reason=missing|invalid}`.
- **Proposed owner:** `configd-server` / `HttpApiServer`

## SEC-026 — `--peer-addresses` parsed with `InetSocketAddress(host, port)` — eager DNS resolution at startup, no SAN cross-check vs `hostname` later
- **Severity:** S3
- **Location:** `configd-server/src/main/java/io/configd/server/ServerConfig.java:194-216`; consumer `TcpRaftTransport.java:318-319` uses `address.getHostString()` for SNI
- **Category:** mtls / api-surface
- **Evidence:** `parsePeerAddresses` calls `new InetSocketAddress(hostPort[0], port)` which performs *eager* DNS resolution. The resolved address is stored. On every reconnect, `createClientSocket` uses `address.getHostString()` for SNI but the resolved IP for the actual TCP connect (the `InetSocketAddress` already has a fixed `InetAddress`). If DNS changes mid-flight (re-IP of a peer pod), the client keeps connecting to the stale IP, which may now belong to *a different pod with a valid cluster trust-store cert*. The SNI hostname check still passes ("HTTPS" identification on `getHostString()`), so cert-mismatch isn't surfaced.
- **Impact:** In a Kubernetes deployment where pods are recreated and StatefulSet headless service IPs rotate, an attacker that obtains a peer cert (any cert in the trust store) and lands on the old IP can MITM Raft traffic from peers that haven't restarted recently. The SNI name still matches because `getHostString()` returns the original DNS name string from `parsePeerAddresses`. Hostname identification verifies SAN against the SNI string, *not* against the resolved IP. So a swapped backend with a valid SAN for any peer hostname will pass.
- **Fix direction:** Construct `InetSocketAddress.createUnresolved(hostPort[0], port)` so DNS is re-resolved per connect attempt; pair with `setEndpointIdentificationAlgorithm("HTTPS")` (already set) so SAN is checked against the *fresh* hostname. Even better: pin the expected NodeId → cert SAN URI mapping (`urn:configd:node:N`) so SAN identity is the strict authentication source — see iter-1 SEC-003 for the full fix path.
- **Proposed owner:** `configd-server` / `ServerConfig` + `configd-transport` / `TcpRaftTransport`

## SEC-027 — `RaftMessageCodec.MAX_SNAPSHOT_CHUNK_BYTES = 16 MiB` exceeds the JDK HttpServer default `Content-Length` heap behaviour, but the *real* gap is no `MAX_INSTALL_SNAPSHOT_TOTAL_BYTES` per session
- **Severity:** S3
- **Location:** `configd-server/src/main/java/io/configd/server/RaftMessageCodec.java:48`; receiver `ConfigStateMachine.restoreSnapshotInternal:394`
- **Category:** fuzzing / api-surface
- **Evidence:** SEC-014 (iter-1) flagged the per-chunk cap. Restating
  for clarity: even with the 16 MiB chunk cap and the 100 M
  `MAX_SNAPSHOT_ENTRIES`, the *aggregate* `data` array assembled
  across `offset` updates has no ceiling. `restoreSnapshotInternal`
  consumes the assembled bytes through `ByteBuffer.wrap(snapshot)`;
  whoever assembles the chunks (the leader-side `SnapshotTransfer`
  in the replication engine and the follower-side accumulator) needs
  a per-session aggregate cap.
- **Impact:** Restated from SEC-014 — included here only because
  iter-1's fix direction emphasised per-peer rate limiting, which
  addresses *throughput* but not *per-session memory*. A
  single-shot 4 GiB snapshot still passes if the aggregate cap is
  absent, even with rate limiting at 256 chunks/sec.
- **Fix direction:** Add `MAX_INSTALL_SNAPSHOT_BYTES = 1 GiB`
  (configurable) and abort with a logged `snapshot_oversize`
  metric when the cumulative `offset + dataLen` exceeds it.
  The legitimate cluster snapshot ceiling is bounded by the
  configured `MAX_SNAPSHOT_ENTRIES * MAX_SNAPSHOT_VALUE_LEN ≈
  100 TB`, which the 1 GiB practical cap dwarfs while still
  fitting on PVC.
- **Proposed owner:** `configd-replication-engine` / `SnapshotTransfer`

---

## Summary

11 findings: **0 S0**, **2 S1** (SEC-017 edge replay, SEC-018 sign-after-mutate), **5 S2** (SEC-019 unknown-peer alloc, SEC-020 SafeLog unwired, SEC-021 trivy ignore-unfixed, SEC-022 gitleaks unanchored allowlist, SEC-026 eager DNS), **4 S3** (SEC-023 escapeJson gaps, SEC-024 health-leader-disclosure, SEC-025 401 reason echo, SEC-027 snapshot aggregate cap).

The two S1s are genuinely new and missed by iter-1 — both are
*consequences* of partially-landed F-0052 / D-004 work where the
state-machine side was hardened but the symmetric edge / ordering
piece was not. iter-1 closure of the obvious holes pushed the
remaining attack surface into apply-ordering and edge-side
persistence, which is exactly where SEC-017 and SEC-018 sit.

The five S2s are mostly defense-layer integrity — `SafeLog` exists
but is not used (SEC-020); `Trivy` is loud-by-default but quiet on
unfixed (SEC-021); `gitleaks` allowlist is too broad (SEC-022). The
remaining S3s are honesty-of-error-surface issues that would matter
once a real customer audit looks at the 401 / health body texts.

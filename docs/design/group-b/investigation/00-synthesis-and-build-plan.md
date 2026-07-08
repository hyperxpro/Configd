# Group B — Investigation Synthesis + Build Plan

Status: **Investigation phase COMPLETE** (all 7 findings §2.1–2.7 delivered, read-only,
uncommitted). This document reconciles the seven findings, records the calls taken on every
fork under the arc rule *"safest, industry-standard call on every fork,"* and isolates the
decisions that are genuinely the operator's. It is the input the gate builds conform to.

The seven findings live beside this file:

| # | Finding | Agent | Headline |
|---|---------|-------|----------|
| §2.1 | `01-vault-kms.md` | inv-vault | Vault **Transit** as seal-custodian; **AppRole** default; hand-rolled `java.net.http` client |
| §2.2 | `02-oauth-oidc-resource-server.md` | inv-oidc | **nimbus-jose-jwt**; validate RFC 9068 access token; explicit `{RS256,ES256}` allowlist; JWKS-rotation cache; **HTTP-plane-only v1** |
| §2.3 | `03-netty-auth-pipeline.md` | inv-netty | Auth gate after decoder / before business handler; `AuthState` channel attr; **`ctx.executor().schedule`** one-shot expiry tick |
| §2.4 | `04-node-join-gate.md` | inv-nodejoin | Node-join = **`PeerIdentityPolicy` strengthened** (fail-closed default + optional separate peer CA); OIDC/Basic interior markers **dormant** |
| §2.5 | `05-unified-credential-expiry.md` | inv-expiry | Lead-time window (token 0.20/30s/5m, cert 0.10/5m/1h); revocation `off\|lax\|strict` default **off**; **inter-node exempt** from strict (OCSP-lockout foot-gun) |
| §2.6 | `06-leadership-auto-balance.md` | inv-autobalance | **Decentralized per-leader-shed** loop (no coordinator); metric max−min leader spread, act at **≥2**; 30s jittered; leader-view derivable locally |
| §2.7 | `07-upgrade-capability.md` | inv-upgrade | **Door open**; 1 fix (`PolicySerializer` `_acl/format` sentinel); Raft plane has no in-band negotiation (blue/green until a cluster-wire-version descriptor is built) |

---

## 1. The reconciling model: three planes, not two

The findings partly conflicted because the charter's "both planes" language collapsed **three**
distinct trust surfaces. Separating them dissolves the apparent conflict:

| Plane | Direction | v1 authentication | Token AUTH frame |
|-------|-----------|-------------------|------------------|
| **Raft interior** | node ↔ node | **mTLS only** — node-join = `PeerIdentityPolicy` allow-list, strengthened to fail-closed-by-default + optional separate peer CA | none; OIDC/Basic node-markers written + reject-tested but **dormant** (no token wire path to the interior exists — this is a genuine correctness boundary, built + documented, not deferred) |
| **Edge / M2M binary** | client ↔ server (fan-out) | mTLS **and/or** a token AUTH frame — **this is the arc's central fork (§3 below)** | *decision pending* |
| **HTTP / REST control** | client ↔ server (HTTP) | four modes: No-Auth (loud) / HTTP Basic / OAuth2-OIDC / mTLS | n/a — request-shaped, per-request bearer |

`inv-nodejoin` confirmed the edge/fan-out plane is **client-only and never routes to a
`RaftNode`**, so token auth there is ordinary client auth (etcd's client gRPC plane supports
token auth beside mTLS — this is standard), *not* an interior door. The interior stays mTLS-only.

---

## 2. Calls taken (safest-industry-standard; operator may override any)

These are resolved per the arc rule; each is grounded in a finding's primary-source research.

**Discovery / SPI plumbing**
- **Build a `ServiceLoader`-based hybrid discovery** (name-selection + `ServiceLoader`, fail-loud,
  never silent downgrade) **once** in Gate 1/2 and reuse it for both `KmsProviderFactory` and
  `AuthenticatorFactory`. Correction to an old memory: there is **no `ServiceLoader` in `src/main`
  today** (0 hits) — this is new, not a mirror of an existing one. `NettyTransport.select()`
  (name-only) is the *fail-loud posture* to mirror, not the mechanism.
- **The KMS SPI is not wired into boot today** — `ConfigdServer.java:1452` only name-checks
  `provider=="local"` and constructs no `KmsProvider`; keyring keys are derived straight from the
  signing key (`:1468`); `SegmentKeyManager.unsealFrom(KmsProvider,WrappedKey)` (`:185`) is the
  intended-but-bypassed call site. Genuinely wiring the SPI into boot (for `local` **and** Vault)
  is Gate 7's load-bearing job; the `local` provider must go through the same seam.

**Authenticator SPI (Gate 2)**
- Three outcomes: `Authenticated(Principal)` / `Denied` / **`Unavailable`** (503, retryable) —
  add `AuthResult.Unavailable` or a checked `AuthnUnavailableException` mirroring
  `KmsUnavailableException`. Both `inv-oidc` (OQ-1) and `inv-netty` require it; today's
  `AuthInterceptor.AuthResult` has only `{Authenticated, Denied}` and always 401s on failure.
- Chain order **`mtls, oidc, bearer`** (static bearer is a hard-rejecting catch-all → must be last,
  else it silently disables OIDC).
- `Principal` = typed immutable (id + Configd roles + attributes + provenance), **never carries the
  credential**; authZ stays in-core and consumes only Configd roles.
- Keep `AuthInterceptor` as a **thin bearer adapter** over the new SPI (lower byte-identity risk)
  rather than deleting it (inv-netty O-5).

**OIDC (Gate 6)**
- Library **`com.nimbusds:nimbus-jose-jwt`** in a new optional module `configd-authn-oidc`; core
  never compile-deps it. Hand-rolling rejected (RFC 8725 §2.1 — the repo "no-deps" rule is about
  wire framing, not crypto).
- Validate the **RFC 9068 access token** (not the id_token). Explicit alg allowlist `{RS256,ES256}`;
  reject `alg:none` and all `HS*`; per-`kid` JWKS key selection typed to one asymmetric alg
  (alg-confusion defense); `iss` exact-match; `aud` must contain the configured value; `exp`/`nbf`
  with ≤60s leeway.
- **JWKS-rotation cache**: positive TTL by `kid` (10m/honor max-age) + refresh-ahead + rate-limited
  refresh-on-unknown-`kid` (≥30s cooldown) + negative cache (30s) + serve-stale-if-warm / fail-closed-
  if-cold + fetch bounds (https-only, ≤64KiB, ≤20 keys, ignore `jku`/`x5u` = SSRF). Nimbus
  `JWKSourceBuilder` implements this.
- **Claims→roles mapping configurable per issuer**, default `[]` = deny; supports dotted/nested paths
  and array | space-delimited value types (covers `scope`/`scp`).
- **Admin-UI = delegated** — Configd builds zero redirect/callback/PKCE (SPA-PKCE-in-browser default;
  reverse-proxy for enterprise). PKCE is verified by the IdP token endpoint (RFC 7636 §4.6).
- No per-connection bearer cache in v1 (revocation-latency cost) (inv-netty O-3).

**Netty pipeline (Gate 3)**
- Edge order: `SslHandler → ByteToEdgeFrameDecoder → EdgeFrameToByteEncoder → EdgeAuthGateHandler →
  FanOutConnection`. Gate sits **after** the decoder (sees only bounds-checked frames) and **before**
  the business handler.
- HTTP: **no new handler** — generalize `AdminApiHandler.checkAuth` to call the shared SPI.
- **Expiry tick = `ctx.executor().schedule(...)`** one-shot on the channel's own event loop,
  cancel + re-arm on REFRESH_AUTH (verbatim the WH-11 first-frame-deadline idiom). Reject
  `HashedWheelTimer` (wrong thread, "do not create many instances") and `IdleStateHandler` (measures
  I/O idleness, not credential lifetime; edge subscribers are idle by design).
- **Minimal-alloc-until-auth**: shared `UNAUTHENTICATED` sentinel (zero per-conn alloc) + one
  auth-deadline `ScheduledFuture`; **no** driver / session-thread / registries until AUTHENTICATED;
  a `PRE_AUTH_MAX_FRAME` ceiling (**16 KiB** — fits multi-KB OIDC JWTs with margin, ≪ the 2 MiB
  steady-state cap) enforced in the decoder's length-peek stage, gated on the auth attr. (Where the
  edge admits token clients, driver/session build must move from post-handshake to post-AUTH; the
  mTLS-only path keeps building at handshake since the handshake *is* auth.)

**Expiry / revocation (Gate 5)**
- Lead-time window, signal-once-in-window, never-close-in-window: **token** frac 0.20 / floor 30s /
  ceil 5m; **cert** frac 0.10 / floor 5m / ceil 1h. Anchored to etcd (refresh at ⅓ TTL) and golang
  oauth2 (`defaultExpiryDelta=10s`).
- Refresh-vs-close **per mode**: OIDC/bearer → in-band REFRESH_AUTH; mTLS cert → graceful CLOSE +
  reconnect (the cert changes at the TLS layer → new handshake mandatory).
- **Distinct close code `CREDENTIAL_EXPIRED(13)`**, not a reuse of `AUTH_FAIL(4)`. *(Conflict
  resolved: inv-netty preferred reuse to avoid a wire bump, but Gate 3 bumps `EDGE_WIRE_VERSION`
  regardless when it adds AUTH/REFRESH_AUTH/CLOSE — so the distinct, debuggable code is free.)*
- Revocation `off | lax | strict`, **default `off`** (byte-identical to today), ramp off→lax→strict.
  OCSP-strict-lockout foot-gun (CRDB verbatim: strict + responder-down locks you out) prevented by a
  **hard invariant**: online revocation applies to **client/edge creds only**; Raft inter-node mTLS
  and the break-glass admin cred are **exempt** (chain + notAfter only, never a responder) → the
  interior can never be bricked by a revocation responder, even under strict. `lax` = fail-open +
  alarm on responder-down; `strict` = fail-closed with blast radius bounded to new client connections.
- **Active mid-connection expiry enforcement** is a deliberate behavior change from handshake-only
  (a long-lived mTLS connection is now closed when its cert crosses `notAfter`) — documented; correct
  security posture. Clock-skew leeway 60s. The tick runs on the edge/control pipeline only; it never
  touches Raft apply/replay/encryption.

**Node-join (Gate 4)**
- Marker per mode: mTLS → cert RDN (default `CN`) value ∈ `allowedNodes` allow-list → `NodeId`,
  optionally also chained to a **separate peer CA** (recommended; structural, beats a CN match);
  reserved `CN=node` convention as the documented default (etcd/CRDB parity), still enumerated in the
  allow-list. OIDC → required node-claim; Basic → reserved `node` account — both **written +
  reject-tested but dormant** (no interior token path).
- **Empty allow-list + TLS + auth-enabled ⇒ boot error** (replaces today's warn-when-unconfigured);
  auth-disabled keeps the loud warning as the escape hatch.
- One enforcement gate, two transports, at the existing resolve sites (`NettyRaftTransport.java:476`,
  `TcpRaftTransport.java:498`); the interior ingress is singular (InstallSnapshot rides the same
  transport; membership rides the Raft log; `addNode/removeNode` intentionally unexposed).
- Optional marker RDN configurable + optional SAN-URI/SPIFFE match mode (mirrors etcd
  `--peer-cert-allowed-hostname`); default remains `CN`.

**Auto-balance (Gate 8)**
- **Decentralized**, one loop per node, each sheds one of its own led groups to an under-loaded peer
  (authority is already sharded — `transferLeadership` returns false off-leader, `RaftNode.java:1076`).
  No central coordinator.
- Metric = absolute max−min leader count; act at **spread ≥ 2** (fractional is meaningless at
  `MAX_SHARD_COUNT=16`). Cadence 30s ±25% jitter on a dedicated `ScheduledExecutor` (model:
  `tlsReloadExecutor`), never the 10ms consensus tick. One transfer per node per cadence; 60s cooldown;
  jittered target among the minima.
- **Back-off (do nothing this cycle)** on any of: a led group `leaderId==null` (mid-election), any
  group's `currentTerm` bumped within `instabilityWindowMs`, a membership change in flight, an
  in-flight transfer, or inside cooldown. The primitive is a hard floor (refuses if
  `configChangePending`, `RaftNode.java:1085`).
- Cluster leader-view is **derivable locally** (every node replicates every group; tally
  `getGroup(g).monitorView().leaderId()` across `driver.groupIds()`) — only a small local aggregation
  helper is new; no gossip/RPC. Drives transfers through the wired+tested
  `DriverLeadershipAdmin.transferLeadership`.
- Defaults (all via Gate-1 `ConfigSource`): `enabled=true` + hard kill switch, `dryRun` available,
  `intervalMs=30000`, `jitterPct=25`, `imbalanceThreshold=2`, `cooldownMs=60000`,
  `maxInFlightTransfers=1`, `instabilityWindowMs=5000`. Inert at N=1 / M=1. Count-based signal for v1
  (hash-sharded groups ≈ uniform load; load/QPS signal noted as a v2 extension point). Cross-box
  leadership only — must not be conflated with within-box owner-thread rehoming
  (`MultiRaftDriver.rehomeGroup`).

**Upgrade (Gate 9)**
- **Apply the `PolicySerializer` fix now**: add a recognized `_acl/format` sentinel key (absent ⇒ v1,
  fail-closed on unsupported), reserve `SUPPORTED_ACL_FORMAT=1`; byte-identical for all existing
  deployments (no such key today). Compatibility rule: format 1 frozen; a future grammar change MUST
  bump `_acl/format` and MUST NOT extend an existing line's positional grammar without it. This is the
  one real foreclosure — NEVER DEFER applies.
- The two other flagged serializers are **not** foreclosures (stale postgres-bar flags): `WrappedKey`
  is in-memory only (disk projection is the versioned keyring CLOUD_KMS entry); `WatchCursor` is
  versioned-by-carrier (edge frame 0x02/0x03) + `topologyEpoch`, never persisted standalone.
- Document the normative **upgrade contract** (C0–C9 from the finding) in the RFC. Edge plane already
  has real negotiation (first-frame version pin). Raft plane has **no in-band negotiation** (strict
  per-frame tripwire, all nodes same `WIRE_VERSION`) — safe (mixed version = terminated connections,
  never corruption) but a Raft frame bump is blue/green, not drop-in-rolling, until a
  cluster-wire-version descriptor is built. No such descriptor is built now (no v2 exists); the
  recommended future mechanism is a CockroachDB-style cluster-wire-version descriptor + finalization
  interlock, documented as the contract.

---

## 3. Decisions genuinely for the operator (build blocks on these)

> **RESOLVED 2026-07-06 (operator):** **D1 = Option A** (build the edge token AUTH frame *additively*).
> **D2 = On by default + hard kill switch.** The build proceeds on these.

### D1 — Edge / M2M binary-plane token authentication scope  *(the central fork)* — **RESOLVED: Option A**
The charter (Gate 2/3 DoD; §4 E2E "token→AUTH frame→validated→ACL"; NEVER DEFER) mandates a live,
mode-tagged token AUTH frame on the M2M binary plane. Four investigators independently recommended
the **narrower** v1 scope (edge mTLS-only; OIDC/Basic on the HTTP plane only; the AUTH frame spec'd
but dormant) on grounds of wire-compat, and "no protocol client exists yet to exercise it" (protocol
clients are Group C).
- **Option A (recommended): build it, additively.** The edge AUTH frame goes live; mTLS clients keep
  authenticating via the handshake (byte-identical, no mandatory AUTH frame); token clients send AUTH
  first; new frame codes are additive under the `EDGE_WIRE_VERSION` bump Gate 3 does anyway. The §4
  E2E SDK-style M2M test client is what exercises it (so it is not dead code). Charter-faithful.
- **Option B: edge mTLS-only for v1.** Spec AUTH/REFRESH_AUTH/CLOSE normatively in the RFC as a
  dormant forward-extension; OAuth/Basic live only on the HTTP control plane. Matches the four
  investigators; smallest wire surface frozen now; token M2M lands with Group C's real client.

### D2 — Leadership auto-balance rollout default
The loop is built + tested either way (the E2E "leader-drift → restores distribution" test exercises
the real transfer path regardless). This is only the shipped default:
- **On by default + hard kill switch** (recommended by inv-autobalance; closes the drift gap
  immediately).
- **Dry-run first release** (observe-only, emits `would_transfer`; safest introduction of a loop that
  moves production leadership).
- **Off by default** (reproduces the exact gap the gate closes — not recommended).

Everything in §2 proceeds under the safest-standard calls unless the operator redirects.

---

## 4. Build sequence (dependencies + the load-bearing seams)

1. **Gate 1 — Config-as-SPI** *(foundation; everything below rides on it)*. `ConfigSource` (YAML+ENV),
   fail-closed on missing-required/malformed; establish the fail-loud `ServiceLoader` discovery idiom
   here. Replaces scattered `System.getProperty`/`getenv` in `ConfigdServer.java`. OIDC settings,
   auto-balance tuning, expiry windows, revocation mode, Vault settings all become `ConfigSource` keys.
2. **Gate 2 — Authenticator SPI + 4 modes** (both HTTP + edge adapters share the SPI; three outcomes;
   `Principal` seam). Seam: `AuthInterceptor.java:40`, `AdminApiHandler.checkAuth:696`,
   `ConfigdServer.java:757`.
3. **Gate 3 — AUTH/REFRESH_AUTH/CLOSE + Netty pipeline** (`FrameType` next code `0x13`; `ErrorCode`
   next `13`; `EDGE_WIRE_VERSION` bump + golden regen; `AuthState` attr; expiry-tick scaffold).
   **Scope set by D1.**
4. **Gate 4 — Node-join gate** (`PeerIdentityPolicy` fail-closed default + optional peer CA; dormant
   OIDC/Basic markers + reject tests). Seam: `NettyRaftTransport.java:476`, `TcpRaftTransport.java:498`,
   `ConfigdServer.java:442`.
5. **Gate 5 — Unified expiry + revocation** (window, refresh-vs-close per mode, lax/strict + inter-node
   exempt invariant, active mid-connection enforcement). Seam: `notAfter` at
   `NettyFanOutServer.java:417`, `exp` at `AuthInterceptor`/OIDC validator.
6. **Gate 6 — OAuth2/OIDC resource-server** (`configd-authn-oidc`, nimbus, JWKS rotation, claims→roles).
   Real Keycloak Testcontainers incl. key-roll.
7. **Gate 7 — Vault KMS provider** (`configd-kms-vault`; **first wire the KMS SPI into boot for `local`
   too**; Transit seal-custodian; AppRole; full lifecycle). Real Vault Testcontainers. Seam:
   `ConfigdServer.java:1444`, `SegmentKeyManager.java:185`, `NodeKeyring.java`.
8. **Gate 8 — Leadership auto-balance** (decentralized loop; local leader-view helper; `DriverLeadership
   Admin` drive). **Default set by D2.**
9. **Gate 9 — Upgrade-capability** (apply the `PolicySerializer` `_acl/format` fix; drop the normative
   upgrade contract into the RFC). Confirm the Vault keyring encryption-context slot during Gate 7
   (bump `KEYRING_FORMAT_VERSION` before freezing if needed).

Then: exhaustive test phase (Vault + Keycloak containers; redteam; auto-balance + upgrade scenarios;
E2E per mode; coverage) → review loop to clean → RFC normative.

Every gate merges only on **actual full-reactor CI green** (2-vCPU box: never two Maven builds at once).

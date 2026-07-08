# Group B §2.5 — Unified Credential-Expiry + Revocation Model (Investigation)

Status: investigation / read-only research. No production code changed. This is the §2.5 findings
input for the Group B auth arc; it sits on top of the §2.3 Netty auth-pipeline findings (the timer
mechanism) and the Gate-3 AUTH / REFRESH_AUTH / CLOSE frames. Grounded at `file:line` against the
tree at HEAD `f971a89`. Primary sources cited inline with URL + section.

---

## 1. Executive summary + recommendation on every fork

Configd authenticates on two planes with two different credential shapes, and the expiry model must
differ per shape:

- **Binary edge plane** — mTLS client certificate, verified at the TLS handshake. The credential's
  expiry is the leaf certificate `notAfter`. The credential is bound to the TLS session, so it
  **cannot** be refreshed in-band; rotation means a new handshake.
- **Control-plane HTTP API** — Bearer token, re-presented per request. If validated as OIDC/JWT the
  expiry is the `exp` claim. The credential is a re-presentable string, so it **can** be refreshed
  in-band (the SDK obtains a fresh token and presents it on the next request / frame).

The load-bearing gap this work closes: **the mTLS handshake validates `notAfter` exactly once, at
connect time.** A long-lived edge/watch connection whose certificate expires *mid-connection* is
never re-checked by TLS and stays up indefinitely. Today Configd does no online revocation at all
(the JDK PKIX default does not enable OCSP/CRL — see §3.6), so a compromised-then-revoked cert keeps
working until it expires or the connection drops. §2.5 adds (a) a proactive lead-time refresh window,
(b) a non-blocking expiry-enforcement tick on the per-connection `AuthState`, and (c) an optional,
fail-safe revocation check.

### Headline recommendations (the forks)

| Fork | Recommendation |
|---|---|
| **Lead-time window** | Enter the refresh window at `expiresAt − W`, where `W = clamp(fraction × lifetime, floor, ceil)`. **Token:** `fraction=0.20, floor=30s, ceil=5m`. **Cert:** `fraction=0.10, floor=5m, ceil=1h`. In the window: signal refresh proactively; **do not** close. Anchored on etcd (refresh at ⅓ TTL) and golang oauth2 (`defaultExpiryDelta = 10s` before `Expiry`). |
| **Refresh vs close, per mode** | **OIDC/Bearer → REFRESH_AUTH in-band** (re-present a fresh token, connection stays up). **mTLS cert → graceful CLOSE-with-reason + reconnect** (the cert changes at the TLS layer, so the session must be re-established with the rotated cert). |
| **Boundary decision** | In-window → emit `REFRESH_AUTH`-needed (token) or reconnect-hint (cert), **once**. Hard-expired (`> exp + leeway`) and not refreshed → CLOSE with a *distinct* `CREDENTIAL_EXPIRED` reason (not `AUTH_FAIL`), so a driver reconnects rather than treating it as a bad-credential reject. |
| **Revocation lax/strict** | Config `configd.auth.revocation.mode = off \| lax \| strict`, modelled on CockroachDB `security.ocsp.mode`. **Safe default = `off`** (byte-identical to today), **`lax` recommended once a responder is configured**, **`strict` only after validating with `lax`** — verbatim CRDB guidance in §2.1. |
| **Strict-self-lockout prevention** | Online revocation applies to **client/edge credentials only**. The **Raft inter-node mTLS plane and the cluster's own break-glass admin credential are EXEMPT** — they validate by chain + `notAfter`, never by a responder. This is the structural guard that makes the CRDB "strict locks you out" foot-gun **unreachable for the cluster interior**, regardless of responder health. |
| **Clock skew** | `configd.auth.clockSkewLeewaySeconds` default **60s**. Hard-expiry close fires at `exp + leeway`; the window (`W ≫ leeway`) opens well before, so leeway never inverts the window. |
| **Consensus isolation** | The expiry tick runs on the **edge / control-plane channel pipeline only**. It closes a *client* connection; it never touches the Raft replication/apply path, the encryption seam, or interior liveness. Inter-node cert expiry is handled by `TlsManager.reload()` rotation, not by this tick. |

---

## 2. Reference-system findings (primary sources)

### 2.1 CockroachDB — the OCSP strict-lockout foot-gun (verbatim)

Source: CockroachDB docs, *Using Online Certificate Status Protocol (OCSP) with CockroachDB*,
`https://www.cockroachlabs.com/docs/stable/manage-certs-revoke-ocsp`. The cluster setting
`security.ocsp.mode` has three values — `off` (default), `lax`, `strict`. The docs recommend `strict`
for production **but only after verifying with `lax`**, and warn, verbatim:

> "For production clusters, we recommend that you set `security.ocsp.mode` to `strict`, but only after
> verifying the configuration with it set to `lax`."
>
> "**Note:** In the strict mode, all certificates are presumed to be invalid if the OCSP server is not
> reachable. Setting the cluster setting `security.ocsp.mode` to `strict` will lock you out of your
> CockroachDB database if your OCSP server is unavailable."

This is the exact foot-gun §2.5 must design around: **strict online revocation checking that
fail-closes on responder-unreachable can brick the entire cluster, including the nodes' own ability to
authenticate to each other.** The default being `off`, and the "verify with lax first" ramp, are the
operational mitigations CRDB itself ships. Configd's structural mitigation (§4.3) is stronger: exempt
the inter-node and admin creds from the responder path entirely.

### 2.2 etcd — lease TTL + keepalive as the lead-time model

Source: etcd v3.5 *etcd API* (Lease API), `https://etcd.io/docs/v3.5/learning/api/` §"Lease API":

> "Leases are a mechanism for detecting client liveness. The cluster grants leases with a
> time-to-live. A lease expires if the etcd cluster does not receive a keepAlive within a given TTL
> period. … Leases are refreshed using a bi-directional stream created with the `LeaseKeepAlive` API
> call."

The lead-time discipline is in the client, not the API doc. etcd's Go client (`client/v3/lease.go:549`,
`https://github.com/etcd-io/etcd/blob/main/client/v3/lease.go`) computes:

```go
nextKeepAlive := time.Now().Add((time.Duration(karesp.TTL) * time.Second) / 3.0)
ka.deadline    = time.Now().Add(time.Duration(karesp.TTL) * time.Second)
```

i.e. the client refreshes at **⅓ of the TTL** and treats the lease dead at TTL — it always tries to
renew with ~⅔ of the lifetime still in hand, giving room for retries before the credential actually
lapses. This is the *fraction-of-lifetime* anchor for Configd's window. (etcd's ⅓ is aggressive
because a lease is a liveness heartbeat; a credential-expiry window needs one proactive refresh with
retry headroom, not a continuous heartbeat — hence Configd uses a larger fraction remaining, i.e. a
smaller window, §4.1.)

### 2.3 golang.org/x/oauth2 — refresh *ahead* of `exp` (the absolute-floor anchor)

Source: `golang.org/x/oauth2`, `token.go` (`https://github.com/golang/oauth2/blob/master/token.go`):

```go
// defaultExpiryDelta determines how earlier a token should be considered
// expired than its actual expiration time. …
const defaultExpiryDelta = 10 * time.Second
...
func (t *Token) expired() bool {
    ...
    return t.Expiry.Round(0).Add(-expiryDelta).Before(timeNow())
}
```

A token is treated as expired **10 seconds before its real `Expiry`**, so the caller refreshes before
the resource server would reject it. This is the canonical *absolute lead-time* anchor. Configd's
token floor (30s) is deliberately larger than 10s to absorb an IdP round-trip plus a retry.

### 2.4 Vault — TTL + renew-before-expiry, auto-revoke on lapse

Source: HashiCorp Vault, *Lease, Renew, and Revoke*,
`https://developer.hashicorp.com/vault/docs/concepts/lease`:

> "Vault promises that the data will be valid for the given duration, or Time To Live (TTL). Once the
> lease is expired, Vault can automatically revoke the data … consumers of secrets need to check in
> with Vault routinely to either renew the lease (if allowed) or request a replacement secret."

Vault reinforces the same shape: bounded TTL, client renews *before* expiry, and the server
auto-revokes on lapse. The takeaway for §2.5 is the "short TTL + renew, don't rely on out-of-band
revocation" posture, which is the safer analog for the OIDC token path (§4.3).

### 2.5 RFC 7662 — OAuth 2.0 Token Introspection (the token revocation analog)

Source: RFC 7662 (`https://www.rfc-editor.org/rfc/rfc7662`), Abstract + §2.2. Introspection lets a
protected resource query the authorization server for a token's *active* state. §2.2 defines the
`active` member:

> "… a `true` value return for the `active` property will generally indicate that a given token has
> been issued by this authorization server, **has not been revoked** by the resource owner, and is
> within its given time window of validity (e.g., after its issuance time and before its expiration
> time)."

So introspection is the online-revocation-check equivalent for bearer tokens — it covers both
revocation and expiry in one call. The same responder-availability trade-off (fail-open vs fail-closed
when the introspection endpoint is down) applies exactly as with OCSP, so it inherits the same
lax/strict knob and the same self-lockout caution.

### 2.6 The general OCSP model (RFC 6960) — soft-fail is the norm

RFC 6960 (`https://www.rfc-editor.org/rfc/rfc6960`) defines OCSP but leaves responder-unreachable
behaviour to the relying party. Real-world browsers and most TLS stacks adopt **soft-fail** (treat
unreachable-responder as "not revoked") precisely because hard-fail turns every responder outage into
a total outage — the same failure mode CRDB warns about (§2.1). OCSP responses carry `thisUpdate` /
`nextUpdate`, so a relying party may **cache** a good response until `nextUpdate` and **staple** it,
turning the responder into a soft dependency rather than a per-handshake hard dependency.

---

## 3. Configd seam grounding (`file:line`)

### 3.1 Where the mTLS cert identity (and `notAfter`) is read — edge plane

`configd-server/src/main/java/io/configd/server/fanout/NettyFanOutServer.java:398-427`. On
`SslHandshakeCompletionEvent` success, `resolveCertIdentity(ctx)` reads:

```java
SslHandler ssl = ctx.pipeline().get(SslHandler.class);
return ssl.engine().getSession().getPeerPrincipal().getName();   // line 423
```

This is the exact seam where `notAfter` must also be captured: the same `SSLSession` exposes the leaf
via `getPeerCertificates()[0]`, which cast to `X509Certificate` yields `.getNotAfter()`. Today only the
Subject DN name is taken; the cert object (and its `notAfter`) is discarded. §2.5 stashes `notAfter`
into the per-connection `AuthState` here, alongside the principal.

### 3.2 Where the mTLS cert identity is read — Raft (inter-node) plane

`configd-netty/src/main/java/io/configd/netty/NettyRaftTransport.java:469-504`. Mirror of the edge:
`resolveCertIdentity` at `:494-504` reads `ssl.engine().getSession().getPeerPrincipal().getName()`, and
the verified `NodeId` is pinned onto the channel as an attribute at `:483`:

```java
ctx.channel().attr(PEER_IDENTITY).set(pinned);
```

This `AttributeKey`-on-channel pattern is the **exact model for the `AuthState` channel attribute** the
§2.3 pipeline introduces. Critically, **this plane must be EXEMPT from the revocation tick** (§4.3) —
it is the cluster interior.

### 3.3 Where the Bearer token is read — control plane

`configd-server/src/main/java/io/configd/server/AdminApiHandler.java:760-766` (`bearerToken`), invoked
per request at `:220-221` and `:696-710` (`checkAuth`). The validator seam is
`configd-control-plane-api/src/main/java/io/configd/api/AuthInterceptor.java:39-42`
(`TokenValidator.validate(String)` → `AuthResult`). This is where a future OIDC validator parses `exp`;
`AuthState.expiresAt` on the control plane = that `exp`. Because the token is re-presented per request,
expiry is naturally re-evaluated on every call at `:220` — the tick (§4) matters for the *long-lived
edge* connection, not the per-request HTTP path.

### 3.4 The current validator has no `exp` (static shared secret today)

`configd-server/src/main/java/io/configd/server/ConfigdServer.java:755-769`. Auth today is a single
static token compared in constant time; a match yields `Authenticated(ROOT_PRINCIPAL, Set.of())` with
**no expiry**. OIDC/`exp` is a forward capability behind the same `TokenValidator` seam (RFC §03 AU7-2
"new server authenticators do not break older drivers"). Auth is **off by default** unless
`--auth-token` is set (`ConfigdServer.java:748`, `ServerConfig.java:207 authEnabled()`), which is why
the revocation default must also be `off` to stay byte-identical (§4.3).

### 3.5 The timer discipline to copy — the first-frame deadline

`configd-server/src/main/java/io/configd/server/fanout/NettyFanOutServer.java:448-472`. A one-shot task
armed on session admission and cancelled on the first routed frame, all on the event loop:

```java
firstFrameDeadline = ctx.executor().schedule(
        () -> onFirstFrameDeadline(ctx), firstFrameDeadlineMs, TimeUnit.MILLISECONDS);   // :452
...
firstFrameDeadline.cancel(false);   // :471  (disarm on first frame)
```

Property `configd.edge.firstFrameDeadlineMs` at `FanOutServer.java:88`, default at `:104`. The
established, legitimately-idle subscriber then relies on the **server→client HEARTBEAT** for liveness
(default `edge.fanout.heartbeatMs = 250`, `FanOutConfig.java:25-26`), emitted from the virtual-thread
session loop (`FanOutSessionCore`). The expiry tick's natural home is that existing periodic wakeup
(per-second granularity is ample for expiry); the exact mechanism is owned by §2.3 and not re-derived
here.

### 3.6 What the handshake enforces today, and what it does NOT

`NettyRaftTransport.java:376` and `NettyFanOutServer.java:300` set `setNeedClientAuth(true)`; the
comment at `:376` is explicit — "a peer with no/**expired**/untrusted cert is rejected." The test
fixture `configd-server/src/test/java/io/configd/server/fanout/AbstractFanOutServerContract.java:141-147`
proves `notAfter` is enforced **only via a CA-signed chain** (a self-signed expired leaf would be
accepted as its own trust anchor — RFC 5280 §6.1 does not check an anchor's own validity). So:

- **Enforced at handshake:** chain trust + `notAfter`/`notBefore` (validity), **once**, at connect.
- **NOT enforced:** any online revocation (the JDK PKIX `TrustManagerFactory` in `TlsManager.java:59-75`
  does not enable OCSP/CRL by default — no `PKIXRevocationChecker`, no `ocsp.enable`), and any
  **mid-connection** re-check of `notAfter`. Both are exactly the gaps §2.5 fills.

### 3.7 Server-side cert rotation seam (inter-node path)

`configd-transport/src/main/java/io/configd/transport/TlsManager.java:51-75` loads the PKCS12
key/trust material from disk; `reload()` at `:84-86` rebuilds the `SSLContext` for rotation without
restart. Inter-node cert *expiry* is handled by this rotation path, **not** by the client-expiry tick —
reinforcing the consensus-isolation rule (§4.5).

### 3.8 Wire vocabulary headroom (owned by §2.3 / Gate 3, noted for completeness)

`FrameType` (`configd-distribution-service/.../wire/FrameType.java:14-44`) uses `0x01..0x12`; next free
edge code is `0x13` — the AUTH / REFRESH_AUTH frames land here. `ErrorCode`
(`.../wire/ErrorCode.java:14-76`) uses `1..12`; `AUTH_FAIL(4)` is the 401-class code and
`NOT_AUTHORIZED(11)` the 403-class; a new **`CREDENTIAL_EXPIRED(13)`** (§4.2) is the graceful
expiry-close reason. Both enums are golden-fixture-pinned — any addition MUST bump
`EdgeFrameCodec.EDGE_WIRE_VERSION` and regenerate the golden fixtures (a §2.3/Gate-3 deliverable, not
this doc's).

---

## 4. Recommended design

### 4.1 The lead-time refresh window

Open a per-connection refresh window at `expiresAt − W`, sized per credential mode:

```
W = clamp(fraction × lifetime, floor, ceil)
lifetime = expiresAt − issuedAt   (cert: notAfter − notBefore; token: exp − iat)
```

| Mode | fraction | floor | ceil | rationale |
|---|---|---|---|---|
| **OIDC / Bearer token** | 0.20 | 30s | 5m | one proactive refresh with retry headroom; floor > oauth2's 10s to absorb an IdP round-trip + retry (§2.3). |
| **mTLS cert** | 0.10 | 5m | 1h | rotation is heavier (reload key material + re-handshake); a larger floor absorbs a slightly-late cert-manager/SPIFFE rotation. The 1h ceil stops a 90-day cert from nagging refresh for days. |

Behaviour inside the window: emit the refresh signal **once** (idempotent — a per-connection
`refreshSignalled` flag), and keep serving. Never close inside the window. The window is deliberately
**less aggressive than etcd's ⅓-TTL** (§2.2) because Configd needs one lead-time nudge, not a
continuous liveness heartbeat (that role is already the HEARTBEAT, §3.5).

Config keys: `configd.auth.refresh.token.{fraction,floorSeconds,ceilSeconds}`,
`configd.auth.refresh.cert.{fraction,floorSeconds,ceilSeconds}`.

### 4.2 Refresh vs close — the decision at the expiry boundary, per mode

**OIDC / Bearer (in-band refresh).** The credential is a re-presentable string, so refresh keeps the
connection up.
- *Control plane:* no in-band frame needed — each request re-presents the token; the SDK simply fetches
  a fresh token (its own IdP round-trip, ahead of `exp` by the window) and uses it on the next request.
  The server validates `exp` per request at `AdminApiHandler.java:220`.
- *Binary edge (forward extension AU3-3):* server emits `REFRESH_AUTH`-needed in the window → client
  sends `AUTH`/`REFRESH_AUTH` carrying a fresh token → server re-validates and **re-arms**
  `AuthState.expiresAt` from the new `exp`, connection uninterrupted.

**mTLS cert (reconnect).** The credential is bound to the TLS session; a new cert is a new handshake,
which cannot happen in-band. So:
- In the window, optionally emit a reconnect-hint (a `REFRESH_AUTH`-needed carrying "reconnect", not
  "re-present a token").
- At the window's end / hard expiry, **CLOSE-with-reason = `CREDENTIAL_EXPIRED`** (graceful), not
  `AUTH_FAIL`. The driver — which already holds a rotated cert on disk from its cert-manager / SPIFFE
  workload API — reconnects and re-handshakes with the fresh cert. The old connection drains.

**The distinct close code matters.** `CREDENTIAL_EXPIRED(13)` tells a driver "reconnect with your
current (rotated) credential"; `AUTH_FAIL(4)` tells it "the credential is bad — (re)authenticate." Both
are RFC §03 401-class, but the graceful pre-expiry close prevents a rotation from looking like an
auth-rejection storm. (Confirm the `ErrorCode` addition + wire-version bump with §2.3.)

### 4.3 Revocation — lax/strict config, safe default, self-lockout prevention

Config `configd.auth.revocation.mode ∈ {off, lax, strict}`, modelled directly on CRDB
`security.ocsp.mode` (§2.1):

| Mode | Responder answers | Responder unreachable/timeout |
|---|---|---|
| `off` (**default**) | no check (byte-identical to today, §3.4/§3.6) | — |
| `lax` (**recommended once configured**) | honour revoked/not-revoked | **fail-open** (allow) + raise a revocation-responder-down alarm/metric |
| `strict` | honour revoked/not-revoked | **fail-closed** (treat as revoked) — carries the CRDB lockout warning |

**Safe default = `off`**, ramp `off → lax → strict`, exactly CRDB's "verify with lax before strict"
guidance (§2.1). Cache OCSP responses to `nextUpdate` and staple where available (§2.6) so a transient
blip is not a per-handshake hard dependency.

**OIDC path.** Prefer **short-TTL access tokens + no-renew** as the primary revocation mechanism (§2.4)
— a token you can't renew dies on its own, no responder needed. RFC 7662 introspection (§2.5) is the
optional online analog (`active:false` ⇒ revoked); it inherits the same lax/strict knob on the
*introspection endpoint* being down.

**Strict-self-lockout prevention — the load-bearing guard.** Online revocation applies to
**client/edge credentials only**. Two credential classes are **EXEMPT** and validate by chain +
`notAfter` only, never consulting a responder:

1. **Raft inter-node mTLS** (`NettyRaftTransport.java:376`, §3.2) — the cluster interior. Consensus,
   replication, and apply keep running even if the responder is down under `strict`.
2. **The cluster's own break-glass admin credential** (the static-root path,
   `ConfigdServer.java:757-769`, §3.4) — a revocation-check failure of the cluster's own admin creds can
   never brick the cluster.

This is strictly stronger than CRDB's own mitigation: the CRDB foot-gun (§2.1) is **unreachable for the
cluster interior by construction**, because the interior never has a responder in its path. Client
creds under `strict` still fail-closed on responder-down (that is the point of `strict`), but the blast
radius is bounded to *new client connections*, and the `lax` default + the alarm + the ramp are the
mitigations for that surface. This satisfies the arc rule "fail-closed BUT no strict-self-lockout."

Config keys: `configd.auth.revocation.mode`, `.responderTimeoutMs`, `.cacheTtlSeconds`,
`.exemptInterNode=true` (default true, and the doc must warn that setting it false re-arms the
foot-gun).

### 4.4 The non-blocking expiry tick — what it checks

Running on the cadence from §2.3 (piggybacked on the existing heartbeat wakeup, §3.5), for each
authenticated channel it reads the immutable `AuthState{principal, expiresAt, mode, refreshSignalled}`:

1. `now ≥ expiresAt − W` and `!refreshSignalled` → emit refresh signal (REFRESH_AUTH-needed for token;
   reconnect-hint for cert), set `refreshSignalled`. **Non-terminal.**
2. `now ≥ expiresAt + leeway` and not refreshed → **CLOSE-with-reason `CREDENTIAL_EXPIRED`** (posted to
   the event loop exactly like `onFirstFrameDeadline`'s `teardown`, §3.5). **Terminal.**
3. On a successful `REFRESH_AUTH`, `AuthState.expiresAt` is re-armed from the new `exp` and
   `refreshSignalled` cleared.

The tick only *reads* `AuthState` and *posts* a close to the event loop; it never blocks and never
does I/O (revocation lookups, if enabled, are cached/stapled per §2.6 and off the tick's hot path).

**Where `notAfter` / `exp` come from at runtime:**
- **mTLS:** `((X509Certificate) ssl.engine().getSession().getPeerCertificates()[0]).getNotAfter()`,
  captured once at handshake-complete in the same spot identity is resolved
  (`NettyFanOutServer.java:417-427`, `NettyRaftTransport.java:494-504`) and stashed into `AuthState`
  (mirroring the `PEER_IDENTITY` attribute, `NettyRaftTransport.java:483`).
- **OIDC token:** the `exp` claim, parsed server-side by the OIDC validator behind
  `TokenValidator.validate` (`AuthInterceptor.java:39-42`); `AuthState.expiresAt = exp`.

### 4.5 Clock skew + consensus isolation

**Leeway.** `configd.auth.clockSkewLeewaySeconds` default **60s** (in the common 30–120s band JWT
libraries use for `exp`/`nbf`). The hard close fires at `exp + leeway`; the window opens at
`expiresAt − W` with `W ≫ leeway`, so the two never cross. Only the **server** clock is authoritative
for its own enforcement — skew is precisely why the connection is not slammed exactly at `exp`.

**Consensus isolation (must-hold).** The expiry tick runs on the **edge / control-plane channel
pipeline only** and closes a *client* connection. It MUST NOT:
- run on, or block, the Raft replication/apply path or the encryption seam;
- consult a revocation responder for inter-node or admin creds (§4.3);
- treat an expiring inter-node cert as a client-expiry event — that is handled by `TlsManager.reload()`
  rotation (§3.7).

So an auth-expiry event can, at worst, close a client connection; it can never stall consensus, a
write, a replay, or the interior. This satisfies "auth expiry closes a CLIENT connection, it must not
touch the Raft interior liveness."

---

## 5. Open questions needing an operator decision

1. **Revocation default & rollout.** Ship `off` (byte-identical) and document the `off → lax → strict`
   ramp (recommended), or auto-enable `lax` when a responder URL is configured? Recommendation: `off`
   by default; never auto-jump to `strict`.
2. **`exemptInterNode` immutability.** Should the inter-node/admin revocation exemption be a hard
   invariant (not operator-toggleable), or a defaulted-true knob with a loud warning if disabled? A
   hard invariant is safest but less flexible.
3. **Edge bearer-token forward extension (AU3-3).** Is the token-bearing edge frame built in this arc
   (enabling *in-band* `REFRESH_AUTH` on the binary plane), or does the edge stay mTLS-only in v1 —
   making the edge expiry path **reconnect-only** and REFRESH_AUTH a control-plane/future-edge concept?
4. **mTLS mid-connection expiry enforcement.** Confirm we want to actively close a connection whose cert
   expires *while connected* (industry norm for long-lived streams: yes). This is a behaviour change
   from today's handshake-only checking (§3.6) and should be called out in the RFC.
5. **Window defaults.** Confirm the token `(0.20, 30s, 5m)` and cert `(0.10, 5m, 1h)` window
   parameters, or tune to a specific IdP token lifetime / cert-manager rotation cadence.
6. **OIDC revocation posture.** Rely on short-TTL + no-renew as primary (recommended, §2.4), with RFC
   7662 introspection optional — or require introspection? Depends on whether the target IdP(s) expose a
   7662 endpoint.
7. **New wire codes.** `CREDENTIAL_EXPIRED(13)` + AUTH/REFRESH_AUTH `FrameType` codes require a
   `EDGE_WIRE_VERSION` bump + golden-fixture regen — confirm this is sequenced with the §2.3/Gate-3 wire
   work so the version bump happens once.

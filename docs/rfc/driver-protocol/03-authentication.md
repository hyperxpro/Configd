# Configd Driver Protocol RFC — §03: Authentication

**Status: DRAFT (2026-06-28). Docs-only; normative.** Third section of the Configd driver-protocol RFC. This
section specifies **how a driver authenticates** — how it presents a credential on the wire, where in the
connection lifecycle authentication happens, the **401-vs-403** boundary with authorization, and the
**forward-compatible, server-pluggable-but-driver-stable** contract. It is written so a driver in **any**
language (Rust / Go / Python / Java) authenticates **identically**, regardless of which server-side
authenticator the deployment runs.

Design rationale is in [`../../archive/design/auth-spi/`](../../archive/design/auth-spi/) (`built-reality.md`,
`authenticator-spi.md`, `authn-authz-boundary.md`). This section is **normative**; the design docs explain
*why*. It **composes with**:

- [`01-paths-and-access.md`](01-paths-and-access.md) — the **authorization** model (capabilities, the 403
  side, the watch-authz contract). §03 owns **authentication** (the 401 side); it does **not** redefine
  authorization. The shared error taxonomy is §1 [§7](01-paths-and-access.md#7-error-taxonomy), restated here
  for the connection lifecycle.
- [`02-watches.md`](02-watches.md) — the watch section — a watch subscription authenticates **before**
  any subscribe/snapshot frame ([AU4](#4-the-connection-lifecycle--authenticate-before-any-data); the integration points are flagged in
  [§8](#8-composition-with-1-and-2)).

Clauses in this section are referenced as **`AU<n>-<m>`** (the authentication-section clause prefix, parallel
to §1's `A<n>-<m>`), so the composed RFC has no clashing identifiers.

---

## 1. Conventions, scope, versioning

### 1.1 Requirement keywords

The keywords **MUST**, **MUST NOT**, **REQUIRED**, **SHALL**, **SHALL NOT**, **SHOULD**, **SHOULD NOT**,
**MAY**, and **OPTIONAL** are to be interpreted as in RFC 2119 / RFC 8174.

### 1.2 Scope of this section

This section specifies how a driver **presents a credential** and how the server reports the **outcome of
authentication**. It does **not** specify *which* authenticators a server runs (that is a server-side,
pluggable deployment choice — [`../../archive/design/auth-spi/authenticator-spi.md`](../../archive/design/auth-spi/authenticator-spi.md)),
nor the **authorization** decision (capabilities, policies, the watch-authz contract — all §1). The
load-bearing property of this section is **AU2-1**: the driver's contract is **stable regardless of the
server's authenticator.**

### 1.3 Versioning

The credential mechanisms and the error taxonomy in this section are **version-1** of the driver protocol —
versioned by the `/v1/` HTTP path prefix and the binary edge's **first-frame version pin** (no negotiation
handshake; [`00-overview.md`](00-overview.md) §4, `06-wire-framing.md` §F4; §1 [§1.3](01-paths-and-access.md#13-versioning)). A driver **MUST NOT**
assume an authentication mechanism the server does not offer, and **MUST** fail closed on an unrecognized
authentication mechanism or challenge (AU7-1).

---

## 2. The authentication model — pluggable server-side, stable for the driver

**AU2-1 (the stable driver contract — normative, load-bearing).** A driver **presents the credential it
has** — an mTLS client certificate, or a bearer token — and receives **either an authenticated session or a
typed error.** A driver **MUST NOT** depend on *how* the server verifies that credential. The server MAY
authenticate a bearer token as a static shared secret, or validate it as an OIDC/JWT, or check it via a
Kubernetes TokenReview; it MAY map an mTLS certificate by Subject DN or by SPIFFE SAN URI. **All of these are
invisible to the driver** — it presents what it has and reads the outcome. This is what lets a deployment
change or add authenticators (static-bearer → OIDC) **without breaking any existing driver** (AU7-2).

**AU2-2 (what a driver presents).** A driver **MUST** support presenting at least one of:

- an **mTLS client certificate** during the TLS handshake (the credential is the certificate), and/or
- a **bearer token** (the credential is an opaque string the driver does not interpret).

A driver **MUST** treat a bearer token as **opaque** — it **MUST NOT** parse, inspect, or depend on the
token's internal structure (a static secret and a JWT are indistinguishable to the driver, by AU2-1).

**AU2-3 (the credential is presented, not minted).** v1 defines **no Configd-issued session token**: a driver
**re-presents** its credential per the lifecycle in AU4 (the certificate is bound for the connection; a bearer
token accompanies each unary HTTP request, or is presented once per edge connection in an `AUTH` frame and
renewed with `REFRESH_AUTH` — AU4-4). A driver **MUST NOT** assume a server-issued token/cookie it can replay;
a Configd auth-session mechanism is a named forward extension (AU7-3), not v1.

**AU2-4 (the four authentication modes — both planes, one shared chain).** A deployment authenticates through
**one pluggable authenticator chain shared by both planes** (the same chain resolves an HTTP `Authorization`
header and an edge `AUTH` frame — AU6-1). The modes a driver may present:

| Mode | HTTP control plane | Binary edge plane | Driver-visible credential |
|---|---|---|---|
| **mTLS** | client cert at the TLS handshake | client cert at the TLS handshake (AU3-2) | an X.509 client certificate |
| **HTTP Basic** (RFC 7617) | `Authorization: Basic <base64(user:pass)>` | an `AUTH` frame, scheme = BASIC (§06 §6A) | a username + password |
| **Bearer / OAuth2-OIDC** | `Authorization: Bearer <token>` (AU3-1) | an `AUTH` frame, scheme = BEARER (§06 §6A) | an **opaque** bearer token (a static secret **or** a JWT/OIDC access token — indistinguishable to the driver, AU2-1) |
| **No-Auth** | none (authentication disabled) | none (authentication disabled) | none — a deployment posture (AU4-3) |

**OAuth2/OIDC is a server-side verification of the bearer shape, not a distinct wire mechanism:** an OIDC/JWT
access token rides the **same** `Bearer` HTTP header / BEARER `AUTH`-frame scheme as a static token, and the
driver neither knows nor cares which the server runs (AU2-1). A driver **MUST** present whichever mode(s) its
deployment configures and **MUST NOT** hard-code an assumption about which the server verifies (AU7-2).

---

## 3. Credential presentation on the wire

**AU3-1 (control-plane HTTP API — bearer).** On the control-plane admin HTTP surface, a driver **MUST** present
a bearer token in the **`Authorization: Bearer <token>`** request header (RFC 6750). The server extracts it
exactly there ([`built-reality.md`](../../archive/design/auth-spi/built-reality.md) §1.1). A driver **MUST NOT** place
the token in a URL query parameter or log it (AU5-3).

**AU3-2 (binary edge protocol — mTLS at handshake).** On the binary edge (fan-out / watch) protocol, one v1
authentication is the **mTLS handshake**: the driver presents a client certificate at the handshake, and the
verified certificate identity is **authoritative**. In the **mTLS-only posture** the edge **requires** a client
certificate (`setNeedClientAuth(true)`); when **token/basic auth is also configured** the edge **relaxes to
`setWantClientAuth(true)`** so a **certificate-less** client can connect and authenticate with an `AUTH` frame
(AU3-3) — a **presented** certificate is still verified and remains the authoritative identity
([`built-reality.md`](../../archive/design/auth-spi/built-reality.md) §1.2). Any identity field the driver also
places in a frame (e.g. an `edgeId`) is **advisory** and the server **MUST** override it with the certificate
identity over mTLS. A driver **MUST NOT** rely on a self-asserted identity frame being trusted.

**AU3-3 (the token-bearing edge `AUTH` frame — BUILT, v1 normative).** The binary edge protocol authenticates
by mTLS (AU3-2) **and/or** by a **token/basic `AUTH` frame**. This **supersedes** the earlier draft that listed
this frame as a forward extension: it is now **built and normative**. A **certificate-less** driver presents a
bearer or Basic credential in an **`AUTH` frame** (wire type `0x13`, wire version `0x04`) and renews it with a
**`REFRESH_AUTH` frame** (`0x14`); the byte layout — a `[scheme u8]` tag (1 = BEARER, 2 = BASIC) then
length-prefixed fields — is **normative in [`06-wire-framing.md`](06-wire-framing.md) §6A** (golden fixtures
`auth_bearer.bin` / `auth_basic.bin` / `refresh_auth_bearer.bin`). The two edge auth paths **compose**:

- a **verified client certificate** authenticates at the **TLS handshake** — **no `AUTH` frame**, byte-identical
  to an mTLS driver from before authentication was added (AU2-4 mTLS row); the cert Subject DN is the
  authoritative identity (AU3-2);
- a **certificate-less** driver **MUST** authenticate with an `AUTH` frame **before any business frame** (the
  connection-level lifecycle is AU4-4…AU4-7).

A driver **MUST** treat the bearer token as **opaque** (AU2-2), **MUST NOT** send an `AUTH`/`REFRESH_AUTH` frame
on a plaintext (non-TLS) connection (AU3-4), and **MUST** stamp wire version `0x04` on **exactly** its auth
frames (a business version on an auth frame, or `0x04` on a business frame, is `FRAME_CORRUPT` — §06 F6A-3/-4).

**AU3-4 (transport security is REQUIRED for a bearer/basic credential).** A driver **MUST** present a bearer
token or Basic credential only over a TLS-secured transport. A driver **MUST NOT** send one over plaintext.
(mTLS already implies TLS; a bearer/Basic credential over the HTTP API requires HTTPS, and an edge `AUTH` frame
requires the TLS-secured edge transport.)

## 3A. The cluster interior (node ↔ node) — mTLS-only (non-driver)

**AU3-5 (the interior is mTLS-only; a driver never joins it).** The Raft consensus interior (node ↔ node) is a
**non-driver** surface (§06 §13): a configd **driver** never opens a Raft connection. Stated for model
completeness — and because adding authentication strengthened this guarantee — the interior authenticates by
**mTLS only**
(`setNeedClientAuth(true)` on both interior transports); there is **no** token / `AUTH`-frame path to consensus
(the interior message set carries **no** credential-bearing frame). A node's membership identity is a
**certificate marker**: by default the Subject-DN **CN** (RDN mode; the RDN is configurable via
`configd.raft.peerIdentity.marker`), or optionally a **SAN-URI / SPIFFE** id (`markerType=san-uri`), matched
against a per-node allow-list (`configd.raft.peerIdentity.allowedNodes`). Token-based node markers (an OIDC
node-claim, a Basic node account) are **dormant, fail-closed forward extensions** — unreachable by construction
today because no interior frame carries a token (a client credential can therefore **never** confer interior
standing). **None of this is a driver concern**; the authoritative spec is
`docs/architecture/node-join-gate.md`.

---

## 4. The connection lifecycle — authenticate before any data

**AU4-1 (authenticate first — normative).** Authentication **MUST** complete **before** any data, subscribe,
or snapshot frame is exchanged:

- **mTLS (binary edge):** authentication is the TLS handshake; it completes **before** the first application
  frame. A driver **MUST NOT** send a `SUBSCRIBE` (or any frame) before the handshake completes. The server
  authorizes the subsequent `SUBSCRIBE` as a streaming read **at subscription, before any snapshot/event/
  progress frame** (§1 [§6](01-paths-and-access.md#6-the-watch-authorization-contract-normative), composed in
  §8) — so on the watch path, **both** authentication (handshake) and authorization (subscription check)
  precede the first data byte.
- **Bearer (HTTP API):** each unary request carries the `Authorization` header; authentication is evaluated
  **before** the operation (`AdminApiHandler.checkAuth` runs first,
  [`built-reality.md`](../../archive/design/auth-spi/built-reality.md) §2.2).

**AU4-2 (per-connection vs per-request).** mTLS identity is established **once per connection** (the handshake)
and is stable for the connection's life. A bearer token on the HTTP API is presented **per request**. A driver
**MUST NOT** assume an HTTP bearer credential persists server-side across requests (AU2-3).

**AU4-3 (auth-disabled deployments).** A server **MAY** run with authentication **disabled** (a deployment
choice that prints a loud server-side warning —
[`built-reality.md`](../../archive/design/auth-spi/built-reality.md) §1.1). A driver **MUST** still be prepared to
present a credential and **MUST** treat a `401` (AU5) as "authentication is required here" even if a prior
connection to a different deployment did not require one. A driver **MUST NOT** infer "auth is off" and stop
presenting a credential.

### 4A. The edge connection-level auth lifecycle (token/basic path)

These clauses are the **connection-level** contract for a **certificate-less** edge driver (the token/basic
path of AU3-3). An **mTLS** edge driver authenticates at the handshake (AU4-1) and skips them entirely — it is
byte-identical to a client from before authentication was added. The wire bytes are §06 §6A; these are the
**rules**.

**AU4-4 (authenticate first — a single pre-auth `AUTH`).** On a token/basic edge, a certificate-less driver
**MUST** send **exactly one** `AUTH` frame as its **first routed frame**, before any
`SUBSCRIBE`/`WATCH_CREATE`/`CURSOR_ACK`. The server admits a **single pre-auth attempt**: a rejected `AUTH` (an
invalid or over-cap credential) closes the connection with **`AUTH_FAIL`** (§07 code 4), so a retry costs a
**fresh connection** — a driver **MUST NOT** hot-loop `AUTH` frames on one connection.

**AU4-5 (the pre-auth window — frame ceiling + first-frame deadline).** While unauthenticated, the connection is
held under a **pre-auth frame-size ceiling** (§06 F6A-5) and the **pre-SUBSCRIBE first-frame deadline** (§06
F10-1d, default **10 s**), which covers the `AUTH` frame: a driver that connects then never sends `AUTH` is
**reaped**. The **first routed frame MUST be the `AUTH`** — a non-`AUTH` first frame is a **`PROTOCOL_VIOLATION`**
close (§07 code 10). Because `AUTH` is **not** acknowledged (there is no `AUTH-OK` frame), a driver **MAY
pipeline** its first business frame(s) **immediately behind** the `AUTH` without a round-trip: while the first
authentication resolves, the server **buffers** up to **8** such frames and **replays** them once it succeeds
(or discards them on `AUTH_FAIL`) — §06 F6A-6. The `PROTOCOL_VIOLATION` is an **ordering** fault, not the
pipelining: a frame **before** the `AUTH`, a **second `AUTH`**, or **more than 8** frames pipelined behind it.
A driver **MUST** send its `AUTH` frame **first** and promptly after the connection is established.

**AU4-6 (`REFRESH_AUTH` renews the SAME identity, only when authenticated).** After authentication a driver
**MAY** send a **`REFRESH_AUTH`** frame (§06 §6A) to **extend the session lifetime**. `REFRESH_AUTH` is valid
**only** on an already-authenticated connection and renews the **same** identity: the driver's identity is
**fixed at first authentication** and is **not re-bound** in v1. Consequently a `REFRESH_AUTH` whose credential
resolves to a **different identity** is **`AUTH_FAIL`**; an over-cap or otherwise-rejected refresh credential is
**`CREDENTIAL_EXPIRED`** (§07 code 13); and a stray **`AUTH` on an already-authenticated connection** is a
**`PROTOCOL_VIOLATION`**. A driver **MUST NOT** attempt to switch identity by `REFRESH_AUTH` — it opens a new
connection instead.

**AU4-7 (business frames after — or pipelined behind — auth).** A driver **MUST NOT** send a business frame
(`SUBSCRIBE`/`WATCH_CREATE`/`CURSOR_ACK`/…) **before** its `AUTH` frame (certificate-less path) or **before** the
handshake completes (mTLS path) — a business frame that **precedes** the credential is a **`PROTOCOL_VIOLATION`**.
On the certificate-less path a driver **MAY**, however, **pipeline** business frames **immediately behind** the
single `AUTH` (there is no `AUTH-OK` ack to wait for): the server buffers up to **8** and replays them on success,
discards them on `AUTH_FAIL` (§06 F6A-6, AU4-5). The invariant is that the credential is the **first** frame —
not that the driver must await an ack it will never receive.

---

## 5. Error taxonomy (the 401 side; composes with §1 §7)

> *The consolidated cross-section taxonomy is [§07](07-errors.md) (the single source of truth); this section's
> authentication (`401`, and the `503`-class "authenticator unavailable") rows are restated there. Where they
> overlap, §07 and this section **MUST** agree.*

**AU5-1 (401 unauthenticated vs 403 forbidden).** The authentication/authorization outcomes use the deployed
control-plane taxonomy of §1 [§7](01-paths-and-access.md#7-error-taxonomy) — the 401/403 rows are identical;
this section **extends** it with an authentication-specific **"authenticator unavailable"** row (AU5-2):

| Condition | Owner | Unary (HTTP) | Streaming (watch) |
|---|---|---|---|
| Missing / blank / malformed / **invalid** credential | **authenticator** (this section) | **401** + `WWW-Authenticate: Bearer` | terminal close, `401`-class `ErrorCode` |
| Authenticated, but capability not granted | **in-core authz** (§1 §5–§6) | **403** | terminal close, `403`-class `ErrorCode` |
| Configured authenticator **unavailable** (fail-closed) | **resolver** (RA-1) | **401** or **503** | terminal close, `401`/`503`-class |

**AU5-2 (fail-closed — no silent downgrade).** If a server's configured authenticator is **unavailable** (e.g.
an OIDC issuer/JWKS is unreachable), the server **MUST** reject the request (a `401`- or `503`-class outcome)
and **MUST NOT** silently fall through to a weaker authenticator or to anonymous access
([`authenticator-spi.md`](../../archive/design/auth-spi/authenticator-spi.md) §5, RA-1). A driver **SHOULD** treat a
`503`-class auth outcome as **retryable** (the issuer may recover) and a `401` as **(re)authenticate**. This
retryable signal exists **only on the HTTP plane**: the **edge** streaming taxonomy is frozen at 13 codes, so an
`AuthResult.Unavailable` there is surfaced as **`AUTH_FAIL`** (§07 E4-2) and the driver recovers a transient
outage through a **bounded reconnect-with-backoff** rather than a distinct retryable code.

**AU5-3 (never echo the credential).** A `401` response **MUST NOT** echo the presented credential, and a
driver **MUST NOT** log a credential. The server does not
([`built-reality.md`](../../archive/design/auth-spi/built-reality.md) §3); a conforming driver matches it.

**AU5-4 (driver reaction — normative).** A driver **MUST** treat **401** as "(re)authenticate — the credential
is missing/invalid for this server" and **403** as "**permanently forbidden** for this principal — do not
retry unchanged" (identical to §1 [A7-2](01-paths-and-access.md#7-error-taxonomy)). A driver **MUST NOT** retry
a `403` with the same credential and target, and **MUST NOT** respond to a `401` by retrying the *same* invalid
credential in a tight loop.

**AU5-5 (audit).** Authentication **failures** (401, and watch authn rejects) are security-relevant and the
server audits them; a driver needs no action beyond AU5-4. (Successful reads are not audited per-event — a DoS
concern, §1 [A7-2](01-paths-and-access.md#7-error-taxonomy).)

**AU5-6 (credential expiry, lead-time, and proactive refresh — normative).** A long-lived **authenticated edge**
connection is closed when its credential **expires**. The server closes at **`expiry + a small clock-skew
leeway`** (default **60 s**) with **`CREDENTIAL_EXPIRED`** (§07 code 13), **never before** — the leeway absorbs
skew between the issuing authority (IdP / CA) and the server. By credential kind:

- a **static** bearer/basic token has a server-side **session-lifetime cap** (default **1 h**, on the server
  clock, **no** leeway). A driver **SHOULD** send a `REFRESH_AUTH` before it lapses.
- an **OIDC/JWT** token expires at its **`exp`**; the server closes at **`exp + leeway`**. A driver **SHOULD**
  refresh within a **lead-time window `W` before `exp`** (server default `W = clamp(0.20·lifetime, 30 s, 5 m)`)
  by sending a `REFRESH_AUTH` carrying a **freshly-minted** token — a refresh inside the window is never cut off.
- an **mTLS client certificate** (when `notAfter` enforcement is enabled) closes at **`notAfter + leeway`**. A
  certificate **cannot** refresh in-band, so `CREDENTIAL_EXPIRED` here is a **reconnect** signal: the driver
  **reconnects with its rotated certificate** (lead-time window default `clamp(0.10·lifetime, 5 m, 1 h)`).

Online certificate **revocation** (an off / lax / strict posture) may **additionally** reject an edge client
certificate at admission with **`AUTH_FAIL`** (a revoked cert, or — under strict — an unreachable responder).
A driver **MUST** treat `CREDENTIAL_EXPIRED` as **"re-authenticate / reconnect"** — distinct from a codec bug
and from a permanent `403` — and **SHOULD** refresh **proactively** rather than waiting for the close. (These
windows/leeway are **server policy defaults**, informative here; the driver-visible contract is the
`CREDENTIAL_EXPIRED` close and the proactive-refresh recommendation.)

---

## 6. The authenticated principal feeds authorization (the seam, driver-visible consequences)

**AU6-1 (one principal, both planes).** The identity the server derives from the driver's credential — the
**principal** — is the **same** on the control-plane and edge planes
([`authn-authz-boundary.md`](../../archive/design/auth-spi/authn-authz-boundary.md), RA-5). A driver authenticating the
**same** credential on both surfaces is the **same** principal to the authorization engine. A driver **MUST
NOT** expect a credential to confer different identity on the two planes.

**AU6-2 (authentication ≠ authorization).** A successful authentication (a `200`/established session) means
only that the server knows *who* the driver is — **not** that any operation is permitted. Authorization is
evaluated separately (§1 §5–§6) and MAY still yield `403`. A driver **MUST** distinguish the two: a `401` is
about the credential; a `403` is about the principal's capabilities.

**AU6-3 (the driver does not see roles/policies).** The server maps the driver's external identity to
**Configd roles internally** ([`authn-authz-boundary.md`](../../archive/design/auth-spi/authn-authz-boundary.md) §2); a
driver **MUST NOT** assume it can read, set, or influence its roles by anything other than presenting its
credential. A driver MAY shape requests to its expected capabilities (e.g. not attempt a `full_chain_verify`
watch without root scope — §1 [A6-3](01-paths-and-access.md#6-the-watch-authorization-contract-normative)) to
avoid predictable `403`s, but the authoritative decision is the server's.

---

## 7. Forward-compatibility

**AU7-1 (unknown mechanism / challenge fails closed).** A driver **MUST** fail closed on an authentication
mechanism, `WWW-Authenticate` challenge scheme, or auth capability it does not recognize — it **MUST NOT**
downgrade to a weaker scheme or proceed unauthenticated. (The §1 fail-closed-on-unknown rule,
[§1.3](01-paths-and-access.md#13-versioning) / [A9-4](01-paths-and-access.md#9-forward-compatibility-and-composition-with-the-watch-section).)

**AU7-2 (new server authenticators do not break older drivers).** Because the driver contract is stable
regardless of the server's authenticator (AU2-1) and a bearer token is opaque (AU2-2), a deployment MAY add or
change server-side authenticators (e.g. validate the existing bearer token as OIDC instead of a static secret,
or add an LDAP/Kubernetes authenticator) **without any driver change**. A driver **MUST NOT** encode an
assumption that ties it to a specific server authenticator.

**AU7-3 (named forward extensions).** The token-bearing edge `AUTH`/`REFRESH_AUTH` frame is **now BUILT**
(AU3-3 / §06 §6A) and is **no longer** a forward extension. The following **remain** named forward extensions; a
driver **MUST** fail closed if it has not negotiated them rather than assuming them: a **Configd-issued auth
session/token** (AU2-3); a **multi-leg mutual-challenge** mechanism beyond the single-shot present-a-credential
model — **Kerberos/SPNEGO, SCRAM/SASL, RADIUS, WebAuthn, SAML redirect** (these need a back-and-forth the v1
contract does not define;
[`../../archive/design/auth-spi/authenticator-spi.md`](../../archive/design/auth-spi/authenticator-spi.md) §3, §10);
and a **token-bearing interior (node-join) auth frame** (AU3-5 — the cluster interior is mTLS-only today, and a
token node marker is dormant/fail-closed). New `Principal` attributes/claims the server may attach are
**additive** — a driver **MUST** ignore attributes it does not recognize (it does not consume them anyway,
AU6-3).

---

## 8. Composition with §1 and §2

This section is built to compose with §1 (paths-and-access) and §2 (watches); the integration points, stated so
the RFC stays coherent:

**AU8-1 (the 401/403 split is shared).** §03 owns the **401** (authentication) side; §1 [§5–§6](01-paths-and-access.md#5-capability-model-and-authorization)
own the **403** (authorization) side; the **shared taxonomy table** is §1 [§7](01-paths-and-access.md#7-error-taxonomy),
restated for the connection lifecycle in AU5-1. Neither section redefines the other's side.

**AU8-2 (watch authentication precedes watch authorization, which precedes data).** On the watch path the order
is: **(1)** authenticate (mTLS handshake, AU4-1) → **(2)** authorize the subscription as a streaming read,
**before any data frame** (§1 [§6](01-paths-and-access.md#6-the-watch-authorization-contract-normative),
restated by `02-watches.md`) → **(3)** stream. A driver **MUST** expect a terminal `401`-class close if the
handshake/identity is unacceptable and a terminal `403`-class close if the subscription exceeds its grant —
**with no data frame emitted first** in either case (§1 [A6-5](01-paths-and-access.md#6-the-watch-authorization-contract-normative)).

**AU8-3 (the principal is the one §1 authorizes).** The principal this section establishes (AU6-1) is exactly
the principal §1's evaluation rule (§1 [A5-4](01-paths-and-access.md#53-evaluation-normative--identical-across-drivers-and-server))
and the watch-authz contract (§1 §6) operate on. The pluggable authenticator does not change §1's evaluation —
it only supplies the principal ([`authn-authz-boundary.md`](../../archive/design/auth-spi/authn-authz-boundary.md) §3,
INV-WATCH-READ preserved).

**AU8-4 (scope/transport mapping).** Per §1 [§8](01-paths-and-access.md#8-compatibility-notes): the
control-plane HTTP surface is `GLOBAL`-only and bearer-authenticated (AU3-1); the binary protocol carries
`scope` as a typed field and authenticates by mTLS (AU3-2). A driver targeting the HTTP API authenticates by
bearer; a driver on the binary protocol authenticates by mTLS.

---

## 9. Summary of normative requirements (driver checklist)

- [ ] Present the credential you have — mTLS cert, bearer token, and/or HTTP Basic — and read the outcome;
      **do not depend on how the server verifies it** (the **four modes** are one shared chain, AU2-1, AU2-4).
- [ ] Treat a bearer token as **opaque** (a static secret and an OIDC/JWT are indistinguishable); never parse
      it; never assume a server-issued replayable session (AU2-2, AU2-3, AU2-4).
- [ ] HTTP: bearer/Basic in `Authorization:` over TLS only. Edge: mTLS cert at the handshake **or** (certless) a
      token/basic **`AUTH` frame** (`0x13`, wire version `0x04`; §06 §6A) — stamp `0x04` on **exactly** the auth
      frames; self-asserted identity frames (`edgeId`) are advisory (AU3-1…AU3-4).
- [ ] Edge token/basic lifecycle: send **one** pre-auth `AUTH` first (a reject ⇒ `AUTH_FAIL`, new connection —
      no hot-loop); no business frame before auth (⇒ `PROTOCOL_VIOLATION`); **`REFRESH_AUTH`** renews the **same**
      identity; on **`CREDENTIAL_EXPIRED`** re-authenticate/reconnect and **refresh proactively** before expiry
      (AU4-4…AU4-7, AU5-6).
- [ ] **Authenticate before any data/subscribe frame**; on the watch path, authn (handshake/`AUTH`) and authz
      (subscription) both precede the first data byte (AU4-1, AU4-7, AU8-2).
- [ ] Be prepared to authenticate even against an auth-disabled deployment; treat `401` as "auth required"
      (AU4-3).
- [ ] **401** = (re)authenticate (the credential); **403** = permanently forbidden (the principal) — do not
      retry a `403` unchanged; do not hot-loop a `401` (AU5-1, AU5-4).
- [ ] Treat a `503`-class auth outcome as retryable; never log/echo a credential (AU5-2, AU5-3).
- [ ] Authentication ≠ authorization; the same credential is the same principal on both planes; you cannot set
      your own roles (AU6-1…AU6-3).
- [ ] **Fail closed** on an unknown auth mechanism/challenge; new server authenticators must not require a
      driver change; named forward extensions must be negotiated, not assumed (AU7-1…AU7-3).

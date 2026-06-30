# Configd Driver Protocol RFC — §03: Authentication

**Status: DRAFT (2026-06-28). Docs-only; normative.** Third section of the Configd driver-protocol RFC. This
section specifies **how a driver authenticates** — how it presents a credential on the wire, where in the
connection lifecycle authentication happens, the **401-vs-403** boundary with authorization, and the
**forward-compatible, server-pluggable-but-driver-stable** contract. It is written so a driver in **any**
language (Rust / Go / Python / Java) authenticates **identically**, regardless of which server-side
authenticator the deployment runs.

Design rationale is in [`../../design/auth-spi/`](../../design/auth-spi/) (`built-reality.md`,
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
pluggable deployment choice — [`../../design/auth-spi/authenticator-spi.md`](../../design/auth-spi/authenticator-spi.md)),
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
token accompanies each unary request). A driver **MUST NOT** assume a server-issued token/cookie it can replay;
a Configd auth-session mechanism is a named forward extension (AU7-3), not v1.

---

## 3. Credential presentation on the wire

**AU3-1 (control-plane HTTP API — bearer).** On the control-plane admin HTTP surface, a driver **MUST** present
a bearer token in the **`Authorization: Bearer <token>`** request header (RFC 6750). The server extracts it
exactly there ([`built-reality.md`](../../design/auth-spi/built-reality.md) §1.1). A driver **MUST NOT** place
the token in a URL query parameter or log it (AU5-3).

**AU3-2 (binary edge protocol — mTLS at handshake).** On the binary edge (fan-out / watch) protocol,
authentication in v1 is the **mTLS handshake**: the driver **MUST** present a client certificate, and the
server requires one (`setNeedClientAuth(true)` —
[`built-reality.md`](../../design/auth-spi/built-reality.md) §1.2). The verified certificate identity is
**authoritative**; any identity field the driver also places in a frame (e.g. an `edgeId`) is **advisory** and
the server **MUST** override it with the certificate identity over mTLS
([`built-reality.md`](../../design/auth-spi/built-reality.md) §1.2). A driver **MUST NOT** rely on a
self-asserted identity frame being trusted.

**AU3-3 (a bearer token on the binary protocol — forward slot).** v1 of the binary protocol authenticates by
mTLS only (AU3-2). A **token-bearing authentication frame** on the binary protocol (so a bearer/OIDC token can
be presented to the edge, not only a certificate) is a **named forward extension** (AU7-3); a driver **MUST**
fail closed if it does not negotiate this capability rather than assuming it.

**AU3-4 (transport security is REQUIRED for a bearer token).** A driver **MUST** present a bearer token only
over a TLS-secured transport. A driver **MUST NOT** send a bearer token over plaintext. (mTLS already implies
TLS; a bearer token over the HTTP API requires HTTPS.)

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
  [`built-reality.md`](../../design/auth-spi/built-reality.md) §2.2).

**AU4-2 (per-connection vs per-request).** mTLS identity is established **once per connection** (the handshake)
and is stable for the connection's life. A bearer token on the HTTP API is presented **per request**. A driver
**MUST NOT** assume an HTTP bearer credential persists server-side across requests (AU2-3).

**AU4-3 (auth-disabled deployments).** A server **MAY** run with authentication **disabled** (a deployment
choice that prints a loud server-side warning —
[`built-reality.md`](../../design/auth-spi/built-reality.md) §1.1). A driver **MUST** still be prepared to
present a credential and **MUST** treat a `401` (AU5) as "authentication is required here" even if a prior
connection to a different deployment did not require one. A driver **MUST NOT** infer "auth is off" and stop
presenting a credential.

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
([`authenticator-spi.md`](../../design/auth-spi/authenticator-spi.md) §5, RA-1). A driver **SHOULD** treat a
`503`-class auth outcome as **retryable** (the issuer may recover) and a `401` as **(re)authenticate**.

**AU5-3 (never echo the credential).** A `401` response **MUST NOT** echo the presented credential, and a
driver **MUST NOT** log a credential. The server does not
([`built-reality.md`](../../design/auth-spi/built-reality.md) §3); a conforming driver matches it.

**AU5-4 (driver reaction — normative).** A driver **MUST** treat **401** as "(re)authenticate — the credential
is missing/invalid for this server" and **403** as "**permanently forbidden** for this principal — do not
retry unchanged" (identical to §1 [A7-2](01-paths-and-access.md#7-error-taxonomy)). A driver **MUST NOT** retry
a `403` with the same credential and target, and **MUST NOT** respond to a `401` by retrying the *same* invalid
credential in a tight loop.

**AU5-5 (audit).** Authentication **failures** (401, and watch authn rejects) are security-relevant and the
server audits them; a driver needs no action beyond AU5-4. (Successful reads are not audited per-event — a DoS
concern, §1 [A7-2](01-paths-and-access.md#7-error-taxonomy).)

---

## 6. The authenticated principal feeds authorization (the seam, driver-visible consequences)

**AU6-1 (one principal, both planes).** The identity the server derives from the driver's credential — the
**principal** — is the **same** on the control-plane and edge planes
([`authn-authz-boundary.md`](../../design/auth-spi/authn-authz-boundary.md), RA-5). A driver authenticating the
**same** credential on both surfaces is the **same** principal to the authorization engine. A driver **MUST
NOT** expect a credential to confer different identity on the two planes.

**AU6-2 (authentication ≠ authorization).** A successful authentication (a `200`/established session) means
only that the server knows *who* the driver is — **not** that any operation is permitted. Authorization is
evaluated separately (§1 §5–§6) and MAY still yield `403`. A driver **MUST** distinguish the two: a `401` is
about the credential; a `403` is about the principal's capabilities.

**AU6-3 (the driver does not see roles/policies).** The server maps the driver's external identity to
**Configd roles internally** ([`authn-authz-boundary.md`](../../design/auth-spi/authn-authz-boundary.md) §2); a
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

**AU7-3 (named forward extensions).** The following are **named** forward extensions; a driver **MUST** fail
closed if it has not negotiated them rather than assuming them: a **token-bearing auth frame on the binary
protocol** (AU3-3, so a bearer/OIDC token reaches the edge); a **Configd-issued auth session/token** (AU2-3); a
**multi-leg mutual-challenge** mechanism beyond the single-shot present-a-credential model — **Kerberos/SPNEGO,
SCRAM/SASL, RADIUS, WebAuthn, SAML redirect** (these need a back-and-forth the v1 contract does not define;
[`../../design/auth-spi/authenticator-spi.md`](../../design/auth-spi/authenticator-spi.md) §3, §10). New
`Principal` attributes/claims the server may attach are **additive** — a driver **MUST** ignore attributes it
does not recognize (it does not consume them anyway, AU6-3).

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
it only supplies the principal ([`authn-authz-boundary.md`](../../design/auth-spi/authn-authz-boundary.md) §3,
INV-WATCH-READ preserved).

**AU8-4 (scope/transport mapping).** Per §1 [§8](01-paths-and-access.md#8-compatibility-notes): the
control-plane HTTP surface is `GLOBAL`-only and bearer-authenticated (AU3-1); the binary protocol carries
`scope` as a typed field and authenticates by mTLS (AU3-2). A driver targeting the HTTP API authenticates by
bearer; a driver on the binary protocol authenticates by mTLS.

---

## 9. Summary of normative requirements (driver checklist)

- [ ] Present the credential you have — mTLS cert and/or bearer token — and read the outcome; **do not depend
      on how the server verifies it** (AU2-1, AU2-2).
- [ ] Treat a bearer token as **opaque**; never parse it; never assume a server-issued replayable session
      (AU2-2, AU2-3).
- [ ] Bearer in `Authorization: Bearer` over TLS only; mTLS cert at the handshake; self-asserted identity
      frames are advisory (AU3-1…AU3-4).
- [ ] **Authenticate before any data/subscribe frame**; on the watch path, authn (handshake) and authz
      (subscription) both precede the first data byte (AU4-1, AU8-2).
- [ ] Be prepared to authenticate even against an auth-disabled deployment; treat `401` as "auth required"
      (AU4-3).
- [ ] **401** = (re)authenticate (the credential); **403** = permanently forbidden (the principal) — do not
      retry a `403` unchanged; do not hot-loop a `401` (AU5-1, AU5-4).
- [ ] Treat a `503`-class auth outcome as retryable; never log/echo a credential (AU5-2, AU5-3).
- [ ] Authentication ≠ authorization; the same credential is the same principal on both planes; you cannot set
      your own roles (AU6-1…AU6-3).
- [ ] **Fail closed** on an unknown auth mechanism/challenge; new server authenticators must not require a
      driver change; named forward extensions must be negotiated, not assumed (AU7-1…AU7-3).

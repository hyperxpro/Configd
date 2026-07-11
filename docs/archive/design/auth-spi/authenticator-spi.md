# The `Authenticator` SPI -- interface, the `Principal` seam, resolution, fail-closed, providers

A compile-checked signature sketch lives in [`sketch/`](sketch/) and is the normative reference for the
signatures quoted here (it compiles and its design-contract checks pass on Corretto JDK 25). Reads on from
[`built-reality.md`](built-reality.md) (the N=2 it generalizes), [`prior-art.md`](prior-art.md)
(Vault/JAAS/servlet/SPIFFE), and the namespace access-control design
([`../namespace-model/access-control.md`](../namespace-model/access-control.md)) the produced `Principal`
feeds. The boundary to the in-core authz engine is [`authn-authz-boundary.md`](authn-authz-boundary.md).

---

## 1. The interface's narrow job -- produce a verified `Principal`, nothing more

**Authentication is "who is this caller?"** An `Authenticator` takes a request's **credential** (an mTLS peer
cert chain, a bearer/JWT token, …) and returns a **verified `Principal`** (a stable id + the Configd roles the
authz engine keys on + identity attributes) -- or a **typed rejection**. That is *all* it does. It is
deliberately **NOT** an authorization API:

- It exposes **`authenticate`** (verify a credential → `Principal`) and **`canAttempt`** (cheap type
  dispatch), plus **`type()`** (the discovery discriminator).
- It **MUST NOT** decide whether a principal may perform an operation on a path. That is the **in-core**
  namespace authz engine's job ([`authn-authz-boundary.md`](authn-authz-boundary.md)). A pluggable authz black
  box would destroy the consistency guarantees (policy-as-config, INV-WATCH-READ) -- so authz stays in-core and
  **only authentication is the SPI**. This split is the whole design (the Vault auth-method → core-policy
  split, [`prior-art.md`](prior-art.md) §1).

This mirrors the KMS-SPI's discipline of a **small, single-purpose interface whose shape forbids the dangerous
thing** ([`../../research/kms-spi/kms-provider-spi.md`](../../research/kms-spi/kms-provider-spi.md) §1). For
the KMS provider the forbidden thing was a per-record `encrypt`; here it is an authorization decision -- there
is **no `mayAccess(path)` method** on which an implementer could make one.

The authenticator sits at one end of a two-stage pipeline; the `Principal` is the seam:

```
  Credential  (mTLS cert chain | bearer token | password | headers -- the wire material)
     │  the Authenticator's domain: VERIFY → identity. Pluggable. Produces ▼
  Principal   (id + Configd roles + attributes + provenance -- NEVER the credential)
     │  the in-core authz engine's domain (authn-authz-boundary.md): roles → policies → decision
     ▼
  Decision    (READ/LIST/WRITE/WATCH/ADMIN on (scope, path) -- the namespace engine, NOT this SPI)
```

---

## 2. The interface contract

The full, compile-checked source is in [`sketch/io/configd/authn/`](sketch/). The contract in signatures:

```java
public interface Authenticator {

    String     type();                       // discovery discriminator: "mtls", "bearer", "oidc", ...

    boolean    canAttempt(Credential c);     // cheap TYPE dispatch: "I handle bearer tokens" -- no validation

    AuthResult authenticate(Credential c)    // VERIFY → Principal, or a typed rejection
                   throws AuthnUnavailableException;   // configured-but-unavailable → fail closed (§5 RA-1)
}
```

```java
public sealed interface AuthResult {
    record Authenticated(Principal principal) implements AuthResult {}
    record Rejected(RejectReason reason, String detail) implements AuthResult {}   // never echoes the credential
}

public enum RejectReason {
    NO_CREDENTIAL,             // nothing presented (→ 401)
    INVALID_CREDENTIAL,        // owned by this authenticator and bad: expired/forged/untrusted (→ 401, HARD STOP §4)
    NOT_THIS_AUTHENTICATOR     // recognised the type but it isn't mine (e.g. a JWT for another issuer) → try next
}
```

`AuthResult` **is `sealed`** -- its two outcomes are a closed set, exactly like the built
`AuthInterceptor.AuthResult` ([`built-reality.md`](built-reality.md) §1.1) it generalises. `Credential`
([§3](#3-the-credential-abstraction)) is intentionally **not** sealed at the case level for *implementers* -- the authz consequence is that an authenticator claims credentials by `canAttempt` rather than by exhaustive
`switch`.

### 2.1 Relationship to the built `AuthInterceptor` (no regression -- it *is* this, generalised)

| Built (`io.configd.api`) | SPI (`io.configd.authn`) |
|---|---|
| `TokenValidator.validate(String) → AuthResult` | `Authenticator.authenticate(Credential) → AuthResult` (any credential, not just a token string) |
| `AuthResult.Authenticated(String principal, Set<String> roles)` | `AuthResult.Authenticated(Principal)` where `Principal` carries `id + roles + attributes + provenance` |
| `AuthResult.Denied(String reason)` | `AuthResult.Rejected(RejectReason, String detail)` -- typed reason |

The built bearer `TokenValidator` lambda ([`built-reality.md`](built-reality.md) §1.1) becomes the in-core
`BearerTokenAuthenticator` ([§7](#7-default-providers--in-core-zero-new-dependency)) unchanged in behavior;
the built edge cert-DN extraction becomes the in-core `MtlsAuthenticator`. **The SPI generalises the existing
N = 2; it does not change either mechanism's verification.**

---

## 3. The `Credential` abstraction

A `Credential` is the **transport-abstract** material an authenticator verifies. The built-in shapes cover the
two built mechanisms *and* every named future provider:

```java
public sealed interface Credential
        permits Credential.CertChain, Credential.BearerToken, Credential.Password, Credential.Headers {

    record CertChain(List<X509Certificate> chain) implements Credential {}   // mTLS -- already TLS-verified
    record BearerToken(String token)              implements Credential {}   // bearer / JWT (redacted toString)
    record Password(String username, char[] secret) implements Credential {} // LDAP bind (wipeable secret)
    record Headers(Map<String,String> headers)    implements Credential {}   // cloud-IAM signed headers / custom
}
```

- **`CertChain`** -- the **already-verified** peer chain (the TLS stack ran `setNeedClientAuth(true)`;
  [`built-reality.md`](built-reality.md) §1.2). The authenticator extracts identity (Subject DN today; a SAN
  URI / SPIFFE ID for a SPIFFE deployment, [`prior-art.md`](prior-art.md) §4.2) -- it does **not** re-do
  path validation, which is the platform's job (RA-6).
- **`BearerToken`** -- the `Authorization: Bearer <token>` value, or a token frame on the binary protocol. Its
  `toString()` is **redacted** (length only) so the credential never reaches a log/audit line -- the built
  *"never echo the token"* rule ([`built-reality.md`](built-reality.md) §3) made structural.
- **`Password`** -- username + a **`char[]`** secret (wipeable, never a `String`) for an LDAP bind flow.
- **`Headers`** -- a generic carrier for signed-header schemes (cloud IAM / custom), so those don't need a new
  credential type either.

**Why sealed is the right call here (and where it differs from `WrappedKey`).** Every **single-credential,
request-shaped** provider -- the ones this SPI targets -- maps onto one of these: **OIDC → `BearerToken`,
LDAP → `Password`, Kubernetes token-review → `BearerToken`, cloud-IAM → `Headers`.** No such provider module
needs a *new* credential shape -- a credential is a **wire/transport concern**, not a provider-SDK concern (the
provider consumes an existing shape). A genuinely novel *single-shot* credential is therefore a small,
**versioned core addition** (like adding a wire frame to the closed
[`EdgeFrame`](../../../rfc/driver-protocol/01-paths-and-access.md) set), not a per-provider fork. Sealing buys
**exhaustiveness** (the transport layer handles a closed set) without blocking the provider modules
([§8](#8-module-layering--the-core-pulls-in-no-provider-sdk)) -- they implement `Authenticator`, which is an
open interface.

> **Honest limit -- what the sealed set + single-shot `authenticate` does NOT cover.** **Multi-leg
> challenge-response** mechanisms -- Kerberos/SPNEGO, SCRAM (SASL), RADIUS Access-Challenge, WebAuthn -- and
> **SAML's redirect/POST flow** are **not** expressible as a one-shot `authenticate(Credential) → AuthResult`.
> Their gap is the **interaction model** (a back-and-forth), not a missing enum case, so they are **not** a
> "rare credential add" -- they require an interface-level **mutual-challenge extension** (RFC §3 AU7-3, a named
> forward extension), exactly like a new wire conversation. Stuffing a SAML assertion into `Headers`/`BearerToken`
> is a **type lie** to avoid (a SAML blob arriving as a `BearerToken` would be hard-rejected by the catch-all
> bearer authenticator or fault an OIDC peek). The sealed-`Credential` claim is therefore scoped to
> **single-credential, request-shaped** providers; challenge-response is deferred with eyes open, not
> overclaimed as covered.

---

## 4. The `Principal` -- the seam type

The single value that crosses the authn→authz boundary
([`authn-authz-boundary.md`](authn-authz-boundary.md)). Typed, immutable, and it **never carries the
credential**.

```java
public record Principal(
        String id,                       // stable subject identifier (cert DN, token subject, oidc iss#sub)
        Set<String> roles,               // the Configd roles the authz engine keys on (already-mapped -- boundary §2)
        Map<String,String> attributes,   // identity claims for audit / future ABAC (oidc claims, SAN URI, tenant)
        String authenticator) {          // PROVENANCE: the type() that produced this ("mtls"/"bearer"/"oidc")

    public Principal {                   // compact ctor: validate + defensively copy → effectively immutable
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("principal id must not be blank");
        roles      = Set.copyOf(roles);
        attributes = Map.copyOf(attributes);
        Objects.requireNonNull(authenticator, "authenticator");
    }
    // toString() shows id, roles, authenticator, and attribute KEYS only (values may be sensitive claims);
    // there is NO field that can hold a credential (RA-3 is structural, not a discipline).
}
```

- **`id`** -- the stable subject identifier. mTLS: the Subject DN (the built behavior,
  [`built-reality.md`](built-reality.md) §1.2). OIDC: `sub`, namespaced by issuer (`iss#sub`) so two issuers
  can't collide. Bearer: the configured principal (`"root"` today).
- **`roles`** -- the set the authz engine keys on. These are **Configd roles**, already mapped from the
  external identity by the authenticator ([`authn-authz-boundary.md`](authn-authz-boundary.md) §2) -- the authz
  engine never sees an OIDC claim or LDAP group.
  This is where the built `Authenticated.roles()` ([`built-reality.md`](built-reality.md) §2.3, *carried but
  unused*) finally lands a consumer.
- **`attributes`** -- identity claims (OIDC claims, the SAN URI, a tenant id) for **audit** and **future ABAC**.
  Not consulted by the role-based authz this design specifies; a forward slot.
- **`authenticator`** -- **provenance**: the `type()` of the authenticator that minted this principal, so an
  audit line can record *"principal X authenticated via `oidc`"*. (Vault records the auth-method accessor on a
  token for exactly this; [`prior-art.md`](prior-art.md) §1.)

**`Principal.roles` depends on the namespace O-6 decision** (roles → policies, recommended). If O-6 is
confirmed, `roles` is the live key into the policy engine. **If O-6 is reversed to per-principal grants**, the
engine keys on `Principal.id` (as the built `AclService.isAllowed(principal, …)` does today) and `roles`
becomes advisory -- the `Principal` carries both, so the design is robust either way
([`authn-authz-boundary.md`](authn-authz-boundary.md) §4).

---

## 5. Multiple authenticators -- resolution + the fail-closed contract

A deployment may enable several authenticators at once (mTLS **and** bearer **and** OIDC -- the built N = 2 is
already two). The resolution must be **ordered, type-dispatched, and fail-closed** -- never "try each until one
says yes," which is how a forged/unavailable credential slips through a weaker path.

### 5.1 Resolution (recommended) -- credential-type dispatch + first-definitive

```
resolve(credential):
  attempted = false
  for auth in orderedChain:
     try:
        if not auth.canAttempt(credential): continue            # TYPE dispatch (cert→mtls, token→bearer/oidc)
        attempted = true
        result = auth.authenticate(credential)
     catch AuthnUnavailableException:  return FAIL_CLOSED        # RA-1  STOP -- never fall through
     catch any other throwable:        return FAIL_CLOSED        # RA-1  STOP -- canAttempt OR authenticate fault
     switch result:
        Authenticated(p):                 return AUTHENTICATED(p)  #       STOP -- first acceptance wins
        Rejected(INVALID_CREDENTIAL, d):  return UNAUTHENTICATED(d)# RA-2  STOP -- owned + bad: never fall through
        Rejected(NOT_THIS_AUTHENTICATOR): continue                #       not mine: try the next
        Rejected(NO_CREDENTIAL):          continue
  return UNAUTHENTICATED(attempted ? "no authenticator accepted the credential"
                                   : "no/unsupported credential")  # RA-4
```

The load-bearing rules and their JAAS lineage ([`prior-art.md`](prior-art.md) §2.3):

| Outcome | Action | JAAS analogue | Why |
|---|---|---|---|
| `AuthnUnavailableException` | **STOP, fail closed** | (none -- stricter than JAAS) | a configured-but-down OIDC MUST NOT let the request through a weaker path (RA-1) |
| any **other** throwable | **STOP, fail closed** | (none) | a buggy/hostile provider faulting MUST NOT proceed or fall through -- a defensive backstop (RA-1) |
| `Authenticated(p)` | **STOP, authenticated** | a sufficient module succeeding | first acceptance by a credential's owner wins |
| `Rejected(INVALID_CREDENTIAL)` | **STOP, 401** | **Requisite** failing (control returns) | an owned-but-forged credential is a definitive reject, **not** "try a weaker authenticator" (RA-2) |
| `Rejected(NOT_THIS_AUTHENTICATOR)` | **continue** | **Sufficient/Optional** failing (proceed) | a JWT for issuer B isn't issuer A's to reject |
| chain exhausted | **401** | no module succeeded | default-deny authn (RA-4) |

`canAttempt` filters by credential **class** (a cert never reaches a bearer authenticator); `NOT_THIS_AUTHENTICATOR`
is the finer **runtime** "recognised the type, not mine" that lets two bearer-type authenticators (a static
token and an OIDC issuer) coexist. **Dispatch (`canAttempt`, and an OIDC issuer-peek) MUST NOT throw on a
foreign/unparseable credential** -- it returns `false` / `NOT_THIS_AUTHENTICATOR`; the "any other throwable →
fail closed" row guards **both** `canAttempt` and `authenticate`, so a provider that violates this (or faults
for any reason) can never fault the chain into an open state -- a malformed token fails closed, never open.

**Recommended ordering (normative for a mixed bearer chain).** Order **specific before catch-all**: a
**catch-all** authenticator -- one whose `canAttempt` matches a whole credential type and which **hard-rejects**
(`INVALID_CREDENTIAL`) anything it doesn't own -- **MUST** come **after** every more-specific authenticator of
that type. The built static-`bearer` authenticator is exactly such a catch-all (it `INVALID_CREDENTIAL`s any
non-matching `BearerToken`, a hard stop), so an OIDC authenticator (which returns `NOT_THIS_AUTHENTICATOR` for a
foreign issuer) **MUST** precede it. The correct order is therefore **`mtls, oidc, bearer`** (the sketch's
order), **not** `mtls, bearer, oidc` -- the latter would make `bearer` hard-reject every OIDC JWT before `oidc`
ever runs, silently disabling OIDC. Across *different* credential types `canAttempt` is disjoint, so order
there is irrelevant; the rule bites only within a credential type. `Authenticators.chain` SHOULD warn if a
catch-all precedes a more-specific authenticator of the same type.

> *Rejected alternative -- per-endpoint authenticator sets.* Letting the control-plane API and the edge use
> **different** authenticators is **rejected**: it would let the two planes disagree on what a principal *is*,
> breaking RA-5 / INV-WATCH-READ. One chain, both planes.

### 5.2 The fail-closed contract (mirror the KMS-SPI R1-R5 discipline)

These are **REQUIREMENTS on every implementer and on the resolver**, encoded in the `Authenticator` Javadoc and
the type signatures (`AuthnUnavailableException` is **checked**, so the fail-closed decision can't be silently
skipped -- exactly as `KmsUnavailableException` is checked,
[`../../research/kms-spi/kms-provider-spi.md`](../../research/kms-spi/kms-provider-spi.md) §3).

- **RA-1 -- A configured-but-unavailable *or* faulting authenticator fails closed; it never downgrades.** If
  `authenticate` throws `AuthnUnavailableException` (OIDC issuer/JWKS unreachable *and* uncached, LDAP down, K8s
  TokenReview API unreachable), the resolver **STOPS** and rejects (a `503`-class "auth temporarily
  unavailable", or `401` if you prefer to hide liveness) -- it **MUST NOT** fall through to a weaker
  authenticator. The resolver **MUST ALSO** treat **any other throwable** from an authenticator as fail-closed
  (STOP, reject) -- `AuthnUnavailableException` is the *cooperative* signal; an unchecked fault (a parser
  throwing on a hostile token, an SDK error under load, a mis-implemented `canAttempt`) is the *non-cooperative*
  one, and a backstop `catch` keeps "fail-closed is structural" true even for a buggy or hostile provider
  (demonstrated in the sketch). This is the KMS R3 / `NettyTransport.select()` posture: *a silent downgrade is
  how a "the request was authenticated" claim becomes fiction.*
- **RA-2 -- Credential-validation failures fail closed.** A malformed/expired/forged/untrusted-issuer
  credential that an authenticator **owns** is `Rejected(INVALID_CREDENTIAL)` → **401, STOP**. Never anonymous,
  never "treat as unauthenticated-but-allowed," never fall through to a weaker authenticator.
- **RA-3 -- The `Principal` never carries the raw credential.** The authenticator extracts identity and
  **discards** the secret; `Principal` has **no _dedicated_ credential field**, and the `Credential` shapes
  redact in `toString` (`BearerToken`/`Password`/`Headers`/`CertChain`). Honest scope: this is *structural for
  a dedicated field* but a *discipline* for the free-form `attributes` map and the `id` -- a careless
  authenticator could smuggle a secret into `attributes` (which audit/ABAC may serialize **by value**) or into
  `id` (printed in `toString`). An authenticator therefore **MUST NOT** place credential-derived material in
  `attributes` or `id`, and audit **MUST NOT** blind-serialize attribute values. Mirrors the KMS *"`RootKey` is
  redacted; only the `WrappedKey` is persistable"* discipline and the built *"never echo the token."*
- **RA-4 -- Auth enabled + no recognised credential → 401 (default-deny authn).** When auth is **configured**,
  an unrecognised/absent credential is `401`. This is distinct from auth **disabled** -- which is a **separate
  boot flag** (the built `authInterceptor == null` open gate + the loud multi-line `WARNING` banner,
  [`built-reality.md`](built-reality.md) §1.1), **not** an empty authenticator chain (an empty chain is itself a
  startup error in `Authenticators.chain`). Auth-disabled is an explicit, loud operator choice that lives
  *outside* the chain; it is never a silent default.
- **RA-5 -- The SAME authenticator chain governs the control-plane API and the edge subscribe path
  (normative).** The chain is built **once at boot** and **shared** by both planes -- there is **no separate,
  weaker edge-only authn**, and the **same credential** resolves to the **same** `Principal` on whichever plane
  evaluates it (so a plane cannot fabricate a stronger identity than the credential warrants). This is what the
  watch-authz guarantee (INV-WATCH-READ) needs. *It does **not** make a human one principal across planes when
  they present different credential types (a cert at the edge, a token at the control plane) -- the
  cross-identity caveat, [`authn-authz-boundary.md`](authn-authz-boundary.md) §3.*
  ([§9](#9-recommended-core-wiring-not-built))
- **RA-6 -- Established libraries only; never roll crypto or token validation; the transport is the
  verification point.** mTLS verification is the **platform TLS stack** with `setNeedClientAuth(true)` (already
  built) -- the `MtlsAuthenticator` does **no chain validation**; it reads identity off an **already-verified**
  chain and **MUST NOT** be fed a self-asserted cert, preserving the built *intrinsic* verification gate with
  no regression (§7). JWT signature/JWKS validation is a **vetted library** (e.g. Nimbus JOSE+JWT), never
  hand-rolled. LDAP is JDK **JNDI**. This mirrors the KMS *"no custom crypto"* rule
  ([`../../research/kms-spi/kms-provider-spi.md`](../../research/kms-spi/kms-provider-spi.md) §9).
- **RA-7 -- Fail-loud selection.** Naming an authenticator whose module is absent from the classpath is a
  **startup error**, never a silent skip to a weaker chain -- the verbatim `NettyTransport.select()` /
  `KmsProviders.select()` posture ([§8](#8-module-layering--the-core-pulls-in-no-provider-sdk)).

### 5.3 Honest difference from the KMS SPI -- authn IS on the per-request path

The KMS SPI gets to be **boot-only**: unseal once, cache the key, drop the provider, so KMS is *structurally*
off the hot path ([`../../research/kms-spi/kms-provider-spi.md`](../../research/kms-spi/kms-provider-spi.md)
§3 R2). **Authentication cannot make that claim** -- you authenticate *every* request. The design is honest
about this and disciplines it instead of pretending:

- The **default providers have no remote dependency.** `MtlsAuthenticator` rides the TLS handshake the
  platform already performed (identity is a field read on the established `SSLSession`); `BearerTokenAuthenticator`
  is a local constant-time compare. Neither can be "unavailable" -- they satisfy RA-1 trivially, like the KMS
  `local` provider's never-throws `unwrap`.
- **Remote-validating providers (OIDC/LDAP/K8s) MUST cache verification material** (JWKS keys, LDAP
  connections, TokenReview results) within a bounded TTL, so steady-state validation is **local** and a
  transient issuer blip with warm keys still authenticates. When validation genuinely **cannot** be performed
  (cold cache + issuer unreachable), they **fail closed** (RA-1) -- they do not fall through. This bounds, but
  does not eliminate, the per-request dependency, and the design says so rather than overclaiming a structural
  guarantee it cannot provide.

---

## 6. Where this wires (the enforcement points it serves)

The SPI serves **both** built enforcement surfaces, producing the **same** `Principal` type at each
([`built-reality.md`](built-reality.md) §1, §2):

| Surface | Credential presented | Authenticator | Produces | Consumed by |
|---|---|---|---|---|
| Control-plane admin HTTP API (`AdminApiHandler.checkAuth`) | `BearerToken` | `bearer` (or `oidc`) | `Principal` | in-core authz `checkAuth(READ/WRITE/LIST)` |
| Edge fan-out subscribe (`FanOutConnectionDriver`) | `CertChain` | `mtls` | `Principal` | in-core watch-authz `checkAuth(READ ∧ WATCH)` at subscription (§namespace §6) |

The edge today turns a cert into a **raw DN string** used only for C4 admission, with **no authz**
([`built-reality.md`](built-reality.md) §2.4). Under the SPI the edge's `MtlsAuthenticator` produces a
first-class `Principal` -- which is exactly what the namespace **watch-authz** contract needs to enforce WATCH
at subscription. **The SPI is the missing piece that lets the edge authorize, not just identify.**

---

## 7. Default providers -- in-core, zero new dependency

Both already exist as behavior ([`built-reality.md`](built-reality.md) §1); the SPI factors them behind the
interface, adding **no dependency** and **no new boot failure mode** -- the parallel to the KMS `local`
provider.

- **`MtlsAuthenticator`** ([`sketch/.../MtlsAuthenticator.java`](sketch/io/configd/authn/MtlsAuthenticator.java)) -- `type() = "mtls"`; `canAttempt(c) = c instanceof CertChain`. Extracts the Subject DN (the built
  `getPeerPrincipal().getName()`), maps DN → roles via a configured mapping (or none), returns
  `Authenticated(Principal(dn, roles, {…}, "mtls"))`. A `CertChain` with no usable identity is
  `Rejected(INVALID_CREDENTIAL)` (fail closed). A SPIFFE variant reads the SAN URI instead
  ([`prior-art.md`](prior-art.md) §4.2) -- same `Principal` out.
  - **Preserve the verification gate -- no regression (normative).** The built edge derives identity via
    `ssl.getSession().getPeerPrincipal().getName()`, which **throws if the peer was not verified** under
    `setNeedClientAuth(true)` (`FanOutServer.java:284-287`) -- verification is *intrinsic* to obtaining the DN.
    The `MtlsAuthenticator` does **no chain validation**; the `CertChain` it receives **MUST** be the verified
    peer chain (Credential.CertChain doc), so production wiring **MUST** construct it only from a session that
    required and completed client-cert verification -- ideally by reading the verified
    `SSLSession.getPeerPrincipal()` (which itself throws if unverified) rather than a raw cert off an unguarded
    list. The authenticator is **never** the verification point; feeding it a self-asserted/`setWantClientAuth`
    cert is a wiring bug that defeats mTLS (RA-6).
- **`BearerTokenAuthenticator`** ([`sketch/.../BearerTokenAuthenticator.java`](sketch/io/configd/authn/BearerTokenAuthenticator.java)) -- `type() = "bearer"`; `canAttempt(c) = c instanceof BearerToken`. The built behavior verbatim:
  **constant-time** compare (`MessageDigest.isEqual`) against the configured admin token →
  `Authenticated(Principal("root", {"admin"}, {}, "bearer"))`; mismatch → `Rejected(INVALID_CREDENTIAL)`. No
  dependency, no remote call.

---

## 8. Module layering -- the core pulls in no provider SDK

Identical to the KMS-SPI layering
([`../../research/kms-spi/kms-provider-spi.md`](../../research/kms-spi/kms-provider-spi.md) §7):

```
configd-authn-spi      interface + Principal/Credential/AuthResult/AuthnUnavailableException
   (zero new deps)     + MtlsAuthenticator + BearerTokenAuthenticator + Authenticators (chain/select)
        ▲              ← the only thing configd-server/-control-plane-api compile-depends on
        ├── configd-authn-oidc     + a vetted JWT lib (e.g. com.nimbusds:nimbus-jose-jwt)   ← the one real new dep
        ├── configd-authn-ldap     + JDK-built-in JNDI/LDAP (no new dep -- cf. KMS pkcs11)
        ├── configd-authn-k8s      + raw java.net.http TokenReview (no new dep) or the k8s client
        └── configd-authn-iam-*    + the cloud provider's SDK (per cloud)
```

- The **SPI + value types + the two defaults + selection** are tiny and dependency-free → a dedicated
  **`configd-authn-spi`** module (recommended -- a clean, versioned contract), **or** fold into
  **`configd-control-plane-api`** (where `AuthInterceptor`/`AclService` already live -- fewer modules).
  *Operator decision (OA-3).*
- Each optional provider is a **separate Maven artifact** depending on the SPI + its own SDK, on the server's
  runtime classpath **only when used**. `configd-server` never compile-depends on it; the core never inherits
  a provider SDK's transitive footprint or CVE surface. Note **LDAP and K8s need no new dependency** (JDK JNDI
  / `java.net.http`) -- only **OIDC** brings a real new dependency (a JWT lib), the parallel to KMS `aws`
  needing the AWS SDK.

**Discovery / selection -- hybrid name + `ServiceLoader`, fail-loud (mirror `KmsProviders.select`).**

- **Selection by an ordered name list** (the chain): `configd.authn.providers = mtls,oidc,bearer`
  (**default `mtls,bearer`** -- the built N = 2). The list *is* the resolution order, and it **MUST** be
  ordered **specific before catch-all** (§5.1) -- a catch-all hard-rejecting authenticator like static `bearer`
  comes **last** among its credential type, so `oidc` precedes `bearer`.
- **Discovery via `ServiceLoader<AuthenticatorFactory>`**: an optional module ships
  `META-INF/services/io.configd.authn.AuthenticatorFactory`; its presence registers its `type()`. The core
  instantiates the named factories without compile-referencing any provider module -- exactly how the KMS SPI,
  JCA providers, and JDBC drivers compose.
- **`mtls` and `bearer` are built in** (wired directly, always available, zero deps) -- never `ServiceLoader`
  entries, like KMS `local`.
- **Fail-loud (RA-7):** naming `oidc` without `configd-authn-oidc` on the classpath is a **startup error**, not
  a silent drop to `mtls,bearer`. The selection reproduces the `NettyTransport.select()` line verbatim -- *"Refusing to silently … downgrade"* -- because **a silent downgrade is how a "the API requires OIDC" claim
  becomes fiction.** Two duplicate `ServiceLoader` modules advertising the same `type()` are likewise a startup
  error (no silent shadow).
- **No silent *downgrade by omission*.** Fail-loud catches an unknown provider *value*; it does **not** catch an
  absent/ineffective `configd.authn.providers` *key* silently defaulting to `mtls,bearer` (a typo'd key, an
  un-plumbed env). So when auth is enabled the operator **SHOULD** set the chain **explicitly**, the boot
  **MUST** log the effective chain (`Authenticators.availabilityReport`) so it is auditable, and a deployment
  that intends a single strong provider **MUST** drop `bearer` from the list -- the static-`bearer` catch-all is
  a standing shared-secret door (OA-5-adjacent).

### 8.1 One non-trivial provider, end-to-end -- `OidcAuthenticator` (sketch, NOT built)

Proves the SPI is implementable for a remote-validating provider and that it satisfies §5. The module depends
on the SPI + a JWT library only; the **core pulls in no JWT SDK.** *Signatures illustrative (Nimbus
JOSE+JWT); the compiled [`sketch/`](sketch/) version uses a local `TokenVerifier` seam so it builds without
the dependency -- the Nimbus wiring is described here, exactly as the KMS AWS sketch describes the AWS SDK
calls without compiling against them.*

```java
// module configd-authn-oidc  (depends on: configd-authn-spi, com.nimbusds:nimbus-jose-jwt)
public final class OidcAuthenticator implements Authenticator {

    private final String issuer;                       // the iss this authenticator owns
    private final String audience;                     // expected aud
    private final JwtVerifier verifier;                // wraps Nimbus: cached JWKS keys + signature/claim checks
    private final Map<String,String> claimToRole;      // external claim value → Configd role (mapping -- boundary §2)
    private final String roleClaim;                    // which claim holds groups/roles (e.g. "groups")

    public String type() { return "oidc"; }
    public boolean canAttempt(Credential c) { return c instanceof Credential.BearerToken; }

    public AuthResult authenticate(Credential c) throws AuthnUnavailableException {
        String jwt = ((Credential.BearerToken) c).token();
        String iss = JwtVerifier.peekIssuer(jwt);                 // unverified parse of `iss` ONLY, for dispatch
        if (!issuer.equals(iss)) {
            return new AuthResult.Rejected(RejectReason.NOT_THIS_AUTHENTICATOR, "iss=" + iss);  // §5: try next
        }
        Claims claims;
        try {
            claims = verifier.verify(jwt, audience);              // Nimbus: JWKS sig + iss/aud/exp/nbf
        } catch (JwksUnavailable e) {
            throw new AuthnUnavailableException("OIDC JWKS unreachable for " + issuer, e);  // RA-1 fail closed
        } catch (InvalidJwt e) {
            return new AuthResult.Rejected(RejectReason.INVALID_CREDENTIAL, "jwt rejected"); // RA-2 (no detail leak)
        }
        Set<String> roles = mapRoles(claims.list(roleClaim));     // external groups → CONFIGD roles (boundary §2)
        String id = claims.issuer() + "#" + claims.subject();     // iss#sub -- stable, collision-free
        return new AuthResult.Authenticated(
                new Principal(id, roles, selectedAttrs(claims), "oidc"));
    }
}
```

**How it satisfies §5:** `canAttempt` claims only bearer tokens; an `iss` mismatch yields
`NOT_THIS_AUTHENTICATOR` (a different issuer's authenticator or the static bearer one may try); a JWKS-unreachable
fault throws `AuthnUnavailableException` → the resolver **stops, fail-closed** (RA-1, never falls through to a
weaker authenticator); a bad signature/aud/exp is `INVALID_CREDENTIAL` → **401, stop** (RA-2); the JWT lib does
all crypto (RA-6); the **claim → Configd-role mapping happens here** so the authz engine only ever sees Configd
roles ([`authn-authz-boundary.md`](authn-authz-boundary.md) §2); the raw JWT never enters the `Principal`
(RA-3). The other modules are analogous: `LdapAuthenticator` (JNDI bind + a group-search → role map),
`K8sServiceAccountAuthenticator` (`TokenReview` POST → `authenticated` + `groups` → roles), cloud-IAM
(verify a signed identity → roles).

---

## 9. Recommended core wiring (not built)

Mirroring the KMS R2 wiring block -- illustrative, to show the boot seam and that the chain is built **once**
and **shared** (RA-5):

```java
// boot -- build the ordered chain ONCE from configd.authn.providers; fail-loud on an absent module (RA-7)
AuthenticatorChain authn = Authenticators.chain(config);   // mtls,bearer built-in; oidc/... via ServiceLoader

// control-plane: AdminApiHandler.checkAuth(...) replaces the inline TokenValidator (built-reality §1.1)
Resolution cp = authn.resolve(new Credential.BearerToken(bearerToken(req)));   // Authenticated(p)/Unauthenticated/Unavailable

// edge: FanOutConnectionDriver replaces the raw-DN bindIdentity (built-reality §1.2, §2.4). The CertChain MUST
// come from the VERIFIED session (setNeedClientAuth(true)) -- the authenticator is NOT the verification gate (§7, RA-6).
Resolution edge = authn.resolve(new Credential.CertChain(verifiedPeerChain(session)));

// Authenticated → feed the Principal to the SAME in-core authz engine (PolicySet / WatchAuthz over the
// replicated policy); Unauthenticated → 401; Unavailable → 503 (fail closed, never proceed -- RA-1).
```

No code is written this session; this is the shape the wiring conforms to.

---

## 10. What this SPI deliberately does NOT do (boundaries)

- **No authorization.** There is no `mayAccess(path)` / capability / policy method. The access decision is the
  **in-core** namespace engine's job ([`authn-authz-boundary.md`](authn-authz-boundary.md)). Keeping it out is
  the structural guarantee that authz stays consistent (policy-as-config, INV-WATCH-READ) across the pluggable
  boundary -- **the load-bearing constraint of the whole design.**
- **No roles → policies mapping.** The authenticator produces **role names**; resolving roles → policies →
  rules is the in-core engine (the namespace design). The SPI doesn't know what a role *grants*.
- **No Configd-minted session/token.** Unlike Vault (which issues a token carrying policies), this design
  **re-authenticates the presented credential** -- mTLS per connection, bearer per request. A Configd session
  token / auth caching layer is a **named future extension**, not part of this design (it would add a
  token-issuance + revocation surface; deferred deliberately).
- **No multi-leg challenge-response.** The single-shot `authenticate(Credential) → AuthResult` covers
  request-shaped providers (OIDC/LDAP/K8s/IAM, §3). **Kerberos/SPNEGO, SCRAM/SASL, RADIUS, WebAuthn, and SAML
  redirect** need a back-and-forth interaction model -- a named **mutual-challenge forward extension** (RFC §3
  AU7-3), interface-level work, **not** a credential-enum add. Not covered, and not overclaimed as covered.
- **No custom crypto / token validation (RA-6).** Providers wrap the platform TLS, a vetted JWT lib, JNDI -- no primitive is rolled here.
- **No change to the in-core authz engine, and authz is never a plug-in.** This is restated because it is the
  one thing the design must not do.

---

## 11. Open decisions for the operator (carried to the handoff)

1. **OA-1 -- Multi-authenticator resolution** (§5): credential-type dispatch + first-definitive + fail-closed
   (recommended) vs per-endpoint sets (rejected -- breaks RA-5) vs single-authenticator-only.
2. **OA-2 -- Which future provider first** -- `mtls`+`bearer` ship as the in-core defaults; **OIDC** is the most
   likely first optional module (cloud-native deployments), then K8s / LDAP / cloud-IAM as named.
3. **OA-3 -- SPI module placement** -- dedicated `configd-authn-spi` (recommended) vs fold into
   `configd-control-plane-api` (where `AuthInterceptor`/`AclService` live).
4. **OA-4 -- Discovery** -- hybrid `ServiceLoader` + ordered-name list (recommended, consistent with KMS) vs an
   explicit registry.
5. **OA-5 -- The edge `"plaintext"`/anonymous case when auth is enabled** -- reject (fail-closed, recommended)
   vs allow an anonymous no-roles principal (test/single-node only). (Today the edge has no authz at all, so
   this is a new, deliberate choice.)
6. **OA-6 -- `Principal.roles` shape** -- depends on the namespace **O-6** (roles → policies, recommended);
   design to it, with the per-principal fallback noted (§4, [`authn-authz-boundary.md`](authn-authz-boundary.md)
   §4).

All are **designed-in now** so that when the namespace authz is wired and a provider is built, they slot in
with **no core change beyond the seam** -- interface-first, as intended.

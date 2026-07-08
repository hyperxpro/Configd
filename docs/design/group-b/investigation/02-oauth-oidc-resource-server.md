# Group B §2.2 — OAuth2/OIDC Resource-Server (any-IdP): Investigation Findings

**Status: investigation, read-only. No production code changed.** Session 2026-07-06. Grounds the OIDC
resource-server design against the wire-protocol RFCs, four reference IdPs, and Configd's actual auth seam.
Companion to the archived auth-SPI design (`docs/archive/design/auth-spi/authenticator-spi.md`) and the
normative driver RFC §03 (`docs/rfc/driver-protocol/03-authentication.md`). Primary sources are cited by RFC
number + section; the load-bearing ones (alg-confusion, JWKS rotation, resource-server validation) are quoted
verbatim.

---

## 1. Executive summary + recommendation on every fork

Configd is a **pure OAuth 2.0 resource server**: it **validates** a presented bearer token and **never** runs a
token, redirect, or PKCE flow. This maps exactly onto the existing bearer seam (`AuthInterceptor.TokenValidator`)
and the RFC's load-bearing property AU2-1 ("the driver presents what it has; it MUST NOT depend on how the
server verifies it") — a deployment swaps static-bearer → OIDC with **zero driver change**.

The single most important production correctness surface is **JWKS key rotation** (kid rollover). The single
most important security surface is **algorithm confirmation** (reject `alg:none`, RS/HS confusion). Both are
solved correctly by a vetted library configured with an **algorithm allowlist** and a **rotation-aware JWKS
cache**; both are exactly what hand-rolled JWT code gets wrong (RFC 8725 §2.1 names the library bugs).

| # | Fork | Recommendation | Why |
|---|------|----------------|-----|
| R1 | **Library: Nimbus vs hand-roll** | **`com.nimbusds:nimbus-jose-jwt`** (lean artifact), isolated in a new optional module `configd-authn-oidc` | RA-6 already mandates "never roll crypto/token validation." Nimbus gives the alg-allowlist key-selector and the rotation-aware `JWKSourceBuilder` for free. The repo's "no src/main deps / hand-rolled codecs" rule is about **the wire framing**, not crypto. |
| R2 | **What token does Configd validate** | The **JWT access token** per **RFC 9068 §4**, NOT the OIDC id_token | A resource server validates access tokens (RFC 6749/6750). `client_credentials` (M2M) issues **no** id_token. id_token validation (OIDC Core §3.1.3.7) is the browser client's job, not Configd's. |
| R3 | **alg policy** | Configure an **explicit allowlist** `{RS256, ES256}` (default), pinned in config; reject everything else incl. `none` and any HS* | RFC 8725 §3.1 + RFC 9068 §4 ("MUST reject `alg` = `none`"); an empty/implicit allowlist re-opens RS/HS confusion. Symmetric HS* is disallowed by construction on a resource server (no shared secret with the IdP). |
| R4 | **Audience** | **Require** a configured expected `aud` (the Configd API identifier); reject if absent/mismatched | RFC 9068 §4 ("MUST reject if `aud` does not contain a resource indicator of the current resource server"); defends the substitution attack RFC 8725 §2.7. |
| R5 | **Discovery** | Fetch `.well-known/openid-configuration` (OIDC) / `oauth-authorization-server` (RFC 8414) **once at authenticator construction** to resolve `issuer` + `jwks_uri`; pin `issuer` in config and require exact match | RFC 8414 §2/§3; RFC 9068 §4 ("`iss` MUST exactly match"). Discovery is convenience; the trust anchor is the operator-pinned issuer + jwks_uri, not a runtime-discovered URL. |
| R6 | **JWKS cache/rotation** | TTL cache keyed by kid + **rate-limited refresh-on-unknown-kid** + negative cache + **serve-stale-if-warm / fail-closed-if-cold**. Defaults below (§4.2). | THE production break-point. Naive "refetch on every unknown kid" is a DoS; naive "never refetch" breaks on every rotation. Nimbus `JWKSourceBuilder` implements this policy. |
| R7 | **claims→roles mapping** | **Configurable**: a claim-path selector (dotted, e.g. `realm_access.roles`), a value type (array \| space-delimited string), an optional value→role map, optional prefix; **default deny** (no roles) when the claim is absent | Every IdP puts roles/groups in a different place (§2.6). The authenticator maps external claims → **Configd roles**; the in-core authz engine sees only Configd roles (AU6-3, boundary §2). |
| R8 | **M2M flow** | Configd = **validate only**. SDK does `client_credentials` to the IdP; presents the JWT as `Authorization: Bearer`. No new Configd surface. | The charter: "PURE resource-server, validate only, NEVER issues." |
| R9 | **Admin-UI flow** | **Delegate** the auth-code+PKCE dance. Default: **SPA does PKCE in the browser** (public client) and calls Configd with `Authorization: Bearer`; enterprise option: a **reverse proxy** (oauth2-proxy/Envoy) terminates OIDC and forwards the bearer. Configd implements **zero** redirect/callback/PKCE. | PKCE `code_verifier` is verified by the **IdP token endpoint** (RFC 7636 §4.6), not the resource server. Building a callback would make Configd a confidential client with a redirect surface — a charter violation. |
| R10 | **Module placement** | New optional Maven module **`configd-authn-oidc`** (SPI + Nimbus). Core never compile-depends on it. Selected fail-loud by name (mirror `NettyTransport.select()`). | authenticator-spi §8; keeps the JWT SDK + its transitive CVE surface (json-smart) out of core. |
| R11 | **Edge (binary) plane** | **Out of scope for v1.** OIDC plugs into the **HTTP control plane only**; the edge is mTLS-only (RFC AU3-2). A token-bearing edge frame is a named forward extension (AU3-3, AU7-3). | Don't widen scope; the edge has no token channel in v1. |
| R12 | **Fail-closed error mapping** | Unavailable authenticator (issuer/JWKS unreachable, cold cache) → **503-class** (retryable); invalid/expired/forged token → **401** `invalid_token`; authenticated-but-unauthorized → **403** | RFC AU5-2 (fail-closed, no silent downgrade); RFC 6750 §3.1 `invalid_token`. **Requires a seam extension** — the built `AuthResult` has only `{Authenticated, Denied}`; there is no "Unavailable/503" outcome today (§3). |

**Net:** build `configd-authn-oidc` with Nimbus, wire it as an additional `TokenValidator`/`Authenticator`
**ahead of** the static bearer catch-all, validate access tokens per RFC 9068 §4 with a pinned alg-allowlist and
a rotation-aware JWKS cache, map claims→Configd-roles by config, and extend the `AuthResult` seam with a
fail-closed "unavailable" (503) outcome. The Admin-UI never touches Configd's server; Configd only ever
validates a bearer.

---

## 2. Spec / reference findings (primary-source citations)

### 2.1 Discovery (RFC 8414 / OIDC Discovery)

An IdP publishes its configuration at a well-known path derived from the issuer identifier. **RFC 8414 §3**
(verbatim):

> "Authorization servers supporting metadata MUST make a JSON document containing metadata … available at a path
> formed by inserting a well-known URI string into the authorization server's issuer identifier between the host
> component and the path component… By default, the well-known URI string used is
> `/.well-known/oauth-authorization-server`. This path MUST use the `https` scheme."

OIDC uses the sibling `/.well-known/openid-configuration`. The two documents (when both exist) **MUST** be
consistent (RFC 9068 §4). The fields Configd needs (**RFC 8414 §2**, verbatim excerpts):

> `issuer` — REQUIRED. The authorization server's issuer identifier, which is a URL that uses the `https`
> scheme and has no query or fragment components… used to prevent authorization server mix-up attacks.
>
> `jwks_uri` — URL of the authorization server's JWK Set document… contains the signing key(s)… MUST use the
> `https` scheme.

**Grounding for the design:** resolve `issuer` + `jwks_uri` **once at authenticator construction**; the
operator **pins** the expected `issuer` in config, and the discovered `issuer` field MUST equal it (mix-up
defense). Do not treat discovery as a per-request or runtime-trust operation.

### 2.2 JWKS fetch + cache + ROTATION — the production break-point

There is no rotation RFC; the correct behavior is derived from how signing keys roll at real IdPs and how the
resource server must react. **The failure modes to design against:**

- **kid rollover.** IdPs rotate signing keys on a schedule (Okta/EntraID/Auth0/Keycloak all do). During an
  **overlap window** the JWKS serves **both** the retiring and the new public key; tokens signed just before
  rotation still carry the **old** `kid`, tokens minted after carry the **new** `kid`. A resource server that
  cached the JWKS and never refetches will **reject every token signed with a new kid** the moment the IdP
  rotates — a total outage at the next rotation.
- **Refetch-on-every-unknown-kid = DoS.** The naive fix ("kid not in cache → GET the JWKS") lets an attacker
  spray tokens with random `kid` values and drive **unbounded** outbound JWKS fetches, a DoS amplifier against
  both Configd and the IdP.
- **Cold-cache + issuer down = must fail closed** (RFC AU5-2), but **warm-cache + transient blip = must keep
  working** (a rotation-unrelated issuer hiccup must not break steady-state auth).

**Correct policy (recommended, industry-standard; Nimbus `JWKSourceBuilder` implements it):**

1. **Positive cache**, keyed by `kid`, TTL-bounded (honor the JWKS response `Cache-Control: max-age` if present,
   else a default TTL). Steady-state validation resolves the key **locally** — no per-request network I/O.
2. **Refresh-on-unknown-kid, rate-limited.** A token whose `kid` is absent from the cache triggers **at most
   one** JWKS refetch within a cooldown window (a minimum inter-refresh interval). Within the cooldown, an
   unknown kid is a **fast reject**, not a fetch.
3. **Negative cache.** Remember recently-seen unknown `kid`s for a short window so a spray of bogus kids is
   rejected without repeated fetch attempts.
4. **Serve-stale-if-warm / fail-closed-if-cold.** On refetch failure: if the cache holds usable keys, keep
   serving them (bounded staleness) so a blip doesn't break auth; if the cache is cold and the kid can't be
   resolved, **fail closed** (RA-1 → 503-class, never fall through to anonymous or a weaker path).
5. **Overlap-friendly eviction.** Do **not** aggressively evict the old key on first sight of a new kid; let
   old keys age out by TTL so in-flight old-kid tokens still validate during the IdP's overlap window.
6. **Bound the fetch.** `https`-only, connect/read timeout, max response bytes, max key count, **no redirects
   to a non-issuer host** (SSRF). Never follow a token-supplied `jku`/`x5u` (RFC 8725 §3.10, below).

RFC 8725 §3.10 (verbatim), on `kid`/`jku`/`x5u` handling:

> "The `kid` (key ID) header is used by the relying application to perform key lookup. Applications should
> ensure that this does not create SQL or LDAP injection vulnerabilities by validating and/or sanitizing the
> received value. Similarly, blindly following a `jku` (JWK set URL) or `x5u` (X.509 URL) header, which may
> contain an arbitrary URL, could result in server-side request forgery (SSRF) attacks."

**Design consequence:** `kid` selects a key **only** from the trusted JWKS fetched from the operator-pinned
`jwks_uri`; `jku`/`x5u` in the token are **ignored**.

### 2.3 Signature validation + algorithm confusion (RFC 8725 — verbatim, load-bearing)

The attack (**RFC 8725 §2.1**, verbatim):

> "The algorithm can be changed to `none` by an attacker, and some libraries would trust this value and
> 'validate' the JWT without checking any signature.
>
> An `RS256` (RSA, 2048 bit) parameter value can be changed into `HS256` (HMAC, SHA-256), and some libraries
> would try to validate the signature using HMAC-SHA256 and using the RSA public key as the HMAC shared secret
> (see [McLean] and [CVE-2015-9235])."

The mitigation (**RFC 8725 §3.1 "Perform Algorithm Verification"**, verbatim):

> "Libraries MUST enable the caller to specify a supported set of algorithms and MUST NOT use any other
> algorithms when performing cryptographic operations. The library MUST ensure that the `alg` or `enc` header
> specifies the same algorithm that is used for the cryptographic operation. Moreover, each key MUST be used
> with exactly one algorithm, and this MUST be checked when the cryptographic operation is performed."

And **RFC 8725 §3.2**: `none` must only ever be consumed when explicitly requested — "JWT libraries SHOULD NOT
consume JWTs using `none` unless explicitly requested by the caller." For a resource server that is **never**.

**Design consequence:** the authenticator constructs the verifier with an **explicit algorithm allowlist**
(`{RS256, ES256}` default) and a **key selector that binds public keys to asymmetric algorithms only**. This
structurally defeats: (a) `alg:none` — not in the allowlist; (b) RS/HS confusion — HS* not in the allowlist,
and each JWKS key is typed to its asymmetric alg, so an RSA public key can never be handed to an HMAC verifier.
Nimbus's `JWSVerificationKeySelector(allowedAlgs, jwkSource)` is precisely this mechanism.

### 2.4 Claims validation for a resource server (RFC 9068 §4 — verbatim, the canonical algorithm)

Configd validates a **JWT access token**. **RFC 9068 §4 "Validating JWT Access Tokens"** is the authoritative
step list (verbatim):

> "Resource servers receiving a JWT access token MUST validate it in the following manner.
>
> - The resource server MUST verify that the `typ` header value is `at+jwt` or `application/at+jwt` and reject
>   tokens carrying any other value.
> - … [encryption] …
> - The issuer identifier for the authorization server (which is typically obtained during discovery) MUST
>   exactly match the value of the `iss` claim.
> - The resource server MUST validate that the `aud` claim contains a resource indicator value corresponding to
>   an identifier the resource server expects for itself. The JWT access token MUST be rejected if `aud` does
>   not contain a resource indicator of the current resource server as a valid audience.
> - The resource server MUST validate the signature of all incoming JWT access tokens according to [RFC7515]
>   using the algorithm specified in the JWT `alg` Header Parameter. The resource server MUST reject any JWT in
>   which the value of `alg` is `none`. The resource server MUST use the keys provided by the authorization
>   server.
> - The current time MUST be before the time represented by the `exp` claim. Implementers MAY provide for some
>   small leeway, usually no more than a few minutes, to account for clock skew."

And on error reporting (RFC 9068 §4 → **RFC 6750 §3.1**): "in case of any failure in the validation checks…
the … response MUST include the error code `invalid_token`."

**`typ: at+jwt` caveat.** RFC 9068 requires `typ` = `at+jwt`, but **not every IdP stamps it** (Keycloak default
access tokens historically used `typ: JWT`; EntraID v1/v2 vary). Recommendation: make the `typ` check
**configurable** (`require-at-jwt: true|false`, default **true** where the IdP supports it) rather than
hard-failing every deployment — this is the "use explicit typing / mutually-exclusive validation" guidance of
RFC 8725 §3.11–§3.12 to avoid access-token/id-token substitution (§2.5 below), softened to real-world IdP
behavior.

For completeness, the **id_token** validation the browser client (not Configd) performs, **OIDC Core §3.1.3.7**
(verbatim excerpts): "The Issuer Identifier for the OpenID Provider … MUST exactly match the value of the `iss`
… Claim." … "The Client MUST validate that the `aud` … Claim contains its `client_id` value … The ID Token MUST
be rejected if … it contains additional audiences not trusted by the Client." … "The `alg` value SHOULD be the
default of `RS256` or the algorithm sent by the Client in the `id_token_signed_response_alg` parameter during
Registration." Note the last line is the same **alg-pinning** principle as RFC 8725 §3.1 — the expected alg
comes from configuration/registration, **not** solely from the token header.

### 2.5 Access token vs ID token — do not confuse them (RFC 8725 §2.7, §2.8)

RFC 8725 §2.7 (substitution) and §2.8 (cross-JWT confusion) require that a JWT minted for one purpose cannot be
replayed at another. For Configd:

- **Validate the ACCESS token, not the id_token.** The id_token's `aud` is the OAuth **client_id** (the app);
  the access token's `aud` is the **resource server** (Configd's API identifier). Accepting an id_token at
  Configd would be a cross-JWT confusion (§2.8) — its audience is the client, not Configd.
- Defenses: require `typ: at+jwt` where available (§3.11), and **always** require Configd's own `aud` (§3.9,
  §2.7) — an id_token will fail the `aud` = Configd-API check.

### 2.6 Per-IdP claims differences — why claims→roles MUST be configurable

Every reference IdP places authorization data in a **different** claim, with **different** nesting and value
shapes. This is why a fixed claim name cannot work and the mapping must be config.

| IdP | Issuer shape | Roles/groups claim | Shape | Scopes claim |
|-----|--------------|--------------------|-------|--------------|
| **Keycloak** | `{authServerUrl}/realms/{realm}` | realm roles: **`realm_access.roles`** (nested); client roles: `resource_access.{clientId}.roles` (nested); group membership via an added mapper: `groups` | JSON **array** | `scope` (space-delimited **string**) |
| **EntraID (Azure AD v2)** | `https://login.microsoftonline.com/{tenant}/v2.0` | app roles: **`roles`** (array); directory groups: `groups` (**object GUIDs**, needs group-claims config, may be `hasgroups` overage) | array | delegated: `scp` (space-delimited string); app-only: `roles` |
| **Okta** | `https://{org}.okta.com/oauth2/{authzServerId}` (custom authz server) | **`groups`** claim (must be added to the authz server as a groups claim/scope) | array | `scp` (array) |
| **Auth0** | `https://{tenant}.auth0.com/` | RBAC: **`permissions`** (array) if enabled, else **namespaced** custom claim e.g. `https://configd.example/roles` (Auth0 forbids non-namespaced custom claims) | array | `scope` (space-delimited string) |

**Consequence:** the mapping needs (a) a **claim-path selector** that supports **nesting** (`realm_access.roles`)
and **namespaced** names (`https://configd.example/roles`); (b) a **value type** (`array` vs
space-delimited-`string`, to cover `scope`/`scp`); (c) an optional **value→Configd-role map** (or pass-through
with a prefix); (d) **default deny** — absent claim ⇒ empty role set (authenticated but unprivileged), never a
default-grant. The authenticator emits **Configd role names only**; the in-core authz engine never sees an IdP
claim (RFC AU6-3; boundary §2).

### 2.7 The two flows (RFC 6749 client_credentials; RFC 7636 PKCE)

- **M2M** (SDK / service → Configd): the SDK runs OAuth **`client_credentials`** (RFC 6749 §4.4) against the
  IdP token endpoint (client secret, or the stronger `private_key_jwt` / mTLS client auth), receives a **JWT
  access token**, and presents it as `Authorization: Bearer` to Configd's control-plane API. **Configd
  validates only** (RFC 9068 §4). No id_token is involved (client_credentials has no user). This is the primary
  path and the first thing to build.
- **Admin-UI** (human in a browser): the authorization-code + **PKCE** flow (RFC 7636). PKCE's `code_verifier`
  is verified by the **IdP token endpoint** (RFC 7636 §4.6), **not** by the resource server, and the client
  **MUST** use `S256` (RFC 7636 §4.2, verbatim): *"If the client is capable of using `S256`, it MUST use
  `S256`, as `S256` is Mandatory To Implement (MTI) on the server."* **Configd implements none of this.** The
  browser SPA (public client) does PKCE and obtains an access token, then calls Configd with
  `Authorization: Bearer` — Configd validates **identically to M2M**. Enterprise alternative: a reverse proxy
  terminates the OIDC browser flow and forwards a validated bearer/signed header. Either way Configd's
  server-side job is one thing: validate the access token.

---

## 3. Configd seam grounding (where OIDC plugs in and produces a Principal)

**The credential enters and the outcome is produced here:**

- `configd-control-plane-api/src/main/java/io/configd/api/AuthInterceptor.java`
  - `TokenValidator.validate(String) → AuthResult` — the **plug point** (line 40).
  - `AuthResult` is **`sealed`** with exactly two cases: `Authenticated(String principal, Set<String> roles)`
    (line 24) and `Denied(String reason)` (line 33). `Authenticated.roles` is already defensively copied to an
    immutable snapshot (lines 27-30) — the OIDC validator returns the **mapped Configd roles** here.
  - `authenticate(String)` (line 61) short-circuits null/blank → `Denied("missing auth token")`.
- `configd-server/src/main/java/io/configd/server/ConfigdServer.java:745-772` — the **current wiring**: an inline
  `TokenValidator` lambda (line 757) does a constant-time (`MessageDigest.isEqual`, line 760) compare against the
  static admin token and returns `Authenticated(ROOT_PRINCIPAL, Set.of())` (line 766) or `Denied("invalid
  token")` (line 768). **The OIDC authenticator replaces/precedes this lambda.**
- `configd-server/src/main/java/io/configd/server/AdminApiHandler.java`
  - `bearerToken(AdminRequest)` (line 760) extracts the `Authorization: Bearer <token>` value — the token
    handed to the validator.
  - `checkAuth(...)` (line 696) calls `authInterceptor.authenticate(bearerToken(req))` (line 710); on
    `Authenticated` it feeds `authed.principal()` + `authed.roles()` to `aclService.isAllowed(...)` (line 722) —
    **the seam is already role-aware; the OIDC roles flow straight into the in-core authz engine unchanged.**
  - `authDenial(AuthCheck)` (line 657) maps `UNAUTHENTICATED → 401 + WWW-Authenticate: Bearer` (line 661) and
    `FORBIDDEN → 403` (line 664). This satisfies RFC AU5-1's 401/403 split for the token path.

**Seam gap (concrete, must be addressed): there is no 503 / "authenticator unavailable" outcome.** The built
`AuthResult` is `{Authenticated, Denied}`; a `Denied` always maps to **401**. RFC AU5-2 requires an unavailable
authenticator (issuer/JWKS unreachable, cold cache) to fail closed as a **503-class** retryable outcome, **not**
a 401 and **never** a silent downgrade. The OIDC work therefore needs to extend the seam with a third outcome
(e.g. `AuthResult.Unavailable(reason)`) — or a checked `AuthnUnavailableException` thrown by the validator and
mapped to 503 in `authDenial`. This mirrors the KMS SPI's checked `KmsUnavailableException`
(`configd-common/src/main/java/io/configd/common/kms/KmsUnavailableException.java`) that makes fail-closed
structural, not a discipline.

**Module + selection precedents (reuse verbatim):**
- **Module layering** parallels the KMS SPI: `configd-common/src/main/java/io/configd/common/kms/KmsProvider.java`
  (`R1–R5` fail-closed contract) → the core depends on the SPI, the heavy SDK lives in an optional module. OIDC
  gets a new `configd-authn-oidc` module carrying **only** Nimbus.
- **Fail-loud selection** reuses the `NettyTransport.select()` posture:
  `configd-netty/src/main/java/io/configd/netty/NettyTransport.java:123` — *"Refusing to silently … downgrade."*
  Naming `oidc` without `configd-authn-oidc` on the classpath is a **startup error**, never a silent drop to
  static bearer.
- **HTTP client for discovery/JWKS** has precedent in-tree: JDK `java.net.http.HttpClient` is already used at
  `configd-linz/src/main/java/io/configd/linz/client/ConfigClient.java:7-9,39` — no new HTTP dependency needed
  (Nimbus's default retriever also uses the JDK client).

**Design intent already on record:** the archived `docs/archive/design/auth-spi/authenticator-spi.md` §8.1 gives
a compiled `OidcAuthenticator` sketch (behind a `TokenVerifier`/`JwtVerifier` seam), §5.1 the resolution chain
(order **`mtls, oidc, bearer`** — OIDC **before** the static-bearer catch-all, else bearer hard-rejects every
JWT and silently disables OIDC), and §5.2 the `RA-1…RA-7` fail-closed requirements this design conforms to. RFC
§03 clauses AU2-1 (stable driver contract), AU5-2 (fail-closed), AU7-2 (new authenticators don't break drivers)
are the normative frame.

---

## 4. Recommended design (concrete)

### 4.1 Validation algorithm (step-by-step, the per-request hot path)

Given `Authorization: Bearer <jwt>` at `AdminApiHandler.checkAuth`:

1. Parse the compact JWS **without trusting it**. Peek `iss` (unverified) for **chain dispatch only**: if
   `iss` ≠ this authenticator's configured issuer → `NOT_THIS_AUTHENTICATOR` (try the next authenticator; do
   **not** throw — a foreign/garbage token must not fault the chain). (authenticator-spi §5.1)
2. Enforce the **algorithm allowlist** from the JWS header `alg` ∈ `{RS256, ES256}` (config). Reject `none` and
   any HS*/unknown → `INVALID_CREDENTIAL` (401). (RFC 8725 §3.1; RFC 9068 §4)
3. If `require-at-jwt` (default true where supported): assert `typ` ∈ `{at+jwt, application/at+jwt}`. (RFC 9068 §4)
4. Select the verification key from the **JWKS cache** by `kid` (§4.2). Unknown kid → rate-limited refetch →
   still unknown ⇒ `INVALID_CREDENTIAL` (401); JWKS unreachable + cold ⇒ `Unavailable` (503). Ignore any
   `jku`/`x5u`. (RFC 8725 §3.10)
5. **Verify the signature** with that key, bound to the header `alg` (each key ↔ exactly one asymmetric alg).
   (RFC 8725 §3.1)
6. Validate claims: `iss` **exact-match** configured issuer; `aud` **contains** the configured Configd API
   identifier; `exp` in the future; `nbf`/`iat` sane; all with a configurable **clock leeway** (default ≤ 60s).
   Any failure → `INVALID_CREDENTIAL` (401, `invalid_token`). (RFC 9068 §4; RFC 8725 §3.8/§3.9)
7. Map roles: read the configured **role claim-path**, coerce to a set, apply the value→Configd-role map;
   absent ⇒ empty set. (§4.3)
8. Emit `Authenticated(Principal(id = iss#sub, roles, attributes, provenance="oidc"))` — mapped to the built
   `AuthResult.Authenticated(iss#sub, roles)` for the current seam. The raw JWT **never** enters the principal
   (RA-3).

Steady state (warm cache) is **CPU-only** (a local signature verify + string checks) — no network I/O on the
request path; this matters because the sibling Netty-pipeline investigation (§2.1/03-netty-auth-pipeline) owns
the "must not block the event loop" constraint: only the **JWKS refresh** (rare, rate-limited) is I/O and it
**MUST** run off the event-loop / async.

### 4.2 JWKS cache/rotation policy + defaults

Implemented via Nimbus `JWKSourceBuilder.create(jwksUri).cache(ttl, refreshTimeout).rateLimited(minInterval)
.refreshAheadCache(...).retrying(true).outageTolerant(...)` + a negative-lookup guard.

| Knob | Default | Rationale |
|------|---------|-----------|
| Positive TTL | **10 min** (or JWKS `Cache-Control: max-age` if smaller) | steady-state local validation; bounded staleness |
| Refresh-ahead | **TTL − 2 min** | refresh before expiry so no request pays the fetch |
| Refresh-on-unknown-kid cooldown (rate limit) | **≥ 30 s** between forced refetches | bounds attacker-driven JWKS fetches (DoS) |
| Negative cache (unknown kid) | **30 s** | fast-reject bogus-kid sprays without refetch |
| Serve-stale-on-outage (outage-tolerant) | **up to 1 h** if cache warm | a transient issuer blip must not break steady-state auth |
| Cold-cache + unreachable | **fail closed → 503** | RA-1; never anonymous, never downgrade |
| Fetch bounds | https-only, ~2 s connect / 3 s read, ≤ 64 KiB, ≤ 20 keys, no cross-host redirect | SSRF/DoS bounds (RFC 8725 §3.10) |

### 4.3 claims→roles config shape (recommended)

```yaml
authn:
  oidc:
    issuer:  https://keycloak.example/realms/configd    # pinned; must equal discovered issuer
    audience: configd-api                                # required expected aud (RFC 9068 §4)
    discovery: true                                      # fetch .well-known once at construction
    jwks:
      ttl: 10m
      refresh-cooldown: 30s
      outage-tolerance: 1h
    algorithms: [RS256, ES256]                           # explicit allowlist; never `none`, never HS*
    clock-skew: 60s
    require-at-jwt: true
    roles:
      claim: realm_access.roles     # dotted path; supports nesting AND namespaced names (a full URI is one segment)
      value-type: array             # array | space-delimited-string  (covers `scope`/`scp`)
      map:                          # optional external-value -> Configd-role; omit for pass-through
        configd-admins: admin
        configd-readers: reader
      default: []                   # absent claim => no roles (authenticated, unprivileged) — DEFAULT DENY
```

Multiple issuers ⇒ a **list** of such blocks, ordered, each an `OidcAuthenticator` in the chain before the
static `bearer` catch-all.

### 4.4 Library + module

- **`com.nimbusds:nimbus-jose-jwt`** (the lean artifact — JWS/JWK/JWT + `JWKSourceBuilder` +
  `DefaultJWTProcessor` + `JWSVerificationKeySelector`). Transitive surface is small: `jcip-annotations` and
  `net.minidev:json-smart` (JSON accessor — keep patched; historical CVEs). Prefer this over the heavier
  `com.nimbusds:oauth2-oidc-sdk`; do the tiny discovery parse (issuer + jwks_uri) with `java.net.http` reusing
  Nimbus's bundled JSON, so no extra JSON dependency and no full OIDC SDK footprint.
- **New module `configd-authn-oidc`** depends on the auth SPI + Nimbus only. `configd-server` /
  `configd-control-plane-api` **never** compile-depend on it; it is on the runtime classpath only when the
  deployment selects `oidc`. Discovery/selection: name-list + fail-loud (mirror `NettyTransport.select()` /
  KMS `select`). The JWT SDK's CVE surface stays **out of core** — the whole point of the module split.
- **Reject hand-rolling.** RA-6 already forbids rolling token validation; RFC 8725 §2.1 names library bugs
  (alg:none, RS/HS) as the exact failure; the repo's "no src/main deps" rule targets the **wire framing**, not
  crypto. Hand-rolling JOSE would re-implement base64url, JSON-of-JWT, alg-binding, constant-time, and JWKS
  rotation — precisely the code that gets alg-confusion wrong.

### 4.5 The two flows — what Configd builds

- **M2M:** nothing beyond the validator. SDK does `client_credentials`; presents `Bearer`; Configd validates.
- **Admin-UI:** nothing server-side. SPA does auth-code+PKCE (`S256`, public client) in the browser, or a
  reverse proxy terminates OIDC; Configd validates the resulting `Bearer` identically. **Configd never
  implements redirect/callback/PKCE** — PKCE is verified by the IdP token endpoint (RFC 7636 §4.6), keeping
  Configd a pure resource server (charter: "validate only, never issues").

### 4.6 Error mapping (RFC AU5, RFC 6750)

`401 invalid_token` + `WWW-Authenticate: Bearer` (already emitted at `AdminApiHandler.java:661`) for
missing/expired/forged/alg-rejected/unknown-kid-after-refetch; **503** (retryable) for issuer/JWKS unreachable
with a cold cache (needs the new seam outcome, §3); **403** for authenticated-but-unauthorized (unchanged, the
in-core authz decision). Never echo the token (RFC AU5-3 — already honored).

---

## 5. Open questions needing an operator decision

1. **OQ-1 (seam extension).** Approve extending `AuthInterceptor.AuthResult` with a third **`Unavailable`
   (503)** outcome (or a checked `AuthnUnavailableException`) so fail-closed-vs-401 is structural? Without it,
   AU5-2's 503-class outcome cannot be produced. **Recommend: yes** (mirror `KmsUnavailableException`).
2. **OQ-2 (`typ: at+jwt`).** Default `require-at-jwt` to **true** (strict RFC 9068) or **false** (accept
   Keycloak/EntraID legacy `typ: JWT`)? **Recommend: true, per-issuer overridable** — audience + alg allowlist
   already block id-token substitution, so strict typ is defense-in-depth, not the sole guard.
3. **OQ-3 (audience source).** Configd's expected `aud` value — a single fixed API identifier, or per-scope?
   **Recommend: one fixed `configd-api` identifier**, operator sets it in each IdP.
4. **OQ-4 (config surface).** OIDC needs structured config (issuer/audience/mapping) that CLI flags don't fit.
   This rides on the arc's **Gate 1 (Config-as-SPI YAML+ENV)**. Confirm OIDC config lands in that YAML, not new
   `--flags`.
5. **OQ-5 (Admin-UI default).** SPA-PKCE-in-browser (default, zero Configd surface) vs mandated reverse proxy
   (enterprise). **Recommend: support both; document SPA-PKCE as default**, proxy as the enterprise pattern.
6. **OQ-6 (roles vs scopes).** Should Configd roles map from **roles/groups claims** (identity) or from OAuth
   **`scope`** (delegated authority), or both? **Recommend: roles/groups by default** (matches the in-core
   role-based authz), with `scope` available as an alternative claim-path for scope-centric deployments.
7. **OQ-7 (edge plane).** Confirm OIDC stays **HTTP-control-plane-only** for v1; edge stays mTLS (AU3-2). A
   token-bearing edge frame is a named forward extension (AU3-3). **Recommend: confirm out-of-scope.**

---

## 6. Keycloak Testcontainers + key-roll recipe (for the test phase)

**No Testcontainers dependency exists in the repo yet** (greenfield harness; Java 25, JUnit 5.11.4). The
de-facto Keycloak module is the community **`com.github.dasniko:testcontainers-keycloak`** (class
`KeycloakContainer`), not a core Testcontainers module.

- **Coordinates / image:** `com.github.dasniko:testcontainers-keycloak` (test scope); image
  `quay.io/keycloak/keycloak:26.4` (`new KeycloakContainer("quay.io/keycloak/keycloak:26.4")`).
- **Realm bootstrap:** `.withRealmImportFile("/configd-realm.json")` — a realm with a confidential client
  (**Service Accounts / client_credentials enabled**) + roles + a roles/groups protocol mapper.
- **Endpoints:** `keycloak.getAuthServerUrl()`; issuer = `{authServerUrl}/realms/{realm}`; JWKS =
  `{issuer}/protocol/openid-connect/certs`; token endpoint = `keycloak.getTokenEndpoint(realm)`. Admin ops via
  `keycloak.getKeycloakAdminClient()`. Token helpers: `keycloak.getAccessToken(realm, client, secret, user,
  pw)` (password grant); for **M2M** hit the token endpoint with `grant_type=client_credentials` via
  `java.net.http`.
- **Roll the signing keys mid-test (exercise the JWKS-rotation path) — one line:** via the admin client,
  `adminClient.realm(r).components().add(<rsa-generated KeyProvider component with higher `priority`>)` to make
  a **new active kid** (mint token B under kid K2), assert Configd sees an unknown kid → **rate-limited JWKS
  refetch** → validates B; then set the **old** key component `enabled=false` (or delete it) so kid K1 leaves
  the JWKS, and assert an **old-kid token A is now rejected 401** after refetch (rotation eviction +
  negative-cache), while B still validates — proving refresh-on-unknown-kid, overlap validation, and
  post-rotation rejection in one flow.

---

## 7. Primary sources

RFC 6749 (OAuth2), **RFC 6750** (Bearer usage / `invalid_token` §3.1), RFC 7515 (JWS), RFC 7517 (JWK Set),
RFC 7519 (JWT), **RFC 7636** (PKCE; `S256` MTI §4.2, verify §4.6), **RFC 8414** (AS metadata / discovery §2,§3),
**RFC 8725** (JWT BCP; alg-confusion §2.1, substitution §2.7/§2.8, alg verification §3.1/§3.2, issuer/audience
§3.8/§3.9, kid/jku/x5u §3.10, explicit typing §3.11/§3.12), **RFC 9068** (JWT access-token profile; resource-
server validation §4), **OpenID Connect Core 1.0 §3.1.3.7** (id_token validation), OpenID Connect Discovery
1.0. IdP references: Keycloak (`realm_access.roles`), Microsoft EntraID v2 (`roles`/`groups`/`scp`), Okta custom
authz server (`groups`/`scp`), Auth0 (namespaced claims / `permissions`).

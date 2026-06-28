# Pluggable Authentication SPI — Decision Log

> **Session:** Auth-SPI design, 2026-06-28. Branch `design-auth-spi`, worktree off `main` (`c35d755`, which
> already contains the namespace/path model #18-pending, the KMS-SPI #17, watches #16, encryption #15).
> **Posture:** design + recommendation + one RFC section — **no production code** (a compile-checked type
> sketch under [`sketch/`](sketch/) is a design artifact; no authenticator built, no wiring, no money).
> Deliverables: the docs in this directory + the RFC section
> [`../../rfc/driver-protocol/03-authentication.md`](../../rfc/driver-protocol/03-authentication.md) + the
> sketch + this log. Stops at a docs-only PR (autonomous review→merge loop, beta).

This log records the **methodology** and the **analytical decisions** behind [`built-reality.md`](built-reality.md),
[`prior-art.md`](prior-art.md), [`authenticator-spi.md`](authenticator-spi.md),
[`authn-authz-boundary.md`](authn-authz-boundary.md), and the RFC section. The DL-A-* decisions are the design
team's evidence-based recommendations; the **operator-binding** calls are the **Open items** (OA-*) at the end.

---

## Methodology

- **DL-A-1 — Ground-truth the BUILT auth from source, not assumption.** Read the load-bearing code directly:
  `AuthInterceptor` (the `TokenValidator` seam + `AuthResult.Authenticated(principal, roles)`),
  `AdminApiHandler.checkAuth`/`bearerToken` (the control-plane 401-vs-403 enforcement point, and that it reads
  `principal()` but **never** `roles()` — roles carried-but-unused), `AclService` (per-prefix longest-match,
  keyed on the principal *id*), `ConfigdServer` auth wiring (constant-time compare, hardcoded `("root",
  {admin})`, the auth-disabled WARNING), `FanOutServer.resolveEdgeIdentity` / `NettyFanOutServer.resolveCertIdentity`
  (mTLS Subject DN, `setNeedClientAuth(true)`), `FanOutConnectionDriver.bindIdentity` (cert principal
  authoritative, the `"plaintext"` sentinel, and that the edge has **identity but no authz**). *Why:* the SPI
  must **generalise the built N = 2 with no regression** — every claim is checked against the line quoted in
  [`built-reality.md`](built-reality.md). The two load-bearing source facts — *roles are latent* and *the edge
  has no authz* — directly shape the design (the `Principal` finally gives `roles` a consumer and gives the
  edge a thing to authorize).
- **DL-A-2 — Primary-source the precedents, verbatim-flag the load-bearing ones.** Web-verified the four
  precedents with citations and **explicit inference flags** ([`prior-art.md`](prior-art.md)): Vault's
  **pluggable auth methods → core path-based policy engine** (the closest analogue — and the honest note that
  "the engine is not pluggable / the method makes no per-path decision" is a *compositional inference*, not a
  verbatim Vault sentence); JAAS `LoginModule` + `Subject`/`Principal` + the **four control flags** (the
  chaining prior art, quoted verbatim); the Servlet `<auth-method>` → `getUserPrincipal()`/`isUserInRole()`
  seam; SPIFFE SVID/SPIFFE-ID + the docs-sourced authn-vs-authz separation. *Why:* the docs feed a protocol
  RFC; reference claims must be accurate, not approximate.
- **DL-A-3 — Compile-check the load-bearing semantics, don't just prose them.** The `Principal`/`Credential`
  redaction, the two defaults, an OIDC provider behind a `TokenVerifier` seam, and — the part most likely to
  hide a bug — the **chain resolution** (dispatch / INVALID-stops / NOT_THIS-continues / UNAVAILABLE-fail-closed
  / fail-loud selection) are a standalone JDK-25 sketch with a `main()` of asserts. `java -ea SketchSmokeTest`
  ⇒ **20/20 design-contract checks pass** on OpenJDK 25. *Why:* the resolution rule is exactly where a
  prose-only design lets a forged/unavailable credential silently fall through to a weaker path.

## Analytical decisions (the recommendations)

- **DL-A-4 — AuthN is the pluggable SPI; AuthZ stays IN-CORE (the load-bearing constraint).** Authentication
  varies per deployment (mTLS, OIDC, LDAP, K8s, cloud-IAM) → it is the SPI. Authorization is Configd's **own**
  role/policy/subtree engine (the namespace design) and **MUST** stay in-core, because the consistency
  guarantees depend on it: policy-as-config replicated by the same Raft means the edge and control plane
  evaluate the **same bytes** with the **same engine**, which is what structurally guarantees INV-WATCH-READ. A
  pluggable authz black box would destroy that. The `Authenticator` interface therefore has **no
  `mayAccess(path)` method** — the shape forbids the dangerous thing (the analogue of the KMS SPI having no
  per-record `encrypt`). **Authz is never a plug-in.**
- **DL-A-5 — The `Principal` is the seam.** `id + Configd roles + attributes + provenance`, typed, immutable,
  and it **never carries the credential** (a structural property — there is no field for it). It generalises
  the built `AuthResult.Authenticated(String principal, Set<String> roles)` (adds `attributes` + provenance,
  and is produced at **both** planes, not just the bearer path). The authenticator produces it; the in-core
  engine consumes `roles` (→ policies → rules). The authenticator never authorizes; the engine never parses a
  credential ([`authn-authz-boundary.md`](authn-authz-boundary.md) B-1…B-3).
- **DL-A-6 — `Credential` is sealed over `{CertChain, BearerToken, Password, Headers}`.** These cover the two
  built mechanisms **and every named, single-credential, request-shaped provider** (OIDC→BearerToken,
  LDAP→Password, K8s→BearerToken, cloud-IAM→Headers) — a credential is a **wire/transport** concern, not a
  provider-SDK concern, so a novel *single-shot* shape is a small **versioned core addition** (like a wire
  frame), not a per-provider fork. Sealing buys exhaustiveness at the transport without blocking the open
  `Authenticator` interface that out-of-tree modules implement. **Scope, stated honestly:** the single-shot
  `authenticate(Credential)` and the sealed set do **NOT** cover **multi-leg challenge-response** (Kerberos/SPNEGO,
  SCRAM/SASL, RADIUS, WebAuthn) or **SAML** redirect flows — those need an interface-level mutual-challenge
  extension (RFC §3 AU7-3), not a credential-enum add, and stuffing them into `Headers`/`BearerToken` is a type
  lie to avoid. Not overclaimed as covered ([`authenticator-spi.md`](authenticator-spi.md) §3, §10).
- **DL-A-7 — Multi-authenticator resolution: credential-type dispatch + first-definitive + fail-closed**
  (recommended; [`authenticator-spi.md`](authenticator-spi.md) §5.1). A credential is dispatched (`canAttempt`)
  to the authenticators that handle its type; the first **definitive** outcome wins. The JAAS-derived,
  load-bearing rule (prior-art §2.3): an **owned-but-invalid** credential is a **hard stop** (401, like
  `Requisite` failing — **never** fall through to a weaker authenticator); a **not-mine** outcome continues
  (like `Sufficient`/`Optional` failing). Per-endpoint authenticator sets are **rejected** (they would break
  RA-5).
- **DL-A-8 — The fail-closed contract (RA-1…RA-7), mirroring the KMS R1–R5 discipline, encoded in the
  interface.** A configured-but-unavailable authenticator throws the **checked** `AuthnUnavailableException` →
  the resolver rejects, **never** downgrades (RA-1); validation failures fail closed (RA-2); the `Principal`
  never carries the credential (RA-3); auth-enabled + no credential → 401 default-deny, distinct from the loud
  auth-disabled open gate (RA-4); the **same chain governs both planes** (RA-5); established libraries only,
  no rolled crypto (RA-6); fail-loud selection (RA-7, the `NettyTransport.select()` / `KmsProviders.select()`
  posture).
- **DL-A-9 — Honest difference from the KMS SPI: authentication IS on the per-request path.** The KMS SPI is
  boot-only (unseal once, drop the provider — structurally off the hot path). Authn cannot claim that. So the
  design disciplines it instead of overclaiming: the **defaults have no remote dependency** (mTLS rides the
  completed handshake; static bearer is a local compare — they satisfy RA-1 trivially); **remote-validating
  providers (OIDC/LDAP/K8s) MUST cache** verification material with a bounded TTL and **fail closed** when
  validation genuinely can't be performed. This bounds, not eliminates, the per-request dependency — stated
  plainly ([`authenticator-spi.md`](authenticator-spi.md) §5.3).
- **DL-A-10 — INV-WATCH-READ is preserved across the pluggable-authn boundary — *per watching principal*.** The
  pluggability ends at the `Principal`; everything the invariant depends on (one in-core engine, one replicated
  policy, the whole-target subscription check) is downstream and unchanged. The proof is **per-principal**: a
  watch by `p` delivers only keys `p` could READ, evaluated by the same engine/policy at the edge as at the
  control plane. RA-5's role is (a) one engine/one policy at both planes and (b) **no separate, weaker edge
  authn** that could fabricate a stronger identity — **not** a claim that one human is one principal across
  planes. **Honest v1 caveat:** the control plane is bearer-only and the edge is mTLS-only, so a human is **two
  principals** (token vs cert); the invariant holds for each, but the operator **MUST** keep their two policies
  consistent or a human could out-read via a watch under their cert identity what their token identity cannot
  read — a cross-identity **provisioning** gap, not an invariant break. Made explicit in
  [`authn-authz-boundary.md`](authn-authz-boundary.md) §3.
- **DL-A-11 — External identity → Configd role mapping happens AT THE AUTHENTICATOR.** `Principal.roles`
  contains **Configd role names only**; the authz engine never sees an OIDC claim or LDAP group, so it is
  identity-system-agnostic (the Vault alias→group→policy model, prior-art §1.5). Robust under **both** O-6
  outcomes: with roles→policies the engine keys on `roles`; with per-principal grants it keys on
  `Principal.id` and `roles` is advisory — the `Principal` carries both
  ([`authn-authz-boundary.md`](authn-authz-boundary.md) §4).
- **DL-A-12 — Two in-core defaults; future providers as optional modules; ServiceLoader + fail-loud.**
  `MtlsAuthenticator` + `BearerTokenAuthenticator` ship in-core, **zero new dependency** (the built behavior
  factored behind the interface — the KMS `local` analogue). Each external provider —
  `configd-authn-{oidc,ldap,k8s,iam-*}` — is a **separate optional Maven artifact** depending on the SPI + its
  own SDK, discovered via `ServiceLoader<AuthenticatorFactory>`, on the runtime classpath only when used; the
  core pulls in no provider SDK. **Only OIDC brings a real new dependency** (a JWT lib); LDAP (JNDI) and K8s
  (`java.net.http`) need none. The **OIDC sketch** proves the contract end-to-end and satisfies RA-1/RA-2/RA-6.
- **DL-A-13 — RFC §3 is normative and composes with §1/§2.** The **stable driver contract** (present the
  credential you have → authenticated session or typed error, **regardless of which authenticator the server
  runs**, AU2-1) is the load-bearing forward-compat property — a deployment can swap static-bearer → OIDC with
  no driver change. §3 owns the **401** (authentication) side; §1 §5–§6 own the **403** (authorization) side;
  the shared taxonomy is §1 §7. Unknown mechanisms/claims **fail closed** (AU7-1). Clause prefix **`AU`** so
  the composed RFC has no clashing identifiers.

---

## Open items (operator-binding — confirm before wiring)

| # | Item | Recommendation | Doc |
|---|---|---|---|
| **OA-1** | Multi-authenticator resolution | **credential-type dispatch + first-definitive + fail-closed**; per-endpoint sets rejected (break RA-5) | authenticator-spi §5 |
| **OA-2** | Which future provider first | `mtls`+`bearer` are the in-core defaults; **OIDC** the likely first optional module, then K8s/LDAP/IAM | authenticator-spi §8 |
| **OA-3** | SPI module placement | dedicated **`configd-authn-spi`** (recommended) vs fold into `configd-control-plane-api` (where `AuthInterceptor`/`AclService` live) | authenticator-spi §8 |
| **OA-4** | Discovery mechanism | **hybrid `ServiceLoader` + ordered-name list** (consistent with KMS) vs explicit registry | authenticator-spi §8 |
| **OA-5** | Edge `"plaintext"`/anonymous when auth enabled | **reject (fail-closed)** vs allow an anonymous no-roles principal (test/single-node only) | authenticator-spi §11 |
| **OA-6** | `Principal.roles` shape | depends on namespace **O-6** (roles→policies, recommended); design to it, per-principal fallback noted | authn-authz-boundary §4 |

None of these are wired; all are recommendations. The operator's confirmation turns them into the contract the
auth wiring (and the driver protocol) conform to.

---

## What this design does NOT do (scope honesty)

- **No authenticator built, no wiring.** No `Authenticator` implementation replaces the inline `TokenValidator`
  or the edge `bindIdentity`; no `ConfigdServer`/`AdminApiHandler`/`FanOutConnectionDriver` change.
- **No authz change, and authz is NOT made pluggable.** The in-core engine (the namespace design) is untouched
  and stays in-core — the load-bearing constraint (DL-A-4).
- **No `ServiceLoader` use, no provider modules, no SDK dependency added.** Nothing was added to any `pom.xml`;
  the sketch is a standalone artifact, not a Maven module.
- **No real crypto / JWT / TLS.** The mTLS extractor and the OIDC `TokenVerifier` are seams; the production
  impls wrap the platform TLS and a vetted JWT lib (RA-6). The AWS-style cloud-IAM and the Nimbus wiring are
  design-level (not compiled against the SDKs this session).
- **No Configd-minted session/token (v1).** v1 re-presents the credential per request; an auth-session layer is
  a named forward extension (AU2-3, AU7-3).
- **No money, no measurement, no ADR, no change to v1 posture** beyond adding this design directory + the RFC
  section.

---

## Handoff — the auth model is decided + RFC-captured; what the operator must confirm, and what wires next

**Decided (design):** authentication is a **pluggable `Authenticator` SPI**; authorization stays **in-core**;
the **`Principal`** (`id + Configd roles + attributes + provenance`, never the credential) is the seam; the
chain resolves by **type-dispatch + first-definitive + fail-closed**; **mTLS + bearer** are the in-core
defaults that generalise the built N = 2; **OIDC/LDAP/K8s/IAM** are optional `ServiceLoader` modules; the model
is captured normatively in RFC §3 (stable driver contract regardless of server authenticator). The compiled
sketch (20/20) is the signature reference.

**Operator confirmations needed:** OA-1…OA-6 above — most importantly the **resolution policy** (OA-1), the
**module placement** (OA-3), and the **edge-anonymous posture** (OA-5). OA-6 is **gated on the namespace O-6
decision** (roles→policies), so confirm O-6 first.

**What wires next (when confirmed, and when the namespace authz is wired):**
1. Introduce `configd-authn-spi` with the SPI + `Principal`/`Credential`/`AuthResult` + the two in-core
   defaults + `Authenticators.chain` (the sketch made concrete + tested).
2. Replace the inline `TokenValidator` (`ConfigdServer.java:714`) and the raw-DN `bindIdentity`
   (`FanOutConnectionDriver.java:159`) with `chain.resolve(...)` producing a `Principal`, **shared by both
   planes** (RA-5).
3. Feed that `Principal` to the in-core authz engine at **both** enforcement points — closing the edge-no-authz
   gap (built-reality §2.4) once the namespace WATCH enforcement lands.
4. Build the first optional provider (likely `configd-authn-oidc`) when a named requirement appears — the same
   "build the cloud module on demand" trigger the KMS-SPI sets.

**Dependency note (honest):** this SPI **composes with** the namespace authz design, which is itself **design,
not yet wired**. The `Principal.roles` consumer (roles→policies) lands when that authz is built; until then the
SPI's value is the **typed identity at both planes** and the **first-class edge `Principal`** the watch-authz
contract needs. Both are interface-first so they slot in with no core change beyond the seam.

---

## Pointers

- [`built-reality.md`](built-reality.md) — the built mTLS+bearer authn, the raw-`String` identity, the
  roles-carried-but-unused fact, the control-plane vs edge enforcement asymmetry, with `file:line`.
- [`prior-art.md`](prior-art.md) — Vault / JAAS / servlet / SPIFFE, cited verbatim with inference flags.
- [`authenticator-spi.md`](authenticator-spi.md) — the `Authenticator` interface, the `Principal`, the
  resolution, RA-1…RA-7, the defaults, the module layering + OIDC sketch.
- [`authn-authz-boundary.md`](authn-authz-boundary.md) — the `Principal`-only seam, role mapping,
  INV-WATCH-READ preserved across the boundary.
- [`../../rfc/driver-protocol/03-authentication.md`](../../rfc/driver-protocol/03-authentication.md) — the
  normative RFC section (composes with §1/§2).
- [`sketch/`](sketch/) — the compile-checked signature artifact (+ 20/20 smoke test).
- Precedents in-repo: KMS-SPI ([`../../research/kms-spi/`](../../research/kms-spi/)) — the SPI/ServiceLoader/
  fail-closed shape this mirrors; the namespace authz
  ([`../namespace-model/`](../namespace-model/)) — the in-core engine the `Principal` feeds; selection seam
  `NettyTransport.select()` (`configd-netty/.../NettyTransport.java:82,128`).

# Prior art — pluggable authentication, fixed/core authorization

> **Session:** Auth-SPI design, 2026-06-28. Design + recommendation only. Sources accessed **2026-06-28**;
> quotes are **verbatim** from primary sources. Where a claim is true **by composition** but is not a single
> verbatim sentence in the source, it is marked **[inference]** — those are *not* quoted as anyone's words,
> matching the verbatim-discipline of the namespace ([`../namespace-model/prior-art.md`](../namespace-model/prior-art.md))
> and KMS ([`../../research/kms-spi/prior-art.md`](../../research/kms-spi/prior-art.md)) research.

The design rests on one recurring industry pattern: **the mechanism that proves "who is this caller"
(authentication) is pluggable; the engine that decides "may this caller do this" (authorization) is core and
uniform.** The seam between them is a **typed identity** carrying roles/policies. Four systems demonstrate it.
Vault is the load-bearing precedent (a config store's nearest analogue); JAAS gives the chaining semantics;
the Servlet model gives the request-level seam; SPIFFE gives the mTLS-identity case.

---

## 1. HashiCorp Vault — pluggable *auth methods* → core path-based *policy engine* (the closest analogue)

Vault is a secret/identity store with the same shape as a config store: many deployments, many identity
systems, one access model. Its split is exactly the one this SPI adopts.

**1.1 Auth methods are a plugin architecture.**
> "Vault uses a plugin architecture to power all functionality offered by auth methods, database engines, and
> secrets engines." — Vault docs, *Discover plugins*
> (https://developer.hashicorp.com/vault/tutorials/get-started/discover-plugins)

The built-in auth methods (verbatim from the auth index): *AppRole, AliCloud, AWS, Azure, Cloud Foundry,
GitHub, Google Cloud, JWT/OIDC, Kerberos, Kubernetes, LDAP, Login MFA, OCI, Okta, RADIUS, SAML, SCEP, SPIFFE,
TLS Certificates, Tokens, Username and Password* (https://developer.hashicorp.com/vault/docs/auth). This is
the open-ended set Configd's `Authenticator` SPI must accommodate — mTLS and bearer are built; OIDC, LDAP,
Kubernetes are the same list Vault ships as plug-ins.

**1.2 An auth method's job: verify external identity, assign identity + policies — *not* decide per path.**
> "Auth methods are the components in Vault that perform authentication and are responsible for assigning
> identity and a set of policies to a user." — Vault docs, *Auth methods*
> (https://developer.hashicorp.com/vault/docs/auth)
> "Upon authentication, a token is generated." — Vault docs, *Authentication*
> (https://developer.hashicorp.com/vault/docs/concepts/auth)

So the auth method's output is **identity + policies**, surfaced as a token. **[inference]** That the method
makes **no per-path authorization decision** is true by composition (the method *assigns* policies; a separate
engine *evaluates* them per request) — it is not a single Vault sentence, so it is not quoted as one.

**1.3 The policy engine is core, uniform, path-based, default-deny; tokens carry policies by name.**
> "Everything in Vault is path-based, and policies are no exception."
> "Policies are deny by default, so an empty policy grants no permission in the system."
> "Tokens are attached policies by name, which are then mapped to the set of rules corresponding to that
> name." — Vault docs, *Policies* (https://developer.hashicorp.com/vault/docs/concepts/policies)

**[inference / framing care]** Vault calls *all* its components a "plugin architecture" (1.1), so the precise
contrast is **"pluggable auth method vs. core ACL _policy evaluation_"** — the evaluation rule (path globbing,
deny-precedence, default-deny) is fixed and applies identically regardless of which auth method minted the
token. That is the property Configd needs: a watch cannot out-read a read because the **same** engine
evaluates the **same** policy whichever authenticator produced the principal.

**1.4 deny / sudo precedence (mirrors the namespace design's deny-wins).**
> "`deny` - Disallows access. This always takes precedence regardless of any other defined capabilities,
> including `sudo`." — Vault docs, *Policies* (same URL)

This is the same absolute-deny-precedence the namespace access-control design adopts
([`../namespace-model/access-control.md`](../namespace-model/access-control.md) §4.1) — evidence the in-core
engine is the right home for that rule, not a per-provider plug-in.

**1.5 The identity seam: external alias → entity/group → policies, unioned at request time.**
> "This representation of a consolidated identity is called an Entity and their corresponding accounts with
> authentication providers can be mapped as Aliases."
> "An external group serves as a mapping to a group that is outside of the identity store." / "Policies set on
> the group are granted to all members of the group."
> "During request time, when the token's entity ID is being evaluated for the policies that it has access to,
> policies that are inherited due to group memberships are granted along with the policies on the entity
> itself." — Vault docs, *Identity* (https://developer.hashicorp.com/vault/docs/concepts/identity)

**The lesson Configd takes:** the auth method maps an **external** group/claim → Vault's **own** identity
constructs (entity/group → policies); the policy engine never sees the raw external group. Configd's
authenticator maps **external identity → Configd roles**; the authz engine only ever sees **Configd roles**
([`authn-authz-boundary.md`](authn-authz-boundary.md) §2). The **token carries the policies** ⇒ Configd's
**`Principal` carries the roles**.

---

## 2. Java JAAS — `LoginModule` (pluggable authn) + `Subject`/`Principal` seam + control flags

JAAS is the JVM-native instance of the split and the direct precedent for **multi-authenticator chaining**.

**2.1 `LoginModule` is the pluggable authentication SPI; a `Configuration` selects modules.**
> "Service-provider interface for authentication technology providers. LoginModules are plugged in under
> applications to provide a particular type of authentication." — `javax.security.auth.spi.LoginModule`
> Javadoc, Java SE 21
> (https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/security/auth/spi/LoginModule.html)
> "LoginContext uses the name as the index into a Configuration to determine which LoginModules should be
> used, and which ones must succeed in order for the overall authentication to succeed." — `LoginContext`
> Javadoc, Java SE 21
> (https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/security/auth/login/LoginContext.html)

**2.2 The authenticated result is a `Subject` of `Principal`s, consumed by authorization.**
> "Subjects may potentially have multiple identities. Each identity is represented as a Principal within the
> Subject." — `javax.security.auth.Subject` Javadoc, Java SE 21
> (https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/security/auth/Subject.html)
> "The Subject is updated by a LoginModule with relevant Principals and credentials if authentication
> succeeds." — JAAS Reference Guide
> (https://docs.oracle.com/javase/8/docs/technotes/guides/security/jaas/JAASRefGuide.html)

**Flags:** the authenticated→`Subject` causal sentence is in the **JAAS Reference Guide** (the Java SE 8
technotes narrative, never re-published per-JDK), not the Java 21 `Subject` Javadoc (which describes
structure only). Also `Subject.doAs(...)` is **deprecated for removal in Java 21**; the replacement is
`Subject.callAs(Subject, Callable)`. Configd cites JAAS for the **`Subject`/`Principal`-as-seam** idea, not
the `doAs` mechanism — the `Principal` is the carry-over.

**2.3 The four control flags — verbatim (the chaining prior art for multi-authenticator resolution).**
From the `Configuration` Javadoc, Java SE 21
(https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/security/auth/login/Configuration.html);
*"The Flag value controls the overall behavior as authentication proceeds down the stack."*

- **Required** — "The LoginModule is required to succeed. If it succeeds or fails, authentication still
  continues to proceed down the LoginModule list."
- **Requisite** — "The LoginModule is required to succeed. If it succeeds, authentication continues down the
  LoginModule list. If it fails, control immediately returns to the application (authentication does not
  proceed down the LoginModule list)."
- **Sufficient** — "The LoginModule is not required to succeed. If it does succeed, control immediately
  returns to the application … If it fails, authentication continues down the LoginModule list."
- **Optional** — "The LoginModule is not required to succeed. If it succeeds or fails, authentication still
  continues to proceed down the LoginModule list."
> "Authentication proceeds down the module list in the exact order specified." (same Javadoc)

**The lesson Configd takes:** JAAS proves a chain of authenticators needs **typed per-module outcomes** and an
**ordered, short-circuiting** resolution — not a blind "try each until one says yes." Configd's resolution
(`authenticator-spi.md` §4) is a deliberately **narrower** rule than JAAS's four-flag matrix: a credential is
**dispatched by type** to the authenticators that can attempt it, and the first **definitive** outcome wins —
an *owned-but-invalid* credential is a hard stop (like **Requisite** failing: control returns, no fall-through
to a weaker module), and a *not-mine* outcome continues (like **Sufficient**/**Optional** failing: proceed
down the list). The JAAS subtlety Configd adopts wholesale: **a module failing must not silently let a later,
weaker module pass the request** — that is the fail-closed core of §4 and the KMS-style R-discipline.

---

## 3. Jakarta Servlet — pluggable auth mechanism → declarative, container-enforced role authz

The Servlet model is the request-level instance of the seam and validates the `getUserPrincipal()` /
`isUserInRole()` shape.

**3.1 Standard auth mechanisms, selected declaratively.**
> §13.6 *Authentication*: "A web client can authenticate a user to a web server using one of the following
> mechanisms:" — HTTP Basic, HTTP Digest, HTTPS Client Authentication, Form Based Authentication. — Jakarta
> Servlet 6.1 spec (https://jakarta.ee/specifications/servlet/6.1/jakarta-servlet-spec-6.1)
> Schema `auth-methodType`: "Legal values are \"BASIC\", \"DIGEST\", \"FORM\", \"CLIENT-CERT\", or a
> vendor-specific authentication scheme." (https://jakarta.ee/xml/ns/jakartaee/web-common_6_1.xsd)

**Precision flag:** the **descriptor** token is `CLIENT-CERT` (hyphen); the **runtime** value from
`HttpServletRequest.getAuthType()` is `CLIENT_CERT` (underscore). Configd cites the *separation*, not the
literal tokens.

**3.2 The authn→authz seam is on the request object.**
> `getUserPrincipal()`: "Returns a java.security.Principal object containing the name of the current
> authenticated user." (null if not authenticated)
> `isUserInRole(String role)`: "Returns a boolean indicating whether the authenticated user is included in
> the specified logical \"role\"." — Servlet 6.1 apidocs, `HttpServletRequest`
> (https://jakarta.ee/specifications/servlet/6.1/apidocs/jakarta.servlet/jakarta/servlet/http/httpservletrequest)

The container **authenticates** (pluggable mechanism); the application/descriptor **authorizes by role**. This
is precisely Configd's `Principal{id, roles}` consumed by an authz check — `getUserPrincipal()` ≈
`Principal.id`, `isUserInRole(r)` ≈ `r ∈ Principal.roles` floored by the policy engine.

**3.3 Role authz is declarative + container-enforced, separate from the mechanism.**
> §13.8: "An authorization constraint establishes a requirement for authentication and names the authorization
> roles permitted to perform the constrained requests. A user must be a member of at least one of the named
> roles to be permitted to perform the constrained requests." (Servlet 6.1 spec, same URL)
> §13.6.5 *Additional Container Authentication Mechanisms*: "Servlet containers should provide public
> interfaces that may be used to integrate and configure additional HTTP message layer authentication
> mechanisms…" (same URL)

The spec **structurally separates** *how you authenticate* (§13.6, pluggable) from *who is authorized* (§13.8,
declarative role constraints) — its own table of contents is evidence for the split.

---

## 4. SPIFFE/SPIRE — SVID identity, SPIFFE ID as principal; authn vs authz explicitly separated

SPIFFE is the precedent for the **mTLS-identity** case — directly relevant to Configd's built
`MtlsAuthenticator` (cert DN today; a SAN-URI identity tomorrow).

**4.1 An SVID is a verifiable identity document, X.509 or JWT.**
> "An SVID is the document with which a workload proves its identity to a resource or caller." / "It encodes
> the SPIFFE ID in a cryptographically-verifiable document, in one of two currently supported formats: an
> X.509 certificate or a JWT token." — SPIFFE Concepts
> (https://spiffe.io/docs/latest/spiffe-about/spiffe-concepts/)

**4.2 The SPIFFE ID lives in the certificate URI SAN (the identity-extraction point).**
> "In an X.509 SVID, the corresponding SPIFFE ID is set as a URI type in the Subject Alternative Name
> extension…" / "An X.509 SVID MUST contain exactly one URI SAN, and by extension, exactly one SPIFFE ID." —
> X509-SVID standard (https://github.com/spiffe/spiffe/blob/main/standards/X509-SVID.md)
> "A SPIFFE Identity (or SPIFFE ID) is defined as an RFC 3986 compliant URI comprising a 'trust domain name'
> and an associated path." — SPIFFE-ID standard
> (https://github.com/spiffe/spiffe/blob/main/standards/SPIFFE-ID.md)

Configd's built `MtlsAuthenticator` reads the Subject **DN** (`getPeerPrincipal().getName()`,
[`built-reality.md`](built-reality.md) §1.2); a SPIFFE deployment would instead read the **URI SAN** →
SPIFFE ID. This is precisely why the SPI's identity extraction is **behind the interface** and the `Principal`
carries `attributes` (so a SAN-URI / SPIFFE ID is a first-class attribute, not a DN hack) — one mTLS
authenticator variant per identity convention, same `Principal` out.

**4.3 SPIFFE provides authentication; authorization is a separate concern.**
> "Typically when a workload receives a message from another workload, having authenticated that message using
> SPIFFE, it must then decide how to act on that message (authorization). SPIFFE and SPIRE do not provide a
> means to implement authorization policies, only authentication policies." — SPIRE comparisons
> (https://spiffe.io/docs/latest/spire-about/comparisons/)

**Flag:** this clean sentence is from **spiffe.io docs (SPIRE comparisons)**, not the normative
SPIFFE-ID/X509-SVID standards. Corroboration that the SPIFFE ID is consumed as the **authz principal**
downstream — Istio `AuthorizationPolicy.principals`:
> "A list of peer identities derived from the peer certificate. The peer identity is in the format of
> \"<TRUST_DOMAIN>/ns/<NAMESPACE>/sa/<SERVICE_ACCOUNT>\"…" — Istio
> (https://istio.io/latest/docs/reference/config/security/authorization-policy/)
> (third-party; note it renders the identity **without** the `spiffe://` scheme prefix.)

---

## 5. Synthesis — what the four agree on, and what Configd takes

| Property | Vault | JAAS | Servlet | SPIFFE | Configd auth-SPI |
|---|---|---|---|---|---|
| Authn is **pluggable** | auth methods | `LoginModule` | `<auth-method>` | SVID issuance | **`Authenticator` SPI** |
| Authz is **core/fixed** | ACL policy eval | `Policy`/role check | role constraints | (out of SPIFFE; downstream) | **in-core namespace engine** |
| The **seam** is a typed identity + roles | token → policies | `Subject`/`Principal` | `getUserPrincipal`/`isUserInRole` | SPIFFE ID | **`Principal{id, roles, attributes}`** |
| External identity → **own** roles at the boundary | alias/group → policy | module populates `Principal`s | container maps to roles | (consumer maps SPIFFE ID) | **authenticator maps → Configd roles** |
| Chaining is **ordered + fail-closed** | mounts per path | Required/Requisite/Sufficient/Optional | one mechanism | n/a | **type-dispatch + first-definitive (§4)** |

**The decisive precedent is Vault** (1.3, 1.5): a config/secret store keeps the **policy engine core and
uniform** so the access decision is identical regardless of which auth method minted the identity — exactly
the property Configd's INV-WATCH-READ depends on (one engine, one replicated policy, evaluated the same at the
control plane and the edge; [`authn-authz-boundary.md`](authn-authz-boundary.md) §3). Making authorization a
plug-in would forfeit that guarantee, which is why this design makes **only authentication** pluggable. JAAS
(2.3) supplies the chaining discipline; Servlet (3.2) the request seam; SPIFFE (4.2) the mTLS-identity case.

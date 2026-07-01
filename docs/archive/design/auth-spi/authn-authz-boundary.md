# The authn → authz boundary — the `Principal` is the only thing that crosses

> **Session:** Auth-SPI design, 2026-06-28. **Status:** design + recommendation only — no production code. This
> document makes the seam **normative**: what crosses the boundary (only a `Principal`), where external
> identity becomes a Configd role (at the authenticator), and why **INV-WATCH-READ survives the
> pluggable-authn boundary**. It binds the pluggable SPI ([`authenticator-spi.md`](authenticator-spi.md)) to
> the **in-core** namespace authz engine
> ([`../namespace-model/access-control.md`](../namespace-model/access-control.md)). The normative keywords
> MUST / MUST NOT are deliberate; the RFC ([`../../rfc/driver-protocol/03-authentication.md`](../../rfc/driver-protocol/03-authentication.md))
> restates the driver-visible parts.

---

## 0. The boundary in one paragraph

Authentication is **pluggable**; authorization is **in-core**. The two halves communicate through exactly one
typed value — the **`Principal`**. The authenticator verifies a credential and produces a `Principal`
(`id + Configd roles + attributes + provenance`); the in-core authz engine consumes that `Principal`,
resolves its **roles → policies → rules**, and evaluates the access decision over policy-as-config. **The
authenticator never makes an authorization decision; the authz engine never parses a credential.** External
identity (an OIDC claim, an LDAP group) becomes a **Configd role at the authenticator**, so the authz engine
is identity-system-agnostic — it only ever sees Configd roles. Because (a) the authz engine is a single
in-core engine over the same Raft-replicated policy, and (b) the **same** authenticator chain governs both the
control-plane read path and the edge watch path (RA-5) — no separate, weaker edge authn — a watch authorizes a
superset operation with the **same-or-stricter** result as a read **for the watching principal** — so
**INV-WATCH-READ is preserved across the pluggable-authn boundary** (§3, including the v1 cross-identity
provisioning caveat).

---

## 1. The data flow — and the five normative boundary rules

```
   credential                Principal                       decision
  ───────────►  AUTHENTICATOR ─────────►  IN-CORE AUTHZ ENGINE ─────────►
   (cert/token)  (pluggable SPI)  (id,roles,   (namespace: roles→policies→rules,
                                   attrs,prov)   PolicySet/WatchAuthz over /_acl/)
                       │                              │
                       │ NEVER authorizes             │ NEVER parses a credential
                       └──────────  the Principal is the ONLY thing that crosses  ──────────┘
```

The data path, concretely, against the built + designed types:

```
Principal.roles()  ──►  roles(p)            (the set the engine keys on; AuthResult.Authenticated.roles today)
                   ──►  policies(roles(p))  (in-core: each role → its policies — namespace O-6)
                   ──►  List<PolicyRule>    (the rules: (scope, pathPattern, effect) → caps)
                   ──►  PolicySet.effectiveCaps / coversTarget / WatchAuthz.authorizeWatch   (the decision)
```

- **B-1 (the sole carrier).** The `Principal` is the **only** value that crosses the boundary. The authz
  engine **MUST** consume `Principal` (its `roles`, and optionally `attributes` for future ABAC) and **MUST
  NOT** receive a `Credential`, a token, or a cert.
- **B-2 (the authenticator never authorizes).** An `Authenticator` **MUST NOT** make, encode, or pre-compute
  any access decision — no path/capability/policy logic. Its output is identity, full stop. (There is no
  `mayAccess` method to tempt it; [`authenticator-spi.md`](authenticator-spi.md) §1, §10.)
- **B-3 (the authz engine never parses a credential).** The in-core engine **MUST NOT** inspect or validate a
  credential. If it can see a token or a cert, the boundary has leaked. It consumes a `Principal` and the
  replicated policy; nothing else.
- **B-4 (role mapping is at the authenticator).** External identity → Configd role mapping **MUST** happen
  **inside the authenticator** (§2). `Principal.roles` **MUST** contain **Configd role names only** — never a
  raw OIDC claim value or an LDAP DN. The authz engine **MUST** be identity-system-agnostic.
- **B-5 (one chain, both planes — INV-WATCH-READ).** The **same** authenticator chain **MUST** govern **both**
  the control-plane and edge enforcement points (RA-5) — no separate, weaker edge-only authn — and the **same**
  credential **MUST** resolve to the **same** `Principal` on whichever plane evaluates it. Combined with the
  single in-core engine over one replicated policy, this preserves INV-WATCH-READ **per watching principal**
  (§3). *(It does **not** make a human one principal across planes when they present different credential types
  — the v1 cross-identity caveat, §3.)*

---

## 2. Role mapping — external identity becomes a Configd role *at the authenticator*

The load-bearing boundary decision: **where does an OIDC claim or an LDAP group become a Configd role?**

**Decision (recommended): at the authenticator, per its configuration.** The authenticator maps the external
identity system's groups/claims → **Configd roles** and puts only those in `Principal.roles`. Consequences:

- **The authz engine never learns about OIDC/LDAP/K8s.** It evaluates **Configd roles → policies → rules**
  ([`../namespace-model/access-control.md`](../namespace-model/access-control.md) §1) and is wholly
  identity-system-agnostic. Adding a new authenticator (SAML, cloud-IAM) requires **zero** authz-engine change.
- **This is the Vault model exactly** ([`prior-art.md`](prior-art.md) §1.5): an auth method maps an external
  group **alias** → a Vault **identity group** → **policies**; the policy engine sees only Vault's own
  constructs. Configd's authenticator maps external group → Configd **role**; the engine sees only roles. The
  Vault *token carries policies* ⇒ the Configd *`Principal` carries roles*.

Worked example (OIDC):
```
# configd-authn-oidc config (lives WITH the authenticator, not the authz engine)
oidc.issuer            = https://login.acme.example
oidc.role-claim        = groups
oidc.claim-role-map    = { "acme-eng-payments": "tenant-payments-rw",
                           "acme-sre":          "platform-admin" }
```
A token with `groups = ["acme-eng-payments"]` →
`Principal(id="https://login.acme.example#u123", roles={"tenant-payments-rw"}, attrs={…}, authenticator="oidc")`.
The authz engine then resolves `tenant-payments-rw` → its policy (`ALLOW {READ,LIST,WATCH,WRITE} on
/team-payments/**`) → the access decision. The string `"acme-eng-payments"` **never** reaches the engine.

> **Rejected alternative — map in the authz engine.** Letting the authz engine map external groups → roles
> would force it to understand *every* identity system's group format and would couple the in-core engine to
> the pluggable layer it must stay independent of. Rejected: mapping is the authenticator's job; the engine's
> input is always Configd roles (B-4).

The two **default** providers are the degenerate cases of this rule: `BearerTokenAuthenticator` maps the one
admin token → the fixed role set `{"admin"}` (the built behavior, [`built-reality.md`](built-reality.md)
§1.1); `MtlsAuthenticator` maps a cert DN → roles via an optional DN→roles config (or none). Same rule, no
external directory.

> **Forward hardening (noted, not v1).** Role names are a **flat global namespace** today — any authenticator
> may mint any Configd role, so adding a low-trust external IdP silently grants it the authority to mint
> `admin`/`platform-admin`. `Principal` carries the **provenance** (`authenticator`) field precisely so a
> future policy can constrain *which authenticator may mint which roles* (e.g. `admin` mintable only via
> `mtls`/`bearer`, never a third-party IdP). v1 trusts the configured authenticators (they are inside the trust
> boundary, §3); **provenance-scoped role-minting** is the named hardening for when low-trust external IdPs are
> introduced. (Operator item — adjacent to OA-2/OA-5.)

---

## 3. INV-WATCH-READ is preserved across the pluggable-authn boundary

The namespace design established **INV-WATCH-READ**
([`../namespace-model/access-control.md`](../namespace-model/access-control.md) §6.5): *for every key `k` a
watch on target `T` could deliver, if `READ k` would be denied to principal `p`, the watch on `T` MUST be
denied to `p`.* It rests on three properties — one engine, one replicated policy at both planes, and a
whole-target check at subscription. **The pluggable authenticator introduces one new degree of freedom — the
`Principal` is now produced by a pluggable module — so we must show the invariant still holds.** It does, and
here is the argument made explicit.

**The boundary is *upstream* of the invariant; the invariant lives *downstream*, in-core.** The pluggability
ends at the `Principal`. Everything INV-WATCH-READ depends on — the engine, the policy, the evaluation rule —
is **in-core and unchanged** by the SPI. INV-WATCH-READ is a **per-principal** property, so the proof is
stated for the **watching principal** `p` (whoever subscribes at the edge); then we state what RA-5 / B-5 add,
and — importantly — what they do **not** claim.

For the watching principal `p` and target `T`:
```
watch_authorized(p, T)   ⟺   READ(p) ∧ WATCH(p) cover all of T          (WatchAuthz, namespace §6.1)
                         ⟹   ∀ k ∈ T :  READ(p) covers k                (PolicySet.coversTarget ⊆ per-key READ)
                         ⟹   ∀ k ∈ T :  read_authorized(p, k)           (same engine, same policy — RA-5)
```
So **a watch never delivers a key its own watching principal could not read** — independently of which
authenticator minted `p`. ∎ The two RA-5 / B-5 properties keep each term meaningful and the edge honest:

- **One engine, one policy (RA-5 + namespace DL-N-11).** The edge evaluates `p` against the **same**
  Raft-replicated policy under `/_acl/`, with the **same** union+deny rule (`PolicySet.coversTarget` is the
  per-key READ check lifted to a whole target), as the control-plane READ path — so "`READ(p)` covers `k`" at
  the edge means exactly what it means at the control plane. There is no second, drifting ACL at the edge.
- **No weaker edge authn fabricates identity (RA-5).** The **same chain** authenticates at the edge, so the
  edge cannot mint a *stronger-than-warranted* `p` from a self-asserted identity (the built design already
  makes the verified cert principal authoritative over the wire `edgeId`,
  [`built-reality.md`](built-reality.md) §1.2); a deployment **MUST NOT** run a separate, weaker edge-only
  authenticator (the rejected per-endpoint option, [`authenticator-spi.md`](authenticator-spi.md) §5.1).

**What RA-5 does NOT claim — the v1 cross-identity caveat (important).** RA-5 is a property of the **chain**
("the same credential resolves to the same principal on whichever plane evaluates it; no plane invents a
stronger one") — **not** a claim that one *human* is one *principal* across planes. In v1 the control plane is
**bearer-only** and the edge is **mTLS-only** (RFC §3 AU3-1/AU3-2), so a single human presents a **different
credential type** on each plane and is therefore **two principals** — `p_token` (control plane) and `p_cert`
(edge), with possibly different roles. INV-WATCH-READ holds for **each** (watch ≤ read for `p_cert`; watch ≤
read for `p_token`), but the operator carries a real obligation: **the cert-DN policy and the bearer/OIDC
policy for the same human MUST be kept consistent.** Otherwise a human read-restricted under `p_token` could
hold a `p_cert` that maps to broad READ and **out-read, via a watch under their cert identity, what they
cannot read on the control plane** — not a violation of the per-principal invariant, but a cross-identity
**provisioning** gap the operator must close (a token-on-the-edge forward extension, RFC AU3-3, would let a
deployment avoid it by using one credential type on both planes).

**Honest scope of the guarantee (what it is and isn't).** INV-WATCH-READ is a **consistency** property:
*watch ≤ read for the same principal.* It is **not** a statement that the authenticator provisions the *right*
roles — a misconfigured OIDC claim→role map that grants `platform-admin` too broadly is an **operator
misconfiguration of a trusted module**, not a break of the invariant (the over-broad principal still has
`watch ≤ read` — it can simply read more than intended). The authenticator is **inside the trust boundary**,
like the KMS provider: it is a configured, vetted module trusted to mint correct identities. The SPI's
contribution to security is making the **edge** able to authorize at all (it produces the `Principal` the
watch-authz check needs, [`built-reality.md`](built-reality.md) §2.4) and guaranteeing the edge cannot
fabricate a *stronger* identity than the credential warrants (RA-5). Correct role provisioning — both per
principal and **across a human's two per-plane identities** (the cross-identity caveat above) — is the
operator's responsibility, separable from and additional to the invariant.

---

## 4. The boundary under both O-6 outcomes (roles → policies, or per-principal)

`Principal.roles` is shaped by the namespace **O-6** decision
([`../namespace-model/decision-log.md`](../namespace-model/decision-log.md)). The boundary is designed to be
**robust to either outcome** because the `Principal` carries **both** `id` and `roles`:

- **O-6 confirmed (roles → policies — recommended).** The engine keys on `Principal.roles`: `roles(p) →
  policies → rules → PolicySet`. This is the live path the §1 data flow shows; it is also the **least-new-
  machinery** path since the roles are already carried in the auth token ([`built-reality.md`](built-reality.md)
  §2.3).
- **O-6 reversed (per-principal grants).** The engine keys on `Principal.id` (as the built
  `AclService.isAllowed(principal, key, perm)` does today, [`built-reality.md`](built-reality.md) §2.1);
  `Principal.roles` becomes **advisory/unused** (the built state). The boundary is unchanged — the same
  `Principal` crosses; only which **field** the engine keys on differs. The authenticator still maps external
  identity → a stable `id` (and, harmlessly, roles).

Either way, **B-1…B-5 hold verbatim** — the `Principal` is the carrier, the authenticator never authorizes,
the engine never parses a credential. The O-6 choice changes the engine's internal keying, not the boundary.

---

## 5. The boundary vs. the 401/403 taxonomy

The boundary aligns precisely with the built 401-vs-403 split ([`built-reality.md`](built-reality.md) §3,
RFC §1 §7):

| Verdict | Owner | Outcome |
|---|---|---|
| no/invalid credential | the **`Authenticator`** (the 401 side of the seam) | `401` + `WWW-Authenticate` / terminal `401`-class watch close |
| authenticated, capability not granted | the **in-core authz engine** (the 403 side) | `403` / terminal `403`-class watch close |
| configured authenticator unavailable | the **resolver** (RA-1, fail-closed) | `503`/`401`-class — **never** falls through to authz |

A `401` is produced **before** the boundary (no `Principal` was minted); a `403` is produced **after** it
(a `Principal` crossed, the engine denied). The seam is therefore visible in the error taxonomy itself — which
is why the RFC can keep §1's authz taxonomy and add only the **authentication** (401) mechanics in §3.

---

## 6. Summary — the boundary as a checklist

- [ ] The `Principal` is the **only** value crossing authn → authz (B-1).
- [ ] The authenticator **never** authorizes; there is no access decision in the SPI (B-2).
- [ ] The authz engine **never** parses a credential; it consumes a `Principal` + the replicated policy (B-3).
- [ ] External identity → Configd role mapping is **at the authenticator**; the engine sees **only** Configd
      roles (B-4).
- [ ] The **same** chain governs both planes (RA-5 / B-5) — no weaker edge authn — so **INV-WATCH-READ is
      preserved per watching principal** (§3); the operator keeps a human's two per-plane identities'
      policies consistent (the v1 cross-identity caveat).
- [ ] The boundary holds under **both** O-6 outcomes; `Principal` carries `id` **and** `roles` (§4).
- [ ] The 401 side of the seam is the authenticator; the 403 side is the in-core engine (§5).

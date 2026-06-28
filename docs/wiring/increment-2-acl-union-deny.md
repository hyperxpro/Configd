# Wiring Increment 2 — ACL Union-of-Ancestors + Absolute Deny-Precedence (O-4)

**Status: implemented (2026-06-28).** The first increment to change shipped, tested authorization
behavior. Touches the live authorization enforcement point. Branch
`wiring-increment-2-acl-union-deny` off `main` @ `893b9a6` (Increment 1).

This is the decision log, the RFC-§5 (A5-4) conformance note, and the handoff for the increment that
changes `AclService` evaluation from **longest-match-only** to **union-of-ancestors + absolute
deny-precedence + default-deny** (the Vault model), per
[`../rfc/driver-protocol/01-paths-and-access.md`](../rfc/driver-protocol/01-paths-and-access.md) §5.3
(A5-4) and the namespace design
[`../design/namespace-model/access-control.md`](../design/namespace-model/access-control.md) §4
(DL-N-08). It is the O-4 entry point named in Increment 1's handoff (§7).

**Review.** Built by `java-distinguished-engineer`; reviewed by a four-lane independent Opus team
(security-reviewer LEAD, redteam-auditor, code-reviewer, divergence-analyst) + a fresh-context Verifier.
All four returned no must-fix: LEAD **APPROVE**, code-review **APPROVE**, divergence
**BYTE-IDENTICAL-CONFIRMED**, red-team **NO-BYPASS-FOUND** (27 adversarial tests, mutation-validated).

---

## 1. What this increment does (and what it deliberately does not)

**IN.**
1. Rewrite `AclService.isAllowed` to evaluate authorization as A5-4: the **union** of the capabilities
   of **all** matching ancestor-prefix `ALLOW` rules, **minus** the union of all matching `DENY` rules
   (**deny is absolute**), **default-deny** when no `ALLOW` matches. Not longest-match-only.
2. Add a `DENY` effect: a `deny(prefix, principal, perms)` method and an immutable per-`(prefix,
   principal)` `GrantEntry{allow, deny}` representation. `grant` and `deny` are **orthogonal effects**
   at a prefix (each overwrites only its own set; both are swapped atomically).

**Behind the stable `isAllowed(principal, key, permission)` signature** — zero call-site change, zero
wire change, zero persistence change, zero enforcement-point change. Exactly two production-tree files
change: `AclService.java` and `AclServiceTest.java` (+ a new red-team test `AclServiceRedTeamTest.java`
and this doc).

**OUT (later increments — unchanged here).** The O-3 capability expansion (`LIST`/`WATCH` stay out — the
`Permission` enum remains `{READ, WRITE, ADMIN}`); the O-6 role/policy model and policy-as-config under
`/_acl/`; scope-aware ACL (the `isAllowed` signature carries no scope — A5-4's `scope` axis is a later
increment); the watch-authz enforcement (O-5; no edge/watch surface exists yet); any change to the
single enforcement call site `AdminApiHandler.checkAuth`.

---

## 2. The semantics change — longest-match-only → union + deny (RFC §5.3 / A5-4)

### 2.1 The rule (normative, A5-4 / access-control.md §4.1)

For a request of capability `C` on key `k` by principal `p`:

```
matching = { rule : rule.prefix is an ancestor of (or equals) k, i.e. k.startsWith(prefix) }
allow    = ⋃ { rule.caps : rule ∈ matching, effect = ALLOW }
deny     = ⋃ { rule.caps : rule ∈ matching, effect = DENY  }
authorized(C)  ⟺  C ∈ allow  AND  C ∉ deny
```

This mirrors the design-blessed reference algorithm `PolicySet.effectiveCaps`
([`../design/namespace-model/sketch/io/configd/namespace/PolicySet.java`](../design/namespace-model/sketch/io/configd/namespace/PolicySet.java)):
accumulate `allow` and `deny` EnumSets across all matching rules, `allow.removeAll(deny)`, test
membership.

### 2.2 What was there before (confirmed, AclService.java pre-893b9a6)

`isAllowed` used `floorKey(key)` + walk-back to the **single longest** matching prefix and returned
**that prefix's** caps, ignoring all shorter (ancestor) prefixes; there was **no DENY concept**. So a
`READ` grant on `db.` was silently dropped for a key under `db.conn.` if a separate grant existed on
`db.conn.` — a hierarchy footgun (a parent grant did not compose with a child grant).

### 2.3 The implementation (behind the stable signature)

`isAllowed` keeps the `floorKey → lowerKey` navigation but **walks to `null`** instead of returning at
the first match, unioning each matching ancestor's `allow`/`deny` into two local `EnumSet`s, then
returns `allow.removeAll(deny); allow.contains(permission)`. The walk visits every stored prefix ≤ the
key in descending order and `key.startsWith(candidate)` selects the ancestors. **Walk-completeness is
structural:** every ancestor `p` of `k` satisfies `p ≤ k` lexicographically (a string prefix is always
≤ the string), so all ancestors lie in `[min, floorKey(k)]`, and the **un-broken** descending walk
covers that whole interval — a non-ancestor key that sorts *between* two ancestors (even one that
*becomes* `floorKey(k)`) is filtered by `startsWith` but **does not halt** the walk. (Re-introducing an
early `break` here is the classic regression; the red-team suite's `WalkStopEvasion` group fails loudly
if anyone does — proven by mutation testing.)

---

## 3. The composition-edge decision — "deny is absolute" (logged per task §7)

A5-4 says deny has **absolute precedence**. The two precedence directions and the explicit resolution:

> **DL-W2-01 (deny is absolute, both directions).** A matching `DENY` for `C` removes `C` regardless of
> specificity:
> - **deny at a less-specific ancestor** over **allow at a more-specific descendant** → denied;
> - **deny at a more-specific descendant** over **allow at a less-specific ancestor** → denied;
> - deny over a co-located allow at the **same** prefix → denied;
> - deny over **`ADMIN`** ("deny beats sudo") → denied.
>
> There is no specificity tie-break (the inverse of the old longest-match intuition). This is the Vault
> model and is what makes "grant the subtree, carve out a sensitive child" expressible. Implemented as a
> single set subtraction `allow.removeAll(deny)` over the unioned sets, so precedence is **insertion-order
> independent** and **specificity independent** by construction. Proven in both directions by
> `DenyPrecedence.denyAtAncestorOverridesAllowAtDescendant` / `…denyAtDescendantOverridesAllowAtAncestor`
> and the red-team `DenyBeatsSudoBothDirections` / `DenyOrderIndependence`.

> **DL-W2-02 (default-deny; a lone DENY never grants).** The `allow` accumulator starts empty, so no
> matching `ALLOW` ⇒ denied; a `DENY` rule contributes only to the subtracted set and can never produce an
> `ALLOW`. Proven by `DefaultDeny.loneDenyNeverGrants`.

> **DL-W2-03 (matching stays literal `startsWith`; segment-awareness is out of scope).** A rule matches a
> key by exact string prefix (`key.startsWith(prefix)`), **unchanged** from the historical evaluation. Two
> consequences are inherent to flat-prefix matching and become operationally relevant only **once `deny()`
> is wired** (it has no production caller today):
> - **(fail-safe) sibling over-reach:** a prefix without a trailing separator matches lexical siblings —
>   `deny("app.secret", …)` also denies `app.secretZ`. Callers MUST dot/slash-terminate subtree prefixes.
> - **(fail-OPEN) subtree-root gap:** a subtree-style deny `deny("/a/secret/", …)` does **not** cover the
>   bare node `"/a/secret"` (`"/a/secret".startsWith("/a/secret/")` is false), so an ancestor `ALLOW` still
>   grants the exact node. If a sensitive value can live at the subtree root, the carve-out must **also**
>   deny the bare node.
>
> Path-segment-aware matching (the RFC A3.4 glob model: `/a/` ≡ `/a/**` subtree, `/a/*` single-level,
> `/a/b` exact) is the **binary/driver-surface** contract (Increment 1 §2.3, deferred) and is the place
> to make a subtree deny cover its root. It is **not** part of O-4. The flat-key ACL surface stays
> literal-prefix, exactly as before. (Pinned by red-team
> `PrefixBoundaryScoping.nonSeparatorTerminatedPrefixMatchesSiblings_documented`.)

---

## 4. Production byte-identity (the proof obligation)

> **DL-W2-04 (byte-identity ⟺ the stored prefix set is an antichain; the deployed config is).** The
> precise precondition for union+deny to decide **identically** to longest-match is that the set of stored
> prefixes forms an **antichain** — no stored prefix is an ancestor of another. Then at most one prefix
> matches any key, the union has a single term, and the two models agree for every `(principal, key,
> permission)`. *(This is sharper than "one rule per principal": a longer prefix granted to a **different**
> principal can shadow this principal's shorter grant under longest-match — e.g. `grant("a",alice,R)` +
> `grant("ab",bob,W)`, then `alice READ "abc"` is denied by longest-match (lands on `"ab"`, alice absent)
> but allowed by the union (picks up `"a"`). So byte-identity is a property of the **global prefix set**,
> not of any single principal's rules.)*
>
> The **only** production grant is `ConfigdServer.java:726` — `grant("", "root", {READ,WRITE,ADMIN})`,
> gated by `config.authEnabled()`. The grant set is a single rule at prefix `""` (a trivial one-element
> antichain) with no `DENY`, so it is byte-identical: `root` is authorized for everything, every other
> principal is default-denied. Runtime reachability seals it — the production `AuthInterceptor` maps the
> sole valid token to `Authenticated("root", …)` and everything else to `Denied`, so the only tuples the
> live API ever evaluates are `("root", *, *)` → all true under both models. The **live HTTP
> authorization contract is unchanged.** (Auth-disabled leaves `aclService` null; `checkAuth` skips
> `isAllowed` entirely — untouched.)

The other in-tree grant sets are all single-distinct-prefix (a trivial antichain), so union ==
longest-match (byte-identical): `AbstractAdminApiServerContract` (`grant("app/", "reader", READ)` /
`("app/", "writer", READ+WRITE)` — distinct principals, one prefix) and the `AdminHttpAllocBenchmark`.
The **only** ancestor-related (overlapping) prefixes in the tree are in the tests. The full HTTP-contract
ACL suite (`Jdk`/`Netty`/`NioFallback` × 37 = 111) passes **unchanged**.

---

## 5. The test re-baseline (per task §3 / §5)

Under union, exactly **one** historical assertion flips: the old
`AclServiceTest.longestPrefixCanDenyWhatShorterAllows` asserted that a longer `READ`-only grant
*restricts* `WRITE` on the sensitive child — true only as a **side effect** of longest-match-only (the
shorter `READ+WRITE` grant was dropped). Under union the shorter grant's `WRITE` survives, so that
side-effect restriction is gone. It is **re-baselined, not deleted**, as
`DenyPrecedence.denyAtDescendantOverridesAllowAtAncestor`: the same intent ("the secret subtree is not
writable, still readable") expressed with an **explicit `DENY`** — the same three observable assertions,
the new mechanism. The companion `longestPrefixTakesPriority` assertions still hold under union (the
longer grant's caps were a superset of the shorter's) and are preserved/strengthened by
`UnionOfAncestors`. Every other historical assertion is single-/non-overlapping-prefix ⇒ byte-identical.

The adversarial proof suites each **fail under longest-match-only** — they prove the semantics, not
assert them:
- `AclServiceTest` (43): `UnionOfAncestors`, `DenyPrecedence`, `DefaultDeny`, `GrantDenyIndependence`,
  `ProductionByteIdentity` + the preserved Basic/Revocation/NullChecks suites.
- `AclServiceRedTeamTest` (27, **mutation-validated**): `WalkStopEvasion` (poisoned-decoy walk
  completeness, incl. a decoy engineered to *become* `floorKey`), `DenyOrderIndependence`,
  `DenyBeatsSudoBothDirections`, `UnionNeverManufacturesCapability`, `PrefixBoundaryScoping`,
  `GlobalEmptyPrefixDeny`, `RevokeAndOverwrite`, `CrossPrincipalIsolation`, `ProductionShape`,
  `ConcurrencySafety` (a standing absolute deny holds under 500k concurrent grant-churn reads; `isAllowed`
  never throws on a torn read). Mutation proof: an early `break` in the walk fails all 4 `WalkStopEvasion`
  tests; neutralizing `removeAll(deny)` fails 12 tests.

---

## 6. Implementation notes

- **`GrantEntry` (immutable record).** `{Set<Permission> allow, Set<Permission> deny}`, both
  `unmodifiableSet(EnumSet.copyOf(...))` or `Set.of()`. `grant`/`deny` replace one set via `withAllow`/
  `withDeny`, swapping the whole entry atomically via the **inner** `ConcurrentHashMap.compute`, so a
  concurrent `isAllowed` reader always sees a consistent `(allow, deny)` pair (never torn). `grant`
  preserves an existing `deny` and vice-versa. The multi-prefix walk is not a single atomic snapshot
  (a concurrent admin change across *different* prefixes can be observed partially) — inherent to a
  lock-free multi-prefix read, benign policy-propagation latency, never a torn pair *within* a prefix.
- **`revoke`** removes the whole `(prefix, principal)` entry (both allow and deny). Operator note for
  when `deny()` is wired: revoking a prefix also clears a standing **carve-out** there, which can
  re-expose access — `revoke` is "remove the rule," not "remove the allow" (proven by
  `revokeClearsDenyToo` / red-team `revokingDenyCarveOutReExposes`).
- **Hot-path cost.** `isAllowed` allocates two `EnumSet`s (a single `long` bitmask each for ≤64
  constants — cheap) and walks the prefixes ≤ key with no early exit. Cost is O(#stored prefixes ≤ key),
  bounded by the (small) ACL size — a control-plane policy set, **not** a storage structure; for the
  deployed single-grant config it is effectively O(1).
- **Not a storage-layer change.** ACL evaluation is control-plane policy; the `acls` map is built fresh
  at boot, never serialized/replicated/persisted (DL-N-08: "not an N=1 byte-identity regression").

---

## 7. Migration caveat (when overlapping grants / `deny()` go live)

`deny()` has **no production caller** today (the deployed config is one root grant). Before deny
carve-outs or overlapping grants are introduced into a live configuration:

- **Privilege-broadening on overlap.** Where longest-match used "a longer grant with fewer caps" to
  *restrict* a broader grant, union now **unions** them (the broader caps leak to the descendant). Any
  such restriction MUST be re-expressed as an explicit `deny()` (DL-N-07 / access-control.md §4.2).
- **Carve-out matching** is literal `startsWith` (DL-W2-03): dot/slash-terminate subtree prefixes, and
  if a sensitive value can live at a subtree root, deny the bare node as well as the subtree. Make this
  segment-aware when the A3.4 glob model lands.

---

## 8. Handoff — entry points for the next increment

- **Increment 3 — operator's choice of O-3 or O-6:**
  - **O-3 (capability expansion)** adds `LIST` and `WATCH` to the `Permission` enum + the capability
    relationship rules (LIST ⊥ READ; WATCH requires READ), and the `LIST` enforcement point. Folding in
    the A3.4 glob (segment-aware) matching belongs here too (DL-W2-03). No roles.
  - **O-6 (roles → policies → principals)** makes `AclService` role-aware (roles are already carried in
    `AuthResult.Authenticated(principal, roles)`, latent/unused) and introduces policies as the rule
    container, with the built per-principal grant the degenerate case; policy-as-config under `/_acl/`.
- **Scope-aware ACL.** A5-4's `scope` axis is not yet in the `isAllowed` signature; threading scope
  through authorization is its own increment (composes with Increment 1's `(scope, key)` routing).
- **The v2 boundary.** The watch-authz contract (O-5, RFC §6) needs the edge/watch surface, which does
  not exist yet; the `full_chain_verify` root-scope gate and the mandatory negative test land with it.

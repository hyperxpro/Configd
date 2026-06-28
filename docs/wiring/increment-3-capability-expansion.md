# Wiring Increment 3 — Authorization Capability Vocabulary Expansion (O-3)

**Status: implemented (2026-06-28).** The second increment to touch the live authorization model. Branch
`wiring-increment-3-capability-expansion` off `main` @ `ee27250` (Increment 2 — the union-of-ancestors +
deny-precedence foundation this builds on).

This is the decision log, the RFC-§5 (A5-1/A5-2) conformance note, the byte-identity proof obligation, and
the handoff for the increment that expands the `AclService.Permission` capability set from
`{READ, WRITE, ADMIN}` to the **v1 set `{READ, LIST, WRITE, WATCH, ADMIN}`** and makes the two normative
capability relationships (`LIST ⊥ READ`; `WATCH ⊇ READ`) load-bearing in `isAllowed`, per
[`../rfc/driver-protocol/01-paths-and-access.md`](../rfc/driver-protocol/01-paths-and-access.md) §5
(A5-1, A5-2) / §6 (INV-WATCH-READ) and the namespace design
[`../design/namespace-model/access-control.md`](../design/namespace-model/access-control.md) §2
(R-CAP-1, R-CAP-2). It is the **O-3** entry point named in Increment 2's handoff (§8).

---

## 1. What this increment does (and what it deliberately does not)

**IN.**
1. **Expand the capability vocabulary.** `AclService.Permission` becomes `{READ, LIST, WRITE, WATCH,
   ADMIN}` (RFC A5-1 order). The enum javadoc is rewritten — `LIST`/`WATCH` were "deliberately out of
   scope"; they are now in scope, with the A5-2 relationships documented.
2. **Make `WATCH ⊇ READ` load-bearing (DL-O3-03).** Inside `isAllowed`, after the increment-2 effective
   set `eff = allow − deny` is computed, the decision for `WATCH` is floored by `READ`:
   `authorized(WATCH) ⟺ WATCH ∈ eff ∧ READ ∈ eff`. Every other capability is decided by exact membership
   `C ∈ eff` — **byte-identical to increment-2**.
3. **Confirm + prove the two free-by-construction properties.** `LIST ⊥ READ` (R-CAP-1) and per-capability
   `DENY` for the new caps require **no new code** — they fall out of independent membership evaluation and
   the existing `allow.removeAll(deny)`. This increment **proves** them adversarially.

**Behind the stable `isAllowed(principal, key, permission)` signature** — zero call-site change, zero wire
change, zero persistence change, zero enforcement-point change. Exactly one production-tree file changes:
`AclService.java` (+ its two tests `AclServiceTest.java` / `AclServiceRedTeamTest.java`, the
`ConfigdServer.java:726` byte-identity model in a test, and this doc).

**OUT (later increments — unchanged here).**
- **No `LIST`/`WATCH` enforcement point.** No `list` or `watch` endpoint exists. `LIST` is wired by O-2
  (the list endpoint); `WATCH` subscription authorization is the RFC §6 / O-5 watch surface (no edge/watch
  exists yet). O-3 is **vocabulary + evaluation only** — the verbs `GET→READ`, `PUT/DELETE→WRITE` in
  `AdminApiHandler` are untouched (`AdminApiHandler.java:195/292/330`).
- **No segment-aware (A3.4 glob) matching (DL-O3-02).** Matching stays literal `key.startsWith(prefix)`.
- **No roles/policies (O-6).** `AclService` stays per-principal; `AuthResult.roles` stays latent/unused.
- **No `ADMIN` super-capability (DL-O3-01).** `ADMIN` authorizes only `ADMIN`.
- **No scope-aware ACL.** A5-4's `scope` axis is still not in the `isAllowed` signature.

---

## 2. The capability expansion (RFC §5.1 / A5-1)

The v1 capability set, declared in RFC A5-1 order:

| Capability | Authorizes | New in O-3? |
|---|---|---|
| `READ`  | read the value at a concrete path (`get`) | no |
| `LIST`  | enumerate children/descendants of a path (`list`) | **yes** |
| `WRITE` | put or delete at a concrete path | no |
| `WATCH` | subscribe to a change stream on a path/subtree | **yes** |
| `ADMIN` | manage policies/roles for a subtree; reach `/_acl/`, `/_system/` | no |

`DENY` remains an **effect on a rule** (via `deny()`), not a permission — unchanged from increment-2.

**Additive-and-compile-safe.** A repo-wide sweep found **zero** `Permission.values()` usages and **zero**
`switch` statements over `Permission`. The only references are `Permission.{READ,WRITE,ADMIN}` literals and
`EnumSet.allOf(Permission.class)` (`ConfigdServer.java:726`). Adding two constants cannot break an
exhaustive switch (there are none) and `allOf` simply widens to five (see §4). A full-reactor `test-compile`
(incl. `configd-server` and `configd-testkit`, which are not pulled by `-am` from the API module) confirms
no break beyond the ACL module.

---

## 3. The capability relationships (RFC §5.1 / A5-2, normative)

A5-2 names **exactly two** capability relationships. Both are honored; nothing else is invented.

### 3.1 LIST ⊥ READ (R-CAP-1) — independent, by construction

`LIST` does not imply `READ` and `READ` does not imply `LIST`. Each capability is decided by its own
membership in `eff`, so holding one never confers the other. **No code is needed** — independence is the
default behavior of `allow.contains(C)`. The proof is the deliverable: both directions are asserted
(`ListIndependentOfRead.readGrantDoesNotConferList` / `…listGrantDoesNotConferRead`, plus the red-team
`ListReadNonCrossing` proving no union of one ever manufactures the other).

### 3.2 WATCH ⊇ READ (R-CAP-2 / INV-WATCH-READ) — the load-bearing coupling

A watch is authorized as a **streaming read**: it must never expose what a read could not (RFC §6, A6-4
INV-WATCH-READ; access-control.md §2.1 R-CAP-2, §6.5). `WATCH` is a separately grantable capability — an
operator may grant point `READ` broadly but `WATCH` selectively (a standing subscription is operationally
heavier) — **but it is floored by `READ`**. The enforcement is a two-line refinement at the tail of
`isAllowed`:

```java
allow.removeAll(deny);                 // eff = allow − deny   (increment-2, unchanged)

if (permission == Permission.WATCH) {  // INV-WATCH-READ enforcement point (DL-O3-03)
    return allow.contains(Permission.WATCH) && allow.contains(Permission.READ);
}
return allow.contains(permission);     // READ/LIST/WRITE/ADMIN — exact membership, byte-identical
```

Consequences (all proven in `WatchRequiresRead` / red-team `WatchNeverOutreadsRead`):
- a `WATCH`-without-`READ` grant yields **no** effective watch authz;
- a `deny(READ)` at any matching ancestor **also** kills effective `WATCH` (the floor is over the
  deny-subtracted `eff`, so deny-precedence composes through it);
- a `deny(WATCH)` kills effective `WATCH` while leaving `READ` intact;
- the floor is over the **union** — `READ` from an ancestor composes with `WATCH` from a descendant.

A future watch subscribe endpoint inherits this floor. For a **single-key** target it authorizes with one
`isAllowed(p, key, WATCH)` call. For a **subtree** target `T` that single call is **necessary but not
sufficient** — it floors only the prefix point and **cannot** see a `deny(READ)`/`deny(WATCH)` on a
*descendant* of `T` (the ancestor-walk visits only `T`'s ancestors; cf. the increment-2 fail-OPEN subtree
carve-out, DL-W2-03). Per RFC §6 A6-2/A6-4 and access-control.md §6.5 (INV-WATCH-READ), the O-5 subscribe
check MUST floor the **whole** target once — verify `READ ∧ WATCH` cover **all of `T`** and **reject** (not
narrow) if any `READ`/`WATCH` deny falls within `T` — so every key the watch could deliver is provably
readable with no per-event re-check. That whole-subtree coverage is O-5's responsibility (§8); `isAllowed`
alone discharges only the single-key case.

### 3.3 Decision log (operator decisions — verbatim)

> **DL-O3-01 (ADMIN is NOT a super-capability).** `ADMIN` authorizes **only** `ADMIN`. An `ADMIN`-only
> principal is **not** authorized for `READ`/`LIST`/`WRITE`/`WATCH`. Rationale: RFC §5.1/A5-2 names exactly
> two normative relationships — `LIST ⊥ READ` and `WATCH ⊇ READ` — and **no** "`ADMIN` implies others"
> relationship; and byte-identity (making `ADMIN` a super-capability would newly authorize an `ADMIN`-only
> principal for other caps, changing decisions). This resolves the task's "super-capability (or per RFC §1)"
> hedge **in favor of RFC §1** (exact-match). No super-capability logic is added. Proven by
> `AdminIsNotSuperCapability.adminOnlyPrincipalIsNotAuthorizedForOtherCaps`.

> **DL-O3-02 (segment-aware matching is OUT of O-3).** Rule→key matching stays literal
> `key.startsWith(prefix)`, unchanged from increment-2. Path-segment-aware (A3.4 glob: `/a/` ≡ `/a/**`
> subtree, `/a/*` single-level, `/a/b` exact) matching is **not** implemented. Rationale: task rule 5; the
> increment-2 deferral DL-W2-03 (segment-awareness belongs to the binary/driver surface); and the flat-key
> ACL surface has **no canonical delimiter**, so a glob is not well-defined there. "Closing the increment-2
> carve-out caveats" therefore means, here: (a) `DENY` is proven first-class for **every** capability
> including `LIST`/`WATCH`, plus the `deny(READ)`-kills-effective-`WATCH` coupling; and (b) the literal-
> `startsWith` caveats — **fail-safe sibling over-reach** and **fail-OPEN subtree-root gap** (DL-W2-03) —
> are documented as **matching** properties that apply **uniformly to all five capabilities**, with the
> matching-function fix remaining the deferred binary surface. Pinned by red-team
> `PrefixBoundaryScoping.prefixMatchingIsCapabilityUniform_documented`.

> **DL-O3-03 (effective-WATCH enforced in `isAllowed`).** `WATCH ⊇ READ` is made load-bearing **inside
> `isAllowed`** as `authorized(WATCH) ⟺ WATCH ∈ eff ∧ READ ∈ eff`; `READ`/`LIST`/`WRITE`/`ADMIN` stay
> exact-match. Rationale: no watch endpoint exists, so the evaluation core is the **only** place to make
> INV-WATCH-READ both load-bearing and provable now (rather than leaving it as a comment for a future
> surface to honor or forget). The floor is minimal, sits after the increment-2 `eff` computation, and does
> not restructure the union/deny evaluation. Proven by the `WatchRequiresRead` suite and the red-team
> `WatchNeverOutreadsRead` / `NewCapabilityDenyNotEvadable` suites.

---

## 4. Production byte-identity (the proof obligation)

The wiring guarantee is that O-3 changes **no live authorization decision** for the deployed config.

**The root grant.** The only production grant is `ConfigdServer.java:726` —
`grant("", "root", EnumSet.allOf(AclService.Permission.class))`, gated by `config.authEnabled()`. Under O-3,
`allOf` auto-widens from three caps to five, so **root gains `LIST` and effective `WATCH`** (`WATCH ∧ READ`
both held). That is correct — root has everything — and it does not regress any existing decision:

- the historical `READ`/`WRITE`/`ADMIN` decisions for `root` are **unchanged** (a single rule at prefix
  `""`, a trivial one-element antichain, no `DENY` → union+deny == longest-match, exactly as in increment-2
  DL-W2-04);
- every **non-root** principal stays **default-denied** for every capability, including the two new ones;
- the two new caps for `root` are evaluated by the same membership logic, with `WATCH` passing its floor
  because `root` holds `READ`.

Runtime reachability seals it (as in increment-2): the production `AuthInterceptor` maps the sole valid
token to `Authenticated("root", …)` and everything else to `Denied`, so the only tuples the live API ever
evaluates are `("root", *, READ|WRITE)` (the only verbs the handler issues) — all true before and after.
Auth-disabled leaves `aclService` null and `checkAuth` skips `isAllowed` entirely.

The byte-identity model test `AclServiceTest.ProductionByteIdentity.singleRootGrantBehavesIdenticallyToLongestMatch`
was updated to mirror production **exactly** (`EnumSet.allOf(Permission.class)`) and now asserts root is
authorized for **all five** caps (incl. effective `WATCH`), the `READ`/`WRITE`/`ADMIN` decisions are
identical, and non-root is default-denied for all five.

**Which suites prove byte-identity, and at which layer:**

1. **Evaluation layer** — the ACL unit suites (`AclServiceTest` 53 + `AclServiceRedTeamTest` 41 = 94). The
   READ/WRITE/ADMIN proofs are unchanged from increment-2 and still green; the new suites are additive and
   touch only `LIST`/`WATCH`/the floor.
2. **Server (HTTP) layer** — the three `AbstractAdminApiServerContract` subclasses
   (`Jdk`/`Netty`/`NettyNioFallback` × 37 = **111**) exercise the live `GET→READ`, `PUT/DELETE→WRITE`
   authorization end-to-end (with `reader`/`writer` principals granted only `READ` / `READ+WRITE`). They
   pass **unchanged** — the server-layer byte-identity proof.

---

## 5. The test additions (per task §3 / deliverables B, C)

No historical assertion flips (unlike increment-2): the change is purely additive over the new vocabulary,
so all 70 prior ACL assertions remain green. New discriminating tests (each written to **fail** if the
semantics were wrong — they prove, not assert):

- `AclServiceTest` (43 → **53**, +10): `ListIndependentOfRead` (3 — both non-crossing directions + an
  independent `LIST` deny carve-out), `WatchRequiresRead` (6 — the full `WATCH ∧ READ` matrix:
  watch-without-read denied, watch+read allowed, read-alone-isn't-watch, `deny(READ)` kills watch,
  `deny(WATCH)` kills watch but not read, no-leak into other caps), `AdminIsNotSuperCapability` (1), and the
  updated `ProductionByteIdentity` (still 1, now modeling `allOf`/all-5).
- `AclServiceRedTeamTest` (27 → **41**, +14): `WatchNeverOutreadsRead` (5 — every-cap-but-READ,
  global-`deny(READ)`, order-independence, poisoned-decoy walk survival, ancestor-READ ∧ descendant-WATCH
  composition), `ListReadNonCrossing` (2 — no stack of one manufactures the other), `NewCapabilityDenyNotEvadable`
  (4 — stacked `deny(LIST)`/`deny(WATCH)`, `deny(READ)` as the second way to revoke watch, re-grant doesn't
  resurrect), a capability-uniformity proof folded into `PrefixBoundaryScoping` (+1), and
  `SingleKeyFloorIsNotWholeTargetCover` (2 — finding RC-O3-1: a single `isAllowed` at a subtree root does
  **not** cover a descendant `deny(READ)` the watch would deliver, pinning the single-key-vs-whole-target
  boundary that O-5 must honor).

The red-team core is **seeded** here; the red-team review added `SingleKeyFloorIsNotWholeTargetCover`
(RC-O3-1, above).

---

## 6. Implementation notes

- **The diff is two lines of logic.** The `isAllowed` tail gains a single `if (permission == WATCH)`
  branch; everything else is javadoc (the enum, the class formula, the method tail). The union/deny walk is
  untouched (no early-`break` reintroduced — the increment-2 `WalkStopEvasion` regression-catchers still
  guard it).
- **`EnumSet` capacity.** `EnumSet.noneOf(Permission.class)` and the bitmask reasoning are unaffected by two
  more constants (5 ≤ 64).
- **Not a storage-layer change.** As in increment-2, ACL evaluation is control-plane policy; the `acls` map
  is built fresh at boot and never serialized/replicated/persisted (DL-N-08 — not an N=1 byte-identity
  regression). The capability **enum** is likewise not persisted; no on-disk/wire representation carries a
  `Permission` ordinal.

---

## 7. RFC §5 conformance note

| RFC clause | Requirement | This increment |
|---|---|---|
| A5-1 | capability set `{READ, LIST, WRITE, WATCH, ADMIN}` + `DENY` | enum expanded in A5-1 order; `DENY` unchanged |
| A5-2 R-CAP-1 | `LIST ⊥ READ` (neither implies the other) | by construction (independent membership); proven both directions |
| A5-2 R-CAP-2 | `WATCH` requires `READ` | `isAllowed` enforces `WATCH ∈ eff ∧ READ ∈ eff` (DL-O3-03) |
| A5-2 | no other relationship (no "`ADMIN` implies others") | `ADMIN` exact-match (DL-O3-01) |
| A5-4 | union of ALLOW − DENY, deny-precedence, default-deny | unchanged from increment-2; the `WATCH` floor composes over `eff` |
| A6-4 INV-WATCH-READ | a watch never exposes what a read could not | the `READ` floor is the evaluation-core embodiment (single-key); the §6 subscribe-time check (O-5) applies it over the **whole subtree**, not just the prefix point |

The **boundary**: O-3 builds the **vocabulary + evaluation**. The `LIST` and `WATCH` **endpoints** are wired
later — `LIST` by O-2 (the list endpoint, A4-6) and `WATCH` by the RFC §2/§6 watch surface (O-5), which is
where the subscription-time `READ ∧ WATCH` whole-target check, the `full_chain_verify` root-scope gate
(A6-3), and the mandatory negative test (A6-5) land. Those surfaces call into this evaluation core, which is
already correct.

---

## 8. Handoff — entry points for the next increment

- **Increment 4 — O-6 (roles → policies → principals).** Make `AclService` role-aware (roles are already
  carried in `AuthResult.Authenticated(principal, roles)`, latent/unused) and introduce policies as the rule
  container, with the built per-principal grant as the degenerate case; policy-as-config under `/_acl/`
  (access-control.md §1, §1.2). The capability set this increment built is what those policies' rules grant.
- **O-2 — the `list` endpoint** wires the `LIST` enforcement point (`checkAuth(LIST)` on the prefix, A4-6 /
  access-control.md §7). The capability already exists; O-2 adds the call site.
- **O-5 — the watch surface** (RFC §2/§6) wires the `WATCH` subscription check. For a single-key target it
  calls `isAllowed(p, key, WATCH)` (already floored by `READ`). For a **subtree** target `T` it MUST
  additionally verify the floor covers **all of `T`** — reject (not narrow) if any `READ`/`WATCH` deny falls
  within `T` (a descendant carve-out is invisible to the single-key ancestor-walk; RFC A6-2/A6-4,
  access-control.md §6.5). It also adds the `full_chain_verify`/`FULL` root-scope gate (A6-3) and the
  mandatory zero-data-frame negative test (A6-5).
- **Segment-aware matching (A3.4 glob)** remains deferred to the binary/driver surface (DL-O3-02 / DL-W2-03)
  — the place to make a subtree deny cover its root and to scope siblings precisely.
- **Scope-aware ACL.** A5-4's `scope` axis is still not in the `isAllowed` signature.

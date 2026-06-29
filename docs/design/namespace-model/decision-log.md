# Hierarchical Path / Namespace Model — Decision Log

**Session: 2026-06-28. Design + recommendation + the first RFC section. No production code** (a
compile-checked type sketch under [`sketch/`](sketch/) is a design artifact). Standing autonomy policy:
design only; stop at the docs-only PR.

This log records the **methodology** and the **analytical decisions** behind [`prior-art.md`](prior-art.md),
[`path-model.md`](path-model.md), [`access-control.md`](access-control.md), and the RFC section
[`../../rfc/driver-protocol/01-paths-and-access.md`](../../rfc/driver-protocol/01-paths-and-access.md).
The decisions DL-N-* are the design team's evidence-based recommendations; the **operator-binding** calls
are the **Open items** at the end (and restated in each doc's §"Open questions").

---

## Implementation status (reconciled 2026-06-28 — Wiring Increments 2–4)

This log was a **design session** record; several recommendations have since been **built and merged**:

- **DL-N-08** (union-of-ancestors + absolute deny-precedence, superseding longest-match-only) → **BUILT,
  Wiring Increment 2 (`ee27250`).** `AclServiceTest` now asserts **union-of-ancestors**, not longest-match.
- **DL-N-09** (capabilities `{READ, LIST, WRITE, WATCH, ADMIN}` + per-capability `DENY`; LIST ⊥ READ;
  effective-WATCH = WATCH ∧ READ) → **BUILT, Wiring Increment 3 (`770d76f`).**
- **DL-N-08 role indirection** (roles → grants consumed by `AclService`) → **BUILT (O-6 Seam 1), Wiring
  Increment 4** — with the **literal-prefix** matcher (glob is DL-O3-02-deferred), **scope out of the rule**
  (DL-O6-02), and root as a **principal-scoped degenerate rule** (DL-O6-01). See the **O-6 Seam 1
  decisions** appended below the DL-N-* list.

So Open items **O-3** (capabilities) and **O-4** (composition) are **wired**, and **O-6** is **partially
wired** (Seam 1 role indirection done; **policy-as-config under `/_acl/` is Seam 2**, unbuilt). The
methodology note DL-N-01 ("longest-match-only", "roles carried but unused") and the bottom "no production
code / no `AclService` change" scope note describe the **design session only** and no longer describe `main`.

---

## Methodology

- **DL-N-01 — Ground-truth the BUILT reality before designing, from source, not ADRs.** Read the load-bearing
  code directly — `StaticShardMap.shardFor` (the hash), `AclService` (per-prefix, **longest-match-only**),
  `ConfigScope`, `ConfigMutation` (opaque `String key`), `ConfigWriteService`/`ConfigReadService` (the
  enforcement-adjacent paths), `AdminApiHandler.checkAuth` (the actual ACL enforcement point + the
  hardcoded `ConfigScope.GLOBAL` on every HTTP write), `VersionedConfigStore.getPrefix` (the **O(total)**
  scan), `AuthInterceptor` (roles **carried but unused**). *Why:* ADR-0017 reads `Accepted` but is **paper**
  (no namespace type in the code); the design must reconcile with what is *built*, not what is *written
  down*. Several design choices (no migration; scope-through-the-API is a wire add; roles already latent)
  fall directly out of source facts.
- **DL-N-02 — Primary-source the reference mechanisms, verbatim-flag the load-bearing ones.** Web-verified
  the few specifics the design leans on rather than trusting memory: ZK ACLs are **non-recursive /
  non-inherited** (the property we deliberately invert); ZK persistent **recursive** watches (3.6.0,
  `addWatch`); Vault capability set + **deny-precedence** (over everything incl. `sudo`) + `*`/`+` globs;
  etcd **users→roles→range(read/write/readwrite)** RBAC with `--prefix`; Curator **namespace-rooting**
  (chroot). *Why:* the docs feed a protocol RFC; reference-system claims must be accurate, not
  approximate. Sources are cited inline in `prior-art.md`.
- **DL-N-03 — Compile-check the load-bearing semantics, don't just prose them.** The path normalization,
  the union+deny evaluation, and the watch-authz contract are encoded as a standalone JDK-25 sketch with a
  `main()` of asserts mirroring the worked examples; `java -ea … SketchSmokeTest` ⇒ `SKETCH OK`. *Why:* the
  evaluation rule (union vs longest-match; deny-precedence) and the watch bypass closure are exactly where
  a prose-only design hides bugs.

## Analytical decisions (the recommendations)

- **DL-N-04 — INV-PATH: hierarchy is logical; routing stays hash-the-full-path** (`path-model.md` §2). The
  central invariant. `shardFor` already hashes the whole key (verified, `StaticShardMap.java:56-79`), so a
  subtree **scatters** by construction; the path tree is an *interpretation* of the opaque key for
  ACL/watch/list/management only. A subtree op is a **logical scatter-gather**. *Evidence it is not a new
  invention:* ADR-0020 already does logical prefix-matching (a radix trie over the event stream) while
  `shardFor` hashes the whole key — the design makes that discipline explicit and extends it. **This is the
  load-bearing constraint; every other decision is subordinate to it.**
- **DL-N-05 — Scope is an orthogonal typed axis; "namespace" is the top subtree by convention; the address
  is `(scope, path)`** (`path-model.md` §3.1). Scope already routes (folded into `shardFor` separately) and
  selects the replication domain — a typed, closed, 3-valued dimension; re-stringifying it as a magic path
  segment (ADR-0017's `/{namespace}/{scope}/{key_path}`) loses the type and contradicts the built routing.
  A tenant is a **Curator-rooted top subtree** isolated by a subtree ACL — no new typed axis. **Scope MAY
  select a shard pool (a typed axis); a path prefix MAY NOT (INV-PATH).** *Operator-confirm* (Open item O-1).
- **DL-N-06 — The config model is a pure superset of the built flat keyspace; no storage migration, no
  `shardFor` change, N=1 byte-identical** (`path-model.md` §6). The store/hash/Raft see only an opaque
  string; "path" lives above the store. A legacy dotted key (`db.host`) is a degenerate single-segment
  path (`/db.host`) — the dot is a legal segment char. The only deltas are **above** the store: surface
  `scope` through the API (a wire add — the HTTP path hardcodes GLOBAL today) and the ACL model change
  (DL-N-08). *Why this matters:* it makes the whole model adoptable without touching the hardened
  storage/consensus layers the N=1 byte-identity discipline governs.
- **DL-N-07 — `list` is an inherent scatter-gather; v1 = paginated scan, v2 = ordered index**
  (`path-model.md` §5). Under INV-PATH a subtree is not shard-local, so `list` **cannot** be served from one
  shard — it fans out to all N and unions. The built `getPrefix` is **O(total keys)** (linear scan over an
  unordered HAMT; the source flags it). Recommendation: **v1 ships the scatter-gather scan** (a
  control/management-plane op, not a hot read), **paginated** with a **per-shard cursor vector** (reusing
  the watch cursor shape) and **per-shard order only** (no global sort); the **ordered secondary index** is
  the named v2 optimization. *Operator-confirm* (O-2).
- **DL-N-08 — ACL: roles → policies → principals (Vault-shaped), union-of-ancestors + absolute
  deny-precedence, superseding the built longest-match-only** (`access-control.md` §1, §4). Roles are
  **already carried** in the auth token (latent); multi-tenancy is unmanageable per-principal at scale.
  The built `AclService` consults **only the single longest-matching prefix** — a hierarchy footgun (a
  parent grant does not compose with a child grant). The new rule **unions** all matching ALLOW rules and
  subtracts DENY (deny wins), default-deny. **Relationship to built behavior:** a **superset** when a
  principal has rules at one level (byte-identical decisions), a **deliberate fix** when rules exist at
  multiple levels. It is **control-plane policy**, not the storage layer — changing it is a policy decision,
  **not** an N=1 byte-identity regression. **BUILT in Wiring Increment 2 (`ee27250`):** `AclServiceTest` now
  asserts **union-of-ancestors** (it formerly asserted longest-match-only). Role indirection over this engine
  is **BUILT in Increment 4 (O-6 Seam 1)** — see the O-6 decisions below. *Wired (O-3, O-4).*
- **DL-N-09 — Capability set `{READ, LIST, WRITE, WATCH, ADMIN}` + `DENY`; LIST ⊥ READ; WATCH requires
  READ** (`access-control.md` §2). **LIST distinct from READ** (enumerating names is separately sensitive —
  Vault/ZK). **WATCH separate but gated by READ** (a watch is a streaming read + a subscription; operators
  may grant point READ but withhold streaming WATCH — but a watch must **never** expose what a read could
  not, so `WATCH ⟹ require READ`). Folding WATCH into READ is the **rejected** alternative (operators lose
  the ability to withhold the firehose). WRITE stays coarse (put+delete) for v1; create/update/delete split
  is a named v2 refinement. *Operator-confirm* (O-3).
- **DL-N-10 — The watch-authz contract, normative, closing the `full_chain_verify` bypass**
  (`access-control.md` §6; RFC §6). The watch research left subscription authz as one hand-wavy bullet with
  a concrete hole: `full_chain_verify` streams the **whole signed chain with no edge filtering**, so a
  subtree-scoped principal could pull **every tenant's** data "to verify locally." Closed normatively:
  (a) authorize **at subscription** as a streaming read — `READ ∧ WATCH` over the **entire** target, before
  any byte; (b) an over-broad target is **rejected, not silently filtered** (silent filtering gives a
  false-completeness view); (c) **`full_chain_verify`/`FULL` requires a root-scope grant** (`/**`); (d) a
  **mandatory negative test** proves zero data frames precede the reject. Encoded + asserted in the sketch.
  *Operator-confirm* (O-5).
- **DL-N-11 — Enforcement consistency via policy-as-config** (`access-control.md` §1.2, §7). Policy lives
  under a reserved subtree (`/_acl/`, `/_system/`) replicated by the same Raft, so the **edge** (which
  enforces WATCH at subscription) and the **control plane** (READ/WRITE/LIST) evaluate the **same bytes**
  with the **same engine**. By construction a watch authorizes a superset operation with the **same or
  stricter** result as a read — **a watch cannot bypass a read ACL** (INV-WATCH-READ). The reserved subtree
  is self-protected by `ADMIN` (a tenant role granted `/tenant/**` cannot reach `/_acl/**`).
- **DL-N-12 — Supersede ADR-0017 where it conflicts; keep its goals** (`prior-art.md` §5). ADR-0017's
  **namespace-as-typed-axis + Raft-group affinity + scope-in-path** is **superseded** (the affinity is the
  direct INV-PATH violation — pinning a subtree to a shard). Its **goals** (tenant isolation, per-tenant
  limits, lifecycle/quota) are **kept**: isolation via **subtree ACLs**, throughput isolation via
  **per-principal rate limiting** (already built, S7.5) and a possible future **scope-pool** variant, and
  the typed **registry/quota/lifecycle** as an optional **v2 logical overlay** over the top segment (never
  a routing input). A follow-up ADR should record ADR-0017 as **Superseded-in-part** once the operator
  confirms O-1. *Operator-confirm* (O-1, O-6).
- **DL-N-13 — The RFC section is normative and composes with the watch section** (RFC §9). One **shared
  cursor-vector type** for `list` and `watch` (vector **even at N=1** — the watch research's single most
  important forward-compat rule); one **shared ordering contract** (per-key + per-shard, never cross-shard);
  the **watch-authz contract lives in this section** and the watch section must not weaken it; capability
  identifiers **fail closed** when unrecognized so v2 extensions don't break older drivers. This is the
  "RFC alongside as decisions land" discipline — the path/namespace decision captured as the contract the
  wiring conforms to.

## O-6 Seam 1 decisions (Wiring Increment 4 — role-aware evaluation, byte-identical)

O-6 ("roles → policies → principals") is built in two seams. **Seam 1** (this increment) adds role
indirection to the evaluation engine with **static in-memory maps**, **byte-identical** to the deployed
config. **Seam 2** (next) is policy-as-config under `/_acl/`. The Seam 1 decisions:

- **DL-O6-01 — Root is a principal-scoped degenerate rule, NOT a "root role".** The deployed
  `ConfigdServer` `grant("","root",all)` is kept **unchanged** and understood as the degenerate rule
  `ALLOW {all}` on the **literal** prefix `""` for the **principal** `root`. This is the tightest
  byte-identity (the production grant path is literally untouched) and avoids introducing a privileged
  "root role". A real `root` role is an optional later refinement.
- **DL-O6-02 — Scope stays OUT of the rule for Seam 1.** `isAllowed` remains **scope-blind**; the rule is
  `(prefix, allow-caps, deny-caps)` with no scope field. Adding scope-to-rule changes the `isAllowed`
  signature and the enforcement call site — a separable ripple deferred to Seam 2 (or its own micro-seam).
- **DL-O6-03 — Role membership is additive from two sources, both EMPTY by default.** A principal's
  effective roles = **authn-asserted** roles (`AuthResult.Authenticated.roles()`, passed into `isAllowed`)
  ∪ an **ACL-static** `principal→roles` map. Their union resolves against role→grant **definitions**
  (`roleDefinitions`, empty default). Evaluation unions {the principal's own direct grants} ∪ {each role's
  rules} into **one** `(allow, deny)` accumulator pair, then applies O-4 union/absolute-deny/default-deny
  and the O-3 effective-WATCH floor **once** over the combined set — so **deny-precedence holds through
  roles** (a role-contributed ALLOW is overridden by any matching DENY, own or role-contributed; a role
  cannot escalate past a deny). With both maps empty and no role defined, every decision is byte-identical;
  in production `roles()`=`{"admin"}` resolves to an **undefined** role ⇒ contributes nothing.
- **Matcher (carried forward, DL-O3-02):** rules use the **literal `key.startsWith(prefix)`** matcher, NOT
  the sketch's glob `PathPattern`. Segment-aware/glob matching remains the deferred binary/driver surface.

**Seam 1 review residuals (four-lane review — all APPROVE, 0 must-fix; carried to Seam 2):**

- **Reserved asserted-role name `"admin"` (security-LEAD).** The production authenticator asserts the role
  name `"admin"` for `root` (`ConfigdServer:720`), but `root`'s authority comes entirely from its
  principal grant (DL-O6-01), so the asserted `"admin"` is **inert today** (no role named `admin` is
  defined). Treat `"admin"` as a **reserved** asserted-role name: once roles are live, defining a role
  literally named `admin` would retroactively affect `root` — a `DENY` rule there would carve `root`'s
  `allOf`. The roles-live increment (Seam 2) MUST reserve/guard it (or `root` should assert `Set.of()`
  once `ConfigdServer` is unfrozen). Cannot escalate anyone under the production authenticator (only
  `root` is ever asserted `admin`, and it is already maximal), hence a SHOULD, not a defect.
- **`AuthResult.Authenticated` hardened (code-reviewer + security-LEAD, applied this seam):** a compact
  constructor now `Objects.requireNonNull(roles)` + `Set.copyOf(roles)`, because the role-aware
  `isAllowed` newly reads `authed.roles()` on the request path from the pluggable `TokenValidator` SPI;
  closes a null→non-200 and an aliasing/CME gap at the authn/authz seam. Byte-identical in decisions.
- **Deprovisioning gap (security-LEAD):** `assignRole`/`defineRole` are additive with no
  `removeRole`/`unassignRole`/`undefineRole`. Acceptable for a boot-populated dormant seam; the roles-live
  increment should add the inverse operations.
- **`Role.rules()` recompute (code-reviewer):** flattens policies→rules on every call — dormant in Seam 1
  (no production caller), but lands on the request hot path the moment Seam 2 wires roles. Precompute the
  flattened list in the `Role` constructor at Seam 2.
- **Scope-blind ripple (DL-O6-02):** role rules match literal prefix only, exactly as scope-blind as own
  grants — no regression now (single `GLOBAL` scope, no live roles). Once scope enters the rule AND roles
  are live, a scope-blind role rule would over-grant across all scopes; must be addressed then.

---

## Open items (operator-binding — confirm before wiring)

| # | Item | Recommendation | Doc |
|---|---|---|---|
| **O-1** | Scope/path/namespace relationship | **scope = orthogonal typed axis; namespace = top subtree by convention**; address = `(scope, path)` (both shapes honor INV-PATH) | path-model §3.1 |
| **O-2** | `list` cost posture | **scatter-gather scan v1** (O(total), paginated, control-plane); **ordered index v2** | path-model §5 |
| **O-3** | Capability set | **`{READ, LIST, WRITE, WATCH, ADMIN}` + `DENY`**; LIST ⊥ READ; WATCH-requires-READ; coarse WRITE v1 | access-control §2 |
| **O-4** | ACL composition | **union-of-ancestors + absolute deny-precedence**, accepting it supersedes built **longest-match-only** (flagged, test-visible) | access-control §4.2 |
| **O-5** | Watch-authz contract | **authorize-at-subscription; reject over-broad (not filter); `full_chain_verify`/`FULL` ⇒ root scope; mandatory negative test** | access-control §6 |
| **O-6** | Model | **roles → policies → principals** (Vault-shaped), policy-as-config under `/_acl/`,`/_system/`; per-principal = degenerate case | access-control §1 |
| **O-7** | Recursive delete | **none in v1** (no cross-shard atomicity); best-effort `deleteSubtree` deferred to v2 with a loud non-atomic contract | path-model §4.1 |

**Status (2026-06-28):** **O-3** and **O-4** are **wired** (Increments 3 `770d76f` and 2 `ee27250`); **O-6**
is **partially wired** — role indirection is Seam 1 (Increment 4), policy-as-config under `/_acl/` is Seam 2
(unbuilt). **O-1, O-2, O-5, O-7** remain recommendations. The operator's confirmation turns each into the
contract the namespace/path wiring (and the driver protocol, and watches) conforms to.

## What this design did NOT do (scope honesty — the design session)

*(Describes the original 2026-06-28 design session. See [Implementation status](#implementation-status-reconciled-2026-06-28--wiring-increments-24) above for what has since been wired.)*

At design time: No production code; no path/ACL/watch wiring; no `shardFor`, `AclService`, or `ConfigScope`
change; no wire format; no `list`/`watch` implementation; no EC2, no money. The compile-checked sketch is a
**design artifact** (standalone, not in the build). The deliverable was a **decided design + a normative RFC
section** — the contract the wiring conforms to.

**Since then**, Wiring Increments 2–4 built the union+deny engine (DL-N-08/O-4), the capability set
(DL-N-09/O-3), and **role indirection (O-6 Seam 1)** into `AclService`. `shardFor`/`ConfigScope` remain
unchanged; **policy-as-config, `list`, `watch`, scope-in-rule, and glob matching remain unbuilt**.

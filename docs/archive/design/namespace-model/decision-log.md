# Hierarchical path / namespace model -- decision log

This log records the methodology and the analytical decisions behind the design in this directory and the
RFC section [`../../rfc/driver-protocol/01-paths-and-access.md`](../../../rfc/driver-protocol/01-paths-and-access.md).
The decisions DL-N-* are the design's evidence-based recommendations; the operator-binding calls are the
open items at the end (and restated in each doc's "Open questions" section).

---

## Implementation status

This log was originally a design-only record; most of its recommendations have since been built and
merged:

- **DL-N-08** (union-of-ancestors plus absolute deny-precedence, superseding longest-match-only) is built
  (commit `ee27250`). `AclServiceTest` now asserts union-of-ancestors, not longest-match.
- **DL-N-09** (capabilities `{READ, LIST, WRITE, WATCH, ADMIN}` plus per-capability `DENY`; LIST separate
  from READ; effective-WATCH = WATCH and READ) is built (commit `770d76f`).
- **DL-N-08 role indirection** (roles mapping to grants consumed by `AclService`) is built -- with a
  literal-prefix matcher (glob matching is deferred), scope kept out of the rule, and root modeled as a
  principal-scoped degenerate rule. See the role-indirection decisions appended below the DL-N-* list.

So open items O-3 (capabilities) and O-4 (composition) are wired, and O-6 is partially wired (role
indirection is done; policy-as-config under `/_acl/` came later). The methodology note DL-N-01
("longest-match-only", "roles carried but unused") and the "no production code / no `AclService` change"
scope note below describe the original design session only and no longer describe `main`.

---

## Methodology

- **DL-N-01 -- Ground-truth the built reality before designing, from source, not ADRs.** Read the
  load-bearing code directly -- `StaticShardMap.shardFor` (the hash), `AclService` (per-prefix,
  longest-match-only), `ConfigScope`, `ConfigMutation` (opaque `String key`),
  `ConfigWriteService`/`ConfigReadService` (the enforcement-adjacent paths), `AdminApiHandler.checkAuth`
  (the actual ACL enforcement point plus the hardcoded `ConfigScope.GLOBAL` on every HTTP write),
  `VersionedConfigStore.getPrefix` (the O(total) scan), `AuthInterceptor` (roles carried but unused). Why:
  ADR-0017 reads Accepted but is paper (no namespace type in the code); the design must reconcile with what
  is built, not what is written down. Several design choices (no migration; scope-through-the-API is a wire
  add; roles already latent) fall directly out of source facts.
- **DL-N-02 -- Primary-source the reference mechanisms, verbatim-flag the load-bearing ones.** Web-verified
  the few specifics the design leans on rather than trusting memory: ZK ACLs are non-recursive /
  non-inherited (the property this design deliberately inverts); ZK persistent recursive watches (3.6.0,
  `addWatch`); Vault capability set plus deny-precedence (over everything including `sudo`) plus `*`/`+`
  globs; etcd users-to-roles-to-range(read/write/readwrite) RBAC with `--prefix`; Curator namespace-rooting
  (chroot). Why: the docs feed a protocol RFC; reference-system claims must be accurate, not approximate.
  Sources are cited inline in [`prior-art.md`](prior-art.md).
- **DL-N-03 -- Compile-check the load-bearing semantics, don't just prose them.** The path normalization,
  the union+deny evaluation, and the watch-authz contract are encoded as a standalone JDK-25 sketch with a
  `main()` of asserts mirroring the worked examples; `java -ea ... SketchSmokeTest` gives `SKETCH OK`. Why:
  the evaluation rule (union vs. longest-match; deny-precedence) and the watch bypass closure are exactly
  where a prose-only design hides bugs.

## Analytical decisions (the recommendations)

- **DL-N-04 -- INV-PATH: hierarchy is logical; routing stays hash-the-full-path** (`path-model.md` §2). The
  central invariant. `shardFor` already hashes the whole key (verified, `StaticShardMap.java:56-79`), so a
  subtree scatters by construction; the path tree is an interpretation of the opaque key for
  ACL/watch/list/management only. A subtree op is a logical scatter-gather. Evidence it is not a new
  invention: ADR-0020 already does logical prefix-matching (a radix trie over the event stream) while
  `shardFor` hashes the whole key -- the design makes that discipline explicit and extends it. This is the
  load-bearing constraint; every other decision is subordinate to it.
- **DL-N-05 -- Scope is an orthogonal typed axis; "namespace" is the top subtree by convention; the address
  is `(scope, path)`** (`path-model.md` §3.1). Scope already routes (folded into `shardFor` separately) and
  selects the replication domain -- a typed, closed, 3-valued dimension; re-stringifying it as a magic path
  segment (ADR-0017's `/{namespace}/{scope}/{key_path}`) loses the type and contradicts the built routing.
  A tenant is a Curator-rooted top subtree isolated by a subtree ACL, no new typed axis. Scope may select a
  shard pool (a typed axis); a path prefix may not (INV-PATH). Operator-confirm (open item O-1).
- **DL-N-06 -- The config model is a pure superset of the built flat keyspace; no storage migration, no
  `shardFor` change, N=1 byte-identical** (`path-model.md` §6). The store/hash/Raft see only an opaque
  string; "path" lives above the store. A legacy dotted key (`db.host`) is a degenerate single-segment path
  (`/db.host`), the dot is a legal segment char. The only deltas are above the store: surface `scope`
  through the API (a wire add, the HTTP path hardcodes GLOBAL today) and the ACL model change (DL-N-08). Why
  this matters: it makes the whole model adoptable without touching the hardened storage/consensus layers
  the N=1 byte-identity discipline governs.
- **DL-N-07 -- `list` is an inherent scatter-gather; a paginated scan now, an ordered index as a later
  optimization** (`path-model.md` §5). Under INV-PATH a subtree is not shard-local, so `list` cannot be
  served from one shard -- it fans out to all N and unions. The built `getPrefix` is O(total keys) (a
  linear scan over an unordered HAMT; the source flags it). Recommendation: ship the scatter-gather scan
  first (a control/management-plane op, not a hot read), paginated with a per-shard cursor vector (reusing
  the watch cursor shape) and per-shard order only (no global sort); an ordered secondary index is the
  named later optimization. Operator-confirm (O-2).
- **DL-N-08 -- ACL: roles to policies to principals (Vault-shaped), union-of-ancestors plus absolute
  deny-precedence, superseding the built longest-match-only** (`access-control.md` §1, §4). Roles are
  already carried in the auth token (latent); multi-tenancy is unmanageable per-principal at scale. The
  built `AclService` consults only the single longest-matching prefix, a hierarchy footgun (a parent grant
  does not compose with a child grant). The new rule unions all matching ALLOW rules and subtracts DENY
  (deny wins), default-deny. Relationship to built behavior: a superset when a principal has rules at one
  level (byte-identical decisions), a deliberate fix when rules exist at multiple levels. It is
  control-plane policy, not the storage layer -- changing it is a policy decision, not an N=1
  byte-identity regression. Built: `AclServiceTest` now asserts union-of-ancestors (it formerly asserted
  longest-match-only). Role indirection over this engine is also built -- see the role-indirection
  decisions below. Wired (O-3, O-4).
- **DL-N-09 -- Capability set `{READ, LIST, WRITE, WATCH, ADMIN}` plus `DENY`; LIST separate from READ;
  WATCH requires READ** (`access-control.md` §2). LIST distinct from READ (enumerating names is separately
  sensitive, per Vault/ZK). WATCH separate but gated by READ (a watch is a streaming read plus a
  subscription; operators may grant point READ but withhold streaming WATCH, but a watch must never expose
  what a read could not, so WATCH implies requiring READ). Folding WATCH into READ is the rejected
  alternative (operators lose the ability to withhold the firehose). WRITE stays coarse (put+delete) for
  now; a create/update/delete split is a named later refinement. Operator-confirm (O-3).
- **DL-N-10 -- The watch-authz contract, normative, closing the `full_chain_verify` bypass**
  (`access-control.md` §6; RFC §6). The watch research left subscription authz as one hand-wavy bullet with
  a concrete hole: `full_chain_verify` streams the whole signed chain with no edge filtering, so a
  subtree-scoped principal could pull every tenant's data "to verify locally." Closed normatively: (a)
  authorize at subscription as a streaming read, READ and WATCH over the entire target, before any byte;
  (b) an over-broad target is rejected, not silently filtered (silent filtering gives a false-completeness
  view); (c) `full_chain_verify`/`FULL` requires a root-scope grant (`/**`); (d) a mandatory negative test
  proves zero data frames precede the reject. Encoded and asserted in the sketch. Operator-confirm (O-5).
- **DL-N-11 -- Enforcement consistency via policy-as-config** (`access-control.md` §1.2, §7). Policy lives
  under a reserved subtree (`/_acl/`, `/_system/`) replicated by the same Raft, so the edge (which enforces
  WATCH at subscription) and the control plane (READ/WRITE/LIST) evaluate the same bytes with the same
  engine. By construction a watch authorizes a superset operation with the same or stricter result as a
  read -- a watch cannot bypass a read ACL (INV-WATCH-READ). The reserved subtree is self-protected by
  ADMIN (a tenant role granted `/tenant/**` cannot reach `/_acl/**`).
- **DL-N-12 -- Supersede ADR-0017 where it conflicts; keep its goals** (`prior-art.md` §5). ADR-0017's
  namespace-as-typed-axis plus Raft-group affinity plus scope-in-path is
  superseded (the affinity is the direct INV-PATH violation, pinning a subtree to a shard). Its goals
  (tenant isolation, per-tenant limits, lifecycle/quota) are kept: isolation via subtree ACLs, throughput
  isolation via per-principal rate limiting (already built) and a possible future scope-pool variant, and
  the typed registry/quota/lifecycle as an optional later logical overlay over the top segment (never a
  routing input). A follow-up ADR should record ADR-0017 as superseded-in-part once the operator confirms
  O-1. Operator-confirm (O-1, O-6).
- **DL-N-13 -- The RFC section is normative and composes with the watch section** (RFC §9). One shared
  cursor-vector type for `list` and `watch` (vector even at N=1, the watch research's single most important
  forward-compat rule); one shared ordering contract (per-key plus per-shard, never cross-shard); the
  watch-authz contract lives in this section and the watch section must not weaken it; capability
  identifiers fail closed when unrecognized so future extensions don't break older drivers. This is the
  "RFC alongside as decisions land" discipline: the path/namespace decision captured as the contract the
  wiring conforms to.

## Role-indirection decisions (role-aware evaluation, byte-identical)

O-6 ("roles to policies to principals") was built in two phases. The first phase adds role indirection to
the evaluation engine with static in-memory maps, byte-identical to the deployed config. The second phase
is policy-as-config under `/_acl/`, itself split into a load/reload sub-phase (the serializer plus
atomic-swap load/reload, byte-identical, built) and an enforcement sub-phase (the ADMIN gate plus
reserved-namespace plus self-protection). The first-phase decisions:

- **Root is a principal-scoped degenerate rule, not a "root role."** The deployed `ConfigdServer`
  `grant("","root",all)` is kept unchanged and understood as the degenerate rule `ALLOW {all}` on the
  literal prefix `""` for the principal `root`. This is the tightest byte-identity (the production grant
  path is literally untouched) and avoids introducing a privileged "root role." A real `root` role is an
  optional later refinement.
- **Scope stays out of the rule for this phase.** `isAllowed` remains scope-blind; the rule is `(prefix,
  allow-caps, deny-caps)` with no scope field. Adding scope-to-rule changes the `isAllowed` signature and
  the enforcement call site, a separable ripple deferred to a later phase.
- **Role membership is additive from two sources, both empty by default.** A principal's effective roles
  equal authn-asserted roles (`AuthResult.Authenticated.roles()`, passed into `isAllowed`) union an
  ACL-static `principal -> roles` map. Their union resolves against role-to-grant definitions
  (`roleDefinitions`, empty default). Evaluation unions the principal's own direct grants with each role's
  rules into one `(allow, deny)` accumulator pair, then applies the union/absolute-deny/default-deny rule
  and the effective-WATCH floor once over the combined set -- so deny-precedence holds through roles (a
  role-contributed ALLOW is overridden by any matching DENY, own or role-contributed; a role cannot
  escalate past a deny). With both maps empty and no role defined, every decision is byte-identical; in
  production `roles()`=`{"admin"}` resolves to an undefined role and contributes nothing.
- **Matcher (carried forward):** rules use the literal `key.startsWith(prefix)` matcher, not the sketch's
  glob `PathPattern`. Segment-aware/glob matching remains deferred to a future binary/driver surface.

**Review residuals from this phase (all approved, zero must-fix; carried forward):**

- **Reserved asserted-role name `"admin"`.** The production authenticator asserts the role name `"admin"`
  for `root` (`ConfigdServer:720`), but `root`'s authority comes entirely from its principal grant (above),
  so the asserted `"admin"` is inert at this point (no role named `admin` is defined). Treat `"admin"` as a
  reserved asserted-role name: once roles are live, defining a role literally named `admin` would
  retroactively affect `root`, a `DENY` rule there would carve `root`'s `allOf`. The next phase must
  reserve/guard it (or `root` should assert `Set.of()` once `ConfigdServer` is unfrozen). It cannot escalate
  anyone under the production authenticator (only `root` is ever asserted `admin`, and it is already
  maximal), hence a should, not a defect.
- **`AuthResult.Authenticated` hardened:** a compact constructor now `Objects.requireNonNull(roles)` plus
  `Set.copyOf(roles)`, because the role-aware `isAllowed` newly reads `authed.roles()` on the request path
  from the pluggable `TokenValidator` SPI; closes a null-to-non-200 and an aliasing/CME gap at the
  authn/authz seam. Byte-identical in decisions.
- **Deprovisioning gap:** `assignRole`/`defineRole` are additive with no `removeRole`/`unassignRole`/
  `undefineRole`. Acceptable for a boot-populated dormant seam; a later phase should add the inverse
  operations.
- **`Role.rules()` recompute:** flattens policies to rules on every call, dormant in this phase (no
  production caller), but lands on the request hot path the moment the next phase wires roles. Precompute
  the flattened list in the `Role` constructor then.
- **Scope-blind ripple:** role rules match literal prefix only, exactly as scope-blind as own grants, no
  regression now (single `GLOBAL` scope, no live roles). Once scope enters the rule and roles are live, a
  scope-blind role rule would over-grant across all scopes; must be addressed then.

## Policy-as-config load/reload decisions (byte-identical)

This phase makes the role/policy model loadable from config under the reserved `_acl/` key subtree,
without changing any authorization decision (production defines no `_acl/` keys, so the config-policy
snapshot is empty and the result is byte-identical). The decisions:

- **Serializer is strict line-oriented text, multi-key under `_acl/`** (`_acl/roles/<name>`,
  `_acl/bindings/<principal>`). No JSON library exists in the codebase; text is more operable for
  operator-authored policy and avoids a JSON parser surface. Literal-prefix only, scope out of the rule.
- **Config-policy sits behind one versioned snapshot (atomic swap plus monotonic publish).** A
  deeply-immutable `ConfigPolicy` plus the store version it was derived from, published via a single
  `AtomicReference` swap; `isAllowed` reads it once so a concurrent reload is never torn (a torn-read fix).
  The loader's publish is version-ordered (`publishConfigPolicy(long, ConfigPolicy)` ignores a stale/older-
  version publish, paired with `VersionedConfigStore.getPrefixVersioned`) so an out-of-order rebuild -- a
  slow boot seed racing a concurrent apply-thread rebuild -- cannot resurrect stale state over a newer
  policy (an out-of-order-write fix; confirmed by adversarial review, closed). It is a separate, additive
  sub-layer; the static imperative layer (including the `grant("","root",all)` root rule above) is
  untouched; an empty default means byte-identical. (An authn-asserted role name resolves against config
  roles, so `AuthResult.roles()` must come from a trusted authenticator, dormant at this phase.)
- **Fail-closed-to-last-good.** Malformed or reserved-colliding policy is rejected (logged plus a metric),
  keeping the current snapshot, never deny-all, never allow-all. A well-formed-but-incomplete policy
  (binding to a not-yet-loaded role) is inert, not a failure (lets the idempotent whole-subtree rebuild
  converge across out-of-order multi-key writes / the snapshot-WAL split).
- **The "admin" footgun is neutralized (resolves the earlier residual).** Root is un-carveable by any
  config role via three closes: root asserts `Set.of()` (the honest version of the root-rule decision above;
  byte-identical since `admin` was never defined); the loader reserves the root principal (rejects a
  config binding to it); the loader reserves the role name `admin` (forward-compat for the enforcement
  phase). Proven: a config binding `root -> deny-all` is rejected and root stays fully authorized.
- **Snapshot-install rebuild hook.** `ConfigStateMachine` gains an additive, empty-default snapshot-install
  listener (fired after a successful `restoreSnapshot`) so InstallSnapshot-delivered `_acl/` keys (follower
  catch-up) converge through the same idempotent rebuild. Byte-identical.

Disposition of the earlier residuals at this phase: the `"admin"` reserved-name residual is resolved (the
three closes above). Still carried forward: the `Role.rules()` precompute (now a hot path under config),
deprovisioning (`removeRole`/`unassignRole`/`undefineRole`), the scope-blind ripple, and (new) multi-shard
`_acl/` aggregation at N>1 (a pre-existing single-`aclService` limitation; N=1 production is complete and
byte-identical).

---

## Open items (operator-binding -- confirm before wiring)

| # | Item | Recommendation | Doc |
|---|---|---|---|
| **O-1** | Scope/path/namespace relationship | scope = orthogonal typed axis; namespace = top subtree by convention; address = `(scope, path)` (both shapes honor INV-PATH) | path-model §3.1 |
| **O-2** | `list` cost posture | scatter-gather scan first (O(total), paginated, control-plane); an ordered index is a later optimization | path-model §5 |
| **O-3** | Capability set | `{READ, LIST, WRITE, WATCH, ADMIN}` plus `DENY`; LIST separate from READ; WATCH requires READ; coarse WRITE for now | access-control §2 |
| **O-4** | ACL composition | union-of-ancestors plus absolute deny-precedence, accepting it supersedes the built longest-match-only (flagged, test-visible) | access-control §4.2 |
| **O-5** | Watch-authz contract | authorize-at-subscription; reject over-broad (not filter); `full_chain_verify`/`FULL` requires root scope; mandatory negative test | access-control §6 |
| **O-6** | Model | roles to policies to principals (Vault-shaped), policy-as-config under `/_acl/`, `/_system/`; per-principal is the degenerate case | access-control §1 |
| **O-7** | Recursive delete | none for now (no cross-shard atomicity); a best-effort `deleteSubtree` is deferred, with a loud non-atomic contract | path-model §4.1 |

**Status:** O-3 and O-4 are wired (commits `770d76f` and `ee27250`); O-6 is partially wired, role
indirection is done, policy-as-config under `/_acl/` came later. O-1, O-2, O-5, O-7 remain
recommendations. The operator's confirmation turns each into the contract the namespace/path wiring (and
the driver protocol, and watches) conforms to.

## What this design did not do (scope honesty at design time)

*(Describes the original design session. See [Implementation status](#implementation-status) above for
what has since been wired.)*

At design time: no production code; no path/ACL/watch wiring; no `shardFor`, `AclService`, or `ConfigScope`
change; no wire format; no `list`/`watch` implementation. The compile-checked sketch is a design artifact
(standalone, not in the build). The deliverable was a decided design plus a normative RFC section, the
contract the wiring conforms to.

Since then, later work built the union+deny engine (DL-N-08/O-4), the capability set (DL-N-09/O-3), and
role indirection (O-6, first phase) into `AclService`. `shardFor`/`ConfigScope` remain unchanged;
policy-as-config, `list`, `watch`, scope-in-rule, and glob matching remained unbuilt at the time this log
was last updated.

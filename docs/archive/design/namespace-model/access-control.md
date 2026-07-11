# Configd access-control model -- the path-aware ACL extension

Design work from 2026-06-28, docs-only, no production code at the time. Builds on
[`prior-art.md`](prior-art.md) (Vault/ZK/etcd ACL mechanisms) and [`path-model.md`](path-model.md) (the
path model plus INV-PATH). Feeds the RFC section
[`../../rfc/driver-protocol/01-paths-and-access.md`](../../../rfc/driver-protocol/01-paths-and-access.md).

This document designs Configd's access-control model over the hierarchical path namespace: the model
(roles/policies vs. per-principal), subtree grants and their composition, the capability set (`READ /
LIST / WRITE / WATCH / ADMIN` plus `DENY`), and, the section that closes the gap the watch research left
open, the normative watch-authorization contract, including the `full_chain_verify` full-store bypass.
Normative keywords (MUST / SHOULD / MAY) are used deliberately; the RFC restates them.

---

## Implementation status

This document was written as a design recommendation; most of its recommendations have since been built
and merged to `main`. Where the prose below calls the built `AclService` "longest-match-only", or the
capabilities/roles "new/unused", read it against this table:

| Recommendation | Status | Landed in |
|---|---|---|
| Union-of-ancestors plus absolute deny-precedence, superseding longest-match-only -- §4 | built | commit `ee27250` |
| Capabilities `{READ, LIST, WRITE, WATCH, ADMIN}` plus per-capability `DENY`; LIST separate from READ; effective-WATCH = WATCH and READ -- §2, §2.1 | built | commit `770d76f` |
| Role indirection -- `AclService` consumes `AuthResult.roles()`; static role-to-grant definitions (empty default) -- §1, §4.1 | built | -- |
| Policy-as-config under `/_acl/`, load plus reload-on-apply (serializer plus atomic-swap snapshot, byte-identical; fail-closed-to-last-good) -- §1.2 | built | -- |
| Policy-as-config enforcement -- `/_acl/` ADMIN gate plus reserved-namespace write protection plus self-protection (§1.2, the "self-protecting" bullet) | built later | -- |
| Path-glob / segment-aware `pathPattern` matching (§1, §3, §4.1) | unbuilt -- the built matcher is literal `key.startsWith(prefix)` (deferred) | -- |
| Scope-in-rule (`rule.scope ⊇ scope`, §4.1) | unbuilt -- built evaluation is scope-blind | -- |
| Watch-authz at subscription (§6); `list` endpoint (§7) | built later | -- |

The built rule is `(String prefix, allow-caps, deny-caps)` with literal-prefix matching, not the glob
`pathPattern` the prose sketches. Role membership is additive (authn-asserted `roles()` union an
ACL-static `principal -> roles` map, both empty by default so the result is byte-identical), and the
deployed `grant("","root",all)` is a principal-scoped degenerate rule, not a "root role".

---

## 0. The model in one paragraph

Authorization is path-glob policies bound to principals through roles (the Vault model, adapted). A policy
is a set of rules `(scope, pathPattern) -> {capabilities}` (with an optional `DENY`). A role is a named
bundle of policies; a principal holds roles (the roles already carried in
`AuthResult.Authenticated(principal, roles)`, now consumed by `AclService`). A request for capability `C`
on `(scope, path)` is authorized iff the union of all the principal's matching `ALLOW` rules grants `C` and
no matching `DENY` rule names `C` -- deny always wins. Grants are inherited down a subtree (a rule on
`/a/` covers `/a/**`). This superseded the former `AclService` longest-match-only evaluation, built as
described in §4.2. A `WATCH` capability is added; a watch is authorized at subscription as a streaming
read -- it requires `READ` and `WATCH` over the entire target, and the `full_chain_verify` full-store
watch requires a root-scope grant (§6).

---

## 1. The recommended model: roles to policies to principals (Vault-shaped)

**Decision (recommended): adopt path-glob policies bound to principals through roles, replacing
per-principal ad-hoc grants.** The unit of authorization is the policy rule; principals acquire rules
indirectly through roles.

```
Principal ──holds──► Role(s) ──bundle──► Policy(ies) ──contain──► Rule(s): (scope, pathPattern) → caps
```

### 1.1 Why roles/policies over per-principal (the tradeoff, decided)

| | Per-principal path-ACL (built) | Roles → policies (recommended) |
|---|---|---|
| Management at scale | Poor -- every principal grants individually; 1000 service identities means 1000 grant sets | Good -- define `tenant-payments-rw` once, attach to every payments identity |
| Multi-tenancy fit | Weak -- no natural "tenant role" | Strong -- a tenant is a role bundling `{READ,LIST,WATCH,WRITE}` on `/tenant/**` |
| Built substrate | `AclService` keys on `principal` | roles already carried in `AuthResult.Authenticated(principal, roles)`, now consumed by `AclService` |
| Complexity | Lower -- one indirection | Higher -- principal to role to policy to rule |
| Revocation | per-principal | revoke a role (affects all its principals) -- and per-principal override via a principal-scoped policy |

**The call:** the operator's entire rationale for hierarchy is "RBAC maps naturally onto path subtrees,"
and multi-tenancy (the ADR-0017 driver) is unmanageable per-principal at scale. Roles/policies is the
right model. It is also the least-new-machinery path: roles are already in the auth token; the work is
(a) make `AclService` role-aware and (b) introduce policies as the rule container. The built per-principal
grant becomes the degenerate case -- a principal-scoped policy with one rule -- so existing grants keep
working.

*This is an operator-confirm item* ([`decision-log.md`](decision-log.md) DL-N-06): the recommendation is
roles/policies; per-principal remains available as the degenerate case for simple deployments.

### 1.2 Policy storage -- policy-as-config (so enforcement is consistent everywhere)

Policies are themselves configuration, stored under a reserved subtree (recommend `/_acl/`, and `/_system/`
reserved generally, matching ADR-0017's `/_system/namespaces/`) and replicated by the same Raft path as
ordinary config. Consequences:

- **Consistent enforcement.** The control-plane write/read handlers and the edge (which enforces `WATCH`
  at subscription, §6) read the same replicated policy. A watch cannot bypass a read ACL because both
  consult the identical, consistently-replicated policy (§7).
- **Auditable and versioned.** Policy changes are commit-confirmed mutations (ADR-0033), versioned and
  auditable like any write.
- **Self-protecting.** The `/_acl/` and `/_system/` subtrees require `ADMIN` (and are denied to ordinary
  tenant roles), exactly as ADR-0017 isolates `/_system`. A tenant role granted `/tenant/**` does not reach
  `/_acl/**`.

---

## 2. The capability set

**Decision (recommended capability set): `{ READ, LIST, WRITE, WATCH, ADMIN }`, plus a `DENY` modifier.**

| Capability | Grants | Distinct because | Built? |
|---|---|---|---|
| READ | get the value at a concrete path | the baseline read | yes, `Permission.READ` |
| LIST | enumerate children/descendants of a path (the names) | knowing a key exists is sensitive -- listing `/secrets/` reveals secret names even without reading values (the Vault/ZK lesson, [`prior-art.md`](prior-art.md) §3.2) | yes, `Permission.LIST` |
| WRITE | put + delete at a concrete path | the baseline mutate | yes, `Permission.WRITE` |
| WATCH | subscribe to a change stream on a path/subtree | a watch is a streaming read plus a standing subscription (a resource commitment / potential firehose); operators may grant point READ but withhold streaming WATCH | yes, `Permission.WATCH`; effective-WATCH = WATCH and READ |
| ADMIN | manage policies/roles/grants for a subtree (delegated administration); access `/_acl/`,`/_system/` | governs who may change authorization, not data | yes, `Permission.ADMIN` |
| DENY (modifier) | explicitly remove a capability for a subtree, with absolute precedence | makes "grant the subtree, carve out a sensitive child" safe (Vault) | yes, `deny()` per-capability, absolute precedence |

### 2.1 The two capability-relationship rules (normative)

- **R-CAP-1 -- LIST is independent of READ.** `LIST` does not imply `READ` and `READ` does not imply
  `LIST`. A principal MAY enumerate child names without reading values, or read a known key without
  enumeration rights. (Vault's orthogonal `list`/`read`.)
- **R-CAP-2 -- WATCH requires READ (WATCH is at least as restrictive as READ).** `WATCH` is a separate
  grantable capability, but it is ineffective without `READ` over the same target: to watch target `T`,
  the principal MUST hold both `READ(T)` and `WATCH(T)`. A watch MUST NEVER expose what a read could not.
  This is the load-bearing rule that prevents a watch from bypassing the read ACL (§6).

  > Rationale for separate-but-gated (vs. `WATCH = READ`): a watch is operationally heavier than a point
  > read (a standing subscription, a subtree firehose). Operators want to grant `READ` broadly but `WATCH`
  > selectively. So `WATCH` is its own capability, but floored by `READ` so it can never widen what is
  > visible. Folding `WATCH` into `READ` is the rejected alternative ([`decision-log.md`](decision-log.md)
  > DL-N-08).

### 2.2 WRITE granularity (coarse now, a later refinement)

The built model has a single coarse `WRITE` (= put + delete). Vault splits `create`/`update`/`delete`.
This design keeps the coarse `WRITE` (least disruption; matches the built `AdminApiHandler` WRITE check on
both PUT and DELETE). The `create`/`update`/`delete` split is a named later refinement (e.g., to grant
append-only or no-delete roles). Recorded as an extension point, not built.

---

## 3. Subtree grants -- the hierarchy payoff

A rule's `pathPattern` denotes a subtree (or an exact path, or a single-segment glob;
[`path-model.md`](path-model.md) §3.3). A grant on `/team-payments/` covers `/team-payments/**`, inherited
down (the Vault/etcd-prefix model; the inverse of ZK's non-inherited per-node ACLs,
[`prior-art.md`](prior-art.md) §1.2). This is the reason the operator chose hierarchy: one grant
authorizes a whole tenant/team subtree.

- A tenant is rooted at `/tenant/` (Curator namespace-rooting): a `tenant-payments` role bundles a policy
  `{READ, LIST, WATCH, WRITE}` on `/team-payments/**` and nothing else. A principal with only that role can
  read, enumerate, watch, and write its subtree and is invisible to / blocked from every other subtree --
  ADR-0017's tenant isolation, delivered by subtree ACLs (no namespace-routing,
  [`prior-art.md`](prior-art.md) §5).
- **Logical, not physical.** A subtree grant is metadata ([`path-model.md`](path-model.md) §2.2), stored
  once (keyed on the prefix) and consulted logically on each access. It does not scatter and does not
  affect routing. The enforcement of a subtree grant on a subtree watch/list is a logical check over the
  prefix, even though the watched/listed data scatters across shards (§6.3).

---

## 4. Composition semantics -- union of ancestors, deny wins

**Decision (recommended): replace longest-match-only with union-of-matching-rules plus absolute
deny-precedence (the Vault model).**

### 4.1 The evaluation rule (normative)

For a request of capability `C` on `(scope, path)` by principal `p`:

```
matchingRules(p, scope, path) =
    { rule ∈ policies(roles(p)) : rule.scope ⊇ scope ∧ rule.pattern matches path }

allowSet = ⋃ { rule.caps : rule ∈ matchingRules, rule.effect == ALLOW }
denySet  = ⋃ { rule.caps : rule ∈ matchingRules, rule.effect == DENY }

authorized(C) ⟺ C ∈ allowSet ∧ C ∉ denySet      # union to allow; deny always wins
```

- **Union (not longest-match-only).** Caps from all matching rules (every ancestor prefix, every role) are
  unioned. A `READ` on `/a/` and a `WRITE` on `/a/b/` give a principal `READ+WRITE` on `/a/b/x`, the
  natural hierarchical expectation.
- **Deny precedence (absolute).** A matching `DENY` for `C` removes `C` regardless of any `ALLOW`,
  including rules on more-specific paths and including `ADMIN`. (Vault: deny beats everything, even sudo.)
  This makes "grant `/a/**`, deny `/a/b/secret/**`" safe and expressible.
- **Default-deny.** No matching `ALLOW` implies denied. (Built: the deployed `grant("", "root", all)`
  catch-all is modeled as a principal-scoped degenerate rule, `ALLOW {all}` on the literal prefix `""` for
  the `root` principal, not a "root role", and stays byte-identical. A real `root` role is an optional
  later refinement.)

### 4.2 The longest-match to union+deny supersession

> **Status: built.** This section recommended a change to the then-built ACL; that change has since
> landed. The text is kept as the rationale, reconciled to past tense.

The former `AclService.isAllowed` consulted only the single longest-matching prefix: it found the longest
prefix via `floorKey` plus walk-back and returned that prefix's caps, ignoring shorter (ancestor) prefixes.
So a `READ` grant on `/a/` was silently dropped for a key under `/a/b/` if a separate grant existed on
`/a/b/`, even if that grant lacked `READ`. That was a hierarchy footgun: a parent grant did not compose
with a child grant.

The built model now unions ancestors and adds deny -- `AclService.isAllowed` walks every matching ancestor
(`floorKey` to `lowerKey` plus `startsWith`), unions ALLOW, subtracts DENY with absolute precedence,
default-deny. Relationship to the prior behavior:

- **Superset in the common case.** When a principal has a rule at only one level for a path (the typical
  case), union equals longest-match, byte-identical decisions. The deployed single-root-grant config is a
  trivial antichain, so production decisions are byte-identical (pinned by
  `AclServiceTest.ProductionByteIdentity`).
- **Deliberate fix in the overlap case.** When a principal has rules at multiple levels for a path, the
  built model unions them (the prior model dropped all but the longest). `AclServiceTest` now asserts
  union-of-ancestors (the `UnionOfAncestors` / `DenyPrecedence` suites); the old longest-match assertions
  were replaced when this landed.
- **Not a storage-layer change.** ACL evaluation is control-plane policy, not the storage/consensus layer
  the N=1 byte-identity discipline governs ([`path-model.md`](path-model.md) §6.1), a policy decision, not
  a byte-identity regression ([`decision-log.md`](decision-log.md) DL-N-07).

### 4.3 Worked example

Roles for principal `p`: `{ tenant-payments, payments-secrets-reader }`. Effective rules:

```
ALLOW {READ, LIST, WATCH, WRITE} on /team-payments/**          # tenant-payments role
ALLOW {READ}                     on /team-payments/secrets/**  # payments-secrets-reader role
DENY  {LIST}                     on /team-payments/secrets/**  # carve-out: may read known secrets, not enumerate them
```

| Request | Decision | Why |
|---|---|---|
| `READ /team-payments/flags/checkout` | allowed | union: `READ` from rule 1 |
| `WRITE /team-payments/flags/checkout` | allowed | union: `WRITE` from rule 1 |
| `READ /team-payments/secrets/stripe-key` | allowed | union: `READ` from rules 1 and 2 |
| `LIST /team-payments/secrets/` | denied | `LIST` in allow (rule 1) but `LIST` in deny (rule 3), deny wins (can read known secret paths, cannot enumerate them) |
| `WATCH /team-payments/secrets/**` | allowed as caps, but see §6 | has `READ`(1,2) and `WATCH`(1) over the target, authorized as a streaming read |
| `READ /team-billing/invoices/x` | denied | no matching ALLOW, default-deny (tenant isolation) |

---

## 5. (Reserved -- composition formalized in §4; numbering kept for cross-refs.)

The longest-match-only to union+deny change and its byte-identity relationship are in §4.2.
Cross-references elsewhere to "§5 (the flagged ACL change)" point here.

---

## 6. The watch-authorization contract (closing the gap the watch research left open)

The watch research ([`../../research/watches/recommendation.md`](../../research/watches/recommendation.md)
§8) left subscription authorization as a single bullet: "Subscriptions MUST be authorized against the
client's namespace ACL ... a client MUST only watch prefixes within its authorized namespaces." That is the
right intent but underspecified, and it has a concrete bypass: the `full_chain_verify` full-store watch
(§7/§10 of that doc) streams the entire verbatim signed chain with no edge filtering. This section
specifies the contract normatively and closes the bypass.

### 6.1 W-AUTHZ-1 -- a watch is authorized at subscription as a streaming read

A watch on target `T` (a key, a prefix/subtree, or `FULL`) MUST be authorized at subscription time, before
any snapshot chunk or change event flows, as a streaming read over `T`: the principal MUST hold `READ(T)`
and `WATCH(T)`, i.e., the union of its `ALLOW` rules minus deny MUST grant both `READ` and `WATCH` covering
all of `T` (§4.1, R-CAP-2). If either is missing for any part of `T`, the subscription MUST be rejected
before the first byte (§6.4).

### 6.2 W-AUTHZ-2 -- over-broad targets are rejected, not silently filtered

If `T` extends beyond the principal's authorized region (the grant does not cover all of `T`), the server
MUST reject the subscription with a terminal error (§6.4), it MUST NOT silently narrow the watch to the
authorized sub-subset.

> Rationale: silent narrowing means the client cannot distinguish "no changes occurred under `T`" from
> "changes occurred but were suppressed by ACL." A watcher that believes it sees all of `T` while ACL
> hides part of it has a false completeness view, a correctness and security footgun (it may, e.g.,
> conclude a config is unchanged when it changed in a hidden sub-subtree). Reject loudly. A client that
> genuinely wants "watch the part of `T` I'm allowed to" MUST request that explicit narrower target. (A
> later `filtered-watch` mode that returns the authorized subset with an explicit "narrowed" signal may be
> added; the design specified here is reject-if-not-fully-covered.)

### 6.3 W-AUTHZ-3 -- `full_chain_verify` / full-store watch requires a root-scope grant (the bypass closed)

The `full_chain_verify:true` watch and the `FULL` (full-store) target stream the entire signed chain
verbatim, with no edge filtering (the untrusted-edge mode, the client verifies and filters locally; watch
`recommendation.md` §7,§10). Because the edge does no filtering in this mode, authorization MUST
compensate:

- A `full_chain_verify` or `FULL` watch MUST require the principal to hold `READ` and `WATCH` over the
  entire scope, i.e., a grant covering the root `/**` for that scope. The full signed chain MUST NOT
  stream to a principal lacking a full-scope grant.
- **Why this is the actual bypass:** without this rule, a principal with `READ` on only
  `/team-payments/**` could set `full_chain_verify:true` and receive the whole store's signed chain, every
  other tenant's keys and values, under the guise of "verify locally." The flag bypasses filtering; the
  root-scope requirement is what stops it from also bypassing authorization. `full_chain_verify` is a
  root-scoped capability.
- A principal with a subtree grant that wants untrusted-edge verification of its subtree does not get the
  full chain; it gets the trusted-edge filtered watch over its subtree, or the signed skip-evidence path
  (ADR-0038, the named untrusted-edge-filtered extension). It never gets the firehose of other tenants'
  data.

### 6.4 W-AUTHZ-4 -- enforcement point, error, and the mandatory negative test

- **Enforcement point:** the edge fan-out subscribe path (where the watch is created), using the same
  replicated policy as the control-plane read path (§1.2, §7). The check is over the logical target `T`
  (the prefix), evaluated once at subscription, not per-shard. The subtree scatters physically, but authz
  is a logical operation over the prefix (consistent with INV-PATH, [`path-model.md`](path-model.md) §2.2).
  The edge is inside the trust boundary the watch design documents.
- **Error:** an unauthorized subscription is rejected with a terminal `WATCH_CANCELED`/close carrying a
  `403`-equivalent `ErrorCode` (authorization), distinct from a `401`-equivalent (unauthenticated), the
  same 401-vs-403 taxonomy the built `AdminApiHandler` uses (§8, RFC §7). No snapshot, no event, no
  bookmark is emitted before the reject.
- **Mandatory negative test (normative):** the implementation MUST have a regression test proving that (a)
  a watch whose target exceeds the grant, and (b) a `full_chain_verify`/`FULL` watch by a non-root-scope
  principal, are each rejected before any `SNAPSHOT_*`/`WATCH_EVENT`/`WATCH_PROGRESS` frame is sent. The
  test asserts zero data frames precede the terminal reject. This is the test the watch research did not
  require and is the proof the bypass is closed.

### 6.5 Consistency with the read path (a watch cannot out-read a read)

Because the watch authz (§6.1) uses the same capabilities, same policy, and same evaluation rule (§4) as
the single-key read path, the following holds and MUST be preserved as an invariant:

> **INV-WATCH-READ.** For every key `k ∈ T`, if a `READ k` would be denied to principal `p`, then a watch
> on `T` that would deliver `k`'s changes MUST be denied to `p`. A watch never delivers a change for a key
> the principal could not read.

W-AUTHZ-2 (reject over-broad, no silent filter) is what makes this checkable at subscription: rather than
evaluating per-key at delivery (expensive, and the edge may not re-check per event), the whole target is
floored by the grant once, so every key the watch can ever deliver is provably readable.

---

## 7. Enforcement points -- and their consistency

| Capability | Enforcement point (built or new) | Engine |
|---|---|---|
| READ | control-plane read path -- `AdminApiHandler.handleGet → checkAuth(READ)` (built) | `aclService` → policy eval (§4) |
| WRITE | control-plane write path -- `handlePut`/`handleDelete → checkAuth(WRITE)` (built) | same |
| LIST | control-plane list path -- `checkAuth(LIST)` on the prefix (new) | same |
| WATCH | edge fan-out subscribe path -- `checkAuth(READ ∧ WATCH)` on `T` at subscription (new, §6) | same policy, replicated to the edge (§1.2) |

**The consistency requirement (normative):** all four enforcement points MUST use the same policy and the
same evaluation rule (§4). Concretely:

- Policy is policy-as-config, replicated by Raft (§1.2), so the edge and the control plane evaluate the
  same bytes. There is no second, drifting ACL store at the edge.
- The watch check (§6) is the read check applied to a target instead of a single key. By construction
  (W-AUTHZ-2 plus INV-WATCH-READ), a watch authorizes a superset operation with the same or stricter
  result as reading each key, it can never be more permissive than `READ`. A watch cannot bypass a read
  ACL, which is the property §5/§6 exist to guarantee.

---

## 8. Error taxonomy (consistent with the built API; RFC §7 normative)

Matching the built `AdminApiHandler` (`AdminApiHandler.java:361-389`), authorization outcomes are:

- **401 Unauthenticated** -- missing/blank/malformed/invalid credential. Authentication, not
  authorization. (`WWW-Authenticate: Bearer`.) Never echoes the token.
- **403 Forbidden** -- authenticated principal, but the policy does not grant the capability (including a
  matching `DENY`, and an over-broad or non-root-scope watch, §6).
- For the streaming watch surface, the equivalent is a terminal `WATCH_CANCELED`/close with a `403`-class
  `ErrorCode` (§6.4) emitted before any data frame.

Audit: authorization failures (401/403, and watch rejects) are security-relevant and MUST be audited (as
the built handler audits denied GET/PUT/DELETE); successful reads are not audited (auditing every read is
a DoS concern, the built rationale, `AdminApiHandler.java:195-196`).

---

## 9. Type/interface sketch (design artifact -- see `sketch/`)

A small compile-checked sketch of the load-bearing types (`Capability`, `Path` normalization, a
`PolicyRule`/`AccessDecision` evaluation surface, and the watch-authz entry point) accompanies this design
under [`sketch/`](sketch/), to make the model concrete and catch type-level mistakes. It is a design
artifact, not wiring, at the time this document was written.

---

## 10. Open questions for the operator

1. **Model** (§1): confirm roles to policies to principals (recommended) vs. keep per-principal grants.
2. **Capability set** (§2): confirm `{READ, LIST, WRITE, WATCH, ADMIN}` plus `DENY`, with LIST distinct
   from READ and WATCH separate-but-gated-by-READ.
3. **Composition** (§4): confirm union-of-ancestors plus absolute deny-precedence, accepting it supersedes
   the built longest-match-only (a flagged, test-visible ACL change, §4.2).
4. **Watch-authz** (§6): confirm the contract -- authorized at subscription as a streaming read; over-broad
   targets rejected (not filtered); `full_chain_verify`/`FULL` requires root-scope; mandatory negative
   test.
5. **WRITE granularity** (§2.2): confirm coarse WRITE for now, with a create/update/delete split deferred.
6. **Policy storage** (§1.2): confirm policy-as-config under `/_acl/`,`/_system/`, replicated by Raft,
   self-protected by `ADMIN`.

These, with [`path-model.md`](path-model.md), are made normative for all drivers in the RFC section
[`../../rfc/driver-protocol/01-paths-and-access.md`](../../../rfc/driver-protocol/01-paths-and-access.md).

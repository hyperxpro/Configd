# Wiring Increment 5 — O-6 Seam 2a: Policy-as-Config Load/Reload (atomic-swap, byte-identical)

**Status:** BUILT. Branch `wiring-inc5-o6-seam2a-policy-config` off main `663a39f` (O-6 Seam 1).
**Scope:** make authorization policy (roles + principal→role bindings) loadable from config under the
reserved `_acl/` key subtree — **without changing any authorization decision**. This is the *safe,
byte-identical first cut* of policy-as-config; the enforcement-carrying increment (the ADMIN gate,
reserved-namespace write protection, self-protection) is **2b** (see Handoff).

---

## 1. What it does

Seam 1 (`663a39f`) made `AclService` role-aware over **static, in-memory** maps populated imperatively
(`defineRole`/`assignRole`) — dormant in production. Seam 2a adds a **config source** for that same
role/policy model: role definitions and bindings authored as ordinary config keys under `_acl/`, loaded
into a new **config-policy snapshot** that `AclService` unions in **additively**.

In production there are **no `_acl/` keys**, so the config-policy snapshot is **empty** and contributes
nothing — every authorization decision is **byte-identical** to `663a39f`. The only change to the policy
*source* is the new config path; the policy *enforcement* (`AdminApiHandler.checkAuth → isAllowed`) is
untouched.

New/changed surface (7 files, +559/−1):
- `io.configd.api.ConfigPolicy` (new) — immutable `(roles, bindings)` snapshot; `EMPTY` default.
- `io.configd.api.PolicySerializer` (new) — strict bytes↔`ConfigPolicy` text codec.
- `io.configd.api.PolicyParseException` (new) — fail-closed parse/validation signal (`extends
  IllegalArgumentException`).
- `io.configd.api.AclService` — `volatile ConfigPolicy` + `publishConfigPolicy` + a third additive source
  in `isAllowed` (read-once); **the static imperative layer + `accumulateOwnGrants` are untouched**.
- `io.configd.store.ConfigStateMachine` — additive `addSnapshotListener` (empty-default; fired after a
  successful `restoreSnapshot`).
- `io.configd.server.AclConfigPolicyLoader` (new) — idempotent whole-subtree rebuild; gated apply
  listener; snapshot-install listener; fail-closed-to-last-good; reserved-name validation.
- `io.configd.server.ConfigdServer` — root asserts `Set.of()` (N1); wires the loader + both listeners +
  boot seed inside `if (config.authEnabled())`, before the tick loop. **`grant("","root",allOf)` at the
  former `:726` is literally unchanged.**

---

## 2. Decisions

### DL-O6-04 — Serializer format: strict line-oriented text, multi-key under `_acl/`
There is no JSON library in `src/main` and the codebase idiom is hand-rolled codecs; policy is
operator-authored and operator-inspected. A small **line-oriented text** format is more operable than
binary (diffable, hand-writable) and avoids adding a JSON parser (a parsing/attack surface a security path
would have to clear). No new dependency.

Key layout: `_acl/roles/<roleName>` (one `<effect> <caps> <prefix>` per line) and
`_acl/bindings/<principal>` (one role name per line). Grammar: UTF-8 split on `\n`; one trailing `\r`
stripped; blank lines and `#`-comment lines ignored; `effect ∈ {allow,deny}`; `caps` = comma-separated
`Permission` names (no spaces); `prefix` = verbatim remainder (may be empty ⇒ matches all; may contain
spaces). Each line → one `PolicyRule`. **Literal-prefix matching only** (no glob — DL-O3-02-deferred);
**scope stays out of the rule** (DL-O6-02).

Known minor limitation: a role name beginning with `#` cannot be referenced on a binding line (it collides
with comment syntax). Such names are pathological; documented, not fixed in 2a.

### DL-O6-05 — Config-policy behind ONE volatile snapshot (the atomic swap; the torn-read fix)
A naive reload mutating `AclService`'s three live collections in place would be observable mid-reload (a
torn read). Instead the config-policy is a **separate, deeply-immutable `ConfigPolicy`** published by a
**single volatile reference swap** (`publishConfigPolicy`), mirroring `VersionedConfigStore`'s one-volatile
discipline. `isAllowed` reads the reference **exactly once**, so a concurrent reload is observed
entirely-old or entirely-new — never a mix. The static imperative layer is a **separate** sub-layer and is
untouched; `isAllowed` unions {own grants} ∪ {static roles} ∪ {config snapshot} into the same
`(allow, deny)` accumulators, deny subtracted **once** (absolute precedence holds **across** layers), then
the effective-`WATCH` = `WATCH` ∧ `READ` floor. The config block is guarded by `!cp.roles().isEmpty() ||
!cp.bindings().isEmpty()`, so the `EMPTY` snapshot short-circuits it → byte-identical.

The config role sub-layer and the imperative role sub-layer are **independent additive sub-layers**: a
name is resolved against the source it was bound through (config binding → config roles; static binding →
static roles), while an **authn-asserted** name is resolved against **both**. The wired server uses exactly
one source (config), so the asymmetry never triggers in practice; documented for completeness.

### DL-O6-06 — Fail-closed-to-last-good on malformed/partial/reserved policy
If the `_acl/` bytes don't parse to a valid policy (or collide with a reserved name), the load is
**rejected**: `SEVERE` log + a failure metric (`configd.acl.policy.load.failed`), and the **current
snapshot is kept** (no swap). It **never deny-alls** (lockout) and **never allow-alls** (open). A
**well-formed-but-incomplete** policy is *not* a failure: a binding naming a not-yet-loaded role parses
fine and is inert until that role's key appears — this is what lets the idempotent whole-subtree rebuild
converge across out-of-order multi-key writes and the snapshot/WAL-suffix split.

### DL-O6-07 — "admin" footgun neutralization: root proven UN-CARVEABLE (N1 + N2 + N3)
Root's break-glass authority is its static `acls` grant; a config role can carve it **only** if root is a
*subject* of that role — via (i) an asserted role name, or (ii) a config binding to principal `root`. The
charter's two candidate fixes each close only vector (i); a binding (ii) survives both. So 2a closes
**both**:
- **N1** — `ConfigdServer` root asserts `Set.of()` (was `{"admin"}`). Root depends only on its principal
  grant (the honest statement of DL-O6-01). Byte-identical: `"admin"` was never defined, so `{"admin"}`
  and `Set.of()` always decided identically. *Closes (i).*
- **N2** — the loader reserves the **root principal**: a config binding any role to a reserved principal is
  rejected (fail-closed-to-last-good). *Closes (ii).*
- **N3** — the loader reserves the role **name `admin`**: a config role so named is rejected. Not
  load-bearing for the proof (root no longer asserts it), but prevents name-squatting before 2b gives
  `admin` reserved-ADMIN semantics — a real upgrade footgun if deferred.

Reserved names are supplied by `ConfigdServer` (which owns the `root`/`admin` literals); `AclService` and
the serializer stay generic. Proven (and mutation-relevant): a config that binds `root → deny-all-role` is
rejected and root remains fully authorized for every capability.

### DL-O6-08 — Snapshot-install rebuild hook (close the follower-catch-up gap)
`restoreSnapshot` wholesale-replaces the store with **no** per-mutation apply notification, so a node that
catches up via InstallSnapshot would miss `_acl/` keys until the next `_acl/`-touching apply. `ConfigState
Machine` gains an **additive, empty-default** snapshot-install listener (fired after a *successful*
restore); the loader subscribes and rebuilds. Boot-snapshot, WAL-replay, live-apply, and runtime
InstallSnapshot now all converge through the same idempotent rebuild. Empty listener list ⇒ byte-identical
to the prior snapshot/apply behavior.

### Listener placement, gating, boot seed (operational)
The loader registers on the primary group's `ConfigStateMachine` (same pattern as the watch listener),
**before the tick loop**. `onConfigChange` first does a cheap O(delta) scan and returns unless an `_acl/`
key was touched; only then does it run the O(N) `getPrefix` rebuild — so in production (no `_acl/` writes)
the apply loop carries **zero** added cost. A boot **seed** `rebuild()` catches a snapshot-restored `_acl/`
prefix. The loader is wired **only when auth is enabled** (`aclService != null`); the no-auth path is
unchanged (gate-open). The reload runs on the apply/owner thread and is bounded + non-blocking (gated; the
scan is bounded by store size and the `_acl/` policy is small).

---

## 3. Proof (local; CI is the gate for merge)

- **Byte-identical** — the existing `AclServiceTest$ProductionByteIdentity`, the full
  `AclServiceByteIdentityDifferentialTest` (independent oracle, 4 lenses) **+ a new 5th lens** asserting an
  EMPTY / non-contributing config-policy never perturbs a decision, and the full `AclServiceRedTeamTest` /
  `AclServiceRoleTest` / `AclServiceRoleRedTeamTest` all pass **unchanged**. The server HTTP-contract suite
  (`JdkAdminApiServerContractTest`, the `checkAuth → isAllowed` enforcement path) passes unchanged.
- **Atomic swap / no torn read** — `AclServiceConfigPolicyTest#concurrentReloadIsNeverTorn`: a writer
  hammers 100k structurally-different snapshots while a reader spins `isAllowed`; no exception, both
  decisions observed.
- **Fail-closed-to-last-good** — `AclConfigPolicyLoaderTest`: a malformed reload keeps the last-good policy
  (alice still READ, bob still denied) + increments the failure metric; never deny-all/allow-all.
- **Root un-carveable** — `AclConfigPolicyLoaderTest#rootIsUncarveableByAnyConfigRole` + the AclService-
  level mechanism/immunity tests.
- **Snapshot hook** — `ConfigStateMachineSnapshotListenerTest`: fires on success, not on failed restore.
- Local module test counts: `configd-control-plane-api` **172/0**, `configd-config-store` **259/0**,
  `configd-server` (loader + contract, targeted) **45/0**.

---

## 4. Scope fence (explicitly NOT in 2a)

No change to `AdminApiHandler` / the `:452` enforcement call. No ADMIN gate on `_acl/` writes, no
reserved-**namespace** write enforcement, no last-admin guard, no validate-at-write-time, no
`removeRole`/`unassignRole`, no `Role.rules()` precompute. No endpoint/HTTP-surface change. No glob /
segment matching (DL-O3-02). No scope-in-rule (DL-O6-02). No watches / auth-SPI work. Authorization
**decisions** stay byte-identical; only the policy **source** gains a config path.

---

## 5. Handoff — 2b (the enforcement / self-protection increment, carries the lockout/window risk)

- **ADMIN gate + reserved-namespace write protection** on `_acl/` (and `_system/`): only ADMIN may write
  the policy subtree (today any WRITE-holder could; in production only root can).
- **Self-protection / last-admin guard / validate-at-write-time:** reject a policy mutation that would lock
  out the last admin or the writer; validate at write rather than only at load.
- **`admin` reserved-role semantics:** give the now-reserved name its ADMIN meaning.
- **Deprovisioning:** `removeRole` / `unassignRole` / `undefineRole`.
- **`auth-disabled` `_acl/` decision:** behavior of the policy path when auth is off.
- **`Role.rules()` precompute:** Seam 1 re-flattens policies→rules per call; now a hot path under config.
- **Multi-shard `_acl/` aggregation (N>1):** at N=1 (production) the primary store holds all `_acl/` keys
  and the single `aclService` is complete + byte-identical; at N>1 the keys scatter and a single-store load
  is incomplete — a pre-existing single-`aclService` limitation, not introduced here, to revisit with N>1.

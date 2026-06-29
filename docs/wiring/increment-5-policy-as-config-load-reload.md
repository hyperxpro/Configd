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
- `io.configd.api.AclService` — the config policy + the store version it was derived from in one immutable
  holder behind an `AtomicReference`; `publishConfigPolicy(ConfigPolicy)` (unconditional) +
  `publishConfigPolicy(long, ConfigPolicy)` (**monotonic** — ignores a stale/older-version publish); a third
  additive source in `isAllowed` (read-once); **the static imperative layer + `accumulateOwnGrants` are
  untouched**.
- `io.configd.store.VersionedConfigStore` — additive `getPrefixVersioned` (the prefix scan + the store
  version, from one consistent snapshot read) so the loader can publish version-ordered.
- `io.configd.store.ConfigStateMachine` — additive `addSnapshotListener` (empty-default; fired after a
  successful `restoreSnapshot`, OUTSIDE the success/fail accounting, each listener isolated).
- `io.configd.server.AclConfigPolicyLoader` (new) — idempotent whole-subtree rebuild (versioned, monotonic
  publish); gated apply listener; snapshot-install listener; fail-closed-to-last-good; reserved-name
  validation.
- `io.configd.server.ConfigdServer` — root asserts `Set.of()` (N1); `ROOT_PRINCIPAL`/`ADMIN_ROLE` constants
  source the identity + the reserved-name guard from one place; wires the loader + both listeners + boot
  seed inside `if (config.authEnabled())`, before the tick loop. **`grant("",ROOT_PRINCIPAL,allOf)` (the
  former `:726`) is unchanged.**

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

Grammar notes (parser is the authority; the design brief was slightly stricter): a **binding** role name is
taken **verbatim** (no trim — consistent with the role-key suffix), and a whitespace-only binding line is
skipped (blank), so a typo'd/space-padded binding produces an *inert* binding (default-deny), not an error
— surfacing inert bindings is a 2b validate-at-write item. **Rule** lines are strict (no leading
indentation: a leading space fail-closes the whole load), while `#`-comment lines may be indented. A role
name beginning with `#` cannot be referenced on a binding line (collides with comment syntax). These are
pathological/forgiving-but-safe; documented, not changed in 2a.

### DL-O6-05 — Config-policy behind ONE versioned snapshot (atomic swap + MONOTONIC publish)
A naive reload mutating `AclService`'s three live collections in place would be observable mid-reload (a
torn read). Instead the config-policy plus **the store version it was derived from** live in one
deeply-immutable holder published via a **single `AtomicReference` swap**; `isAllowed` reads it **exactly
once**, so a concurrent reload is observed entirely-old or entirely-new — never a mix (the torn-READ fix).
The publish used by the loader is **version-ordered / monotonic** (`publishConfigPolicy(long, ConfigPolicy)`
ignores a publish whose store version is ≤ the currently-published version): an idempotent rebuild that
scanned an *older* store snapshot (e.g. a slow boot seed racing a concurrent apply-thread rebuild, the
red-team's confirmed stale-clobber) can never resurrect stale state over a newer policy (the out-of-order-
WRITE fix — store versions advance monotonically across applies and forward-only snapshot installs). The
loader pairs this with `VersionedConfigStore.getPrefixVersioned` (scan + version from ONE snapshot read).
The static imperative layer is a **separate** sub-layer and is untouched; `isAllowed` unions {own grants} ∪
{static roles} ∪ {config snapshot} into the same `(allow, deny)` accumulators, deny subtracted **once**
(absolute precedence holds **across** layers), then the effective-`WATCH` = `WATCH` ∧ `READ` floor.
**Trust contract:** an authn-asserted role name resolves against config roles (config defines roles, authn
asserts membership), so `AuthResult.roles()` MUST come from a *trusted* authenticator (the `TokenValidator`
SPI) — dormant in 2a (the sole authenticator asserts `Set.of()` for root). The config block is guarded by
`!cp.roles().isEmpty() ||
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
key was touched; only then does it run the rebuild — whose scan is **O(total store keys)** (a full snapshot
scan; the HAMT has no ordered prefix iteration), **not** O(policy size), on the owner thread. So in
production (no `_acl/` writes) the gate short-circuits and the apply loop carries **zero** added cost;
`_acl/` writes are rare admin ops. A boot **seed** `rebuild()` catches a snapshot-restored `_acl/` prefix.
The loader is wired **only when auth is enabled** (`aclService != null`); the no-auth path is unchanged
(gate-open). A secondary `_acl/` index / off-owner-thread rebuild for very large stores is a 2b item.

---

## 3. Proof (local; CI is the gate for merge)

- **Byte-identical** — the existing `AclServiceTest$ProductionByteIdentity`, the full
  `AclServiceByteIdentityDifferentialTest` (independent oracle, 4 lenses) **+ a new 5th lens** asserting an
  EMPTY / non-contributing config-policy never perturbs a decision, and the full `AclServiceRedTeamTest` /
  `AclServiceRoleTest` / `AclServiceRoleRedTeamTest` all pass **unchanged**. The server HTTP-contract suite
  (`JdkAdminApiServerContractTest`, the `checkAuth → isAllowed` enforcement path) passes unchanged.
- **Atomic swap / no torn read (strong)** — `AclServiceConfigPolicyTest#concurrentReloadIsNeverTorn`: two
  snapshots that BOTH grant `alice READ` via *different* role names, so a torn / read-twice read yields the
  *impossible* value `false`; a writer hammers 200k swaps while a reader asserts the decision is **always
  true** (deterministic — not a flaky "observe both"), and never throws.
- **Monotonic publish / no stale clobber** — `AclServiceConfigPolicyTest#staleVersionedPublishDoesNotClobber
  NewerPolicy`: an older-or-equal-version publish is ignored; a strictly-newer one supersedes (the redteam
  stale-clobber, closed).
- **Fail-closed-to-last-good** — `AclConfigPolicyLoaderTest`: a malformed reload keeps the last-good policy
  (alice still READ, bob still denied) + increments the failure metric; never deny-all/allow-all. The
  intentional whole-load-reject (one poison `_acl/` key freezes updates) is characterized by
  `#poisonKeyFreezesSubsequentUpdates`.
- **Root un-carveable** — `AclConfigPolicyLoaderTest#rootIsUncarveableByAnyConfigRole` (N2 mutation-checked
  with teeth) + the AclService immunity test, with `#rootAssertingAdminRoleWouldBeCarvedByAConfigAdminRole_
  whyN1` as the positive witness that the asserted-role vector N1 closes is real.
- **Snapshot hook** — `ConfigStateMachineSnapshotListenerTest`: fires on success, not on failed restore,
  isolated per-listener. `VersionedConfigStoreTest$PrefixScan` covers `getPrefixVersioned`.
- Local test counts: `configd-control-plane-api` **243/0** full module (of which **174** are the ACL/policy
  classes named above), `configd-config-store` **264/0** full module, `configd-server` **46/0** for the
  loader + HTTP-contract classes (targeted; the full server suite is validated by CI).

**Review:** five-lane Opus team — security-reviewer (LEAD), divergence-analyst (BYTE-IDENTICAL-CONFIRMED),
redteam-auditor (NO-BYPASS-FOUND), reliability-engineer, code-reviewer — **all APPROVE, 0 must-fix**. The
adopted fixes (monotonic publish, snapshot-notify placement+isolation, root/admin constants, the strong
torn-read test, the poison + N1-witness + getPrefixVersioned tests, and the doc corrections) close every
SHOULD-FIX that three lanes independently raised; the remaining residuals are latent (empty-config-dormant)
and tracked as 2b items below. A fresh-context Verifier signs off the final diff.

---

## 4. Scope fence (explicitly NOT in 2a)

No change to `AdminApiHandler` / the `:452` enforcement call. No ADMIN gate on `_acl/` writes, no
reserved-**namespace** write enforcement, no last-admin guard, no validate-at-write-time, no
`removeRole`/`unassignRole`, no `Role.rules()` precompute. No endpoint/HTTP-surface change. No glob /
segment matching (DL-O3-02). No scope-in-rule (DL-O6-02). No watches / auth-SPI work. Authorization
**decisions** stay byte-identical; only the policy **source** gains a config path.

---

## 5. Handoff — 2b (the enforcement / self-protection increment, carries the lockout/window risk)

- **ADMIN gate + reserved-namespace write protection** on `_acl/` (and `_system/`) — **BLOCKER (security
  LEAD).** Only ADMIN may write the policy subtree. In 2a `_acl/` has NO write-gate (by design): in
  production only root can write it, but the instant a config role grants a non-root principal WRITE over a
  prefix ≤ `_acl/`, that principal could self-escalate (write its own `_acl/roles/*` + `_acl/bindings/self`)
  or freeze policy reload by parking one malformed `_acl/` key (whole-load reject). 2b MUST land this gate
  before any non-root WRITE can reach `_acl/`.
- **Self-protection / last-admin guard / validate-at-write-time:** reject a policy mutation that would lock
  out the last admin or the writer; validate at write rather than only at load (closes the persisted-poison-
  key freeze — though an already-committed / snapshot-delivered key still needs deletion).
- **`admin` reserved-role semantics:** give the now-reserved name its ADMIN meaning. Also: reserved-name
  matching is **exact/case-sensitive** in 2a (`Admin`/`Root`/`root ` are inert, not reserved); decide
  whether to canonicalize (trim/case-fold) when reserved-ADMIN goes live.
- **Deprovisioning:** `removeRole` / `unassignRole` / `undefineRole`.
- **`auth-disabled` `_acl/` decision:** behavior of the policy path when auth is off (today: no loader).
- **`Role.rules()` precompute:** Seam 1 re-flattens policies→rules per call; now a hot path under config.
- **Observability (reliability):** a **freshness/divergence gauge** ("seconds since last successful load" /
  "store `_acl/` newer than loaded") + an alert on `rate(configd.acl.policy.load.failed) > 0` so a persistent
  fail-closed-to-last-good divergence is on-call-visible; optionally split the `reload` counter by source
  (boot-seed / apply / snapshot-install).
- **Owner-thread hardening:** add an `assertOwnerThread` tripwire to `restoreSnapshot` (today only `apply`
  has one); a secondary `_acl/` index or off-owner-thread rebuild for very large stores (the rebuild scan is
  O(total store keys)). The monotonic versioned publish already makes a *future* off-owner-thread publisher
  safe, but keep the boot-seed-on-owner-thread option in mind if the threading model changes.
- **Multi-shard `_acl/` aggregation (N>1):** at N=1 (production) the primary store holds all `_acl/` keys
  and the single `aclService` is complete + byte-identical; at N>1 the keys scatter and a single-store load
  is incomplete — a pre-existing single-`aclService` limitation, not introduced here, to revisit with N>1.

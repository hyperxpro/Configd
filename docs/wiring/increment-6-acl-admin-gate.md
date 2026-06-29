# Wiring Increment 6 — O-6 Seam 2b: `/_acl/` Reserved-Prefix ADMIN Gate + Write-Time Validation (enforcement)

**Status:** BUILT. Branch `worktree-wiring-6-acl-admin-gate` off main `f7b061e` (O-6 Seam 2a).
**Scope:** the FIRST enforcement of `ADMIN` anywhere. A key under a reserved namespace (`_acl/` or
`_system/`) now requires `ADMIN` for **every** method (GET/PUT/DELETE) — closing both policy **mutation**
and policy **disclosure** — fail-closed, with write-time policy validation and an auth-disabled refusal.
**Byte-identical in production** (only `root` touches `_acl/`, and `root` holds `ADMIN` via its `allOf`
grant, so no production decision changes). After 2b the ACL/namespace foundation (scope, paths,
capabilities, roles, policy-as-config, **enforcement**) is **COMPLETE**.

This closes the **BLOCKER** the 2a handoff (§5) named: in 2a `_acl/` had no write-gate, so the instant a
config role granted a non-root principal WRITE over a prefix ≤ `_acl/`, that principal could self-escalate
(`_acl/roles/*` + `_acl/bindings/self`) or freeze policy reload by parking one malformed `_acl/` key.

---

## 1. What it does

The gate lives entirely in the transport-agnostic decision core `AdminApiHandler` (both the JDK and Netty
adapters delegate to it), so it is decided once and re-proven by the identical contract on each transport.

New/changed surface (3 main + 2 test + 3 ops files):
- `io.configd.server.AdminApiHandler` — `checkAuth` gains the reserved-prefix ADMIN gate; `handlePut` gains
  the write-time `_acl/` validation call; a private `isReserved(key)` + `SYSTEM_PREFIX` constant.
- `io.configd.server.AclConfigPolicyLoader` — `validateReserved` factored to a shared `static` form; a new
  `static validateAclWrite(key, value)` (the write-time validator = the IDENTICAL parse + reserved-name
  check the reload path runs); `RESERVED_ROLE_ADMIN`/`RESERVED_PRINCIPAL_ROOT`/`RESERVED_ROLES`/
  `RESERVED_PRINCIPALS` constants as the single source of truth.
- `io.configd.server.ConfigdServer` — `ROOT_PRINCIPAL` aliased to `AclConfigPolicyLoader.RESERVED_PRINCIPAL_ROOT`
  and the loader constructed with the shared `RESERVED_ROLES`/`RESERVED_PRINCIPALS` (byte-identical by
  value; removes the now-redundant local `ADMIN_ROLE`).
- Tests: `ReservedPrefixAdminGateTest` (handler-level, full control); `AbstractAdminApiServerContract`
  Section 9 (the gate over real HTTP on all three transports + percent-decoding evasion vectors).
- Ops: a `ConfigdAclPolicyLoadFailed` alert + a promtool fires/quiet test + `ops/runbooks/acl-policy-load.md`.

---

## 2. Decisions

### DL-O6-09 — Reserved prefix ⇒ ADMIN for ALL methods (mutation AND disclosure), fail-closed
A reserved key requires `ADMIN` for GET as well as PUT/DELETE. A non-ADMIN **read** of `_acl/` would leak
the access structure (which principals/roles exist, what they can reach) — a reconnaissance surface — so
the gate closes disclosure too. This matches the `AclService.Permission.ADMIN` javadoc ("reaches the
reserved subtrees" = all access), is the simplest rule (one predicate, one required permission), and is
byte-identical in production (only `root`, who holds `ADMIN`, touches `_acl/`). Implemented as a permission
override in `checkAuth`: `required = isReserved(key) ? ADMIN : permission`, where `permission` is the
caller's existing GET→READ / PUT|DELETE→WRITE mapping.

**Fail-closed corner.** `AdminApiHandler` treats `aclService` and `authInterceptor` as *independently*
nullable. A reserved key whose `ADMIN` cannot be evaluated — `aclService == null`, or (defensively, for the
sealed `AuthResult`) a non-`Authenticated` result — is **denied (403)**, never allowed to fall through to
`AuthCheck.ok`. The ONLY paths a reserved key reaches `ok` are (a) auth disabled + a READ (see DL-O6-12) or
(b) `aclService != null` and `isAllowed(…, ADMIN)` returned true. The gate is evaluated as the **first**
thing each handler does — **before** `keyValidationReason`, `parseScope`, the body drain (`req.body()`),
the replay check, and any store access — so it is live from the first request with **no window**, and an
unauthorized reserved request never drains its body.

### DL-O6-10 — The predicate-alignment invariant (the security proof)
The gate predicate (`AdminApiHandler.isReserved` → `key.startsWith(PolicySerializer.ACL_PREFIX)`), the
loader predicate (`AclConfigPolicyLoader` → `startsWith(ACL_PREFIX)`), and the store key
(`VersionedConfigStore` — **verbatim**, zero normalization) all operate on the **SAME** post-strip key with
the **SAME** constant. Therefore **"evades the gate" and "is real policy" are mutually exclusive**: a key
that slips `isReserved` (e.g. leading-slash `/_acl/…`, upper-case `_ACL/…`) is *also* invisible to the
loader's `startsWith("_acl/")` and is a *distinct* store key — so it can never corrupt real policy. This is
safe **by construction**, not by vigilance. The load-bearing constraint: **2b introduces NO key
normalization** (percent-decode beyond the adapter's existing `new URI(...).getPath()`, `..` collapsing,
case-folding) in the write path without applying the identical transform to the gate predicate. The path is
already percent-decoded-but-not-normalized (the C6/RR-020 property the strong-read vectors rely on); 2b
keeps it that way, aligned across gate + loader + store. The `_acl/` constant is reused from
`PolicySerializer.ACL_PREFIX` — never a hand-typed literal — so the three sites cannot drift.

### DL-O6-11 — Write-time validation via the IDENTICAL shared validator (never a second validator)
`handlePut` validates an `_acl/` write *before* proposing it, using the **exact same**
`PolicySerializer.parse` + reserved-name check the reload path runs, factored into the shared
`static AclConfigPolicyLoader.validateAclWrite(key, value)` over the shared `RESERVED_ROLES` /
`RESERVED_PRINCIPALS`. Because write-time and reload-time share one validator and one set of reserved
names, **a key that passes the write can never freeze a later whole-subtree reload for its own
contribution** (the persisted-poison-key freeze of DL-O6-06, closed at the source for live writes). A
malformed shape / role-line / binding grammar, or a reserved name (`_acl/roles/admin`,
`_acl/bindings/root`), yields **400 pre-commit** — the rejection is **before** `writeService.put`, so the
store is unchanged. A **well-formed-but-incomplete** policy (a binding to a not-yet-defined role) parses
successfully and is intentionally **NOT** rejected (DL-O6-06): single-key validation is exactly the right
granularity — rejecting cross-key-incomplete policy would break the idempotent multi-key convergence.
Locality favored co-locating the *call* in `handlePut` (it shares the `ACL_PREFIX` constant and the audit
context) while the shared validation *code* lives next to the reload path in `AclConfigPolicyLoader`; the
lower-layer `ConfigWriteService.WriteValidator` seam was not used (it does not know `_acl/` semantics and a
key delivered via snapshot/replay would bypass it anyway — that residual stays covered by reload's
fail-closed-to-last-good + the `load.failed` alert).

### DL-O6-12 — Auth-disabled reserved-write refusal (close the bring-up poison footgun)
When auth is off (`authInterceptor == null`, the loudly-warned non-production mode), `checkAuth` is
otherwise open — but a **WRITE** to a reserved prefix is **refused** (403, store unchanged). An `_acl/` key
written during an auth-off bring-up would **persist** and be picked up by the seed rebuild on the **first
secured boot**, potentially fail-closed-freezing policy. Policy is meaningless without auth, so refusing
costs nothing real and closes the footgun. **Reads** and **ordinary writes** stay open under auth-off
(consistent with auth-off being otherwise fully open — we closed only the one footgun, not all access).
`_system/` writes are refused on the same branch.

### DL-O6-13 — The last-admin guard is deliberately NOT built
The 2a handoff floated a "reject a mutation that locks out the last admin" guard. **It is not built**, by
design: `root`'s static in-memory `grant("", root, allOf)` is the **un-carveable break-glass** (proven in
2a — N1+N2+N3: root asserts no roles, and the loader rejects binding any role to `root` or defining the
`admin` role), so a lockout is always recoverable by `root`. A *precise* last-admin guard is also not
computable from stored state (it would require enumerating every principal's effective ADMIN across all
layers at write time). So we rely on the documented break-glass (and the `ReservedPrefixAdminGateTest`
self-deny test proves it: an adversarial config policy denying a principal's own ADMIN does **not** lock
`root` out). Revisit only if the break-glass model itself changes.

### DL-O6-14 — Surface the `load.failed` counter (fail-closed-to-last-good is otherwise silent)
The fail-closed-to-last-good loader (DL-O6-06) keeps last-good on a rejected reload — invisible to clients,
yet it means an `_acl/` update did **not** take effect (and one persisted poison key freezes **all**
subsequent policy updates until removed). The `configd.acl.policy.load.failed` counter already exists
(eager-created ⇒ emits `_total 0`); 2b surfaces it as the `ConfigdAclPolicyLoadFailed` warn alert
(`increase(configd_acl_policy_load_failed_total[15m]) >= 1`, `for: 5m`) with a fires/quiet promtool test and
`ops/runbooks/acl-policy-load.md`. (The fuller freshness/divergence gauge and the source-split `reload`
counter from the 2a handoff are 2c.)

---

## 3. Proof (local; CI is the gate for merge)

- **The 8 negative tests (all green).** `ReservedPrefixAdminGateTest` (handler-level, 10 cases — the 8
  scenarios + the auth-on/no-ACL fail-closed corner) + `AbstractAdminApiServerContract` Section 9
  (5 cases × 3 transports) cover: (1) no-window escalation
  (WRITE-not-ADMIN → PUT/DELETE `_acl/` → 403); (2) ADMIN allowed + root allowed (→ 200); (3) prefix-evasion
  — the percent-decoding vectors `%5Facl/`, `_acl%2F`, `_acl/../` are ADMIN-gated over real HTTP, while the
  distinct-key vectors `/_acl/…` and `_ACL/…` route to a verbatim distinct key (predicate-alignment); (4)
  non-ADMIN rejected for both PUT and DELETE (and GET — disclosure); (5) self-deny survivable via break-glass
  (root un-carveable after an adversarial self-deny policy); (6) bad policy rejected at write-time 400
  pre-commit (store unchanged via proposer-not-invoked) + incomplete-is-non-error; (7) auth-disabled
  reserved-write refused (ordinary writes + reserved reads unaffected); (8) no over-gating (`_acl`/`_aclx`/
  `_acladmin` NOT gated — the trailing slash is load-bearing) + ordinary-key byte-identity.
- **"Store unchanged" proxy.** A `CapturingProposer` records whether a write ever reached Raft; the store is
  strictly downstream of the proposer, so `calls == 0` after a rejected request is a sound **pre-commit**
  store-unchanged proof (stronger than inspecting an empty store).
- **Byte-identical / no regression.** The full `configd-server` suite (**387/0**) — including the incumbent
  `AbstractAdminApiServerContract` Sections 1–8 (× 3 transports), `ConfigdServerTest`, `AclConfigPolicyLoaderTest`
  (loader unaffected by the static factoring), and `ScopeAndPathValidationTest` — passes unchanged; the full
  `configd-control-plane-api` suite (**243/0**) confirms the shared `AclService`/`PolicySerializer`/`ConfigPolicy`
  types are untouched.
- **Alert.** `promtool check rules` → 15 rules; `promtool test rules` → SUCCESS (the new alert fires on a
  rejected reload and stays quiet otherwise). promtool 2.53.2 (the gate-6 pin).

**Review:** Opus Agent Team — security-reviewer (LEAD), redteam-auditor, divergence-analyst, code-reviewer
— plus a fresh-context Verifier. _(Verdicts recorded at merge; see the PR.)_

---

## 4. Scope fence (explicitly NOT in 2b — the 2c follow-ups)

- **Last-admin guard** — deliberately not built (DL-O6-13); break-glass relied on.
- **`admin` reserved-role ADMIN semantics** — the name is reserved (2a N3) but has no built-in ADMIN
  meaning yet; giving it one (and deciding canonicalization — `Admin`/`Root`/`root␠` are exact/inert today)
  is 2c.
- **Deprovisioning** — `removeRole` / `unassignRole` / `undefineRole`.
- **`Role.rules()` precompute** — Seam 1 re-flattens policies→rules per `isAllowed`; a hot-path optimization
  once config policy is in use (zero prod cost today).
- **Owner-thread hardening / secondary `_acl/` index** — the rebuild scan is O(total store keys) on the
  owner thread; an off-owner-thread rebuild for very large stores is deferred (the monotonic versioned
  publish already makes a future off-owner publisher safe).
- **Freshness/divergence gauge + source-split `reload` counter** — beyond the `load.failed` alert landed here.
- **Write-time "near-miss" guard (operability, divergence-analyst D-1)** — the gate deliberately does NOT
  normalize the key (the predicate-alignment invariant depends on it), so `_ACL/…`, `/_acl/…`, `_acl//…`,
  `_acl/../…` are accepted as ordinary, ungated, *dead-policy* keys: an operator who fat-fingers the prefix
  writes a silent no-op the loader ignores. Mature systems (ZooKeeper/Vault) normalize-or-reject. A 2c
  write-time near-miss guard could 400/warn ("did you mean `_acl/`?") **without** touching the (correct)
  gate predicate — defense-in-depth against silent dead policy, security-neutral.
- **`_system/` validation-when-lit (divergence-analyst D-2)** — `_system/` is ADMIN-gated but has no parsed
  format and no consumer, so it is intentionally NOT write-validated or loaded. When it gains a meaning it
  MUST replicate the `_acl/` pattern (write-validate + a fail-closed-to-last-good loader + a `load.failed`
  alert) or it inherits the poison-key-freeze class this increment just closed for `_acl/`.
- **Multi-shard `_acl/` aggregation (N>1)** — at N=1 (production) the primary store holds all `_acl/` keys and
  the single `aclService` is complete + byte-identical. At N>1 the keys scatter: the gate stays correct
  (ADMIN required for `_acl/` in *any* scope — fail-safe), but policy *loading* scans one store/loader, so an
  ADMIN-authorized `_acl/` write under a non-default scope could land on a shard the loader doesn't scan and
  be silently inert (a liveness item, not a security bypass; pre-existing single-`aclService` limitation, not
  introduced here). Revisit with the multi-shard (v2) roadmap.
- No glob / segment matching (DL-O3-02); no scope-in-rule (DL-O6-02); no watches / auth-SPI work.

---

## 5. Handoff — the ACL/namespace foundation is COMPLETE; next arc = watches

2b lands enforcement, so the O-1…O-6 ACL/namespace foundation — **scope** (Increment 1), **paths**
(Increment 1), **capabilities** (Increment 3: READ/LIST/WRITE/WATCH/ADMIN + the WATCH⊇READ floor), **roles**
(Increment 4: role-aware eval), **policy-as-config** (Increment 5: load/reload), and now **enforcement**
(Increment 6: the reserved-prefix ADMIN gate) — is **COMPLETE**. The 2c follow-ups above are refinements,
not gaps in the foundation.

**Next arc = watches** (the client-facing change-stream projection of the edge fan-out plane): RFC §2 + the
veneer + the gate flip. Per the watches research (PR #16): a watch is the client projection of the edge
fan-out; cursor-vector-native + edge-served + streaming; per-key order yes / cross-shard order no; N=1 is
v1-capable **iff** the driver protocol ships; multi-shard watch is v2. The capability vocabulary (WATCH, the
INV-WATCH-READ floor) and the watch-authz contract are already in place from Increments 3–6; the O-5
subscribe path must apply the WATCH floor over the **whole target** (per delivered key, or a whole-target
cover-check), **not** a single `isAllowed(p, subtreeRoot, WATCH)` call.

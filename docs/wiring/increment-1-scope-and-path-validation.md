# Wiring Increment 1 — Scope-through-the-API + Path Validation

**Status: implemented (2026-06-28). The first design→code wiring of the driver-protocol corpus.**
Touches the live request path (reads + writes). Branch `wiring-1-scope-pathvalidation` off `main` @ `401228b`.

This is the decision log, the RFC-§1 conformance note, and the handoff for the increment that wires
`scope` through the HTTP control-plane API and adds a key-validation gate at the edge, per
[`../rfc/driver-protocol/01-paths-and-access.md`](../rfc/driver-protocol/01-paths-and-access.md) (§2
scope, §3 path syntax) and the namespace design
[`../design/namespace-model/path-model.md`](../design/namespace-model/path-model.md) (§3.2, §6).

---

## 1. What this increment does (and what it deliberately does not)

**IN.**
1. Parse `scope` at the HTTP edge (a `?scope=` query parameter) → `ConfigScope`, defaulting to `GLOBAL`.
2. Replace the three hardcoded `ConfigScope.GLOBAL` literals — `AdminApiHandler` PUT (was `:296`),
   DELETE (was `:315`), and the `ConfigdServer` read-path pin (was `:802`) — with the parsed scope.
3. Widen the read API (`ConfigReadService` + the `ConfigReader`/`LeadershipConfirmer` SPI) to carry
   scope, so a GET routes by the **same `(scope, key)`** as the corresponding write (read-your-writes).
4. Add a **superset** key-validation gate at the edge (non-blank + ≤ 1024 bytes UTF-8), mapped to a
   `400` per the RFC error taxonomy (A7-1).

**OUT (later increments — unchanged here).** The O-4 ACL union+deny change; the O-6 role/policy model;
the O-3 capability expansion `{READ,LIST,WRITE,WATCH,ADMIN}`+`DENY`; watches; true REGIONAL/LOCAL
replication semantics (v2); any `shardFor`/storage/Raft-command change (scope stays **routing-input-only**).

---

## 2. The central compatibility finding — the path grammar conflict, surfaced (RFC §6 / task §3.1)

RFC §1 specifies a strict path grammar (A3): paths **MUST** be absolute (begin with `/`), segments
**MUST** be `seg-char` only (`[A-Za-z0-9._-]`), `//`/`.`/`..` are invalid, and the path **MUST** be
canonically normalized. **Applying that grammar as a reject-gate on the live HTTP admin surface would
reject keys that are valid today** — a silent regression. This conflict is **surfaced here, not silently
shipped** (hard rule #6).

### 2.1 What the system accepts today (measured, not assumed)

The deployed keyspace is **any non-blank UTF-8 string ≤ 1024 bytes**. The only validation in the live
write path is `ConfigWriteService.put` (`configd-control-plane-api/.../ConfigWriteService.java:241,248`):
non-blank + ≤ 1024-byte key + ≤ 1 MiB value. There is **no** charset, segment, absolute-path, or
normalization check anywhere below the edge. The key is extracted as
`path.substring("/v1/config/".length())` from the **percent-decoded** `URI.getPath()` and passed to the
store **verbatim** (`AdminApiHandler.java:183`).

### 2.2 Why the strict A3 grammar cannot gate this surface — proven by the existing security suite

The strict grammar would reject currently-valid keys, and the existing tests **depend on those keys not
being rejected**:

| Currently-valid key | A3 verdict if gated | Existing test that would break |
|---|---|---|
| `db.host`, `app/feature` | not absolute → reject | round-trip PUT/GET across the contract |
| `secure/../killswitch` | `..` segment → reject | `AbstractAdminApiServerContract.dotDotInsideStrongPrefixStaysStrong` asserts this key stays **strong (503)**, not 400 |
| `//secure/killswitch` → key `/secure/killswitch` | `//` empty segment → reject | `leadingDoubleSlashIsADifferentKeyNotAStrongLeak` asserts **404** (a distinct key) |
| `secure%2Fkillswitch` → `secure/killswitch` | embedded `/` in segment → reject | `encodedSlashInPrefixIsClassifiedStrongAndFailsClosed` asserts **503** |
| a 300-char single-token key | > 256 B/segment → reject (A3-5 SHOULD) | none today, but it is ≤ 1024 B and thus valid today |

Crucially, the **C6 / RR-020 strong-read boundary** (`AdminApiHandler` class javadoc) requires the key to
be used **un-normalized** — normalizing `secure/../killswitch` to `killswitch` would *break* the
fail-closed classification that `StrongReadFailClosedTest` pins. So the edge **must not rewrite the key**
either. RFC A3-4 itself says implementations MUST *reject* non-canonical input rather than rewrite it;
the legacy surface does neither — it passes the key through, which is the only compatibility-preserving
behavior.

### 2.3 Resolution (per design `path-model.md` §6.1)

The design already settled this: *"the path syntax, the scope axis, and the capability model are the
**driver-protocol contract** (RFC §3-6). New deployments adopt paths natively; **the legacy flat-key
surface remains a degenerate case.**"* RFC A8-1/A8-2 sanction it: a flat key like `db.host` is a
*degenerate single-segment path*, and *"a driver targeting the HTTP admin API MUST send/assume GLOBAL."*

Therefore:

- **The live HTTP edge enforces only the A3 rules that are a true superset of the current keyspace:**
  **non-blank** (A3-1: a path/key is non-empty) and **≤ 1024 bytes UTF-8** (A3-5: *"the deployed
  key-length limit"* — already enforced for PUT; this increment makes it uniform across GET/PUT/DELETE
  and maps it to the RFC `400`). Every currently-accepted key still validates (proven by a corpus test,
  §5); only genuinely-malformed input the system already rejected (blank, over-length) is rejected.
- **The strict A3 grammar (absolute + `seg-char` + canonical normalization + 64-segment / 256-byte
  bounds) is the contract for the typed binary / driver protocol surface**, which is greenfield (typed
  `scope`, absolute paths, no flat-key legacy) and **does not exist yet**. It is **not** built in this
  increment (it would be dead code with no caller); it is the named entry point for the binary-surface
  increment (§7).

> **DL-W1-01.** The edge key-validation gate is the **superset** {non-blank, ≤ 1024 B UTF-8}, not the
> strict RFC A3 grammar. The strict grammar is the binary/driver-surface contract, deferred with a
> documented reason (this section). Rationale: hard rule #2 (no currently-valid key may become invalid),
> the existing strong-read security suite, and design `path-model.md` §6.1.

---

## 3. Scope wire encoding (RFC §1 is silent for the HTTP surface → simplest, logged)

RFC A8-2 says the HTTP admin surface *assumes GLOBAL* and defers a typed `scope` to the binary protocol;
it specifies **no** HTTP encoding. Per task §5 ("if ambiguous, pick the simplest and log it"):

> **DL-W1-02.** Scope is carried on the HTTP surface as a **`?scope=` query parameter**, value parsed
> **case-insensitively** into `ConfigScope` (`GLOBAL`/`REGIONAL`/`LOCAL`). **Absent or blank ⇒ `GLOBAL`**
> (A2-3 default — byte-identical to today). An **unrecognized** value ⇒ **`400`** (fail-closed; never a
> silent coercion that could mis-route — closes the §6 "scope-confusion" red-team angle).

Why a query param (not a header or path segment): (a) `scope` **MUST NOT** be a path segment (A2-1);
(b) the edge already parses the query for `consistency=linearizable`, so a query param is the
least-surprising addition and is uniformly available as `req.uri().getQuery()` on **both** transports
(JDK `HttpApiServer` and `NettyHttpApiServer` build the `URI` identically); (c) a query param is separate
from `URI.getPath()`, so it does **not** touch the key or the C6/RR-020 strong-read classification.

> **DL-W1-03.** Both the scope parse and the key-validation gate run **after** authentication, mirroring
> the existing auth-first ordering (a malformed scope/key from an unauthenticated caller still gets
> `401`, not a pre-auth `400`). Existing valid-key traffic is unaffected by placement; this choice
> preserves the security ordering against a red-team "you moved validation ahead of auth" critique.

---

## 4. Read API widening — the seam (read-your-writes, minimal blast radius)

The read path was scope-blind: `ConfigReadService.staleRead(key)` / `linearizableRead(key)` →
`ConfigReader.get(key)` / `LeadershipConfirmer.confirmLeadership(key)`, with the shard resolved from a
**construction-time** `readScope = GLOBAL` captured in `ConfigdServer` (`:802`, `:810`). A per-request
scope cannot flow through a construction-time capture, so the SPI is widened to carry scope **per call**:

- `ConfigReader` gains `default ReadResult get(ConfigScope, String)` (and the `minVersion` overload),
  **delegating to the key-only method** — so every existing key-only `ConfigReader` (3 test fixtures)
  compiles and behaves **unchanged**. Only the production `shardedConfigReader` **overrides** the
  scope-aware method to resolve `shardFor(scope, key)` with the **call-time** scope.
- `LeadershipConfirmer` gains `default boolean confirmLeadership(ConfigScope, String)`, delegating to the
  key-only SAM — so every existing `key -> …` confirmer lambda (4 fixtures) is **unchanged**. The
  production confirmer becomes an anonymous class overriding the scope-aware method (call-time scope).
- `ConfigReadService` gains `linearizableRead(ConfigScope, String)` and `staleRead(ConfigScope, String)`;
  the key-only methods are retained, **delegating to the GLOBAL overload** — so every existing caller
  (the testkit, ConfigdServerTest) is unchanged.

> **DL-W1-04.** Scope-aware read methods are added as **defaults delegating to the key-only methods**
> (back-compat shim), with only production overriding them. This routes a GET by the same `(scope, key)`
> as its write while leaving all existing key-only readers/confirmers/callers byte-identical.
> `shardedConfigReader` keeps its 4-arg signature; the `readScope` argument is now the **A2-3 GLOBAL
> default for the legacy key-only `ConfigReader.get(String)` path** (still exercised by `ShardedRoutingTest`),
> while the new scope-aware overrides use the caller's scope.

---

## 5. N=1 byte-identity + read-your-writes-per-scope (the proof obligations)

- **N=1 byte-identity.** `StaticShardMap.shardFor` is `floorMod(hash, N)`; at `N=1` every scope resolves
  to **group 0** regardless of `scope.ordinal()`. Default scope is `GLOBAL`. So for all existing
  GLOBAL-only traffic the entire increment is a no-op on routing. Re-proven by the full existing contract
  suite (Jdk + Netty + NIO-fallback) passing unchanged, plus `ShardedRoutingTest.n1ShardedReaderIsTheSingleStore`.
- **Read-your-writes-per-scope.** A write to scope `S` and a read from scope `S` resolve `shardFor(S,key)`
  — the **same** shard — so the read sees the write. At `N=1` that shard is group 0 (byte-identical);
  at `N>1` it is `S`'s shard. Proven by a new test (write `S` → read `S` → value, for each `S`), with a
  non-vacuity assertion at `N>1` that a *mismatched*-scope read can miss (scope is genuinely load-bearing).
- **Superset corpus.** A new test validates that a corpus of currently-valid keys (`db.host`, `app/feature`,
  `secure/../killswitch`, `/secure/killswitch`, `a//b`, dotted/dashed/underscored, `:@+`, unicode, a
  full 1024-byte key) **all pass** the edge gate, while blank and 1025-byte keys are **rejected `400`**.

Scope stays **routing-input-only**: no `shardFor`, storage, or Raft-command change. The Raft command
still encodes only `[type][key][value]` (`ConfigWriteService.encodeCommand`); scope is never serialized.

---

## 6. The v2 boundary (documented, out of scope here)

At `N=1` (and at `N>1` today, where all scopes share one pool — `StaticShardMap` "spread-all"),
REGIONAL/LOCAL **route** correctly but do **not** yet have distinct replication **semantics** (a separate
regional Raft topology, a non-replicated local store). Surfacing `scope` end-to-end is the wiring; **true
REGIONAL/LOCAL replication domains are v2** (the "scope selects a dedicated pool" `StaticShardMap` variant
named in its javadoc + `path-model.md` §2.4). A client that writes scope `S` and reads scope `S'≠S`
gets read-your-writes only when `S==S'`; a cross-scope mismatch is a client error, not a system bug.

---

## 7. Handoff — entry points for the next increments

- **Increment 2 (O-4 ACL union+deny).** The authorization seam is `AdminApiHandler.checkAuth` →
  `AclService.isAllowed(principal, key, permission)` (longest-match-only today). The RFC §5 union-of-ALLOW
  minus DENY, deny-precedence, default-deny model replaces it there. Unchanged by this increment.
- **Binary / driver surface (strict A3 path grammar).** When the typed binary protocol is built, the
  strict RFC §1 A3 grammar (absolute + `seg-char` + canonical normalization + 64-seg / 256-byte bounds)
  becomes a hard reject-gate **on that surface only** (typed `scope`, absolute paths). That is where a
  `ConfigPath` validator class belongs — built when it has a caller, not before (§2.3).
- **Watches (RFC §2).** Independent; needs the client veneer + cursor vector. Parallel fast-follow.

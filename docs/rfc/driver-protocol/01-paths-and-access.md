# Configd Driver Protocol RFC — §01: Paths and Access Control

**Status: DRAFT (2026-06-28). Docs-only; normative.** First section of the Configd driver-protocol RFC.
This section specifies the **address model** (scope + path), the **path syntax and wire encoding**, the
**subtree semantics** drivers must understand, the **capability model and authorization contract**
(including the watch-authorization contract), and the **error taxonomy**. It is written to be rigorous
enough that a driver in **any** language (Rust / Go / Python / Java) implements path handling and
authorization **identically**.

Design rationale and prior art are in [`../../design/namespace-model/`](../../design/namespace-model/)
(`prior-art.md`, `path-model.md`, `access-control.md`). Where this RFC says MUST/SHOULD/MAY, the design
docs explain *why*. This section is **normative**; the design docs are explanatory.

This section is designed to **compose with the watch section** that follows (`02-watches.md`, derived from
[`../../research/watches/recommendation.md`](../../research/watches/recommendation.md)); the integration
points are flagged in [§9](#9-forward-compatibility-and-composition-with-the-watch-section).

---

## 1. Conventions, scope, versioning

### 1.1 Requirement keywords

The keywords **MUST**, **MUST NOT**, **REQUIRED**, **SHALL**, **SHALL NOT**, **SHOULD**, **SHOULD NOT**,
**MAY**, and **OPTIONAL** are to be interpreted as in RFC 2119 / RFC 8174.

### 1.2 Scope of this section

This section specifies how a driver **names** configuration (the `(scope, path)` address), how a path is
**encoded on the wire**, what a **subtree** operation means under Configd's hash-sharded keyspace, and the
**authorization model** (capabilities, evaluation, the watch contract, errors). It does **not** specify the
watch wire frames or the cursor-vector mechanics — those are `02-watches.md` — but it **defines the cursor
vector's role** for `list` pagination and references it for watch authorization.

### 1.3 Versioning

This address model and capability set are part of the driver-protocol version negotiated at connection
setup. The path encoding and capability identifiers in this section are **version-1**. A driver **MUST NOT**
assume a capability identifier or path rule it does not recognize; unknown capabilities **MUST** be treated
as **not granted** (fail closed).

---

## 2. The address model

### 2.1 An address is `(scope, path)`

**A2-1.** A configuration entry is addressed by the pair **`(scope, path)`**:

- **`scope`** is a typed enumeration **`{ GLOBAL, REGIONAL, LOCAL }`** identifying the **replication
  domain**. `scope` is an **orthogonal axis** and **MUST** be transmitted as a typed field — it **MUST NOT**
  be encoded as a path segment.
- **`path`** is a slash-delimited absolute path ([§3](#3-path-syntax)) identifying the entry **within** its
  scope. The path string **is** the storage key.

**A2-2.** The `path` is the unit of **logical hierarchy** (authorization, listing, watching, management).
`scope` is **not** hierarchical (it is a closed 3-valued enum).

**A2-3.** A driver **MUST** allow `scope` to default to **`GLOBAL`** when a caller does not specify it
(compatibility with the deployed control-plane HTTP surface, which is GLOBAL-only today). A driver
**SHOULD** expose `scope` explicitly for `REGIONAL`/`LOCAL` use.

### 2.2 No path segment is a routing input (the invariant drivers must respect)

**A2-4 (INV-PATH, normative for drivers).** The server places an entry by hashing the **entire** `(scope,
path)` — the `/` delimiters carry **no** routing meaning. A driver **MUST NOT** assume that paths sharing a
prefix are co-located, ordered together, or served by the same shard. A subtree **scatters across all
shards** ([§4](#4-subtree-semantics)). A driver **MUST NOT** attempt to route, shard, or order by path
prefix.

---

## 3. Path syntax

### 3.1 Grammar (ABNF)

```abnf
path        = "/" / "/" segment *( "/" segment )
segment     = 1*seg-char
seg-char    = ALPHA / DIGIT / "." / "_" / "-"      ; [A-Za-z0-9._-]
```

**A3-1.** A path **MUST** be absolute (begin with `/`). The path `/` is the **root** (the whole store within
a scope).

**A3-2.** A segment **MUST** be non-empty and **MUST** consist only of `seg-char`. The delimiter `/`
**MUST NOT** appear within a segment. The whole-segment forms `.` and `..` are **RESERVED** and **MUST NOT**
appear in a path (no relative traversal).

**A3-3 (encoding).** A path **MUST** be encoded on the wire as **UTF-8** bytes. Because `seg-char` is a
subset of ASCII, the encoding is unambiguous; a driver **MUST** reject (client-side) any path containing a
byte outside the `seg-char` set (other than the `/` delimiter).

### 3.2 Normalization (REQUIRED — paths have one canonical form)

**A3-4.** Before a path is sent or stored, it **MUST** be normalized to its single canonical form:

1. collapse no empty segments — `//` is **invalid**, not collapsed (reject it);
2. remove a trailing `/` **except** for the root `/`;
3. apply **no** case folding — paths are **case-sensitive**.

`/a/b` and `/a/b/` denote the **same** entry; `/a//b`, `/a/./b`, `/a/../b` are **invalid**. A driver
**MUST** reject non-canonical input rather than silently rewriting it (so the caller's intent is explicit),
**except** it MAY strip a single trailing slash. The canonical string is what the server hashes and stores;
two inputs that canonicalize differently are **different keys** on possibly **different shards**.

### 3.3 Limits

**A3-5.** A path **MUST NOT** exceed **1024 bytes** UTF-8 (the deployed key-length limit). A driver
**SHOULD** additionally reject paths exceeding **64 segments** in depth or **256 bytes** in any single
segment. A value **MUST NOT** exceed **1 MiB** (1 048 576 bytes), the deployed limit.

### 3.4 Patterns (for ACL rules, `list`, and watch targets — never for stored paths)

A **stored** path is always concrete (wildcard-free). Patterns appear only as ACL-rule targets, `list`
prefixes, and watch targets:

| Pattern form | Matches | Use |
|---|---|---|
| `/a/` or `/a/**` | the **subtree** under `/a` (recursive, any depth) | subtree grant; recursive list; subtree watch |
| `/a/*` | exactly **one** segment under `/a` (direct children only) | direct-children list; single-level grant |
| `/a/b` | the **exact** path `/a/b` | exact grant / exact watch |

**A3-6.** The **subtree** form (`/a/` ≡ `/a/**`) is **REQUIRED** in v1. The single-segment form (`/a/*`)
is **RECOMMENDED**. A driver **MUST** match patterns exactly as specified here so that authorization and
listing are identical across drivers. The empty/root subtree is `/**` (equivalently the root `/` as a
subtree target).

---

## 4. Subtree semantics

### 4.1 A subtree scatters; subtree ops are scatter-gather

**A4-1.** The keys under a subtree pattern **scatter across all shards** (A2-4). Therefore:

- a **single-key** operation (`get`/`put`/`delete` on a concrete path) addresses **exactly one** shard;
- a **subtree** operation (`list`, prefix/full `watch`) is a **scatter-gather across all shards**: the
  server fans the operation out to every shard and unions the per-shard results.

**A4-2 (ordering).** A driver **MUST NOT** assume any **cross-shard / global order** for subtree results.
The only ordering guarantees are **per-key** (a single key's events/versions are ordered) and **per-shard**
(one shard's results are ordered by its applied-mutation sequence `S`). This is identical to the watch
ordering contract (`02-watches.md` §5) and the cross-shard consistency contract
(ADR-multiraft-cross-shard). A driver that needs sorted subtree output **MUST** sort a **bounded** result
page client-side.

### 4.2 The `list` operation

**A4-3.** `list(scope, prefix, mode, cursor, limit)` enumerates a subtree:

- **`mode = CHILDREN`** returns the **distinct immediate child segments** under `prefix` (ZK `getChildren`
  semantics).
- **`mode = RECURSIVE`** returns the **descendant leaf paths** under `prefix`.

**A4-4 (pagination — REQUIRED).** `list` **MUST** be paginated and bounded. The request carries a `limit`;
the response carries the page plus a **continuation cursor**. Because results come from N independent shards
with no global order, the continuation cursor **MUST** be a **per-shard cursor vector** — the **same
encoding** as the watch cursor (`02-watches.md` §3): a length-prefixed list of `(uint32 gid, uint64 S)`
pairs ordered by `gid`. A driver **MUST** treat it as opaque-but-structured and re-send the full vector to
resume. **At N = 1 the vector has one element** (a driver **MUST** still treat it as a vector — A9-1).

**A4-5.** `list` results are **unordered across shards** (A4-2). A driver **MUST NOT** present `list` output
as globally sorted unless it has sorted a bounded page locally.

**A4-6 (authorization).** `list` is gated by the **`LIST`** capability over `prefix` ([§5](#5-capability-model-and-authorization)).
A `list` whose `prefix` exceeds the principal's `LIST` grant **MUST** be rejected (A5/§6 semantics — reject,
do not silently narrow).

### 4.3 No recursive delete in v1

**A4-7.** v1 defines **no recursive/subtree delete**. A subtree spans all shards and Configd offers **no
cross-shard atomicity** (a multi-key write spanning shards is rejected — ADR-multiraft-cross-shard). A driver
**MUST** delete leaves individually (each is a single-shard, commit-confirmed delete). A non-atomic,
explicitly best-effort `deleteSubtree` MAY appear in a later version with a loud "not atomic" contract; it
is **out of scope** here.

---

## 5. Capability model and authorization

### 5.1 The capability set

**A5-1.** The v1 capability set is **`{ READ, LIST, WRITE, WATCH, ADMIN }`**, with a **`DENY`** effect on
rules:

| Capability | Authorizes |
|---|---|
| `READ` | read the value at a concrete path (`get`) |
| `LIST` | enumerate children/descendants of a path (`list`) |
| `WRITE` | put or delete at a concrete path |
| `WATCH` | subscribe to a change stream on a path/subtree (`02-watches.md`) |
| `ADMIN` | manage policies/roles for a subtree; access the reserved `/_acl/`, `/_system/` subtrees |

**A5-2 (capability relationships — normative).**

- **`LIST` is independent of `READ`.** `LIST` **MUST NOT** be assumed to imply `READ`, nor `READ` to imply
  `LIST`.
- **`WATCH` requires `READ`.** To watch a target `T`, a principal **MUST** hold **both** `READ` and `WATCH`
  covering all of `T`. A watch **MUST NEVER** expose what a read could not (§6, INV-WATCH-READ).

### 5.2 Policies, roles, principals

**A5-3.** Authorization is expressed as **path-glob policies bound to principals through roles**:

- a **rule** is `(scope, pathPattern, effect ∈ {ALLOW, DENY}) → {capabilities}`;
- a **policy** is a set of rules; a **role** bundles policies; a **principal** holds roles.

A driver does not author policies on the data path, but a driver **MUST** understand that its effective
permission is the result of evaluating its principal's rules per A5-4 (so it can predict 403s and shape
requests — e.g., not attempt a `full_chain_verify` watch without root scope, §6.3).

### 5.3 Evaluation (normative — identical across drivers and server)

**A5-4.** For a request of capability `C` on `(scope, path)` by principal `p`:

```
matching = { rule : rule.scope covers `scope` AND rule.pattern matches `path` }
allow    = ⋃ { rule.caps : rule ∈ matching, rule.effect = ALLOW }
deny     = ⋃ { rule.caps : rule ∈ matching, rule.effect = DENY }
authorized(C)  ⟺  C ∈ allow  AND  C ∉ deny
```

- **Union** the capabilities of **all** matching `ALLOW` rules (every matching ancestor prefix, every role)
  — **not** longest-match-only.
- **`DENY` has absolute precedence**: a matching `DENY` for `C` removes `C` regardless of any `ALLOW`,
  including more-specific paths and including `ADMIN`.
- **Default-deny**: no matching `ALLOW` ⇒ not authorized.

> This is the Vault model. It is **BUILT** in the server's `AclService` (Wiring Increment 2, `ee27250`),
> superseding the former longest-match-only evaluation; see
> [`../../design/namespace-model/access-control.md`](../../design/namespace-model/access-control.md) §4.2.
> Drivers implement A5-4 verbatim; the server enforces the identical rule.

---

## 6. The watch-authorization contract (normative)

A watch is authorized as a **streaming read**. This subsection is normative and closes the
`full_chain_verify` bypass left open by the watch research. It is restated in `02-watches.md`; both MUST
agree.

**A6-1 (authorize at subscription).** A watch on target `T` **MUST** be authorized **at subscription**,
**before any snapshot chunk, change event, or progress/bookmark frame is sent**, as a streaming read: the
principal **MUST** hold **`READ(T) ∧ WATCH(T)`** per A5-4 (both capabilities, covering **all** of `T`).

**A6-2 (reject over-broad, do not filter).** If `T` extends beyond the principal's authorized region, the
server **MUST reject** the subscription (A6-5) and **MUST NOT** silently narrow it to the authorized subset.
*(Rationale: silent narrowing gives the client a false-completeness view it cannot distinguish from "no
changes." A client wanting the authorized subset **MUST** request that narrower target explicitly.)*

**A6-3 (`full_chain_verify` / `FULL` requires root scope).** A watch with `full_chain_verify = true`, or a
watch whose target is `FULL` (the whole store), streams the **entire signed chain verbatim with no edge
filtering**. Such a watch **MUST** require the principal to hold **`READ ∧ WATCH` over the root `/**`** for
that `scope`. **The full signed chain MUST NOT stream to a principal lacking full-scope grant.** A principal
with only a subtree grant that requests `full_chain_verify`/`FULL` **MUST** be rejected — it **MUST NOT**
receive other subtrees' data under the guise of local verification.

**A6-4 (consistency — INV-WATCH-READ).** For every key `k` a watch on `T` could deliver, if `READ k` would
be denied to `p`, the watch **MUST** be denied to `p`. A watch **MUST NEVER** deliver a change for a key the
principal could not read. (A6-2's whole-target check at subscription guarantees this without per-event
re-checks.)

**A6-5 (error + mandatory negative test).** An unauthorized subscription **MUST** be terminated with a
terminal close carrying a **`403`-class** `ErrorCode` (authorization), distinct from a `401`-class
(unauthenticated), with **no data frame emitted first**. A conforming implementation **MUST** have a
regression test proving that (a) an over-broad-target watch and (b) a non-root-scope `full_chain_verify`/`FULL`
watch are each rejected with **zero** `SNAPSHOT_*`/`WATCH_EVENT`/`WATCH_PROGRESS` frames preceding the
terminal reject.

---

## 7. Error taxonomy

> *The full cross-section error/status taxonomy — every HTTP status code and streaming `ErrorCode`, each with
> its required driver reaction — is consolidated in [§07](07-errors.md) (the single source of truth). This
> section's authorization (`401`/`403`) rows are restated there; where they overlap, §07 and this section
> **MUST** agree.*

**A7-1.** Authorization outcomes use the deployed control-plane taxonomy:

| Condition | Unary (HTTP) | Streaming (watch) |
|---|---|---|
| Missing/blank/malformed/invalid credential (authentication) | **401** + `WWW-Authenticate: Bearer` | terminal close, `401`-class `ErrorCode` |
| Authenticated but capability not granted (incl. `DENY`, over-broad watch, non-root `full_chain_verify`) | **403** | terminal close, `403`-class `ErrorCode` (A6-5) |
| Path syntactically invalid (A3) | **400** | reject at subscription, `400`-class |
| Value/path exceeds limits (A3-5) | **400** | n/a |

**A7-2.** A `401` response **MUST NOT** echo the credential. Authorization **failures** (401/403 and watch
rejects) **MUST** be audited; successful **reads** **MUST NOT** be audited per-event (DoS concern).
A driver **MUST** treat **401** as "(re)authenticate" and **403** as "permanently forbidden for this
principal" (do not retry a 403 unchanged).

---

## 8. Compatibility notes

**A8-1 (flat keys).** A legacy flat key (e.g., `db.host`) is a **degenerate single-segment path**
(`/db.host`) — the dot is a legal `seg-char`. A driver **MAY** accept a caller-supplied flat key and treat
it as `"/" + key` after validating it against A3, **or** require absolute paths; it **MUST** document which.
No stored key moves as a result of the path model (the server hashes the same string).

**A8-2 (scope default).** Until the control-plane surface carries `scope` end-to-end, a driver targeting the
HTTP admin API **MUST** send/assume `GLOBAL` (A2-3). A driver targeting the binary protocol **MUST** carry
`scope` as a typed field.

---

## 9. Forward-compatibility and composition with the watch section

This section is built to compose with `02-watches.md` (the watch RFC, from the watch research). The
integration points, stated so the RFC stays coherent as sections are added:

**A9-1 (the cursor vector is shared).** The `list` continuation cursor (A4-4) and the watch resume cursor
(`02-watches.md` §3) are the **same type**: a length-prefixed `(uint32 gid, uint64 S)[]` vector ordered by
`gid`. A driver **MUST** implement **one** cursor-vector type and reuse it for both. A driver **MUST** treat
it as a vector **even at N = 1** (a one-element vector) — a scalar-only encoding is **FORBIDDEN** (the
single most important forward-compat rule from the watch research: a scalar cursor silently breaks when the
cluster shards).

**A9-2 (the ordering contract is shared).** The subtree ordering guarantee (A4-2: per-key + per-shard,
never cross-shard) is **identical** to the watch ordering contract (`02-watches.md` §5). A driver **MUST**
present the same ordering semantics for `list` and for `watch`.

**A9-3 (the watch-authz contract lives here).** §6 is the authorization contract for the watch surface;
`02-watches.md` references it for the subscription-time check and **MUST NOT** weaken it. The `full_chain_verify`
flag defined in `02-watches.md` §7 is gated by A6-3 (root scope).

**A9-4 (capabilities extend, not break).** The `create`/`update`/`delete` split of `WRITE`, a v2
`filtered-watch` mode (authorized subset with an explicit "narrowed" signal), the untrusted-edge
signed-skip-evidence path (ADR-0038), and the typed namespace/quota registry (ADR-0017, as a logical
overlay) are **named forward extensions**. A driver **MUST** fail closed on capability identifiers it does
not recognize (A1.3) so these can be added without breaking older drivers.

---

## 10. Summary of normative requirements (driver checklist)

- [ ] Address is `(scope, path)`; `scope` is a typed field, **never** a path segment (A2-1, A2-3).
- [ ] Paths are absolute, `seg-char`-only, UTF-8, **canonically normalized**, case-sensitive, ≤ 1024 B
      (A3-1…A3-5).
- [ ] **Never** route/shard/order by path prefix; a subtree **scatters** (A2-4, A4-1).
- [ ] Subtree ops are scatter-gather; **no cross-shard order**; sort bounded pages locally if needed (A4-2,
      A4-5).
- [ ] `list` is paginated with a **per-shard cursor vector**, gated by `LIST` (A4-4…A4-6).
- [ ] Capability set `{READ, LIST, WRITE, WATCH, ADMIN}` + `DENY`; `LIST`⊥`READ`; `WATCH` requires `READ`
      (A5-1, A5-2).
- [ ] Evaluate authz as **union of ALLOW minus DENY, deny-precedence, default-deny** (A5-4).
- [ ] A watch is authorized **at subscription** as a streaming read; over-broad targets **rejected** (not
      filtered); `full_chain_verify`/`FULL` requires **root scope**; **negative test** mandatory
      (A6-1…A6-5).
- [ ] 401 vs 403 taxonomy; never echo credentials; audit auth failures (A7).
- [ ] **One** cursor-vector type shared by `list` and `watch`; **vector even at N = 1** (A9-1).

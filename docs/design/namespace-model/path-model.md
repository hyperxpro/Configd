# Configd Path / Namespace Model — the Design

**Design session, 2026-06-28. Docs-only. No production code.** Builds on
[`prior-art.md`](prior-art.md) (mechanisms) and feeds [`access-control.md`](access-control.md) (the ACL
extension) and the RFC section [`../../rfc/driver-protocol/01-paths-and-access.md`](../../rfc/driver-protocol/01-paths-and-access.md).

This document designs Configd's v1 hierarchical path model: the path syntax, its relationship to
`ConfigScope`, the logical→physical mapping, the operations (get/put/delete/**list**/watch), the
list-children cost, and compatibility with the built flat keyspace. The operator's decisions are settled
(hierarchical, path-based, ZK/Curator/Vault-style, v1, RFC-alongside); this designs *to* them.

---

## 1. The model in one paragraph

A Configd address is a pair **`(ConfigScope scope, Path path)`**. `scope ∈ {GLOBAL, REGIONAL, LOCAL}` is
the typed **replication domain** (which Raft topology owns the key) — an orthogonal axis, **not** part of
the path. `path` is a **slash-delimited absolute path** (`/team-payments/feature-flags/checkout`) that is
the **logical hierarchy** used for authorization, watch scoping, listing, and human management. The path
string **is the storage key**: `shardFor(scope, path)` hashes the **entire path string** (unchanged from
today), so the path's `/`-structure carries **no routing meaning** and a subtree **scatters across all N
shards**. Hierarchy is a *lens* the API/ACL/watch layers apply to an opaque string; the store, the hash,
and Raft see only that string. Multi-tenancy is a tenant **rooted at a top subtree** (`/tenant/**`) by ACL,
Curator-style — not a separate typed axis.

---

## 2. THE CENTRAL INVARIANT — hierarchy is LOGICAL; routing stays hash-the-full-path

This is the load-bearing property of the entire design. Everything else is subordinate to it.

> **INV-PATH (the central invariant).** The path hierarchy is a **logical overlay** used **only** for
> authorization, watch scoping, listing, and management. **Physical placement is and remains
> `shardFor(scope, fullPath)` — a hash of the entire path string.** The `/` delimiters are ordinary bytes
> in the hash input; they convey no routing information. A subtree (`/a/**`) is therefore **not a
> shard-local unit** — its members **scatter across all N shards**. Routing **MUST NEVER** inspect path
> structure, and a path prefix **MUST NEVER** select a shard.

### 2.1 Why — restated from the built code

`StaticShardMap.shardFor(scope, key)` (`StaticShardMap.java:56-79`) hashes `scope.ordinal()` then **every
character of the key** (FNV-1a → SplitMix64 avalanche → `floorMod N`). Two sibling paths hash
independently:

```
shardFor(GLOBAL, "/team-payments/flags/checkout")  ─hash─►  shard 5
shardFor(GLOBAL, "/team-payments/flags/cart")       ─hash─►  shard 2     # same prefix, different shard
shardFor(GLOBAL, "/team-payments/flags/search")     ─hash─►  shard 5
```

The subtree `/team-payments/flags/**` is **spread across the whole pool `[0, N)`** by construction. This is
not incidental — it is *why* sharding lifts aggregate throughput: even distribution requires that adjacent
keys land on different owners (`configd-phase0-state`: one group ≈ one owner thread; the aggregate scales
only because keys spread). Routing by path prefix would collapse a tenant's whole subtree onto one shard —
one owner thread, one hot group — destroying the even distribution sharding exists to provide.

### 2.2 The reconciliation — a subtree operation is a logical scatter-gather

Because a subtree scatters, **every subtree operation is a scatter-gather over the hash-distributed
keyspace**, decomposed as:

| Subtree operation | Logical (hierarchy) part | Physical (scatter-gather) part |
|---|---|---|
| **Grant** `/a/**` → caps | Store one policy rule keyed on the prefix `/a/` | **None at write time** — a grant is *metadata*, not a data scatter. It is consulted (logically) on each access. |
| **List** children of `/a/` | Interpret the prefix; extract next-segment names | Scatter `getPrefix("/a/")` to **all N shards**, union, paginate ([§5](#5-list-children--the-cost-of-hierarchy-over-hash-sharding)) |
| **Watch** `/a/**` | Authorize the prefix once at subscription | Scatter-gather the per-shard fan-out; merge with a **cursor vector** (watches `recommendation.md` §4) |

The hierarchy lives entirely in the **logical** column; the **physical** column is always "fan out to all
shards, gather." This is the same separation ADR-0020 already practices (its radix trie matches prefixes
*logically* against the event stream while `shardFor` hashes the whole key). We make it explicit and
extend it to ACLs, listing, and watches.

### 2.3 The payoff and the cost (stated honestly)

- **Payoff:** ZooKeeper/Vault **usability** (subtree ACLs, subtree watches, list-children, operator-legible
  hierarchy) **on top of** Configd's **horizontal scale** (even hash distribution, aggregate throughput).
- **Cost:** subtree operations are **scatter-gathers**, not local scans, and carry **no cross-shard order**
  (a `list` or `watch` over a subtree has per-shard order only). This cost is **inherent and accepted** —
  it is the dual of the throughput win. We pay it on the **control/management plane** (list, grant) and on
  the **already-built fan-out plane** (watch), never on the single-key data path (a single-key get/put/delete
  still addresses exactly one shard).

### 2.4 Scope is a routing input; path hierarchy is not (the distinction that is easy to miss)

`shardFor` folds `scope.ordinal()` into the hash, and the `StaticShardMap` Javadoc notes a **future**
variant where **scope selects a dedicated pool** ("scope selects the pool"). That is **permitted by
INV-PATH** and is **not** a contradiction: **scope is a typed, closed, 3-valued replication-domain axis** —
a coarse routing input by design (it already chooses GLOBAL vs REGIONAL vs LOCAL Raft topology). **Path
hierarchy is the open, arbitrary-depth logical overlay** and is **forbidden** from routing. The invariant
is specifically about *path-prefix* routing, not *scope* routing. Keep the two distinct: **scope MAY select
a pool; a path prefix MAY NOT.**

---

## 3. Path syntax + the scope relationship

### 3.1 The scope/path/namespace relationship (the key structural decision)

**Decision (recommended): scope is an ORTHOGONAL TYPED AXIS, not a path segment; "namespace" is the TOP
PATH SEGMENT by convention, not a separate typed level.** The addressable unit is `(scope, path)`.

Rationale (full reconciliation with ADR-0017 in [`prior-art.md`](prior-art.md) §5):

- Scope is **already** a typed axis folded into `shardFor` *separately* from the key (`StaticShardMap.java:67`).
  It selects the **replication domain** — a property of a different kind than "where in the tree." Encoding
  it as a magic first path segment (`/global/...`, ADR-0017's `/{namespace}/{scope}/{key_path}`) re-stringifies
  a typed dimension and invites `/GLOBAL/...` vs `/global/...` ambiguity. Keep scope typed.
- A **namespace/tenant is what the hierarchy is *for*** — the top subtree. A tenant is rooted at `/tenant/`
  (Curator namespace-rooting, [`prior-art.md`](prior-art.md) §2.2) and isolated by an ACL grant over
  `/tenant/**`. This needs **no new typed axis**: the path hierarchy *already* expresses it, and the ACL
  model *already* enforces subtree grants. ADR-0017's typed-namespace + lifecycle/quota registry becomes an
  **optional v2 logical overlay** over the top segment, never a routing axis.

**Alternative considered (scope as a top path segment):** `/{scope}/{path}`, one unified string. **Rejected**
because it loses the type, contradicts the built separate-axis routing, and complicates the wire (the watch
RFC and the write path both already carry `scope` as a typed field). *This is an operator-confirm item*
([`decision-log.md`](decision-log.md) DL-N-03): both shapes honor INV-PATH; the recommendation is
orthogonal-axis for least disruption.

```
Address  ::=  (scope, path)
scope    ::=  GLOBAL | REGIONAL | LOCAL          # typed axis; a shardFor input; NOT in the path
path     ::=  "/" segment ( "/" segment )*       # the logical hierarchy; the storage key string
```

### 3.2 Path grammar (normative shape; the RFC §3 states it MUST/SHOULD)

```
path        ::= "/" | "/" segment ( "/" segment )*
segment     ::= seg-char+                         # non-empty
seg-char    ::= ALPHA / DIGIT / "." / "_" / "-"   # [A-Za-z0-9._-]
```

- **Delimiter:** `/` (U+002F). Matches ZK/Curator/Vault/etcd-prefix convention and the *already-built*
  ADR-0020 subscription prefixes (`/service/api/*`).
- **Absolute:** every path begins with `/`. The **root** is `/` (denotes the whole store within a scope).
- **Segments:** non-empty, drawn from `[A-Za-z0-9._-]`. The dot is allowed inside a segment so existing
  dotted keys (`db.host`, `feature.flags.checkout`) map to single-segment paths (`/db.host`) verbatim
  (§6). `/` is reserved as the delimiter and MUST NOT appear in a segment.
- **Forbidden:** empty segments (`//`), relative references (`.`, `..` as whole segments — no traversal),
  trailing slash except on root, control characters, and non-UTF-8 byte sequences.
- **Normalization (load-bearing):** a path has exactly **one** canonical form. The server normalizes
  (collapse no `//`, strip trailing `/` except root) **before** hashing/storing, so `/a/b` and `/a/b/` are
  the **same key** and never two shards. The canonical normalized string is what `shardFor` hashes and what
  the store keys on. Paths are **case-sensitive** (no case folding).
- **Limits:** the built **1024-byte total key length** is retained (`ConfigWriteService.java:241`). Add a
  **max depth** (recommend **64 segments**) and a **max segment length** (recommend **256 bytes**) to bound
  pathological inputs. These are validation limits at the API edge, not storage limits.

### 3.3 Wildcards / globs (for ACL rules, list, and watch targets — not for stored paths)

A *stored path* is always a concrete, wildcard-free address. Wildcards appear only in **patterns** (ACL
rules, list/watch targets):

| Pattern | Meaning | Source precedent |
|---|---|---|
| `/a/` (trailing slash) **or** `/a/**` | the **subtree** under `/a` (recursive, any depth) | the built `AclService` prefix grant; ADR-0020 `**`; Vault `secret/a/*` |
| `/a/*` | exactly **one** segment under `/a` (`/a/x`, not `/a/x/y`) — direct children only | Vault `+`; ZK `getChildren` |
| `/a/b` (no wildcard) | the **exact** path | etcd exact key; ADR-0020 exact match |

This regularizes ADR-0020's informal glob (which used `*` and `**` loosely) into crisp semantics. **For v1
the load-bearing pattern is the subtree (`/a/` ≡ `/a/**`)** — it is what the built prefix-grant already
means and what multi-tenancy needs; single-segment `*` is a refinement. The RFC §3 specifies the exact
matcher so all drivers agree.

---

## 4. Operations

| Op | Signature | Shards touched | Notes |
|---|---|---|---|
| **get** | `get(scope, path) → value?` | **1** = `shardFor(scope, path)` | Single key; linearizable or stale, exactly as built (`ConfigReadService`). Byte-identical to today. |
| **put** | `put(scope, path, value) → seq` | **1** | Single key; commit-confirmed (ADR-0033). Byte-identical to today. |
| **delete** | `delete(scope, path) → seq` | **1** | Single-key tombstone. **No implicit recursive delete** (§4.1). |
| **list** | `list(scope, pathPrefix, mode, cursor, limit) → (entries, nextCursor)` | **all N** (scatter-gather) | The hierarchy-enabled op. Children (`/a/*`) or recursive (`/a/**`). Paginated, bounded, per-shard order only ([§5](#5-list-children--the-cost-of-hierarchy-over-hash-sharding)). |
| **watch** | `watch(scope, target, cursorVector) → stream` | **1** (single key) or **all N** (prefix/full) | Per the watch research; subtree = scatter-gather + cursor vector. Authorized at subscription ([`access-control.md`](access-control.md) §6). |

### 4.1 Why there is no recursive delete in v1

A recursive delete of `/a/**` would have to delete keys spanning **all N shards atomically**. Configd
**does not offer cross-shard atomicity** — the cross-shard write guard *rejects* a multi-key write whose
keys span shards (`ConfigWriteService.ProposeCommitResult.CrossShardRejected`, DISCLAIM,
ADR-multiraft-cross-shard). A recursive delete is therefore either (a) **not atomic** (a best-effort
per-shard sweep that can partially fail, leaving a half-deleted subtree) or (b) **impossible to make
atomic** without the cross-shard transaction Configd deliberately lacks. **v1 recommendation: no recursive
delete.** A client deletes leaves individually (each is a single-shard, commit-confirmed delete). A bounded,
**non-atomic, explicitly-best-effort** `deleteSubtree` MAY be offered in v2 with a loud "not atomic"
contract — recorded as an extension point, not built. (This mirrors Curator's `deletingChildrenIfNeeded`
being an explicit, non-transactional opt-in.)

### 4.2 Single-key ops are unchanged

`get`/`put`/`delete` on a concrete path address exactly one shard (`shardFor(scope, path)`), exactly as the
built single-key path does today. The path model adds **no cost and no semantic change** to the single-key
data plane — it changes only what the key *string* means to the API/ACL/watch layers. This is the basis of
the N=1 byte-identity claim (§6).

---

## 5. List-children — the cost of hierarchy over hash sharding

`list` is the operation the hierarchy enables and the one INV-PATH makes expensive. This section designs it
and analyzes the cost honestly.

### 5.1 What list must do

`list(scope, "/a/", mode)` returns either the **immediate children** of `/a/` (mode = `CHILDREN`, ZK
`getChildren` / the distinct child *segments*) or **all descendants** (mode = `RECURSIVE`, the full subtree
leaf set). Under INV-PATH the keys under `/a/` are spread across all N shards, so:

```
list(scope, "/a/", mode):
    per shard i in [0, N):                       # SCATTER — parallel
        local_i = shard_i.getPrefix("/a/")       # keys on shard i with that prefix
    union = ⋃ local_i                            # GATHER
    if mode == CHILDREN:
        return { firstSegmentAfter("/a/", k) for k in union }   # distinct child names
    else:
        return union                             # full leaf set (paginated)
```

A `list` **cannot** be served from one shard — the subtree is not shard-local. This is INV-PATH's direct,
unavoidable consequence.

### 5.2 The cost — and the built starting point

| Layer | Cost today (built) | Why |
|---|---|---|
| Per-shard `getPrefix` | **O(K_i)** where `K_i` = total keys on shard i | `VersionedConfigStore.getPrefix` is a **linear scan + `startsWith`** over an **unordered HAMT** (`VersionedConfigStore.java:257-270`) — it touches every key, not just matches. The source flags this. |
| Aggregate scatter | **O(total keys)** across all shards | sum of the per-shard scans |
| Network | N scatter requests + 1 gather | one round of fan-out |

So a naïve v1 `list` is **O(total keyspace)** — acceptable for a small store, linear-bad for a large one.
**This is a pre-existing property of the built `getPrefix`, not introduced by the path model** — but the
path model makes `list` a first-class operation, so the cost must be owned.

### 5.3 Recommendation: scatter-gather scan for v1; maintained index is the v2 optimization

- **v1: scatter-gather over `getPrefix`, on the control/management plane, paginated and bounded.** `list`
  is an **admin/management operation, not a hot data-plane read** — it is invoked by operators, tooling,
  and occasional client enumeration, not on every config fetch. The O(total) cost is tolerable at v1 scale
  and behind pagination. **Document it as a known cost**, with the maintained-index optimization named.
- **Pagination + bounds are mandatory, not optional.** A `list` over a huge subtree must not return an
  unbounded set or scan unboundedly per call. `list` takes a `limit` and returns a **continuation cursor**.
  Because results come from N independent shards with no global order, the continuation cursor is a
  **per-shard cursor vector** — the **same shape as the watch cursor** (watches `recommendation.md` §3),
  a deliberate reuse so drivers implement one cursor type. Each shard advances its own component; the
  client re-sends the vector to resume. (See RFC §4 for the encoding.)
- **No cross-shard order.** `list` results are **unordered across shards** (per-shard order only), identical
  to the watch contract and ADR-multiraft-cross-shard. A client wanting sorted output sorts the **bounded**
  page client-side. The RFC MUST state drivers MUST NOT assume `list` is globally sorted.
- **v2 optimization (named, not built): a maintained subtree index.** Two options, with the trade:
  - *Per-shard ordered secondary index* (a `ConcurrentSkipListMap<String,…>` mirror of each shard's keys):
    makes per-shard `getPrefix` **O(log K_i + matches)** instead of O(K_i), at the cost of extra memory and
    a write-path index update. Still a scatter-gather (the subtree still spans shards), just cheaper per
    shard.
  - *Per-shard parent→children tree index* (each shard maintains `parentPrefix → {childSegment}` for the
    keys it owns): makes `CHILDREN` listing **O(children on this shard)** with no scan, at the cost of
    write-path bookkeeping that **must live in the deterministic state machine** (so every replica builds
    the identical index — it is replicated state, not a cache). This is the heavier option.
  - **Recommendation:** if/when `list` cost matters, prefer the **ordered secondary index** (simpler,
    cache-like, no new replicated state) over the tree index. Defer both; v1 ships the scan. Recorded as a
    v2 extension point.

### 5.4 List authorization

`list` is gated by the **`LIST` capability** ([`access-control.md`](access-control.md) §4), **distinct from
`READ`** (enumerating child names is a separate power from reading values — the Vault/ZK lesson). A `list`
over `/a/` requires `LIST` covering `/a/`. Listing returns only entries the principal may `LIST`; a `list`
whose target exceeds the grant is **rejected** (consistent with the watch-authz contract, not silently
filtered — [`access-control.md`](access-control.md) §6). Enforced at the same point and with the same engine
as read authz.

---

## 6. Compatibility with the built flat keyspace (N=1 byte-identity at the storage layer)

The path model is a **pure superset** of the built flat keyspace, achievable with **no storage migration,
no `shardFor` change, and no Raft change**. The argument:

1. **The store keys on an opaque string.** `ConfigMutation.key()`, `VersionedConfigStore`, `shardFor`, and
   the Raft log all treat the key as an opaque `String` (§0 of [`prior-art.md`](prior-art.md)). A "path" is
   just a *constrained, interpreted* string. The storage/hash/consensus layers are **unchanged** — they
   never learn what `/` means.
2. **`shardFor` is untouched.** A path string hashes exactly as any key string hashes today. Enabling the
   path model **moves no existing key** and changes no routing. **N=1 stays byte-identical** (every key →
   group 0; `StaticShardMap` Javadoc) and N>1 routing is identical to the current hash.
3. **Existing dotted keys map verbatim.** `db.host` is a valid single-segment path `/db.host` (the dot is a
   legal segment char, §3.2). Existing keys need not be rewritten to be addressable; the path layer accepts
   a legacy flat key as a **degenerate single-segment path**.
4. **The path model lives *above* the store.** Path validation/normalization, subtree ACLs, list, and watch
   scoping are **API/driver/ACL-layer** concerns. The boundary is clean: below the API, it is the existing
   opaque-key store; at and above the API, it is the path model.

### 6.1 Migration / rollout (least-disruptive)

- **Storage:** none. No key moves; no reshuffle; no format change.
- **Write path:** the HTTP admin handler currently **hardcodes `ConfigScope.GLOBAL`** on every PUT/DELETE
  (`AdminApiHandler.java:296,315`) and is scope-blind on GET. The path model surfaces `scope` as a typed
  field (the driver protocol carries it; the RFC §3 specifies it). **This is a wire/API addition, not a
  storage change** — existing GLOBAL-only behavior is the default when scope is omitted.
- **ACL:** the built `AclService` is already prefix-based, so existing grants are already subtree grants in
  the new model (a grant on `app/` already covers `app/**`). The behavior changes the model introduces —
  **union-of-ancestors** and **deny-precedence** instead of **longest-match-only** — are a deliberate,
  flagged ACL semantic change ([`access-control.md`](access-control.md) §5), **not** a storage-layer change;
  it does not touch the N=1 byte-identity discipline (which governs storage/consensus, not control-plane
  policy).
- **Drivers:** the path syntax, the scope axis, and the capability model are the **driver-protocol
  contract** (RFC §3-6). New deployments adopt paths natively; the legacy flat-key surface remains a
  degenerate case.

---

## 7. What stays out of routing (restated, because it is the whole point)

- `shardFor(scope, path)` hashes `scope.ordinal()` + the **entire normalized path string**. The `/`
  delimiters are bytes in the hash; they carry **no** routing meaning.
- **No path prefix, subtree, namespace, or tenant ever selects a shard.** A subtree scatters across `[0, N)`.
- **Scope** (the typed axis) *may* select a shard pool in a future variant — that is permitted and is a
  different mechanism from path-prefix routing (§2.4).
- Every subtree operation (grant, list, watch) is a **logical scatter-gather** over the hash-distributed
  keyspace — never a routed-to-one-shard operation.

If a future change is ever tempted to "route a tenant's subtree to its own shard for isolation," that is
**ADR-0017's superseded affinity** ([`prior-art.md`](prior-art.md) §5) and **violates INV-PATH** — the
isolation goal is met by per-principal rate limiting and subtree ACLs instead.

---

## 8. Open questions for the operator

1. **Scope/path/namespace relationship** (§3.1): confirm **scope = orthogonal typed axis, namespace = top
   path segment by convention** (recommended), vs scope-as-path-segment (ADR-0017). Both honor INV-PATH.
2. **`list` cost posture** (§5): confirm **scatter-gather scan for v1** (O(total), paginated, control-plane)
   with the **ordered secondary index** as the named v2 optimization.
3. **Recursive delete** (§4.1): confirm **none in v1** (no cross-shard atomicity); best-effort `deleteSubtree`
   deferred to v2 with a loud non-atomic contract.
4. **Wildcard surface** (§3.3): confirm **subtree (`/a/`) is the v1 load-bearing pattern**, single-segment
   `*` a refinement.
5. **Typed namespace registry / quotas / lifecycle** (ADR-0017): confirm **deferred to v2 as a logical
   overlay** over the top segment (multi-tenancy delivered in v1 by subtree ACLs + per-principal limits).

These feed [`access-control.md`](access-control.md) (the capability set and the watch-authz contract) and
the RFC section, which makes the path handling normative for all drivers.

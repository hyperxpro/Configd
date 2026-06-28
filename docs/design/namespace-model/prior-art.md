# Hierarchical Path / Namespace Model — Prior Art (mechanisms, mapped to Configd)

**Design session, 2026-06-28. Docs-only. No production code.** Companion to
[`path-model.md`](path-model.md) (the Configd design), [`access-control.md`](access-control.md) (the ACL
extension), and the first driver-protocol RFC section
[`../../rfc/driver-protocol/01-paths-and-access.md`](../../rfc/driver-protocol/01-paths-and-access.md).

This document extracts the **mechanisms** of the reference systems — ZooKeeper, Apache Curator, HashiCorp
Vault, etcd — at the level a protocol author needs, and maps each mechanism onto Configd's **built
reality** (a flat, hash-sharded keyspace with per-prefix ACLs). The goal is to borrow ZooKeeper's
*usability* (a path tree, per-node authorization, subtree watches) without importing the property
Configd cannot afford: **routing by path prefix**, which would defeat the even hash distribution that
sharding exists to provide (the central invariant, [`path-model.md`](path-model.md) §2).

Every section ends with **→ Configd:** the lesson taken (adopt / adapt / reject, with reason).

---

## 0. Configd's built reality (the baseline every borrow is measured against)

So the borrows below are concrete, here is exactly what is built today (verified against source, not the
ADRs):

| Dimension | Built reality | Evidence |
|---|---|---|
| **Key model** | A key is an **opaque flat `String`** (≤ 1024 bytes UTF-8) plus a typed `ConfigScope`. There is **no path type, no namespace type, no tree**. | `ConfigMutation.java` (`String key()`); `ConfigWriteService.put(key, value, scope, principal)` size-check at `ConfigWriteService.java:241` |
| **Scope** | `enum ConfigScope { GLOBAL, REGIONAL, LOCAL }` — selects the **replication domain** (which Raft topology owns the key). A typed, closed, 3-valued axis. | `ConfigScope.java` |
| **Routing** | `StaticShardMap.shardFor(scope, key)` = FNV-1a over `scope.ordinal()` **then every char of the key**, SplitMix64-finalized, `floorMod N`. **The whole key is hashed.** Routing is *always* `shardFor`; callers never inline `mod N`. | `StaticShardMap.java:56-79` |
| **ACL** | `AclService`: per-**prefix** grants, `prefix → principal → {READ,WRITE,ADMIN}`, **longest-prefix match, only the single longest match consulted** (not a union of ancestors). `grant("", "root", …)` is the catch-all. | `AclService.java:79-102` |
| **ACL enforcement** | Checked at the HTTP admin handler: READ on GET, WRITE on PUT/DELETE, via `aclService.isAllowed(principal, key, perm)`. | `AdminApiHandler.java:396-418` |
| **Identity** | `AuthResult.Authenticated(String principal, Set<String> roles)` — **roles are carried but `AclService` ignores them** (it keys on `principal`). A latent capability. | `AuthInterceptor.java:20-23`; `AclService.isAllowed(principal, …)` |
| **List / prefix scan** | `getPrefix(prefix)` = **O(total-keys) linear scan** with `key.startsWith(prefix)` over a HAMT (unordered; no navigable index). The source itself flags this for large key sets. | `VersionedConfigStore.java:257-270` |
| **Subscriptions** | Edge nodes subscribe to **path-style prefixes** (`/service/api/*`, `/service/**`) matched by a **radix trie on the distribution node** — *logical* prefix matching that already coexists with hash routing. | ADR-0020 §"Subscription Matching" |

Two facts from this table shape everything below:

1. **The store sees only an opaque string.** "Path" is an *interpretation* of that string by the API / ACL
   / watch layers — never by the store, the hash, or Raft. This is why a hierarchical model can be a
   pure superset with **no storage migration and no `shardFor` change** ([`path-model.md`](path-model.md)
   §6).
2. **Logical prefix matching already coexists with hash routing in the built system** (ADR-0020's radix
   trie filters the *event stream* by prefix; `shardFor` still hashes the whole key). The central
   invariant is not a new invention — it is the discipline ADR-0020 already follows, made explicit and
   extended to ACLs, listing, and watches.

---

## 1. ZooKeeper — the path tree, per-znode ACLs, and watches

ZooKeeper is the canonical hierarchical coordination store and the model the operator named (ZK / Curator
/ Vault-secret-path style). Its three relevant mechanisms:

### 1.1 The znode path tree

- The namespace is a **tree of znodes** addressed by **slash-delimited absolute paths** (`/app/config/db`),
  exactly like a Unix filesystem. Paths are absolute (no relative references; no `.`/`..`), and a few
  names are reserved (`zookeeper`). A znode is **both a value-holder and a directory** (it can have data
  *and* children) — unlike a filesystem's file/dir split.
- **Ephemeral vs persistent** znodes: ephemeral nodes vanish when the creating session ends (the basis of
  ZK's lock/membership recipes); persistent nodes outlive sessions. **Sequential** znodes get a
  monotonic counter appended.

**→ Configd:** adopt the **slash-delimited absolute-path tree** as the *logical* address space and the
"a path both holds a value and has children" model (Configd keys are leaves that also imply interior
nodes). **Reject ephemeral/sequential znodes** — Configd is a config store, not a coordination service;
session-lifecycle nodes and server-assigned sequence suffixes are out of scope (they belong to the
coordination-primitive use case ZK also serves, which Configd explicitly does not). Interior nodes in
Configd are **implicit** (derived from the set of leaf keys), not first-class created objects — see
[`path-model.md`](path-model.md) §3.

### 1.2 Per-znode ACLs — and the property we deliberately invert

- Each znode carries its **own ACL list**. An ACL entry is `scheme:id:permissions`, where `scheme` is the
  authentication scheme (`world`, `auth`, `digest`, `ip`, `x509`), `id` is the principal within that
  scheme, and `permissions` is a subset of **CREATE, READ, WRITE, DELETE, ADMIN** (`crwda`). Note ZK
  splits **CREATE** (make a child) and **DELETE** (remove a child) out from **WRITE** (set this node's
  data), and **ADMIN** governs *changing the ACL itself*.
- **The load-bearing property: ZK ACLs are NOT recursive and NOT inherited.** *"An ACL pertains only to
  the znode it is associated with; in particular it does not apply to children. … ACLs are not
  recursive."* If `/app` is readable only by `ip:172.16.16.1` but `/app/status` is world-readable, anyone
  can read `/app/status`. A child does **not** inherit its parent's ACL.

**→ Configd:** adopt ZK's **fine-grained capability split idea** (we land on `{READ, LIST, WRITE, WATCH,
ADMIN}`, with ADMIN governing policy management — [`access-control.md`](access-control.md) §4). But
**deliberately invert ZK's non-inheritance.** Configd's built ACL is already *prefix*-based (a grant on a
prefix covers everything under it), and the operator's whole rationale for hierarchy is "RBAC maps
naturally onto path subtrees." So a Configd grant on `/team-payments/` **MUST** cover the subtree
(inherited-down), which is the opposite of ZK and the same as Vault (§3). ZK's per-node, set-it-on-every-node
model is the usability failure we are avoiding. We keep ZK's *vocabulary* and reject its *propagation rule*.

### 1.3 Watches — one-shot → persistent recursive

- Classic ZK watches are **one-shot**: a watch fires once and must be re-registered, and between the fire
  and the re-registration the client can miss changes (the well-known "cannot reliably see every change"
  gap). A watch is set per-operation (`getData`, `getChildren`, `exists`) and is scoped to that one znode.
- **Since 3.6.0**, ZooKeeper adds **persistent recursive watches** via `addWatch(path, watcher, mode)`
  with `AddWatchMode.PERSISTENT` / `PERSISTENT_RECURSIVE`. *Persistent* = the watch is not removed when it
  fires (no re-arm); *recursive* = it fires for the registered node **and all descendants**. This is ZK
  catching up to a subtree-subscription model.

**→ Configd:** the **persistent recursive subtree watch is exactly the target** — and Configd's watch
research already lands there independently (persistent, resumable, prefix/subtree-scoped — watches
`recommendation.md` §1). The contribution of *this* design is **authorization** for that watch: a recursive
subtree watch must be authorized over the *whole* subtree at subscription, as a streaming read
([`access-control.md`](access-control.md) §6). ZK's recursive watch has no special subtree-authz story
(its ACLs are per-node and non-recursive, so ZK cannot even express "authorize this whole subtree" cleanly)
— a gap our inherited-down model closes.

---

## 2. Apache Curator — the path-based API and namespace-rooting

Curator is the high-level client library that made ZooKeeper *usable*; it is the operator's stated
usability target ("how a path-based API feels to use"). Two mechanisms matter:

### 2.1 The fluent path API (the usability bar)

- Curator wraps raw ZK in a fluent, path-centric builder: `client.create().forPath("/a/b", data)`,
  `client.getChildren().forPath("/a")`, `client.delete().deletingChildrenIfNeeded().forPath("/a")`, plus
  the *recipes* (locks, leader election, caches) built on paths. The lesson is the **shape of the API**:
  operations are verbs over a path, listing children is first-class, and recursive operations
  (`creatingParentsIfNeeded`, `deletingChildrenIfNeeded`) are explicit opt-ins.

**→ Configd:** the driver protocol's surface should be **verbs over a `(scope, path)`** — `get`, `put`,
`delete`, `list` (children of a path), `watch` (a path/subtree) — with recursive operations **explicit and
bounded** (a recursive `list` is a paginated scatter-gather; there is no implicit recursive *delete* in v1
— a subtree spans all shards and a cross-shard atomic delete does not exist, ADR-multiraft-cross-shard).
See [`path-model.md`](path-model.md) §4.

### 2.2 Namespace-rooting (chroot)

- A `CuratorFramework` can be built with `.namespace("MyApp")`; Curator then **transparently prepends
  `/MyApp`** to every path the client uses. `create().forPath("/test")` actually writes `/MyApp/test`. The
  client believes it operates at the root; it is *chrooted* into a subtree. (ZK itself supports a chroot
  suffix on the connection string; Curator's namespace is the client-side equivalent.)

**→ Configd:** **adopt namespace-rooting as the multi-tenancy primitive** — and this is the key
reconciliation with ADR-0017. A "tenant" or "namespace" is **not a new typed axis**; it is the **top
segment of the path**, and a tenant client is **rooted** at `/tenant/` by (a) an ACL grant that covers
exactly `/tenant/**` and (b) optional driver-side path-prefixing à la Curator. This delivers ADR-0017's
isolation goal (a tenant sees and touches only its subtree) **without** ADR-0017's routing conflict
(namespace-pinned shard groups — §5 below). Rooting is a *logical* lens; the rooted subtree still
hash-scatters across all shards.

---

## 3. HashiCorp Vault — path-glob policies, the list/read split, deny-precedence

Vault is the operator's third named model and the closest match to what Configd should build for
*authorization*, because Vault, like Configd, layers access control over a **path namespace** with
**inherited-down** semantics.

### 3.1 Path-glob policies → capabilities

- A Vault **policy** is a list of rules, each a **path pattern → capabilities**:
  ```hcl
  path "secret/data/app-a/*" { capabilities = ["read", "list"] }
  path "secret/data/app-a/private/*" { capabilities = ["deny"] }
  ```
- **Capabilities:** `create` (POST to a new path), `read` (GET), `update` (POST/PUT to an existing path),
  `delete` (DELETE), `list` (enumerate keys under a path), `sudo` (root-protected paths), and `deny`.
- **Path globs:** `*` matches as a suffix wildcard (a trailing `secret/app-a/*` matches the subtree); `+`
  matches **exactly one path segment** (`secret/+/config` matches `secret/app-a/config` but not
  `secret/app-a/b/config`). A policy is bound to a token **through one or more policies attached to the
  auth role / identity**; a token's effective permission is the **union of all its policies' matching
  rules**.

### 3.2 The two properties Configd should import wholesale

1. **`list` is a capability distinct from `read`.** Enumerating the keys under `secret/app-a/` (learning
   *what names exist*) is a separate, separately-grantable power from reading a value at a known path.
   Knowing that `secret/app-a/stripe-prod-key` *exists* is itself sensitive. Vault (and ZK, via the
   `getChildren` permission being part of READ on the parent) treat enumeration as access-controlled.
2. **`deny` takes precedence over everything**, including `sudo`. When a token's policies yield both a
   grant and a deny for a path, **deny wins** unconditionally. This is what makes "grant the subtree,
   carve out a sensitive sub-subtree" expressible and *safe*.

**→ Configd:** **this is the recommended ACL model** ([`access-control.md`](access-control.md)). Adopt:
**inherited-down subtree grants** (a rule on a path prefix covers its subtree); **`LIST` as a capability
distinct from `READ`**; **union of all matching rules** (superseding the built `AclService`'s
longest-match-*only* evaluation — a deliberate, flagged semantic change, [`access-control.md`](access-control.md)
§5); and **explicit `DENY` with absolute precedence** (a new capability the built model lacks). Configd's
capability set lands on `{READ, LIST, WRITE, WATCH, ADMIN}` + a `DENY` modifier — Vault's `create`/`update`
split is folded into the built coarse `WRITE` for v1 (noted as a v2 refinement), and `WATCH` is added
because Configd has a streaming subscription Vault does not.

### 3.3 What Configd must NOT copy from Vault

- Vault **does not shard by path** (it has a storage backend and a single logical namespace tree); its
  `list` is cheap because the backend is ordered. Configd's `list` is a **scatter-gather over hash-distributed
  shards** and is fundamentally more expensive ([`path-model.md`](path-model.md) §5). Do not assume Vault's
  `list` cost model.

---

## 4. etcd — flat storage, prefix-as-hierarchy, range RBAC

etcd is the most architecturally-relevant reference because, like Configd, **its storage is a flat
keyspace** and "hierarchy" is a *convention* over key prefixes — not a tree.

### 4.1 Flat keys + range/prefix operations

- etcd stores **flat byte-string keys**. "Directories" are a convention: `/foo/bar` and `/foo/baz` are
  unrelated keys that happen to share a prefix. Hierarchy is expressed through **range queries** — a
  `Range(start, end)` over the sorted keyspace, with the common case (`--prefix`) desugaring to the range
  `[p, p+1)` (prefix `p` to the next-prefix). `Get`, `Delete`, and `Watch` all take a key or a range.
- This works because etcd's single keyspace is **globally sorted** (one MVCC b-tree under one Raft log), so
  a prefix is a contiguous range and a range scan is O(log n + matches).

### 4.2 RBAC over key ranges

- etcd v3 RBAC: **users → roles → permissions**, where a permission is `{read | write | readwrite}` over a
  **key or key range** (`etcdctl role grant-permission r read /foo`, or `--prefix=true read /foo/` for the
  subtree). There is **no separate `list`** capability — a range *read* is the enumeration. Permissions are
  **range grants**, the direct analogue of subtree grants.

### 4.3 The lesson — and the one place Configd is harder than etcd

**→ Configd:** etcd proves the **"flat storage, prefix-as-hierarchy, range/prefix RBAC"** hybrid is sound
and production-proven — which is precisely Configd's situation (flat opaque keys + per-prefix ACL). Adopt
the **users → roles → range(subtree) permissions** structure. **But Configd is strictly harder on one
axis:** etcd's keyspace is a *single globally-sorted Raft log*, so a prefix is a contiguous, cheaply-scannable,
**globally-ordered** range. Configd **hash-shards** the keyspace across N independent Raft groups, so:

- a prefix is **not contiguous** — it **scatters** across all N shards ([`path-model.md`](path-model.md) §2);
- there is **no global order** across shards — a prefix `list` or `watch` has **per-shard order only**
  (watches `recommendation.md` §5; ADR-multiraft-cross-shard);
- a prefix `list` is a **scatter-gather**, not a single range scan.

This is the deliberate trade Configd makes (global order → aggregate throughput) and the reason etcd's
clean range model cannot be copied verbatim. We take etcd's **RBAC structure and prefix-grant semantics**
and pay the **scatter-gather cost** etcd does not.

---

## 5. ADR-0017 (Namespace Multi-Tenancy) — reconciliation

ADR-0017 is the existing **designed-but-unbuilt** namespace model. Its status reads `Accepted`, but there
is **no namespace type, no namespace enforcement, and no namespace lifecycle in the code** — the addressable
unit is still `(ConfigScope, String key)` (§0). Per the session charter, ADR-0017 is **paper**, and this
design **supersedes it where it conflicts with the hash-routing invariant or the built reality**. The
reconciliation, item by item:

| ADR-0017 element | Disposition | Reason |
|---|---|---|
| Key format `/{namespace}/{scope}/{key_path}` | **Supersede.** Scope is **not** a path segment; the addressable unit is `(scope, path)` with `scope` an orthogonal typed axis, and "namespace" is the **top path segment by convention** (Curator-rooting, §2.2), not a separate typed level. | Scope is already a typed, closed, replication-domain axis folded into `shardFor` *separately* from the key (`StaticShardMap.java:67`). Re-encoding it as a stringly-typed magic path segment loses the type and contradicts the built routing. See [`path-model.md`](path-model.md) §3. |
| "Namespaces can be **pinned to specific Raft shard groups**" / "Raft group affinity" / "a namespace can be assigned to its own Raft shard group" | **Supersede / reject.** A namespace is the top path segment; its keys **hash-scatter across all shards** and **MUST NOT** be pinned to one group. | This is the **direct violation of the central invariant** ([`path-model.md`](path-model.md) §2). Pinning a subtree to a shard routes by path prefix → one tenant's keys collapse onto one group → hot shard, lost even distribution — the exact failure sharding exists to prevent. |
| Per-namespace **write-throughput isolation** (the *goal* behind shard affinity) | **Keep the goal, change the mechanism.** Achieve it via **per-principal API-layer rate limiting** (already built, S7.5 — `ConfigWriteService` per-principal token buckets) and, if ever needed, a future **scope-pool** variant (scope, a typed axis, *may* select a shard pool — `StaticShardMap` Javadoc — but **path/namespace may not**). | The isolation goal is legitimate; the prefix-routing implementation is not. Rate limiting sheds a noisy tenant at the edge before any Raft work, with no routing change. |
| Per-namespace **key visibility** (tenant A's keys invisible to tenant B) | **Keep, via subtree ACLs.** A tenant token is granted `{READ, LIST, WATCH}` over exactly `/tenant/**` and nothing else, so it cannot read, enumerate, or watch outside its subtree. | This is the inherited-down Vault model (§3) applied to the top segment. Stronger and simpler than a bespoke namespace-visibility check. |
| Per-namespace **ACL policies**, **rate limits**, **quotas**, **lifecycle** (create/configure/drain/delete) stored under `/_system/namespaces/` | **Keep as an optional logical overlay (v2).** A "namespace registry" MAY attach policy/quota metadata to a top-segment prefix, stored under a reserved subtree (e.g., `/_acl/`, `/_system/`), replicated as ordinary config. It remains a **logical overlay, never a routing input**. | Compatible with the invariant as long as it does not route. Deferred: v1 multi-tenancy is delivered by subtree ACLs + per-principal limits; the typed registry/quota/lifecycle is additive later. |
| Per-namespace **subscription isolation** ("Plumtree fan-out filters events by namespace") | **Keep, and make normative as the watch-authz contract.** Subscriptions/watches are authorized at subscription against the subtree grant; a watch beyond the grant is **rejected, not silently filtered** ([`access-control.md`](access-control.md) §6). | ADR-0017 stated the goal; the watch research left the *enforcement* as a single hand-wavy bullet. This design specifies it normatively and closes the `full_chain_verify` full-store bypass. |

**Net:** ADR-0017's *intent* (tenant isolation in a shared cluster, without per-tenant clusters) is
**preserved and strengthened**; its *mechanism* (namespace as a typed axis with Raft-group affinity and
scope-in-the-path) is **superseded** by (a) namespace-as-top-subtree, (b) scope-as-orthogonal-axis, and
(c) inherited-down subtree ACLs + per-principal rate limiting — all of which honor the hash-routing
invariant. A follow-up ADR should record ADR-0017 as **Superseded-in-part** by this design once the
operator confirms the path/scope/namespace relationship ([`decision-log.md`](decision-log.md) DL-N-12).

---

## 6. Mechanism summary — what is adopted, adapted, rejected

| Mechanism | Source | Configd disposition |
|---|---|---|
| Slash-delimited absolute path tree; a path holds a value *and* has children | ZK | **Adopt** (logical address space; interior nodes **implicit**) |
| Ephemeral / sequential znodes | ZK | **Reject** (coordination-primitive, not config) |
| Fine-grained capability split (`crwda`) | ZK | **Adapt** → `{READ, LIST, WRITE, WATCH, ADMIN}` |
| Per-node, **non-inherited** ACLs | ZK | **Reject the propagation rule**; invert to inherited-down |
| Persistent **recursive** subtree watch | ZK 3.6+ | **Adopt** (already the watch-research target; add subtree-authz) |
| Fluent verbs-over-path API; explicit recursive ops | Curator | **Adopt** (driver surface shape) |
| **Namespace-rooting / chroot** | Curator | **Adopt** as the multi-tenancy primitive (top-segment + grant) |
| **Path-glob policies → capabilities** | Vault | **Adopt** (the recommended ACL model) |
| **`list` distinct from `read`** | Vault | **Adopt** |
| **`deny` with absolute precedence**; **union** of matching rules | Vault | **Adopt** (supersedes built longest-match-only) |
| Sharded/ordered backend assumptions for cheap `list` | Vault | **Reject** (Configd `list` is scatter-gather) |
| **Flat storage + prefix-as-hierarchy** | etcd | **Adopt** (it *is* Configd's situation) |
| **users → roles → range(subtree) RBAC** | etcd | **Adopt** the structure |
| Prefix = contiguous, globally-ordered, single-scan range | etcd | **Reject** (Configd prefix scatters; per-shard order only) |
| Namespace as typed axis + Raft-group affinity + scope-in-path | ADR-0017 | **Supersede** (violates hash-routing / built reality) |
| Namespace isolation, per-tenant limits, lifecycle/quota registry | ADR-0017 | **Keep the goals** via subtree ACLs + per-principal limits + (v2) logical registry |

The single thread through all of it: **borrow the hierarchy as a *logical* structure for authorization,
listing, watching, and human management; never let it touch routing.** The next document
([`path-model.md`](path-model.md)) makes that invariant precise.

---

## Primary sources

- ZooKeeper Programmer's Guide — ZNodes, ACLs (*"An ACL pertains only to the znode … it does not apply to
  children … ACLs are not recursive"*), Watches:
  <https://zookeeper.apache.org/doc/current/zookeeperProgrammers.html>
- ZooKeeper persistent recursive watches (`addWatch`, `AddWatchMode`), ZOOKEEPER-1416 (3.6.0):
  <https://issues.apache.org/jira/browse/ZOOKEEPER-1416>
- Apache Curator Framework — namespace-rooting (`.namespace(...)`), fluent path API:
  <https://curator.apache.org/docs/framework/>
- HashiCorp Vault — Policies (capabilities `create/read/update/delete/list/sudo/deny`; `deny` precedence;
  `*`/`+` path globs): <https://developer.hashicorp.com/vault/docs/concepts/policies>
- etcd — Role-based access control (users → roles → range/prefix `read`/`write`/`readwrite`):
  <https://etcd.io/docs/v3.3/op-guide/authentication/>
- Configd ADR-0017 (Namespace Multi-Tenancy), ADR-0020 (Prefix-Based Subscription Model),
  ADR-multiraft-cross-shard, and the source files cited inline in §0.

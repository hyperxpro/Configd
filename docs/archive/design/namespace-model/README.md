# Hierarchical path / namespace model -- design and first RFC section

Design work from 2026-06-28, docs-only, no production code (a compile-checked type sketch under
[`sketch/`](sketch/) is a design artifact, not wiring). This is a design plus recommendation plus the
first RFC section, not a build.

The operator's decisions were settled and this designs to them: Configd's config model is hierarchical and
path-based (ZooKeeper / Apache Curator / HashiCorp Vault-secret-path style), not flat keys; read/write ACLs
are live and a WATCH verb is needed; and the driver-protocol RFC is written alongside as decisions land --
this work produces its first section. The built model at the time was a flat, hash-sharded keyspace
(`String key` + `ConfigScope`) with per-prefix ACLs; the hierarchical namespace model (ADR-0017) had been
designed but never built (paper only). This directory designs the real path model plus the ACL extension
and drafts the RFC section the wiring conforms to.

## The central invariant (read this first)

> **Hierarchy is LOGICAL; routing stays hash-the-full-path.** The path tree is a logical overlay for
> **authorization, watch scoping, listing, and management**. Physical placement is and remains
> `shardFor(scope, fullPath)` -- a hash of the **entire** path string -- so a subtree (`/team-payments/**`)
> **scatters across all N shards**. A path prefix **never** selects a shard. Every subtree operation
> (grant / list / watch) is a **logical scatter-gather** over the hash-distributed keyspace.

Get this right → ZooKeeper's usability **plus** Configd's horizontal scale. Conflate them (route by
prefix) → one tenant's subtree collapses onto one shard → hot shard → the even distribution sharding
exists to provide is lost. This is the load-bearing constraint; it is preserved in every section.

## Read in this order

1. **[`prior-art.md`](prior-art.md)** -- ZooKeeper (path tree, **non-inherited** per-znode ACLs, persistent
   recursive watches), Curator (fluent path API, **namespace-rooting/chroot**), Vault (**path-glob
   policies**, **list-vs-read**, **deny-precedence**), etcd (**flat storage + prefix-as-hierarchy + range
   RBAC**) -- mechanisms extracted and mapped to Configd, plus the **ADR-0017 reconciliation** (§5: what is
   superseded and why).
2. **[`path-model.md`](path-model.md)** -- the path syntax; the **scope/path/namespace relationship**
   (scope = orthogonal typed axis; namespace = top subtree by convention); **INV-PATH** (§2); subtree =
   scatter-gather; the **list-children design + cost** over the HAMT; **compatibility with the built flat
   keyspace** (N=1 byte-identity at the storage layer -- no migration, no `shardFor` change).
3. **[`access-control.md`](access-control.md)** -- the recommended ACL model (**roles → policies →
   principals**, Vault-shaped); **subtree grants** + **union-of-ancestors + deny-precedence** composition
   (superseding the built longest-match-only); the **capability set** `{READ, LIST, WRITE, WATCH, ADMIN}`
   (**LIST ⊥ READ**, **WATCH requires READ**); and the **normative watch-authorization contract** that
   closes the `full_chain_verify` full-store bypass the watch research left open.
4. **[`../../rfc/driver-protocol/01-paths-and-access.md`](../../../rfc/driver-protocol/01-paths-and-access.md)** -- the **first RFC section**, normative (MUST/SHOULD/MAY), rigorous enough that drivers in any language
   implement path handling + authz identically; built to **compose with the watch RFC section** to follow.
5. **[`decision-log.md`](decision-log.md)** -- methodology + the analytical decisions (DL-N-01…N-13) and
   the open items the operator must confirm.
6. **[`sketch/`](sketch/)** -- a compile-checked (JDK 25) type sketch of the load-bearing logic (path
   normalization, union+deny evaluation, the watch-authz contract); `java -ea … SketchSmokeTest` ⇒
   `SKETCH OK`.

## The answer in six sentences

A Configd address is **`(scope, path)`**: `scope` stays the typed replication-domain axis, and `path` is a
slash-delimited hierarchy that is the **logical** structure for ACLs, watches, listing, and management -- but **`shardFor` hashes the whole path**, so a subtree **scatters across all shards** and every subtree
operation is a **scatter-gather** (the central invariant). It is a **pure superset of the built flat
keyspace**: the store/hash/Raft see only an opaque string, so there is **no storage migration, no
`shardFor` change, and N=1 stays byte-identical**; a legacy flat key is a degenerate single-segment path.
Authorization becomes **Vault-shaped path-glob policies bound through roles**, with **subtree grants**,
**union-of-ancestors + absolute deny-precedence** (superseding the built longest-match-only), and a
capability set **`{READ, LIST, WRITE, WATCH, ADMIN}`** where **LIST is distinct from READ** and **WATCH
requires READ** (a watch can never expose what a read could not). The **watch-authz contract** is now
normative: a watch is authorized **at subscription as a streaming read**, an over-broad target is
**rejected (not silently filtered)**, and the **`full_chain_verify`/full-store watch requires a root-scope
grant** -- closing the bypass where a subtree-scoped principal could pull the whole store's signed chain.
Multi-tenancy (ADR-0017's goal) falls out of **subtree ACLs + per-principal rate limiting** with **no
namespace-routing**, so ADR-0017's Raft-group affinity is superseded by INV-PATH.

## What the operator must confirm (before wiring)

The scope/path/namespace relationship (scope orthogonal vs in-path) · roles/policies vs per-principal ACLs
· the capability set (incl. LIST distinct, WATCH-requires-READ) · accepting union+deny supersedes the
built longest-match-only (a flagged, test-visible ACL change) · the watch-authz contract · `list` cost
posture (scatter-gather scan first, ordered index later) · no recursive delete at first. Full list:
[`decision-log.md`](decision-log.md) §"Open items".

## What wires next (each conforming to the RFC section)

**namespaces** (path validation + scope-through-the-API + the policy/role model + LIST) → **the fan-out
drain / driver protocol** (the binary client surface the RFC specifies) → **watches** (the watch RFC
section, riding the edge plane, authorized by §6 here). None of it moves a key or changes routing.

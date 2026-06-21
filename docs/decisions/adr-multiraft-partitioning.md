# ADR (multi-Raft, D-A): Partition the Control Plane by HASH-within-Scope, Not Range

## Status

**Proposed** (Session M1, 2026-06-21). Not yet Accepted — awaits operator sign-off.
Part of the multi-Raft arc (`docs/multiraft/recommendation-summary.md`). Pairs with
`adr-multiraft-topology.md` (D-B), `adr-multiraft-cross-shard.md` (D-C), `adr-throughput-target.md`.
Evidence: `docs/multiraft/prior-art.md`, `docs/multiraft/configd-analysis.md`.

## Context

Moving the write path to multiple Raft groups (shards) requires a partitioning scheme: a deterministic
function from a key to the shard (Raft group) that owns it. The classic choice is **HASH** (keys
hashed into shards — even spread, hot-shard-resistant, but no ordered/prefix range scans) vs **RANGE**
(ordered key-ranges — supports prefix/range scans, but prone to hot-shard skew and needs split/merge).

The decision is driven by **Configd's read pattern**, which is verified, not assumed:

- **Edge reads are point lookups by key** against an in-process lock-free HAMT (getHit ~483 ns,
  getMiss ~12 ns at 100M keys; `docs/session-7.5/scale-read-gc.md`). There are **no range/scan reads**
  at the edge or in storage.
- The only prefix-shaped access is **edge subscription** (ADR-0020): edges subscribe to key prefixes
  (`/service/api/*`) matched by a **radix trie at the distribution node**, which *filters the fan-out
  event stream*. This is a fan-out concern, not a consensus-routing concern.

The write seam already exists: `ConfigWriteService.propose(ConfigScope scope, cmd)` ("determine the
Raft group by key scope") and `MultiRaftDriver.propose(int groupId, cmd)` — today both collapse to
`DEFAULT_RAFT_GROUP=0`. `ConfigScope = {GLOBAL, REGIONAL, LOCAL}` already encodes latency/topology
tiers (ADR-0030); namespaces already pin to groups for tenant isolation (ADR-0017).

## Decision

**Shard key = `hash(namespace_id, full_key) mod N_scope`**, computed *within* the existing
`ConfigScope` tier.

- **`ConfigScope` is the tier selector**, not the shard key: it picks the *pool* of Raft groups (and
  voter topology / region) for a key. Hash spreads keys evenly across the groups *in that pool*.
- **A write routes** as: `propose(scope, cmd)` → scope selects the pool → `hash(namespace, key) mod N`
  selects the group within the pool → propose to that `RaftNode` via `MultiRaftDriver`. This replaces
  the constant `0` at the existing seam. **No edge-plane or wire change.**
- **Fan-out is unchanged**: the distribution node already ingests every shard's committed delta stream
  and localizes each per-key delta to subscribed edges via its radix trie. Under hash, the trie's
  *input* is N committed streams instead of 1; the trie logic and per-edge output are byte-for-byte
  identical, because the prefix→edge mapping lives in the trie, never in the shard layout.

## Rationale

1. **Read-path penalty is zero — the decisive fact.** RANGE exists to keep adjacent keys co-located so
   *ordered/prefix range-scans* touch few shards (TiKV: Range "can better aggregate keys with the same
   prefix, which is convenient for operations like scan"; CockroachDB serves ordered SQL scans).
   Configd issues **no range scans anywhere**. So RANGE's entire benefit column is inapplicable, while
   its entire cost column (hot-shard skew) is retained. You would pay for a feature you never use.
2. **Hot-shard skew is the real axis, and hash wins it.** Config keys are hierarchical and
   deployment-correlated: a rollout writes `/team-x/regional/feature.*` in a burst; popular namespaces
   cluster. RANGE places that burst on **one contiguous shard** — the exact single-range hotspot
   CockroachDB documents (load-based splitting "making things worse" on write-heavy sequential load;
   it cannot split a hot boundary point). That is the heartbeat-starvation collapse Configd already
   measured at ~1000/s — RANGE would reintroduce single-group saturation *even with N groups
   configured*. Hash disperses the burst across all N by construction. For a write-throughput fix,
   dispersing the write hotspot **is the entire point**.
3. **The prefix-subscription "crux" dissolves.** The worry — hash scatters a prefix across all N
   shards, so fan-out must merge N streams — is answered by the actual data flow: the distribution
   node is a single union fan-out tier that **already** ingests every shard's stream and **already**
   merges per-key deltas through the radix trie (O(key_length) per event, source-shard-agnostic). Hash
   adds no new component; it changes only the number of inbound stream connections on an internal,
   non-consensus tier already sized for "the full event stream." RANGE's contiguity buys nothing,
   because the trie — not shard adjacency — localizes a prefix to its edges.
4. **Hash breaks nothing the contract promises.** A given key always hashes to the same group, so all
   writes to that key are totally ordered by that group's sequence (single-key linearizability
   preserved). Per-group monotonic sequence + gap detection (ADR-0004) are *already* per-group and
   designed for a prefix's events to arrive interleaved from multiple groups. Cross-group order is
   already N/A in the contract; hash only forfeits a cross-prefix global order the contract never
   offered (and RANGE wouldn't deliver either — still N groups, N sequences).
5. **Self-balances at the 10⁹-key ceiling with zero operator babysitting** (the generic-OSS bias).
   A good hash over `(namespace, key)` keeps expected keys/shard = 10⁹/N with shrinking relative skew,
   with **zero data movement on key creation** (Slicer: "create new keys without Slicer on the critical
   path"). RANGE at 10⁹ keys needs continuous split-merge to chase the moving hotspot and still cannot
   split a sequential burst point.

## Prior-Art Mechanism Borrowed

**Google Slicer (OSDI '16)** — hash the application key to a slice key, then range-partition the
*hashed* space: "clusters of hot keys in the application's keyspace are uniformly distributed in the
hashed keyspace," and "an application can create new keys without Slicer on the critical path." This
gives hot-key dispersion *and* zero-coordination key creation, and — critically — preserves cheap
split/merge on the **hashed** axis (an axis that cannot hotspot) as a future D-B hook. Secondary:
**Amazon Dynamo / Cassandra** consistent hashing (bounded 1/N rebalancing on N change); **CockroachDB
hash-sharded indexes** as the cautionary inverse (they retrofit hashing precisely to kill sequential
hotspots). Configd is in the **etcd / Dynamo / Slicer** world (point access, no scans), not the
TiKV / Cockroach / Spanner world (ordered SQL/KV scans). See `prior-art.md` §1–§2, L2.

## Rejected Alternatives

- **RANGE / key-prefix-range.** Reintroduces single-shard hotspots on sequential/bursty prefixes (the
  measured ~1000/s collapse mode), buys zero read benefit absent range scans, and *forces* early
  dynamic resharding (coupling D-A→D-B toward complexity). Rejected.
- **`ConfigScope` as the shard key.** Only 3 buckets; GLOBAL/REGIONAL each collapse to one group and
  re-hit the ~1000/s wall — this is *today's broken state*. Scope is a tier, not a shard key. Rejected.
- **Plain `hash(key)`** (no scope). Discards scope-tiered latency routing (ADR-0030) and namespace
  affinity (ADR-0017). Acceptable only as a fallback if scope-tiering is descoped. Rejected as default.

## Consequences

- **Positive:** throughput scales ~linearly with N; no hotspot babysitting; tenant isolation
  (ADR-0017) and scope routing (ADR-0030) preserved by composition; key creation needs no coordination
  at the 10⁹ ceiling.
- **Negative / accepted:** a prefix's keys scatter across all N shards → fan-out ingests N committed
  streams (**not "no new component"** — the live fan-out is single-source today, `ConfigdServer.java:492`,
  so N shards need an **N-way merge/sequencer in front of the bounded `FanOutBuffer`** and add a
  cross-shard drop-amplification mode; Red-Team) and
  WAL-replay catch-up uses N cursors; there is no cross-prefix global order (the contract already
  disclaims it — ADR-0004); **cheap meta-only split/merge is forfeited** unless the Slicer
  hash-space-range / consistent-hashing mechanism is adopted (deferred to D-B).
- **Single hot *key*** (one key = one group, unsplittable by hashing) is rare for config, is equally
  unsplittable under RANGE, and is a per-key remediation (read replica/cache), not a partitioning
  defect.

## Red-Team Critique (surviving)

- **"Hash forecloses cheap elastic split/merge — TiKV's headline RANGE advantage (meta-only split,
  no data move)."** *Surviving and acknowledged.* Rebuttal that bounds it, not erases it: (1) borrow
  Slicer's hash-space-ranges so split/merge operates on the *hashed* axis and stays cheap *without*
  reintroducing key-locality hotspots; (2) hash prevents the *dominant* resharding trigger
  (write-throughput skew), so Configd reshards far less than a range system chasing hotspots; (3)
  fixed-N hash + consistent-hashing/vnodes bounds rebalancing to ~1/N keys on N change. Net: this
  narrows the D-A↔D-B boundary (how elastic must topology be — Open Q1) but does not overturn hash.
- **"Prefix-filtered *snapshots* (ADR-0020 bootstrap) must now read all N shards."** *Minor.* A
  prefix-filtered bootstrap snapshot is served from the regional replica's full HAMT (ADR-0020 tier
  table), already shard-agnostic; only WAL-replay catch-up touches multiple shards, bounded by N
  cursors.
- **"Hash adds no new component" is false for the live fan-out tier.** *Surviving (verified).* Fan-out is
  single-source today (one apply thread → `fanOutBuffer.publish`, `ConfigdServer.java:492`). N shards = N
  owner threads writing one bounded, drop-oldest buffer → an **N-way merge/sequencer in front of fan-out**
  is required, plus a new cross-shard drop-amplification mode (a hot shard evicts another shard's deltas).
  Costed, not free.
- **Cross-shard READ composition is a real per-subscriber regression.** *Surviving.* A prefix subscription
  under hash needs **O(N) per-shard cursors per subscriber** (each shard's slice gap-tracked
  independently; one shard's gap stalls its slice), vs O(1) under RANGE. "Byte-for-byte identical
  per-edge output" hides that the *cursor bookkeeping* is N×.
- **Namespace-in-hash breaks ADR-0017 tenant locality; the rebalance story is unbuilt.** *Surviving.*
  `hash(namespace,key)` scatters a tenant across all N shards, so tenant isolation needs a *dedicated
  pool*, not pinning — making Open Q2 (spread vs pin) **load-bearing, not confirm-later**. And
  `StaticShardMap` has `epoch=0`/no vnodes, so "bounded 1/N movement on N change" is **aspirational** — an
  N change today is a full keyspace rehash (Slicer hash-space-ranges / consistent-hashing is deferred to
  D-B Open Q1). None overturns hash; each must be costed.

## Verification Extension (extend, do not replace)

1. **Sim** (`RaftSimulation`/`SimulatedNetwork`): parameterize over `N ∈ {1,4,8,16}` with a **hash
   router**; replay a bursty single-prefix deployment workload (e.g. 100k/s into one prefix); assert
   per-group write rate ≤ the measured ~800/s knee and that no group hits the heartbeat-starvation
   point — i.e. *prove hash flattens the burst that sinks RANGE*; add a RANGE router as a negative
   control that reproduces the hotspot.
2. **configd-linz (Porcupine):** assert single-key linearizability holds across the hash router (same
   key → same group) and per-group monotonic gap-detection under multi-group interleaving for a
   subscribed prefix; assert cross-group reads remain unordered (do not over-assert a global order the
   contract denies).
3. **Chaos matrix:** election/group loss on a subset of shards while a prefix subscription spans all N
   → assert the edge sees a partial gap on exactly the failed group's sequence (not a whole-prefix
   stall) and recovers via that group's catch-up; add a hash-rebalance N→N+1 event and bound key
   movement to ~1/N.

## Open Questions for the Operator

1. **Fixed-N or elastic?** If fixed-N hash is acceptable for the horizon, D-B simplifies dramatically.
   If elasticity is required, do you accept Slicer hash-space-ranges / consistent-hashing-with-vnodes
   as the rebalancing mechanism? (The single knob that moves the D-A↔D-B boundary.)
2. **Namespace pinning vs spread:** default to spreading all namespaces across the shared pool (max
   balance) with opt-in dedicated pools (ADR-0017) for high-value/noisy tenants — confirm.
3. **Hash input = `(namespace, full_key)`** (disperses per-tenant bursts), vs bare key — confirm.
4. **Scope→pool cardinality:** how many groups per scope tier at launch, and may `N_scope` differ per
   tier?

## Related

ADR-0020 (prefix subscription), ADR-0017 (namespace multi-tenancy), ADR-0004 (per-group sequence),
ADR-0030 (scope-tiered topology), ADR-0023 (multi-raft deferred). `prior-art.md` (Slicer/Dynamo/TiKV),
`configd-analysis.md` §6.

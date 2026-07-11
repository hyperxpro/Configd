# ADR: Partition the Control Plane by Hash-within-Scope, Not Range

## Status

Accepted. The hash-within-scope scheme described here is what shipped: it is wired into the server and
exercised in simulation and on real hardware. The default is a single Raft group (N=1); an operator turns
on multiple shards for horizontal scale by setting `configd.raft.shardCount` above 1 - that is an
operating mode available today, not a future version. Pairs with
[adr-multiraft-topology](adr-multiraft-topology.md), [adr-multiraft-cross-shard](adr-multiraft-cross-shard.md),
and [adr-throughput-target](adr-throughput-target.md).

This decision supersedes the "Raft group affinity" idea in
[ADR-0017](adr-0017-namespace-multi-tenancy.md): keys are routed by hashing the full path within a scope,
not by pinning a namespace to a shard group. Pinning a whole namespace would collapse that tenant onto a
single owner thread and defeat even distribution.

## Context

Moving the write path to multiple Raft groups (shards) requires a partitioning scheme: a deterministic
function from a key to the shard (Raft group) that owns it. The classic choice is hash (keys hashed into
shards - even spread, hot-shard-resistant, but no ordered/prefix range scans) vs range (ordered
key-ranges - supports prefix/range scans, but prone to hot-shard skew and needs split/merge).

The decision is driven by Configd's read pattern, which is verified, not assumed:

- **Edge reads are point lookups by key** against an in-process lock-free HAMT (getHit ~483 ns,
  getMiss ~12 ns at 100M keys; measured on hardware). There are **no range/scan reads**
  at the edge or in storage.
- The only prefix-shaped access is **edge subscription** (ADR-0020): edges subscribe to key prefixes
  (`/service/api/*`) matched by a **radix trie at the distribution node**, which *filters the fan-out
  event stream*. This is a fan-out concern, not a consensus-routing concern.

The write seam already exists: `ConfigWriteService.propose(ConfigScope scope, cmd)` ("determine the
Raft group by key scope") and `MultiRaftDriver.propose(int groupId, cmd)` - today both collapse to
`DEFAULT_RAFT_GROUP=0`. `ConfigScope = {GLOBAL, REGIONAL, LOCAL}` already encodes latency/topology
tiers (ADR-0030); namespaces already pin to groups for tenant isolation (ADR-0017).

## Decision

**Shard key = `hash(namespace_id, full_key) mod N_scope`**, computed *within* the existing
`ConfigScope` tier.

- **`ConfigScope` is the tier selector**, not the shard key: it picks the *pool* of Raft groups (and
  voter topology / region) for a key. Hash spreads keys evenly across the groups *in that pool*.
- **A write routes** as: `propose(scope, cmd)` -> scope selects the pool -> `hash(namespace, key) mod N`
  selects the group within the pool -> propose to that `RaftNode` via `MultiRaftDriver`. This replaces
  the constant `0` at the existing seam. **No edge-plane or wire change.**
- **Fan-out is per shard.** Each shard gets its own `FanOutBuffer` and compactor (built as
  `ConfigdServer.ShardedFanOut`), so a shard's committed stream feeds its own buffer on its own owner
  thread; there is no merged, cross-shard stream. A watch subscription tracks a per-shard cursor vector,
  and the prefix -> edge mapping lives entirely in the radix trie, which is source-shard-agnostic.

## Rationale

1. **Read-path penalty is zero - the decisive fact.** RANGE exists to keep adjacent keys co-located so
   *ordered/prefix range-scans* touch few shards (TiKV: Range "can better aggregate keys with the same
   prefix, which is convenient for operations like scan"; CockroachDB serves ordered SQL scans).
   Configd issues **no range scans anywhere**. So RANGE's entire benefit column is inapplicable, while
   its entire cost column (hot-shard skew) is retained. You would pay for a feature you never use.
2. **Hot-shard skew is the real axis, and hash wins it.** Config keys are hierarchical and
   deployment-correlated: a rollout writes `/team-x/regional/feature.*` in a burst; popular namespaces
   cluster. RANGE places that burst on **one contiguous shard** - the exact single-range hotspot
   CockroachDB documents (load-based splitting "making things worse" on write-heavy sequential load;
   it cannot split a hot boundary point). That is the heartbeat-starvation collapse Configd measured at
   ~1000/s single-group - RANGE would reintroduce single-group saturation *even with N groups
   configured*. Hash disperses the burst across all N by construction. For a write-throughput fix,
   dispersing the write hotspot **is the entire point**.
3. **The prefix-subscription concern dissolves.** The worry - hash scatters a prefix across all N
   shards, so fan-out must merge N streams - is answered by keeping fan-out per shard rather than
   merged: the distribution node localizes each shard's stream through the radix trie
   (O(key_length) per event, source-shard-agnostic) without ever combining shards into one stream. Hash
   adds cursor-tracking cost (a subscriber now tracks N per-shard cursors instead of one), but no new
   fan-out component; RANGE's contiguity buys nothing here either, because the trie - not shard
   adjacency - localizes a prefix to its edges.
4. **Hash breaks nothing the contract promises.** A given key always hashes to the same group, so all
   writes to that key are totally ordered by that group's sequence (single-key linearizability
   preserved). Per-group monotonic sequence + gap detection (ADR-0004) are *already* per-group and
   designed for a prefix's events to arrive interleaved from multiple groups. Cross-group order is
   already not offered by the contract; hash only forfeits a cross-prefix global order the contract
   never offered (and RANGE wouldn't deliver either - still N groups, N sequences).
5. **Self-balances at the 10^9-key ceiling with zero operator babysitting.**
   A good hash over `(namespace, key)` keeps expected keys/shard = 10^9/N with shrinking relative skew,
   with **zero data movement on key creation** (Slicer: "create new keys without Slicer on the critical
   path"). RANGE at 10^9 keys needs continuous split-merge to chase the moving hotspot and still cannot
   split a sequential burst point.

## Prior-Art Mechanism Borrowed

**Google Slicer (OSDI '16)** - hash the application key to a slice key, then range-partition the
*hashed* space: "clusters of hot keys in the application's keyspace are uniformly distributed in the
hashed keyspace," and "an application can create new keys without Slicer on the critical path." This
gives hot-key dispersion *and* zero-coordination key creation, and - critically - preserves cheap
split/merge on the **hashed** axis (an axis that cannot hotspot) as a future hook if dynamic resharding
is ever built. Secondary: **Amazon Dynamo / Cassandra** consistent hashing (bounded 1/N rebalancing on N
change); **CockroachDB hash-sharded indexes** as the cautionary inverse (they retrofit hashing precisely
to kill sequential hotspots). Configd is in the **etcd / Dynamo / Slicer** world (point access, no
scans), not the TiKV / Cockroach / Spanner world (ordered SQL/KV scans).

## Rejected Alternatives

- **RANGE / key-prefix-range.** Reintroduces single-shard hotspots on sequential/bursty prefixes (the
  measured ~1000/s collapse mode), buys zero read benefit absent range scans, and *forces* dynamic
  resharding to be built early to chase the moving hotspot. Rejected.
- **`ConfigScope` as the shard key.** Only 3 buckets; GLOBAL/REGIONAL each collapse to one group and
  re-hit the ~1000/s wall - that was the pre-sharding broken state. Scope is a tier, not a shard key.
  Rejected.
- **Plain `hash(key)`** (no scope). Discards scope-tiered latency routing (ADR-0030) and namespace
  affinity (ADR-0017). Acceptable only as a fallback if scope-tiering is descoped. Rejected as default.

## Consequences

- **Positive:** throughput scales ~linearly with N; no hotspot babysitting; tenant isolation
  (ADR-0017) and scope routing (ADR-0030) preserved by composition; key creation needs no coordination
  at the 10^9 ceiling.
- **Negative / accepted:** a prefix's keys scatter across all N shards, so a subscriber tracks N
  per-shard cursors instead of one, and WAL-replay catch-up uses N cursors; there is no cross-prefix
  global order (the contract already disclaims this - ADR-0004). Cheap meta-only split/merge is
  forfeited unless a Slicer-style hash-space-range or consistent-hashing mechanism is adopted - that is
  not built; see [adr-multiraft-topology](adr-multiraft-topology.md) for the static-shard-count decision
  and its seam for a future dynamic implementation.
- **A single hot *key*** (one key = one group, unsplittable by hashing) is rare for config, is equally
  unsplittable under RANGE, and is a per-key remediation (read replica/cache), not a partitioning
  defect.

## Known limitations

- **"Hash forecloses cheap elastic split/merge - TiKV's headline RANGE advantage (meta-only split, no
  data move)."** Acknowledged, bounded rather than erased: (1) a Slicer-style hash-space-range scheme
  would let split/merge operate on the *hashed* axis and stay cheap without reintroducing key-locality
  hotspots, if built; (2) hash prevents the *dominant* resharding trigger (write-throughput skew), so
  Configd reshards far less than a range system chasing hotspots; (3) fixed-N hash plus
  consistent-hashing/vnodes would bound rebalancing to ~1/N keys on N change, if built. Net: this narrows
  how elastic the topology needs to be, but does not overturn hash.
- **"Prefix-filtered *snapshots* (ADR-0020 bootstrap) must now read all N shards."** Minor: a
  prefix-filtered bootstrap snapshot is served from the regional replica's full HAMT (ADR-0020 tier
  table), already shard-agnostic; only WAL-replay catch-up touches multiple shards, bounded by N
  cursors.
- **Cross-shard READ composition is a real per-subscriber cost.** A prefix subscription under hash needs
  O(N) per-shard cursors per subscriber (each shard's slice gap-tracked independently; one shard's gap
  stalls only its own slice). This is how the shipped watch protocol works: per-key and per-shard order,
  never global, with a per-shard cursor vector - the cost is real and documented, not hidden behind
  "byte-for-byte identical per-edge output."
- **Namespace-in-hash and ADR-0017 tenant locality.** `hash(namespace,key)` scatters a tenant across all
  N shards, so tenant isolation needs a dedicated pool, not pinning - this makes the spread-vs-pin
  question (below) load-bearing, not something to confirm later. `StaticShardMap` reads its epoch from
  the deploy-time `TopologyDescriptor` rather than hardcoding it, but an N change is still a full
  keyspace rehash today - a Slicer hash-space-range or consistent-hashing scheme for bounded
  rebalancing on N change is not built.

## Verification Extension

1. **Sim** (`RaftSimulation`/`SimulatedNetwork`): parameterize over `N  in  {1,4,8,16}` with a **hash
   router**; replay a bursty single-prefix deployment workload (e.g. 100k/s into one prefix); assert
   per-group write rate <= the measured ~800/s knee and that no group hits the heartbeat-starvation
   point - i.e. *prove hash flattens the burst that sinks RANGE*; add a RANGE router as a negative
   control that reproduces the hotspot.
2. **configd-linz (Porcupine):** assert single-key linearizability holds across the hash router (same
   key -> same group) and per-group monotonic gap-detection under multi-group interleaving for a
   subscribed prefix; assert cross-group reads remain unordered (do not over-assert a global order the
   contract denies).
3. **Chaos matrix:** election/group loss on a subset of shards while a prefix subscription spans all N
   -> assert the edge sees a partial gap on exactly the failed group's sequence (not a whole-prefix
   stall) and recovers via that group's catch-up; add a hash-rebalance N->N+1 event and bound key
   movement to ~1/N.

## Open Questions for the Operator

1. **Fixed-N or elastic?** If fixed-N hash is acceptable for the horizon, the topology model stays
   simple. If elasticity is required, is a Slicer-style hash-space-range or consistent-hashing-with-vnodes
   scheme worth building as the rebalancing mechanism? (The single knob that decides how much topology
   work is left to do.)
2. **Namespace pinning vs spread:** default to spreading all namespaces across the shared pool (max
   balance) with opt-in dedicated pools (ADR-0017) for high-value/noisy tenants - confirm.
3. **Hash input = `(namespace, full_key)`** (disperses per-tenant bursts), vs bare key - confirm.
4. **Scope->pool cardinality:** how many groups per scope tier at launch, and may `N_scope` differ per
   tier?

## Related

ADR-0020 (prefix subscription), ADR-0017 (namespace multi-tenancy), ADR-0004 (per-group sequence),
ADR-0030 (scope-tiered topology), ADR-0023 (multi-raft, superseded by this arc).

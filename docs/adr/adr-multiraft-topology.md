# ADR: Static-N Shards Behind a `ShardMap` Seam; Dynamic Resharding Is Not Built; the Single-Tick-Thread Fix Is a Co-Delivery Prerequisite

## Status

Accepted. This is the design as built: static-N Raft shards behind a `ShardMap` routing seam, shipped
together with the single-tick-thread fix (coalesced heartbeats and a sharded owner-executor pool), which
are both built and wired on `main` (`HeartbeatCoalescer`, `CoalescingRaftTransport`, `OwnerExecutorPool`).
Online dynamic resharding (split/merge/rebalance) is not built; the `ShardMap` interface leaves a seam
for it, but it is a deliberate scope choice, not a scheduled future release. Pairs with
[adr-multiraft-partitioning](adr-multiraft-partitioning.md), [adr-multiraft-cross-shard](adr-multiraft-cross-shard.md),
and [adr-throughput-target](adr-throughput-target.md).

## Context

Two topology questions: (1) static-N (fixed shard count chosen at deploy; no online
split/merge/rebalance) vs dynamic (shards split when hot/large, merge when cold, rebalance online - the
full TiKV/CockroachDB model). (2) The threading model under which N groups run - which is *not* a free
variable, because Configd's measured single-group ceiling (~800/s) is **heartbeat starvation on one tick
thread**, and a naive `MultiRaftDriver.tick()` that ticks all groups on one thread makes this worse as N
grows.

The threading analysis proves: **naive N-group multi-Raft on a single-threaded driver would be strictly
worse than the single group we started with** (aggregate <= 800/s shared N ways ~ 50/s/shard at N=16,
with each group's heartbeat slip able to trigger an election). So this decision covers both the
shard-count policy and mandates the threading fix that makes any N>1 safe.

## Decision

**Configd ships static-N Raft shards** (N is a deploy-time constant, capped at 16 - see Known
limitations; do not treat 16 as a target throughput number before a dedicated-host re-measure of the
per-shard knee) behind a thin `ShardMap` routing indirection. Online split/merge/rebalance is not built;
the `ShardMap` seam leaves room for a dynamic implementation to be swapped in later without a rewrite of
callers, but that is not part of what ships today. **The single-tick-thread fix is co-delivered**
(coalesced heartbeats + a sharded tick-executor pool + per-tick broadcast-coalescing) - without it,
multi-Raft would be a regression.

### The `ShardMap` abstraction (the static/dynamic seam)

```
interface ShardMap {
    int shardFor(ConfigScope scope, String key);  // routing: stable key -> groupId
    IntStream shardIds();                          // membership: which groups exist
    long epoch();                                  // monotonic; bumps on any split/merge/rebalance
}
```

- **The `StaticShardMap` that ships today:** `shardFor = hash(scope, key) mod N` (see the partitioning
  decision); `shardIds = [0..N)`; `epoch` is read from the deploy-time `TopologyDescriptor` and does not
  change while the deployment's shard count is stable. Online resharding is not built. N is a deploy-time
  constant, identical on all nodes.
- **A dynamic shard map is not built.** If one is added later, the interface stays the same: `shardFor`
  would consult a versioned table that placement logic mutates on split/merge/rebalance, and `epoch`
  would bump on every change. Swapping the implementation would be the entire routing delta for a future
  dynamic mode - no caller changes.

### Three invariants that keep a future dynamic mode additive (not a rewrite)

1. **Opaque, stable shard IDs.** `groupId` is an identity, never a source of behavior - no
   `groupId == 0` special-casing, no "GLOBAL is always group 0." A split must be able to mint a brand-new
   ID without disturbing siblings. (This is the one thing early "-> group 0" code got wrong for the
   future, and it has been cleaned up as sharding was wired.)
2. **Routing is always `ShardMap.shardFor(...)`, never an inlined `mod N`.** The day a caller hardcodes
   `mod 16`, a future dynamic router becomes a rewrite.
3. **An epoch / membership-version field on the routing/wire envelope.** Carrying `epoch()` lets a stale
   router that sends a key to a shard that split be told "wrong epoch, re-resolve" instead of
   mis-committing - the same shape as TiKV's `RegionEpoch{ConfVer,Version}` plus error-driven
   client-cache-update, cheap routing correctness with no transaction machinery. This was a wire-format
   change, not a free reservation: `FrameCodec`'s header carries an explicit 8-byte reserved epoch field
   (`HEADER_SIZE=26`, `WIRE_VERSION=0x02`), currently must-be-zero and CI-enforced as such. The field is
   reserved and the wire version was bumped once, deliberately, so a future dynamic mode can activate it
   without another wire break.

### The co-delivery prerequisite (the threading fix)

- **Coalesced heartbeats** - one heartbeat message *per peer-node per tick* carrying all co-located
  groups' beats. Collapses idle heartbeat traffic from `40.N`/s to `40`/s/peer-pair, constant in N.
  Built (`HeartbeatCoalescer`, `CoalescingRaftTransport`). *(CockroachDB coalesced heartbeats; TiKV merged
  store heartbeats #5620.)*
- **Sharded tick-executor pool** (each group owner-executed via `ownerExecutor(shardId) =
  pool[shardId % poolSize]`). Built (`OwnerExecutorPool`, wired through `MultiRaftDriver` and
  `ConfigdServer`). This raises the aggregate ceiling toward ~`pool x knee` instead of one thread shared
  N ways. *(CockroachDB MultiRaft: "a small, constant number of goroutines (currently 3) instead of one
  goroutine per range"; TiKV raftstore worker pool.)*
- **Per-tick broadcast-coalescing for real (non-heartbeat) replication traffic** is a separate, narrower
  question: `RaftNode.propose()` still broadcasts `AppendEntries` inline per proposal, which is correct
  for real log replication (a proposal should go out immediately, not wait for the next tick) - the
  coalescing that matters for the N-scaling story is the heartbeat path above, which is built.
- **The marshalling boundary.** The sharded pool only stays safe because
  `ownerExecutor(shardId) = pool[shardId % poolSize]` and every path touching a shard's `RaftNode` runs
  on that shard's owner thread: tick, inbound `routeMessage` (demuxed by `groupId` before dispatch),
  `propose`, commit-callback, group-commit `flush`, `maybeCompact`, ReadIndex `completeRead`, and the
  non-volatile `commitIndex`/`lastApplied` metric reads. This is the per-shard synchronization model
  `docs/architecture/raft-threading-contract.md` documents; building it out from the earlier
  single-tick-executor design was consensus-core work, not a drop-in pool swap.
- **Hibernation is not built and is not planned as a default.** At N~16 with coalesced heartbeats the
  idle cost is already flat in N, and TiKV #34906 showed hibernation can cause 20-minute leaderless
  failover windows - unacceptable against the write-availability target. If ever adopted (for much
  larger N), it would need to be paired with proactive health-driven wake.

### Shard count

`N = ceil( target / (per-group stable knee x efficiency) )`. With target 10k/s, knee ~800/s (measured,
co-location-confounded), efficiency ~0.75: `10000 / (800 x 0.75) ~ 16.7`. The shard count is capped at 16
in the server (`configd.raft.shardCount` must be in `[1, 16]`) - a power-of-two ceiling that keeps
`hash mod N` clean and gives headroom against the hottest shard. N is a deploy-time constant precisely so
a higher dedicated-host knee can move the operator's chosen N without a reshard-rewrite; in practice,
roughly ten or eleven busy leaders saturate a 16-vCPU box, so 16 is a ceiling operators approach rather
than a number every deployment runs at.

## Rationale

- **Static-N delivers both target wins** - throughput (~ pool x knee) and blast-radius containment (a
  churning shard contains 1/N of writes, not all) - at minimal surface, reusing the existing
  `propose(scope)` / `propose(int groupId)` seams.
- **The threading fix is prerequisite *regardless* of static-vs-dynamic.** Shipping dynamic resharding
  from day one would add the entire online-resharding machinery (placement, split/merge state machines,
  epoch invalidation, rebalance-under-fault) *on top of* the prerequisite, for **zero** extra
  throughput - dynamic only redistributes load, it does not raise the ceiling.
- **Dynamic split/merge is the richest production-bug vein in the prior-art survey**: CRDB
  split-nemesis Jepsen inconsistency, the 20.2 closed-timestamp-past-subsumption follower-read
  corruption, over-aggressive quiescing, TiKV tombstone-region panics / raw split keys /
  hotspot-outruns-split / meta-corruption-under-nemesis. A generic config plane gains little from online
  auto-split and would inherit this entire class of bugs.
- **The seam gives a future dynamic mode for free, if it's ever needed.** Static-N with the three
  invariants *becomes* dynamic by swapping `StaticShardMap -> DynamicShardMap`; there is no rewrite tax
  being deferred, only feature work that has not been undertaken because nothing has demanded it yet.

## Prior-Art Mechanism Borrowed

- **Threading:** CockroachDB **MultiRaft scheduler** (small fixed worker pool, not one goroutine per
  range) + **coalesced heartbeats** (per peer-node per tick); TiKV **raftstore** worker pool - the exact
  antidote to the single-thread ceiling, and the model `MultiRaftDriver`'s own documentation cites.
- **Routing correctness:** TiKV **`RegionEpoch{ConfVer,Version}`** + error-driven client-cache update.
- **A possible future dynamic path:** Spanner **`movedir`** (background bulk move + tiny atomic
  Raft-committed cutover) - the lowest-disruption repartition, preferable to TiKV/CRDB online split if a
  dynamic mode is ever built.
- **Avoided:** TiKV **Hibernate Region** as a default (failover foot-gun #34906).

## Rejected Alternatives

- **Dynamic resharding in the first ship.** Rejected: (1) no incremental throughput over static-N - both
  top out at pool x knee; dynamic only redistributes; (2) online split is the single hardest,
  most-bug-prone operation in multi-Raft (atomically divide a key range, hand part of the log/snapshot to
  a new group, keep linearizability across the boundary mid-split, survive faults during the split) -
  months of work plus a combinatorial fault matrix, gating the throughput win; (3) reachable later via
  the seam - nothing is foreclosed.
- **Naive multi-Raft on a single tick thread** (no threading fix). Rejected: strictly worse than the
  status quo (the threading analysis proves this).
- **Hibernation as a default.** Rejected for now (TiKV #34906 failover risk; the benefit it targets is
  already captured by coalesced heartbeats at N~16).

## Consequences

- (+) The aggregate ceiling is raised toward ~ pool x knee with the coalesced-heartbeat and
  owner-executor-pool mechanisms now built; a *churning* shard contains 1/N of writes (but under *node
  loss* on a 3-node deploy the blast radius is 1/3, not 1/N - see below); a future dynamic mode would be
  a `StaticShardMap -> DynamicShardMap` swap with no *caller* changes (the wire epoch field is already
  reserved for it).
- (-) A hot shard cannot be auto-split (mitigated: the hash partitioner spreads prefix-skew;
  per-principal rate limiting already exists; N-headroom; an interim manual-reshard runbook is still
  owed - see below).
- (-) Node add/remove requires a manual reshard, and absent vnodes or a `movedir`-style mechanism,
  "manual reshard" is a full-keyspace rehash with no online-move tooling - plausibly a downtime
  re-bootstrap of up to 10^9 keys. A documented, tested reshard procedure has not been written yet.
- (-) A node leading many shards loses them all on crash, which can trigger a correlated election storm
  across its led shards; coalesced heartbeats and per-shard staggered election timeouts mitigate this,
  and it needs dedicated chaos testing (see Verification Extension).
- (-) N WALs/snapshots per node means a linear memory/fsync working-set growth (fine at <=32; watch at
  >=64).
- (-) Per-shard observability (per-shard election/apply-lag series, a leader-count-per-node view) is
  required for the correlated-election-storm risk above to be visible; this has since shipped (see
  `raft_node_leader_count`, `raft_shard_leader_<gid>` and related per-shard metrics in
  `docs/operations/known-limitations.md`).
- (-) Reconciliation with ADR-0030 ("avoided a PlacementDriver / scope-aware routing"): static-N needs
  only a deterministic shard map, not a dynamic PlacementDriver, so the operational complexity ADR-0030
  rejected is not re-imported by this decision - it would be if a dynamic mode were ever built.

## Known limitations

- **A generic config system has an unpredictable, skewed keyspace. With static-N you get a single hot
  shard (the 99th-pct tenant, or one hot prefix) that exceeds ~800/s alone. You can't auto-split it, so
  that tenant is throttled to one group's ceiling while other shards idle, and the aggregate target
  assumes uniform load.** This is the real limit of static-N, stated honestly. Mitigations: (1) hash
  partitioning turns hot-*prefix* skew into per-key spread across shards (see
  [adr-multiraft-partitioning](adr-multiraft-partitioning.md)); (2) per-principal rate limiting (already
  in `ConfigWriteService`) stops one tenant starving others; (3) N is chosen with headroom; (4) the seam
  keeps a future dynamic mode cheap, and a manual-reshard runbook would cover the interim once written.
  A single hot **key** is unsplittable by *any* sharding scheme, dynamic included, so dynamic resharding
  would not rescue that pathological case either - what's deferred here is online adaptivity, not
  correctness.
- **A node leading many shards loses them all at once on crash or partition, so blast radius is
  per-node, not per-shard, for that failure mode.** Handled as a distinct chaos scenario (correlated
  leadership loss); coalesced heartbeats and staggered per-shard election timeouts bound the resulting
  election storm. Blast-radius-per-shard holds for partition of one shard's quorum; node loss is a
  separate, and more common, scenario that needs its own chaos coverage.
- **Blast radius on a 3-node deploy is 1/3, not 1/N.** Each node leads ~5-6 of 16 shards; a node crash
  removes ~1/3 of write capacity until re-election - the common fault. 1/N holds only for the rarer
  single-shard-quorum partition. The throughput aggregate during a node outage is `(2/3) x N x knee`
  (carried into [adr-throughput-target](adr-throughput-target.md)).
- **N~16 rests on a co-location-confounded ~800/s knee.** N is a deploy-time constant, re-derivable once
  a dedicated-host re-measure exists; that re-measure has not been done as a clean, isolated single-host
  test (a related cross-machine measurement exists - see
  `docs/archive/measurement/ec2-horizontal-2026-07-01/` - but it changes the network topology at the same
  time, so it does not cleanly isolate the co-location effect). Do not treat 16 as validated throughput
  headroom; treat it as the current hard ceiling.
- **The interim manual reshard would be a downtime re-bootstrap.** Absent vnodes or a `movedir`-style
  tool, a reshard rehashes the keyspace and moves ~(N-1)/N of ownership with no online-move mechanism. A
  documented, tested procedure for this has not been written.

## Verification Extension

- **Sim:** instantiate N groups under one driver; assert per-tick heartbeat-emit latency stays under the
  election floor as N scales; seed cross-group proposal mixes.
- **TLA+:** routing-correctness invariant - a key resolves to exactly one live shard under a given epoch;
  for a future dynamic mode, a split/merge refinement proving no key is dropped or double-owned across an
  epoch change (extends `ConsensusSpec`/`SnapshotInstallSpec`).
- **configd-linz:** per-key registers unchanged; add the cross-shard router; assert per-key
  linearizability with N groups (no cross-group order asserted - the model encodes that).
- **Chaos / fault matrix:** (a) correlated leadership loss (kill a node leading many shards -> no
  aggregate election storm); (b) routing under reconfiguration (stale `epoch` sheds/redirects, never
  mis-routes); (c) per-shard partition (blast radius is exactly 1/N - the containment claim); (d) if a
  dynamic mode is ever built: split/merge under partition / fsync-lie / ENOSPC (the hardest cell).

## Open Questions for the Operator

1. **Is online dynamic resharding worth building?** Recommendation: no, absent a concrete need - the
   `ShardMap` seam keeps it cheap to add later. Building it now would land the rejected-alternative cost
   (an online split state machine and its fault matrix) for no throughput gain.
2. **Is the per-group knee really ~800/s on a *dedicated* host?** N depends on it; the measurement flags
   a co-location confound that a one-node-per-host re-measure would resolve. Not yet done.
3. **Do GLOBAL/REGIONAL/LOCAL map to *distinct* shard pools, or is `ConfigScope` orthogonal to
   sharding?** Cleanest model: scope selects a pool, `hash mod N_pool` selects within. Confirm whether
   scope-isolation is a requirement.
4. **Is `429`-shedding a single hot tenant (vs auto-splitting it) an acceptable steady state?**

## Related

ADR-0009 (single-I/O-thread store pattern), ADR-0023 (multi-raft - reserved the concept, superseded by
this arc), ADR-0030 (centralized root; operational-simplicity axis),
[adr-multiraft-partitioning](adr-multiraft-partitioning.md) (hash),
[adr-throughput-target](adr-throughput-target.md) (N derivation),
`docs/architecture/raft-threading-contract.md` (the owner-executor model as built), the measured ceiling
(see `docs/measurement/` and `docs/archive/measurement/`).

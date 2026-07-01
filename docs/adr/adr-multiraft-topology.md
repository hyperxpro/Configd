# ADR (multi-Raft, D-B): Static-N Shards Behind a `ShardMap` Seam; Dynamic Resharding Deferred to v2; the Single-Tick-Thread Fix is a Co-Delivery Prerequisite

## Status

**Proposed** (Session M1, 2026-06-21). Not yet Accepted — awaits operator sign-off.
Part of the multi-Raft arc. Pairs with `adr-multiraft-partitioning.md` (D-A),
`adr-multiraft-cross-shard.md` (D-C), `adr-throughput-target.md`. Evidence:
`docs/multiraft/prior-art.md`, `docs/multiraft/configd-analysis.md` (esp. §3, the heartbeat model).

## Context

Two topology questions: (1) **STATIC-N** (fixed shard count chosen at deploy; no online
split/merge/rebalance) vs **DYNAMIC** (shards split when hot/large, merge when cold, rebalance online —
the full TiKV/CockroachDB model). (2) **The threading model** under which N groups run — which is *not*
a free variable, because Configd's measured single-group ceiling (~800/s, S7.5) is **heartbeat
starvation on one tick thread**, and `MultiRaftDriver.tick()` ticks all groups on one thread
(`MultiRaftDriver.java:100`; `ConfigdServer.java:367` single-thread `tickExecutor`).

`configd-analysis.md` §3 proves: **naive N-group multi-Raft on today's single-threaded driver is
strictly worse than the single group we have** (aggregate ≤ 800/s shared N ways ≈ 50/s/shard at N=16,
with each group's heartbeat slip able to trigger an election). So D-B must decide both the shard-count
policy *and* mandate the threading fix that makes any N>1 safe.

## Decision

**Ship v1 with STATIC-N Raft shards (N ≈ 16, **deploy-derived — see Red-Team; do not bake "16" before
the dedicated-host knee re-measure**) behind a thin `ShardMap` routing indirection. Defer online split/merge/rebalance to v2 as a drop-in `ShardMap` implementation swap.
Mandatorily co-deliver the single-tick-thread fix** (coalesced heartbeats + a small sharded
tick-executor pool + per-tick broadcast-coalescing) — without it, multi-Raft is a regression.

### The `ShardMap` abstraction (the v1/v2 seam)

```
interface ShardMap {
    int shardFor(ConfigScope scope, String key);  // routing: stable key -> groupId
    IntStream shardIds();                          // membership: which groups exist
    long epoch();                                  // monotonic; bumps on any split/merge/rebalance
}
```

- **v1 `StaticShardMap`:** `shardFor = hash(scope, key) mod N` (D-A); `shardIds = [0..N)`; `epoch = 0`
  forever. Online resharding is OUT. N is a deploy-time constant, identical on all nodes.
- **v2 `DynamicShardMap` (slot):** same interface; `shardFor` consults a versioned table that
  PD-style placement mutates on split/merge/rebalance; `epoch` bumps on every change. Swapping the
  implementation is the *entire* v2 routing delta — no caller changes.

### Three v1 invariants that make v2 additive (not a rewrite)

1. **Opaque, stable shard IDs.** `groupId` is an identity, never a source of behavior — **no
   `groupId == 0` special-casing, no "GLOBAL is always group 0."** A split must mint a brand-new ID
   without disturbing siblings. *(This is the one thing today's "→ group 0" code does wrong for the
   future.)*
2. **Routing is always `ShardMap.shardFor(...)`, never an inlined `mod N`.** The day a caller hardcodes
   `mod 16`, v2 dynamic routing becomes a rewrite.
3. **An epoch / membership-version field on the routing/wire envelope.** Carry `epoch()` so a stale
   router that sends a key to a shard that split is told "wrong epoch, re-resolve" instead of
   mis-committing. v1 never bumps it; v2 depends on it existing. This is the **TiKV
   `RegionEpoch{ConfVer,Version}` + error-driven client-cache-update** pattern (prior-art L4) — cheap
   routing correctness with *no* transaction machinery. **Red-team caveat (verified): this is a
   WIRE-FORMAT BREAK, not a free reservation.** `FrameCodec` (HEADER_SIZE=18 = length+version+type+
   `groupId`+term; `WIRE_VERSION=0x01`; header changes fail the wire-compat CI) has **no epoch field and
   no reserved bytes**. So v1 must either reserve the epoch field and bump `WIRE_VERSION` **once,
   deliberately, now** (a cost paid in v1 for a field it does not yet bump), or accept that v2 breaks the
   wire. The clean seam is the `ShardMap` *interface*; the *wire* seam is not reserved — do not claim "no
   wire change in v2" without reserving epoch now.

### The co-delivery prerequisite (the threading fix — `configd-analysis.md` §3.5/§3.7)

- **(i) Coalesced heartbeats** — one heartbeat message *per peer-node per tick* carrying all
  co-located groups' beats. Collapses idle heartbeat traffic from `40·N`/s to `40`/s/peer-pair,
  **constant in N**. *(CockroachDB coalesced heartbeats; TiKV merged store heartbeats #5620.)*
  **Prerequisite.**
- **(iii) Sharded tick-executor pool** (2–4 threads, each owning `shardId % poolSize`) **+ per-tick
  broadcast-coalescing** (broadcast per tick, not per propose — the RR-113 lever, **recommended in
  S7.5 §E.2 but UNBUILT/unvalidated today**: `RaftNode.propose():460` broadcasts inline per-proposal and
  no coalesced heartbeat exists). *Projects* the aggregate ceiling to ≈ `pool × knee` instead of one
  thread shared N ways (a projection over unbuilt mechanisms, to be proven on hardware). *(CockroachDB MultiRaft:
  "a small, constant number of goroutines (currently 3) instead of one goroutine per range"; TiKV
  raftstore worker pool.)* **Prerequisite.**
- **The marshalling boundary (R-01-critical, verified).** The sharded pool only stays safe if
  **`ownerExecutor(shardId) = pool[shardId % poolSize]`** and *every* path touching a shard's `RaftNode`
  runs on that shard's owner thread: `tick`, inbound `routeMessage` (demuxed by `groupId` *before*
  dispatch), `propose`, commit-callback, group-commit `flush`, `maybeCompact`, ReadIndex `completeRead`,
  and the non-volatile `commitIndex/lastApplied` metric reads. Today the single `tickExecutor` *is* the
  synchronization for the non-synchronized `RaftNode` (R-01, `ConfigdServer.java:362-365`); the pool
  replaces it and must rebuild this per-shard, **re-opening the S2–S4 integration-verification surface**.
  This is partly-greenfield consensus-core work — see Red-Team and `configd-analysis.md` §3.8.
- **(ii) Hibernation is OUT for v1** (see Rejected/Red-Team): at N≈16 with coalesced heartbeats the
  idle cost is already flat in N, and TiKV #34906 shows hibernation can cause 20-minute leaderless
  failover windows — unacceptable against the 99.999% write SLO. If ever adopted (large N), it MUST be
  paired with proactive health-driven wake.

### Shard count (coordinate with `adr-throughput-target.md`)

`N = ceil( target / (per-group stable knee × efficiency) )`. With target 10k/s, knee ~800/s (S7.5
measured, co-location-confounded), efficiency ~0.75: `10000 / (800 × 0.75) ≈ 16.7`. **Recommend
N = 16** (power-of-two for clean `hash mod N`; ~5–6 led shards/node on 3 nodes; headroom for the
hottest shard). N is a deploy-time constant precisely so a higher dedicated-host knee can drop N
without a reshard-rewrite.

## Rationale

- **Static-N delivers both target wins** — throughput (≈ pool×knee) and blast-radius containment (a
  churning shard contains 1/N of writes, not all) — at minimal surface, reusing the existing
  `propose(scope)` / `propose(int groupId)` seams.
- **The threading fix is prerequisite *regardless* of static-vs-dynamic.** Shipping dynamic-from-day-one
  adds the entire online-resharding machinery (placement, split/merge state machines, epoch
  invalidation, rebalance-under-fault) *on top of* the prerequisite, for **zero** extra throughput —
  dynamic only redistributes load, it does not raise the ceiling.
- **Dynamic split/merge is the richest production-bug vein in the survey** (prior-art L3): CRDB
  split-nemesis Jepsen inconsistency, the 20.2 closed-timestamp-past-subsumption follower-read
  corruption, over-aggressive quiescing, TiKV tombstone-region panics / raw split keys /
  hotspot-outruns-split / meta-corruption-under-nemesis. A generic OSS config plane gains little from
  online auto-split and inherits this entire class.
- **The seam gives the v2 option for free.** Static-N with the three invariants *becomes* dynamic by
  swapping `StaticShardMap → DynamicShardMap`; there is no rewrite tax being deferred, only feature
  work. This is exactly the charter's "simplest design with dynamic as a clean future seam."

## Prior-Art Mechanism Borrowed

- **Threading:** CockroachDB **MultiRaft scheduler** (small fixed worker pool, not one goroutine per
  range) + **coalesced heartbeats** (per peer-node per tick); TiKV **raftstore** worker pool. The exact
  antidote to the single-thread ceiling — and the "store pattern" `MultiRaftDriver`'s javadoc already
  cites.
- **Routing correctness:** TiKV **`RegionEpoch{ConfVer,Version}`** + error-driven client-cache update.
- **Deferred dynamic path:** Spanner **`movedir`** (background bulk move + tiny atomic Raft-committed
  cutover) — the lowest-disruption repartition, preferred over TiKV/CRDB online split when v2 lands.
- **Avoided:** TiKV **Hibernate Region** as a v1 default (failover foot-gun #34906). See
  `prior-art.md` L1, L3, §1–§3.

## Rejected Alternatives

- **Dynamic-from-v1 (online split/merge/rebalance in the first ship).** Rejected: (1) no incremental
  throughput over static-N — both top out at pool×knee; dynamic only redistributes; (2) online split
  is the single hardest, most-bug-prone operation in multi-Raft (atomically divide a key range, hand
  part of the log/snapshot to a new group, keep linearizability across the boundary mid-split, survive
  faults during the split) — months of work + a combinatorial fault matrix gating the throughput win;
  (3) contradicts the charter's simplest-thing bias; (4) reachable later via the seam — nothing is
  foreclosed.
- **Naive multi-Raft on the existing single tick thread** (no threading fix). Rejected: strictly worse
  than the status quo (`configd-analysis.md` §3.3).
- **Hibernation in v1.** Rejected for v1 (TiKV #34906 failover risk; benefit already captured by
  coalesced heartbeats at N≈16).

## Consequences

- (+) Aggregate ceiling *projects* to ≈ pool×knee (the coalesced-HB/broadcast levers are unbuilt — see
  Red-Team); a *churning* shard contains 1/N of writes (**but under *node loss* on a 3-node deploy the
  blast radius is 1/3, not 1/N** — Red-Team); v2 dynamic = swap `StaticShardMap → DynamicShardMap`, no
  *caller* changes (the *wire* needs an epoch field — Red-Team).
- (−) A hot shard cannot be auto-split (mitigated: D-A hash partitioner spreads prefix-skew;
  per-principal rate limiting already exists; N-headroom; an interim manual-reshard runbook).
- (−) Node add/remove ⇒ manual reshard in v1 — and absent vnodes/`movedir`, "manual reshard" is a
  **full-keyspace rehash with no online-move tooling, i.e. plausibly a downtime re-bootstrap** of up to
  10⁹ keys (Red-Team). A documented, tested reshard procedure is a v1 deliverable.
- (−) **NEW failure mode:** a node leading many shards loses them all on crash → correlated election
  storm across its led shards (must be tested; coalesced heartbeats + per-shard *staggered* election
  timeouts mitigate).
- (−) N WALs/snapshots per node ⇒ linear memory/fsync working-set growth (fine ≤32; watch ≥64).
- (−) **Day-2 per-shard observability is a v1 deliverable (Red-Team):** today's health metrics
  (`raft_elections`, `pendingApply`, `fanout.buffer.dropped`) are **group-0-only** (`ConfigdServer.java:779`);
  N shards need per-shard election/apply-lag series + a leader-count-per-node view, or the
  correlated-election-storm this ADR introduces is invisible.
- (−) Reconciliation with ADR-0030 Reasoning #4 ("avoided a PlacementDriver / scope-aware routing"):
  static-N needs only a deterministic shard-map, **not** a dynamic PlacementDriver, so the operational
  complexity ADR-0030 rejected is *not* re-imported in v1 (it would be in v2 — another reason to defer).

## Red-Team Critique (surviving)

- **"A generic config system has an unpredictable, skewed keyspace. With static-N you get a single hot
  shard (the 99th-pct tenant, or one hot prefix) that exceeds ~800/s alone. You can't auto-split it, so
  that tenant is throttled to one group's ceiling while N−1 shards idle, and your aggregate 10k/s is a
  fiction that assumes uniform load."** *Surviving — the real limit of static-N, stated honestly.*
  Rebuttal: (1) D-A **hash** turns hot-*prefix* skew into per-key spread across shards; (2) per-principal
  rate limiting (already in `ConfigWriteService`) stops one tenant starving others; (3) pick N with
  headroom; (4) the seam makes the v2 escape cheap + a manual-reshard runbook covers the interim. And:
  a single hot **key** is unsplittable by *any* sharding (dynamic included), so dynamic does not rescue
  the genuine pathological case. We defer *online adaptivity*, not correctness.
- **"A node leading many shards loses them ALL at once on crash/partition → blast radius is per-node,
  not per-shard."** *Surviving.* Handled as a NEW chaos cell (correlated leadership loss); coalesced
  heartbeats + staggered per-shard election timeouts bound the election storm. Blast-radius-per-shard
  holds for *partition of one shard's quorum*; node loss is a distinct, tested scenario.
- **[#1 — the single largest hidden cost] The sharded tick-pool re-opens R-01.** *Surviving (verified).*
  The post-R-01 server makes the non-synchronized `RaftNode` safe by funnelling **all** access onto the
  **single** `tickExecutor` (`ConfigdServer.java:362-365`). A sharded pool DELETES that guarantee; the
  prerequisite is `ownerExecutor(shardId)=pool[shardId%poolSize]` with every shard-touching path on its
  owner (Decision → co-delivery), which **re-opens the S2–S4 integration-verification surface** (the
  concurrent tick+inbound+propose+flush stress test `STATE-OF-REALITY` §6.1 flags as missing) and must
  precede shard-routing. Partly-greenfield consensus-core work, not a drop-in pool.
- **The coalesced-HB and per-tick-broadcast levers are UNBUILT.** *Surviving (verified).*
  `RaftNode.propose():460` broadcasts inline per-proposal; heartbeats are per-peer; S7.5 validated only
  admission control. So `aggregate ≈ pool×knee` is a **projection over unbuilt code**, and the throughput
  target is a measurement plan, not a result (`adr-throughput-target.md`). Coalesced-HB assembly also
  crosses pool threads (reads every co-located group's state each tick) and one lost coalesced frame
  de-livens all N co-located groups at once — it *causes* the correlated-election-storm cell, not only
  node loss.
- **Blast radius on a 3-node deploy is 1/3, not 1/N.** *Surviving (verified).* Each node leads ~5–6 of 16
  shards; a node crash removes ~1/3 of write capacity until re-election — the *common* fault. 1/N holds
  only for the rare single-shard-quorum partition. The throughput aggregate is `(2/3)N×knee` during a
  node outage (carried into `adr-throughput-target.md`).
- **The epoch field is a WIRE-FORMAT BREAK** (seam invariant #3, verified): `FrameCodec` HEADER_SIZE=18,
  no epoch, no reserved bytes, WIRE_VERSION-gated. Reserve+bump now or drop the "no wire change in v2"
  claim; the clean seam is the `ShardMap` interface, not the wire.
- **N≈16 is deploy-derived, doubly forward-referential.** It rests on a co-location-confounded ~800/s
  knee AND the (unbuilt) threading model meant to raise that knee. N is a deploy-time constant,
  re-derived from the dedicated-host re-measure; do not bake "16" prematurely.
- **The interim manual reshard is a downtime re-bootstrap.** Absent vnodes/`movedir`, a reshard rehashes
  the keyspace and moves ~(N−1)/N of ownership with no online-move tool. A documented, tested procedure
  is a v1 deliverable — state the cost out loud.
- **Day-2 per-shard observability is a v1 deliverable.** Today's metrics are group-0-only
  (`ConfigdServer.java:779`); without per-shard election/lag/leader-distribution series the
  correlated-election-storm this ADR introduces is unobservable.

## Verification Extension (extend, do not replace)

- **Sim:** instantiate N groups under one driver; **amplification guard** — assert per-tick
  heartbeat-emit latency stays under the election floor as N scales (the §3 guarantee); seed
  cross-group proposal mixes.
- **TLA+:** routing-correctness invariant — a key resolves to exactly one live shard under a given
  epoch; for v2, a split/merge **refinement** proving no key is dropped or double-owned across an epoch
  change (extends `ConsensusSpec`/`SnapshotInstallSpec`).
- **configd-linz:** per-key registers unchanged; add the cross-shard router; assert per-key
  linearizability with N groups (no cross-group order asserted — the model encodes that).
- **Chaos / fault matrix (NEW cells static-N introduces):** (a) correlated leadership loss (kill a node
  leading many shards → no aggregate election storm); (b) routing under reconfiguration (stale `epoch`
  sheds/redirects, never mis-routes); (c) per-shard partition (blast radius is exactly 1/N — the
  containment claim); (d) v2 only: split/merge under partition / fsync-lie / ENOSPC (the hardest cell).

## Open Questions for the Operator

1. **SCOPE-DEFINING (flagged): is online dynamic resharding IN or OUT of v1?** Recommendation: **OUT**
   (v2 extension via the seam). If IN, the rejected-alternative cost (online split state machine + its
   fault matrix) lands in v1 and gates the throughput win.
2. **Is the per-group knee really ~800/s on a *dedicated* host?** N (baked into v1 deploys) depends on
   it; S7.5 flags co-location confound. A one-node-per-host re-measure before freezing N is recommended
   (mitigated by N being a deploy-time constant).
3. **Do GLOBAL/REGIONAL/LOCAL map to *distinct* shard pools, or is `ConfigScope` orthogonal to
   sharding?** Cleanest model: scope selects a pool, `hash mod N_pool` selects within. Confirm whether
   scope-isolation is a v1 requirement.
4. **Is `429`-shedding a single hot tenant (vs auto-splitting it) acceptable for v1?**

## Related

ADR-0009 (single-I/O-thread store pattern), ADR-0023 (multi-raft deferred — reserved the concept),
ADR-0030 (centralized root; operational-simplicity axis), `adr-multiraft-partitioning.md` (hash),
`adr-throughput-target.md` (N derivation), `docs/session-7.5/throughput-part2.md` (the measured
ceiling), `configd-analysis.md` §3–§5.

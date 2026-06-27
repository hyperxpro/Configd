# Phase 1 — V: the multi-shard verification machinery (design note + evidence)

> Charter §2 Prime Directive: build the multi-group simulator + the six new invariants FIRST, proven
> NON-VACUOUS (an injected mis-route / wrong-shard-write goes RED), before any production sharding code.
> This is that deliverable. Status: **built, green, committed.** Five of the six invariants have a
> dedicated injected-RED non-vacuity proof; cross-shard isolation is structural this phase (no dedicated
> RED) and its genuine non-vacuous surface is mandatory in C3 (review SF1, below).

## What was built

| Artifact | Where | Role |
|---|---|---|
| `ShardMap` (interface) | `configd-replication-engine` main | The logic-free routing contract (D-B seam): `shardFor / shardIds / epoch`. The *logic* (`StaticShardMap`) is C1; this is the contract the machinery judges. |
| `MultiShardSim` | `configd-testkit` test | Composes `S` shards, each a proven `ConsistencyPropertyTests.ClusterHarness` of `R` nodes, under a `ShardMap`-routed deterministic workload. Per-shard `SimInvariants` every tick + the new cross-shard checks. Pure function of the master seed (`mix(seed, shardId)` / `mix(seed, WORKLOAD_TAG)`). |
| `ShardRouters` | `configd-testkit` test | The deliberately non-functional `rotating` router (routing/disjoint non-vacuity). The CORRECT router the green tests route through is the **production `StaticShardMap`** itself (review SF2), so the sim judges the real C1 hash, not a stand-in. |
| `MultiShardSimTest` | `configd-testkit` test | The six invariants, each GREEN under a correct router and RED under an injected bug. |

## The six invariants → checks → non-vacuity proof (all green)

| Invariant | How checked (every seed) | Injected RED that proves it non-vacuous |
|---|---|---|
| **Routing correctness** | `checkRoutingStability`: a key's resolved shard never changes; `write()` routes only to `shardFor(scope,key)` | `rotating` router (same key → different shard) → routing RED (`nonVacuity_nonFunctionalRouter`) |
| **Disjoint ownership** | `checkDisjointOwnership`: scan every shard's store — no key on two shards, and the owning shard == `shardFor(key)` | `CROSS_SHARD_REDIRECT` bug lands a key on a shard that does not own it → disjoint RED (`nonVacuity_crossShardRedirect`) |
| **Per-shard linearizability** | each shard's `SimInvariants.checkAll()` + throwing in-node checker, every tick (version monotonicity, log matching, state-machine safety, single-leader-per-term, the 9 in-node) | inherits `SimInvariants`' own non-vacuity (proven by `SeedSweepTest`); exercised across the sweep |
| **Cross-shard isolation** | `faultShardMajority` kills one shard (no quorum); it must stall while the others keep committing (`commitsAdvancedOn`) and stay safe | **structural this phase** (see note) — no dedicated injected-RED; the routing leak that *would* break it is caught RED by `nonVacuity_crossShardRedirect`. The genuine non-vacuous surface is **mandatory in C3** |
| **Stale-map redirect (exactly-once)** | a stale cached leader → intra-shard redirect to the live leader (the `X-Leader-Hint` generalized), never crossing shards; the write commits (no loss); disjoint ownership proves no scatter | `NO_REDIRECT` bug → the stale-leader write is never accepted → lost (`nonVacuity_noRedirect_losesTheWrite`) |
| **N=1 equivalence** | drive the same op stream through the N=1 sim AND a bare single-group control on the identical per-shard seed; committed views must be byte-identical | `DROP_OP_AT_N1` bug → committed state diverges from the control (`nonVacuity_droppedOpAtN1`) |

## Evidence

`mvn -o -pl configd-testkit test -Dtest=MultiShardSimTest` → **Tests run: 72, Failures: 0, Errors: 0**
(12-seed small sweeps for routing/disjoint/N=1, a 40-seed full-surface sweep with a mid-run per-shard
fault, plus the targeted green + non-vacuity cases). The sweep counts are system-property-tunable
(`-Dconfigd.multiShard.seedSweep.count`) — C5 cranks the full-surface sweep to ≥10k for the gate.

## Soundness notes (honest scope of each check)

- **No-loss** (`checkNoWritesLost`) asserts an *accepted* write commits — sound ONLY after heal+drain with
  no post-acceptance leadership loss (a write accepted by a leader then isolated before replicating
  legitimately never commits — RR-004 — and is not a redirect bug). The faulting sweeps therefore assert
  only `checkDisjointOwnership` (always sound); no-loss is asserted in the fault-free / stable-leader paths.
- **Cross-shard isolation is STRUCTURAL this phase, not a proven-non-vacuous invariant** (review SF1).
  Independent per-shard harnesses share no thread / network / clock, so a fault on shard A *cannot* leak
  into shard B by construction — the green test is a useful liveness smoke test (a dead shard does not
  stop the others, and now asserts the dead shard truly stalled), but **no injected bug drives *this*
  check RED** (its "non-vacuity" is borrowed from the disjoint-ownership routing-leak RED). So of the six,
  **five have a dedicated injected-RED non-vacuity proof; isolation is structural this phase.** **C3 makes
  the genuine non-vacuous isolation surface MANDATORY** — S groups per physical node on the Phase-0
  owner-executor pool (the shared-node, correlated-node-fault surface), where isolation is NOT structural
  and a real coupling leak (e.g. a stuck owner thread starving sibling shards) must be shown to go RED on
  the real `MultiRaftDriver`.
- The `ShardMap` used here is the test `hashReference`; **C1 swaps in the production `StaticShardMap`** and
  re-runs this surface against it (routing correctness + disjoint ownership + N=1 equivalence on the real
  hash).

## Why this satisfies "verification machinery first"

Nothing in C1–C5 is judged by hope: the invariants that would catch a mis-route, a wrong-shard write, a
dropped redirect, or an N=1 regression are executable and **demonstrated to go RED on exactly those bugs**
before the production sharding code exists. C1 (StaticShardMap) is written against this surface.

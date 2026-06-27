# Multi-Raft Phase 1 — Design Note (the sharding-layer build)

> Phase 1 builds the **static-N sharding layer** on the Phase 0 owner-executor foundation, per the M1
> ADRs (`adr-multiraft-{partitioning,topology,cross-shard}.md`). Architecture is SETTLED (charter §1);
> this note records HOW it is implemented and verified, not WHETHER. Verification machinery is built
> FIRST (charter §2). Decisions: `decision-log.md`. Real-hardware aggregate-throughput validation is the
> NEXT, operator-gated session (charter §5) — Phase 1 stops at "built + sim-verified".

## 1. The foundation Phase 1 sits on (Phase 0, mainline `c58ac1f`)

- **`MultiRaftDriver` is group-parametric** — `propose(int gid, cmd)`, `routeMessage(int gid, msg)`,
  `addGroup(gid, node)`, `ownerExecutor(gid)`, `currentOwnerIndex(gid)` (= `floorMod(gid, poolSize)`
  unless a rehoming override exists; rehoming is DORMANT). The driver needs no change to accept N groups.
- **`RaftNode` single-owner safety** — `assertOwnerThread()` guards every O entry point; `monitorView()`
  is the safe cross-thread snapshot. The owner pool `pool[gid % poolSize]` is the marshalling boundary.
- **Coalesced heartbeats** — `CoalescingRaftTransport` + per-owner `HeartbeatCoalescer` +
  `tickOwner` drain; flat-in-N, proven in-sim. The *wire* frame for N>1 is deferred (DL-P1-05).
- **Deterministic sim** — `AdversarialSim implements ClusterView` (one Raft group, R nodes, fault
  schedule, workload) + `SimInvariants` (cross-node safety: single-leader-per-term, version
  monotonicity, log-matching, state-machine-safety) + the in-node `InvariantChecker` (9 checks).
  `ConsistencyPropertyTests.ClusterHarness` drives linearizability. 20,001-seed sweeps are green.

## 2. The three production seams (mapped against live HEAD this session)

### 2.1 Propose / routing seam — where `ShardMap` plugs in
- `ConfigWriteService.put/delete(key, value, ConfigScope scope, principal)` →
  `RaftProposer.propose(ConfigScope scope, byte[] command)` (the **key is NOT visible** at the proposer
  boundary — it is already inside `command`). `ConfigScope = {GLOBAL, REGIONAL, LOCAL}`, hardcoded
  `GLOBAL` in prod. **No `namespace` concept on the write path** — the key is a flat UTF-8 string.
- The proposer lambda (`ConfigdServer.raftProposer`, ~L1309) **captures `groupId=0` AND group-0's owner
  executor** — structurally single-group-bound. Sharding requires: invoke `shardFor(scope, key)` where
  both are live (`ConfigWriteService.put/delete`, L237/L266) **or** widen the SPI to carry the key/groupId;
  re-resolve `ownerExecutor(gid)` per call; loop `addGroup` over the shard set (`ConfigdServer:367`).
- **BATCH is codec-only** (`CommandCodec.TYPE_BATCH=0x03`, `encodeBatch`, `decode()→Batch.mutations()→key()`),
  NO HTTP endpoint. The cross-shard guard enumerates a BATCH's keys via `decode()` and rejects >1 distinct shard.

### 2.2 Wire / codec seam
- `FrameCodec`: `HEADER_SIZE=18` (`len4|ver1|type1|groupId4(int32)|term8`), `WIRE_VERSION=0x01`, **fully
  packed, no reserved bytes**. Double-pinned: golden-bytes unit test + a CI `wire-compat` job that fails
  if `GoldenFixtures` changes without a `WIRE_VERSION` bump. `MessageType` uses `0x01..0x10`; `0x11` free;
  **no** `RAFT_COALESCED_HEARTBEAT` type.
- **`RaftTransportAdapter` two latent N>1 bugs** (DL-P1-06, fixed in C3): inbound drops `frame.groupId()`
  and routes to the captured constant `0` (`ConfigdServer:1217`); outbound stamps the adapter's
  construction-time constant `0` (`RaftTransportAdapter:52`). The groupId is *already in the frame* — the
  fix threads it through; **no wire-format change**.
- **Epoch reservation + CoalescedHeartbeat wire frame** = wire-format breaks (`WIRE_VERSION` bump) —
  DEFERRED to the operator/EC2 gate (DL-P1-04/05).

### 2.3 Observability / gate seam
- Raft scrape is **group-0-only**: `ConfigdServer:883-901` `if (owner==0) { getGroup(0).monitorView() →
  one AtomicLong pendingApply + one raftElections counter }`. `MetricsRegistry` is **NOT tag-capable**
  (flat string names; the in-repo convention for a dimension is name-encoding `base.<id>`). `RaftMetrics`
  already carries per-group term/role/commitIndex/lastApplied/lag and is safe off-owner via `monitorView()`.
- Gate: `gates/gate-phase0.sh` + `gate-B.sh`, cumulative via the `needs:` DAG in `ci.yml` + in-script
  self-skip flags. `gate-phase1.sh` slots in with `needs: gate-B` (fast PR step + nightly FULL step).

## 3. The sharding model (M1 ADRs, implemented)

```
interface ShardMap {                          // configd-replication-engine, io.configd.replication
    int shardFor(ConfigScope scope, String key);  // routing: stable key -> opaque groupId
    java.util.stream.IntStream shardIds();         // membership: which groups exist
    long epoch();                                  // monotonic; v1 StaticShardMap returns 0 forever
}
```
- **`StaticShardMap`**: `shardFor = hash(scope, key) mod N_scope` (D-A hash-within-scope). Opaque, stable
  shard IDs — **no `groupId==0` special-casing**, no inlined `mod N` at any caller (D-B invariants 1–2).
  `epoch()==0` forever (invariant 3, in-memory only this phase — DL-P1-04).
- **Default N=1 below threshold** (D-C): a single shard ⇒ today's whole-keyspace behavior, byte-identical.
  Sharding engages only when configured N>1.
- **Hash input** `(scope, key)`: there is no namespace on the write path yet; the ADR's
  `hash(namespace, key)` is realized as `hash(scope, key)` (scope is the only tier dimension present), with
  the hash function structured so a namespace term can be folded in later without changing the shard of
  existing keys when N is fixed. (Logged: the bare-key vs namespace input is M1 Open-Q3, scope is present.)
- **Cross-shard: DISCLAIM** — single-key writes strongly consistent; a multi-key BATCH spanning >1 shard is
  REJECTED with a clear error (the guard). Co-located keys (same scope/shard) use single-shard atomic BATCH.

## 4. Verification machinery FIRST (charter §2) — the multi-shard simulator

A new deterministic sim composes the Phase-0 single-group building blocks into **S shards, each an
independent Raft group of R nodes**, with a `ShardMap` routing the client workload. Built and proven
NON-VACUOUS before any production `StaticShardMap`/routing code.

### 4.1 The 6 new invariants → how each is checked every seed
| Invariant | Check | Non-vacuity (injected RED) |
|---|---|---|
| **Routing correctness** | every write for key K is proposed only to `shardFor(scope,K)`'s group; a per-key audit records (key→shard) and asserts stability | a router that returns a wrong/rotating shard for some keys → RED |
| **Disjoint ownership** | the (key→shard) map is a function: no key observed under two shards across the whole run | a router with overlapping ranges (two shards claim a key) → RED |
| **Per-shard linearizability** | each shard independently passes the S2–S4 surface (`SimInvariants` per shard + per-key register history) | corrupt one shard's commit (stale overwrite) → RED on that shard |
| **Cross-shard isolation** | a fault scheduled on shard A (leader loss / partition) never trips an invariant on shard B; B keeps committing | leak A's fault into B (shared leader) → RED |
| **Stale-map redirect correctness** | a client routed to a stale leader is redirected to the current leader; the write commits **exactly once** (a dedup key proves no loss, no duplicate) | drop the redirect (write lost) or double-apply (duplicate) → RED |
| **N=1 equivalence** | with N=1 the multi-shard sim's per-seed history is byte-identical to the single-group `AdversarialSim` for the same seed | a router that adds overhead / reorders at N=1 → RED on the equivalence diff |

### 4.2 Strict component sequencing (charter §2.4, §4)
`V (this machinery) → C1 (ShardMap+hash) → C2 (routing+redirect+guard) → C3 (multi-group wiring) →
C4 (config N + observability) → C5 (integrated 10k-seed sweep)`. A component is DONE only when it runs in
the sim under adversarial schedules AND its tests pass AND a review agent signs its design note. Routing /
shard-map / redirect get **four-way rigor** (implementer + diff-review + independent re-run + red-team).

## 5. Scope boundaries (what Phase 1 does NOT do)
- **No dynamic resharding** (static-N only; `DynamicShardMap` is the v2 seam swap).
- **No early-ack path** (Durability Level 0/1, fsync-before-ack always).
- **No EC2 / real-hardware run** — stop at the seam; aggregate-throughput validation is operator-gated.
- **No production wire-format break** — epoch + CoalescedHeartbeat frame deferred (DL-P1-04/05).
- **Rehoming stays dormant** — no placement movement activated (DL-P1-07).

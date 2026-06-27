# Phase 1 — C2: write routing + leader-redirect + cross-shard guard (design note)

> The new write subsystem: route by shard, redirect on stale-leader (exactly-once), reject a cross-shard
> multi-key BATCH. Consensus-adjacent → FOUR-WAY sign-off. Status: DESIGN (code after C1 DONE).

## What changes (from the propose-seam map, design.md §2.1)

Today: `ConfigWriteService.put/delete(key, value, scope, principal)` → `RaftProposer.propose(scope,
command)` → a server lambda that **captures `groupId=0` and group-0's owner executor** → `driver.propose(0,
command)`. The key is invisible at the proposer boundary; the closure is single-group-bound.

### C2-a — route by shard
- Widen the `RaftProposer` SPI to carry the routing key: `propose(ConfigScope scope, String key, byte[]
  command)`. `ConfigWriteService.put/delete` already have `(scope, key)` live — pass them through.
- In the server proposer closure, resolve `int gid = shardMap.shardFor(scope, key)` and
  `driver.propose(gid, command)`, marshalling onto **`driver.ownerExecutor(gid)` re-resolved per call**
  (not the captured group-0 executor). This is the one structural de-binding.
- N groups registered at wiring (C3/C4 loop over `shardMap.shardIds()`); at N=1 the resolved gid is
  always 0 and the path is byte-identical to today (the N=1-equivalence guard).

### C2-b — leader-redirect (stale map), exactly-once
- The redirect mechanism ALREADY exists per-group: the write path returns a leader hint
  (`X-Leader-Hint`) on `NOT_LEADER`; the client retries against the hinted leader. Generalize it
  **per shard**: a client caches `(shard → leader)`; on `NOT_LEADER` for shard s it refreshes shard s's
  leader and retries — **intra-shard only, never crossing shards** (a cross-shard "redirect" would
  scatter the key — the V `CROSS_SHARD_REDIRECT` non-vacuity proves disjoint-ownership catches it).
- Exactly-once at the sharding layer = no scatter (disjoint ownership) + eventual progress (redirect
  reaches the live leader). Intra-shard exactly-once (log dedup) is Raft's, already covered per-shard.
- Verified by the V redirect tests against the real routing; red-team attacks the redirect race
  (failover mid-retry) + the stale-map window (the cached leader is N elections stale).

### C2-c — cross-shard multi-key BATCH guard (DISCLAIM, D-C)
- A `BATCH` whose keys resolve to >1 shard is REJECTED with a clear error (not a silent partial write).
- Mechanism: given a BATCH command, `CommandCodec.decode(cmd)` → `DecodedCommand.Batch.mutations()` →
  for each `mutation.key()` compute `shardMap.shardFor(scope, key)`; if the distinct shard count > 1,
  reject with a `CrossShardBatchException` (or a typed rejection result) naming the offending keys/shards.
  Co-located keys (all → one shard) pass straight through to that shard's atomic single-group BATCH.
- **Scope note (DL-P1-07, to finalize in C2):** BATCH is codec-only today (no HTTP endpoint — CM-033).
  The guard is built + unit/sim-tested as a reusable component (`CrossShardWriteGuard`) and wired at the
  `ConfigWriteService` batch seam. Whether to ALSO wire the full BATCH HTTP endpoint this phase (the ADR's
  "hard co-delivery") vs document it as the immediate co-requirement is decided in C2 and logged — leaning:
  build the guard + a `batch(...)` service method tested directly; full HTTP endpoint wiring is a small
  additive follow-up flagged for the operator if not completed, since Phase 1 stops before production ship.

## Verification (C2 DONE criteria)
- V sim: routing correctness + disjoint ownership + redirect (no loss/scatter) GREEN against the real
  `StaticShardMap` + the real redirect; non-vacuity retained (rotating router, cross-shard-redirect bug).
- Guard unit tests: single-shard BATCH passes; cross-shard BATCH rejected with the offending keys named;
  N=1 ⇒ never rejects (all keys → group 0).
- Four-way: implementer + diff-review + independent re-run + red-team (redirect race, stale-map window,
  guard bypass, a BATCH that resolves to one shard only by hash collision).

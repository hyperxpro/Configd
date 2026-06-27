# Phase 1 — C1: StaticShardMap + hash-within-scope (design note)

> The ownership model: `hash(scope, key) mod N` → opaque group id, behind the `ShardMap` seam (V).
> Verified against the V machinery (routing correctness + disjoint ownership + N=1 equivalence) AND its
> own unit tests. Four-way sign-off (consensus-adjacent). Status: DESIGN (code after V sign-off).

## Decision: `StaticShardMap` shape

```java
public final class StaticShardMap implements ShardMap {
    public StaticShardMap(int shardCount);        // uniform: all scopes share the pool [0, shardCount)
    public int shardFor(ConfigScope scope, String key);  // floorMod(stableHash(scope,key), shardCount)
    public IntStream shardIds();                  // [0, shardCount)
    public long epoch();                          // 0 forever (static-N)
}
```

- **Uniform single pool `[0, N)`** (the M1 "spread-all" recommended default, Open-Q2). Scope is folded
  into the hash so it is a *routing input*, not ignored — but all scopes share the one pool (no
  per-scope dedicated pools in v1). Production uses `GLOBAL` only today, so this is exercised end-to-end.
- **Per-scope DEDICATED pools** (the ADR's "scope selects the pool") are a clean, additive extension —
  a future constructor `StaticShardMap(Map<ConfigScope,int[]> pools)` — deferred to operator Open-Q4
  (scope→pool cardinality). Logged as a Phase-1 decision; NOT a re-litigation (M1 left it open).
- **Hash**: 64-bit FNV-1a over the scope ordinal + the key's UTF-16 code units, then a SplitMix64
  finalizer, then `Math.floorMod(h, N)`. Stable across JVMs/restarts (defined `String.charAt`); good
  avalanche so the keyspace spreads. `floorMod` guarantees a non-negative shard for a negative hash.

## The three D-B invariants (honored, asserted in tests)

1. **Opaque, stable ids** — ids are `[0,N)`; no `groupId == 0` special-casing anywhere. A key that hashes
   to 0 is ordinary. (Test: a key hashing to 0 routes + commits identically to any other.)
2. **Routing is always `shardFor(...)`** — callers (C2/C3 wiring) never inline `mod N`. (Enforced by code
   review + the fact that `StaticShardMap` is the only `mod` site.)
3. **`epoch() == 0`** — present in the interface; never bumped under static-N. Wire-epoch reservation is
   deferred (DL-P1-04, operator-gated); nothing in v1 needs the wire field to function.

## Verification (C1 DONE criteria)

- **Unit** (`StaticShardMapTest`): determinism/stability (same key → same shard ×N), range
  (`shardFor ∈ [0,N)` incl. negative-hash keys), `shardIds()==[0,N)`, `epoch()==0`, N=1 ⇒ every key→0,
  opaque-id (no 0 special-case), distribution sanity (every shard used; max/min bounded over 10k keys).
- **Sim integration**: swap the V sim + `MultiShardSimTest` from `ShardRouters.hashReference(n)` to
  `new StaticShardMap(n)` → routing correctness + disjoint ownership + N=1 equivalence GREEN against the
  REAL impl across the sweep. Keep `ShardRouters.rotating` for the routing non-vacuity (an injected
  mis-route still goes RED).
- **N=1 equivalence**: the existing `nEqualsOne_byteIdenticalToSingleGroup` test now runs the production
  `StaticShardMap(1)` → proves the default path is unchanged.

## Four-way sign-off (consensus-adjacent)

implementer (me) + diff-review agent + independent re-run (the sim sweep + unit tests, a second agent)
+ red-team (attack: a key that lands off-range; an unstable hash; a 0-special-case; a hash that
collapses the keyspace onto one shard).

## Home

`configd-replication-engine` (io.configd.replication), beside `ShardMap` + `MultiRaftDriver` (DL-P1-02).

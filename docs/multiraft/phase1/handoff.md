# Multi-Raft Phase 1 — Handoff

> Autonomous build session (operator asleep). Charter: build the static-N sharding layer on the Phase-0
> owner-executor foundation, verification machinery FIRST, strict component sequencing, STOP at the merge
> gate and at the EC2 (money) gate. This handoff is the clean-stop record: what is built + sim-verified,
> the sim evidence, the precise "ready for EC2" note, residual risks, and the N=1-default behavior.
>
> **Branch:** `multiraft-phase1` (off `origin/main` = `c58ac1f`). **A PR is opened; the merge is the
> operator's (STOPPED at the merge gate).** No EC2 was provisioned (money gate).

## 1. Status at a glance

| Item | Status | Evidence |
|---|---|---|
| **V — multi-shard sim + 6 invariants** | ✅ DONE, four-way reviewed | `MultiShardSimTest` 73/0; non-vacuity mutation-proven (red-team) |
| **C1 — ShardMap + StaticShardMap (hash-within-scope)** | ✅ DONE | `StaticShardMapTest` 12/0; sim routes through the real `StaticShardMap` |
| **C2 — cross-shard write GUARD (DISCLAIM)** | ✅ DONE | `CrossShardWriteGuardTest` 7/0 |
| **C2 — routing + leader-redirect (logic)** | ✅ SIM-VERIFIED | routing correctness + stale-map redirect (no loss/scatter) green + non-vacuous in `MultiShardSim` |
| **C2 — production write-path WIRING** | ⏭ EC2-PREP (deferred) | needs the N-group server registration (coupled to C3 server) |
| **C3 — per-shard isolation (driver/shared-node)** | ◑ PARTIAL | independent-harness isolation green; the shared-node coupling-leak RED (SF1 mandate) is the remaining sim item |
| **C3 — ConfigdServer N-group wiring + adapter groupId fix** | ⏭ EC2-PREP (deferred) | the production multi-group deployment bridge |
| **C4 — config N-selection + per-shard observability** | ⏭ EC2-PREP (deferred) | `StaticShardMap(N)` built; config plumbing + per-shard metrics are server-coupled |
| **C5 — integrated ≥10k-seed sweep** | ✅ DONE | `MultiShardSimTest -Dconfigd.multiShard.seedSweep.count=10000` green (see §3) |
| **C5 — N=1 regression (default path unchanged)** | ✅ DONE | `nEqualsOne_byteIdenticalToSingleGroup` vs a single-group control |
| **C5 — fan-out/edge re-confirm with N>1** | ⏭ EC2-PREP (deferred) | needs N>1 committed streams from the real server |
| **Gate — `gate-phase1.sh` + CI job** | ✅ DONE | cumulative `needs: gate-B`; fast-PR + nightly-10k |

**Honest framing:** the charter's verification is SIMULATOR-based (§5), and that is complete and green for
the sharding ownership model, the routing/redirect logic, the DISCLAIM guard, and N=1 equivalence. The
**production server N-group wiring** (the consensus-setup block in `ConfigdServer` becoming a per-group
loop, the write-path routing, the per-shard metrics) is the **bridge to the EC2 multi-node measurement** —
a large, consensus-adjacent surgery that was deliberately NOT half-built (charter hard rule #9: never
leave routing/sharding half-built; "stop clean beats finish dirty"). It is specified precisely in §4.

## 2. What is built + sim-verified (the foundation)

- **`ShardMap`** (interface, `configd-replication-engine`): the D-B seam — `shardFor(scope,key)/shardIds()/
  epoch()`; opaque ids (no `groupId==0` special-casing), epoch present (returns 0 under static-N).
- **`StaticShardMap`**: `hash(scope,key) mod N` over a uniform pool `[0,N)` (FNV-1a + SplitMix64 finalizer
  + `floorMod`); stable function, healthy spread (max/min < 1.5 over 10k keys), **N=1 ⇒ every key → group
  0**.
- **`CrossShardWriteGuard` + `CrossShardBatchException`**: a multi-key BATCH spanning > 1 shard is REJECTED
  with a clear, named error; co-located keys pass to the single-shard atomic BATCH; **N=1 never rejects**.
- **`MultiShardSim` + `MultiShardSimTest`** (`configd-testkit`): S shards × R nodes, each a proven
  `ClusterHarness`, under a `StaticShardMap`-routed deterministic workload; the six invariants checked
  every seed; the green tests route through the PRODUCTION map; five invariants have a dedicated
  injected-RED non-vacuity proof, the sixth (cross-shard isolation) is structural this phase with a sound
  liveness witness (C3 makes it non-vacuous at the shared-node fidelity).

## 3. Sim evidence

- `MultiShardSimTest`: **Tests run: 73, Failures: 0** (12-seed small sweeps + the full-surface sweep + the
  targeted green/non-vacuity cases). Routes through the production `StaticShardMap`.
- Integrated **≥10k-seed** full-surface sweep: `-Dconfigd.multiShard.seedSweep.count=10000` →
  **Tests run: 10033, Failures: 0, Errors: 0** (~192 s) — all six invariants green across 10k+ seeds
  against the production `StaticShardMap` (the gate runs this on the nightly path).
- `StaticShardMapTest` 12/0, `CrossShardWriteGuardTest` 7/0.
- Four-way review of V: java-correctness (no BLOCKER) + red-team mutation-test (5/6 genuinely
  non-vacuous; the isolation liveness-witness vacuity it found is FIXED — `commitsAdvancedOn` now uses the
  strictly-increasing max committed version, proven by `nonVacuity_allShardsDead_isolationWitnessReportsNoProgress`).

## 4. READY FOR EC2 — the precise next-session work (operator-gated: money + production wiring)

Real-hardware multi-node aggregate-throughput validation (proving the N×knee aggregate) requires EC2 =
money = the operator-approval gate. Before that measurement is meaningful, the production server must run
N>1 groups end-to-end. The exact work, mapped to `file:line` this session:

**A. Server N-group wiring (the coupled chunk — C2-wiring + C3-server + C4):**
1. A `ShardMap` in the server (default `StaticShardMap(Integer.getInteger("configd.raft.shardCount", 1))`
   — C4 N-selection, default N=1).
2. `ConfigdServer.java:361-468` — the single-RaftNode consensus-setup block becomes a **per-group loop**
   over `shardMap.shardIds()`: one `RaftNode` + `RaftTransportAdapter` + `CoalescingRaftTransport` +
   group-commit/flush wiring per shard. `addGroup(gid, node)` per shard (replaces `:367`).
3. **Route writes by shard** — widen `RaftProposer.propose(scope, command)` to carry the key (or a
   pre-resolved gid); in the `ConfigdServer.raftProposer` closure (~`:1309`) resolve
   `gid = shardMap.shardFor(scope, key)` and `driver.propose(gid, …)` marshalling onto
   `driver.ownerExecutor(gid)` re-resolved per call (drop the captured group-0 executor at `:638`).
   Wire the `CrossShardWriteGuard` at the BATCH seam.
4. **`RaftTransportAdapter` groupId fix (DL-P1-06, NO wire-format change — the groupId is already in the
   frame):** inbound (`RaftTransportAdapter.java:64-69` + `ConfigdServer.java:1217`) must route on
   `frame.groupId()` not the constant `0`; outbound (`RaftTransportAdapter.java:52`) must stamp each
   group's real id (construct one adapter per group). **N=1 stays byte-identical** (only group 0 exists).
5. **Per-shard observability (C4):** `ConfigdServer.java:883-901` scrape is `if (owner==0)` group-0-only;
   iterate the driver's groups and emit per-shard series. NOTE: `MetricsRegistry` is NOT tag-capable —
   use the in-repo name-encoding convention (`base.<shardId>`) or adopt Micrometer.

**B. Wire-format work (operator-gated `WIRE_VERSION` bump — see DL-P1-04/05):**
6. **CoalescedHeartbeat wire frame** for N>1 over TCP: a new `MessageType.RAFT_COALESCED_HEARTBEAT`
   (`0x11`) + a count-bounded multi-group payload codec + `FrameCodec`/`NettyConsensusFrameEncoder` support
   + inbound demux → `driver.routeCoalescedHeartbeat`. Without it, N>1 sends un-coalesced heartbeats
   (correct, but loses the flat-in-N benefit the EC2 run measures). Forces a `WIRE_VERSION 0x01→0x02` bump
   + 16 golden-fixture regen (the `wire-compat` CI gate enforces this).
7. **Epoch wire field (OPERATOR DECISION, DL-P1-04):** reserve now (a deliberate `WIRE_VERSION` bump in v1
   for a field static-N never bumps) vs accept a v2 wire break. M1 left this open (DL-M1-09). Bundle with
   #6 if reserving (both are `WIRE_VERSION` changes; do them together). **Static-N v1 does not need the
   wire epoch to function** — the C2 redirect uses per-shard leader hints, not the wire epoch.

**C. The remaining sim item (C3 shared-node isolation, SF1 mandate):** extend
`OwnerIsolationMultiOwnerTest` (replication-engine) with the S2–S4 surface per group + cross-shard fault
schedules + a real coupling-leak RED (e.g. a stuck owner thread starving sibling shards) on the actual
`MultiRaftDriver` + owner pool — the genuinely non-vacuous isolation surface that the independent-harness
sim cannot provide.

**D. The EC2 measurement itself:** dedicated multi-box, derive N from the re-measured per-shard knee,
prove the N×knee aggregate (the charter §5 / `adr-throughput-target.md` plan). Operator-approved spend.

## 5. The N=1-default behavior (the safety property)

Below the throughput threshold, **N=1** (the default): `StaticShardMap(1)` routes every key to group 0,
so the deployment is a single Raft group — **today's behavior, byte-identical** (proven by
`nEqualsOne_byteIdenticalToSingleGroup` vs a single-group control on the same seed + op stream). N=1
retains whole-keyspace atomic BATCH (the guard never rejects). Sharding engages only when `N>1` is
configured. The default path does not regress.

## 6. Residual risks / carried-forward

- **Cross-shard isolation is structural in the Phase-1 sim** (independent harnesses) — the genuinely
  non-vacuous surface is the C3 shared-node item (§4.C), MANDATORY before isolation judges the real driver.
- **The production server is still single-group** — N>1 is sim-proven but not yet wired end-to-end (§4.A);
  the EC2 run is blocked on it.
- **Rehoming stays DORMANT** (DL-P1-07); the D-016 re-verify-on-activation obligation does not trigger
  (no placement movement activated).
- **Durability unchanged** — Level 0/1, fsync-before-ack always; no early-ack path added.

## 7. Decisions + provenance

All autonomous decisions are logged in `decision-log.md` (DL-P1-01..09) for retroactive veto, including
the operator-flagged wire-epoch deferral (DL-P1-04) and the no-wire-change groupId fix (DL-P1-06). Design
notes: `design.md`, `v-verification-machinery.md`, `c1-shardmap.md`, `c2-routing-redirect-guard.md`,
`c3-multigroup-wiring.md`. Did NOT re-litigate M1; did NOT build dynamic resharding; did NOT add an
early-ack path; did NOT provision EC2.

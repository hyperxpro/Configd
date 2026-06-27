# Next session — EC2-prep: production-server N-group wiring + the aggregate measurement

> The Phase 1 **sim-verified foundation is MERGED to main** (`db854d7`, PR #7). The sharding LOGIC
> (`ShardMap`, `StaticShardMap`, `CrossShardWriteGuard`) and the multi-shard simulator are on main but
> **DORMANT** — defined, not wired into any `src/main` path. This session WIRES them into the production
> server so N>1 works end-to-end, then measures the N×knee aggregate on EC2. Build on merged main.
>
> Authoritative deferral map with `file:line`: `handoff.md` §4. Decisions: `decision-log.md`. This doc is
> the build plan for executing §4.

## 0. Operator decisions needed UP FRONT (they gate the wire-format work)

| # | Decision | Notes |
|---|---|---|
| **D1** | **Epoch wire reservation** (DL-P1-04 / DL-M1-09) — reserve now vs accept a v2 wire break | A `WIRE_VERSION 0x01→0x02` bump in v1 for a field static-N never bumps. **v1 does NOT need it to function.** |
| **D2** | **Ship the CoalescedHeartbeat wire frame** for N>1-over-TCP? | Without it, N>1 sends un-coalesced heartbeats (correct, but loses flat-in-N — the thing the EC2 run measures). Bundle with D1: both are `WIRE_VERSION` bumps + golden-fixture regen, do them in ONE cutover. |
| **D3** | **N target / the 10k/s question** (`adr-throughput-target.md`) | N is deploy-derived from the re-measured per-shard knee (Step 2.b of the measurement). Not frozen at 16. |

## 1. Build order (dependency-ordered; each step: code + tests + sim re-run + four-way for consensus-adjacent)

**The N=1 byte-identity bar applies to EVERY step:** at `configd.raft.shardCount=1` (default) the whole
server path must stay byte-identical to today (the merged `nEqualsOne_byteIdenticalToSingleGroup` sim is
the model; add a server-level smoke).

### Step 1 — C4a: config N + the server `ShardMap` (small, safe, do first)
- Add `configd.raft.shardCount` (default **1**). Construct `new StaticShardMap(shardCount)` in
  `ConfigdServer` and thread it to the proposer wiring (Step 4) + group registration (Step 2).
- Set `configd.raft.ownerPoolSize` for N (Phase-0 owner pool; `ownerExecutor(gid)=pool[gid % poolSize]`).

### Step 2 — C3-server: the per-group consensus-setup loop (the HARD part — four-way)
- `ConfigdServer.java:361-468` (the single-`RaftNode` block) becomes a **loop over `shardMap.shardIds()`**:
  per shard build `RaftNode` + `RaftTransportAdapter` + `CoalescingRaftTransport` + group-commit/flush
  wiring; `driver.addGroup(gid, node)` (replaces the single `addGroup(0,…)` at `:367`).
- The `MultiRaftDriver` + owner pool are ALREADY group-parametric (Phase 0) — no driver change.
- **Re-verify:** N=1 byte-identical (existing sim + `OwnerThreadSimIntegrationTest` + a server smoke); the
  **correlated-node-loss election storm** cell (a node leading many shards crashes → no aggregate storm;
  staggered per-shard election timeouts + coalesced heartbeats mitigate — ADR D-B).

### Step 3 — `RaftTransportAdapter` groupId fix (NO wire-format change — DL-P1-06)
- **Inbound:** route on `frame.groupId()` (`RaftTransportAdapter.java:64-69` + `ConfigdServer.java:1217`),
  not the captured constant `0`. The groupId is already in the decoded frame.
- **Outbound:** one adapter per group, each stamped with its groupId (`RaftTransportAdapter.java:52`).
- **Test:** a frame stamped `gid=k` reaches group `k` (not 0); N=1 unchanged (only group 0 exists).

### Step 4 — C2-wiring: route writes by shard + wire the guard
- Widen `RaftProposer.propose(scope, command)` to carry the key (`ConfigWriteService.java:105`); resolve
  `gid = shardMap.shardFor(scope, key)` in the proposer closure (~`ConfigdServer.java:1309`); marshal onto
  `driver.ownerExecutor(gid)` **re-resolved per call** (drop the captured group-0 executor at `:638`).
- Wire `CrossShardWriteGuard.requireSingleShard(...)` at the BATCH seam (when the BATCH endpoint is added —
  CM-033, codec-only today; the guard component already exists + is unit-tested).
- **Re-verify:** routing correctness + stale-map redirect — the merged sim proves the LOGIC; add a
  server-level integration test (write to a stale leader → redirect → exactly-once).

### Step 5 — C4b: per-shard observability
- `ConfigdServer.java:883-901` (`if (owner==0)` group-0-only scrape) → iterate the driver's groups, emit
  per-shard election/apply-lag + a leader-count-per-node view. **`MetricsRegistry` is NOT tag-capable** →
  use the in-repo name-encoding convention (`base.<shardId>`) or adopt Micrometer.

### Step 6 — Wire-format (GATED on D1/D2 — one cutover)
- **CoalescedHeartbeat frame:** `MessageType.RAFT_COALESCED_HEARTBEAT` (`0x11`) + a **count-bounded**
  multi-group payload codec + `FrameCodec`/`NettyConsensusFrameEncoder` support + inbound demux →
  `driver.routeCoalescedHeartbeat` (the receive-side demux already exists + is test-proven).
- **Epoch field (if D1=reserve):** `HEADER_SIZE 18→26` + the SAME `WIRE_VERSION 0x01→0x02` bump.
- Both force regenerating the **16 golden fixtures** (the `wire-compat` CI gate enforces the bump). HARD
  cutover — no peer negotiation handshake exists; coordinate the deploy.

### Step 7 — C3a: the shared-node isolation sim (the SF1 mandate — closes the one structural gap)
- Extend `OwnerIsolationMultiOwnerTest` (replication-engine): the S2–S4 surface per group + cross-shard
  fault schedules + a **real coupling-leak RED** (e.g. a stuck owner thread starving sibling shards) on
  the actual `MultiRaftDriver` + owner pool — the genuinely non-vacuous isolation the independent-harness
  sim could not provide (the merged sim's invariant 4 is structural this phase).

### Step 8 — fan-out N>1 re-confirm
- Committed writes from ALL shards fan out correctly. The read plane is unchanged, but re-confirm
  end-to-end at N>1: the distribution node now ingests **N committed streams** → an N-way merge/sequencer
  in front of the bounded `FanOutBuffer` + a cross-shard drop-amplification mode (ADR
  `adr-multiraft-partitioning` Consequences; `ConfigdServer.java:492` is single-source today).

## 2. The EC2 measurement (MONEY GATE — operator approval to provision)

1. Provision (Workstream-C precedent: m6id.4xlarge on-demand, dry-run-green-on-the-free-box-first,
   ~$0.59 verified-teardown). **Do not provision without operator approval.**
2. Re-measure the **per-shard knee on a dedicated host** (S7.5 / Workstream C measured ~800/s,
   co-location-confounded — see [[configd-multiraft]] decision log DL-C-04).
3. Derive `N = ceil(target / (knee × efficiency))`; prove the **N×knee aggregate** (HdrHistogram +
   iostat/mpstat rails; under node loss the aggregate is `(2/3)N×knee` on a 3-node deploy — ADR D-B).
4. Verify teardown against the AWS API.

## 3. Verification gates (extend, don't replace)
- **N=1 byte-identical regression** (the merged `nEqualsOne` sim + a server smoke) — the default path must
  not regress; this is the single most important bar.
- `gate-phase1` extended with the server N-group integration tests + Step 7 (C3a).
- The full cumulative CI chain green.

## 4. Risks
- The consensus-setup loop (Step 2) is **consensus-adjacent** — four-way rigor, the N=1 regression bar, the
  correlated-node-loss cell. It does NOT re-open R-01 (the Phase-0 owner pool already provides per-group
  single-owner-thread safety), but the per-group wiring must be exact.
- The wire-format work (Step 6) is a **HARD cutover** (`WIRE_VERSION`, no negotiation) — sequence the deploy.
- **Rehoming stays DORMANT** unless a placement policy is activated; if activated, **re-verify D-016** (the
  dormant-state proofs do not transfer to live use).

## References
`handoff.md` §4 (the file:line deferral map), `decision-log.md` (DL-P1-04 epoch, DL-P1-06 groupId fix,
DL-P1-09 scope), `design.md`, `c2-routing-redirect-guard.md`, `c3-multigroup-wiring.md`, the M1 ADRs
(`adr-multiraft-{partitioning,topology,cross-shard}`, `adr-throughput-target`).

# Multi-Raft Phase 1 — Production-Wiring Session, Handoff

> This session WIRED the first two seams of the dormant static-N sharding into the live `ConfigdServer`
> and **STOPPED CLEAN** before the large, coupled C3 consensus-bringup surgery (charter prime directive:
> "stop clean beats finish dirty, absolutely … NEVER leave the server path half-wired"). Branch
> `multiraft-phase1-server-wiring` off `origin/main` `3aa8c82`. **PR opened; STOPPED at the merge gate.**
> EC2 NOT provisioned. This handoff is the clean-stop record + the precise next-session map.

## 1. What is wired + verified this session (Seams A + B)

| Seam | Charter step | What it does | Evidence |
|---|---|---|---|
| **A — C4a config N** | Step 1 | `configd.raft.shardCount` (default 1, range [1,16]); `resolveShardCount` + `enforceFixedShardCount` (fixed-at-deploy reshard guard, crash-durable marker); `StaticShardMap(N)` constructed at boot; **temporary N>1 boot guard** (refuse N>1 until the loop lands — no silent mis-route) | `ShardCountConfigTest` 8/0; `ConfigdServerTest` 22/0 |
| **B — DL-P1-06 inbound demux** | Step 3 (inbound) | `RaftTransportAdapter.InboundHandler` threads `frame.groupId()` through; `ConfigdServer.raftDemuxInboundHandler` routes each frame to ITS group (`routeMessage(gid)` on `ownerExecutor(gid)`), not constant 0; no wire change | `RaftInboundDemuxTest` 2/0; `NettyConsensusLivenessTest` 3/0 (real multi-node consensus); loopback/marshalling/owner-net/throwable all green |

- **Full `configd-server` suite: 297/0** (regression). **`gate-phase1` GREEN** (chain-skipped: c1 + multi-shard sim 200-seed + artifacts + the new server-wiring section).
- **Four-way reviewed** (consensus-adjacent): diff-review APPROVE-WITH-NITS + red-team SHIP-WITH-FIXES; both confirmed N=1 byte-identity; the two actionable findings (test-vacuity at N=2; marker fsync) were FIXED with red/green. See `server-wiring-decision-log.md` (DL-W-01..06 + the review record).
- **N=1 (the production default) is byte-identical**: the marker is additive metadata (`FileStorage` never enumerates the dir); the demux resolves to `ownerExecutor(0)`+`routeMessage(0)` on every frame; outbound unchanged. Consensus/wire/WAL behaviour unchanged.

## 2. What REMAINS — Seams C–G (the large coupled surgery, deferred CLEAN)

The remaining work is the heavy `ConfigdServer` N-group surgery the Phase-1 sim session ALSO deferred
(handoff.md §4.A). It is **one large, coupled unit** — N independent state machines + sharded reads +
sharded writes + N-way fan-out — that cannot be partially wired without a correctness hole at N>1.
**The temporary N>1 boot guard (Seam A) is the safety boundary that keeps every intermediate state
safe** (N>1 simply cannot boot until the guard is removed at the END of the wiring).

### The downstream coupling map (why C is one unit — verified at `file:line` this session)
Today these singletons all bind to **group 0's** objects; at N>1 each must become shard-aware:
- `raftNode` → group-commit setup (`ConfigdServer.java:~456`), bind (`~488`), health readiness leader
  (`~620`), write-service `leaderId` (`~653`), the linearizable-read dispatch
  (`readIndex`/`whenReadReady`/`completeRead`, `~705–736`), strong-read `leaderId` (`~791`).
- `stateMachine` → fan-out listener (`~541`), watch listener (`~568`), snapshot replay source (`~809`),
  the `ConfigdServer` field (`~848`) + `stateMachine()` accessor + `createReplaySource`.
- `configStore` → the read service `ConfigReader` (`~678–681`), compactor snapshot (`~564`), HTTP API
  server (`~790`).
- `storage` → `RaftLog` (`~300`), `AuditLog` (`~765`).

### Seam C — C3: the per-group consensus-setup loop (charter Step 2; FOUR-WAY)
Generalize the single-group bringup (`ConfigdServer.java:~361–533` + the per-group objects scattered at
`~200–300`) to N groups over `shardMap.shardIds()`. **Recommended structure** (debated this session):
extract a `RaftGroupRuntime buildRaftGroup(gid, groupStorage, sharedDeps…)` holding
`{storage, raftLog, configStore, stateMachine, raftNode, adapter, coalescingTransport, groupCommit}` and
use it for ALL groups (one path, no duplication of the intricate R-01/H-6/group-commit/coalescing
bringup), then loop + `driver.addGroup(gid, node)`.
- **Per-group storage path:** N=1 ⇒ `Storage.file(dataDir)` (BYTE-IDENTICAL); N>1 ⇒
  `Storage.file(dataDir.resolve("shard-" + gid))`. Keep `AuditLog`/signing-key at the **node level**
  (`dataDir`), NOT per shard.
- **Outbound adapter per group** (completes DL-P1-06's outbound half, deferred from Seam B): one
  `RaftTransportAdapter` per group stamping its gid, wrapped in `CoalescingRaftTransport`; bind each
  group's coalescer to ITS owner (`currentOwnerIndex(gid)`).
- **Owner pool:** for the EC2 aggregate, set `configd.raft.ownerPoolSize >= shardCount` (one group = one
  owner thread — Workstream C). The per-owner tick loop (`~877–933`) is ALREADY group-parametric (Phase
  0) — no change. The inbound demux (Seam B) already routes to all groups.
- **Re-verify:** N=1 byte-identical (boot smoke + `NettyConsensusLivenessTest` + marshalling/owner-net);
  the **correlated-node-loss election-storm** cell (ADR D-B: staggered per-shard election timeouts +
  coalesced heartbeats).

### Seam D — C2: sharded writes + reads + cross-shard guard on the LIVE path (charter Step 4; FOUR-WAY)
- **Writes:** widen `ConfigWriteService.RaftProposer.propose(scope, command)` to carry the key; in the
  server proposer closure resolve `gid = shardMap.shardFor(scope, key)` and re-resolve
  `driver.ownerExecutor(gid)` per call (drop the captured group-0 executor at `~639`). NOTE: this
  touches `RaftInboundMarshallingTest`/`RaftProposerCommitConfirmTest` which call the SPI directly.
- **Reads:** route `ConfigReader.get/getPrefix` to the owning shard's `configStore`; the linearizable
  read dispatch picks the group by key. `getPrefix` spanning shards needs a scatter-gather across stores.
- **Guard:** wire `CrossShardWriteGuard.requireSingleShard(...)` at the BATCH seam (BATCH is codec-only
  today — CM-033; the guard component exists + is unit-tested).
- **Red-team:** the redirect race (failover mid-retry) + the stale-map window on the LIVE path.

### Seam E — C4b: per-shard observability (charter Step 5)
`ConfigdServer.java:~883–901` is `if (owner==0)` group-0-only. Iterate the driver's groups; emit
per-shard election/apply-lag + a leader-count-per-node view. `MetricsRegistry` is **not** tag-capable →
use the in-repo name-encoding convention (`base.<shardId>`) or adopt Micrometer.

### Seam F — Wire-format D1 + D2 (charter Step 6; FOUR-WAY; HARD cutover)
**ONE** `WIRE_VERSION 0x01→0x02` bump, both fields dormant at N=1:
- **D1 epoch reservation:** `FrameCodec.HEADER_SIZE 18→26` (reserve the epoch field). **NOTE:** this
  grows EVERY frame by 8 bytes — it is a *deliberate, versioned, sanctioned* wire change (charter §2 D1),
  applied uniformly (not N-dependent). It is the one explicit exception to "N=1 wire bytes identical";
  N=1 *behaviour* is unchanged.
- **D2 CoalescedHeartbeat frame:** `MessageType.RAFT_COALESCED_HEARTBEAT` (`0x11`) + a count-bounded
  multi-group payload codec + `FrameCodec`/`NettyConsensusFrameEncoder` support + inbound demux →
  `driver.routeCoalescedHeartbeat` (the receive-side demux already exists + is test-proven).
- Regenerate the **16 golden fixtures**; the `wire-compat` CI gate must reflect the bump intentionally.
  This is in the transport/codec module — largely INDEPENDENT of the C bringup (could be sequenced
  early), but it is a HARD cutover (no negotiation handshake) — coordinate the deploy.

### Seam G — C3a isolation sim + fan-out N>1 + gate + Verifier (charter Steps 7, 8)
- **C3a (FOUR-WAY):** extend `OwnerIsolationMultiOwnerTest` (replication-engine) with the S2–S4 surface
  per group + cross-shard fault schedules + a **real coupling-leak RED** (a stuck owner starving sibling
  shards) on the actual `MultiRaftDriver` + owner pool — the genuinely non-vacuous isolation the
  independent-harness sim could not provide (the merged sim's invariant 4 is structural).
- **Fan-out N>1 (Step 8):** the distribution node now ingests **N committed streams** → an N-way
  merge/sequencer in front of the bounded `FanOutBuffer` (`ConfigdServer.java:~492` is single-source
  today) + a cross-shard drop-amplification mode (ADR `adr-multiraft-partitioning` Consequences).
- **Remove the temporary N>1 boot guard** (Seam A) ONLY once C+D+E+G make N>1 correct end-to-end.
- Extend `gate-phase1` further (server-drives-N, routing redirect/guard, per-shard isolation, wire-compat
  new version, S2–S4 per-shard + cross-shard-fault, coalesced-HB at wired N, fan-out N>1). Final
  fresh-context **Verifier APPROVE-0-must-fix** before the PR.

## 3. EC2 readiness — NOT YET

The real-hardware **N×knee aggregate-throughput** measurement is operator-gated (money) and requires the
server to run N>1 end-to-end — i.e. Seams **C, D, and G(fan-out)** at minimum (writes are the throughput
signal; reads/fan-out are required for end-to-end correctness). **It is NOT ready after A+B.** When C–G
land + verify, the precise note becomes: "Production wiring complete + verified; ready for the
operator-approved EC2 N×knee aggregate-throughput measurement." For that run, configure
`shardCount=N` AND `ownerPoolSize>=N`, and (red-team carry-forward) run the Raft transport with
**mTLS + client auth** (the demux now branches on an attacker-influenceable `groupId`; it drops hostile
gids safely, but defence-in-depth wants authenticated peers).

## 4. Residual risks / carried-forward
- **The production server is still single-group at runtime.** N>1 is refused at boot (Seam A guard) until
  C–G land. The sharding LOGIC remains sim-verified (10,033/0); the live N-group path is unbuilt.
- **Static-N / fixed-at-deploy (documented limitation):** changing N on an existing deployment is
  REJECTED at boot (the marker guard) — a manual reshard or a fresh data dir is required. v2 dynamic
  resharding is the `DynamicShardMap` seam swap.
- **Rehoming stays DORMANT** (DL-P1-07); the D-016 re-verify obligation does not trigger.
- **No early-ack; durability Level 0/1 unchanged.**
- **Accepted/deferred review items** (server-wiring-decision-log.md): fixed `.tmp` multi-writer race
  (N>1/concurrent-boot; subsumed by the pre-existing no-boot-lock gap); `Integer.getInteger` malformed
  fallback (matches the `configd.raft.*` idiom, fails safe to N=1).

## 5. Did NOT
Re-litigate M1; build dynamic resharding; add an early-ack path; provision EC2; touch the group-0 N=1
path's runtime behaviour; make a second wire break (Seam F bundles D1+D2 into one bump — not done this
session).

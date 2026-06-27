# Multi-Raft Phase 1 — Production-Wiring Session 2, Handoff (Seams C/D/E done; F/G remain)

> This session WIRED the heavy C–G surgery's first three seams into the live `ConfigdServer` and
> **STOPPED CLEAN** before the two heaviest remaining seams (F = the protocol-critical wire bump; G =
> the boot-guard removal + isolation sim + N-way fan-out). Branch `multiraft-phase1-server-wiring`;
> commits `cd533ea`(A) … `ad7153f`(E). **Charter prime directive honoured: stop-clean beats finish-dirty
> — never half-wire the consensus/wire path.** EC2 NOT provisioned. N=1 is byte-identical throughout;
> N>1 stays boot-REFUSED (the Seam-A guard) — every shipped state is safe.

## 1. What is wired + verified this session (Seams C, D, E)

| Seam | What it does | Four-way | N=1 | Evidence |
|---|---|---|---|---|
| **C — N-group consensus bring-up** | `buildRaftGroup(gid,…) → RaftGroupRuntime`, looped over `shardMap.shardIds()` on the owner pool; per-group storage/log/store/SM/node + per-group outbound adapter (DL-P1-06 outbound half); single hardened inbound demux; per-group owner-bind + coalescer | ✅ diff APPROVE-WITH-NITS + red-team SHIP + re-run | byte-identical | `MultiGroupBringupTest` 5/0; §3 mTLS 5/0 on `NettyRaftTransport`; full server 303/0 |
| **D — live write/read routing + cross-shard guard** | `propose(scope,keys,cmd)` routes via `requireSingleShard` (router+DISCLAIM guard in one); shard-aware leader redirect; sharded reader (stale GET + linearizable + scatter-gather getPrefix); keyed read 503 hint | ✅ diff REQUEST-CHANGES→re-review APPROVE-WITH-NITS + red-team SHIP-WITH-FIXES + re-run | byte-identical | `ShardedRoutingTest` 6/0; full server 309/0; cp-api 15/0 |
| **E — per-shard observability** | `registerPerShardMetrics`: per-group leader/term/commit-index/apply-lag + node leader-count, name-encoded, from `monitorView()` | implementer + re-run (additive; full four-way → final Verifier) | additive | `PerShardMetricsTest` 3/0; full server 312/0; `MetricsWiringContractTest` 4/0 |

- **`gate-phase1` GREEN** (chain-skipped): c1 + multi-shard sim + artifacts + wiring(A/B) + wiring-c + wiring-d + wiring-e.
- **Decision log:** `server-wiring-decision-log.md` (DL-W-C-01..04, DL-W-D-01..05, DL-W-E-01..02 + the
  four-way records). Design notes: `seam-c-multigroup-bringup.md`, `seam-d-live-routing.md`,
  `seam-e-per-shard-metrics.md`.

## 2. What REMAINS — Seams F + G (each large, self-contained; deferred CLEAN)

### Seam F — the D1+D2 wire bump (charter Step 6; FOUR-WAY; HARD cutover)
**ONE** `WIRE_VERSION 0x01→0x02` bump, both fields dormant at N=1:
- **D1 epoch reservation:** `FrameCodec.java` — `HEADER_SIZE 18→26` (reserve an 8-byte epoch field;
  encode 0, decode-but-ignore). NOTE: this grows EVERY frame 8 bytes — a *deliberate, versioned,
  sanctioned* change (charter §2 D1), the one explicit exception to "N=1 wire bytes identical" (N=1
  *behaviour* unchanged). Touch `FrameCodec.encode/decode` (both array + ByteBuffer overloads),
  `frameSize`, and the `Frame` record if the epoch is exposed.
- **D2 CoalescedHeartbeat frame:** `MessageType.RAFT_COALESCED_HEARTBEAT(0x11)` (resize `BY_CODE` to
  `0x12`) + a count-bounded multi-group payload codec + `FrameCodec`/`NettyConsensusFrameEncoder`
  support + inbound demux → `driver.routeCoalescedHeartbeat` (the receive-side demux already exists +
  is test-proven in `MultiRaftDriver`). The send-side drain (`ConfigdServer` `enableHeartbeatCoalescing`
  callback, currently "frame each group individually") swaps to emit ONE coalesced frame at N>1.
- **Regenerate the golden fixtures:** `configd-transport/src/test/.../wirecompat/GoldenFixtures.java`
  (regen via `WireFixtureGenerator.java`). The CI `wire-compat` job (`.github/workflows/ci.yml:545`)
  asserts a `GoldenFixtures` byte change is accompanied by a `FrameCodec.WIRE_VERSION` change — so the
  bump is INTENTIONAL, not an accidental break. Many tests reference `FrameCodec.HEADER_SIZE`
  (constant — auto-adapt) but check for any hardcoded 18/22 frame-size literals.
- **Four-way** (wire = protocol-critical). N=1 frames byte-identical EXCEPT the intended version byte +
  the 8 reserved epoch bytes (zeros). Largely INDEPENDENT of C — could be done first next session.

### Seam G — boot-guard removal + isolation sim + fan-out N>1 (charter Steps 7,8; FOUR-WAY)
- **Remove the temporary N>1 boot guard** — `ConfigdServer.resolveShardCount` `:1264`
  (`if (shardCount > 1) throw IllegalStateException(...)`). Remove ONLY after the below make N>1 correct
  end-to-end. THE riskiest single action in the project (enables N>1 in production) — must be airtight.
- **N-way fan-out (Step 8):** the fan-out/watch state-machine listeners are bound to the PRIMARY group
  only — `ConfigdServer.java:608` (`stateMachine.addListener(... fanOutBuffer.publish ...)`) + `:635`
  (`watchService::onConfigChange`). At N>1 the distribution node must ingest N committed streams → an
  N-way merge/sequencer in front of the bounded `FanOutBuffer` (`:586`) + the cross-shard
  drop-amplification mode (ADR `adr-multiraft-partitioning`). The `SnapshotReplaySource` (`:834`,
  `replaySource()` `:~1700`) is single-store (primary) — make it span shards.
- **Health readiness** (`:686` `raftNode.leaderId()` — group 0) → per-shard health (ready iff this node
  is a healthy member of each shard it owns).
- **Per-shard write-throughput / 429** (DL-W-E-02 deferral) — wire the per-gid write metrics here, when
  they become observable at N>1.
- **C3a shared-node isolation sim (FOUR-WAY):** extend `OwnerIsolationMultiOwnerTest` (replication-
  engine) with the S2–S4 surface per group + cross-shard fault schedules + a REAL coupling-leak RED (a
  stuck owner starving sibling shards) on the actual `MultiRaftDriver` + owner pool — the genuinely
  non-vacuous isolation the independent-harness sim could not give (`c3-multigroup-wiring.md` SF1).
- **Seam-G-gated obligations carried forward** (from the C/D red-teams — re-verify BEFORE lifting the
  guard): (1) thread-safety of the shared `configSigner` / the two `InvariantChecker`s / `configdMetrics`
  touched by multiple owner threads at N>1 (audit `ConfigSigner` for a single `java.security.Signature`);
  (2) close already-built `RaftGroupRuntime`s on a mid-bring-up boot failure (partial-bring-up leak);
  (3) mTLS mandatory for the EC2 run.
- Extend `gate-phase1` further (server-drives-N end-to-end, wire-compat new version, fan-out N>1, the
  isolation coupling-leak RED). **Final fresh-context Verifier APPROVE-0-must-fix before the PR merges.**

## 3. EC2 readiness — C+D done, G remains

The N×knee aggregate-throughput measurement needs the server to RUN N>1 end-to-end = **C + D + G**
(writes are the throughput signal — DONE; reads/routing — DONE; fan-out + guard-removal — Seam G). So it
is **NOT yet ready**: C + D are landed + verified, but G (which removes the boot guard + N-way fan-out)
remains. When G lands + verifies, the note becomes: "Production wiring complete + verified; the server
runs N groups with live routing + authenticated peers; ready for the operator-approved EC2 N×knee
aggregate-throughput measurement — set `configd.raft.shardCount=N`, `configd.raft.ownerPoolSize>=N`,
and run the Raft transport with mTLS + client-auth (`--tls-*`)." Do NOT provision EC2 until then
(operator/money gate).

## 4. Safety state at this stop
- **N=1 (every production deployment) is byte-identical** to before — re-proven after each seam (full
  `configd-server` 312/0 incl. the real-wire `NettyConsensusLivenessTest` + `ConfigdServerTest`
  boot/restart; `MetricsWiringContractTest` 4/0).
- **N>1 is boot-REFUSED** (`resolveShardCount` guard) — the live N-group write path is built + sim/
  component-verified (`MultiGroupBringupTest`, `ShardedRoutingTest`, `PerShardMetricsTest`) but cannot
  boot in production until Seam G removes the guard. No half-wired N>1 state ships.
- **§3 peer authentication** is enforced (NettyRaftTransport mTLS + `needClientAuth`) and proven by the
  `AbstractRaftTransportContract` negatives on the production transport — the demux only routes an
  authenticated peer's groupId (+ a hostile gid is dropped on the inbound thread before marshalling).
- Dynamic resharding NOT built; rehoming DORMANT; no early-ack; EC2 NOT provisioned.

## 5. Did NOT (this session)
Build the wire bump (F); remove the N>1 boot guard (G); N-way the fan-out (G); run the C3a coupling-leak
isolation sim (G); provision EC2; touch the N=1 runtime behaviour.

# Multi-Raft Phase 1 — Production-Wiring Session 3, Handoff (Seam F DONE; Seam G remains)

> This session completed **Seam F** — the protocol-critical `WIRE_VERSION 0x01→0x02` bump (D1 reserved
> epoch + D2 CoalescedHeartbeat frame), BOTH dormant — and **STOPPED CLEAN** before **Seam G** (the
> boot-guard removal + live isolation sim + N-way fan-out — "the switch-flip"). Branch
> `multiraft-phase1-seam-fg`; commits `1c931ba`(F) · `a7dadef`(four-way) · `3707233`(Verifier). Charter
> prime directive honoured: **F atomic + complete before G; stop-clean beats finish-dirty.** A wire bump
> and a boot-guard removal are each all-or-nothing; F is fully landed, G is untouched. EC2 NOT
> provisioned. **N=1 is byte-identical to today EXCEPT the sanctioned version byte + 8 zero epoch bytes;
> N>1 stays boot-REFUSED** — every shipped state is safe.

## 1. What Seam F wired + verified (this session)

| Part | What it does | Four-way | N=1 | Evidence |
|---|---|---|---|---|
| **D1 — reserved epoch** | `FrameCodec` `HEADER_SIZE 18→26`, `WIRE_VERSION 0x01→0x02`; 8-byte epoch after term, encode-0/decode-ignore (forward-compatible). NOT on the `Frame` record (60 sites). All 4 hand-rolled zero-alloc encoders + the 2 `FrameCodec` overloads write it; both decoders skip it. | ✅ diff APPROVE-WITH-NITS + red-team SHIP + re-run REPRODUCED-GREEN + **Verifier APPROVE-0-must-fix** | byte-identical except version+epoch | `FrameCodecEpochReservationTest` 6/0 (incl. the v2→v1 splice-back proof); `WireCompatGoldenBytesTest` 17/0; `NettyConsensusFrameEncoderByteIdentityTest` 1/0 |
| **D2 — CoalescedHeartbeat frame** | `MessageType.RAFT_COALESCED_HEARTBEAT(0x11)`; count-bounded payload codec in `RaftMessageCodec` (40 B/group, `MAX_COALESCED_GROUPS`, overflow/dup/trailing/negative rejects, fail-closed type guard); send drain `frameHeartbeatDrain` (coalesced only at >1 group → dormant at N=1); inbound per-group demux on `RaftTransportAdapter` (each group → its OWN owner thread, NOT `routeCoalescedHeartbeat` inline — DL-F-03). | ✅ same four-way + red-team 6-test PoC battery (7/7 attack classes DEFENDED) | dormant (emission); decode reachable-but-hardened | `CoalescedHeartbeatCodecTest` 13/0; `RaftTransportAdapterCoalescedInboundTest` 3/0; `HeartbeatDrainFramingTest` 3/0; `RedTeamCoalescedWirePoCTest` 6/0 |

- **`gate-phase1` GREEN** — new `wiring-f` block (CI-wired, cumulative): epoch reservation + N=1 byte-identity, v2 golden bytes (intentional bump), Netty byte-identity, coalesced codec + bounds, inbound demux, send-drain dormancy, the red-team PoC. Greps assert `WIRE_VERSION 0x02` / `HEADER_SIZE 26` / `RAFT_COALESCED_HEARTBEAT(0x11)`.
- Full suites: `configd-transport` 143/0, `configd-netty` 71/0 (1 pre-existing skip), `configd-server` 337/0.
- **Decision log:** `server-wiring-decision-log.md` DL-F-01..04. **Design:** `seam-f-wire-bump.md`.
- **Clean cutover** recorded: no external v1 deployments; the strict version tripwire means every node
  must run the same `WIRE_VERSION` (no v1/v2 negotiation until an ADR-0030+ peer Hello).

## 2. What REMAINS — Seam G (the switch-flip; FOUR-WAY; gated on the integrated sweep)

Carried forward verbatim from handoff-2 §2 (re-confirm file:line, the tree has moved):
- **Remove the temporary N>1 boot guard** — `ConfigdServer.resolveShardCount` (`if (shardCount > 1) throw …`).
  **THE riskiest single action in the project** — remove ONLY after the below make N>1 correct end-to-end,
  and ONLY with the integrated sweep GREEN (charter §3.4 / §5). Re-prove N=1 boot/run byte-identical after.
- **N-way fan-out (charter §5):** the fan-out/watch state-machine listeners bind to the PRIMARY group only
  (`ConfigdServer` `stateMachine.addListener(... fanOutBuffer.publish ...)` + `watchService::onConfigChange`).
  At N>1 the distribution node must ingest N committed streams → an N-way merge/sequencer in front of the
  bounded `FanOutBuffer` + the cross-shard drop-amplification mode (ADR `adr-multiraft-partitioning`). The
  `SnapshotReplaySource` / `replaySource()` is single-store (primary) — make it span shards.
- **Health readiness** (`raftNode.leaderId()` — group 0) → per-shard (ready iff a healthy member of each
  shard it owns).
- **Per-shard write-throughput / 429** (DL-W-E-02 deferral) — wire per-gid write metrics when observable at N>1.
- **C3a shared-node isolation sim (FOUR-WAY):** extend `OwnerIsolationMultiOwnerTest` (replication-engine)
  with the S2–S4 surface per group + cross-shard fault schedules + a REAL coupling-leak RED (a stuck owner
  starving sibling shards) on the **actual `MultiRaftDriver` + owner pool** — the non-vacuous isolation the
  independent-harness sim could not give (`c3-multigroup-wiring.md` SF1).
- **Seam-G-gated obligations** (from the C/D red-teams — re-verify BEFORE lifting the guard): (1) thread-
  safety of the shared `configSigner` / the two `InvariantChecker`s / `configdMetrics` touched by multiple
  owner threads at N>1 (audit `ConfigSigner` for a single `java.security.Signature`); (2) close already-built
  `RaftGroupRuntime`s on a mid-bring-up boot failure (partial-bring-up leak); (3) mTLS mandatory for EC2.
- **The integrated sweep (the gate on "N>1 is real"):** full S2–S4 per-shard + cross-shard-fault + shared-node
  isolation live + fan-out N>1, all GREEN at N>1, BEFORE the guard removal. Removing the guard without this
  green is forbidden (charter §3.4 / §8.3).
- Extend `gate-phase1` further (server-drives-N end-to-end, fan-out N>1, the isolation coupling-leak RED).
  **Final fresh-context Verifier APPROVE-0-must-fix before the PR merges.**

**Seam F's coalesced send path activates automatically at N>1** (the `>1 group` drain branch + the inbound
demux are already wired and dormant) — so when G removes the boot guard, coalesced heartbeats go live on the
wire with no further wire work. The integrated sweep must exercise the coalesced frame at the wired N.

## 3. EC2 readiness — F done, **G still gates it**

The N×knee aggregate-throughput measurement needs the server to RUN N>1 end-to-end = C + D + **G** (writes
are the throughput signal — DONE; routing — DONE; fan-out + guard-removal — Seam G, NOT done). So it is
**NOT yet ready**. When G lands + verifies, the note becomes: *"Production wiring complete + verified; the
server runs N groups live (routing + authenticated peers + isolation + fan-out + coalesced heartbeats);
ready for the operator-approved EC2 N×knee aggregate-throughput measurement — set
`configd.raft.shardCount=N`, `configd.raft.ownerPoolSize>=N`, run the Raft transport with mTLS +
client-auth (`--tls-*`) — which also runs the deferred soak + DR drill."* Do NOT provision EC2 until then
(operator/money gate). **Not provisioned this session.**

## 4. Safety state at this stop
- **N=1 (every production deployment)** — wire bytes are identical to v1 EXCEPT the version byte `01→02`
  and the 8 reserved epoch bytes (all zero); behaviour, Raft WAL/snapshot format, and the consensus path
  are unchanged. Re-proven: `configd-server` 337/0 (incl. `ConfigdServerTest` boot/restart, the real-wire
  `NettyConsensusLivenessTest`, `MetricsWiringContractTest`).
- **N>1 is boot-REFUSED** (`resolveShardCount` guard) — the live N-group write path + the v2 wire are built
  + component/sim-verified but cannot boot in production until Seam G removes the guard with the integrated
  sweep green. No half-wired N>1 state ships.
- **§3 peer authentication** unchanged (NettyRaftTransport mTLS + `needClientAuth`); the demux routes only an
  authenticated peer's groupId, and a coalesced frame is bounds-hardened + per-group dropped like any AE.
- Dynamic resharding NOT built; rehoming DORMANT (D-016 re-verify not triggered); no early-ack; EC2 NOT provisioned.

## 5. v2-deferred list (carried forward)
- Dynamic resharding / running-cluster N change (still rejected; not built).
- Client-SDK maturity for sharded routing (the redirect hint exists; a polished client is v2).
- Cross-region / multi-DC placement policy.
- The epoch field's ACTIVATION (DL-P1-04 operator decision) — the wire bytes are reserved; activating needs
  only the `Frame.epoch()` accessor + an `encode` overload (no further wire bump).

## 6. Did NOT (this session)
Start Seam G (boot guard untouched; fan-out still primary-group-only; no isolation coupling-leak sim);
provision EC2; touch the N=1 runtime behaviour; make a second wire break.

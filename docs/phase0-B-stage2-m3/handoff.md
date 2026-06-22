# Phase 0 — Workstream B — Stage 2 — M3 Handoff (coalesced heartbeats DONE)

> **State:** M3 wires the dormant `HeartbeatCoalescer` so heartbeat cost is FLAT IN GROUP COUNT — the
> RR-113 de-regression that lets multi-Raft (N>1) not be slower than N=1. Additive, behind the net, the
> consensus core (`RaftNode`) UNTOUCHED. Branch `phase0-B-rethreading` (pushed); `main` pinned at the
> verified `cedc706` (D-010). **M1 + M2a + M2b + M3 done; M4 (gate-B + merge) remains.**
> Design: `design.md`. Decisions: D-020 (design + reviews) / D-021 (closure).

---

## 1. What M3 built — coalesced heartbeats, the three proofs

The naive N>1 model sends one heartbeat per GROUP per peer per tick, so heartbeat traffic scales with the
group count — which would make N>1 *slower* than N=1 and reproduce-and-amplify RR-113. Coalescing sends
**one message per peer-node per tick regardless of group count** (the CockroachDB/TiKV technique).

| Proof | Surface / evidence |
|---|---|
| **(1) Cost flat in group count** | `HeartbeatCoalescingTest`: one coalesced message per peer per tick independent of G (G=1/16/256); the **un-coalesced baseline scales with G** (test-the-tester); the multi-group `CoalescedHeartbeat` + `routeCoalescedHeartbeat` **demux round-trip** + neuter. |
| **(2) No spurious election under load** | `CoalescedHeartbeatLivenessTest`: no spurious election under **idle / low / sustained** load WITH coalescing (leader stays leader at a fixed term); the **broken-drain test-the-testers** all churn — DROP → no stable leader; DELAY-past-timeout (idle) → destabilization (follower re-election OR same-term CheckQuorum step-down, caught by the role check); single-peer drop → PreVote-shielded (no spurious election, but the victim provably churns PreVotes). Plus the **20,001-seed `SeedSweepTest` wired through the coalescer** in the sim, green. |
| **(3) Correctness preserved** | demux delivers per-group liveness; the **full S2–S4 surface re-closes WITH coalescing** — 2000-seed safety+liveness sweep + linearizability/consistency + rehoming sweep + cross-group net = 4052 tests, 0 fail. |

**Net non-vacuous across all four classes re-confirmed post-change:** off-owner (`OwnerNetCatchesOffOwnerInboundTest`
fires), cross-group (`OwnerIsolationMultiOwnerTest`), rehoming-race (`RehomingHandoffTest` /
`RehomingInjectedSweepTest`), **double-ownership** (jcstress `RehomingDoubleOwnershipTest` rebuilt
post-change, 28/28 at `-m quick`).

## 2. The mechanism as-built

- **`CoalescingRaftTransport`** (consensus-core) — a per-group `RaftTransport` decorator wrapping the node's
  transport. On `send(peer, msg)`: if `msg` is an EMPTY `AppendEntriesRequest` (a heartbeat) AND the owner's
  tick window is open, record it into the owner's coalescer; else pass straight through. Entry-carrying
  AppendEntries (real replication), votes, snapshots, and any out-of-window heartbeat are NEVER coalesced.
  Resolves the CURRENT owner's coalescer on each record via a `Supplier` (rehoming-aware — D-021 A2).
- **`HeartbeatCoalescer`** (relocated to consensus-core, repurposed) — a per-owner, single-threaded,
  payload-carrying buffer `Map<peer, Map<group, AppendEntriesRequest>>`; tick-window (`beginTick` /
  `drainAndEndTick`); the old time-window API DELETED (a window past a tick slips the election timeout).
- **`MultiRaftDriver.tickOwner`** — opens the window, ticks the owner's groups, then in a **try/finally**
  drains one message per peer (a mid-tick throw still flushes — H-2; per-peer exception isolation).
  `enableHeartbeatCoalescing` / `heartbeatCoalescer` / `routeCoalescedHeartbeat` (demux).
- **`CoalescedHeartbeat`** (consensus-core) — a transport-level VALUE (NOT a `RaftMessage`, NOT a wire
  frame) carrying `from` + `Map<group, AppendEntriesRequest>`. **At N=1 (production) every drain has exactly
  one group ⇒ a plain AppendEntries goes out and the wire / `FrameCodec` / `MessageType` / sealed
  `RaftMessage` set are ALL UNCHANGED.** The `CoalescedHeartbeat` arises only at N>1 (the test-only
  multi-group surface), demuxed by `routeCoalescedHeartbeat` into per-group `routeMessage`.
- **Wiring:** `ConfigdServer` enables coalescing on the real transport only (inert single-node/test); the
  cross-node sim (`ClusterHarness`) wires a per-node coalescer + per-node drain.

**DORMANT-effective in production** (N=1 single group ⇒ every drain is one group ⇒ wire unchanged); the
coalescing *benefit* engages at N>1, which is test-only until Phase-1 sharding.

## 3. Relationship to RR-113 (and what is NOT claimed)

RR-113: the ~800/s ceiling is single-thread heartbeat starvation (heartbeat slips the election timeout →
churn). M3 makes heartbeat cost flat in N so the per-tick heartbeat work does not grow with the group
count — the de-regression that opens the multi-Raft path. **M3 proves the heartbeat PROPERTY (flat in N,
no spurious elections); it does NOT measure writes/s.** The throughput NUMBER is **Workstream C on
hardware** (the 2-vCPU box is ENV-BLOCKED for throughput — D-003).

## 4. What REMAINS

### M4 — gate-B + merge B to main (§6 gate) — its own session
- Assemble `gates/gate-B.sh` (CI-wired, cumulative 1–7 + phase0) — can call `gate-phase0.sh` (which now
  includes the M3 coalesce step c2). Record `cedc706` baseline-green.
- Merge B → main ONLY when gate-B is green in CI and all milestones are four-way verified.

### Workstream C — the hardware throughput measurement (after the merge)
- Measure N=1-with-fix and N>1 sharding writes/s on dedicated hardware; decide v1-vs-v2 sharding.

## 5. Residual risks / Phase-1 activation items (carry forward)

- **Production `CoalescedHeartbeat` wire frame is Phase-1.** M3 keeps the prod wire unchanged (N=1
  passthrough). When N>1 ships, add a `MessageType.RAFT_COALESCED_HEARTBEAT` frame + `FrameCodec`/codec
  support + the `TcpRaftTransport` inbound demux. The receive-side demux LOGIC exists
  (`routeCoalescedHeartbeat`) and is test-proven; only the wire encode/decode is deferred.
- **`RaftTransportAdapter` inbound hardcodes `DEFAULT_RAFT_GROUP`** (ignores `frame.groupId()`) — latent,
  fine at N=1 (single group). **Phase-1 must fix it before N>1-over-TCP** (else all groups' inbound
  collapses to group 0). Orthogonal to M3 (leader-outbound).
- **Oversized `CoalescedHeartbeat` (M-1).** Inapplicable at M3 (N=1 sends only empties, which never
  encode-reject; test transports don't reject). **Phase-1 must bound/split** the coalesced frame when it
  ships on the wire, or a per-group inflightCount could leak past the RR-103 guard.
- **Coalescing × ACTIVE rehoming — combined proof deferred (D-016).** The decorator is now rehoming-SAFE
  (dynamic `Supplier` resolution → records into the new owner's coalescer after a rehome), but the combined
  coalescing+active-rehoming surface is not exercised (production is N=1 / no rehome). A Phase-1 placement
  policy must re-run the proofs with both active (mirrors the M2b dormant-state caveat).
- **Follower RESPONSE coalescing is deferred** (its own milestone + liveness proof). M3's de-regression is
  the leader's outbound heartbeat amplification (RR-113's bottleneck).
- **2-vCPU box:** the jcstress + full-gate chain is multi-hour → defer to CI. The M3 deterministic surface
  (sweep + coalescing tests) runs in minutes; the jcstress double-ownership re-confirm was run at `-m quick`
  (28/28). See `[[configd-environment]]`.

## 6. How to resume (M4)

Resume from the M3 S4 commit. Prime directive holds: four-way each dangerous step, the net re-proven
NON-VACUOUS for each class, STOP CLEAN at the last verified seam. M4 assembles gate-B and merges B → main
only at the §6 gate. Do NOT claim a throughput number (Workstream C). M1+M2a+M2b+M3 are COMPLETE fallbacks.

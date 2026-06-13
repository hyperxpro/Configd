# RR-103 kernel-fix review — per-peer inflight-window leak

Session 4 / Workstream A1. Fix carries the full Session-2 kernel-fix regime:
discriminating test (captured pre-fix RED) → minimal fix → mutation-check of the fix
region → spec-twin review → inflight-accounting assertion twin (fire-verified) →
mutation thresholds unregressed → full 10k re-sweep. Two-agent reproduction owed at
pickup (P1) — see §6.

## 1. Diagnosis (confirmed by code reading)

`RaftNode.inflightCount` is a per-peer pipelining window (cap `maxInflightAppends`,
default 10): `+1` on each `sendAppendEntries`/`sendInstallSnapshot` (after a successful
`transport.send`), `-1` only on a response (`handleAppendEntriesResponse:1280`,
`handleInstallSnapshotResponse`), reset only at `becomeLeader`. The periodic heartbeat is
routed through the SAME `sendAppendEntries`, which returns early at the gate
`if (inflight >= maxInflightAppends) return;`. So once `maxInflightAppends` messages to a
peer are **lost on the network** (partition / crash / drop) — `transport.send` succeeds
locally, the window increments, but no response can arrive — the window pins at the cap
and the leader is **permanently silenced toward that peer for the whole term**: no
heartbeat, no backfill, no InstallSnapshot, no error, no metric. Only a term change
(which rebuilds the map) recovers it.

Note: the *codec-reject* leak (oversized message → IAE before send) was already closed in
S2 (increment-after-send, comment at `:1657`). RR-103 is the distinct **network-drop**
leak, which increment-after-send does not address (the send succeeds; the loss is later).

## 2. Fix — heartbeat decay of the per-peer window

In `tickHeartbeat`, when the heartbeat fires and CheckQuorum passes, reset
`inflightCount[peer]=0` for every peer that is **both pinned at the cap AND absent from
the active set** (no response in the just-elapsed interval — its in-flight RPCs are
presumed lost), immediately before the broadcast. Actively-responding peers keep their
window (drained normally by response decrements), so the window's flow-control role is
intact. The existing `peerActivity` / `buildActiveSetAndReset()` seam already tracks
per-interval responsiveness — **no new hot-path state**. Bound: a lost message can stall a
peer for at most one heartbeat interval, never a whole term. This is the register's
suggested "heartbeat decay" shape and is standard-Raft-faithful (etcd/raft never gates
heartbeats by the inflight window; in Probe state a heartbeat frees the probe).

**Narrowing to pinned-AND-inactive (review finding §2b).** The first form reset every
inactive peer. The independent reviewer flagged the benign-but-real edge: a
congested-but-alive WAN follower (RTT > one heartbeat interval, architecture §12) could
land zero responses in an interval and have a window full of *genuinely* in-flight RPCs
reset to 0, causing a bounded (~2×cap) over-send. Gating the decay on `inflightCount[peer]
>= maxInflightAppends` confines it to the actual silenced state (the wedge signature) and
removes the over-send entirely, while still fixing the leak (a pinned window is freed at
the next heartbeat). Adopted.

## 3. Spec-twin review (ConsensusSpec.tla)

**Conclusion: no TLA+ change is needed or desirable; the fix RESTORES conformance to the
existing spec.**

- `ConsensusSpec` has `nextIndex`/`matchIndex` per node but **no `inflightCount` /
  pipelining window** — the window is an implementation-level throughput optimization
  with no abstract counterpart. The spec's `AppendEntries` action is always enabled for a
  leader, and `Spec == Init /\ [][Next]_vars /\ WF_vars(Next)` (line 585) imposes **weak
  fairness on `Next`**, so the spec assumes a leader keeps (re)sending AppendEntries to a
  lagging follower until it catches up.
- RR-103 was the implementation introducing a state (the pinned window) **not present in
  the spec** that silently *disabled* the always-available `AppendEntries` the spec relies
  on under fairness. I.e., the implementation diverged from its own abstract model.
- The fix re-establishes the spec's structural assumption: after decay, a leader can
  always (re)send to a reachable peer within one heartbeat interval — exactly what
  `WF_vars(Next)` requires.
- Adding `inflightCount` to the spec would only re-encode the optimization and could
  itself reintroduce the abstracted-away stall. The correct twin for an
  implementation-only invariant is a **runtime assertion**, not a spec variable — hence
  `inflight_window_progress` (§4), which guards that the optimization never disables the
  AppendEntries action the spec assumes. This mirrors how other implementation-detail
  twins are handled.
- RR-103 was also a concrete real-world failure mode of the spec's `EdgePropagationLiveness`
  (LIVE-1, `~>`): a committed entry that never reaches a follower never reaches an edge
  subscribed through it (the `EdgeBootstrapMidChurnTest` symptom that surfaced it). The
  fix improves conformance to LIVE-1 (TLC cannot verify LIVE-1 at model bounds — note
  F-V2-02 — so this is a structural, not a TLC, argument).

## 4. Runtime assertion twin — `inflight_window_progress`

Added at the heartbeat-broadcast call site (per peer, after the decay):
`inflightCount[peer] < maxInflightAppends || activeSet.contains(peer)` — "no peer may be
both silenced (window at cap) AND inactive (not draining)", precisely the
permanent-silence state. On the real path post-fix this always holds (decay resets inactive
peers to 0; `0 < cap` since `cap≥1`; active peers satisfy the right disjunct), so it never
fires spuriously in any sim/seed-sweep.

Fire-verification (§4.5 rule "an assertion never observed firing is unverified"):
- `AssertionTwinFiringTest.everyRaftNodeTwinIsObservedFiring` — added to `RAFTNODE_TWINS`
  and fired through the production check shape via `fireInNodeTwinForTest`
  ("inflight_window_progress"). **Observed firing (GREEN).**
- `InvariantCallSiteTest.leaderHeartbeatInvokesInflightWindowProgress` — drives a real
  3-node leader heartbeat with an observing checker and asserts the production call site
  actually invokes the check, killing the `VoidMethodCall` removal mutant. **GREEN.**
- Real-path firing (live-net proof): with the decay reverted, the assertion fires under
  the sweep on any window-pinning seed (the throwing checker in `AdversarialSim`) — see
  §5 mutation-revert (iii).

## 5. Mutation-revert evidence (each fails a named test)

- **(i) decay removed (`inflightCount.put(peer,0)` deleted, check kept)** →
  `Rr103InflightWindowRecoveryTest.leaderBackfillsHealedFollowerWithinSameTerm` FAILS:
  after heal the follower stays pinned at commitIndex **5** while the leader is at **19**
  (`expected: <19> but was: <5>`) — the registered "permanently silenced" signature.
  Captured: `docs/session-4/captures/rr103-prefix-failure.txt`. *Proves the decay is
  load-bearing for liveness.*
- **(ii) check call removed (decay kept)** → `InvariantCallSiteTest` FAILS (the observing
  checker never records `inflight_window_progress`); `Rr103...` stays GREEN (the decay still
  works) — *confirms the check is pure defence-in-depth, not load-bearing for behavior.*
- **(iii) decay removed, check kept, throwing checker** → the assertion fires on the real
  path under the sweep (window-pinning seed) — *proves the twin is a live net, not just a
  forced-fire.*

## 6. Discriminating test red→green

`Rr103InflightWindowRecoveryTest` — deterministic in-process `RoutingCluster` (real
RaftNodes, per-node DROP partition): elect N1, baseline-commit, partition N3, burst-propose
> `maxInflightAppends` to pin the window, heal, assert **same-term** backfill (no
election). Pre-fix RED (`5` vs `19`, captured); post-fix GREEN (1/1). Second-agent
reproduction: owed at pickup (P1) — to be performed independently against the captured
command before the register flips to RESOLVED.

Repro: `./mvnw -pl configd-consensus-core test -Dtest=Rr103InflightWindowRecoveryTest`.

## 7. Regression / thresholds

- Full consensus-core suite: **316 run, 0 failures, 0 errors, 2 skipped** (the known
  RR-064 wire-compat stubs) with the fix in place.
- 10k consensus adversarial re-sweep (`AdversarialSimTest#nightlyAdversarialSweep`,
  5 nodes × 1500 ticks × 10k seeds): result in
  `docs/session-4/captures/rr103-10k-resweep.txt` — must show **0 safety violations** and
  the assertion never firing; the stall count vs the S2 baseline (7/10k) feeds the RR-095
  causal analysis (Workstream A2).
- Mutation thresholds (§4.1): the fix region's mutants are covered by (i) Rr103 (decay
  behavior + the `!activeSet.contains` conditional), (ii) InvariantCallSiteTest (call
  removal), (iii) AssertionTwinFiringTest + the sweep (the check expression). Scoped PIT to
  be recorded at gate assembly.

## 8. Second-agent review outcome (2026-06-13)

Independent read-only review (code-reviewer agent) — **verdict APPROVE-WITH-CHANGES**, no
prod-blocking findings. It independently confirmed the diagnosis with current line cites
(gate `:1668`, increments `:1706/:1758`, decrements `:1317/:2174`, reset `:1635`; full
`inflightCount` mutation-site enumeration → the only decreases are the two response
decrements and the two resets, so a successful-send-then-no-response pins the window
permanently), confirmed the fix is safe across joint-consensus reconfiguration (new peers
handled by `getOrDefault`/`put`; removed peers drop out of `peersOf`), leadership transfer,
InstallSnapshot pacing, and CheckQuorum step-down ordering, and confirmed the discriminating
test is genuinely RED pre-fix and that the same-term assertion correctly rejects the
"recovery via new election" false positive.

Findings and resolutions:
- **§2b (bounded over-send to a congested-but-alive peer)** — ADOPTED the reviewer's
  recommended tightening: decay gated on `>= maxInflightAppends` (see §2 Narrowing). The
  over-send is gone.
- **§3 (assertion is post-decay ⇒ tautological at its site; real-path value is
  mutation-revert-only)** — ACKNOWLEDGED in code and docs: the twin is documented as
  defence-in-depth (the same category as `election_safety`/`log_matching`, which also sit
  behind guards and are fired via the forced seam). NOT relocated before the decay: doing so
  would make it fire on every legitimate partition-heal transient, which the throwing
  sim/test checker would turn into spurious sweep failures. The honest framing is now in the
  inline comment and §4.
- **§4 (test hardening)** — ADOPTED both: (a) a precondition peek
  (`inflightCountForTest(N3) == 10`) taken before any step proves the window is pinned at the
  cap (the wedge state), closing the latent vacuity; (b) the recovery phase now MEASURES the
  backfill latency and asserts a tight bound (≤ 500 ticks) instead of a blind `step(2000)` —
  catching a slow-recovery regression, not only a permanent wedge. **Measured:
  recoveryTicks = 50 (exactly one heartbeat interval)** — the healed follower backfills at
  the first post-heal heartbeat.
- **§5.4 (sweep capture + independent test re-run owed)** — the 10k re-sweep capture exists
  (`docs/session-4/captures/rr103-10k-resweep.txt`) and shows 0 safety violations. The
  independent code-level reproduction is this review; the run-level red→green is
  demonstrated by the captured mutation-revert (i) (decay removed → the registered `5 vs 19`
  RED) and the GREEN suite. The register flip to RESOLVED gates on the final-code sweep
  capture (§7).

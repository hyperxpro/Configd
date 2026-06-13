# EXP-001 — RR-103: per-peer inflight-window leak silences the leader after message loss

- **Workstream:** A1 (the liveness debt)
- **Register row:** RR-103 (P1, Consensus / replication liveness), OPEN at pickup
- **Owner:** consensus-recovery-engineer (chaos-lead arbitrating verdict)
- **Status:** verdict CODE-FINDING → fix landed (see §Verdict / §Fix)

## 1. Hypothesis (expected behavior, cited)

**Cited expectation.** `docs/architecture.md` §6 *Failure Handling* — *Leader Isolation*
and *Asymmetric Partitions* — promises that the cluster tolerates partitions and
recovers: CheckQuorum sheds an isolated leader, PreVote prevents term inflation, and an
isolated/partitioned node **rejoins and is brought current once the partition heals**.
This is the standard Raft liveness guarantee: *a leader continues to attempt replication
to every peer in its configuration, and a reachable follower is backfilled to the
committed prefix.* The implicit bound is **one heartbeat interval** — after a peer becomes
reachable again, the next heartbeat (≤ `heartbeatIntervalTicks`) must reach it and begin
backfill.

The §6 docs do **not** state an explicit numeric recovery bound for *backfill-after-heal*
(only the 150–300 ms election window is quantified). That absence is recorded as a
secondary DOC-FINDING and feeds the recovery-bounds ledger (`recovery-bounds.md`,
fault class *follower-backfill-after-heal*). It does **not** change this experiment's
primary verdict, because the observed behavior — *permanent* silence — violates the
guarantee under any finite bound.

**Predicted behavior (what SHOULD happen):** after a partitioned follower heals, the
leader's next heartbeat reaches it; the follower's `commitIndex`/`lastApplied` converge to
the leader's committed prefix within a small, bounded number of heartbeat intervals
(a few round trips for any `nextIndex` walk-back), **within the same term** — no election
required.

## 2. Injection (exact, reproducible)

**Mechanism:** in-process deterministic routing harness (`Rr103InflightWindowRecoveryTest`,
consensus-core scope, no edge, no network sim) — a message bus over real `RaftNode`s with
a per-node *partitioned* flag that drops every frame to/from a partitioned node (models a
clean DROP partition; no RST).

**Steps (deterministic, no sleeps):**
1. Elect N1 leader of `{N1,N2,N3}`; commit its term no-op; replicate to N2 and N3.
2. Propose & commit a baseline batch (quorum N1+N2+N3).
3. **Partition N3** (drop all frames to/from N3). N1+N2 remain a quorum, so N1 stays leader.
4. Propose ≥ `maxInflightAppends` (=10) further entries and step through
   ≥ 10 heartbeat intervals. Each `broadcastAppendEntries → sendAppendEntries(N3)`
   increments `inflightCount[N3]`; no response can arrive (N3 dropped), so the window
   pins at `maxInflightAppends`. These entries commit via N1+N2; N3 misses them.
5. **Heal N3** (re-enable delivery).
6. Step through a generous bound (2000 ticks ≈ 40 heartbeat intervals), delivering each round.

**Repro by command (kernel-level discriminating test):**
```
./mvnw -pl configd-consensus-core test \
  -Dtest=Rr103InflightWindowRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false
```
**Registered field-level repro (integrated, pre-existing):** seed 4242, 5 nodes via the
`AdversarialSim`/`EdgeBootstrapMidChurnTest` path (isolate leader, heal; node stays ~47
entries behind for 10k+ quiet ticks). Re-confirmed at A2 alongside the RR-095 seed family.

## 3. Observation

- **Pre-fix (captured RED):** after heal, N3's `commitIndex` stays pinned at the
  pre-partition value for the entire 2000-tick window while N1/N2 sit at the committed
  head. The leader never sends N3 another `AppendEntries` (the window is pinned at 10 and
  `sendAppendEntries` returns early at the gate), so no backfill, no `InstallSnapshot`,
  no error, no metric — exactly the registered signature. Role FOLLOWER throughout (PreVote
  + recent-leader shield + stale log all prevent N3 from forcing a term change), so the
  only spontaneous recovery — a new term — never occurs.
- **Post-fix (captured GREEN):** the first heartbeat after heal finds N3 absent from the
  active set (`peerActivity[N3]==FALSE`) and resets `inflightCount[N3]=0` (heartbeat
  decay); the broadcast reaches N3; N3 catches up to the committed head within a few
  heartbeat intervals, same term.

Capture: `docs/session-4/captures/rr103-prefix-failure.txt`.

## 4. Verdict

**CODE-FINDING (P1), CONFIRMED by code reading and by the discriminating test red→green.**
The pipelining window — a throughput optimization — could permanently defeat the
liveness guarantee, because the periodic heartbeat is routed through the same
`sendAppendEntries` gate it is supposed to be immune to. Second-agent reproduction owed
per register discipline (P1) — performed independently (see review).

## 5. Fix (kernel-fix regime)

**Heartbeat decay of the per-peer window.** In `tickHeartbeat`, when the heartbeat fires
and CheckQuorum passes, reset `inflightCount[peer]=0` for every peer **not in the active
set** (no response observed in the just-elapsed interval). This frees a leaked window for
an unresponsive peer within at most one heartbeat interval, guaranteeing the heartbeat
broadcast always reaches it, while leaving the window's flow-control role intact for
actively-responding peers (their windows drain normally via response decrements). The
existing `peerActivity`/`buildActiveSetAndReset()` seam already tracks per-interval
responsiveness — no new state on the hot path. Standard-Raft-faithful (etcd/raft never
gates heartbeats by the inflight window).

Runtime assertion twin: `inflight_window_progress` (see AssertionTwinFiringTest +
InvariantCallSiteTest). Mutation-check, spec-twin review (ConsensusSpec), and the 10k
re-sweep are recorded in the RR-103 register resolution.

## 6. Recovery bound (measured)

Time from fault clear (heal) to N3 fully current, this hardware, sim time:
- **Backfill-after-heal:** ≤ N heartbeat intervals (measured in the test; recorded in
  `recovery-bounds.md`). Feeds Session 6 SLOs.
- Pre-fix: **unbounded** (never, within the term) — the defect.

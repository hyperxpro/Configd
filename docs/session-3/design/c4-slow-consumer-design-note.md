# C4 As-Built Design Note — Slow-Consumer Policy (`SlowConsumerGovernor`)

> **Status: AS-BUILT** — for dual sign-off (review-architect + contract-qa, charter §2).
> Test names are citations. Draft: `c4-slow-consumer-design-draft.md`; screen conditions:
> `../reviews/c2-c5-design-screen.md` §C4 (C4-1..C4-3). Deviations §6; named residuals §7.

## 1. What exists now

`SlowConsumerGovernor` (configd-distribution-service) is the architecture-§7 policy
ladder, live at runtime: it turns C1's per-session distress signals (queue-warn pressure
edges, `DemotionEvent`s, CURSOR_ACK progress) into per-**identity** state — keyed on the
mTLS cert principal, not the connection, so a reconnect storm cannot dodge policy. It is
wired into `FanOutServer` (SUBSCRIBE admission + demotion feed + ≤1 Hz evaluation on the
session loop) and, opt-in, into the simulator's `C1StreamDriver`. The pre-session
`SlowConsumerPolicy` orphan is **deleted in the same change** (superseded; screen C4-1),
as is `CatchUpService` (zero consumers; C3's recommendation) — RR-088 is narrowed to
`HyParViewOverlay` accordingly.

## 2. The state machine (every transition: named config + metric + structured log + test)

```
HEALTHY ──queue ≥ warn sustained queueWarnWindowMs──▶ SLOW      (still streaming)
SLOW ──ack progress drains below warn──▶ HEALTHY
any  ──C1 demotion (overflow / ack-lag / gap / transport)──▶ CATCHUP
CATCHUP ──snapshot applied + ack progress──▶ HEALTHY
any  ──demoteLimit distress demotions (or gapDemoteLimit GAPs) in demoteWindowMs──▶ QUARANTINED
        (disconnect: ERROR_CLOSE code 8 QUARANTINED + socket close; SUBSCRIBEs refused
         for quarantineCooldownMs; then readmitted ALLOW_FORCE_SNAPSHOT)
QUARANTINED ──quarantineLimit quarantines in unhealthyWindowMs──▶ UNHEALTHY
        (alert-grade; refused until unhealthyCooldownMs elapses — the cooldown alone is a
         sufficient exit, C4-3 — or an operator reset, which is additional)
```

Named configs (all validated, `SlowConsumerPolicyConfig`): `edge.fanout.policy.{
queueWarnWindowMs 10s, demoteLimit 3, gapDemoteLimit 10, demoteWindowMs 60s,
quarantineCooldownMs 60s, quarantineLimit 3, unhealthyWindowMs 1h, unhealthyCooldownMs 1h,
maxTrackedIdentities 4096}`. Metrics (eager, RR-013):
`edge_fanout_slow_transitions_total`, `edge_fanout_quarantines_total`,
`edge_fanout_reconnects_refused_total`, `edge_fanout_unhealthy_total`,
`edge_fanout_readmissions_total`, `edge_fanout_sessions_closed_quarantined_total`,
`edge_fanout_consumer_state_{healthy,slow,catchup,quarantined,unhealthy}` gauges.
Structured single-line logs: `edge_fanout_consumer_transition` (identity, from, to,
reason, cursor, lastAckedSeq, window counts, atMillis) and `edge_fanout_admission_refused`
— transition-paced, never per-frame.

## 3. The three decide-and-justify rulings (draft §3)

1. **Quarantine ≠ repeated demotion blindly (C4-2):** GAP demotions count on their own
   ladder (`gapDemoteLimit` 10) separate from distress demotions (`demoteLimit` 3, same
   window): a lossy WAN that gaps-and-heals is not slowness; the limit remains the
   backstop for a genuine gap loop. Pinned: `SlowConsumerQuarantineTransitionTest`
   (mixed-reason matrix) and the sim flap scenario
   (`SlowConsumerStateMachineWalkTest#networkLossFlappingNeverEscalatesAHealthyEdge` —
   3 partition/heal cycles across the replay horizon: recovery fires each time, zero
   policy escalation, ends HEALTHY).
2. **Disconnect at the wire:** `ERROR_CLOSE` carrying the existing golden-pinned
   `ErrorCode.QUARANTINED` (code 8) + socket close; the session loop exits before the
   owed snapshot transfer. UNHEALTHY shares code 8 (a new code would be a wire-version
   bump; governor state + message text distinguish).
3. **Next subscribe:** refused during cooldown (counted + structured log + cooldown
   remaining in the close diagnostic); after cooldown `ALLOW_FORCE_SNAPSHOT` — the
   server **rebinds the resume cursor to 0** so C3's `decideMode` cursor-0 rule yields
   SNAPSHOT_FIRST: reuse, not duplication. The edge-side reaction was verified, not
   rebuilt: code 8 lands on `EdgeClientCore.onErrorClose`'s existing fatal arm
   (`EdgeClientCoreTest$ErrorCloseHandling.fatalCloseQueuesReconnectAtCursor`), and the
   shell's bounded backoff absorbs the refusal loop.

## 4. The charter's gate-3 walk

`SlowConsumerStateMachineWalkTest` (configd-testkit, seed 31): a deliberately-lagging
edge actor walks, in recorded order, HEALTHY→SLOW→CATCHUP→QUARANTINED→(refusals during
cooldown)→CATCHUP→HEALTHY, with edge invariants checked every tick and post-readmission
convergence to a fresh commit; a second test replays the walk and asserts the
`TransitionEvent` streams byte-equal (the determinism proof for where the governor
actually runs — see deviation 4); the third is the C4-2 flap scenario.
Process level: `FanOutServerQuarantineTest` (real sockets, injected clock — no sleeps):
demotions → wire code 8 + close → SUBSCRIBE refused with diagnostic → cooldown elapsed →
`SUBSCRIBE_OK(SNAPSHOT_FIRST)` despite a bogus-high resume cursor → snapshot → ack →
HEALTHY; plus identity independence (another cert unaffected).

## 5. Determinism, threading, hot-path law

No wall-clock reads anywhere — time enters as `nowMillis` (caller's clock / sim clock).
Methods are `synchronized` but every call is a policy-frequency event (pressure edge,
demotion, subscribe, ack advance, ≤1 Hz evaluate; one clock read per session-loop
iteration) — never per-frame; nothing runs on the publish path. The identity map is
bounded (`maxTrackedIdentities`, skip-distressed eviction on insert only).
Gate path: byte-identical — `EdgeSeedCompatTest` green and the 507-seed sweep summary
identical to the C3 baseline; the governor is opt-in in the sim (null = historical
behavior) and no new mixSeed tags were needed.

## 6. Deviations (each justified)

1. Draft's "demotions **>** DEMOTE_LIMIT" implemented as **>=** (the Nth event trips) —
   matches §7's "3 quarantines in 1 hour" reading and the screen's own C4-2 phrasing;
   consistent across both ladders.
2. `consumer_state{state}` → per-suffix gauges (label-less registry; established
   deviation).
3. Additions beyond the draft's lists so every transition/threshold is independently
   named and metered: `edge_fanout_readmissions_total`, `gapDemoteLimit`,
   `quarantineLimit` (was hardcoded in the draft), `maxTrackedIdentities`.
4. Draft §4's "governor state folded into the determinism digest" discharged as
   walk-replay equality instead: the governor is absent on the gate path (opt-in), so
   folding it into the gate digest would be vacuous; the replay compare is the
   non-vacuous determinism proof for where it runs.
5. UNHEALTHY shares wire code 8 (closed golden-pinned taxonomy).

## 7. Named residuals (none blocking)

- **Teardown bye race (pre-existing):** the best-effort quarantine `ERROR_CLOSE` can
  interleave with the writer thread mid-frame and arrive torn; the refusal path (no
  concurrent traffic) carries the clean code 8 and is what the process test asserts
  strictly. Writer-handoff fix is out of C4 scope.
- **C3-signoff CT-06 NOTE re-named:** the process-level re-bootstrap trigger remains
  not-snapshot-suppressed (one wasted transfer worst case; edge-triggered, no livelock).
- **C3 double-fault corner inherited:** a readmitted identity forced to cursor 0 against
  an *empty* ring (server restarted+restored, zero new commits) gets TAIL and idles until
  the first commit — staleness-ladder-visible, self-healing.
- **Plaintext identity:** in plaintext mode (test/single-node) the governor keys on the
  wire `edgeId` — same trust posture as C1's identity binding; mTLS keys on the cert
  principal.
- **Multi-connection identity:** only the tripping connection is torn down synchronously;
  siblings of the same cert die at their next subscribe/demotion (degenerate by design —
  one cert per edge).
- No new edge-cache unit test for code 8 specifically (the reaction arm was already
  pinned there; the sim walk drives code 8 through the real core).

## 8. Verification snapshot

Full reactor `clean test` green, 15/15 modules (agent run + lead's independent run);
standalone 10k-seed `SeedSweepTest` green (20,181 tests). PIT (RUN_ERROR=0):
`SlowConsumerGovernor` **100% (94/94)**, `SlowConsumerPolicyConfig` 100% (3/3);
module-wide 78.0% (518/664) vs the 65 floor — up from C3's 74.3%; `FanOutSessionCore`
unchanged 66.7% (pre-existing survivors, named, not regressed).

## 9. Contract rows this component claims (for the contract-qa audit)

The §7 slow-consumer family as the map names them (CT-26..CT-30 class): warn transition
(CT-27 → `SlowConsumerWarningTransitionTest`), quarantine (CT-28 →
`SlowConsumerQuarantineTransitionTest`), re-bootstrap-after-quarantine (CT-29 →
`QuarantineReBootstrapTest` + `FanOutServerQuarantineTest`), repeat-quarantine/unhealthy
(CT-30 → `RepeatQuarantineUnhealthyTest`), and the gate-3 state-machine walk row
(`SlowConsumerStateMachineWalkTest`). The audit, not this note, flips the map.

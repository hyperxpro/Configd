# C4 Design Draft — Slow-Consumer Policy State Machine

> **Status: DRAFT for review-architect screening.** Contract rows CT-27..CT-30; charter
> §4 C4. C1 deliberately shipped the substrate (session states STREAMING/CATCHUP + the
> demotion events with cursor evidence); C4 is the governance layer that turns repeated
> distress into quarantine/disconnect/re-bootstrap, each transition with a test, a metric,
> and a structured log event.

## 1. Signals (all exist after C1; no new instrumentation needed to decide)

Per session: queue depth vs `queueWarnPct` (warning signal), demotion events with reason
(`ack_lag` / `transport_backpressure` / `gap`), demotion frequency, time-in-CATCHUP,
CURSOR_ACK progress rate. The §7 credit-based numbers are superseded (C1 design §4,
review condition 4); the §7 POLICY LADDER (warn → quarantine → remove) is what C4
implements, re-based on these signals.

## 2. State machine (per subscriber identity — the mTLS cert identity, not the connection)

```
HEALTHY ──queue>80% sustained QUEUE_WARN_WINDOW──▶ SLOW (warn metric+log; still streaming)
SLOW ──ack progress resumes──▶ HEALTHY
* ──overflow/gap──▶ CATCHUP (C1 demotion; counted)
CATCHUP ──snapshot+resume ok──▶ HEALTHY
* ──demotions > DEMOTE_LIMIT within DEMOTE_WINDOW──▶ QUARANTINED
     (disconnect with ERROR code 8 QUARANTINED; cursor evidence logged;
      reconnects REFUSED for QUARANTINE_COOLDOWN; then must re-bootstrap:
      SUBSCRIBE accepted only with snapshot-first forced)
QUARANTINED ──3 quarantines within UNHEALTHY_WINDOW (1h)──▶ UNHEALTHY
     (refused until operator reset or UNHEALTHY_COOLDOWN; alert-grade metric)
```

Named configs (each with a metric, charter §6 rule 8): `edge.fanout.policy.queueWarnWindowMs`
(default 10_000 — §7's "0 credits for >10 s" analogue), `demoteLimit` (3),
`demoteWindowMs` (60_000), `quarantineCooldownMs` (60_000 — §7's "must re-bootstrap"),
`unhealthyWindowMs` (3_600_000, §7's "3 quarantines in 1 hour"), `unhealthyCooldownMs`
(3_600_000). Metrics: `edge_fanout_consumer_state{state}` (gauge per state count),
`edge_fanout_slow_transitions_total`, `edge_fanout_quarantines_total`,
`edge_fanout_unhealthy_total`, `edge_fanout_reconnects_refused_total`. Structured log
event per transition: (identity, from, to, reason, cursor, lastAckedSeq, counts).

## 3. Placement

`SlowConsumerGovernor` in `configd-distribution-service` (deterministic, clock-injected,
same testability shape as `FanOutSessionCore`); consulted by `FanOutServer` at SUBSCRIBE
(admission: QUARANTINED/UNHEALTHY refusal, snapshot-first forcing) and fed by session
events. The existing orphan `SlowConsumerPolicy` class is SUPERSEDED by the governor
(its clock-threshold model predates the C1 signal set); it is deleted in C4 (RR-034-class
implement-or-delete: this is the delete half, with the governor as the implement half) —
its useful test patterns move to the governor's suite.

## 4. Tests (written first) + sim walk

Per transition (CT-27..30): `SlowConsumerWarningTransitionTest`,
`SlowConsumerQuarantineTransitionTest`, `QuarantineReBootstrapTest` (reconnect refused in
cooldown; after cooldown SUBSCRIBE forced snapshot-first), `RepeatQuarantineUnhealthyTest`
(clock-driven, SimulatedClock-style fake, no sleeps). Sim: the charter-mandated walk — a
deliberately-lagging edge actor (V1 `lag()`) walks HEALTHY→SLOW→CATCHUP→QUARANTINED→
re-bootstrap→HEALTHY inside `EdgeFanOutSim` with invariants checked throughout
(`SlowConsumerStateMachineWalkTest`, the gate-3 step). Governor state is folded into the
determinism digest.

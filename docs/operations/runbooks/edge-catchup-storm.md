# Runbook: Edge Fleet Catch-Up Storm After Extended Partition

> **Note:** The previous version of this runbook directed operators at `CatchUpService`
> and `SlowConsumerPolicy` (both since deleted), at
> admin endpoints that never existed (`/admin/catchup-rate-limit`,
> `/admin/force-snapshot-resync`), and at metric names nothing emits. Everything below
> names only machinery that exists at runtime, with its tests.

## What a catch-up storm is here

After a wide partition heals, many edges resubscribe at once carrying stale cursors.
Each gets either TAIL replay from the commit-notification boundary (cursor within the
replay horizon) or a paced chunked snapshot (beyond it / cursor 0 - `decideMode`).
The per-session machinery is self-limiting by design: bounded per-subscriber
queues, paced transfers (would-block pause/resume), ack-lag demotion, and the
per-identity `SlowConsumerGovernor` ladder. A "storm" is therefore a capacity event,
not a correctness event - the system sheds via demotion/quarantine rather than stalling
the control plane.

## Detection
- `edge_fanout_connected_subscribers` (gauge) jumps while
  `edge_fanout_subscribe_snapshot_first_total` climbs - many cold/lapsed resubscribes.
- `edge_fanout_snapshot_transfers_total` rate spikes; `edge_fanout_queue_depth`
  (process-level high-water gauge) elevated.
- `edge_fanout_subscribe_horizon_distance` (last-decision gauge) deeply negative -
  resubscribers far beyond the replay horizon (snapshot-heavy storm).
- Edge side: `edge_staleness_ms` high but FALLING per edge once its transfer lands;
  `edge_rebootstrap_triggered_total` firing across the fleet.
- Demotion pressure: `edge_fanout_demotions_ack_lag_total` /
  `edge_fanout_demotions_queue_overflow_total` climbing;
  `edge_fanout_slow_transitions_total` (governor SLOW tier) climbing.

## Diagnosis
1. How many subscribers, and which mode are they getting?
   ```bash
   curl -s --cacert <ca.pem> https://<cp>:8080/metrics | \
     grep -E 'edge_fanout_(connected_subscribers|subscribe_tail_total|subscribe_snapshot_first_total|subscribe_horizon_distance)'
   ```
2. Is the governor escalating (expected under a genuine storm) or quarantining broadly
   (a sign edges are too slow to drain, not just late)?
   ```bash
   curl -s --cacert <ca.pem> https://<cp>:8080/metrics | \
     grep -E 'edge_fanout_(consumer_state_|quarantines_total|reconnects_refused_total|unhealthy_total|readmissions_total)'
   ```
3. Per-edge progress (any edge): staleness ladder + applied cursor + transfer count.
   ```bash
   curl -s http://<edge>:8081/metrics | \
     grep -E 'edge_(staleness_ms|staleness_state|cursor_lag|snapshots_applied_total|applied_total|gaps_total)'
   curl -s http://<edge>:8081/health/ready   # 503 at DEGRADED+ is the LB drain signal
   ```
4. Structured logs on the CP: `edge_fanout_consumer_transition` lines carry identity,
   from-state, to-state, reason, cursor evidence, and window counts - the storm's shape in one grep.

## Mitigation
There is deliberately no kill-switch rate limiter; the bounded queues + paced transfers
+ the governor ARE the rate limit. Operator levers that exist:
1. **Let the ladder work.** Quarantine (refusal with `quarantineCooldownMs` backoff)
   staggers the herd automatically; readmission forces SNAPSHOT_FIRST (clean, paced).
2. **Stagger manually if needed:** edges already jitter reconnects
   (`edge.reconnect.backoffMs` base, doubling, +/-50% jitter - `EdgeStreamClient`);
   restarting a subset of edges in waves widens the stagger.
3. **Capacity:** add fan-out endpoints (any CP node serves `--edge-port` over the same
   ADR-0034 seams) and point edge `--fanout-endpoints` lists at the spread.
4. **Tune thresholds only by config and only deliberately** (`edge.fanout.policy.*` on
   the CP; each is a named config with a metric - see the architecture doc, section 7's amended table).

## Recovery
1. Watch convergence per edge: `edge_staleness_state` returns CURRENT
   (`/health/ready` -> 200); `edge_cursor_lag` -> 0.
2. Fleet-wide: `edge_fanout_consumer_state_healthy` returns to subscriber count;
   `edge_fanout_quarantines_total` stops climbing; `edge_fanout_queue_depth` baseline.
3. Quarantined/UNHEALTHY identities auto-readmit after their cooldowns (C4-3 - no
   operator action required; `edge_fanout_readmissions_total` confirms).

## Verification
- `gates/e2e-compose-scenario.sh` phase 3 is the executable miniature of this runbook
  (partition -> ladder -> re-bootstrap -> convergence).
- The governor's full state machine walk: `SlowConsumerStateMachineWalkTest` (sim) and
  `FanOutServerQuarantineTest` (wire).

## Prevention
- Edge reconnect jitter is built-in (`EdgeStreamClient`); do not zero the backoff base.
- Snapshot-vs-replay selection is automatic (`decideMode`) - there is no
  version-gap knob to mis-set.
- Run the partition legs of the integrated sim
  (`EdgeGapRecoveryTest`, `EdgeReBootstrapOnDisconnectTest`) after relevant changes.
- Size `edge.fanout.maxSessions` (admission bound) and the per-session
  `queueFrames`/`snapshotChunkBytes` for the worst-case fleet reconnect; the built-in
  pacing keeps any single transfer bounded regardless.

## Known residuals (honest)
- A long-PAUSED transfer (wedged-but-open transport) has no dedicated stalled-transfer
  signal yet - queue-depth shows pressure only; a dedicated metric is a planned observability improvement.
- Deep-store bootstraps can emit one redundant re-demote envelope per ack RTT
  (deliberate self-healing; reduced envelope frequency is a planned efficiency improvement).
- A wedged CP follower (consensus per-peer inflight accumulation) can starve the
  edges subscribed to it - the edges' staleness ladder + multi-endpoint failover is the
  containment.

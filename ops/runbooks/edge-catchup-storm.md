# Runbook: Edge Catch-Up Storm / Clock Skew

**Alert:** `ConfigdClockSkewSuspected` (`increase(edge_staleness_implausible_total[15m]) >= 1`, warn)
**Also covers (no direct alert):** edge fleet catch-up storm after a wide
partition heals, slow-consumer quarantine, forced re-bootstrap.
**Severity:** warn

> Reconciled for S6 from `docs/runbooks/edge-catchup-storm.md` (S3 doc pass,
> RR-050 runbook-fiction closure). Everything below names only machinery that
> exists at runtime and series proven emitted by `EdgeMetricsContractTest`.
> The deleted `CatchUpService` / `SlowConsumerPolicy` / `/admin/catchup-*`
> endpoints from older drafts are gone.

## Symptom

- **Clock skew:** `ConfigdClockSkewSuspected` warns — an edge reported a
  frontier dated in the future past the 500 ms HLC fence
  (`edge_staleness_implausible_total` incremented). This is a fencing
  signal, not a correctness break (the read still serves bounded-stale).
- **Catch-up storm** (capacity event, not correctness): after a wide
  partition heals, many edges resubscribe at once with stale cursors —
  `edge_fanout_connected_subscribers` jumps, `edge_rebootstrap_triggered_total`
  fires across the fleet, `edge_fanout_queue_depth` elevates. The system
  sheds via demotion/quarantine rather than stalling the control plane.

## Diagnosis

Open the `Configd Data Plane` dashboard (`ops/dashboards/configd-data-plane.json`).

1. **Clock skew.** Confirm and locate the skewed clock:
   ```sh
   kubectl -n configd exec <edge> -- \
     curl -sf http://localhost:8080/metrics \
     | grep -E '^edge_staleness_(implausible_total|ms|state)'
   ```
   `edge_staleness_implausible_total > 0` on an edge whose `edge_staleness_ms`
   is otherwise sane = that node's wall clock (or the control plane's) drifted
   past the fence. Check NTP/chrony on the edge host and on the CP leader.
2. **Storm shape — how many, which mode.** Panels **"Connected subscribers"**
   and **"Edge re-bootstrap / reconnect"**. Scrape the CP fan-out series:
   ```sh
   kubectl -n configd exec <cp> -- curl -sf http://localhost:8080/metrics | \
     grep -E '^edge_fanout_(connected_subscribers|queue_depth|demotions_(total|ack_lag_total))'
   ```
3. **Is the governor escalating (expected) or quarantining broadly (edges too
   slow to drain)?** Panels **"Slow-consumer state transitions"** and **"Edge
   consumer state population"** (`edge_fanout_consumer_state_*`,
   `edge_fanout_quarantines_total`, `edge_fanout_unhealthy_total`,
   `edge_fanout_readmissions_total`).
4. **Per-edge progress.** Panel **"Per-edge staleness"** — a healthy
   recovering edge shows `edge_staleness_ms` high but **falling**, and
   `edge_cursor_lag` draining toward 0. `/health/ready` returns 503 at
   DEGRADED+ (the LB drain signal):
   ```sh
   kubectl -n configd exec <edge> -- curl -s -o /dev/null -w '%{http_code}\n' \
     http://localhost:8080/health/ready
   ```

## Resolution steps

There is deliberately **no kill-switch rate limiter** — the bounded
per-session queues + paced transfers (RR-102) + the `SlowConsumerGovernor`
ladder ARE the rate limit. Operator levers:

1. **Clock skew:** fix time sync. Restart `chronyd`/`ntpd` on the offending
   host; once skew is back inside the 500 ms fence,
   `edge_staleness_implausible_total` stops incrementing. No Configd-side
   action — the fence is doing its job by refusing the implausible frontier.
2. **Let the ladder work.** Quarantine (refusal with `quarantineCooldownMs`
   backoff) staggers the herd automatically; readmission forces a clean,
   paced `SNAPSHOT_FIRST`. Quarantined/UNHEALTHY identities auto-readmit
   after their cooldowns — no operator action (`edge_fanout_readmissions_total`
   confirms).
3. **Stagger manually if needed.** Edges already jitter reconnects
   (`--reconnect-backoff-ms` base, doubling, ±50% jitter — `EdgeStreamClient`).
   Restart a subset of edges in waves to widen the stagger. Do **not** zero
   the backoff base.
4. **Add fan-out capacity.** Any CP node serves the fan-out port
   (`--edge-port`); point edge `--fanout-endpoints` lists at the spread so
   the herd fans across more endpoints.
5. **Tune thresholds only by config, deliberately** (`edge.fanout.policy.*`
   on the CP). Do not relax the demotion ladder to "let everyone in" — that
   reintroduces the unbounded-queue OOM the bound prevents.

## Verification

- **Clock skew:** `edge_staleness_implausible_total` flat (no further
  increase); `ConfigdClockSkewSuspected` clears after its 15m window.
- **Storm:** per edge, `edge_staleness_state` returns CURRENT
  (`/health/ready` → 200) and `edge_cursor_lag` → 0. Fleet-wide,
  `edge_fanout_consumer_state_healthy` returns to the subscriber count,
  `edge_fanout_quarantines_total` stops climbing, and
  `edge_fanout_queue_depth` returns to baseline.

## Escalation

- Page the next tier if a wedged CP follower is starving the edges
  subscribed to it (RR-103 family): the edges' staleness ladder +
  multi-endpoint failover is the containment, but a persistently wedged
  follower is a control-plane issue → [raft-saturation.md](raft-saturation.md).
- A long-PAUSED transfer (wedged-but-open transport) has no dedicated
  stalled-transfer signal yet — queue-depth shows pressure only
  (S6 residual). If queue depth stays elevated with no convergence,
  recycle the stuck edge pod.

## Validation (fault injection)

`gates/e2e-compose-scenario.sh` phase 3 is the executable miniature
(partition → demotion ladder → re-bootstrap → convergence), captured green
in `docs/session-3/captures/e2e-compose-scenario-run.txt`. The governor's
state-machine walk is `SlowConsumerStateMachineWalkTest` (sim) +
`FanOutServerQuarantineTest` (wire). Reconnect-storm recovery is asserted by
`OverloadChaosTest` (post-partition reconnect storm,
`configd-testkit/src/test/java/io/configd/testkit/OverloadChaosTest.java`).
Clock-skew injection: `SkewedClock`
(`configd-testkit/src/test/java/io/configd/testkit/SkewedClock.java`) drives
the adversarial sim; the `edge_staleness_implausible_total` emission itself is
asserted by `EdgeMetricsContractTest`. Recovery-verified = every edge returns
to `edge_staleness_state = CURRENT` and `edge_cursor_lag = 0`.

## Related

- `docs/runbooks/edge-catchup-storm.md` — the longer S3 narrative this is
  reconciled from.
- ADR-0040 poison-pill ladder; RR-100 `decideMode`; RR-102 transfer pacing.
- [overload-shedding.md](overload-shedding.md) — when the storm drives CP 429s.

# Runbook: Edge Propagation Delay (Staleness)

**Alerts:** `ConfigdEdgeStalenessWarn` (warn, `max(edge_staleness_ms) > 500`, 2m),
`ConfigdEdgeStalenessDegraded` (page, `max(edge_staleness_ms) > 2000`, 1m)
**SLO:** edge staleness p99 < 500 ms (commit → visible at every edge)
**Severity:** warn (500 ms) / page (2 s)

> The old `configd_propagation_delay_seconds` alert (`ConfigdPropagationFastBurn`)
> queried a series the app never emitted, so it could never fire, and has been
> removed. The real signal is the served `edge_staleness_ms` gauge (per edge).
> Thresholds are the consistency-contract state boundaries: CURRENT->STALE = 500 ms
> (warn), STALE->DEGRADED = 2 s (page).

## Symptom

- `ConfigdEdgeStalenessWarn` warns (an edge crossed the CURRENT→STALE
  boundary) or `ConfigdEdgeStalenessDegraded` pages (STALE→DEGRADED — fan-out
  propagation has stalled).
- `Configd Data Plane` dashboard **"Per-edge staleness"** panel
  (`edge_staleness_ms`) shows one or more edges climbing above 500 ms / 2 s
  while others stay flat.
- A config change applied at the control plane is taking longer than the
  staleness contract to become visible at the affected edge(s); apps relying
  on prompt delivery (rate limits, flags) lag.

## Diagnosis

Open `Configd Data Plane` (`ops/dashboards/configd-data-plane.json`).

1. **Single edge or fleet-wide?** **"Per-edge staleness"** (`edge_staleness_ms`)
   — one line climbing = a single stuck edge; many climbing together = a
   leader-side fan-out problem.
2. **Single-edge case.** Check that edge's cursor lag and read refusals:
   ```sh
   kubectl -n configd exec <edge> -- curl -sf http://localhost:8080/metrics | \
     grep -E '^edge_(staleness_ms|staleness_state|cursor_lag|reconnects_total|rebootstrap_triggered_total)'
   ```
   - `edge_cursor_lag` large and **not** draining → the edge's consume loop is
     stalled (CPU starvation / GC). Check `Configd Runtime` for that pod.
   - `edge_staleness_state` at DEGRADED → `/health/ready` returns 503 and the
     LB should already be draining it.
3. **Fleet-wide case.** Check the leader-side fan-out:
   ```sh
   kubectl -n configd exec <cp> -- curl -sf http://localhost:8080/metrics | \
     grep -E '^edge_fanout_(queue_depth|connected_subscribers|demotions_)'
   ```
   - `edge_fanout_queue_depth` elevated + demotions climbing → many slow
     consumers; this is often a **catch-up storm** →
     [edge-catchup-storm.md](edge-catchup-storm.md).
   - Subscribers spiking + re-bootstraps firing → also storm.
4. **Is Raft healthy?** `Configd Control Plane` **"Term changes / min"**. If
   the leader is re-electing, propagation lags because there is nothing
   committing to fan out → [control-plane-down.md](control-plane-down.md).

## Resolution steps

1. **Single lagging edge with stalled consume loop:** roll the pod —
   ```sh
   kubectl -n configd delete pod <edge>
   ```
   The replacement re-bootstraps from the latest snapshot and rejoins
   fan-out. File a bug for the stalled consume loop. A cold-cache replacement
   is briefly stale — let its bootstrap complete before declaring failure.
2. **Fleet-wide staleness from fan-out pressure / storm:** do **not** add a
   rate limiter — the bounded per-session queues + `SlowConsumerGovernor`
   ladder are the limit. Follow [edge-catchup-storm.md](edge-catchup-storm.md).
   Add fan-out capacity by pointing edge `--fanout-endpoints` at more CP nodes
   (any CP node serves the `--edge-port`).
3. **Hot-prefix write storm out-running fan-out:** rate-limit the offending
   namespace at the API gateway. Do not raise the staleness SLO.
4. **Leader churn driving it:** go to [control-plane-down.md](control-plane-down.md);
   propagation recovers once a stable leader is committing again.

## Verification

- `max(edge_staleness_ms)` returns below 500 ms; both
  `ConfigdEdgeStalenessWarn` and `ConfigdEdgeStalenessDegraded` clear after
  their windows.
- The previously-lagging edge reports `edge_staleness_state = CURRENT`
  (`/health/ready` → 200) and `edge_cursor_lag` → 0.

## Escalation

- `ConfigdEdgeStalenessDegraded` (the page) that does not clear after rolling
  the lagging edge and ruling out leader churn means the fan-out pipeline
  itself is broken → escalate to the data-plane owner and, if no edge
  converges at all, [disaster-recovery.md](disaster-recovery.md).
- Page platform if the leader's outbound link is saturated (network, not
  Configd).

## Validation (fault injection)

`gates/e2e-compose-scenario.sh` phase 3 partitions one edge
(`docker network disconnect`) and asserts the staleness ladder walks
DEGRADED then recovers to CURRENT on heal — the executable miniature of this
runbook. `EdgeMetricsContractTest.stalenessGaugesAndViolationCounterTrackTheLiveCore`
(`configd-edge-node/src/test/java/io/configd/edge/node/EdgeMetricsContractTest.java`)
drives the CURRENT→STALE transition (advance clock past 500 ms) and asserts
`edge_staleness_ms` / `edge_staleness_state` track it. Recovery-verified =
the partitioned edge returns to `edge_staleness_state = CURRENT`,
`edge_cursor_lag = 0` after the network heals.

## Related

- ADR-0039 — frontier / staleness gauge.
- [edge-catchup-storm.md](edge-catchup-storm.md), [control-plane-down.md](control-plane-down.md)
- Do not page on the monotonic-read invariant counter alone;
  `edge_staleness_ms` is the operator signal.

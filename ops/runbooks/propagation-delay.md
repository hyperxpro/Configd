# Runbook: Propagation Delay

**Alert:** `ConfigdPropagationFastBurn`
**SLO:** `propagation.delay.p99 < 500 ms` (commit → all edges)
**Severity:** page (after 2 min)

## Symptoms

- Page from `ConfigdPropagationFastBurn` after 2 minutes of breach.
- `Configd Overview` dashboard shows `configd_propagation_delay_seconds`
  p99 above 500 ms.
- Per-edge propagation lag dashboards show one or more edges growing
  unbounded while others stay flat.
  <!-- TODO PA-XXXX: metric configd_edge_apply_lag_seconds not yet
  emitted by ConfigdMetrics; until it ships, the operator-visible
  signal is the per-edge tail of the leader's `configd_propagation_
  delay_seconds` histogram broken down by destination edge. -->


## Impact

Propagation delay measures wall time from Raft commit on the leader to
the change being visible on every edge node in the cluster. Crossing
500 ms p99 indicates the change-stream fanout is degrading. Common
causes:

- a slow follower is back-pressuring the broadcast channel
- a TCP congestion window collapse on the inter-zone link
- one or more edge nodes have stalled their consume loop

Customer-facing effect: a config change applied via the control plane
takes longer than the documented < 500 ms staleness contract to become
visible at the edge — applications relying on prompt config delivery
(rate limits, feature flags) silently lag.

## Operator-Setup

Per ADR-0025 the operator must, before this runbook applies:

1. Wire `ConfigdPropagationFastBurn` from
   `ops/alerts/configd-slo-alerts.yaml` into the on-call paging
   service.
2. Provision the propagation-delay panels in the operator's Grafana
   dashboard (they ship in `ops/dashboards/configd-overview.json` and
   query `configd_propagation_delay_seconds`).
   <!-- TODO PA-XXXX: a dedicated configd_edge_apply_lag_seconds gauge
   is not yet emitted; the panels currently derive lag from the
   leader-side propagation histogram. -->


## Diagnosis

1. **Identify lagging edges.** Query the leader-side
   `configd_propagation_delay_seconds` histogram broken down by
   destination edge — the highest value is the one to investigate. If
   it's a single edge, isolate it; if it's distributed across many
   edges, the leader's broadcast path is the suspect.
   <!-- TODO PA-XXXX: configd_edge_apply_lag_seconds per-edge gauge is
   not yet emitted; the query above is the closest available signal. -->


2. **Single-edge case.**
   - Check edge's CPU and GC. A pod that lost its CPU quota will fall
     behind silently.
   - Roll the edge pod. If it returns to healthy lag immediately, file a
     post-incident bug for the consume loop.

3. **Multi-edge case.**
   - Check leader's outbound network bandwidth. Saturated link → page
     platform.
   - Check leader-side fan-out backlog (per-pod logs).
     <!-- TODO PA-XXXX: metric configd_changefeed_backlog_bytes not yet
     emitted by ConfigdMetrics; until it ships, leader logs and JFR
     captures of the propagation worker are the operator-visible
     signals. -->


4. **Verify Raft is healthy.** Propagation can lag because Raft is
   re-electing. If elections are firing repeatedly (no stable leader
   over a 2-min window per the per-pod logs), jump to
   `control-plane-down.md`.
   <!-- TODO PA-XXXX: a dedicated Leader-churn panel/metric is not yet
   emitted; the leader-id transitions in the per-pod logs are the
   current operator-visible signal. -->


## Mitigation

- For a single lagging edge: roll the pod
  (`kubectl -n configd delete pod <edge-pod>`) — the replacement
  re-bootstraps from the latest snapshot and re-joins fan-out.
- For multi-edge lag with leader-side saturation: reduce hot-prefix
  write rate at the API gateway (rate-limit the offending namespace);
  do NOT raise the propagation SLO.
- For network-induced lag: page the platform team; this is not a
  Configd-side mitigation.

## Resolution

`configd_propagation_delay_seconds` p99 returns below 500 ms across
the entire alert window. The alert clears after one full SLO
evaluation interval. The previously-lagging edge(s) report
`configd_propagation_delay_seconds` flat at the cluster baseline.
<!-- TODO PA-XXXX: dedicated configd_edge_apply_lag_seconds gauge not
yet emitted; remove the workaround once it ships. -->


## Rollback

If rolling an edge pod made the lag worse (e.g. the replacement is
slower to bootstrap than the original was lagging), let the original
pod's bootstrap complete before declaring failure — the cold cache is
a known transient. If it never converges, the bootstrap pipeline is
itself broken: escalate to `disaster-recovery.md`.

## Postmortem

Open a post-incident review within one business day. Required fields:

- Single-edge or multi-edge cause?
- Was Raft healthy throughout (or was this a downstream effect of
  leader churn → in which case the post-mortem is on
  `control-plane-down.md`)?
- Action items: was the lagging edge under-provisioned? Is there a hot
  prefix that needs application-side back-pressure?

## Related

- `ProductionSloDefinitions.PROPAGATION_DELAY_P99`
- `PropagationLivenessMonitor`
- `docs/decisions/adr-0011-fan-out-topology.md` — fan-out topology
  decision that defines the < 500 ms target.
- `docs/decisions/adr-0018-event-driven-notifications.md` — push-stream
  delivery contract.

## Do not

- Do not silence the alert thinking "propagation will catch up" — it
  often will, but a chronic 500 ms p99 means you've drifted from the
  contract. Open a ticket even if it self-recovers.

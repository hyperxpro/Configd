# F5 — Metric Drift Diff (Tier-1-METRIC-DRIFT)

Date: 2026-04-19
Scope: H-001 (S0), DOC-014 (S1), N-003, N-005, N-016.

## Method

Grep'd `ops/alerts/configd-slo-alerts.yaml`, `ops/runbooks/*.md`, and
`ops/dashboards/configd-overview.json` for `configd_*` metric names.
Cross-referenced against `MetricsRegistry` registrations in Java sources.

## Referenced (alerts) — MUST close

These metrics are queried by `ops/alerts/configd-slo-alerts.yaml`. If
they are not emitted, the alert can never fire and operators have no
SLO coverage. **All are S0 / S1.**

| Metric (alert query) | Type | Registry name | Emission point |
|---|---|---|---|
| `configd_write_commit_seconds_bucket{le="0.150"}` | Histogram | `configd.write.commit.seconds` | `ConfigStateMachine.apply` (PUT/DELETE/BATCH) |
| `configd_write_commit_seconds_count` | Histogram (derived) | same as above | same |
| `configd_write_commit_total` | Counter | `configd.write.commit` | `ConfigStateMachine.apply` |
| `configd_write_commit_failed_total` | Counter | `configd.write.commit.failed` | `ConfigStateMachine.apply` (signing fail) |
| `configd_edge_read_seconds_bucket` | Histogram | `configd.edge.read.seconds` | `HttpApiServer.handleGet` |
| `configd_propagation_delay_seconds_bucket` | Histogram | `configd.propagation.delay.seconds` | `ConfigdServer` change-listener (apply→fanout) |
| `configd_raft_pending_apply_entries` | Gauge | `configd.raft.pending.apply.entries` | `RaftLog.commitIndex - lastApplied` |
| `configd_snapshot_install_failed_total` | Counter | `configd.snapshot.install.failed` | `ConfigStateMachine.restoreSnapshot` (failure path) |

## Referenced (runbooks/dashboards) — closed for DOC-014

Runbooks reference the following metric names that operators read during
incident triage. Since DOC-014 is in scope, register them as well.

| Metric | Type | Registry name | Wire-up |
|---|---|---|---|
| `configd_edge_read_total` | Counter | `configd.edge.read` | `HttpApiServer.handleGet` (every read) |
| `configd_apply_total` | Counter | `configd.apply` (alias of `configd_write_commit_total`) | satisfied by `configd_write_commit_total`; runbook reference will be left as-is and resolved via the new counter naming |
| `configd_apply_seconds` | Histogram | `configd.apply.seconds` | `ConfigStateMachine.apply` (latency around state-machine apply step) |
| `configd_snapshot_rebuild_total` | Counter | `configd.snapshot.rebuild` | `ConfigStateMachine.restoreSnapshot` success |
| `configd_subscription_prefix_count` | Gauge (already present elsewhere) | n/a — out of F5 scope (subscription manager) | left untouched (dashboard variable; not alert-critical) |
| `configd_raft_elections_total` | Counter | n/a — out of F5 scope | left untouched (dashboard panel; not in alert) |
| `configd_build_info` | Info | n/a — out of F5 scope | left untouched (dashboard variable; build-info convention) |
| `configd_edge_apply_lag_seconds` | Histogram | n/a — out of F5 scope (edge-side metric) | left untouched (only meaningful on edge cache; not server-side) |
| `configd_changefeed_backlog_bytes` | Gauge | n/a — out of F5 scope (distribution layer) | left untouched (will be in iter-2 distribution metrics) |

## Currently registered metrics

Inventory of metric names found in `MetricsRegistry` callers (pre-F5):

- `propagation.lag.violation` (`PropagationLivenessMonitor`)
- `configd.raft.commit-count` (test / example; `PrometheusExporterTest`)

Effectively **zero** of the alert-cited metrics were registered prior to
F5 — confirming H-001's S0 finding.

## Diff (Referenced \\ Registered)

The following must be added by F5:

```
configd.write.commit                       (counter)
configd.write.commit.failed                (counter)
configd.write.commit.seconds               (histogram, ns)
configd.apply.seconds                      (histogram, ns)
configd.edge.read                          (counter)
configd.edge.read.seconds                  (histogram, ns)
configd.propagation.delay.seconds          (histogram, ns)
configd.raft.pending.apply.entries         (gauge)
configd.snapshot.install.failed            (counter)
configd.snapshot.rebuild                   (counter)
```

## Bucket-bound alignment (alerts use seconds; registry stores ns)

Alert queries use Prometheus-format `le="0.150"` (seconds, fractional).
The current `PrometheusExporter.DEFAULT_BUCKET_BOUNDS` uses long-valued
boundaries (1, 5, …, 1e9). For the histogram alerts to match the `le`
threshold, F5 adds a per-histogram bucket-bounds override map keyed by
registry name. The override pairs (le-string, ns-cutoff) are:

- `configd.write.commit.seconds`: `0.005`→5e6, `0.010`→1e7, `0.025`→2.5e7,
  `0.050`→5e7, `0.100`→1e8, `0.150`→1.5e8, `0.250`→2.5e8, `0.500`→5e8,
  `1.000`→1e9, `2.500`→2.5e9, `5.000`→5e9, `10.000`→1e10
- `configd.edge.read.seconds`: `0.0001`→1e5, `0.0005`→5e5, `0.001`→1e6,
  `0.0025`→2.5e6, `0.005`→5e6, `0.010`→1e7, `0.025`→2.5e7, `0.050`→5e7,
  `0.100`→1e8
- `configd.propagation.delay.seconds`: `0.010`→1e7, `0.025`→2.5e7,
  `0.050`→5e7, `0.100`→1e8, `0.250`→2.5e8, `0.500`→5e8, `1.000`→1e9,
  `2.500`→2.5e9, `5.000`→5e9, `10.000`→1e10
- `configd.apply.seconds`: same ladder as `configd.write.commit.seconds`

These ladders cover every `le` threshold currently in
`ops/alerts/configd-slo-alerts.yaml` and provide enough resolution
around the SLO target for histogram_quantile interpolation.

# Session 6 — Observability: SLO/SLI, Dashboards, Alerts (Workstream A)

This closes the S1 "production would be blind on a green dashboard" finding: *all 9 SLO metrics
hardwired to zero; 6/9 alerts query series that are never emitted.* S6 found the defect was still
live in three forms and fixed all three (Decision Log D-2/D-3/D-4), then wired dashboards and alerts
to series that are **proven emitted and recorded with real data** by contract tests.

## The three blind-dashboard defects found and closed

1. **Series registered but never recorded.** `ConfigdMetrics` eagerly registered the SLO series, but
   no `record()/increment()` handle was ever called in `main`, and the raft-pending gauge was
   literally `() -> 0L`. → Wired at real sites (D-2). Proof: `MetricsWiringContractTest`.
2. **Exporter built without histogram schedules.** The control-plane `PrometheusExporter` was
   constructed without `histogramSchedules()`, so the `_bucket{le=...}` series the burn-rate alerts
   query were never emitted (histograms rendered as quantile lines). → Fixed (D-3). Proof: the
   `le="0.150"` bucket assertion in `MetricsWiringContractTest`.
3. **Dashboard panels on non-existent series.** Panels 5/6 queried `configd_raft_elections_total` /
   `configd_subscription_prefix_count` that did not exist; panel 3 + an alert queried the ghost
   `configd_propagation_delay_seconds`. → elections/subscription wired real (D-4); propagation
   reconciled to the real served `edge_staleness_ms` (D-2). Proof:
   `EdgeMetricsContractTest.everyDashboardAndAlertSeriesIsProvenEmitted`.

## SLO / SLI definitions (mapped to §0.1 targets + S5 baselines)

Every SLI is backed by a **named, emitted, contract-tested** series. Thresholds are **PROPOSED** from
the S5 reference-hardware baselines, to be confirmed under real load in S7.5 (M-1…M-10).

| SLO (§0.1) | SLI series (proven emitted) | Target | S5 baseline | Alert(s) | Runbook |
|---|---|---|---|---|---|
| Control-plane write availability | `configd_write_commit_failed_total` / `configd_write_commit_total` | 99.999% | n/a | `ConfigdControlPlaneAvailability` | control-plane-down |
| Write commit latency p99 | `configd_write_commit_seconds_bucket{le=0.150}` | < 150 ms | 16 ms local (84 ms modeled X-region, M-1) | `ConfigdWriteCommitFastBurn/SlowBurn` | write-commit-latency |
| Edge read availability/latency p99 | `configd_edge_read_seconds_bucket{le=0.001}` | < 1 ms | 1.60 µs (p999 32 µs) | `ConfigdEdgeReadFastBurn`, `…P999Breach` | edge-read-latency |
| Edge staleness (propagation) p99 | `edge_staleness_ms` (gauge, per edge) | < 500 ms | 255 ms local (global M-2) | `ConfigdEdgeStalenessWarn` (500 ms) / `…Degraded` (2 s) | propagation-delay |
| Write overload shedding (RR-110) | `configd_write_rejected_overloaded_total` | — | sheds at queue=1024 (M-9/M-10) | `ConfigdWriteOverloadShedding` | overload-shedding |
| Raft apply backlog (observability) | `configd_raft_pending_apply_entries` | — | ~0 steady state | `ConfigdRaftApplyBacklog` (> 5000) | raft-saturation |
| Snapshot install integrity | `configd_snapshot_install_failed_total` | — | — | `ConfigdSnapshotInstallStalled` | snapshot-install |
| Clock-skew fence (S4 carried) | `edge_staleness_implausible_total` | 500 ms HLC fence | — | `ConfigdClockSkewSuspected` | edge-catchup-storm |
| Runtime leak (FD/thread/heap) | `process_open_fds`, `jvm_threads_current`, `jvm_heap_used_bytes`/`_max_bytes` | no monotonic growth | FD 69, thr 93, heap ~220–290 MB | `ConfigdFileDescriptorLeak`, `…ThreadLeak`, `…HeapPressure` | resource-leak |

Leader churn (`configd_raft_elections_total`, term-delta), GC (`jvm_gc_collection_millis`), and the
full fan-out/edge data-plane series are dashboard-only (no page) — all proven emitted.

## Dashboards (committed as code, `ops/dashboards/`)

| Board | File | Panels reference |
|---|---|---|
| SLO overview | `configd-overview.json` | write/edge/staleness SLIs, apply backlog, leader churn, subscribed prefixes |
| Control plane | `configd-control-plane.json` | write commit p99/p50, apply p99, write outcomes, apply backlog, term churn, snapshot/subscriptions |
| Data plane | `configd-data-plane.json` | per-edge staleness, cursor lag, fan-out queue depth, subscribers, slow-consumer transitions, consumer-state population, reads/refusals, reconnect/re-bootstrap |
| Runtime | `configd-runtime.json` | heap used/max, GC time fraction (ZGC), threads, open FDs, GC collections |

`cluster`/`instance` are Prometheus-added external labels (the registry is label-free); the app emits
bare series. Every panel's series is asserted emitted by `everyDashboardAndAlertSeriesIsProvenEmitted`.

## Alerts (committed as code, `ops/alerts/configd-slo-alerts.yaml`)

14 rules; `promtool check rules` clean. Each has a **fires-when-injected** and a
**stays-quiet-when-normal** test in `ops/alerts/configd-slo-alerts.test.yaml`
(`promtool test rules`, all green). Every threshold carries a one-line `# PROPOSED:` derivation from
the S5 baseline + margin, labeled to be confirmed under real load in S7.5. The 6/9 S1
never-emitted-series alerts are rewritten (real series) or removed (the propagation ghost).

## Reproduce

```
# fires/quiet (pinned promtool, downloaded by gate-6):
promtool check rules ops/alerts/configd-slo-alerts.yaml
promtool test  rules ops/alerts/configd-slo-alerts.test.yaml
# series-emission contract (every panel/alert series is real):
./mvnw -o -pl configd-edge-node test -Dtest=EdgeMetricsContractTest
./mvnw -o -pl configd-server     test -Dtest=MetricsWiringContractTest
```

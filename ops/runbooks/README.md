# Configd Runbooks

This directory holds runbooks for the alerts in
`ops/alerts/configd-slo-alerts.yaml`. Each alert annotates a
`runbook_url` pointing here.

| Alert | Runbook |
|-------|---------|
| `ConfigdWriteCommitFastBurn` / `SlowBurn` | [write-commit-latency.md](write-commit-latency.md) |
| `ConfigdEdgeReadFastBurn` / `…P999Breach` | [edge-read-latency.md](edge-read-latency.md) |
| `ConfigdEdgeStalenessWarn` / `…Degraded` | [propagation-delay.md](propagation-delay.md) |
| `ConfigdControlPlaneAvailability` | [control-plane-down.md](control-plane-down.md) |
| `ConfigdWriteOverloadShedding` | [overload-shedding.md](overload-shedding.md) |
| `ConfigdRaftApplyBacklog` | [raft-saturation.md](raft-saturation.md) |
| `ConfigdSnapshotInstallStalled` | [snapshot-install.md](snapshot-install.md) |
| `ConfigdClockSkewSuspected` | [edge-catchup-storm.md](edge-catchup-storm.md) |
| `ConfigdFileDescriptorLeak` / `…ThreadLeak` / `…HeapPressure` | [resource-leak.md](resource-leak.md) |
| (disk-layer branch of the above) | [disk-full-fsync.md](disk-full-fsync.md) |

## In-process burn-rate alerting

The rules above are the production alerting path: Prometheus evaluates them over scraped series.
The node **also** evaluates burn rate itself, on a `configd-slo-alerts` thread, every
`configd.slo.alertIntervalMs` (default 60000, floor 1000). It runs the same multi-window arithmetic
against the in-process `SloTracker` over the SLOs in `ProductionSloDefinitions`. This needs no
Prometheus, which is what makes it usable on a single node and during a drill.

Burn rate is `(1 - observed) / (1 - target)` — how fast the error budget is being spent relative to
spending it exactly evenly across the window. `1.0` means the budget runs out precisely at the end of
the window; `14.4` is the fast-burn multiplier, at which 2% of a 30-day budget burns in an hour. So a
`warning` is "this will exhaust the budget if it continues", and a `critical` is "this exhausts it
within the hour". Those are the multipliers the ratified thresholds in
`ops/alerts/configd-slo-alerts.yaml` encode, now evaluated rather than merely declared.

Where it surfaces:

| Series | Meaning |
|--------|---------|
| `configd_slo_burn_alerts_active` | alerts breaching at the last evaluation |
| `configd_slo_burn_alerts_fired_critical_total` | firings at or above the fast-burn multiplier |
| `configd_slo_burn_alerts_fired_warning_total` | firings at or above slow-burn, below fast-burn |
| `configd_slo_burn_evaluations_total` | completed evaluations |
| `configd_slo_burn_evaluations_failed_total` | evaluations that threw and were absorbed |

Each firing is also logged: `SLO burn-rate CRITICAL: slo=… burnRate=… window=…` (`SEVERE`), or the
same at `WARNING` for slow burn.

**Read `configd_slo_burn_evaluations_total` before believing the active gauge.** If that counter is
flat, the evaluator is not running and the gauge is stale — a stale gauge reading zero is
indistinguishable from healthy. On a failed evaluation the gauge deliberately holds its previous
value rather than resetting to zero, so a failure never renders as "no alerts"; the failure counter is
what tells the two apart. A failed evaluation is swallowed on purpose: a throwable escaping a
`scheduleAtFixedRate` task cancels every future execution, so propagating it would disable alerting
for the life of the process, silently.

## Operational runbooks (no alert trigger)

| Runbook | Purpose |
|---------|---------|
| [release.md](release.md) | Cut, sign, attest, verify, deploy a tagged release |
| [disaster-recovery.md](disaster-recovery.md) | Top-level DR coordination — escalation target |
| [restore-from-snapshot.md](restore-from-snapshot.md) | Rebuild cluster state from a verified snapshot |
| [runbook-conformance-template.md](runbook-conformance-template.md) | Definition of "passed drill" — every runbook is tested against this |

## Audience

These runbooks assume the responder is on the operator's on-call rotation
(see `docs/adr/adr-0025-on-call-rotation-required.md`) and has
shell access to the cluster, the Grafana dashboards in `ops/dashboards/`,
and `kubectl` + `curl` against the configd HTTP surface (`HttpApiServer`).

**HTTP surface.** `HttpApiServer` exposes ONLY `/health/live`,
`/health/ready`, `/metrics`, and `/v1/config/<key>` (GET/PUT/DELETE). There
is **no** `/admin/*`, `/raft/status`, or `raftctl` CLI — earlier drafts that
referenced them have been reconciled. Operator signals are: the
`X-Leader-Hint` response header (a non-leader write returns `503` + this
header), the `X-Config-Version` / `X-Configd-Cursor` headers, the `/metrics`
series, and `kubectl` for pod/PVC/StatefulSet actions. There is **no**
operator-triggerable add/remove-server RPC (`proposeMembershipChange` is
unwired): a node reset keeps its StatefulSet ordinal (= node-id), so membership
is unchanged; a permanent topology change rebuilds via restore-from-snapshot /
disaster-recovery.

## Convention

Every alert-driven runbook follows: **# Title → ## Symptom (which alert
fires, what the operator sees) → ## Diagnosis (which dashboard panel + which
emitted series, with the exact PromQL/metric) → ## Resolution steps (numbered,
copy-pasteable real commands) → ## Verification (the series/alert that must
return to normal) → ## Escalation → ## Validation (fault injection)**. Every
series named is proven emitted by a contract test; every command uses a real
CLI flag / endpoint. Where a runbook says "do not", treat it as
non-negotiable — escalate rather than ignore.

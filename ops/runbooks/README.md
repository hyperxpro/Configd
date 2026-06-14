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

## Operational runbooks (no alert trigger)

| Runbook | Purpose |
|---------|---------|
| [release.md](release.md) | Cut, sign, attest, verify, deploy a tagged release |
| [disaster-recovery.md](disaster-recovery.md) | Top-level DR coordination — escalation target |
| [restore-from-snapshot.md](restore-from-snapshot.md) | Rebuild cluster state from a verified snapshot |
| [runbook-conformance-template.md](runbook-conformance-template.md) | Definition of "passed drill" — every runbook is tested against this |

## Audience

These runbooks assume the responder is on the operator's on-call rotation
(see `docs/decisions/adr-0025-on-call-rotation-required.md`) and has
shell access to the cluster, the Grafana dashboards in `ops/dashboards/`,
and `kubectl` + `curl` against the configd HTTP surface (`HttpApiServer`).

**HTTP surface (S6-verified).** `HttpApiServer` exposes ONLY `/health/live`,
`/health/ready`, `/metrics`, and `/v1/config/<key>` (GET/PUT/DELETE). There
is **no** `/admin/*`, `/raft/status`, or `raftctl` CLI — earlier drafts that
referenced them have been reconciled. Operator signals are: the
`X-Leader-Hint` response header (a non-leader write returns `503` + this
header), the `X-Config-Version` / `X-Configd-Cursor` headers, the `/metrics`
series, and `kubectl` for pod/PVC/StatefulSet actions. Membership changes go
through the StatefulSet + `--peers` (joint consensus), not an RPC.

## Convention (S6 strict format)

Every alert-driven runbook follows: **# Title → ## Symptom (which alert
fires, what the operator sees) → ## Diagnosis (which dashboard panel + which
emitted series, with the exact PromQL/metric) → ## Resolution steps (numbered,
copy-pasteable real commands) → ## Verification (the series/alert that must
return to normal) → ## Escalation → ## Validation (fault injection)**. Every
series named is proven emitted by a contract test; every command uses a real
CLI flag / endpoint. Where a runbook says "do not", treat it as
non-negotiable — escalate rather than ignore.

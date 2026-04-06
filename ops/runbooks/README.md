# Configd Runbooks

This directory holds runbooks for the alerts in
`ops/alerts/configd-slo-alerts.yaml`. Each alert annotates a
`runbook_url` pointing here.

| Alert | Runbook |
|-------|---------|
| `ConfigdWriteCommitFastBurn` / `SlowBurn` | [write-commit-latency.md](write-commit-latency.md) |
| `ConfigdEdgeReadFastBurn` / `P999Breach` | [edge-read-latency.md](edge-read-latency.md) |
| `ConfigdPropagationFastBurn` | [propagation-delay.md](propagation-delay.md) |
| `ConfigdControlPlaneAvailability` | [control-plane-down.md](control-plane-down.md) |
| `ConfigdRaftPipelineSaturation` | [raft-saturation.md](raft-saturation.md) |
| `ConfigdSnapshotInstallStalled` | [snapshot-install.md](snapshot-install.md) |

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
shell access to the cluster, the Grafana dashboard
`ops/dashboards/configd-overview.json`, and `kubectl` + `curl` against
the configd HTTP admin surface (see `HttpApiServer`).
<!-- TODO PA-XXXX: admin endpoint missing — earlier drafts of these
runbooks referenced a `raftctl` CLI that was never built. Every
`raftctl` callsite has been replaced with the documented
`curl /admin/<endpoint>` equivalent or — where the endpoint itself
does not yet exist on `HttpApiServer` — a TODO marker pointing to
the missing surface. Search the runbooks for `TODO PA-` to enumerate
the gaps. -->


## Convention

Every runbook follows the same skeleton: **What this means → Triage in
order → Mitigate → Do not → Related.** "Do not" is non-negotiable —
treating it as advisory is how outages compound. If a step in "Do not"
seems wrong for your situation, escalate rather than ignore.

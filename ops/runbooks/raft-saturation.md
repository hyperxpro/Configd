# Runbook: Raft Apply Queue Saturation

**Alert:** `ConfigdRaftPipelineSaturation`
**Threshold:** `configd_raft_pending_apply_entries > 5000` for 5 min
**Severity:** warn

## Symptoms

- Warn from `ConfigdRaftPipelineSaturation`.
- `configd_raft_pending_apply_entries` gauge above 5 000 for at least
  5 minutes.
- `configd_apply_seconds` p99 trending upward; the leader is accepting
  faster than the state machine can apply.

## Impact

The leader has accepted entries from clients but the apply pipeline
(the state machine call that mutates the HAMT) is falling behind.
Sustained queue depth > 5 000 means write commit latency will breach
within minutes. **This alert is an early warning before
`ConfigdWriteCommitFastBurn` fires** — treat it accordingly.

## Operator-Setup

Per ADR-0025 the operator must, before this runbook applies:

1. Wire `ConfigdRaftPipelineSaturation` from
   `ops/alerts/configd-slo-alerts.yaml` into the on-call notification
   channel (warn-level, not necessarily a page).
2. Have JFR / async-profiler available on the leader pod (the
   `docker/Dockerfile.runtime` JDK image already includes `jcmd`).

## Diagnosis

1. **Check apply throughput.** `rate(configd_apply_seconds_count[1m])` —
   compare to ingress `rate(configd_write_commit_total[1m])`. If apply <
   ingress, queue grows.
   <!-- TODO PA-XXXX: counter configd_apply_total not yet emitted; the
   `_count` series of the `configd_apply_seconds` histogram is the
   canonical apply-rate signal until a dedicated counter ships
   alongside the histogram in ConfigdMetrics. -->


2. **Profile the apply hot path.** `jcmd <leader-pid> JFR.start
   duration=30s filename=apply.jfr`. The usual culprits:
   - HAMT mutation cost regression on a deep prefix
   - Signature verification overhead spike
   - GC pause during apply (check ZGC logs)

## Mitigation

- Step down the leader to a less-loaded voter
  (`kubectl -n configd delete pod <leader-pod>` — the StatefulSet
  PDB ensures only the current leader rolls; re-election picks a
  fresh voter).
- If JFR shows signature verification dominating: confirm the
  configured `ConfigSigner` library hasn't been swapped for a slow
  implementation. The shipped library is pinned in `pom.xml` for a
  reason.
- If a hot-prefix write burst is the cause: rate-limit the offending
  namespace at the API gateway. Do NOT raise the apply-queue capacity.

## Resolution

`configd_raft_pending_apply_entries` returns below 5 000 sustained.
The warn alert clears. Apply throughput matches or exceeds ingress
rate. If a follow-up `ConfigdWriteCommitFastBurn` did not fire, the
mitigation succeeded; otherwise switch to `write-commit-latency.md`.

## Rollback

If stepping down the leader made things worse (the new leader is even
slower), transfer back via the same mechanism — there is no
hard-to-revert state in a leader transfer.

## Postmortem

Open a post-incident review only if the apply queue stayed saturated
for > 30 minutes or escalated into the write-commit alert. Required
fields:

- Was the cause a hot prefix, a signature library swap, a HAMT
  regression, or GC?
- Was the JFR profile attached to the incident ticket?
- Action items: capacity calibration update in `docs/perf-baseline.md`
  if the baseline write rate has shifted.

## Related

- `ProductionSloDefinitions.WRITE_COMMIT_LATENCY_P99` (downstream effect)
- `docs/decisions/adr-0001-embedded-raft-consensus.md` — apply pipeline
  ownership.
- `docs/decisions/adr-0012-purpose-built-storage-engine.md` — HAMT
  mutation cost contract.

## Do not

- Do not increase the apply queue capacity. The queue is bounded for
  back-pressure on purpose. Lifting the bound just delays the eventual
  symptom.

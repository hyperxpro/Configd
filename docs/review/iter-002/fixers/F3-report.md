# F3 — Operator / Runbook / Alert / K8s Drift — iter-002 Report

**Date:** 2026-04-19
**Lane:** F3 (operations only — no Java code touched, no commits made)

## Tasks Completed

### 1. H-001 (S0) — restore-snapshot.sh kubectl rewrite
- `ops/scripts/restore-snapshot.sh` (full rewrite). Replaced every
  `systemctl` invocation with `kubectl scale statefulset` calls; added
  `--namespace`/`-n` (default `configd`, env `CONFIGD_NAMESPACE`) and
  `--replicas` (default 3, env `CONFIGD_REPLICAS`); detect kubectl on
  PATH via `command -v kubectl` and fail-close (exit 3) with a clear
  error if absent (lines 121-124). New ordering: (a) scale to 0
  (lines 219-228), (b) `kubectl wait --for=delete pod -l app=...`
  120 s timeout (lines 230-234), (c) snapshot stage / conformance
  (lines 245-285), (d) scale back to `--replicas` and rollout-status
  300 s (lines 290-310).

### 2. H-002 (S0) — restore-from-snapshot.md flag sync
- `ops/runbooks/restore-from-snapshot.md` lines 226-234. Replaced
  `--snapshot=` with `--snapshot-path` and added the required
  `--target-cluster configd` flag, plus a one-paragraph note that all
  three flags are required.

### 3. R-008 (S1) — StatefulSet command/entrypoint reconciliation
- `deploy/kubernetes/configd-statefulset.yaml` lines 79-104. Dropped
  the `command:` override (which invoked `-jar configd-server.jar`,
  not present in the image) in favour of the image ENTRYPOINT
  (`java -cp libs/* io.configd.server.ConfigdServer`); kept server
  CLI flags under `args:`.

### 4. H-007 (S1) — HTTPS probes
- `deploy/kubernetes/configd-statefulset.yaml` lines 117-145. Added
  `scheme: HTTPS` to both readinessProbe and livenessProbe httpGet
  blocks; comment notes HttpApiServer serves `/health/*` on the same
  listener as the rest of the API (HttpApiServer.java:100-101).

### 5. H-003 (S1) — release.md rollback success criteria
- `ops/runbooks/release.md` lines 272-291. Replaced
  `/admin/raft/status | jq .commit_index` with `X-Config-Version`
  header on `/v1/config/__release_probe__` (PA-XXXX TODO marker
  added, matching N-108 fix proposal); replaced
  `configd_write_failure_total` with the actual
  `configd_write_commit_failed_total`; replaced
  `configd_slo_burn_rate_1h` with
  `count(ALERTS{alertname=~...,alertstate="firing"}) == 0` plus
  PA-XXXX TODO marker.

### 6. H-004 + H-005 + H-006 (S1) — alert math fixes
- `ops/alerts/configd-slo-alerts.yaml` (single file holds all three
  alerts).
  - **H-005:** lines 57-105. Rewrote `ConfigdEdgeReadFastBurn` as a
    real MWMBR burn-rate alert (`bucket{le="0.001"}/count` < `(1 -
    14.4 * (1 - 0.99))`); added matching `ConfigdEdgeReadSlowBurn`
    (1 h / 6×). Kept P999 raw threshold as a warn-only symptom view.
  - **H-004:** lines 130-167. Changed
    `ConfigdControlPlaneAvailability` denominator to `failed +
    total` so a complete outage (zero successes) no longer divides by
    zero; the Java rename (success counter → `_succeeded_total`,
    new attempts `_total`) is tracked separately.
  - **H-006:** lines 181-198. Threshold now `≥ 3 in 15 min` for `5m`
    on `ConfigdSnapshotInstallStalled` (debounce). Symptoms section
    of `ops/runbooks/snapshot-install.md` lines 3-26 updated.

### 7. H-011 (S2) — runbook metric drift sweep
- `ops/runbooks/raft-saturation.md` line 35: `configd_apply_total` →
  `configd_apply_seconds_count` + TODO marker.
- `ops/runbooks/propagation-delay.md` lines 12-13, 38-43, 45-50,
  59-63, 64-71, 80-87: `configd_edge_apply_lag_seconds`,
  `configd_changefeed_backlog_bytes`, `Leader churn` panel — all
  replaced with closest existing signal + TODO markers.
- `ops/runbooks/edge-read-latency.md` lines 11-15, 71-72, 96-100:
  `configd_edge_read_latency_seconds` →
  `configd_edge_read_seconds`; `configd_slo_budget_seconds_remaining`
  → SloTracker + TODO marker.
- `ops/runbooks/write-commit-latency.md` lines 40-49, 96-100,
  107-110: `Leader churn` → log scraping + TODO marker;
  `configd_slo_budget_seconds_remaining` → SloTracker + TODO marker.
- `ops/runbooks/disaster-recovery.md` line 134:
  `configd_edge_staleness_seconds` →
  `configd_propagation_delay_seconds` + TODO marker.
- `ops/runbooks/restore-from-snapshot.md` line 223: same staleness
  rewrite + TODO marker.
- `ops/runbooks/snapshot-install.md` lines 13-26, 105-114:
  `configd_snapshot_install_total`, `configd_raft_follower_lag`,
  `configd_raft_last_applied_index` — TODO markers added; parity
  check switched to `configd_raft_pending_apply_entries` /
  X-Config-Version.
- `ops/runbooks/control-plane-down.md` lines 73-83, 100-105:
  `Leader churn` → log scraping + TODO marker (also fixes N-109).

### 8. N-105 (S2) — docs/runbooks deprecation banner
- `docs/runbooks/README.md` (new file, single banner per spec).
- One-line deprecation banner header added to all 8 stale files:
  `cert-rotation.md`, `edge-catchup-storm.md`, `leader-stuck.md`,
  `poison-config.md`, `reconfiguration-rollback.md`, `region-loss.md`,
  `version-gap.md`, `write-freeze.md` (line 3 of each).

### 9. N-106 (S2) — leader-stuck.md `*Ms` → `*Ticks`
- `docs/runbooks/leader-stuck.md` lines 47-52. Rewrote to use
  `electionTimeoutMin/MaxTicks` (default 15-30 ticks ≈ 150-300 ms
  wall) and `heartbeatIntervalTicks` (default 5 ticks ≈ 50 ms wall);
  cited iter-1 D-003 rename. (Banner per N-105 also applied at top.)

### 10. N-109 (S2) — control-plane-down.md selector
- `ops/runbooks/control-plane-down.md` line 73-82. Selector
  `app=configd-server` → `kubectl -n configd get pods -l app=configd`.

## Tasks Deferred

None of the assigned tasks were deferred. All ten items in the F3
dispatch were closed in this iteration. The Java-side parts of H-004
(rename `_total` → `_succeeded_total` and emit attempts on every
apply) were intentionally NOT touched (constraint: ops-only lane); the
alert was fixed via the math-only Option (b) so the alert itself is
correct today, and the Java work is recorded in the alert YAML
comment for the next consensus-lane subagent to pick up.

## Syntax Checks

Both restore scripts parse cleanly:

```
$ bash -n ops/scripts/restore-snapshot.sh && echo "restore-snapshot.sh OK"
restore-snapshot.sh OK

$ bash -n ops/scripts/restore-conformance-check.sh && echo "restore-conformance-check.sh OK"
restore-conformance-check.sh OK
```

## Files Modified

- `ops/scripts/restore-snapshot.sh` (full rewrite)
- `ops/runbooks/restore-from-snapshot.md`
- `ops/runbooks/release.md`
- `ops/runbooks/snapshot-install.md`
- `ops/runbooks/raft-saturation.md`
- `ops/runbooks/propagation-delay.md`
- `ops/runbooks/edge-read-latency.md`
- `ops/runbooks/write-commit-latency.md`
- `ops/runbooks/disaster-recovery.md`
- `ops/runbooks/control-plane-down.md`
- `ops/alerts/configd-slo-alerts.yaml`
- `deploy/kubernetes/configd-statefulset.yaml`
- `docs/runbooks/README.md` (new)
- `docs/runbooks/cert-rotation.md`
- `docs/runbooks/edge-catchup-storm.md`
- `docs/runbooks/leader-stuck.md`
- `docs/runbooks/poison-config.md`
- `docs/runbooks/reconfiguration-rollback.md`
- `docs/runbooks/region-loss.md`
- `docs/runbooks/version-gap.md`
- `docs/runbooks/write-freeze.md`

No git commits were created; all edits remain in the working tree.

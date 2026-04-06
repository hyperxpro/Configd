# Runbook: Snapshot Install Failure

**Alert:** `ConfigdSnapshotInstallStalled`
**Threshold:** ≥ 3 `configd_snapshot_install_failed_total` increases in
last 15 min, sustained for 5 min (H-006 closure, iter-2)
**Severity:** warn

## Symptoms

- Warn from `ConfigdSnapshotInstallStalled` after a sustained failure
  rate (≥ 3 in 15 min). A single transient is below threshold and
  self-clears via the leader's automatic retry — see Mitigation.
- Leader logs contain `InstallSnapshot RPC failed` lines naming a
  specific follower peer.
- `configd_snapshot_install_failed_total` increments without a matching
  successful install for the same follower.
  <!-- TODO PA-XXXX: metric configd_snapshot_install_total not yet
  emitted by ConfigdMetrics; the failure counter is the only direct
  signal. The leader's per-follower nextIndex (logs) is the indirect
  signal until the success counter ships. -->
- One follower's `commit_index` lag stays flat after a leader log
  truncation — it is no longer able to catch up via log replication
  and the snapshot path is its only route.
  <!-- TODO PA-XXXX: metric configd_raft_follower_lag not yet emitted
  by MetricsRegistry; leader logs are the operator-visible signal until
  the gauge ships. -->


## Impact

A follower attempted to catch up via `InstallSnapshot` and it failed.
This is normally invisible (followers retry), but a sustained failure
means the follower will eventually fall outside the leader's log
retention window and be effectively excluded from quorum until manual
intervention. Once excluded, the cluster is one voter closer to losing
quorum on the next failure — the alert is a leading indicator of
quorum-fragility, not a customer-facing latency event in itself.

## Operator-Setup

Per ADR-0025 the operator must, before this runbook applies:

1. Wire `ConfigdSnapshotInstallStalled` from
   `ops/alerts/configd-slo-alerts.yaml` into the on-call notification
   channel (warn-level).
2. Hold the public half of the snapshot signing key on the operator
   workstation; the conformance test in
   `configd-consensus-core/src/test/java/io/configd/raft/SnapshotInstallSpecReplayerTest.java`
   relies on the same chain of trust.

## Diagnosis

1. **Identify the follower.** Look at the leader's logs around the
   alert window — `InstallSnapshot RPC failed` includes the follower
   peer ID.

2. **Identify the failure class.**
   - Network: connection refused / timeout → check follower pod health.
   - Snapshot integrity: `SnapshotChecksumMismatch` → see the formal
     spec `spec/SnapshotInstallSpec.tla` and the conformance test in
     `configd-consensus-core/src/test/java/io/configd/raft/SnapshotInstallSpecReplayerTest.java`.
   - Disk full on follower → `kubectl exec <follower> df -h`.

3. **Check leader-side log retention.** If the follower's
   `nextIndex` is older than the leader's earliest retained log entry,
   only the snapshot path can recover it; raising retention will not
   help and may harm steady-state memory. See "Do not" below.

## Mitigation

For a network or transient disk-pressure failure: wait one retry
window. The leader retries `InstallSnapshot` automatically and most
transients self-clear.

For a stuck follower missing log entries past the leader's retention,
manually drain and re-add it as a fresh voter:

- Drain it from the cluster.
  <!-- TODO PA-XXXX: admin endpoint missing — there is no
  `POST /admin/raft/remove-server` handler on HttpApiServer today.
  Until it ships, the operator must scale the StatefulSet down so
  the follower terminates and re-deploy with the smaller `--peers`
  list, allowing the surviving quorum to commit a joint-consensus
  reconfiguration that excludes the failing voter. -->
  ```sh
  curl -fsS -X POST \
    -H "Authorization: Bearer ${CONFIGD_AUTH_TOKEN}" \
    "http://configd-0.configd.svc:8080/admin/raft/remove-server?peer=<follower-id>"
  ```
- Wipe its persistent volume.
- Re-add as a fresh voter. The catch-up will re-issue InstallSnapshot
  from a clean state.
  <!-- TODO PA-XXXX: admin endpoint missing — same gap as above for
  `POST /admin/raft/add-server`. -->
  ```sh
  curl -fsS -X POST \
    -H "Authorization: Bearer ${CONFIGD_AUTH_TOKEN}" \
    "http://configd-0.configd.svc:8080/admin/raft/add-server?peer=<follower-id>"
  ```
- Verify quorum is restored before marking the incident closed.

## Resolution

The incident is resolved when:

- `configd_snapshot_install_failed_total` stops incrementing for the
  affected follower for at least one full alert window.
- The follower's `commit_index` matches the leader's within one
  heartbeat (compare `configd_raft_pending_apply_entries` ≈ 0 across
  pods; the follower's served `X-Config-Version` header on a probe key
  is a coarser bound).
  <!-- TODO PA-XXXX: gauge configd_raft_last_applied_index not yet
  emitted by MetricsRegistry; once it ships, switch the parity check
  to that gauge. -->

- A test write commits and is observed on the previously-stuck follower
  via its own `/health/ready` and metrics surface.

## Rollback

If the manual drain-and-rejoin made things worse (e.g. the freshly
re-added voter cannot catch up either), the symptom is no longer
"snapshot install" but a deeper cluster-state problem — escalate to
`disaster-recovery.md`. There is no rollback for a wiped PV; the
forensic backup taken in `disaster-recovery.md` step 3 is the only
recovery path if the wipe was a mistake.

## Postmortem

Open a post-incident review only if:

- Two or more followers hit `ConfigdSnapshotInstallStalled` in the same
  window (suggests a leader-side snapshot issue, not a follower issue),
  or
- The manual drain-and-rejoin was needed (suggests retention tuning or
  a snapshot-format regression).

Required fields:

- Failure class (network, integrity, disk, or other)?
- Was `SnapshotChecksumMismatch` involved? If yes, attach the
  `SnapshotInstallSpec.tla` invariant violation trace.
- Action items: retention tuning, snapshot-format compatibility audit,
  or operator drill update.

## Related

- `spec/SnapshotInstallSpec.tla` — formal model of snapshot install
- `docs/decisions/adr-0028-snapshot-on-disk-format.md` — snapshot
  on-disk / on-wire format ADR (TLV trailer, signing-epoch carry-over,
  envelope bounds); `SnapshotConsistency` invariant background
- `docs/decisions/adr-0001-embedded-raft-consensus.md` — log retention
  vs. snapshot trade-off ownership
- [restore-from-snapshot.md](restore-from-snapshot.md)
- [disaster-recovery.md](disaster-recovery.md)

## Do not

- Do not bypass the snapshot checksum check. It exists to guarantee the
  TLA+-proven `SnapshotConsistency` invariant.
- Do not lower the log retention to "fix" the catch-up window — that
  trades a recoverable problem for an unrecoverable one.
- Do not wipe a follower's PV without first confirming via the leader
  logs that it is the failing follower; wiping the wrong PV converts a
  recoverable warn into a quorum-loss page.

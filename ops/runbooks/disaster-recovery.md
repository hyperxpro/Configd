# Runbook: Disaster Recovery — Coordination

**Purpose:** the top-level reference when the cluster is in a state
that the per-symptom runbooks cannot recover. This runbook is the
escalation target referenced by `control-plane-down.md` and
`snapshot-install.md`.

## Symptoms

A scenario is a disaster — and this runbook applies — when one or more
of the following are true:

- **Quorum lost beyond automatic recovery.** Fewer than ⌊N/2⌋ + 1
  voters are healthy and Raft cannot elect a leader.
- **Persistent corruption.** Snapshot checksum mismatch on ≥ 2 voters
  (`SnapshotConsistency` invariant violated, see
  `spec/SnapshotInstallSpec.tla`).
- **Data loss suspected.** A successful client commit that is not
  observed on any surviving voter after recovery.
- **Cross-cluster compromise.** The signing key (Ed25519, see
  `ConfigStateMachine.signCommand` and ADR-0027) is suspected leaked.

If none of those, return to the per-symptom runbook — disaster
declaration has high overhead and should not be used loosely.

## Impact

The cluster is unable to serve writes (no leader, or the only candidate
leader fails-close), or it is serving them but consumers cannot trust
the data. Edge reads may continue from the local HAMT for the duration
of the bounded-staleness window, but they will eventually trip
`ConfigdEdgeReadFastBurn` once the snapshot rebuild stalls.

The error budget is being spent in real time. Customer-facing config
deploys are blocked until recovery completes.

## Operator-Setup

Per ADR-0025 the operator must, before this runbook applies:

1. Hold the public half of the Ed25519 signing keypair on each
   incident-commander workstation, mounted at the path expected by
   `restore-from-snapshot.md` step 2.
2. Maintain an off-cluster snapshot store (S3 / GCS / equivalent) and
   ship `ops/scripts/restore-snapshot.sh` to a path on the bastion.
3. Have an incident-commander pool large enough for 24/7 coverage — the
   destructive recovery branches require explicit IC sign-off.
4. Train operators that the interactive `read -r -p` confirmation in
   the destructive section below is intentional friction; bypassing it
   is a known data-loss risk.

## Diagnosis

### Incident commander first 5 minutes

1. **Declare the incident.** Page the on-call rotation procured by the
   operator (see `docs/decisions/adr-0025-on-call-rotation-required.md`).
2. **Freeze writes.** At the API gateway, set the read-only flag.
   Confirm via `configd_write_commit_total` rate going to 0.
3. **Snapshot current state.** Even if state is corrupt, capture for
   forensics:
   ```sh
   for pod in configd-0 configd-1 configd-2; do
     kubectl exec $pod -- tar czf - /data > /backup/${pod}-$(date -Is).tgz
   done
   ```
4. **Stop reconciliation loops.** Disable any operator that might mutate
   the cluster while you investigate (e.g. cluster autoscaler).
5. **Start the incident timer.** Every minute past the 30-min mark
   triggers customer communication per the SLA.

### Recovery decision tree

#### Quorum lost (no leader, not recoverable)

→ Go to **Restore from snapshot** in
`ops/runbooks/restore-from-snapshot.md`. This is destructive of any
in-flight (uncommitted) writes; obtain incident-commander sign-off.

#### Persistent corruption

1. Identify the last known-good snapshot from S3 / object store.
2. Verify the snapshot's signature against the operator-held public key.
3. Follow `restore-from-snapshot.md` with that snapshot as input.
4. Open a post-incident bug for the corruption root cause; do not
   close until reproduced in test.

#### Data loss suspected

1. **Do not** restore yet. Restoring overwrites forensic evidence.
2. Take filesystem-level backups of all voter data dirs (step 3 above
   if you haven't already).
3. Compare the missing commit's signed envelope against
   `audit_log` (when AuditLogger lands per S8 — until then this step
   is best-effort with whatever logs exist).
4. Engage the incident commander; data-loss recovery is an
   organisational decision, not a runbook one.

#### Signing key compromise

1. **Rotate the Ed25519 keypair immediately.** The new key starts
   signing all subsequent commits.
2. **Republish the public half** to all clients via the operator's
   key-distribution channel.
3. **Audit all commits since the suspected compromise window.** Any
   commit whose signature verifies against the old key is
   provisionally trusted but flagged for human review.
4. Open a post-incident review covering the compromise vector.

## Mitigation

The decision-tree branches above ARE the mitigation. The mitigation
chosen depends on which symptom is observed:

- Quorum lost → `restore-from-snapshot.md`.
- Persistent corruption → snapshot restore from a verified earlier
  snapshot (same runbook, different input).
- Data-loss suspected → freeze, forensic backup, escalate to operator
  leadership.
- Signing-key compromise → rotate keypair, re-publish public half
  (per ADR-0027 fail-close contract).

During mitigation, keep writes frozen and avoid touching the data
volumes more than the chosen branch requires.

## Resolution

The incident is **resolved** when:

- All voters report ready and are at the same `commit_index`.
- A test write commits successfully end-to-end (round-trip from API
  gateway → leader → quorum → state machine).
- The write freeze at the API gateway has been lifted.
- Edge propagation p99 (`configd_propagation_delay_seconds`) returns to
  its normal baseline.
  <!-- TODO PA-XXXX: metric configd_edge_staleness_seconds not yet
  emitted by ConfigdMetrics; the propagation-delay histogram is the
  closest registered signal until a dedicated edge-staleness gauge
  ships. -->

- For the signing-key-compromise branch: every edge has the new public
  key and the old key has been revoked from the edge bundle (after the
  ≥ 2-release deprecation window per §8.10).

### Reset and re-bootstrap (last resort)

> **DESTRUCTIVE — DATA LOSS — irreversible.**
> Every step in this section permanently destroys cluster state.
> The interactive confirmation prompt below is intentional friction:
> the operator must type the cluster name out loud (in shell) before
> the destructive `kubectl delete pvc` command runs. Do **not** wrap
> these commands in automation, do **not** silence the prompt, and do
> **not** run this section without explicit incident commander sign-off.

When all else fails, you bring up a fresh cluster from the most recent
verified-good snapshot. Set the expected cluster name once at the top
of your shell so the confirmation prompt below has something to compare
against:

```sh
export EXPECTED_CLUSTER="configd"   # the namespace / cluster identifier
```

```sh
# Drain everything
kubectl delete -n "$EXPECTED_CLUSTER" statefulset/configd

# Wipe PVCs (DESTRUCTIVE — only do this with incident commander sign-off).
# The read prompt is the last guard before irreversible data loss; do
# not paste past it, do not script around it.
read -r -p "Type the cluster name to confirm destructive PVC delete: " confirm
if [ "$confirm" != "$EXPECTED_CLUSTER" ]; then
  echo "Aborted: cluster name mismatch."
  exit 1
fi
kubectl delete -n "$EXPECTED_CLUSTER" pvc -l app=configd

# Restore the snapshot to a fresh PVC (operator-specific glue). The
# script defaults to --dry-run=true; pass --dry-run=false and
# --i-have-a-backup explicitly to perform the restore.
./ops/scripts/restore-snapshot.sh \
  --snapshot-path=<local-path-to-snapshot> \
  --target-cluster="$EXPECTED_CLUSTER" \
  --dry-run=false \
  --i-have-a-backup

# Bring up a single-voter cluster, then add voters one at a time
kubectl apply -f deploy/kubernetes/configd-bootstrap.yaml
# ... wait for leader election ...
# Add voter 1 via the Raft admin HTTP API. The control-plane REST
# surface lives on the API port (default 8080) of any current voter.
# <!-- TODO PA-XXXX: admin endpoint missing — HttpApiServer does not
# yet expose POST /admin/raft/add-server. Until it lands, the operator
# must restart the StatefulSet replica with the new --peers list and
# rely on Raft joint-consensus to pick up the change at boot. -->
curl -fsS -X POST \
  -H "Authorization: Bearer ${CONFIGD_AUTH_TOKEN}" \
  "http://configd-0.configd.svc:8080/admin/raft/add-server?peer=configd-1"
# ... wait for catch-up ...
curl -fsS -X POST \
  -H "Authorization: Bearer ${CONFIGD_AUTH_TOKEN}" \
  "http://configd-0.configd.svc:8080/admin/raft/add-server?peer=configd-2"
```

Do this **only** with incident commander sign-off and only when the
preceding paths have been ruled out.

## Rollback

If the chosen recovery branch made the situation worse:

- For a failed snapshot restore: stop, do NOT delete the forensic
  backup taken in Diagnosis step 3; restore the failed voter's `/data`
  from that backup and pick a different (older) verified snapshot.
- For a mistaken `kubectl delete pvc` (e.g. wrong namespace): there is
  no rollback once the storage provider has reclaimed the volume — the
  data is gone. This is why the interactive confirmation guard exists;
  do NOT bypass it.
- For a key-rotation that broke verification on edges: keep the OLD
  public key in the edge bundle until every edge has the NEW one, then
  remove the OLD one in a follow-up roll. Per §8.10 (deprecation ≥ 2
  releases), this is the only path that honours the wire-compat
  contract.

## Postmortem

A disaster declaration ALWAYS triggers a post-incident review,
regardless of recovery time. Required fields:

- Which symptom branch was hit and when.
- Whether the runbook was sufficient or required improvisation
  (improvisation is a runbook bug — file an issue).
- Time-to-detect, time-to-declare, time-to-mitigate, time-to-resolve.
- Customer impact: how many config-write requests failed, what the
  staleness window looked like at the edge, whether any application
  was exposed to inconsistent config.
- Action items: code fixes, runbook edits, alert improvements, drill
  cadence adjustments (`runbook-conformance-template.md`).
- Sign-off by incident commander + service owner.

## Do not

- Do not restore from a snapshot whose signature does not verify. The
  signing chain exists to detect this.
- Do not skip step 3 (forensic backup) to recover faster. Data
  forensics is non-recoverable; cluster state is.
- Do not use `kubectl delete --force` on PVCs — that can leave volume
  attachments in inconsistent state on the storage provider.

## Related

- [restore-from-snapshot.md](restore-from-snapshot.md)
- [control-plane-down.md](control-plane-down.md)
- [snapshot-install.md](snapshot-install.md)
- `docs/decisions/adr-0025-on-call-rotation-required.md`
- `docs/decisions/adr-0027-sign-or-fail-close.md` — sign-or-fail-close
  decision invoked in the "Signing key compromise" branch.
- `spec/SnapshotInstallSpec.tla`

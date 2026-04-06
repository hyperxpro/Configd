# Runbook: Restore from Snapshot

**Purpose:** rebuild the cluster's persistent state from a previously
captured Raft snapshot. Reached from `disaster-recovery.md`. **This
runbook is destructive of in-flight uncommitted writes.**

## Symptoms

This runbook is not alert-driven; it is **invoked from**
`disaster-recovery.md` after one of the following triggered:

- Quorum lost beyond automatic recovery (no leader after the operator
  has waited out the election timeout).
- Persistent corruption (snapshot checksum mismatch on ≥ 2 voters,
  i.e. `SnapshotConsistency` invariant violated, see
  `spec/SnapshotInstallSpec.tla`).
- An incident commander has authorised destructive recovery.

If you arrived here without coming through `disaster-recovery.md`,
**stop** and start there. The decision tree in disaster-recovery is
what justifies the destructive steps below.

## Impact

Running this runbook permanently destroys all in-flight uncommitted
writes (those accepted by the leader but not yet replicated to
quorum), wipes every voter's persistent volume, and re-bootstraps the
cluster from a single voter loaded from the chosen snapshot. The
recovery window is bounded by:

- snapshot download time (operator network),
- single-voter bootstrap (minutes),
- two follower catch-up cycles via `InstallSnapshot` (minutes each).

Customer-facing config writes remain frozen at the API gateway for the
entire duration. Edge reads continue to serve cached values within the
bounded-staleness window; if the runbook overruns that window,
`ConfigdEdgeReadFastBurn` will fire.

## Operator-Setup

Per ADR-0025 the operator must, before this runbook applies:

1. Have an off-cluster snapshot store (S3 / GCS / equivalent) with
   recent verified-good snapshots and their detached `.sig` siblings.
2. Hold the public half of the Ed25519 snapshot signing keypair at the
   path expected by Step 2 below (`/etc/configd/signing.pub` is the
   reference path).
3. Have the operator-specific restore image (referenced in Step 5) and
   `ops/scripts/restore-snapshot.sh` shipped to the bastion.
4. Hold incident-commander sign-off per `disaster-recovery.md`.

## Diagnosis

The `disaster-recovery.md` decision tree has already established
**which** snapshot to restore from. The remaining diagnostic question
this runbook answers is **whether the chosen snapshot is itself
trustworthy**:

1. **Identify the candidate snapshot.**

   ```sh
   # List recent snapshots from the operator's snapshot store. Format
   # depends on operator integration; for the reference S3 bucket:
   aws s3 ls s3://<operator-bucket>/configd-snapshots/ \
     --recursive \
     | sort -k1,2 -r \
     | head
   ```

   Pick the most recent snapshot **whose signature verifies** (next
   step). Older snapshots are valid fallbacks if the most recent fails
   to verify.

2. **Verify the snapshot signature.**

   ```sh
   # Download
   aws s3 cp s3://<bucket>/configd-snapshots/<file>.snap /tmp/restore.snap
   aws s3 cp s3://<bucket>/configd-snapshots/<file>.snap.sig /tmp/restore.snap.sig

   # Verify against the operator-held public key
   openssl pkeyutl -verify -pubin -inkey /etc/configd/signing.pub \
     -sigfile /tmp/restore.snap.sig \
     -rawin -in /tmp/restore.snap
   ```

   If verification fails: **STOP.** Either the snapshot is corrupt or
   the signing chain has been compromised. Return to
   `disaster-recovery.md`.

## Mitigation

The mitigation is the destructive restore sequence itself. Each step
must complete successfully before moving to the next; do not parallelise.

### Step 1. Drain the existing cluster

```sh
# Scale to zero (does not delete PVCs)
kubectl -n configd scale statefulset/configd --replicas=0

# Wait for all pods to terminate
kubectl -n configd wait --for=delete pod -l app=configd --timeout=120s
```

### Step 2. Wipe the data volumes

DESTRUCTIVE — only proceed with incident commander sign-off.

```sh
for i in 0 1 2; do
  kubectl -n configd delete pvc data-configd-$i
done

# Wait for PVCs to be released by the storage provider
kubectl -n configd wait --for=delete pvc -l app=configd --timeout=300s
```

### Step 3. Bring up voter-0 from the snapshot

```sh
# Restore the snapshot into the new PVC for voter-0 (operator glue,
# typically a Job that mounts the PVC and untars the snapshot)
kubectl -n configd apply -f - <<EOF
apiVersion: batch/v1
kind: Job
metadata:
  name: configd-restore-0
spec:
  template:
    spec:
      restartPolicy: OnFailure
      containers:
        - name: restore
          image: <operator-restore-image>
          env:
            - name: SNAPSHOT_URI
              value: s3://<bucket>/configd-snapshots/<file>.snap
          volumeMounts:
            - name: data
              mountPath: /data
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: data-configd-0
EOF

kubectl -n configd wait --for=condition=complete job/configd-restore-0 --timeout=600s
```

### Step 4. Bring up the cluster as a single-voter fresh cluster

The state machine starts with the snapshot loaded; Raft starts as a
1-of-1 cluster (since the other voters' PVCs are empty).

```sh
kubectl -n configd scale statefulset/configd --replicas=1
kubectl -n configd wait --for=condition=ready pod/configd-0 --timeout=300s
```

Verify health:

```sh
kubectl -n configd exec configd-0 -- \
  curl -sf http://localhost:8080/health/ready

# Verify state is loaded — the snapshot's last index should be visible
# via the Prometheus exposition. The `configd_raft_last_applied_index`
# gauge (when wired) should equal the snapshot's lastIncludedIndex.
# <!-- TODO PA-XXXX: admin endpoint missing — `/raft/status` does not
# exist on HttpApiServer. The `configd_raft_last_applied_index` gauge
# is not yet emitted by MetricsRegistry either; until both ship the
# operator must approximate via the `X-Config-Version` response header
# returned on a GET /v1/config/<probe-key>. -->
kubectl -n configd exec configd-0 -- \
  curl -sf http://localhost:8080/metrics \
  | grep -E '^configd_raft_last_applied_index' || true
```

### Step 5. Re-add the other voters one at a time

```sh
kubectl -n configd scale statefulset/configd --replicas=2
kubectl -n configd wait --for=condition=ready pod/configd-1 --timeout=300s
# Voter 1 will catch up via InstallSnapshot from the leader.
# Confirm it has caught up before adding voter 2:
# <!-- TODO PA-XXXX: admin endpoint missing — same gap as above; until
# `/raft/status` and `configd_raft_last_applied_index` ship, fall back
# to the metrics surface or the `X-Config-Version` header. -->
kubectl -n configd exec configd-1 -- \
  curl -sf http://localhost:8080/metrics \
  | grep -E '^configd_raft_last_applied_index' || true

kubectl -n configd scale statefulset/configd --replicas=3
kubectl -n configd wait --for=condition=ready pod/configd-2 --timeout=300s
```

### Step 6. Lift the write freeze

Only after **all** of:
- All voters report `ready`.
- Quorum > 1 (≥ ⌊N/2⌋ + 1 voters reporting healthy from
  `kubectl -n configd get pods -l app=configd` and a leader visible —
  the per-voter check is `curl -sf http://<pod>:8080/health/ready`).
  <!-- TODO PA-XXXX: admin endpoint missing — HttpApiServer does not
  expose `/raft/status` or `/admin/raft/status`. Until it ships,
  quorum has to be inferred from the readiness probes plus the
  `X-Leader-Hint` header returned on a write attempt. -->
- A test write commits successfully.

```sh
# Operator-specific: re-enable writes at the API gateway.
```

## Resolution

The runbook is **complete** when all of the following hold:

- All voters ready and at the same `commit_index`.
- A test write round-trips end-to-end (API gateway → leader → quorum →
  state machine, observable via a follow-up read).
- Edge propagation p99 (`configd_propagation_delay_seconds`) has
  returned to baseline.
  <!-- TODO PA-XXXX: metric configd_edge_staleness_seconds not yet
  emitted by ConfigdMetrics; the propagation-delay histogram is the
  closest registered signal until a dedicated edge-staleness gauge
  ships. -->

- The conformance check below has passed:

  ```sh
  ./ops/scripts/restore-conformance-check.sh \
    --snapshot-path /tmp/restore.snap \
    --target-cluster configd \
    --cluster-endpoint https://configd-0.configd.svc:8080
  ```

  All three flags are required (the script's argument parser rejects the
  legacy `--snapshot=` form and refuses to run without `--target-cluster`;
  see `ops/scripts/restore-conformance-check.sh` `--help`).

  This test reads back the keys present in the snapshot manifest and
  asserts byte-equality. Operator must script this against their
  namespace prefix scheme; see `runbook-conformance-template.md` for
  the contract a restore drill must satisfy.

- The write freeze at the API gateway has been lifted.
- The forensic backup taken in `disaster-recovery.md` step 3 is
  retained — do not delete it until the post-mortem is signed off.

## Rollback

If the restore made the situation worse (e.g. the conformance check
fails, the cluster comes up but a sample of writes does not commit, or
edges report inconsistent values):

- **Stop.** Do NOT delete the forensic backup taken in
  `disaster-recovery.md` step 3.
- Restore the failed voter's `/data` from that backup.
- Pick a different (older) verified snapshot and restart at Step 1.
- If no older snapshot verifies, return to `disaster-recovery.md` —
  the situation is now a "data loss suspected" branch, not a snapshot
  restore one.

If the destructive `kubectl delete pvc` in Step 2 was issued in the
wrong namespace, **there is no rollback** once the storage provider
has reclaimed the volume. This is why incident-commander sign-off is
required and why the `disaster-recovery.md` confirmation guard exists.

## Postmortem

A snapshot restore ALWAYS triggers a post-incident review (it inherits
the disaster-recovery PIR requirement). Required fields:

- Which snapshot was restored from? Date, signing-key fingerprint,
  size, `lastIncludedIndex`.
- Did Step 2 (signature verify) ever fail on a candidate snapshot? If
  yes, root-cause the signing chain.
- How long did each step take? Use this to update the runbook's
  expected timing baseline.
- Did the conformance check pass on first attempt?
- Action items: snapshot retention tuning, restore drill cadence,
  operator-glue script improvements.

## Related

- [disaster-recovery.md](disaster-recovery.md) — escalation parent
- [snapshot-install.md](snapshot-install.md) — peer-symptom runbook
- [runbook-conformance-template.md](runbook-conformance-template.md)
- `spec/SnapshotInstallSpec.tla` — formal model of snapshot install
- `docs/decisions/adr-0028-snapshot-on-disk-format.md` — snapshot
  on-disk / on-wire format (TLV trailer, signing-epoch, envelope
  bounds); `SnapshotConsistency` invariant background
- `docs/decisions/adr-0027-sign-or-fail-close.md` — signing chain that
  Step 2 verification depends on

## Do not

- Do not skip Step 2 (signature verify). A snapshot that doesn't
  verify is suspect and may itself be the disaster.
- Do not bring up multiple voters simultaneously from snapshot. Each
  voter must catch up via InstallSnapshot from the freshly-bootstrapped
  leader; bringing up several at once produces a split-vote storm.
- Do not delete the forensic backup until the incident is closed and
  the post-mortem is signed off — even if recovery succeeds.
- Do not use `kubectl delete --force` on the PVCs in Step 2; that can
  leave volume attachments in inconsistent state on the storage
  provider.

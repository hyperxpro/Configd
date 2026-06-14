# Runbook: Snapshot Install Failure

**Alert:** `ConfigdSnapshotInstallStalled` (warn, `increase(configd_snapshot_install_failed_total[15m]) >= 3`, 5m)
**Severity:** warn (leading indicator of quorum fragility)

A follower tried to catch up via `InstallSnapshot` and it failed ≥ 3 times
in 15 min. A single transient self-clears (the leader retries) — sustained
failure means the follower will eventually fall outside the leader's log
retention window and be excluded from quorum until manual intervention,
leaving the cluster one voter closer to losing quorum.

## Symptom

- `ConfigdSnapshotInstallStalled` warns after ≥ 3 failures in 15 min.
- `Configd Control Plane` dashboard panel **"Snapshot install failures +
  subscribed prefixes"** (`increase(configd_snapshot_install_failed_total[15m])`)
  shows the step up.
- Leader logs contain `InstallSnapshot RPC failed` naming a specific
  follower peer; that follower's commit index stays flat after a leader log
  truncation (log replication can no longer catch it; snapshot is its only
  route).

## Diagnosis

1. **Identify the follower and failure class** from the leader logs around
   the alert window (`InstallSnapshot RPC failed` carries the peer id). The
   classes:
   - **Network** — connection refused / timeout → check follower pod health
     (`kubectl -n configd get pods -l app=configd`).
   - **Integrity** — `SnapshotChecksumMismatch` → the snapshot is corrupt;
     see the formal spec `spec/SnapshotInstallSpec.tla` and the conformance
     test `SnapshotInstallSpecReplayerTest`. Do not bypass the checksum.
   - **Disk full on the follower** —
     ```sh
     kubectl -n configd exec <follower> -- df -h /data
     ```
     → [disk-full-fsync.md](disk-full-fsync.md).
2. **Check leader-side retention.** If the follower's `nextIndex` is older
   than the leader's earliest retained log entry (visible in leader logs),
   only the snapshot path can recover it — raising retention will not help and
   costs steady-state memory.

## Resolution steps

1. **Network or transient disk-pressure failure:** wait one retry window. The
   leader retries `InstallSnapshot` automatically; most transients self-clear.
2. **Disk full on the follower:** free its disk per
   [disk-full-fsync.md](disk-full-fsync.md), then let the retry succeed.
3. **Stuck follower past the leader's retention** (must be re-seeded clean):
   the follower keeps its StatefulSet ordinal (= its node-id), so cluster
   **membership does not change** — there is no add/remove-server RPC and none
   is needed. Wipe ONLY that follower's state and let the StatefulSet respawn
   the SAME ordinal with empty state; it catches up via `InstallSnapshot`:
   ```sh
   # 1. Confirm the failing follower's ordinal from the leader logs first
   #    (wiping the wrong PV converts a warn into a page).
   kubectl -n configd logs statefulset/configd | grep -iE 'install.?snapshot|nextIndex'
   # 2. Delete ONLY that follower's pod AND its PVC. The StatefulSet recreates
   #    the SAME ordinal (same node-id, same membership) with a fresh empty PVC.
   kubectl -n configd delete pod configd-<ordinal>
   kubectl -n configd delete pvc data-configd-<ordinal>
   # 3. Wait for the respawn; the fresh voter catches up from a clean state via
   #    InstallSnapshot. Membership never changed; quorum held throughout.
   kubectl -n configd rollout status statefulset/configd
   ```
   Never let more than one voter be down at once (quorum = floor(N/2)+1). A
   genuine add/remove of a *different* node is NOT an operator path in this
   release (`proposeMembershipChange` exists but has no wired trigger); a
   permanent topology change goes through
   [disaster-recovery.md](disaster-recovery.md).
4. **Do not** lower log retention to "fix" the catch-up window — that trades a
   recoverable problem for an unrecoverable one. **Do not** bypass the
   snapshot checksum — it guarantees the TLA+-proven `SnapshotConsistency`
   invariant.

## Verification

- `configd_snapshot_install_failed_total` stops incrementing for the affected
  follower for at least one full 15-min window; `ConfigdSnapshotInstallStalled`
  clears.
- The follower has caught up: `configd_raft_pending_apply_entries ≈ 0` across
  all pods, and the follower's `/health/ready` returns 200.
- A test write commits and is observable on the previously-stuck follower
  (read it back from that pod).

## Escalation

- Two or more followers hit this in the same window → suspect a leader-side
  snapshot issue (not a follower issue); escalate to the consensus owner and
  attach any `SnapshotChecksumMismatch` / `SnapshotInstallSpec.tla` violation
  trace.
- A freshly re-seeded voter cannot catch up either → this is now a deeper
  cluster-state problem → [disaster-recovery.md](disaster-recovery.md). There
  is no rollback for a wiped PV; the forensic backup from disaster-recovery is
  the only recovery if the wipe was a mistake.

## Validation (fault injection)

`InstallSnapshotTest`
(`configd-consensus-core/src/test/java/io/configd/raft/InstallSnapshotTest.java`)
drives a 3-node cluster through a real snapshot transfer to a lagging
follower. `SnapshotInstallSpecReplayerTest` (same module) property-replays
`SnapshotInstallSpec.tla` (no install beyond committed index, matching, no
commit revert). The failure-counter emission is via
`ServerStateMachineMetrics` (`configd_snapshot_install_failed_total`).
Recovery-verified = the follower's state machine is restored and its
nextIndex/matchIndex advance. A live ≥3-failures-in-15-min drill against a
running cluster is validation-pending (no injector forces repeated
InstallSnapshot RPC failures end-to-end).

## Related

- `spec/SnapshotInstallSpec.tla`, `docs/decisions/adr-0028-snapshot-on-disk-format.md`
- [restore-from-snapshot.md](restore-from-snapshot.md),
  [disaster-recovery.md](disaster-recovery.md), [disk-full-fsync.md](disk-full-fsync.md)
